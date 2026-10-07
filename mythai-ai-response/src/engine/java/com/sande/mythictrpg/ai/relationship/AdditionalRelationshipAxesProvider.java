package com.sande.mythictrpg.ai.relationship;

import java.util.Map;

/**
 * Optional game-owned source for relationship dimensions that PlayerMythProfile does not yet own. The AI provides
 * no default storage for these axes; an RPG owner may supply this read-only provider after agreeing the authority.
 */
@FunctionalInterface
public interface AdditionalRelationshipAxesProvider {
    Map<String, Integer> axes(String playerId, String npcId);

    static AdditionalRelationshipAxesProvider none() {
        return (playerId, npcId) -> Map.of();
    }
}
