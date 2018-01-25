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

"""DirectRunner, executing on the local machine.

The DirectRunner is a runner implementation that executes the entire
graph of transformations belonging to a pipeline on the local machine.
"""

from __future__ import absolute_import

import collections
import logging

from google.protobuf import wrappers_pb2

import apache_beam as beam
from apache_beam import typehints
from apache_beam.coders import StrUtf8Coder
from apache_beam.metrics.execution import MetricsEnvironment
from apache_beam.options.pipeline_options import DirectOptions
from apache_beam.options.pipeline_options import StandardOptions
from apache_beam.options.value_provider import RuntimeValueProvider
from apache_beam.pvalue import PCollection
from apache_beam.runners.direct.bundle_factory import BundleFactory
from apache_beam.runners.direct.clock import RealClock
from apache_beam.runners.direct.clock import TestClock
from apache_beam.runners.runner import PipelineResult
from apache_beam.runners.runner import PipelineRunner
from apache_beam.runners.runner import PipelineState
from apache_beam.runners.runner import PValueCache
from apache_beam.transforms.core import _GroupAlsoByWindow
from apache_beam.transforms.core import _GroupByKeyOnly
from apache_beam.transforms.ptransform import PTransform

__all__ = ['DirectRunner']


# Type variables.
K = typehints.TypeVariable('K')
V = typehints.TypeVariable('V')


@typehints.with_input_types(typehints.KV[K, V])
@typehints.with_output_types(typehints.KV[K, typehints.Iterable[V]])
class _StreamingGroupByKeyOnly(_GroupByKeyOnly):
  """Streaming GroupByKeyOnly placeholder for overriding in DirectRunner."""
  urn = "direct_runner:streaming_gbko:v0.1"

  # These are needed due to apply overloads.
  def to_runner_api_parameter(self, unused_context):
    return _StreamingGroupByKeyOnly.urn, None

  @PTransform.register_urn(urn, None)
  def from_runner_api_parameter(unused_payload, unused_context):
    return _StreamingGroupByKeyOnly()


@typehints.with_input_types(typehints.KV[K, typehints.Iterable[V]])
@typehints.with_output_types(typehints.KV[K, typehints.Iterable[V]])
class _StreamingGroupAlsoByWindow(_GroupAlsoByWindow):
  """Streaming GroupAlsoByWindow placeholder for overriding in DirectRunner."""
  urn = "direct_runner:streaming_gabw:v0.1"

  # These are needed due to apply overloads.
  def to_runner_api_parameter(self, context):
    return (
        _StreamingGroupAlsoByWindow.urn,
        wrappers_pb2.BytesValue(value=context.windowing_strategies.get_id(
            self.windowing)))

  @PTransform.register_urn(urn, wrappers_pb2.BytesValue)
  def from_runner_api_parameter(payload, context):
    return _StreamingGroupAlsoByWindow(
        context.windowing_strategies.get_by_id(payload.value))

class _DirectReadStringsFromPubSub(PTransform):
  def __init__(self, source):
    self._source = source

  def _infer_output_coder(self, unused_input_type=None,
                          unused_input_coder=None):
    return coders.StrUtf8Coder()

  def get_windowing(self, inputs):
    return beam.Windowing(beam.window.GlobalWindows())

  def expand(self, pvalue):
    # This is handled as a native transform.
    return PCollection(self.pipeline)



