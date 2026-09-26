package com.sande.mythictrpg.ai.action;

import com.sande.mythictrpg.quest.dynamic.GeneratedQuestCreationResult;
import com.sande.mythictrpg.quest.dynamic.GeneratedQuestService;
import com.sande.mythictrpg.quest.dynamic.GeneratedQuestTemplate;
import com.sande.mythictrpg.quest.dynamic.GeneratedQuestTemplateManager;
import com.sande.mythictrpg.quest.dynamic.GeneratedQuestValidation;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;

/** Creates one server-authored SIDE quest; AI controls only template choice and narration. */
final class GeneratedQuestOfferAiAction {
    private GeneratedQuestOfferAiAction() {
    }

    static AiActionDefinition definition() {
        return new AiActionDefinition(AiActionTypes.GENERATED_QUEST_OFFER,
                AiActionDefinition.ConfirmationPolicy.IMMEDIATE,
                GeneratedQuestOfferAiAction::validate, GeneratedQuestOfferAiAction::execute);
    }

    private static AiActionValidation validate(AiActionContext context, AiActionProposal proposal) {
        GeneratedQuestTemplate template = template(proposal);
        if (template == null) {
            return AiActionValidation.reject("Proposal template_id is not an authorized generated quest");
        }
        GeneratedQuestValidation validation = GeneratedQuestService.INSTANCE.validate(
                context.targetPlayer(), template);
        return validation.allowed() ? AiActionValidation.accept()
                : AiActionValidation.reject(validation.reason());
    }

    private static AiActionExecution execute(AiActionContext context, AiActionProposal proposal) {
        GeneratedQuestTemplate template = template(proposal);
        if (template == null) {
            return AiActionExecution.rejected("Proposal template_id is not an authorized generated quest");
        }
        GeneratedQuestCreationResult result = GeneratedQuestService.INSTANCE.create(
                context.targetPlayer(), template, proposal.title(), proposal.summary());
        if (!result.created()) {
            return AiActionExecution.rejected(result.reason());
        }
        var instance = result.instance().orElseThrow();
        return AiActionExecution.executed(Map.of(
                "instance_id", instance.instanceId().toString(),
                "template_id", instance.templateId().toString(),
                "quest_role", "side",
                "required_count", Integer.toString(instance.requiredCount()),
                "reward_tier", Integer.toString(instance.rewardTier()),
                "catch_up_applied", Boolean.toString(instance.catchUpApplied())));
    }

    private static GeneratedQuestTemplate template(AiActionProposal proposal) {
        ResourceLocation id = ResourceLocation.tryParse(proposal.parameters().getOrDefault("template_id", ""));
        return id == null ? null : GeneratedQuestTemplateManager.INSTANCE
                .find(id, proposal.actingGodId()).orElse(null);
    }
}
