package com.sande.mythai.response.memory;

import com.google.gson.JsonParser;
import com.sande.mythictrpg.ai.intent.ConversationAct;
import com.sande.mythictrpg.ai.intent.ConversationIntent;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Stage 1 offline fixtures: no server bootstrap, model call, live-world read or embedding. */
public final class RecallStageTest {
    private static int checks;
    private static final long NOW = Instant.parse("2026-09-15T08:00:00Z").toEpochMilli();
    private static final UUID WORLD = UUID.randomUUID(), PLAYER = UUID.randomUUID(), SESSION = UUID.randomUUID(), OLD = UUID.randomUUID();
    private static final MemoryJournal.Key KEY = new MemoryJournal.Key(WORLD, "mythictrpg:fortuna", PLAYER);
    private static final RecallQuery.Scope SCOPE = new RecallQuery.Scope(KEY, SESSION, Set.of(PLAYER));
    private static final RecallSettings REAL = new RecallSettings(true, RecallSettings.TimeBasis.REAL_KST);
    private static void check(boolean condition, String message) {
        checks++; if (!condition) throw new AssertionError(message);
    }
    private static MemoryJournal.Entry entry(String text, long at) {
        return new MemoryJournal.Entry(UUID.randomUUID(), KEY, OLD, 1, MemoryJournal.Source.PLAYER_STATEMENT, Set.of(PLAYER), at, text, false);
    }
    private static RecallQuery query(String text) { return RecallQuery.plan(SCOPE, text, 1, NOW, null); }
    private static RecallSearch.Result search(List<MemoryJournal.Entry> entries, String text) {
        return RecallSearch.search(new MemoryJournal.ReadView(KEY, Set.of(PLAYER), entries, Set.of(), true, false),
                query(text), REAL, Set.of(), List.of(), NOW, 1_000_000_000);
    }
    private static void put(MemoryJournal journal, MemoryJournal.Entry entry) throws Exception {
        check(journal.append(entry).get(5, TimeUnit.SECONDS) == MemoryJournal.Result.STORED, "fixture stored");
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory(Path.of(args[0]), "recall-stage01-");
        checks += com.sande.mythictrpg.ai.RecallDirectiveChecks.run();
        var plan = entry("나 내일 바다에 가게 됬어", NOW - 60_000);
        var q = query("내가 내일 어디간다고 했더라");
        check(q.explicit() && q.planQuestion(), "September 15 indirect recall without question mark");
        for (String rewording : List.of("내일 내가 어디 가기로 했지", "전에 말한 내 일정이 뭐였지", "내가 내일 간다고 말한 곳이 어디였지", "내일 내 계획이 뭐였더라")) {
            var r = search(List.of(plan), rewording);
            check(r.query().explicit() && r.selected().contains(plan), "paraphrased plan retrieval: " + rewording);
        }
        check(search(List.of(plan), q.text()).status() == RecallSearch.Status.FOUND, "one dated plan supported");
        for (int turn = 2; turn <= 4; turn++) {
            var next = RecallQuery.plan(SCOPE, "한번만 말해주라", turn, NOW + 1000 * turn, q.focus());
            check(next.followUp() && next.text().equals(q.text()) && next.askedAt() == NOW, "follow-up anchors original question/time");
        }
        check(!RecallQuery.plan(SCOPE, "다시 알려줘", 5, NOW + 4000, q.focus()).explicit(), "three-turn expiry");
        check(!RecallQuery.plan(SCOPE, "다시 알려줘", 2, NOW + 300001, q.focus()).explicit(), "five-minute expiry");
        check(!RecallQuery.plan(SCOPE, "다시 알려줘", 2, NOW - 1, q.focus()).explicit(), "clock reversal expires");
        for (String unrelated : List.of("다른 얘기 하자", "오늘은 무기 제작 재료를 찾을래", "고마워", "응", "그만하자")) {
            check(RecallQuery.plan(SCOPE, unrelated, 2, NOW + 1000, q.focus()).focus() == null, "topic/ack clears focus");
        }
        for (RecallQuery.Scope other : List.of(new RecallQuery.Scope(KEY, UUID.randomUUID(), Set.of(PLAYER)),
                new RecallQuery.Scope(new MemoryJournal.Key(WORLD, "mythictrpg:demeter", PLAYER), SESSION, Set.of(PLAYER)),
                new RecallQuery.Scope(KEY, SESSION, Set.of(PLAYER, UUID.randomUUID())),
                new RecallQuery.Scope(new MemoryJournal.Key(UUID.randomUUID(), KEY.god(), PLAYER), SESSION, Set.of(PLAYER)))) {
            check(!RecallQuery.plan(other, "알려줘", 2, NOW + 1000, q.focus()).explicit(), "scope/generation boundary");
        }
        check(!RecallQuery.plan(SCOPE, "한번만 말해줘", 1, NOW, null).explicit(), "no focus resurrected across sessions");
        check(RecallQuery.plan(SCOPE, "기억나?", 2, NOW + 1000, q.focus()).text().equals(q.text()), "bare memory query keeps focus");
        check(!RecallQuery.plan(SCOPE, "다시 사냥하러 갈 거야", 2, NOW + 1000, q.focus()).explicit(), "new action is not a recall follow-up");
        var followUp = RecallQuery.plan(SCOPE, "한번만 말해주라", 2, NOW + 1000, q.focus());
        var withQuestion = new MemoryJournal.ReadView(KEY, Set.of(PLAYER), List.of(plan, entry(q.text(), NOW)), Set.of(), true, false);
        check(RecallSearch.search(withQuestion, followUp, REAL, Set.of(plan.text()), List.of(q.text()), NOW + 1000,
                1_000_000_000).selected().equals(List.of(plan)), "recent suppression does not hide needed raw evidence; query never answers itself");

        var oldDate = entry("내일 산에 갈 거야", NOW - Duration.ofDays(1).toMillis());
        check(search(List.of(oldDate, plan), "내일 내가 어디 가기로 했지").selected().equals(List.of(plan)), "utterance-relative calendar day");
        check(search(List.of(oldDate), "내일 내가 어디 가기로 했지").selected().isEmpty(), "fallback cannot resurrect wrong date");
        check(search(List.of(oldDate, plan), "어제 말한 내일 일정이 뭐였지").selected().equals(List.of(oldDate)), "recorded day vs event day");
        check(RecallSearch.date("내일", Instant.parse("2026-09-15T15:01:00Z").toEpochMilli(), REAL.timeBasis())
                .equals(LocalDate.of(2026,9,17)), "KST midnight");
        check(RecallSearch.date("내일", NOW, RecallSettings.TimeBasis.UNSPECIFIED) == null, "unapproved time policy not guessed");
        check(RecallSearch.date("게임에서 내일", NOW, REAL.timeBasis()) == null, "game time not converted to reality");
        var unknownTime = RecallSearch.search(new MemoryJournal.ReadView(KEY, Set.of(PLAYER), List.of(plan), Set.of(), true, false),
                q, new RecallSettings(true, RecallSettings.TimeBasis.UNSPECIFIED), Set.of(), List.of(), NOW, 1_000_000_000);
        check(unknownTime.selected().contains(plan), "undecided date policy does not hide original words");
        var correction = entry("바다 말고 산에 가기로 했어", NOW - 1000);
        var additional = entry("산에도 갈 거야", NOW - 500);
        var corrected = search(List.of(plan, correction), q.text());
        check(corrected.selected().containsAll(List.of(plan, correction)) && corrected.status() == RecallSearch.Status.AMBIGUOUS,
                "raw corrections retained as alternatives, no destructive auto-supersede");
        check(search(List.of(plan, additional), q.text()).selected().containsAll(List.of(plan, additional)), "additional plan not erased");
        for (String quote : List.of("내일 가기로 했다는 건 농담이야", "내일 간다면 좋겠어", "친구가 내일 간다고 말했어", "내일 일정은 취소야"))
            check(search(List.of(entry(quote, NOW)), q.text()).status() != RecallSearch.Status.FOUND, "uncertain/cancelled/attributed source: " + quote);
        var npc = new MemoryJournal.Entry(UUID.randomUUID(), KEY, OLD, 2, MemoryJournal.Source.NPC_UTTERANCE, Set.of(PLAYER), NOW, "내일 바다에 가기로 했지", false);
        check(search(List.of(npc), q.text()).selected().isEmpty(), "NPC text not player evidence");
        check(search(List.of(), q.text()).status() == RecallSearch.Status.NO_MATCH, "no match is explicit");
        var expired = entry("내일 성소에 갈 거야", NOW - Duration.ofDays(31).toMillis());
        check(search(List.of(expired), "내 일정 뭐라고 했지").selected().isEmpty(), "v1 casual expiry preserved");
        var pinned = new MemoryJournal.Entry(expired.id(), KEY, OLD, 1, expired.source(), expired.audience(), expired.occurredAt(), expired.text(), true);
        check(search(List.of(pinned), "내 일정 뭐라고 했지").selected().contains(pinned), "pin survives expiry without declaring completion");
        var wrongScope = new MemoryJournal.ReadView(new MemoryJournal.Key(WORLD, "mythictrpg:demeter", PLAYER), Set.of(PLAYER), List.of(plan), Set.of(), true, false);
        check(RecallSearch.search(wrongScope, q, REAL, Set.of(), List.of(), NOW, 1_000_000).selected().isEmpty(), "reject mismatched read contract");
        check(RecallSearch.search(new MemoryJournal.ReadView(KEY, Set.of(PLAYER), List.of(), Set.of(), false, false), q,
                REAL, Set.of(), List.of(), NOW, 1).status() == RecallSearch.Status.PENDING_INDEX, "loading is not forgotten");
        check(RecallSearch.search(withQuestion, q, REAL, Set.of(), List.of(), NOW, 0).status() == RecallSearch.Status.UNAVAILABLE, "deadline distinguished");

        Path journalPath = root.resolve("journal");
        try (var journal = new MemoryJournal(journalPath)) {
            check(journal.awaitIdle(Duration.ofSeconds(5)), "ready");
            put(journal, plan);
            var snapshot = journal.readView(KEY, Set.of(PLAYER));
            var own = new MemoryJournal.Entry(UUID.randomUUID(), KEY, SESSION, 1, MemoryJournal.Source.PLAYER_STATEMENT,
                    Set.of(PLAYER), NOW, q.text(), false);
            put(journal, own);
            check(journal.stillCurrent(snapshot, SESSION, 1), "accepted current input does not invalidate its own read");
            UUID stranger = UUID.randomUUID();
            put(journal, new MemoryJournal.Entry(UUID.randomUUID(), new MemoryJournal.Key(WORLD, KEY.god(), stranger), OLD,
                    1, MemoryJournal.Source.PLAYER_STATEMENT, Set.of(stranger), NOW, "내일 다른 곳에 갈 거야", false));
            check(journal.stillCurrent(snapshot, SESSION, 1), "other player's write does not cancel this response");
            check(journal.readView(KEY, Set.of(PLAYER, stranger)).entries().isEmpty(), "audience permission before retrieval");
            check(journal.readView(KEY, Set.of()).entries().isEmpty(), "empty audience denied");
            check(journal.readView(new MemoryJournal.Key(UUID.randomUUID(), KEY.god(), PLAYER), Set.of(PLAYER)).entries().isEmpty(), "world isolation");
            check(journal.readView(new MemoryJournal.Key(WORLD, "mythictrpg:demeter", PLAYER), Set.of(PLAYER)).entries().isEmpty(), "god isolation");
            put(journal, correction);
            check(!journal.stillCurrent(snapshot, SESSION, 1), "same-bucket new correction invalidates in-flight result");
            var beforeDelete = journal.readView(KEY, Set.of(PLAYER));
            check(journal.delete(plan.id(), journal.view().revision()).get(5, TimeUnit.SECONDS) == MemoryJournal.Result.STORED, "forget");
            check(!journal.stillCurrent(beforeDelete, SESSION, 1), "forget invalidates async response");
            check(journal.append(plan).get(5, TimeUnit.SECONDS) == MemoryJournal.Result.DUPLICATE, "retired memory cannot reappear");
            check(!journal.readView(KEY, Set.of(PLAYER)).entries().contains(plan), "read-your-writes excludes retired ID");
            var pendingEntry = entry("내일 숲에 갈 거야", NOW);
            var writing = journal.append(pendingEntry);
            check(journal.readView(KEY, Set.of(PLAYER)).entries().contains(pendingEntry), "read-your-writes visible before or after commit");
            check(writing.get(5, TimeUnit.SECONDS) == MemoryJournal.Result.STORED, "pending committed");
            check(journal.readView(KEY, Set.of(PLAYER)).pending().isEmpty(), "durability status settles");
        }
        try (var reopened = new MemoryJournal(journalPath)) {
            check(reopened.awaitIdle(Duration.ofSeconds(5)), "restart journal");
            check(!reopened.readView(KEY, Set.of(PLAYER)).entries().contains(plan), "deleted memory stays gone after restart");
            check(reopened.readView(KEY, Set.of(PLAYER)).entries().contains(correction), "correction raw evidence survives restart");
        }
        for (int players : List.of(1,4,6)) {
            try (var journal = new MemoryJournal(root.resolve("players-" + players))) {
                check(journal.awaitIdle(Duration.ofSeconds(5)), "load player suite");
                var pool = Executors.newFixedThreadPool(players);
                var jobs = new ArrayList<Future<Boolean>>();
                for (int i = 0; i < players; i++) jobs.add(pool.submit(() -> {
                    UUID p = UUID.randomUUID(); var key = new MemoryJournal.Key(WORLD, KEY.god(), p);
                    var e = new MemoryJournal.Entry(UUID.randomUUID(), key, OLD, 1, MemoryJournal.Source.PLAYER_STATEMENT,
                            Set.of(p), NOW, "내일 성소에 갈 거야", false);
                    if (journal.append(e).get(5, TimeUnit.SECONDS) != MemoryJournal.Result.STORED) return false;
                    var scope = new RecallQuery.Scope(key, SESSION, Set.of(p));
                    return RecallSearch.search(journal.readView(key, Set.of(p)), RecallQuery.plan(scope, q.text(), 1, NOW, null),
                            REAL, Set.of(), List.of(), NOW, 1_000_000_000).selected().equals(List.of(e));
                }));
                try { for (var job : jobs) check(job.get(10, TimeUnit.SECONDS), "concurrent isolated search"); }
                finally { pool.shutdown(); }
            }
        }
        for (ConversationAct act : ConversationAct.values()) {
            var intent = ConversationIntent.heuristicFallback().withConversationAct(act);
            check(MemoryRecallPolicy.effectiveIntent(intent, false).equals(intent), "OFF intent preserved");
            check(MemoryRecallPolicy.effectiveIntent(intent, true).conversationAct() == ConversationAct.UNSPECIFIED,
                    "effective intent no contradictory event/answer instruction");
        }
        var packed = MemoryRecallPolicy.pack(corrected, List.of());
        var pendingResult = new RecallSearch.Result(q, RecallSearch.Status.FOUND, List.of(plan), Map.of(plan.id(), "plan_time"),
                Set.of(plan.id()), 1, "fixture");
        check(MemoryRecallPolicy.pack(pendingResult, List.of()).prompt().contains("PENDING_NOT_DURABLE"), "pending never reported as durable");
        check(MemoryRecallPolicy.pack(pendingResult, List.of(), REAL).prompt().contains("2026-09-16"), "calendar calculation is code-owned");
        var longEntry = entry("내일 성소에 갈 거야 " + "설명 ".repeat(150), NOW);
        var longResult = search(List.of(longEntry), q.text());
        check(MemoryRecallPolicy.pack(longResult, List.of()).prompt().contains("[truncated]"), "long raw quote is visibly incomplete");
        check(packed.prompt().length() <= 1200 && packed.selected().containsAll(corrected.selected()), "correction evidence fits bounded shared budget");
        var json = JsonParser.parseString(packed.prompt().substring(packed.prompt().indexOf("[{"))).getAsJsonArray();
        check(json.size() == packed.selected().size(), "logged IDs reflect actual packed rows");
        String policy = MemoryRecallPolicy.recallSystem("persona R_HOSTILE / game authority", corrected);
        check(policy.contains("R_HOSTILE") && policy.contains("NOT that the conversation never happened"), "persona plus no-match distinction");
        check(!policy.contains("바다") && !policy.contains("포르투나"), "no scripted content answer");
        check(MemoryRecallPolicy.recallContext(packed.prompt(), corrected, RecallSettings.OFF).contains("UNDECIDED"), "unresolved policy visible to generator");
        check(MemoryRecallPolicy.fitContext("x".repeat(12000), true).length() == 12000, "context boundary accepted");
        try { MemoryRecallPolicy.fitContext("[CURRENT_PLAYER_MESSAGE]" + "x".repeat(12000) + "[OUTPUT_RULES]", true); throw new AssertionError("silently truncated constraints"); }
        catch (IllegalArgumentException expected) { checks++; }
        check(RecallSettings.load(root.resolve("missing.json")).equals(RecallSettings.OFF), "missing config is v1");
        Files.writeString(root.resolve("invalid.json"), "{\"schemaVersion\":99,\"recallV2\":true}");
        check(RecallSettings.load(root.resolve("invalid.json")).equals(RecallSettings.OFF), "invalid config is v1");
        Files.writeString(root.resolve("on.json"), "{\"schemaVersion\":1,\"recallV2\":true,\"timeBasis\":\"REAL_KST\"}");
        check(RecallSettings.load(root.resolve("on.json")).equals(REAL), "explicit opt-in config");
        String adapter = Files.readString(Path.of(args[1]));
        check(adapter.contains("beginAsync") && adapter.contains("continueAfterMemory"), "async adapter attachment compiled");
        check(adapter.contains("sessions.get(player.getUUID()) != session || session.turn != turn"), "late result session/turn guard");
        check(adapter.contains("DialogueMemoryBridge.accept(player, memory)"), "late result generation/evidence guard");
        check(adapter.contains("DialogueTurnDirective.recall()") && adapter.contains("evidence_reselected=false"), "intent/directive and lookup trace aligned");
        check(adapter.contains("finishFailure(player, session, turn, oversized.getMessage())"), "over-budget path releases pending");
        var fullBucket = new ArrayList<MemoryJournal.Entry>();
        for (int i = 0; i < 1999; i++) fullBucket.add(entry("오늘 돌을 모으고 집 근처를 걸었어 " + i, NOW - 100000 - i));
        fullBucket.add(plan);
        var fullView = new MemoryJournal.ReadView(KEY, Set.of(PLAYER), fullBucket, Set.of(), true, false);
        int budgetMisses = 0;
        for (int i = 0; i < 5; i++) {
            var r = RecallSearch.search(fullView, q, REAL, Set.of(), List.of(), NOW, 15_000_000);
            if (r.status() == RecallSearch.Status.UNAVAILABLE) budgetMisses++;
        }
        System.out.println("Full 2000-entry bucket, 15ms budget misses=" + budgetMisses + "/5 (cold/warm diagnostic)");
        var latencies = new ArrayList<Long>();
        for (int i = 0; i < 100; i++) search(List.of(plan, correction), q.text());
        for (int i = 0; i < 300; i++) { long start = System.nanoTime(); search(List.of(plan, correction), q.text()); latencies.add(System.nanoTime() - start); }
        Collections.sort(latencies);
        System.out.printf("Offline tiny-fixture search p95=%.3f ms; NOT server/LLM latency%n", latencies.get(284)/1_000_000.0);
        System.out.println("RecallStageTest: PASS (" + checks + " checks); artifacts=" + root);
    }
}
