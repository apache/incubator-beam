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

# cython: language_level=3
# cython: profile=True

"""Worker operations executor."""

from __future__ import absolute_import

import collections
import logging
import sys
import threading
from builtins import filter
from builtins import object
from builtins import zip
from typing import TYPE_CHECKING
from typing import Any
from typing import DefaultDict
from typing import Dict
from typing import FrozenSet
from typing import Hashable
from typing import Iterator
from typing import List
from typing import MutableMapping
from typing import Optional
from typing import Tuple
from typing import Union

from apache_beam import pvalue
from apache_beam.internal import pickler
from apache_beam.io import iobase
from apache_beam.metrics import monitoring_infos
from apache_beam.metrics.execution import MetricsContainer
from apache_beam.portability.api import beam_fn_api_pb2
from apache_beam.portability.api import metrics_pb2
from apache_beam.runners import common
from apache_beam.runners.common import Receiver
from apache_beam.runners.dataflow.internal.names import PropertyNames
from apache_beam.runners.worker import opcounters
from apache_beam.runners.worker import operation_specs
from apache_beam.runners.worker import sideinputs
from apache_beam.transforms import sideinputs as apache_sideinputs
from apache_beam.transforms import combiners
from apache_beam.transforms import core
from apache_beam.transforms import userstate
from apache_beam.transforms.combiners import PhasedCombineFnExecutor
from apache_beam.transforms.combiners import curry_combine_fn
from apache_beam.transforms.window import GlobalWindows
from apache_beam.utils.windowed_value import WindowedValue

if TYPE_CHECKING:
  from apache_beam.coders import coders
  from apache_beam.runners.worker.bundle_processor import ExecutionContext
  from apache_beam.runners.worker.statesampler import StateSampler

# Allow some "pure mode" declarations.
try:
  import cython
except ImportError:
  class FakeCython(object):
    @staticmethod
    def cast(type, value):
      return value
  globals()['cython'] = FakeCython()


_globally_windowed_value = GlobalWindows.windowed_value(None)
_global_window_type = type(_globally_windowed_value.windows[0])


class ConsumerSet(Receiver):
  """A ConsumerSet represents a graph edge between two Operation nodes.

  The ConsumerSet object collects information from the output of the
  Operation at one end of its edge and the input of the Operation at
  the other edge.
  ConsumerSet are attached to the outputting Operation.
  """
  @staticmethod
  def create(counter_factory,
             step_name,  # type: str
             output_index,
             consumers,  # type: List[Operation]
             coder
            ):
    # type: (...) -> ConsumerSet
    if len(consumers) == 1:
      return SingletonConsumerSet(
          counter_factory, step_name, output_index, consumers, coder)
    else:
      return ConsumerSet(
          counter_factory, step_name, output_index, consumers, coder)

  def __init__(self,
               counter_factory,
               step_name,  # type: str
               output_index,
               consumers,  # type: List[Operation]
               coder
              ):
    self.consumers = consumers
    self.opcounter = opcounters.OperationCounters(
        counter_factory, step_name, coder, output_index)
    # Used in repr.
    self.step_name = step_name
    self.output_index = output_index
    self.coder = coder

  def receive(self, windowed_value):
    # type: (WindowedValue) -> None
    self.update_counters_start(windowed_value)
    for consumer in self.consumers:
      cython.cast(Operation, consumer).process(windowed_value)
    self.update_counters_finish()

  def try_split(self, fraction_of_remainder):
    # TODO(SDF): Consider supporting splitting each consumer individually.
    # This would never come up in the existing SDF expansion, but might
    # be useful to support fused SDF nodes.
    # This would require dedicated delivery of the split results to each
    # of the consumers separately.
    return None

  def current_element_progress(self):
    # type: () -> Optional[iobase.RestrictionProgress]
    """Returns the progress of the current element.

    This progress should be an instance of
    apache_beam.io.iobase.RestrictionProgress, or None if progress is unknown.
    """
    # TODO(SDF): Could implement this as a weighted average, if it becomes
    # useful to split on.
    return None

  def update_counters_start(self, windowed_value):
    # type: (WindowedValue) -> None
    self.opcounter.update_from(windowed_value)

  def update_counters_finish(self):
    # type: () -> None
    self.opcounter.update_collect()

  def __repr__(self):
    return '%s[%s.out%s, coder=%s, len(consumers)=%s]' % (
        self.__class__.__name__, self.step_name, self.output_index, self.coder,
        len(self.consumers))


class SingletonConsumerSet(ConsumerSet):
  def __init__(
      self, counter_factory, step_name, output_index, consumers, coder):
    assert len(consumers) == 1
    super(SingletonConsumerSet, self).__init__(
        counter_factory, step_name, output_index, consumers, coder)
    self.consumer = consumers[0]

  def receive(self, windowed_value):
    # type: (WindowedValue) -> None
    self.update_counters_start(windowed_value)
    self.consumer.process(windowed_value)
    self.update_counters_finish()

  def try_split(self, fraction_of_remainder):
    return self.consumer.try_split(fraction_of_remainder)

  def current_element_progress(self):
    return self.consumer.current_element_progress()


