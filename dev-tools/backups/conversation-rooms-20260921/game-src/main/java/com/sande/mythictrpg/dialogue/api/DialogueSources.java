package com.sande.mythictrpg.dialogue.api;

import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.resources.ResourceLocation;

public final class DialogueSources {
    public static final ResourceLocation GOD = id("god");
    public static final ResourceLocation WORLD_EVENT = id("world_event");
    public static final ResourceLocation QUEST = id("quest");
    public static final ResourceLocation RAID = id("raid");
    public static final ResourceLocation SYSTEM = id("system");

    private DialogueSources() {
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, path);
    }
}
