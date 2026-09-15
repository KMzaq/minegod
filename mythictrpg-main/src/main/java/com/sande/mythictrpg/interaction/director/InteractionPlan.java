package com.sande.mythictrpg.interaction.director;

import com.sande.mythictrpg.interaction.api.InteractionMode;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** An approved plan, not evidence that an interaction has started. */
public record InteractionPlan(
        UUID initiatingPlayerId,
        InteractionAudience audience,
        InteractionMode mode,
        ResourceLocation signalType,
        InteractionParticipants participants,
        PlanRevisionStamp revisions,
        List<ResourceLocation> selectionReasons
) {
    public InteractionPlan {
        Objects.requireNonNull(initiatingPlayerId, "initiatingPlayerId");
        Objects.requireNonNull(audience, "audience");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(signalType, "signalType");
        Objects.requireNonNull(participants, "participants");
        Objects.requireNonNull(revisions, "revisions");
        selectionReasons = selectionReasons.stream().distinct().sorted().limit(16).toList();
        if (!audience.initiatingPlayerId().equals(initiatingPlayerId)) {
            throw new IllegalArgumentException("Plan initiator and audience initiator differ");
        }
    }
}
