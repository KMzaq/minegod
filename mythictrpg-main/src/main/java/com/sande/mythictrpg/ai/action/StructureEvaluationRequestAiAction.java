package com.sande.mythictrpg.ai.action;

import com.sande.mythictrpg.quest.structure.StructureEvaluationService;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;

/** AI may request evaluation; it cannot supply a score, evidence, or reward. */
final class StructureEvaluationRequestAiAction {
    private StructureEvaluationRequestAiAction() {}
    static AiActionDefinition definition(){return new AiActionDefinition(AiActionTypes.STRUCTURE_EVALUATION_REQUEST,
            AiActionDefinition.ConfirmationPolicy.IMMEDIATE,StructureEvaluationRequestAiAction::validate,StructureEvaluationRequestAiAction::execute);}
    private static AiActionValidation validate(AiActionContext context,AiActionProposal proposal){
        if(!AiActionParameters.hasOnly(proposal,"quest_id"))return AiActionValidation.reject("Structure evaluation requires only 'quest_id'");
        ResourceLocation quest=id(proposal);if(quest==null)return AiActionValidation.reject("quest_id must be a namespaced ID");
        var result=StructureEvaluationService.INSTANCE.validateRequest(context.targetPlayer(),quest,proposal.actingGodId());
        return result.allowed()?AiActionValidation.accept():AiActionValidation.reject(result.reason());}
    private static AiActionExecution execute(AiActionContext context,AiActionProposal proposal){ResourceLocation quest=id(proposal);if(quest==null)return AiActionExecution.rejected("Invalid quest_id");var result=StructureEvaluationService.INSTANCE.requestEvaluation(context.targetPlayer(),quest,proposal.actingGodId());if(!result.succeeded())return AiActionExecution.rejected(result.reason());return AiActionExecution.executed(Map.of("quest_id",quest.toString(),"score",Integer.toString(result.report().score()),"build_score",Long.toString(Math.round(result.report().buildScore())),"environment_score",Long.toString(Math.round(result.report().environmentScore())),"evidence",result.report().evidenceSummary(),"quest_status",result.questResult().status().name()));}
    private static ResourceLocation id(AiActionProposal p){String raw=p.parameters().getOrDefault("quest_id","");ResourceLocation id=ResourceLocation.tryParse(raw);return id!=null&&raw.contains(":")?id:null;}
}
