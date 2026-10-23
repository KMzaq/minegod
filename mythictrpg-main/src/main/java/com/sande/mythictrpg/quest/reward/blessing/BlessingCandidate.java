package com.sande.mythictrpg.quest.reward.blessing;

import net.minecraft.resources.ResourceLocation;
import java.util.Objects;

/** FLOOR is an absolute level, UPGRADE adds an authored increment to the current baseline. */
public record BlessingCandidate(String id, ResourceLocation effectId, Mode mode, int level) {
    public enum Mode { FLOOR, UPGRADE }
    public BlessingCandidate {
        Objects.requireNonNull(effectId);
        Objects.requireNonNull(mode);
        if (id == null || !id.matches("[a-z0-9_./-]{1,128}") || id.equals("ownership_only")) {
            throw new IllegalArgumentException("Invalid or reserved blessing candidate ID");
        }
        BlessingLevels.requireLevel(effectId, level);
    }
}
