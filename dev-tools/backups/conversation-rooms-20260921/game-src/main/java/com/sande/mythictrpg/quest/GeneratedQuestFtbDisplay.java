package com.sande.mythictrpg.quest;

import com.sande.mythictrpg.quest.dynamic.GeneratedQuestInstance;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/** Public FTB-free facade used by the generated quest domain. */
public final class GeneratedQuestFtbDisplay {
    private GeneratedQuestFtbDisplay() {
    }

    public static Optional<Mirror> create(ServerPlayer player, GeneratedQuestInstance instance) {
        return FtbQuestAdapter.INSTANCE.createGenerated(player, instance);
    }

    public static boolean sync(ServerPlayer player, GeneratedQuestInstance instance) {
        return FtbQuestAdapter.INSTANCE.syncGenerated(player, instance);
    }

    public static boolean restore(ServerPlayer player, GeneratedQuestInstance instance) {
        return FtbQuestAdapter.INSTANCE.restoreGenerated(player, instance);
    }

    public static void hide(ServerPlayer player, GeneratedQuestInstance instance) {
        FtbQuestAdapter.INSTANCE.hideGenerated(player, instance);
    }

    public record Mirror(long questId, long markerQuestId, long taskId) {
    }
}
