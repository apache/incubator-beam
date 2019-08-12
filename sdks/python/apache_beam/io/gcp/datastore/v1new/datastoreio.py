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

"""
A connector for reading from and writing to Google Cloud Datastore.

Please use this module for Datastore I/O since
``apache_beam.io.gcp.datastore.v1.datastoreio`` will be deprecated in the
next Beam major release.

This module uses the newer google-cloud-datastore package. Its API was different
enough to require extensive changes to this and associated modules.

This module is experimental, no backwards compatibility guarantees.
"""
from __future__ import absolute_import
from __future__ import division

import logging
import time
from builtins import round

from apache_beam import typehints
from apache_beam.io.gcp.datastore.v1 import util
from apache_beam.io.gcp.datastore.v1.adaptive_throttler import AdaptiveThrottler
from apache_beam.io.gcp.datastore.v1new import helper
from apache_beam.io.gcp.datastore.v1new import query_splitter
from apache_beam.io.gcp.datastore.v1new import types
from apache_beam.metrics.metric import Metrics
from apache_beam.transforms import Create
from apache_beam.transforms import DoFn
from apache_beam.transforms import ParDo
from apache_beam.transforms import PTransform
from apache_beam.transforms import Reshuffle

__all__ = ['ReadFromDatastore', 'WriteToDatastore', 'DeleteFromDatastore']


