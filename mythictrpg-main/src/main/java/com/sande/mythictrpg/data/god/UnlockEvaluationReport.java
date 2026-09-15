package com.sande.mythictrpg.data.god;

import net.minecraft.resources.ResourceLocation;

import java.util.List;

public record UnlockEvaluationReport(int checked, List<ResourceLocation> newlyUnlocked) {
    private static final UnlockEvaluationReport EMPTY = new UnlockEvaluationReport(0, List.of());

    public UnlockEvaluationReport {
        newlyUnlocked = List.copyOf(newlyUnlocked);
    }

    public static UnlockEvaluationReport empty() {
        return EMPTY;
    }

    public int newlyUnlockedCount() {
        return newlyUnlocked.size();
    }
}
