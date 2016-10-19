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
package org.apache.beam.runners.core.reactors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.beam.runners.core.reactors.TriggerReactorTester.SimpleTriggerReactorTester;
import org.apache.beam.sdk.transforms.windowing.FixedWindows;
import org.apache.beam.sdk.transforms.windowing.GlobalWindow;
import org.apache.beam.sdk.transforms.windowing.GlobalWindows;
import org.apache.beam.sdk.transforms.windowing.IntervalWindow;
import org.apache.beam.sdk.transforms.windowing.Sessions;
import org.apache.beam.sdk.transforms.windowing.WindowFn;
import org.joda.time.Duration;
import org.joda.time.Instant;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link RepeatedlyReactor}.
 */
@RunWith(JUnit4.class)
public class RepeatedlyReactorTest {

  @Mock private TriggerReactor mockTrigger;
  private SimpleTriggerReactorTester<IntervalWindow> tester;
  private static TriggerReactor.TriggerContext anyTriggerContext() {
    return Mockito.<TriggerReactor.TriggerContext>any();
  }

  public void setUp(WindowFn<Object, IntervalWindow> windowFn) throws Exception {
    MockitoAnnotations.initMocks(this);
    tester = TriggerReactorTester.forTrigger(RepeatedlyReactor.forever(mockTrigger), windowFn);
  }

  /**
   * Tests that onElement correctly passes the data on to the subtrigger.
   */
  @Test
  public void testOnElement() throws Exception {
    setUp(FixedWindows.of(Duration.millis(10)));
    tester.injectElements(37);
    verify(mockTrigger).onElement(Mockito.<TriggerReactor.OnElementContext>any());
  }

  /**
   * Tests that the repeatedly is ready to fire whenever the subtrigger is ready.
   */
  @Test
  public void testShouldFire() throws Exception {
    setUp(FixedWindows.of(Duration.millis(10)));

    when(mockTrigger.shouldFire(anyTriggerContext())).thenReturn(true);
    assertTrue(tester.shouldFire(new IntervalWindow(new Instant(0), new Instant(10))));

    when(mockTrigger.shouldFire(Mockito.<TriggerReactor.TriggerContext>any()))
        .thenReturn(false);
    assertFalse(tester.shouldFire(new IntervalWindow(new Instant(0), new Instant(10))));
  }

  @Test
  public void testShouldFireAfterMerge() throws Exception {
    tester = TriggerReactorTester.forTrigger(
        RepeatedlyReactor.forever(AfterPaneReactor.elementCountAtLeast(2)),
        Sessions.withGapDuration(Duration.millis(10)));

    tester.injectElements(1);
    IntervalWindow firstWindow = new IntervalWindow(new Instant(1), new Instant(11));
    assertFalse(tester.shouldFire(firstWindow));

    tester.injectElements(5);
    IntervalWindow secondWindow = new IntervalWindow(new Instant(5), new Instant(15));
    assertFalse(tester.shouldFire(secondWindow));

    // Merge them, if the merged window were on the second trigger, it would be ready
    tester.mergeWindows();
    IntervalWindow mergedWindow = new IntervalWindow(new Instant(1), new Instant(15));
    assertTrue(tester.shouldFire(mergedWindow));
  }

  @Test
  public void testRepeatedlyAfterFirstElementCount() throws Exception {
    SimpleTriggerReactorTester<GlobalWindow> tester =
        TriggerReactorTester.forTrigger(
            RepeatedlyReactor.forever(
                AfterFirstReactor.of(
                    AfterProcessingTimeReactor.pastFirstElementInPane()
                        .plusDelayOf(Duration.standardMinutes(15)),
                    AfterPaneReactor.elementCountAtLeast(5))),
            new GlobalWindows());

    GlobalWindow window = GlobalWindow.INSTANCE;

    tester.injectElements(1);
    assertFalse(tester.shouldFire(window));

    tester.injectElements(2, 3, 4, 5);
    assertTrue(tester.shouldFire(window));
    tester.fireIfShouldFire(window);
    assertFalse(tester.shouldFire(window));
  }

  @Test
  public void testRepeatedlyAfterFirstProcessingTime() throws Exception {
    SimpleTriggerReactorTester<GlobalWindow> tester =
        TriggerReactorTester.forTrigger(
            RepeatedlyReactor.forever(
                AfterFirstReactor.of(
                    AfterProcessingTimeReactor.pastFirstElementInPane()
                        .plusDelayOf(Duration.standardMinutes(15)),
                    AfterPaneReactor.elementCountAtLeast(5))),
            new GlobalWindows());

    GlobalWindow window = GlobalWindow.INSTANCE;

    tester.injectElements(1);
    assertFalse(tester.shouldFire(window));

    tester.advanceProcessingTime(new Instant(0).plus(Duration.standardMinutes(15)));
    assertTrue(tester.shouldFire(window));
    tester.fireIfShouldFire(window);
    assertFalse(tester.shouldFire(window));
  }

  @Test
  public void testRepeatedlyElementCount() throws Exception {
    SimpleTriggerReactorTester<GlobalWindow> tester =
        TriggerReactorTester.forTrigger(
            RepeatedlyReactor.forever(AfterPaneReactor.elementCountAtLeast(5)),
            new GlobalWindows());

    GlobalWindow window = GlobalWindow.INSTANCE;

    tester.injectElements(1);
    assertFalse(tester.shouldFire(window));

    tester.injectElements(2, 3, 4, 5);
    assertTrue(tester.shouldFire(window));
    tester.fireIfShouldFire(window);
    assertFalse(tester.shouldFire(window));
  }

  @Test
  public void testRepeatedlyProcessingTime() throws Exception {
    SimpleTriggerReactorTester<GlobalWindow> tester =
        TriggerReactorTester.forTrigger(
            RepeatedlyReactor.forever(
                    AfterProcessingTimeReactor.pastFirstElementInPane()
                        .plusDelayOf(Duration.standardMinutes(15))),
            new GlobalWindows());

    GlobalWindow window = GlobalWindow.INSTANCE;

    tester.injectElements(1);
    assertFalse(tester.shouldFire(window));

    tester.advanceProcessingTime(new Instant(0).plus(Duration.standardMinutes(15)));
    assertTrue(tester.shouldFire(window));
    tester.fireIfShouldFire(window);
    assertFalse(tester.shouldFire(window));
  }


  @Test
  public void testToString() {
    TriggerReactor trigger = RepeatedlyReactor.forever(new StubTriggerReactor() {
        @Override
        public String toString() {
          return "innerTrigger";
        }
      });

    assertEquals("Repeatedly.forever(innerTrigger)", trigger.toString());
  }

}
