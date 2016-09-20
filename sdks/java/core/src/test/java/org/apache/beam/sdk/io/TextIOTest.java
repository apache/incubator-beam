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

import static org.apache.beam.sdk.TestUtils.INTS_ARRAY;
import static org.apache.beam.sdk.TestUtils.LINES_ARRAY;
import static org.apache.beam.sdk.TestUtils.NO_INTS_ARRAY;
import static org.apache.beam.sdk.TestUtils.NO_LINES_ARRAY;
import static org.apache.beam.sdk.io.TextIO.CompressionType.AUTO;
import static org.apache.beam.sdk.io.TextIO.CompressionType.BZIP2;
import static org.apache.beam.sdk.io.TextIO.CompressionType.GZIP;
import static org.apache.beam.sdk.io.TextIO.CompressionType.UNCOMPRESSED;
import static org.apache.beam.sdk.io.TextIO.CompressionType.ZIP;
import static org.apache.beam.sdk.transforms.display.DisplayDataMatchers.hasDisplayItem;
import static org.apache.beam.sdk.transforms.display.DisplayDataMatchers.hasValue;
import static org.apache.beam.sdk.util.IOChannelUtils.resolve;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.annotation.Nullable;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.coders.StringUtf8Coder;
import org.apache.beam.sdk.coders.TextualIntegerCoder;
import org.apache.beam.sdk.coders.VoidCoder;
import org.apache.beam.sdk.io.BoundedSource.BoundedReader;
import org.apache.beam.sdk.io.TextIO.CompressionType;
import org.apache.beam.sdk.io.TextIO.TextSource;
import org.apache.beam.sdk.options.GcsOptions;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.testing.NeedsRunner;
import org.apache.beam.sdk.testing.PAssert;
import org.apache.beam.sdk.testing.RunnableOnService;
import org.apache.beam.sdk.testing.SourceTestUtils;
import org.apache.beam.sdk.testing.TestPipeline;
import org.apache.beam.sdk.testing.TestPipelineOptions;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.display.DisplayData;
import org.apache.beam.sdk.transforms.display.DisplayDataEvaluator;
import org.apache.beam.sdk.util.CoderUtils;
import org.apache.beam.sdk.util.GcsUtil;
import org.apache.beam.sdk.util.IOChannelUtils;
import org.apache.beam.sdk.util.gcsfs.GcsPath;
import org.apache.beam.sdk.values.PCollection;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

/**
 * Tests for TextIO Read and Write transforms.
 */
// TODO: Change the tests to use RunnableOnService instead of NeedsRunner
@RunWith(JUnit4.class)
@SuppressWarnings("unchecked")
public class TextIOTest {

  private static final String MY_HEADER = "myHeader";
  private static final String MY_FOOTER = "myFooter";
  private static final String[] EMPTY = new String[] {};
  private static final String[] TINY =
      new String[] {"Irritable eagle", "Optimistic jay", "Fanciful hawk"};
  private static final String[] LARGE = makeLines(5000);

  private static Path tempFolder;
  private static File emptyTxt;
  private static File tinyTxt;
  private static File largeTxt;
  private static File emptyGz;
  private static File tinyGz;
  private static File largeGz;
  private static File emptyBzip2;
  private static File tinyBzip2;
  private static File largeBzip2;
  private static File emptyZip;
  private static File tinyZip;
  private static File largeZip;

  @Rule
  public ExpectedException expectedException = ExpectedException.none();

  private static File writeToFile(String[] lines, String filename, CompressionType compression)
      throws IOException {
    File file = tempFolder.resolve(filename).toFile();
    OutputStream output = new FileOutputStream(file);
    switch (compression) {
      case UNCOMPRESSED:
        break;
      case GZIP:
        output = new GZIPOutputStream(output);
        break;
      case BZIP2:
        output = new BZip2CompressorOutputStream(output);
        break;
      case ZIP:
        ZipOutputStream zipOutput = new ZipOutputStream(output);
        zipOutput.putNextEntry(new ZipEntry("entry"));
        output = zipOutput;
        break;
      default:
        throw new UnsupportedOperationException(compression.toString());
    }
    writeToStreamAndClose(lines, output);
    return file;
  }

  @BeforeClass
  public static void setupClass() throws IOException {
    IOChannelUtils.registerStandardIOFactories(TestPipeline.testingPipelineOptions());
    tempFolder =  Files.createTempDirectory("TextIOTest");
    // empty files
    emptyTxt = writeToFile(EMPTY, "empty.txt", CompressionType.UNCOMPRESSED);
    emptyGz = writeToFile(EMPTY, "empty.gz", GZIP);
    emptyBzip2 = writeToFile(EMPTY, "empty.bz2", BZIP2);
    emptyZip = writeToFile(EMPTY, "empty.zip", ZIP);
    // tiny files
    tinyTxt = writeToFile(TINY, "tiny.txt", CompressionType.UNCOMPRESSED);
    tinyGz = writeToFile(TINY, "tiny.gz", GZIP);
    tinyBzip2 = writeToFile(TINY, "tiny.bz2", BZIP2);
    tinyZip = writeToFile(TINY, "tiny.zip", ZIP);
    // large files
    largeTxt = writeToFile(LARGE, "large.txt", CompressionType.UNCOMPRESSED);
    largeGz = writeToFile(LARGE, "large.gz", GZIP);
    largeBzip2 = writeToFile(LARGE, "large.bz2", BZIP2);
    largeZip = writeToFile(LARGE, "large.zip", ZIP);
  }

