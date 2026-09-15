package com.sande.mythictrpg.interaction.rule;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class InteractionRuleSchema {
    private static final int MAX_BINDINGS = 256;
    private static final int MAX_ABSOLUTE_SCORE = 1_000_000;
    private static final Set<String> ROOT_FIELDS = Set.of("schema_version", "signal", "bindings");
    private static final Set<String> BINDING_FIELDS = Set.of("god", "god_category", "score", "reason");

    private InteractionRuleSchema() {
    }

    public static InteractionRule parse(ResourceLocation ruleId, JsonObject json) {
        rejectUnknownFields(json, ROOT_FIELDS, "interaction rule");
        int version = requiredInt(json, "schema_version");
        if (version != InteractionRule.CURRENT_SCHEMA_VERSION) {
            if (version > InteractionRule.CURRENT_SCHEMA_VERSION) {
                throw new JsonParseException("Interaction rule " + ruleId + " uses unknown future schema_version "
                        + version + " (supported: " + InteractionRule.CURRENT_SCHEMA_VERSION + ")");
            }
            throw new JsonParseException("Interaction rule " + ruleId
                    + " uses unsupported schema_version " + version);
        }
        ResourceLocation signal = requiredId(json, "signal");
        JsonElement bindingsValue = required(json, "bindings");
        if (!bindingsValue.isJsonArray()) {
            throw new JsonParseException("Field 'bindings' must be an array");
        }
        JsonArray array = bindingsValue.getAsJsonArray();
        if (array.isEmpty() || array.size() > MAX_BINDINGS) {
            throw new JsonParseException("Field 'bindings' must contain 1.." + MAX_BINDINGS + " entries");
        }

        List<InteractionRuleBinding> bindings = new ArrayList<>();
        Set<String> duplicateKeys = new HashSet<>();
        for (int index = 0; index < array.size(); index++) {
            JsonElement value = array.get(index);
            if (!value.isJsonObject()) {
                throw new JsonParseException("Binding at index " + index + " must be an object");
            }
            InteractionRuleBinding binding = parseBinding(value.getAsJsonObject(), index);
            String duplicateKey = binding.godId().map(id -> "god:" + id)
                    .orElseGet(() -> "category:" + binding.godCategoryId().orElseThrow())
                    + "|" + binding.reason();
            if (!duplicateKeys.add(duplicateKey)) {
                throw new JsonParseException("Duplicate binding target/reason at index " + index);
            }
            bindings.add(binding);
        }
        return new InteractionRule(version, signal, bindings);
    }

    private static InteractionRuleBinding parseBinding(JsonObject json, int index) {
        rejectUnknownFields(json, BINDING_FIELDS, "binding at index " + index);
        boolean hasGod = json.has("god") && !json.get("god").isJsonNull();
        boolean hasCategory = json.has("god_category") && !json.get("god_category").isJsonNull();
        if (hasGod == hasCategory) {
            throw new JsonParseException("Binding at index " + index
                    + " must contain exactly one of 'god' and 'god_category'");
        }
        int score = requiredInt(json, "score");
        if (Math.abs((long) score) > MAX_ABSOLUTE_SCORE) {
            throw new JsonParseException("Binding score at index " + index + " exceeds +/-"
                    + MAX_ABSOLUTE_SCORE);
        }
        return new InteractionRuleBinding(
                hasGod ? Optional.of(requiredId(json, "god")) : Optional.empty(),
                hasCategory ? Optional.of(requiredId(json, "god_category")) : Optional.empty(),
                score,
                requiredId(json, "reason"));
    }

    private static void rejectUnknownFields(JsonObject json, Set<String> allowed, String location) {
        json.keySet().forEach(field -> {
            if (!allowed.contains(field)) {
                throw new JsonParseException("Unknown field '" + field + "' in " + location);
            }
        });
    }

    private static JsonElement required(JsonObject json, String field) {
        JsonElement value = json.get(field);
        if (value == null || value.isJsonNull()) {
            throw new JsonParseException("Missing required field '" + field + "'");
        }
        return value;
    }

    private static int requiredInt(JsonObject json, String field) {
        JsonElement value = required(json, field);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new JsonParseException("Field '" + field + "' must be an integer");
        }
        try {
            int parsed = value.getAsInt();
            if (value.getAsDouble() != parsed) {
                throw new JsonParseException("Field '" + field + "' must be an integer");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new JsonParseException("Field '" + field + "' must be an integer", exception);
        }
    }

    private static ResourceLocation requiredId(JsonObject json, String field) {
        JsonElement value = required(json, field);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new JsonParseException("Field '" + field + "' must be a namespaced ID string");
        }
        String raw = value.getAsString();
        ResourceLocation id = ResourceLocation.tryParse(raw);
        if (id == null || !raw.contains(":")) {
            throw new JsonParseException("Field '" + field + "' must be a valid namespaced ID");
        }
        return id;
    }
}
