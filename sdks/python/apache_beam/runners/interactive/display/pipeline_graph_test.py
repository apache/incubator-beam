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

"""Tests for apache_beam.runners.interactive.display.pipeline_graph."""
from __future__ import absolute_import

import apache_beam as beam
import unittest
from apache_beam.runners.interactive import interactive_beam as ib
from apache_beam.runners.interactive import interactive_environment as ie
from apache_beam.runners.interactive import interactive_runner as ir
from apache_beam.runners.interactive.display import pipeline_graph

# pylint: disable=range-builtin-not-iterating,unused-variable,possibly-unused-variable
# Reason:
#   Disable pylint for pipelines built for testing. Not all PCollections are
#   used but they need to be assigned to variables so that we can test how
#   interactive beam applies the magic around user-defined variables.


# The tests need graphviz to work.
@unittest.skipIf(not ie.current_env().is_interactive_ready,
                 '[interactive] dependency is not installed.')
class PipelineGraphTest(unittest.TestCase):

  def setUp(self):
    ie.new_env()

  def test_decoration(self):
    p = beam.Pipeline(ir.InteractiveRunner())
    # We are examining if literal `"` and trailing literal `\` are decorated
    # correctly.
    pcoll = p | '"Cell 1": "Create\\"' >> beam.Create(range(1000))
    ib.watch(locals())

    self.assertEqual(
        ('digraph G {\n'
         'node [color=blue, fontcolor=blue, shape=box];\n'
         # The py string literal from `\\\\\\"` is `\\\"` in dot and will be
         # rendered as `\"` because they are enclosed by `"`.
         '"\\"Cell 1\\": \\"Create\\\\\\"";\n'
         'pcoll [shape=circle];\n'
         '"\\"Cell 1\\": \\"Create\\\\\\"" -> pcoll;\n'
         '}\n'),
        pipeline_graph.PipelineGraph(p).get_dot())

  def test_get_dot(self):
    p = beam.Pipeline(ir.InteractiveRunner())
    init_pcoll = p | 'Init' >> beam.Create(range(10))
    squares = init_pcoll | 'Square' >> beam.Map(lambda x: x * x)
    cubes = init_pcoll | 'Cube' >> beam.Map(lambda x: x ** 3)
    ib.watch(locals())

    self.assertEqual(
        ('digraph G {\n'
         'node [color=blue, fontcolor=blue, shape=box];\n'
         '"Init";\n'
         'init_pcoll [shape=circle];\n'
         '"Square";\n'
         'squares [shape=circle];\n'
         '"Cube";\n'
         'cubes [shape=circle];\n'
         '"Init" -> init_pcoll;\n'
         'init_pcoll -> "Square";\n'
         'init_pcoll -> "Cube";\n'
         '"Square" -> squares;\n'
         '"Cube" -> cubes;\n'
         '}\n'),
        pipeline_graph.PipelineGraph(p).get_dot())


if __name__ == '__main__':
  unittest.main()
