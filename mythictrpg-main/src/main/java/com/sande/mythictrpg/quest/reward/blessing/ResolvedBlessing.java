package com.sande.mythictrpg.quest.reward.blessing;

import net.minecraft.resources.ResourceLocation;
import java.util.Objects;
import java.util.Optional;

/** Immutable acquisition decision. Persist this value; never select it again on login/recalculation. */
public record ResolvedBlessing(ResourceLocation definitionId, ResourceLocation godId, GodRank godRank,
        String candidateId, Optional<ResourceLocation> effectId, int level, double intrinsicScore) {
    public ResolvedBlessing {
        Objects.requireNonNull(definitionId);
        Objects.requireNonNull(godId);
        Objects.requireNonNull(godRank);
        Objects.requireNonNull(effectId);
        if (candidateId == null || !candidateId.matches("[a-z0-9_./-]{1,128}")) {
            throw new IllegalArgumentException("Invalid blessing candidate ID");
        }
        if (!Double.isFinite(intrinsicScore) || intrinsicScore < 0 || intrinsicScore > 1_000_000) {
            throw new IllegalArgumentException("intrinsicScore must be finite and within 0..1000000");
        }
        if (effectId.isPresent()) {
            BlessingLevels.requireLevel(effectId.orElseThrow(), level);
        } else if (level != 0 || !candidateId.equals("ownership_only")) {
            throw new IllegalArgumentException("Ownership-only blessing requires level 0 and ownership_only candidate");
        }
    }
    public boolean ownershipOnly() { return effectId.isEmpty(); }
    public int amplifier() {
        return BlessingLevels.amplifier(effectId.orElseThrow(() -> new IllegalStateException("Blessing has no effect")), level);
    }
}
