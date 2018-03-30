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

apk add --update openssl

wget https://dl.google.com/dl/cloudsdk/channels/rapid/downloads/google-cloud-sdk-189.0.0-linux-x86_64.tar.gz -O gcloud.tar.gz
tar xf gcloud.tar.gz
./google-cloud-sdk/install.sh --quiet
. ./google-cloud-sdk/path.bash.inc
gcloud components update --quiet || echo 'gcloud components update failed'
RUN  gcloud auth activate-service-account --key-file=credentials.json && \
     gcloud config set project my-first-project-190318 && \
     rm credentials.json
exec "$@"