  @AfterClass
  public static void testdownClass() throws IOException {
    Files.walkFileTree(tempFolder, new SimpleFileVisitor<Path>() {
      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        Files.delete(file);
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
        Files.delete(dir);
        return FileVisitResult.CONTINUE;
      }
    });
  }

  private <T> void runTestRead(T[] expected, Coder<T> coder) throws Exception {
    File tmpFile = Files.createTempFile(tempFolder, "file", "txt").toFile();
    String filename = tmpFile.getPath();

    try (PrintStream writer = new PrintStream(new FileOutputStream(tmpFile))) {
      for (T elem : expected) {
        byte[] encodedElem = CoderUtils.encodeToByteArray(coder, elem);
        String line = new String(encodedElem);
        writer.println(line);
      }
    }

    Pipeline p = TestPipeline.create();

    TextIO.Read.Bound<T> read;
    if (coder.equals(StringUtf8Coder.of())) {
      TextIO.Read.Bound<String> readStrings = TextIO.Read.from(filename);
      // T==String
      read = (TextIO.Read.Bound<T>) readStrings;
    } else {
      read = TextIO.Read.from(filename).withCoder(coder);
    }

    PCollection<T> output = p.apply(read);

    PAssert.that(output).containsInAnyOrder(expected);
    p.run();
  }

  @Test
  @Category(NeedsRunner.class)
  public void testReadStrings() throws Exception {
    runTestRead(LINES_ARRAY, StringUtf8Coder.of());
  }

  @Test
  @Category(NeedsRunner.class)
  public void testReadEmptyStrings() throws Exception {
    runTestRead(NO_LINES_ARRAY, StringUtf8Coder.of());
  }

  @Test
  @Category(NeedsRunner.class)
  public void testReadInts() throws Exception {
    runTestRead(INTS_ARRAY, TextualIntegerCoder.of());
  }

  @Test
  @Category(NeedsRunner.class)
  public void testReadEmptyInts() throws Exception {
    runTestRead(NO_INTS_ARRAY, TextualIntegerCoder.of());
  }

  @Test
  @Category(NeedsRunner.class)
  public void testReadNulls() throws Exception {
    runTestRead(new Void[]{null, null, null}, VoidCoder.of());
  }

  @Test
  public void testReadNamed() throws Exception {
    Pipeline p = TestPipeline.create();

    assertEquals(
        "TextIO.Read/Read.out",
        p.apply(TextIO.Read.withoutValidation().from("somefile")).getName());
    assertEquals(
        "MyRead/Read.out",
        p.apply("MyRead", TextIO.Read.withoutValidation().from(emptyTxt.getPath())).getName());
  }

  @Test
  public void testReadDisplayData() {
    TextIO.Read.Bound<?> read = TextIO.Read
        .from("foo.*")
        .withCompressionType(BZIP2)
        .withoutValidation();

    DisplayData displayData = DisplayData.from(read);

    assertThat(displayData, hasDisplayItem("filePattern", "foo.*"));
    assertThat(displayData, hasDisplayItem("compressionType", BZIP2.toString()));
    assertThat(displayData, hasDisplayItem("validation", false));
  }

  @Test
  @Category(RunnableOnService.class)
  public void testPrimitiveReadDisplayData() {
    DisplayDataEvaluator evaluator = DisplayDataEvaluator.create();

    TextIO.Read.Bound<String> read = TextIO.Read
        .from("foobar")
        .withoutValidation();

    Set<DisplayData> displayData = evaluator.displayDataForPrimitiveSourceTransforms(read);
    assertThat("TextIO.Read should include the file prefix in its primitive display data",
        displayData, hasItem(hasDisplayItem(hasValue(startsWith("foobar")))));
  }

  private <T> void runTestWrite(T[] elems, Coder<T> coder) throws Exception {
    runTestWrite(elems, null, null, coder, 1);
  }

  private <T> void runTestWrite(T[] elems, Coder<T> coder, int numShards) throws Exception {
    runTestWrite(elems, null, null, coder, numShards);
  }

  private <T> void runTestWrite(T[] elems, Coder<T> coder, String header, String footer)
      throws Exception {
    runTestWrite(elems, header, footer, coder, 1);
  }

  private <T> void runTestWrite(
      T[] elems, String header, String footer, Coder<T> coder, int numShards) throws Exception {
    String outputName = "file.txt";
    Path baseDir = Files.createTempDirectory(tempFolder, "testwrite");
    String baseFilename = baseDir.resolve(outputName).toString();

    Pipeline p = TestPipeline.create();

    PCollection<T> input = p.apply(Create.of(Arrays.asList(elems)).withCoder(coder));

    TextIO.Write.Bound<T> write;
    if (coder.equals(StringUtf8Coder.of())) {
      TextIO.Write.Bound<String> writeStrings = TextIO.Write.to(baseFilename);
      // T==String
      write = (TextIO.Write.Bound<T>) writeStrings;
    } else {
      write = TextIO.Write.to(baseFilename).withCoder(coder);
    }
    write = write.withHeader(header).withFooter(footer);

    if (numShards == 1) {
      write = write.withoutSharding();
    } else if (numShards > 0) {
      write = write.withNumShards(numShards).withShardNameTemplate(ShardNameTemplate.INDEX_OF_MAX);
    }
    input.apply(write);

    p.run();

    assertOutputFiles(elems, header, footer, coder, numShards, baseDir, outputName,
        write.getShardNameTemplate());
  }

  public static <T> void assertOutputFiles(
      T[] elems,
      final String header,
      final String footer,
      Coder<T> coder,
      int numShards,
      Path rootLocation,
      String outputName,
      String shardNameTemplate)
      throws Exception {
    List<File> expectedFiles = new ArrayList<>();
    if (numShards == 0) {
      String pattern =
          resolve(rootLocation.toAbsolutePath().toString(), outputName + "*");
      for (String expected : IOChannelUtils.getFactory(pattern).match(pattern)) {
        expectedFiles.add(new File(expected));
      }
    } else {
      for (int i = 0; i < numShards; i++) {
        expectedFiles.add(
            new File(
                rootLocation.toString(),
                IOChannelUtils.constructName(outputName, shardNameTemplate, "", i, numShards)));
      }
    }

    List<List<String>> actual = new ArrayList<>();

    for (File tmpFile : expectedFiles) {
      try (BufferedReader reader = new BufferedReader(new FileReader(tmpFile))) {
        List<String> currentFile = new ArrayList<>();
        for (;;) {
          String line = reader.readLine();
          if (line == null) {
            break;
          }
          currentFile.add(line);
        }
        actual.add(currentFile);
      }
    }

    List<String> expectedElements = new ArrayList<>(elems.length);
    for (T elem : elems) {
      byte[] encodedElem = CoderUtils.encodeToByteArray(coder, elem);
      String line = new String(encodedElem);
      expectedElements.add(line);
    }

    List<String> actualElements =
        Lists.newArrayList(
            Iterables.concat(
                FluentIterable
                    .from(actual)
                    .transform(removeHeaderAndFooter(header, footer))
                    .toList()));

    assertThat(actualElements, containsInAnyOrder(expectedElements.toArray()));

    assertTrue(Iterables.all(actual, haveProperHeaderAndFooter(header, footer)));
  }

  private static Function<List<String>, List<String>> removeHeaderAndFooter(final String header,
                                                                            final String footer) {
    return new Function<List<String>, List<String>>() {
      @Nullable
      @Override
      public List<String> apply(List<String> lines) {
        ArrayList<String> newLines = Lists.newArrayList(lines);
        if (header != null) {
          newLines.remove(0);
        }
        if (footer != null) {
          int last = newLines.size() - 1;
          newLines.remove(last);
        }
        return newLines;
      }
    };
  }

  private static Predicate<List<String>> haveProperHeaderAndFooter(final String header,
                                                                   final String footer) {
    return new Predicate<List<String>>() {
      @Override
      public boolean apply(List<String> fileLines) {
        int last = fileLines.size() - 1;
        return (header == null || fileLines.get(0).equals(header))
            && (footer == null || fileLines.get(last).equals(footer));
      }
    };
  }

  @Test
  @Category(NeedsRunner.class)
  public void testWriteStrings() throws Exception {
    runTestWrite(LINES_ARRAY, StringUtf8Coder.of());
  }

  @Test
  @Category(NeedsRunner.class)
  public void testWriteEmptyStringsNoSharding() throws Exception {
    runTestWrite(NO_LINES_ARRAY, StringUtf8Coder.of(), 0);
  }

  @Test
  @Category(NeedsRunner.class)
  public void testWriteEmptyStrings() throws Exception {
    runTestWrite(NO_LINES_ARRAY, StringUtf8Coder.of());
  }

  @Test
  @Category(NeedsRunner.class)
  public void testWriteInts() throws Exception {
    runTestWrite(INTS_ARRAY, TextualIntegerCoder.of());
  }

  @Test
  @Category(NeedsRunner.class)
  public void testWriteEmptyInts() throws Exception {
    runTestWrite(NO_INTS_ARRAY, TextualIntegerCoder.of());
  }

  @Test
  @Category(NeedsRunner.class)
  public void testShardedWrite() throws Exception {
    runTestWrite(LINES_ARRAY, StringUtf8Coder.of(), 5);
  }

  @Test
  @Category(NeedsRunner.class)
  public void testWriteWithHeader() throws Exception {
    runTestWrite(LINES_ARRAY, StringUtf8Coder.of(), MY_HEADER, null);
  }

  @Test
  @Category(NeedsRunner.class)
  public void testWriteWithFooter() throws Exception {
    runTestWrite(LINES_ARRAY, StringUtf8Coder.of(), null, MY_FOOTER);
  }

  @Test
  @Category(NeedsRunner.class)
  public void testWriteWithHeaderAndFooter() throws Exception {
    runTestWrite(LINES_ARRAY, StringUtf8Coder.of(), MY_HEADER, MY_FOOTER);
  }

  @Test
  public void testWriteDisplayData() {
    TextIO.Write.Bound<?> write = TextIO.Write
        .to("foo")
        .withSuffix("bar")
        .withShardNameTemplate("-SS-of-NN-")
        .withNumShards(100)
        .withFooter("myFooter")
        .withHeader("myHeader")
        .withoutValidation();

    DisplayData displayData = DisplayData.from(write);

    assertThat(displayData, hasDisplayItem("filePrefix", "foo"));
    assertThat(displayData, hasDisplayItem("fileSuffix", "bar"));
    assertThat(displayData, hasDisplayItem("fileHeader", "myHeader"));
    assertThat(displayData, hasDisplayItem("fileFooter", "myFooter"));
    assertThat(displayData, hasDisplayItem("shardNameTemplate", "-SS-of-NN-"));
    assertThat(displayData, hasDisplayItem("numShards", 100));
    assertThat(displayData, hasDisplayItem("validation", false));
  }

  @Test
  @Category(RunnableOnService.class)
  @Ignore("[BEAM-436] DirectRunner RunnableOnService tempLocation configuration insufficient")
  public void testPrimitiveWriteDisplayData() throws IOException {
    PipelineOptions options = DisplayDataEvaluator.getDefaultOptions();
    String tempRoot = options.as(TestPipelineOptions.class).getTempRoot();
    String outputPath = IOChannelUtils.getFactory(tempRoot).resolve(tempRoot, "foobar");

    DisplayDataEvaluator evaluator = DisplayDataEvaluator.create();

    TextIO.Write.Bound<?> write = TextIO.Write.to(outputPath);

    Set<DisplayData> displayData = evaluator.displayDataForPrimitiveTransforms(write);
    assertThat("TextIO.Write should include the file prefix in its primitive display data",
        displayData, hasItem(hasDisplayItem(hasValue(startsWith(outputPath)))));
  }

  @Test
  public void testUnsupportedFilePattern() throws IOException {
    // Windows doesn't like resolving paths with * in them.
    String filename = tempFolder.resolve("output@5").toString();

    Pipeline p = TestPipeline.create();

    PCollection<String> input =
        p.apply(Create.of(Arrays.asList(LINES_ARRAY))
            .withCoder(StringUtf8Coder.of()));

    expectedException.expect(IllegalArgumentException.class);
    expectedException.expectMessage("Output name components are not allowed to contain");
    input.apply(TextIO.Write.to(filename));
  }

  /**
   * Recursive wildcards are not supported.
   * This tests "**".
   */
  @Test
  public void testBadWildcardRecursive() throws Exception {
    Pipeline pipeline = TestPipeline.create();

    // Check that applying does fail.
    expectedException.expect(IllegalArgumentException.class);
    expectedException.expectMessage("wildcard");

    pipeline.apply(TextIO.Read.from("gs://bucket/foo**/baz"));
  }

  @Test
  public void testReadWithoutValidationFlag() throws Exception {
    TextIO.Read.Bound<String> read = TextIO.Read.from("gs://bucket/foo*/baz");
    assertTrue(read.needsValidation());
    assertFalse(read.withoutValidation().needsValidation());
  }

  @Test
  public void testWriteWithoutValidationFlag() throws Exception {
    TextIO.Write.Bound<String> write = TextIO.Write.to("gs://bucket/foo/baz");
    assertTrue(write.needsValidation());
    assertFalse(write.withoutValidation().needsValidation());
  }

  @Test
  public void testCompressionTypeIsSet() throws Exception {
    TextIO.Read.Bound<String> read = TextIO.Read.from("gs://bucket/test");
    assertEquals(AUTO, read.getCompressionType());
    read = TextIO.Read.from("gs://bucket/test").withCompressionType(GZIP);
    assertEquals(GZIP, read.getCompressionType());
  }

  /**
   * Helper that writes the given lines (adding a newline in between) to a stream, then closes the
   * stream.
   */
  private static void writeToStreamAndClose(String[] lines, OutputStream outputStream) {
    try (PrintStream writer = new PrintStream(outputStream)) {
      for (String line : lines) {
        writer.println(line);
      }
    }
  }

  /**
   * Helper method that runs TextIO.Read.from(filename).withCompressionType(compressionType)
   * and asserts that the results match the given expected output.
   */
  private static void assertReadingCompressedFileMatchesExpected(
      File file, CompressionType compressionType, String[] expected) {
    Pipeline p = TestPipeline.create();
    TextIO.Read.Bound<String> read =
        TextIO.Read.from(file.getPath()).withCompressionType(compressionType);
    PCollection<String> output = p.apply(read);

    PAssert.that(output).containsInAnyOrder(expected);
    p.run();
  }

  /**
   * Helper to make an array of compressible strings. Returns ["word"i] for i in range(0,n).
   */
  private static String[] makeLines(int n) {
    String[] ret = new String[n];
    for (int i = 0; i < n; ++i) {
      ret[i] = "word" + i;
    }
    return ret;
  }

  /**
   * Tests reading from a small, gzipped file with no .gz extension but GZIP compression set.
   */
  @Test
  @Category(NeedsRunner.class)
  public void testSmallCompressedGzipReadNoExtension() throws Exception {
    File smallGzNoExtension = writeToFile(TINY, "tiny_gz_no_extension", GZIP);
    assertReadingCompressedFileMatchesExpected(smallGzNoExtension, GZIP, TINY);
  }

  /**
   * Tests reading from a small, uncompressed file with .gz extension.
   * This must work in AUTO or GZIP modes. This is needed because some network file systems / HTTP
   * clients will transparently decompress gzipped content.
   */
  @Test
  @Category(NeedsRunner.class)
  public void testSmallCompressedGzipReadActuallyUncompressed() throws Exception {
    File smallGzNotCompressed =
        writeToFile(TINY, "tiny_uncompressed.gz", CompressionType.UNCOMPRESSED);
    // Should work with GZIP compression set.
    assertReadingCompressedFileMatchesExpected(smallGzNotCompressed, GZIP, TINY);
    // Should also work with AUTO mode set.
    assertReadingCompressedFileMatchesExpected(smallGzNotCompressed, AUTO, TINY);
  }

  /**
   * Tests reading from a small, bzip2ed file with no .bz2 extension but BZIP2 compression set.
   */
  @Test
  @Category(NeedsRunner.class)
  public void testSmallCompressedBzip2ReadNoExtension() throws Exception {
    File smallBz2NoExtension = writeToFile(TINY, "tiny_bz2_no_extension", BZIP2);
    assertReadingCompressedFileMatchesExpected(smallBz2NoExtension, BZIP2, TINY);
  }

  /**
   * Create a zip file with the given lines.
   *
   * @param expected A list of expected lines, populated in the zip file.
   * @param filename Optionally zip file name (can be null).
   * @param fieldsEntries Fields to write in zip entries.
   * @return The zip filename.
   * @throws Exception In case of a failure during zip file creation.
   */
  private String createZipFile(List<String> expected, String filename, String[]
      ...
      fieldsEntries)
      throws Exception {
    File tmpFile = tempFolder.resolve(filename).toFile();
    String tmpFileName = tmpFile.getPath();

    ZipOutputStream out = new ZipOutputStream(new FileOutputStream(tmpFile));
    PrintStream writer = new PrintStream(out, true /* auto-flush on write */);

    int index = 0;
    for (String[] entry : fieldsEntries) {
      out.putNextEntry(new ZipEntry(Integer.toString(index)));
      for (String field : entry) {
        writer.println(field);
        expected.add(field);
      }
      out.closeEntry();
      index++;
    }

    writer.close();
    out.close();

    return tmpFileName;
  }

  @Test
  @Category(NeedsRunner.class)
  public void testTxtReadRead() throws Exception {
    // Files with non-compressed extensions should work in AUTO and UNCOMPRESSED modes.
    for (CompressionType type : new CompressionType[] { AUTO, UNCOMPRESSED }) {
      assertReadingCompressedFileMatchesExpected(emptyTxt, type, EMPTY);
      assertReadingCompressedFileMatchesExpected(tinyTxt, type, TINY);
      assertReadingCompressedFileMatchesExpected(largeTxt, type, LARGE);
    }
  }

  @Test
  @Category(NeedsRunner.class)
  public void testGzipCompressedRead() throws Exception {
    // Files with the right extensions should work in AUTO and GZIP modes.
    for (CompressionType type : new CompressionType[] { AUTO, GZIP }) {
      assertReadingCompressedFileMatchesExpected(emptyGz, type, EMPTY);
      assertReadingCompressedFileMatchesExpected(tinyGz, type, TINY);
      assertReadingCompressedFileMatchesExpected(largeGz, type, LARGE);
    }

    // Sanity check that we're properly testing compression.
    assertThat(largeTxt.length(), greaterThan(largeGz.length()));

    // GZIP files with non-gz extension should work in GZIP mode.
    File gzFile = writeToFile(TINY, "tiny_gz_no_extension", GZIP);
    assertReadingCompressedFileMatchesExpected(gzFile, GZIP, TINY);
  }

  @Test
  @Category(NeedsRunner.class)
  public void testBzip2CompressedRead() throws Exception {
    // Files with the right extensions should work in AUTO and BZIP2 modes.
    for (CompressionType type : new CompressionType[] { AUTO, BZIP2 }) {
      assertReadingCompressedFileMatchesExpected(emptyBzip2, type, EMPTY);
      assertReadingCompressedFileMatchesExpected(tinyBzip2, type, TINY);
      assertReadingCompressedFileMatchesExpected(largeBzip2, type, LARGE);
    }

    // Sanity check that we're properly testing compression.
    assertThat(largeTxt.length(), greaterThan(largeBzip2.length()));

    // BZ2 files with non-bz2 extension should work in BZIP2 mode.
    File bz2File = writeToFile(TINY, "tiny_bz2_no_extension", BZIP2);
    assertReadingCompressedFileMatchesExpected(bz2File, BZIP2, TINY);
  }

  @Test
  @Category(NeedsRunner.class)
  public void testZipCompressedRead() throws Exception {
    // Files with the right extensions should work in AUTO and ZIP modes.
    for (CompressionType type : new CompressionType[]{AUTO, ZIP}) {
      assertReadingCompressedFileMatchesExpected(emptyZip, type, EMPTY);
      assertReadingCompressedFileMatchesExpected(tinyZip, type, TINY);
      assertReadingCompressedFileMatchesExpected(largeZip, type, LARGE);
    }

    // Sanity check that we're properly testing compression.
    assertThat(largeTxt.length(), greaterThan(largeZip.length()));

    // Zip files with non-zip extension should work in ZIP mode.
    File zipFile = writeToFile(TINY, "tiny_zip_no_extension", ZIP);
    assertReadingCompressedFileMatchesExpected(zipFile, ZIP, TINY);
  }

  /**
   * Tests a zip file with no entries. This is a corner case not tested elsewhere as the default
   * test zip files have a single entry.
   */
  @Test
  @Category(NeedsRunner.class)
  public void testZipCompressedReadWithNoEntries() throws Exception {
    String filename = createZipFile(new ArrayList<String>(), "empty zip file");
    assertReadingCompressedFileMatchesExpected(new File(filename), CompressionType.ZIP, EMPTY);
  }

  /**
   * Tests a zip file with multiple entries. This is a corner case not tested elsewhere as the
   * default test zip files have a single entry.
   */
  @Test
  @Category(NeedsRunner.class)
  public void testZipCompressedReadWithMultiEntriesFile() throws Exception {
    String[] entry0 = new String[]{"first", "second", "three"};
    String[] entry1 = new String[]{"four", "five", "six"};
    String[] entry2 = new String[]{"seven", "eight", "nine"};

    List<String> expected = new ArrayList<>();

    String filename = createZipFile(expected, "multiple entries", entry0, entry1, entry2);
    assertReadingCompressedFileMatchesExpected(
        new File(filename), CompressionType.ZIP, expected.toArray(new String[]{}));
  }

  /**
   * Read a ZIP compressed file containing data, multiple empty entries, and then more data. We
   * expect just the data back.
   */
  @Test
  @Category(NeedsRunner.class)
  public void testZipCompressedReadWithComplexEmptyAndPresentEntries() throws Exception {
    String filename = createZipFile(
        new ArrayList<String>(),
        "complex empty and present entries",
        new String[]{"cat"},
        new String[]{},
        new String[]{},
        new String[]{"dog"});

    assertReadingCompressedFileMatchesExpected(
        new File(filename), CompressionType.ZIP, new String[] {"cat", "dog"});
  }

  @Test
  public void testTextIOGetName() {
    assertEquals("TextIO.Read", TextIO.Read.from("somefile").getName());
    assertEquals("TextIO.Write", TextIO.Write.to("somefile").getName());
    assertEquals("TextIO.Read", TextIO.Read.from("somefile").toString());
  }

  @Test
  public void testProgressEmptyFile() throws IOException {
    try (BoundedReader<String> reader =
        prepareSource(new byte[0]).createReader(PipelineOptionsFactory.create())) {
      // Check preconditions before starting.
      assertEquals(0.0, reader.getFractionConsumed(), 1e-6);
      assertEquals(0, reader.getSplitPointsConsumed());
      assertEquals(BoundedReader.SPLIT_POINTS_UNKNOWN, reader.getSplitPointsRemaining());

      // Assert empty
      assertFalse(reader.start());

      // Check postconditions after finishing
      assertEquals(1.0, reader.getFractionConsumed(), 1e-6);
      assertEquals(0, reader.getSplitPointsConsumed());
      assertEquals(0, reader.getSplitPointsRemaining());
    }
  }

  @Test
  public void testProgressTextFile() throws IOException {
    String file = "line1\nline2\nline3";
    try (BoundedReader<String> reader =
        prepareSource(file.getBytes()).createReader(PipelineOptionsFactory.create())) {
      // Check preconditions before starting
      assertEquals(0.0, reader.getFractionConsumed(), 1e-6);
      assertEquals(0, reader.getSplitPointsConsumed());
      assertEquals(BoundedReader.SPLIT_POINTS_UNKNOWN, reader.getSplitPointsRemaining());

      // Line 1
      assertTrue(reader.start());
      assertEquals(0, reader.getSplitPointsConsumed());
      assertEquals(BoundedReader.SPLIT_POINTS_UNKNOWN, reader.getSplitPointsRemaining());

      // Line 2
      assertTrue(reader.advance());
      assertEquals(1, reader.getSplitPointsConsumed());
      assertEquals(BoundedReader.SPLIT_POINTS_UNKNOWN, reader.getSplitPointsRemaining());

      // Line 3
      assertTrue(reader.advance());
      assertEquals(2, reader.getSplitPointsConsumed());
      assertEquals(1, reader.getSplitPointsRemaining());

      // Check postconditions after finishing
      assertFalse(reader.advance());
      assertEquals(1.0, reader.getFractionConsumed(), 1e-6);
      assertEquals(3, reader.getSplitPointsConsumed());
      assertEquals(0, reader.getSplitPointsRemaining());
    }
  }

  @Test
  public void testProgressAfterSplitting() throws IOException {
    String file = "line1\nline2\nline3";
    BoundedSource<String> source = prepareSource(file.getBytes());
    BoundedSource<String> remainder;

    // Create the remainder, verifying properties pre- and post-splitting.
    try (BoundedReader<String> readerOrig = source.createReader(PipelineOptionsFactory.create())) {
      // Preconditions.
      assertEquals(0.0, readerOrig.getFractionConsumed(), 1e-6);
      assertEquals(0, readerOrig.getSplitPointsConsumed());
      assertEquals(BoundedReader.SPLIT_POINTS_UNKNOWN, readerOrig.getSplitPointsRemaining());

      // First record, before splitting.
      assertTrue(readerOrig.start());
      assertEquals(0, readerOrig.getSplitPointsConsumed());
      assertEquals(BoundedReader.SPLIT_POINTS_UNKNOWN, readerOrig.getSplitPointsRemaining());

      // Split. 0.1 is in line1, so should now be able to detect last record.
      remainder = readerOrig.splitAtFraction(0.1);
      System.err.println(readerOrig.getCurrentSource());
      assertNotNull(remainder);

      // First record, after splitting.
      assertEquals(0, readerOrig.getSplitPointsConsumed());
      assertEquals(1, readerOrig.getSplitPointsRemaining());

      // Finish and postconditions.
      assertFalse(readerOrig.advance());
      assertEquals(1.0, readerOrig.getFractionConsumed(), 1e-6);
      assertEquals(1, readerOrig.getSplitPointsConsumed());
      assertEquals(0, readerOrig.getSplitPointsRemaining());
    }

    // Check the properties of the remainder.
    try (BoundedReader<String> reader = remainder.createReader(PipelineOptionsFactory.create())) {
      // Preconditions.
      assertEquals(0.0, reader.getFractionConsumed(), 1e-6);
      assertEquals(0, reader.getSplitPointsConsumed());
      assertEquals(BoundedReader.SPLIT_POINTS_UNKNOWN, reader.getSplitPointsRemaining());

      // First record should be line 2.
      assertTrue(reader.start());
      assertEquals(0, reader.getSplitPointsConsumed());
      assertEquals(BoundedReader.SPLIT_POINTS_UNKNOWN, reader.getSplitPointsRemaining());

      // Second record is line 3
      assertTrue(reader.advance());
      assertEquals(1, reader.getSplitPointsConsumed());
      assertEquals(1, reader.getSplitPointsRemaining());

      // Check postconditions after finishing
      assertFalse(reader.advance());
      assertEquals(1.0, reader.getFractionConsumed(), 1e-6);
      assertEquals(2, reader.getSplitPointsConsumed());
      assertEquals(0, reader.getSplitPointsRemaining());
    }
  }

  @Test
  public void testReadEmptyLines() throws Exception {
    runTestReadWithData("\n\n\n".getBytes(StandardCharsets.UTF_8),
        ImmutableList.of("", "", ""));
  }

  @Test
  public void testReadFileWithLineFeedDelimiter() throws Exception {
    runTestReadWithData("asdf\nhjkl\nxyz\n".getBytes(StandardCharsets.UTF_8),
        ImmutableList.of("asdf", "hjkl", "xyz"));
  }

  @Test
  public void testReadFileWithCarriageReturnDelimiter() throws Exception {
    runTestReadWithData("asdf\rhjkl\rxyz\r".getBytes(StandardCharsets.UTF_8),
        ImmutableList.of("asdf", "hjkl", "xyz"));
  }

  @Test
  public void testReadFileWithCarriageReturnAndLineFeedDelimiter() throws Exception {
    runTestReadWithData("asdf\r\nhjkl\r\nxyz\r\n".getBytes(StandardCharsets.UTF_8),
        ImmutableList.of("asdf", "hjkl", "xyz"));
  }

  @Test
  public void testReadFileWithMixedDelimiters() throws Exception {
    runTestReadWithData("asdf\rhjkl\r\nxyz\n".getBytes(StandardCharsets.UTF_8),
        ImmutableList.of("asdf", "hjkl", "xyz"));
  }

  @Test
  public void testReadFileWithLineFeedDelimiterAndNonEmptyBytesAtEnd() throws Exception {
    runTestReadWithData("asdf\nhjkl\nxyz".getBytes(StandardCharsets.UTF_8),
        ImmutableList.of("asdf", "hjkl", "xyz"));
  }

  @Test
  public void testReadFileWithCarriageReturnDelimiterAndNonEmptyBytesAtEnd() throws Exception {
    runTestReadWithData("asdf\rhjkl\rxyz".getBytes(StandardCharsets.UTF_8),
        ImmutableList.of("asdf", "hjkl", "xyz"));
  }

  @Test
  public void testReadFileWithCarriageReturnAndLineFeedDelimiterAndNonEmptyBytesAtEnd()
      throws Exception {
    runTestReadWithData("asdf\r\nhjkl\r\nxyz".getBytes(StandardCharsets.UTF_8),
        ImmutableList.of("asdf", "hjkl", "xyz"));
  }

  @Test
  public void testReadFileWithMixedDelimitersAndNonEmptyBytesAtEnd() throws Exception {
    runTestReadWithData("asdf\rhjkl\r\nxyz".getBytes(StandardCharsets.UTF_8),
        ImmutableList.of("asdf", "hjkl", "xyz"));
  }

  private void runTestReadWithData(byte[] data, List<String> expectedResults) throws Exception {
    TextSource<String> source = prepareSource(data);
    List<String> actual = SourceTestUtils.readFromSource(source, PipelineOptionsFactory.create());
    assertThat(actual, containsInAnyOrder(new ArrayList<>(expectedResults).toArray(new String[0])));
  }

  @Test
  public void testSplittingSourceWithEmptyLines() throws Exception {
    TextSource<String> source = prepareSource("\n\n\n".getBytes(StandardCharsets.UTF_8));
    SourceTestUtils.assertSplitAtFractionExhaustive(source, PipelineOptionsFactory.create());
  }

  @Test
  public void testSplittingSourceWithLineFeedDelimiter() throws Exception {
    TextSource<String> source = prepareSource("asdf\nhjkl\nxyz\n".getBytes(StandardCharsets.UTF_8));
    SourceTestUtils.assertSplitAtFractionExhaustive(source, PipelineOptionsFactory.create());
  }

  @Test
  public void testSplittingSourceWithCarriageReturnDelimiter() throws Exception {
    TextSource<String> source = prepareSource("asdf\rhjkl\rxyz\r".getBytes(StandardCharsets.UTF_8));
    SourceTestUtils.assertSplitAtFractionExhaustive(source, PipelineOptionsFactory.create());
  }

  @Test
  public void testSplittingSourceWithCarriageReturnAndLineFeedDelimiter() throws Exception {
    TextSource<String> source = prepareSource(
        "asdf\r\nhjkl\r\nxyz\r\n".getBytes(StandardCharsets.UTF_8));
    SourceTestUtils.assertSplitAtFractionExhaustive(source, PipelineOptionsFactory.create());
  }

  @Test
  public void testSplittingSourceWithMixedDelimiters() throws Exception {
    TextSource<String> source = prepareSource(
        "asdf\rhjkl\r\nxyz\n".getBytes(StandardCharsets.UTF_8));
    SourceTestUtils.assertSplitAtFractionExhaustive(source, PipelineOptionsFactory.create());
  }

  @Test
  public void testSplittingSourceWithLineFeedDelimiterAndNonEmptyBytesAtEnd() throws Exception {
    TextSource<String> source = prepareSource("asdf\nhjkl\nxyz".getBytes(StandardCharsets.UTF_8));
    SourceTestUtils.assertSplitAtFractionExhaustive(source, PipelineOptionsFactory.create());
  }

  @Test
  public void testSplittingSourceWithCarriageReturnDelimiterAndNonEmptyBytesAtEnd()
      throws Exception {
    TextSource<String> source = prepareSource("asdf\rhjkl\rxyz".getBytes(StandardCharsets.UTF_8));
    SourceTestUtils.assertSplitAtFractionExhaustive(source, PipelineOptionsFactory.create());
  }

  @Test
  public void testSplittingSourceWithCarriageReturnAndLineFeedDelimiterAndNonEmptyBytesAtEnd()
      throws Exception {
    TextSource<String> source = prepareSource(
        "asdf\r\nhjkl\r\nxyz".getBytes(StandardCharsets.UTF_8));
    SourceTestUtils.assertSplitAtFractionExhaustive(source, PipelineOptionsFactory.create());
  }

  @Test
  public void testSplittingSourceWithMixedDelimitersAndNonEmptyBytesAtEnd() throws Exception {
    TextSource<String> source = prepareSource("asdf\rhjkl\r\nxyz".getBytes(StandardCharsets.UTF_8));
    SourceTestUtils.assertSplitAtFractionExhaustive(source, PipelineOptionsFactory.create());
  }

  private TextSource<String> prepareSource(byte[] data) throws IOException {
    Path path = Files.createTempFile(tempFolder, "tempfile", "ext");
    Files.write(path, data);
    return new TextSource<>(path.toString(), StringUtf8Coder.of());
  }

  @Test
  public void testInitialSplitIntoBundlesAutoModeTxt() throws Exception {
    PipelineOptions options = TestPipeline.testingPipelineOptions();
    long desiredBundleSize = 1000;

    // Sanity check: file is at least 2 bundles long.
    assertThat(largeTxt.length(), greaterThan(2 * desiredBundleSize));

    FileBasedSource<String> source = TextIO.Read.from(largeTxt.getPath()).getSource();
    List<? extends FileBasedSource<String>> splits =
        source.splitIntoBundles(desiredBundleSize, options);

    // At least 2 splits and they are equal to reading the whole file.
    assertThat(splits, hasSize(greaterThan(1)));
    SourceTestUtils.assertSourcesEqualReferenceSource(source, splits, options);
  }

 @Test
  public void testInitialSplitIntoBundlesAutoModeGz() throws Exception {
    long desiredBundleSize = 1000;
    PipelineOptions options = TestPipeline.testingPipelineOptions();

    // Sanity check: file is at least 2 bundles long.
    assertThat(largeGz.length(), greaterThan(2 * desiredBundleSize));

    FileBasedSource<String> source = TextIO.Read.from(largeGz.getPath()).getSource();
    List<? extends FileBasedSource<String>> splits =
        source.splitIntoBundles(desiredBundleSize, options);

    // Exactly 1 split, even in AUTO mode, since it is a gzip file.
    assertThat(splits, hasSize(equalTo(1)));
    SourceTestUtils.assertSourcesEqualReferenceSource(source, splits, options);
  }

  @Test
  public void testInitialSplitIntoBundlesGzipModeTxt() throws Exception {
    PipelineOptions options = TestPipeline.testingPipelineOptions();
    long desiredBundleSize = 1000;

    // Sanity check: file is at least 2 bundles long.
    assertThat(largeTxt.length(), greaterThan(2 * desiredBundleSize));

    FileBasedSource<String> source =
        TextIO.Read.from(largeTxt.getPath()).withCompressionType(GZIP).getSource();
    List<? extends FileBasedSource<String>> splits =
        source.splitIntoBundles(desiredBundleSize, options);

    // Exactly 1 split, even though splittable text file, since using GZIP mode.
    assertThat(splits, hasSize(equalTo(1)));
    SourceTestUtils.assertSourcesEqualReferenceSource(source, splits, options);
  }

  @Test
  public void testInitialSplitIntoBundlesGzipModeGz() throws Exception {
    PipelineOptions options = TestPipeline.testingPipelineOptions();
    long desiredBundleSize = 1000;

    // Sanity check: file is at least 2 bundles long.
    assertThat(largeGz.length(), greaterThan(2 * desiredBundleSize));

    FileBasedSource<String> source =
        TextIO.Read.from(largeGz.getPath()).withCompressionType(GZIP).getSource();
    List<? extends FileBasedSource<String>> splits =
        source.splitIntoBundles(desiredBundleSize, options);

    // Exactly 1 split using .gz extension and using GZIP mode.
    assertThat(splits, hasSize(equalTo(1)));
    SourceTestUtils.assertSourcesEqualReferenceSource(source, splits, options);
  }

  ///////////////////////////////////////////////////////////////////////////////////////////////
  // Test "gs://" paths

  private GcsUtil buildMockGcsUtil() throws IOException {
    GcsUtil mockGcsUtil = Mockito.mock(GcsUtil.class);

    // Any request to open gets a new bogus channel
    Mockito
        .when(mockGcsUtil.open(Mockito.any(GcsPath.class)))
        .then(new Answer<SeekableByteChannel>() {
          @Override
          public SeekableByteChannel answer(InvocationOnMock invocation) throws Throwable {
            return FileChannel.open(
                Files.createTempFile("channel-", ".tmp"),
                StandardOpenOption.CREATE, StandardOpenOption.DELETE_ON_CLOSE);
          }
        });

    // Any request for expansion returns a list containing the original GcsPath
    // This is required to pass validation that occurs in TextIO during apply()
    Mockito
        .when(mockGcsUtil.expand(Mockito.any(GcsPath.class)))
        .then(new Answer<List<GcsPath>>() {
          @Override
          public List<GcsPath> answer(InvocationOnMock invocation) throws Throwable {
            return ImmutableList.of((GcsPath) invocation.getArguments()[0]);
          }
        });

    return mockGcsUtil;
  }

  /**
   * This tests a few corner cases that should not crash.
   */
  @Test
  @Category(NeedsRunner.class)
  public void testGoodWildcards() throws Exception {
    GcsOptions options = TestPipeline.testingPipelineOptions().as(GcsOptions.class);
    options.setGcsUtil(buildMockGcsUtil());

    Pipeline pipeline = Pipeline.create(options);

    applyRead(pipeline, "gs://bucket/foo");
    applyRead(pipeline, "gs://bucket/foo/");
    applyRead(pipeline, "gs://bucket/foo/*");
    applyRead(pipeline, "gs://bucket/foo/?");
    applyRead(pipeline, "gs://bucket/foo/[0-9]");
    applyRead(pipeline, "gs://bucket/foo/*baz*");
    applyRead(pipeline, "gs://bucket/foo/*baz?");
    applyRead(pipeline, "gs://bucket/foo/[0-9]baz?");
    applyRead(pipeline, "gs://bucket/foo/baz/*");
    applyRead(pipeline, "gs://bucket/foo/baz/*wonka*");
    applyRead(pipeline, "gs://bucket/foo/*baz/wonka*");
    applyRead(pipeline, "gs://bucket/foo*/baz");
    applyRead(pipeline, "gs://bucket/foo?/baz");
    applyRead(pipeline, "gs://bucket/foo[0-9]/baz");

    // Check that running doesn't fail.
    pipeline.run();
  }

  private void applyRead(Pipeline pipeline, String path) {
    pipeline.apply("Read(" + path + ")", TextIO.Read.from(path));
  }
}