class Operation(object):
  """An operation representing the live version of a work item specification.

  An operation can have one or more outputs and for each output it can have
  one or more receiver operations that will take that as input.
  """

  def __init__(self,
               name_context,  # type: Union[str, common.NameContext]
               spec,
               counter_factory,
               state_sampler  # type: StateSampler
              ):
    """Initializes a worker operation instance.

    Args:
      name_context: A NameContext instance or string(deprecated), with the
        name information for this operation.
      spec: A operation_specs.Worker* instance.
      counter_factory: The CounterFactory to use for our counters.
      state_sampler: The StateSampler for the current operation.
    """
    if isinstance(name_context, common.NameContext):
      # TODO(BEAM-4028): Clean this up once it's completely migrated.
      # We use the specific operation name that is used for metrics and state
      # sampling.
      self.name_context = name_context
    else:
      self.name_context = common.NameContext(name_context)

    self.spec = spec
    self.counter_factory = counter_factory
    self.execution_context = None  # type: Optional[ExecutionContext]
    self.consumers = collections.defaultdict(list)  # type: DefaultDict[int, List[Operation]]

    # These are overwritten in the legacy harness.
    self.metrics_container = MetricsContainer(self.name_context.metrics_name())

    self.state_sampler = state_sampler
    self.scoped_start_state = self.state_sampler.scoped_state(
        self.name_context, 'start', metrics_container=self.metrics_container)
    self.scoped_process_state = self.state_sampler.scoped_state(
        self.name_context, 'process', metrics_container=self.metrics_container)
    self.scoped_finish_state = self.state_sampler.scoped_state(
        self.name_context, 'finish', metrics_container=self.metrics_container)
    # TODO(ccy): the '-abort' state can be added when the abort is supported in
    # Operations.
    self.receivers = []  # type: List[ConsumerSet]
    # Legacy workers cannot call setup() until after setting additional state
    # on the operation.
    self.setup_done = False
    self.step_name = None

  def setup(self):
    # type: () -> None
    """Set up operation.

    This must be called before any other methods of the operation."""
    with self.scoped_start_state:
      self.debug_logging_enabled = logging.getLogger().isEnabledFor(
          logging.DEBUG)
      # Everything except WorkerSideInputSource, which is not a
      # top-level operation, should have output_coders
      #TODO(pabloem): Define better what step name is used here.
      if getattr(self.spec, 'output_coders', None):
        self.receivers = [
            ConsumerSet.create(
                self.counter_factory,
                self.name_context.logging_name(),
                i,
                self.consumers[i], coder)
            for i, coder in enumerate(self.spec.output_coders)]
    self.setup_done = True

  def start(self):
    # type: () -> None
    """Start operation."""
    if not self.setup_done:
      # For legacy workers.
      self.setup()

  def process(self, o):
    # type: (WindowedValue) -> None
    """Process element in operation."""
    pass

  def finalize_bundle(self):
    # type: () -> None
    pass

  def needs_finalization(self):
    return False

  def try_split(self, fraction_of_remainder):
    return None

  def current_element_progress(self):
    return None

  def finish(self):
    # type: () -> None
    """Finish operation."""
    pass

  def teardown(self):
    # type: () -> None
    """Tear down operation.

    No other methods of this operation should be called after this."""
    pass

  def reset(self):
    # type: () -> None
    self.metrics_container.reset()

  def output(self, windowed_value, output_index=0):
    # type: (WindowedValue, int) -> None
    cython.cast(Receiver, self.receivers[output_index]).receive(windowed_value)

  def add_receiver(self, operation, output_index=0):
    # type: (Operation, int) -> None
    """Adds a receiver operation for the specified output."""
    self.consumers[output_index].append(operation)

  def progress_metrics(self):
    # type: () -> beam_fn_api_pb2.Metrics.PTransform
    return beam_fn_api_pb2.Metrics.PTransform(
        processed_elements=beam_fn_api_pb2.Metrics.PTransform.ProcessedElements(
            measured=beam_fn_api_pb2.Metrics.PTransform.Measured(
                total_time_spent=(
                    self.scoped_start_state.sampled_seconds()
                    + self.scoped_process_state.sampled_seconds()
                    + self.scoped_finish_state.sampled_seconds()),
                # Multi-output operations should override this.
                output_element_counts=(
                    # If there is exactly one output, we can unambiguously
                    # fix its name later, which we do.
                    # TODO(robertwb): Plumb the actual name here.
                    {'ONLY_OUTPUT': self.receivers[0].opcounter
                                    .element_counter.value()}
                    if len(self.receivers) == 1
                    else None))),
        user=self.metrics_container.to_runner_api())

  def monitoring_infos(self, transform_id):
    # type: (str) -> Dict[FrozenSet, metrics_pb2.MonitoringInfo]
    """Returns the list of MonitoringInfos collected by this operation."""
    all_monitoring_infos = self.execution_time_monitoring_infos(transform_id)
    all_monitoring_infos.update(
        self.pcollection_count_monitoring_infos(transform_id))
    all_monitoring_infos.update(self.user_monitoring_infos(transform_id))
    return all_monitoring_infos

  def pcollection_count_monitoring_infos(self, transform_id):
    """Returns the element count MonitoringInfo collected by this operation."""
    if len(self.receivers) == 1:
      # If there is exactly one output, we can unambiguously
      # fix its name later, which we do.
      # TODO(robertwb): Plumb the actual name here.
      elem_count_mi = monitoring_infos.int64_counter(
          monitoring_infos.ELEMENT_COUNT_URN,
          self.receivers[0].opcounter.element_counter.value(),
          ptransform=transform_id,
          tag='ONLY_OUTPUT' if len(self.receivers) == 1 else str(None),
      )

      (unused_mean, sum, count, min, max) = (
          self.receivers[0].opcounter.mean_byte_counter.value())
      metric = metrics_pb2.Metric(
          distribution_data=metrics_pb2.DistributionData(
              int_distribution_data=metrics_pb2.IntDistributionData(
                  count=count,
                  sum=sum,
                  min=min,
                  max=max
              )
          )
      )
      sampled_byte_count = monitoring_infos.int64_distribution(
          monitoring_infos.SAMPLED_BYTE_SIZE_URN,
          metric,
          ptransform=transform_id,
          tag='ONLY_OUTPUT' if len(self.receivers) == 1 else str(None),
      )
      return {
          monitoring_infos.to_key(elem_count_mi) : elem_count_mi,
          monitoring_infos.to_key(sampled_byte_count) : sampled_byte_count
      }
    return {}

  def user_monitoring_infos(self, transform_id):
    """Returns the user MonitoringInfos collected by this operation."""
    return self.metrics_container.to_runner_api_monitoring_infos(transform_id)

  def execution_time_monitoring_infos(self, transform_id):
    # type: (str) -> Dict[FrozenSet, metrics_pb2.MonitoringInfo]
    total_time_spent_msecs = (
        self.scoped_start_state.sampled_msecs_int()
        + self.scoped_process_state.sampled_msecs_int()
        + self.scoped_finish_state.sampled_msecs_int())
    mis = [
        monitoring_infos.int64_counter(
            monitoring_infos.START_BUNDLE_MSECS_URN,
            self.scoped_start_state.sampled_msecs_int(),
            ptransform=transform_id
        ),
        monitoring_infos.int64_counter(
            monitoring_infos.PROCESS_BUNDLE_MSECS_URN,
            self.scoped_process_state.sampled_msecs_int(),
            ptransform=transform_id
        ),
        monitoring_infos.int64_counter(
            monitoring_infos.FINISH_BUNDLE_MSECS_URN,
            self.scoped_finish_state.sampled_msecs_int(),
            ptransform=transform_id
        ),
        monitoring_infos.int64_counter(
            monitoring_infos.TOTAL_MSECS_URN,
            total_time_spent_msecs,
            ptransform=transform_id
        ),
    ]
    return {monitoring_infos.to_key(mi) : mi for mi in mis}

  def __str__(self):
    """Generates a useful string for this object.

    Compactly displays interesting fields.  In particular, pickled
    fields are not displayed.  Note that we collapse the fields of the
    contained Worker* object into this object, since there is a 1-1
    mapping between Operation and operation_specs.Worker*.

    Returns:
      Compact string representing this object.
    """
    return self.str_internal()

  def str_internal(self, is_recursive=False):
    """Internal helper for __str__ that supports recursion.

    When recursing on receivers, keep the output short.
    Args:
      is_recursive: whether to omit some details, particularly receivers.
    Returns:
      Compact string representing this object.
    """
    printable_name = self.__class__.__name__
    if hasattr(self, 'step_name'):
      printable_name += ' %s' % self.name_context.logging_name()
      if is_recursive:
        # If we have a step name, stop here, no more detail needed.
        return '<%s>' % printable_name

    if self.spec is None:
      printable_fields = []
    else:
      printable_fields = operation_specs.worker_printable_fields(self.spec)

    if not is_recursive and getattr(self, 'receivers', []):
      printable_fields.append('receivers=[%s]' % ', '.join([
          str(receiver) for receiver in self.receivers]))

    return '<%s %s>' % (printable_name, ', '.join(printable_fields))


