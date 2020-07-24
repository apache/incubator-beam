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

"""Nexmark Query 0: Pass through (send and receive auction events).

The Nexmark suite is a series of queries (streaming pipelines) performed
on a simulation of auction events.

This query is a pass through that
simply parses the events generated by the launcher. It serves as a test
to verify the infrastructure.
"""

# pytype: skip-file

from __future__ import absolute_import

import apache_beam as beam
from apache_beam.testing.benchmarks.nexmark.nexmark_util import ParseJsonEvnetFn


def load(events, query_args=None):
  return (events | 'serialization' >> beam.Map(repr)
                 | 'deserialization' >> beam.ParDo(ParseJsonEvnetFn()))
