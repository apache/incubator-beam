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
package org.apache.beam.fn.harness;

import static org.apache.beam.vendor.guava.v26_0_jre.com.google.common.collect.Iterables.getOnlyElement;

import com.google.auto.service.AutoService;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.apache.beam.fn.harness.control.BundleSplitListener;
import org.apache.beam.fn.harness.data.BeamFnDataClient;
import org.apache.beam.fn.harness.data.BeamFnTimerClient;
import org.apache.beam.fn.harness.data.PCollectionConsumerRegistry;
import org.apache.beam.fn.harness.data.PTransformFunctionRegistry;
import org.apache.beam.fn.harness.state.BeamFnStateClient;
import org.apache.beam.model.fnexecution.v1.BeamFnApi.Elements.Data;
import org.apache.beam.model.pipeline.v1.RunnerApi;
import org.apache.beam.model.pipeline.v1.RunnerApi.Coder;
import org.apache.beam.model.pipeline.v1.RunnerApi.Components;
import org.apache.beam.model.pipeline.v1.RunnerApi.PCollection;
import org.apache.beam.model.pipeline.v1.RunnerApi.PTransform;
import org.apache.beam.model.pipeline.v1.RunnerApi.WindowingStrategy;
import org.apache.beam.runners.core.construction.PTransformTranslation;
import org.apache.beam.runners.core.construction.RehydratedComponents;
import org.apache.beam.sdk.fn.data.FnDataReceiver;
import org.apache.beam.sdk.function.ThrowingRunnable;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.transforms.DoFn.BundleFinalizer;
import org.apache.beam.sdk.util.WindowedValue;
import org.apache.beam.sdk.util.WindowedValue.WindowedValueCoder;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.collect.ImmutableMap;

/** Executes flatten PTransforms. */
@SuppressWarnings({
  "rawtypes" // TODO(https://issues.apache.org/jira/browse/BEAM-10556)
})
public class FlattenRunner<InputT> {
  /** A registrar which provides a factory to handle flatten PTransforms. */
  @AutoService(PTransformRunnerFactory.Registrar.class)
  public static class Registrar implements PTransformRunnerFactory.Registrar {

    @Override
    public Map<String, PTransformRunnerFactory> getPTransformRunnerFactories() {
      return ImmutableMap.of(PTransformTranslation.FLATTEN_TRANSFORM_URN, new Factory());
    }
  }

  /** A factory for {@link FlattenRunner}. */
  static class Factory<InputT> implements PTransformRunnerFactory<FlattenRunner<InputT>> {
    @Override
    public FlattenRunner<InputT> createRunnerForPTransform(
        PipelineOptions pipelineOptions,
        BeamFnDataClient beamFnDataClient,
        BeamFnStateClient beamFnStateClient,
        BeamFnTimerClient beamFnTimerClient,
        String pTransformId,
        RunnerApi.PTransform pTransform,
        Supplier<String> processBundleInstructionId,
        Map<String, PCollection> pCollections,
        Map<String, Coder> coders,
        Map<String, RunnerApi.WindowingStrategy> windowingStrategies,
        PCollectionConsumerRegistry pCollectionConsumerRegistry,
        PTransformFunctionRegistry startFunctionRegistry,
        PTransformFunctionRegistry finishFunctionRegistry,
        Consumer<ThrowingRunnable> addResetFunction,
        Consumer<ThrowingRunnable> tearDownFunctions,
        Consumer<ProgressRequestCallback> addProgressRequestCallback,
        BundleSplitListener splitListener,
        BundleFinalizer bundleFinalizer)
        throws IOException {

      // Give each input a MultiplexingFnDataReceiver to all outputs of the flatten.
      String output = getOnlyElement(pTransform.getOutputsMap().values());
      FnDataReceiver<WindowedValue<?>> receiver =
          pCollectionConsumerRegistry.getMultiplexingConsumer(output);

      RehydratedComponents components =
          RehydratedComponents.forComponents(Components.newBuilder().putAllCoders(coders).build());

      FlattenRunner<InputT> runner = new FlattenRunner<>();
      for (String pCollectionId : pTransform.getInputsMap().values()) {
        pCollectionConsumerRegistry.register(
            pCollectionId,
            pTransformId,
            (FnDataReceiver) receiver,
            getValueCoder(components, pCollections, pCollectionId));
      }

      return runner;
    }

    @Override
    public FlattenRunner<InputT> createRunnerForDataPTransform(
        PipelineOptions pipelineOptions,
        BeamFnStateClient beamFnStateClient,
        String pTransformId,
        PTransform pTransform,
        Supplier<String> processBundleInstructionId,
        Map<String, PCollection> pCollections,
        Map<String, Coder> coders,
        Map<String, WindowingStrategy> windowingStrategies,
        PCollectionConsumerRegistry pCollectionConsumerRegistry,
        PTransformFunctionRegistry startFunctionRegistry,
        PTransformFunctionRegistry finishFunctionRegistry,
        Consumer<ThrowingRunnable> addResetFunction,
        Consumer<ThrowingRunnable> addTearDownFunction,
        Consumer<ProgressRequestCallback> addProgressRequestCallback,
        BundleFinalizer bundleFinalizer,
        Supplier<List<Data>> inputSupplier,
        Consumer<Data> outputConsumer)
        throws IOException {
      return createRunnerForPTransform(
          pipelineOptions,
          null,
          beamFnStateClient,
          null,
          pTransformId,
          pTransform,
          processBundleInstructionId,
          pCollections,
          coders,
          windowingStrategies,
          pCollectionConsumerRegistry,
          startFunctionRegistry,
          finishFunctionRegistry,
          addResetFunction,
          addTearDownFunction,
          addProgressRequestCallback,
          null,
          bundleFinalizer);
    }

    private org.apache.beam.sdk.coders.Coder<?> getValueCoder(
        RehydratedComponents components,
        Map<String, PCollection> pCollections,
        String pCollectionId)
        throws IOException {
      if (!pCollections.containsKey(pCollectionId)) {
        throw new IllegalArgumentException(
            String.format("Missing PCollection for id: %s", pCollectionId));
      }
      org.apache.beam.sdk.coders.Coder<?> coder =
          components.getCoder(pCollections.get(pCollectionId).getCoderId());
      if (coder instanceof WindowedValueCoder) {
        coder = ((WindowedValueCoder<InputT>) coder).getValueCoder();
      }
      return coder;
    }
  }
}