class ReadOperation(Operation):

  def start(self):
    with self.scoped_start_state:
      super(ReadOperation, self).start()
      range_tracker = self.spec.source.source.get_range_tracker(
          self.spec.source.start_position, self.spec.source.stop_position)
      for value in self.spec.source.source.read(range_tracker):
        if isinstance(value, WindowedValue):
          windowed_value = value
        else:
          windowed_value = _globally_windowed_value.with_value(value)
        self.output(windowed_value)


class ImpulseReadOperation(Operation):

  def __init__(self, name_context, counter_factory, state_sampler,
               consumers, source, output_coder):
    super(ImpulseReadOperation, self).__init__(
        name_context, None, counter_factory, state_sampler)
    self.source = source
    self.receivers = [
        ConsumerSet.create(
            self.counter_factory, self.name_context.step_name, 0,
            next(iter(consumers.values())), output_coder)]

  def process(self, unused_impulse):
    # type: (WindowedValue) -> None
    with self.scoped_process_state:
      range_tracker = self.source.get_range_tracker(None, None)
      for value in self.source.read(range_tracker):
        if isinstance(value, WindowedValue):
          windowed_value = value
        else:
          windowed_value = _globally_windowed_value.with_value(value)
        self.output(windowed_value)


class InMemoryWriteOperation(Operation):
  """A write operation that will write to an in-memory sink."""

  def process(self, o):
    # type: (WindowedValue) -> None
    with self.scoped_process_state:
      if self.debug_logging_enabled:
        logging.debug('Processing [%s] in %s', o, self)
      self.spec.output_buffer.append(
          o if self.spec.write_windowed_values else o.value)


