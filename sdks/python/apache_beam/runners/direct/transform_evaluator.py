#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

"""An evaluator of a specific application of a transform."""

from __future__ import absolute_import

import collections
import random
import time

import apache_beam.io as io
from apache_beam import coders
from apache_beam import pvalue
from apache_beam.internal import pickler
from apache_beam.options.pipeline_options import TypeOptions
from apache_beam.runners.common import DoFnRunner
from apache_beam.runners.common import DoFnState
from apache_beam.runners.dataflow.native_io.iobase import _NativeWrite  # pylint: disable=protected-access
from apache_beam.runners.direct.direct_runner import _StreamingGroupAlsoByWindow
from apache_beam.runners.direct.direct_runner import _StreamingGroupByKeyOnly
from apache_beam.runners.direct.util import KeyedWorkItem
from apache_beam.runners.direct.util import TransformResult
from apache_beam.runners.direct.watermark_manager import WatermarkManager
from apache_beam.testing.test_stream import ElementEvent
from apache_beam.testing.test_stream import ProcessingTimeEvent
from apache_beam.testing.test_stream import TestStream
from apache_beam.testing.test_stream import WatermarkEvent
from apache_beam.transforms import core
from apache_beam.transforms.trigger import TimeDomain
from apache_beam.transforms.trigger import _CombiningValueStateTag
from apache_beam.transforms.trigger import _ListStateTag
from apache_beam.transforms.trigger import create_trigger_driver
from apache_beam.transforms.window import GlobalWindows
from apache_beam.transforms.window import WindowedValue
from apache_beam.typehints.typecheck import OutputCheckWrapperDoFn
from apache_beam.typehints.typecheck import TypeCheckError
from apache_beam.typehints.typecheck import TypeCheckWrapperDoFn
from apache_beam.utils import counters
from apache_beam.utils.timestamp import MIN_TIMESTAMP
from apache_beam.utils.timestamp import Timestamp


class TransformEvaluatorRegistry(object):
  """For internal use only; no backwards-compatibility guarantees.

  Creates instances of TransformEvaluator for the application of a transform.
  """

  def __init__(self, evaluation_context):
    assert evaluation_context
    self._evaluation_context = evaluation_context
    self._evaluators = {
        io.Read: _BoundedReadEvaluator,
        io.ReadStringsFromPubSub: _PubSubReadEvaluator,
        core.Flatten: _FlattenEvaluator,
        core.ParDo: _ParDoEvaluator,
        core._GroupByKeyOnly: _GroupByKeyOnlyEvaluator,
        _StreamingGroupByKeyOnly: _StreamingGroupByKeyOnlyEvaluator,
        _StreamingGroupAlsoByWindow: _StreamingGroupAlsoByWindowEvaluator,
        _NativeWrite: _NativeWriteEvaluator,
        TestStream: _TestStreamEvaluator,
    }
    self._root_bundle_providers = {
        core.PTransform: DefaultRootBundleProvider,
        TestStream: _TestStreamRootBundleProvider,
    }

  def get_evaluator(
      self, applied_ptransform, input_committed_bundle,
      side_inputs, scoped_metrics_container):
    """Returns a TransformEvaluator suitable for processing given inputs."""
    assert applied_ptransform
    assert bool(applied_ptransform.side_inputs) == bool(side_inputs)

    # Walk up the class hierarchy to find an evaluable type. This is necessary
    # for supporting sub-classes of core transforms.
    for cls in applied_ptransform.transform.__class__.mro():
      evaluator = self._evaluators.get(cls)
      if evaluator:
        break

    if not evaluator:
      raise NotImplementedError(
          'Execution of [%s] not implemented in runner %s.' % (
              type(applied_ptransform.transform), self))
    return evaluator(self._evaluation_context, applied_ptransform,
                     input_committed_bundle, side_inputs,
                     scoped_metrics_container)

  def get_root_bundle_provider(self, applied_ptransform):
    provider_cls = None
    for cls in applied_ptransform.transform.__class__.mro():
      provider_cls = self._root_bundle_providers.get(cls)
      if provider_cls:
        break
    if not provider_cls:
      raise NotImplementedError(
          'Root provider for [%s] not implemented in runner %s' % (
              type(applied_ptransform.transform), self))
    return provider_cls(self._evaluation_context, applied_ptransform)

  def should_execute_serially(self, applied_ptransform):
    """Returns True if this applied_ptransform should run one bundle at a time.

    Some TransformEvaluators use a global state object to keep track of their
    global execution state. For example evaluator for _GroupByKeyOnly uses this
    state as an in memory dictionary to buffer keys.

    Serially executed evaluators will act as syncing point in the graph and
    execution will not move forward until they receive all of their inputs. Once
    they receive all of their input, they will release the combined output.
    Their output may consist of multiple bundles as they may divide their output
    into pieces before releasing.

    Args:
      applied_ptransform: Transform to be used for execution.

    Returns:
      True if executor should execute applied_ptransform serially.
    """
    return isinstance(applied_ptransform.transform,
                      (core._GroupByKeyOnly,
                       _StreamingGroupByKeyOnly,
                       _StreamingGroupAlsoByWindow,
                       _NativeWrite,))


