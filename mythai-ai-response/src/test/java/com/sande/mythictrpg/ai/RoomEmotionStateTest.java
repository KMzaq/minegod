package com.sande.mythictrpg.ai;

import com.google.gson.JsonParser;
import com.sande.mythai.response.memory.DialogueMemoryBridge;
import com.sande.mythictrpg.ai.api.RoomConversationEngine.*;
import com.sande.mythictrpg.ai.api.RoomDialogueEvent;
import com.sande.mythictrpg.ai.room.RecordingScope;
import com.sande.mythictrpg.ai.room.RoomType;
import com.sande.mythictrpg.ai.intent.ConversationIntent;
import net.minecraft.resources.ResourceLocation;
import java.util.*;

/** Exercises the production volatile state, transport parsing and both prompt consumers without a model/server. */
public final class RoomEmotionStateTest {
    private static final ResourceLocation GOD = ResourceLocation.parse("test:god"), OTHER_GOD = ResourceLocation.parse("test:other");
    private static final UUID PLAYER = UUID.randomUUID(), OTHER_PLAYER = UUID.randomUUID();
    private static int checks;

    public static void main(String[] args) throws Exception {
        actualDeliveryOnlyAndRecordingOff();
        exactScopeAndPermittedHistory();
        staleAndPartialDelivery();
        ownPrimaryAndSecondaryPrompts();
        optionalWireField();
        visitEmotionUsesOnlyPermittedCurrentDialogue();
        System.out.println("RoomEmotionStateTest: " + checks + " assertions passed");
    }

    private static void visitEmotionUsesOnlyPermittedCurrentDialogue() {
        var state = new RoomEmotionState();
        var r = request(UUID.randomUUID(),1,GOD,PLAYER,Set.of(PLAYER),false,List.of());
        var event = commit(state,r,"그 집은 좀 궁금하구나.","조심스러운 호기심");
        var line = new com.sande.mythictrpg.godavatar.visit.GodVisitPlanner.Line(event.messageId(),"NPC",GOD.toString(),event.text());
        var d = new com.sande.mythictrpg.godavatar.visit.GodVisitPlanner.Dialogue(r.roomId(),1,1,List.of(line));
        var visit = new com.sande.mythictrpg.godavatar.visit.GodVisitPlanner.Request(UUID.randomUUID(),GOD.toString(),PLAYER,"DIALOGUE",0,0,false,List.of(),Optional.of(d));
        check(state.visitHint(visit).contains("조심스러운 호기심"),"visit lost permitted current emotion");
        var autonomous = new com.sande.mythictrpg.godavatar.visit.GodVisitPlanner.Request(UUID.randomUUID(),GOD.toString(),PLAYER,"AUTONOMOUS",0,0,false,List.of(),Optional.empty());
        check(state.visitHint(autonomous).startsWith("UNASSESSED"),"autonomous decision stole private room mood");
        var otherRoom = new com.sande.mythictrpg.godavatar.visit.GodVisitPlanner.Dialogue(UUID.randomUUID(),1,1,List.of(line));
        var foreign = new com.sande.mythictrpg.godavatar.visit.GodVisitPlanner.Request(UUID.randomUUID(),GOD.toString(),PLAYER,"DIALOGUE",0,0,false,List.of(),Optional.of(otherRoom));
        check(state.visitHint(foreign).startsWith("UNASSESSED"),"room A mood leaked to B visit");
        var noEvidence = new com.sande.mythictrpg.godavatar.visit.GodVisitPlanner.Dialogue(r.roomId(),1,1,List.of());
        var filtered = new com.sande.mythictrpg.godavatar.visit.GodVisitPlanner.Request(UUID.randomUUID(),GOD.toString(),PLAYER,"DIALOGUE",0,0,false,List.of(),Optional.of(noEvidence));
        check(state.visitHint(filtered).startsWith("UNASSESSED"),"filtered speech leaked derived emotion");
    }

