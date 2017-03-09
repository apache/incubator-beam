# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#!/bin/sh

# Install python
sudo apt-get update
sudo apt-get install python-pip
sudo pip install --upgrade pip
sudo apt-get install python-dev
sudo pip install tornado numpy
echo

# Identify external IP of the pod
external_ip="$(kubectl get svc elasticsearch -o jsonpath='{.status.loadBalancer.ingress[0].ip}')"
echo "External IP - $external_ip"
echo

#run the script
/usr/bin/python es_test_data.py --es_url=http://$external_ip:9200
echo "Test data for Elasticsearch generated"

