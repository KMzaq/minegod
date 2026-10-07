package com.sande.mythai.response.memory;

import java.util.concurrent.*;
import java.util.function.*;

/** Re-prepare expired evidence only for the SAME still-current game request. No lease extension or scope transfer. */
public final class RoomEvidenceRefresh {
    private RoomEvidenceRefresh() { }
    public static CompletableFuture<Boolean> ensure(BooleanSupplier scopeCurrent, BooleanSupplier evidenceCurrent,
            Supplier<CompletableFuture<Boolean>> prepare, Consumer<Runnable> dispatch) {
        return ensure(scopeCurrent, evidenceCurrent, prepare, dispatch,
                timeout -> CompletableFuture.delayedExecutor(10, TimeUnit.SECONDS).execute(timeout));
    }
    static CompletableFuture<Boolean> ensure(BooleanSupplier scopeCurrent, BooleanSupplier evidenceCurrent,
            Supplier<CompletableFuture<Boolean>> prepare, Consumer<Runnable> dispatch, Consumer<Runnable> timer) {
        var result = new CompletableFuture<Boolean>();
        try {
            if (!scopeCurrent.getAsBoolean()) return CompletableFuture.completedFuture(false);
            if (evidenceCurrent.getAsBoolean()) return CompletableFuture.completedFuture(true);
            timer.accept(() -> result.complete(false));
            if (result.isDone()) return result;
            prepare.get().whenComplete((ready, failure) -> {
                try { dispatch.accept(() -> {
                    if (result.isDone()) return;
                    try { result.complete(failure == null && Boolean.TRUE.equals(ready)
                            && scopeCurrent.getAsBoolean() && evidenceCurrent.getAsBoolean()); }
                    catch (RuntimeException unavailable) { result.complete(false); }
                }); } catch (RuntimeException stopped) { result.complete(false); }
            });
        } catch (RuntimeException unavailable) { result.complete(false); }
        return result;
    }
}
