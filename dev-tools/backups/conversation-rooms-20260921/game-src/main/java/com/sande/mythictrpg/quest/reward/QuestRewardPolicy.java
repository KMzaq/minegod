package com.sande.mythictrpg.quest.reward;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Authored composition of a God default tier, direct rewards and one optional choice. */
public record QuestRewardPolicy(Mode mode, int baseTier, String selectionTitle,
        List<RewardEntry> automaticRewards, List<RewardChoiceOption> choices) {
    public QuestRewardPolicy {
        Objects.requireNonNull(mode, "mode");
        selectionTitle = selectionTitle == null ? "" : selectionTitle.trim();
        if (selectionTitle.codePointCount(0, selectionTitle.length()) > 120) {
            throw new IllegalArgumentException("selectionTitle may contain at most 120 code points");
        }
        automaticRewards = List.copyOf(Objects.requireNonNull(automaticRewards, "automaticRewards"));
        choices = List.copyOf(Objects.requireNonNull(choices, "choices"));
        if (automaticRewards.size() > 32) {
            throw new IllegalArgumentException("A quest may contain at most 32 automatic direct rewards");
        }
        if (!choices.isEmpty() && (choices.size() < 2 || choices.size() > 6)) {
            throw new IllegalArgumentException("Reward choice must contain 2..6 options");
        }
        Set<ResourceLocation> optionIds = new LinkedHashSet<>();
        for (RewardChoiceOption choice : choices) {
            if (!optionIds.add(choice.optionId())) {
                throw new IllegalArgumentException("Duplicate reward option ID " + choice.optionId());
            }
        }
        if (mode == Mode.ADD && (baseTier < 1 || baseTier > 100)) {
            throw new IllegalArgumentException("ADD reward policy requires baseTier 1..100");
        }
        if (mode == Mode.REPLACE && baseTier != 0) {
            throw new IllegalArgumentException("REPLACE reward policy must not define baseTier");
        }
        if (mode == Mode.REPLACE && automaticRewards.isEmpty() && choices.isEmpty()) {
            throw new IllegalArgumentException("REPLACE reward policy must define a reward");
        }
    }

    public static QuestRewardPolicy parse(JsonObject json, String location) {
        rejectUnknown(json, Set.of("mode", "baseTier", "selectionTitle",
                "automaticRewards", "choices"), location);
        Mode mode;
        try {
            mode = Mode.valueOf(string(json, "mode", location).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(location + ".mode must be ADD or REPLACE");
        }
        int baseTier = json.has("baseTier") ? integer(json, "baseTier", location) : 0;
        String title = json.has("selectionTitle") ? string(json, "selectionTitle", location) : "보상을 선택하세요";
        List<RewardEntry> automatic = parseRewards(json, "automaticRewards", location, true);
        List<RewardChoiceOption> choices = new ArrayList<>();
        if (json.has("choices")) {
            if (!json.get("choices").isJsonArray()) {
                throw new IllegalArgumentException(location + ".choices must be an array");
            }
            int index = 0;
            for (JsonElement element : json.getAsJsonArray("choices")) {
                if (!element.isJsonObject()) {
                    throw new IllegalArgumentException(location + ".choices[" + index + "] must be an object");
                }
                JsonObject option = element.getAsJsonObject();
                String optionLocation = location + ".choices[" + index + "]";
                rejectUnknown(option, Set.of("optionId", "displayName", "rewards"), optionLocation);
                choices.add(new RewardChoiceOption(
                        id(string(option, "optionId", optionLocation), optionLocation + ".optionId"),
                        string(option, "displayName", optionLocation),
                        parseRewards(option, "rewards", optionLocation, false)));
                index++;
            }
        }
        return new QuestRewardPolicy(mode, baseTier, title, automatic, choices);
    }

    private static List<RewardEntry> parseRewards(JsonObject json, String key,
            String location, boolean optional) {
        if (!json.has(key)) {
            if (optional) {
                return List.of();
            }
            throw new IllegalArgumentException("Missing array '" + key + "' at " + location);
        }
        if (!json.get(key).isJsonArray()) {
            throw new IllegalArgumentException(location + "." + key + " must be an array");
        }
        List<RewardEntry> rewards = new ArrayList<>();
        int index = 0;
        for (JsonElement element : json.getAsJsonArray(key)) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException(location + "." + key + "[" + index + "] must be an object");
            }
            rewards.add(RewardEntryCodec.parse(element.getAsJsonObject(),
                    location + "." + key + "[" + index + "]"));
            index++;
        }
        return List.copyOf(rewards);
    }

    private static void rejectUnknown(JsonObject json, Set<String> allowed, String location) {
        json.keySet().forEach(field -> {
            if (!allowed.contains(field)) {
                throw new IllegalArgumentException("Unknown field '" + field + "' at " + location);
            }
        });
    }

    private static String string(JsonObject json, String key, String location) {
        if (!json.has(key) || !json.get(key).isJsonPrimitive()
                || !json.getAsJsonPrimitive(key).isString()) {
            throw new IllegalArgumentException("Missing string '" + key + "' at " + location);
        }
        String value = json.get(key).getAsString().trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Blank string '" + key + "' at " + location);
        }
        return value;
    }

    private static int integer(JsonObject json, String key, String location) {
        if (!json.has(key) || !json.get(key).isJsonPrimitive()
                || !json.getAsJsonPrimitive(key).isNumber()) {
            throw new IllegalArgumentException("Missing integer '" + key + "' at " + location);
        }
        double raw = json.get(key).getAsDouble();
        int value = json.get(key).getAsInt();
        if (!Double.isFinite(raw) || raw != value) {
            throw new IllegalArgumentException("Field '" + key + "' must be an integer at " + location);
        }
        return value;
    }

    private static ResourceLocation id(String value, String field) {
        ResourceLocation id = ResourceLocation.tryParse(value);
        if (id == null || !value.contains(":")) {
            throw new IllegalArgumentException("Invalid namespaced ID in " + field + ": " + value);
        }
        return id;
    }

    public enum Mode {
        ADD,
        REPLACE
    }
}
