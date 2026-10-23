package com.sande.mythictrpg.quest.reward.blessing;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Strict versioned authoring schema; no NPC names, ranks, balance values, or effect defaults are inferred. */
public final class BlessingDefinitionSchema {
    private BlessingDefinitionSchema() { }

    public static BlessingDefinition definition(ResourceLocation id, JsonObject json) {
        header(json, "definition", Set.of("schema_version", "kind", "god_id", "intrinsic_score", "candidates", "fallback"));
        JsonElement list = required(json, "candidates");
        if (!list.isJsonArray() || list.getAsJsonArray().isEmpty() || list.getAsJsonArray().size() > 32) {
            throw new IllegalArgumentException("candidates must contain 1..32 entries");
        }
        var candidates = new ArrayList<BlessingCandidate>();
        for (JsonElement entry : list.getAsJsonArray()) {
            JsonObject candidate = object(entry, "candidate");
            rejectUnknown(candidate, Set.of("id", "effect", "mode", "level"));
            candidates.add(new BlessingCandidate(string(candidate, "id"), id(string(candidate, "effect")),
                    enumValue(BlessingCandidate.Mode.class, string(candidate, "mode")), integer(candidate, "level")));
        }
        return new BlessingDefinition(id, id(string(json, "god_id")), number(json, "intrinsic_score"),
                candidates, enumValue(BlessingDefinition.Fallback.class, string(json, "fallback")));
    }

    public static GodBlessingPolicy godPolicy(JsonObject json) {
        header(json, "god_policy", Set.of("schema_version", "kind", "god_id", "effects"));
        var effects = object(required(json, "effects"), "effects");
        if (effects.size() < 1 || effects.size() > 64) throw new IllegalArgumentException("effects needs 1..64 entries");
        var rules = new LinkedHashMap<ResourceLocation, GodBlessingPolicy.EffectRule>();
        for (var entry : effects.entrySet()) {
            var rule = object(entry.getValue(), "effect rule");
            rejectUnknown(rule, Set.of("max_level", "priority"));
            rules.put(id(entry.getKey()), new GodBlessingPolicy.EffectRule(integer(rule, "max_level"), integer(rule, "priority")));
        }
        return new GodBlessingPolicy(id(string(json, "god_id")), rules);
    }

    public static BlessingRankPolicy rankPolicy(JsonObject json) {
        header(json, "rank_policy", Set.of("schema_version", "kind", "rank", "effect_caps"));
        var effects = object(required(json, "effect_caps"), "effect_caps");
        if (effects.size() < 1 || effects.size() > 64) throw new IllegalArgumentException("effect_caps needs 1..64 entries");
        Map<ResourceLocation, Integer> caps = new LinkedHashMap<>();
        effects.entrySet().forEach(entry -> caps.put(id(entry.getKey()), integer(effects, entry.getKey())));
        return new BlessingRankPolicy(GodRank.parse(string(json, "rank")), caps);
    }

    public static String kind(JsonObject json) { return string(json, "kind"); }

    private static void header(JsonObject json, String kind, Set<String> fields) {
        rejectUnknown(json, fields);
        if (integer(json, "schema_version") != 1) throw new IllegalArgumentException("Unsupported blessing schema_version");
        if (!string(json, "kind").equals(kind)) throw new IllegalArgumentException("Expected kind " + kind);
    }
    private static void rejectUnknown(JsonObject json, Set<String> fields) {
        json.keySet().forEach(key -> { if (!fields.contains(key)) throw new IllegalArgumentException("Unknown blessing field " + key); });
    }
    private static JsonElement required(JsonObject json, String key) {
        var value = json.get(key);
        if (value == null || value.isJsonNull()) throw new IllegalArgumentException("Missing blessing field " + key);
        return value;
    }
    private static JsonObject object(JsonElement value, String field) {
        if (!value.isJsonObject()) throw new IllegalArgumentException(field + " must be an object");
        return value.getAsJsonObject();
    }
    private static String string(JsonObject json, String key) {
        var value = required(json, key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || value.getAsString().isBlank()) {
            throw new IllegalArgumentException(key + " must be a nonblank string");
        }
        return value.getAsString();
    }
    private static double number(JsonObject json, String key) {
        var value = required(json, key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException(key + " must be a number");
        double result = value.getAsDouble();
        if (!Double.isFinite(result)) throw new IllegalArgumentException(key + " must be finite");
        return result;
    }
    private static int integer(JsonObject json, String key) {
        double value = number(json, key);
        if (value != (int) value) throw new IllegalArgumentException(key + " must be an integer");
        return (int) value;
    }
    private static ResourceLocation id(String value) {
        var result = ResourceLocation.tryParse(value);
        if (result == null || !value.contains(":")) throw new IllegalArgumentException("Expected namespaced blessing ID: " + value);
        return result;
    }
    private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
    }
}
