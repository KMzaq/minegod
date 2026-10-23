package com.sande.mythictrpg.power;

import com.google.gson.*;
import java.util.*;

/** Pure proposal model, shared by present-state display and hypothetical blessing selection.
 * Power weights are provisional balance inputs; physical constants must match the actual vanilla combat path. */
public final class CombatPowerEvaluator {
    private CombatPowerEvaluator() { }
    public record Model(CombatPowerPolicy.Aggregation aggregation, double basePower, double healthCoefficient,
            double equipmentCoefficient, double ownedBlessingCoefficient, double skillCoefficient,
            double worldContributionCoefficient, double offenseCoefficient, double healthBoostPerLevel,
            double regenerationHealthFraction, double resistanceReductionPerLevel, double protectionHalfValue,
            double defenseExponent, double strengthFlatPerLevel, double strengthWeaponFractionPerLevel,
            double sharpnessPerLevel, double sharpnessBase, double hasteFractionPerLevel,
            Map<String, Double> effectPointsPerLevel, Map<String, Double> booleanEffectPoints) {
        public Model {
            Objects.requireNonNull(aggregation);
            for (double value : new double[]{basePower, healthCoefficient, equipmentCoefficient, ownedBlessingCoefficient,
                    skillCoefficient, worldContributionCoefficient, offenseCoefficient, healthBoostPerLevel,
                    regenerationHealthFraction, resistanceReductionPerLevel, protectionHalfValue, defenseExponent,
                    strengthFlatPerLevel, strengthWeaponFractionPerLevel, sharpnessPerLevel, sharpnessBase, hasteFractionPerLevel})
                CombatPowerState.finite(value);
            if (resistanceReductionPerLevel * 4 >= 1 || protectionHalfValue <= 0 || defenseExponent > 1)
                throw new IllegalArgumentException("UNBOUNDED_DEFENSE_MODEL");
            // These are physical Minecraft 1.21.1 inputs, not power weights. Actual combat still
            // supplies them through vanilla attributes/enchantment effects, so a rating-only edit is invalid.
            if (healthBoostPerLevel != 4 || resistanceReductionPerLevel != 0.2 || strengthFlatPerLevel != 3
                    || sharpnessPerLevel != 0.5 || sharpnessBase != 0.5)
                throw new IllegalArgumentException("VANILLA_PHYSICAL_CONSTANT_MISMATCH");
            effectPointsPerLevel = Map.copyOf(effectPointsPerLevel); booleanEffectPoints = Map.copyOf(booleanEffectPoints);
            Set<String> reserved = Set.of("minecraft:strength", "minecraft:haste", "minecraft:resistance", "minecraft:regeneration", "minecraft:health_boost");
            Set<String> seen = new HashSet<>(reserved);
            for (var table : List.of(effectPointsPerLevel, booleanEffectPoints)) {
                if (table.size() > 256) throw new IllegalArgumentException("EFFECT_SCORE_LIMIT");
                table.forEach((id, power) -> {
                    if (!id.contains(":")) throw new IllegalArgumentException("EFFECT_ID");
                    net.minecraft.resources.ResourceLocation.parse(id); CombatPowerState.finite(power);
                    if (!seen.add(id)) throw new IllegalArgumentException("DOUBLE_COUNTED_EFFECT:" + id);
                });
            }
            for (String id : List.of("minecraft:fire_resistance", "minecraft:saturation"))
                if (!booleanEffectPoints.containsKey(id)) throw new IllegalArgumentException("BOOLEAN_EFFECT_SCORE_REQUIRED:" + id);
        }
    }
    public record Breakdown(double total, double base, double health, double offense, double defenseEffects,
            double utilityEffects, double equipmentIntrinsic, double ownedBlessingIntrinsic, double skills,
            double worldContribution, double evaluatedMaxHealth, double weaponAttack, int sharpnessLevel, int protectionLevel) { }

