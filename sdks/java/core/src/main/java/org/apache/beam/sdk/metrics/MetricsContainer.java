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
package org.apache.beam.sdk.metrics;

import java.io.Serializable;

import org.apache.beam.model.pipeline.v1.MetricsApi;
import org.apache.beam.sdk.annotations.Experimental;
import org.apache.beam.sdk.annotations.Experimental.Kind;
import org.apache.beam.sdk.util.HistogramData;
import org.apache.commons.lang3.NotImplementedException;

/**
 * Holds the metrics for a single step. Each of the methods should return an implementation of the
 * appropriate metrics interface for the "current" step.
 */
@Experimental(Kind.METRICS)
public interface MetricsContainer extends Serializable {

  /**
   * Return the {@link Counter} that should be used for implementing the given {@code metricName} in
   * this container.
   */
  Counter getCounter(MetricName metricName);

  /**
   * Return the {@link Distribution} that should be used for implementing the given {@code
   * metricName} in this container.
   */
  Distribution getDistribution(MetricName metricName);

  /**
   * Return the {@link Gauge} that should be used for implementing the given {@code metricName} in
   * this container.
   */
  Gauge getGauge(MetricName metricName);

  /**
   * Return the {@link Histogram} that should be used for implementing the given {@code metricName}
   * in this container.
   */
  default Histogram getHistogram(MetricName metricName, HistogramData.BucketType bucketType) {
    throw new RuntimeException("Histogram metric is not supported yet.");
  }

  /** Return the cumulative values for any metrics in this container as MonitoringInfos. */
  default Iterable<MetricsApi.MonitoringInfo> getMonitoringInfos() {
    throw new NotImplementedException("getMonitoringInfos is not impemented on this MetricsContainer.");
  }
}
