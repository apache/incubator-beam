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
package org.apache.beam.runners.core;

import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.apache.beam.sdk.values.PCollectionView;

/** An implementation of a {@link SideInputReader} that actually does not support side-inputs. */
public class UnsupportedSideInputReader implements SideInputReader {
  private final String transformName;

  public UnsupportedSideInputReader(String transformName) {
    this.transformName = transformName;
  }

  @Override
  public <T> T get(PCollectionView<T> view, BoundedWindow window) {
    throw new UnsupportedOperationException(
        String.format("%s does not support side inputs.", transformName));
  }

  @Override
  public <T> boolean contains(PCollectionView<T> view) {
    throw new UnsupportedOperationException(
        String.format("%s does not support side inputs.", transformName));
  }

  @Override
  public boolean isEmpty() {
    throw new UnsupportedOperationException(
        String.format("%s does not support side inputs.", transformName));
  }

  @Override
  public PCollectionView get(String sideInputTag) {
    throw new UnsupportedOperationException(
            String.format("%s does not support side inputs.", transformName));
  }
}
