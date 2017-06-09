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
import functools
import random
import time

from apache_beam import coders
from apache_beam import pvalue
from apache_beam.internal import pickler
import apache_beam.io as io
from apache_beam.options.pipeline_options import TypeOptions
from apache_beam.runners.common import DoFnRunner
from apache_beam.runners.common import DoFnState
from apache_beam.runners.direct.watermark_manager import WatermarkManager
from apache_beam.runners.direct.transform_result import TransformResult
from apache_beam.runners.dataflow.native_io.iobase import _NativeWrite  # pylint: disable=protected-access
from apache_beam.testing.test_stream import TestStream
from apache_beam.transforms import core
from apache_beam.utils.timestamp import MAX_TIMESTAMP
from apache_beam.utils.timestamp import MIN_TIMESTAMP
from apache_beam.transforms.trigger import create_trigger_driver
from apache_beam.transforms.window import GlobalWindows
from apache_beam.transforms.window import Timestamp
from apache_beam.transforms.window import WindowedValue
from apache_beam.typehints.typecheck import OutputCheckWrapperDoFn
from apache_beam.typehints.typecheck import TypeCheckError
from apache_beam.typehints.typecheck import TypeCheckWrapperDoFn
from apache_beam.utils import counters
from apache_beam.testing.test_stream import ElementEvent
from apache_beam.testing.test_stream import WatermarkEvent
from apache_beam.testing.test_stream import ProcessingTimeEvent

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
    print 'WTF', self._applied_ptransform, self._applied_ptransform.inputs
    # TODO: these are root bundles, they don't really need a producer... but apparently some inputs are not defined.
    empty_bundle = (
        self._evaluation_context.create_empty_committed_bundle(
            self._applied_ptransform.inputs[0] if self._applied_ptransform.inputs else pvalue.PBegin(self._applied_ptransform.transform.pipeline)))
    return [empty_bundle]

class _TestStreamRootBundleProvider(RootBundleProvider):

  def get_root_bundles(self):
    test_stream = self._applied_ptransform.transform
    bundles = []
    if len(test_stream.events) > 0:
      bundle = self._evaluation_context.create_bundle(
          self._applied_ptransform.inputs[0])
      # Explicitly set timestamp to MIN_TIMESTAMP to ensure that we hold the watermark.
      bundle.add(GlobalWindows.windowed_value(0, timestamp=MIN_TIMESTAMP))
      bundle.commit(None)
      bundles.append(bundle)
    return bundles


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
        core._GroupAlsoByWindow: _GroupAlsoByWindowEvaluator,
        _NativeWrite: _NativeWriteEvaluator,
        TestStream: _TestStreamEvaluator,
    }
    self._root_bundle_providers = {
        core.PTransform: DefaultRootBundleProvider,
        TestStream: _TestStreamRootBundleProvider,
    }

  def for_application(
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
    return False
    return isinstance(applied_ptransform.transform,
                      (core._GroupByKeyOnly, _NativeWrite))


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
        applied_ptransform, input_committed_bundle.key)
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
    # assert not input_committed_bundle, input_committed_bundle
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

    return TransformResult(
        self._applied_ptransform, bundles, [], None, None, None, None, None)


class _TestStreamEvaluator(_TransformEvaluator):
  """TransformEvaluator for the TestStream transform."""

  def __init__(self, evaluation_context, applied_ptransform,
               input_committed_bundle, side_inputs, scoped_metrics_container):
    # TODO: figure out why timers fire
    # assert not input_committed_bundle, input_committed_bundle
    assert not side_inputs
    self.test_stream = applied_ptransform.transform
    super(_TestStreamEvaluator, self).__init__(
        evaluation_context, applied_ptransform, input_committed_bundle,
        side_inputs, scoped_metrics_container)

  def start_bundle(self):
    # TODO: currently, for the first pass through the TestStream, no elements
    # are passed through--we can therefore assume that the current index is 0
    # in this case; otherwise, the index will be stored on the process_element
    # call.
    self.current_index = -1
    self.watermark = MIN_TIMESTAMP

  def process_element(self, element):
    index = element.value
    self.watermark = element.timestamp
    assert isinstance(index, int)
    assert 0 <= index <= len(self.test_stream.events)
    self.current_index = index

  def finish_bundle(self):
    assert len(self._outputs) == 1
    output_pcollection = list(self._outputs)[0]

    # TODO: move to process_element once we provide a root bundle.
    bundles = []
    event = self.test_stream.events[self.current_index]
    watermark = self.watermark
    if isinstance(event, ElementEvent):
      bundle = self._evaluation_context.create_bundle(output_pcollection)
      for tv in event.timestamped_values:
        bundle.output(
            GlobalWindows.windowed_value(tv.value, timestamp=tv.timestamp))
      bundles.append(bundle)
    elif isinstance(event, WatermarkEvent):
      assert event.new_watermark >= self.watermark
      watermark = event.new_watermark
    elif isinstance(event, ProcessingTimeEvent):
      # TODO: advance processing time in the context's mock clock.
      pass
    else:
      raise ValueError('Invalid TestStream event: %s.' % event)

    unprocessed_bundle = None
    hold = MAX_TIMESTAMP
    if self.current_index < len(self.test_stream.events) - 1:
      unprocessed_bundle = self._evaluation_context.create_bundle(
          self._applied_ptransform.inputs[0])
      unprocessed_bundle.add(GlobalWindows.windowed_value(self.current_index + 1, timestamp=watermark))
      hold = watermark



    print '[!] TestStream finish_bundle', self.current_index, event, unprocessed_bundle

    return TransformResult(
        self._applied_ptransform, bundles, unprocessed_bundle, None, None, None,
            None, hold)


