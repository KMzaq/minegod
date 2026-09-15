package com.sande.mythictrpg.ai.relationship;

import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Compatibility adapter for a read-only relationship view. It can later be replaced by the RPG-owned provider
 * without changing AI-facing contracts; it is not an AI affinity persistence mechanism.
 */
public final class LegacyRelationshipSnapshotProvider implements RelationshipSnapshotProvider {
    private final RelationshipProvider source;

    public LegacyRelationshipSnapshotProvider(RelationshipProvider source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    @Override
    public Optional<RelationshipSnapshot> find(String playerId, String npcId) {
        try {
            UUID parsedPlayerId = UUID.fromString(playerId);
            ResourceLocation parsedNpcId = ResourceLocation.parse(npcId);
            return Optional.of(snapshot(parsedPlayerId, parsedNpcId));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    public RelationshipSnapshot snapshot(UUID playerId, ResourceLocation npcId) {
        RelationshipMetrics metrics = source.relationship(playerId, npcId);
        Map<String, Integer> axes = new LinkedHashMap<>();
        axes.put(RelationshipAxes.AFFINITY, metrics.affinity());
        axes.put(RelationshipAxes.TRUST, metrics.trust());
        axes.put(RelationshipAxes.RESPECT, metrics.respect());
        axes.put(RelationshipAxes.CAUTION, metrics.caution());
        return new RelationshipSnapshot(npcId.toString(), playerId.toString(), axes);
    }
}
