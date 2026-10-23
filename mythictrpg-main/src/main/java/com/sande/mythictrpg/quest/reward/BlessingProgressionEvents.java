package com.sande.mythictrpg.quest.reward;

import com.sande.mythictrpg.condition.builtin.BuiltinConditionTypes;
import com.sande.mythictrpg.condition.engine.ConditionChangeDispatcher;
import net.minecraft.server.level.ServerPlayer;
import java.util.List;

final class BlessingProgressionEvents {
    private BlessingProgressionEvents() { }
    static void acquired(ServerPlayer player, List<RewardEntry> rewards) {
        if (rewards.stream().anyMatch(reward -> reward instanceof ResolvedBlessingRewardEntry
                || reward instanceof BlessingRewardEntry blessing && blessing.permanent()))
            ConditionChangeDispatcher.publishPlayerDependencyChanged(player.server,
                    BuiltinConditionTypes.PLAYER_BLESSING_HISTORY_DEPENDENCY, player.getUUID());
    }
}
