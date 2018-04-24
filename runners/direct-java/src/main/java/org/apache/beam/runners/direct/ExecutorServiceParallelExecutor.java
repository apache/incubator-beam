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
package org.apache.beam.runners.direct;

import com.google.common.base.Optional;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.cache.RemovalListener;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.apache.beam.runners.local.ExecutionDriver;
import org.apache.beam.runners.local.ExecutionDriver.DriverState;
import org.apache.beam.runners.local.PipelineMessageReceiver;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.PipelineResult.State;
import org.apache.beam.sdk.runners.AppliedPTransform;
import org.apache.beam.sdk.util.UserCodeException;
import org.apache.beam.sdk.values.PValue;
import org.joda.time.Duration;
import org.joda.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An {@link PipelineExecutor} that uses an underlying {@link ExecutorService} and {@link
 * EvaluationContext} to execute a {@link Pipeline}.
 */
final class ExecutorServiceParallelExecutor
    implements PipelineExecutor, BundleProcessor<CommittedBundle<?>, AppliedPTransform<?, ?, ?>> {
  private static final Logger LOG = LoggerFactory.getLogger(ExecutorServiceParallelExecutor.class);

  private final String id = UUID.randomUUID().toString();

  private final int targetParallelism;
  private final ExecutorService executorService;

  private final TransformEvaluatorRegistry registry;

  private final EvaluationContext evaluationContext;

  private final TransformExecutorFactory executorFactory;
  private final TransformExecutorService parallelExecutorService;
  private final LoadingCache<StepAndKey, TransformExecutorService> serialExecutorServices;

  private final QueueMessageReceiver visibleUpdates;

  private final AtomicReference<State> pipelineState = new AtomicReference<>(State.RUNNING);
  private final AtomicReference<Throwable> resultException = new AtomicReference<>();
  private volatile CompletableFuture<Boolean> endingTask;

  public static ExecutorServiceParallelExecutor create(
      int targetParallelism,
      TransformEvaluatorRegistry registry,
      Map<String, Collection<ModelEnforcementFactory>> transformEnforcements,
      EvaluationContext context) {
    return new ExecutorServiceParallelExecutor(
        targetParallelism, registry, transformEnforcements, context);
  }

  private ExecutorServiceParallelExecutor(
      int targetParallelism,
      TransformEvaluatorRegistry registry,
      Map<String, Collection<ModelEnforcementFactory>> transformEnforcements,
      EvaluationContext context) {
    this.targetParallelism = targetParallelism;
    // Don't use Daemon threads for workers. The Pipeline should continue to execute even if there
    // are no other active threads (for example, because waitUntilFinish was not called)
    this.executorService =
        // TODO: think how to make that configurable through options, threadfactory, poolfactory?
        Executors.newFixedThreadPool(
            targetParallelism,
            new ThreadFactoryBuilder()
                .setThreadFactory(MoreExecutors.platformThreadFactory())
                .setNameFormat("direct-runner-worker-" + id)
                .setUncaughtExceptionHandler(onThreadException())
                .build());
    this.registry = registry;
    this.evaluationContext = context;

    // Weak Values allows TransformExecutorServices that are no longer in use to be reclaimed.
    // Executing TransformExecutorServices have a strong reference to their TransformExecutorService
    // which stops the TransformExecutorServices from being prematurely garbage collected
    serialExecutorServices =
        CacheBuilder.newBuilder()
            .weakValues()
            .removalListener(shutdownExecutorServiceListener())
            .build(serialTransformExecutorServiceCacheLoader());

    this.visibleUpdates = new QueueMessageReceiver();

    parallelExecutorService = TransformExecutorServices.parallel(executorService);
    executorFactory = new DirectTransformExecutor.Factory(context, registry, transformEnforcements);
  }

  private CacheLoader<StepAndKey, TransformExecutorService>
      serialTransformExecutorServiceCacheLoader() {
    return new CacheLoader<StepAndKey, TransformExecutorService>() {
      @Override
      public TransformExecutorService load(StepAndKey stepAndKey) throws Exception {
        return TransformExecutorServices.serial(executorService);
      }
    };
  }

  private RemovalListener<StepAndKey, TransformExecutorService> shutdownExecutorServiceListener() {
    return notification -> {
      TransformExecutorService service = notification.getValue();
      if (service != null) {
        service.shutdown();
      }
    };
  }

  @Override
  public void start(DirectGraph graph, RootProviderRegistry rootProviderRegistry) {
    int numTargetSplits = Math.max(3, targetParallelism);
    ImmutableMap.Builder<AppliedPTransform<?, ?, ?>, ConcurrentLinkedQueue<CommittedBundle<?>>>
        pendingRootBundles = ImmutableMap.builder();
    for (AppliedPTransform<?, ?, ?> root : graph.getRootTransforms()) {
      ConcurrentLinkedQueue<CommittedBundle<?>> pending = new ConcurrentLinkedQueue<>();
      try {
        Collection<CommittedBundle<?>> initialInputs =
            rootProviderRegistry.getInitialInputs(root, numTargetSplits);
        pending.addAll(initialInputs);
      } catch (Exception e) {
        throw UserCodeException.wrap(e);
      }
      pendingRootBundles.put(root, pending);
    }
    evaluationContext.initialize(pendingRootBundles.build());
    final ExecutionDriver executionDriver =
        QuiescenceDriver.create(
            evaluationContext, graph, this, visibleUpdates, pendingRootBundles.build());
    executorService.execute(
        new Runnable() {
          @Override
          public void run() {
            DriverState drive = executionDriver.drive();
            if (drive.isTermainal()) {
              switch (drive) {
                case FAILED:
                  executeEndTask(() -> shutdown(State.FAILED));
                  break;
                case SHUTDOWN:
                  executeEndTask(() -> shutdown(State.DONE));
                  break;
                case CONTINUE:
                  throw new IllegalStateException(
                      String.format("%s should not be a terminal state", DriverState.CONTINUE));
                default:
                  throw new IllegalArgumentException(
                      String.format("Unknown %s %s", DriverState.class.getSimpleName(), drive));
              }
            } else {
              executorService.execute(this);
            }
          }

          // use another thread to ensure we can stop current one in shutdown(state)
          private void executeEndTask(final Runnable task) {
            endingTask = new CompletableFuture<>();
            final Thread thread = new Thread(() -> {
              try {
                task.run();
                endingTask.complete(true);
              } catch (final Throwable th) {
                endingTask.completeExceptionally(th);
              }
            });
            thread.setUncaughtExceptionHandler(onThreadException());
            thread.setName("shutting-down-direct-runner-instance-" + id);
            thread.start();
          }
        });
  }

  // when these threads hit an exception it is already processed so just log it
  private Thread.UncaughtExceptionHandler onThreadException() {
    return (t, e) -> LOG.debug(e.getMessage(), e);
  }

  @SuppressWarnings("unchecked")
  @Override
  public void process(
      CommittedBundle<?> bundle,
      AppliedPTransform<?, ?, ?> consumer,
      CompletionCallback onComplete) {
    evaluateBundle(consumer, bundle, onComplete);
  }

  private <T> void evaluateBundle(
      final AppliedPTransform<?, ?, ?> transform,
      final CommittedBundle<T> bundle,
      final CompletionCallback onComplete) {
    TransformExecutorService transformExecutor;

    if (isKeyed(bundle.getPCollection())) {
      final StepAndKey stepAndKey = StepAndKey.of(transform, bundle.getKey());
      // This executor will remain reachable until it has executed all scheduled transforms.
      // The TransformExecutors keep a strong reference to the Executor, the ExecutorService keeps
      // a reference to the scheduled DirectTransformExecutor callable. Follow-up TransformExecutors
      // (scheduled due to the completion of another DirectTransformExecutor) are provided to the
      // ExecutorService before the Earlier DirectTransformExecutor callable completes.
      transformExecutor = serialExecutorServices.getUnchecked(stepAndKey);
    } else {
      transformExecutor = parallelExecutorService;
    }

    TransformExecutor callable =
        executorFactory.create(bundle, transform, onComplete, transformExecutor);
    if (!pipelineState.get().isTerminal()) {
      transformExecutor.schedule(callable);
    }
  }

  private boolean isKeyed(PValue pvalue) {
    return evaluationContext.isKeyed(pvalue);
  }

  @Override
  public State waitUntilFinish(Duration duration) throws Exception {
    Instant completionTime;
    final boolean infinite = duration.equals(Duration.ZERO);
    if (infinite) {
      completionTime = new Instant(Long.MAX_VALUE);
    } else {
      completionTime = Instant.now().plus(duration);
    }

    VisibleExecutorUpdate update = null;
    while (Instant.now().isBefore(completionTime)
        && (update == null || isTerminalStateUpdate(update))) {
      // Get an update; don't block forever if another thread has handled it. The call to poll will
      // wait the entire timeout; this call primarily exists to relinquish any core.
      update = visibleUpdates.tryNext(Duration.millis(25L));
      if (update == null && pipelineState.get().isTerminal()) {
        // there are no updates to process and no updates will ever be published because the
        // executor is shutdown
        Throwable throwable = resultException.get();
        if (throwable != null) {
          if (infinite) {
            waitEnd();
          }
          rethrow(throwable);
        }
        break;
      } else {
        processUpdate(update);
        // don't directly exit even on error,
        // wait next iteration to let a chance to cleanup resources
      }
    }
    if (infinite) {
      waitEnd();
    }
    return pipelineState.get();
  }

  private void waitEnd() { // note that the best would be to return the completionstage to the user
    if (endingTask == null) {
      return;
    }
    try {
      endingTask.get();
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (final ExecutionException e) {
      throw new IllegalStateException(e);
    }
  }

  private void processUpdate(final VisibleExecutorUpdate update) {
    if (update != null && update.thrown.isPresent()) {
      final Throwable thrown = update.thrown.get();
      final Throwable previous = resultException.get();
      if (previous != null) {
        previous.addSuppressed(thrown);
      } else {
        if (!resultException.compareAndSet(null, thrown)) {
          resultException.get().addSuppressed(thrown);
        }
      }
    }
  }

  private void rethrow(final Throwable thrown) throws Exception {
    if (thrown instanceof Exception) {
      throw (Exception) thrown;
    } else if (thrown instanceof Error) {
      throw (Error) thrown;
    }
    throw new Exception("Unknown Type of Throwable", thrown);
  }

  @Override
  public State getPipelineState() {
    return pipelineState.get();
  }

  private boolean isTerminalStateUpdate(final VisibleExecutorUpdate update) {
    final State state = update.getNewState();
    return state == null || state.isTerminal();
  }

  @Override
  public void stop() {
    shutdown(State.CANCELLED);
    visibleUpdates.cancelled();
  }

  private void shutdown(final State newState) {
    LOG.debug("Pipeline has terminated. Shutting down.");

    final Collection<Exception> errors = new ArrayList<>();
    // Stop accepting new work before shutting down the executor. This ensures that thread don't try
    // to add work to the shutdown executor.
    try {
      serialExecutorServices.invalidateAll();
      serialExecutorServices.cleanUp();
    } catch (final RuntimeException re) {
      errors.add(re);
    }
    try {
      parallelExecutorService.shutdown();
    } catch (final RuntimeException re) {
      errors.add(re);
    }
    try {
      executorService.shutdown(); // don't exec tasks, they can be infinite
    } catch (final RuntimeException re) {
      errors.add(re);
    }
    while (!executorService.isTerminated()) {
      try {
          Thread.sleep(50L);
      } catch (final InterruptedException e) {
          Thread.currentThread().interrupt();
      }
    }
    try {
      registry.cleanup();
    } catch (final Exception e) {
      errors.add(e);
    }
    pipelineState.compareAndSet(State.RUNNING, newState);
    if (!errors.isEmpty()) {
      final IllegalStateException exception = new IllegalStateException(
        "Error" + (errors.size() == 1 ? "" : "s") + " during executor shutdown:\n"
        + errors.stream().map(Exception::getMessage)
          .collect(Collectors.joining("\n- ", "- ", "")));
      visibleUpdates.failed(exception);
      throw exception;
    }
  }

  /**
   * An update of interest to the user. Used in {@link #waitUntilFinish} to decide whether to return
   * normally or throw an exception.
   */
  private static class VisibleExecutorUpdate {
    private final Optional<? extends Throwable> thrown;
    @Nullable private final State newState;

    public static VisibleExecutorUpdate fromException(Exception e) {
      return new VisibleExecutorUpdate(null, e);
    }

    public static VisibleExecutorUpdate fromError(Error err) {
      return new VisibleExecutorUpdate(State.FAILED, err);
    }

    public static VisibleExecutorUpdate finished() {
      return new VisibleExecutorUpdate(State.DONE, null);
    }

    public static VisibleExecutorUpdate cancelled() {
      return new VisibleExecutorUpdate(State.CANCELLED, null);
    }

    private VisibleExecutorUpdate(State newState, @Nullable Throwable exception) {
      this.thrown = Optional.fromNullable(exception);
      this.newState = newState;
    }

    State getNewState() {
      return newState;
    }
  }

  private static class QueueMessageReceiver implements PipelineMessageReceiver {
    // If the type of BlockingQueue changes, ensure the findbugs filter is updated appropriately
    private final BlockingQueue<VisibleExecutorUpdate> updates = new LinkedBlockingQueue<>();

    @Override
    public void failed(Exception e) {
      updates.offer(VisibleExecutorUpdate.fromException(e));
    }

    @Override
    public void failed(Error e) {
      updates.offer(VisibleExecutorUpdate.fromError(e));
    }

    @Override
    public void cancelled() {
      updates.offer(VisibleExecutorUpdate.cancelled());
    }

    @Override
    public void completed() {
      updates.offer(VisibleExecutorUpdate.finished());
    }

    /**
     * Try to get the next unconsumed message in this {@link QueueMessageReceiver}.
     */
    @Nullable
    private VisibleExecutorUpdate tryNext(Duration timeout) throws InterruptedException {
      return updates.poll(timeout.getMillis(), TimeUnit.MILLISECONDS);
    }
  }
}