class _TaggedReceivers(dict):

  def __init__(self, counter_factory, step_name):
    self._counter_factory = counter_factory
    self._step_name = step_name

  def __missing__(self, tag):
    self[tag] = receiver = ConsumerSet(
        self._counter_factory, self._step_name, tag, [], None)
    return receiver


class DoOperation(Operation):
  """A Do operation that will execute a custom DoFn for each input element."""

  def __init__(self,
               name,  # type: common.NameContext
               spec,  # operation_specs.WorkerDoFn  # need to fix this type
               counter_factory,
               sampler,
               side_input_maps=None,
               user_state_context=None,
               timer_inputs=None
              ):
    super(DoOperation, self).__init__(name, spec, counter_factory, sampler)
    self.side_input_maps = side_input_maps
    self.user_state_context = user_state_context
    self.tagged_receivers = None  # type: Optional[_TaggedReceivers]
    # A mapping of timer tags to the input "PCollections" they come in on.
    self.timer_inputs = timer_inputs or {}
    self.input_info = None  # type: Optional[Tuple[str, str, coders.WindowedValueCoder, MutableMapping[str, str]]]

  def _read_side_inputs(self, tags_and_types):
    # type: (...) -> Iterator[apache_sideinputs.SideInputMap]
    """Generator reading side inputs in the order prescribed by tags_and_types.

    Args:
      tags_and_types: List of tuples (tag, type). Each side input has a string
        tag that is specified in the worker instruction. The type is actually
        a boolean which is True for singleton input (read just first value)
        and False for collection input (read all values).

    Yields:
      With each iteration it yields the result of reading an entire side source
      either in singleton or collection mode according to the tags_and_types
      argument.
    """
    # Only call this on the old path where side_input_maps was not
    # provided directly.
    assert self.side_input_maps is None

    # We will read the side inputs in the order prescribed by the
    # tags_and_types argument because this is exactly the order needed to
    # replace the ArgumentPlaceholder objects in the args/kwargs of the DoFn
    # getting the side inputs.
    #
    # Note that for each tag there could be several read operations in the
    # specification. This can happen for instance if the source has been
    # sharded into several files.
    for i, (side_tag, view_class, view_options) in enumerate(tags_and_types):
      sources = []
      # Using the side_tag in the lambda below will trigger a pylint warning.
      # However in this case it is fine because the lambda is used right away
      # while the variable has the value assigned by the current iteration of
      # the for loop.
      # pylint: disable=cell-var-from-loop
      for si in filter(
          lambda o: o.tag == side_tag, self.spec.side_inputs):
        if not isinstance(si, operation_specs.WorkerSideInputSource):
          raise NotImplementedError('Unknown side input type: %r' % si)
        sources.append(si.source)
        # The tracking of time spend reading and bytes read from side inputs is
        # behind an experiment flag to test its performance impact.
        si_counter = opcounters.SideInputReadCounter(
            self.counter_factory,
            self.state_sampler,
            declaring_step=self.name_context.step_name,
            # Inputs are 1-indexed, so we add 1 to i in the side input id
            input_index=i + 1)
      iterator_fn = sideinputs.get_iterator_fn_for_sources(
          sources, read_counter=si_counter)

      # Backwards compatibility for pre BEAM-733 SDKs.
      if isinstance(view_options, tuple):
        if view_class == pvalue.AsSingleton:
          has_default, default = view_options
          view_options = {'default': default} if has_default else {}
        else:
          view_options = {}

      yield apache_sideinputs.SideInputMap(
          view_class, view_options, sideinputs.EmulatedIterable(iterator_fn))

  def setup(self):
    # type: () -> None
    with self.scoped_start_state:
      super(DoOperation, self).setup()

      # See fn_data in dataflow_runner.py
      fn, args, kwargs, tags_and_types, window_fn = (
          pickler.loads(self.spec.serialized_fn))

      state = common.DoFnState(self.counter_factory)
      state.step_name = self.name_context.logging_name()

      # Tag to output index map used to dispatch the side output values emitted
      # by the DoFn function to the appropriate receivers. The main output is
      # tagged with None and is associated with its corresponding index.
      self.tagged_receivers = _TaggedReceivers(
          self.counter_factory, self.name_context.logging_name())

      output_tag_prefix = PropertyNames.OUT + '_'
      for index, tag in enumerate(self.spec.output_tags):
        if tag == PropertyNames.OUT:
          original_tag = None  # type: Optional[str]
        elif tag.startswith(output_tag_prefix):
          original_tag = tag[len(output_tag_prefix):]
        else:
          raise ValueError('Unexpected output name for operation: %s' % tag)
        self.tagged_receivers[original_tag] = self.receivers[index]

      if self.user_state_context:
        self.user_state_context.update_timer_receivers(self.tagged_receivers)
        self.timer_specs = {
            spec.name: spec
            for spec in userstate.get_dofn_specs(fn)[1]
        }

      if self.side_input_maps is None:
        if tags_and_types:
          self.side_input_maps = list(self._read_side_inputs(tags_and_types))
        else:
          self.side_input_maps = []

      self.dofn_runner = common.DoFnRunner(
          fn, args, kwargs, self.side_input_maps, window_fn,
          tagged_receivers=self.tagged_receivers,
          step_name=self.name_context.logging_name(),
          state=state,
          user_state_context=self.user_state_context,
          operation_name=self.name_context.metrics_name())
      self.dofn_runner.setup()

      self.dofn_receiver = (self.dofn_runner
                            if isinstance(self.dofn_runner, Receiver)
                            else DoFnRunnerReceiver(self.dofn_runner))

  def start(self):
    # type: () -> None
    with self.scoped_start_state:
      super(DoOperation, self).start()
      self.dofn_runner.start()

  def process(self, o):
    # type: (WindowedValue) -> None
    with self.scoped_process_state:
      self.dofn_receiver.receive(o)

  def finalize_bundle(self):
    # type: () -> None
    self.dofn_receiver.finalize()

  def needs_finalization(self):
    # type: () -> bool
    return self.dofn_receiver.bundle_finalizer_param.has_callbacks()

  def process_timer(self, tag, windowed_timer):
    key, timer_data = windowed_timer.value
    timer_spec = self.timer_specs[tag]
    self.dofn_receiver.process_user_timer(
        timer_spec, key, windowed_timer.windows[0], timer_data['timestamp'])

  def finish(self):
    # type: () -> None
    with self.scoped_finish_state:
      self.dofn_runner.finish()
      if self.user_state_context:
        self.user_state_context.commit()

  def teardown(self):
    # type: () -> None
    with self.scoped_finish_state:
      self.dofn_runner.teardown()

  def reset(self):
    # type: () -> None
    super(DoOperation, self).reset()
    for side_input_map in self.side_input_maps:
      side_input_map.reset()
    if self.user_state_context:
      self.user_state_context.reset()
    self.dofn_receiver.bundle_finalizer_param.reset()

  def progress_metrics(self):
    # type: () -> beam_fn_api_pb2.Metrics.PTransform
    metrics = super(DoOperation, self).progress_metrics()
    if self.tagged_receivers:
      metrics.processed_elements.measured.output_element_counts.clear()
      for tag, receiver in self.tagged_receivers.items():
        metrics.processed_elements.measured.output_element_counts[
            str(tag)] = receiver.opcounter.element_counter.value()
    return metrics

  def monitoring_infos(self, transform_id):
    # type: (str) -> Dict[FrozenSet, metrics_pb2.MonitoringInfo]
    infos = super(DoOperation, self).monitoring_infos(transform_id)
    if self.tagged_receivers:
      for tag, receiver in self.tagged_receivers.items():
        mi = monitoring_infos.int64_counter(
            monitoring_infos.ELEMENT_COUNT_URN,
            receiver.opcounter.element_counter.value(),
            ptransform=transform_id,
            tag=str(tag)
        )
        infos[monitoring_infos.to_key(mi)] = mi
        (unused_mean, sum, count, min, max) = (
            receiver.opcounter.mean_byte_counter.value())
        metric = metrics_pb2.Metric(
            distribution_data=metrics_pb2.DistributionData(
                int_distribution_data=metrics_pb2.IntDistributionData(
                    count=count,
                    sum=sum,
                    min=min,
                    max=max
                )
            )
        )
        sampled_byte_count = monitoring_infos.int64_distribution(
            monitoring_infos.SAMPLED_BYTE_SIZE_URN,
            metric,
            ptransform=transform_id,
            tag=str(tag)
        )
        infos[monitoring_infos.to_key(sampled_byte_count)] = sampled_byte_count
    return infos


