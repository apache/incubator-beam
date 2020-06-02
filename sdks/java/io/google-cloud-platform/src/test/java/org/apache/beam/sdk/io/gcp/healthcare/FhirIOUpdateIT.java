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

import static org.apache.beam.sdk.io.gcp.healthcare.FhirIOTestUtil.BUNDLES;
import static org.apache.beam.sdk.io.gcp.healthcare.FhirIOTestUtil.DEFAULT_TEMP_BUCKET;
import static org.apache.beam.sdk.io.gcp.healthcare.FhirIOTestUtil.RESOURCES;
import static org.apache.beam.sdk.io.gcp.healthcare.HL7v2IOTestUtil.HEALTHCARE_DATASET_TEMPLATE;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.extensions.gcp.options.GcpOptions;
import org.apache.beam.sdk.io.gcp.healthcare.FhirIO.Import.ContentStructure;
import org.apache.beam.sdk.io.gcp.healthcare.FhirIO.Write.Result;
import org.apache.beam.sdk.io.gcp.healthcare.FhirIOTestUtil.GetByKey;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.testing.PAssert;
import org.apache.beam.sdk.testing.TestPipeline;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.WithKeys;
import org.apache.beam.sdk.values.KV;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class FhirIOUpdateIT {

  @Parameters(name = "{0}")
  public static Collection<String> versions() {
    return Arrays.asList("DSTU2", "STU3", "R4");
  }

  private final String fhirStoreName;
  private FhirIOTestOptions options;
  private transient HealthcareApiClient client;
  private String healthcareDataset;
  private long testTime = System.currentTimeMillis();

  public String version;

  @Rule public transient TestPipeline pipeline = TestPipeline.create();

  public FhirIOUpdateIT(String version) {
    this.version = version;
    this.fhirStoreName =
        "FHIR_store_" + version + "_write_it_" + testTime + "_" + (new SecureRandom().nextInt(32));
  }

  @Before
  public void setup() throws Exception {
    if (client == null) {
      client = new HttpHealthcareApiClient();
    }
    PipelineOptionsFactory.register(FhirIOTestOptions.class);
    String project = TestPipeline.testingPipelineOptions().as(GcpOptions.class).getProject();
    healthcareDataset = String.format(HEALTHCARE_DATASET_TEMPLATE, project);
    options = TestPipeline.testingPipelineOptions().as(FhirIOTestOptions.class);
    options.setGcsTempPath(
        String.format("gs://%s/FhirIOWrite%sIT/%s/temp/", DEFAULT_TEMP_BUCKET, version, testTime));
    options.setGcsDeadLetterPath(
        String.format(
            "gs://%s/FhirIOWrite%sIT/%s/deadletter/", DEFAULT_TEMP_BUCKET, version, testTime));
    options.setFhirStore(healthcareDataset + "/fhirStores/" + fhirStoreName);
    HealthcareApiClient client = new HttpHealthcareApiClient();
    client.createFhirStore(healthcareDataset, fhirStoreName, version);
  }

  @After
  public void teardownFhirStore() throws IOException {
    HealthcareApiClient client = new HttpHealthcareApiClient();
    client.deleteFhirStore(healthcareDataset + "/fhirStores/" + fhirStoreName);
    // clean up GCS objects if any.
  }

  @AfterClass
  public static void teardownBucket() throws IOException {
    FhirIOTestUtil.tearDownTempBucket();
  }

  // TODO(jaketf) add IT for conditional create, update, conditional update transforms.
  @Test
  public void testFhirIO_CreateResources() {
    Result writeResult =
        (Result)
            pipeline
                .apply("Create Test Resources", Create.of(RESOURCES.get(version)))
                .apply(
                    FhirIO.<String>createResources(options.getFhirStore())
                        .withTypeFunction(new GetByKey("resourceType"))
                        .withIfNotExistFunction(new FhirIOTestUtil.ExtractIDSearchQuery())
                        .withFormatBodyFunction(x -> x));

    PAssert.that(writeResult.getFailedBodies()).empty();

    pipeline.run().waitUntilFinish();
  }

  @Test
  public void testFhirIO_Update() {
    // TODO write initial resources to FHIR
    Result writeResult =
        (Result)
            pipeline
                .apply("Create Test Resources", Create.of(RESOURCES.get(version)))
                .apply("Extract ID keys", WithKeys.of(new GetByKey("id")))
                .apply(
                    "Update Resources",
                    FhirIO.<KV<String, String>>update(options.getFhirStore())
                        .withResourceNameFunction(x -> x.getKey())
                        .withFormatBodyFunction(x -> ((KV<String, String>) x).getValue()));

    PAssert.that(writeResult.getFailedBodies()).empty();

    pipeline.run().waitUntilFinish();
    // TODO spot check update results
  }

  @Test
  public void testFhirIO_ConditionalUpdate() {
    // TODO write initial resources to FHIR
    Result writeResult =
        (Result)
            pipeline
                .apply("Create Test Resources", Create.of(RESOURCES.get(version)))
                .apply(
                    "Conditional Update Resources",
                    FhirIO.<String>conditionalUpdate(options.getFhirStore())
                        .withTypeFunction(x -> "patient")
                        .withFormatBodyFunction(x -> "{}")
                        .withSearchParametersFunction(x -> new HashMap<>()));
    // TODO spot check update results
  }
}
