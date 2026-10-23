package com.sande.mythictrpg.recording.server;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Completion ordering only, never read authority. Tracks whole captures, including later receipt batches. */
final class CaptureReadFence implements AutoCloseable {
    private final int limit;
    private final Set<CompletableFuture<Boolean>> pending = Collections.newSetFromMap(new IdentityHashMap<>());
    private int overflow;
    private boolean closed;

    CaptureReadFence(int limit) {
        if (limit < 1) throw new IllegalArgumentException("positive capture limit required");
        this.limit = limit;
    }

    synchronized void track(CompletionStage<Boolean> capture) {
        if (closed) return;
        if (pending.size() >= limit) {
            overflow++;
            capture.whenComplete((value, failure) -> { synchronized (this) { overflow--; } });
            return;
        }
        var settled = new CompletableFuture<Boolean>();
        pending.add(settled);
        capture.whenComplete((value, failure) -> {
            synchronized (this) {
                // Publish completion before removal so snapshots cannot overtake it.
                settled.complete(!closed && failure == null && Boolean.TRUE.equals(value));
                pending.remove(settled);
            }
        });
    }

    /** Snapshot of already admitted world captures; later turns cannot extend this wait indefinitely.
     * Cross-room ordering does not share content: the eventual read issuer still checks its own audience. */
    synchronized CompletableFuture<Boolean> snapshot() {
        if (closed || overflow != 0) return CompletableFuture.completedFuture(false);
        var captured = List.copyOf(pending);
        return CompletableFuture.allOf(captured.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> captured.stream().allMatch(future -> Boolean.TRUE.equals(future.getNow(false))));
    }

    @Override public synchronized void close() {
        closed = true;
        List.copyOf(pending).forEach(future -> future.complete(false));
        pending.clear();
    }

    /** Always issue on the game thread, after revalidation. Timeout/cancel never cancels a durable write. */
    static <T> CompletableFuture<Optional<T>> after(CompletionStage<Boolean> captured, Consumer<Runnable> dispatch,
            BooleanSupplier current, Supplier<Optional<T>> issue, long timeoutMillis) {
        var result = new CompletableFuture<Optional<T>>();
        result.completeOnTimeout(Optional.empty(), timeoutMillis, TimeUnit.MILLISECONDS);
        captured.whenComplete((ready, failure) -> {
            if (result.isDone()) return;
            try {
                dispatch.accept(() -> {
                    if (result.isDone()) return;
                    try {
                        result.complete(failure == null && Boolean.TRUE.equals(ready) && current.getAsBoolean()
                                ? issue.get() : Optional.empty());
                    } catch (RuntimeException unavailable) { result.complete(Optional.empty()); }
                });
            } catch (RuntimeException stopped) { result.complete(Optional.empty()); }
        });
        return result;
    }
}
