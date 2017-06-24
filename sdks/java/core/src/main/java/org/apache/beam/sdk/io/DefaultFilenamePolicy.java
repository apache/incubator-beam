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

import static com.google.common.base.MoreObjects.firstNonNull;

import com.google.common.annotations.VisibleForTesting;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.text.DecimalFormat;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import org.apache.beam.sdk.coders.AtomicCoder;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.coders.CoderException;
import org.apache.beam.sdk.coders.StringUtf8Coder;
import org.apache.beam.sdk.io.FileBasedSink.FilenamePolicy;
import org.apache.beam.sdk.io.FileBasedSink.OutputFileHints;
import org.apache.beam.sdk.io.fs.ResolveOptions.StandardResolveOptions;
import org.apache.beam.sdk.io.fs.ResourceId;
import org.apache.beam.sdk.options.ValueProvider;
import org.apache.beam.sdk.options.ValueProvider.StaticValueProvider;
import org.apache.beam.sdk.transforms.display.DisplayData;
import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.apache.beam.sdk.transforms.windowing.GlobalWindow;
import org.apache.beam.sdk.transforms.windowing.IntervalWindow;
import org.apache.beam.sdk.transforms.windowing.PaneInfo;
import org.apache.beam.sdk.transforms.windowing.PaneInfo.Timing;

/**
 * A default {@link FilenamePolicy} for windowed and unwindowed files. This policy is constructed
 * using three parameters that together define the output name of a sharded file, in conjunction
 * with the number of shards, index of the particular file, current window and pane information,
 * using {@link #constructName}.
 *
 * <p>Most users will use this {@link DefaultFilenamePolicy}. For more advanced
 * uses in generating different files for each window and other sharding controls, see the
 * {@code WriteOneFilePerWindow} example pipeline.
 */
public final class DefaultFilenamePolicy extends FilenamePolicy {
  /** The default sharding name template. */
  public static final String DEFAULT_UNWINDOWED_SHARD_TEMPLATE = ShardNameTemplate.INDEX_OF_MAX;

  /** The default windowed sharding name template used when writing windowed files.
   *  This is used as default in cases when user did not specify shard template to
   *  be used and there is a need to write windowed files. In cases when user does
   *  specify shard template to be used then provided template will be used for both
   *  windowed and non-windowed file names.
   */
  private static final String DEFAULT_WINDOWED_SHARD_TEMPLATE =
      "W-P" + DEFAULT_UNWINDOWED_SHARD_TEMPLATE;

  /*
   * pattern for both windowed and non-windowed file names.
   */
  private static final Pattern SHARD_FORMAT_RE = Pattern.compile("(S+|N+|W|P)");

  /**
   * Encapsulates constructor parameters to {@link DefaultFilenamePolicy}.
   *
   * <p>This is used as the {@code DestinationT} argument to allow {@link DefaultFilenamePolicy}
   * objects to be dynamically generated.
   */
  public static class Params implements Serializable {
    private ValueProvider<ResourceId> baseFilename;
    private String shardTemplate;
    private String suffix;

    public Params(ValueProvider<ResourceId> baseFilename, String shardTemplate, String suffix) {
      this.baseFilename = baseFilename;
      this.shardTemplate = shardTemplate;
      this.suffix = suffix;
    }

    public Params(ResourceId baseFilename, String shardTemplate, String suffix) {
      this(StaticValueProvider.of(baseFilename), shardTemplate, suffix);
    }