class RootBundleProvider(object):
  """Provides bundles for the initial execution of a root transform."""

  def __init__(self, evaluation_context, applied_ptransform):
    self._evaluation_context = evaluation_context
    self._applied_ptransform = applied_ptransform

  def get_root_bundles(self):
    raise NotImplementedError


class DefaultRootBundleProvider(RootBundleProvider):
  """Provides an empty bundle by default for root transforms."""

  def get_root_bundles(self):
    input_node = pvalue.PBegin(self._applied_ptransform.transform.pipeline)
    empty_bundle = (
        self._evaluation_context.create_empty_committed_bundle(input_node))
    return [empty_bundle]


class _TestStreamRootBundleProvider(RootBundleProvider):
  """Provides an initial bundle for the TestStream evaluator."""

  def get_root_bundles(self):
    test_stream = self._applied_ptransform.transform
    bundles = []
    if len(test_stream.events) > 0:
      bundle = self._evaluation_context.create_bundle(
          pvalue.PBegin(self._applied_ptransform.transform.pipeline))
      # Explicitly set timestamp to MIN_TIMESTAMP to ensure that we hold the
      # watermark.
      bundle.add(GlobalWindows.windowed_value(0, timestamp=MIN_TIMESTAMP))
      bundle.commit(None)
      bundles.append(bundle)
    return bundles


