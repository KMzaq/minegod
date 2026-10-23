package com.sande.mythictrpg.quest.reward;

import net.minecraft.resources.ResourceLocation;
import java.util.Objects;

/** Authored opt-in. Resolved once, by the game, before a personal reward is delivered. */
public record AdaptiveBlessingRewardEntry(ResourceLocation definitionId) implements RewardEntry {
    public AdaptiveBlessingRewardEntry { Objects.requireNonNull(definitionId, "definitionId"); }
    @Override public String description() { return "가호 " + definitionId + " (수령 시 보유 능력에 맞춰 결정)"; }
}
