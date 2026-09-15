package com.sande.mythictrpg.interaction.start;

import com.sande.mythictrpg.data.god.GodAccessService;
import com.sande.mythictrpg.data.god.GodAppearanceService;
import com.sande.mythictrpg.interaction.api.ExplicitGodCallPayload;
import com.sande.mythictrpg.interaction.api.InteractionMode;
import com.sande.mythictrpg.interaction.api.InteractionSignalTypes;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashSet;
import java.util.Set;

public final class InteractionStartValidator {
    public static final InteractionStartValidator INSTANCE = new InteractionStartValidator();

    private InteractionStartValidator() {
    }

    public StartValidationResult validate(InteractionStartRequest request,
            InteractionStartSnapshot snapshot) {
        var signal = request.signal();
        var plan = request.plan();
        var context = snapshot.context();
        if (!plan.initiatingPlayerId().equals(signal.initiatingPlayerId())
                || plan.mode() != signal.mode()
                || !plan.signalType().equals(signal.type().id())) {
            return rejected(StartValidationResult.Status.LEGALITY_FAILED,
                    InteractionStartReasons.SIGNAL_PLAN_MISMATCH);
        }
        if (!context.ready()) {
            return rejected(StartValidationResult.Status.LEGALITY_FAILED,
                    context.unavailableReason().orElse(InteractionStartReasons.CONTEXT_UNAVAILABLE));
        }
        if (plan.revisions().godDefinitionGeneration() != context.godSnapshot().generation()) {
            return rejected(StartValidationResult.Status.STALE_PLAN,
                    InteractionStartReasons.STALE_GOD_DEFINITIONS);
        }
        if (plan.revisions().interactionRuleGeneration().isPresent()
                && plan.revisions().interactionRuleGeneration().orElseThrow()
                != context.ruleSnapshot().generation()) {
            return rejected(StartValidationResult.Status.STALE_PLAN,
                    InteractionStartReasons.STALE_INTERACTION_RULES);
        }
        if (!snapshot.onlinePlayerIds().containsAll(plan.audience().recipientPlayerIds())) {
            return rejected(StartValidationResult.Status.AUDIENCE_UNAVAILABLE,
                    InteractionStartReasons.AUDIENCE_OFFLINE);
        }
        if (!snapshot.activePlayerIds().containsAll(plan.audience().recipientPlayerIds())) {
            return rejected(StartValidationResult.Status.AUDIENCE_UNAVAILABLE,
                    InteractionStartReasons.AUDIENCE_INACTIVE);
        }
        var runtimeBlock = context.runtime().planningBlockReason(
                plan.initiatingPlayerId(), plan.mode());
        if (runtimeBlock.isPresent()) {
            return rejected(StartValidationResult.Status.RUNTIME_BUSY,
                    runtimeBlock.orElseThrow());
        }

        Set<ResourceLocation> participants = new LinkedHashSet<>();
        participants.add(plan.participants().primaryGodId());
        participants.addAll(plan.participants().secondaryGodIds());
        for (ResourceLocation godId : participants) {
            if (!context.godSnapshot().definitions().containsKey(godId)) {
                return rejected(StartValidationResult.Status.LEGALITY_FAILED,
                        InteractionStartReasons.UNKNOWN_PARTICIPANT);
            }
            boolean unlocked = GodAccessService.effectiveUnlockStatus(godId,
                    context.conditionContext().world(), context.conditionContext().gods()).orElse(false);
            if (!unlocked) {
                return rejected(StartValidationResult.Status.LEGALITY_FAILED,
                        InteractionStartReasons.GOD_LOCKED);
            }
            if (context.cooldowns().blockReason(plan.initiatingPlayerId(), godId, plan.mode()).isPresent()) {
                return rejected(StartValidationResult.Status.LEGALITY_FAILED,
                        InteractionStartReasons.COOLDOWN_BLOCKED);
            }
        }

        if (plan.mode() == InteractionMode.EXPLICIT) {
            if (!(signal.payload() instanceof ExplicitGodCallPayload payload)
                    || !payload.policyId().equals(InteractionSignalTypes.PLAYER_EXPLICIT_POLICY)
                    || !payload.targetGodId().equals(plan.participants().primaryGodId())) {
                return rejected(StartValidationResult.Status.LEGALITY_FAILED,
                        InteractionStartReasons.EXPLICIT_POLICY_REJECTED);
            }
        } else {
            for (ResourceLocation godId : participants) {
                if (!GodAppearanceService.INSTANCE.evaluateAutomaticAppearance(
                        context.godSnapshot(), context.conditionContext(), true, godId).eligible()) {
                    return rejected(StartValidationResult.Status.LEGALITY_FAILED,
                            InteractionStartReasons.APPEARANCE_REJECTED);
                }
            }
        }
        return StartValidationResult.accepted();
    }

    private static StartValidationResult rejected(StartValidationResult.Status status,
            ResourceLocation reason) {
        return StartValidationResult.rejected(status, reason);
    }
}
