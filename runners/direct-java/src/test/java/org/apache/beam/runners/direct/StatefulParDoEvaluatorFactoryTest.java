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

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThat;
import static org.mockito.Matchers.anyList;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import org.apache.beam.runners.direct.DirectRunner.CommittedBundle;
import org.apache.beam.runners.direct.DirectRunner.UncommittedBundle;
import org.apache.beam.runners.direct.ParDoMultiOverrideFactory.StatefulParDo;
import org.apache.beam.runners.direct.WatermarkManager.TimerUpdate;
import org.apache.beam.sdk.coders.StringUtf8Coder;
import org.apache.beam.sdk.testing.TestPipeline;
import org.apache.beam.sdk.transforms.AppliedPTransform;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.View;
import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.apache.beam.sdk.transforms.windowing.FixedWindows;
import org.apache.beam.sdk.transforms.windowing.IntervalWindow;
import org.apache.beam.sdk.transforms.windowing.PaneInfo;
import org.apache.beam.sdk.transforms.windowing.Window;
import org.apache.beam.sdk.util.ReadyCheckingSideInputReader;
import org.apache.beam.sdk.util.WindowedValue;
import org.apache.beam.sdk.util.WindowingStrategy;
import org.apache.beam.sdk.util.state.CopyOnAccessInMemoryStateInternals;
import org.apache.beam.sdk.util.state.StateInternals;
import org.apache.beam.sdk.util.state.StateNamespace;
import org.apache.beam.sdk.util.state.StateNamespaces;
import org.apache.beam.sdk.util.state.StateSpec;
import org.apache.beam.sdk.util.state.StateSpecs;
import org.apache.beam.sdk.util.state.StateTag;
import org.apache.beam.sdk.util.state.StateTags;
import org.apache.beam.sdk.util.state.ValueState;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionTuple;
import org.apache.beam.sdk.values.PCollectionView;
import org.joda.time.Duration;
import org.joda.time.Instant;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Matchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

/** Tests for {@link StatefulParDoEvaluatorFactory}. */
@RunWith(JUnit4.class)
public class StatefulParDoEvaluatorFactoryTest implements Serializable {
  @Mock private transient EvaluationContext mockEvaluationContext;
  @Mock private transient DirectExecutionContext mockExecutionContext;
  @Mock private transient DirectExecutionContext.DirectStepContext mockStepContext;
  @Mock private transient ReadyCheckingSideInputReader mockSideInputReader;
  @Mock private transient UncommittedBundle<Integer> mockUncommittedBundle;

  private static final String KEY = "any-key";
  private transient StateInternals<Object> stateInternals =
      CopyOnAccessInMemoryStateInternals.<Object>withUnderlying(KEY, null);

  private static final BundleFactory BUNDLE_FACTORY = ImmutableListBundleFactory.create();

  @Before
  public void setup() {
    MockitoAnnotations.initMocks(this);
    when((StateInternals<Object>) mockStepContext.stateInternals()).thenReturn(stateInternals);
  }

