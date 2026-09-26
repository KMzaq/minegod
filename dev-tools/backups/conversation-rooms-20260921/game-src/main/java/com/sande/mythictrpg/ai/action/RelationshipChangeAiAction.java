package com.sande.mythictrpg.ai.action;

import com.sande.mythictrpg.data.player.PlayerMythDataService;
import com.sande.mythictrpg.data.player.PlayerMythProfile;

import java.util.Map;

/** Applies a bounded change only to the acting God's affinity with the target player. */
final class RelationshipChangeAiAction {
    private static final int MAX_ABSOLUTE_DELTA = 50;

    private RelationshipChangeAiAction() {
    }

    static AiActionDefinition definition() {
        return new AiActionDefinition(AiActionTypes.RELATIONSHIP_CHANGE,
                AiActionDefinition.ConfirmationPolicy.IMMEDIATE,
                RelationshipChangeAiAction::validate, RelationshipChangeAiAction::execute);
    }

    private static AiActionValidation validate(AiActionContext context, AiActionProposal proposal) {
        if (!AiActionParameters.hasOnly(proposal, "affinity_delta")) {
            return AiActionValidation.reject("Relationship change requires only 'affinity_delta'");
        }
        Integer delta = delta(proposal);
        if (delta == null || delta == 0 || Math.abs(delta) > MAX_ABSOLUTE_DELTA) {
            return AiActionValidation.reject("AI affinity delta must be between -50 and 50 and not zero");
        }
        PlayerMythProfile profile = PlayerMythDataService.get(context.server())
                .find(context.targetPlayer().getUUID()).orElseGet(PlayerMythProfile::createActive);
        int current = profile.affinities().getOrDefault(proposal.actingGodId(), 0);
        int next = bounded(current + delta);
        return next != current ? AiActionValidation.accept()
                : AiActionValidation.reject("Affinity is already at its allowed boundary");
    }

    private static AiActionExecution execute(AiActionContext context, AiActionProposal proposal) {
        Integer requested = delta(proposal);
        if (requested == null) {
            return AiActionExecution.rejected("Affinity delta is no longer valid");
        }
        PlayerMythDataService service = PlayerMythDataService.get(context.server());
        int previous = service.find(context.targetPlayer().getUUID())
                .orElseGet(PlayerMythProfile::createActive).affinities()
                .getOrDefault(proposal.actingGodId(), 0);
        PlayerMythProfile changed = service.adjustAffinity(context.targetPlayer().getUUID(),
                proposal.actingGodId(), requested);
        int current = changed.affinities().getOrDefault(proposal.actingGodId(), 0);
        if (current == previous) {
            return AiActionExecution.rejected("Affinity did not change");
        }
        return AiActionExecution.executed(Map.of(
                "god_id", proposal.actingGodId().toString(),
                "previous_affinity", Integer.toString(previous),
                "current_affinity", Integer.toString(current),
                "applied_delta", Integer.toString(current - previous)));
    }

    private static Integer delta(AiActionProposal proposal) {
        try {
            return Integer.valueOf(proposal.parameters().getOrDefault("affinity_delta", ""));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static int bounded(int value) {
        return Math.max(PlayerMythProfile.MIN_AFFINITY,
                Math.min(PlayerMythProfile.MAX_AFFINITY, value));
    }
}
