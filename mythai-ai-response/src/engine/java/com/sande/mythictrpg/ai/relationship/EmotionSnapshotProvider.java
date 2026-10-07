package com.sande.mythictrpg.ai.relationship;

import java.util.Optional;

/** Read-only game-to-AI boundary for current emotion data. */
@FunctionalInterface
public interface EmotionSnapshotProvider {
    Optional<EmotionSnapshot> find(String playerId, String npcId);
}
