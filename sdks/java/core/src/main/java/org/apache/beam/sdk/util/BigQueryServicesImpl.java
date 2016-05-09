/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.sdk.util;

import org.apache.beam.sdk.options.BigQueryOptions;

import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.util.BackOff;
import com.google.api.client.util.BackOffUtils;
import com.google.api.client.util.Sleeper;
import com.google.api.services.bigquery.Bigquery;
import com.google.api.services.bigquery.model.Dataset;
import com.google.api.services.bigquery.model.DatasetReference;
import com.google.api.services.bigquery.model.Job;
import com.google.api.services.bigquery.model.JobConfiguration;
import com.google.api.services.bigquery.model.JobConfigurationExtract;
import com.google.api.services.bigquery.model.JobConfigurationLoad;
import com.google.api.services.bigquery.model.JobConfigurationQuery;
import com.google.api.services.bigquery.model.JobReference;
import com.google.api.services.bigquery.model.JobStatus;
import com.google.api.services.bigquery.model.Table;
import com.google.api.services.bigquery.model.TableReference;
import com.google.api.services.bigquery.model.TableRow;
import com.google.cloud.hadoop.util.ApiErrorExtractor;
import com.google.common.annotations.VisibleForTesting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.NoSuchElementException;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

/**
 * An implementation of {@link BigQueryServices} that actually communicates with the cloud BigQuery
 * service.
 */
public class BigQueryServicesImpl implements BigQueryServices {

  private static final Logger LOG = LoggerFactory.getLogger(BigQueryServicesImpl.class);

  // The maximum number of attempts to execute a BigQuery RPC.
  private static final int MAX_RPC_ATTEMPTS = 10;

  // The initial backoff for executing a BigQuery RPC.
  private static final long INITIAL_RPC_BACKOFF_MILLIS = TimeUnit.SECONDS.toMillis(1);

  // The initial backoff for polling the status of a BigQuery job.
  private static final long INITIAL_JOB_STATUS_POLL_BACKOFF_MILLIS = TimeUnit.SECONDS.toMillis(1);

  @Override
  public JobService getJobService(BigQueryOptions options) {
    return new JobServiceImpl(options);
  }

  @Override
  public DatasetService getDatasetService(BigQueryOptions options) {
    return new DatasetServiceImpl(options);
  }

  @Override
  public BigQueryJsonReader getReaderFromTable(BigQueryOptions bqOptions, TableReference tableRef) {
    return BigQueryJsonReaderImpl.fromTable(bqOptions, tableRef);
  }

  @Override
  public BigQueryJsonReader getReaderFromQuery(
      BigQueryOptions bqOptions, String query, String projectId, @Nullable Boolean flatten) {
    return BigQueryJsonReaderImpl.fromQuery(bqOptions, query, projectId, flatten);
  }

  @VisibleForTesting
  static class JobServiceImpl implements BigQueryServices.JobService {
    private final ApiErrorExtractor errorExtractor;
    private final Bigquery client;

    @VisibleForTesting
    JobServiceImpl(Bigquery client) {
      this.errorExtractor = new ApiErrorExtractor();
      this.client = client;
    }