class _PubSubReadEvaluator(_TransformEvaluator):
  """TransformEvaluator for PubSub read."""

  def __init__(self, *args, **kwargs):
    super(_PubSubReadEvaluator, self).__init__(*args, **kwargs)
    source = self._applied_ptransform.transform._source
    subscription_name = (
        source.subscription or 'dataflow_%x' % random.randrange(1 << 32))
    self._subscription = self._execution_context.legacy_existing_state
    if not self._subscription:
      self._subscription = self.create_subscription(
          source.topic, subscription_name)
      if not source.subscription:
        self._subscription.create()

  _subscription_cache = {}
  @classmethod
  def create_subscription(cls, topic, subscription_name):
    key = topic, subscription_name
    if key not in cls._subscription_cache:
      from google.cloud import pubsub
      cls._subscription_cache[key] = (
          pubsub.Client().topic(topic).subscription(subscription_name, ack_deadline=30))
    return cls._subscription_cache[key]

  # TODO(ccy): this should be run when we terminate execution, but not when
  # this specific evaluator instance is deleted.
  # def __del__(self):
  #   if not self._applied_ptransform.transform._source.subscription:
  #     self._subscription.delete()

  def start_bundle(self):
    pass

  def process_element(self, element):
    pass

  def finish_bundle(self):
    data = self._read_from_pubsub()
    if data:
      output_pcollection = list(self._outputs)[0]
      bundle = self._evaluation_context.create_bundle(output_pcollection)
      now = Timestamp.of(time.time())
      for message_data in data:
        bundle.output(GlobalWindows.windowed_value(message_data, timestamp=now))
      bundles = [bundle]
    else:
      bundles = []

    unprocessed_bundle = self._evaluation_context.create_bundle(
        self._applied_ptransform.inputs[0])

    return TransformResult(
        self._applied_ptransform, bundles,
        unprocessed_bundle, None, self._subscription, None, None,
        Timestamp.of(time.time()))

  def _read_from_pubsub(self):
    from google.cloud import pubsub
    print 'READ FROM PUBSUB'
    with pubsub.subscription.AutoAck(self._subscription, return_immediately=True, max_messages=10) as results:
      print 'DONE READ FROM PUBSUB'
      return [message.data for ack_id, message in results.items()]


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
    return TransformResult(
        self._applied_ptransform, bundles, [], None, None, None, None, None)


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
    print 'TRANSFORMSI', transform, 'SIDE INPUTS', self._side_inputs

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
        self._applied_ptransform, bundles, [], None, None, None, result_counters,
        None, self._tagged_receivers.undeclared_in_memory_tag_values)

class KeyedWorkItem(object):
  def __init__(self, key, timers=None, elements=None):
    self.key = key
    self.timers = timers
    self.elements = elements

  def __repr__(self):
    return '<KeyedWorkItem key: %s, timers: %s, elements: %s>' % (self.key, self.timers, self.elements)


class _GroupByKeyOnlyEvaluator(_TransformEvaluator):
  """TransformEvaluator for _GroupByKeyOnly transform."""

  MAX_ELEMENT_PER_BUNDLE = None

  def __init__(self, evaluation_context, applied_ptransform,
               input_committed_bundle, side_inputs, scoped_metrics_container):
    assert not side_inputs
    super(_GroupByKeyOnlyEvaluator, self).__init__(
        evaluation_context, applied_ptransform, input_committed_bundle,
        side_inputs, scoped_metrics_container)

  def start_bundle(self):
    self.gbk_items = collections.defaultdict(list)

    assert len(self._outputs) == 1
    self.output_pcollection = list(self._outputs)[0]

    kv_type_hint = (
        self._applied_ptransform.transform.get_type_hints().input_types[0])
    self.key_coder = coders.registry.get_coder(kv_type_hint[0].tuple_types[0])

  def process_element(self, element):
    # print '[!] GBK process_element', element
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
    for encoded_k, vs in self.gbk_items.iteritems():
      k = self.key_coder.decode(encoded_k)
      bundle = self._evaluation_context.create_keyed_bundle(self.output_pcollection, k)
      kwi = KeyedWorkItem(k, elements=vs)
      bundle.add(GlobalWindows.windowed_value(kwi))
      bundles.append(bundle)
    # if not bundles:
    #   bundle = self._evaluation_context.create_keyed_bundle(self.output_pcollection, None)
    #   bundles.append(bundle)

    return TransformResult(
        self._applied_ptransform, bundles, [], None, None, None, None, None)

