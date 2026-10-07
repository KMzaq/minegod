package com.sande.mythai.response.memory;

import com.google.gson.Gson;
import com.sande.mythictrpg.ai.api.RoomConversationEngine;
import com.sande.mythictrpg.ai.api.RoomDialogueEvent;
import com.sande.mythictrpg.ai.api.RoomEvidenceReference;
import com.sande.mythictrpg.ai.room.RecordingScope;
import com.sande.mythictrpg.ai.room.RoomType;
import com.sande.mythictrpg.ai.memorycontract.MemoryFoundationSettings.Mode;
import com.sande.mythictrpg.recording.api.MemoryReadSession;
import net.minecraft.resources.ResourceLocation;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
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
        shadowFailureIsolation();
        shadowBoundedPagination();
        modeIsolation(root.resolve("heard-modes"));
        searchAndRestart(root.resolve("heard-room-store"));
        questionEchoSuppression(root.resolve("heard-question-echo"));
        temporalSearch(root.resolve("heard-temporal-search"));
        ancestryRestart(root.resolve("heard-room-ancestry"));
        legacyReferences(root.resolve("heard-legacy-source"));
        iterativeGraph();
        persistenceFailures(root.resolve("heard-room-failures"));
        return checks;
    }
    private static void shadowFailureIsolation() {
        var god=ResourceLocation.parse(SELF);
        var request=new RoomConversationEngine.Request(ROOM,1,UUID.randomUUID(),PLAYER,"player",List.of(god),god,
                "shadow query",List.of(),true,true,false,List.of(new RoomConversationEngine.GodState(god,"","","",null)),false,Set.of(PLAYER));
        var before=RecordedMemoryShadow.diagnostics();
        RecordedMemoryShadow.compare(() -> { throw new IllegalStateException("private diagnostic must not be logged"); },request,Runnable::run);
        RecordedMemoryShadow.compare(Optional::empty,request,Runnable::run);
        RecordedMemoryShadow.compare(() -> Optional.of(shadowSession(1)),request,Runnable::run);
        RecordedMemoryShadow.compare(() -> Optional.of(shadowSession(0)),request,
                task -> { throw new java.util.concurrent.RejectedExecutionException("stopped dispatcher"); });
        RecordedMemoryShadow.compare(() -> Optional.of(shadowSession(3)),request,Runnable::run);
        RecordedMemoryShadow.compare(() -> Optional.of(shadowSession(2)),request,Runnable::run);
        var after=RecordedMemoryShadow.diagnostics();
        check(after.get("unavailable")==before.get("unavailable")+5&&after.get("completed").equals(before.get("completed")),
                "optional SHADOW opener/query/dispatch/proof/future failures are isolated as aggregate diagnostics");
        RecordedMemoryShadow.compare(() -> Optional.of(shadowSession(0)),request,Runnable::run);
        var recovered=RecordedMemoryShadow.diagnostics();
        check(recovered.get("completed")==before.get("completed")+1
                &&recovered.get("recentHistoryMatches").equals(before.get("recentHistoryMatches"))
                &&recovered.keySet().equals(Set.of("completed","unavailable","recentHistoryMatches","legacySelected","legacyMatches")),
                "failed SHADOW comparison cannot prevent later comparison or expose raw diagnostic details");
        var shared = UUID.randomUUID(); var onlyLegacy = UUID.randomUUID();
        var archived = new MemoryReadSession() {
            public CompletableFuture<Page> query(Query q, Optional<Cursor> c, Budget b) {
                return CompletableFuture.completedFuture(new Page(Status.PARTIAL, List.of(new Entry(shared,
                        new com.sande.mythictrpg.recording.api.RecordingRecords.ActorRef(
                                com.sande.mythictrpg.recording.api.RecordingRecords.ActorKind.PLAYER, PLAYER.toString()),
                        Instant.now(), "private quote is never logged", false)), Optional.empty()));
            }
            public boolean current(Page p) { return true; }
        };
        RecordedMemoryShadow.compare(() -> Optional.of(archived), request, Runnable::run, Set.of(shared, onlyLegacy));
        var compared = RecordedMemoryShadow.diagnostics();
        check(compared.get("legacySelected") == recovered.get("legacySelected") + 2
                && compared.get("legacyMatches") == recovered.get("legacyMatches") + 1,
                "SHADOW compares actual existing reader selection without adding a legacy query or changing its result");
    }
    private static MemoryReadSession shadowSession(int failure) {
        return new MemoryReadSession() {
            @Override public CompletableFuture<Page> query(Query query,Optional<Cursor> cursor,Budget budget) {
                if(failure==1)throw new IllegalStateException("query unavailable");
                if(failure==2)return CompletableFuture.failedFuture(new IllegalStateException("reader failed"));
                return CompletableFuture.completedFuture(new Page(Status.PARTIAL,List.of(),Optional.empty()));
            }
            @Override public boolean current(Page page) {
                if(failure==3)throw new IllegalStateException("proof unavailable");
                return true;
            }
        };
    }
    private static final class ShadowSequence implements MemoryReadSession {
        final List<CompletableFuture<Page>> answers;
        final List<Budget> budgets = new ArrayList<>();
        final Set<Page> revoked = Collections.newSetFromMap(new IdentityHashMap<>());
        final List<Optional<Cursor>> cursors = new ArrayList<>();
        Query originalQuery;
        ShadowSequence(List<CompletableFuture<Page>> answers) { this.answers = answers; }
        @Override public CompletableFuture<Page> query(Query query, Optional<Cursor> cursor, Budget budget) {
            if (originalQuery == null) originalQuery = query;
            check(originalQuery.equals(query), "SHADOW continuation keeps exact original query");
            int call = budgets.size(); budgets.add(budget); cursors.add(cursor);
            check(call < 3 && call < answers.size(), "SHADOW is bounded to three sequential archive queries");
            if (call == 0) check(cursor.isEmpty(), "first SHADOW page has no forged cursor");
            else check(answers.get(call - 1).isDone() && answers.get(call - 1).join().next().equals(cursor), "later SHADOW page uses only preceding returned cursor");
            return answers.get(call);
        }
        @Override public boolean current(Page page) { return !revoked.contains(page); }
    }
    private static MemoryReadSession.Page shadowPage(List<UUID> ids, String text, boolean more) {
        var actor = new com.sande.mythictrpg.recording.api.RecordingRecords.ActorRef(
                com.sande.mythictrpg.recording.api.RecordingRecords.ActorKind.PLAYER, PLAYER.toString());
        return new MemoryReadSession.Page(MemoryReadSession.Status.PARTIAL,
                ids.stream().map(id -> new MemoryReadSession.Entry(id, actor, Instant.now(), text, false)).toList(),
                more ? Optional.of(MemoryReadSession.Cursor.unregistered()) : Optional.empty());
    }
    private static ShadowSequence shadowSequence(MemoryReadSession.Page... pages) {
        return new ShadowSequence(Arrays.stream(pages).map(CompletableFuture::completedFuture).toList());
    }
    private static void shadowBoundedPagination() {
        var firstId = UUID.randomUUID(); var lateId = UUID.randomUUID(); var neverRead = UUID.randomUUID();
        var god = ResourceLocation.parse(SELF);
        var request = new RoomConversationEngine.Request(ROOM, 1, UUID.randomUUID(), PLAYER, "player", List.of(god), god,
                "shadow query", List.of(new RoomConversationEngine.HistoryLine("PLAYER", PLAYER.toString(), "player", "old text", ROOM, firstId)),
                true, true, false, List.of(new RoomConversationEngine.GodState(god, "", "", "", null)), false, Set.of(PLAYER));
        var one = shadowPage(List.of(firstId), "first", true);
        var two = shadowPage(List.of(firstId), "duplicate", true);
        var three = shadowPage(List.of(lateId), "later private text must never escape", true);
        var sequence = shadowSequence(one, two, three, shadowPage(List.of(neverRead), "must not query", false));
        var before = RecordedMemoryShadow.diagnostics();
        RecordedMemoryShadow.compare(() -> Optional.of(sequence), request, Runnable::run, Set.of(firstId, lateId, neverRead));
        var after = RecordedMemoryShadow.diagnostics();
        check(sequence.budgets.size() == 3 && after.get("completed") == before.get("completed") + 1,
                "later pages contribute without exceeding three-page comparison bound");
        check(after.get("legacyMatches") == before.get("legacyMatches") + 2 && after.get("recentHistoryMatches") == before.get("recentHistoryMatches") + 1,
                "duplicate message IDs count once and later-page legacy selection is compared");
        check(after.get("legacySelected") == before.get("legacySelected") + 3,
                "actual legacy selection is counted once for the whole comparison");
        check(sequence.budgets.get(1).rows() == 3 && sequence.budgets.get(2).rows() == 3
                        && sequence.budgets.get(2).utf8Bytes() == 4096 - "first".length() - "duplicate".length(),
                "remaining row budget counts unique messages but aggregate bytes count duplicate content too");

        var firstFuture = new CompletableFuture<MemoryReadSession.Page>();
        var secondFuture = new CompletableFuture<MemoryReadSession.Page>();
        var delayed = new ShadowSequence(List.of(firstFuture, secondFuture));
        before = RecordedMemoryShadow.diagnostics();
        RecordedMemoryShadow.compare(() -> Optional.of(delayed), request, Runnable::run, Set.of(firstId, lateId));
        check(delayed.budgets.size() == 1 && RecordedMemoryShadow.diagnostics().get("completed").equals(before.get("completed")),
                "no speculative parallel page queries or early diagnostics while first page pending");
        firstFuture.complete(one);
        check(delayed.budgets.size() == 2 && RecordedMemoryShadow.diagnostics().get("legacyMatches").equals(before.get("legacyMatches")),
                "next page starts only after first returns and no first-page hits are published early");
        delayed.revoked.add(one); secondFuture.complete(shadowPage(List.of(lateId), "still current second page", false));
        after = RecordedMemoryShadow.diagnostics();
        check(after.get("unavailable") == before.get("unavailable") + 1 && after.get("completed").equals(before.get("completed"))
                        && after.get("legacyMatches").equals(before.get("legacyMatches")) && after.get("legacySelected").equals(before.get("legacySelected")),
                "withdrawn first-page proof invalidates entire comparison even when last page is current");

        var fourIds = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        var four = shadowSequence(shadowPage(fourIds, "x", true));
        RecordedMemoryShadow.compare(() -> Optional.of(four), request, Runnable::run, Set.copyOf(fourIds));
        check(four.budgets.size() == 1, "four unique messages stop further pagination");
        var bytes = shadowSequence(shadowPage(List.of(firstId), "x".repeat(3000), true), shadowPage(List.of(lateId), "y".repeat(1000), true));
        RecordedMemoryShadow.compare(() -> Optional.of(bytes), request, Runnable::run);
        check(bytes.budgets.size() == 2 && bytes.budgets.get(1).utf8Bytes() == 1096,
                "all pages share one 4KiB budget and <256 remaining bytes stop next query");

        var oversize = shadowSequence(shadowPage(List.of(firstId), "가".repeat(2000), false));
        before = RecordedMemoryShadow.diagnostics();
        RecordedMemoryShadow.compare(() -> Optional.of(oversize), request, Runnable::run, Set.of(firstId));
        after = RecordedMemoryShadow.diagnostics();
        check(after.get("unavailable") == before.get("unavailable") + 1 && after.get("completed").equals(before.get("completed")),
                "misbehaving response exceeding requested UTF-8 bytes contributes no diagnostics");
        var unavailablePage = shadowSequence(new MemoryReadSession.Page(MemoryReadSession.Status.UNAVAILABLE, List.of(), Optional.empty()));
        before = RecordedMemoryShadow.diagnostics();
        RecordedMemoryShadow.compare(() -> Optional.of(unavailablePage), request, Runnable::run);
        check(RecordedMemoryShadow.diagnostics().get("unavailable") == before.get("unavailable") + 1,
                "unavailable status is not a successful empty comparison even if fake current returns true");

        var finalProofCalls = new AtomicInteger();
        var lastMoment = new MemoryReadSession() {
            public CompletableFuture<Page> query(Query query, Optional<Cursor> cursor, Budget budget) {
                return CompletableFuture.completedFuture(shadowPage(List.of(firstId), "quote", false));
            }
            public boolean current(Page page) { return finalProofCalls.incrementAndGet() < 3; }
        };
        before = RecordedMemoryShadow.diagnostics();
        RecordedMemoryShadow.compare(() -> Optional.of(lastMoment), request, Runnable::run, Set.of(firstId));
        after = RecordedMemoryShadow.diagnostics();
        check(finalProofCalls.get() == 3 && after.get("unavailable") == before.get("unavailable") + 1
                        && after.get("legacyMatches").equals(before.get("legacyMatches")),
                "all pages are checked again immediately before writing aggregate diagnostics");
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
            var projection = RoomMemoryBridge.projectRecall(List.of(longRow, player, other), "검은 수정");
            check(projection.context().equals(prompt) && projection.promptVariants().size() == 3
                    && projection.promptVariants().get(1).equals(RoomMemoryBridge.prompt(List.of(longRow, player), "검은 수정"))
                    && projection.promptVariants().get(2).equals(RoomMemoryBridge.prompt(List.of(longRow), "검은 수정")),
                    "budget variants preserve ranked whole records with each original source and excerpt attribution");
            check(projection.sourceMessageIds().equals(Set.of(longRow.messageId(), player.messageId(), other.messageId())),
                    "budget selection retains conservative complete source revalidation dependency set");
            check(RoomMemoryBridge.Recall.EMPTY.promptVariants().isEmpty(), "no recall remains no recall, not a fabricated empty event");
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
    private static void questionEchoSuppression(Path directory) throws Exception {
        String question = "내가 신전 봉인을 뭐라고 했지?";
        var currentQuestion = row("PLAYER", PLAYER.toString(), "가람", question, false, PEOPLE, GODS, List.of(), Set.of());
        var previousQuestion = row("PLAYER", PLAYER.toString(), "가람", "내가 신전 봉인을 뭐라고 말했지?", false, PEOPLE, GODS, List.of(), Set.of());
        var followUp = row("PLAYER", PLAYER.toString(), "가람", "다시 알려줘", false, PEOPLE, GODS, List.of(), Set.of());
        var statement = row("PLAYER", PLAYER.toString(), "가람", "신전 봉인은 지하 제단에 있어", false, PEOPLE, GODS, List.of(), Set.of());
        var ordinaryQuestion = row("PLAYER", PLAYER.toString(), "가람", "신전 봉인을 언제 열어?", false, PEOPLE, GODS, List.of(), Set.of());
        var npcQuestion = row("NPC", SELF, SELF, "네가 신전 봉인을 뭐라고 했지?", false, PEOPLE, GODS, List.of(), Set.of());
        var scope = scope(PLAYER, PEOPLE, GODS, false);
        try (var store = new RoomMemoryStore(directory)) {
            for (var row : List.of(currentQuestion, previousQuestion, followUp, npcQuestion)) stored(store, row);
            check(search(store, scope, question).isEmpty(),
                    "current and previous explicit recall questions cannot answer the player's recall request");
            check(search(store, scope, "다시 알려줘 기억나?").isEmpty(),
                    "a bare follow-up remains recorded words, not an answer to explicit recall");
            check(search(store, scope, "네가 신전 봉인을 뭐라고 했지?").isEmpty(),
                    "NPC recall questions cannot become the God's recalled answer either");
            check(store.record(currentQuestion.messageId()).isPresent() && store.record(followUp.messageId()).isPresent(),
                    "recall filtering preserves original questions and their evidence identities");
            for (var row : List.of(statement, ordinaryQuestion)) stored(store, row);
            check(new HashSet<>(search(store, scope, question)).equals(Set.of(statement, ordinaryQuestion)),
                    "meaningful ordinary questions and statements remain eligible attributed words");
            check(!search(store, scope, statement.text()).contains(statement),
                    "current non-recall input is not immediately echoed as earlier memory");
            check(search(store, scope, "내가 신전 봉인 지하 제단을 뭐라고 말했지?").contains(statement),
                    "excluding identical current input does not erase its later explicit recall");
        }
    }
    private static void temporalSearch(Path directory) throws Exception {
        Files.createDirectories(directory);
        Path config = directory.resolve("ai-recall.json");
        Files.writeString(config, "{\"schemaVersion\":1,\"recallV2\":true,\"timeBasis\":\"REAL_KST\"}");
        long askedAt = Instant.parse("2026-09-15T14:59:30Z").toEpochMilli();
        long yesterday = askedAt - Duration.ofDays(1).toMillis();
        var scope = scope(PLAYER, PEOPLE, GODS, false);
        var todayPlan = at(row("PLAYER", PLAYER.toString(), "가람", "내일 바다 성소에 가기로 했어", false, PEOPLE, GODS, List.of(), Set.of()), askedAt - 60_000);
        var yesterdayPlan = at(row("PLAYER", PLAYER.toString(), "가람", todayPlan.text(), false, PEOPLE, GODS, List.of(), Set.of()), yesterday);
        var question = roomQuery(scope, "내가 내일 바다 성소 계획을 뭐라고 했지?", askedAt);
        try (var store = new RoomMemoryStore(directory.resolve("real"), null, config)) {
            stored(store, todayPlan); stored(store, yesterdayPlan);
            check(store.recallSettings().equals(new RecallSettings(true, RecallSettings.TimeBasis.REAL_KST)),
                    "room writer loads existing recall policy from the supplied server config path");
            check(search(store, scope, question, askedAt).equals(List.of(todayPlan)),
                    "identical tomorrow plans retain their original KST dates instead of shifting to retrieval day");
            var saidYesterday = roomQuery(scope, "내가 어제 말한 내일 바다 성소 일정이 뭐였지?", askedAt);
            check(search(store, scope, saidYesterday, askedAt).equals(List.of(yesterdayPlan)),
                    "recorded day and planned day remain distinct in yesterday's tomorrow question");

            long afterMidnight = askedAt + 60_000;
            var laterPlan = at(row("PLAYER", PLAYER.toString(), "가람", todayPlan.text(), false, PEOPLE, GODS, List.of(), Set.of()), askedAt + 40_000);
            stored(store, laterPlan);
            var followUp = RecallQuery.plan(question.scope(), "다시 알려줘", 2, afterMidnight, question.focus());
            check(followUp.followUp() && search(store, scope, followUp, afterMidnight).equals(List.of(todayPlan)),
                    "follow-up crossing KST midnight keeps the original question's day and excludes the newly dated plan");
            var nextDayQuestion = roomQuery(scope, question.text(), afterMidnight);
            check(search(store, scope, nextDayQuestion, afterMidnight).equals(List.of(laterPlan)),
                    "a fresh question after midnight receives its own calendar anchor");

            var oldWords = at(row("PLAYER", PLAYER.toString(), "가람", "푸른 동굴의 봉인은 닫혀 있어", false, PEOPLE, GODS, List.of(), Set.of()), yesterday);
            var newWords = at(row("PLAYER", PLAYER.toString(), "가람", oldWords.text(), false, PEOPLE, GODS, List.of(), Set.of()), askedAt - 30_000);
            stored(store, oldWords); stored(store, newWords);
            check(search(store, scope, roomQuery(scope, "내가 어제 말한 푸른 동굴 봉인이 뭐였지?", askedAt), askedAt).equals(List.of(oldWords)),
                    "recording-date filter also applies to non-plan statements");
            check(search(store, scope, roomQuery(scope, "내가 오늘 말한 푸른 동굴 봉인이 뭐였지?", askedAt), askedAt).equals(List.of(newWords)),
                    "today's recorded words do not select yesterday's identical utterance");

            var undatedPlan = at(row("PLAYER", PLAYER.toString(), "가람", "바다 성소에 갈 계획은 날짜 미정이야", false, PEOPLE, GODS, List.of(), Set.of()), askedAt - 20_000);
            var cancelledPlan = at(row("PLAYER", PLAYER.toString(), "가람", "내일 바다 성소 일정은 취소야", false, PEOPLE, GODS, List.of(), Set.of()), askedAt - 10_000);
            stored(store, undatedPlan); stored(store, cancelledPlan);
            check(new HashSet<>(search(store, scope, question, askedAt)).equals(Set.of(todayPlan, undatedPlan, cancelledPlan)),
                    "unknown dates and an explicit cancellation stay attributed raw alternatives without invented completion or supersession");
            var currentQuestion = at(row("PLAYER", PLAYER.toString(), "가람", question.text(), false, PEOPLE, GODS, List.of(), Set.of()), askedAt);
            stored(store, currentQuestion);
            check(!search(store, scope, question, askedAt).contains(currentQuestion),
                    "typed temporal retrieval preserves current-question self-echo exclusion");

            var godPlan = at(row("NPC", SELF, SELF, "내일 바다 성소에서 만나기로 했어", false, PEOPLE, GODS, List.of(), Set.of()), askedAt - 60_000);
            var oldGodPlan = at(row("NPC", SELF, SELF, godPlan.text(), false, PEOPLE, GODS, List.of(), Set.of()), yesterday);
            stored(store, godPlan); stored(store, oldGodPlan);
            check(search(store, scope, roomQuery(scope, "네가 내일 바다 성소 계획을 뭐라고 했지?", askedAt), askedAt).equals(List.of(godPlan)),
                    "God-word source filters and original plan dates compose without selecting player words");
            var publicScope = scope(PLAYER, PEOPLE, GODS, true);
            check(search(store, publicScope, roomQuery(publicScope, question.text(), askedAt), askedAt).isEmpty(),
                    "date constraints do not widen private words into public recall");
            var mismatched = roomQuery(scope(PEER, PEOPLE, GODS, false), question.text(), askedAt);
            check(search(store, scope, mismatched, askedAt).isEmpty(), "typed query requester must match the current game scope");

            String longText = "긴 원문 설명 ".repeat(300) + "내일 별빛 항구에 가기로 했어";
            var longPlan = at(row("PLAYER", PLAYER.toString(), "가람", longText, false, PEOPLE, GODS, List.of(), Set.of()), askedAt - 60_000);
            var oldLongPlan = at(row("PLAYER", PLAYER.toString(), "가람", longText, false, PEOPLE, GODS, List.of(), Set.of()), yesterday);
            stored(store, longPlan); stored(store, oldLongPlan);
            check(search(store, scope, roomQuery(scope, "내가 내일 별빛 항구 일정을 뭐라고 했지?", askedAt), askedAt).equals(List.of(longPlan)),
                    "full room text keeps date and topic evidence beyond legacy entry length limits");
            var ancient = at(row("PLAYER", PLAYER.toString(), "가람", "먼 별의 수정 봉인을 지켰어", false, PEOPLE, GODS, List.of(), Set.of()), askedAt - Duration.ofDays(400).toMillis());
            stored(store, ancient);
            check(search(store, scope, roomQuery(scope, "내가 먼 별의 수정 봉인을 뭐라고 했지?", askedAt), askedAt).contains(ancient),
                    "typed temporal retrieval does not import a legacy age-based expiration");

            var projection = RoomMemoryBridge.projectRecall(List.of(todayPlan, cancelledPlan, undatedPlan), followUp, store.recallSettings());
            check(projection.context().contains("Question date=2026-09-15")
                    && projection.context().contains("mentioned_plan_date_kst_not_completion")
                    && projection.context().contains("2026-09-16"),
                    "prompt preserves the original question date and labels planned dates as uncompleted claims");
            check(projection.sourceMessageIds().equals(Set.of(todayPlan.messageId(), cancelledPlan.messageId(), undatedPlan.messageId()))
                    && projection.promptVariants().size() == 3,
                    "temporal prompt variants retain all source dependencies and whole-record budgeting");
            check(store.retire(todayPlan.messageId()).get(5, TimeUnit.SECONDS) == RoomMemoryStore.Result.STORED
                    && !search(store, scope, question, askedAt).contains(todayPlan),
                    "a date match cannot resurrect a retired source");
        }
        try (var reopened = new RoomMemoryStore(directory.resolve("real"), null, config)) {
            check(reopened.awaitIdle(Duration.ofSeconds(5))
                    && search(reopened, scope, roomQuery(scope, "내가 어제 말한 내일 바다 성소 일정이 뭐였지?", askedAt), askedAt).equals(List.of(yesterdayPlan)),
                    "typed date filtering survives real writer close/reopen without changing raw storage format");
        }
        for (String policy : List.of("{\"schemaVersion\":1,\"recallV2\":false,\"timeBasis\":\"REAL_KST\"}",
                "{\"schemaVersion\":1,\"recallV2\":true,\"timeBasis\":\"UNSPECIFIED\"}")) {
            Path other = directory.resolve(policy.contains("false") ? "disabled" : "unspecified");
            Files.createDirectories(other); Path otherConfig = other.resolve("ai-recall.json"); Files.writeString(otherConfig, policy);
            try (var store = new RoomMemoryStore(other.resolve("store"), null, otherConfig)) {
                stored(store, todayPlan); stored(store, yesterdayPlan);
                check(new HashSet<>(search(store, scope, question, askedAt)).equals(Set.of(todayPlan, yesterdayPlan)),
                        "disabled or unspecified calendar policy does not guess dates or discard permitted words");
            }
        }
    }
    private static RoomMemoryStore.Record at(RoomMemoryStore.Record row, long occurredAt) {
        return new RoomMemoryStore.Record(row.messageId(), row.worldId(), row.sourceRoomId(), row.sourceRevision(), occurredAt,
                row.role(), row.speakerId(), row.speakerName(), row.text(), row.publicSpeech(), row.fullPlayerAudience(), row.heardGodIds(), row.evidenceRefs(), row.sourceMessageIds());
    }
    private static RecallQuery roomQuery(RoomMemoryStore.Scope scope, String text, long askedAt) {
        return RecallQuery.plan(new RecallQuery.Scope(new MemoryJournal.Key(scope.worldId(), scope.readerGodId(), scope.requesterId()),
                ROOM, scope.playerAudience()), text, 1, askedAt, null);
    }
    private static List<RoomMemoryStore.Record> search(RoomMemoryStore store, RoomMemoryStore.Scope scope, RecallQuery query, long now) {
        return store.candidates(scope, query, Set.of(), 32, TimeUnit.SECONDS.toNanos(5), now);
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
