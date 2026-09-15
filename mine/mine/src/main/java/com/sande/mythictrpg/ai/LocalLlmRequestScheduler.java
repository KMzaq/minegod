package com.sande.mythictrpg.ai;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Bounded worker scheduler for local LLM requests. It deliberately has no Minecraft dependencies: sessions decide
 * ordering, while this scheduler only limits concurrent backend requests and records queue wait time.
 */
final class LocalLlmRequestScheduler implements AutoCloseable {
    private final Object configurationLock = new Object();
    private final ConcurrentHashMap<UUID, CompletableFuture<?>> outstanding = new ConcurrentHashMap<>();
    private ThreadPoolExecutor executor;
    private int activeConcurrency = -1;
    private int activeQueueCapacity = -1;
    private boolean closed;

    <T> ScheduledRequest<T> submit(UUID requestId, AiDialogueConfig.Settings settings, Supplier<T> request) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(requestId, "requestId");
        Instant submittedAt = Instant.now();
        CompletableFuture<ScheduledResult<T>> completion = new CompletableFuture<>();
        if (outstanding.putIfAbsent(requestId, completion) != null) {
            throw new IllegalArgumentException("Duplicate local LLM request ID " + requestId);
        }
        try {
            executorFor(settings).execute(() -> execute(requestId, submittedAt, request, completion));
        } catch (RejectedExecutionException exception) {
            outstanding.remove(requestId);
            completion.completeExceptionally(new RejectedExecutionException(
                    "Local LLM request queue is full (capacity " + settings.llmQueueCapacity() + ")", exception));
        }
        return new ScheduledRequest<>(requestId, submittedAt, completion);
    }

    private <T> void execute(UUID requestId, Instant submittedAt, Supplier<T> request,
            CompletableFuture<ScheduledResult<T>> completion) {
        Instant startedAt = Instant.now();
        try {
            T result = request.get();
            Instant completedAt = Instant.now();
            completion.complete(new ScheduledResult<>(result, Duration.between(submittedAt, startedAt),
                    Duration.between(startedAt, completedAt)));
        } catch (Throwable throwable) {
            completion.completeExceptionally(throwable);
        } finally {
            outstanding.remove(requestId);
        }
    }

    private ThreadPoolExecutor executorFor(AiDialogueConfig.Settings settings) {
        synchronized (configurationLock) {
            if (closed) {
                throw new RejectedExecutionException("Local LLM request scheduler is closed");
            }
            int requestedConcurrency = settings.maxConcurrentLlmRequests();
            int requestedQueueCapacity = settings.llmQueueCapacity();
            if (executor == null) {
                replaceExecutor(requestedConcurrency, requestedQueueCapacity);
            } else if ((activeConcurrency != requestedConcurrency || activeQueueCapacity != requestedQueueCapacity)
                    && executor.getActiveCount() == 0 && executor.getQueue().isEmpty()) {
                executor.shutdown();
                replaceExecutor(requestedConcurrency, requestedQueueCapacity);
            }
            return executor;
        }
    }

    private void replaceExecutor(int concurrency, int queueCapacity) {
        executor = new ThreadPoolExecutor(concurrency, concurrency, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity), new LlmThreadFactory(), new ThreadPoolExecutor.AbortPolicy());
        activeConcurrency = concurrency;
        activeQueueCapacity = queueCapacity;
    }

    @Override
    public void close() {
        synchronized (configurationLock) {
            if (closed) {
                return;
            }
            closed = true;
            if (executor != null) {
                executor.shutdownNow();
            }
        }
        RejectedExecutionException failure = new RejectedExecutionException("Local LLM request scheduler was stopped");
        outstanding.forEach((requestId, completion) -> completion.completeExceptionally(failure));
        outstanding.clear();
    }

    record ScheduledRequest<T>(UUID requestId, Instant submittedAt,
            CompletableFuture<ScheduledResult<T>> completion) {
    }

    record ScheduledResult<T>(T value, Duration queueWait, Duration requestDuration) {
    }

    private static final class LlmThreadFactory implements ThreadFactory {
        private final AtomicInteger nextId = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "mythictrpg-ollama-worker-" + nextId.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