class SdfProcessSizedElements(DoOperation):

  def __init__(self, *args, **kwargs):
    super(SdfProcessSizedElements, self).__init__(*args, **kwargs)
    self.lock = threading.RLock()
    self.element_start_output_bytes = None

  def process(self, o):
    # type: (WindowedValue) -> None
    assert self.tagged_receivers is not None
    with self.scoped_process_state:
      try:
        with self.lock:
          self.element_start_output_bytes = self._total_output_bytes()
          for receiver in self.tagged_receivers.values():
            receiver.opcounter.restart_sampling()
        # Actually processing the element can be expensive; do it without
        # the lock.
        delayed_application = self.dofn_runner.process_with_sized_restriction(o)
        if delayed_application:
          assert self.execution_context is not None
          self.execution_context.delayed_applications.append(
              (self, delayed_application))
      finally:
        with self.lock:
          self.element_start_output_bytes = None

  def try_split(self, fraction_of_remainder):
    split = self.dofn_runner.try_split(fraction_of_remainder)
    if split:
      primary, residual = split
      return (self, primary), (self, residual)

  def current_element_progress(self):
    with self.lock:
      if self.element_start_output_bytes is not None:
        progress = self.dofn_runner.current_element_progress()
        if progress is not None:
          return progress.with_completed(
              self._total_output_bytes() - self.element_start_output_bytes)

  def progress_metrics(self):
    # type: () -> beam_fn_api_pb2.Metrics.PTransform
    with self.lock:
      metrics = super(SdfProcessSizedElements, self).progress_metrics()
      current_element_progress = self.current_element_progress()
    if current_element_progress:
      assert self.input_info is not None
      metrics.active_elements.measured.input_element_counts[
          self.input_info[1]] = 1
      metrics.active_elements.fraction_remaining = (
          current_element_progress.fraction_remaining)
    return metrics

  def _total_output_bytes(self):
    total = 0
    for receiver in self.tagged_receivers.values():
      elements = receiver.opcounter.element_counter.value()
      if elements > 0:
        mean = (receiver.opcounter.mean_byte_counter.value())[0]
        total += elements * mean
    return total


