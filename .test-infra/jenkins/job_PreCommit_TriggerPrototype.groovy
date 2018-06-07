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

// Prototype job for testing triggering
job('beam_PreCommit_TriggerPrototype') {
  description('Runs a generic gradle command based on pull request triggering.')

  // Execute concurrent builds if necessary.
  concurrentBuild()

  // Set common parameters.
  common_job_properties.setTopLevelMainJobProperties(
    delegate,
    'master',
    90)

  // Publish all test results to Jenkins. Note that Nose documentation
  // specifically mentions that it produces JUnit compatible test results.
  publishers {
    archiveJunit('**/nosetests.xml')
  }

  // Sets that this is a PreCommit job.
  triggers {
    githubPullRequest {
      admins(['asfbot'])
      useGitHubHooks()
      orgWhitelist(['apache'])
      allowMembersOfWhitelistedOrgsAsAdmin()
      permitAll()
      triggerPhrase('abracadabra')

      // TODO: Remove once triggering is added
      onlyTriggerPhrase()

      extensions {
        commitStatus {
          // This is the name that will show up in the GitHub pull request UI
          // for this Jenkins project. It has a limit of 255 characters.
          context('TriggerPrototype')
        }

        // Comment messages after build completes.
        buildStatus {
          completedStatus('SUCCESS', '--none--')
          completedStatus('FAILURE', '--none--')
          completedStatus('ERROR', '--none--')
        }
      }
    }
  }

  steps {
    gradle {
      rootBuildScriptDir(common_job_properties.checkoutDir)
      tasks('tasks')
      common_job_properties.setGradleSwitches(delegate)
    }
  }
}
