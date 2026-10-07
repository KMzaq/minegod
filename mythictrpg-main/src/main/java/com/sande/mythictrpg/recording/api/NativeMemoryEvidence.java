package com.sande.mythictrpg.recording.api;

import com.google.gson.*;
import com.sande.mythictrpg.ai.api.RoomEvidenceReference;
import com.sande.mythictrpg.recording.api.RecordingRecords.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Immutable source-level native speech provenance. Syntax and hashes never grant read/publication authority.
 * A matching game-issued manifest row and current source/receipt/disclosure validation are mandatory. */
public final class NativeMemoryEvidence {
    public static final String KIND = "RECORDED_NATIVE_MEMORY_V1";
    public static final int VERSION = 1, MAX_PAGES = 8, MAX_ROOTS = 64, MAX_DEPENDENCIES = 64;
    public static final int MAX_AUDIENCE = 256, MAX_GODS = 16, MAX_REFERENCE_BYTES = 1024, MAX_MANIFEST_BYTES = 65536;
    private static final Gson JSON = new Gson();
    private NativeMemoryEvidence() { }

    /** Compact pointer only; does not prove that any stored issuance exists. */
    public record Reference(int version, UUID worldId, UUID datasetId, UUID sealId, String manifestHash) {
        public Reference {
            if (version != VERSION) throw invalid();
            Objects.requireNonNull(worldId); Objects.requireNonNull(datasetId); Objects.requireNonNull(sealId); digest(manifestHash);
        }
    }
    /** The recipient God's actual native source, knowledge and GAME_HEARD delivery bindings. */
    public record SpeechDependency(UUID messageId, SourceRef source, UUID knowledgeReceiptId, String receiptHash,
            UUID deliveryReceiptId, String deliveryHash, String bodyHash, String disclosureHash, Set<UUID> parentMessageIds) {
        public SpeechDependency {
            Objects.requireNonNull(messageId); Objects.requireNonNull(source); Objects.requireNonNull(knowledgeReceiptId);
            Objects.requireNonNull(deliveryReceiptId); digest(receiptHash); digest(deliveryHash); digest(bodyHash); digest(disclosureHash);
            parentMessageIds = Set.copyOf(parentMessageIds);
            if (!source.owner().equals("room-publication-v2") || source.revision() != 1
                    || !Set.of(SourceKind.DIALOGUE_DIRECT, SourceKind.DERIVED_SPEECH).contains(source.kind())
                    || !source.sourceId().equals(messageId.toString()) || parentMessageIds.size() > MAX_DEPENDENCIES
                    || parentMessageIds.contains(messageId)) throw invalid();
        }
    }
    /** Original issuance scope is recorded; later history must be revalidated against its new live scope. */
    public record Manifest(int version, UUID worldId, UUID datasetId, long originalWatermark,
            UUID roomId, long revision, UUID turnId, UUID playerId, String recipientGodId, boolean publicRoom,
            String recordingPolicy, String memoryMode, Set<ActorRef> audience, Set<UUID> roots, List<SpeechDependency> dependencies) {
        public Manifest {
            Objects.requireNonNull(worldId); Objects.requireNonNull(datasetId); Objects.requireNonNull(roomId);
            Objects.requireNonNull(turnId); Objects.requireNonNull(playerId);
            var recipient = new ActorRef(ActorKind.GOD, recipientGodId);
            audience = Set.copyOf(audience); roots = Set.copyOf(roots);
            dependencies = List.copyOf(dependencies).stream().sorted(Comparator.comparing(d -> d.messageId().toString())).toList();
            if (version != VERSION || originalWatermark < 1 || revision < 0
                    || !Set.of("STANDARD", "TEST_RECORDING").contains(recordingPolicy)
                    || !Set.of("PERSONAL", "RUMOR_TEST").contains(memoryMode)
                    || audience.size() < 2 || audience.size() > MAX_AUDIENCE || !audience.contains(recipient)
                    || !audience.contains(new ActorRef(ActorKind.PLAYER, playerId.toString()))
                    || audience.stream().filter(a -> a.kind() == ActorKind.GOD).count() > MAX_GODS
                    || roots.isEmpty() || roots.size() > MAX_ROOTS || dependencies.isEmpty() || dependencies.size() > MAX_DEPENDENCIES) throw invalid();
            var byMessage = new HashMap<UUID,SpeechDependency>(); var knowledge = new HashSet<UUID>(); var delivery = new HashSet<UUID>();
            for (var dependency : dependencies) {
                if (!dependency.source().worldId().equals(worldId) || !dependency.source().datasetId().equals(datasetId)
                        || byMessage.put(dependency.messageId(), dependency) != null || !knowledge.add(dependency.knowledgeReceiptId())
                        || !delivery.add(dependency.deliveryReceiptId())) throw invalid();
            }
            if (!byMessage.keySet().containsAll(roots)
                    || dependencies.stream().anyMatch(d -> !byMessage.keySet().containsAll(d.parentMessageIds()))) throw invalid();
            var colors = new HashMap<UUID,Integer>();
            for (UUID root : roots) visit(root, byMessage, colors);
            if (colors.size() != dependencies.size()) throw invalid(); // No omitted dependency or unrelated added source.
            bounded(JSON.toJson(manifestValues(version, worldId, datasetId, originalWatermark, roomId, revision, turnId, playerId,
                    recipientGodId, publicRoom, recordingPolicy, memoryMode, audience, roots, dependencies)), MAX_MANIFEST_BYTES);
        }
    }

