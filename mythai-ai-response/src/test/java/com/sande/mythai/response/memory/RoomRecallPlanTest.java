package com.sande.mythai.response.memory;

import java.time.Duration;
import java.util.*;

/** Carries the existing player-only plan without a second planner, store, model or Minecraft server. */
public final class RoomRecallPlanTest {
    private static int checks;
    private static final UUID WORLD = UUID.randomUUID(), PLAYER = UUID.randomUUID(), OTHER = UUID.randomUUID(), ROOM_GENERATION = UUID.randomUUID();
    private static final String GOD = "test:athena";
    private static final long NOW = 1_800_000_000_000L;

    public static void main(String[] args) {
        carriedPlan(); focusBoundaries(); oldConstructors();
        System.out.println("RoomRecallPlanTest: " + checks + " checks passed; no server/model/files");
    }

    private static void carriedPlan() {
        var scope = scope(WORLD, GOD, PLAYER, ROOM_GENERATION, Set.of(PLAYER));
        var initial = RecallQuery.plan(scope, "내가 내일 어디에 가기로 했지?", 1, NOW, null);
        check(initial.explicit() && !initial.followUp() && initial.focus() != null, "existing planner recognizes explicit player recall");
        var absent = RoomMemoryBridge.projectRecall(List.of(), initial, RecallSettings.OFF);
        check(absent.query().orElseThrow() == initial, "legitimate no-hit result retains the exact already-computed plan");
        check(absent.context().isEmpty() && absent.sourceMessageIds().isEmpty() && absent.promptVariants().isEmpty(),
                "carrying a plan never fabricates a recalled statement or prompt content");
        var follow = RecallQuery.plan(scope, "다시 알려줘", 2, NOW + 30_000, initial.focus());
        check(follow.explicit() && follow.followUp() && follow.text().equals(initial.text()) && follow.askedAt() == NOW,
                "bare follow-up preserves original question and date rather than embedding the follow-up alone");
        var row = new RoomMemoryStore.Record(UUID.randomUUID(), WORLD, UUID.randomUUID(), 1, NOW - 60_000,
                "PLAYER", PLAYER.toString(), "test-player", "내일 바다 성소에 가기로 했어", false,
                Set.of(PLAYER), Set.of(GOD), List.of(), Set.of());
        var selected = RoomMemoryBridge.projectRecall(List.of(row), follow, RecallSettings.OFF);
        check(selected.query().orElseThrow() == follow && selected.sourceMessageIds().equals(Set.of(row.messageId())),
                "nonempty legacy selection and its immutable scoped plan travel together");
        var untyped = RoomMemoryBridge.projectRecall(List.of(row), follow.text());
        check(selected.context().equals(untyped.context()) && selected.promptVariants().equals(untyped.promptVariants()),
                "carried metadata does not rewrite the existing prompt projection");
        check(untyped.query().isEmpty(), "old String-only projection does not invent a plan");
        for (int i = 0; i < 3; i++) check(RoomMemoryBridge.projectRecall(List.of(), follow, RecallSettings.OFF).query().orElseThrow() == follow,
                "consumers can reuse one plan without advancing its focus turn/date");
        check(follow.focus() == initial.focus() && initial.focus().firstTurn() == 1, "projection is not another mutable focus owner");
    }

