package com.sande.mythictrpg.ai.tone;

import com.sande.mythictrpg.ai.AiDialogueModels;

import java.util.Map;

/**
 * Game-to-AI extension point for titles, contracts, factions, encounter roles, or story authority.  The AI module
 * does not derive rank from chat text, Minecraft permissions, or its own data store.
 */
@FunctionalInterface
public interface SocialAuthorityContextProvider {
    Map<String, NpcSocialAuthorityContext> capture(AiDialogueModels.SessionSnapshot session,
            String triggeringParticipantId);

    static SocialAuthorityContextProvider none() {
        return (session, triggeringParticipantId) -> Map.of();
    }
}
