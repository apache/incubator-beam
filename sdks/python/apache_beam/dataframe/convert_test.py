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

from __future__ import absolute_import

import unittest

import pandas as pd

import apache_beam as beam
from apache_beam.dataframe import convert
from apache_beam.testing.util import assert_that
from apache_beam.testing.util import equal_to


def equal_to_unordered_series(expected):
  def check(actual):
    actual = pd.concat(actual)
    if sorted(expected) != sorted(actual):
      raise AssertionError('Series not equal: \n%s\n%s\n' % (expected, actual))

  return check


class ConvertTest(unittest.TestCase):
  def test_convert_yield_pandas(self):
    with beam.Pipeline() as p:
      a = pd.Series([1, 2, 3])
      b = pd.Series([100, 200, 300])

      pc_a = p | 'A' >> beam.Create([a])
      pc_b = p | 'B' >> beam.Create([b])

      df_a = convert.to_dataframe(pc_a, proxy=a[:0])
      df_b = convert.to_dataframe(pc_b, proxy=b[:0])

      df_2a = 2 * df_a
      df_3a = 3 * df_a
      df_ab = df_a * df_b

      # Converting multiple results at a time can be more efficient.
      pc_2a, pc_ab = convert.to_pcollection(df_2a, df_ab,
                                            yield_elements='pandas')
      # But separate conversions can be done as well.
      pc_3a = convert.to_pcollection(df_3a, yield_elements='pandas')

      assert_that(pc_2a, equal_to_unordered_series(2 * a), label='Check2a')
      assert_that(pc_3a, equal_to_unordered_series(3 * a), label='Check3a')
      assert_that(pc_ab, equal_to_unordered_series(a * b), label='Checkab')

  def test_convert(self):
    with beam.Pipeline() as p:
      a = pd.Series([1, 2, 3])
      b = pd.Series([100, 200, 300])

      pc_a = p | 'A' >> beam.Create(a)
      pc_b = p | 'B' >> beam.Create(b)

      df_a = convert.to_dataframe(pc_a)
      df_b = convert.to_dataframe(pc_b)

      df_2a = 2 * df_a
      df_3a = 3 * df_a
      df_ab = df_a * df_b

      # Converting multiple results at a time can be more efficient.
      pc_2a, pc_ab = convert.to_pcollection(df_2a, df_ab)
      # But separate conversions can be done as well.
      pc_3a = convert.to_pcollection(df_3a)

      assert_that(pc_2a, equal_to(list(2 * a)), label='Check2a')
      assert_that(pc_3a, equal_to(list(3 * a)), label='Check3a')
      assert_that(pc_ab, equal_to(list(a * b)), label='Checkab')

  def test_convert_scalar(self):
    with beam.Pipeline() as p:
      pc = p | 'A' >> beam.Create([1, 2, 3])
      s = convert.to_dataframe(pc)
      pc_sum = convert.to_pcollection(s.sum())
      assert_that(pc_sum, equal_to([6]))

  def test_convert_memoization(self):
    with beam.Pipeline() as p:
      a = pd.Series([1, 2, 3])
      b = pd.Series([100, 200, 300])

      pc_a = p | 'A' >> beam.Create([a])
      pc_b = p | 'B' >> beam.Create([b])

      df_a = convert.to_dataframe(pc_a, proxy=a[:0])
      df_b = convert.to_dataframe(pc_b, proxy=b[:0])

      df_2a = 2 * df_a
      df_3a = 3 * df_a
      df_ab = df_a * df_b

      # Converting multiple results at a time can be more efficient.
      pc_2a_, pc_ab_ = convert.to_pcollection(df_2a, df_ab)
      # Converting the same expressions should yeild the same pcolls
      pc_3a, pc_2a, pc_ab = convert.to_pcollection(df_3a, df_2a, df_ab)

      self.assertEqual(id(pc_2a), id(pc_2a_))
      self.assertEqual(id(pc_ab), id(pc_ab_))

      assert_that(pc_2a, equal_to(list(2 * a)), label='Check2a')
      assert_that(pc_3a, equal_to(list(3 * a)), label='Check3a')
      assert_that(pc_ab, equal_to(list(a * b)), label='Checkab')

  def test_convert_memoization_yield_pandas(self):
    with beam.Pipeline() as p:
      a = pd.Series([1, 2, 3])
      b = pd.Series([100, 200, 300])

      pc_a = p | 'A' >> beam.Create([a])
      pc_b = p | 'B' >> beam.Create([b])

      df_a = convert.to_dataframe(pc_a, proxy=a[:0])
      df_b = convert.to_dataframe(pc_b, proxy=b[:0])

      df_2a = 2 * df_a
      df_3a = 3 * df_a
      df_ab = df_a * df_b

      # Converting multiple results at a time can be more efficient.
      pc_2a_, pc_ab_ = convert.to_pcollection(df_2a, df_ab,
                                            yield_elements='pandas')

      # Converting the same expressions should yeild the same pcolls
      pc_3a, pc_2a, pc_ab = convert.to_pcollection(df_3a, df_2a, df_ab,
                                                   yield_elements='pandas')

      self.assertEqual(id(pc_2a), id(pc_2a_))
      self.assertEqual(id(pc_ab), id(pc_ab_))

      assert_that(pc_2a, equal_to_unordered_series(2 * a), label='Check2a')
      assert_that(pc_3a, equal_to_unordered_series(3 * a), label='Check3a')
      assert_that(pc_ab, equal_to_unordered_series(a * b), label='Checkab')

  def test_convert_memoization_clears_cache(self):
    # This test re-runs the other memoization tests, and makes sure that the
    # cache is cleaned up with the pipeline. Otherwise there would be concerns
    # of it growing without bound.

    import gc

    # Make sure cache is clear
    gc.collect()
    self.assertEqual(len(convert.TO_PCOLLECTION_CACHE), 0)

    # Disable GC so it doesn't run pre-emptively, confounding assertions about
    # cache size
    gc.disable()

    try:
      self.test_convert_memoization()
      self.assertEqual(len(convert.TO_PCOLLECTION_CACHE), 3)

      self.test_convert_memoization_yield_pandas()
      self.assertEqual(len(convert.TO_PCOLLECTION_CACHE), 6)

      gc.collect()

      # PCollections should be removed from cache after pipelines go out of
      # scope and are GC'd
      self.assertEqual(len(convert.TO_PCOLLECTION_CACHE), 0)
    finally:
      # Always re-enable GC
      gc.enable()


if __name__ == '__main__':
  unittest.main()
