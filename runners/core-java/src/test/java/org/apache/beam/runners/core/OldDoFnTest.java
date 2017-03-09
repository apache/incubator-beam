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
package org.apache.beam.runners.core;

import static org.hamcrest.Matchers.empty;
import static org.junit.Assert.assertThat;

import java.io.Serializable;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.transforms.Aggregator;
import org.apache.beam.sdk.transforms.Combine.CombineFn;
import org.apache.beam.sdk.transforms.Max;
import org.apache.beam.sdk.transforms.SerializableFunction;
import org.apache.beam.sdk.transforms.Sum;
import org.apache.beam.sdk.transforms.display.DisplayData;
import org.apache.beam.sdk.values.TupleTag;
import org.joda.time.Instant;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for OldDoFn.
 */
@RunWith(JUnit4.class)
public class OldDoFnTest implements Serializable {

  @Rule
  public transient ExpectedException thrown = ExpectedException.none();

  @Test
  public void testCreateAggregatorWithNullNameThrowsException() {
    thrown.expect(NullPointerException.class);
    thrown.expectMessage("name cannot be null");

    OldDoFn<Void, Void> doFn = new NoOpOldDoFn<>();

    doFn.createAggregator(null, Sum.ofLongs());
  }

  @Test
  public void testCreateAggregatorWithNullCombineFnThrowsException() {
    CombineFn<Object, Object, Object> combiner = null;

    thrown.expect(NullPointerException.class);
    thrown.expectMessage("combiner cannot be null");

    OldDoFn<Void, Void> doFn = new NoOpOldDoFn<>();

    doFn.createAggregator("testAggregator", combiner);
  }

  @Test
  public void testCreateAggregatorWithNullSerializableFnThrowsException() {
    SerializableFunction<Iterable<Object>, Object> combiner = null;

    thrown.expect(NullPointerException.class);
    thrown.expectMessage("combiner cannot be null");

    OldDoFn<Void, Void> doFn = new NoOpOldDoFn<>();

    doFn.createAggregator("testAggregator", combiner);
  }

  @Test
  public void testCreateAggregatorWithSameNameThrowsException() {
    String name = "testAggregator";
    CombineFn<Double, ?, Double> combiner = Max.ofDoubles();

    OldDoFn<Void, Void> doFn = new NoOpOldDoFn<>();

    doFn.createAggregator(name, combiner);

    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage("Cannot create");
    thrown.expectMessage(name);
    thrown.expectMessage("already exists");

    doFn.createAggregator(name, combiner);
  }

  private OldDoFn<String, String>.Context createContext(OldDoFn<String, String> fn) {
    return fn.new Context() {
      @Override
      public PipelineOptions getPipelineOptions() {
        throw new UnsupportedOperationException();
      }

      @Override
      public void output(String output) {
        throw new UnsupportedOperationException();
      }

      @Override
      public void outputWithTimestamp(String output, Instant timestamp) {
        throw new UnsupportedOperationException();
      }

      @Override
      public <T> void sideOutput(TupleTag<T> tag, T output) {
        throw new UnsupportedOperationException();
      }

      @Override
      public <T> void sideOutputWithTimestamp(TupleTag<T> tag, T output, Instant timestamp) {
        throw new UnsupportedOperationException();
      }

      @Override
      public <AggInputT, AggOutputT>
      Aggregator<AggInputT, AggOutputT> createAggregatorInternal(
              String name, CombineFn<AggInputT, ?, AggOutputT> combiner) {
        throw new UnsupportedOperationException();
      }
    };
  }

  @Test
  public void testPopulateDisplayDataDefaultBehavior() {
    OldDoFn<String, String> usesDefault =
        new OldDoFn<String, String>() {
          @Override
          public void processElement(ProcessContext c) throws Exception {}
        };

    DisplayData data = DisplayData.from(usesDefault);
    assertThat(data.items(), empty());
  }
}
