package com.sande.mythictrpg.ai.room;

import com.sande.mythictrpg.ai.action.AiActionResult;
import com.sande.mythictrpg.ai.api.RoomConversationEngine;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Pure result-evidence contract checks; never invokes a model, gateway or game executor. */
public final class RoomActionOutcomeContractTest {
    private static final ResourceLocation GOD = ResourceLocation.parse("mythictrpg:fortuna");
    private static int checks;

    public static void main(String[] args) {
        projectionContainsOnlyBoundedGameFields();
        pendingAndTerminalAreDifferentEvidence();
        immutableRequestAndConstructorCompatibility();
        System.out.println("RoomActionOutcomeContractTest: " + checks + " assertions passed");
    }

    private static void projectionContainsOnlyBoundedGameFields() {
        var details = new LinkedHashMap<String, String>();
        details.put("item_id", "minecraft:gold_ingot"); details.put("count", "2");
        details.put("title", "MODEL_TITLE"); details.put("summary", "MODEL_SUMMARY");
        details.put("private_payload", "PRIVATE_DATA"); details.put("template_id", "가".repeat(159) + "😀rest\n");
        var result = new AiActionResult(UUID.randomUUID(), ResourceLocation.parse("mythictrpg:item_request"),
                AiActionResult.Status.EXECUTED, "r".repeat(319) + "😀rest\n", details);
        var projected = projection(result);
        check(projected.details().get("item_id").equals("minecraft:gold_ingot") && projected.details().get("count").equals("2"),
                "actual item ID and count retained");
        check(!projected.details().containsKey("title") && !projected.details().containsKey("summary")
                && !projected.details().containsKey("private_payload"), "model fields and arbitrary executor details excluded");
        check(projected.reason().length() == 319 && projected.details().get("template_id").length() == 159,
                "bounds never split a supplementary character");
        check(projected.actionType().equals("mythictrpg:item_request") && projected.proposalId().equals(result.proposalId()),
                "exact result identity retained");
        try { projected.details().put("count", "99"); throw new AssertionError("Mutable details"); }
        catch (UnsupportedOperationException expected) { checks++; }
        var clean = new AiActionResult(UUID.randomUUID(), GOD, AiActionResult.Status.EXECUTED,
                "server\nresult", Map.of("status", "MENU\tOPENED"));
        check(clean.dialogueReason().equals("server result") && clean.dialogueDetails().get("status").equals("MENU OPENED"),
                "control characters removed before JSON encoding");
        for (var status : AiActionResult.Status.values()) {
            var value = new AiActionResult(UUID.randomUUID(), GOD, status, "reason", Map.of("count", "9"));
            check(projection(value).status().name().equals(status.name()), "all gateway statuses retain distinct meaning");
            check(status == AiActionResult.Status.EXECUTED || value.dialogueDetails().isEmpty(),
                    "nonexecution status cannot carry execution details");
        }
        rejects(() -> new RoomConversationEngine.ActionOutcome(UUID.randomUUID(), "bad action", RoomConversationEngine.ActionStatus.EXECUTED,
                "", Map.of()), "invalid action ID rejected");
        rejects(() -> new RoomConversationEngine.ActionOutcome(UUID.randomUUID(), GOD.toString(), RoomConversationEngine.ActionStatus.PENDING_CONFIRMATION,
                "", Map.of("count", "9")), "pending cannot impersonate executed details");
    }

    private static void pendingAndTerminalAreDifferentEvidence() {
        UUID id = UUID.randomUUID();
        var pending = projection(new AiActionResult(id, GOD, AiActionResult.Status.PENDING_CONFIRMATION, "confirm", Map.of()));
        var done = projection(new AiActionResult(id, GOD, AiActionResult.Status.EXECUTED, "consumed", Map.of("count", "1")));
        check(!List.of(pending).equals(List.of(done)), "pending-to-executed invalidates captured evidence");
        check(!List.of(done).equals(List.of(projection(new AiActionResult(id, GOD, AiActionResult.Status.EXECUTED,
                "consumed", Map.of("count", "2"))))), "execution-detail changes invalidate captured evidence");
        check(List.of(done).equals(List.of(projection(new AiActionResult(id, GOD, AiActionResult.Status.EXECUTED,
                "consumed", Map.of("count", "1"))))), "unchanged evidence compares equal across projections");
        var forming = projection(new AiActionResult(id, ResourceLocation.parse("mythictrpg:raid_offer"), AiActionResult.Status.EXECUTED,
                "formed", Map.of("status", "FORMING", "combat_started", "false")));
        check(forming.details().get("status").equals("FORMING") && forming.details().get("combat_started").equals("false"),
                "executed raid offer retains recruitment boundary");
    }

