package com.sande.mythictrpg.quest.reward;

import com.sande.mythictrpg.equipment.EquipmentCatalog;
import net.minecraft.resources.ResourceLocation;

/** Authored reference; a personal claim freezes this to an ordinary item before delivery. */
public record EquipmentRewardEntry(ResourceLocation definitionId) implements RewardEntry {
    public EquipmentRewardEntry {
        if (definitionId == null) throw new IllegalArgumentException("Missing equipment definition ID");
    }
    public NpcRewardEntry resolve() {
        return EquipmentCatalog.find(definitionId).orElseThrow(() ->
                new IllegalArgumentException("Unknown equipment definition: " + definitionId)).reward();
    }
    /** Canonical equipment keeps its source receipt after completed claims are pruned. This marker grants no skill authority. */
    static boolean resolvedEquipment(RewardEntry entry) {
        return entry instanceof NpcRewardEntry item && item.components().getCompound("minecraft:custom_data")
                .contains("equipment_definition", net.minecraft.nbt.Tag.TAG_STRING);
    }
    @Override public String description() { return "장비: " + definitionId; }
}
