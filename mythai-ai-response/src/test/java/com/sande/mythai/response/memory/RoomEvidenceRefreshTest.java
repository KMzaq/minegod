package com.sande.mythai.response.memory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class RoomEvidenceRefreshTest {
    private static int checks;
    public static void main(String[] args) {
        var calls = new AtomicInteger();
        check(!RoomEvidenceRefresh.ensure(() -> false, () -> true, () -> { calls.incrementAndGet(); return CompletableFuture.completedFuture(true); }, Runnable::run).join(),
                "expired request cannot reprepare even valid evidence");
        check(RoomEvidenceRefresh.ensure(() -> true, () -> true, () -> { calls.incrementAndGet(); return CompletableFuture.completedFuture(true); }, Runnable::run).join(),
                "unexpired evidence needs no redundant storage work");
        check(calls.get() == 0, "neither fast path touches storage");
        for (String outcome : List.of("valid", "scope", "content", "denied", "failed", "cancel", "timeout", "dispatch")) scenario(outcome);
        check(!RoomEvidenceRefresh.ensure(() -> true, () -> false, () -> { throw new IllegalStateException(); }, Runnable::run, t -> {}).join(),
                "preparation exceptions fail closed");
        check(!RoomEvidenceRefresh.ensure(() -> { throw new IllegalStateException(); }, () -> true,
                () -> CompletableFuture.completedFuture(true), Runnable::run, t -> {}).join(), "authority exceptions fail closed");
        System.out.println("RoomEvidenceRefreshTest: " + checks + " checks passed; no lease extension/model/server");
    }
    private static void scenario(String outcome) {
        var scope = new AtomicBoolean(true); var evidence = new AtomicBoolean(false);
        var source = new CompletableFuture<Boolean>(); var game = new ArrayDeque<Runnable>(); var timer = new ArrayList<Runnable>();
        var result = RoomEvidenceRefresh.ensure(scope::get, evidence::get, () -> source,
                action -> { if (outcome.equals("dispatch")) throw new IllegalStateException(); game.add(action); }, timer::add);
        check(!result.isDone(), "expired proof awaits real asynchronous validation: " + outcome);
        check(timer.size() == 1, "one bounded preparation timer: " + outcome);
        if (outcome.equals("cancel")) result.cancel(false);
        if (outcome.equals("timeout")) timer.getFirst().run();
        evidence.set(!outcome.equals("content"));
        if (outcome.equals("failed")) source.completeExceptionally(new IllegalStateException());
        else source.complete(!outcome.equals("denied"));
        if (outcome.equals("scope")) scope.set(false); // Changed after worker completion, before game callback.
        if (!outcome.equals("dispatch") && !outcome.equals("cancel") && !outcome.equals("timeout"))
            check(!result.isDone(), "worker completion cannot publish before game dispatcher: " + outcome);
        while (!game.isEmpty()) game.removeFirst().run();
        if (outcome.equals("cancel")) check(result.isCancelled(), "late owner approval does not resurrect cancelled caller");
        else check(result.join() == outcome.equals("valid"), "final scope AND real owner AND original future required: " + outcome);
        timer.getFirst().run();
        if (outcome.equals("valid")) check(result.join(), "late timer cannot rewrite completed valid result");
    }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
