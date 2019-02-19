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
package org.apache.beam.runners.flink.metrics;

import static org.apache.beam.runners.flink.metrics.FlinkMetricContainer.getFlinkMetricNameString;
import static org.apache.beam.sdk.metrics.MetricUrns.ELEMENT_COUNT_URN;
import static org.apache.beam.sdk.metrics.MetricUrns.PTRANSFORM_LABEL;
import static org.apache.beam.sdk.metrics.MetricUrns.USER_COUNTER_URN_PREFIX;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.mockito.Matchers.anyObject;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.argThat;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.beam.model.pipeline.v1.MetricsApi;
import org.apache.beam.model.pipeline.v1.MetricsApi.CounterData;
import org.apache.beam.model.pipeline.v1.MetricsApi.DoubleDistributionData;
import org.apache.beam.model.pipeline.v1.MetricsApi.IntDistributionData;
import org.apache.beam.model.pipeline.v1.MetricsApi.Metric;
import org.apache.beam.model.pipeline.v1.MetricsApi.MonitoringInfo;
import org.apache.beam.runners.core.metrics.CounterCell;
import org.apache.beam.runners.core.metrics.DistributionCell;
import org.apache.beam.runners.core.metrics.DistributionData;
import org.apache.beam.runners.core.metrics.MetricsContainerStepMap;
import org.apache.beam.runners.core.metrics.SimpleMonitoringInfoBuilder;
import org.apache.beam.runners.flink.metrics.FlinkMetricContainer.FlinkDistributionGauge;
import org.apache.beam.sdk.metrics.Counter;
import org.apache.beam.sdk.metrics.Distribution;
import org.apache.beam.sdk.metrics.DistributionResult;
import org.apache.beam.sdk.metrics.Gauge;
import org.apache.beam.sdk.metrics.GaugeResult;
import org.apache.beam.sdk.metrics.MetricKey;
import org.apache.beam.sdk.metrics.MetricName;
import org.apache.beam.sdk.metrics.MetricsContainer;
import org.apache.beam.vendor.grpc.v1p13p1.com.google.common.collect.ImmutableList;
import org.apache.flink.api.common.functions.RuntimeContext;
import org.apache.flink.metrics.MetricGroup;
import org.apache.flink.metrics.SimpleCounter;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** Tests for {@link FlinkMetricContainer}. */
public class FlinkMetricContainerTest {

  @Mock private RuntimeContext runtimeContext;
  @Mock private MetricGroup metricGroup;

  @Before
  public void beforeTest() {
    MockitoAnnotations.initMocks(this);
    when(runtimeContext.<MetricsContainerStepMap, MetricsContainerStepMap>getAccumulator(
            anyString()))
        .thenReturn(new MetricsAccumulator());
    when(runtimeContext.getMetricGroup()).thenReturn(metricGroup);
  }

  @Test
  public void testMetricNameGeneration() {
    MetricKey key = MetricKey.ptransform("step", "namespace", "name");
    String name = getFlinkMetricNameString(key);
    assertThat(name, is("step.namespace.name"));
  }

  @Test
  public void testCounter() {
    SimpleCounter flinkCounter = new SimpleCounter();
    when(metricGroup.counter("step.namespace.name")).thenReturn(flinkCounter);

    FlinkMetricContainer container = new FlinkMetricContainer(runtimeContext);
    MetricsContainer step = container.ptransformContainer("step");
    MetricName metricName = MetricName.named("namespace", "name");
    Counter counter = step.getCounter(metricName);
    counter.inc();
    counter.inc();

    assertThat(flinkCounter.getCount(), is(0L));
    container.updateFlinkMetrics("step");
    assertThat(flinkCounter.getCount(), is(2L));
  }

