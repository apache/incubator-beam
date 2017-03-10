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

import common_job_properties

// This job runs the Beam performance tests on PerfKit Benchmarker.
job('beam_PerformanceTests_Dataflow'){
    // Set default Beam job properties.
    common_job_properties.setTopLevelMainJobProperties(delegate)

    // Run job in postcommit every 6 hours and don't trigger every push.
    common_job_properties.setPostCommit(delegate, '0 */6 * * *', false)

    steps {
        // Clones appropriate perfkit branch
        shell('git clone -b apache --single-branch https://github.com/jasonkuster/PerfKitBenchmarker.git')
        shell('pip install --user -r PerfKitBenchmarker/requirements.txt')
        shell('python PerfKitBenchmarker/pkb.py --project=apache-beam-testing --ci_run=true --benchmarks=dpb_wordcount_benchmark --dpb_dataflow_staging_location=gs://temp-storage-for-perf-tests/staging --dpb_dataflow_jar=./ --dpb_wordcount_input=dataflow-samples/shakespeare/kinglear.txt --dpb_log_level=INFO --config_override=dpb_wordcount_benchmark.dpb_service.service_type=dataflow --bigquery_table=beam_performance.pkb_results --official=true')
        shell('rm -rf PerfKitBenchmarker')
    }
}