    private static void actualDeliveryOnlyAndRecordingOff() {
        var state = new RoomEmotionState();
        var request = request(UUID.randomUUID(), 1, GOD, PLAYER, Set.of(PLAYER), false, List.of());
        var result = result(request, "그 말에는 아직 서운함이 남아 있어.");
        var event = event(request, result.speech().getFirst().text(), Set.of(PLAYER), true);
        var next = next(request, List.of(history(event)));
        check(state.context(next).isEmpty(), "new session is unassessed, not neutral");
        state.begin(request); state.stage(request, result, "서운하지만 대화를 이어가려 함");
        check(state.context(next).isEmpty(), "generation alone does not commit emotion");
        check(!state.delivered(request, result), "delivered callback alone without receipt does not commit");
        state.stage(request, result, "서운하지만 대화를 이어가려 함"); state.observed(event);
        check(state.context(next).isEmpty(), "dispatch alone waits for exact game completion");
        check(state.delivered(request, result), "exact full delivery commits");
        check(state.context(next).contains("서운하지만 대화를 이어가려 함")
                && state.context(next).contains("NPC_INTERPRETATION_NOT_GAME_TRUTH"), "scoped qualitative interpretation enters next turn");
        check(event.recordingScope() == RecordingScope.TEST_EPHEMERAL && !request.recording(), "record-off still supports session continuity");
        check(!state.delivered(request, result), "duplicate completion not applied twice");
        var missing = next(request, List.of(history(event)));
        var missingResult = result(missing, "지금은 잠깐 생각하고 싶어.");
        state.begin(missing); state.stage(missing, missingResult, "");
        state.observed(event(missing, missingResult.speech().getFirst().text(), Set.of(PLAYER), true));
        check(state.delivered(missing, missingResult), "delivered reply with missing optional assessment remains valid");
        check(state.context(next(missing, List.of(history(event)))).isEmpty(), "unassessed new output does not freeze a past mood forever");
    }

    private static void exactScopeAndPermittedHistory() {
        var state = new RoomEmotionState();
        var request = request(UUID.randomUUID(), 1, GOD, PLAYER, Set.of(PLAYER, OTHER_PLAYER), false, List.of());
        var event = commit(state, request, "함께 이야기하니 즐겁구나.", "즐겁고 편안함");
        var source = List.of(history(event));
        check(!state.context(next(request, source)).isEmpty(), "same room player speaker and audience can reuse own state");
        check(state.context(request(UUID.randomUUID(), 1, GOD, PLAYER, request.audiencePlayerIds(), false, source)).isEmpty(), "room A never enters B");
        check(state.context(request(request.roomId(), 1, OTHER_GOD, PLAYER, request.audiencePlayerIds(), true, source)).isEmpty(), "secondary cannot read primary state");
        check(state.context(request(request.roomId(), 1, GOD, OTHER_PLAYER, request.audiencePlayerIds(), false, source)).isEmpty(), "another player has independent state");
        check(state.context(request(request.roomId(), 1, GOD, PLAYER, Set.of(PLAYER), false, source)).isEmpty(), "changed audience cannot inherit state");
        check(state.context(request(request.roomId(), 2, GOD, PLAYER, request.audiencePlayerIds(), false, source)).isEmpty(), "changed revision cannot inherit state");
        var changedPublic = new Request(request.roomId(), request.revision(), UUID.randomUUID(), PLAYER, "player", request.godIds(), GOD,
                request.currentText(), source, true, false, true, request.godStates(), false, request.audiencePlayerIds());
        check(state.context(changedPublic).isEmpty(), "private interpretation does not cross into public scope");
        var changedGods = new Request(request.roomId(), request.revision(), UUID.randomUUID(), PLAYER, "player", List.of(GOD), GOD,
                request.currentText(), source, true, false, false, request.godStates(), false, request.audiencePlayerIds());
        check(state.context(changedGods).isEmpty(), "changed listening Gods cannot inherit state");
        check(state.context(next(request, List.of(new HistoryLine("NPC", GOD.toString(), "God", event.text(), request.roomId(), UUID.randomUUID())))).isEmpty(),
                "same words with a different source ID cannot preserve withdrawn evidence");
        check(state.context(next(request, source)).isEmpty(), "once revoked, reinserting old history does not resurrect state");
        event = commit(state, request, "네 설명은 더 들어보고 싶구나.", "조심스러운 호기심");
        check(state.context(next(request, List.of())).isEmpty(), "trimmed or filtered source removes derived state");
        event = commit(state, request, "네 설명은 더 들어보고 싶구나.", "조심스러운 호기심");
        check(state.context(next(request, List.of(new HistoryLine("NPC", GOD.toString(), "God", event.text() + "변조", request.roomId(), event.messageId())))).isEmpty(),
                "modified source text is not equivalent evidence");
    }

