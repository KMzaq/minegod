package com.sande.mythai.response.memory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Offline recall regressions. No Minecraft boot, model calls or access to the live world. */
public final class MemoryRecallTest {
    private static int checks;
    private static final long NOW = System.currentTimeMillis();
    private static final UUID PLAYER = UUID.randomUUID(), WORLD = UUID.randomUUID(), SESSION = UUID.randomUUID();
    private static final MemoryJournal.Key KEY = new MemoryJournal.Key(WORLD, "mythictrpg:fortuna", PLAYER);

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static MemoryJournal.Entry entry(MemoryJournal.Key key, UUID session, MemoryJournal.Source source,
            Set<UUID> audience, String text, long time) {
        return new MemoryJournal.Entry(UUID.randomUUID(), key, session, 1, source, audience, time, text, false);
    }

    private static void add(MemoryJournal journal, MemoryJournal.Entry entry) throws Exception {
        check(journal.append(entry).get(10, TimeUnit.SECONDS) == MemoryJournal.Result.STORED, "write fixture");
    }

    private static List<MemoryJournal.Entry> recall(MemoryJournal journal, String query, List<String> recent) {
        return journal.searchConversation(KEY, Set.of(PLAYER), query, Set.copyOf(recent), recent, SESSION,
                NOW, 3, 100_000_000);
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory(Path.of(args[0]), "recall-test-");
        UUID oldSession = UUID.randomUUID(), stranger = UUID.randomUUID();
        var swimming = entry(KEY, oldSession, MemoryJournal.Source.PLAYER_STATEMENT, Set.of(PLAYER),
                "난 수영을 못해", NOW - 180_000);
        var departure = entry(KEY, oldSession, MemoryJournal.Source.PLAYER_STATEMENT, Set.of(PLAYER),
                "내가 갑자기 급한일이 생겨서 먼저 가볼게", NOW - 120_000);
        try (var journal = new MemoryJournal(root)) {
            add(journal, swimming);
            add(journal, departure);
        }
        try (var journal = new MemoryJournal(root)) {
            check(journal.awaitIdle(Duration.ofSeconds(5)), "load real journal after restart");
            check(recall(journal, "수영을 해보려해", List.of()).contains(swimming), "original failing conversation retrieves evidence");
            check(recall(journal, "이번에는 배워보려고", List.of("수영을 해보려해")).contains(swimming), "implicit follow-up uses player topic");
            check(recall(journal, "이번에는 배워보려고", List.of()).isEmpty(), "no invented topic without context");
            check(recall(journal, "오늘은 무기 제작 재료를 찾을래", List.of("수영을 해보려해")).isEmpty(), "independent topic does not drag memory along");
            check(recall(journal, "다른 얘기 하자 그건 기억나?", List.of("수영을 해보려해")).isEmpty(), "explicit topic switch");
            check(recall(journal, "나 돌아왔어", List.of()).equals(List.of(departure)), "return recalls only latest earlier player event");
            check(recall(journal, "난 수영을 못해", List.of()).isEmpty(), "current utterance dedup");
            check(recall(journal, "안녕", List.of()).isEmpty(), "ordinary greeting stays cheap");
            check(recall(journal, "응", List.of("수영을 해보려해")).isEmpty(), "short acknowledgement does not force recall");
            check(recall(journal, "수영을 해보려해", List.of(swimming.text())).isEmpty(), "recent history not duplicated");

            var otherGod = new MemoryJournal.Key(WORLD, "mythictrpg:demeter", PLAYER);
            var otherPlayer = new MemoryJournal.Key(WORLD, KEY.god(), stranger);
            var otherWorld = new MemoryJournal.Key(UUID.randomUUID(), KEY.god(), PLAYER);
            for (var key : List.of(otherGod, otherPlayer, otherWorld)) {
                add(journal, entry(key, oldSession, MemoryJournal.Source.PLAYER_STATEMENT, Set.of(key.player()),
                        "아무에게도 말하지 않은 수영을 못하는 비밀", NOW - 1_000));
            }
            var hearsay = entry(KEY, oldSession, MemoryJournal.Source.HEARSAY_NPC, Set.of(PLAYER), "수영을 못한다는 소문", NOW - 500);
            add(journal, hearsay);
            check(recall(journal, "나 돌아왔어", List.of()).equals(List.of(departure)), "return excludes other gods/players/worlds and hearsay");
            check(journal.searchConversation(KEY, Set.of(PLAYER, stranger), "나 돌아왔어", Set.of(), List.of(), SESSION,
                    NOW, 3, 100_000_000).isEmpty(), "cross-session return respects audience privacy");
            check(journal.searchConversation(KEY, Set.of(PLAYER, stranger), "이번에는 배워보려고", Set.of(), List.of("수영을 해보려해"), SESSION,
                    NOW, 3, 100_000_000).isEmpty(), "context expansion respects audience privacy");
            check(journal.searchConversation(KEY, Set.of(PLAYER), "나 돌아왔어", Set.of(), List.of(), oldSession,
                    NOW, 3, 100_000_000).isEmpty(), "return is not a repeat of current session");
            check(journal.searchConversation(KEY, Set.of(PLAYER), "나 돌아왔어", Set.of(), List.of(), SESSION,
                    NOW + Duration.ofHours(7).toMillis(), 3, 100_000_000).isEmpty(), "return anchor expires after six hours");
            check(journal.searchConversation(KEY, Set.of(PLAYER), "나 돌아왔어", Set.of(), List.of(), SESSION,
                    NOW, 3, 0).isEmpty(), "shared deadline fallback");
            var smithing = entry(KEY, oldSession, MemoryJournal.Source.PLAYER_STATEMENT, Set.of(PLAYER),
                    "나는 대장간의 망치 소리가 무서워", NOW - 100_000);
            add(journal, smithing);
            check(recall(journal, "그건 아직도 무서울 것 같아", List.of("대장간의 망치 소리를 들으러 가려고")).contains(smithing), "different topic uses same generic discourse policy");

            var correction = entry(KEY, SESSION, MemoryJournal.Source.PLAYER_STATEMENT, Set.of(PLAYER),
                    "이제 수영을 배워서 혼자서도 잘해", NOW);
            add(journal, correction);
            var evidence = recall(journal, "수영을 해보려해", List.of());
            check(evidence.contains(swimming) && evidence.contains(correction), "keeps contradictory statements as dated evidence, not silent overwrite");
            check(evidence.indexOf(swimming) < evidence.indexOf(correction), "evidence chronology");
            String prompt = DialogueMemoryBridge.prompt(evidence, List.of());
            check(prompt.contains(correction.text()) && prompt.contains("recorded_at"), "newer correction present with recording time");
            check(prompt.length() <= 800, "memory data prompt remains bounded");
            check(journal.delete(swimming.id(), journal.view().revision()).get(10, TimeUnit.SECONDS) == MemoryJournal.Result.STORED, "delete old memory");
            check(!journal.stillCurrent(evidence), "in-flight evidence invalidated on deletion");
            check(!recall(journal, "이번에는 배워보려고", List.of("수영을 해보려해")).contains(swimming), "deleted memory cannot reappear through context");
            long started = System.nanoTime();
            for (int i = 0; i < 600; i++) recall(journal, "이번에는 배워보려고", List.of("수영을 해보려해"));
            System.out.printf("Recall fixture: 600 queries in %.1f ms (not full-server latency)%n", (System.nanoTime() - started) / 1_000_000.0);
        }
        for (String relation : List.of("R_HOSTILE", "R_NEUTRAL", "R_TRUSTED")) {
            String base = "Game authority / JSON / " + relation;
            check(MemoryRecallPolicy.generationSystem(base, false).equals(base), "OFF/no-memory prompt unchanged");
            String system = MemoryRecallPolicy.generationSystem(base, true);
            check(system.startsWith(base) && system.contains("actual relationship"), "relationship preserved");
            check(system.contains("current correction") && system.contains("PLAYER_STATEMENT") && system.contains("RUMOR_RECEIVED"), "provenance and correction safeguards");
            check(system.contains("takes priority over brevity") && system.contains("NEVER overrides game authority"), "specific conflict resolved without authority expansion");
            check(!system.contains("수영") && !system.contains("바다") && !system.contains("포르투나"), "no scenario-specific scripted answer");
        }
        check(!MemoryRecallPolicy.returning("언제 돌아왔어?"), "question is not a player return event");
        check(!MemoryRecallPolicy.returning("친구가 돌아왔어"), "third-party return is not player return");
        String adapter = Files.readString(Path.of(args[1]));
        check(adapter.contains("MemoryRecallPolicy.generationSystem(system, !session.memoryTurn.prompt().isEmpty())"), "generation uses tested policy");
        check(adapter.contains("filter(t -> \"PLAYER\".equals(t.role()))"), "query context excludes NPC-authored topics");
        int methodStart = adapter.indexOf("public StartResult start(");
        int refused = adapter.indexOf("return StartResult.NORMAL_AI_SESSION_ACTIVE;", methodStart);
        int unbind = adapter.indexOf("DialogueMemoryBridge.unbind(player.getUUID());", methodStart);
        int load = adapter.indexOf("contentRegistry.load(godId", methodStart);
        check(methodStart >= 0 && refused > methodStart && unbind > refused && load > unbind, "rejected start retains existing binding");
        check(adapter.contains("\"MEMORY_RECALL\"") && adapter.contains("session.memoryTurn.selected().stream()"), "selected evidence logged for diagnosis");
        System.out.println("MemoryRecallTest: PASS (" + checks + " checks); artifacts=" + root);
    }
}
