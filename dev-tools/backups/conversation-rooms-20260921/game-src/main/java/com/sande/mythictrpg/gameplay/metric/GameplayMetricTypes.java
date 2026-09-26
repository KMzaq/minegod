package com.sande.mythictrpg.gameplay.metric;

import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.resources.ResourceLocation;

public final class GameplayMetricTypes {
    public static final ResourceLocation MATURE_CROP_HARVESTED =
            ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, "mature_crop_harvested");
    public static final ResourceLocation ELIGIBLE_BLOCK_MINED =
            ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, "eligible_block_mined");

    private GameplayMetricTypes() {
    }
}
