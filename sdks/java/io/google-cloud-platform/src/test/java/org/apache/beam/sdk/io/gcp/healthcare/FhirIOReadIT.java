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
package org.apache.beam.sdk.io.gcp.healthcare;

import static org.apache.beam.sdk.io.gcp.healthcare.HL7v2IOTestUtil.HEALTHCARE_DATASET_TEMPLATE;

import com.google.api.services.healthcare.v1beta1.model.HttpBody;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collection;
import org.apache.beam.runners.direct.DirectOptions;
import org.apache.beam.sdk.PipelineResult;
import org.apache.beam.sdk.extensions.gcp.options.GcpOptions;
import org.apache.beam.sdk.io.gcp.pubsub.PubsubIO;
import org.apache.beam.sdk.io.gcp.pubsub.TestPubsubSignal;
import org.apache.beam.sdk.testing.PAssert;
import org.apache.beam.sdk.testing.TestPipeline;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.base.Supplier;
import org.joda.time.Duration;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class FhirIOReadIT {

  @Parameters(name = "{0}")
  public static Collection<String> versions() {
    return Arrays.asList("DSTU2", "STU3", "R4");
  }

  @Rule public transient TestPipeline pipeline = TestPipeline.create();
  @Rule public transient TestPubsubSignal signal = TestPubsubSignal.create();

  private final String fhirStoreName;
  private final String pubsubTopic;
  private transient HealthcareApiClient client;
  private String healthcareDataset;

  public String version;

  public FhirIOReadIT(String version) {
    this.version = version;
    long testTime = System.currentTimeMillis();
    this.fhirStoreName =
        "FHIR_store_" + version + "_write_it_" + testTime + "_" + (new SecureRandom().nextInt(32));
    this.pubsubTopic =
        "projects/apache-beam-testing/topics/FhirIO-IT-" + version + "-notifications";
  }

  @Before
  public void setup() throws Exception {
    String project = TestPipeline.testingPipelineOptions().as(GcpOptions.class).getProject();
    healthcareDataset = String.format(HEALTHCARE_DATASET_TEMPLATE, project);
    if (client == null) {
      this.client = new HttpHealthcareApiClient();
    }
    client.createFhirStore(healthcareDataset, fhirStoreName, version);
  }

  @After
  public void deleteFHIRtore() throws IOException {
    HealthcareApiClient client = new HttpHealthcareApiClient();
    client.deleteFhirStore(healthcareDataset + "/fhirStores/" + fhirStoreName);
  }

  @Test
  public void testFhirIORead() throws Exception {
    pipeline.getOptions().as(DirectOptions.class).setBlockOnRun(false);
    FhirIO.Read.Result result =
        pipeline.apply(PubsubIO.readStrings().fromTopic(pubsubTopic)).apply(FhirIO.readResources());

    PCollection<HttpBody> resources = result.getResources();
    resources.apply(
        "waitForAnyMessage", signal.signalSuccessWhen(resources.getCoder(), anyResources -> true));
    // wait for any resource
    PAssert.that(result.getFailedReads()).empty();

    Supplier<Void> start = signal.waitForStart(Duration.standardMinutes(5));
    pipeline.apply(signal.signalStart());
    PipelineResult job = pipeline.run();
    start.get();

    // Execute bundles to trigger FHIR notificiations to input topic
    FhirIOTestUtil.executeFhirBundles(
        client,
        healthcareDataset + "/fhirStores/" + fhirStoreName,
        FhirIOTestUtil.BUNDLES.get(version));

    signal.waitForSuccess(Duration.standardSeconds(30));
    // A runner may not support cancel
    try {
      job.cancel();
    } catch (UnsupportedOperationException exc) {
      // noop
    }
  }
}
