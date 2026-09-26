package com.sande.mythictrpg.ai;

import com.google.gson.*;
import com.sande.mythictrpg.ai.api.RoomConversationEngine.Request;
import com.sande.mythictrpg.ai.api.RoomEvidenceReference;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Read-only bridge to audience-filtered static content. Raw loreAvailableTo is NOT a prompt-safe fallback. */
final class RoomKnowledgeContext {
    static final String EVIDENCE_KIND = "CONTENT_DISCLOSURE_V1";
    private static final Gson JSON = new Gson();
    private static final String REGISTRY = "com.sande.mythaiaicontent.content.AiContentRegistry";
    private RoomKnowledgeContext() { }

    static AiTestContentRegistryBridge.ContentSnapshot load(Request request) {
        return load(request.speakerGodId(), request.speakerState().relationshipTier(), request.publicRoom(),
                request.godIds(), request.audiencePlayerIds());
    }

    /** Pair-specific relation reference, but NEVER a pair-sized substitute for the actual disclosure audience. */
    static List<String> directionalRelationTags(Request request, ResourceLocation targetGod) {
        if (targetGod.equals(request.speakerGodId()) || !request.godIds().contains(targetGod))
            throw new IllegalArgumentException("Relation target is not another current participant");
        return load(request.speakerGodId(), request.speakerState().relationshipTier(), request.publicRoom(),
                request.godIds(), request.audiencePlayerIds(), List.of(request.speakerGodId(), targetGod)).socialRelationTags();
    }

    static List<String> directionalRelations(ResourceLocation speaker, String tier, boolean publicRoom,
            List<ResourceLocation> allGods, Set<UUID> players, List<ResourceLocation> relationReferenceGodIds) {
        if (!allGods.contains(speaker) || !allGods.containsAll(relationReferenceGodIds))
            throw new IllegalArgumentException("Directional relation targets must be current participants");
        return load(speaker, tier, publicRoom, allGods, players, relationReferenceGodIds).socialRelationTags();
    }

    /** Also supports conservative internal split reasoning, provided callers pass its complete potential audience. */
    static AiTestContentRegistryBridge.ContentSnapshot load(ResourceLocation speaker, String relationshipTier,
            boolean publicRoom, List<ResourceLocation> gods, Set<UUID> players) {
        return load(speaker, relationshipTier, publicRoom, gods, players, gods);
    }

    private static AiTestContentRegistryBridge.ContentSnapshot load(ResourceLocation speaker, String relationshipTier,
            boolean publicRoom, List<ResourceLocation> gods, Set<UUID> players, List<ResourceLocation> relationTargets) {
        try {
            if (!ModList.get().isLoaded("mythaiaicontent")) throw new IllegalStateException("Content registry missing");
            var type = Class.forName(REGISTRY);
            Object registry = type.getField("INSTANCE").get(null);
            Object value = type.getMethod("audienceContentFor", ResourceLocation.class, String.class,
                    boolean.class, List.class, Set.class, List.class)
                    .invoke(registry, speaker, relationshipTier, publicRoom, gods, players, relationTargets);
            if (!(value instanceof Optional<?> resolved) || resolved.isEmpty())
                throw new IllegalStateException("Audience-safe content profile missing for " + speaker);
            return convert(resolved.get());
        } catch (ReflectiveOperationException failure) {
            // Older registries cannot prove field/example disclosure. Do not silently re-enable their raw profile.
            throw new IllegalStateException("Audience-aware content registry contract unavailable", failure);
        }
    }

    static RoomEvidenceReference evidence(Request request, AiTestContentRegistryBridge.ContentSnapshot content) {
        return evidence(request.speakerGodId(), request.speakerState().relationshipTier(), content, request.godIds());
    }

    static RoomEvidenceReference evidence(ResourceLocation speaker, String relationshipTier,
            AiTestContentRegistryBridge.ContentSnapshot content) {
        return evidence(speaker, relationshipTier, content, List.of(speaker));
    }

    static RoomEvidenceReference evidence(ResourceLocation speaker, String relationshipTier,
            AiTestContentRegistryBridge.ContentSnapshot content, List<ResourceLocation> relationTargets) {
        var data = new JsonObject();
        data.addProperty("speakerGodId", speaker.toString());
        data.addProperty("relationshipTier", relationshipTier);
        data.addProperty("sha256", fingerprint(content));
        data.add("relationTargets", JSON.toJsonTree(relationTargets.stream().map(ResourceLocation::toString).toList()));
        return new RoomEvidenceReference(EVIDENCE_KIND, JSON.toJson(data));
    }

    static boolean validEvidence(RoomEvidenceReference reference, boolean publicRoom,
            List<ResourceLocation> gods, Set<UUID> players) {
        return validEvidence(reference, publicRoom, gods, players, RoomKnowledgeContext::load);
    }

