package com.sande.mythictrpg.quest.reward;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

/** Permanent title unlock. Equipping/display presentation can be added independently. */
public record TitleRewardEntry(ResourceLocation titleId, String displayName) implements RewardEntry {
    public TitleRewardEntry {
        Objects.requireNonNull(titleId, "titleId");
        displayName = displayName == null ? "" : displayName.trim();
        if (displayName.isBlank() || displayName.codePointCount(0, displayName.length()) > 80) {
            throw new IllegalArgumentException("Title displayName must contain 1..80 code points");
        }
    }

    @Override
    public String description() {
        return "칭호: " + displayName;
    }
}
