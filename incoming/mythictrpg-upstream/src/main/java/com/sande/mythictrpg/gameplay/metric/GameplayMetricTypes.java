package com.sande.mythictrpg.gameplay.metric;

import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.resources.ResourceLocation;

public final class GameplayMetricTypes {
    public static final ResourceLocation MATURE_CROP_HARVESTED =
            ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, "mature_crop_harvested");

    private GameplayMetricTypes() {
    }
}