    /**
     * A helper function to construct a {@link DefaultFilenamePolicy.Params} using the standard
     * filename parameters, namely a provided {@link ResourceId} for the output prefix, and
     * possibly-null shard name template and suffix.
     *
     * <p>Any filename component of the provided resource will be used as the filename prefix.
     *
     * <p>If provided, the shard name template will be used; otherwise {@link
     * #DEFAULT_UNWINDOWED_SHARD_TEMPLATE} will be used for non-windowed file names and {@link
     * #DEFAULT_WINDOWED_SHARD_TEMPLATE} will be used for windowed file names.
     *
     * <p>If provided, the suffix will be used; otherwise the files will have an empty suffix.
     */
    public static Params fromStandardParameters(
        ValueProvider<ResourceId> baseFilename,
        @Nullable String shardTemplate,
        @Nullable String filenameSuffix,
        boolean windowedWrites) {
      // Pick the appropriate default policy based on whether windowed writes are being performed.
      String defaultTemplate =
          windowedWrites ? DEFAULT_WINDOWED_SHARD_TEMPLATE : DEFAULT_UNWINDOWED_SHARD_TEMPLATE;
      return new Params(baseFilename,
          firstNonNull(shardTemplate, defaultTemplate),
          firstNonNull(filenameSuffix, ""));
    }
  }

  /**
   */
  public static class ParamsCoder extends AtomicCoder<Params> {
    private static final ParamsCoder INSTANCE = new ParamsCoder();
    private Coder<String> stringCoder = StringUtf8Coder.of();

    public static ParamsCoder of() {
      return INSTANCE;
    }

    @Override
    public void encode(Params value, OutputStream outStream)
        throws IOException {
      if (value == null) {
        throw new CoderException("cannot encode a null value");
      }
      stringCoder.encode(value.baseFilename.get().toString(), outStream);
      stringCoder.encode(value.shardTemplate, outStream);
      stringCoder.encode(value.suffix, outStream);
    }

    @Override
    public Params decode(InputStream inStream) throws IOException {
      ResourceId prefix = FileBasedSink.convertToFileResourceIfPossible(
          stringCoder.decode(inStream));
      String shardTemplate = stringCoder.decode(inStream);
      String suffix = stringCoder.decode(inStream);
      return new Params(prefix, shardTemplate, suffix);
    }
  }

  private final Params params;
  /**
   * Constructs a new {@link DefaultFilenamePolicy}.
   *
   * @see DefaultFilenamePolicy for more information on the arguments to this function.
   */
  @VisibleForTesting
  DefaultFilenamePolicy(Params params) {
    this.params = params;

  }

  /**
   * Construct a {@link DefaultFilenamePolicy}.
   *
   * <p>This is a shortcut for {@code DefaultFilenamePolicy.fromParams(Params
   * .fromStandardParameters(baseFilename, shardTemplate, filenameSuffix, windowedWrites)}.
   */
  public static DefaultFilenamePolicy fromStandardParameters(
      ValueProvider<ResourceId> baseFilename,
      @Nullable String shardTemplate,
      @Nullable String filenameSuffix,
      boolean windowedWrites) {
    return fromParams(
        Params.fromStandardParameters(baseFilename, shardTemplate, filenameSuffix, windowedWrites));
  }

  /**
   * Construct a {@link DefaultFilenamePolicy} from a {@link Params} object.
   */
  public static DefaultFilenamePolicy fromParams(Params params) {
    return new DefaultFilenamePolicy(params);
  }

