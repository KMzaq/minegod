package com.sande.mythictrpg.raid;

import com.sande.mythictrpg.quest.reward.RewardExecutionService;
import com.sande.mythictrpg.quest.reward.RewardGrantPurpose;
import net.minecraft.server.MinecraftServer;

import java.util.Optional;

/** Live cross-registry validation, after reload snapshots are applied; never selects a player's reward. */
final class RaidRewardValidation {
    private RaidRewardValidation() { }

    static Optional<String> error(MinecraftServer server, RaidDefinition raid) {
        if (raid.rewards().isEmpty()) return Optional.empty();
        var validation = RewardExecutionService.validateForGod(raid.rewardGodId(), raid.rewards(), RewardGrantPurpose.QUEST);
        if (!validation.allowed()) return Optional.of(validation.reason());
        var components = RewardExecutionService.validateItemComponents(server.registryAccess(), raid.rewards());
        if (!components.allowed()) return Optional.of(components.reason());
        return Optional.empty();
    }
}
