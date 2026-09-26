package com.sande.mythictrpg.quest.reward;

import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Variable-length, NPC-owned reward grade table. */
public record NpcRewardTable(ResourceLocation id, ResourceLocation npcId,
        Map<Integer, NpcRewardTier> tiers) {
    public NpcRewardTable {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(npcId, "npcId");
        tiers = Map.copyOf(Objects.requireNonNull(tiers, "tiers"));
        if (tiers.isEmpty()) {
            throw new IllegalArgumentException("NPC reward table must contain at least one tier");
        }
        int maximum = tiers.keySet().stream().mapToInt(Integer::intValue).max().orElseThrow();
        for (int tier = 1; tier <= maximum; tier++) {
            if (!tiers.containsKey(tier)) {
                throw new IllegalArgumentException("NPC reward tiers must be contiguous from 1; missing " + tier);
            }
        }
    }

    public int maximumTier() {
        return tiers.keySet().stream().mapToInt(Integer::intValue).max().orElseThrow();
    }

    public Optional<NpcRewardTier> tier(int value) {
        return Optional.ofNullable(tiers.get(value));
    }
}
