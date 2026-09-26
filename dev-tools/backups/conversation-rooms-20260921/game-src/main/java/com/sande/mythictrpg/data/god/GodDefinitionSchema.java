package com.sande.mythictrpg.data.god;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.mojang.serialization.JsonOps;
import com.sande.mythictrpg.condition.api.ConditionNode;
import com.sande.mythictrpg.condition.engine.ConditionTreeParser;
import com.sande.mythictrpg.condition.registry.ConditionTypeRegistry;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

public final class GodDefinitionSchema {
    public static final int CURRENT_VERSION = 2;
    private static final ConditionTreeParser CONDITION_PARSER =
            new ConditionTreeParser(ConditionTypeRegistry.INSTANCE);
    private static final Set<String> ALLOWED_FIELDS = Set.of(
            "schema_version", "display_name", "origin", "faction", "categories",
            "unlock_conditions", "appearance_conditions", "identification_conditions"
    );

    private GodDefinitionSchema() {
    }

    public static GodDefinition parse(ResourceLocation id, JsonObject json) {
        json.keySet().forEach(field -> {
            if (!ALLOWED_FIELDS.contains(field)) {
                throw new JsonParseException("Unknown God field '" + field + "'");
            }
        });

        int sourceVersion = requiredInt(json, "schema_version");
        if (sourceVersion != 1 && sourceVersion != CURRENT_VERSION) {
            throw unsupportedVersion(id, sourceVersion);
        }

        Component displayName = ComponentSerialization.CODEC
                .parse(JsonOps.INSTANCE, required(json, "display_name"))
                .getOrThrow(message -> new JsonParseException("Invalid display_name: " + message));
        return new GodDefinition(
                CURRENT_VERSION,
                displayName,
                requiredId(json, "origin"),
                requiredId(json, "faction"),
                requiredIdSet(json, "categories"),
                condition(json, "unlock_conditions", sourceVersion),
                condition(json, "appearance_conditions", sourceVersion),
                condition(json, "identification_conditions", sourceVersion)
        );
    }

    private static Optional<ConditionNode> condition(JsonObject json, String field, int sourceVersion) {
        JsonElement value = json.get(field);
        if (value == null || value.isJsonNull()) {
            return Optional.empty();
        }
        if (sourceVersion == 1) {
            if (!value.isJsonArray()) {
                throw new JsonParseException("Schema 1 field '" + field + "' must be an array");
            }
            if (!value.getAsJsonArray().isEmpty()) {
                throw new JsonParseException("Cannot migrate non-empty schema 1 field '" + field
                        + "' because its evaluation semantics were never defined");
            }
            return Optional.empty();
        }
        if (!value.isJsonObject()) {
            throw new JsonParseException("Schema 2 field '" + field + "' must be a condition object");
        }
        return Optional.of(CONDITION_PARSER.parse(value));
    }

    private static JsonParseException unsupportedVersion(ResourceLocation id, int version) {
        if (version > CURRENT_VERSION) {
            return new JsonParseException("God " + id + " uses unknown future schema_version " + version
                    + " (supported: " + CURRENT_VERSION + ")");
        }
        return new JsonParseException("God " + id + " uses unsupported schema_version " + version);
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
        ResourceLocation id = ResourceLocation.tryParse(value.getAsString());
        if (id == null || !value.getAsString().contains(":")) {
            throw new JsonParseException("Field '" + field + "' must be a valid namespaced ID");
        }
        return id;
    }

    private static Set<ResourceLocation> requiredIdSet(JsonObject json, String field) {
        JsonElement value = required(json, field);
        if (!value.isJsonArray()) {
            throw new JsonParseException("Field '" + field + "' must be an array");
        }
        JsonArray array = value.getAsJsonArray();
        if (array.isEmpty()) {
            throw new JsonParseException("Field '" + field + "' must not be empty");
        }

        Set<ResourceLocation> result = new LinkedHashSet<>();
        for (int index = 0; index < array.size(); index++) {
            JsonElement item = array.get(index);
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) {
                throw new JsonParseException("Field '" + field + "' must contain namespaced ID strings");
            }
            String valueString = item.getAsString();
            ResourceLocation category = ResourceLocation.tryParse(valueString);
            if (category == null || !valueString.contains(":")) {
                throw new JsonParseException("Invalid namespaced ID in field '" + field + "': " + valueString);
            }
            if (!result.add(category)) {
                throw new JsonParseException("Duplicate ID in field '" + field + "': " + category);
            }
        }
        return Set.copyOf(result);
    }
}
