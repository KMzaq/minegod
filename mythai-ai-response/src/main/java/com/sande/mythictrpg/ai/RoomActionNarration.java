package com.sande.mythictrpg.ai;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.sande.mythictrpg.ai.api.RoomConversationEngine;
import java.util.ArrayList;
import java.util.List;

/** Replaces pre-action facts instead of appending contradictory versions of the same snapshot. */
final class RoomActionNarration {
    private static final Gson JSON = new Gson();
    static boolean sameTurn(RoomConversationEngine.Request before, RoomConversationEngine.Request after) {
        return before.roomId().equals(after.roomId()) && before.revision()==after.revision()
                && before.turnId().equals(after.turnId()) && before.playerId().equals(after.playerId())
                && before.speakerGodId().equals(after.speakerGodId()) && before.godIds().equals(after.godIds())
                && before.audiencePlayerIds().equals(after.audiencePlayerIds()) && before.publicRoom()==after.publicRoom()
                && before.recording()==after.recording() && !before.readOnly() && !after.readOnly()
                && !before.secondary() && !after.secondary() && before.currentText().equals(after.currentText());
    }
    static List<AiDialogueModels.OllamaMessage> messages(List<AiDialogueModels.OllamaMessage> previous,
            RoomConversationEngine.Request request) {
        return messages(previous,request,null);
    }
    static List<AiDialogueModels.OllamaMessage> messages(List<AiDialogueModels.OllamaMessage> previous,
            RoomConversationEngine.Request request, AiTestContentRegistryBridge.ContentSnapshot content) {
        var messages=new ArrayList<AiDialogueModels.OllamaMessage>();
        boolean replaced=false;
        for(var message:previous) {
            if(!message.role().equals("user")){messages.add(message);continue;}
            try {
                var scene=JsonParser.parseString(message.content()).getAsJsonObject();
                if(scene.has("CURRENT_PLAYER_MESSAGE")&&scene.has("ownPersona")) {
                    scene.addProperty("gameContextWithSourceBoundaries",request.speakerState().gameContext());
                    scene.add("gameConfirmedActionOutcomes",JSON.toJsonTree(request.actionOutcomes()));
                    scene.addProperty("gameplayProposalsAllowed",false);
                    scene.addProperty("npcActivityProposalsAllowed",false);
                    scene.add("questRosterMenuQuestIds",JSON.toJsonTree(List.of()));
                    if(scene.has("ownRelationship"))scene.getAsJsonObject("ownRelationship")
                            .addProperty("tier",request.speakerState().relationshipTier());
                    if(content!=null) {
                        // Relationship/action changes can revoke lore or change authored guidance in the same turn.
                        scene.add("ownPersona",JSON.toJsonTree(content.profile()));
                        scene.add("ownRelationship",JSON.toJsonTree(java.util.Map.of(
                                "tier",request.speakerState().relationshipTier(),"authoredGuidance",content.relationshipGuidance(),
                                "directionalSocialTags",content.socialRelationTags())));
                        var permitted=JSON.toJsonTree(content.lore()).getAsJsonArray();
                        var retained=new com.google.gson.JsonArray();
                        if(scene.has("selectedAudiencePermittedLore"))for(var old:scene.getAsJsonArray("selectedAudiencePermittedLore"))
                            for(var current:permitted)if(current.equals(old)){retained.add(current);break;}
                        scene.add("selectedAudiencePermittedLore",retained);
                    }
                    messages.add(new AiDialogueModels.OllamaMessage("user",JSON.toJson(scene)));replaced=true;
                } else messages.add(message);
            } catch(RuntimeException plainText){messages.add(message);}
        }
        if(!replaced)throw new IllegalArgumentException("No canonical room scene for action narration");
        messages.add(new AiDialogueModels.OllamaMessage("system", """
                The game has now processed this turn's chosen actions. This is the final spoken reply, not a new decision.
                Use the refreshed gameConfirmedActionOutcomes and current game context. Return proposals=[]; no controls,
                new actions, invented results or retries. Keep this God's own motives, personality and emotional continuity.
                React naturally to what actually happened. EXECUTED can mean an order STARTED or a menu opened, not a
                completed kill, arrival or reward. PENDING_CONFIRMATION is still waiting; REJECTED/FAILED did not succeed.
                Do not re-ask an already clear request or repeat a speech about choice, responsibility or readiness.
                Usually a brief reaction is enough; add explanation only when the actual result calls for it. Never quote
                internal status codes. Use the same dialogue JSON schema and only the authorized speaker.
                """));
        return List.copyOf(messages);
    }
    private RoomActionNarration() { }
}