def _get_transform_overrides(pipeline_options):
  # A list of PTransformOverride objects to be applied before running a pipeline
  # using DirectRunner.
  # Currently this only works for overrides where the input and output types do
  # not change.
  # For internal use only; no backwards-compatibility guarantees.

  # Importing following locally to avoid a circular dependency.
  from apache_beam.runners.sdf_common import SplittableParDoOverride
  from apache_beam.runners.direct.sdf_direct_runner import ProcessKeyedElementsViaKeyedWorkItemsOverride
  from apache_beam.pipeline import PTransformOverride

  from apache_beam.transforms.core import CombinePerKey
  from apache_beam.transforms.core import _GroupByKeyOnly

  # try:.
  # TODO: deal with import error
  from apache_beam.io.gcp.pubsub import ReadStringsFromPubSub
  from apache_beam.io.gcp.pubsub import WriteStringsToPubSub
  # except ImportError:
    # pass

  class CombinePerKeyOverride(PTransformOverride):
    def get_matcher(self):
      def _matcher(applied_ptransform):
        if isinstance(applied_ptransform.transform, CombinePerKey):
          return True
      return _matcher

    def get_replacement_transform(self, transform):
      # TODO: Move imports to top. Pipeline <-> Runner dependency cause problems
      # with resolving imports when they are at top.
      # pylint: disable=wrong-import-position
      from apache_beam.runners.direct.helper_transforms import LiftedCombinePerKey
      try:
        print 'YES REPLACE', transform, transform.fn, transform.args, transform.kwargs
        a= LiftedCombinePerKey(
            transform.fn, transform.args, transform.kwargs)
        print 'REPL', a
        return a
      except NotImplementedError:
        print 'NO REPLACE'
        return transform

  class StreamingGroupByKeyOverride(PTransformOverride):
    def get_matcher(self):
      def _matcher(applied_ptransform):
        # Note: we match the exact class, since we replace it with a subclass.
        return applied_ptransform.transform.__class__ == _GroupByKeyOnly
      return _matcher

    def get_replacement_transform(self, transform):
      # Use specialized streaming implementation, if requested.
      type_hints = transform.get_type_hints()
      transform = (_StreamingGroupByKeyOnly()
                      .with_input_types(*type_hints.input_types[0])
                      .with_output_types(*type_hints.output_types[0]))
      print 'a', transform
      return transform

  class StreamingGroupAlsoByWindowOverride(PTransformOverride):
    def get_matcher(self):
      def _matcher(applied_ptransform):
        # Note: we match the exact class, since we replace it with a subclass.
        return applied_ptransform.transform.__class__ == _GroupAlsoByWindow
      return _matcher

    def get_replacement_transform(self, transform):
      # Use specialized streaming implementation, if requested.
      type_hints = transform.get_type_hints()
      print 'TH', type_hints
      transform = (_StreamingGroupAlsoByWindow(transform.windowing)
                      .with_input_types(*type_hints.input_types[0])
                      .with_output_types(*type_hints.output_types[0]))
      print 'a', transform
      return transform

  class ReadStringsFromPubSubOverride(PTransformOverride):
    def get_matcher(self):
      def _matcher(applied_ptransform):
        print applied_ptransform.transform.__class__
        return applied_ptransform.transform.__class__.__name__ == 'ReadStringsFromPubSub'
      return _matcher

    def get_replacement_transform(self, transform):
      try:
        from google.cloud import pubsub as unused_pubsub
      except ImportError:
        raise ImportError('Google Cloud PubSub not available, please install '
                          'apache_beam[gcp]')
      print 'TTTT', transform
      if not pipeline_options.view_as(StandardOptions).streaming:
        raise Exception('PubSub I/O is only available in streaming mode (use the --streaming flag).')
      return _DirectReadStringsFromPubSub(transform._source)

  class WriteStringsToPubSubOverride(PTransformOverride):
    def get_matcher(self):
      def _matcher(applied_ptransform):
        print applied_ptransform.transform.__class__
        return applied_ptransform.transform.__class__.__name__ == 'WriteStringsToPubSub'
      return _matcher

    def get_replacement_transform(self, transform):
      try:
        from google.cloud import pubsub
      except ImportError:
        raise ImportError('Google Cloud PubSub not available, please install '
                          'apache_beam[gcp]')
      print 'TTTT', transform


      class _DirectWriteToPubSub(beam.DoFn):
        _topic = None

        def __init__(self, project, topic_name):
          self.project = project
          self.topic_name = topic_name

        def start_bundle(self):
          if self._topic is None:
            self._topic = pubsub.Client(project=self.project).topic(
                self.topic_name)
          self._buffer = []

        def process(self, elem):
          self._buffer.append(elem.encode('utf-8'))
          if len(self._buffer) >= 100:
            self._flush()

        def finish_bundle(self):
          self._flush()

        def _flush(self):
          if self._buffer:
            with self._topic.batch() as batch:
              for datum in self._buffer:
                batch.publish(datum)
            self._buffer = []

      if not pipeline_options.view_as(StandardOptions).streaming:
        raise Exception('PubSub I/O is only available in streaming mode (use the --streaming flag).')
      project = transform._sink.project
      topic_name = transform._sink.topic_name
      return beam.ParDo(_DirectWriteToPubSub(project, topic_name))

  overrides = [SplittableParDoOverride(),
          ProcessKeyedElementsViaKeyedWorkItemsOverride(),
          CombinePerKeyOverride(),
          ReadStringsFromPubSubOverride(),
          WriteStringsToPubSubOverride(),
          ]

  if pipeline_options.view_as(StandardOptions).streaming:
    overrides.append(StreamingGroupByKeyOverride())
    overrides.append(StreamingGroupAlsoByWindowOverride())

  return overrides