class _GroupAlsoByWindowEvaluator(_TransformEvaluator):
  """TransformEvaluator for GroupByKeyOnly transform."""

  MAX_ELEMENT_PER_BUNDLE = None

  def __init__(self, evaluation_context, applied_ptransform,
               input_committed_bundle, side_inputs, scoped_metrics_container):
    assert not side_inputs
    super(_GroupAlsoByWindowEvaluator, self).__init__(
        evaluation_context, applied_ptransform, input_committed_bundle,
        side_inputs, scoped_metrics_container)

  #   self.driver = create_trigger_driver(self.windowing)
  #   self.state_type = InMemoryUnmergedState

  # def process(self, element):
  #   k, vs = element
  #   state = self.state_type()
  #   # TODO(robertwb): Conditionally process in smaller chunks.
  #   for wvalue in self.driver.process_elements(state, vs, MIN_TIMESTAMP):
  #     yield wvalue.with_value((k, wvalue.value))
  #   while state.timers:
  #     fired = state.get_and_clear_timers()
  #     for timer_window, (name, time_domain, fire_time) in fired:
  #       for wvalue in self.driver.process_timer(
  #           timer_window, name, time_domain, fire_time, state):
  #         yield wvalue.with_value((k, wvalue.value))
  def start_bundle(self):
    assert len(self._outputs) == 1
    self.output_pcollection = list(self._outputs)[0]

    self.state = self._execution_context.get_step_context().get_state()

    self.driver = create_trigger_driver(self._applied_ptransform.transform.windowing)

    self.gabw_items = []

    # The input type of a GroupByKey will be KV[Any, Any] or more specific.
    print 'GABW_TRANSFORM', self._applied_ptransform.transform, self._applied_ptransform.transform.get_type_hints(), self._applied_ptransform.transform.get_type_hints().input_types
    kv_type_hint = (
        self._applied_ptransform.transform.get_type_hints().input_types[0])
    self.key_coder = coders.registry.get_coder(kv_type_hint[0].tuple_types[0])
    # print 'GABW KEY_CODER', self.key_coder

  def process_element(self, element):
    kwi = element.value
    print '[!] GABW process_element', element, kwi
    assert isinstance(kwi, KeyedWorkItem), (element, kwi)
    k, timers, vs = kwi.key, kwi.timers, kwi.elements
    encoded_k = self.key_coder.encode(k)
    state = self.state

    print '[!] GABW current input watermark:', self._execution_context.watermarks.input_watermark
    fired = state.get_and_clear_timers(
      self._execution_context.watermarks.input_watermark)
    print '[!!!] fired', fired
    for timer_window, (name, time_domain, fire_time) in fired:
      for wvalue in self.driver.process_timer(
          timer_window, name, time_domain, fire_time, state):
        # print '[!!] wvalue.value', wvalue.value
        self.gabw_items.append(wvalue.with_value((k, wvalue.value)))

    # TODO(robertwb): Conditionally process in smaller chunks.
    if vs:
      for wvalue in self.driver.process_elements(state, vs, MIN_TIMESTAMP):
        self.gabw_items.append(wvalue.with_value((k, wvalue.value)))
    # while state.timers:
    #   fired = state.get_and_clear_timers()
    #   for timer_window, (name, time_domain, fire_time) in fired:
    #     for wvalue in self.driver.process_timer(
    #         timer_window, name, time_domain, fire_time, state):
    #       yield wvalue.with_value((k, wvalue.value))

  def finish_bundle(self):
    print '[!] GABW_OUTPUT', self.gabw_items
    bundle = self._evaluation_context.create_bundle(
        self.output_pcollection)
    for item in self.gabw_items:  # TODO
      bundle.add(item)
    bundles = []
    if self.gabw_items:
      bundles = [bundle]
    hold = self.state.get_earliest_hold()
    return TransformResult(
        self._applied_ptransform, bundles, [], self.state, None, None, None, hold)


class _NativeWriteEvaluator(_TransformEvaluator):
  """TransformEvaluator for _NativeWrite transform."""

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
    # state: [values]
    self.state = (self._execution_context.legacy_existing_state
                  if self._execution_context.legacy_existing_state else [])

  def process_element(self, element):
    self.state.append(element)

  def finish_bundle(self):
    # finish_bundle will append incoming bundles in memory until all the bundles
    # carrying data is processed. This is done to produce only a single output
    # shard (some tests depends on this behavior). It is possible to have
    # incoming empty bundles after the output is produced, these bundles will be
    # ignored and would not generate additional output files.
    # TODO(altay): Do not wait until the last bundle to write in a single shard.
    if self._is_final_bundle:
      if self._has_already_produced_output:
        # Ignore empty bundles that arrive after the output is produced.
        assert self.state == []
      else:
        self._sink.pipeline_options = self._evaluation_context.pipeline_options
        with self._sink.writer() as writer:
          for v in self.state:
            writer.Write(v.value)
      state = None
      hold = WatermarkManager.WATERMARK_POS_INF
    else:
      state = self.state
      hold = WatermarkManager.WATERMARK_NEG_INF

    return TransformResult(
        self._applied_ptransform, [], [], None, state, None, None, hold)
