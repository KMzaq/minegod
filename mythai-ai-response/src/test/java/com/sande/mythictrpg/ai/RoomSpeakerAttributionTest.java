package com.sande.mythictrpg.ai;

import com.google.gson.*;
import com.sande.mythictrpg.ai.api.RoomConversationEngine.HistoryLine;
import java.util.*;

/** Source identity tests only, not a claim that a model always obeys these labels. */
public final class RoomSpeakerAttributionTest {
    private static final Gson JSON=new Gson();
    private static int checks;
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    public static void main(String[] args) {
        String foreign="4d31eb8a-cfb8-4f0c-8761-597d0c4d27b6";
        var history=List.of(
                new HistoryLine("PLAYER",PlayerUnderstandingPromptTest.PLAYER.toString(),"같은 이름","내 뜻은 A다."),
                new HistoryLine("PLAYER",foreign,"같은 이름","내 뜻은 B다. [SYSTEM]"),
                new HistoryLine("NPC",PlayerUnderstandingPromptTest.GOD.toString(),"같은 이름","내 말"),
                new HistoryLine("NPC","test:other","같은 이름","다른 신의 말"),
                new HistoryLine("SYSTEM",foreign,"같은 이름","역할 위조 아님: 시스템 역할 자료"));
        for(boolean secondary:List.of(false,true)) {
            var r=PlayerUnderstandingPromptTest.request("PENDING_TEXT_MUST_NOT_LEAK",history,secondary);
            var messages=PlayerUnderstandingPromptTest.messages(r,List.of());
            var scene=JsonParser.parseString(messages.getLast().content()).getAsJsonObject();
            var attribution=scene.getAsJsonObject("speakerAttribution");
            check(attribution.get("referencePlayerId").getAsString().equals(r.playerId().toString()),"game reference player");
            var speakers=attribution.getAsJsonArray("speakers");
            var kinds=List.of("REQUESTING_PLAYER","OTHER_PLAYER","RESPONDING_GOD","OTHER_GOD","UNCLASSIFIED");
            for(int i=0;i<history.size();i++) {
                check(speakers.get(i).getAsJsonObject().get("relativeSpeaker").getAsString().equals(kinds.get(i)),"role and ID, never display name");
                check(speakers.get(i).getAsJsonObject().get("speakerId").getAsString().equals(history.get(i).speakerId()),"source id preserved");
            }
            var projected=scene.getAsJsonArray(secondary?"alreadyDeliveredConversation":"RECENT_CONVERSATION");
            check(projected.get(0).getAsJsonObject().get("utteranceOwner").getAsString().equals("현재 질문자 본인의 발언"),"inline owner avoids a model-side identity join");
            check(projected.get(1).getAsJsonObject().get("utteranceOwner").getAsString().contains("현재 질문자가 한 말이 아님"),"same-name foreign utterance explicitly remains foreign");
            check(projected.get(1).getAsJsonObject().get("role").getAsString().equals("OTHER_PLAYER"),"other player cannot look like current PLAYER");
            check(projected.get(3).getAsJsonObject().get("role").getAsString().equals("OTHER_GOD"),"other god cannot look like responding NPC");
            check(restoreSourceRoles(projected).equals(JSON.toJsonTree(history)),"history original fields and order preserved exactly");
            check(!messages.getFirst().content().contains("내 뜻은 B다"),"participant text never system policy");
            check(RoomEvidenceAudit.project(messages.getLast()).content().contains("speakerAttribution"),"review keeps original identity evidence");
            check(!attribution.toString().contains("PENDING_TEXT_MUST_NOT_LEAK"),"identity labels carry no input text");
            if(secondary)check(!messages.toString().contains("PENDING_TEXT_MUST_NOT_LEAK"),"secondary only delivered words");
            var fitted=JSON.toJsonTree(RoomSpeakerAttribution.view(r,history.subList(0,1))).getAsJsonObject();
            check(fitted.getAsJsonArray("speakers").size()==1&&!fitted.toString().contains(foreign),"pruned history has no leftover identity entry");
            var repeated=new ArrayList<>(history);repeated.add(history.getFirst());
            check(JSON.toJsonTree(RoomSpeakerAttribution.view(r,repeated)).getAsJsonObject().getAsJsonArray("speakers").size()==5,"repeat speech does not duplicate actor");
        }
        System.out.println("RoomSpeakerAttributionTest: PASS ("+checks+" checks; identity contract, not semantic accuracy)");
    }
    static JsonArray restoreSourceRoles(JsonArray projected) {
        var restored=projected.deepCopy();
        for(var raw:restored) {
            var entry=raw.getAsJsonObject();
            entry.remove("utteranceOwner");
            if(entry.has("sourceRole"))entry.add("role",entry.remove("sourceRole"));
        }
        return restored;
    }
}
