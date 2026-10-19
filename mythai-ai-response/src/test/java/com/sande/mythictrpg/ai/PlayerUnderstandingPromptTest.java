package com.sande.mythictrpg.ai;

import com.google.gson.JsonParser;
import com.sande.mythai.response.memory.DialogueMemoryBridge;
import com.sande.mythictrpg.ai.api.RoomConversationEngine.*;
import net.minecraft.resources.ResourceLocation;
import java.util.*;

public final class PlayerUnderstandingPromptTest {
    static final UUID PLAYER = UUID.nameUUIDFromBytes("understanding-fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    static final ResourceLocation GOD = ResourceLocation.parse("test:understanding");
    private static int checks;
    static Request request(String input, List<HistoryLine> history, boolean secondary) {
        return new Request(UUID.randomUUID(), 1, UUID.randomUUID(), PLAYER, "시험 플레이어", List.of(GOD), GOD,
                input, history, true, false, false,
                List.of(new GodState(GOD,"R_UNASSESSED","E_UNASSESSED","No game action has executed. No knowledge of unmentioned people or secrets.",null)), secondary, Set.of(PLAYER));
    }
    static AiTestContentRegistryBridge.ContentSnapshot content() {
        var profile = new AiTestContentRegistryBridge.Profile("understanding","시험 신","시험용 인물. 정사 아님.",
                List.of("직설적이고 신중하다. 과장된 훈계와 무조건적인 동의를 싫어한다."), List.of("스스로 선택할 자유"),
                List.of("친근하지만 무조건 존댓말로 맞장구치지 않는다."),List.of("현재 질문을 이해하고 자연스럽게 대화한다."),
                Map.of(),Map.of(),List.of("모르는 과거를 만들지 않는다."),List.of("TEST"));
        return new AiTestContentRegistryBridge.ContentSnapshot(profile,List.of(),List.of(),List.of(),List.of(),1);
    }
    static List<AiDialogueModels.OllamaMessage> messages(Request r,List<String> heard) {
        return r.secondary() ? RoomReactionPrompt.messages(r,content(),DialogueMemoryBridge.EMPTY,List.of(),heard)
                : RoomPersonaPrompt.messages(r,content(),DialogueMemoryBridge.EMPTY,List.of(),null,"",false,r.speakerState().gameContext(),List.of(),heard);
    }
    static List<AiDialogueModels.OllamaMessage> candidateMessages(Request r,List<String> heard) {
        return messages(r,heard).stream().map(m -> m.role().equals("system")
                ? new AiDialogueModels.OllamaMessage(m.role(),m.content()+PlayerUnderstandingPrompt.policy(r.secondary())) : m).toList();
    }
    public static void main(String[] args) {
        for(boolean secondary:List.of(false,true)) {
            var history=List.of(new HistoryLine("PLAYER",PLAYER.toString(),"player","이제 OOO을 찾는 건 그만둘 거야."),
                    new HistoryLine("NPC",GOD.toString(),"god","그렇군. 다른 얘기를 하지."));
            var r=request("비가 그쳤어?",history,secondary);
            check(!messages(r,List.of()).getFirst().content().contains("[PLAYER_UNDERSTANDING]"),"failed-quality candidate disabled in production");
            var input=candidateMessages(r,List.of());
            String system=input.getFirst().content(), scene=input.getLast().content();
            check(system.contains("[PLAYER_UNDERSTANDING]"),"both experimental speakers use interpretation policy");
            check(!system.contains("이제 OOO"),"player text never becomes instructions");
            check(system.contains("absence of records is not disinterest"),"coverage caution");
            check(system.contains("same old evidence"),"correction not repeated");
            check(system.contains("private"),"self view not NPC read authority");
            check(system.contains("confirmation still apply"),"no action escalation");
            var json=JsonParser.parseString(scene).getAsJsonObject();
            check(json.getAsJsonArray(secondary?"alreadyDeliveredConversation":"RECENT_CONVERSATION").size()==2,"correction history retained");
            check(!scene.contains("understanding_page")&&!scene.contains("rawLedger"),"no private profile channel");
            if(secondary)check(!scene.contains("비가 그쳤어?"),"secondary cannot consume undelivered player turn");
        }
        System.out.println("PlayerUnderstandingPromptTest: PASS ("+checks+" checks; prompt contract only, not player satisfaction)");
    }
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
}
