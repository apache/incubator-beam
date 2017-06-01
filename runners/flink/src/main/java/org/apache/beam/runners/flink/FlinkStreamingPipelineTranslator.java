/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.runners.flink;

import java.util.Map;
import org.apache.beam.runners.core.construction.PTransformReplacements;
import org.apache.beam.runners.core.construction.ReplacementOutputs;
import org.apache.beam.runners.core.construction.SplittableParDo;
import org.apache.beam.runners.core.construction.UnconsumedReads;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.runners.AppliedPTransform;
import org.apache.beam.sdk.runners.PTransformOverrideFactory;
import org.apache.beam.sdk.runners.TransformHierarchy;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo.MultiOutput;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionTuple;
import org.apache.beam.sdk.values.PValue;
import org.apache.beam.sdk.values.TupleTag;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This is a {@link FlinkPipelineTranslator} for streaming jobs. Its role is to translate
 * the user-provided {@link org.apache.beam.sdk.values.PCollection}-based job into a
 * {@link org.apache.flink.streaming.api.datastream.DataStream} one.
 *
 */
class FlinkStreamingPipelineTranslator extends FlinkPipelineTranslator {

  private static final Logger LOG = LoggerFactory.getLogger(FlinkStreamingPipelineTranslator.class);

  /** The necessary context in the case of a straming job. */
  private final FlinkStreamingTranslationContext streamingContext;

  private int depth = 0;

  private FlinkRunner flinkRunner;

  private final FlinkPipelineOptions options;

  public FlinkStreamingPipelineTranslator(
      FlinkRunner flinkRunner,
      StreamExecutionEnvironment env,
      PipelineOptions options,
      PipelineTranslationOptimizer optimizer) {
    this.streamingContext = new FlinkStreamingTranslationContext(env, options, optimizer);
    this.flinkRunner = flinkRunner;
    this.options = options.as(FlinkPipelineOptions.class);
  }

  @Override
  public void translate(Pipeline pipeline) {
    // Ensure all outputs of all reads are consumed.
    UnconsumedReads.ensureAllReadsConsumed(pipeline);
    super.translate(pipeline);
  }

  // --------------------------------------------------------------------------------------------
  //  Pipeline Visitor Methods
  // --------------------------------------------------------------------------------------------

  @Override
  public CompositeBehavior enterCompositeTransform(TransformHierarchy.Node node) {
    LOG.info("{} enterCompositeTransform- {}", genSpaces(this.depth), node.getFullName());
    this.depth++;

    PTransform<?, ?> transform = node.getTransform();
    if (transform != null) {
      StreamTransformTranslator<?> translator =
          FlinkStreamingTransformTranslators.getTranslator(transform);

      if (translator != null && applyCanTranslate(transform, node, translator)) {
        applyStreamingTransform(transform, node, translator);
        LOG.info("{} translated- {}", genSpaces(this.depth), node.getFullName());
        return CompositeBehavior.DO_NOT_ENTER_TRANSFORM;
      }
    }
    return CompositeBehavior.ENTER_TRANSFORM;
  }

  @Override
  public void leaveCompositeTransform(TransformHierarchy.Node node) {
    this.depth--;
    LOG.info("{} leaveCompositeTransform- {}", genSpaces(this.depth), node.getFullName());
  }

  @Override
  public void visitPrimitiveTransform(TransformHierarchy.Node node) {
    LOG.info("{} visitPrimitiveTransform- {}", genSpaces(this.depth), node.getFullName());
    // get the transformation corresponding to hte node we are
    // currently visiting and translate it into its Flink alternative.

    PTransform<?, ?> transform = node.getTransform();
    StreamTransformTranslator<?> translator =
        FlinkStreamingTransformTranslators.getTranslator(transform);

    if (translator == null || !applyCanTranslate(transform, node, translator)) {
      LOG.info(node.getTransform().getClass().toString());
      throw new UnsupportedOperationException(
          "The transform " + transform + " is currently not supported.");
    }
    applyStreamingTransform(transform, node, translator);
  }

  @Override
  public void visitValue(PValue value, TransformHierarchy.Node producer) {
    // do nothing here
  }

  private <T extends PTransform<?, ?>> void applyStreamingTransform(
      PTransform<?, ?> transform,
      TransformHierarchy.Node node,
      StreamTransformTranslator<?> translator) {

    @SuppressWarnings("unchecked")
    T typedTransform = (T) transform;

    @SuppressWarnings("unchecked")
    StreamTransformTranslator<T> typedTranslator = (StreamTransformTranslator<T>) translator;

    // create the applied PTransform on the streamingContext
    streamingContext.setCurrentTransform(node.toAppliedPTransform(getPipeline()));

    // Override the streamingContext parallelism if perTransformParallelism is found.
    Integer perTransformParallelism =
        streamingContext.getOptimizer().getPerTransformParallelism(node.getFullName());
    if (perTransformParallelism != null) {
      streamingContext.getExecutionEnvironment().setParallelism(perTransformParallelism);
    }

    typedTranslator.translateNode(typedTransform, streamingContext);
  }

  private <T extends PTransform<?, ?>> boolean applyCanTranslate(
      PTransform<?, ?> transform,
      TransformHierarchy.Node node,
      StreamTransformTranslator<?> translator) {

    @SuppressWarnings("unchecked")
    T typedTransform = (T) transform;

    @SuppressWarnings("unchecked")
    StreamTransformTranslator<T> typedTranslator = (StreamTransformTranslator<T>) translator;

    streamingContext.setCurrentTransform(node.toAppliedPTransform(getPipeline()));

    return typedTranslator.canTranslate(typedTransform, streamingContext);
  }

  /**
   * The interface that every Flink translator of a Beam operator should implement.
   * This interface is for <b>streaming</b> jobs. For examples of such translators see
   * {@link FlinkStreamingTransformTranslators}.
   */
  abstract static class StreamTransformTranslator<T extends PTransform> {

    /**
     * Translate the given transform.
     */
    abstract void translateNode(T transform, FlinkStreamingTranslationContext context);

    /**
     * Returns true iff this translator can translate the given transform.
     */
    boolean canTranslate(T transform, FlinkStreamingTranslationContext context) {
      return true;
    }
  }

  /**
   * A {@link PTransformOverrideFactory} that overrides a <a
   * href="https://s.apache.org/splittable-do-fn">Splittable DoFn</a> with {@link SplittableParDo}.
   */
  static class SplittableParDoOverrideFactory<InputT, OutputT>
      implements PTransformOverrideFactory<
          PCollection<InputT>, PCollectionTuple, MultiOutput<InputT, OutputT>> {
    @Override
    public PTransformReplacement<PCollection<InputT>, PCollectionTuple>
        getReplacementTransform(
            AppliedPTransform<
                    PCollection<InputT>, PCollectionTuple, MultiOutput<InputT, OutputT>>
                transform) {
      return PTransformReplacement.of(
          PTransformReplacements.getSingletonMainInput(transform),
          (SplittableParDo<InputT, OutputT, ?>) SplittableParDo.forAppliedParDo(transform));
    }

    @Override
    public Map<PValue, ReplacementOutput> mapOutputs(
        Map<TupleTag<?>, PValue> outputs, PCollectionTuple newOutput) {
      return ReplacementOutputs.tagged(outputs, newOutput);
    }
  }
}
