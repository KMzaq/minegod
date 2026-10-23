package com.sande.mythictrpg.ai.action;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;

import java.util.Map;

/** Applies only an authored temporary vanilla Minecraft effect after confirmation. */
final class BlessingOfferAiAction {
    private BlessingOfferAiAction() {
    }

    static AiActionDefinition definition() {
        return new AiActionDefinition(AiActionTypes.BLESSING_OFFER,
                AiActionDefinition.ConfirmationPolicy.PLAYER_CONFIRMATION_REQUIRED,
                BlessingOfferAiAction::validate, BlessingOfferAiAction::execute);
    }

    private static AiActionValidation validate(AiActionContext context, AiActionProposal proposal) {
        if (!AiActionParameters.hasOnly(proposal, "template_id")) {
            return AiActionValidation.reject("Blessing offer requires only 'template_id'");
        }
        BlessingOfferTemplate template = AiActionParameters.template(proposal, BlessingOfferTemplate.class);
        if (template == null) {
            return AiActionValidation.reject("Blessing template is not registered for this God");
        }
        var effect = BuiltInRegistries.MOB_EFFECT.getHolder(template.effectId()).orElse(null);
        if (effect == null) return AiActionValidation.reject("Blessing effect is no longer registered");
        var issue = effect.value().isBeneficial() ? java.util.Optional.<String>empty()
                : com.sande.mythictrpg.ai.action.protection.AiConsequenceGate.curseRejection(
                        new MobEffectInstance(effect, template.durationTicks(), template.amplifier()));
        return issue.map(AiActionValidation::reject).orElseGet(AiActionValidation::accept);
    }

    private static AiActionExecution execute(AiActionContext context, AiActionProposal proposal) {
        BlessingOfferTemplate template = AiActionParameters.template(proposal, BlessingOfferTemplate.class);
        if (template == null) {
            return AiActionExecution.rejected("Blessing template is no longer registered");
        }
        Holder.Reference<MobEffect> effect = BuiltInRegistries.MOB_EFFECT.getHolder(template.effectId())
                .orElse(null);
        if (effect == null) {
            return AiActionExecution.rejected("Blessing effect could not be applied");
        }
        if (!effect.value().isBeneficial()) {
            var issue = com.sande.mythictrpg.ai.action.protection.AiConsequenceGate.curseRejection(
                    new MobEffectInstance(effect, template.durationTicks(), template.amplifier()));
            if (issue.isPresent()) return AiActionExecution.rejected(issue.get());
        }
        var applied = com.sande.mythictrpg.quest.reward.BlessingEffectApplication.apply(context.targetPlayer(),
                new MobEffectInstance(effect, template.durationTicks(), template.amplifier(), false, true, true));
        if (applied == com.sande.mythictrpg.quest.reward.BlessingEffectApplication.Result.REJECTED)
            return AiActionExecution.rejected("Blessing effect could not be applied");
        return AiActionExecution.executed(Map.of(
                "template_id", template.id().toString(),
                "effect_id", template.effectId().toString(),
                "duration_ticks", Integer.toString(template.durationTicks()),
                "amplifier", Integer.toString(template.amplifier()),
                "application", applied.name()));
    }
}