class DirectRunner(PipelineRunner):
  def __init__(self):
    self._runner = None

  def run_pipeline(self, pipeline):
    self._runner = BundleBasedDirectRunner()
    # from apache_beam.runners.portability.fn_api_runner import FnApiRunner
    # self._runner = FnApiRunner()
    return self._runner.run_pipeline(pipeline)

class BundleBasedDirectRunner(PipelineRunner):
  """Executes a single pipeline on the local machine."""

  # def __init__(self):
    # self._cache = None

  # def apply_CombinePerKey(self, transform, pcoll):
  #   raise Exception
  #   # TODO: Move imports to top. Pipeline <-> Runner dependency cause problems
  #   # with resolving imports when they are at top.
  #   # pylint: disable=wrong-import-position
  #   from apache_beam.runners.direct.helper_transforms import LiftedCombinePerKey
  #   try:
  #     return pcoll | LiftedCombinePerKey(
  #         transform.fn, transform.args, transform.kwargs)
  #   except NotImplementedError:
  #     return transform.expand(pcoll)

  # def apply_TestStream(self, transform, pcoll):
  #   self._use_test_clock = True  # use TestClock() for testing
  #   return transform.expand(pcoll)

  # def apply__GroupByKeyOnly(self, transform, pcoll):
  #   if (transform.__class__ == _GroupByKeyOnly and
  #       pcoll.pipeline._options.view_as(StandardOptions).streaming):
  #     # Use specialized streaming implementation, if requested.
  #     type_hints = transform.get_type_hints()
  #     return pcoll | (_StreamingGroupByKeyOnly()
  #                     .with_input_types(*type_hints.input_types[0])
  #                     .with_output_types(*type_hints.output_types[0]))
  #   return transform.expand(pcoll)

  # def apply__GroupAlsoByWindow(self, transform, pcoll):
  #   if (transform.__class__ == _GroupAlsoByWindow and
  #       pcoll.pipeline._options.view_as(StandardOptions).streaming):
  #     # Use specialized streaming implementation, if requested.
  #     type_hints = transform.get_type_hints()
  #     return pcoll | (_StreamingGroupAlsoByWindow(transform.windowing)
  #                     .with_input_types(*type_hints.input_types[0])
  #                     .with_output_types(*type_hints.output_types[0]))
  #   return transform.expand(pcoll)

  # def apply_ReadStringsFromPubSub(self, transform, pcoll):
  #   try:
  #     from google.cloud import pubsub as unused_pubsub
  #   except ImportError:
  #     raise ImportError('Google Cloud PubSub not available, please install '
  #                       'apache_beam[gcp]')
  #   # Execute this as a native transform.
  #   output = PCollection(pcoll.pipeline)
  #   output.element_type = unicode
  #   return output

  # def apply_WriteStringsToPubSub(self, transform, pcoll):
  #   try:
  #     from google.cloud import pubsub
  #   except ImportError:
  #     raise ImportError('Google Cloud PubSub not available, please install '
  #                       'apache_beam[gcp]')
  #   project = transform._sink.project
  #   topic_name = transform._sink.topic_name


  #   output = pcoll | beam.ParDo(DirectWriteToPubSub(project, topic_name))
  #   output.element_type = unicode
  #   return output

  def run_pipeline(self, pipeline):
    """Execute the entire pipeline and returns an DirectPipelineResult."""

    # Performing configured PTransform overrides.
    output_map = pipeline.replace_all(_get_transform_overrides(pipeline._options))
    # if self._cache:
    #   self._cache.register_replacement_outputs(output_map)

    # TODO: Move imports to top. Pipeline <-> Runner dependency cause problems
    # with resolving imports when they are at top.
    # pylint: disable=wrong-import-position
    from apache_beam.runners.direct.consumer_tracking_pipeline_visitor import \
      ConsumerTrackingPipelineVisitor
    from apache_beam.runners.direct.evaluation_context import EvaluationContext
    from apache_beam.runners.direct.executor import Executor
    from apache_beam.runners.direct.transform_evaluator import \
      TransformEvaluatorRegistry
    from apache_beam.testing.test_stream import TestStream

    MetricsEnvironment.set_metrics_supported(True)
    logging.info('Running pipeline with DirectRunner.')
    self.consumer_tracking_visitor = ConsumerTrackingPipelineVisitor()
    pipeline.visit(self.consumer_tracking_visitor)

    use_test_clock = False
    for applied_ptransform in self.consumer_tracking_visitor.all_transforms:
      if isinstance(applied_ptransform.transform, TestStream):
        print 'USE TEST'
        use_test_clock = True
    clock = TestClock() if use_test_clock else RealClock()

    evaluation_context = EvaluationContext(
        pipeline._options,
        BundleFactory(stacked=pipeline._options.view_as(DirectOptions)
                      .direct_runner_use_stacked_bundle),
        self.consumer_tracking_visitor.root_transforms,
        self.consumer_tracking_visitor.value_to_consumers,
        self.consumer_tracking_visitor.step_names,
        self.consumer_tracking_visitor.views,
        clock)

    # evaluation_context.use_pvalue_cache(self._cache)

    executor = Executor(self.consumer_tracking_visitor.value_to_consumers,
                        TransformEvaluatorRegistry(evaluation_context),
                        evaluation_context)
    # DirectRunner does not support injecting
    # PipelineOptions values at runtime
    RuntimeValueProvider.set_runtime_options({})
    # Start the executor. This is a non-blocking call, it will start the
    # execution in background threads and return.
    executor.start(self.consumer_tracking_visitor.root_transforms)
    result = DirectPipelineResult(executor, evaluation_context)

    # if self._cache:
    #   # We are running in eager mode, block until the pipeline execution
    #   # completes in order to have full results in the cache.
    #   result.wait_until_finish()
    #   self._cache.finalize()

    return result

  # @property
  # def cache(self):
  #   if not self._cache:
  #     self._cache = BufferingInMemoryCache()
  #   return self._cache.pvalue_cache

