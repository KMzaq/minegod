package com.sande.mythictrpg.relation;

import java.util.List;
import java.util.Objects;

public record GodRelationTransitionResult(Status status, String reason, List<GodRelationAppliedChange> changes) {
    public GodRelationTransitionResult {
        Objects.requireNonNull(status, "status");
        reason = Objects.requireNonNull(reason, "reason").trim();
        changes = List.copyOf(Objects.requireNonNull(changes, "changes"));
    }

    public boolean succeeded() {
        return status == Status.PREVIEW || status == Status.APPLIED;
    }

    public enum Status {
        PREVIEW,
        APPLIED,
        REJECTED
    }
}

