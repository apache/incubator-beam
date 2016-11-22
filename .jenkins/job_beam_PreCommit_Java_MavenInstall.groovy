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

// Defines a job.
mavenJob('beam_PreCommit_Java_MavenInstall') {
  description('Runs an install of the current GitHub Pull Request.')

  // Set common parameters.
  common_job_properties.setTopLevelJobProperties(delegate)

  // Set pull request build trigger.
  common_job_properties.setPullRequestBuildTrigger(
      delegate,
      'Jenkins: Maven clean install')

  // Set Maven parameters.
  common_job_properties.setMavenConfig(delegate)

  // Set spark env variables for ITs.
  common_job_properties.setSparkEnvVariables(delegate)
  
  goals('-B -e -Prelease,include-runners,jenkins-precommit,direct-runner,dataflow-runner,spark-runner,flink-runner,apex-runner -DrepoToken=${COVERALLS_REPO_TOKEN} -DpullRequest=99999 help:effective-settings clean install coveralls:report')
}
