package com.sande.mythictrpg.interaction.start;

import com.sande.mythictrpg.data.player.EncounterBatchResult;
import com.sande.mythictrpg.data.player.PlayerGodKnowledgeService;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

import java.util.Set;
import java.util.UUID;

@FunctionalInterface
public interface EncounterCommitter {
    EncounterCommitter PERSISTENT = (server, playerId, godIds) ->
            PlayerGodKnowledgeService.get(server).recordEncounters(playerId, godIds);

    EncounterBatchResult commit(MinecraftServer server, UUID playerId, Set<ResourceLocation> godIds);
}
