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
package org.apache.beam.sdk.io;

import static org.apache.beam.vendor.guava.v26_0_jre.com.google.common.base.Preconditions.checkArgument;
import static org.apache.beam.vendor.guava.v26_0_jre.com.google.common.base.Preconditions.checkNotNull;

import com.google.auto.value.AutoValue;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.apache.beam.sdk.annotations.Experimental;
import org.apache.beam.sdk.annotations.Experimental.Kind;
import org.apache.beam.sdk.annotations.Internal;
import org.apache.beam.sdk.coders.CannotProvideCoderException;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.coders.Coder.NonDeterministicException;
import org.apache.beam.sdk.coders.IterableCoder;
import org.apache.beam.sdk.coders.KvCoder;
import org.apache.beam.sdk.coders.ListCoder;
import org.apache.beam.sdk.coders.ShardedKeyCoder;
import org.apache.beam.sdk.coders.StringUtf8Coder;
import org.apache.beam.sdk.coders.VarIntCoder;
import org.apache.beam.sdk.io.FileBasedSink.DynamicDestinations;
import org.apache.beam.sdk.io.FileBasedSink.FileResult;
import org.apache.beam.sdk.io.FileBasedSink.FileResultCoder;
import org.apache.beam.sdk.io.FileBasedSink.WriteOperation;
import org.apache.beam.sdk.io.FileBasedSink.Writer;
import org.apache.beam.sdk.io.fs.ResourceId;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.ValueProvider;
import org.apache.beam.sdk.options.ValueProvider.StaticValueProvider;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.Flatten;
import org.apache.beam.sdk.transforms.GroupByKey;
import org.apache.beam.sdk.transforms.GroupIntoBatches;
import org.apache.beam.sdk.transforms.MapElements;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.Reify;
import org.apache.beam.sdk.transforms.Reshuffle;
import org.apache.beam.sdk.transforms.SimpleFunction;
import org.apache.beam.sdk.transforms.Values;
import org.apache.beam.sdk.transforms.View;
import org.apache.beam.sdk.transforms.WithKeys;
import org.apache.beam.sdk.transforms.display.DisplayData;
import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.apache.beam.sdk.transforms.windowing.DefaultTrigger;
import org.apache.beam.sdk.transforms.windowing.GlobalWindow;
import org.apache.beam.sdk.transforms.windowing.GlobalWindows;
import org.apache.beam.sdk.transforms.windowing.PaneInfo;
import org.apache.beam.sdk.transforms.windowing.Window;
import org.apache.beam.sdk.util.CoderUtils;
import org.apache.beam.sdk.util.IdentityWindowFn;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollection.IsBounded;
import org.apache.beam.sdk.values.PCollectionList;
import org.apache.beam.sdk.values.PCollectionTuple;
import org.apache.beam.sdk.values.PCollectionView;
import org.apache.beam.sdk.values.PCollectionViews;
import org.apache.beam.sdk.values.PDone;
import org.apache.beam.sdk.values.PValue;
import org.apache.beam.sdk.values.ShardedKey;
import org.apache.beam.sdk.values.TupleTag;
import org.apache.beam.sdk.values.TupleTagList;
import org.apache.beam.sdk.values.ValueInSingleWindow;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.base.Objects;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.collect.ArrayListMultimap;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.collect.Lists;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.collect.Maps;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.collect.Multimap;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.hash.Hashing;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.joda.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link PTransform} that writes to a {@link FileBasedSink}. A write begins with a sequential
 * global initialization of a sink, followed by a parallel write, and ends with a sequential
 * finalization of the write. The output of a write is {@link PDone}.
 *
 * <p>By default, every bundle in the input {@link PCollection} will be processed by a {@link
 * WriteOperation}, so the number of output will vary based on runner behavior, though at least 1
 * output will always be produced. The exact parallelism of the write stage can be controlled using
 * {@link WriteFiles#withNumShards}, typically used to control how many files are produced or to
 * globally limit the number of workers connecting to an external service. However, this option can
 * often hurt performance: it adds an additional {@link GroupByKey} to the pipeline.
 *
 * <p>Example usage with runner-determined sharding:
 *
 * <pre>{@code p.apply(WriteFiles.to(new MySink(...)));}</pre>
 *
 * <p>Example usage with a fixed number of shards:
 *
 * <pre>{@code p.apply(WriteFiles.to(new MySink(...)).withNumShards(3));}</pre>
 */
@Experimental(Kind.SOURCE_SINK)
@AutoValue
@SuppressWarnings({
  "nullness", // TODO(https://issues.apache.org/jira/browse/BEAM-10402)
  "rawtypes"
})
public abstract class WriteFiles<UserT, DestinationT, OutputT>
    extends PTransform<PCollection<UserT>, WriteFilesResult<DestinationT>> {
  private static final Logger LOG = LoggerFactory.getLogger(WriteFiles.class);

  /** For internal use by runners. */
  @Internal
  public static final Class<? extends WriteFiles> CONCRETE_CLASS = AutoValue_WriteFiles.class;

  // The maximum number of file writers to keep open in a single bundle at a time, since file
  // writers default to 64mb buffers. This comes into play when writing per-window files.
  // The first 20 files from a single WriteFiles transform will write files inline in the
  // transform. Anything beyond that might be shuffled.
  // Keep in mind that specific runners may decide to run multiple bundles in parallel, based on
  // their own policy.
  private static final int DEFAULT_MAX_NUM_WRITERS_PER_BUNDLE = 20;

  // When we spill records, shard the output keys to prevent hotspots.
  // We could consider making this a parameter.
  private static final int SPILLED_RECORD_SHARDING_FACTOR = 10;

  // The record count and buffering duration to trigger flushing records to a tmp file. Mainly used
  // for writing unbounded data to avoid generating too many small files.
  private static final int FILE_TRIGGERING_RECORD_COUNT = 100000;
  private static final Duration FILE_TRIGGERING_RECORD_BUFFERING_DURATION =
      Duration.standardSeconds(5);

  static final int UNKNOWN_SHARDNUM = -1;
  static final int DUMMY_SHARDNUM = 0;
  private @Nullable WriteOperation<DestinationT, OutputT> writeOperation;

  /**
   * Creates a {@link WriteFiles} transform that writes to the given {@link FileBasedSink}, letting
   * the runner control how many different shards are produced.
   */
  public static <UserT, DestinationT, OutputT> WriteFiles<UserT, DestinationT, OutputT> to(
      FileBasedSink<UserT, DestinationT, OutputT> sink) {
    checkArgument(sink != null, "sink can not be null");
    return new AutoValue_WriteFiles.Builder<UserT, DestinationT, OutputT>()
        .setSink(sink)
        .setComputeNumShards(null)
        .setNumShardsProvider(null)
        .setWindowedWrites(false)
        .setMaxNumWritersPerBundle(DEFAULT_MAX_NUM_WRITERS_PER_BUNDLE)
        .setSideInputs(sink.getDynamicDestinations().getSideInputs())
        .build();
  }

  public abstract FileBasedSink<UserT, DestinationT, OutputT> getSink();

  public abstract @Nullable PTransform<PCollection<UserT>, PCollectionView<Integer>>
      getComputeNumShards();

  // We don't use a side input for static sharding, as we want this value to be updatable
  // when a pipeline is updated.

  public abstract @Nullable ValueProvider<Integer> getNumShardsProvider();

  public abstract boolean getWindowedWrites();

  abstract int getMaxNumWritersPerBundle();

  abstract List<PCollectionView<?>> getSideInputs();

  public abstract @Nullable ShardingFunction<UserT, DestinationT> getShardingFunction();

  abstract Builder<UserT, DestinationT, OutputT> toBuilder();

  @AutoValue.Builder
  abstract static class Builder<UserT, DestinationT, OutputT> {
    abstract Builder<UserT, DestinationT, OutputT> setSink(
        FileBasedSink<UserT, DestinationT, OutputT> sink);

    abstract Builder<UserT, DestinationT, OutputT> setComputeNumShards(
        @Nullable PTransform<PCollection<UserT>, PCollectionView<Integer>> computeNumShards);

    abstract Builder<UserT, DestinationT, OutputT> setNumShardsProvider(
        @Nullable ValueProvider<Integer> numShardsProvider);

    abstract Builder<UserT, DestinationT, OutputT> setWindowedWrites(boolean windowedWrites);

    abstract Builder<UserT, DestinationT, OutputT> setMaxNumWritersPerBundle(
        int maxNumWritersPerBundle);

    abstract Builder<UserT, DestinationT, OutputT> setSideInputs(
        List<PCollectionView<?>> sideInputs);

    abstract Builder<UserT, DestinationT, OutputT> setShardingFunction(
        @Nullable ShardingFunction<UserT, DestinationT> shardingFunction);

    abstract WriteFiles<UserT, DestinationT, OutputT> build();
  }

  @Override
  public Map<TupleTag<?>, PValue> getAdditionalInputs() {
    return PCollectionViews.toAdditionalInputs(getSideInputs());
  }

  /**
   * Returns a new {@link WriteFiles} that will write to the current {@link FileBasedSink} using the
   * specified number of shards.
   *
   * <p>This option should be used sparingly as it can hurt performance. See {@link WriteFiles} for
   * more information.
   *
   * <p>A value less than or equal to 0 will be equivalent to the default behavior of
   * runner-determined sharding.
   */
  public WriteFiles<UserT, DestinationT, OutputT> withNumShards(int numShards) {
    if (numShards > 0) {
      return withNumShards(StaticValueProvider.of(numShards));
    }
    return withRunnerDeterminedSharding();
  }

  /**
   * Returns a new {@link WriteFiles} that will write to the current {@link FileBasedSink} using the
   * {@link ValueProvider} specified number of shards.
   *
   * <p>This option should be used sparingly as it can hurt performance. See {@link WriteFiles} for
   * more information.
   */
  public WriteFiles<UserT, DestinationT, OutputT> withNumShards(
      ValueProvider<Integer> numShardsProvider) {
    return toBuilder().setNumShardsProvider(numShardsProvider).build();
  }

  /** Set the maximum number of writers created in a bundle before spilling to shuffle. */
  public WriteFiles<UserT, DestinationT, OutputT> withMaxNumWritersPerBundle(
      int maxNumWritersPerBundle) {
    return toBuilder().setMaxNumWritersPerBundle(maxNumWritersPerBundle).build();
  }

  public WriteFiles<UserT, DestinationT, OutputT> withSideInputs(
      List<PCollectionView<?>> sideInputs) {
    return toBuilder().setSideInputs(sideInputs).build();
  }

  /**
   * Returns a new {@link WriteFiles} that will write to the current {@link FileBasedSink} using the
   * specified {@link PTransform} to compute the number of shards.
   *
   * <p>This option should be used sparingly as it can hurt performance. See {@link WriteFiles} for
   * more information.
   */
  public WriteFiles<UserT, DestinationT, OutputT> withSharding(
      PTransform<PCollection<UserT>, PCollectionView<Integer>> sharding) {
    checkArgument(
        sharding != null, "sharding can not be null. Use withRunnerDeterminedSharding() instead.");
    return toBuilder().setComputeNumShards(sharding).build();
  }

  /**
   * Returns a new {@link WriteFiles} that will write to the current {@link FileBasedSink} with
   * runner-determined sharding.
   */
  public WriteFiles<UserT, DestinationT, OutputT> withRunnerDeterminedSharding() {
    return toBuilder().setComputeNumShards(null).setNumShardsProvider(null).build();
  }

  /**
   * Returns a new {@link WriteFiles} that will write to the current {@link FileBasedSink} using the
   * specified sharding function to assign shard for inputs.
   */
  public WriteFiles<UserT, DestinationT, OutputT> withShardingFunction(
      ShardingFunction<UserT, DestinationT> shardingFunction) {
    return toBuilder().setShardingFunction(shardingFunction).build();
  }

  /**
   * Returns a new {@link WriteFiles} that writes preserves windowing on it's input.
   *
   * <p>If this option is not specified, windowing and triggering are replaced by {@link
   * GlobalWindows} and {@link DefaultTrigger}.
   *
   * <p>If there is no data for a window, no output shards will be generated for that window. If a
   * window triggers multiple times, then more than a single output shard might be generated
   * multiple times; it's up to the sink implementation to keep these output shards unique.
   *
   * <p>This option can only be used if {@link #withNumShards(int)} is also set to a positive value.
   */
  public WriteFiles<UserT, DestinationT, OutputT> withWindowedWrites() {
    return toBuilder().setWindowedWrites(true).build();
  }

  /**
   * Returns a new {@link WriteFiles} that writes all data without spilling, simplifying the
   * pipeline. This option should not be used with {@link #withMaxNumWritersPerBundle(int)} and it
   * will eliminate this limit possibly causing many writers to be opened. Use with caution.
   *
   * <p>This option only applies to writes {@link #withRunnerDeterminedSharding()}.
   */
  public WriteFiles<UserT, DestinationT, OutputT> withNoSpilling() {
    return toBuilder().setMaxNumWritersPerBundle(-1).build();
  }

  @Override
  public void validate(PipelineOptions options) {
    getSink().validate(options);
  }

  @Override
  public WriteFilesResult<DestinationT> expand(PCollection<UserT> input) {
    if (input.isBounded() == IsBounded.UNBOUNDED) {
      checkArgument(
          getWindowedWrites(),
          "Must use windowed writes when applying %s to an unbounded PCollection",
          WriteFiles.class.getSimpleName());
    }
    this.writeOperation = getSink().createWriteOperation();
    this.writeOperation.setWindowedWrites(getWindowedWrites());

    if (!getWindowedWrites()) {
      // Re-window the data into the global window and remove any existing triggers.
      input =
          input.apply(
              "RewindowIntoGlobal",
              Window.<UserT>into(new GlobalWindows())
                  .triggering(DefaultTrigger.of())
                  .discardingFiredPanes());
    }

    Coder<DestinationT> destinationCoder;
    try {
      destinationCoder =
          getDynamicDestinations()
              .getDestinationCoderWithDefault(input.getPipeline().getCoderRegistry());
      destinationCoder.verifyDeterministic();
    } catch (CannotProvideCoderException | NonDeterministicException e) {
      throw new RuntimeException(e);
    }
    @SuppressWarnings("unchecked")
    Coder<BoundedWindow> windowCoder =
        (Coder<BoundedWindow>) input.getWindowingStrategy().getWindowFn().windowCoder();
    FileResultCoder<DestinationT> fileResultCoder =
        FileResultCoder.of(windowCoder, destinationCoder);

    PCollectionView<Integer> numShardsView =
        (getComputeNumShards() == null) ? null : input.apply(getComputeNumShards());

    boolean fixedSharding = getComputeNumShards() != null || getNumShardsProvider() != null;
    PCollection<List<FileResult<DestinationT>>> tempFileResults;
    if (fixedSharding) {
      tempFileResults =
          input
              .apply(
                  "WriteShardedBundlesToTempFiles",
                  new WriteShardedBundlesToTempFiles(
                      destinationCoder, fileResultCoder, numShardsView))
              .apply("GatherTempFileResults", new GatherResults<>(fileResultCoder));
    } else {
      if (input.isBounded() == IsBounded.BOUNDED) {
        tempFileResults =
            input
                .apply(
                    "WriteUnshardedBundlesToTempFiles",
                    new WriteUnshardedBundlesToTempFiles(destinationCoder, fileResultCoder))
                .apply("GatherTempFileResults", new GatherResults<>(fileResultCoder));
      } else {
        tempFileResults =
            input.apply(
                "WriteAutoShardedBundlesToTempFiles",
                new WriteAutoShardedBundlesToTempFiles(destinationCoder, fileResultCoder));
      }
    }

    return tempFileResults.apply(
        "FinalizeTempFileBundles", new FinalizeTempFileBundles(numShardsView, destinationCoder));
  }

  @Override
  public void populateDisplayData(DisplayData.Builder builder) {
    super.populateDisplayData(builder);
    builder
        .add(DisplayData.item("sink", getSink().getClass()).withLabel("WriteFiles Sink"))
        .include("sink", getSink());
    if (getComputeNumShards() != null) {
      builder.include("sharding", getComputeNumShards());
    } else {
      builder.addIfNotNull(
          DisplayData.item("numShards", getNumShardsProvider())
              .withLabel("Fixed Number of Shards"));
    }
  }

  private DynamicDestinations<UserT, DestinationT, OutputT> getDynamicDestinations() {
    return (DynamicDestinations<UserT, DestinationT, OutputT>)
        writeOperation.getSink().getDynamicDestinations();
  }

  private class GatherResults<ResultT>
      extends PTransform<PCollection<ResultT>, PCollection<List<ResultT>>> {
    private final Coder<ResultT> resultCoder;

    private GatherResults(Coder<ResultT> resultCoder) {
      this.resultCoder = resultCoder;
    }

    @Override
    public PCollection<List<ResultT>> expand(PCollection<ResultT> input) {
      if (getWindowedWrites()) {
        // Reshuffle the results to make them stable against retries.
        // Use a single void key to maximize size of bundles for finalization.
        return input
            .apply("Add void key", WithKeys.of((Void) null))
            .apply("Reshuffle", Reshuffle.of())
            .apply("Drop key", Values.create())
            .apply("Gather bundles", ParDo.of(new GatherBundlesPerWindowFn<>()))
            .setCoder(ListCoder.of(resultCoder))
            // Reshuffle one more time to stabilize the contents of the bundle lists to finalize.
            .apply(Reshuffle.viaRandomKey());
      } else {
        // Pass results via a side input rather than reshuffle, because we need to get an empty
        // iterable to finalize if there are no results.
        return input
            .getPipeline()
            .apply(Reify.viewInGlobalWindow(input.apply(View.asList()), ListCoder.of(resultCoder)));
      }
    }
  }

  private class WriteUnshardedBundlesToTempFiles
      extends PTransform<PCollection<UserT>, PCollection<FileResult<DestinationT>>> {
    private final Coder<DestinationT> destinationCoder;
    private final Coder<FileResult<DestinationT>> fileResultCoder;

    private WriteUnshardedBundlesToTempFiles(
        Coder<DestinationT> destinationCoder, Coder<FileResult<DestinationT>> fileResultCoder) {
      this.destinationCoder = destinationCoder;
      this.fileResultCoder = fileResultCoder;
    }

    @Override
    public PCollection<FileResult<DestinationT>> expand(PCollection<UserT> input) {
      if (getMaxNumWritersPerBundle() < 0) {
        return input
            .apply(
                "WritedUnshardedBundles",
                ParDo.of(new WriteUnshardedTempFilesFn(null, destinationCoder))
                    .withSideInputs(getSideInputs()))
            .setCoder(fileResultCoder);
      }
      TupleTag<FileResult<DestinationT>> writtenRecordsTag = new TupleTag<>("writtenRecords");
      TupleTag<KV<ShardedKey<Integer>, UserT>> unwrittenRecordsTag =
          new TupleTag<>("unwrittenRecords");
      PCollectionTuple writeTuple =
          input.apply(
              "WriteUnshardedBundles",
              ParDo.of(new WriteUnshardedTempFilesFn(unwrittenRecordsTag, destinationCoder))
                  .withSideInputs(getSideInputs())
                  .withOutputTags(writtenRecordsTag, TupleTagList.of(unwrittenRecordsTag)));
      PCollection<FileResult<DestinationT>> writtenBundleFiles =
          writeTuple.get(writtenRecordsTag).setCoder(fileResultCoder);
      // Any "spilled" elements are written using WriteShardedBundles. Assign shard numbers in
      // finalize to stay consistent with what WriteWindowedBundles does.
      PCollection<FileResult<DestinationT>> writtenSpilledFiles =
          writeTuple
              .get(unwrittenRecordsTag)
              .setCoder(KvCoder.of(ShardedKeyCoder.of(VarIntCoder.of()), input.getCoder()))
              // Here we group by a synthetic shard number in the range [0, spill factor),
              // just for the sake of getting some parallelism within each destination when
              // writing the spilled records, whereas the non-spilled records don't have a shard
              // number assigned at all. Drop the shard number on the spilled records so that
              // shard numbers are assigned together to both the spilled and non-spilled files in
              // finalize.
              .apply("GroupUnwritten", GroupByKey.create())
              .apply(
                  "WriteUnwritten",
                  ParDo.of(new WriteShardsIntoTempFilesFn()).withSideInputs(getSideInputs()))
              .setCoder(fileResultCoder)
              .apply(
                  "DropShardNum",
                  ParDo.of(
                      new DoFn<FileResult<DestinationT>, FileResult<DestinationT>>() {
                        @ProcessElement
                        public void process(ProcessContext c) {
                          c.output(c.element().withShard(UNKNOWN_SHARDNUM));
                        }
                      }));
      return PCollectionList.of(writtenBundleFiles)
          .and(writtenSpilledFiles)
          .apply(Flatten.pCollections())
          .setCoder(fileResultCoder);
    }
  }

  /**
   * Writes all the elements in a bundle using a {@link Writer} produced by the {@link
   * WriteOperation} associated with the {@link FileBasedSink}.
   */
  private class WriteUnshardedTempFilesFn extends DoFn<UserT, FileResult<DestinationT>> {
    private final @Nullable TupleTag<KV<ShardedKey<Integer>, UserT>> unwrittenRecordsTag;
    private final Coder<DestinationT> destinationCoder;

    // Initialized in startBundle()
    private @Nullable Map<WriterKey<DestinationT>, Writer<DestinationT, OutputT>> writers;

    private int spilledShardNum = UNKNOWN_SHARDNUM;

    WriteUnshardedTempFilesFn(
        @Nullable TupleTag<KV<ShardedKey<Integer>, UserT>> unwrittenRecordsTag,
        Coder<DestinationT> destinationCoder) {
      this.unwrittenRecordsTag = unwrittenRecordsTag;
      this.destinationCoder = destinationCoder;
    }

    @StartBundle
    public void startBundle(StartBundleContext c) {
      // Reset state in case of reuse. We need to make sure that each bundle gets unique writers.
      writers = Maps.newHashMap();
    }

    @ProcessElement
    public void processElement(ProcessContext c, BoundedWindow window) throws Exception {
      getDynamicDestinations().setSideInputAccessorFromProcessContext(c);
      PaneInfo paneInfo = c.pane();
      // If we are doing windowed writes, we need to ensure that we have separate files for
      // data in different windows/panes. Similar for dynamic writes, make sure that different
      // destinations go to different writers.
      // In the case of unwindowed writes, the window and the pane will always be the same, and
      // the map will only have a single element.
      DestinationT destination = getDynamicDestinations().getDestination(c.element());
      WriterKey<DestinationT> key = new WriterKey<>(window, c.pane(), destination);
      Writer<DestinationT, OutputT> writer = writers.get(key);
      if (writer == null) {
        if (getMaxNumWritersPerBundle() < 0 || writers.size() <= getMaxNumWritersPerBundle()) {
          String uuid = UUID.randomUUID().toString();
          LOG.info(
              "Opening writer {} for window {} pane {} destination {}",
              uuid,
              window,
              paneInfo,
              destination);
          writer = writeOperation.createWriter();
          writer.setDestination(destination);
          writer.open(uuid);
          writers.put(key, writer);
          LOG.debug("Done opening writer");
        } else {
          if (spilledShardNum == UNKNOWN_SHARDNUM) {
            // Cache the random value so we only call ThreadLocalRandom once per DoFn instance.
            spilledShardNum = ThreadLocalRandom.current().nextInt(SPILLED_RECORD_SHARDING_FACTOR);
          } else {
            spilledShardNum = (spilledShardNum + 1) % SPILLED_RECORD_SHARDING_FACTOR;
          }
          c.output(
              unwrittenRecordsTag,
              KV.of(
                  ShardedKey.of(hashDestination(destination, destinationCoder), spilledShardNum),
                  c.element()));
          return;
        }
      }
      writeOrClose(writer, getDynamicDestinations().formatRecord(c.element()));
    }

    @FinishBundle
    public void finishBundle(FinishBundleContext c) throws Exception {
      for (Map.Entry<WriterKey<DestinationT>, Writer<DestinationT, OutputT>> entry :
          writers.entrySet()) {
        WriterKey<DestinationT> key = entry.getKey();
        Writer<DestinationT, OutputT> writer = entry.getValue();
        try {
          writer.close();
        } catch (Exception e) {
          // If anything goes wrong, make sure to delete the temporary file.
          writer.cleanup();
          throw e;
        }
        BoundedWindow window = key.window;
        c.output(
            new FileResult<>(
                writer.getOutputFile(), UNKNOWN_SHARDNUM, window, key.paneInfo, key.destination),
            window.maxTimestamp(),
            window);
      }
    }
  }

  private static <DestinationT, OutputT> void writeOrClose(
      Writer<DestinationT, OutputT> writer, OutputT t) throws Exception {
    try {
      writer.write(t);
    } catch (Exception e) {
      try {
        writer.close();
        // If anything goes wrong, make sure to delete the temporary file.
        writer.cleanup();
      } catch (Exception closeException) {
        if (closeException instanceof InterruptedException) {
          // Do not silently ignore interrupted state.
          Thread.currentThread().interrupt();
        }
        // Do not mask the exception that caused the write to fail.
        e.addSuppressed(closeException);
      }
      throw e;
    }
  }

  private static class WriterKey<DestinationT> {
    private final BoundedWindow window;
    private final PaneInfo paneInfo;
    private final DestinationT destination;

    WriterKey(BoundedWindow window, PaneInfo paneInfo, DestinationT destination) {
      this.window = window;
      this.paneInfo = paneInfo;
      this.destination = destination;
    }

    @Override
    public boolean equals(@Nullable Object o) {
      if (!(o instanceof WriterKey)) {
        return false;
      }
      WriterKey other = (WriterKey) o;
      return Objects.equal(window, other.window)
          && Objects.equal(paneInfo, other.paneInfo)
          && Objects.equal(destination, other.destination);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(window, paneInfo, destination);
    }
  }

  // Hash the destination in a manner that we can then use as a key in a GBK. Since Java's
  // hashCode isn't guaranteed to be stable across machines, we instead serialize the destination
  // and use murmur3_32 to hash it. We enforce that destinationCoder must be deterministic, so
  // this can be used as a key.
  private static <DestinationT> int hashDestination(
      DestinationT destination, Coder<DestinationT> destinationCoder) throws IOException {
    return Hashing.murmur3_32()
        .hashBytes(CoderUtils.encodeToByteArray(destinationCoder, destination))
        .asInt();
  }

  private class WriteShardedBundlesToTempFiles
      extends PTransform<PCollection<UserT>, PCollection<FileResult<DestinationT>>> {
    private final Coder<DestinationT> destinationCoder;
    private final Coder<FileResult<DestinationT>> fileResultCoder;
    private final @Nullable PCollectionView<Integer> numShardsView;

    private WriteShardedBundlesToTempFiles(
        Coder<DestinationT> destinationCoder,
        Coder<FileResult<DestinationT>> fileResultCoder,
        @Nullable PCollectionView<Integer> numShardsView) {
      this.destinationCoder = destinationCoder;
      this.fileResultCoder = fileResultCoder;
      this.numShardsView = numShardsView;
    }

    @Override
    public PCollection<FileResult<DestinationT>> expand(PCollection<UserT> input) {
      List<PCollectionView<?>> shardingSideInputs = Lists.newArrayList(getSideInputs());
      if (numShardsView != null) {
        shardingSideInputs.add(numShardsView);
      }

      ShardingFunction<UserT, DestinationT> shardingFunction =
          getShardingFunction() == null
              ? new RandomShardingFunction(destinationCoder)
              : getShardingFunction();

      return input
          .apply(
              "ApplyShardingKey",
              ParDo.of(new ApplyShardingFunctionFn(shardingFunction, numShardsView))
                  .withSideInputs(shardingSideInputs))
          .setCoder(KvCoder.of(ShardedKeyCoder.of(VarIntCoder.of()), input.getCoder()))
          .apply("GroupIntoShards", GroupByKey.create())
          .apply(
              "WriteShardsIntoTempFiles",
              ParDo.of(new WriteShardsIntoTempFilesFn()).withSideInputs(getSideInputs()))
          .setCoder(fileResultCoder);
    }
  }

  private class WriteAutoShardedBundlesToTempFiles
      extends PTransform<PCollection<UserT>, PCollection<List<FileResult<DestinationT>>>> {
    private final Coder<DestinationT> destinationCoder;
    private final Coder<FileResult<DestinationT>> fileResultCoder;

    private WriteAutoShardedBundlesToTempFiles(
        Coder<DestinationT> destinationCoder, Coder<FileResult<DestinationT>> fileResultCoder) {
      this.destinationCoder = destinationCoder;
      this.fileResultCoder = fileResultCoder;
    }

    @Override
    public PCollection<List<FileResult<DestinationT>>> expand(PCollection<UserT> input) {
      // Auto-sharding is achieved via GroupIntoBatches.WithShardedKey which shards, groups and at
      // the same time batches the input records. The sharding behavior depends on runners. The
      // batching is per window and we also emit the batches if there are a certain number of
      // records buffered or they have been buffered for a certain time, controlled by
      // FILE_TRIGGERING_RECORD_COUNT and BUFFERING_DURATION respectively.

      PCollection<KV<Integer, UserT>> keyedInput =
          input
              .apply(
                  "KeyedByDestinationHash",
                  ParDo.of(
                      new DoFn<UserT, KV<Integer, UserT>>() {
                        @ProcessElement
                        public void processElement(@Element UserT element, ProcessContext context)
                            throws Exception {
                          getDynamicDestinations().setSideInputAccessorFromProcessContext(context);
                          DestinationT destination =
                              getDynamicDestinations().getDestination(context.element());
                          context.output(
                              KV.of(hashDestination(destination, destinationCoder), element));
                        }
                      }))
              .setCoder(KvCoder.of(VarIntCoder.of(), input.getCoder()));

      if (input.getWindowingStrategy().needsMerge()) {
        // To make sure that the elements are partitioned by original input windows and to work with
        // merging windows such as sessions, we firstly group the elements by destination to reify
        // the window, if the window needs to be merged, and then perform sharded writes for each
        // destination and window pair.
        Coder<BoundedWindow> windowCoder =
            (Coder<BoundedWindow>) input.getWindowingStrategy().getWindowFn().windowCoder();
        return reifyWindowAndWrite(keyedInput, windowCoder, input.getCoder());
      } else {
        return write(keyedInput, input.getCoder());
      }
    }

    private PCollection<List<FileResult<DestinationT>>> reifyWindowAndWrite(
        PCollection<KV<Integer, UserT>> keyedInput,
        Coder<BoundedWindow> windowCoder,
        Coder<UserT> inputCoder) {
      PCollection<KV<ValueInSingleWindow<Integer>, UserT>> windowedInput =
          keyedInput
              .apply("GroupByDestination", GroupByKey.create())
              .apply(
                  "ReifyWindowInKey",
                  ParDo.of(
                      new DoFn<
                          KV<Integer, Iterable<UserT>>, KV<ValueInSingleWindow<Integer>, UserT>>() {
                        @ProcessElement
                        public void processElement(
                            @Element KV<Integer, Iterable<UserT>> elements,
                            BoundedWindow window,
                            PaneInfo pane,
                            OutputReceiver<KV<ValueInSingleWindow<Integer>, UserT>> r) {
                          for (UserT element : elements.getValue()) {
                            // Assign the max timestamp of the window to the element since we want
                            // all the elements in the same window with different timestamps to be
                            // grouped together.
                            r.output(
                                KV.of(
                                    ValueInSingleWindow.of(
                                        elements.getKey(), window.maxTimestamp(), window, pane),
                                    element));
                          }
                        }
                      }))
              .setCoder(
                  KvCoder.of(
                      ValueInSingleWindow.Coder.of(VarIntCoder.of(), windowCoder), inputCoder));

      // Shard and write elements to temp files.
      PCollection<KV<ValueInSingleWindow<DestinationT>, FileResult<DestinationT>>> tempFiles =
          windowedInput
              .apply(
                  "IdentityWindow",
                  Window.<KV<ValueInSingleWindow<Integer>, UserT>>into(
                          new IdentityWindowFn<>(windowCoder))
                      .discardingFiredPanes())
              .apply(
                  "ShardAndBatch",
                  GroupIntoBatches.<ValueInSingleWindow<Integer>, UserT>ofSize(
                          FILE_TRIGGERING_RECORD_COUNT)
                      .withMaxBufferingDuration(FILE_TRIGGERING_RECORD_BUFFERING_DURATION)
                      .withShardedKey())
              .apply(
                  "WriteShardsIntoTempFiles",
                  ParDo.of(new WriteWindowedShardsIntoTempFilesFn())
                      .withSideInputs(getSideInputs()))
              .setCoder(
                  KvCoder.of(
                      ValueInSingleWindow.Coder.of(destinationCoder, windowCoder),
                      fileResultCoder));
      return gatherFilesInWindow(tempFiles);
    }

    private PCollection<List<FileResult<DestinationT>>> write(
        PCollection<KV<Integer, UserT>> keyedInput, Coder<UserT> inputCoder) {
      PCollection<KV<org.apache.beam.sdk.util.ShardedKey<Integer>, Iterable<UserT>>> shardedInput =
          keyedInput
              .apply(
                  "ShardAndBatch",
                  GroupIntoBatches.<Integer, UserT>ofSize(FILE_TRIGGERING_RECORD_COUNT)
                      .withMaxBufferingDuration(FILE_TRIGGERING_RECORD_BUFFERING_DURATION)
                      .withShardedKey())
              .setCoder(
                  KvCoder.of(
                      org.apache.beam.sdk.util.ShardedKey.Coder.of(VarIntCoder.of()),
                      IterableCoder.of(inputCoder)));

      // Write grouped elements to temp files.
      PCollection<KV<DestinationT, FileResult<DestinationT>>> tempFiles =
          shardedInput
              .apply(
                  "AddDummyShard",
                  MapElements.via(
                      new SimpleFunction<
                          KV<org.apache.beam.sdk.util.ShardedKey<Integer>, Iterable<UserT>>,
                          KV<ShardedKey<Integer>, Iterable<UserT>>>() {
                        @Override
                        public KV<ShardedKey<Integer>, Iterable<UserT>> apply(
                            KV<org.apache.beam.sdk.util.ShardedKey<Integer>, Iterable<UserT>>
                                input) {
                          // Add dummy shard since it is required by WriteShardsIntoTempFilesFn. It
                          // will be dropped after we generate the temp files.
                          return KV.of(
                              ShardedKey.of(input.getKey().getKey(), DUMMY_SHARDNUM),
                              input.getValue());
                        }
                      }))
              .setCoder(
                  KvCoder.of(ShardedKeyCoder.of(VarIntCoder.of()), IterableCoder.of(inputCoder)))
              .apply(
                  "WriteShardsIntoTempFiles",
                  ParDo.of(new WriteShardsIntoTempFilesFn()).withSideInputs(getSideInputs()))
              .setCoder(fileResultCoder)
              .apply(
                  "DropShardNum",
                  ParDo.of(
                      new DoFn<FileResult<DestinationT>, FileResult<DestinationT>>() {
                        @ProcessElement
                        public void process(ProcessContext c) {
                          c.output(c.element().withShard(UNKNOWN_SHARDNUM));
                        }
                      }))
              .apply(
                  "KeyedByDestination",
                  WithKeys.of(
                      new SimpleFunction<FileResult<DestinationT>, DestinationT>() {
                        @Override
                        public DestinationT apply(FileResult<DestinationT> input) {
                          return input.getDestination();
                        }
                      }))
              .setCoder(KvCoder.of(destinationCoder, fileResultCoder));

      return gatherFilesInWindow(tempFiles);
    }

    private <KeyT> PCollection<List<FileResult<DestinationT>>> gatherFilesInWindow(
        PCollection<KV<KeyT, FileResult<DestinationT>>> tempFiles) {
      // Group temp file results by destination again to gather all the files in the same window.
      // This is needed since we don't have shard idx associated with each temp file so have to rely
      // on the indexing within a bundle.
      return tempFiles
          .apply("GatherResultsPerWindow", GroupByKey.create())
          .apply(
              "ExtractValuesToList",
              ParDo.of(
                  new DoFn<
                      KV<KeyT, Iterable<FileResult<DestinationT>>>,
                      List<FileResult<DestinationT>>>() {
                    @ProcessElement
                    public void processElement(
                        @Element KV<KeyT, Iterable<FileResult<DestinationT>>> element,
                        ProcessContext c) {
                      List<FileResult<DestinationT>> result = new ArrayList<>();
                      for (FileResult<DestinationT> e : element.getValue()) {
                        result.add(e);
                      }
                      c.output(result);
                    }
                  }))
          .setCoder(ListCoder.of(fileResultCoder));
    }
  }

  private class RandomShardingFunction implements ShardingFunction<UserT, DestinationT> {
    private final Coder<DestinationT> destinationCoder;

    private int shardNumber;

    RandomShardingFunction(Coder<DestinationT> destinationCoder) {
      this.destinationCoder = destinationCoder;
      this.shardNumber = UNKNOWN_SHARDNUM;
    }

    @Override
    public ShardedKey<Integer> assignShardKey(
        DestinationT destination, UserT element, int shardCount) throws Exception {

      if (shardNumber == UNKNOWN_SHARDNUM) {
        // We want to desynchronize the first record sharding key for each instance of
        // ApplyShardingKey, so records in a small PCollection will be statistically balanced.
        shardNumber = ThreadLocalRandom.current().nextInt(shardCount);
      } else {
        shardNumber = (shardNumber + 1) % shardCount;
      }
      // We avoid using destination itself as a sharding key, because destination is often large.
      // e.g. when using {@link DefaultFilenamePolicy}, the destination contains the entire path
      // to the file. Often most of the path is constant across all destinations, just the path
      // suffix is appended by the destination function. Instead we key by a 32-bit hash (carefully
      // chosen to be guaranteed stable), and call getDestination again in the next ParDo to resolve
      // the destinations. This does mean that multiple destinations might end up on the same shard,
      // however the number of collisions should be small, so there's no need to worry about memory
      // issues.
      return ShardedKey.of(hashDestination(destination, destinationCoder), shardNumber);
    }
  }

  private class ApplyShardingFunctionFn extends DoFn<UserT, KV<ShardedKey<Integer>, UserT>> {

    private final ShardingFunction<UserT, DestinationT> shardingFn;
    private final @Nullable PCollectionView<Integer> numShardsView;

    ApplyShardingFunctionFn(
        ShardingFunction<UserT, DestinationT> shardingFn,
        @Nullable PCollectionView<Integer> numShardsView) {
      this.numShardsView = numShardsView;
      this.shardingFn = shardingFn;
    }

    @ProcessElement
    public void processElement(ProcessContext context) throws Exception {
      getDynamicDestinations().setSideInputAccessorFromProcessContext(context);
      final int shardCount;
      if (numShardsView != null) {
        shardCount = context.sideInput(numShardsView);
      } else {
        checkNotNull(getNumShardsProvider());
        shardCount = getNumShardsProvider().get();
      }
      checkArgument(
          shardCount > 0,
          "Must have a positive number of shards specified for non-runner-determined sharding."
              + " Got %s",
          shardCount);

      DestinationT destination = getDynamicDestinations().getDestination(context.element());
      ShardedKey<Integer> shardKey =
          shardingFn.assignShardKey(destination, context.element(), shardCount);
      context.output(KV.of(shardKey, context.element()));
    }
  }

  private class WriteWindowedShardsIntoTempFilesFn
      extends DoFn<
          KV<org.apache.beam.sdk.util.ShardedKey<ValueInSingleWindow<Integer>>, Iterable<UserT>>,
          KV<ValueInSingleWindow<DestinationT>, FileResult<DestinationT>>> {
    @ProcessElement
    public void processElement(ProcessContext c) throws Exception {
      getDynamicDestinations().setSideInputAccessorFromProcessContext(c);
      ValueInSingleWindow<Integer> key = c.element().getKey().getKey();
      Iterable<UserT> value = c.element().getValue();
      List<FileResult<DestinationT>> results =
          writeTempFiles(value, key.getWindow(), key.getPane(), UNKNOWN_SHARDNUM);
      for (FileResult<DestinationT> result : results) {
        c.output(
            KV.of(
                ValueInSingleWindow.of(
                    result.getDestination(), key.getTimestamp(), key.getWindow(), key.getPane()),
                result));
      }
    }
  }

  private class WriteShardsIntoTempFilesFn
      extends DoFn<KV<ShardedKey<Integer>, Iterable<UserT>>, FileResult<DestinationT>> {
    @ProcessElement
    public void processElement(ProcessContext c, BoundedWindow window) throws Exception {
      getDynamicDestinations().setSideInputAccessorFromProcessContext(c);
      int shard = c.element().getKey().getShardNumber();
      checkArgument(
          shard != UNKNOWN_SHARDNUM,
          "Shard should have been set, but is unset for element %s",
          c.element());
      List<FileResult<DestinationT>> results =
          writeTempFiles(c.element().getValue(), window, c.pane(), shard);
      for (FileResult<DestinationT> result : results) {
        c.output(result);
      }
    }
  }

  private List<FileResult<DestinationT>> writeTempFiles(
      Iterable<UserT> elements, BoundedWindow window, PaneInfo pane, int shard) throws Exception {
    List<FileResult<DestinationT>> results = new ArrayList<>();
    // Since we key by a 32-bit hash of the destination, there might be multiple destinations
    // in this iterable. The number of destinations is generally very small (1000s or less), so
    // there will rarely be hash collisions.
    Map<DestinationT, Writer<DestinationT, OutputT>> writers = Maps.newHashMap();
    for (UserT input : elements) {
      DestinationT destination = getDynamicDestinations().getDestination(input);
      Writer<DestinationT, OutputT> writer = writers.get(destination);
      if (writer == null) {
        String uuid = UUID.randomUUID().toString();
        LOG.info(
            "Opening writer {} for window {} pane {} destination {}",
            uuid,
            window,
            pane,
            destination);
        writer = writeOperation.createWriter();
        writer.setDestination(destination);
        writer.open(uuid);
        writers.put(destination, writer);
      }
      writeOrClose(writer, getDynamicDestinations().formatRecord(input));
    }

    // Close all writers.
    for (Map.Entry<DestinationT, Writer<DestinationT, OutputT>> entry : writers.entrySet()) {
      Writer<DestinationT, OutputT> writer = entry.getValue();
      try {
        // Close the writer; if this throws let the error propagate.
        writer.close();
      } catch (Exception e) {
        // If anything goes wrong, make sure to delete the temporary file.
        writer.cleanup();
        throw e;
      }
      results.add(new FileResult<>(writer.getOutputFile(), shard, window, pane, entry.getKey()));
    }
    return results;
  }

  private class FinalizeTempFileBundles
      extends PTransform<
          PCollection<List<FileResult<DestinationT>>>, WriteFilesResult<DestinationT>> {
    private final @Nullable PCollectionView<Integer> numShardsView;
    private final Coder<DestinationT> destinationCoder;

    private FinalizeTempFileBundles(
        @Nullable PCollectionView<Integer> numShardsView, Coder<DestinationT> destinationCoder) {
      this.numShardsView = numShardsView;
      this.destinationCoder = destinationCoder;
    }

    @Override
    public WriteFilesResult<DestinationT> expand(
        PCollection<List<FileResult<DestinationT>>> input) {

      List<PCollectionView<?>> finalizeSideInputs = Lists.newArrayList(getSideInputs());
      if (numShardsView != null) {
        finalizeSideInputs.add(numShardsView);
      }
      PCollection<KV<DestinationT, String>> outputFilenames =
          input
              .apply("Finalize", ParDo.of(new FinalizeFn()).withSideInputs(finalizeSideInputs))
              .setCoder(KvCoder.of(destinationCoder, StringUtf8Coder.of()))
              // Reshuffle the filenames to make sure they are observable downstream
              // only after each one is done finalizing.
              .apply(Reshuffle.viaRandomKey());

      TupleTag<KV<DestinationT, String>> perDestinationOutputFilenamesTag =
          new TupleTag<>("perDestinationOutputFilenames");
      return WriteFilesResult.in(
          input.getPipeline(), perDestinationOutputFilenamesTag, outputFilenames);
    }

    private class FinalizeFn
        extends DoFn<List<FileResult<DestinationT>>, KV<DestinationT, String>> {
      @ProcessElement
      public void process(ProcessContext c) throws Exception {
        getDynamicDestinations().setSideInputAccessorFromProcessContext(c);
        @Nullable Integer fixedNumShards;
        if (numShardsView != null) {
          fixedNumShards = c.sideInput(numShardsView);
        } else if (getNumShardsProvider() != null) {
          fixedNumShards = getNumShardsProvider().get();
        } else {
          fixedNumShards = null;
        }
        List<FileResult<DestinationT>> fileResults = Lists.newArrayList(c.element());
        LOG.info("Finalizing {} file results", fileResults.size());
        DestinationT defaultDest = getDynamicDestinations().getDefaultDestination();
        List<KV<FileResult<DestinationT>, ResourceId>> resultsToFinalFilenames =
            fileResults.isEmpty()
                ? writeOperation.finalizeDestination(
                    defaultDest, GlobalWindow.INSTANCE, fixedNumShards, fileResults)
                : finalizeAllDestinations(fileResults, fixedNumShards);
        writeOperation.moveToOutputFiles(resultsToFinalFilenames);
        for (KV<FileResult<DestinationT>, ResourceId> entry : resultsToFinalFilenames) {
          FileResult<DestinationT> res = entry.getKey();
          c.output(KV.of(res.getDestination(), entry.getValue().toString()));
        }
      }
    }
  }

  private List<KV<FileResult<DestinationT>, ResourceId>> finalizeAllDestinations(
      List<FileResult<DestinationT>> fileResults, @Nullable Integer fixedNumShards)
      throws Exception {
    Multimap<KV<DestinationT, BoundedWindow>, FileResult<DestinationT>> res =
        ArrayListMultimap.create();
    for (FileResult<DestinationT> result : fileResults) {
      res.put(KV.of(result.getDestination(), result.getWindow()), result);
    }
    List<KV<FileResult<DestinationT>, ResourceId>> resultsToFinalFilenames = Lists.newArrayList();
    for (Map.Entry<KV<DestinationT, BoundedWindow>, Collection<FileResult<DestinationT>>>
        destEntry : res.asMap().entrySet()) {
      KV<DestinationT, BoundedWindow> destWindow = destEntry.getKey();
      resultsToFinalFilenames.addAll(
          writeOperation.finalizeDestination(
              destWindow.getKey(), destWindow.getValue(), fixedNumShards, destEntry.getValue()));
    }
    return resultsToFinalFilenames;
  }

  private static class GatherBundlesPerWindowFn<T> extends DoFn<T, List<T>> {
    private transient @Nullable Multimap<BoundedWindow, T> bundles = null;

    @StartBundle
    public void startBundle() {
      bundles = ArrayListMultimap.create();
    }

    @ProcessElement
    public void process(ProcessContext c, BoundedWindow w) {
      bundles.put(w, c.element());
    }

    @FinishBundle
    public void finishBundle(FinishBundleContext c) throws Exception {
      for (BoundedWindow w : bundles.keySet()) {
        c.output(Lists.newArrayList(bundles.get(w)), w.maxTimestamp(), w);
      }
    }
  }
}
