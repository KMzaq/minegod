package com.sande.mythictrpg.data.god;

import com.sande.mythictrpg.data.player.PlayerGodKnowledgeService;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/** Resolves player-specific names without mutating the canonical God definition. */
public final class GodIdentityService {
    public static final GodIdentityService INSTANCE = new GodIdentityService();

    private GodIdentityService() {
    }

    public Component getDisplayName(ServerPlayer player, ResourceLocation godId) {
        return getDisplayName(player.server, player.getUUID(), godId);
    }

    public Component getDisplayName(MinecraftServer server, UUID playerId, ResourceLocation godId) {
        boolean identified = PlayerGodKnowledgeService.get(server).snapshot(playerId, godId).identified();
        if (!identified) {
            return Component.translatable("display.mythictrpg.unknown_god");
        }
        return GodDefinitionManager.INSTANCE.find(godId)
                .map(GodDefinition::displayName)
                .orElseGet(() -> Component.translatable("display.mythictrpg.unavailable_god"));
    }
}
