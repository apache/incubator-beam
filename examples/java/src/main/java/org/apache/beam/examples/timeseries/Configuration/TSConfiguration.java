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
package org.apache.beam.examples.timeseries.configuration;

import java.io.Serializable;
import org.apache.beam.sdk.annotations.Experimental;
import org.joda.time.Duration;

/** configuration options for the timeseries pipeline. */
@SuppressWarnings("serial")
@Experimental
public class TSConfiguration implements Serializable {

  /**
   * Used to determine the backfill operation type. NONE : Do not backfill NULL : Do not set a value
   * LAST_KNOWN_VALUE : Use the last known value
   */
  public enum BFillOptions {
    NONE,
    NULL,
    LAST_KNOWN_VALUE
  }

  private BFillOptions fillOption = BFillOptions.LAST_KNOWN_VALUE;

  // The down sample period which must be set.
  private Duration downSampleDuration = Duration.standardSeconds(1);

  // Once a key is observed we will generate a value during periods when the key has not been observed if the
  // fillOption is set to anything other than NONE.
  private Duration timeToLive = Duration.ZERO;

  // Set if this is a streaming pipeline.
  private boolean isStreaming = false;

  public BFillOptions fillOption() {
    return fillOption;
  };

  // The down sample period which must be set.
  public Duration downSampleDuration() {
    return downSampleDuration;
  };

  // Once a key is observed we will generate a value during periods when the key has not been observed if the
  // fillOption is set to anything other than NONE.
  public Duration timeToLive() {
    return timeToLive;
  };

  // Set if this is a streaming pipeline.
  public boolean isStreaming() {
    return this.isStreaming;
  };

  public static TSConfiguration builder() {
    return new TSConfiguration();
  }

  public TSConfiguration fillOption(BFillOptions bFillOptions) {
    this.fillOption = bFillOptions;
    return this;
  };

  public TSConfiguration downSampleDuration(Duration downSampleDuration) {
    this.downSampleDuration = downSampleDuration;
    return this;
  };

  public TSConfiguration timeToLive(Duration timeToLive) {
    this.timeToLive = timeToLive;
    return this;
  };

  public TSConfiguration isStreaming(boolean isStreaming) {
    this.isStreaming = isStreaming;
    return this;
  };

  // Internal metadata value used for Accums which have been autogenerated
  public static final String HEARTBEAT = "HB";
}