DirectRunner = BundleBasedDirectRunner

# class BufferingInMemoryCache(object):
#   """PValueCache wrapper for buffering bundles until a PValue is fully computed.

#   BufferingInMemoryCache keeps an in memory cache of
#   (applied_ptransform, tag) tuples. It accepts appending to existing cache
#   entries until it is finalized. finalize() will make all the existing cached
#   entries visible to the underyling PValueCache in their entirety, clean the in
#   memory cache and stop accepting new cache entries.
#   """

#   def __init__(self):
#     self._cache = collections.defaultdict(list)
#     self._pvalue_cache = PValueCache()
#     self._finalized = False

#   @property
#   def pvalue_cache(self):
#     return self._pvalue_cache

#   def register_replacement_outputs(self, output_map):
#     """Register the given map of original to replacement PCollections.

#     Args:
#       output_map: A map from original pipeline PCollection to replacement
#       pipeline PCollections, resulting from the application of
#       PTransformOverrides in pipeline.Pipeline.replace_all().
#     """
#     self._pvalue_cache.register_replacement_outputs(output_map)

#   def append(self, applied_ptransform, tag, elements):
#     assert not self._finalized
#     assert elements is not None
#     self._cache[(applied_ptransform, tag)].extend(elements)

#   def finalize(self):
#     """Make buffered cache elements visible to the underlying PValueCache."""
#     assert not self._finalized
#     for key, value in self._cache.iteritems():
#       applied_ptransform, tag = key
#       self._pvalue_cache.cache_output(applied_ptransform, tag, value)
    # self._cache = None


class DirectPipelineResult(PipelineResult):
  """A DirectPipelineResult provides access to info about a pipeline."""

  def __init__(self, executor, evaluation_context):
    super(DirectPipelineResult, self).__init__(PipelineState.RUNNING)
    self._executor = executor
    self._evaluation_context = evaluation_context

  def __del__(self):
    if self._state == PipelineState.RUNNING:
      logging.warning(
          'The DirectPipelineResult is being garbage-collected while the '
          'DirectRunner is still running the corresponding pipeline. This may '
          'lead to incomplete execution of the pipeline if the main thread '
          'exits before pipeline completion. Consider using '
          'result.wait_until_finish() to wait for completion of pipeline '
          'execution.')

  def _is_in_terminal_state(self):
    return self._state is not PipelineState.RUNNING

  def wait_until_finish(self, duration=None):
    if not self._is_in_terminal_state():
      if duration:
        raise NotImplementedError(
            'DirectRunner does not support duration argument.')
      try:
        self._executor.await_completion()
        self._state = PipelineState.DONE
      except:  # pylint: disable=broad-except
        self._state = PipelineState.FAILED
        raise
    return self._state

  def aggregated_values(self, aggregator_or_name):
    return self._evaluation_context.get_aggregator_values(aggregator_or_name)

  def metrics(self):
    return self._evaluation_context.metrics()


class EagerRunner(DirectRunner):

  is_eager = True