class _TransformEvaluator(object):
  """An evaluator of a specific application of a transform."""

  def __init__(self, evaluation_context, applied_ptransform,
               input_committed_bundle, side_inputs, scoped_metrics_container):
    self._evaluation_context = evaluation_context
    self._applied_ptransform = applied_ptransform
    self._input_committed_bundle = input_committed_bundle
    self._side_inputs = side_inputs
    self._expand_outputs()
    self._execution_context = evaluation_context.get_execution_context(
        applied_ptransform)
    self.scoped_metrics_container = scoped_metrics_container
    with scoped_metrics_container:
      self.start_bundle()

  def _expand_outputs(self):
    outputs = set()
    for pval in self._applied_ptransform.outputs.values():
      if isinstance(pval, pvalue.DoOutputsTuple):
        pvals = (v for v in pval)
      else:
        pvals = (pval,)
      for v in pvals:
        outputs.add(v)
    self._outputs = frozenset(outputs)

  def _split_list_into_bundles(
      self, output_pcollection, elements, max_element_per_bundle,
      element_size_fn):
    """Splits elements, an iterable, into multiple output bundles.

    Args:
      output_pcollection: PCollection that the elements belong to.
      elements: elements to be chunked into bundles.
      max_element_per_bundle: (approximately) the maximum element per bundle.
        If it is None, only a single bundle will be produced.
      element_size_fn: Function to return the size of a given element.

    Returns:
      List of output uncommitted bundles with at least one bundle.
    """
    bundle = self._evaluation_context.create_bundle(output_pcollection)
    bundle_size = 0
    bundles = [bundle]
    for element in elements:
      if max_element_per_bundle and bundle_size >= max_element_per_bundle:
        bundle = self._evaluation_context.create_bundle(output_pcollection)
        bundle_size = 0
        bundles.append(bundle)

      bundle.output(element)
      bundle_size += element_size_fn(element)
    return bundles

  def start_bundle(self):
    """Starts a new bundle."""
    pass

  def process_timer_wrapper(self, timer_firing):
    """Process timer by clearing and then calling process_timer().

    This method is called with any timer firing and clears the delivered
    timer from the keyed state and then calls process_timer().  The default
    process_timer() implementation emits a KeyedWorkItem for the particular
    timer and passes it to process_element().  Evaluator subclasses which
    desire different timer delivery semantics can override process_timer().
    """
    state = self.step_context.get_keyed_state(timer_firing.encoded_key)
    state.clear_timer(
        timer_firing.window, timer_firing.name, timer_firing.time_domain)
    self.process_timer(timer_firing)

  def process_timer(self, timer_firing):
    """Default process_timer() impl. generating KeyedWorkItem element."""
    self.process_element(
        GlobalWindows.windowed_value(
            KeyedWorkItem(timer_firing.encoded_key,
                          timer_firings=[timer_firing])))

  def process_element(self, element):
    """Processes a new element as part of the current bundle."""
    raise NotImplementedError('%s do not process elements.', type(self))

  def finish_bundle(self):
    """Finishes the bundle and produces output."""
    pass


class _BoundedReadEvaluator(_TransformEvaluator):
  """TransformEvaluator for bounded Read transform."""

  # After some benchmarks, 1000 was optimal among {100,1000,10000}
  MAX_ELEMENT_PER_BUNDLE = 1000

  def __init__(self, evaluation_context, applied_ptransform,
               input_committed_bundle, side_inputs, scoped_metrics_container):
    assert not side_inputs
    self._source = applied_ptransform.transform.source
    self._source.pipeline_options = evaluation_context.pipeline_options
    super(_BoundedReadEvaluator, self).__init__(
        evaluation_context, applied_ptransform, input_committed_bundle,
        side_inputs, scoped_metrics_container)

  def finish_bundle(self):
    assert len(self._outputs) == 1
    output_pcollection = list(self._outputs)[0]

    def _read_values_to_bundles(reader):
      read_result = [GlobalWindows.windowed_value(e) for e in reader]
      return self._split_list_into_bundles(
          output_pcollection, read_result,
          _BoundedReadEvaluator.MAX_ELEMENT_PER_BUNDLE, lambda _: 1)

    if isinstance(self._source, io.iobase.BoundedSource):
      # Getting a RangeTracker for the default range of the source and reading
      # the full source using that.
      range_tracker = self._source.get_range_tracker(None, None)
      reader = self._source.read(range_tracker)
      bundles = _read_values_to_bundles(reader)
    else:
      with self._source.reader() as reader:
        bundles = _read_values_to_bundles(reader)

    return TransformResult(self, bundles, [], None, None)


