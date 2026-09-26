package com.sande.mythictrpg.quest.reward;

import net.minecraft.resources.ResourceLocation;
import java.util.Objects;

/** Authored quest entitlement, not a title, affinity threshold, or observation proof. */
public record WatchRewardEntry(ResourceLocation godId, String displayName) implements RewardEntry {
    public WatchRewardEntry {
        Objects.requireNonNull(godId, "godId");
        displayName = displayName == null ? "" : displayName.trim();
        if (displayName.isEmpty() || displayName.codePointCount(0, displayName.length()) > 80)
            throw new IllegalArgumentException("Watch displayName must contain 1..80 code points");
    }
    @Override public String description() { return displayName + "의 주시"; }
}
