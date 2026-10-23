package com.sande.mythictrpg.quest.reward.blessing;

import net.minecraft.resources.ResourceLocation;
import java.util.Map;
import java.util.Objects;

/** Explicit authored rank caps, with no implicit relationship to table tiers or equipment grades. */
public record BlessingRankPolicy(GodRank rank, Map<ResourceLocation, Integer> effectCaps) {
    public BlessingRankPolicy {
        Objects.requireNonNull(rank);
        effectCaps = Map.copyOf(effectCaps);
        if (effectCaps.isEmpty() || effectCaps.size() > 64) throw new IllegalArgumentException("Rank policy needs 1..64 caps");
        effectCaps.forEach(BlessingLevels::requireLevel);
    }
}
