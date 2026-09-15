package com.sande.mythictrpg.ai.agent;

import com.sande.mythictrpg.ai.AiDialogueModels;
import com.sande.mythictrpg.ai.relationship.CurrentEmotion;
import com.sande.mythictrpg.ai.tag.NpcTagProfile;

import java.util.Objects;
import java.util.Optional;

/**
 * Runtime read-only view of an agent. Global emotion belongs to {@link NpcAgent}; current emotion belongs to one
 * player/NPC relationship and is intentionally not merged into it.
 */
public record NpcAgentState(NpcAgent agent, Optional<AiDialogueModels.GodPersona> persona,
        Optional<NpcTagProfile> tags, CurrentEmotion playerCurrentEmotion) {
    public NpcAgentState {
        Objects.requireNonNull(agent, "agent");
        persona = persona == null ? Optional.empty() : persona;
        tags = tags == null ? Optional.empty() : tags;
        playerCurrentEmotion = playerCurrentEmotion == null ? CurrentEmotion.calm() : playerCurrentEmotion;
    }
}
