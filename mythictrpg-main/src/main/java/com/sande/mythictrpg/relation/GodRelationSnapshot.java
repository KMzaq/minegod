package com.sande.mythictrpg.relation;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Objects;
import java.util.Set;

public record GodRelationSnapshot(GodRelationKey key, int score, Set<GodRelationTag> tags,
        long revision, long lastChangedGameTime, ResourceLocation lastCauseId,
        List<GodRelationHistoryEntry> recentHistory) {
    public static final int MIN_SCORE = -1000;
    public static final int MAX_SCORE = 1000;

    public GodRelationSnapshot {
        Objects.requireNonNull(key, "key");
        if (score < MIN_SCORE || score > MAX_SCORE || revision < 0 || lastChangedGameTime < 0) {
            throw new IllegalArgumentException("Invalid God relation snapshot bounds");
        }
        tags = Set.copyOf(Objects.requireNonNull(tags, "tags"));
        Objects.requireNonNull(lastCauseId, "lastCauseId");
        recentHistory = List.copyOf(Objects.requireNonNull(recentHistory, "recentHistory"));
        GodRelationRules.requireCompatible(tags);
    }

    public static GodRelationSnapshot neutral(GodRelationKey key) {
        return new GodRelationSnapshot(key, 0, Set.of(), 0, 0,
                ResourceLocation.fromNamespaceAndPath("mythictrpg", "initial"), List.of());
    }
}