    /** A different God's recall still checks the ORIGINAL source God's knowledge and the NEW complete audience. */
    static boolean validEvidence(RoomEvidenceReference reference, boolean publicRoom, List<ResourceLocation> gods,
            Set<UUID> players, Lookup lookup) {
        try {
            if (reference == null || !EVIDENCE_KIND.equals(reference.kind()) || reference.payload().length() > 16384)
                return false;
            var value = JsonParser.parseString(reference.payload());
            if (!value.isJsonObject()) return false;
            var data = value.getAsJsonObject();
            if (!data.keySet().equals(Set.of("speakerGodId", "relationshipTier", "sha256", "relationTargets"))) return false;
            for (String key : List.of("speakerGodId", "relationshipTier", "sha256"))
                if (!data.get(key).isJsonPrimitive() || !data.getAsJsonPrimitive(key).isString()) return false;
            var speaker = ResourceLocation.parse(data.get("speakerGodId").getAsString());
            String tier = data.get("relationshipTier").getAsString();
            String digest = data.get("sha256").getAsString();
            if (!digest.matches("[0-9a-f]{64}")) return false;
            if (!data.get("relationTargets").isJsonArray()) return false;
            var relationTargets = new ArrayList<ResourceLocation>();
            for (var entry : data.getAsJsonArray("relationTargets")) {
                if (!entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString()) return false;
                relationTargets.add(ResourceLocation.parse(entry.getAsString()));
            }
            if (relationTargets.isEmpty() || relationTargets.size() > 16 || !relationTargets.contains(speaker)
                    || relationTargets.stream().distinct().count() != relationTargets.size()) return false;
            return digest.equals(fingerprint(lookup.load(speaker, tier, publicRoom, List.copyOf(gods), Set.copyOf(players),
                    List.copyOf(relationTargets))));
        } catch (RuntimeException unavailableOrChanged) {
            return false;
        }
    }

    /** Reload counter is intentionally excluded: it is not stable across restart and cannot invalidate persistence. */
    static String fingerprint(AiTestContentRegistryBridge.ContentSnapshot content) {
        var object = new JsonObject();
        object.add("profile", JSON.toJsonTree(content.profile()));
        object.add("lore", JSON.toJsonTree(content.lore()));
        object.add("examples", JSON.toJsonTree(content.examples()));
        object.add("relationshipGuidance", JSON.toJsonTree(content.relationshipGuidance()));
        object.add("socialRelationTags", JSON.toJsonTree(content.socialRelationTags()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(JSON.toJson(canonical(object)).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static JsonElement canonical(JsonElement value) {
        if (value.isJsonObject()) {
            var result = new JsonObject();
            value.getAsJsonObject().keySet().stream().sorted()
                    .forEach(key -> result.add(key, canonical(value.getAsJsonObject().get(key))));
            return result;
        }
        if (value.isJsonArray()) {
            var result = new JsonArray();
            value.getAsJsonArray().forEach(item -> result.add(canonical(item)));
            return result;
        }
        return value;
    }

    private static AiTestContentRegistryBridge.ContentSnapshot convert(Object value) {
        Object p = read(value, "profile");
        var profile = new AiTestContentRegistryBridge.Profile(text(read(p, "displayName")), text(read(p, "identity")),
                text(read(p, "description")), strings(read(p, "personality")), strings(read(p, "values")),
                strings(read(p, "speechStyles")), strings(read(p, "dialogueGuidelines")),
                guidance(read(p, "situationGuidelines")), guidance(read(p, "repetitionGuidelines")),
                strings(read(p, "restrictions")), strings(read(p, "characterTags")));
        var lore = new ArrayList<AiTestContentRegistryBridge.Lore>();
        for (Object entry : list(read(value, "lore"))) {
            var levels = new ArrayList<String>();
            for (Object level : list(read(entry, "accessibleLevels")))
                levels.add("L" + ((Number) read(level, "level")).intValue() + ": " + text(read(level, "content")));
            lore.add(new AiTestContentRegistryBridge.Lore(text(read(entry, "id")), text(read(entry, "title")),
                    text(read(entry, "secrecy")), ((Number) read(entry, "knowledgeLevel")).intValue(), List.copyOf(levels)));
        }
        var examples = new ArrayList<AiTestContentRegistryBridge.Example>();
        for (Object entry : list(read(value, "examples"))) {
            var turns = new ArrayList<AiTestContentRegistryBridge.ExampleTurn>();
            for (Object turn : list(read(entry, "dialogue")))
                turns.add(new AiTestContentRegistryBridge.ExampleTurn(text(read(turn, "role")), text(read(turn, "text"))));
            examples.add(new AiTestContentRegistryBridge.Example(text(read(entry, "id")),
                    strings(read(entry, "tags")).stream().sorted().toList(), List.copyOf(turns)));
        }
        return new AiTestContentRegistryBridge.ContentSnapshot(profile, List.copyOf(lore), List.copyOf(examples),
                strings(read(value, "relationshipGuidance")), strings(read(value, "socialRelationTags")),
                ((Number) read(value, "generation")).longValue());
    }
    private static Object read(Object target, String method) {
        try { return target.getClass().getMethod(method).invoke(target); }
        catch (InvocationTargetException failure) { throw new IllegalStateException("Content projection rejected", failure.getCause()); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException("Content projection unavailable", failure); }
    }
    private static List<?> list(Object value) {
        if (!(value instanceof Collection<?> collection)) throw new IllegalStateException("Invalid content list");
        return List.copyOf(collection);
    }
    private static List<String> strings(Object value) { return list(value).stream().map(RoomKnowledgeContext::text).toList(); }
    private static String text(Object value) {
        return Objects.requireNonNull(value, "content text").toString();
    }
    private static Map<String, List<String>> guidance(Object value) {
        if (!(value instanceof Map<?, ?> map)) throw new IllegalStateException("Invalid content guidance");
        var result = new TreeMap<String, List<String>>();
        map.forEach((key, values) -> result.put(text(key), strings(values)));
        return Map.copyOf(result);
    }

    @FunctionalInterface interface Lookup {
        AiTestContentRegistryBridge.ContentSnapshot load(ResourceLocation god, String tier, boolean publicRoom,
                List<ResourceLocation> gods, Set<UUID> players, List<ResourceLocation> relationTargets);
    }
}