    private static void immutableRequestAndConstructorCompatibility() {
        UUID room = UUID.randomUUID(), turn = UUID.randomUUID(), player = UUID.randomUUID();
        var states = List.of(new RoomConversationEngine.GodState(GOD, "R_NEUTRAL", "UNASSESSED", "", null));
        var old13 = new RoomConversationEngine.Request(room, 1, turn, player, "player", List.of(GOD), GOD, "text",
                List.of(), false, false, false, states);
        var old14 = new RoomConversationEngine.Request(room, 1, turn, player, "player", List.of(GOD), GOD, "text",
                List.of(), false, false, false, states, false);
        var old15 = new RoomConversationEngine.Request(room, 1, turn, player, "player", List.of(GOD), GOD, "text",
                List.of(), false, false, false, states, false, Set.of(player));
        check(old13.actionOutcomes().isEmpty() && old14.actionOutcomes().isEmpty() && old15.actionOutcomes().isEmpty(),
                "all prior constructors default to no result evidence");
        var outcome = projection(new AiActionResult(UUID.randomUUID(), GOD, AiActionResult.Status.EXECUTED, "ok", Map.of()));
        var outcomes = new ArrayList<>(List.of(outcome));
        var snapshot = request(old15, false, false, outcomes);
        outcomes.clear();
        check(snapshot.actionOutcomes().equals(List.of(outcome)), "request captures immutable evidence snapshot");
        try { snapshot.actionOutcomes().clear(); throw new AssertionError("Mutable outcomes"); }
        catch (UnsupportedOperationException expected) { checks++; }
        rejects(() -> request(old15, true, false, List.of(outcome)), "read-only context cannot receive action receipts");
        rejects(() -> request(old15, false, true, List.of(outcome)), "secondary cannot receive another speaker's action receipts");
        rejects(() -> request(old15, false, false, List.of(outcome, outcome)), "duplicate receipt identity rejected");
        var maximum = new ArrayList<RoomConversationEngine.ActionOutcome>();
        for (int i = 0; i < 16; i++) maximum.add(projection(new AiActionResult(UUID.randomUUID(), GOD, AiActionResult.Status.EXECUTED, "", Map.of())));
        check(request(old15, false, false, maximum).actionOutcomes().size() == 16, "existing feedback-window limit preserved");
        maximum.add(outcome);
        rejects(() -> request(old15, false, false, maximum), "oversized outcome window rejected");
    }

    private static RoomConversationEngine.Request request(RoomConversationEngine.Request source, boolean readOnly, boolean secondary,
            List<RoomConversationEngine.ActionOutcome> outcomes) {
        return new RoomConversationEngine.Request(source.roomId(), source.revision(), source.turnId(), source.playerId(), source.playerName(),
                source.godIds(), source.speakerGodId(), source.currentText(), source.history(), readOnly, source.recording(), source.publicRoom(),
                source.godStates(), secondary, source.audiencePlayerIds(), outcomes);
    }
    private static RoomConversationEngine.ActionOutcome projection(AiActionResult value) {
        return new RoomConversationEngine.ActionOutcome(value.proposalId(), value.actionType().toString(),
                RoomConversationEngine.ActionStatus.valueOf(value.status().name()), value.dialogueReason(), value.dialogueDetails());
    }
    private static void rejects(Runnable operation, String message) {
        try { operation.run(); throw new AssertionError(message); }
        catch (IllegalArgumentException expected) { checks++; }
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
