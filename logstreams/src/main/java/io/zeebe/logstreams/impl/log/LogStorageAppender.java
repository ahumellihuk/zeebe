/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */
package io.zeebe.logstreams.impl.log;

import static io.zeebe.dispatcher.impl.log.DataFrameDescriptor.flagsOffset;
import static io.zeebe.dispatcher.impl.log.DataFrameDescriptor.lengthOffset;

import com.netflix.concurrency.limits.limit.AbstractLimit;
import com.netflix.concurrency.limits.limit.WindowedLimit;
import io.atomix.raft.zeebe.ZeebeEntry;
import io.zeebe.dispatcher.BlockPeek;
import io.zeebe.dispatcher.Subscription;
import io.zeebe.dispatcher.impl.log.DataFrameDescriptor;
import io.zeebe.logstreams.impl.Loggers;
import io.zeebe.logstreams.impl.backpressure.AlgorithmCfg;
import io.zeebe.logstreams.impl.backpressure.AppendBackpressureMetrics;
import io.zeebe.logstreams.impl.backpressure.AppendEntryLimiter;
import io.zeebe.logstreams.impl.backpressure.AppendLimiter;
import io.zeebe.logstreams.impl.backpressure.AppenderGradient2Cfg;
import io.zeebe.logstreams.impl.backpressure.AppenderVegasCfg;
import io.zeebe.logstreams.impl.backpressure.BackpressureConstants;
import io.zeebe.logstreams.impl.backpressure.NoopAppendLimiter;
import io.zeebe.logstreams.spi.LogStorage;
import io.zeebe.logstreams.spi.LogStorage.AppendListener;
import io.zeebe.util.Environment;
import io.zeebe.util.TriConsumer;
import io.zeebe.util.health.FailureListener;
import io.zeebe.util.health.HealthMonitorable;
import io.zeebe.util.health.HealthStatus;
import io.zeebe.util.sched.Actor;
import io.zeebe.util.sched.future.ActorFuture;
import io.zeebe.util.sched.future.CompletableActorFuture;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Queue;
import org.agrona.concurrent.UnsafeBuffer;
import org.slf4j.Logger;

/** Consume the write buffer and append the blocks to the distributedlog. */
public final class LogStorageAppender extends Actor implements HealthMonitorable {

  public static final Logger LOG = Loggers.LOGSTREAMS_LOGGER;
  private static final Map<String, AlgorithmCfg> ALGORITHM_CFG =
      Map.of("vegas", new AppenderVegasCfg(), "gradient2", new AppenderGradient2Cfg());

  private final String name;
  private final Subscription writeBufferSubscription;
  private final int maxAppendBlockSize;
  private final LogStorage logStorage;
  private final AppendLimiter appendEntryLimiter;
  private final AppendBackpressureMetrics appendBackpressureMetrics;
  private final Environment env;
  private FailureListener failureListener;
  private final ActorFuture<Void> closeFuture;

  public LogStorageAppender(
      final String name,
      final int partitionId,
      final LogStorage logStorage,
      final Subscription writeBufferSubscription,
      final int maxBlockSize) {
    this.env = new Environment();
    this.name = name;
    this.logStorage = logStorage;
    this.writeBufferSubscription = writeBufferSubscription;
    this.maxAppendBlockSize = maxBlockSize;
    appendBackpressureMetrics = new AppendBackpressureMetrics(partitionId);

    final boolean isBackpressureEnabled =
        env.getBool(BackpressureConstants.ENV_BP_APPENDER).orElse(true);
    appendEntryLimiter =
        isBackpressureEnabled ? initBackpressure(partitionId) : initNoBackpressure(partitionId);
    closeFuture = new CompletableActorFuture<>();
  }

  private AppendLimiter initBackpressure(final int partitionId) {
    final String algorithmName =
        env.get(BackpressureConstants.ENV_BP_APPENDER_ALGORITHM).orElse("vegas").toLowerCase();
    final AlgorithmCfg algorithmCfg =
        ALGORITHM_CFG.getOrDefault(algorithmName, new AppenderVegasCfg());
    algorithmCfg.applyEnvironment(env);

    final AbstractLimit abstractLimit = algorithmCfg.get();
    final boolean windowedLimiter =
        env.getBool(BackpressureConstants.ENV_BP_APPENDER_WINDOWED).orElse(false);

    LOG.debug(
        "Configured log appender back pressure at partition {} as {}. Window limiting is {}",
        partitionId,
        algorithmCfg,
        windowedLimiter ? "enabled" : "disabled");
    return AppendEntryLimiter.builder()
        .limit(windowedLimiter ? WindowedLimit.newBuilder().build(abstractLimit) : abstractLimit)
        .partitionId(partitionId)
        .build();
  }

