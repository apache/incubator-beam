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

"""EvaluationContext tracks global state, triggers and watermarks."""

from __future__ import absolute_import

import collections
import threading

from apache_beam.transforms import sideinputs
from apache_beam.runners.direct.clock import Clock
from apache_beam.runners.direct.watermark_manager import WatermarkManager
from apache_beam.runners.direct.executor import TransformExecutor
from apache_beam.runners.direct.direct_metrics import DirectMetrics
from apache_beam.transforms.trigger import InMemoryUnmergedState
from apache_beam.utils import counters




class DirectUnmergedState(InMemoryUnmergedState):
  def __init__(self):
    super(DirectUnmergedState, self).__init__()
    self.new_timers = []

  def set_timer(self, window, name, time_domain, timestamp):
    print '[!!] set_timer', window, name, time_domain, timestamp
    super(DirectUnmergedState, self).set_timer(window, name, time_domain, timestamp)

  def clear_timer(self, window, name, time_domain):
    print '[!!] clear_timer', window, name, time_domain
    # TODO(ccy): this is not implemented, but is not strictly necessary as it is an optimization
    super(DirectUnmergedState, self).clear_timer(window, name, time_domain)



class StepContext(object):
  def __init__(self, execution_context):
    self._execution_context = execution_context
    self._state = None

  def get_state(self):
    # TODO(ccy): consider using copy on write semantics so that work items can be retried.
    if not self._state:
      if self._execution_context.existing_state:
        self._state = self._execution_context.existing_state
      else:
        self._state = DirectUnmergedState()
    return self._state



class _ExecutionContext(object):

  def __init__(self, applied_ptransform, watermarks, existing_state, legacy_existing_state, key):
    self.applied_ptransform = applied_ptransform
    self.watermarks = watermarks
    self.existing_state = existing_state
    self.legacy_existing_state = legacy_existing_state
    self.key = key
    # TODO(ccy): key, clock as first arguments for consistency with Java.
    self._step_context = None

  def get_step_context(self):
    if not self._step_context:
      self._step_context = StepContext(self)
    return self._step_context



class _SideInputView(object):

  def __init__(self, view):
    self._view = view
    self.callable_queue = collections.deque()
    self.elements = []
    self.value = None
    self.has_result = False


class _SideInputsContainer(object):
  """An in-process container for side inputs.

  It provides methods for blocking until a side-input is available and writing
  to a side input.
  """

  def __init__(self, views):
    self._lock = threading.Lock()
    self._views = {}
    for view in views:
      self._views[view] = _SideInputView(view)

  def get_value_or_schedule_after_output(self, side_input, task):
    with self._lock:
      view = self._views[side_input]
      if not view.has_result:
        view.callable_queue.append(task)
        task.blocked = True
      return (view.has_result, view.value)

  def add_values(self, side_input, values):
    with self._lock:
      view = self._views[side_input]
      if view.has_result:
        print 'ERROR!!! VIEW ALREADY HAS RESULT', side_input, values
      assert not view.has_result
      view.elements.extend(values)

  def finalize_value_and_get_tasks(self, side_input):
    print '***** FINALIZE VIEWWWWW', side_input
    with self._lock:
      view = self._views[side_input]
      assert not view.has_result
      assert view.value is None
      assert view.callable_queue is not None
      view.value = self._pvalue_to_value(side_input, view.elements)
      print 'result', view.elements, view.value
      view.elements = None
      result = tuple(view.callable_queue)
      for task in result:
        task.blocked = False
      view.callable_queue = None
      view.has_result = True
      return result

  def _pvalue_to_value(self, view, values):
    """Given a side input view, returns the associated value in requested form.

    Args:
      view: SideInput for the requested side input.
      values: Iterable values associated with the side input.

    Returns:
      The side input in its requested form.

    Raises:
      ValueError: If values cannot be converted into the requested form.
    """
    return sideinputs.SideInputMap(type(view), view._view_options(), values)


