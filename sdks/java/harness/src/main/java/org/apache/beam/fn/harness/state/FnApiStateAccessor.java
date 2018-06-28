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

package org.apache.beam.fn.harness.state;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.google.protobuf.ByteString;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import org.apache.beam.model.fnexecution.v1.BeamFnApi.StateKey;
import org.apache.beam.runners.core.SideInputReader;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.coders.KvCoder;
import org.apache.beam.sdk.fn.function.ThrowingRunnable;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.state.BagState;
import org.apache.beam.sdk.state.CombiningState;
import org.apache.beam.sdk.state.MapState;
import org.apache.beam.sdk.state.ReadableState;
import org.apache.beam.sdk.state.ReadableStates;
import org.apache.beam.sdk.state.SetState;
import org.apache.beam.sdk.state.StateBinder;
import org.apache.beam.sdk.state.StateContext;
import org.apache.beam.sdk.state.StateSpec;
import org.apache.beam.sdk.state.ValueState;
import org.apache.beam.sdk.state.WatermarkHoldState;
import org.apache.beam.sdk.transforms.Combine.CombineFn;
import org.apache.beam.sdk.transforms.CombineWithContext.CombineFnWithContext;
import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.apache.beam.sdk.transforms.windowing.TimestampCombiner;
import org.apache.beam.sdk.util.CombineFnUtil;
import org.apache.beam.sdk.util.WindowedValue;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollectionView;
import org.apache.beam.sdk.values.TupleTag;

/**
 * Provides access to side inputs and state via a {@link BeamFnStateClient}.
 */
public class FnApiStateAccessor implements SideInputReader, StateBinder {
  private final PipelineOptions pipelineOptions;
  private final Map<StateKey, Object> stateKeyObjectCache;
  private final Map<TupleTag<?>, SideInputSpec> sideInputSpecMap;
  private final BeamFnStateClient beamFnStateClient;
  private final String ptransformId;
  private final Supplier<String> processBundleInstructionId;
  private final Collection<ThrowingRunnable> stateFinalizers;

  private final Supplier<BoundedWindow> currentWindowSupplier;

  private final Supplier<ByteString> encodedCurrentKeySupplier;
  private final Supplier<ByteString> encodedCurrentWindowSupplier;

  public FnApiStateAccessor(
      PipelineOptions pipelineOptions,
      String ptransformId,
      Supplier<String> processBundleInstructionId,
      Map<TupleTag<?>, SideInputSpec> sideInputSpecMap,
      BeamFnStateClient beamFnStateClient,
      Coder<?> keyCoder,
      Coder<BoundedWindow> windowCoder,
      Supplier<WindowedValue<?>> currentElementSupplier,
      Supplier<BoundedWindow> currentWindowSupplier) {
    this.pipelineOptions = pipelineOptions;
    this.stateKeyObjectCache = Maps.newHashMap();
    this.sideInputSpecMap = sideInputSpecMap;
    this.beamFnStateClient = beamFnStateClient;
    this.ptransformId = ptransformId;
    this.processBundleInstructionId = processBundleInstructionId;
    this.stateFinalizers = new ArrayList<>();
    this.currentWindowSupplier = currentWindowSupplier;
    this.encodedCurrentKeySupplier = memoizeFunction(currentElementSupplier,
        element -> {
          checkState(
              element.getValue() instanceof KV,
              "Accessing state in unkeyed context. Current element is not a KV: %s.",
              element);
          checkState(
              keyCoder != null,
              "Accessing state in unkeyed context, no key coder available");

          ByteString.Output encodedKeyOut = ByteString.newOutput();
          try {
            ((Coder) keyCoder).encode(((KV<?, ?>) element.getValue()).getKey(), encodedKeyOut);
          } catch (IOException e) {
            throw new IllegalStateException(e);
          }
          return encodedKeyOut.toByteString();
        });

    this.encodedCurrentWindowSupplier = memoizeFunction(
        currentWindowSupplier,
        window -> {
          ByteString.Output encodedWindowOut = ByteString.newOutput();
          try {
            windowCoder.encode(window, encodedWindowOut);
          } catch (IOException e) {
            throw new IllegalStateException(e);
          }
          return encodedWindowOut.toByteString();
        });
  }

  private static <ArgT, ResultT> Supplier<ResultT> memoizeFunction(
      Supplier<ArgT> arg, Function<ArgT, ResultT> f) {
    return new Supplier<ResultT>() {
      private ArgT memoizedArg;
      private ResultT memoizedResult;

      @Override
      public ResultT get() {
        ArgT currentArg = arg.get();
        if (currentArg != memoizedArg) {
          memoizedResult = f.apply(this.memoizedArg = currentArg);
        }
        return memoizedResult;
      }
    };
  }

