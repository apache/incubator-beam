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
package org.apache.beam.examples.cookbook;

import com.google.api.services.bigquery.model.TableRow;
import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.apache.beam.examples.cookbook.TriggerExample.ExtractFlowInfo;
import org.apache.beam.examples.cookbook.TriggerExample.TotalFlow;
import org.apache.beam.sdk.testing.PAssert;
import org.apache.beam.sdk.testing.TestPipeline;
import org.apache.beam.sdk.testing.ValidatesRunner;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.DoFnTester;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.windowing.FixedWindows;
import org.apache.beam.sdk.transforms.windowing.Window;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.TimestampedValue;
import org.joda.time.Duration;
import org.joda.time.Instant;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit Tests for {@link TriggerExample}.
 * The results generated by triggers are by definition non-deterministic and hence hard to test.
 * The unit test does not test all aspects of the example.
 */
@RunWith(JUnit4.class)
public class TriggerExampleTest {

  private static final String[] INPUT =
    {"01/01/2010 00:00:00,1108302,94,E,ML,36,100,29,0.0065,66,9,1,0.001,74.8,1,9,3,0.0028,71,1,9,"
        + "12,0.0099,67.4,1,9,13,0.0121,99.0,1,,,,,0,,,,,0,,,,,0,,,,,0", "01/01/2010 00:00:00,"
            + "1100333,5,N,FR,9,0,39,,,9,,,,0,,,,,0,,,,,0,,,,,0,,,,,0,,,,,0,,,,,0,,,,"};

  private static final List<TimestampedValue<String>> TIME_STAMPED_INPUT = Arrays.asList(
      TimestampedValue.of("01/01/2010 00:00:00,1108302,5,W,ML,36,100,30,0.0065,66,9,1,0.001,"
          + "74.8,1,9,3,0.0028,71,1,9,12,0.0099,87.4,1,9,13,0.0121,99.0,1,,,,,0,,,,,0,,,,,0,,,"
          + ",,0", new Instant(60000)),
      TimestampedValue.of("01/01/2010 00:00:00,1108302,110,E,ML,36,100,40,0.0065,66,9,1,0.001,"
          + "74.8,1,9,3,0.0028,71,1,9,12,0.0099,67.4,1,9,13,0.0121,99.0,1,,,,,0,,,,,0,,,,,0,,,"
          + ",,0", new Instant(1)),
      TimestampedValue.of("01/01/2010 00:00:00,1108302,110,E,ML,36,100,50,0.0065,66,9,1,"
          + "0.001,74.8,1,9,3,0.0028,71,1,9,12,0.0099,97.4,1,9,13,0.0121,50.0,1,,,,,0,,,,,0"
          + ",,,,,0,,,,,0", new Instant(1)));

  private static final TableRow OUT_ROW_1 =
      new TableRow()
          .set("trigger_type", "default")
          .set("freeway", "5")
          .set("total_flow", 30)
          .set("number_of_records", 1)
          .set("isFirst", true)
          .set("isLast", true)
          .set("timing", "ON_TIME")
          .set("window", "[1970-01-01T00:01:00.000Z..1970-01-01T00:02:00.000Z)");

  private static final TableRow OUT_ROW_2 =
      new TableRow()
          .set("trigger_type", "default")
          .set("freeway", "110")
          .set("total_flow", 90)
          .set("number_of_records", 2)
          .set("isFirst", true)
          .set("isLast", true)
          .set("timing", "ON_TIME")
          .set("window", "[1970-01-01T00:00:00.000Z..1970-01-01T00:01:00.000Z)");

  @Rule
  public TestPipeline pipeline = TestPipeline.create();

  @Test
  public void testExtractTotalFlow() throws Exception {
    DoFnTester<String, KV<String, Integer>> extractFlowInfow = DoFnTester
        .of(new ExtractFlowInfo());

    List<KV<String, Integer>> results = extractFlowInfow.processBundle(INPUT);
    Assert.assertEquals(results.size(), 1);
    Assert.assertEquals(results.get(0).getKey(), "94");
    Assert.assertEquals(results.get(0).getValue(), new Integer(29));

    List<KV<String, Integer>> output = extractFlowInfow.processBundle("");
    Assert.assertEquals(output.size(), 0);
  }

  @Test
  @Category(ValidatesRunner.class)
  public void testTotalFlow () {
    PCollection<KV<String, Integer>> flow = pipeline
        .apply(Create.timestamped(TIME_STAMPED_INPUT))
        .apply(ParDo.of(new ExtractFlowInfo()));

    PCollection<TableRow> totalFlow = flow
        .apply(Window.<KV<String, Integer>>into(FixedWindows.of(Duration.standardMinutes(1))))
        .apply(new TotalFlow("default"));

    PCollection<String> results =  totalFlow.apply(ParDo.of(new FormatResults()));

    PAssert.that(results)
        .containsInAnyOrder(canonicalFormat(OUT_ROW_1), canonicalFormat(OUT_ROW_2));
    pipeline.run().waitUntilFinish();

  }

  // Sort the fields and toString() the values, since TableRow has a bit of a dynamically
  // typed API and equals()/hashCode() are not appropriate for matching in tests
  static String canonicalFormat(TableRow row) {
    List<String> entries = Lists.newArrayListWithCapacity(row.size());
    for (Map.Entry<String, Object> entry : row.entrySet()) {
      entries.add(entry.getKey() + ":" + entry.getValue());
    }
    Collections.sort(entries);
    return Joiner.on(",").join(entries);
  }

  static class FormatResults extends DoFn<TableRow, String> {
    @ProcessElement
    public void processElement(ProcessContext c) throws Exception {
      TableRow element = c.element();
      TableRow row = new TableRow()
          .set("trigger_type", element.get("trigger_type"))
          .set("freeway", element.get("freeway"))
          .set("total_flow", element.get("total_flow"))
          .set("number_of_records", element.get("number_of_records"))
          .set("isFirst", element.get("isFirst"))
          .set("isLast", element.get("isLast"))
          .set("timing", element.get("timing"))
          .set("window", element.get("window"));
      c.output(canonicalFormat(row));
    }
  }
}
