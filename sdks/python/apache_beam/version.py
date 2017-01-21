#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

"""Apache Beam SDK version information and utilities."""


import re

# Do not use directly, use get_version() instead.
__version__ = None


def get_version():
  global __version__
  if not __version__:
    __version__ = get_version_from_pom()
  return __version__


def get_version_from_pom():
  """Reads the actual version from pom.xml file."""
  with open('./pom.xml', 'r') as f:
    pom = f.read()
    regex = (r'.*<parent>\s*'
             r'<groupId>[a-z\.]+</groupId>\s*'
             r'<artifactId>[a-z\-]+</artifactId>\s*'
             r'<version>([0-9a-zA-Z\.\-]+)</version>.*')
    pattern = re.compile(str(regex))
    search = pattern.search(pom)
    version = search.group(1)
    version = version.replace("-incubating-SNAPSHOT", ".dev+incubating")
    return version


if __name__ == '__main__':
  get_version()