class DoFnRunnerReceiver(Receiver):

  def __init__(self, dofn_runner):
    self.dofn_runner = dofn_runner

  def receive(self, windowed_value):
    # type: (WindowedValue) -> None
    self.dofn_runner.process(windowed_value)


class CombineOperation(Operation):
  """A Combine operation executing a CombineFn for each input element."""

  def __init__(self, name_context, spec, counter_factory, state_sampler):
    super(CombineOperation, self).__init__(
        name_context, spec, counter_factory, state_sampler)
    # Combiners do not accept deferred side-inputs (the ignored fourth argument)
    # and therefore the code to handle the extra args/kwargs is simpler than for
    # the DoFn's of ParDo.
    fn, args, kwargs = pickler.loads(self.spec.serialized_fn)[:3]
    self.phased_combine_fn = (
        PhasedCombineFnExecutor(self.spec.phase, fn, args, kwargs))

  def process(self, o):
    # type: (WindowedValue) -> None
    with self.scoped_process_state:
      if self.debug_logging_enabled:
        logging.debug('Processing [%s] in %s', o, self)
      key, values = o.value
      self.output(
          o.with_value((key, self.phased_combine_fn.apply(values))))

  def finish(self):
    logging.debug('Finishing %s', self)


def create_pgbk_op(step_name, spec, counter_factory, state_sampler):
  if spec.combine_fn:
    return PGBKCVOperation(step_name, spec, counter_factory, state_sampler)
  else:
    return PGBKOperation(step_name, spec, counter_factory, state_sampler)