class _TestStreamEvaluator(_TransformEvaluator):
  """TransformEvaluator for the TestStream transform."""

  def __init__(self, evaluation_context, applied_ptransform,
               input_committed_bundle, side_inputs, scoped_metrics_container):
    assert not side_inputs
    self.test_stream = applied_ptransform.transform
    super(_TestStreamEvaluator, self).__init__(
        evaluation_context, applied_ptransform, input_committed_bundle,
        side_inputs, scoped_metrics_container)

  def start_bundle(self):
    self.current_index = -1
    self.watermark = MIN_TIMESTAMP
    self.bundles = []

  def process_element(self, element):
    index = element.value
    self.watermark = element.timestamp
    assert isinstance(index, int)
    assert 0 <= index <= len(self.test_stream.events)
    self.current_index = index
    event = self.test_stream.events[self.current_index]
    if isinstance(event, ElementEvent):
      assert len(self._outputs) == 1
      output_pcollection = list(self._outputs)[0]
      bundle = self._evaluation_context.create_bundle(output_pcollection)
      for tv in event.timestamped_values:
        bundle.output(
            GlobalWindows.windowed_value(tv.value, timestamp=tv.timestamp))
      self.bundles.append(bundle)
    elif isinstance(event, WatermarkEvent):
      assert event.new_watermark >= self.watermark
      self.watermark = event.new_watermark
    elif isinstance(event, ProcessingTimeEvent):
      # TODO(ccy): advance processing time in the context's mock clock.
      pass
    else:
      raise ValueError('Invalid TestStream event: %s.' % event)

  def finish_bundle(self):
    unprocessed_bundles = []
    hold = None
    if self.current_index < len(self.test_stream.events) - 1:
      unprocessed_bundle = self._evaluation_context.create_bundle(
          pvalue.PBegin(self._applied_ptransform.transform.pipeline))
      unprocessed_bundle.add(GlobalWindows.windowed_value(
          self.current_index + 1, timestamp=self.watermark))
      unprocessed_bundles.append(unprocessed_bundle)
      hold = self.watermark

    return TransformResult(
        self, self.bundles, unprocessed_bundles, None, {None: hold})


class _PubSubSubscriptionWrapper(object):
  """Wrapper for garbage-collecting temporary PubSub subscriptions."""

  def __init__(self, subscription, should_cleanup):
    self.subscription = subscription
    self.should_cleanup = should_cleanup

  def __del__(self):
    if self.should_cleanup:
      self.subscription.delete()


class _PubSubReadEvaluator(_TransformEvaluator):
  """TransformEvaluator for PubSub read."""

  _subscription_cache = {}

  def __init__(self, evaluation_context, applied_ptransform,
               input_committed_bundle, side_inputs, scoped_metrics_container):
    assert not side_inputs
    super(_PubSubReadEvaluator, self).__init__(
        evaluation_context, applied_ptransform, input_committed_bundle,
        side_inputs, scoped_metrics_container)

    source = self._applied_ptransform.transform._source
    self._subscription = _PubSubReadEvaluator.get_subscription(
        self._applied_ptransform, source.project, source.topic_name,
        source.subscription_name)

  @classmethod
  def get_subscription(cls, transform, project, topic, subscription_name):
    if transform not in cls._subscription_cache:
      from google.cloud import pubsub
      should_create = not subscription_name
      if should_create:
        subscription_name = 'beam_%d_%x' % (
            int(time.time()), random.randrange(1 << 32))
      cls._subscription_cache[transform] = _PubSubSubscriptionWrapper(
          pubsub.Client(project=project).topic(topic).subscription(
              subscription_name),
          should_create)
      if should_create:
        cls._subscription_cache[transform].subscription.create()
    return cls._subscription_cache[transform].subscription

  def start_bundle(self):
    pass

  def process_element(self, element):
    pass

  def _read_from_pubsub(self):
    from google.cloud import pubsub
    # Because of the AutoAck, we are not able to reread messages if this
    # evaluator fails with an exception before emitting a bundle. However,
    # the DirectRunner currently doesn't retry work items anyway, so the
    # pipeline would enter an inconsistent state on any error.
    with pubsub.subscription.AutoAck(
        self._subscription, return_immediately=True,
        max_messages=10) as results:
      return [message.data for unused_ack_id, message in results.items()]

  def finish_bundle(self):
    data = self._read_from_pubsub()
    if data:
      output_pcollection = list(self._outputs)[0]
      bundle = self._evaluation_context.create_bundle(output_pcollection)
      # TODO(ccy): we currently do not use the PubSub message timestamp or
      # respect the PubSub source's id_label field.
      now = Timestamp.of(time.time())
      for message_data in data:
        bundle.output(GlobalWindows.windowed_value(message_data, timestamp=now))
      bundles = [bundle]
    else:
      bundles = []
    if self._applied_ptransform.inputs:
      input_pvalue = self._applied_ptransform.inputs[0]
    else:
      input_pvalue = pvalue.PBegin(self._applied_ptransform.transform.pipeline)
    unprocessed_bundle = self._evaluation_context.create_bundle(
        input_pvalue)

    return TransformResult(self, bundles, [unprocessed_bundle], None,
                           {None: Timestamp.of(time.time())})


