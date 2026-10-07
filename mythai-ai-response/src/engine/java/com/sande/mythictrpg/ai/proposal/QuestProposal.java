package com.sande.mythictrpg.ai.proposal;

import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A request for the game-owned quest system to consider a narrative quest. It contains no quest ID, registry access,
 * progress tracking, or completion authority.
 */
public record QuestProposal(List<ResourceLocation> giverNpcIds, List<UUID> participantPlayerIds, boolean shared,
        QuestConcept concept, String narrativeReason, String collaborationReason) implements AiGameProposal {
    public static final String TYPE = "quest_proposal";

    public QuestProposal {
        giverNpcIds = distinctNonNull(giverNpcIds, "giverNpcIds");
        participantPlayerIds = distinctNonNull(participantPlayerIds, "participantPlayerIds");
        if (shared != (participantPlayerIds.size() > 1)) {
            throw new IllegalArgumentException("shared must match whether more than one player participates");
        }
        concept = Objects.requireNonNull(concept, "concept");
        narrativeReason = requireText(narrativeReason, "narrativeReason");
        collaborationReason = collaborationReason == null ? "" : collaborationReason.trim();
        if (giverNpcIds.size() > 1 && collaborationReason.isEmpty()) {
            throw new IllegalArgumentException("Multiple quest givers require collaborationReason");
        }
    }

    @Override
    public String type() {
        return TYPE;
    }

    private static <T> List<T> distinctNonNull(List<T> values, String name) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        LinkedHashSet<T> checked = new LinkedHashSet<>();
        for (T value : values) {
            checked.add(Objects.requireNonNull(value, name + " value"));
        }
        return List.copyOf(checked);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
