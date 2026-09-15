package com.sande.mythictrpg.interaction.candidate;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Objects;

public record GodCandidate(ResourceLocation godId, long totalScore, List<ResourceLocation> reasonIds) {
    public static final int MAX_REASONS = 16;

    public GodCandidate {
        Objects.requireNonNull(godId, "godId");
        reasonIds = reasonIds.stream().distinct().sorted().limit(MAX_REASONS).toList();
    }
}
