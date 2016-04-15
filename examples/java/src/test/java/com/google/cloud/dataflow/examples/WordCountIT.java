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

package com.google.cloud.dataflow.examples;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.google.cloud.dataflow.sdk.PipelineResult;
import com.google.cloud.dataflow.sdk.testing.TestDataflowPipelineRunner;
import com.google.cloud.dataflow.sdk.testing.TestPipelineArgs;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.nio.file.Path;
import java.nio.file.Paths;


/**
 * End-to-end tests of WordCount.
 */
@RunWith(JUnit4.class)
public class WordCountIT {
  @Test
  public void testE2EWordCount() throws Exception {
    TestPipelineArgs args = TestDataflowPipelineRunner.createPipelineArgs();
    Path outputLoc = Paths.get(TestPipelineArgs.getTestFileLocation(),
        "output", args.getJobName(), "results");
    //args.add("output", outputLoc.toString());
    args.add("output",
        TestPipelineArgs.getTestFileLocation() + "output/" + args.getJobName() + "/results");
    args.add("workerLogLevelOverrides",
        "{\"com.google.cloud.dataflow.sdk.util.UploadIdResponseInterceptor\":\"DEBUG\"}");
    WordCount.main(args.build());
    PipelineResult result =
        TestDataflowPipelineRunner.getPipelineResultByJobName(args.getJobName());

    assertNotNull(result);
    assertEquals(PipelineResult.State.DONE, result.getState());
  }
}
