package com.sande.mythaiaicontent.content;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Static quest plus the reusable list and 0..100 progress track it belongs to. */
public record QuestCandidateDefinition(ResourceLocation questListId, ResourceLocation progressTrackId,
        QuestDefinition quest) {
    public QuestCandidateDefinition {
        Objects.requireNonNull(questListId, "questListId");
        Objects.requireNonNull(progressTrackId, "progressTrackId");
        Objects.requireNonNull(quest, "quest");
    }

    public String promptSummary() {
        return "questListId=" + questListId + ", progressTrackId=" + progressTrackId + ", "
                + quest.promptSummary();
    }

    /** Stable item-specific author evidence, independent of reload counters and live quest availability. */
    public String fingerprint() {
        var json = new Gson();
        var value = new JsonObject();
        value.addProperty("questListId", questListId.toString());
        value.addProperty("progressTrackId", progressTrackId.toString());
        value.addProperty("questId", quest.questId().toString());
        value.addProperty("title", quest.title()); value.addProperty("content", quest.content());
        value.add("objectives", json.toJsonTree(quest.objectives()));
        value.add("rewards", json.toJsonTree(quest.rewards()));
        value.add("acceptanceConditions", json.toJsonTree(quest.acceptanceConditions()));
        value.addProperty("progressOnClear", quest.progressOnClear());
        value.addProperty("mode", quest.disclosure().mode().name());
        value.add("allowedGodIds", json.toJsonTree(quest.disclosure().allowedGodIds().stream()
                .map(ResourceLocation::toString).sorted().toList()));
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(json.toJson(canonical(value)).getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static JsonElement canonical(JsonElement value) {
        if (value.isJsonObject()) {
            var result = new JsonObject();
            value.getAsJsonObject().keySet().stream().sorted().forEach(key -> result.add(key, canonical(value.getAsJsonObject().get(key))));
            return result;
        }
        if (value.isJsonArray()) {
            var result = new JsonArray(); value.getAsJsonArray().forEach(item -> result.add(canonical(item))); return result;
        }
        return value;
    }
}
