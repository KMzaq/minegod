package com.sande.mythictrpg.ai.server;

import com.sande.mythictrpg.ai.memorycontract.ConversationMemoryContext;
import net.minecraft.resources.ResourceLocation;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable authority for one explicitly requested dialogue test, independent of global memory mode. */
record TestConversationScope(UUID playerId, UUID interactionId, UUID generation, List<ResourceLocation> godIds,
        boolean recording) {
    static final int MAX_GODS = 16;

    TestConversationScope {
        Objects.requireNonNull(playerId);
        Objects.requireNonNull(interactionId);
        Objects.requireNonNull(generation);
        godIds = List.copyOf(godIds);
        if (godIds.isEmpty() || godIds.size() > MAX_GODS || new HashSet<>(godIds).size() != godIds.size()) {
            throw new IllegalArgumentException("A dialogue test requires 1..16 distinct Gods");
        }
    }

    boolean matches(UUID interaction, UUID expectedGeneration) {
        return interactionId.equals(interaction) && generation.equals(expectedGeneration);
    }

    boolean recordingAllowed(ConversationMemoryContext context) {
        return recording && context != null && matches(context.interactionId(), context.generation())
                && playerId.equals(context.playerId()) && context.audience().equals(java.util.Set.of(playerId))
                && godIds.stream().anyMatch(god -> god.toString().equals(context.godId()));
    }
}
