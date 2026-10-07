package com.sande.mythai.response.memory;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Offline persistence/permission regressions for NPC listeners, including pre-field Gson data. */
public final class MemoryAudienceTest {
    private static final Gson JSON = new Gson();
    private static final String ATHENA = "mythictrpg:greek_olympus_athena";
    private static final String HERMES = "mythictrpg:greek_olympus_hermes";
    private static final String HADES = "mythictrpg:greek_underworld_hades";
    private static final UUID PLAYER = UUID.randomUUID();
    private static final MemoryJournal.Key KEY = new MemoryJournal.Key(UUID.randomUUID(), ATHENA, PLAYER);
    private static final Set<String> GROUP = Set.of(ATHENA, HERMES);
    private static final long NOW = System.currentTimeMillis();
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory(Path.of(args[0]), "memory-audience-");
        legacyJson(root.resolve("legacy"));
        audienceAndPersistence(root.resolve("group"));
        derivedAndVectorAudience(root.resolve("derived-audience"));
        invalidMetadata();
        checks += RoomListeningMemoryTest.run(root);
        checks += RoomRecallSourceTest.run(root);
        checks += RoomMemoryStoreTest.run(root);
        checks += RecordedInterpretationShadowTest.run();
        System.out.println("MemoryAudienceTest: " + checks + " checks PASS");
    }

    private static void legacyJson(Path directory) throws Exception {
        var authored = entry("아테나와 단둘이 나눈 비밀 약속", Set.of(ATHENA));
        JsonObject legacy = JSON.toJsonTree(authored).getAsJsonObject();
        legacy.remove("godAudience");
        var decoded = JSON.fromJson(legacy, MemoryJournal.Entry.class);
        check(decoded.godAudience().equals(Set.of(ATHENA)), "missing legacy field defaults only to owner");
        legacy.add("godAudience", com.google.gson.JsonNull.INSTANCE);
        check(JSON.fromJson(legacy, MemoryJournal.Entry.class).godAudience().equals(Set.of(ATHENA)),
                "explicit legacy null cannot widen disclosure");
        legacy.remove("godAudience");
        JsonArray rows = new JsonArray(); rows.add(legacy);
        JsonObject snapshot = new JsonObject();
        snapshot.addProperty("version", 1); snapshot.addProperty("sequence", 1);
        snapshot.add("entries", rows); snapshot.add("retired", new JsonArray());
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("snapshot.json"), JSON.toJson(snapshot));
        try (var journal = new MemoryJournal(directory)) {
            check(journal.awaitIdle(Duration.ofSeconds(5)), "real legacy snapshot loads");
            check(journal.readView(KEY, Set.of(PLAYER)).entries().equals(List.of(decoded)), "owner retains old memory");
            check(journal.readView(KEY, Set.of(PLAYER), GROUP).entries().isEmpty(), "old private memory excluded with another God present");
            check(search(journal, "비밀 약속", GROUP).isEmpty(), "legacy lexical lookup cannot disclose old private memory");
        }
    }

    private static void audienceAndPersistence(Path directory) throws Exception {
        var privateEntry = entry("비밀 약속은 아테나에게만 이야기했다", Set.of(ATHENA));
        var shared = entry("공동 약속은 아테나와 헤르메스에게 이야기했다", GROUP);
        MemoryJournal.Entry replacement;
        try (var journal = new MemoryJournal(directory)) {
            check(waitFor(journal.append(privateEntry)) == MemoryJournal.Result.STORED, "private append");
            check(waitFor(journal.append(shared)) == MemoryJournal.Result.STORED, "group append");
            var groupView = journal.readView(KEY, Set.of(PLAYER), GROUP);
            check(groupView.godAudience().equals(GROUP) && groupView.entries().equals(List.of(shared)), "group readView is scoped");
            check(journal.readView(KEY, Set.of(PLAYER)).entries().size() == 2, "owner-only read sees own private and shared entries");
            check(journal.readView(KEY, Set.of(PLAYER), Set.of(ATHENA, HADES)).entries().isEmpty(), "new NPC listener cannot read earlier group");
            check(journal.readView(KEY, Set.of(PLAYER, UUID.randomUUID()), GROUP).entries().isEmpty(), "player audience remains enforced");
            var otherKey = new MemoryJournal.Key(KEY.world(), HERMES, PLAYER);
            check(journal.readView(otherKey, Set.of(PLAYER), GROUP).entries().isEmpty(), "listener status does not give access to another owner bucket");
            check(search(journal, "공동 약속", GROUP).equals(List.of(shared)), "group lexical retrieval");
            check(search(journal, "비밀 약속", GROUP).stream().noneMatch(e -> e.id().equals(privateEntry.id())), "group lexical privacy");
            check(search(journal, "공동 약속", Set.of(ATHENA, HADES)).isEmpty(), "lexical query rechecks current NPC membership");
            var privateAddition = entry("새로운 비밀 약속", Set.of(ATHENA));
            waitFor(journal.append(privateAddition));
            check(journal.stillCurrent(groupView, UUID.randomUUID(), 999), "currentness reuses group audience, not owner-only defaults");
            check(waitFor(journal.pin(shared.id(), journal.view().revision(), true)) == MemoryJournal.Result.STORED, "group pin");
            var pinned = journal.readView(KEY, Set.of(PLAYER), GROUP).entries().getFirst();
            check(pinned.important() && pinned.godAudience().equals(GROUP), "pin preserves NPC audience");
            check(!journal.stillCurrent(groupView, UUID.randomUUID(), 999), "changed selected evidence invalidates old read");
            var widened = replacement(pinned, Set.of(ATHENA, HERMES, HADES));
            check(waitFor(journal.supersede(pinned.id(), widened, journal.view().revision())) == MemoryJournal.Result.STALE,
                    "correction cannot widen NPC audience");
            var narrowed = replacement(pinned, Set.of(ATHENA));
            check(waitFor(journal.supersede(pinned.id(), narrowed, journal.view().revision())) == MemoryJournal.Result.STALE,
                    "correction cannot silently rewrite NPC audience");
            replacement = replacement(pinned, GROUP);
            check(waitFor(journal.supersede(pinned.id(), replacement, journal.view().revision())) == MemoryJournal.Result.STORED,
                    "same audience correction succeeds");
            check(journal.readView(KEY, Set.of(PLAYER), GROUP).entries().equals(List.of(replacement)), "corrected group memory retained");
        }
        try (var journal = new MemoryJournal(directory)) {
            check(journal.awaitIdle(Duration.ofSeconds(5)), "NPC audience operations replay after restart");
            var view = journal.readView(KEY, Set.of(PLAYER), GROUP);
            check(view.entries().equals(List.of(replacement)), "replayed pin/correction preserve explicit listeners");
            check(waitFor(journal.delete(replacement.id(), journal.view().revision())) == MemoryJournal.Result.STORED, "group delete");
            check(!journal.stillCurrent(view, UUID.randomUUID(), 999), "deletion invalidates pending group lookup");
            check(journal.readView(KEY, Set.of(PLAYER), GROUP).entries().isEmpty(), "deleted shared memory absent");
        }
    }

    private static void invalidMetadata() {
        reject(() -> entry("누락", Set.of(HERMES)), "owner must be in NPC audience");
        reject(() -> entry("비어 있음", Set.of()), "empty audience cannot mean public");
        reject(() -> entry("잘못된 ID", Set.of(ATHENA, "not a resource location")), "invalid God ID rejected");
        Set<String> tooMany = new java.util.HashSet<>(); tooMany.add(ATHENA);
        for (int i = 0; i < 16; i++) tooMany.add("mythictrpg:test_" + i);
        reject(() -> entry("한도 초과", tooMany), "NPC audience bound enforced");
        var standard = new MemoryJournal.Entry(UUID.randomUUID(), KEY, UUID.randomUUID(), 1,
                MemoryJournal.Source.PLAYER_STATEMENT, Set.of(PLAYER), NOW, "기존 생성자", false);
        check(standard.godAudience().equals(Set.of(ATHENA)), "old Java constructor remains owner-only");
    }

    private static void derivedAndVectorAudience(Path directory) throws Exception {
        var privateEntry = entry("내가 바다에서 수영을 무서워한다고 말했어", Set.of(ATHENA));
        var sharedEntry = entry("우리는 강가에서 산책한 이야기를 했어", GROUP);
        var changedAudience = new MemoryJournal.Entry(privateEntry.id(), privateEntry.key(), privateEntry.session(),
                privateEntry.turn(), privateEntry.source(), privateEntry.audience(), privateEntry.occurredAt(),
                privateEntry.text(), privateEntry.important(), GROUP);
        String legacyHash = DerivedMemory.hash(JSON.toJson(List.of(privateEntry.id().toString(), KEY.world().toString(),
                KEY.god(), KEY.player().toString(), privateEntry.session().toString(), privateEntry.turn(),
                privateEntry.source().name(), privateEntry.audience().stream().map(UUID::toString).sorted().toList(),
                privateEntry.occurredAt(), privateEntry.text(), privateEntry.important())));
        check(DerivedMemory.fingerprint(privateEntry).equals(legacyHash), "owner-only fingerprints preserve legacy bytes");
        check(!DerivedMemory.fingerprint(privateEntry).equals(DerivedMemory.fingerprint(changedAudience)),
                "NPC audience change invalidates derived fingerprint");
        var reversed = new MemoryJournal.Entry(changedAudience.id(), changedAudience.key(), changedAudience.session(),
                changedAudience.turn(), changedAudience.source(), changedAudience.audience(), changedAudience.occurredAt(),
                changedAudience.text(), changedAudience.important(), new java.util.LinkedHashSet<>(List.of(HERMES, ATHENA)));
        check(DerivedMemory.fingerprint(changedAudience).equals(DerivedMemory.fingerprint(reversed)),
                "God audience set iteration cannot change stable evidence hash");
        var oldLink = new MemoryIndexRow.Link(privateEntry.id(), DerivedMemory.fingerprint(privateEntry), "CORRECTS");
        var row = new MemoryIndexRow(2, sharedEntry, DerivedMemory.fingerprint(sharedEntry), "fixture-npc-audience-v1",
                new float[]{1, 0}, "fixture", "SELF_CLAIM", List.of(oldLink));
        var mixedView = new MemoryJournal.ReadView(KEY, Set.of(PLAYER), List.of(privateEntry, sharedEntry),
                Set.of(), true, false, GROUP);
        check(row.current(mixedView), "shared index source is permitted in its original group");
        check(!row.linkCurrent(oldLink, mixedView), "manually mixed readView cannot create cross-God-audience correction link");
        var expandedView = new MemoryJournal.ReadView(KEY, Set.of(PLAYER), List.of(sharedEntry), Set.of(), true, false,
                Set.of(ATHENA, HERMES, HADES));
        check(!row.current(expandedView), "index source rejects an unapproved additional God even in forged raw list");
        var basis = RecallSettings.TimeBasis.UNSPECIFIED;
        var privateNote = DerivedMemory.project(privateEntry, basis);
        var sharedNote = DerivedMemory.project(sharedEntry, basis);
        var index = new SemanticIndex(List.of(
                new SemanticIndex.Row(privateNote, new SemanticIndex.Vector("fixture-npc-audience-v1", new float[]{1, 0})),
                new SemanticIndex.Row(sharedNote, new SemanticIndex.Vector("fixture-npc-audience-v1", new float[]{1, 0}))));
        try (var journal = new MemoryJournal(directory.resolve("raw"))) {
            waitFor(journal.append(privateEntry)); waitFor(journal.append(sharedEntry));
            var groupView = journal.readView(KEY, Set.of(PLAYER), GROUP);
            check(index.allowed(groupView).stream().map(r -> r.note().sourceId()).toList().equals(List.of(sharedEntry.id())),
                    "group prefilter removes private vectors before similarity ranking");
            var settings = new RecallSettings(true, basis);
            var query = RecallQuery.plan(new RecallQuery.Scope(KEY, UUID.randomUUID(), Set.of(PLAYER)),
                    "내가 뭐라고 말했지?", 2, NOW, null);
            var lexical = RecallSearch.search(groupView, query, settings, Set.of(), List.of(), NOW, TimeUnit.SECONDS.toNanos(1));
            var provider = new SemanticIndex.Provider() {
                public String fingerprint() { return "fixture-npc-audience-v1"; }
                public CompletableFuture<SemanticIndex.Vector> embed(String text) {
                    return CompletableFuture.completedFuture(new SemanticIndex.Vector(fingerprint(), new float[]{1, 0}));
                }
            };
            var result = HybridRetrieval.search(groupView, lexical, settings, index, provider, NOW, TimeUnit.SECONDS.toNanos(1));
            check(result.semanticState().equals("READY") && result.recall().selected().equals(List.of(sharedEntry)),
                    "semantic query returns approved shared raw only despite equally close private vector");
            try (var derived = new DerivedStore(directory.resolve("derived"), journal, new DerivedSettings(true, false, 2_000_000, 100))) {
                derived.awaitIdle();
                check(derived.append(privateEntry, basis).get(5, TimeUnit.SECONDS) == DerivedStore.Result.STORED,
                        "private derived fixture stored");
                check(derived.append(sharedEntry, basis).get(5, TimeUnit.SECONDS) == DerivedStore.Result.STORED,
                        "shared derived fixture stored");
                check(derived.view(groupView).stream().map(DerivedMemory.Note::sourceId).toList().equals(List.of(sharedEntry.id())),
                        "derived sidecar lookup excludes private raw outside scoped group view");
                check(derived.view(journal.readView(KEY, Set.of(PLAYER))).size() == 2,
                        "owner-only lookup still retrieves both private and shared derived notes");
            }
        }
    }

    private static MemoryJournal.Entry entry(String text, Set<String> gods) {
        return new MemoryJournal.Entry(UUID.randomUUID(), KEY, UUID.randomUUID(), 1,
                MemoryJournal.Source.PLAYER_STATEMENT, Set.of(PLAYER), NOW, text, false, gods);
    }
    private static MemoryJournal.Entry replacement(MemoryJournal.Entry previous, Set<String> gods) {
        return new MemoryJournal.Entry(UUID.randomUUID(), previous.key(), previous.session(), previous.turn(),
                previous.source(), previous.audience(), NOW, "공동 약속 내용을 정정했다", previous.important(), gods);
    }
    private static List<MemoryJournal.Entry> search(MemoryJournal journal, String query, Set<String> gods) {
        return journal.searchConversation(KEY, Set.of(PLAYER), query, Set.of(), List.of(), UUID.randomUUID(),
                NOW, 3, TimeUnit.SECONDS.toNanos(1), gods);
    }
    private static MemoryJournal.Result waitFor(CompletableFuture<MemoryJournal.Result> future) throws Exception {
        return future.get(5, TimeUnit.SECONDS);
    }
    private static void reject(Runnable work, String message) {
        try { work.run(); } catch (IllegalArgumentException expected) { check(true, message); return; }
        throw new AssertionError(message);
    }
    private static void check(boolean value, String message) {
        checks++; if (!value) throw new AssertionError(message);
    }
}
