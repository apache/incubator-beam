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

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Predicates.in;
import static com.google.common.collect.Iterables.all;

import com.google.common.collect.ImmutableSet;
import java.util.HashSet;
import java.util.Set;
import org.apache.beam.runners.core.SplittableParDo;
import org.apache.beam.runners.direct.DirectGroupByKey.DirectGroupAlsoByWindow;
import org.apache.beam.runners.direct.DirectGroupByKey.DirectGroupByKeyOnly;
import org.apache.beam.sdk.Pipeline.PipelineVisitor;
import org.apache.beam.sdk.runners.TransformHierarchy;
import org.apache.beam.sdk.transforms.GroupByKey;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.values.PValue;

/**
 * A pipeline visitor that tracks all keyed {@link PValue PValues}. A {@link PValue} is keyed if it
 * is the result of a {@link PTransform} that produces keyed outputs. A {@link PTransform} that
 * produces keyed outputs is assumed to colocate output elements that share a key.
 *
 * <p>All {@link GroupByKey} transforms, or their runner-specific implementation primitive, produce
 * keyed output.
 */
// TODO: Handle Key-preserving transforms when appropriate and more aggressively make PTransforms
// unkeyed
class KeyedPValueTrackingVisitor implements PipelineVisitor {

  private static final Set<Class<? extends PTransform>> PRODUCES_KEYED_OUTPUTS =
      ImmutableSet.of(
          SplittableParDo.GBKIntoKeyedWorkItems.class,
          DirectGroupByKeyOnly.class,
          DirectGroupAlsoByWindow.class);

  private final Set<PValue> keyedValues;
  private boolean finalized;

  public static KeyedPValueTrackingVisitor create() {
    return new KeyedPValueTrackingVisitor();
  }

  private KeyedPValueTrackingVisitor() {
    this.keyedValues = new HashSet<>();
  }

  @Override
  public CompositeBehavior enterCompositeTransform(TransformHierarchy.Node node) {
    checkState(
        !finalized,
        "Attempted to use a %s that has already been finalized on a pipeline (visiting node %s)",
        KeyedPValueTrackingVisitor.class.getSimpleName(),
        node);
    return CompositeBehavior.ENTER_TRANSFORM;
  }

  @Override
  public void leaveCompositeTransform(TransformHierarchy.Node node) {
    checkState(
        !finalized,
        "Attempted to use a %s that has already been finalized on a pipeline (visiting node %s)",
        KeyedPValueTrackingVisitor.class.getSimpleName(),
        node);
    if (node.isRootNode()) {
      finalized = true;
    } else if (PRODUCES_KEYED_OUTPUTS.contains(node.getTransform().getClass())) {
      keyedValues.addAll(node.getOutputs());
    }
  }

  @Override
  public void visitPrimitiveTransform(TransformHierarchy.Node node) {}

  @Override
  public void visitValue(PValue value, TransformHierarchy.Node producer) {
    if (PRODUCES_KEYED_OUTPUTS.contains(producer.getTransform().getClass())
        || (isKeyPreserving(producer.getTransform())
            && all(producer.getInputs(), in(keyedValues)))) {
      keyedValues.addAll(value.expand());
    }
  }

  public Set<PValue> getKeyedPValues() {
    checkState(
        finalized, "can't call getKeyedPValues before a Pipeline has been completely traversed");
    return keyedValues;
  }

  private static boolean isKeyPreserving(PTransform<?, ?> transform) {
    // There are currently no key-preserving transforms; this lays the infrastructure for them
    return false;
  }
}