class _FlattenEvaluator(_TransformEvaluator):
  """TransformEvaluator for Flatten transform."""

  def __init__(self, evaluation_context, applied_ptransform,
               input_committed_bundle, side_inputs, scoped_metrics_container):
    assert not side_inputs
    super(_FlattenEvaluator, self).__init__(
        evaluation_context, applied_ptransform, input_committed_bundle,
        side_inputs, scoped_metrics_container)

  def start_bundle(self):
    assert len(self._outputs) == 1
    output_pcollection = list(self._outputs)[0]
    self.bundle = self._evaluation_context.create_bundle(output_pcollection)

  def process_element(self, element):
    self.bundle.output(element)

  def finish_bundle(self):
    bundles = [self.bundle]
    return TransformResult(self, bundles, [], None, None)


class _TaggedReceivers(dict):
  """Received ParDo output and redirect to the associated output bundle."""

  def __init__(self, evaluation_context):
    self._evaluation_context = evaluation_context
    self._null_receiver = None
    self._undeclared_in_memory_tag_values = None
    super(_TaggedReceivers, self).__init__()

  @property
  def undeclared_in_memory_tag_values(self):
    assert (not self._undeclared_in_memory_tag_values
            or self._evaluation_context.has_cache)
    return self._undeclared_in_memory_tag_values

  class NullReceiver(object):
    """Ignores undeclared outputs, default execution mode."""

    def output(self, element):
      pass

  class _InMemoryReceiver(object):
    """Buffers undeclared outputs to the given dictionary."""

    def __init__(self, target, tag):
      self._target = target
      self._tag = tag

    def output(self, element):
      self._target[self._tag].append(element)

  def __missing__(self, key):
    if self._evaluation_context.has_cache:
      if not self._undeclared_in_memory_tag_values:
        self._undeclared_in_memory_tag_values = collections.defaultdict(list)
      receiver = _TaggedReceivers._InMemoryReceiver(
          self._undeclared_in_memory_tag_values, key)
    else:
      if not self._null_receiver:
        self._null_receiver = _TaggedReceivers.NullReceiver()
      receiver = self._null_receiver
    return receiver


class _ParDoEvaluator(_TransformEvaluator):
  """TransformEvaluator for ParDo transform."""
  def start_bundle(self):
    transform = self._applied_ptransform.transform

    self._tagged_receivers = _TaggedReceivers(self._evaluation_context)
    for output_tag in self._applied_ptransform.outputs:
      output_pcollection = pvalue.PCollection(None, tag=output_tag)
      output_pcollection.producer = self._applied_ptransform
      self._tagged_receivers[output_tag] = (
          self._evaluation_context.create_bundle(output_pcollection))
      self._tagged_receivers[output_tag].tag = output_tag

    self._counter_factory = counters.CounterFactory()

    # TODO(aaltay): Consider storing the serialized form as an optimization.
    dofn = pickler.loads(pickler.dumps(transform.dofn))

    pipeline_options = self._evaluation_context.pipeline_options
    if (pipeline_options is not None
        and pipeline_options.view_as(TypeOptions).runtime_type_check):
      dofn = TypeCheckWrapperDoFn(dofn, transform.get_type_hints())

    dofn = OutputCheckWrapperDoFn(dofn, self._applied_ptransform.full_label)
    self.runner = DoFnRunner(
        dofn, transform.args, transform.kwargs,
        self._side_inputs,
        self._applied_ptransform.inputs[0].windowing,
        tagged_receivers=self._tagged_receivers,
        step_name=self._applied_ptransform.full_label,
        state=DoFnState(self._counter_factory),
        scoped_metrics_container=self.scoped_metrics_container)
    self.runner.start()

  def process_element(self, element):
    self.runner.process(element)

  def finish_bundle(self):
    self.runner.finish()
    bundles = self._tagged_receivers.values()
    result_counters = self._counter_factory.get_counters()
    return TransformResult(
        self, bundles, [], result_counters, None,
        self._tagged_receivers.undeclared_in_memory_tag_values)