  /**
   * Constructs a fully qualified name from components.
   *
   * <p>The name is built from a base filename, shard template (with shard numbers
   * applied), and a suffix.  All components are required, but may be empty
   * strings.
   *
   * <p>Within a shard template, repeating sequences of the letters "S" or "N"
   * are replaced with the shard number, or number of shards respectively.
   * "P" is replaced with by stringification of current pane.
   * "W" is replaced by stringification of current window.
   *
   * <p>The numbers are formatted with leading zeros to match the length of the
   * repeated sequence of letters.
   *
   * <p>For example, if baseFilename = "path/to/output", shardTemplate = "-SSS-of-NNN", and
   * suffix = ".txt", with shardNum = 1 and numShards = 100, the following is
   * produced:  "path/to/output-001-of-100.txt".
   */
  static ResourceId constructName(
      ResourceId baseFilename, String shardTemplate, String suffix, int shardNum,
      int numShards,
      String paneStr, String windowStr) {
    String prefix = extractFilename(baseFilename);
    // Matcher API works with StringBuffer, rather than StringBuilder.
    StringBuffer sb = new StringBuffer();
    sb.append(prefix);

    Matcher m = SHARD_FORMAT_RE.matcher(shardTemplate);
    while (m.find()) {
      boolean isCurrentShardNum = (m.group(1).charAt(0) == 'S');
      boolean isNumberOfShards = (m.group(1).charAt(0) == 'N');
      boolean isPane = (m.group(1).charAt(0) == 'P') && paneStr != null;
      boolean isWindow = (m.group(1).charAt(0) == 'W') && windowStr != null;

      char[] zeros = new char[m.end() - m.start()];
      Arrays.fill(zeros, '0');
      DecimalFormat df = new DecimalFormat(String.valueOf(zeros));
      if (isCurrentShardNum) {
        String formatted = df.format(shardNum);
        m.appendReplacement(sb, formatted);
      } else if (isNumberOfShards) {
        String formatted = df.format(numShards);
        m.appendReplacement(sb, formatted);
      } else if (isPane) {
        m.appendReplacement(sb, paneStr);
      } else if (isWindow) {
        m.appendReplacement(sb, windowStr);
      }
    }
    m.appendTail(sb);

    sb.append(suffix);
    return baseFilename.getCurrentDirectory().resolve(sb.toString(),
        StandardResolveOptions.RESOLVE_FILE);
  }

  @Override
  @Nullable
  public ResourceId unwindowedFilename(Context context, OutputFileHints outputFileHints) {
    return constructName(
        params.baseFilename.get(),
        params.shardTemplate,
        params.suffix + outputFileHints.getSuggestedFilenameSuffix(),
        context.getShardNumber(),
        context.getNumShards(),
        null,
        null);
  }

  @Override
  public ResourceId windowedFilename(
      WindowedContext context, OutputFileHints outputFileHints) {
    final PaneInfo paneInfo = context.getPaneInfo();
    String paneStr = paneInfoToString(paneInfo);
    String windowStr = windowToString(context.getWindow());
    return constructName(params.baseFilename.get(), params.shardTemplate,
    params.suffix + outputFileHints.getSuggestedFilenameSuffix(),
        context.getShardNumber(), context.getNumShards(), paneStr, windowStr);
  }

  /*
   * Since not all windows have toString() that is nice or is compatible to be a part of file name.
   */
  private String windowToString(BoundedWindow window) {
    if (window instanceof GlobalWindow) {
      return "GlobalWindow";
    }
    if (window instanceof IntervalWindow) {
      IntervalWindow iw = (IntervalWindow) window;
      return String.format("%s-%s", iw.start().toString(), iw.end().toString());
    }
    return window.toString();
  }

  private String paneInfoToString(PaneInfo paneInfo) {
    String paneString = String.format("pane-%d", paneInfo.getIndex());
    if (paneInfo.getTiming() == Timing.LATE) {
      paneString = String.format("%s-late", paneString);
    }
    if (paneInfo.isLast()) {
      paneString = String.format("%s-last", paneString);
    }
    return paneString;
  }

  @Override
  public void populateDisplayData(DisplayData.Builder builder) {
    String filenamePattern;
    if (params.baseFilename.isAccessible()) {
      filenamePattern = String.format("%s%s%s", params.baseFilename.get(),
          params.shardTemplate, params.suffix);
    } else {
      filenamePattern = String.format("%s%s%s", params.baseFilename, params.shardTemplate,
          params.suffix);
    }
    builder.add(
        DisplayData.item("filenamePattern", filenamePattern)
            .withLabel("Filename Pattern"));
  }


  private static String extractFilename(ResourceId input) {
    if (input.isDirectory()) {
      return "";
    } else {
      return firstNonNull(input.getFilename(), "");
    }
  }
}