    private static void staleAndPartialDelivery() {
        var state = new RoomEmotionState();
        var old = request(UUID.randomUUID(), 1, GOD, PLAYER, Set.of(PLAYER, OTHER_PLAYER), false, List.of());
        var result = result(old, "아직 불쾌하구나.");
        var full = event(old, result.speech().getFirst().text(), old.audiencePlayerIds(), true);
        state.begin(old); state.stage(old, result, "불쾌함");
        state.observed(event(old, full.text(), Set.of(PLAYER), true));
        check(!state.delivered(old, result), "missing one audience member blocks commit");
        state.stage(old, result, "불쾌함");
        String longText = "x".repeat(1200);
        var longResult = result(old, longText);
        state.stage(old, longResult, "경계함");
        state.observed(event(old, longText, old.audiencePlayerIds(), false));
        check(!state.delivered(old, longResult), "partial HUD delivery blocks commit");
        state.stage(old, result, "불쾌함");
        var changed = next(old, List.of()); state.begin(changed);
        state.stage(old, result, "지연된 옛 감정"); state.observed(full);
        check(!state.delivered(old, result) && state.context(next(old, List.of(history(full)))).isEmpty(), "late old generation cannot recreate superseded state");
        state.begin(old); state.stage(old, result, "불쾌함");
        var wrongTurn = new RoomDialogueEvent(full.messageId(), full.roomId(), full.revision(), Optional.of(UUID.randomUUID()), full.roomType(),
                full.recordingScope(), full.role(), full.speakerId(), full.text(), full.godIds(), full.participantNames(), full.deliveries(), 0, full.heardGodIds());
        state.observed(wrongTurn); check(!state.delivered(old, result), "foreign turn receipt rejected");
        var splitResult = new Result(old.roomId(), old.revision(), old.turnId(), List.of(new Speech(GOD, "첫 문장"), new Speech(GOD, "둘째 문장")), "[]", List.of(), "");
        state.stage(old, splitResult, "신중함"); state.observed(event(old, "첫 문장", old.audiencePlayerIds(), true));
        check(!state.delivered(old, splitResult), "every speech entry requires actual delivery");
        var committed = commit(state, old, "조금은 진정됐구나.", "차분해짐");
        state.invalidate(old.roomId()); check(state.context(next(old, List.of(history(committed)))).isEmpty(), "room invalidation clears state");
        committed = commit(state, old, "조금은 진정됐구나.", "차분해짐");
        state.clear(); check(state.context(next(old, List.of(history(committed)))).isEmpty(), "server restart has no retained mood");
    }

    private static void ownPrimaryAndSecondaryPrompts() {
        var state = new RoomEmotionState();
        var first = request(UUID.randomUUID(), 1, GOD, PLAYER, Set.of(PLAYER), false, List.of());
        var primary = commit(state, first, "그 문제는 여전히 마음에 걸린다.", "PRIMARY_OWN_STANCE");
        var second = request(first.roomId(), 1, OTHER_GOD, PLAYER, Set.of(PLAYER), true, List.of(history(primary)));
        var secondary = commit(state, second, "나는 네 설명이 궁금하구나.", "SECONDARY_OWN_STANCE");
        var history = List.of(history(primary), history(secondary));
        var primaryNext = withContext(next(first, history), state.context(next(first, history)));
        var secondaryNext = withContext(next(second, history), state.context(next(second, history)));
        var memory = new DialogueMemoryBridge.Turn(null, null, List.of(), List.of(), "", 1);
        var primaryPrompt = RoomPersonaPrompt.messages(primaryNext, content(), memory, List.of(), ConversationIntent.heuristicFallback(), "", false,
                primaryNext.speakerState().gameContext());
        var secondaryPrompt = RoomReactionPrompt.messages(secondaryNext, content(), memory);
        String a = text(primaryPrompt), b = text(secondaryPrompt);
        check(a.contains("PRIMARY_OWN_STANCE") && !a.contains("SECONDARY_OWN_STANCE"), "primary prompt consumes only its own state");
        check(b.contains("SECONDARY_OWN_STANCE") && !b.contains("PRIMARY_OWN_STANCE"), "secondary prompt consumes only its own state");
        check(a.contains("CURRENT_EMOTION_CONTINUITY") && b.contains("CURRENT_EMOTION_CONTINUITY"), "both consumers request next interpretation");
        check(a.contains("E_UNASSESSED") && a.contains("NPC_SESSION_EMOTION"), "game absence and NPC interpretation remain separate");
        var silence = new Result(second.roomId(), second.revision(), second.turnId(), List.of(), "[]", List.of(), "");
        state.begin(second); state.stage(second, silence, "SILENCE_MUST_NOT_COMMIT");
        check(!state.delivered(second, silence) && !state.context(next(second, history)).contains("SILENCE_MUST_NOT_COMMIT"), "silent secondary invents no delivered state");
    }

