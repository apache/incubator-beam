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
package org.apache.beam.fn.harness.data;

import static org.mockito.Matchers.anyObject;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.powermock.api.mockito.PowerMockito.mockStatic;

import java.io.Closeable;
import org.apache.beam.runners.core.metrics.MetricsContainerImpl;
import org.apache.beam.runners.core.metrics.MetricsContainerStepMap;
import org.apache.beam.sdk.fn.function.ThrowingRunnable;
import org.apache.beam.sdk.metrics.MetricsContainer;
import org.apache.beam.sdk.metrics.MetricsEnvironment;
import org.apache.beam.vendor.grpc.v1p13p1.io.netty.util.internal.MathUtil;
import org.hamcrest.Matchers;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;
import org.mockito.internal.verification.VerificationModeFactory;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

/** Tests for {@link PTransformFunctionRegistry}. */
@RunWith(PowerMockRunner.class)
@PrepareForTest(MetricsEnvironment.class)
public class PTransformFunctionRegistryTest {

  @Test
  public void functionsAreInvokedIndirectlyAfterRegisteringAndInvoking() throws Exception {
    MetricsContainerStepMap metricsContainerRegistry = mock(MetricsContainerStepMap.class);
    PTransformFunctionRegistry testObject =
        new PTransformFunctionRegistry(metricsContainerRegistry);

    ThrowingRunnable runnableA = mock(ThrowingRunnable.class);
    ThrowingRunnable runnableB = mock(ThrowingRunnable.class);
    testObject.register("pTransformA", runnableA);
    testObject.register("pTransformB", runnableB);

    for (ThrowingRunnable func : testObject.getFunctions()) {
      func.run();
    }

    verify(runnableA, times(1)).run();
    verify(runnableB, times(1)).run();
  }

  @Test
  public void testScopedMetricContainerInvokedUponRunningFunctions() throws Exception {
    mockStatic(MetricsEnvironment.class);
    MetricsContainerStepMap metricsContainerRegistry = new MetricsContainerStepMap();
    PTransformFunctionRegistry testObject =
        new PTransformFunctionRegistry(metricsContainerRegistry);

    ThrowingRunnable runnableA = mock(ThrowingRunnable.class);
    ThrowingRunnable runnableB = mock(ThrowingRunnable.class);
    testObject.register("pTransformA", runnableA);
    testObject.register("pTransformB", runnableB);

    for (ThrowingRunnable func : testObject.getFunctions()) {
      func.run();
    }

    // Verify that static scopedMetricsContainer is called with pTransformA's container.
    PowerMockito.verifyStatic(times(1));
    MetricsEnvironment.scopedMetricsContainer(
        metricsContainerRegistry.getContainer("pTransformA"));

    // Verify that static scopedMetricsContainer is called with pTransformB's container.
    PowerMockito.verifyStatic(times(1));
    MetricsEnvironment.scopedMetricsContainer(
        metricsContainerRegistry.getContainer("pTransformB"));
  }
}
