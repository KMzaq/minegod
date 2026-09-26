package com.sande.mythictrpg.ai.experiencecontract;

import java.util.*;
import java.util.function.Predicate;

/** Opaque game-issued lease. AI can omit evidence, never add recipients, mint observations or grant itself a lease. */
public final class ExperienceLease {
    private final ExperienceView view;
    private final Predicate<Set<UUID>> valid;
    ExperienceLease(ExperienceView view, Predicate<Set<UUID>> valid) { this.view = view; this.valid = valid; }
    public ExperienceView view() { return view; }
    public boolean current(Set<UUID> included) {
        Set<UUID> ids = Set.copyOf(included);
        if (!view.events().stream().map(ExperienceView.Event::observationId).toList().containsAll(ids)) return false;
        return valid.test(ids);
    }
    public static ExperienceLease unavailable(String reason) {
        return new ExperienceLease(ExperienceView.unavailable(reason), ids -> ids.isEmpty());
    }
}
