package com.sande.mythictrpg.ai.action;

import com.sande.mythictrpg.quest.reward.NpcRewardGrantService;
import com.sande.mythictrpg.quest.reward.RewardGrantPurpose;

import java.util.Map;

/** Grants only a fixed tier from an authored reward table owned by the acting God. */
final class RewardProposalAiAction {
    private RewardProposalAiAction() {
    }

    static AiActionDefinition definition() {
        return new AiActionDefinition(AiActionTypes.REWARD_PROPOSAL,
                AiActionDefinition.ConfirmationPolicy.IMMEDIATE,
                RewardProposalAiAction::validate, RewardProposalAiAction::execute);
    }

    private static AiActionValidation validate(AiActionContext context, AiActionProposal proposal) {
        if (!AiActionParameters.hasOnly(proposal, "template_id")) {
            return AiActionValidation.reject("Reward proposal requires only 'template_id'");
        }
        RewardProposalTemplate template = AiActionParameters.template(proposal, RewardProposalTemplate.class);
        if (template == null) {
            return AiActionValidation.reject("Reward template is not registered for this God");
        }
        NpcRewardGrantService.Validation validation = NpcRewardGrantService.validate(
                proposal.actingGodId(), template.rewardTableId(), template.tier(),
                RewardGrantPurpose.AI_ACTION);
        return validation.allowed() ? AiActionValidation.accept()
                : AiActionValidation.reject(validation.reason());
    }

    private static AiActionExecution execute(AiActionContext context, AiActionProposal proposal) {
        RewardProposalTemplate template = AiActionParameters.template(proposal, RewardProposalTemplate.class);
        if (template == null) {
            return AiActionExecution.rejected("Reward template is no longer registered");
        }
        NpcRewardGrantService.Result result = NpcRewardGrantService.grant(context.targetPlayer(),
                proposal.actingGodId(), template.rewardTableId(), template.tier(),
                RewardGrantPurpose.AI_ACTION);
        if (!result.granted()) {
            return AiActionExecution.rejected(result.reason());
        }
        return AiActionExecution.executed(Map.of(
                "template_id", template.id().toString(),
                "reward_table_id", template.rewardTableId().toString(),
                "tier", Integer.toString(template.tier()),
                "rewards", String.join(", ", result.rewards())));
    }
}
