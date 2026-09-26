package com.sande.mythaiaicontent.content;

import net.minecraft.resources.ResourceLocation;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** The game supplies actual recipients even when memory recording is OFF. No membership is inferred by this mod. */
public record ContentAudience(ResourceLocation speakerGodId, boolean publicRoom, List<ResourceLocation> godIds,
                              Set<UUID> playerIds) {
    public ContentAudience {
        Objects.requireNonNull(speakerGodId, "speakerGodId");
        godIds = List.copyOf(godIds);
        playerIds = Set.copyOf(playerIds);
        // A recalled utterance may have an original author who is not in the current room. The live Request
        // validates its selected speaker membership; disclosure here checks the actual current listener set.
        if (godIds.isEmpty() || godIds.size() > 16
                || Set.copyOf(godIds).size() != godIds.size() || playerIds.isEmpty()
                || !publicRoom && playerIds.size() > 64)
            throw new IllegalArgumentException("Invalid game-supplied content audience");
    }
}
