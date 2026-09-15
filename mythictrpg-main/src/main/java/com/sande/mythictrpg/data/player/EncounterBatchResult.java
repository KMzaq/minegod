package com.sande.mythictrpg.data.player;

import net.minecraft.resources.ResourceLocation;

import java.util.Set;

public record EncounterBatchResult(EncounterBatchStatus status, Set<ResourceLocation> godIds,
        int newRecordCount) {
    public EncounterBatchResult {
        godIds = Set.copyOf(godIds);
        if (newRecordCount < 0 || newRecordCount > godIds.size()) {
            throw new IllegalArgumentException("Invalid encounter batch count");
        }
    }

    public boolean committed() {
        return status == EncounterBatchStatus.NEW_RECORDS
                || status == EncounterBatchStatus.ALREADY_RECORDED;
    }
}
