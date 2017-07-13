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
package org.apache.beam.runners.jstorm.translation.runtime;

import static com.google.common.base.Preconditions.checkNotNull;

import org.apache.beam.sdk.util.WindowedValue;
import org.apache.beam.sdk.values.TupleTag;

public class FlattenExecutor<InputT> implements Executor {

  private final String description;
  private TupleTag mainOutputTag;
  private ExecutorContext context;
  private ExecutorsBolt executorsBolt;

  public FlattenExecutor(String description, TupleTag mainTupleTag) {
    this.description = checkNotNull(description, "description");
    this.mainOutputTag = mainTupleTag;
  }

  @Override
  public void init(ExecutorContext context) {
    this.context = context;
    this.executorsBolt = context.getExecutorsBolt();
  }

  @Override
  public void process(TupleTag tag, WindowedValue elem) {
    executorsBolt.processExecutorElem(mainOutputTag, elem);
  }

  @Override
  public void cleanup() {
  }

  @Override
  public String toString() {
    return description;
  }
}