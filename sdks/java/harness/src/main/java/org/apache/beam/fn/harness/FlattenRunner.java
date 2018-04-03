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

import static com.google.common.collect.Iterables.getOnlyElement;

import com.google.auto.service.AutoService;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;
import java.io.IOException;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.apache.beam.fn.harness.data.BeamFnDataClient;
import org.apache.beam.fn.harness.data.MultiplexingFnDataReceiver;
import org.apache.beam.fn.harness.fn.ThrowingRunnable;
import org.apache.beam.fn.harness.state.BeamFnStateClient;
import org.apache.beam.model.pipeline.v1.RunnerApi;
import org.apache.beam.model.pipeline.v1.RunnerApi.Coder;
import org.apache.beam.model.pipeline.v1.RunnerApi.PCollection;
import org.apache.beam.runners.core.construction.PTransformTranslation;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.util.WindowedValue;
import org.apache.beam.util.fn.data.FnDataReceiver;

/** Executes flatten PTransforms. */
public class FlattenRunner<InputT>{
  /** A registrar which provides a factory to handle flatten PTransforms. */
  @AutoService(PTransformRunnerFactory.Registrar.class)
  public static class Registrar implements
      PTransformRunnerFactory.Registrar {

    @Override
    public Map<String, PTransformRunnerFactory> getPTransformRunnerFactories() {
      return ImmutableMap.of(
          PTransformTranslation.FLATTEN_TRANSFORM_URN, new Factory());
    }
  }

  /** A factory for {@link FlattenRunner}. */
  static class Factory<InputT> implements
      PTransformRunnerFactory<FlattenRunner<InputT>> {
    @Override
    public FlattenRunner<InputT> createRunnerForPTransform(
        PipelineOptions pipelineOptions,
        BeamFnDataClient beamFnDataClient,
        BeamFnStateClient beamFnStateClient,
        String pTransformId,
        RunnerApi.PTransform pTransform,
        Supplier<String> processBundleInstructionId,
        Map<String, PCollection> pCollections,
        Map<String, Coder> coders,
        Map<String, RunnerApi.WindowingStrategy> windowingStrategies,
        Multimap<String, FnDataReceiver<WindowedValue<?>>> pCollectionIdsToConsumers,
        Consumer<ThrowingRunnable> addStartFunction,
        Consumer<ThrowingRunnable> addFinishFunction)
        throws IOException {

      // Give each input a MultiplexingFnDataReceiver to all outputs of the flatten.
      ImmutableSet.Builder<FnDataReceiver<WindowedValue<InputT>>> consumersBuilder =
          new ImmutableSet.Builder<>();
      String output = getOnlyElement(pTransform.getOutputsMap().values());
      consumersBuilder.addAll((Iterable) pCollectionIdsToConsumers.get(output));

      FnDataReceiver<WindowedValue<InputT>> receiver =
          MultiplexingFnDataReceiver.forConsumers(consumersBuilder.build());
      FlattenRunner<InputT> runner = new FlattenRunner<>();

      for (String pCollectionId : pTransform.getInputsMap().values()) {
        pCollectionIdsToConsumers.put(pCollectionId, (FnDataReceiver) receiver);
      }

      return runner;
    }
  }
}
