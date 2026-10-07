package com.sande.mythai.response.memory;

import com.sande.mythictrpg.ai.api.RoomConversationEngine.Request;
import com.sande.mythictrpg.ai.api.RoomConversationEngine.GodState;
import com.sande.mythictrpg.ai.memorycontract.MemoryFoundationSettings.Mode;
import net.minecraft.resources.ResourceLocation;
import java.util.*;

/** No server, files, stores, model, or read session. Planning carries player intent only. */
public final class RoomRecallPlannerTest {
    private static final UUID WORLD = UUID.randomUUID(), ROOM = UUID.randomUUID(), PLAYER = UUID.randomUUID(), OTHER = UUID.randomUUID();
    private static final ResourceLocation GOD = ResourceLocation.parse("test:athena"), SECOND = ResourceLocation.parse("test:hermes");
    private static final long NOW = 1_800_000_000_000L;
    private static int checks;
    public static void main(String[] args) {
        idempotence(); isolation(); secondaryAttribution(); lifetime(); bounded();
        System.out.println("RoomRecallPlannerTest: " + checks + " checks passed; no journal/model/reader/server");
    }
    private static void idempotence() {
        var planner = new RoomRecallPlanner();
        var first = request("내가 바다 성소에서 뭐라고 말했지?");
        var plan = planner.plan(WORLD, first, Mode.PERSONAL, NOW).orElseThrow();
        check(plan.explicit() && plan.focus().firstTurn() == 1, "fresh player question opens focus without any retrieval");
        for (int i = 1; i <= 10; i++) check(planner.plan(WORLD, first, Mode.PERSONAL, NOW + i).orElseThrow() == plan,
                "same game turn across consumers reuses exact immutable plan and cannot advance the focus");
        var conflicting = copy(first, first.roomId(), first.revision(), first.turnId(), first.playerId(), first.godIds(),
                first.speakerGodId(), "다른 말을 했다고 꾸미기", false, first.audiencePlayerIds());
        check(planner.plan(WORLD, conflicting, Mode.PERSONAL, NOW + 100).isEmpty(), "same turn cannot replace original player text");
        var next = request("다시 알려줘");
        var follow = planner.plan(WORLD, next, Mode.PERSONAL, NOW + 1000).orElseThrow();
        check(follow.followUp() && follow.text().equals(plan.text()) && follow.askedAt() == NOW, "follow-up retains question and occurrence anchor");
        check(RecordedRecallQuery.prepare(next, Optional.of(follow)).orElseThrow().semanticEligible(), "shared query adapter accepts genuine planned scope");
        check(planner.size() == 1, "turn deduplication does not grow per-turn history");
    }
    private static void isolation() {
        var original = request("네가 바다 성소에서 뭐라고 말했지?");
        var follow = request("다시 알려줘");
        var changed = List.of(
                copy(follow, UUID.randomUUID(), 2, UUID.randomUUID(), PLAYER, List.of(GOD), GOD, follow.currentText(), false, Set.of(PLAYER)),
                copy(follow, ROOM, 3, UUID.randomUUID(), PLAYER, List.of(GOD), GOD, follow.currentText(), false, Set.of(PLAYER)),
                copy(follow, ROOM, 2, UUID.randomUUID(), OTHER, List.of(GOD), GOD, follow.currentText(), false, Set.of(PLAYER, OTHER)),
                copy(follow, ROOM, 2, UUID.randomUUID(), PLAYER, List.of(GOD, SECOND), SECOND, follow.currentText(), false, Set.of(PLAYER)),
                copy(follow, ROOM, 2, UUID.randomUUID(), PLAYER, List.of(GOD, SECOND), GOD, follow.currentText(), false, Set.of(PLAYER)),
                copy(follow, ROOM, 2, UUID.randomUUID(), PLAYER, List.of(GOD), GOD, follow.currentText(), true, Set.of(PLAYER)),
                copy(follow, ROOM, 2, UUID.randomUUID(), PLAYER, List.of(GOD), GOD, follow.currentText(), false, Set.of(PLAYER, OTHER)));
        for (var request : changed) {
            var planner = new RoomRecallPlanner(); planner.plan(WORLD, original, Mode.PERSONAL, NOW);
            check(!planner.plan(WORLD, request, Mode.PERSONAL, NOW + 1).orElseThrow().followUp(),
                    "room/revision/player/speaker/participating Gods/public/audience isolate player focus");
        }
        var planner = new RoomRecallPlanner(); planner.plan(WORLD, original, Mode.PERSONAL, NOW);
        check(!planner.plan(UUID.randomUUID(), follow, Mode.PERSONAL, NOW + 1).orElseThrow().followUp(), "world cannot borrow another world's focus");
        check(!planner.plan(WORLD, follow, Mode.RUMOR_TEST, NOW + 2).orElseThrow().followUp(), "policy mode change discards old state");
        check(!planner.plan(WORLD, request("다시 알려줘"), Mode.PERSONAL, NOW + 3).orElseThrow().followUp(), "switching back does not resurrect old mode focus");
        planner.plan(WORLD, original, Mode.PERSONAL, NOW + 4);
        check(planner.plan(WORLD, follow, Mode.OFF, NOW + 5).isEmpty() && planner.size() == 0, "OFF clears and does not plan");
    }
    private static void lifetime() {
        var planner = new RoomRecallPlanner();
        var first = planner.plan(WORLD, request("내가 내일 어디에 가기로 했지?"), Mode.PERSONAL, NOW).orElseThrow();
        for (int i = 1; i <= 3; i++) check(planner.plan(WORLD, request("다시 알려줘"), Mode.PERSONAL, NOW + i).orElseThrow().followUp(),
                "three distinct following turns retain existing focus");
        check(!planner.plan(WORLD, request("다시 알려줘"), Mode.PERSONAL, NOW + 4).orElseThrow().followUp(), "fourth turn expires focus");
        planner.clear(); check(planner.size() == 0, "runtime stop clears transient planning");
        check(!planner.plan(WORLD, request("다시 알려줘"), Mode.PERSONAL, NOW + 5).orElseThrow().followUp(), "no persistence implied");
        planner.plan(WORLD, request(first.text()), Mode.PERSONAL, NOW + 6);
        check(!planner.plan(WORLD, request("다시 알려줘"), Mode.PERSONAL, NOW + 300007).orElseThrow().followUp(), "five-minute time bound remains");
        planner.plan(WORLD, request(first.text()), Mode.PERSONAL, NOW + 300008);
        planner.plan(WORLD, request("다른 얘기 하자"), Mode.PERSONAL, NOW + 300009);
        check(!planner.plan(WORLD, request("다시 알려줘"), Mode.PERSONAL, NOW + 300010).orElseThrow().followUp(), "topic change clears focus");
    }
    private static void secondaryAttribution() {
        var planner = new RoomRecallPlanner(); var question = request("내가 어디에 가기로 했지?");
        var secondary = new Request(question.roomId(), question.revision(), UUID.randomUUID(), question.playerId(), question.playerName(),
                question.godIds(), question.speakerGodId(), question.currentText(), List.of(), false, true, false, question.godStates(), true, question.audiencePlayerIds());
        var npc = planner.plan(WORLD, secondary, Mode.PERSONAL, NOW).orElseThrow();
        check(!npc.explicit() && !npc.followUp() && npc.focus() == null && planner.size() == 0,
                "other NPC speech never creates player focus even when phrased as a recall question");
        check(!planner.plan(WORLD, request("다시 알려줘"), Mode.PERSONAL, NOW + 1).orElseThrow().followUp(),
                "bare player follow-up cannot inherit previous NPC question");
        var original = planner.plan(WORLD, request("네가 바다에서 뭐라고 말했지?"), Mode.PERSONAL, NOW + 2).orElseThrow();
        planner.plan(WORLD, secondary, Mode.PERSONAL, NOW + 3);
        var next = planner.plan(WORLD, request("다시 알려줘"), Mode.PERSONAL, NOW + 4).orElseThrow();
        check(next.followUp() && next.text().equals(original.text()), "secondary turn leaves genuine player focus untouched");
    }
    private static void bounded() {
        var planner = new RoomRecallPlanner(); var first = request("내가 뭐라고 말했지?");
        planner.plan(WORLD, first, Mode.PERSONAL, NOW);
        for (int i = 0; i < 4096; i++) planner.plan(WORLD,
                copy(first, UUID.randomUUID(), 2, UUID.randomUUID(), PLAYER, List.of(GOD), GOD, first.currentText(), false, Set.of(PLAYER)),
                Mode.PERSONAL, NOW + i);
        check(planner.size() == 4096, "bounded 4096 scope states");
        check(!planner.plan(WORLD, request("다시 알려줘"), Mode.PERSONAL, NOW + 5000).orElseThrow().followUp(), "evicted focus cannot survive through another store");
    }
    private static Request request(String text) {
        return new Request(ROOM, 2, UUID.randomUUID(), PLAYER, "Fixture", List.of(GOD), GOD, text, List.of(), false, true, false,
                List.of(new GodState(GOD, "R_NEUTRAL", "E_NEUTRAL", "", null)), false, Set.of(PLAYER));
    }
    private static Request copy(Request request, UUID room, long revision, UUID turn, UUID player, List<ResourceLocation> gods,
            ResourceLocation god, String text, boolean publicRoom, Set<UUID> audience) {
        return new Request(room, revision, turn, player, request.playerName(), gods, god, text, List.of(), false, true, publicRoom,
                List.of(new GodState(god, "R_NEUTRAL", "E_NEUTRAL", "", null)), false, audience);
    }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