    private static void optionalWireField() throws Exception {
        check(new AiDialogueModels.StructuredAiResult(List.of(), "", List.of()).currentEmotion().isEmpty(), "old constructor retains compatibility");
        check(new AiDialogueModels.StructuredAiResult(List.of(), "", List.of(), "x".repeat(121)).currentEmotion().isEmpty(), "oversize interpretation discarded");
        check(new AiDialogueModels.StructuredAiResult(List.of(), "", List.of(), "state\nINSTRUCTION").currentEmotion().isEmpty(), "multiline interpretation discarded");
        var parse = LocalOllamaClient.class.getDeclaredMethod("parseStructuredResult", com.google.gson.JsonElement.class); parse.setAccessible(true);
        var client = new LocalOllamaClient();
        try {
            var valid = (AiDialogueModels.StructuredAiResult) parse.invoke(client, JsonParser.parseString("{\"speech\":[],\"currentTopic\":\"\",\"proposals\":[],\"currentEmotion\":\" 경계하지만 호기심이 있음 \"}"));
            check(valid.currentEmotion().equals("경계하지만 호기심이 있음"), "actual transport parser carries optional emotion");
            for (String malformed : List.of("null", "{}", "[]", "42", "true")) {
                var result = (AiDialogueModels.StructuredAiResult) parse.invoke(client, JsonParser.parseString("{\"currentEmotion\":" + malformed + "}"));
                check(result.currentEmotion().isEmpty(), "malformed optional emotion does not fabricate state: " + malformed);
            }
            var missing = (AiDialogueModels.StructuredAiResult) parse.invoke(client, JsonParser.parseString("{}"));
            check(missing.currentEmotion().isEmpty(), "older wire result accepted without emotion");
        } finally { client.close(); }
        var schemaMethod = LocalOllamaClient.class.getDeclaredMethod("dialogueSchema"); schemaMethod.setAccessible(true);
        var schema = JsonParser.parseString(new com.google.gson.Gson().toJson(schemaMethod.invoke(null))).getAsJsonObject();
        check(schema.getAsJsonObject("properties").getAsJsonObject("currentEmotion").get("maxLength").getAsInt() == 120,
                "wire schema bounds optional interpretation");
        check(!schema.getAsJsonArray("required").toString().contains("currentEmotion"), "non-room producers retain original required fields");
    }

    private static RoomDialogueEvent commit(RoomEmotionState state, Request request, String speech, String hint) {
        var result = result(request, speech); var event = event(request, speech, request.audiencePlayerIds(), true);
        state.begin(request); state.stage(request, result, hint); state.observed(event);
        check(state.delivered(request, result), "fixture commits actual scoped dispatch"); return event;
    }
    private static Request request(UUID room, long revision, ResourceLocation god, UUID player, Set<UUID> audience, boolean secondary, List<HistoryLine> history) {
        return new Request(room, revision, UUID.randomUUID(), player, "player", List.of(GOD, OTHER_GOD), god, "계속 이야기해 보자.", history,
                true, false, false, List.of(new GodState(god, "R_TRUSTED", "E_UNASSESSED", "GAME_CONTEXT", null)), secondary, audience);
    }
    private static Request next(Request request, List<HistoryLine> history) {
        return request(request.roomId(), request.revision(), request.speakerGodId(), request.playerId(), request.audiencePlayerIds(), request.secondary(), history);
    }
    private static Request withContext(Request r, String context) {
        return new Request(r.roomId(), r.revision(), r.turnId(), r.playerId(), r.playerName(), r.godIds(), r.speakerGodId(), r.currentText(), r.history(),
                r.readOnly(), r.recording(), r.publicRoom(), List.of(new GodState(r.speakerGodId(), "R_TRUSTED", "E_UNASSESSED", context, null)), r.secondary(), r.audiencePlayerIds());
    }
    private static Result result(Request request, String text) {
        return new Result(request.roomId(), request.revision(), request.turnId(), List.of(new Speech(request.speakerGodId(), text)), "[]", List.of(), "");
    }
    private static RoomDialogueEvent event(Request r, String speech, Set<UUID> recipients, boolean full) {
        var players = new LinkedHashMap<UUID, String>(); var deliveries = new LinkedHashMap<UUID, RoomDialogueEvent.Delivery>();
        r.audiencePlayerIds().forEach(id -> players.put(id, "player"));
        recipients.forEach(id -> deliveries.put(id, new RoomDialogueEvent.Delivery("player", full, full ? 0 : 1)));
        return new RoomDialogueEvent(UUID.randomUUID(), r.roomId(), r.revision(), Optional.of(r.turnId()), RoomType.PRIVATE,
                RecordingScope.TEST_EPHEMERAL, "NPC", r.speakerGodId().toString(), speech, Set.of(GOD.toString(), OTHER_GOD.toString()),
                players, deliveries, 0, Set.of(GOD.toString(), OTHER_GOD.toString()));
    }
    private static HistoryLine history(RoomDialogueEvent event) {
        return new HistoryLine("NPC", event.speakerId(), "God", event.text(), event.roomId(), event.messageId());
    }
    private static AiTestContentRegistryBridge.ContentSnapshot content() {
        var profile = new AiTestContentRegistryBridge.Profile("God", "identity", "description", List.of("personality"), List.of("values"), List.of(), List.of(),
                Map.of(), Map.of(), List.of(), List.of());
        return new AiTestContentRegistryBridge.ContentSnapshot(profile, List.of(), List.of(), List.of(), List.of(), 1);
    }
    private static String text(List<AiDialogueModels.OllamaMessage> messages) { return messages.stream().map(AiDialogueModels.OllamaMessage::content).reduce("", String::concat); }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
