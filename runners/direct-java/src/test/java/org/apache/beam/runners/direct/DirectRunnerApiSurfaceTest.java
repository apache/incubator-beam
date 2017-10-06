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

package org.apache.beam.runners.direct;

import static org.apache.beam.sdk.util.ApiSurface.containsOnlyPackages;
import static org.junit.Assert.assertThat;

import com.google.common.collect.ImmutableSet;

import java.io.IOException;
import java.util.Set;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.PipelineRunner;
import org.apache.beam.sdk.metrics.MetricResults;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.PipelineOptionsRegistrar;
import org.apache.beam.sdk.transforms.display.DisplayData;
import org.apache.beam.sdk.util.ApiSurface;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** API surface verification for {@link org.apache.beam.runners.direct}. */
@RunWith(JUnit4.class)
public class DirectRunnerApiSurfaceTest {
    /**
     * All classes transitively reachable via only public method signatures of the Direct Runner.
     *
     * <p>Note that our idea of "public" does not include various internal-only APIs.
     */
  private static ApiSurface directRunnerApiSurface(
          final Package directRunnerPackage, final ClassLoader classLoader) throws IOException {
    ApiSurface apiSurface =
            ApiSurface.ofPackage(directRunnerPackage, classLoader)
                    // Do not include dependencies that are required based on the known exposures.
                    // This could alternatively prune everything exposed by the public parts of
                    // the Core SDK
                    .pruningClass(Pipeline.class)
                    .pruningClass(PipelineRunner.class)
                    .pruningClass(PipelineOptions.class)
                    .pruningClass(PipelineOptionsRegistrar.class)
                    .pruningClass(PipelineOptions.DirectRunner.class)
                    .pruningClass(DisplayData.Builder.class)
                    .pruningClass(MetricResults.class)
                    .pruningPattern("org[.]apache[.]beam[.].*Test.*")
                    .pruningPattern("org[.]apache[.]beam[.].*IT")
                    .pruningPattern("java[.]io.*")
                    .pruningPattern("java[.]lang.*")
                    .pruningPattern("java[.]util.*");
    return apiSurface;
  }
  @Test
  public void testDirectRunnerApiSurface() throws Exception {
    // The DirectRunner can expose the Core SDK, anything exposed by the Core SDK, and itself
    @SuppressWarnings("unchecked")
    final Set<String> allowed =
        ImmutableSet.of("org.apache.beam.sdk", "org.apache.beam.runners.direct", "org.joda.time");

    final Package thisPackage = getClass().getPackage();
    final ClassLoader thisClassLoader = getClass().getClassLoader();
    assertThat(directRunnerApiSurface(thisPackage, thisClassLoader), containsOnlyPackages(allowed));
  }
}
