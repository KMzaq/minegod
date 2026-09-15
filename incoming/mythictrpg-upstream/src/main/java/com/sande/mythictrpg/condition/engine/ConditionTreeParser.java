package com.sande.mythictrpg.condition.engine;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.sande.mythictrpg.condition.api.ConditionNode;
import com.sande.mythictrpg.condition.registry.ConditionType;
import com.sande.mythictrpg.condition.registry.ConditionTypeRegistry;
import net.minecraft.resources.ResourceLocation;

public final class ConditionTreeParser {
    public static final int MAX_DEPTH = 32;
    public static final int MAX_NODES = 256;

    private final ConditionTypeRegistry registry;

    public ConditionTreeParser(ConditionTypeRegistry registry) {
        this.registry = registry;
    }

    public ConditionNode parse(JsonElement element) {
        return parseNode(element, 1, new ParseState());
    }

    private ConditionNode parseNode(JsonElement element, int depth, ParseState state) {
        if (depth > MAX_DEPTH) {
            throw new JsonParseException("Condition tree exceeds maximum depth " + MAX_DEPTH);
        }
        if (++state.nodeCount > MAX_NODES) {
            throw new JsonParseException("Condition tree exceeds maximum node count " + MAX_NODES);
        }
        if (!element.isJsonObject()) {
            throw new JsonParseException("Condition node must be a JSON object");
        }

        JsonObject json = element.getAsJsonObject();
        JsonElement typeValue = json.get("type");
        if (typeValue == null || !typeValue.isJsonPrimitive() || !typeValue.getAsJsonPrimitive().isString()) {
            throw new JsonParseException("Condition node requires string field 'type'");
        }
        ResourceLocation typeId = ResourceLocation.tryParse(typeValue.getAsString());
        if (typeId == null || !typeValue.getAsString().contains(":")) {
            throw new JsonParseException("Invalid namespaced condition type: " + typeValue.getAsString());
        }

        ConditionType<? extends ConditionNode> type = registry.find(typeId)
                .orElseThrow(() -> new JsonParseException("Unknown condition type: " + typeId));
        ConditionNode node = type.decode(json, child -> parseNode(child, depth + 1, state));
        if (!node.typeId().equals(typeId)) {
            throw new JsonParseException("Condition codec returned mismatched type for " + typeId);
        }
        node.scope().ifPresent(scope -> {
            if (!type.allowedScopes().contains(scope)) {
                throw new JsonParseException("Scope " + scope.serializedName()
                        + " is not allowed for condition type " + typeId);
            }
        });
        return node;
    }

    private static final class ParseState {
        private int nodeCount;
    }
}
