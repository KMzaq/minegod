package com.sande.mythictrpg.ai.relationship;

import net.minecraft.resources.ResourceLocation;

import java.util.Optional;
import java.util.Objects;
import java.util.UUID;

/**
 * Read-only compatibility adapter for existing emotion SavedData. It stays separate from the relationship
 * snapshot adapter so an external game service can replace either source independently.
 */
public final class LegacyEmotionSnapshotProvider implements EmotionSnapshotProvider {
    private final RelationshipProvider source;

    public LegacyEmotionSnapshotProvider(RelationshipProvider source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    @Override
    public Optional<EmotionSnapshot> find(String playerId, String npcId) {
        try {
            UUID parsedPlayerId = UUID.fromString(playerId);
            ResourceLocation parsedNpcId = ResourceLocation.parse(npcId);
            return Optional.of(snapshot(parsedPlayerId, parsedNpcId));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    public EmotionSnapshot snapshot(UUID playerId, ResourceLocation npcId) {
        return new EmotionSnapshot(npcId.toString(), playerId.toString(), source.emotion(playerId, npcId).intensities());
    }
}