class PGBKOperation(Operation):
  """Partial group-by-key operation.

  This takes (windowed) input (key, value) tuples and outputs
  (key, [value]) tuples, performing a best effort group-by-key for
  values in this bundle, memory permitting.
  """

  def __init__(self, name_context, spec, counter_factory, state_sampler):
    super(PGBKOperation, self).__init__(
        name_context, spec, counter_factory, state_sampler)
    assert not self.spec.combine_fn
    self.table = collections.defaultdict(list)
    self.size = 0
    # TODO(robertwb) Make this configurable.
    self.max_size = 10 * 1000

  def process(self, o):
    # type: (WindowedValue) -> None
    with self.scoped_process_state:
      # TODO(robertwb): Structural (hashable) values.
      key = o.value[0], tuple(o.windows)
      self.table[key].append(o)
      self.size += 1
      if self.size > self.max_size:
        self.flush(9 * self.max_size // 10)

  def finish(self):
    self.flush(0)

  def flush(self, target):
    limit = self.size - target
    for ix, (kw, vs) in enumerate(list(self.table.items())):
      if ix >= limit:
        break
      del self.table[kw]
      key, windows = kw
      output_value = [v.value[1] for v in vs]
      windowed_value = WindowedValue(
          (key, output_value),
          vs[0].timestamp, windows)
      self.output(windowed_value)


class PGBKCVOperation(Operation):

  def __init__(self, name_context, spec, counter_factory, state_sampler):
    super(PGBKCVOperation, self).__init__(
        name_context, spec, counter_factory, state_sampler)
    # Combiners do not accept deferred side-inputs (the ignored fourth
    # argument) and therefore the code to handle the extra args/kwargs is
    # simpler than for the DoFn's of ParDo.
    fn, args, kwargs = pickler.loads(self.spec.combine_fn)[:3]
    self.combine_fn = curry_combine_fn(fn, args, kwargs)
    self.combine_fn_add_input = self.combine_fn.add_input
    base_compact = (
        core.CombineFn.compact if sys.version_info >= (3,)
        else core.CombineFn.compact.__func__)
    if self.combine_fn.compact.__func__ is base_compact:
      self.combine_fn_compact = None
    else:
      self.combine_fn_compact = self.combine_fn.compact
    # Optimization for the (known tiny accumulator, often wide keyspace)
    # combine functions.
    # TODO(b/36567833): Bound by in-memory size rather than key count.
    self.max_keys = (
        1000 * 1000 if
        isinstance(fn, (combiners.CountCombineFn, combiners.MeanCombineFn)) or
        # TODO(b/36597732): Replace this 'or' part by adding the 'cy' optimized
        # combiners to the short list above.
        (isinstance(fn, core.CallableWrapperCombineFn) and
         fn._fn in (min, max, sum)) else 100 * 1000)  # pylint: disable=protected-access
    self.key_count = 0
    self.table = {}

  def process(self, wkv):
    # type: (WindowedValue) -> None
    with self.scoped_process_state:
      key, value = wkv.value
      # pylint: disable=unidiomatic-typecheck
      # Optimization for the global window case.
      if len(wkv.windows) == 1 and type(wkv.windows[0]) is _global_window_type:
        wkey = 0, key  # type: Tuple[Hashable, Any]
      else:
        wkey = tuple(wkv.windows), key
      entry = self.table.get(wkey, None)
      if entry is None:
        if self.key_count >= self.max_keys:
          target = self.key_count * 9 // 10
          old_wkeys = []
          # TODO(robertwb): Use an LRU cache?
          for old_wkey, old_wvalue in self.table.items():
            old_wkeys.append(old_wkey)  # Can't mutate while iterating.
            self.output_key(old_wkey, old_wvalue[0])
            self.key_count -= 1
            if self.key_count <= target:
              break
          for old_wkey in reversed(old_wkeys):
            del self.table[old_wkey]
        self.key_count += 1
        # We save the accumulator as a one element list so we can efficiently
        # mutate when new values are added without searching the cache again.
        entry = self.table[wkey] = [self.combine_fn.create_accumulator()]
      entry[0] = self.combine_fn_add_input(entry[0], value)

  def finish(self):
    for wkey, value in self.table.items():
      self.output_key(wkey, value[0])
    self.table = {}
    self.key_count = 0

  def output_key(self, wkey, accumulator):
    windows, key = wkey
    if self.combine_fn_compact is None:
      value = accumulator
    else:
      value = self.combine_fn_compact(accumulator)
    if windows == 0:
      self.output(_globally_windowed_value.with_value((key, value)))
    else:
      self.output(
          WindowedValue((key, value), windows[0].max_timestamp(), windows))


class FlattenOperation(Operation):
  """Flatten operation.

  Receives one or more producer operations, outputs just one list
  with all the items.
  """

  def process(self, o):
    # type: (WindowedValue) -> None
    with self.scoped_process_state:
      if self.debug_logging_enabled:
        logging.debug('Processing [%s] in %s', o, self)
      self.output(o)


def create_operation(name_context, spec, counter_factory, step_name=None,
                     state_sampler=None, test_shuffle_source=None,
                     test_shuffle_sink=None, is_streaming=False):
  # type: (...) -> Operation
  """Create Operation object for given operation specification."""

  # TODO(pabloem): Document arguments to this function call.
  if not isinstance(name_context, common.NameContext):
    name_context = common.NameContext(step_name=name_context)

  if isinstance(spec, operation_specs.WorkerRead):
    if isinstance(spec.source, iobase.SourceBundle):
      op = ReadOperation(
          name_context, spec, counter_factory, state_sampler)  # type: Operation
    else:
      from dataflow_worker.native_operations import NativeReadOperation
      op = NativeReadOperation(
          name_context, spec, counter_factory, state_sampler)
  elif isinstance(spec, operation_specs.WorkerWrite):
    from dataflow_worker.native_operations import NativeWriteOperation
    op = NativeWriteOperation(
        name_context, spec, counter_factory, state_sampler)
  elif isinstance(spec, operation_specs.WorkerCombineFn):
    op = CombineOperation(
        name_context, spec, counter_factory, state_sampler)
  elif isinstance(spec, operation_specs.WorkerPartialGroupByKey):
    op = create_pgbk_op(name_context, spec, counter_factory, state_sampler)
  elif isinstance(spec, operation_specs.WorkerDoFn):
    op = DoOperation(name_context, spec, counter_factory, state_sampler)
  elif isinstance(spec, operation_specs.WorkerGroupingShuffleRead):
    from dataflow_worker.shuffle_operations import GroupedShuffleReadOperation
    op = GroupedShuffleReadOperation(
        name_context, spec, counter_factory, state_sampler,
        shuffle_source=test_shuffle_source)
  elif isinstance(spec, operation_specs.WorkerUngroupedShuffleRead):
    from dataflow_worker.shuffle_operations import UngroupedShuffleReadOperation
    op = UngroupedShuffleReadOperation(
        name_context, spec, counter_factory, state_sampler,
        shuffle_source=test_shuffle_source)
  elif isinstance(spec, operation_specs.WorkerInMemoryWrite):
    op = InMemoryWriteOperation(
        name_context, spec, counter_factory, state_sampler)
  elif isinstance(spec, operation_specs.WorkerShuffleWrite):
    from dataflow_worker.shuffle_operations import ShuffleWriteOperation
    op = ShuffleWriteOperation(
        name_context, spec, counter_factory, state_sampler,
        shuffle_sink=test_shuffle_sink)
  elif isinstance(spec, operation_specs.WorkerFlatten):
    op = FlattenOperation(
        name_context, spec, counter_factory, state_sampler)
  elif isinstance(spec, operation_specs.WorkerMergeWindows):
    from dataflow_worker.shuffle_operations import BatchGroupAlsoByWindowsOperation
    from dataflow_worker.shuffle_operations import StreamingGroupAlsoByWindowsOperation
    if is_streaming:
      op = StreamingGroupAlsoByWindowsOperation(
          name_context, spec, counter_factory, state_sampler)
    else:
      op = BatchGroupAlsoByWindowsOperation(
          name_context, spec, counter_factory, state_sampler)
  elif isinstance(spec, operation_specs.WorkerReifyTimestampAndWindows):
    from dataflow_worker.shuffle_operations import ReifyTimestampAndWindowsOperation
    op = ReifyTimestampAndWindowsOperation(
        name_context, spec, counter_factory, state_sampler)
  else:
    raise TypeError('Expected an instance of operation_specs.Worker* class '
                    'instead of %s' % (spec,))
  return op


class SimpleMapTaskExecutor(object):
  """An executor for map tasks.

   Stores progress of the read operation that is the first operation of a map
   task.
  """

  def __init__(
      self, map_task, counter_factory, state_sampler,
      test_shuffle_source=None, test_shuffle_sink=None):
    """Initializes SimpleMapTaskExecutor.

    Args:
      map_task: The map task we are to run. The maptask contains a list of
        operations, and aligned lists for step_names, original_names,
        system_names of pipeline steps.
      counter_factory: The CounterFactory instance for the work item.
      state_sampler: The StateSampler tracking the execution step.
      test_shuffle_source: Used during tests for dependency injection into
        shuffle read operation objects.
      test_shuffle_sink: Used during tests for dependency injection into
        shuffle write operation objects.
    """

    self._map_task = map_task
    self._counter_factory = counter_factory
    self._ops = []  # type: List[Operation]
    self._state_sampler = state_sampler
    self._test_shuffle_source = test_shuffle_source
    self._test_shuffle_sink = test_shuffle_sink

  def operations(self):
    # type: () -> List[Operation]
    return self._ops[:]

  def execute(self):
    """Executes all the operation_specs.Worker* instructions in a map task.

    We update the map_task with the execution status, expressed as counters.

    Raises:
      RuntimeError: if we find more than on read instruction in task spec.
      TypeError: if the spec parameter is not an instance of the recognized
        operation_specs.Worker* classes.
    """

    # operations is a list of operation_specs.Worker* instances.
    # The order of the elements is important because the inputs use
    # list indexes as references.
    for name_context, spec in zip(self._map_task.name_contexts,
                                  self._map_task.operations):
      # This is used for logging and assigning names to counters.
      op = create_operation(
          name_context, spec, self._counter_factory, None,
          self._state_sampler,
          test_shuffle_source=self._test_shuffle_source,
          test_shuffle_sink=self._test_shuffle_sink)
      self._ops.append(op)

      # Add receiver operations to the appropriate producers.
      if hasattr(op.spec, 'input'):
        producer, output_index = op.spec.input
        self._ops[producer].add_receiver(op, output_index)
      # Flatten has 'inputs', not 'input'
      if hasattr(op.spec, 'inputs'):
        for producer, output_index in op.spec.inputs:
          self._ops[producer].add_receiver(op, output_index)

    for ix, op in reversed(list(enumerate(self._ops))):
      logging.debug('Starting op %d %s', ix, op)
      op.start()
    for op in self._ops:
      op.finish()