    public static Breakdown evaluate(CombatPowerState state, Model model) {
        return evaluateSelected(state, model, selectGear(state, model));
    }
    private static Breakdown evaluateSelected(CombatPowerState state, Model model, List<CombatPowerState.Gear> selected) {
        double equipment = 0, attack = state.baseAttackDamage(); int sharpness = 0, protection = 0;
        Map<String, Double> skills = new TreeMap<>();
        skills.putAll(state.learnedSkills());
        for (var item : selected) {
            equipment += item.intrinsicPower();
            // One strongest weapon supplies attack/sharpness, never a synthetic combination of two weapons.
            double itemAttack = item.attackDamage() + sharpnessBonus(item.sharpnessLevel(), model);
            if (itemAttack > attack - state.baseAttackDamage()) {
                attack = state.baseAttackDamage() + itemAttack; sharpness = item.sharpnessLevel();
            }
            protection = Math.addExact(protection, item.protectionLevel());
            item.skills().forEach((id, value) -> skills.merge(id, value, Math::max));
        }
        double hp = state.baseMaxHealth() + model.healthBoostPerLevel() * state.level("minecraft:health_boost");
        double health = hp * model.healthCoefficient();
        double strength = state.level("minecraft:strength"), haste = state.level("minecraft:haste");
        double offense = model.offenseCoefficient() * attackPower(attack, strength, haste, model);
        double remaining = remainingDamageFraction(state.level("minecraft:resistance"), protection, model);
        double defense = health * (1 + model.regenerationHealthFraction() * state.level("minecraft:regeneration"))
                * Math.pow(1 / remaining, model.defenseExponent()) - health;
        double utility = 0;
        Set<String> interactions = Set.of("minecraft:strength", "minecraft:haste", "minecraft:resistance", "minecraft:regeneration", "minecraft:health_boost");
        // Sorted iteration makes equality stable across map insertion order and save/reload.
        for (var entry : new TreeMap<>(state.effectLevels()).entrySet()) {
            String id = entry.getKey().toString();
            if (interactions.contains(id)) continue;
            Double booleanPoints = model.booleanEffectPoints().get(id);
            Double perLevel = model.effectPointsPerLevel().get(id);
            if (booleanPoints == null && perLevel == null) throw new IllegalArgumentException("UNSCORED_ACTIVE_EFFECT:" + id);
            utility += booleanPoints != null ? booleanPoints : perLevel * entry.getValue();
        }
        double gearPower = equipment * model.equipmentCoefficient()+state.accessoryFixedPower();
        double ownedPower = state.ownedBlessingIntrinsicPower() * model.ownedBlessingCoefficient();
        double skillPower = skills.values().stream().mapToDouble(Double::doubleValue).sum() * model.skillCoefficient();
        double worldPower = state.worldContribution() * model.worldContributionCoefficient();
        double total = model.basePower() + health + offense + defense + utility + gearPower + ownedPower + skillPower + worldPower;
        CombatPowerState.finite(total);
        return new Breakdown(total, model.basePower(), health, offense, defense, utility, gearPower, ownedPower,
                skillPower, worldPower, hp, attack, sharpness, protection);
    }
    public static double attackPower(double weaponAttack, double strengthLevel, double hasteLevel, Model model) {
        return (weaponAttack + model.strengthFlatPerLevel() * strengthLevel
                + weaponAttack * model.strengthWeaponFractionPerLevel() * strengthLevel)
                * (1 + model.hasteFractionPerLevel() * hasteLevel);
    }
    public static double remainingDamageFraction(int resistanceLevel, int protectionLevel, Model model) {
        return (1 - model.resistanceReductionPerLevel() * resistanceLevel)
                / (1 + protectionLevel / model.protectionHalfValue());
    }
    private static double sharpnessBonus(int level, Model model) {
        return level == 0 ? 0 : model.sharpnessBase() + model.sharpnessPerLevel() * level;
    }
    private static List<CombatPowerState.Gear> selectGear(CombatPowerState state, Model model) {
        // V2 evaluates each historical candidate under the same state, then retains the best per slot.
        // Intrinsic points alone are not a gear rank: a high-enchantment weapon may have zero intrinsic points.
        Comparator<CombatPowerState.Gear> order = Comparator.comparingDouble((CombatPowerState.Gear item) -> evaluateSelected(state,model,List.of(item)).total())
                .thenComparing(CombatPowerState.Gear::identity);
        if (model.aggregation() == CombatPowerPolicy.Aggregation.MAX_ALL) return state.acquiredGear().stream().max(order).map(List::of).orElse(List.of());
        Map<String, CombatPowerState.Gear> selected = new TreeMap<>();
        for (var item : state.acquiredGear()) selected.merge(item.slot(), item, (a, b) -> order.compare(a, b) >= 0 ? a : b);
        return List.copyOf(selected.values());
    }
    public static Model parse(JsonObject json, CombatPowerPolicy.Aggregation aggregation) {
        Set<String> fields = Set.of("basePower", "healthCoefficient", "equipmentCoefficient", "ownedBlessingCoefficient",
                "skillCoefficient", "worldContributionCoefficient", "offenseCoefficient", "healthBoostPerLevel",
                "regenerationHealthFraction", "resistanceReductionPerLevel", "protectionHalfValue", "defenseExponent",
                "strengthFlatPerLevel", "strengthWeaponFractionPerLevel", "sharpnessPerLevel", "sharpnessBase", "hasteFractionPerLevel",
                "effectPointsPerLevel", "booleanEffectPoints");
        if (!json.keySet().equals(fields)) throw new IllegalArgumentException("MODEL_FIELDS");
        return new Model(aggregation, number(json,"basePower"), number(json,"healthCoefficient"), number(json,"equipmentCoefficient"),
                number(json,"ownedBlessingCoefficient"), number(json,"skillCoefficient"), number(json,"worldContributionCoefficient"),
                number(json,"offenseCoefficient"), number(json,"healthBoostPerLevel"), number(json,"regenerationHealthFraction"),
                number(json,"resistanceReductionPerLevel"), number(json,"protectionHalfValue"), number(json,"defenseExponent"),
                number(json,"strengthFlatPerLevel"), number(json,"strengthWeaponFractionPerLevel"), number(json,"sharpnessPerLevel"),
                number(json,"sharpnessBase"), number(json,"hasteFractionPerLevel"), table(json,"effectPointsPerLevel"), table(json,"booleanEffectPoints"));
    }
    private static Map<String, Double> table(JsonObject json, String key) {
        if (!json.has(key) || !json.get(key).isJsonObject()) throw new IllegalArgumentException("MODEL_TABLE:" + key);
        Map<String, Double> result = new TreeMap<>();
        JsonObject table = json.getAsJsonObject(key); table.keySet().forEach(id -> result.put(id, number(table,id))); return result;
    }
    private static double number(JsonObject json, String key) {
        if (!json.has(key) || !json.get(key).isJsonPrimitive() || !json.get(key).getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("MODEL_NUMBER:" + key);
        double value = json.get(key).getAsDouble(); CombatPowerState.finite(value); return value;
    }
}
