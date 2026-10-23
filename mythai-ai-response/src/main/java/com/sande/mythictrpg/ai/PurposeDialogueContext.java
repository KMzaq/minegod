package com.sande.mythictrpg.ai;

import com.google.gson.*;
import com.sande.mythictrpg.understanding.*;
import java.util.*;
import java.util.function.Predicate;

/** Same source-bound projection is present in generation, review and repair. Not an NPC knowledge grant. */
final class PurposeDialogueContext {
    static final String KEY="sourceBoundPurposeState";
    private static final Gson JSON=new Gson();
    static final String RULES="""
            [SOURCE_BOUND_PURPOSE]
            sourceBoundPurposeState separates statedIntention, observedProgress, evidenceStrength and lifecycle.
            It is a fallible purpose interpretation, not an execution permission or omniscient player profile.
            Repeated DESIRED remains a wish, never an already made decision. Actions can suggest pursuit without
            proving the player said/decided anything. DECIDED acknowledges a stated decision, not an executed action.
            Neither repetition nor the word goal proves emotional intensity, a firm resolve, lifelong devotion,
            or an earlier plan. A later withdrawal does not retrospectively upgrade a wish into a former decision.
            LEARN_ABOUT and MEET are different purposes. Keep the owner of curiosity distinct from its subject.
            A tentative, evidence-linked observation is allowed; do not refuse ordinary conversation merely
            because an inner motive is uncertain. Address the actual question before adding interpretation.
            Original evidence and current explicit correction outrank an interpretation. Preserve actor/target,
            negation and conditions. Withdrawn/disowned goals are not active. WITHDRAWN supports acknowledging
            a choice to stop, not a choice to pursue. inferredPursuit is a hypothesis about actions, not proof
            of a clearly settled positive goal. A missing candidate is NOT proof
            of no intention. Incomplete coverage is not proof that no later correction exists.
            Use this only when relevant to the current exchange, naturally in your own character. Do not recite
            internal fields, score the player, repeat an analysis unprompted, or require another confirmation
            just to acknowledge an explicit statement. No quest/reward/relationship/world-state change follows.
            """;
    static final String AUDIT_RULES="""
            [PURPOSE_EVIDENCE_AUDIT]
            sourceBoundPurposeState contains fallible interpretations AND their original sourceEvidence.
            Review the actual assertion in each draft unit; do NOT roleplay, speak in character, or write a reply.
            Separate the NPC's own feeling/offer from an assertion about the PLAYER's motive, choice or agency.
            Saying that the PLAYER acted by free choice or firmly decided is an attributed factual assertion,
            even when surrounded by polite acknowledgement. It is not exempt as the NPC's social expression.
            DESIRED/CONSIDERING is not DECIDED. Repetition and observed preparation do not create a decision
            or proof that the player declared one. A withdrawal also does not create a former definite plan.
            DECIDED refers to commitment TO PURSUE that goal, not all uses of the word decision.
            WITHDRAWN positively supports the player's stated choice NOT TO PURSUE; saying they decided
            to stop is allowed. DISOWNED supports a correction of ownership. UNKNOWN statedIntention on
            an inactive goal does not erase that explicit negative choice. Compare action AND polarity.
            Conversely, an ACTIVE DESIRED goal plus converging actions supports inferred pursuit only.
            'The player clearly set the goal / resolved to meet' asserts a positive settled commitment even
            without the literal word DECIDED. It is not supported merely by inferredPursuit=true. Reject
            that overstatement, but allow a tentative observation linking the wish with the actual choices.
            A GAME_ACTION with context FORCED describes a constrained action, not voluntary goal pursuit.
            AMBIGUOUS/MISSING/SYSTEM_SUGGESTED does not establish its motivation. Check the exact action:
            a separate voluntary reward choice cannot make a forced journey voluntary. Contradicting this
            is CONTEXT_CONTRADICTION. Inference may be tentative; a categorical causal claim needs support.
            Original attributed statements and current corrections outrank the derived state. DISOWNED and
            WITHDRAWN are inactive; later conflicting observations are not an implicit resumption.
            Distinguish seeking information FOR another person from reporting one's own goal, and asking
            for news from delivering news. Do not reverse questioner, beneficiary, speaker or target.
            Do not demand a decision to acknowledge a wish, deny an explicit wish because it is uncertain,
            or reject an NPC's new offer merely because it was not a past promise. Ordinary dialogue remains allowed.
            These rules constrain interpretation only. The current response schema and unit findings still apply.
            """;
    private PurposeDialogueContext(){ }
    record Prepared(PurposeState.Projection projection,List<PurposeEvidence> sources) {
        Prepared { sources=List.copyOf(sources); }
    }
    static Prepared prepare(UUID world,UUID player,long start,List<PurposeEvidence> sources,boolean complete,
            Predicate<PurposeEvidence> permitted) {
        // Never compute a hidden global profile and redact its provenance afterwards.
        var allowed=sources.stream().filter(e->world.equals(e.worldId())&&player.equals(e.playerId())
                &&e.sourceOrder()>start&&e.usable()&&permitted.test(e)).toList();
        return new Prepared(PurposeState.project(world,player,start,allowed,complete,e->true),allowed);
    }
    static List<AiDialogueModels.OllamaMessage> attach(List<AiDialogueModels.OllamaMessage> messages,Prepared prepared) {
        if(prepared.sources().isEmpty())return messages;
        // Whole context or nothing; do not silently drop a late withdrawal to meet a budget.
        String encoded=JSON.toJson(Map.of("rulesVersion",PurposeState.RULES_VERSION,"interpretationNotFact",prepared.projection(),
                "sourceEvidence",prepared.sources(),"scope","ONLY_CURRENT_NPC_AND_AUDIENCE_PERMITTED_SOURCES"));
        if(encoded.length()>16000)throw new IllegalArgumentException("PURPOSE_CONTEXT_BUDGET");
        var result=new ArrayList<AiDialogueModels.OllamaMessage>();boolean attached=false;
        for(var m:messages) {
            if(m.role().equals("system"))result.add(new AiDialogueModels.OllamaMessage(m.role(),m.content()+"\n"+RULES));
            else if(!attached && m.role().equals("user")) {
                JsonObject scene;
                try {scene=JsonParser.parseString(m.content()).getAsJsonObject();}
                catch(RuntimeException invalid){result.add(m);continue;}
                if(scene.has("CURRENT_PLAYER_MESSAGE")||scene.has("alreadyDeliveredConversation")) {
                    scene.add(KEY,JsonParser.parseString(encoded));attached=true;
                    result.add(new AiDialogueModels.OllamaMessage(m.role(),scene.toString()));
                } else result.add(m);
            } else result.add(m);
        }
        if(!attached)throw new IllegalArgumentException("PURPOSE_MISSING_SCOPED_SCENE");
        return List.copyOf(result);
    }
}