class _GroupByKeyOnlyEvaluator(_TransformEvaluator):
  """TransformEvaluator for _GroupByKeyOnly transform."""

  MAX_ELEMENT_PER_BUNDLE = None
  ELEMENTS_TAG = _ListStateTag('elements')
  COMPLETION_TAG = _CombiningValueStateTag('completed', any)

  def __init__(self, evaluation_context, applied_ptransform,
               input_committed_bundle, side_inputs, scoped_metrics_container):
    assert not side_inputs
    super(_GroupByKeyOnlyEvaluator, self).__init__(
        evaluation_context, applied_ptransform, input_committed_bundle,
        side_inputs, scoped_metrics_container)

  def _is_final_bundle(self):
    return (self._execution_context.watermarks.input_watermark
            == WatermarkManager.WATERMARK_POS_INF)

  def start_bundle(self):
    self.step_context = self._execution_context.get_step_context()
    self.step_context.clear_partial_states()
    self.global_state = self.step_context.get_keyed_state(None)

    assert len(self._outputs) == 1
    self.output_pcollection = list(self._outputs)[0]

    # The input type of a GroupByKey will be KV[Any, Any] or more specific.
    kv_type_hint = (
        self._applied_ptransform.transform.get_type_hints().input_types[0])
    self.key_coder = coders.registry.get_coder(kv_type_hint[0].tuple_types[0])

  def process_timer(self, timer_firing):
    # We do not need to emit a KeyedWorkItem to process_element().
    pass

  def process_element(self, element):
    assert not self.global_state.get_state(
        None, _GroupByKeyOnlyEvaluator.COMPLETION_TAG)
    if (isinstance(element, WindowedValue)
        and isinstance(element.value, collections.Iterable)
        and len(element.value) == 2):
      k, v = element.value
      encoded_k = self.key_coder.encode(k)
      state = self.step_context.get_keyed_state(encoded_k)
      state.add_state(None, _GroupByKeyOnlyEvaluator.ELEMENTS_TAG, v)
    else:
      raise TypeCheckError('Input to _GroupByKeyOnly must be a PCollection of '
                           'windowed key-value pairs. Instead received: %r.'
                           % element)

  def finish_bundle(self):
    if self._is_final_bundle():
      if self.global_state.get_state(
          None, _GroupByKeyOnlyEvaluator.COMPLETION_TAG):
        # Ignore empty bundles after emitting output. (This may happen because
        # empty bundles do not affect input watermarks.)
        bundles = []
      else:
        gbk_result = []
        # TODO(ccy): perhaps we can clean this up to not use this
        # internal attribute of the DirectStepContext.
        for encoded_k in self.step_context.existing_keyed_state:
          # Ignore global state.
          if encoded_k is None:
            continue
          k = self.key_coder.decode(encoded_k)
          state = self.step_context.get_keyed_state(encoded_k)
          vs = state.get_state(None, _GroupByKeyOnlyEvaluator.ELEMENTS_TAG)
          gbk_result.append(GlobalWindows.windowed_value((k, vs)))

        def len_element_fn(element):
          _, v = element.value
          return len(v)

        bundles = self._split_list_into_bundles(
            self.output_pcollection, gbk_result,
            _GroupByKeyOnlyEvaluator.MAX_ELEMENT_PER_BUNDLE, len_element_fn)

      self.global_state.add_state(
          None, _GroupByKeyOnlyEvaluator.COMPLETION_TAG, True)
      hold = WatermarkManager.WATERMARK_POS_INF
    else:
      bundles = []
      hold = WatermarkManager.WATERMARK_NEG_INF
      self.global_state.set_timer(
          None, '', TimeDomain.WATERMARK, WatermarkManager.WATERMARK_POS_INF)

    return TransformResult(self, bundles, [], None, {None: hold})


