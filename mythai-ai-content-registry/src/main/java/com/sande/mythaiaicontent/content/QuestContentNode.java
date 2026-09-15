package com.sande.mythaiaicontent.content;

import java.util.LinkedHashMap;
import java.util.Map;

/** One typed, static quest objective, reward, or acceptance condition. */
public record QuestContentNode(String type, String description, Map<String, String> parameters) {
    public QuestContentNode {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("Quest content node type must not be blank");
        }
        type = type.trim();
        description = description == null ? "" : description.trim();
        Map<String, String> copy = new LinkedHashMap<>();
        if (parameters != null) {
            parameters.forEach((key, value) -> {
                if (key == null || key.isBlank() || value == null) {
                    throw new IllegalArgumentException("Quest content node parameters must use non-blank keys and values");
                }
                copy.put(key.trim(), value.trim());
            });
        }
        parameters = Map.copyOf(copy);
    }
}
