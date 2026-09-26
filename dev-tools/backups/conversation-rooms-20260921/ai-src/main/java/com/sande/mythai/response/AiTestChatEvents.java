package com.sande.mythai.response;

import com.sande.mythictrpg.ai.AiTestDialogueAdapter;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/** Routes only explicitly started test sessions into the isolated dialogue adapter. */
final class AiTestChatEvents {
    private AiTestChatEvents() {
    }

    static void onServerChat(ServerChatEvent event) {
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

    static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            AiTestDialogueAdapter.INSTANCE.onPlayerLoggedOut(player);
        }
    }
}
