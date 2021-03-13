#!/bin/bash
#
#    Licensed to the Apache Software Foundation (ASF) under one or more
#    contributor license agreements.  See the NOTICE file distributed with
#    this work for additional information regarding copyright ownership.
#    The ASF licenses this file to You under the Apache License, Version 2.0
#    (the "License"); you may not use this file except in compliance with
#    the License.  You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#    Unless required by applicable law or agreed to in writing, software
#    distributed under the License is distributed on an "AS IS" BASIS,
#    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#    See the License for the specific language governing permissions and
#    limitations under the License.
#

# This script will update apache beam master branch with next release version
# and cut release branch for current development version.

# Parse parameters passing into the script

set -e

function clean_up(){
  echo "Do you want to clean local clone repo ${LOCAL_CLONE_DIR}? [y|N]"
  read confirmation
  if [[ $confirmation = "y" ]]; then
    cd ~
    rm -rf ${LOCAL_CLONE_DIR}
    echo "Cleaned up local repo."
  fi
}

if [[ $# -eq 1 && $1 = "-h" ]]; then
	echo "This script will update apache beam master branch with next release version and cut release branch for current development version."
	echo "There are two params required:"
	echo "--release=\${CURRENT_RELEASE_VERSION}"
	echo "--next_release=\${NEXT_RELEASE_VERSION}"
	echo "There is one optional parameter:"
	echo "--rc_num\${RC_NUM}"
	exit
else
	for param in "$@"
	do
		if [[ $param =~ --release\=([0-9]\.[0-9]*\.[0-9]) ]]; then
			RELEASE=${BASH_REMATCH[1]}
		fi
		if [[ $param =~ --next_release\=([0-9]\.[0-9]*\.[0-9]) ]]; then
			NEXT_VERSION_IN_BASE_BRANCH=${BASH_REMATCH[1]}
		fi
		if [[ $param =~ --rc_num\=([0-9]) ]]; then
		  RC_NUM=${BASH_REMATCH[1]}
		fi
	done
fi
if [[ -z "$RELEASE" || -z "$NEXT_VERSION_IN_BASE_BRANCH" ]]; then
	echo "This script needs to be ran with params, please run with -h to get more instructions."
	exit
fi


MASTER_BRANCH=master
DEV=${RELEASE}.dev
RELEASE_BRANCH=release-${RELEASE}
GITHUB_REPO_URL=https://gitbox.apache.org/repos/asf/beam.git
BEAM_ROOT_DIR=beam
LOCAL_CLONE_DIR=beam_release_${RELEASE}

echo "=====================Environment Variables====================="
echo "version: ${RELEASE}"
echo "next_release: ${NEXT_VERSION_IN_BASE_BRANCH}"
echo "working master branch: ${MASTER_BRANCH}"
echo "working release branch: ${RELEASE_BRANCH}"
echo "local repo dir: ~/${LOCAL_CLONE_DIR}/${BEAM_ROOT_DIR}"
if [ -n "${RC_NUM}" ]; then echo "release control version: ${RC_NUM}"
fi
echo "==============================================================="

cd ~
if [[ -d ${LOCAL_CLONE_DIR} ]]; then
  echo "Deleting existing local clone repo ${LOCAL_CLONE_DIR}."
  rm -rf ${LOCAL_CLONE_DIR}
fi
mkdir ${LOCAL_CLONE_DIR}
cd ${LOCAL_CLONE_DIR}
git clone ${GITHUB_REPO_URL}
cd ${BEAM_ROOT_DIR}

# Create local release branch
git branch ${RELEASE_BRANCH}

git checkout ${MASTER_BRANCH}

echo "====================Current working branch====================="
echo ${MASTER_BRANCH}
echo "==============================================================="

# Update master branch
sed -i -e "s/'${RELEASE}'/'${NEXT_VERSION_IN_BASE_BRANCH}'/g" buildSrc/src/main/groovy/org/apache/beam/gradle/BeamModulePlugin.groovy
sed -i -e "s/${RELEASE}/${NEXT_VERSION_IN_BASE_BRANCH}/g" gradle.properties
sed -i -e "s/${RELEASE}/${NEXT_VERSION_IN_BASE_BRANCH}/g" sdks/python/apache_beam/version.py
sed -i -e "s/${RELEASE}/${NEXT_VERSION_IN_BASE_BRANCH}/g" sdks/go/pkg/beam/core/core.go

echo "==============Update master branch as following================"
git diff
echo "==============================================================="

echo "Please make sure all changes above are expected. Do you confirm to commit?: [y|N]"
read confirmation
if [[ $confirmation != "y" ]]; then
  echo "Exiting without committing any changes on master branch."
  clean_up
  exit
fi

git add buildSrc/src/main/groovy/org/apache/beam/gradle/BeamModulePlugin.groovy
git add gradle.properties
git add sdks/python/apache_beam/version.py
git add sdks/go/pkg/beam/core/core.go
git commit -m "Moving to ${NEXT_VERSION_IN_BASE_BRANCH}-SNAPSHOT on master branch."
if git push origin ${MASTER_BRANCH}; then
  break
else
  clean_up
  exit
fi

# Checkout and update release branch
git checkout ${RELEASE_BRANCH}

echo "==================Current working branch======================="
echo ${RELEASE_BRANCH}
echo "==============================================================="

sed -i -e "s/${DEV}/${RELEASE}/g" gradle.properties
if [ -z "${RC_NUM}" ]
 then sed -i -e "s/${DEV}/${RELEASE}/g" sdks/python/apache_beam/version.py;
 else sed -i -e "s/${DEV}/${RELEASE}-rc${RC_NUM}/g" sdks/python/apache_beam/version.py;
fi
sed -i -e "s/${DEV}/${RELEASE}/g" sdks/go/pkg/beam/core/core.go
# TODO: [BEAM-4767]
sed -i -e "s/'beam-master-.*'/'beam-${RELEASE}'/g" runners/google-cloud-dataflow-java/build.gradle

echo "===============Update release branch as following=============="
git diff
echo "==============================================================="

echo "Please make sure all changes above are expected. Do you confirm to commit?: [y|N]"
read confirmation
if [[ $confirmation != "y" ]]; then
  echo "Exiting without committing any changes on release branch."
  clean_up
  exit
fi

git add gradle.properties
git add sdks/python/apache_beam/version.py
git add sdks/go/pkg/beam/core/core.go
git add runners/google-cloud-dataflow-java/build.gradle
git commit -m "Create release branch for version ${RELEASE}."
git push --set-upstream origin ${RELEASE_BRANCH}

clean_up
