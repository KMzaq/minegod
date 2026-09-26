package com.sande.mythictrpg.ai;

import com.google.gson.*;
import com.sande.mythictrpg.ai.api.RoomEvidenceReference;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;
import java.util.*;

/** Item-scoped static quest text provenance, not proof that a quest was accepted, completed, or still offerable. */
final class RoomQuestKnowledge {
    static final String EVIDENCE_KIND = "QUEST_CONTENT_DISCLOSURE_V1";
    private static final Gson JSON = new Gson();
    private static final String REGISTRY = "com.sande.mythaiaicontent.content.AiContentRegistry";
    private RoomQuestKnowledge() { }

    static RoomEvidenceReference evidence(ResourceLocation source, ResourceLocation quest, String fingerprint) {
        if (!fingerprint.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid quest fingerprint");
        var value = new JsonObject();
        value.addProperty("sourceGodId", source.toString()); value.addProperty("questId", quest.toString());
        value.addProperty("sha256", fingerprint);
        return new RoomEvidenceReference(EVIDENCE_KIND, JSON.toJson(value));
    }

    static boolean validEvidence(RoomEvidenceReference reference, boolean publicRoom,
            List<ResourceLocation> gods, Set<UUID> players) {
        return validEvidence(reference, publicRoom, gods, players, RoomQuestKnowledge::currentFingerprint);
    }

    /** Original author ownership plus CURRENT full audience; source God need not still participate. */
    static boolean validEvidence(RoomEvidenceReference reference, boolean publicRoom, List<ResourceLocation> gods,
            Set<UUID> players, Lookup lookup) {
        try {
            if (reference == null || !EVIDENCE_KIND.equals(reference.kind()) || reference.payload().length() > 4096) return false;
            var value = JsonParser.parseString(reference.payload()).getAsJsonObject();
            if (!value.keySet().equals(Set.of("sourceGodId", "questId", "sha256"))) return false;
            for (var key : value.keySet())
                if (!value.get(key).isJsonPrimitive() || !value.getAsJsonPrimitive(key).isString()) return false;
            var source = ResourceLocation.parse(value.get("sourceGodId").getAsString());
            var quest = ResourceLocation.parse(value.get("questId").getAsString());
            var fingerprint = value.get("sha256").getAsString();
            return fingerprint.matches("[0-9a-f]{64}") && lookup.current(source, quest, publicRoom,
                    List.copyOf(gods), Set.copyOf(players)).filter(fingerprint::equals).isPresent();
        } catch (RuntimeException unavailable) { return false; }
    }

    private static Optional<String> currentFingerprint(ResourceLocation source, ResourceLocation quest,
            boolean publicRoom, List<ResourceLocation> gods, Set<UUID> players) {
        try {
            if (!ModList.get().isLoaded("mythaiaicontent")) return Optional.empty();
            var type = Class.forName(REGISTRY); var registry = type.getField("INSTANCE").get(null);
            var raw = type.getMethod("audienceQuestCandidatesFor", ResourceLocation.class, boolean.class, List.class, Set.class)
                    .invoke(registry, source, publicRoom, gods, players);
            if (!(raw instanceof List<?> candidates)) return Optional.empty();
            for (var candidate : candidates) {
                var definition = candidate.getClass().getMethod("quest").invoke(candidate);
                if (quest.equals(definition.getClass().getMethod("questId").invoke(definition)))
                    return Optional.of(String.valueOf(candidate.getClass().getMethod("fingerprint").invoke(candidate)));
            }
            return Optional.empty();
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Audience-safe quest registry unavailable", failure);
        }
    }

    @FunctionalInterface interface Lookup {
        Optional<String> current(ResourceLocation source, ResourceLocation quest, boolean publicRoom,
                List<ResourceLocation> gods, Set<UUID> players);
    }
}