  @Override
  @Nullable
  public <T> T get(PCollectionView<T> view, BoundedWindow window) {
    TupleTag<?> tag = view.getTagInternal();

    SideInputSpec sideInputSpec = sideInputSpecMap.get(tag);
    checkArgument(sideInputSpec != null, "Attempting to access unknown side input %s.", view);
    KvCoder<?, ?> kvCoder = (KvCoder) sideInputSpec.getCoder();

    ByteString.Output encodedWindowOut = ByteString.newOutput();
    try {
      sideInputSpec
          .getWindowCoder()
          .encode(sideInputSpec.getWindowMappingFn().getSideInputWindow(window), encodedWindowOut);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    ByteString encodedWindow = encodedWindowOut.toByteString();

    StateKey.Builder cacheKeyBuilder = StateKey.newBuilder();
    cacheKeyBuilder
        .getMultimapSideInputBuilder()
        .setPtransformId(ptransformId)
        .setSideInputId(tag.getId())
        .setWindow(encodedWindow);
    return (T)
        stateKeyObjectCache.computeIfAbsent(
            cacheKeyBuilder.build(),
            key ->
                sideInputSpec
                    .getViewFn()
                    .apply(
                        new MultimapSideInput<>(
                            beamFnStateClient,
                            processBundleInstructionId.get(),
                            ptransformId,
                            tag.getId(),
                            encodedWindow,
                            kvCoder.getKeyCoder(),
                            kvCoder.getValueCoder())));
  }

  @Override
  public <T> boolean contains(PCollectionView<T> view) {
    return sideInputSpecMap.containsKey(view.getTagInternal());
  }

  @Override
  public boolean isEmpty() {
    return sideInputSpecMap.isEmpty();
  }

  @Override
  public <T> ValueState<T> bindValue(String id, StateSpec<ValueState<T>> spec, Coder<T> coder) {
    return (ValueState<T>)
        stateKeyObjectCache.computeIfAbsent(
            createBagUserStateKey(id),
            new Function<StateKey, Object>() {
              @Override
              public Object apply(StateKey key) {
                return new ValueState<T>() {
                  private final BagUserState<T> impl = createBagUserState(id, coder);

                  @Override
                  public void clear() {
                    impl.clear();
                  }

                  @Override
                  public void write(T input) {
                    impl.clear();
                    impl.append(input);
                  }

                  @Override
                  public T read() {
                    Iterator<T> value = impl.get().iterator();
                    if (value.hasNext()) {
                      return value.next();
                    } else {
                      return null;
                    }
                  }

                  @Override
                  public ValueState<T> readLater() {
                    // TODO: Support prefetching.
                    return this;
                  }
                };
              }
            });
  }

  @Override
  public <T> BagState<T> bindBag(String id, StateSpec<BagState<T>> spec, Coder<T> elemCoder) {
    return (BagState<T>)
        stateKeyObjectCache.computeIfAbsent(
            createBagUserStateKey(id),
            new Function<StateKey, Object>() {
              @Override
              public Object apply(StateKey key) {
                return new BagState<T>() {
                  private final BagUserState<T> impl = createBagUserState(id, elemCoder);

                  @Override
                  public void add(T value) {
                    impl.append(value);
                  }

                  @Override
                  public ReadableState<Boolean> isEmpty() {
                    return ReadableStates.immediate(!impl.get().iterator().hasNext());
                  }

                  @Override
                  public Iterable<T> read() {
                    return impl.get();
                  }

                  @Override
                  public BagState<T> readLater() {
                    // TODO: Support prefetching.
                    return this;
                  }

                  @Override
                  public void clear() {
                    impl.clear();
                  }
                };
              }
            });
  }

  @Override
  public <T> SetState<T> bindSet(String id, StateSpec<SetState<T>> spec, Coder<T> elemCoder) {
    throw new UnsupportedOperationException("TODO: Add support for a map state to the Fn API.");
  }

  @Override
  public <KeyT, ValueT> MapState<KeyT, ValueT> bindMap(
      String id,
      StateSpec<MapState<KeyT, ValueT>> spec,
      Coder<KeyT> mapKeyCoder,
      Coder<ValueT> mapValueCoder) {
    throw new UnsupportedOperationException("TODO: Add support for a map state to the Fn API.");
  }

  @Override
  public <ElementT, AccumT, ResultT> CombiningState<ElementT, AccumT, ResultT> bindCombining(
      String id,
      StateSpec<CombiningState<ElementT, AccumT, ResultT>> spec,
      Coder<AccumT> accumCoder,
      CombineFn<ElementT, AccumT, ResultT> combineFn) {
    return (CombiningState<ElementT, AccumT, ResultT>)
        stateKeyObjectCache.computeIfAbsent(
            createBagUserStateKey(id),
            new Function<StateKey, Object>() {
              @Override
              public Object apply(StateKey key) {
                // TODO: Support squashing accumulators depending on whether we know of all
                // remote accumulators and local accumulators or just local accumulators.
                return new CombiningState<ElementT, AccumT, ResultT>() {
                  private final BagUserState<AccumT> impl = createBagUserState(id, accumCoder);

                  @Override
                  public AccumT getAccum() {
                    Iterator<AccumT> iterator = impl.get().iterator();
                    if (iterator.hasNext()) {
                      return iterator.next();
                    }
                    return combineFn.createAccumulator();
                  }

                  @Override
                  public void addAccum(AccumT accum) {
                    Iterator<AccumT> iterator = impl.get().iterator();

                    // Only merge if there was a prior value
                    if (iterator.hasNext()) {
                      accum = combineFn.mergeAccumulators(ImmutableList.of(iterator.next(), accum));
                      // Since there was a prior value, we need to clear.
                      impl.clear();
                    }

                    impl.append(accum);
                  }

                  @Override
                  public AccumT mergeAccumulators(Iterable<AccumT> accumulators) {
                    return combineFn.mergeAccumulators(accumulators);
                  }

                  @Override
                  public CombiningState<ElementT, AccumT, ResultT> readLater() {
                    return this;
                  }

                  @Override
                  public ResultT read() {
                    Iterator<AccumT> iterator = impl.get().iterator();
                    if (iterator.hasNext()) {
                      return combineFn.extractOutput(iterator.next());
                    }
                    return combineFn.defaultValue();
                  }

                  @Override
                  public void add(ElementT value) {
                    AccumT newAccumulator = combineFn.addInput(getAccum(), value);
                    impl.clear();
                    impl.append(newAccumulator);
                  }

                  @Override
                  public ReadableState<Boolean> isEmpty() {
                    return ReadableStates.immediate(!impl.get().iterator().hasNext());
                  }

                  @Override
                  public void clear() {
                    impl.clear();
                  }
                };
              }
            });
  }

  @Override
  public <ElementT, AccumT, ResultT>
  CombiningState<ElementT, AccumT, ResultT> bindCombiningWithContext(
      String id,
      StateSpec<CombiningState<ElementT, AccumT, ResultT>> spec,
      Coder<AccumT> accumCoder,
      CombineFnWithContext<ElementT, AccumT, ResultT> combineFn) {
    return (CombiningState<ElementT, AccumT, ResultT>)
        stateKeyObjectCache.computeIfAbsent(
            createBagUserStateKey(id),
            key ->
                bindCombining(
                    id,
                    spec,
                    accumCoder,
                    CombineFnUtil.bindContext(
                        combineFn,
                        new StateContext<BoundedWindow>() {
                          @Override
                          public PipelineOptions getPipelineOptions() {
                            return pipelineOptions;
                          }

                          @Override
                          public <T> T sideInput(PCollectionView<T> view) {
                            return get(view, currentWindowSupplier.get());
                          }

                          @Override
                          public BoundedWindow window() {
                            return currentWindowSupplier.get();
                          }
                        })));
  }

  /**
   * @deprecated The Fn API has no plans to implement WatermarkHoldState as of this writing and is
   * waiting on resolution of BEAM-2535.
   */
  @Override
  @Deprecated
  public WatermarkHoldState bindWatermark(
      String id, StateSpec<WatermarkHoldState> spec, TimestampCombiner timestampCombiner) {
    throw new UnsupportedOperationException("WatermarkHoldState is unsupported by the Fn API.");
  }

  private <T> BagUserState<T> createBagUserState(String stateId, Coder<T> valueCoder) {
    BagUserState<T> rval =
        new BagUserState<>(
            beamFnStateClient,
            processBundleInstructionId.get(),
            ptransformId,
            stateId,
            encodedCurrentWindowSupplier.get(),
            encodedCurrentKeySupplier.get(),
            valueCoder);
    stateFinalizers.add(rval::asyncClose);
    return rval;
  }

  private StateKey createBagUserStateKey(String stateId) {
    StateKey.Builder builder = StateKey.newBuilder();
    builder
        .getBagUserStateBuilder()
        .setWindow(encodedCurrentWindowSupplier.get())
        .setKey(encodedCurrentKeySupplier.get())
        .setPtransformId(ptransformId)
        .setUserStateId(stateId);
    return builder.build();
  }

  public void finalizeState() {
    // Persist all dirty state cells
    try {
      for (ThrowingRunnable runnable : stateFinalizers) {
        runnable.run();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
