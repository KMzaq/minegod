package com.sande.mythictrpg.quest.reward.blessing;

import net.minecraft.resources.ResourceLocation;
import java.util.Map;
import java.util.Objects;

/** Explicit god allowlist. Absence never grants a unique effect or a default priority. */
public record GodBlessingPolicy(ResourceLocation godId, Map<ResourceLocation, EffectRule> effects) {
    public record EffectRule(int maxLevel, int priority) {
        public EffectRule {
            if (maxLevel < 1 || maxLevel > 17 || priority < 0 || priority > 1_000_000) {
                throw new IllegalArgumentException("Invalid god blessing effect cap/priority");
            }
        }
    }
    public GodBlessingPolicy {
        Objects.requireNonNull(godId);
        effects = Map.copyOf(effects);
        if (effects.isEmpty() || effects.size() > 64) throw new IllegalArgumentException("God allowlist needs 1..64 effects");
        effects.forEach((effect, rule) -> BlessingLevels.requireLevel(effect, rule.maxLevel()));
    }
}
