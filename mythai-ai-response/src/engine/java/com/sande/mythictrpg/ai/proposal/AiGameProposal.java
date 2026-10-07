package com.sande.mythictrpg.ai.proposal;

/**
 * A typed, non-authoritative proposal produced by the conversation layer. Implementations intentionally carry only
 * narrative concepts and game-owned identities; they have no method that can mutate a Minecraft or RPG system.
 * Concrete wire types are restricted by {@link StructuredProposalDecoder}, not by Java package placement.
 */
public interface AiGameProposal {
    String type();
}