    public static RoomEvidenceReference encode(Reference reference) {
        Objects.requireNonNull(reference);
        String payload = JSON.toJson(referenceValues(reference)); bounded(payload, MAX_REFERENCE_BYTES);
        return new RoomEvidenceReference(KIND, payload);
    }
    public static Reference decode(RoomEvidenceReference reference) {
        Objects.requireNonNull(reference);
        if (!KIND.equals(reference.kind())) throw invalid(); bounded(reference.payload(), MAX_REFERENCE_BYTES);
        var value = object(reference.payload()); keys(value, "version", "worldId", "datasetId", "sealId", "manifestHash");
        var parsed = new Reference(integer(value, "version"), uuid(value, "worldId"), uuid(value, "datasetId"), uuid(value, "sealId"), string(value, "manifestHash"));
        if (!encode(parsed).payload().equals(reference.payload())) throw invalid();
        return parsed;
    }
    public static String encodeManifest(Manifest manifest) {
        Objects.requireNonNull(manifest);
        String value = JSON.toJson(manifestValues(manifest.version(), manifest.worldId(), manifest.datasetId(), manifest.originalWatermark(),
                manifest.roomId(), manifest.revision(), manifest.turnId(), manifest.playerId(), manifest.recipientGodId(), manifest.publicRoom(),
                manifest.recordingPolicy(), manifest.memoryMode(), manifest.audience(), manifest.roots(), manifest.dependencies()));
        bounded(value, MAX_MANIFEST_BYTES); return value;
    }
    public static Manifest decodeManifest(String serialized) {
        bounded(serialized, MAX_MANIFEST_BYTES); var value = object(serialized);
        keys(value, "version", "worldId", "datasetId", "originalWatermark", "roomId", "revision", "turnId", "playerId",
                "recipientGodId", "publicRoom", "recordingPolicy", "memoryMode", "audience", "roots", "dependencies");
        var audience = new HashSet<ActorRef>();
        for (var member : array(value, "audience", MAX_AUDIENCE)) {
            String key = text(member); int colon = key.indexOf(':'); if (colon <= 0) throw invalid();
            var actor = new ActorRef(ActorKind.valueOf(key.substring(0, colon)), key.substring(colon + 1));
            if (!audience.add(actor)) throw invalid();
        }
        var dependencies = new ArrayList<SpeechDependency>();
        for (var entry : array(value, "dependencies", MAX_DEPENDENCIES)) {
            var d = entry.getAsJsonObject(); keys(d, "messageId", "source", "knowledgeReceiptId", "receiptHash", "deliveryReceiptId",
                    "deliveryHash", "bodyHash", "disclosureHash", "parentMessageIds");
            var s = d.getAsJsonObject("source"); keys(s, "worldId", "datasetId", "kind", "owner", "sourceId", "revision", "hash");
            var source = new SourceRef(uuid(s, "worldId"), uuid(s, "datasetId"), SourceKind.valueOf(string(s, "kind")),
                    string(s, "owner"), string(s, "sourceId"), number(s, "revision"), string(s, "hash"));
            dependencies.add(new SpeechDependency(uuid(d, "messageId"), source, uuid(d, "knowledgeReceiptId"), string(d, "receiptHash"),
                    uuid(d, "deliveryReceiptId"), string(d, "deliveryHash"), string(d, "bodyHash"), string(d, "disclosureHash"), uuids(d, "parentMessageIds", MAX_DEPENDENCIES)));
        }
        var parsed = new Manifest(integer(value, "version"), uuid(value, "worldId"), uuid(value, "datasetId"), number(value, "originalWatermark"),
                uuid(value, "roomId"), number(value, "revision"), uuid(value, "turnId"), uuid(value, "playerId"), string(value, "recipientGodId"),
                bool(value, "publicRoom"), string(value, "recordingPolicy"), string(value, "memoryMode"), audience, uuids(value, "roots", MAX_ROOTS), dependencies);
        if (!encodeManifest(parsed).equals(serialized)) throw invalid();
        return parsed;
    }
    /** Integrity checksum only. Callers must still validate game-issued storage and all live source permissions. */
    public static String hash(Manifest manifest) { return RecordingRecords.sha256(encodeManifest(manifest)); }

