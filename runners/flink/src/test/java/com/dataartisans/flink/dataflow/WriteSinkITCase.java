/*
 * Copyright 2015 Data Artisans GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.dataartisans.flink.dataflow;

import com.google.cloud.dataflow.sdk.Pipeline;
import com.google.cloud.dataflow.sdk.coders.Coder;
import com.google.cloud.dataflow.sdk.coders.StringUtf8Coder;
import com.google.cloud.dataflow.sdk.io.Sink;
import com.google.cloud.dataflow.sdk.options.PipelineOptions;
import com.google.cloud.dataflow.sdk.transforms.Create;
import com.google.cloud.dataflow.sdk.transforms.Write;
import com.google.common.base.Joiner;
import org.apache.flink.core.fs.FileSystem;
import org.apache.flink.core.fs.Path;
import org.apache.flink.test.util.JavaProgramTestBase;

import java.io.File;
import java.io.PrintWriter;
import java.net.URI;

import static org.junit.Assert.*;

/**
 * Tests the translation of custom Write.Bound sinks.
 */
public class WriteSinkITCase extends JavaProgramTestBase {

	protected String resultPath;

	public WriteSinkITCase(){
	}

	static final String[] EXPECTED_RESULT = new String[] {
			"Joe red 3", "Mary blue 4", "Max yellow 23"};

	@Override
	protected void preSubmit() throws Exception {
		resultPath = getTempDirPath("result");
	}

	@Override
	protected void postSubmit() throws Exception {
		compareResultsByLinesInMemory(Joiner.on('\n').join(EXPECTED_RESULT), resultPath);
	}

	@Override
	protected void testProgram() throws Exception {
		runProgram(resultPath);
	}

	private static void runProgram(String resultPath) {
		Pipeline p = FlinkTestPipeline.create();

		p.apply(Create.of(EXPECTED_RESULT)).setCoder(StringUtf8Coder.of())
			.apply("CustomSink", Write.to(new MyCustomSink(resultPath)));

		p.run();
	}

	/**
	 * Simple custom sink which writes to a file.
	 */
	private static class MyCustomSink extends Sink<String> {

		private final String resultPath;

		public MyCustomSink(String resultPath) {
			this.resultPath = resultPath;
		}

		@Override
		public void validate(PipelineOptions options) {
			assertNotNull(options);
		}

		@Override
		public WriteOperation<String, ?> createWriteOperation(PipelineOptions options) {
			return new MyWriteOperation();
		}

		private class MyWriteOperation extends WriteOperation<String, String> {

			@Override
			public Coder<String> getWriterResultCoder() {
				return StringUtf8Coder.of();
			}

			@Override
			public void initialize(PipelineOptions options) throws Exception {

			}

			@Override
			public void finalize(Iterable<String> writerResults, PipelineOptions options) throws Exception {

			}

			@Override
			public Writer<String, String> createWriter(PipelineOptions options) throws Exception {
				return new MyWriter();
			}

			@Override
			public Sink<String> getSink() {
				return MyCustomSink.this;
			}

			/**
			 * Simple Writer which writes to a file.
			 */
			private class MyWriter extends Writer<String, String> {

				private PrintWriter internalWriter;

				@Override
				public void open(String uId) throws Exception {
					Path path = new Path(resultPath + "/" + uId);
					FileSystem.get(new URI("file:///")).create(path, false);
					internalWriter = new PrintWriter(new File(path.toUri()));
				}

				@Override
				public void write(String value) throws Exception {
					internalWriter.println(value);
				}

				@Override
				public String close() throws Exception {
					internalWriter.close();
					return resultPath;
				}

				@Override
				public WriteOperation<String, String> getWriteOperation() {
					return MyWriteOperation.this;
				}
			}
		}
	}

}

