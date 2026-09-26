package com.sande.mythictrpg.ai.action;

import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable, non-authoritative action data. Constructing this value never changes
 * Minecraft state; only a registered game-side executor may do so.
 */
public record AiActionProposal(UUID proposalId, UUID sessionId, ResourceLocation actionType,
        ResourceLocation actingGodId, UUID targetPlayerId, String title, String summary,
        Map<String, String> parameters) {
    private static final int MAX_PARAMETERS = 32;

    public AiActionProposal {
        Objects.requireNonNull(proposalId, "proposalId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(actionType, "actionType");
        Objects.requireNonNull(actingGodId, "actingGodId");
        Objects.requireNonNull(targetPlayerId, "targetPlayerId");
        title = bounded(title, "title", 120);
        summary = bounded(summary, "summary", 600);
        if (parameters == null) {
            parameters = Map.of();
        } else {
            if (parameters.size() > MAX_PARAMETERS) {
                throw new IllegalArgumentException("AI action has too many parameters");
            }
            Map<String, String> copy = new LinkedHashMap<>();
            parameters.forEach((key, value) -> copy.put(
                    boundedRequired(key, "parameter key", 64),
                    boundedRequired(value, "parameter value", 512)));
            parameters = Map.copyOf(copy);
        }
    }

    private static String bounded(String value, String name, int maximumCodePoints) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.codePointCount(0, normalized.length()) > maximumCodePoints) {
            throw new IllegalArgumentException(name + " is too long");
        }
        return normalized;
    }

    private static String boundedRequired(String value, String name, int maximumCodePoints) {
        String normalized = bounded(value, name, maximumCodePoints);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}