@typehints.with_output_types(types.Entity)
class ReadFromDatastore(PTransform):
  """A ``PTransform`` for querying Google Cloud Datastore.

  To read a ``PCollection[Entity]`` from a Cloud Datastore ``Query``, use
  the ``ReadFromDatastore`` transform by providing a `query` to
  read from. The project and optional namespace are set in the query.
  The query will be split into multiple queries to allow for parallelism. The
  degree of parallelism is automatically determined, but can be overridden by
  setting `num_splits` to a value of 1 or greater.

  Note: Normally, a runner will read from Cloud Datastore in parallel across
  many workers. However, when the `query` is configured with a `limit` or if the
  query contains inequality filters like `GREATER_THAN, LESS_THAN` etc., then
  all the returned results will be read by a single worker in order to ensure
  correct data. Since data is read from a single worker, this could have
  significant impact on the performance of the job. Using a
  :class:`~apache_beam.transforms.util.Reshuffle` transform after the read in
  this case might be beneficial for parallelizing work across workers.

  The semantics for query splitting is defined below:
    1. If `num_splits` is equal to 0, then the number of splits will be chosen
    dynamically at runtime based on the query data size.

    2. Any value of `num_splits` greater than
    `ReadFromDatastore._NUM_QUERY_SPLITS_MAX` will be capped at that value.

    3. If the `query` has a user limit set, or contains inequality filters, then
    `num_splits` will be ignored and no split will be performed.

    4. Under certain cases Cloud Datastore is unable to split query to the
    requested number of splits. In such cases we just use whatever Cloud
    Datastore returns.

  See https://developers.google.com/datastore/ for more details on Google Cloud
  Datastore.
  """

  # An upper bound on the number of splits for a query.
  _NUM_QUERY_SPLITS_MAX = 50000
  # A lower bound on the number of splits for a query. This is to ensure that
  # we parallelize the query even when Datastore statistics are not available.
  _NUM_QUERY_SPLITS_MIN = 12
  # Default bundle size of 64MB.
  _DEFAULT_BUNDLE_SIZE_BYTES = 64 * 1024 * 1024

  def __init__(self, query, num_splits=0):
    """Initialize the `ReadFromDatastore` transform.

    This transform outputs elements of type
    :class:`~apache_beam.io.gcp.datastore.v1new.types.Entity`.

    Args:
      query: (:class:`~apache_beam.io.gcp.datastore.v1new.types.Query`) query
        used to fetch entities.
      num_splits: (:class:`int`) (optional) Number of splits for the query.
    """
    super(ReadFromDatastore, self).__init__()

    if not query.project:
      raise ValueError("query.project cannot be empty")
    if not query:
      raise ValueError("query cannot be empty")
    if num_splits < 0:
      raise ValueError("num_splits must be greater than or equal 0")

    self._project = query.project
    # using _namespace conflicts with DisplayData._namespace
    self._datastore_namespace = query.namespace
    self._query = query
    self._num_splits = num_splits

  def expand(self, pcoll):
    # This is a composite transform involves the following:
    #   1. Create a singleton of the user provided `query` and apply a ``ParDo``
    #   that splits the query into `num_splits` queries if possible.
    #
    #   If the value of `num_splits` is 0, the number of splits will be
    #   computed dynamically based on the size of the data for the `query`.
    #
    #   2. The resulting ``PCollection`` is sharded across workers using a
    #   ``Reshuffle`` operation.
    #
    #   3. In the third step, a ``ParDo`` reads entities for each query and
    #   outputs a ``PCollection[Entity]``.

    return (pcoll.pipeline
            | 'UserQuery' >> Create([self._query])
            | 'SplitQuery' >> ParDo(ReadFromDatastore._SplitQueryFn(
                self._num_splits))
            | Reshuffle()
            | 'Read' >> ParDo(ReadFromDatastore._QueryFn()))

  def display_data(self):
    disp_data = {'project': self._query.project,
                 'query': str(self._query),
                 'num_splits': self._num_splits}

    if self._datastore_namespace is not None:
      disp_data['namespace'] = self._datastore_namespace

    return disp_data

  @typehints.with_input_types(types.Query)
  @typehints.with_output_types(types.Query)
  class _SplitQueryFn(DoFn):
    """A `DoFn` that splits a given query into multiple sub-queries."""
    def __init__(self, num_splits):
      super(ReadFromDatastore._SplitQueryFn, self).__init__()
      self._num_splits = num_splits

    def process(self, query, *args, **kwargs):
      client = helper.get_client(query.project, query.namespace)
      try:
        # Short circuit estimating num_splits if split is not possible.
        query_splitter.validate_split(query)

        if self._num_splits == 0:
          estimated_num_splits = self.get_estimated_num_splits(client, query)
        else:
          estimated_num_splits = self._num_splits

        logging.info("Splitting the query into %d splits", estimated_num_splits)
        query_splits = query_splitter.get_splits(
            client, query, estimated_num_splits)
      except query_splitter.QuerySplitterError:
        logging.info("Unable to parallelize the given query: %s", query,
                     exc_info=True)
        query_splits = [query]

      return query_splits

    def display_data(self):
      disp_data = {'num_splits': self._num_splits}
      return disp_data

    @staticmethod
    def query_latest_statistics_timestamp(client):
      """Fetches the latest timestamp of statistics from Cloud Datastore.

      Cloud Datastore system tables with statistics are periodically updated.
      This method fetches the latest timestamp (in microseconds) of statistics
      update using the `__Stat_Total__` table.
      """
      if client.namespace is None:
        kind = '__Stat_Total__'
      else:
        kind = '__Stat_Ns_Total__'
      query = client.query(kind=kind, order=["-timestamp", ])
      entities = list(query.fetch(limit=1))
      if not entities:
        raise RuntimeError("Datastore total statistics unavailable.")
      return entities[0]['timestamp']

    @staticmethod
    def get_estimated_size_bytes(client, query):
      """Get the estimated size of the data returned by this instance's query.

      Cloud Datastore provides no way to get a good estimate of how large the
      result of a query is going to be. Hence we use the __Stat_Kind__ system
      table to get size of the entire kind as an approximate estimate, assuming
      exactly 1 kind is specified in the query.
      See https://cloud.google.com/datastore/docs/concepts/stats.
      """
      kind_name = query.kind
      latest_timestamp = (
          ReadFromDatastore._SplitQueryFn
          .query_latest_statistics_timestamp(client))
      logging.info('Latest stats timestamp for kind %s is %s',
                   kind_name, latest_timestamp)

      if client.namespace is None:
        kind = '__Stat_Kind__'
      else:
        kind = '__Stat_Ns_Kind__'
      query = client.query(kind=kind)
      query.add_filter('kind_name', '=', kind_name)
      query.add_filter('timestamp', '=', latest_timestamp)

      entities = list(query.fetch(limit=1))
      if not entities:
        raise RuntimeError(
            'Datastore statistics for kind %s unavailable' % kind_name)
      return entities[0]['entity_bytes']

    @staticmethod
    def get_estimated_num_splits(client, query):
      """Computes the number of splits to be performed on the query."""
      try:
        estimated_size_bytes = (
            ReadFromDatastore._SplitQueryFn
            .get_estimated_size_bytes(client, query))
        logging.info('Estimated size bytes for query: %s', estimated_size_bytes)
        num_splits = int(min(ReadFromDatastore._NUM_QUERY_SPLITS_MAX, round(
            (float(estimated_size_bytes) /
             ReadFromDatastore._DEFAULT_BUNDLE_SIZE_BYTES))))
      except Exception as e:
        logging.warning('Failed to fetch estimated size bytes: %s', e)
        # Fallback in case estimated size is unavailable.
        num_splits = ReadFromDatastore._NUM_QUERY_SPLITS_MIN

      return max(num_splits, ReadFromDatastore._NUM_QUERY_SPLITS_MIN)

  @typehints.with_input_types(types.Query)
  @typehints.with_output_types(types.Entity)
  class _QueryFn(DoFn):
    """A DoFn that fetches entities from Cloud Datastore, for a given query."""
    def process(self, query, *unused_args, **unused_kwargs):
      _client = helper.get_client(query.project, query.namespace)
      client_query = query._to_client_query(_client)
      for client_entity in client_query.fetch(query.limit):
        yield types.Entity.from_client_entity(client_entity)


