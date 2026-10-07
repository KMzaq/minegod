package com.sande.mythictrpg.ai.agent;

import com.sande.mythictrpg.ai.GodPersonaRepository;
import com.sande.mythictrpg.ai.relationship.RelationshipDataService;
import com.sande.mythictrpg.ai.tag.NpcCharacterTagRepository;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Resolves configuration and player-specific state without allowing the agent to change either one. */
public final class NpcAgentResolver {
    public Optional<NpcAgentState> resolve(MinecraftServer server, UUID playerId, ResourceLocation npcId) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(npcId, "npcId");
        return NpcAgentRepository.INSTANCE.find(npcId).map(agent -> new NpcAgentState(agent,
                GodPersonaRepository.INSTANCE.find(agent.personaId()),
                NpcCharacterTagRepository.INSTANCE.find(agent.identity().id()),
                RelationshipDataService.get(server).emotion(playerId, agent.identity().id())));
    }
}
