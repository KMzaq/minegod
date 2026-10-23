package com.sande.mythictrpg.ai;

import com.google.gson.JsonParser;
import com.sande.mythictrpg.ai.api.RoomConversationEngine.*;
import net.minecraft.resources.ResourceLocation;
import java.util.*;

public final class RoomActionNarrationTest {
    public static void main(String[] args) {
        var god=ResourceLocation.parse("test:god");var player=UUID.randomUUID();var room=UUID.randomUUID();var turn=UUID.randomUUID();
        var before=request(room,turn,player,god,"old scene",List.of());
        var outcome=new ActionOutcome(UUID.randomUUID(),"mythictrpg:npc_directive",ActionStatus.EXECUTED,"Order accepted",
                Map.of("status","STARTED","work_completed","false"));
        var after=request(room,turn,player,god,"new scene",List.of(outcome));
        check(RoomActionNarration.sameTurn(before,after),"same turn result rejected");
        check(!RoomActionNarration.sameTurn(before,request(UUID.randomUUID(),turn,player,god,"new",List.of(outcome))),"foreign room accepted");
        check(!RoomActionNarration.sameTurn(before,request(room,UUID.randomUUID(),player,god,"new",List.of(outcome))),"late turn accepted");
        check(!RoomActionNarration.sameTurn(before,request(room,turn,UUID.randomUUID(),god,"new",List.of(outcome))),"foreign player accepted");
        var input=List.of(new AiDialogueModels.OllamaMessage("system","persona and authority"),
                new AiDialogueModels.OllamaMessage("user","{\"CURRENT_PLAYER_MESSAGE\":\"help\",\"ownPersona\":{},\"gameContextWithSourceBoundaries\":\"old scene\",\"gameConfirmedActionOutcomes\":[],\"gameplayProposalsAllowed\":true,\"npcActivityProposalsAllowed\":true}"));
        var output=RoomActionNarration.messages(input,after);var scene=JsonParser.parseString(output.get(1).content()).getAsJsonObject();
        check(scene.get("gameContextWithSourceBoundaries").getAsString().equals("new scene"),"stale scene retained");
        check(!scene.get("gameplayProposalsAllowed").getAsBoolean()&&!scene.get("npcActivityProposalsAllowed").getAsBoolean(),"actions enabled on finalization");
        check(scene.getAsJsonArray("gameConfirmedActionOutcomes").size()==1,"execution evidence missing");
        check(output.getLast().content().contains("not a\ncompleted kill")&&output.getLast().content().contains("proposals=[]"),"start/completion or no-replay policy absent");
        check(input.get(1).content().contains("old scene"),"original input mutated");
        var staleScene=JsonParser.parseString(input.get(1).content()).getAsJsonObject();
        staleScene.add("selectedAudiencePermittedLore",JsonParser.parseString("[{\"id\":\"private_secret\"}]"));
        var revoked=new AiTestContentRegistryBridge.ContentSnapshot(null,List.of(),List.of(),List.of("new relationship guidance"),List.of(),1);
        var reprojected=RoomActionNarration.messages(List.of(new AiDialogueModels.OllamaMessage("user",staleScene.toString())),after,revoked);
        var refreshedScene=JsonParser.parseString(reprojected.getFirst().content()).getAsJsonObject();
        check(refreshedScene.getAsJsonArray("selectedAudiencePermittedLore").isEmpty(),"revoked lore survived action finalization");
        check(refreshedScene.getAsJsonObject("ownRelationship").getAsJsonArray("authoredGuidance").get(0).getAsString().equals("new relationship guidance"),"old relationship guidance retained");
        try {RoomActionNarration.messages(List.of(new AiDialogueModels.OllamaMessage("user","no canonical scene")),after);throw new AssertionError("missing canonical scene allowed");}
        catch(IllegalArgumentException expected) { }
        System.out.println("RoomActionNarrationTest PASS: scope, source replacement, disclosure revocation and no action replay (12 checks)");
    }
    static Request request(UUID room,UUID turn,UUID player,ResourceLocation god,String context,List<ActionOutcome> outcomes) {
        return new Request(room,1,turn,player,"test",List.of(god),god,"help",List.of(),false,false,false,
                List.of(new GodState(god,"R_NEUTRAL","UNASSESSED",context,null)),false,Set.of(player),outcomes);
    }
    static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