class _StreamingGroupByKeyOnlyEvaluator(_TransformEvaluator):
  """TransformEvaluator for _StreamingGroupByKeyOnly transform.

  The _GroupByKeyOnlyEvaluator buffers elements until its input watermark goes
  to infinity, which is suitable for batch mode execution. During streaming
  mode execution, we emit each bundle as it comes to the next transform.
  """

  MAX_ELEMENT_PER_BUNDLE = None

  def __init__(self, evaluation_context, applied_ptransform,
               input_committed_bundle, side_inputs, scoped_metrics_container):
    assert not side_inputs
    super(_StreamingGroupByKeyOnlyEvaluator, self).__init__(
        evaluation_context, applied_ptransform, input_committed_bundle,
        side_inputs, scoped_metrics_container)

  def start_bundle(self):
    self.gbk_items = collections.defaultdict(list)

    assert len(self._outputs) == 1
    self.output_pcollection = list(self._outputs)[0]

    # The input type of a GroupByKey will be KV[Any, Any] or more specific.
    kv_type_hint = (
        self._applied_ptransform.transform.get_type_hints().input_types[0])
    self.key_coder = coders.registry.get_coder(kv_type_hint[0].tuple_types[0])

  def process_element(self, element):
    if (isinstance(element, WindowedValue)
        and isinstance(element.value, collections.Iterable)
        and len(element.value) == 2):
      k, v = element.value
      self.gbk_items[self.key_coder.encode(k)].append(v)
    else:
      raise TypeCheckError('Input to _GroupByKeyOnly must be a PCollection of '
                           'windowed key-value pairs. Instead received: %r.'
                           % element)

  def finish_bundle(self):
    bundles = []
    bundle = None
    for encoded_k, vs in self.gbk_items.iteritems():
      if not bundle:
        bundle = self._evaluation_context.create_bundle(
            self.output_pcollection)
        bundles.append(bundle)
      kwi = KeyedWorkItem(encoded_k, elements=vs)
      bundle.add(GlobalWindows.windowed_value(kwi))

    return TransformResult(self, bundles, [], None, None)


