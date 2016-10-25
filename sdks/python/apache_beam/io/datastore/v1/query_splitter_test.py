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

"""Cloud Datastore query splitter test."""

import unittest
import uuid

from mock import MagicMock
from mock import call

from apache_beam.io.datastore.v1 import query_splitter
from apache_beam.io.datastore.v1 import helper

from google.datastore.v1 import datastore_pb2
from google.datastore.v1 import query_pb2
from google.datastore.v1.query_pb2 import PropertyFilter


class QuerySplitterTest(unittest.TestCase):

  def test_get_splits_query_with_multiple_kinds(self):
    query = query_pb2.Query()
    query.kind.add()
    query.kind.add()
    self.assertRaises(ValueError, query_splitter.get_splits, None, query, 1)

  def test_get_splits_query_with_order(self):
    query = query_pb2.Query()
    query.kind.add()
    query.order.add()

    self.assertRaises(ValueError, query_splitter.get_splits, None, query, 1)

  def test_get_splits_query_with_unsupported_filter(self):
    query = query_pb2.Query()
    query.kind.add()
    test_filter = query.filter.composite_filter.filters.add()
    test_filter.property_filter.op = PropertyFilter.GREATER_THAN
    self.assertRaises(ValueError, query_splitter.get_splits, None, query, 1)

  def test_create_scatter_query(self):
    query = query_pb2.Query()
    kind = query.kind.add()
    kind.name = 'shakespeare-demo'
    num_splits = 10
    scatter_query = query_splitter._create_scatter_query(query, num_splits)
    self.assertEqual(scatter_query.kind[0], kind)
    self.assertEqual(scatter_query.limit.value,
                     (num_splits -1) * query_splitter.KEYS_PER_SPLIT)
    self.assertEqual(scatter_query.order[0].direction,
                     query_pb2.PropertyOrder.ASCENDING)
    self.assertEqual(scatter_query.projection[0].property.name,
                     query_splitter.KEY_PROPERTY_NAME)

  def test_get_splits_with_two_splits(self):
    query = query_pb2.Query()
    kind = query.kind.add()
    kind.name = 'shakespeare-demo'
    num_splits = 2
    num_entities = 100
    batch_size = 10

    self.get_splits_test(query, num_splits, num_entities, batch_size)

  def test_get_splits_with_multiple_splits(self):
    query = query_pb2.Query()
    kind = query.kind.add()
    kind.name = 'shakespeare-demo'
    num_splits = 4
    num_entities = 400
    batch_size = 10

    self.get_splits_test(query, num_splits, num_entities, batch_size)

  def test_get_splits_with_large_num_splits(self):
    query = query_pb2.Query()
    kind = query.kind.add()
    kind.name = 'shakespeare-demo'
    num_splits = 10
    num_entities = 4
    batch_size = 10

    self.get_splits_test(query, num_splits, num_entities, batch_size)

  def test_get_splits_with_small_num_entities(self):
    query = query_pb2.Query()
    kind = query.kind.add()
    kind.name = 'shakespeare-demo'
    num_splits = 4
    num_entities = 50
    batch_size = 10

    self.get_splits_test(query, num_splits, num_entities, batch_size)

  def test_get_splits_with_batch_size_exact_multiple(self):
    query = query_pb2.Query()
    kind = query.kind.add()
    kind.name = 'shakespeare-demo'
    num_splits = 4
    num_entities = 400
    batch_size = 32

    self.get_splits_test(query, num_splits, num_entities, batch_size)

  def test_get_splits_with_large_batch_size(self):
    query = query_pb2.Query()
    kind = query.kind.add()
    kind.name = 'shakespeare-demo'
    num_splits = 4
    num_entities = 400
    batch_size = 500

    self.get_splits_test(query, num_splits, num_entities, batch_size)

  def get_splits_test(self, query, num_splits, num_entities, batch_size):
    expected_requests = QuerySplitterTest.create_scatter_requests(
        query, num_splits, batch_size, num_entities)

    entities = QuerySplitterTest.create_entities(num_entities)
    mock_datastore = MagicMock()
    mock_datastore.run_query.side_effect = \
      QuerySplitterTest.create_run_query(entities, batch_size)

    # if request num_splits is greater than num_entities, the best we can
    # do is one entity per split.
    expected_num_splits = min(num_splits, num_entities + 1)
    split_queries = query_splitter.get_splits(mock_datastore, query, num_splits)

    self.assertEqual(len(split_queries), expected_num_splits)

    keys = []
    for entity_result in entities:
      keys.append(entity_result.entity.key)

    keys.sort(helper.key_comparator)
    expected_calls = []
    for req in expected_requests:
      expected_calls.append(call(req))

    self.assertEqual(expected_calls, mock_datastore.run_query.call_args_list)

  @staticmethod
  def create_run_query(entities, batch_size):
    def fake_run_query(req):
      start = int(req.query.start_cursor) if req.query.start_cursor else 0
      count = min(batch_size, req.query.limit.value)
      end = min(len(entities), start + count)
      finish = False
      # Finish reading when there are no more entities to return,
      # or request query limit has been satisfied.
      if end == len(entities) or count == req.query.limit.value:
        finish = True
      return QuerySplitterTest.create_scatter_response(entities[start:end],
                                                       str(end), finish)
    return fake_run_query

  @staticmethod
  def create_scatter_requests(query, num_splits, batch_size, num_entities):
    requests = []
    count = (num_splits - 1) * query_splitter.KEYS_PER_SPLIT
    start_cursor = ''
    i = 0
    scatter_query = query_splitter._create_scatter_query(query, count)
    while i < count and i < num_entities:
      request = datastore_pb2.RunQueryRequest()
      request.query.CopyFrom(scatter_query)
      request.query.start_cursor = start_cursor
      request.query.limit.value = count - i
      requests.append(request)
      i += batch_size
      start_cursor = str(i)

    return requests

  @staticmethod
  def create_scatter_response(entities, end_cursor, finish):
    resp = datastore_pb2.RunQueryResponse()
    if finish:
      resp.batch.more_results = query_pb2.QueryResultBatch.NO_MORE_RESULTS
    else:
      resp.batch.more_results = query_pb2.QueryResultBatch.NOT_FINISHED

    resp.batch.end_cursor = end_cursor
    for entity_result in entities:
      resp.batch.entity_results.add().CopyFrom(entity_result)

    return resp

  @staticmethod
  def create_entities(count):
    entities = []

    for _ in range(0, count):
      entity_result = query_pb2.EntityResult()
      entity_result.entity.key.path.add().name = str(uuid.uuid4())
      entities.append(entity_result)

    return entities


if __name__ == '__main__':
  unittest.main()
