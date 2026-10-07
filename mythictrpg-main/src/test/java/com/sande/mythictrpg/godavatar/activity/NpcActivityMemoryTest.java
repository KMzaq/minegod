package com.sande.mythictrpg.godavatar.activity;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Pure deterministic contracts: no server, player, synthetic room or model required. */
public final class NpcActivityMemoryTest {
    private static final String A = "mythictrpg:demeter", B = "mythictrpg:fortuna", C = "mythictrpg:outside";
    private static final UUID ACTOR = UUID.randomUUID(), PLAYER = UUID.randomUUID(), OUTSIDE = UUID.randomUUID();
    public static void main(String[] args) {
        duplicateAndGodIsolation(); disclosureAndMasking(); boundedRelevantRecall(); koreanKindRecall(); offPreservation();
        affectUsesEveryInputAndSurvivesNewEvents(); strictRestore(); godCapacityIsNonfatal();
        System.out.println("NpcActivityMemoryTest passed");
    }
    private static void duplicateAndGodIsolation() {
        var dirty = new AtomicInteger(); var bank = new NpcActivityMemory(() -> true, dirty::incrementAndGet);
        var event = lifecycle(A, 1, "COMPLETED", "private target, exact position and book contents");
        require(bank.record(event), "first event rejected");
        require(!bank.record(event) && dirty.get() == 1, "duplicate mutated state");
        expectFailure(() -> bank.record(new NpcActivityMemory.Event(event.eventId(), event.runId(), A, ACTOR, 1,
                event.activityId(), "READ", "REAL", "FAILED", "different", "", "", Set.of(), Set.of())));
        require(bank.view(B, Set.of(B), Set.of(), "").experiences().isEmpty(), "another God inherited experience");
        require(bank.project(B, event.eventId(), Set.of(B), Set.of()).isEmpty(), "another God projected source");
        var view = bank.view(A, Set.of(A), Set.of(), "");
        require(bank.current(view, Set.of(A), Set.of()), "new view not current");
        bank.record(lifecycle(A, 2, "STARTED", ""));
        require(!bank.current(view, Set.of(A), Set.of()) && bank.disclosureCurrent(view, Set.of(A), Set.of()), "revision and disclosure conflated");
    }
    private static void disclosureAndMasking() {
        var bank = new NpcActivityMemory(() -> true, () -> {});
        var speech = speech(A, 1, Set.of(A, B), Set.of(PLAYER), "우리끼리 한 비공개 이야기"); bank.record(speech);
        require(bank.project(A, speech.eventId(), Set.of(B), Set.of(PLAYER)).isPresent(), "source owner incorrectly required in later audience");
        require(bank.project(A, speech.eventId(), Set.of(A, C), Set.of()).isEmpty(), "outside God received private speech");
        require(bank.project(A, speech.eventId(), Set.of(A), Set.of(OUTSIDE)).isEmpty(), "outside player received private speech");
        var failure = lifecycle(A, 2, "FAILED", "secret book was partially edited at 100,70,200 with Fortuna"); bank.record(failure);
        String projection = bank.project(A, failure.eventId(), Set.of(C), Set.of(OUTSIDE)).orElseThrow().toString();
        require(!projection.contains("secret") && !projection.contains("100") && !projection.contains("Fortuna"), "lifecycle detail disclosed");
        require(projection.contains("FAILED") && !projection.contains("no effect"), "failure rewritten into fictitious side-effect certainty");
        expectFailure(() -> speech(A, 3, Set.of(B), Set.of(), "unheard"));
    }
    private static void boundedRelevantRecall() {
        var bank = new NpcActivityMemory(() -> true, () -> {});
        var old = speech(A, 1, Set.of(A), Set.of(), "orchard memory"); bank.record(old);
        for (int i = 2; i <= 8; i++) bank.record(lifecycle(A, i, "COMPLETED", "orchard raw detail must not be searched"));
        var recall = bank.view(A, Set.of(A), Set.of(), "orchard");
        require(recall.experiences().size() == 4 && recall.experiences().get(3).eventId().equals(old.eventId()), "recent three plus relevant older selection failed");
        require(bank.view(A, Set.of(A), Set.of(), "raw detail").experiences().size() == 3, "private details influenced recall");
        for (int i = 9; i <= 70; i++) bank.record(lifecycle(A, i, "COMPLETED", ""));
        require(bank.snapshot().getFirst().events().size() == 64 && bank.project(A, old.eventId(), Set.of(A), Set.of()).isEmpty(), "event retention unbounded");
        require(bank.view(A, Set.of(A), Set.of(), "COMPLETED").experiences().size() == 6, "recall six item bound failed");
    }
    private static void offPreservation() {
        var enabled = new AtomicBoolean(true); var dirty = new AtomicInteger();
        var bank = new NpcActivityMemory(enabled::get, dirty::incrementAndGet);
        var event = lifecycle(A, 1, "COMPLETED", ""); bank.record(event);
        var prior = bank.view(A, Set.of(A), Set.of(), ""); var saved = bank.snapshot(); enabled.set(false);
        require(!bank.record(lifecycle(A, 2, "COMPLETED", "")), "OFF recorded new memory");
        require(bank.view(A, Set.of(A), Set.of(), "").equals(NpcActivityMemory.View.empty(A)), "OFF read stored memory");
        require(bank.project(A, event.eventId(), Set.of(A), Set.of()).isEmpty(), "OFF projected original");
        require(!bank.current(prior, Set.of(A), Set.of()) && !bank.disclosureCurrent(prior, Set.of(A), Set.of()), "OFF accepted nonempty source snapshot");
        require(bank.current(NpcActivityMemory.View.empty(A), Set.of(A), Set.of()), "OFF empty context blocked ordinary activity");
        require(!bank.applyAffect(prior, new NpcActivityMemory.Affect("평온했던 기억", List.of(event.eventId())), Set.of(A), Set.of()), "OFF stored affect");
        var restored = new NpcActivityMemory(enabled::get, dirty::incrementAndGet); restored.restore(saved);
        require(restored.snapshot().equals(saved) && dirty.get() == 1, "OFF persistence lost source or marked load dirty");
        enabled.set(true); require(restored.view(A, Set.of(A), Set.of(), "").equals(prior), "reenabling lost preserved source");
    }
    private static void koreanKindRecall() {
        var bank = new NpcActivityMemory(() -> true, () -> {}); var earlierReadIds = new HashSet<UUID>();
        for (int i = 1; i <= 3; i++) { var event = lifecycle(A, i, "COMPLETED", "private title"); bank.record(event); earlierReadIds.add(event.eventId()); }
        for (int i = 4; i <= 6; i++) bank.record(new NpcActivityMemory.Event(UUID.randomUUID(), UUID.randomUUID(), A, ACTOR, i,
                "mythictrpg:rest", "REST", "DECORATIVE", "COMPLETED", "private location", "", "", Set.of(), Set.of()));
        var view = bank.view(A, Set.of(A), Set.of(), "아까 무슨 책 읽었어?");
        require(view.experiences().size() == 6 && view.experiences().subList(3, 6).stream().allMatch(memory -> earlierReadIds.contains(memory.eventId())),
                "Korean kind query failed to retrieve three older reading experiences");
        require(view.experiences().stream().noneMatch(memory -> memory.toString().contains("private")), "Korean recall exposed raw details");
    }
    private static void affectUsesEveryInputAndSurvivesNewEvents() {
        var bank = new NpcActivityMemory(() -> true, () -> {});
        var privateSpeech = speech(A, 1, Set.of(A, B), Set.of(PLAYER), "중요한 사적 이야기"); bank.record(privateSpeech);
        var publicEvent = lifecycle(A, 2, "COMPLETED", "private exact location"); bank.record(publicEvent);
        var before = bank.view(A, Set.of(A, B), Set.of(PLAYER), "");
        require(!bank.applyAffect(before, new NpcActivityMemory.Affect("잘못된 근거", List.of(UUID.randomUUID())), Set.of(A, B), Set.of(PLAYER)), "foreign affect source accepted");
        require(bank.applyAffect(before, new NpcActivityMemory.Affect("조심스러웠던 해석", List.of(publicEvent.eventId())), Set.of(A, B), Set.of(PLAYER)), "valid affect rejected");
        var after = bank.view(A, Set.of(A, B), Set.of(PLAYER), "");
        require(new HashSet<>(after.affect().sourceEventIds()).equals(Set.of(privateSpeech.eventId(), publicEvent.eventId())), "selected public ref laundered private input");
        require(bank.view(A, Set.of(A, C), Set.of(OUTSIDE), "").affect().equals(NpcActivityMemory.Affect.empty()), "private affect leaked to expanded audience");
        bank.record(lifecycle(A, 3, "STARTED", ""));
        require(bank.view(A, Set.of(A, B), Set.of(PLAYER), "").affect().equals(after.affect()), "new start erased past interpretation");
        require(!bank.current(after, Set.of(A, B), Set.of(PLAYER)) && bank.disclosureCurrent(after, Set.of(A, B), Set.of(PLAYER)), "past source treated as current or stale disclosure blocked");
        require(!bank.disclosureCurrent(after, Set.of(A, C), Set.of(OUTSIDE)), "delayed speech leaked expanded audience");
        var next = bank.view(A, Set.of(A, B), Set.of(PLAYER), "");
        require(bank.applyAffect(next, new NpcActivityMemory.Affect("다시 생각한 해석", List.of(publicEvent.eventId())), Set.of(A, B), Set.of(PLAYER)), "second interpretation rejected");
        require(bank.disclosureCurrent(after, Set.of(A, B), Set.of(PLAYER)), "replacement hint invalidated old but still safe input");
        for (int i = 4; i <= 66; i++) bank.record(lifecycle(A, i, "COMPLETED", ""));
        require(bank.view(A, Set.of(A, B), Set.of(PLAYER), "").affect().equals(NpcActivityMemory.Affect.empty()), "orphaned affect survived eviction");
        require(!bank.disclosureCurrent(after, Set.of(A, B), Set.of(PLAYER)), "evicted source snapshot accepted");
    }
    private static void strictRestore() {
        var event = lifecycle(A, 1, "COMPLETED", "");
        expectFailure(() -> new NpcActivityMemory.Event(event.eventId(), event.runId(), A, ACTOR, 1,
                "mythictrpg:read", "ASSASSINATE", "REAL", "COMPLETED", "", "", "", Set.of(), Set.of()));
        expectFailure(() -> new NpcActivityMemory(() -> true, () -> {}).restore(List.of(new NpcActivityMemory.StoredGod(A, 1, List.of(event, event), NpcActivityMemory.Affect.empty()))));
        expectFailure(() -> new NpcActivityMemory(() -> true, () -> {}).restore(List.of(new NpcActivityMemory.StoredGod(B, 1, List.of(event), NpcActivityMemory.Affect.empty()))));
        expectFailure(() -> new NpcActivityMemory(() -> true, () -> {}).restore(List.of(new NpcActivityMemory.StoredGod(A, 1, List.of(event), new NpcActivityMemory.Affect("출처 없음", List.of(UUID.randomUUID()))))));
    }
    private static void godCapacityIsNonfatal() {
        var bank = new NpcActivityMemory(() -> true, () -> {});
        for (int i = 0; i < NpcActivityMemory.MAX_GODS; i++) require(bank.record(lifecycle("mythictrpg:fixture_" + i, 1, "COMPLETED", "")), "capacity exhausted early");
        require(!bank.record(lifecycle("mythictrpg:overflow", 1, "COMPLETED", "")), "God capacity exceeded");
        require(bank.snapshot().size() == NpcActivityMemory.MAX_GODS, "capacity rejection changed store");
    }
    private static NpcActivityMemory.Event lifecycle(String god, int time, String phase, String detail) {
        return new NpcActivityMemory.Event(UUID.randomUUID(), UUID.randomUUID(), god, ACTOR, time, "mythictrpg:read", "READ", "REAL", phase, detail, "", "", Set.of(), Set.of());
    }
    private static NpcActivityMemory.Event speech(String god, int time, Set<String> gods, Set<UUID> players, String text) {
        return new NpcActivityMemory.Event(UUID.randomUUID(), UUID.randomUUID(), god, ACTOR, time, "mythictrpg:read", "READ", "REAL", "SPEECH", "", A, text, gods, players);
    }
    private static void expectFailure(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { return; } throw new AssertionError("invalid state accepted"); }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
