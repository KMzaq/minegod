package com.sande.mythictrpg.ai.experiencecontract;

import java.util.*;
import java.util.function.Predicate;
import com.sande.mythictrpg.ai.api.RoomEvidenceReference;

/** Opaque game-issued lease. AI can omit evidence, never add recipients, mint observations or grant itself a lease. */
public final class ExperienceLease {
    private final ExperienceView view;
    private final Predicate<Set<UUID>> valid;
    private final Map<UUID, RoomEvidenceReference> portableEvidence;
    ExperienceLease(ExperienceView view, Predicate<Set<UUID>> valid) { this(view, valid, Map.of()); }
    ExperienceLease(ExperienceView view, Predicate<Set<UUID>> valid, Map<UUID, RoomEvidenceReference> portableEvidence) {
        this.view = view; this.valid = valid; this.portableEvidence = Map.copyOf(portableEvidence);
    }
    public ExperienceView view() { return view; }
    Map<UUID, RoomEvidenceReference> portableEvidence() { return portableEvidence; }
    public boolean current(Set<UUID> included) {
        Set<UUID> ids = Set.copyOf(included);
        if (!view.events().stream().map(ExperienceView.Event::observationId).toList().containsAll(ids)) return false;
        return valid.test(ids);
    }
    public static ExperienceLease unavailable(String reason) {
        return new ExperienceLease(ExperienceView.unavailable(reason), ids -> ids.isEmpty());
    }
}
