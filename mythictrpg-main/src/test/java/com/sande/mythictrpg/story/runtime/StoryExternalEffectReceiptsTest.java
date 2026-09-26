package com.sande.mythictrpg.story.runtime;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Actual receipt coordinator: no server, RNG, fabricated story outcome or model invocation. */
public final class StoryExternalEffectReceiptsTest {
    private static int checks;
    public static void main(String[] args) {
        var saved = new AtomicReference<Set<String>>(Set.of("canonical"));
        var applications = new HashMap<String, Integer>();
        var available = new HashSet<>(Set.of("a"));
        var ids = List.of("a", "b", "c");
        java.util.function.Predicate<String> execute = id -> {
            if (!available.contains(id)) return false;
            applications.merge(id, 1, Integer::sum); return true;
        };
        var first = StoryExternalEffectReceipts.apply(ids, saved.get(), execute, saved::set);
        check(!first.complete() && first.failedEffectId().orElseThrow().equals("b"), "first unavailable effect stops processing");
        check(saved.get().equals(Set.of("canonical", "a")), "successful effect receipt survives partial failure");
        available.add("b");
        var second = StoryExternalEffectReceipts.apply(ids, saved.get(), execute, saved::set);
        check(!second.complete() && saved.get().equals(Set.of("canonical", "a", "b")), "retry persists new success before later failure");
        check(applications.get("a") == 1 && applications.get("b") == 1, "retry never reapplies an earlier one-use transition");
        available.add("c");
        var finalAttempt = StoryExternalEffectReceipts.apply(ids, saved.get(), execute, saved::set);
        check(finalAttempt.complete() && finalAttempt.failedEffectId().isEmpty(), "remaining effect can complete");
        check(applications.values().stream().allMatch(count -> count == 1), "all transitions applied exactly once across ordinary retries");
        check(StoryExternalEffectReceipts.apply(ids, saved.get(), execute, saved::set).complete()
                && applications.values().stream().allMatch(count -> count == 1), "completed receipt replay causes no effect");
        saved.set(Set.of());
        try {
            StoryExternalEffectReceipts.apply(ids, saved.get(), id -> { if (id.equals("b")) throw new IllegalStateException("unavailable"); return true; }, saved::set);
            throw new AssertionError("external exception hidden");
        } catch (IllegalStateException expected) { checks++; }
        check(saved.get().equals(Set.of("a")), "earlier receipt survives a later thrown exception");
        check(StoryExternalEffectReceipts.apply(List.of(), Set.of("canonical"), id -> false, saved::set).complete(), "no external effects resolve immediately");
        System.out.println("StoryExternalEffectReceiptsTest: PASS (" + checks + " checks; no server or LLM)");
    }
    private static void check(boolean condition, String description) { checks++; if (!condition) throw new AssertionError(description); }
}
