package com.sande.mythictrpg.quest.reward;

import net.minecraft.resources.ResourceLocation;
import java.util.Objects;

/** Explicit opt-in random item pool; fixed quest rewards never acquire luck scaling implicitly. */
public record LuckyRewardEntry(ResourceLocation poolId) implements RewardEntry {
    public LuckyRewardEntry { Objects.requireNonNull(poolId); }
    @Override public String description() { return "행운 보상 " + poolId + " (수령 시 확정)"; }
}
