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
package org.apache.beam.runners.dataflow.worker.fn.control;

import static org.apache.beam.runners.dataflow.worker.counters.DataflowCounterUpdateExtractor.longToSplitInt;

import com.google.api.services.dataflow.model.CounterUpdate;
import com.google.api.services.dataflow.model.IntegerMean;
import com.google.api.services.dataflow.model.NameAndKind;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;
import org.apache.beam.model.pipeline.v1.MetricsApi.IntDistributionData;
import org.apache.beam.model.pipeline.v1.MetricsApi.MonitoringInfo;
import org.apache.beam.runners.core.metrics.SpecMonitoringInfoValidator;
import org.apache.beam.runners.dataflow.worker.counters.NameContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** MonitoringInfo to CounterUpdate transformer capable to transform MeanByteCount counter. */
public class MeanByteCountMonitoringInfoToCounterUpdateTransformer
    implements MonitoringInfoToCounterUpdateTransformer {

  private static final Logger LOG = LoggerFactory.getLogger(BeamFnMapTaskExecutor.class);

  private final SpecMonitoringInfoValidator specValidator;
  private final Map<String, NameContext> pcollectionIdToNameContext;

  // TODO(BEAM-6945): utilize value from metrics.proto once it gets in.
  private static final String SUPPORTED_URN = "beam:metric:sampled_byte_size:v1";

  /**
   * @param specValidator SpecMonitoringInfoValidator to utilize for default validation.
   * @param pcollectionIdToNameContext This mapping is utilized to generate DFE CounterUpdate name.
   */
  public MeanByteCountMonitoringInfoToCounterUpdateTransformer(
      SpecMonitoringInfoValidator specValidator,
      Map<String, NameContext> pcollectionIdToNameContext) {
    this.specValidator = specValidator;
    this.pcollectionIdToNameContext = pcollectionIdToNameContext;
  }

  /**
   * Validates provided monitoring info against specs and common safety checks.
   *
   * @param monitoringInfo to validate.
   * @return Optional.empty() all validation checks are passed. Optional with error text otherwise.
   * @throws RuntimeException if received unexpected urn.
   */
  protected Optional<String> validate(MonitoringInfo monitoringInfo) {
    Optional<String> validatorResult = specValidator.validate(monitoringInfo);
    if (validatorResult.isPresent()) {
      return validatorResult;
    }

    String urn = monitoringInfo.getUrn();
    if (!urn.equals(SUPPORTED_URN)) {
      throw new RuntimeException(String.format("Received unexpected counter urn: %s", urn));
    }

    // TODO(BEAM-6945): extract and utilize pcollection label from beam_fn_api.proto
    if (!pcollectionIdToNameContext.containsKey(monitoringInfo.getLabelsMap().get("PCOLLECTION"))) {
      return Optional.of(
          "Encountered ElementCount MonitoringInfo with unknown PCollectionId: "
              + monitoringInfo.toString());
    }

    return Optional.empty();
  }

  /**
   * Generates CounterUpdate to send to DFE based on ElementCount MonitoringInfo.
   *
   * @param monitoringInfo Monitoring info to transform.
   * @return CounterUpdate generated based on provided monitoringInfo
   */
  @Override
  @Nullable
  public CounterUpdate transform(MonitoringInfo monitoringInfo) {
    Optional<String> validationResult = validate(monitoringInfo);
    if (validationResult.isPresent()) {
      LOG.info(validationResult.get());
      return null;
    }

    IntDistributionData value = monitoringInfo.getMetric().getDistributionData().getIntDistributionData();

    final String pcollectionId = monitoringInfo.getLabelsMap().get("PCOLLECTION");
    final String pcollectionName = pcollectionIdToNameContext.get(pcollectionId).userName();

    String counterName = pcollectionName + "-MeanByteCount";
    NameAndKind name = new NameAndKind();
    name.setName(counterName).setKind("MEAN");

    return new CounterUpdate()
        .setNameAndKind(name)
        .setCumulative(true)
        .setIntegerMean(
            new IntegerMean()
                .setSum(longToSplitInt(value.getSum()))
                .setCount(longToSplitInt(value.getCount())));
  }

  /** @return iterable of Urns that this transformer can convert to CounterUpdates. */
  public static String getSupportedUrn() {
    return SUPPORTED_URN;
  }
}