  @Test
  public void windowCleanupScheduled() throws Exception {
    // To test the factory, first we set up a pipeline and then we use the constructed
    // pipeline to create the right parameters to pass to the factory
    TestPipeline pipeline = TestPipeline.create();

    final String stateId = "my-state-id";

    // For consistency, window it into FixedWindows. Actually we will fabricate an input bundle.
    PCollection<KV<String, Integer>> input =
        pipeline
            .apply(Create.of(KV.of("hello", 1), KV.of("hello", 2)))
            .apply(Window.<KV<String, Integer>>into(FixedWindows.of(Duration.millis(10))));

    PCollection<Integer> produced =
        input.apply(
            ParDo.of(
                new DoFn<KV<String, Integer>, Integer>() {
                  @StateId(stateId)
                  private final StateSpec<Object, ValueState<String>> spec =
                      StateSpecs.value(StringUtf8Coder.of());

                  @ProcessElement
                  public void process(ProcessContext c) {}
                }));

    StatefulParDoEvaluatorFactory<String, Integer, Integer> factory =
        new StatefulParDoEvaluatorFactory(mockEvaluationContext);

    AppliedPTransform<
            PCollection<? extends KV<String, Iterable<Integer>>>, PCollectionTuple,
            StatefulParDo<String, Integer, Integer>>
        producingTransform = (AppliedPTransform) produced.getProducingTransformInternal();

    // Then there will be a digging down to the step context to get the state internals
    when(mockEvaluationContext.getExecutionContext(
            eq(producingTransform), Mockito.<StructuralKey>any()))
        .thenReturn(mockExecutionContext);
    when(mockExecutionContext.getOrCreateStepContext(anyString(), anyString()))
        .thenReturn(mockStepContext);

    IntervalWindow firstWindow = new IntervalWindow(new Instant(0), new Instant(9));
    IntervalWindow secondWindow = new IntervalWindow(new Instant(10), new Instant(19));

    StateNamespace firstWindowNamespace =
        StateNamespaces.window(IntervalWindow.getCoder(), firstWindow);
    StateNamespace secondWindowNamespace =
        StateNamespaces.window(IntervalWindow.getCoder(), secondWindow);
    StateTag<Object, ValueState<String>> tag =
        StateTags.tagForSpec(stateId, StateSpecs.value(StringUtf8Coder.of()));

    // Set up non-empty state. We don't mock + verify calls to clear() but instead
    // check that state is actually empty. We musn't care how it is accomplished.
    stateInternals.state(firstWindowNamespace, tag).write("first");
    stateInternals.state(secondWindowNamespace, tag).write("second");

    // A single bundle with some elements in the global window; it should register cleanup for the
    // global window state merely by having the evaluator created. The cleanup logic does not
    // depend on the window.
    CommittedBundle<KV<String, Integer>> inputBundle =
        BUNDLE_FACTORY
            .createBundle(input)
            .add(
                WindowedValue.of(
                    KV.of("hello", 1), new Instant(3), firstWindow, PaneInfo.NO_FIRING))
            .add(
                WindowedValue.of(
                    KV.of("hello", 2), new Instant(11), secondWindow, PaneInfo.NO_FIRING))
            .commit(Instant.now());

    // Merely creating the evaluator should suffice to register the cleanup callback
    factory.forApplication(producingTransform, inputBundle);

    ArgumentCaptor<Runnable> argumentCaptor = ArgumentCaptor.forClass(Runnable.class);
    verify(mockEvaluationContext)
        .scheduleAfterWindowExpiration(
            eq(producingTransform),
            eq(firstWindow),
            Mockito.<WindowingStrategy<?, ?>>any(),
            argumentCaptor.capture());

    // Should actually clear the state for the first window
    argumentCaptor.getValue().run();
    assertThat(stateInternals.state(firstWindowNamespace, tag).read(), nullValue());
    assertThat(stateInternals.state(secondWindowNamespace, tag).read(), equalTo("second"));

    verify(mockEvaluationContext)
        .scheduleAfterWindowExpiration(
            eq(producingTransform),
            eq(secondWindow),
            Mockito.<WindowingStrategy<?, ?>>any(),
            argumentCaptor.capture());

    // Should actually clear the state for the second window
    argumentCaptor.getValue().run();
    assertThat(stateInternals.state(secondWindowNamespace, tag).read(), nullValue());
  }

