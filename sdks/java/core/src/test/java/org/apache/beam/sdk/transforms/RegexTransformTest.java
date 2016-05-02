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
package org.apache.beam.sdk.transforms;

import java.io.Serializable;

import org.apache.beam.sdk.testing.PAssert;
import org.apache.beam.sdk.testing.TestPipeline;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for {@link RegexTransform}.
 */
@RunWith(JUnit4.class)
public class RegexTransformTest implements Serializable {
  @Test
  public void testFind() {
    TestPipeline p = TestPipeline.create();

    PCollection<String> output = p
        .apply(Create.of("aj", "xj", "yj", "zj"))
        .apply(RegexTransform.find("[xyz]"));

    PAssert.that(output).containsInAnyOrder("x", "y", "z");
    p.run();
  }
  
  @Test
  public void testFindGroup() {
    TestPipeline p = TestPipeline.create();

    PCollection<String> output = p
        .apply(Create.of("aj", "xj", "yj", "zj"))
        .apply(RegexTransform.find("([xyz])", 1));

    PAssert.that(output).containsInAnyOrder("x", "y", "z");
    p.run();
  }

  @Test
  public void testFindNone() {
    TestPipeline p = TestPipeline.create();

    PCollection<String> output = p
        .apply(Create.of("a", "b", "c", "d"))
        .apply(RegexTransform.find("[xyz]"));
    
    PAssert.that(output).empty();
    p.run();
  }
  
  @Test
  public void testKVFind() {
    TestPipeline p = TestPipeline.create();

    PCollection<KV<String, String>> output = p
        .apply(Create.of("a b c"))
        .apply(RegexTransform.findKV("a (b) (c)", 1, 2));

    PAssert.that(output).containsInAnyOrder(KV.of("b", "c"));
    p.run();
  }

  @Test
  public void testKVFindNone() {
    TestPipeline p = TestPipeline.create();

    PCollection<KV<String, String>> output = p
        .apply(Create.of("x y z"))
        .apply(RegexTransform.findKV("a (b) (c)", 1, 2));
    
    PAssert.that(output).empty();
    p.run();
  }
  
  @Test
  public void testMatches() {
    TestPipeline p = TestPipeline.create();

    PCollection<String> output = p
        .apply(Create.of("a", "x", "y", "z"))
        .apply(RegexTransform.matches("[xyz]"));

    PAssert.that(output).containsInAnyOrder("x", "y", "z");
    p.run();
  }

  @Test
  public void testMatchesNone() {
    TestPipeline p = TestPipeline.create();

    PCollection<String> output = p
        .apply(Create.of("a", "b", "c", "d"))
        .apply(RegexTransform.matches("[xyz]"));
    
    PAssert.that(output).empty();
    p.run();
  }
  
  @Test
  public void testMatchesGroup() {
    TestPipeline p = TestPipeline.create();

    PCollection<String> output = p
        .apply(Create.of("a", "x xxx", "x yyy", "x zzz"))
        .apply(RegexTransform.matches("x ([xyz]*)", 1));

    PAssert.that(output).containsInAnyOrder("xxx", "yyy", "zzz");
    p.run();
  }
  
  @Test
  public void testKVMatches() {
    TestPipeline p = TestPipeline.create();

    PCollection<KV<String, String>> output = p
        .apply(Create.of("a b c"))
        .apply(RegexTransform.matchesKV("a (b) (c)", 1, 2));

    PAssert.that(output).containsInAnyOrder(KV.of("b", "c"));
    p.run();
  }

  @Test
  public void testKVMatchesNone() {
    TestPipeline p = TestPipeline.create();

    PCollection<KV<String, String>> output = p
        .apply(Create.of("x y z"))
        .apply(RegexTransform.matchesKV("a (b) (c)", 1, 2));
    
    PAssert.that(output).empty();
    p.run();
  }
}
