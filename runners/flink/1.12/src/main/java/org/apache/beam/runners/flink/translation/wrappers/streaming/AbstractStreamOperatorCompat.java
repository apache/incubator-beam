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
package org.apache.beam.runners.flink.translation.wrappers.streaming;

import org.apache.flink.streaming.api.operators.AbstractStreamOperator;
import org.apache.flink.streaming.api.operators.InternalTimeServiceManager;
import org.apache.flink.streaming.api.operators.InternalTimeServiceManagerImpl;

/** Compatibility layer for {@link AbstractStreamOperator} breaking changes. */
public abstract class AbstractStreamOperatorCompat<OutputT>
    extends AbstractStreamOperator<OutputT> {

  /**
   * Getter for timeServiceManager, which has been made private in Flink 1.11.
   *
   * @return Time service manager.
   */
  protected InternalTimeServiceManager<?> getTimeServiceManagerCompat() {
    return getTimeServiceManager()
        .orElseThrow(() -> new IllegalStateException("Time service manager is not set."));
  }

  /**
   * This call has been removed from {@link AbstractStreamOperator} in Flink 1.12.
   *
   * <p>{@link InternalTimeServiceManagerImpl#numProcessingTimeTimers()}
   */
  protected int numProcessingTimeTimers() {
    return getTimeServiceManager()
        .map(
            manager -> {
              final InternalTimeServiceManagerImpl<?> cast =
                  (InternalTimeServiceManagerImpl<?>) getTimeServiceManagerCompat();
              return cast.numProcessingTimeTimers();
            })
        .orElse(0);
  }
}