    private static void focusBoundaries() {
        var mutableAudience = new HashSet<>(Set.of(PLAYER));
        var current = scope(WORLD, GOD, PLAYER, ROOM_GENERATION, mutableAudience); mutableAudience.add(OTHER);
        check(current.audience().equals(Set.of(PLAYER)), "query scope owns an immutable audience snapshot");
        var original = RecallQuery.plan(current, "네가 바다 성소에서 뭐라고 말했지?", 10, NOW, null);
        var valid = RecallQuery.plan(current, "다시 알려줘", 13, NOW + Duration.ofMinutes(5).toMillis(), original.focus());
        check(valid.followUp() && valid.text().equals(original.text()) && valid.askedAt() == original.askedAt(),
                "existing inclusive three-turn/five-minute focus limit is preserved");
        for (var boundary : List.of(
                RecallQuery.plan(current, "다시 알려줘", 14, NOW + 1000, original.focus()),
                RecallQuery.plan(current, "다시 알려줘", 11, NOW + Duration.ofMinutes(5).toMillis() + 1, original.focus()),
                RecallQuery.plan(current, "다시 알려줘", 11, NOW - 1, original.focus()),
                RecallQuery.plan(current, "다시 알려줘", 10, NOW + 1000, original.focus()))) {
            check(!boundary.explicit() && !boundary.followUp() && boundary.focus() == null && boundary.text().equals("다시 알려줘"),
                    "expired/reversed/repeated turn cannot reuse an old focus");
        }
        for (var otherScope : List.of(
                scope(UUID.randomUUID(), GOD, PLAYER, ROOM_GENERATION, Set.of(PLAYER)),
                scope(WORLD, "test:hermes", PLAYER, ROOM_GENERATION, Set.of(PLAYER)),
                scope(WORLD, GOD, OTHER, ROOM_GENERATION, Set.of(PLAYER)),
                scope(WORLD, GOD, PLAYER, UUID.randomUUID(), Set.of(PLAYER)),
                scope(WORLD, GOD, PLAYER, ROOM_GENERATION, Set.of(PLAYER, OTHER)))) {
            var result = RecallQuery.plan(otherScope, "다시 알려줘", 11, NOW + 1000, original.focus());
            check(!result.followUp() && !result.explicit() && result.focus() == null, "world/God/player/room revision/audience change isolates recall focus");
        }
        for (String text : List.of("응", "고마워", "다른 얘기 하자", "아무튼 날씨 좋네")) {
            var changed = RecallQuery.plan(current, text, 11, NOW + 1000, original.focus());
            check(changed.focus() == null && !changed.explicit(), "acknowledgement or topic change clears the old recall question");
            check(!RecallQuery.plan(current, "다시 알려줘", 12, NOW + 2000, changed.focus()).followUp(), "a later bare request cannot resurrect cleared focus");
        }
        var fresh = RecallQuery.plan(current, "내가 내일 뭐라고 약속했지?", 11, NOW + 1000, original.focus());
        check(fresh.explicit() && !fresh.followUp() && fresh.askedAt() == NOW + 1000 && !fresh.text().equals(original.text()),
                "a new explicit question gets its own actor wording and temporal anchor");
    }

    private static void oldConstructors() {
        check(RoomMemoryBridge.Recall.EMPTY.query().isEmpty(), "OFF/unavailable/stale EMPTY has no manufactured plan");
        check(RoomMemoryBridge.projectRecall(List.of(), "다시 알려줘") == RoomMemoryBridge.Recall.EMPTY,
                "old unplanned empty result preserves its singleton compatibility");
        var two = new RoomMemoryBridge.Recall("legacy-context", Set.of(UUID.randomUUID()));
        var three = new RoomMemoryBridge.Recall(two.context(), two.sourceMessageIds(), two.promptVariants());
        check(two.query().isEmpty() && three.query().isEmpty() && two.equals(three), "both old constructors remain source-compatible and carry no plan");
        var query = RecallQuery.plan(scope(WORLD, GOD, PLAYER, ROOM_GENERATION, Set.of(PLAYER)), "내가 뭐라고 말했지?", 1, NOW, null);
        var ready = RoomMemoryBridge.projectRecall(List.of(), query, RecallSettings.OFF);
        var unavailable = ready.withoutQuery();
        check(unavailable.query().isEmpty() && unavailable.equals(RoomMemoryBridge.Recall.EMPTY),
                "failure path can remove optional plan without altering existing selected content");
        var selected = new RoomMemoryBridge.Recall(two.context(), two.sourceMessageIds(), two.promptVariants(), Optional.of(query));
        var stripped = selected.withoutQuery();
        check(stripped.query().isEmpty() && stripped.context().equals(selected.context())
                        && stripped.sourceMessageIds().equals(selected.sourceMessageIds()) && stripped.promptVariants().equals(selected.promptVariants()),
                "actual preparation-failure strip preserves all permitted legacy quotes, dependencies and variants");
        check(selected.query().orElseThrow() == query && stripped.withoutQuery() == stripped && RoomMemoryBridge.Recall.EMPTY.withoutQuery() == RoomMemoryBridge.Recall.EMPTY,
                "stripping is immutable and cannot create a plan from an unavailable or already-unplanned result");
        boolean rejected = false;
        try { new RoomMemoryBridge.Recall("", Set.of(), List.of(), null); } catch (NullPointerException expected) { rejected = true; }
        check(rejected, "optional query itself cannot be null");
    }

    private static RecallQuery.Scope scope(UUID world, String god, UUID player, UUID generation, Set<UUID> audience) {
        return new RecallQuery.Scope(new MemoryJournal.Key(world, god, player), generation, audience);
    }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
