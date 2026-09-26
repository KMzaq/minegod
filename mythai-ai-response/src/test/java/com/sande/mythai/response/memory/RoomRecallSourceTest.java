package com.sande.mythai.response.memory;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Role-aware real journal/lexical recall. No model, synthetic dialogue output or nickname guessing. */
final class RoomRecallSourceTest {
    private static final String SELF = "mythictrpg:hermes", OTHER = "mythictrpg:athena", OUTSIDE = "mythictrpg:hades";
    private static final UUID PLAYER = UUID.randomUUID(), PEER = UUID.randomUUID(), SESSION = UUID.randomUUID();
    private static final MemoryJournal.Key KEY = new MemoryJournal.Key(UUID.randomUUID(), SELF, PLAYER);
    private static final Set<UUID> PEOPLE = Set.of(PLAYER, PEER);
    private static final Set<String> GODS = Set.of(SELF, OTHER);
    private static final long NOW = System.currentTimeMillis();
    private static int checks;

    static int run(Path root) throws Exception {
        checks = 0;
        var player = entry(MemoryJournal.Source.PLAYER_STATEMENT, "", PEOPLE, GODS, "신전 봉인을 지키겠다고 내가 말했어");
        var other = entry(MemoryJournal.Source.NPC_UTTERANCE, OTHER, PEOPLE, GODS, "신전 봉인은 위험하다고 조언했다");
        var self = entry(MemoryJournal.Source.NPC_UTTERANCE, SELF, PEOPLE, GODS, "신전 봉인은 이미 닫혔다고 답했다");
        var privateOther = entry(MemoryJournal.Source.NPC_UTTERANCE, OTHER, Set.of(PLAYER), GODS, "신전 봉인의 비밀 통로를 설명했다");
        var unshared = entry(MemoryJournal.Source.NPC_UTTERANCE, OUTSIDE, PEOPLE, Set.of(SELF, OUTSIDE), "신전 봉인의 지하 열쇠를 언급했다");
        var hearsay = entry(MemoryJournal.Source.HEARSAY_NPC, OTHER, PEOPLE, GODS, "신전 봉인은 전부 사라졌다는 소문이야");
        try (var journal = new MemoryJournal(root.resolve("source-role-recall"))) {
            for (var row : List.of(player, other, self, privateOther, unshared, hearsay))
                check(journal.append(row).get(5, TimeUnit.SECONDS) == MemoryJournal.Result.STORED, "attributed raw fixture stored");
            var view = journal.readView(KEY, PEOPLE, GODS);
            assertOnly(view, "내가 신전 봉인을 뭐라고 했지?", player, "player's own words never replaced by an NPC quotation");
            assertOnly(view, "네가 신전 봉인을 뭐라고 했지?", self, "second person means the replying God, not the heard God");
            assertOnly(view, "다른 신이 신전 봉인을 뭐라고 했지?", other, "other-God words retrieved with actual speaker identity");
            assertOnly(view, OTHER + "가 신전 봉인을 뭐라고 했지?", other, "explicit game-ID speaker resolves without display-name inference");
            assertOnly(view, OTHER + "에게 내가 신전 봉인을 뭐라고 했지?", player, "God ID as addressee does not become the speaking actor");
            check(RecallSourceScope.resolve(OTHER + "가 신전 봉인을 뭐라고 했지?").speakerGodIds().equals(Set.of(OTHER)),
                    "source filter retains exact actor ID independently of lexical topic terms");
            check(RecallSourceScope.lexicalQuery(OTHER + "가 신전 봉인을 뭐라고 했지?").equals("신전 봉인을 뭐라고 했지?"),
                    "long actor ID does not dilute topic-match threshold");
            check(RecallSourceScope.lexicalQuery(OTHER + "에게 내가 신전 봉인을 뭐라고 했지?").equals("내가 신전 봉인을 뭐라고 했지?"),
                    "addressee metadata is also separated without changing the player-speaker filter");
            check(RecallSourceScope.lexicalQuery("mythictrpg:seal_item을 뭐라고 했지?").contains("mythictrpg:seal_item"),
                    "a resource ID used as the subject matter is not stripped as an actor");
            check(search(view, OUTSIDE + "가 신전 봉인을 뭐라고 했지?").selected().isEmpty(), "identity filter cannot bypass current God audience");
            check(search(view, "다른 신이 검은 용에 관해 뭐라고 했지?").selected().isEmpty(), "no raw-recent fallback for unrelated NPC speech");
            var unrelated = query("다른 신이 검은 용에 관해 뭐라고 했지?");
            check(RecallSearch.search(view, unrelated, RecallSettings.OFF, Set.of(), List.of("신전 봉인"), NOW,
                    TimeUnit.SECONDS.toNanos(1)).selected().isEmpty(), "context-only association cannot satisfy explicit NPC recall");
            check(search(view, "다른 신이 무엇을 말했지?").selected().isEmpty(), "speaker role alone is not enough to guess a past NPC statement");
            var unknown = search(view, "신전 봉인을 뭐라고 했지?");
            check(unknown.selected().containsAll(List.of(player, self, other)), "ambiguous subject retains separately attributed lexical evidence");
            String ambiguousPrompt = MemoryRecallPolicy.pack(unknown, List.of()).prompt();
            check(ambiguousPrompt.contains("PLAYER_STATEMENT") && ambiguousPrompt.contains("NPC_UTTERANCE")
                    && ambiguousPrompt.contains(OTHER) && ambiguousPrompt.contains(SELF), "ambiguous recall cannot relabel NPC words as player statements");
            check(RecallSourceScope.resolve("아테나가 신전 봉인을 뭐라고 했지?").role() == RecallSourceScope.Role.UNSPECIFIED,
                    "unprovided nickname mapping is not invented");
            var scope = new RecallQuery.Scope(KEY, SESSION, PEOPLE);
            var first = RecallQuery.plan(scope, "다른 신이 신전 봉인을 뭐라고 했지?", 1, NOW, null);
            var follow = RecallQuery.plan(scope, "알려줘", 2, NOW + 1, first.focus());
            check(follow.followUp() && RecallSourceScope.resolve(follow.text()).role() == RecallSourceScope.Role.OTHER_GOD,
                    "follow-up retains original speaker-source role");
            check(RecallSearch.search(view, follow, RecallSettings.OFF, Set.of(), List.of(), NOW + 1,
                    TimeUnit.SECONDS.toNanos(1)).selected().equals(List.of(other)), "follow-up actually retrieves only the originally requested God role");
            for (var pair : Map.of("내가 신전 봉인을 뭐라고 했지?", player,
                    "네가 신전 봉인을 뭐라고 했지?", self, "다른 신이 신전 봉인을 뭐라고 했지?", other,
                    OTHER + "가 신전 봉인을 뭐라고 했지?", other, OTHER + "에게 내가 신전 봉인을 뭐라고 했지?", player).entrySet())
                check(journal.searchConversation(KEY, PEOPLE, pair.getKey(), Set.of(), List.of(), SESSION, NOW,
                        3, TimeUnit.SECONDS.toNanos(1), GODS).equals(List.of(pair.getValue())), "legacy lexical path enforces role: " + pair.getKey());
            check(!RecallSearch.semanticEligible(player, first, RecallSettings.OFF, NOW),
                    "semantic candidate fusion cannot reintroduce player words into NPC-only recall");
            check(!RecallSearch.semanticEligible(other, query("내가 신전 봉인을 뭐라고 했지?"), RecallSettings.OFF, NOW),
                    "NPC records cannot become personal semantic evidence");
            var noPlayer = new MemoryJournal.ReadView(KEY, PEOPLE, List.of(self, other), Set.of(), true, false, GODS);
            check(search(noPlayer, "내가 신전 봉인을 뭐라고 했지?").selected().isEmpty(), "missing player evidence stays missing despite relevant NPC speech");
            check(journal.delete(other.id(), journal.view().revision()).get(5, TimeUnit.SECONDS) == MemoryJournal.Result.STORED,
                    "heard original can be explicitly retired");
            check(!journal.stillCurrent(List.of(other)), "pending recall invalidates when the heard original is retired");
            check(search(journal.readView(KEY, PEOPLE, GODS), "다른 신이 신전 봉인을 뭐라고 했지?").selected().isEmpty(),
                    "retired heard speech cannot return through private, hearsay or outsider alternatives");
        }
        return checks;
    }
    private static void assertOnly(MemoryJournal.ReadView view, String text, MemoryJournal.Entry expected, String message) {
        var result = search(view, text);
        check(result.query().explicit() && result.selected().equals(List.of(expected)), message);
    }
    private static RecallSearch.Result search(MemoryJournal.ReadView view, String text) {
        return RecallSearch.search(view, query(text), RecallSettings.OFF, Set.of(), List.of(), NOW, TimeUnit.SECONDS.toNanos(1));
    }
    private static RecallQuery query(String text) { return RecallQuery.plan(new RecallQuery.Scope(KEY, SESSION, PEOPLE), text, 1, NOW, null); }
    private static MemoryJournal.Entry entry(MemoryJournal.Source source, String speaker, Set<UUID> people, Set<String> gods, String text) {
        return new MemoryJournal.Entry(UUID.randomUUID(), KEY, SESSION, 1, source, people, NOW - 100, text, false, gods, speaker);
    }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
