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

import CommonProperties as commonProperties

/**
 * This class is to be used for defining jobs for post- and pre-commit tests.
 *
 * Purpose of this class is to define common strategies and reporting/building paramereters
 * for pre- and post- commit test jobs and unify them across the project.
 */
class PostcommitJobBuilder {
  private def scope;
  private def jobDefinition;
  private def job;

  PostcommitJobBuilder(scope, jobDefinition = {}) {
    this.scope = scope
    this.jobDefinition = jobDefinition
    this.job = null
  }

  static void postCommitJob(nameBase,
                            triggerPhrase,
                            githubUiHint,
                            scope,
                            jobDefinition = {}) {
    PostcommitJobBuilder jb = new PostcommitJobBuilder(scope, jobDefinition)
    jb.defineAutoPostCommitJob(nameBase)
    jb.defineGhprbTriggeredJob(nameBase + "_PR", triggerPhrase, githubUiHint, false)
  }

  void defineAutoPostCommitJob(name) {
    def autoBuilds = scope.job(name) {
      commonProperties.setAutoJob delegate
    }
    autoBuilds.with(jobDefinition)
  }

  private void defineGhprbTriggeredJob(name, triggerPhrase, githubUiHint, triggerOnPrCommit) {
    def ghprbBuilds = scope.job(name) {
      commonProperties.setPullRequestBuildTrigger(
        delegate,
        githubUiHint,
        triggerPhrase,
        !triggerOnPrCommit)
    }
    ghprbBuilds.with(jobDefinition)
  }
}