  @Test
  public void testGauge() {
    FlinkMetricContainer.FlinkGauge flinkGauge =
        new FlinkMetricContainer.FlinkGauge(GaugeResult.empty());
    when(metricGroup.gauge(eq("step.namespace.name"), anyObject())).thenReturn(flinkGauge);

    FlinkMetricContainer container = new FlinkMetricContainer(runtimeContext);
    MetricsContainer step = container.ptransformContainer("step");
    MetricName metricName = MetricName.named("namespace", "name");
    Gauge gauge = step.getGauge(metricName);

    assertThat(flinkGauge.getValue(), is(GaugeResult.empty()));
    // first set will install the mocked gauge
    container.updateFlinkMetrics("step");
    gauge.set(1);
    gauge.set(42);
    container.updateFlinkMetrics("step");
    assertThat(flinkGauge.getValue().getValue(), is(42L));
  }

  @Test
  public void testMonitoringInfoUpdate() {
    FlinkMetricContainer container = new FlinkMetricContainer(runtimeContext);
    MetricsContainer step = container.ptransformContainer("step");

    SimpleCounter userCounter = new SimpleCounter();
    when(metricGroup.counter("step.ns1.metric1")).thenReturn(userCounter);

    SimpleCounter elemCounter = new SimpleCounter();
    when(metricGroup.counter("pcoll.beam.metric.element_count.v1")).thenReturn(elemCounter);

    MonitoringInfo userCountMonitoringInfo =
        new SimpleMonitoringInfoBuilder()
            .userMetric("step", "ns1", "metric1")
            .setInt64Value(111)
            .build();
    assertNotNull(userCountMonitoringInfo);

    MonitoringInfo elemCountMonitoringInfo =
        new SimpleMonitoringInfoBuilder()
            .setUrn(ELEMENT_COUNT_URN)
            .setInt64Value(222)
            .setPCollectionLabel("pcoll")
            .build();
    assertNotNull(elemCountMonitoringInfo);

    assertThat(userCounter.getCount(), is(0L));
    assertThat(elemCounter.getCount(), is(0L));
    container.updateMetrics(
        "step", ImmutableList.of(userCountMonitoringInfo, elemCountMonitoringInfo));
    assertThat(userCounter.getCount(), is(111L));
    assertThat(elemCounter.getCount(), is(222L));
  }

