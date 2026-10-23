package com.sande.mythictrpg.quest.reward.blessing;

import net.minecraft.resources.ResourceLocation;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public record BlessingDefinition(ResourceLocation id, ResourceLocation godId, double intrinsicScore,
        List<BlessingCandidate> candidates, Fallback fallback) {
    public enum Fallback { NONE, ONLY_OWNERSHIP }
    public BlessingDefinition {
        Objects.requireNonNull(id);
        Objects.requireNonNull(godId);
        Objects.requireNonNull(fallback);
        if (!Double.isFinite(intrinsicScore) || intrinsicScore < 0 || intrinsicScore > 1_000_000) {
            throw new IllegalArgumentException("intrinsicScore must be finite and within 0..1000000");
        }
        candidates = List.copyOf(candidates);
        if (candidates.isEmpty() || candidates.size() > 32) {
            throw new IllegalArgumentException("Blessing needs 1..32 authored candidates");
        }
        var ids = new HashSet<String>();
        for (var candidate : candidates) {
            if (!ids.add(candidate.id())) throw new IllegalArgumentException("Duplicate blessing candidate " + candidate.id());
        }
    }
}
