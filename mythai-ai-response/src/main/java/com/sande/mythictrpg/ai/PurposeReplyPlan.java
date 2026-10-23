package com.sande.mythictrpg.ai;

import com.google.gson.*;
import com.sande.mythictrpg.understanding.*;
import java.util.*;

/** Evaluation-only surface realization. A protected clause is NOT an NPC knowledge grant. */
final class PurposeReplyPlan {
    static final boolean PRODUCTION_ENABLED = false;
    private static final Gson JSON = new Gson();
    record Anchor(String id, String text, UUID sourceId, PurposeEvidence.Goal goal) { }
    record Plan(String speakerId, UUID playerId, List<Anchor> anchors, String stateJson, String sourcesJson, List<String> continuations) {
        Plan { Objects.requireNonNull(speakerId); Objects.requireNonNull(playerId); anchors=List.copyOf(anchors); continuations=List.copyOf(continuations); }
        Map<String,Object> schema() {
            return schema(false);
        }
        Map<String,Object> schema(boolean bounded) {
            var ids=new ArrayList<String>(); ids.add("NONE"); anchors.forEach(a->ids.add(a.id()));
            var fields=new LinkedHashMap<String,Object>();
            fields.put("anchorId",Map.of("type","string","enum",ids));
            fields.put("continuation",bounded?Map.of("type","string","enum",continuations):Map.of("type","string","maxLength",1600));
            return Map.of("type","object","additionalProperties",false,"properties",fields,"required",List.of("anchorId","continuation"));
        }
        AiDialogueModels.StructuredAiResult realize(JsonObject reply) {
            return realize(reply,false);
        }
        AiDialogueModels.StructuredAiResult realize(JsonObject reply,boolean bounded) {
            if(!reply.keySet().equals(Set.of("anchorId","continuation")))throw new IllegalArgumentException("PLAN_REPLY_SHAPE");
            String id=string(reply,"anchorId"), continuation=string(reply,"continuation").strip();
            if(bounded&&!continuations.contains(continuation))throw new IllegalArgumentException("PLAN_UNISSUED_CONTINUATION");
            if(continuation.length()>1600)throw new IllegalArgumentException("PLAN_REPLY_BOUND");
            String prefix=id.equals("NONE")?"":anchors.stream().filter(a->a.id().equals(id)).findFirst()
                    .orElseThrow(()->new IllegalArgumentException("PLAN_UNKNOWN_ANCHOR")).text();
            String text=(prefix+(prefix.isEmpty()||continuation.isEmpty()?"":" ")+continuation).strip();
            if(text.isEmpty())throw new IllegalArgumentException("PLAN_EMPTY_REPLY");
            return new AiDialogueModels.StructuredAiResult(List.of(new AiDialogueModels.Speech(speakerId,text,List.of())),"",List.of());
        }
        void requirePreserved(AiDialogueModels.StructuredAiResult before,AiDialogueModels.StructuredAiResult after) {
            if(before.speech().size()!=1||after.speech().size()!=1||!after.speech().getFirst().speakerId().equals(speakerId))
                throw new IllegalArgumentException("PLAN_REPAIR_IDENTITY");
            for(var anchor:anchors)if(before.speech().getFirst().text().startsWith(anchor.text())
                    &&!after.speech().getFirst().text().startsWith(anchor.text()))throw new IllegalArgumentException("PLAN_REPAIR_CHANGED_ANCHOR");
        }
        void requireBounded(AiDialogueModels.StructuredAiResult reply) {
            if(reply.speech().size()!=1||!reply.speech().getFirst().speakerId().equals(speakerId)||!reply.proposals().isEmpty())
                throw new IllegalArgumentException("PLAN_BOUNDED_IDENTITY");
            String text=reply.speech().getFirst().text();
            if(continuations.contains(text))return;
            for(var anchor:anchors)for(String continuation:continuations)
                if(text.equals((anchor.text()+(continuation.isEmpty()?"":" ")+continuation).strip()))return;
            throw new IllegalArgumentException("PLAN_BOUNDED_REPAIR_ESCAPED");
        }
    }
    private PurposeReplyPlan() { }
    static Plan compile(String speaker, PurposeDialogueContext.Prepared prepared, List<PurposeUtterance.Target> targets) {
        var p=prepared.projection();
        if(prepared.sources().stream().anyMatch(e->!e.worldId().equals(p.worldId())||!e.playerId().equals(p.playerId())
                ||e.sourceOrder()<=p.startOrder()||!e.usable()))throw new IllegalArgumentException("PLAN_UNSCOPED_SOURCE");
        var recomputed=PurposeState.project(p.worldId(),p.playerId(),p.startOrder(),prepared.sources(),p.complete(),e->true);
        if(!recomputed.equals(p))throw new IllegalArgumentException("PLAN_SOURCE_STATE_MISMATCH");
        if(targets.size()>16||targets.stream().map(PurposeUtterance.Target::id).distinct().count()!=targets.size())
            throw new IllegalArgumentException("PLAN_TARGET_BOUND");
        var names=new HashMap<String,String>(); targets.forEach(t->names.put(t.id(),t.name()));
        var anchors=new ArrayList<Anchor>();
        if(p.complete()) for(var state:p.goals()) {
            String name=names.get(state.goal().targetId()); if(name==null)continue;
            var source=prepared.sources().stream().filter(e->e.goal().equals(state.goal())&&e.usable()
                    &&e.origin()==PurposeEvidence.Origin.SELF_SPEECH&&e.context()==PurposeEvidence.Context.DIRECTED)
                    .max(Comparator.comparingLong(PurposeEvidence::sourceOrder).thenComparing(e->e.sourceId().toString())).orElse(null);
            if(source==null)continue;
            // No inference from observed progress, repetition, emotional intensity, or game execution.
            String verb=switch(state.goal().kind()) {
                case MEET -> name+objectParticle(name)+" 만나";
                case LEARN_ABOUT -> name+"에 관해 알아보";
                case DO_ACTIVITY -> name+objectParticle(name)+" 하";
            };
            String stem=switch(source.signal()) {
                case DESIRE -> "네가 "+verb+"고 싶다고 했";
                case CONSIDER -> "네가 "+verb+"는 일을 아직 고민 중이라고 했";
                case DECIDE -> "네가 "+verb+"기로 결정했다고 했";
                case WITHDRAW -> "네가 "+verb+"는 일은 그만두겠다고 했";
                case DISOWN -> "네가 "+verb+"는 건 네 목적이 아니라고 바로잡았";
                case PAUSE -> "네가 "+verb+"는 일을 잠시 보류하겠다고 했";
                default -> null;
            };
            if(stem==null||state.lifecycle()==PurposeState.Lifecycle.ACHIEVED)continue;
            String base="a"+anchors.size();
            anchors.add(new Anchor(base+"c",stem+"지.",source.sourceId(),state.goal()));
            anchors.add(new Anchor(base+"p",stem.replaceFirst("네가 ","당신이 ").replace("네 목적","당신의 목적")+"지요.",source.sourceId(),state.goal()));
        }
        var followups=new LinkedHashSet<String>();followups.add("");
        followups.add("알겠어. 네 뜻은 그렇게 받아들일게.");followups.add("알겠습니다. 당신의 뜻은 그렇게 받아들이겠습니다.");
        if(p.complete()&&p.goals().size()==1) {
            var state=p.goals().getFirst();String name=names.get(state.goal().targetId());
            if(name!=null&&state.lifecycle()==PurposeState.Lifecycle.ACTIVE) {
                if(state.goal().kind()==PurposeEvidence.Kind.MEET) {
                    followups.add("만약 "+name+objectParticle(name)+" 만나게 된다면, 어떤 이야기를 하고 싶어?");
                    followups.add("만약 "+name+objectParticle(name)+" 만나게 된다면, 어떤 이야기를 하고 싶으신가요?");
                } else if(state.goal().kind()==PurposeEvidence.Kind.LEARN_ABOUT) {
                    followups.add(name+"에 관해 어떤 점이 궁금해?");followups.add(name+"에 관해 어떤 점이 궁금하신가요?");
                }
                if(state.inferredPursuit()) {
                    followups.add("최근의 선택들이 그 바람과 관련되어 있을 수도 있겠군.");
                    followups.add("최근의 선택들이 그 바람과 관련되어 있을 수도 있겠군요.");
                }
            }
        }
        return new Plan(speaker,p.playerId(),anchors,JSON.toJson(p),JSON.toJson(prepared.sources()),List.copyOf(followups));
    }
    static List<AiDialogueModels.OllamaMessage> messages(List<AiDialogueModels.OllamaMessage> original,Plan plan) {
        return messages(original,plan,false);
    }
    static List<AiDialogueModels.OllamaMessage> messages(List<AiDialogueModels.OllamaMessage> original,Plan plan,boolean bounded) {
        requireScene(original,plan);
        var result=new ArrayList<>(original);
        result.add(new AiDialogueModels.OllamaMessage("system", """
                [PURPOSE_PROTECTED_REALIZATION_V1]
                This evaluation uses a different response schema: anchorId and continuation ONLY.
                You remain the authorized NPC. Select at most one supplied anchor for a relevant purpose acknowledgement,
                or NONE when no anchor fits the current exchange. Code inserts its exact text before continuation.
                Do NOT copy/rephrase the anchor in continuation. Its player/target/negation/modality cannot change.
                continuation is your in-character response to the actual CURRENT_PLAYER_MESSAGE, not an analysis report.
                Match the chosen anchor's speech register throughout. Do not repeat the same acknowledgement.
                Use continuation for your own stance, a relevant open question, or your response to the player's offer.
                Do not add a personality diagnosis, intensity claim (obsession/determination), or certain causal account
                of why the player acted. A wish and selected actions do not prove obsession or lifelong resolve.
                Do not turn a wish into a commitment, an observed choice into proven motivation, or another person's
                plan into the player's. You may tentatively connect authorized observations without inventing a decision.
                If corrected by the current utterance, do not pick an outdated anchor. Missing anchors do not deny goals.
                Preserve who offers what to whom: if someone offers to listen to YOU, respond to that offer by accepting,
                declining, or explicitly counteroffering. A counteroffer need not accept or be kind; don't silently invert
                speaker and listener. Do not ask for a decision merely to acknowledge an explicit wish or withdrawal.
                No new world facts, secret knowledge, action completion, proposals, or narration.
                """));
        result.add(new AiDialogueModels.OllamaMessage("user",JSON.toJson(Map.of("availableAnchorsNotNewEvidence",plan.anchors(),
                "realizationMode",bounded?"Select the most relevant allowed continuation, or empty; no free rewrite. This is a limited-purpose experimental response, not a new world fact.":"FREE_CONTINUATION_REQUIRES_REVIEW",
                "responseSchema",plan.schema(bounded)))));
        return List.copyOf(result);
    }
    private static String string(JsonObject o,String key) {
        var v=o.get(key);if(v==null||!v.isJsonPrimitive()||!v.getAsJsonPrimitive().isString())throw new IllegalArgumentException("PLAN_REPLY_STRING");
        return v.getAsString();
    }
    static void requireScene(List<AiDialogueModels.OllamaMessage> messages,Plan plan) {
        int scenes=0;
        for(var m:messages)if(m.role().equals("user")) {
            JsonObject scene;
            try {scene=JsonParser.parseString(m.content()).getAsJsonObject();}catch(RuntimeException ignored){continue;}
            if(!scene.has("authorizedSpeakerId")||!scene.has("CURRENT_PLAYER_MESSAGE"))continue;
            scenes++;
            if(!string(scene,"authorizedSpeakerId").equals(plan.speakerId())
                    ||!string(scene.getAsJsonObject("currentPlayer"),"id").equals(plan.playerId().toString()))
                throw new IllegalArgumentException("PLAN_ROOM_IDENTITY");
            if(scene.has(PurposeDialogueContext.KEY)) {
                var purpose=scene.getAsJsonObject(PurposeDialogueContext.KEY);
                if(!JsonParser.parseString(plan.stateJson()).equals(purpose.get("interpretationNotFact"))
                        ||!JsonParser.parseString(plan.sourcesJson()).equals(purpose.get("sourceEvidence")))
                    throw new IllegalArgumentException("PLAN_ROOM_EVIDENCE_CHANGED");
            } else if(!JsonParser.parseString(plan.sourcesJson()).getAsJsonArray().isEmpty())
                throw new IllegalArgumentException("PLAN_ROOM_EVIDENCE_MISSING");
        }
        if(scenes!=1)throw new IllegalArgumentException("PLAN_PRIMARY_SCENE_REQUIRED");
    }
    private static String objectParticle(String name) {
        char last=name.charAt(name.length()-1);
        return last>='가'&&last<='힣'?(last-'가')%28==0?"를":"을":"을/를";
    }
}
