package com.sande.mythictrpg.quest.reward.luck;

import net.minecraft.resources.ResourceLocation;
import java.util.List;
import java.util.Objects;

public record LuckyItemPool(ResourceLocation id, List<LuckyItemCandidate> candidates) {
    public LuckyItemPool {
        Objects.requireNonNull(id);
        candidates = List.copyOf(candidates);
        if (candidates.isEmpty() || candidates.size() > 32) throw new IllegalArgumentException("Lucky pool needs 1..32 candidates");
        if (candidates.stream().map(LuckyItemCandidate::id).distinct().count() != candidates.size()) {
            throw new IllegalArgumentException("Duplicate lucky item candidate IDs");
        }
    }
}
