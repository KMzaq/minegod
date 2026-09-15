package com.sande.mythictrpg.relation;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Objects;

/** An authored, bounded and server-validated relation transition. */
public record GodRelationTransition(ResourceLocation id, ResourceLocation actingGodId,
        boolean aiEnabled, int maxApplications, String summary, List<GodRelationTransitionChange> changes) {
    public GodRelationTransition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(actingGodId, "actingGodId");
        if (maxApplications < 1 || maxApplications > 1_000) {
            throw new IllegalArgumentException("maxApplications must be within 1..1000");
        }
        summary = Objects.requireNonNull(summary, "summary").trim();
        if (summary.isEmpty() || summary.length() > 240) {
            throw new IllegalArgumentException("God relation transition summary must be 1..240 characters");
        }
        changes = List.copyOf(Objects.requireNonNull(changes, "changes"));
        if (changes.isEmpty() || changes.size() > 16) {
            throw new IllegalArgumentException("God relation transition requires 1..16 changes");
        }
        if (changes.stream().anyMatch(change -> !change.sourceGodId().equals(actingGodId)
                && !change.targetGodId().equals(actingGodId))) {
            throw new IllegalArgumentException("The acting God must participate in every relation change");
        }
    }
}
