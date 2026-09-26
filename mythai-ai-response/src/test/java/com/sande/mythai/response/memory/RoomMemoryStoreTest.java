package com.sande.mythai.response.memory;

import com.google.gson.Gson;
import com.sande.mythictrpg.ai.api.RoomDialogueEvent;
import com.sande.mythictrpg.ai.api.RoomEvidenceReference;
import com.sande.mythictrpg.ai.room.RecordingScope;
import com.sande.mythictrpg.ai.room.RoomType;
import com.sande.mythictrpg.ai.memorycontract.MemoryFoundationSettings.Mode;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Real JSONL/restart/search and production projection/DAG checks, without a server, LLM or authored outcome. */
public final class RoomMemoryStoreTest {
    private static final String SELF = "mythictrpg:hermes", OTHER = "mythictrpg:athena", OUTSIDE = "mythictrpg:hades";
    private static final UUID WORLD = UUID.randomUUID(), ROOM = UUID.randomUUID(), PLAYER = UUID.randomUUID(), PEER = UUID.randomUUID();
    private static final Set<UUID> PEOPLE = Set.of(PLAYER, PEER);
    private static final Set<String> GODS = Set.of(SELF, OTHER);
    private static final long NOW = System.currentTimeMillis() - 1000;
    private static int checks;
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory(Path.of(args[0]), "room-heard-memory-");
        System.out.println("RoomMemoryStoreTest: " + run(root) + " checks PASS");
    }
    static int run(Path root) throws Exception {
        checks = 0;
        projection();
        modeIsolation(root.resolve("heard-modes"));
        searchAndRestart(root.resolve("heard-room-store"));
        ancestryRestart(root.resolve("heard-room-ancestry"));
        legacyReferences(root.resolve("heard-legacy-source"));
        iterativeGraph();
        persistenceFailures(root.resolve("heard-room-failures"));
        return checks;
    }
    private static void modeIsolation(Path root) throws Exception {
        check(!RoomMemoryBridge.directory(Mode.PERSONAL).equals(RoomMemoryBridge.directory(Mode.RUMOR_TEST)),
                "experimental rumor mode has a different durable namespace from PERSONAL");
        reject(() -> RoomMemoryBridge.directory(Mode.OFF), "OFF cannot select any persistent namespace");
        var personal = row("NPC", SELF, SELF, "현실 신전 봉인의 개인 기억", true, PEOPLE, GODS, List.of(), Set.of());
        var experiment = row("NPC", SELF, SELF, "가상 지옥 봉인의 소문 시험", true, PEOPLE, GODS, List.of(), Set.of());
        try (var live = new RoomMemoryStore(root.resolve(RoomMemoryBridge.directory(Mode.PERSONAL)));
             var trial = new RoomMemoryStore(root.resolve(RoomMemoryBridge.directory(Mode.RUMOR_TEST)))) {
            stored(live, personal); stored(trial, experiment);
            check(search(live, scope(PLAYER, PEOPLE, GODS, true), "가상 지옥").isEmpty()
                    && search(trial, scope(PLAYER, PEOPLE, GODS, true), "현실 신전").isEmpty(),
                    "same world and God cannot retrieve the other global mode's records");
        }
        var recordedTest = event("NPC", SELF, "기록을 허용한 명시 테스트", RoomType.PRIVATE, RecordingScope.TEST_RECORDING,
                Map.of(PLAYER, "가람"), Map.of(PLAYER, new RoomDialogueEvent.Delivery("가람", true, 0)), GODS, Optional.empty());
        check(RoomMemoryProjection.persistent(recordedTest, true).isPresent(), "TEST_RECORDING on still intentionally contributes memory within its selected global mode");
    }
    private static void projection() {
        var wide = new LinkedHashMap<UUID,String>(); wide.put(PLAYER, "가람");
        for (int i = 0; i < 40; i++) wide.put(UUID.randomUUID(), "참가자" + i);
        var delivered = new LinkedHashMap<UUID,RoomDialogueEvent.Delivery>();
        wide.forEach((id, name) -> delivered.put(id, new RoomDialogueEvent.Delivery(name, true, 0)));
        String text = "긴 이야기 ".repeat(500) + "검은 수정은 지하 제단에서 빛난다";
        var initial = event("NPC", SELF, text, RoomType.PRIVATE, RecordingScope.STANDARD, wide, delivered, GODS, Optional.empty());
        var row = RoomMemoryProjection.persistent(initial, true).orElseThrow();
        check(row.text().equals(text) && row.text().length() > 1200, "initial non-LLM speech retains the full original, not a prefix");
        check(row.fullPlayerAudience().size() == 41 && row.heardGodIds().equals(GODS), "more than sixteen players and both actually hearing Gods retained");
        check(RoomMemoryProjection.persistent(initial, false).isEmpty(), "global memory OFF forbids persistence even with room recording ON");
        var off = event("NPC", SELF, text, RoomType.PRIVATE, RecordingScope.TEST_EPHEMERAL, wide, delivered, GODS, Optional.empty());
        check(RoomMemoryProjection.persistent(off, true).isEmpty(), "room recording OFF forbids persistence");
        var offReceipt = RoomMemoryProjection.receipt(off).orElseThrow();
        check(offReceipt.gods().equals(GODS) && offReceipt.parents().isEmpty(), "OFF still has game-issued revocable metadata for immediate history");
        check(Arrays.stream(RoomMemoryEvidence.Receipt.class.getRecordComponents()).noneMatch(c -> c.getName().equals("text")),
                "volatile metadata does not retain dialogue text");
        var player = event("PLAYER", PLAYER.toString(), "신전 봉인을 지키겠다고 말했다", RoomType.PRIVATE,
                RecordingScope.STANDARD, Map.of(PLAYER, "가람", PEER, "나래"), Map.of(), GODS, Optional.empty());
        var playerRow = RoomMemoryProjection.persistent(player, true).orElseThrow();
        check(playerRow.fullPlayerAudience().equals(Set.of(PLAYER)) && playerRow.speakerName().equals("가람"),
                "non-LLM player input persists without a request, and its author knows the input");
        var partial = event("NPC", SELF, text, RoomType.PRIVATE, RecordingScope.STANDARD,
                Map.of(PLAYER, "가람", PEER, "나래"), Map.of(PLAYER, new RoomDialogueEvent.Delivery("가람", false, 1),
                        PEER, new RoomDialogueEvent.Delivery("나래", true, 0)), GODS, Optional.empty());
        check(RoomMemoryProjection.persistent(partial, true).orElseThrow().fullPlayerAudience().equals(Set.of(PEER)),
                "partial HUD dispatch cannot authorize future disclosure of the entire original");
        check(RoomMemoryProjection.persistent(initial.withHeardGods(Set.of()), true).isEmpty(), "membership without actual God hearing yields no memory");
        var proof = new RoomEvidenceReference("STORY_DISCLOSURE_V1", "authoritative-game-reference");
        var parent = UUID.randomUUID();
        var guarded = RoomMemoryProjection.persistent(initial.withEvidence(List.of(proof), Set.of(parent)), true).orElseThrow();
        check(guarded.evidenceRefs().equals(List.of(proof)) && guarded.sourceMessageIds().equals(Set.of(parent)),
                "guarded Story speech is retained with proof and ancestry, not silently omitted or promoted to a fact");
        var leaving = initial.withHeardGods(Set.of(OTHER));
        var leavingRow = RoomMemoryProjection.persistent(leaving, true).orElseThrow();
        check(leavingRow.speakerId().equals(SELF) && leavingRow.heardGodIds().equals(Set.of(OTHER)),
                "game-certified leaving God remains the speaker without fabricating its own listener receipt");
    }
    private static void searchAndRestart(Path directory) throws Exception {
        var player = row("PLAYER", PLAYER.toString(), "가람", "신전 봉인을 지키겠다고 내가 말했어", false, PEOPLE, GODS, List.of(), Set.of());
        var peer = row("PLAYER", PEER.toString(), "나래", "신전 봉인에 접근하지 않겠다고 내가 말했어", false, PEOPLE, GODS, List.of(), Set.of());
        var other = row("NPC", OTHER, OTHER, "신전 봉인은 위험하다고 조언했다", false, PEOPLE, GODS, List.of(), Set.of());
        var self = row("NPC", SELF, SELF, "신전 봉인은 이미 닫혔다고 답했다", false, PEOPLE, GODS, List.of(), Set.of());
        var publicRow = row("NPC", OTHER, OTHER, "여명의 종은 해가 뜨면 세 번 울린다", true, Set.of(PLAYER), GODS, List.of(), Set.of());
        var longRow = row("NPC", OTHER, OTHER, "흐르는 바람 ".repeat(400) + "검은 수정은 지하 제단에서 빛난다",
                true, PEOPLE, GODS, List.of(), Set.of());
        var widePlayers = new HashSet<>(PEOPLE); for (int i = 0; i < 39; i++) widePlayers.add(UUID.randomUUID());
        var wideRow = row("NPC", OTHER, OTHER, "황혼 항구의 등대를 함께 찾아보자", false, widePlayers, GODS, List.of(), Set.of());
        var scope = scope(PLAYER, PEOPLE, GODS, false);
        try (var store = new RoomMemoryStore(directory)) {
            for (var row : List.of(player, peer, other, self, publicRow, longRow, wideRow)) stored(store, row);
            check(search(store, scope(PLAYER, widePlayers, GODS, false), "황혼 항구").equals(List.of(wideRow))
                    && store.evidenceCurrent(scope(PLAYER, widePlayers, GODS, false), Set.of(wideRow.messageId()), ignored -> true),
                    "forty-one-person room performs real lexical recall and permission validation beyond old sixteen-person format");
            check(search(store, scope, "내가 신전 봉인을 뭐라고 했지?").equals(List.of(player)), "own-word recall never substitutes another player or God");
            check(search(store, scope(PEER, PEOPLE, GODS, false), "내가 신전 봉인을 뭐라고 했지?").equals(List.of(peer)),
                    "different questioner resolves their own words without changing the God's memory owner");
            check(search(store, scope, "네가 신전 봉인을 뭐라고 했지?").equals(List.of(self)), "second person resolves to the remembering God");
            check(search(store, scope(PEER, PEOPLE, GODS, false), "다른 신이 신전 봉인을 뭐라고 했지?").equals(List.of(other)),
                    "another player can ask the God about actual heard NPC speech");
            check(search(store, scope, OTHER + "가 신전 봉인을 뭐라고 했지?").equals(List.of(other)), "exact God ID remains a source filter, not lexical noise");
            check(search(store, scope(PEER, PEOPLE, GODS, false), "가람이 신전 봉인을 뭐라고 했지?").equals(List.of(player)),
                    "recorded player name filters the real source UUID without player-owner bucketing");
            check(search(store, scope, "신전 봉인을 뭐라고 했지?").containsAll(List.of(player, peer, other, self)), "ambiguous source keeps separate player/God attribution");
            check(search(store, scope, "다른 신이 검은 용에 대해 뭐라고 했지?").isEmpty(), "no NPC role-only or recent fallback invents a matching recollection");
            var newcomer = UUID.randomUUID();
            var wider = scope(newcomer, Set.of(newcomer), Set.of(SELF, OUTSIDE), true);
            check(search(store, wider, "여명의 종").equals(List.of(publicRow)), "explicit PUBLIC speech can be retold to new players and Gods");
            check(search(store, wider, "신전 봉인").isEmpty(), "private speech cannot be laundered into a public room");
            check(search(store, scope(newcomer, Set.of(newcomer), GODS, false), "신전 봉인").isEmpty(), "new private audience cannot inherit private utterances");
            check(search(store, scope(PEER, Set.of(PEER), Set.of(SELF), false), "신전 봉인").containsAll(List.of(player, peer, other, self)),
                    "split room containing a subset of original listeners can recall heard speech");
            check(search(store, scope(PLAYER, PEOPLE, Set.of(SELF, OUTSIDE), false), "신전 봉인").isEmpty(), "merge with unapproved God excludes private rows");
            check(search(store, new RoomMemoryStore.Scope(WORLD, OUTSIDE, PLAYER, PEOPLE, Set.of(OUTSIDE), false), "여명의 종").isEmpty(),
                    "public disclosure permission does not fabricate a God having heard the original");
            check(search(store, new RoomMemoryStore.Scope(UUID.randomUUID(), SELF, PLAYER, PEOPLE, GODS, false), "신전 봉인").isEmpty(),
                    "world separation remains exact");
            check(search(store, scope, "검은 수정").equals(List.of(longRow)), "real lexical search finds a fact beyond the former 1200-character prefix");
            String prompt = RoomMemoryBridge.prompt(List.of(longRow, player, other), "검은 수정");
            check(prompt.contains("검은 수정은 지하 제단") && prompt.contains("NPC_UTTERANCE") && prompt.contains("PLAYER_STATEMENT")
                    && prompt.contains(PLAYER.toString()) && prompt.contains(OTHER) && prompt.contains("excerpt_start"),
                    "bounded prompt excerpt finds relevant tail without losing original speaker/source identity");
            check(store.append(longRow).get(5, TimeUnit.SECONDS) == RoomMemoryStore.Result.DUPLICATE, "publication callback duplicates are idempotent");
            var conflict = new RoomMemoryStore.Record(longRow.messageId(), WORLD, ROOM, 1, NOW, "NPC", OTHER, OTHER,
                    "변조된 원문", true, PEOPLE, GODS, List.of(), Set.of());
            check(store.append(conflict).get(5, TimeUnit.SECONDS) == RoomMemoryStore.Result.CONFLICT, "same message ID with changed text is rejected, not overwritten");
        }
        try (var store = new RoomMemoryStore(directory)) {
            check(store.awaitIdle(Duration.ofSeconds(5)), "real JSONL replay after restart");
            check(store.record(longRow.messageId()).orElseThrow().text().equals(longRow.text()), "restarted stored text is byte-for-byte full original");
            check(search(store, scope(PEER, PEOPLE, GODS, false), "다른 신이 신전 봉인을 뭐라고 했지?").equals(List.of(other)),
                    "cross-questioner NPC recall survives actual writer close/reopen");
            check(search(store, scope(PLAYER, PEOPLE, GODS, true), "신전 봉인").isEmpty(), "private-room origin is not forgotten after restart");
        }
    }
    private static void legacyReferences(Path directory) throws Exception {
        var entry = new MemoryJournal.Entry(UUID.randomUUID(), new MemoryJournal.Key(WORLD, SELF, PLAYER), UUID.randomUUID(), 1,
                MemoryJournal.Source.NPC_UTTERANCE, PEOPLE, NOW, "예전 원장의 신전 봉인 발언", false, GODS, OTHER);
        var proof = new LegacyRoomEvidence.Source(WORLD, SELF, PLAYER, "PERSONAL", entry.id(), DerivedMemory.fingerprint(entry));
        try (var journal = new MemoryJournal(directory)) {
            check(journal.append(entry).get(5, TimeUnit.SECONDS) == MemoryJournal.Result.STORED, "legacy original stored for portable source reference");
            check(LegacyRoomEvidence.journalCurrent(journal, proof, PEOPLE, Set.of(OTHER)),
                    "legacy proof follows original owner while a certified other listener recalls its utterance");
            check(!LegacyRoomEvidence.journalCurrent(journal, proof, PEOPLE, Set.of(SELF, OUTSIDE)), "legacy portable proof cannot widen NPC audience");
            check(!LegacyRoomEvidence.journalCurrent(journal, proof, Set.of(PLAYER, PEER, UUID.randomUUID()), GODS),
                    "legacy portable proof cannot widen player audience");
            var changed = new LegacyRoomEvidence.Source(WORLD, SELF, PLAYER, "PERSONAL", entry.id(), "0".repeat(64));
            check(!LegacyRoomEvidence.journalCurrent(journal, changed, PEOPLE, GODS), "source UUID alone cannot authorize changed legacy content");
        }
        try (var journal = new MemoryJournal(directory)) {
            check(journal.awaitIdle(Duration.ofSeconds(5)) && LegacyRoomEvidence.journalCurrent(journal, proof, PEOPLE, GODS),
                    "legacy evidence fingerprint and audience verify after real restart");
            check(journal.delete(entry.id(), journal.view().revision()).get(5, TimeUnit.SECONDS) == MemoryJournal.Result.STORED
                    && !LegacyRoomEvidence.journalCurrent(journal, proof, PEOPLE, GODS), "legacy deletion revokes its portable reference immediately");
        }
    }
    private static void ancestryRestart(Path directory) throws Exception {
        var ref = new RoomEvidenceReference("TEST_CURRENT_POLICY", "definition-fingerprint");
        var original = row("NPC", OTHER, OTHER, "봉인 계보의 원본 근거", false, PEOPLE, GODS, List.of(ref), Set.of());
        var child = row("NPC", SELF, SELF, "봉인 계보에 근거한 후속 발언", false, PEOPLE, GODS, List.of(), Set.of(original.messageId()));
        var descendant = row("NPC", OTHER, OTHER, "봉인 계보의 다시 전달된 발언", false, PEOPLE, GODS, List.of(), Set.of(child.messageId()));
        var missing = row("NPC", OTHER, OTHER, "원문은 실제로 들었으나 앞선 기록은 OFF였다", false, PEOPLE, GODS, List.of(), Set.of(UUID.randomUUID()));
        var scope = scope(PLAYER, PEOPLE, GODS, false);
        try (var store = new RoomMemoryStore(directory)) {
            for (var row : List.of(original, child, descendant, missing)) stored(store, row);
            check(store.evidenceCurrent(scope, Set.of(descendant.messageId()), ref::equals), "transitive ancestry resolves original authoritative evidence");
            check(!store.evidenceCurrent(scope, Set.of(descendant.messageId()), ignored -> false), "revoked definition invalidates descendants immediately");
            check(store.contains(missing.messageId()) && !store.evidenceCurrent(scope, Set.of(missing.messageId()), ignored -> true),
                    "missing OFF ancestor does not drop actual heard original, but cannot become durable truth");
        }
        try (var store = new RoomMemoryStore(directory)) {
            check(store.awaitIdle(Duration.ofSeconds(5)), "proof rows replay");
            check(store.evidenceCurrent(scope, Set.of(descendant.messageId()), ref::equals), "portable proof, source audience and ancestry survive restart");
            check(!store.evidenceCurrent(scope, Set.of(descendant.messageId()), ignored -> false), "restart never strips revoked or unknown proof kinds");
            check(store.retire(original.messageId()).get(5, TimeUnit.SECONDS) == RoomMemoryStore.Result.STORED, "explicit retirement preserves tombstone");
            check(store.retired(original.messageId()) && !store.evidenceCurrent(scope, Set.of(descendant.messageId()), ignored -> true),
                    "retired ancestor revokes every descendant without deleting their original text");
            check(store.append(original).get(5, TimeUnit.SECONDS) == RoomMemoryStore.Result.STALE, "late duplicate cannot resurrect a tombstoned source");
        }
        try (var store = new RoomMemoryStore(directory)) {
            check(store.awaitIdle(Duration.ofSeconds(5)) && store.retired(original.messageId()), "retirement itself survives restart");
            check(!store.evidenceCurrent(scope, Set.of(descendant.messageId()), ignored -> true), "restarted descendant still depends on retired ancestor");
        }
    }
    private static void iterativeGraph() {
        var ref = new RoomEvidenceReference("TEST_CURRENT_POLICY", "one shared reference");
        var map = new HashMap<UUID,RoomMemoryEvidence.Receipt>();
        UUID first = null, last = null, previous = null;
        for (int i = 0; i < 10000; i++) {
            var parents = new HashSet<UUID>(); if (last != null) parents.add(last); if (previous != null) parents.add(previous);
            UUID id = UUID.randomUUID();
            map.put(id, new RoomMemoryEvidence.Receipt(id, WORLD, ROOM, i, PEOPLE, GODS, false, List.of(ref), parents));
            if (first == null) first = id; previous = last; last = id;
        }
        var scope = scope(PLAYER, PEOPLE, GODS, false); var calls = new AtomicInteger();
        check(RoomMemoryEvidence.current(scope, List.of(), Set.of(last), map::get, ignored -> { calls.incrementAndGet(); return true; }),
                "ten thousand-turn shared ancestry has no stack, 64-depth or 1024-node cutoff");
        check(calls.get() == 1, "same policy proof is checked once per full DAG walk, not exponentially");
        calls.set(0);
        var lookups = new AtomicInteger();
        var roots = Set.of(last, previous, first);
        check(RoomMemoryEvidence.currentSources(scope, roots, id -> { lookups.incrementAndGet(); return map.get(id); },
                ignored -> { calls.incrementAndGet(); return true; }).equals(roots), "batch history validation retains every current source");
        check(calls.get() == 1 && lookups.get() == 10000, "batch source checks share one DAG/proof traversal across history lines");
        var original = map.remove(first);
        check(!RoomMemoryEvidence.current(scope, List.of(), Set.of(last), map::get, ignored -> true), "evicted OFF metadata remains fail-closed rather than becoming unguarded");
        map.put(first, original);
        UUID cycleA = UUID.randomUUID(), cycleB = UUID.randomUUID();
        map.put(cycleA, new RoomMemoryEvidence.Receipt(cycleA, WORLD, ROOM, 1, PEOPLE, GODS, false, List.of(), Set.of(cycleB)));
        map.put(cycleB, new RoomMemoryEvidence.Receipt(cycleB, WORLD, ROOM, 1, PEOPLE, GODS, false, List.of(), Set.of(cycleA)));
        check(!RoomMemoryEvidence.current(scope, List.of(), Set.of(cycleA), map::get, ignored -> true), "malformed circular evidence is rejected without recursion");
        var privateRoot = row("NPC", SELF, SELF, "개인 대화", false, Set.of(PLAYER), Set.of(SELF), List.of(), Set.of());
        var publicChild = row("NPC", SELF, SELF, "공개된 듯한 파생 대화", true, PEOPLE, GODS, List.of(), Set.of(privateRoot.messageId()));
        map.put(privateRoot.messageId(), RoomMemoryEvidence.Receipt.from(privateRoot)); map.put(publicChild.messageId(), RoomMemoryEvidence.Receipt.from(publicChild));
        check(!RoomMemoryEvidence.current(scope(PEER, PEOPLE, GODS, true), List.of(), Set.of(publicChild.messageId()), map::get, ignored -> true),
                "PUBLIC descendant cannot wash away PRIVATE ancestor permission");
        var oldPublic = row("NPC", OUTSIDE, OUTSIDE, "공개 소식을 전했다", true, PEOPLE, Set.of(OUTSIDE), List.of(), Set.of());
        var relayed = row("NPC", SELF, SELF, "실제 공개 소식을 들어 전달했다", true, PEOPLE, GODS, List.of(), Set.of(oldPublic.messageId()));
        map.put(oldPublic.messageId(), RoomMemoryEvidence.Receipt.from(oldPublic)); map.put(relayed.messageId(), RoomMemoryEvidence.Receipt.from(relayed));
        check(RoomMemoryEvidence.current(scope, List.of(), Set.of(relayed.messageId()), map::get, ignored -> true),
                "listener need not have attended every older public ancestor, only the actual directly recalled speech");
        check(!RoomMemoryEvidence.current(scope, List.of(), Set.of(oldPublic.messageId()), map::get, ignored -> true),
                "same older source cannot be recalled directly by a God that did not hear it");
    }
    private static void persistenceFailures(Path directory) throws Exception {
        Files.createDirectories(directory);
        var config = directory.resolve("capacity.json");
        Files.writeString(config, new Gson().toJson(new MemoryRetentionSettings(1, 1, 1, 65536, 0, 0)));
        var row = row("NPC", SELF, SELF, "작은 저장소의 첫 원문", true, PEOPLE, GODS, List.of(), Set.of());
        var path = directory.resolve("limited");
        try (var store = new RoomMemoryStore(path, config)) {
            stored(store, row);
            var second = row("NPC", SELF, SELF, "두 번째 원문", true, PEOPLE, GODS, List.of(), Set.of());
            check(store.append(second).get(5, TimeUnit.SECONDS) == RoomMemoryStore.Result.FULL, "capacity failure is explicit, never automatic old-record deletion");
            check(store.records().equals(List.of(row)), "first heard original survives capacity refusal unchanged");
            try (var competing = new RoomMemoryStore(path, config)) {
                check(!competing.awaitIdle(Duration.ofSeconds(5)) && competing.failed(), "second writer cannot share the same durable journal");
            }
        }
        var file = path.resolve("heard-dialogue.jsonl"); var good = Files.readString(file);
        Files.writeString(file, "{truncated", StandardOpenOption.APPEND);
        var damaged = Files.readString(file);
        try (var store = new RoomMemoryStore(path, config)) {
            check(!store.awaitIdle(Duration.ofSeconds(5)) && store.failed(), "corrupted restart is unavailable, not partially trusted");
            check(Files.readString(file).equals(damaged), "corrupt source bytes are preserved, not auto-repaired or overwritten");
            check(search(store, scope(PLAYER, PEOPLE, GODS, false), "저장소 원문").isEmpty(), "quarantined rows cannot leak through lexical search");
        }
        reject(() -> row("NPC", "bad id", "bad id", "잘못된 화자", false, PEOPLE, GODS, List.of(), Set.of()), "NPC speaker must have valid game identity syntax");
        reject(() -> new RoomMemoryStore.Scope(WORLD, SELF, PLAYER, PEOPLE, Set.of(SELF, "bad id"), false), "query cannot inject invalid God identity");
    }
    private static RoomDialogueEvent event(String role, String speaker, String text, RoomType type, RecordingScope recording,
            Map<UUID,String> names, Map<UUID,RoomDialogueEvent.Delivery> deliveries, Set<String> heard, Optional<UUID> turn) {
        return new RoomDialogueEvent(UUID.randomUUID(), ROOM, 1, turn, type, recording, role, speaker, text,
                GODS, names, deliveries, NOW, heard).withWorld(WORLD);
    }
    private static RoomMemoryStore.Record row(String role, String speaker, String name, String text, boolean publicSpeech,
            Set<UUID> players, Set<String> gods, List<RoomEvidenceReference> refs, Set<UUID> parents) {
        return new RoomMemoryStore.Record(UUID.randomUUID(), WORLD, ROOM, 1, NOW, role, speaker, name, text, publicSpeech, players, gods, refs, parents);
    }
    private static RoomMemoryStore.Scope scope(UUID requester, Set<UUID> players, Set<String> gods, boolean publicRoom) {
        return new RoomMemoryStore.Scope(WORLD, SELF, requester, players, gods, publicRoom);
    }
    private static List<RoomMemoryStore.Record> search(RoomMemoryStore store, RoomMemoryStore.Scope scope, String text) {
        return store.candidates(scope, text, Set.of(), 32, TimeUnit.SECONDS.toNanos(5));
    }
    private static void stored(RoomMemoryStore store, RoomMemoryStore.Record row) throws Exception {
        check(store.append(row).get(5, TimeUnit.SECONDS) == RoomMemoryStore.Result.STORED, "real full record persisted");
    }
    private static void reject(Runnable action, String message) {
        try { action.run(); } catch (IllegalArgumentException expected) { check(true, message); return; }
        throw new AssertionError(message);
    }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
