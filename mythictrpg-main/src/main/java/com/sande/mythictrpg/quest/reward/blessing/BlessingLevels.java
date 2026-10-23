package com.sande.mythictrpg.quest.reward.blessing;

import net.minecraft.resources.ResourceLocation;

/** Shared public-level/vanilla-amplifier boundary. Public levels are one-based. */
public final class BlessingLevels {
    private static final ResourceLocation RESISTANCE = ResourceLocation.withDefaultNamespace("resistance");
    private BlessingLevels() { }
    public static int maximum(ResourceLocation effectId) { return RESISTANCE.equals(effectId) ? 4 : 17; }
    public static int requireLevel(ResourceLocation effectId, int level) {
        if (level < 1 || level > maximum(effectId)) {
            throw new IllegalArgumentException("Blessing level outside 1.." + maximum(effectId) + " for " + effectId);
        }
        return level;
    }
    public static int amplifier(ResourceLocation effectId, int level) { return requireLevel(effectId, level) - 1; }
    public static int level(ResourceLocation effectId, int amplifier) {
        if (amplifier < 0 || amplifier >= maximum(effectId)) {
            throw new IllegalArgumentException("Blessing amplifier outside supported range for " + effectId);
        }
        return amplifier + 1;
    }
}