  /**
   * A test that explicitly delays a side input so that the main input will have to be reprocessed,
   * testing that {@code finishBundle()} re-assembles the GBK outputs correctly.
   */
  @Test
  public void testUnprocessedElements() throws Exception {
    // To test the factory, first we set up a pipeline and then we use the constructed
    // pipeline to create the right parameters to pass to the factory
    TestPipeline pipeline = TestPipeline.create();

    final String stateId = "my-state-id";

    // For consistency, window it into FixedWindows. Actually we will fabricate an input bundle.
    PCollection<KV<String, Integer>> mainInput =
        pipeline
            .apply(Create.of(KV.of("hello", 1), KV.of("hello", 2)))
            .apply(Window.<KV<String, Integer>>into(FixedWindows.of(Duration.millis(10))));

    final PCollectionView<List<Integer>> sideInput =
        pipeline
            .apply("Create side input", Create.of(42))
            .apply("Window side input", Window.<Integer>into(FixedWindows.of(Duration.millis(10))))
            .apply("View side input", View.<Integer>asList());

    PCollection<Integer> produced =
        mainInput.apply(
            ParDo.withSideInputs(sideInput)
                .of(
                    new DoFn<KV<String, Integer>, Integer>() {
                      @StateId(stateId)
                      private final StateSpec<Object, ValueState<String>> spec =
                          StateSpecs.value(StringUtf8Coder.of());

                      @ProcessElement
                      public void process(ProcessContext c) {}
                    }));

    StatefulParDoEvaluatorFactory<String, Integer, Integer> factory =
        new StatefulParDoEvaluatorFactory(mockEvaluationContext);

    // This will be the stateful ParDo from the expansion
    AppliedPTransform<
            PCollection<KV<String, Iterable<Integer>>>, PCollectionTuple,
            StatefulParDo<String, Integer, Integer>>
        producingTransform = (AppliedPTransform) produced.getProducingTransformInternal();

    // Then there will be a digging down to the step context to get the state internals
    when(mockEvaluationContext.getExecutionContext(
            eq(producingTransform), Mockito.<StructuralKey>any()))
        .thenReturn(mockExecutionContext);
    when(mockExecutionContext.getOrCreateStepContext(anyString(), anyString()))
        .thenReturn(mockStepContext);
    when(mockEvaluationContext.createBundle(Matchers.<PCollection<Integer>>any()))
        .thenReturn(mockUncommittedBundle);
    when(mockStepContext.getTimerUpdate()).thenReturn(TimerUpdate.empty());

    // And digging to check whether the window is ready
    when(mockEvaluationContext.createSideInputReader(anyList())).thenReturn(mockSideInputReader);
    when(mockSideInputReader.isReady(
            Matchers.<PCollectionView<?>>any(), Matchers.<BoundedWindow>any()))
        .thenReturn(false);

    IntervalWindow firstWindow = new IntervalWindow(new Instant(0), new Instant(9));

    // A single bundle with some elements in the global window; it should register cleanup for the
    // global window state merely by having the evaluator created. The cleanup logic does not
    // depend on the window.
    WindowedValue<KV<String, Iterable<Integer>>> gbkOutputElement =
        WindowedValue.of(
            KV.<String, Iterable<Integer>>of("hello", Lists.newArrayList(1, 13, 15)),
            new Instant(3),
            firstWindow,
            PaneInfo.NO_FIRING);
    CommittedBundle<KV<String, Iterable<Integer>>> inputBundle =
        BUNDLE_FACTORY
            .createBundle(producingTransform.getInput())
            .add(gbkOutputElement)
            .commit(Instant.now());
    TransformEvaluator<KV<String, Iterable<Integer>>> evaluator =
        factory.forApplication(producingTransform, inputBundle);
    evaluator.startBundle();
    evaluator.processElement(gbkOutputElement);

    // This should push back every element as a KV<String, Iterable<Integer>>
    // in the appropriate window. Since the keys are equal they are single-threaded
    TransformResult<KV<String, Iterable<Integer>>> result = evaluator.finishBundle();

    List<Integer> pushedBackInts = new ArrayList<>();

    for (WindowedValue<?> unprocessedElement : result.getUnprocessedElements()) {
      WindowedValue<KV<String, Iterable<Integer>>> unprocessedKv =
          (WindowedValue<KV<String, Iterable<Integer>>>) unprocessedElement;

      assertThat(
          Iterables.getOnlyElement(unprocessedElement.getWindows()),
          equalTo((BoundedWindow) firstWindow));
      assertThat(unprocessedKv.getValue().getKey(), equalTo("hello"));
      for (Integer i : unprocessedKv.getValue().getValue()) {
        pushedBackInts.add(i);
      }
    }
    assertThat(pushedBackInts, containsInAnyOrder(1, 13, 15));
  }
}
