import common_job_properties

// Defines a job.
mavenJob('beam_PreCommit_Java_Build') {
  description('Runs a compile of the current GitHub Pull Request.')

  // Set common parameters.
  common_job_properties.setTopLevelJobProperties(delegate)

  // Set pull request build trigger.
  common_job_properties.setPullRequestBuildTrigger(
      delegate,
      'Jenkins: Maven clean compile')

  // Set Maven parameters.
  common_job_properties.setMavenConfig(delegate)
  
  goals('-B -e -Prelease clean compile')
}