    private JobServiceImpl(BigQueryOptions options) {
      this.errorExtractor = new ApiErrorExtractor();
      this.client = Transport.newBigQueryClient(options).build();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Retries the RPC for at most {@code MAX_RPC_ATTEMPTS} times until it succeeds.
     *
     * @throws IOException if it exceeds max RPC retries.
     */
    @Override
    public void startLoadJob(
        String jobId,
        JobConfigurationLoad loadConfig) throws InterruptedException, IOException {
      Job job = new Job();
      JobReference jobRef = new JobReference();
      jobRef.setProjectId(loadConfig.getDestinationTable().getProjectId());
      jobRef.setJobId(jobId);
      job.setJobReference(jobRef);
      JobConfiguration jobConfig = new JobConfiguration();
      jobConfig.setLoad(loadConfig);
      job.setConfiguration(jobConfig);

      startJob(job, errorExtractor, client);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Retries the RPC for at most {@code MAX_RPC_ATTEMPTS} times until it succeeds.
     *
     * @throws IOException if it exceeds max RPC retries.
     */
    @Override
    public void startExtractJob(String jobId, JobConfigurationExtract extractConfig)
        throws InterruptedException, IOException {
      Job job = new Job();
      JobReference jobRef = new JobReference();
      jobRef.setProjectId(extractConfig.getSourceTable().getProjectId());
      jobRef.setJobId(jobId);
      job.setJobReference(jobRef);
      JobConfiguration jobConfig = new JobConfiguration();
      jobConfig.setExtract(extractConfig);
      job.setConfiguration(jobConfig);

      startJob(job, errorExtractor, client);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Retries the RPC for at most {@code MAX_RPC_ATTEMPTS} times until it succeeds.
     *
     * @throws IOException if it exceeds max RPC retries.
     */
    @Override
    public void startQueryJob(String jobId, JobConfigurationQuery queryConfig, boolean dryRun)
        throws IOException, InterruptedException {
      Job job = new Job();
      JobReference jobRef = new JobReference();
      jobRef.setProjectId(queryConfig.getDestinationTable().getProjectId());
      jobRef.setJobId(jobId);
      job.setJobReference(jobRef);
      JobConfiguration jobConfig = new JobConfiguration();
      jobConfig.setQuery(queryConfig);
      jobConfig.setDryRun(dryRun);
      job.setConfiguration(jobConfig);

      startJob(job, errorExtractor, client);
    }

    private static void startJob(Job job,
      ApiErrorExtractor errorExtractor,
      Bigquery client) throws IOException, InterruptedException {
      BackOff backoff =
          new AttemptBoundedExponentialBackOff(MAX_RPC_ATTEMPTS, INITIAL_RPC_BACKOFF_MILLIS);
      startJob(job, errorExtractor, client, Sleeper.DEFAULT, backoff);
    }

    @VisibleForTesting
    static void startJob(
        Job job,
        ApiErrorExtractor errorExtractor,
        Bigquery client,
        Sleeper sleeper,
        BackOff backoff) throws IOException, InterruptedException {
      JobReference jobRef = job.getJobReference();
      Exception lastException = null;
      do {
        try {
          client.jobs().insert(jobRef.getProjectId(), job).execute();
          return; // SUCCEEDED
        } catch (GoogleJsonResponseException e) {
          if (errorExtractor.itemAlreadyExists(e)) {
            return; // SUCCEEDED
          }
          // ignore and retry
          LOG.warn("Ignore the error and retry inserting the job.", e);
          lastException = e;
        } catch (IOException e) {
          // ignore and retry
          LOG.warn("Ignore the error and retry inserting the job.", e);
          lastException = e;
        }
      } while (nextBackOff(sleeper, backoff));
      throw new IOException(
          String.format(
              "Unable to insert job: %s, aborting after %d retries.",
              jobRef.getJobId(), MAX_RPC_ATTEMPTS),
          lastException);
    }

    @Override
    public Job pollJob(String projectId, String jobId, int maxAttempts)
        throws InterruptedException {
      BackOff backoff = new AttemptBoundedExponentialBackOff(
          maxAttempts, INITIAL_JOB_STATUS_POLL_BACKOFF_MILLIS);
      return pollJob(projectId, jobId, Sleeper.DEFAULT, backoff);
    }

    @VisibleForTesting
    Job pollJob(
        String projectId,
        String jobId,
        Sleeper sleeper,
        BackOff backoff) throws InterruptedException {
      do {
        try {
          Job job = client.jobs().get(projectId, jobId).execute();
          JobStatus status = job.getStatus();
          if (status != null && status.getState() != null && status.getState().equals("DONE")) {
            return job;
          }
          // The job is not DONE, wait longer and retry.
        } catch (IOException e) {
          // ignore and retry
          LOG.warn("Ignore the error and retry polling job status.", e);
        }
      } while (nextBackOff(sleeper, backoff));
      LOG.warn("Unable to poll job status: {}, aborting after reached max retries.", jobId);
      return null;
    }
  }

  @VisibleForTesting
  static class DatasetServiceImpl implements DatasetService {
    private final ApiErrorExtractor errorExtractor;
    private final Bigquery client;

    @VisibleForTesting
    DatasetServiceImpl(Bigquery client) {
      this.errorExtractor = new ApiErrorExtractor();
      this.client = client;
    }

    private DatasetServiceImpl(BigQueryOptions bqOptions) {
      this.errorExtractor = new ApiErrorExtractor();
      this.client = Transport.newBigQueryClient(bqOptions).build();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Retries the RPC for at most {@code MAX_RPC_ATTEMPTS} times until it succeeds.
     *
     * @throws IOException if it exceeds max RPC retries.
     */
    @Override
    public Table getTable(String projectId, String datasetId, String tableId)
        throws IOException, InterruptedException {
      BackOff backoff =
          new AttemptBoundedExponentialBackOff(MAX_RPC_ATTEMPTS, INITIAL_RPC_BACKOFF_MILLIS);
      return getTable(projectId, datasetId, tableId, Sleeper.DEFAULT, backoff);
    }

    @VisibleForTesting
    Table getTable(
        String projectId,
        String datasetId,
        String tableId,
        Sleeper sleeper,
        BackOff backoff)
        throws IOException, InterruptedException {
      Exception lastException = null;
      do {
        try {
          return client.tables().get(projectId, datasetId, tableId).execute();
        } catch (IOException e) {
          LOG.warn("Ignore the error and retry getting the table.", e);
          lastException = e;
        }
      } while (nextBackOff(sleeper, backoff));
      throw new IOException(
          String.format(
              "Unable to get table: %s, aborting after %d retries.",
              tableId, MAX_RPC_ATTEMPTS),
          lastException);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Retries the RPC for at most {@code MAX_RPC_ATTEMPTS} times until it succeeds.
     *
     * @throws IOException if it exceeds max RPC retries.
     */
    @Override
    public void createDataset(
        String projectId, String datasetId, String location, String description)
        throws IOException, InterruptedException {
      BackOff backoff =
          new AttemptBoundedExponentialBackOff(MAX_RPC_ATTEMPTS, INITIAL_RPC_BACKOFF_MILLIS);
      createDataset(projectId, datasetId, location, description, Sleeper.DEFAULT, backoff);
    }

    @VisibleForTesting
    void createDataset(
        String projectId,
        String datasetId,
        String location,
        String description,
        Sleeper sleeper,
        BackOff backoff) throws IOException, InterruptedException {
      DatasetReference datasetRef = new DatasetReference();
      datasetRef.setProjectId(projectId);
      datasetRef.setDatasetId(datasetId);

      Dataset dataset = new Dataset();
      dataset.setDatasetReference(datasetRef);
      dataset.setLocation(location);
      dataset.setFriendlyName(location);
      dataset.setDescription(description);

      Exception lastException;
      do {
        try {
          client.datasets().insert(projectId, dataset).execute();
          return; // SUCCEEDED
        } catch (GoogleJsonResponseException e) {
          if (errorExtractor.itemAlreadyExists(e)) {
            return; // SUCCEEDED
          }
          // ignore and retry
          LOG.warn("Ignore the error and retry creating the dataset.", e);
          lastException = e;
        } catch (IOException e) {
          LOG.warn("Ignore the error and retry creating the dataset.", e);
          lastException = e;
        }
      } while (nextBackOff(sleeper, backoff));
      throw new IOException(
          String.format(
              "Unable to create dataset: %s, aborting after %d retries.",
              datasetId, MAX_RPC_ATTEMPTS),
          lastException);
    }
  }

  private static class BigQueryJsonReaderImpl implements BigQueryJsonReader {
    BigQueryTableRowIterator iterator;

    private BigQueryJsonReaderImpl(BigQueryTableRowIterator iterator) {
      this.iterator = iterator;
    }

    private static BigQueryJsonReader fromQuery(
        BigQueryOptions bqOptions,
        String query,
        String projectId,
        @Nullable Boolean flattenResults) {
      return new BigQueryJsonReaderImpl(
          BigQueryTableRowIterator.fromQuery(
              query, projectId, Transport.newBigQueryClient(bqOptions).build(), flattenResults));
    }

    private static BigQueryJsonReader fromTable(
        BigQueryOptions bqOptions,
        TableReference tableRef) {
      return new BigQueryJsonReaderImpl(BigQueryTableRowIterator.fromTable(
          tableRef, Transport.newBigQueryClient(bqOptions).build()));
    }

    @Override
    public boolean start() throws IOException {
      try {
        iterator.open();
        return iterator.advance();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException("Interrupted during start() operation", e);
      }
    }

    @Override
    public boolean advance() throws IOException {
      try {
        return iterator.advance();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException("Interrupted during advance() operation", e);
      }
    }

    @Override
    public TableRow getCurrent() throws NoSuchElementException {
      return iterator.getCurrent();
    }

    @Override
    public void close() throws IOException {
      iterator.close();
    }
  }

  /**
   * Identical to {@link BackOffUtils#next} but without checked IOException.
   * @throws InterruptedException
   */
  private static boolean nextBackOff(Sleeper sleeper, BackOff backoff) throws InterruptedException {
    try {
      return BackOffUtils.next(sleeper, backoff);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
