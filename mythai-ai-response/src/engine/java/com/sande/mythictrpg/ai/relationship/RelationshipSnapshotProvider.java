package com.sande.mythictrpg.ai.relationship;

import java.util.Optional;

/**
 * Read-only game-to-AI boundary. Implementations may read a mod database, a plugin API, or a remote game service,
 * but intentionally provide no relationship mutation method to the AI.
 */
@FunctionalInterface
public interface RelationshipSnapshotProvider {
    Optional<RelationshipSnapshot> find(String playerId, String npcId);
}
