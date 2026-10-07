package com.sande.mythictrpg.power;

import com.google.gson.*;
import net.minecraft.resources.ResourceLocation;
import java.util.*;

/** Authored effect/level scores, independent of whether an owned blessing is currently applied. */
public record BlessingPowerPolicy(String id, Map<String, Double> scores) {
    public BlessingPowerPolicy { scores = Map.copyOf(scores); }
    public static BlessingPowerPolicy parse(String id, JsonObject json) {
        fields(json, "schemaVersion", "effects");
        if (integer(json.get("schemaVersion")) != 1) throw new IllegalArgumentException("Unsupported blessing power schema");
        JsonArray rows = json.getAsJsonArray("effects");
        if (rows == null || rows.isEmpty() || rows.size() > 512) throw new IllegalArgumentException("Bounded effect table required");
        Map<String, Double> scores = new LinkedHashMap<>(); Set<String> ids = new HashSet<>();
        for (JsonElement element : rows) {
            JsonObject row = element.getAsJsonObject(); fields(row, "effectId", "scores");
            JsonElement rawId = row.get("effectId");
            if (rawId == null || !rawId.isJsonPrimitive() || !rawId.getAsJsonPrimitive().isString())
                throw new IllegalArgumentException("Effect ID must be a string");
            String effect = rawId.getAsString();
            if (!effect.contains(":") || !ResourceLocation.parse(effect).toString().equals(effect) || !ids.add(effect))
                throw new IllegalArgumentException("Explicit unique effect ID required");
            JsonArray levels = row.getAsJsonArray("scores");
            if (levels == null || levels.isEmpty() || levels.size() > 256) throw new IllegalArgumentException("1..256 level scores required");
            double previous = -1;
            for (int amplifier = 0; amplifier < levels.size(); amplifier++) {
                JsonElement value = levels.get(amplifier);
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("Numeric score required");
                double score = value.getAsDouble();
                if (!Double.isFinite(score) || score < 0 || score < previous) throw new IllegalArgumentException("Finite nonnegative nondecreasing scores required");
                scores.put(effect + "#" + amplifier, score); previous = score;
            }
        }
        return new BlessingPowerPolicy(id, scores);
    }
    /** Vanilla same-effect levels do not stack. A stronger temporary effect counts once, never twice. */
    public static Set<String> effectInputs(Map<ResourceLocation, Integer> owned, Map<ResourceLocation, Integer> active) {
        Map<ResourceLocation, Integer> levels = new HashMap<>();
        for (var values : List.of(owned, active)) values.forEach((effect, amplifier) -> {
            if (effect == null || amplifier == null || amplifier < 0 || amplifier > 255) throw new IllegalArgumentException("Invalid effect input");
            levels.merge(effect, amplifier, Math::max);
        });
        Set<String> result = new HashSet<>(); levels.forEach((effect, amplifier) -> result.add(effect + "#" + amplifier));
        return Set.copyOf(result);
    }
    /** Explicit world-policy entries take precedence over this editable starting score table. */
    public Map<String, Double> withOverrides(Map<String, Double> overrides) {
        Map<String, Double> result = new HashMap<>(scores); result.putAll(overrides); return Map.copyOf(result);
    }
    public static double total(Set<String> inputs, Map<String, Double> scores) {
        double result = 0;
        for (String key : inputs) {
            Double score = scores.get(key);
            if (score == null || !Double.isFinite(score) || score < 0) throw new IllegalArgumentException("UNSCORED_EFFECT:" + key);
            result += score;
        }
        if (!Double.isFinite(result)) throw new IllegalArgumentException("EFFECT_SCORE_OVERFLOW");
        return result;
    }
    private static int integer(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("Integer required");
        return value.getAsBigDecimal().intValueExact();
    }
    private static void fields(JsonObject json, String... expected) {
        if (!json.keySet().equals(Set.of(expected))) throw new IllegalArgumentException("Missing or unknown blessing score fields");
    }
}