    private static Map<String,Object> referenceValues(Reference reference) {
        var value = new LinkedHashMap<String,Object>(); value.put("version", reference.version()); value.put("worldId", reference.worldId().toString());
        value.put("datasetId", reference.datasetId().toString()); value.put("sealId", reference.sealId().toString()); value.put("manifestHash", reference.manifestHash()); return value;
    }
    private static Map<String,Object> manifestValues(int version, UUID world, UUID dataset, long watermark, UUID room, long revision,
            UUID turn, UUID player, String god, boolean publicRoom, String recording, String mode, Set<ActorRef> audience,
            Set<UUID> roots, List<SpeechDependency> dependencies) {
        var value = new LinkedHashMap<String,Object>();
        value.put("version", version); value.put("worldId", world.toString()); value.put("datasetId", dataset.toString()); value.put("originalWatermark", watermark);
        value.put("roomId", room.toString()); value.put("revision", revision); value.put("turnId", turn.toString()); value.put("playerId", player.toString());
        value.put("recipientGodId", god); value.put("publicRoom", publicRoom); value.put("recordingPolicy", recording); value.put("memoryMode", mode);
        value.put("audience", audience.stream().map(ActorRef::key).sorted().toList()); value.put("roots", sorted(roots));
        value.put("dependencies", dependencies.stream().map(NativeMemoryEvidence::dependencyValues).toList()); return value;
    }
    private static Map<String,Object> dependencyValues(SpeechDependency dependency) {
        var source = dependency.source(); var s = new LinkedHashMap<String,Object>();
        s.put("worldId", source.worldId().toString()); s.put("datasetId", source.datasetId().toString()); s.put("kind", source.kind().name());
        s.put("owner", source.owner()); s.put("sourceId", source.sourceId()); s.put("revision", source.revision()); s.put("hash", source.hash());
        var value = new LinkedHashMap<String,Object>(); value.put("messageId", dependency.messageId().toString()); value.put("source", s);
        value.put("knowledgeReceiptId", dependency.knowledgeReceiptId().toString()); value.put("receiptHash", dependency.receiptHash());
        value.put("deliveryReceiptId", dependency.deliveryReceiptId().toString()); value.put("deliveryHash", dependency.deliveryHash());
        value.put("bodyHash", dependency.bodyHash()); value.put("disclosureHash", dependency.disclosureHash()); value.put("parentMessageIds", sorted(dependency.parentMessageIds())); return value;
    }
    private static void visit(UUID id, Map<UUID,SpeechDependency> values, Map<UUID,Integer> colors) {
        int color = colors.getOrDefault(id, 0); if (color == 1) throw invalid(); if (color == 2) return;
        colors.put(id, 1); for (UUID parent : values.get(id).parentMessageIds()) visit(parent, values, colors); colors.put(id, 2);
    }
    private static List<String> sorted(Set<UUID> ids) { return ids.stream().map(UUID::toString).sorted().toList(); }
    private static JsonObject object(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
    private static void keys(JsonObject value, String... expected) { if (value == null || !value.keySet().equals(Set.of(expected))) throw invalid(); }
    private static String text(JsonElement value) { if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw invalid(); return value.getAsString(); }
    private static String string(JsonObject value, String key) { return text(value.get(key)); }
    private static UUID uuid(JsonObject value, String key) { return uuid(string(value, key)); }
    private static UUID uuid(String value) { UUID id = UUID.fromString(value); if (!id.toString().equals(value)) throw invalid(); return id; }
    private static long number(JsonObject value, String key) {
        var item = value.get(key); if (item == null || !item.isJsonPrimitive() || !item.getAsJsonPrimitive().isNumber()
                || !item.toString().matches("0|[1-9][0-9]*")) throw invalid();
        try { return Long.parseLong(item.toString()); } catch (NumberFormatException ignored) { throw invalid(); }
    }
    private static int integer(JsonObject value, String key) { long result = number(value, key); if (result > Integer.MAX_VALUE) throw invalid(); return (int) result; }
    private static boolean bool(JsonObject value, String key) {
        var item = value.get(key); if (item == null || !item.isJsonPrimitive() || !item.getAsJsonPrimitive().isBoolean()) throw invalid(); return item.getAsBoolean();
    }
    private static JsonArray array(JsonObject value, String key, int limit) {
        var array = value.getAsJsonArray(key); if (array == null || array.size() > limit) throw invalid(); return array;
    }
    private static Set<UUID> uuids(JsonObject value, String key, int limit) {
        var values = new HashSet<UUID>(); for (var entry : array(value, key, limit)) if (!values.add(uuid(text(entry)))) throw invalid(); return Set.copyOf(values);
    }
    private static void digest(String value) { if (value == null || !value.matches("[0-9a-f]{64}")) throw invalid(); }
    private static void bounded(String value, int limit) { if (value == null || value.isBlank() || value.length() > limit || value.getBytes(StandardCharsets.UTF_8).length > limit) throw invalid(); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("INVALID_NATIVE_MEMORY_EVIDENCE"); }
}