class EvaluationContext(object):
  """Evaluation context with the global state information of the pipeline.

  The evaluation context for a specific pipeline being executed by the
  DirectRunner. Contains state shared within the execution across all
  transforms.

  EvaluationContext contains shared state for an execution of the
  DirectRunner that can be used while evaluating a PTransform. This
  consists of views into underlying state and watermark implementations, access
  to read and write side inputs, and constructing counter sets and
  execution contexts. This includes executing callbacks asynchronously when
  state changes to the appropriate point (e.g. when a side input is
  requested and known to be empty).

  EvaluationContext also handles results by committing finalizing
  bundles based on the current global state and updating the global state
  appropriately. This includes updating the per-(step,key) state, updating
  global watermarks, and executing any callbacks that can be executed.
  """

  def __init__(self, pipeline_options, bundle_factory, root_transforms,
               value_to_consumers, step_names, views):
    self.pipeline_options = pipeline_options
    self._bundle_factory = bundle_factory
    self._root_transforms = root_transforms
    self._value_to_consumers = value_to_consumers
    self._step_names = step_names
    self.views = views
    self._pcollection_to_views = collections.defaultdict(list)
    for view in views:
      self._pcollection_to_views[view.pvalue].append(view)
    import pprint
    print 'PCOLLECTION TO VIEWS'
    pprint.pprint(self._pcollection_to_views)

    # AppliedPTransform -> Evaluator specific state objects
    self._legacy_existing_state = {}
    # AppliedPTransform -> {key -> DirectUnmergedState objects}; todo: rename
    self._transform_keyed_states = self._initialize_transform_states(root_transforms, value_to_consumers)
    self._watermark_manager = WatermarkManager(
        Clock(), root_transforms, value_to_consumers, self._transform_keyed_states)
    self._side_inputs_container = _SideInputsContainer(views)
    self._pending_unblocked_tasks = []
    self._counter_factory = counters.CounterFactory()
    self._cache = None
    self._metrics = DirectMetrics()

    self._lock = threading.Lock()
  
  def _initialize_transform_states(self, root_transforms, value_to_consumers):
    transform_keyed_states = {}
    for transform in root_transforms:
      transform_keyed_states[transform] = {}
    for consumers in value_to_consumers.values():
      for consumer in consumers:
        transform_keyed_states[consumer] = {}
    return transform_keyed_states

  def use_pvalue_cache(self, cache):
    assert not self._cache
    self._cache = cache

  def metrics(self):
    # TODO. Should this be made a @property?
    return self._metrics

  @property
  def has_cache(self):
    return self._cache is not None

  def append_to_cache(self, applied_ptransform, tag, elements):
    with self._lock:
      assert self._cache
      self._cache.append(applied_ptransform, tag, elements)

  def is_root_transform(self, applied_ptransform):
    return applied_ptransform in self._root_transforms

  def handle_result(
      self, completed_bundle, completed_timers, result):
    """Handle the provided result produced after evaluating the input bundle.

    Handle the provided TransformResult, produced after evaluating
    the provided committed bundle (potentially None, if the result of a root
    PTransform).

    The result is the output of running the transform contained in the
    TransformResult on the contents of the provided bundle.

    Args:
      completed_bundle: the bundle that was processed to produce the result.
      completed_timers: the timers that were delivered to produce the
                        completed_bundle.
      result: the TransformResult of evaluating the input bundle

    Returns:
      the committed bundles contained within the handled result.
    """
    with self._lock:
      committed_bundles, unprocessed_bundle = self._commit_bundles(
          result.uncommitted_output_bundles,
          result.unprocessed_bundle)
      self._watermark_manager.update_watermarks(
          completed_bundle, unprocessed_bundle, result.transform, completed_timers,
          committed_bundles, result.watermark_hold)

      self._metrics.commit_logical(completed_bundle,
                                   result.logical_metric_updates)

      print 'HANDLE RESULT COMMITED', committed_bundles, result.uncommitted_output_bundles, result.unprocessed_bundle
      # If the result is for a view, update side inputs container.
      if (result.uncommitted_output_bundles
          and result.uncommitted_output_bundles[0].pcollection
          in self._pcollection_to_views):
        print '***** HI I AM A VIEW', result.uncommitted_output_bundles[0].pcollection, committed_bundles
        for view in self._pcollection_to_views[
            result.uncommitted_output_bundles[0].pcollection]:
          for committed_bundle in committed_bundles:
            print 'ADD TO VIEW', committed_bundle.get_elements_iterable(make_copy=True)
            # side_input must be materialized.
            self._side_inputs_container.add_values(
                view,
                committed_bundle.get_elements_iterable(make_copy=True))
          completed = True
          print 'STUFF', self._transform_keyed_states[result.transform]
          watermarks = self._watermark_manager.get_watermarks(result.transform)
          if watermarks._pending:
            completed = False
          print 'WATERMARKS', watermarks, watermarks._pending
          for key in self._transform_keyed_states[result.transform]:
            if (self.get_execution_context(result.transform, key).watermarks.input_watermark
                < WatermarkManager.WATERMARK_POS_INF or
                self.get_execution_context(result.transform, key).watermarks._pending):
              print '&&& COMPLETED FALSE', result.transform, key, self.get_execution_context(result.transform, key).watermarks._pending
              completed = False
          if completed:
            print '***** HI I AM COMPLETED', result.uncommitted_output_bundles[0].pcollection
            self._pending_unblocked_tasks.extend(
                self._side_inputs_container.finalize_value_and_get_tasks(view))
          else:
            print '***** HI I AM *NOT* COMPLETED', result.uncommitted_output_bundles[0].pcollection

      if result.counters:
        for counter in result.counters:
          merged_counter = self._counter_factory.get_counter(
              counter.name, counter.combine_fn)
          merged_counter.accumulator.merge([counter.accumulator])

      self._legacy_existing_state[result.transform] = result.legacy_state
      if not result.state:
        if completed_bundle.key in self._transform_keyed_states[result.transform]:
          del self._transform_keyed_states[result.transform][completed_bundle.key]
      else:
        self._transform_keyed_states[result.transform][completed_bundle.key] = result.state
      return committed_bundles

  def get_aggregator_values(self, aggregator_or_name):
    return self._counter_factory.get_aggregator_values(aggregator_or_name)

  def schedule_pending_unblocked_tasks(self, executor_service):
    if self._pending_unblocked_tasks:
      with self._lock:
        for task in self._pending_unblocked_tasks:
          executor_service.submit(task)
        self._pending_unblocked_tasks = []

  def _commit_bundles(self, uncommitted_output_bundles, unprocessed_bundle):
    """Commits bundles and returns a immutable set of committed bundles."""
    for in_progress_bundle in uncommitted_output_bundles:
      producing_applied_ptransform = in_progress_bundle.pcollection.producer
      watermarks = self._watermark_manager.get_watermarks(
          producing_applied_ptransform)
      in_progress_bundle.commit(watermarks.synchronized_processing_output_time)
    if unprocessed_bundle:
      unprocessed_bundle.commit(None)
    return tuple(uncommitted_output_bundles), unprocessed_bundle

  def get_execution_context(self, applied_ptransform, key):
    return _ExecutionContext(
        applied_ptransform,
        self._watermark_manager.get_watermarks(applied_ptransform),
        self._transform_keyed_states[applied_ptransform].get(key),
        self._legacy_existing_state.get(applied_ptransform),
        key)

  def create_bundle(self, output_pcollection):
    """Create an uncommitted bundle for the specified PCollection."""
    return self._bundle_factory.create_bundle(output_pcollection)

  def create_keyed_bundle(self, output_pcollection, key):
    """Create an uncommitted bundle for the specified PCollection."""
    return self._bundle_factory.create_keyed_bundle(output_pcollection, key)

  def create_empty_committed_bundle(self, output_pcollection):
    """Create empty bundle useful for triggering evaluation."""
    return self._bundle_factory.create_empty_committed_bundle(
        output_pcollection)

  def extract_fired_timers(self):
    return self._watermark_manager.extract_fired_timers()

  def is_done(self, transform=None):
    """Checks completion of a step or the pipeline.

    Args:
      transform: AppliedPTransform to check for completion.

    Returns:
      True if the step will not produce additional output. If transform is None
      returns true if all steps are done.
    """
    if transform:
      return self._is_transform_done(transform)

    for applied_ptransform in self._step_names:
      if not self._is_transform_done(applied_ptransform):
        return False
    return True

  def _is_transform_done(self, transform):
    tw = self._watermark_manager.get_watermarks(transform)
    print '[!!] TRANSFORM_DONE?', transform, 'IWM', tw.input_watermark,'OWM', tw.output_watermark, tw.output_watermark == WatermarkManager.WATERMARK_POS_INF, tw._pending
    return tw.output_watermark == WatermarkManager.WATERMARK_POS_INF

  def get_value_or_schedule_after_output(self, side_input, task):
    assert isinstance(task, TransformExecutor)
    return self._side_inputs_container.get_value_or_schedule_after_output(
        side_input, task)
