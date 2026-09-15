package com.sande.mythictrpg.ai.integration;

import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

public final class AiDialogueEvents {
    private AiDialogueEvents() {
    }

    public static void onServerChat(ServerChatEvent event) {
        if (AiDialogueEngineBridge.INSTANCE.handlePlayerText(event.getPlayer(), event.getRawText())) {
            event.setCanceled(true);
        }
    }

    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            AiDialogueEngineBridge.INSTANCE.onPlayerLoggedOut(player);
        }
    }
}
