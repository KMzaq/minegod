package com.sande.mythictrpg.quest.reward;

import com.sande.mythictrpg.quest.reward.luck.LuckyRewardSelection;
import net.minecraft.resources.ResourceLocation;
import java.util.Objects;

/** Receipt-owned concrete result. Item delivery uses the normal reward/gear recording boundary. */
public record ResolvedLuckyRewardEntry(ResourceLocation poolId, LuckyRewardSelection selection) implements RewardEntry {
    public ResolvedLuckyRewardEntry { Objects.requireNonNull(poolId); Objects.requireNonNull(selection); }
    @Override public String description() { return selection.reward().description() + " / 적용 행운 " + selection.level(); }
}
