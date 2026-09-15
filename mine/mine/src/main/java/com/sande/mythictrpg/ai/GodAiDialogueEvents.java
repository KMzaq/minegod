package com.sande.mythictrpg.ai;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/** Routes only an active test dialogue session; all other chat remains untouched. */
public final class GodAiDialogueEvents {
    private GodAiDialogueEvents() {
    }

    public static void onServerChat(ServerChatEvent event) {
        ServerPlayer player = event.getPlayer();
        if (!GodAiDialogueService.INSTANCE.isActive(player)) {
            return;
        }
        String raw = event.getRawText();
        if (raw.startsWith("!")) {
            event.setMessage(Component.literal(raw.substring(1)));
            return;
        }
        event.setCanceled(true);
        GodAiDialogueService.INSTANCE.handlePlayerText(player, raw);
    }

    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            GodAiDialogueService.INSTANCE.onPlayerLoggedOut(player);
        }
    }
}