  @Test
  public void testDropUnexpectedMonitoringInfoTypes() {
    FlinkMetricContainer flinkContainer = new FlinkMetricContainer(runtimeContext);
    MetricsContainer container = flinkContainer.ptransformContainer("step");

    MonitoringInfo intCounter =
        MonitoringInfo.newBuilder()
            .setUrn(USER_COUNTER_URN_PREFIX + "ns1:int_counter")
            .putLabels(PTRANSFORM_LABEL, "step")
            .setMetric(
                Metric.newBuilder().setCounterData(CounterData.newBuilder().setInt64Value(111)))
            .build();

    MonitoringInfo doubleCounter =
        MonitoringInfo.newBuilder()
            .setUrn(USER_COUNTER_URN_PREFIX + "ns2:double_counter")
            .putLabels(PTRANSFORM_LABEL, "step")
            .setMetric(
                Metric.newBuilder().setCounterData(CounterData.newBuilder().setDoubleValue(222)))
            .build();

    MonitoringInfo intDistribution =
        MonitoringInfo.newBuilder()
            .setUrn(USER_COUNTER_URN_PREFIX + "ns3:int_distribution")
            .putLabels(PTRANSFORM_LABEL, "step")
            .setMetric(
                Metric.newBuilder()
                    .setDistributionData(
                        MetricsApi.DistributionData.newBuilder()
                            .setIntDistributionData(
                                IntDistributionData.newBuilder()
                                    .setSum(30)
                                    .setCount(10)
                                    .setMin(1)
                                    .setMax(5))))
            .build();

    MonitoringInfo doubleDistribution =
        MonitoringInfo.newBuilder()
            .setUrn(USER_COUNTER_URN_PREFIX + "ns4:double_distribution")
            .putLabels(PTRANSFORM_LABEL, "step")
            .setMetric(
                Metric.newBuilder()
                    .setDistributionData(
                        MetricsApi.DistributionData.newBuilder()
                            .setDoubleDistributionData(
                                DoubleDistributionData.newBuilder()
                                    .setSum(30)
                                    .setCount(10)
                                    .setMin(1)
                                    .setMax(5))))
            .build();

    // Mock out the counter and distribution that Flink returns
    SimpleCounter flinkCounter = new SimpleCounter();

    DistributionData distributionData = DistributionData.create(30, 10, 1, 5);
    DistributionResult distributionResult = distributionData.extractResult();
    FlinkDistributionGauge distributionGauge = new FlinkDistributionGauge(distributionResult);

    // Simulate Flink's metric system returning the counter and distribution above in response to
    // queries for corresponding metric names
    when(metricGroup.counter("step.ns1.int_counter")).thenReturn(flinkCounter);
    when(metricGroup.gauge(
            eq("step.ns3.int_distribution"),
            argThat(
                new ArgumentMatcher<FlinkDistributionGauge>() {
                  @Override
                  public boolean matches(Object argument) {
                    DistributionResult actual = ((FlinkDistributionGauge) argument).getValue();
                    return actual.equals(distributionResult);
                  }
                })))
        .thenReturn(distributionGauge);

    flinkContainer.updateMetrics(
        "step", ImmutableList.of(intCounter, doubleCounter, intDistribution, doubleDistribution));

    // Flink's MetricGroup should only have asked for one counter (the integer-typed one) to be
    // created (the double-typed one is dropped currently)
    verify(metricGroup).counter(eq("step.ns1.int_counter"));

    // Verify that the counter injected into flink has the right value
    assertThat(flinkCounter.getCount(), is(111L));

    // Verify the counter in the java SDK MetricsContainer
    long count =
        ((CounterCell) container.getCounter(MetricName.named("ns1", "int_counter")))
            .getCumulative();
    assertThat(count, is(111L));

    // The one Flink distribution that gets created is a FlinkDistributionGauge; here we verify its
    // initial (and in this test, final) value
    verify(metricGroup)
        .gauge(
            eq("step.ns3.int_distribution"),
            argThat(
                new ArgumentMatcher<FlinkDistributionGauge>() {
                  @Override
                  public boolean matches(Object argument) {
                    DistributionResult actual = ((FlinkDistributionGauge) argument).getValue();
                    DistributionResult expected = DistributionResult.create(30, 10, 1, 5);
                    return actual.equals(expected);
                  }
                }));

    // Verify that the Java SDK MetricsContainer holds the same information
    DistributionData actualDistributionData =
        ((DistributionCell) container.getDistribution(MetricName.named("ns3", "int_distribution")))
            .getCumulative();
    assertThat(actualDistributionData, is(distributionData));
  }

  @Test
  public void testDistribution() {
    FlinkMetricContainer.FlinkDistributionGauge flinkGauge =
        new FlinkMetricContainer.FlinkDistributionGauge(DistributionResult.IDENTITY_ELEMENT);
    when(metricGroup.gauge(eq("step.namespace.name"), anyObject())).thenReturn(flinkGauge);

    FlinkMetricContainer container = new FlinkMetricContainer(runtimeContext);
    MetricsContainer step = container.ptransformContainer("step");
    MetricName metricName = MetricName.named("namespace", "name");
    Distribution distribution = step.getDistribution(metricName);

    assertThat(flinkGauge.getValue(), is(DistributionResult.IDENTITY_ELEMENT));
    // first set will install the mocked distribution
    container.updateFlinkMetrics("step");
    distribution.update(42);
    distribution.update(-23);
    distribution.update(0);
    distribution.update(1);
    container.updateFlinkMetrics("step");
    assertThat(flinkGauge.getValue().getMax(), is(42L));
    assertThat(flinkGauge.getValue().getMin(), is(-23L));
    assertThat(flinkGauge.getValue().getCount(), is(4L));
    assertThat(flinkGauge.getValue().getSum(), is(20L));
    assertThat(flinkGauge.getValue().getMean(), is(5.0));
  }
}