  private AppendLimiter initNoBackpressure(final int partition) {
    LOG.warn(
        "No back pressure for the log appender (partition = {}) configured! This might cause problems.",
        partition);
    return new NoopAppendLimiter();
  }

  private void appendBlock(final BlockPeek blockPeek) {
    final ByteBuffer rawBuffer = blockPeek.getRawBuffer();
    final int bytes = rawBuffer.remaining();
    final ByteBuffer copiedBuffer = ByteBuffer.allocate(bytes).put(rawBuffer).flip();

    final var listener = new Listener(blockPeek.getHandlers(), blockPeek.getBlockLength());
    appendToStorage(copiedBuffer, listener);
    blockPeek.markCompleted();
  }

  private void appendToStorage(final ByteBuffer buffer, final Listener listener) {
    logStorage.append(buffer, listener);
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  protected void onActorStarting() {
    actor.consume(writeBufferSubscription, this::onWriteBufferAvailable);
  }

  @Override
  protected void onActorClosed() {
    closeFuture.complete(null);
  }

  @Override
  public ActorFuture<Void> closeAsync() {
    if (actor.isClosed()) {
      return closeFuture;
    }
    super.closeAsync();
    return closeFuture;
  }

  @Override
  protected void handleFailure(final Exception failure) {
    onFailure(failure);
  }

  @Override
  public void onActorFailed() {
    closeFuture.complete(null);
  }

  private void onWriteBufferAvailable() {
    final BlockPeek blockPeek = new BlockPeek();
    if (writeBufferSubscription.peekBlock(blockPeek, maxAppendBlockSize, true) > 0) {
      appendBlock(blockPeek);
    } else {
      actor.yield();
    }
  }

  @Override
  public HealthStatus getHealthStatus() {
    return actor.isClosed() ? HealthStatus.UNHEALTHY : HealthStatus.HEALTHY;
  }

  @Override
  public void addFailureListener(final FailureListener failureListener) {
    actor.run(() -> this.failureListener = failureListener);
  }

  private void onFailure(final Throwable error) {
    LOG.error("Actor {} failed in phase {}.", name, actor.getLifecyclePhase(), error);
    actor.fail();
    if (failureListener != null) {
      failureListener.onFailure();
    }
  }

  private final class Listener implements AppendListener {

    private final Queue<TriConsumer<ZeebeEntry, Long, Integer>> handlers;
    private final int blockLength;

    private Listener(
        final Queue<TriConsumer<ZeebeEntry, Long, Integer>> handlers, final int blockLength) {
      this.handlers = handlers;
      this.blockLength = blockLength;
    }

    @Override
    public void onWrite(final long address) {}

    @Override
    public void onWriteError(final Throwable error) {
      LOG.error("Failed to append block.", error);
      if (error instanceof NoSuchElementException) {
        // Not a failure. It is probably during transition to follower.
        return;
      }
      actor.run(() -> onFailure(error));
    }

    @Override
    public void onCommit(final long address) {
      releaseBackPressure();
    }

    @Override
    public void onCommitError(final long address, final Throwable error) {
      LOG.error("Failed to commit block.", error);
      releaseBackPressure();
      actor.run(() -> onFailure(error));
    }

    @Override
    public void updateRecords(final ZeebeEntry entry, final long index) {
      final UnsafeBuffer block = new UnsafeBuffer(entry.data());
      int fragOffset = 0;
      int recordIndex = 0;
      boolean inBatch = false;

      while (fragOffset < blockLength) {
        final long position = (index << 8) + recordIndex;
        final byte flags = block.getByte(flagsOffset(fragOffset));

        if (inBatch) {
          if (DataFrameDescriptor.flagBatchEnd(flags)) {
            inBatch = false;
          }
        } else {
          inBatch = DataFrameDescriptor.flagBatchBegin(flags);
        }

        if (inBatch) {
          updateRecords(handlers.peek(), entry, position, fragOffset);
        } else {
          updateRecords(handlers.poll(), entry, position, fragOffset);
        }
        recordIndex++;
        fragOffset += DataFrameDescriptor.alignedLength(block.getInt(lengthOffset(fragOffset)));

        if (recordIndex > 0xFF) {
          throw new IllegalStateException(
              String.format(
                  "The number of records in the entry with index %d exceeds the supported amount of %d",
                  index, 0xFF));
        }
      }

      entry.setLowestPosition(index << 8);
      entry.setHighestPosition((index << 8) + (recordIndex - 1));
    }

    private void updateRecords(
        final TriConsumer<ZeebeEntry, Long, Integer> handler,
        final ZeebeEntry entry,
        final long position,
        final int fragOffset) {
      if (handler == null) {
        throw new IllegalStateException("Expected to have handler for entry but none was found.");
      }

      handler.accept(entry, position, fragOffset);
    }

    private void releaseBackPressure() {
      //      actor.run(() -> appendEntryLimiter.onCommit(positions.highest));
    }
  }
}