class _StreamingGroupAlsoByWindowEvaluator(_TransformEvaluator):
  """TransformEvaluator for the _StreamingGroupAlsoByWindow transform.

  This evaluator is only used in streaming mode.  In batch mode, the
  GroupAlsoByWindow operation is evaluated as a normal DoFn, as defined
  in transforms/core.py.
  """

  def __init__(self, evaluation_context, applied_ptransform,
               input_committed_bundle, side_inputs, scoped_metrics_container):
    assert not side_inputs
    super(_StreamingGroupAlsoByWindowEvaluator, self).__init__(
        evaluation_context, applied_ptransform, input_committed_bundle,
        side_inputs, scoped_metrics_container)

  def start_bundle(self):
    assert len(self._outputs) == 1
    self.output_pcollection = list(self._outputs)[0]
    self.step_context = self._execution_context.get_step_context()
    self.step_context.clear_partial_states()
    self.driver = create_trigger_driver(
        self._applied_ptransform.transform.windowing)
    self.gabw_items = []
    self.keyed_holds = {}

    # The input type of a GroupAlsoByWindow will be KV[Any, Iter[Any]] or more
    # specific.
    kv_type_hint = (
        self._applied_ptransform.transform.get_type_hints().input_types[0])
    self.key_coder = coders.registry.get_coder(kv_type_hint[0].tuple_types[0])

  def process_element(self, element):
    kwi = element.value
    assert isinstance(kwi, KeyedWorkItem), kwi
    encoded_k, timer_firings, vs = (
        kwi.encoded_key, kwi.timer_firings, kwi.elements)
    k = self.key_coder.decode(encoded_k)
    state = self.step_context.get_keyed_state(encoded_k)

    for timer_firing in timer_firings:
      for wvalue in self.driver.process_timer(
          timer_firing.window, timer_firing.name, timer_firing.time_domain,
          timer_firing.timestamp, state):
        self.gabw_items.append(wvalue.with_value((k, wvalue.value)))
    if vs:
      for wvalue in self.driver.process_elements(state, vs, MIN_TIMESTAMP):
        self.gabw_items.append(wvalue.with_value((k, wvalue.value)))

    self.keyed_holds[encoded_k] = state.get_earliest_hold()

  def finish_bundle(self):
    bundles = []
    if self.gabw_items:
      bundle = self._evaluation_context.create_bundle(self.output_pcollection)
      for item in self.gabw_items:
        bundle.add(item)
      bundles.append(bundle)

    return TransformResult(self, bundles, [], None, self.keyed_holds)


class _NativeWriteEvaluator(_TransformEvaluator):
  """TransformEvaluator for _NativeWrite transform."""

  ELEMENTS_TAG = _ListStateTag('elements')

  def __init__(self, evaluation_context, applied_ptransform,
               input_committed_bundle, side_inputs, scoped_metrics_container):
    assert not side_inputs
    super(_NativeWriteEvaluator, self).__init__(
        evaluation_context, applied_ptransform, input_committed_bundle,
        side_inputs, scoped_metrics_container)

    assert applied_ptransform.transform.sink
    self._sink = applied_ptransform.transform.sink

  @property
  def _is_final_bundle(self):
    return (self._execution_context.watermarks.input_watermark
            == WatermarkManager.WATERMARK_POS_INF)

  @property
  def _has_already_produced_output(self):
    return (self._execution_context.watermarks.output_watermark
            == WatermarkManager.WATERMARK_POS_INF)

  def start_bundle(self):
    self.step_context = self._execution_context.get_step_context()
    self.step_context.clear_partial_states()
    self.global_state = self.step_context.get_keyed_state(None)

  def process_timer(self, timer_firing):
    # We do not need to emit a KeyedWorkItem to process_element().
    pass

  def process_element(self, element):
    self.global_state.add_state(
        None, _NativeWriteEvaluator.ELEMENTS_TAG, element)

  def finish_bundle(self):
    # finish_bundle will append incoming bundles in memory until all the bundles
    # carrying data is processed. This is done to produce only a single output
    # shard (some tests depends on this behavior). It is possible to have
    # incoming empty bundles after the output is produced, these bundles will be
    # ignored and would not generate additional output files.
    # TODO(altay): Do not wait until the last bundle to write in a single shard.
    if self._is_final_bundle:
      elements = self.global_state.get_state(
          None, _NativeWriteEvaluator.ELEMENTS_TAG)
      if self._has_already_produced_output:
        # Ignore empty bundles that arrive after the output is produced.
        assert elements == []
      else:
        self._sink.pipeline_options = self._evaluation_context.pipeline_options
        with self._sink.writer() as writer:
          for v in elements:
            writer.Write(v.value)
      hold = WatermarkManager.WATERMARK_POS_INF
    else:
      hold = WatermarkManager.WATERMARK_NEG_INF
      self.global_state.set_timer(
          None, '', TimeDomain.WATERMARK, WatermarkManager.WATERMARK_POS_INF)

    return TransformResult(self, [], [], None, {None: hold})
