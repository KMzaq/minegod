package com.sande.mythictrpg.story.runtime;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Persists each successful external effect before attempting another, including partial retries. */
public final class StoryExternalEffectReceipts {
    private StoryExternalEffectReceipts() { }
    public static <K> Result<K> apply(List<K> effectIds, Set<K> previouslyApplied,
            Predicate<K> execute, Consumer<Set<K>> persist) {
        Objects.requireNonNull(execute); Objects.requireNonNull(persist);
        var applied = new LinkedHashSet<>(previouslyApplied);
        for (K effectId : List.copyOf(effectIds)) {
            if (applied.contains(effectId)) continue;
            if (!execute.test(effectId)) return new Result<>(false, applied, Optional.of(effectId));
            applied.add(effectId);
            persist.accept(Set.copyOf(applied));
        }
        return new Result<>(true, applied, Optional.empty());
    }
    public record Result<K>(boolean complete, Set<K> applied, Optional<K> failedEffectId) {
        public Result { applied = Set.copyOf(applied); Objects.requireNonNull(failedEffectId); }
    }
}
