package com.sande.mythictrpg.ai;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/** Test-only chat ingress. It intercepts only players who explicitly started an /ai_test session. */
public final class AiTestDialogueEvents {
    private AiTestDialogueEvents() {
    }

    public static void onServerChat(ServerChatEvent event) {
        ServerPlayer player = event.getPlayer();
        if (!AiTestDialogueAdapter.INSTANCE.isActive(player)) {
            return;
        }
        String raw = event.getRawText();
        if (raw.startsWith("!")) {
            event.setMessage(Component.literal(raw.substring(1)));
            return;
        }
        event.setCanceled(true);
        AiTestDialogueAdapter.INSTANCE.handlePlayerText(player, raw);
    }

    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            AiTestDialogueAdapter.INSTANCE.onPlayerLoggedOut(player);
        }
    }
}
