package com.sande.mythictrpg.power;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import com.sande.mythictrpg.quest.dynamic.CombatPowerAssessment;
import com.sande.mythictrpg.quest.reward.NpcRewardEntry;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import java.util.*;

/** Authored comparison policy only. Never changes an item, attribute, effect, reward or world progress. */
public record CombatPowerPolicy(String id, Aggregation aggregation, double basePower, double equipmentCoefficient,
        double effectCoefficient, double permanentCoefficient, double lowRatioExclusive,
        List<ItemRule> items, Map<String, Double> effects, Map<String, Double> permanent,
        List<Recommendation> recommendations) {
    public enum Aggregation { MAX_ALL, SUM_SLOT_BEST }
    public record ItemRule(ResourceLocation itemId, CompoundTag components, String slot, double score, boolean ignored) {
        public ItemRule { components = components.copy(); }
        @Override public CompoundTag components() { return components.copy(); }
        CompoundTag identity(HolderLookup.Provider registries) {
            return RewardPowerState.identity(new NpcRewardEntry(itemId, 1, components).createStack(registries), registries);
        }
    }
    public record Range(int minimum, int maximum) { }
    public record Recommendation(Map<String, Range> progress, double power) {
        public Recommendation { progress = Map.copyOf(progress); }
        boolean matches(Map<String, Integer> values) {
            return progress.entrySet().stream().allMatch(e -> values.getOrDefault(e.getKey(), 0) >= e.getValue().minimum()
                    && values.getOrDefault(e.getKey(), 0) <= e.getValue().maximum());
        }
    }
    public record Gear(String slot, double score) { }
    public CombatPowerPolicy {
        items = List.copyOf(items); effects = Map.copyOf(effects); permanent = Map.copyOf(permanent);
        recommendations = List.copyOf(recommendations);
    }
    public List<Gear> scoreItems(List<CompoundTag> acquired, HolderLookup.Provider registries) {
        List<CompoundTag> identities = items.stream().map(rule -> rule.identity(registries)).toList();
        if (new HashSet<>(identities).size() != identities.size()) throw new IllegalArgumentException("AMBIGUOUS_ITEM_RULE");
        List<Gear> result = new ArrayList<>();
        for (CompoundTag identity : acquired) {
            int index = identities.indexOf(identity);
            if (index < 0) throw new IllegalArgumentException("UNKNOWN_REWARD_VARIANT");
            ItemRule rule = items.get(index);
            if (!rule.ignored()) result.add(new Gear(rule.slot(), rule.score()));
        }
        return List.copyOf(result);
    }
    public CombatPowerPolicy withEffectDefaults(Map<String, Double> defaults) {
        Map<String, Double> combined = new HashMap<>(defaults); combined.putAll(effects);
        return new CombatPowerPolicy(id, aggregation, basePower, equipmentCoefficient, effectCoefficient,
                permanentCoefficient, lowRatioExclusive, items, combined, permanent, recommendations);
    }
    public CombatPowerAssessment evaluate(List<Gear> gear, Map<String, Integer> progress,
            Set<String> activeEffects, Map<String, Double> actualPermanentModifiers) {
        List<Recommendation> matching = recommendations.stream().filter(row -> row.matches(progress)).toList();
        if (matching.size() != 1) return CombatPowerAssessment.unavailable("RECOMMENDATION_MISSING_OR_AMBIGUOUS");
        Map<String, Double> slotBest = new HashMap<>(); double best = 0;
        for (Gear value : gear) { slotBest.merge(value.slot(), value.score(), Math::max); best = Math.max(best, value.score()); }
        double equipment = aggregation == Aggregation.MAX_ALL ? best : slotBest.values().stream().mapToDouble(Double::doubleValue).sum();
        double effectPower = 0, permanentPower = 0;
        for (String effect : activeEffects) {
            Double score = effects.get(effect);
            if (score == null) return CombatPowerAssessment.unavailable("UNKNOWN_ACTIVE_EFFECT");
            effectPower += score;
        }
        for (var modifier : actualPermanentModifiers.entrySet()) {
            Double coefficient = permanent.get(modifier.getKey());
            if (coefficient == null) return CombatPowerAssessment.unavailable("UNKNOWN_PERMANENT_MODIFIER");
            permanentPower += modifier.getValue() * coefficient;
        }
        double actual = basePower + equipment * equipmentCoefficient + effectPower * effectCoefficient + permanentPower * permanentCoefficient;
        double recommended = matching.getFirst().power();
        if (!Double.isFinite(actual) || actual < 0) return CombatPowerAssessment.unavailable("INVALID_COMPUTED_POWER");
        return new CombatPowerAssessment(CombatPowerAssessment.Status.AVAILABLE, actual, recommended,
                actual < recommended * lowRatioExclusive ? 1 : 0,
                "policy=" + id + "; historicalRewardGear=" + equipment + "; ownedBlessingsOrActiveEffects=" + effectPower
                        + "; actualPermanentModifiers=" + permanentPower + "; no_equipped_gear_input");
    }
    public static CombatPowerPolicy parse(String id, JsonObject json) {
        fields(json, "schemaVersion", "aggregation", "basePower", "equipmentCoefficient", "effectCoefficient",
                "permanentCoefficient", "permanentMode", "lowRatioExclusive", "items", "effects", "permanentModifiers", "recommendations");
        if (integer(json, "schemaVersion") != 1 || !string(json, "permanentMode").equals("ACTUAL_PERMANENT_MODIFIERS_ONLY"))
            throw new IllegalArgumentException("Unsupported power schema/permanent source");
        double ratio = number(json, "lowRatioExclusive");
        if (ratio <= 0 || ratio >= 1) throw new IllegalArgumentException("lowRatioExclusive must be authored in (0,1)");
        List<ItemRule> items = new ArrayList<>();
        for (JsonElement element : array(json, "items")) {
            JsonObject rule = element.getAsJsonObject(); fields(rule, "itemId", "components", "classification", "slot", "score");
            String classification = string(rule, "classification"), slot = string(rule, "slot");
            if (!Set.of("GEAR", "IGNORE").contains(classification) || !slot.matches("[a-z0-9_]{1,32}"))
                throw new IllegalArgumentException("item classification/slot");
            double score = number(rule, "score");
            if (classification.equals("IGNORE") && (score != 0 || !slot.equals("none"))) throw new IllegalArgumentException("IGNORE must explicitly score zero in none slot");
            JsonObject components = rule.getAsJsonObject("components");
            if (components == null || components.toString().length() > 16_384) throw new IllegalArgumentException("components required/bounded");
            items.add(new ItemRule(namespaced(string(rule, "itemId")), (CompoundTag)JsonOps.INSTANCE.convertTo(NbtOps.INSTANCE, components),
                    slot, score, classification.equals("IGNORE")));
        }
        Map<String, Double> effects = new LinkedHashMap<>(), permanent = new LinkedHashMap<>();
        for (JsonElement element : array(json, "effects")) {
            JsonObject rule = element.getAsJsonObject(); fields(rule, "effectId", "amplifier", "score");
            int amplifier = integer(rule, "amplifier"); if (amplifier < 0 || amplifier > 255) throw new IllegalArgumentException("effect amplifier");
            unique(effects, namespaced(string(rule, "effectId")) + "#" + amplifier, number(rule, "score"));
        }
        for (JsonElement element : array(json, "permanentModifiers")) {
            JsonObject rule = element.getAsJsonObject(); fields(rule, "attributeId", "modifierId", "operation", "coefficient");
            String operation = string(rule, "operation");
            if (!Set.of("ADD_VALUE", "ADD_MULTIPLIED_BASE", "ADD_MULTIPLIED_TOTAL").contains(operation)) throw new IllegalArgumentException("modifier operation");
            unique(permanent, namespaced(string(rule, "attributeId")) + "#" + namespaced(string(rule, "modifierId")) + "#" + operation,
                    number(rule, "coefficient"));
        }
        List<Recommendation> recommendations = new ArrayList<>();
        for (JsonElement element : array(json, "recommendations")) {
            JsonObject row = element.getAsJsonObject(); fields(row, "progress", "recommendedPower");
            JsonObject tracks = row.getAsJsonObject("progress");
            if (tracks == null || tracks.isEmpty() || tracks.size() > 64) throw new IllegalArgumentException("Explicit world progress tracks required");
            Map<String, Range> ranges = new LinkedHashMap<>();
            for (var entry : tracks.entrySet()) {
                namespaced(entry.getKey()); JsonObject range = entry.getValue().getAsJsonObject(); fields(range, "minimum", "maximum");
                int min = integer(range, "minimum"), max = integer(range, "maximum");
                if (min < 0 || max < min) throw new IllegalArgumentException("progress range");
                ranges.put(entry.getKey(), new Range(min, max));
            }
            double recommended = number(row, "recommendedPower");
            if (recommended <= 0) throw new IllegalArgumentException("recommendedPower must be positive");
            recommendations.add(new Recommendation(ranges, recommended));
        }
        if (recommendations.isEmpty()) throw new IllegalArgumentException("recommendation table required");
        return new CombatPowerPolicy(id, Aggregation.valueOf(string(json, "aggregation")), number(json, "basePower"),
                number(json, "equipmentCoefficient"), number(json, "effectCoefficient"), number(json, "permanentCoefficient"),
                ratio, items, effects, permanent, recommendations);
    }
    private static void unique(Map<String, Double> map, String key, double value) {
        if (map.putIfAbsent(key, value) != null) throw new IllegalArgumentException("Duplicate power rule " + key);
    }
    private static void fields(JsonObject json, String... names) {
        if (!json.keySet().equals(Set.of(names))) throw new IllegalArgumentException("Missing or unknown power policy fields");
    }
    private static String string(JsonObject json, String key) {
        JsonElement value = json.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || value.getAsString().isBlank())
            throw new IllegalArgumentException("Expected string " + key);
        return value.getAsString();
    }
    private static double number(JsonObject json, String key) {
        JsonElement value = json.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("Expected number " + key);
        double result = value.getAsDouble(); if (!Double.isFinite(result) || result < 0) throw new IllegalArgumentException("Invalid number " + key); return result;
    }
    private static int integer(JsonObject json, String key) {
        number(json, key); return json.get(key).getAsBigDecimal().intValueExact();
    }
    private static JsonArray array(JsonObject json, String key) {
        JsonArray values = json.getAsJsonArray(key); if (values == null || values.size() > 4096) throw new IllegalArgumentException("Missing/bounded array " + key); return values;
    }
    private static ResourceLocation namespaced(String value) {
        if (!value.contains(":")) throw new IllegalArgumentException("Explicit namespaced ID required"); return ResourceLocation.parse(value);
    }
}
