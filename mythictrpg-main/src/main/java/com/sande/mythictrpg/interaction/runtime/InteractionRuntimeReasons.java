package com.sande.mythictrpg.interaction.runtime;

import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.resources.ResourceLocation;

public final class InteractionRuntimeReasons {
    public static final ResourceLocation PLAYER_BUSY = id("interaction_player_busy");
    public static final ResourceLocation SPONTANEOUS_COOLDOWN = id("spontaneous_interaction_cooldown");

    private InteractionRuntimeReasons() {
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, path);
    }
}
