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

import java.io.IOException;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.Pipeline.PipelineExecutionException;
import org.apache.beam.sdk.PipelineResult;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.options.PipelineOptionsValidator;
import org.apache.beam.sdk.runners.PipelineRunner;
import org.apache.beam.sdk.util.UserCodeException;
import org.joda.time.Duration;

/**
 * Test Flink runner.
 */
public class TestFlinkRunner extends PipelineRunner<PipelineResult> {

  private FlinkRunner delegate;

  private TestFlinkRunner(FlinkPipelineOptions options) {
    // We use [auto] for testing since this will make it pick up the Testing ExecutionEnvironment
    options.setFlinkMaster("[auto]");
    this.delegate = FlinkRunner.fromOptions(options);
  }

  public static TestFlinkRunner fromOptions(PipelineOptions options) {
    FlinkPipelineOptions flinkOptions =
        PipelineOptionsValidator.validate(FlinkPipelineOptions.class, options);
    return new TestFlinkRunner(flinkOptions);
  }

  public static TestFlinkRunner create(boolean streaming) {
    FlinkPipelineOptions flinkOptions = PipelineOptionsFactory.as(FlinkPipelineOptions.class);
    flinkOptions.setRunner(TestFlinkRunner.class);
    flinkOptions.setStreaming(streaming);
    return TestFlinkRunner.fromOptions(flinkOptions);
  }

  @Override
  public PipelineResult run(Pipeline pipeline) {
    try {
      PipelineResult result = delegate.run(pipeline);
      // For tests that don't call waitUntilFinish().
      try {
        result.waitUntilFinish(Duration.standardSeconds(30));
      } finally {
        try {
          result.cancel();
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      }
      return result;
    } catch (Throwable e) {
      // Special case hack to pull out assertion errors from PAssert; instead there should
      // probably be a better story along the lines of UserCodeException.
      Throwable cause = e;
      Throwable oldCause = e;
      PipelineExecutionException executionException = null;
      do {

        // find UserCodeException and throw PipelineExecutionException
        if (cause instanceof UserCodeException) {
          executionException = new PipelineExecutionException(cause.getCause());
        }

        if (cause.getCause() == null) {
          break;
        }

        oldCause = cause;
        cause = cause.getCause();

      } while (!oldCause.equals(cause));
      if (cause instanceof AssertionError) {
        throw (AssertionError) cause;
      } else {
        if (executionException != null) {
          throw executionException;
        } else {
          throw e;
        }
      }
    }
  }

  public PipelineOptions getPipelineOptions() {
    return delegate.getPipelineOptions();
  }
}


