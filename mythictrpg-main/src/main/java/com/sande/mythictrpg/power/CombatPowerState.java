package com.sande.mythictrpg.power;

import net.minecraft.resources.ResourceLocation;
import java.util.*;

/** Immutable game-owned input. Effect levels are public levels (one means amplifier zero).
 * Base attributes exclude equipment and effect modifiers; equipment comes only from reward history. */
public record CombatPowerState(double baseMaxHealth, double baseAttackDamage,
        Map<ResourceLocation, Integer> effectLevels, List<Gear> acquiredGear,
        double ownedBlessingIntrinsicPower, double worldContribution,
        Map<String,Double> learnedSkills, double accessoryFixedPower) {
    public CombatPowerState(double baseMaxHealth,double baseAttackDamage,Map<ResourceLocation,Integer> effectLevels,List<Gear> acquiredGear,
            double ownedBlessingIntrinsicPower,double worldContribution) {
        this(baseMaxHealth,baseAttackDamage,effectLevels,acquiredGear,ownedBlessingIntrinsicPower,worldContribution,Map.of(),0);
    }
    public record Gear(String identity, String slot, double intrinsicPower, double attackDamage,
            int sharpnessLevel, int protectionLevel, Map<String, Double> skills) {
        public Gear {
            if (identity == null || identity.isBlank() || slot == null || slot.isBlank()) throw new IllegalArgumentException("GEAR_IDENTITY");
            finite(intrinsicPower); finite(attackDamage);
            if (sharpnessLevel < 0 || sharpnessLevel > 255 || protectionLevel < 0 || protectionLevel > 255)
                throw new IllegalArgumentException("GEAR_ENCHANTMENT_LEVEL");
            skills = Map.copyOf(skills);
            if (skills.size() > 64) throw new IllegalArgumentException("GEAR_SKILL_LIMIT");
            skills.forEach((id, power) -> { ResourceLocation.parse(id); finite(power); });
        }
    }
    public CombatPowerState {
        finite(baseMaxHealth); finite(baseAttackDamage); finite(ownedBlessingIntrinsicPower); finite(worldContribution); finite(accessoryFixedPower);
        learnedSkills=Map.copyOf(learnedSkills);
        if(learnedSkills.size()>4096)throw new IllegalArgumentException("Learned skill power limit");
        learnedSkills.forEach((id,power)->{ResourceLocation.parse(id);finite(power);});
        if (baseMaxHealth <= 0) throw new IllegalArgumentException("MAX_HEALTH_MUST_BE_POSITIVE");
        effectLevels = Map.copyOf(effectLevels); acquiredGear = List.copyOf(acquiredGear);
        if (effectLevels.size() > 256 || acquiredGear.size() > 4096) throw new IllegalArgumentException("POWER_INPUT_LIMIT");
        effectLevels.forEach((id, level) -> {
            int maximum = id.toString().equals("minecraft:resistance") ? 4 : 17;
            if (level == null || level < 1 || level > maximum) throw new IllegalArgumentException("UNSUPPORTED_EFFECT_LEVEL:" + id);
        });
    }
    public CombatPowerState withEffects(Map<ResourceLocation, Integer> levels) {
        return new CombatPowerState(baseMaxHealth, baseAttackDamage, levels, acquiredGear, ownedBlessingIntrinsicPower, worldContribution,learnedSkills,accessoryFixedPower);
    }
    public CombatPowerState withOwnedBlessingPower(double power) {
        return new CombatPowerState(baseMaxHealth, baseAttackDamage, effectLevels, acquiredGear, power, worldContribution,learnedSkills,accessoryFixedPower);
    }
    public int level(String id) { return effectLevels.getOrDefault(ResourceLocation.parse(id), 0); }
    static void finite(double value) {
        if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException("INVALID_POWER_NUMBER");
    }
}
