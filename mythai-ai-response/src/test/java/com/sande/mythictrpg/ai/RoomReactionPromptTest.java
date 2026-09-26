package com.sande.mythictrpg.ai;

import com.sande.mythai.response.memory.DialogueMemoryBridge;
import com.sande.mythictrpg.ai.api.RoomConversationEngine.*;
import net.minecraft.resources.ResourceLocation;
import java.util.*;

/** Offline contract/prompt tests; a fixture proves isolation, not a model's narrative quality. */
public final class RoomReactionPromptTest {
    private static int checks;
    public static void main(String[] args) {
        var god = ResourceLocation.parse("test:listener");
        var primary = ResourceLocation.parse("test:primary");
        var player = UUID.randomUUID();
        var request = new Request(UUID.randomUUID(), 1, UUID.randomUUID(), player, "player", List.of(primary, god), god,
                "PLAYER_ASKED", List.of(new HistoryLine("NPC", primary.toString(), "primary", "ACTUALLY_PUBLISHED")),
                true, true, false, List.of(new GodState(god, "R_NEUTRAL", "E_NEUTRAL", "OWN_RELATION_FEEDBACK", null)), true);
        var profile = new AiTestContentRegistryBridge.Profile("listener", "OWN_IDENTITY", "description", List.of("OWN_PERSONALITY"),
                List.of("OWN_VALUES"), List.of("T_COOL"), List.of("OWN_GUIDANCE"), Map.of(), Map.of(), List.of(), List.of());
        var content = new AiTestContentRegistryBridge.ContentSnapshot(profile,
                List.of(new AiTestContentRegistryBridge.Lore("public", "public", "PUBLIC", 1, List.of("PUBLIC_DETAIL")),
                        new AiTestContentRegistryBridge.Lore("permitted_private", "private", "SECRET", 1, List.of("AUTHOR_APPROVED_PRIVATE_LORE"))),
                List.of(), List.of("OWN_RELATION_GUIDANCE"), List.of("STATIC_FEAR"), 1);
        var memory = new DialogueMemoryBridge.Turn(null, null, List.of(), List.of(), "OWN_MEMORY", 1);
        var messages = RoomReactionPrompt.messages(request, content, memory);
        String prompt = messages.stream().map(AiDialogueModels.OllamaMessage::content).reduce("", String::concat);
        for (String required : List.of("OWN_IDENTITY", "OWN_PERSONALITY", "OWN_MEMORY", "OWN_RELATION_FEEDBACK", "ACTUALLY_PUBLISHED",
                "STATIC_FEAR", "PUBLIC_DETAIL", "remain silent", "proposals")) check(prompt.contains(required), required);
        check(prompt.contains("AUTHOR_APPROVED_PRIVATE_LORE"), "registry-approved private lore is not silently discarded by a PUBLIC-only second filter");
        check(!prompt.contains("PLAYER_ASKED"), "unproven raw player input cannot bypass filtered history");
        var silence = new AiDialogueModels.StructuredAiResult(List.of(), "", List.of());
        check(RoomReactionPrompt.speech(request, silence).isEmpty(), "silence not a generation failure");
        var spoken = new AiDialogueModels.StructuredAiResult(List.of(new AiDialogueModels.Speech(god.toString(), "네 말에 동의하지 않는다.", List.of())), "", List.of());
        check(RoomReactionPrompt.speech(request, spoken).getFirst().godId().equals(god), "own response accepted");
        rejected(() -> RoomReactionPrompt.speech(request, new AiDialogueModels.StructuredAiResult(
                List.of(new AiDialogueModels.Speech(primary.toString(), "impersonated", List.of())), "", List.of())), "foreign speaker rejected");
        rejected(() -> RoomReactionPrompt.speech(request, new AiDialogueModels.StructuredAiResult(
                List.of(new AiDialogueModels.Speech(god.toString(), "private aside", List.of(player.toString()))), "", List.of())),
                "model cannot change the authorized audience");
        for (String action : List.of("quest_offer", "relationship_change", "conversation_leave", "story_event_hook")) {
            rejected(() -> RoomReactionPrompt.speech(request, new AiDialogueModels.StructuredAiResult(spoken.speech(), "",
                    List.of(new AiDialogueModels.Proposal(action, "", "", List.of(), Map.of())))), "no secondary action: " + action);
        }
        var live=new Request(request.roomId(),request.revision(),request.turnId(),player,"player",request.godIds(),god,
                request.currentText(),request.history(),false,true,false,request.godStates(),true,Set.of(player));
        check(RoomReactionPrompt.speech(live,new AiDialogueModels.StructuredAiResult(spoken.speech(),"",
                List.of(new AiDialogueModels.Proposal("story_event_hook","","",List.of(),Map.of("hook_alias","hook_1"))))).size()==1,
                "live reaction forwards own hook to normalizer/game validator");
        rejected(() -> RoomReactionPrompt.speech(request, new AiDialogueModels.StructuredAiResult(
                List.of(new AiDialogueModels.Speech(god.toString(), "x".repeat(4001), List.of())), "", List.of())), "oversized reaction rejected");
        var otherRoom = new Request(UUID.randomUUID(), 1, UUID.randomUUID(), player, "player", List.of(primary,god), god,
                "DIFFERENT_PLAYER_TEXT", List.of(), true, false, true,
                List.of(new GodState(god,"R_NEUTRAL","E_NEUTRAL","OTHER_ROOM_CONTEXT",null)), true);
        String other = RoomReactionPrompt.messages(otherRoom, content, DialogueMemoryBridge.EMPTY).toString();
        check(!other.contains("ACTUALLY_PUBLISHED") && !other.contains("OWN_RELATION_FEEDBACK") && !other.contains("OWN_MEMORY"),
                "same god/player does not share another room's context");
        publicationIdentityBoundary(request, god);
        System.out.println("RoomReactionPromptTest: PASS (" + checks + " checks; no server or model)");
    }
    private static void publicationIdentityBoundary(Request request, ResourceLocation god) {
        var result = new Result(request.roomId(), request.revision(), request.turnId(),
                List.of(new Speech(god, "delivered identity")), "[]", List.of(), "");
        var event = new com.sande.mythictrpg.ai.api.RoomDialogueEvent(UUID.randomUUID(), request.roomId(), request.revision(),
                Optional.of(request.turnId()), com.sande.mythictrpg.ai.room.RoomType.PRIVATE,
                com.sande.mythictrpg.ai.room.RecordingScope.STANDARD, "NPC", god.toString(), "delivered identity",
                Set.of(god.toString()), Map.of(request.playerId(), "player"), Map.of(), 1);
        check(MythAiRoomConversationEngine.publicationMatches(request, result, event), "identity deferral requires exact delivered bundle");
        for (int variant = 0; variant < 6; variant++) {
            var invalid = new com.sande.mythictrpg.ai.api.RoomDialogueEvent(UUID.randomUUID(), variant == 0 ? UUID.randomUUID() : event.roomId(),
                    variant == 1 ? event.revision() + 1 : event.revision(), variant == 2 ? Optional.of(UUID.randomUUID()) : event.turnId(),
                    event.roomType(), event.recordingScope(), variant == 3 ? "PLAYER" : event.role(),
                    variant == 3 ? request.playerId().toString() : variant == 4 ? "test:foreign" : event.speakerId(), variant == 5 ? "unrelated" : event.text(),
                    variant == 4 ? Set.of("test:foreign") : event.godIds(), event.participantNames(), event.deliveries(), 1);
            check(!MythAiRoomConversationEngine.publicationMatches(request, result, invalid), "foreign publication not in identity batch " + variant);
        }
    }
    private static void check(boolean okay,String label) { checks++; if(!okay)throw new AssertionError(label); }
    private static void rejected(Runnable code,String label) {
        try {code.run();throw new AssertionError(label);}catch(IllegalArgumentException expected){checks++;}
    }
}
