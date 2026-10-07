package com.sande.mythictrpg.recording.api;

import com.google.gson.*;
import com.sande.mythictrpg.ai.api.RoomEvidenceReference;
import com.sande.mythictrpg.recording.api.NativeMemoryEvidence.*;
import com.sande.mythictrpg.recording.api.RecordingRecords.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;

/** Pure immutable-codec checks only. Checksums, parsed refs and allocated seals do not issue game authority. */
public final class NativeMemoryEvidenceTest {
    private static final Gson JSON = new Gson();
    private static final UUID WORLD = UUID.fromString("abcdef01-2345-4678-9abc-def012345678"), DATASET = UUID.randomUUID(), ROOM = UUID.randomUUID();
    private static final UUID TURN = UUID.randomUUID(), PLAYER = UUID.randomUUID(), A = UUID.randomUUID(), B = UUID.randomUUID(), C = UUID.randomUUID();
    private static final String GOD = "test:athena", HASH = "a".repeat(64);
    private static final Set<ActorRef> AUDIENCE = Set.of(new ActorRef(ActorKind.GOD, GOD), new ActorRef(ActorKind.PLAYER, PLAYER.toString()));
    private static int checks;

    public static void main(String[] args) {
        roundTrips(); referenceFailures(); manifestFailures(); scopeAndGraph(); bounds(); integrityNotAuthority();
        System.out.println("NativeMemoryEvidenceTest: " + checks + " checks passed; codec only, no issuance/storage/model/server");
    }
    private static Manifest fixture() {
        return manifest(AUDIENCE, Set.of(C), List.of(dependency(C, Set.of(A, B)), dependency(A, Set.of()), dependency(B, Set.of(A))));
    }
    private static Manifest manifest(Set<ActorRef> audience, Set<UUID> roots, List<SpeechDependency> dependencies) {
        return new Manifest(1, WORLD, DATASET, 44, ROOM, 3, TURN, PLAYER, GOD, false, "STANDARD", "PERSONAL", audience, roots, dependencies);
    }
    private static SpeechDependency dependency(UUID id, Set<UUID> parents) {
        var source = new SourceRef(WORLD, DATASET, parents.isEmpty() ? SourceKind.DIALOGUE_DIRECT : SourceKind.DERIVED_SPEECH,
                "room-publication-v2", id.toString(), 1, HASH);
        return new SpeechDependency(id, source, UUID.nameUUIDFromBytes((id + "/knowledge").getBytes(StandardCharsets.UTF_8)), "b".repeat(64),
                UUID.nameUUIDFromBytes((id + "/delivery").getBytes(StandardCharsets.UTF_8)), "c".repeat(64), "d".repeat(64), "e".repeat(64), parents);
    }
    private static Reference reference(Manifest manifest) { return new Reference(1, WORLD, DATASET, UUID.randomUUID(), NativeMemoryEvidence.hash(manifest)); }
    private static void roundTrips() {
        var original = fixture(); String encoded = NativeMemoryEvidence.encodeManifest(original);
        check(NativeMemoryEvidence.decodeManifest(encoded).equals(original), "all exact native bindings and issued scope roundtrip without lost fields");
        check(encoded.getBytes(StandardCharsets.UTF_8).length <= NativeMemoryEvidence.MAX_MANIFEST_BYTES, "full serialized manifest obeys UTF-8 budget");
        check(NativeMemoryEvidence.hash(original).equals(RecordingRecords.sha256(encoded)), "hash covers entire canonical source manifest");
        var ref = reference(original); var portable = NativeMemoryEvidence.encode(ref);
        check(NativeMemoryEvidence.decode(portable).equals(ref) && portable.kind().equals(NativeMemoryEvidence.KIND), "compact pointer is deterministic strict current-kind JSON");
        check(portable.payload().getBytes(StandardCharsets.UTF_8).length <= NativeMemoryEvidence.MAX_REFERENCE_BYTES, "reference cannot copy a large source manifest into each event");
        var parsed = JsonParser.parseString(portable.payload()).getAsJsonObject();
        check(parsed.keySet().equals(Set.of("version", "worldId", "datasetId", "sealId", "manifestHash")), "portable reference has no speech, knowledge grant or mutable authority flag");
        var reverse = new ArrayList<>(original.dependencies()); Collections.reverse(reverse);
        var reversed = manifest(new LinkedHashSet<>(AUDIENCE), original.roots(), reverse);
        check(NativeMemoryEvidence.encodeManifest(reversed).equals(encoded) && reversed.equals(original), "dependency/set insertion order cannot change canonical bytes");
        var ids = original.dependencies().stream().map(d -> d.messageId().toString()).toList();
        check(ids.equals(ids.stream().sorted().toList()), "UUID ordering is lexical canonical text, not signed UUID natural ordering");
        var parents = new HashSet<>(Set.of(A)); var dependency = dependency(B, parents); parents.clear();
        check(dependency.parentMessageIds().equals(Set.of(A)), "dependency snapshots caller-owned mutable parent collection");
        var audience = new HashSet<>(AUDIENCE); var roots = new HashSet<>(Set.of(A)); var list = new ArrayList<>(List.of(dependency(A, Set.of())));
        var immutable = manifest(audience, roots, list); audience.clear(); roots.clear(); list.clear();
        check(immutable.audience().equals(AUDIENCE) && immutable.roots().equals(Set.of(A)) && immutable.dependencies().size() == 1, "manifest deeply snapshots containers");
        rejects(() -> immutable.dependencies().clear(), "dependency list immutable");
        rejects(() -> immutable.audience().clear(), "audience immutable");
        rejects(() -> immutable.roots().clear(), "root set immutable");
        rejects(() -> dependency.parentMessageIds().clear(), "parent set immutable");
    }
    private static void referenceFailures() {
        var portable = NativeMemoryEvidence.encode(reference(fixture()));
        rejects(() -> NativeMemoryEvidence.decode(new RoomEvidenceReference("WATCH_OBSERVATION_V1", portable.payload())), "foreign evidence kind cannot be upgraded into native proof");
        rejects(() -> NativeMemoryEvidence.decode(new RoomEvidenceReference(NativeMemoryEvidence.KIND, portable.payload() + " ")), "noncanonical trailing whitespace rejected");
        rejects(() -> NativeMemoryEvidence.decode(new RoomEvidenceReference(NativeMemoryEvidence.KIND, "{\"version\":1," + portable.payload().substring(1))), "duplicate JSON keys rejected by exact canonical comparison");
        for (Consumer<JsonObject> mutation : List.<Consumer<JsonObject>>of(
                v -> v.addProperty("version", 2), v -> v.addProperty("version", "1"), v -> v.addProperty("version", 1.0),
                v -> v.addProperty("manifestHash", "A".repeat(64)), v -> v.addProperty("manifestHash", "f".repeat(63)),
                v -> v.addProperty("worldId", "1-1-1-1-1"), v -> v.addProperty("worldId", WORLD.toString().toUpperCase(Locale.ROOT)),
                v -> v.addProperty("permission", "ALL"), v -> v.remove("sealId"), v -> v.add("datasetId", JsonNull.INSTANCE)))
            rejects(() -> NativeMemoryEvidence.decode(new RoomEvidenceReference(NativeMemoryEvidence.KIND, mutate(portable.payload(), mutation))), "invalid/unknown/missing/noncanonical reference field rejected");
        rejects(() -> NativeMemoryEvidence.decode(new RoomEvidenceReference(NativeMemoryEvidence.KIND, "x".repeat(1025))), "reference length admitted before JSON parsing is bounded");
        rejects(() -> NativeMemoryEvidence.decode(new RoomEvidenceReference(NativeMemoryEvidence.KIND, "가".repeat(400))), "UTF-8 reference byte budget differs from character count");
        rejects(() -> new Reference(0, WORLD, DATASET, UUID.randomUUID(), HASH), "unknown descriptor schema rejected");
    }
    private static void manifestFailures() {
        String good = NativeMemoryEvidence.encodeManifest(fixture());
        for (Consumer<JsonObject> mutation : List.<Consumer<JsonObject>>of(
                v -> v.addProperty("version", 9), v -> v.addProperty("originalWatermark", 0), v -> v.addProperty("originalWatermark", -1),
                v -> v.addProperty("originalWatermark", "44"), v -> v.addProperty("originalWatermark", 44.0),
                v -> v.addProperty("originalWatermark", new java.math.BigInteger("9223372036854775808")),
                v -> v.addProperty("revision", -1), v -> v.addProperty("publicRoom", "false"),
                v -> v.addProperty("recordingPolicy", "TEST_EPHEMERAL"), v -> v.addProperty("recordingPolicy", "CUSTOM_ALLOW_ALL"),
                v -> v.addProperty("memoryMode", "OFF"), v -> v.addProperty("memoryMode", "UNKNOWN"),
                v -> v.addProperty("worldId", UUID.randomUUID().toString()), v -> v.addProperty("datasetId", UUID.randomUUID().toString()),
                v -> v.addProperty("playerId", UUID.randomUUID().toString()), v -> v.addProperty("recipientGodId", "test:unheard"),
                v -> v.remove("turnId"), v -> v.addProperty("current", true),
                v -> v.getAsJsonArray("audience").add(v.getAsJsonArray("audience").get(0)),
                v -> v.getAsJsonArray("roots").add(v.getAsJsonArray("roots").get(0)),
                v -> v.getAsJsonArray("dependencies").add(v.getAsJsonArray("dependencies").get(0)),
                v -> v.getAsJsonArray("dependencies").get(0).getAsJsonObject().addProperty("rawText", "secret body"),
                v -> v.getAsJsonArray("dependencies").get(0).getAsJsonObject().getAsJsonObject("source").addProperty("kind", "RUMOR_RECEIVED"),
                v -> v.getAsJsonArray("dependencies").get(0).getAsJsonObject().getAsJsonObject("source").addProperty("owner", "action-ledger-v1"),
                v -> v.getAsJsonArray("dependencies").get(0).getAsJsonObject().getAsJsonObject("source").addProperty("sourceId", UUID.randomUUID().toString()),
                v -> v.getAsJsonArray("dependencies").get(0).getAsJsonObject().getAsJsonObject("source").addProperty("revision", 2)))
            rejects(() -> NativeMemoryEvidence.decodeManifest(mutate(good, mutation)), "malformed/unsupported source or issued scope is fail-closed");
        rejects(() -> NativeMemoryEvidence.decodeManifest("[" + good + "]"), "manifest must be one exact object");
        rejects(() -> NativeMemoryEvidence.decodeManifest(good + "{}"), "multiple JSON documents rejected");
        rejects(() -> NativeMemoryEvidence.decodeManifest(" " + good), "noncanonical leading whitespace rejected");
        rejects(() -> NativeMemoryEvidence.decodeManifest("{\"version\":1," + good.substring(1)), "duplicate manifest field cannot survive canonical verification");
        for (String field : List.of("receiptHash", "deliveryHash", "bodyHash", "disclosureHash"))
            rejects(() -> NativeMemoryEvidence.decodeManifest(mutate(good, v -> v.getAsJsonArray("dependencies").get(0).getAsJsonObject().addProperty(field, "not-a-digest"))), "all dependency hashes use exact lowercase SHA-256 format");
        var alternate = new Manifest(1, WORLD, DATASET, 44, ROOM, 3, TURN, PLAYER, GOD, true, "TEST_RECORDING", "RUMOR_TEST",
                AUDIENCE, Set.of(A), List.of(dependency(A, Set.of())));
        check(NativeMemoryEvidence.decodeManifest(NativeMemoryEvidence.encodeManifest(alternate)).equals(alternate), "supported public/test-recording/native rumor-mode speech remains a typed native source, not a rumor seal");
    }
    private static void scopeAndGraph() {
        rejects(() -> manifest(AUDIENCE, Set.of(A), List.of()), "empty dependencies cannot grant empty proof");
        rejects(() -> manifest(AUDIENCE, Set.of(), List.of(dependency(A, Set.of()))), "at least one actual issued source root required");
        rejects(() -> manifest(AUDIENCE, Set.of(C), List.of(dependency(A, Set.of()))), "unbound root rejected");
        rejects(() -> manifest(AUDIENCE, Set.of(A), List.of(dependency(A, Set.of(B)))), "missing mandatory parent rejected");
        rejects(() -> dependency(A, Set.of(A)), "direct self-parent rejected");
        rejects(() -> manifest(AUDIENCE, Set.of(A), List.of(dependency(A, Set.of(B)), dependency(B, Set.of(C)), dependency(C, Set.of(A)))), "indirect cycle rejected");
        rejects(() -> manifest(AUDIENCE, Set.of(A), List.of(dependency(A, Set.of()), dependency(B, Set.of()))), "unrelated extra source cannot be silently appended to a root's proof");
        var first = dependency(A, Set.of()); var other = dependency(B, Set.of(A));
        rejects(() -> manifest(AUDIENCE, Set.of(B), List.of(first, new SpeechDependency(B, other.source(), first.knowledgeReceiptId(), other.receiptHash(),
                other.deliveryReceiptId(), other.deliveryHash(), other.bodyHash(), other.disclosureHash(), other.parentMessageIds()))), "same knowledge receipt cannot bind two distinct sources");
        rejects(() -> manifest(AUDIENCE, Set.of(B), List.of(first, new SpeechDependency(B, other.source(), other.knowledgeReceiptId(), other.receiptHash(),
                first.deliveryReceiptId(), other.deliveryHash(), other.bodyHash(), other.disclosureHash(), other.parentMessageIds()))), "same delivery receipt cannot bind two distinct sources");
        rejects(() -> manifest(Set.of(new ActorRef(ActorKind.GOD, GOD)), Set.of(A), List.of(first)), "missing requester from original audience rejected");
        rejects(() -> manifest(Set.of(new ActorRef(ActorKind.PLAYER, PLAYER.toString())), Set.of(A), List.of(first)), "missing recipient God from original audience rejected");
        var multiRoots = manifest(AUDIENCE, Set.of(A, B), List.of(first, dependency(B, Set.of())));
        check(NativeMemoryEvidence.decodeManifest(NativeMemoryEvidence.encodeManifest(multiRoots)).roots().size() == 2, "multiple independent actually issued roots supported without inventing parent linkage");
    }
    private static void bounds() {
        var many = new ArrayList<SpeechDependency>(); UUID parent = null;
        for (int i = 0; i < 64; i++) {
            UUID id = UUID.nameUUIDFromBytes(("chain/" + i).getBytes(StandardCharsets.UTF_8)); many.add(dependency(id, parent == null ? Set.of() : Set.of(parent))); parent = id;
        }
        var largest = manifest(AUDIENCE, Set.of(parent), many);
        check(largest.dependencies().size() == 64 && NativeMemoryEvidence.decodeManifest(NativeMemoryEvidence.encodeManifest(largest)).equals(largest), "bounded 64-source acyclic chain roundtrips");
        var overflowing = new ArrayList<>(many); UUID overflow = UUID.randomUUID(); overflowing.add(dependency(overflow, Set.of(parent)));
        rejects(() -> manifest(AUDIENCE, Set.of(overflow), overflowing), "65th source rejected, never partial ancestry");
        var tooManyRoots = new HashSet<UUID>(); for (int i = 0; i < 65; i++) tooManyRoots.add(UUID.randomUUID());
        rejects(() -> manifest(AUDIENCE, tooManyRoots, List.of(dependency(A, Set.of()))), "65th root rejected before resolution");
        var manyGods = new HashSet<>(AUDIENCE); for (int i = 0; i < 16; i++) manyGods.add(new ActorRef(ActorKind.GOD, "test:god_" + i));
        rejects(() -> manifest(manyGods, Set.of(A), List.of(dependency(A, Set.of()))), "more than sixteen current Gods is unsupported");
        var manyPeople = new HashSet<>(AUDIENCE); for (int i = 0; i < 255; i++) manyPeople.add(new ActorRef(ActorKind.PLAYER, UUID.randomUUID().toString()));
        rejects(() -> manifest(manyPeople, Set.of(A), List.of(dependency(A, Set.of()))), "audience is bounded independently of source count");
        rejects(() -> NativeMemoryEvidence.decodeManifest("x".repeat(65537)), "oversized text rejected before JSON traversal");
        rejects(() -> NativeMemoryEvidence.decodeManifest("가".repeat(24000)), "manifest uses UTF-8 bytes, not only UTF-16 length");
        var dense = new ArrayList<SpeechDependency>(); var prior = new HashSet<UUID>(); UUID last = null;
        for (int i = 0; i < 64; i++) { last = UUID.randomUUID(); dense.add(dependency(last, prior)); prior.add(last); }
        UUID denseRoot = last;
        rejects(() -> manifest(AUDIENCE, Set.of(denseRoot), dense), "valid but oversized dense DAG cannot escape the full-manifest byte limit");
        check(NativeMemoryEvidence.MAX_PAGES == 8 && NativeMemoryEvidence.MAX_REFERENCE_BYTES == 1024
                && NativeMemoryEvidence.MAX_MANIFEST_BYTES == 65536, "issuer and codec share explicit finite budgets");
    }
    private static void integrityNotAuthority() {
        var manifest = fixture(); var reference = reference(manifest); var portable = NativeMemoryEvidence.encode(reference);
        var one = NativeMemorySeal.unregistered(portable); var two = NativeMemorySeal.unregistered(portable);
        check(one != two && !one.equals(two) && one.reference().equals(portable), "allocating identical valid pointers does not clone issuing Session identity");
        var registry = new IdentityHashMap<NativeMemorySeal,Boolean>(); registry.put(one, true);
        check(!registry.containsKey(two), "only explicit game issuer registration can make a token current");
        check(one.toString().equals("NativeMemorySeal[opaque]") && !one.toString().contains(reference.sealId().toString()), "opaque diagnostic string does not print sources or manifest IDs");
        String altered = mutate(NativeMemoryEvidence.encodeManifest(manifest), value -> value.addProperty("revision", 4));
        var changed = NativeMemoryEvidence.decodeManifest(altered);
        check(!NativeMemoryEvidence.hash(changed).equals(reference.manifestHash()), "a syntactically valid altered manifest changes checksum; issued row comparison must reject it");
        var syntacticallyValidOnly = new Reference(1, WORLD, DATASET, UUID.randomUUID(), NativeMemoryEvidence.hash(changed));
        check(NativeMemoryEvidence.decode(NativeMemoryEvidence.encode(syntacticallyValidOnly)).equals(syntacticallyValidOnly), "codec deliberately cannot assert whether a checksum has a game-issued row or current authority");
        rejects(() -> NativeMemorySeal.unregistered(new RoomEvidenceReference("UNKNOWN_KIND", portable.payload())), "seal allocation cannot even parse unknown evidence kinds");
    }
    private static String mutate(String json, Consumer<JsonObject> mutation) { var value = JsonParser.parseString(json).getAsJsonObject(); mutation.accept(value); return JSON.toJson(value); }
    private static void rejects(Runnable code, String description) { try { code.run(); } catch (RuntimeException expected) { checks++; return; } throw new AssertionError(description); }
    private static void check(boolean value, String description) { checks++; if (!value) throw new AssertionError(description); }
}