class _Mutate(PTransform):
  """A ``PTransform`` that writes mutations to Cloud Datastore.

  Only idempotent Datastore mutation operations (upsert and delete) are
  supported, as the commits are retried when failures occur.
  """

  def __init__(self, mutate_fn):
    """Initializes a Mutate transform.

     Args:
       mutate_fn: Instance of `DatastoreMutateFn` to use.
     """
    self._mutate_fn = mutate_fn

  def expand(self, pcoll):
    return pcoll | 'Write Batch to Datastore' >> ParDo(self._mutate_fn)

  class DatastoreMutateFn(DoFn):
    """A ``DoFn`` that write mutations to Datastore.

    Mutations are written in batches, where the maximum batch size is
    `util.WRITE_BATCH_SIZE`.

    Commits are non-transactional. If a commit fails because of a conflict over
    an entity group, the commit will be retried. This means that the mutation
    should be idempotent (`upsert` and `delete` mutations) to prevent duplicate
    data or errors.
    """
    def __init__(self, project):
      """
      Args:
        project: (str) cloud project id
      """
      self._project = project
      self._client = None
      self._rpc_successes = Metrics.counter(
          _Mutate.DatastoreMutateFn, "datastoreRpcSuccesses")
      self._rpc_errors = Metrics.counter(
          _Mutate.DatastoreMutateFn, "datastoreRpcErrors")
      self._throttled_secs = Metrics.counter(
          _Mutate.DatastoreMutateFn, "cumulativeThrottlingSeconds")
      self._throttler = AdaptiveThrottler(window_ms=120000, bucket_ms=1000,
                                          overload_ratio=1.25)

    def _update_rpc_stats(self, successes=0, errors=0, throttled_secs=0):
      self._rpc_successes.inc(successes)
      self._rpc_errors.inc(errors)
      self._throttled_secs.inc(throttled_secs)

    def start_bundle(self):
      self._client = helper.get_client(self._project, namespace=None)
      self._init_batch()

      self._batch_sizer = util.DynamicBatchSizer()
      self._target_batch_size = self._batch_sizer.get_batch_size(
          time.time() * 1000)

    def add_element_to_batch(self, element):
      raise NotImplementedError

    def process(self, element):
      self.add_element_to_batch(element)
      self._batch_bytes_size += self._batch.mutations[-1].ByteSize()

      if (len(self._batch.mutations) >= self._target_batch_size or
          self._batch_bytes_size > util.WRITE_BATCH_MAX_BYTES_SIZE):
        self._flush_batch()

    def finish_bundle(self):
      if self._batch.mutations:
        self._flush_batch()

    def _init_batch(self):
      self._batch_bytes_size = 0
      self._batch = self._client.batch()
      self._batch_mutations = []

    def _flush_batch(self):
      # Flush the current batch of mutations to Cloud Datastore.
      latency_ms = helper.write_mutations(
          self._batch, self._batch_mutations, self._throttler,
          rpc_stats_callback=self._update_rpc_stats,
          throttle_delay=util.WRITE_BATCH_TARGET_LATENCY_MS // 1000)
      logging.debug("Successfully wrote %d mutations in %dms.",
                    len(self._batch.mutations), latency_ms)

      now = time.time() * 1000
      self._batch_sizer.report_latency(
          now, latency_ms, len(self._batch.mutations))
      self._target_batch_size = self._batch_sizer.get_batch_size(now)

      self._init_batch()


@typehints.with_input_types(types.Entity)
class WriteToDatastore(_Mutate):
  """
  Writes elements of type
  :class:`~apache_beam.io.gcp.datastore.v1new.types.Entity` to Cloud Datastore.

  Entity keys must be complete. The ``project`` field in each key must match the
  project ID passed to this transform. If ``project`` field in entity or
  property key is empty then it is filled with the project ID passed to this
  transform.
  """

  def __init__(self, project):
    """Initialize the `WriteToDatastore` transform.

    Args:
      project: (:class:`str`) The ID of the project to write entities to.
    """
    mutate_fn = WriteToDatastore._DatastoreWriteFn(project)
    super(WriteToDatastore, self).__init__(mutate_fn)

  class _DatastoreWriteFn(_Mutate.DatastoreMutateFn):
    def add_element_to_batch(self, element):
      if not isinstance(element, types.Entity):
        raise ValueError('apache_beam.io.gcp.datastore.v1new.datastoreio.Entity'
                         ' expected, got: %s' % type(element))
      if not element.key.project:
        element.key.project = self._project
      client_entity = element.to_client_entity()
      if client_entity.key.is_partial:
        raise ValueError('Entities to be written to Cloud Datastore must '
                         'have complete keys:\n%s' % client_entity)
      self._batch.put(client_entity)
      self._batch_mutations.append((helper._WRITE_MUTATION, client_entity))

    def display_data(self):
      return {
          'mutation': 'Write (upsert)',
          'project': self._project,
      }


@typehints.with_input_types(types.Key)
class DeleteFromDatastore(_Mutate):
  """
  Deletes elements matching input
  :class:`~apache_beam.io.gcp.datastore.v1new.types.Key` elements from Cloud
  Datastore.

  Keys must be complete. The ``project`` field in each key must match the
  project ID passed to this transform. If ``project`` field in key is empty then
  it is filled with the project ID passed to this transform.
  """
  def __init__(self, project):
    """Initialize the `DeleteFromDatastore` transform.

    Args:
      project: (:class:`str`) The ID of the project from which the entities will
        be deleted.
    """
    mutate_fn = DeleteFromDatastore._DatastoreDeleteFn(project)
    super(DeleteFromDatastore, self).__init__(mutate_fn)

  class _DatastoreDeleteFn(_Mutate.DatastoreMutateFn):
    def add_element_to_batch(self, element):
      if not isinstance(element, types.Key):
        raise ValueError('apache_beam.io.gcp.datastore.v1new.datastoreio.Key'
                         ' expected, got: %s' % type(element))
      if not element.project:
        element.project = self._project
      client_key = element.to_client_key()
      if client_key.is_partial:
        raise ValueError('Keys to be deleted from Cloud Datastore must be '
                         'complete:\n%s' % client_key)
      self._batch.delete(client_key)
      self._batch_mutations.append((helper._DELETE_MUTATION, client_key))

    def display_data(self):
      return {
          'mutation': 'Delete',
          'project': self._project,
      }
