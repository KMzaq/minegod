package com.sande.mythictrpg.ai.action;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;

import java.util.Map;

/** Applies only an authored damage profile selected for the acting God. */
final class PlayerDamageAiAction {
    private PlayerDamageAiAction() {
    }

    static AiActionDefinition definition() {
        return new AiActionDefinition(AiActionTypes.PLAYER_DAMAGE,
                AiActionDefinition.ConfirmationPolicy.IMMEDIATE,
                PlayerDamageAiAction::validate, PlayerDamageAiAction::execute);
    }

    private static AiActionValidation validate(AiActionContext context, AiActionProposal proposal) {
        if (!AiActionParameters.hasOnly(proposal, "template_id")) {
            return AiActionValidation.reject("Player damage requires only 'template_id'");
        }
        PlayerDamageTemplate template = AiActionParameters.template(proposal, PlayerDamageTemplate.class);
        if (template == null) {
            return AiActionValidation.reject("Damage template is not registered for this God");
        }
        if (!context.targetPlayer().isAlive()) {
            return AiActionValidation.reject("Target player is not alive");
        }
        if (!template.allowDeath() && context.targetPlayer().getHealth() <= 1.0F) {
            return AiActionValidation.reject("Non-lethal damage cannot reduce this player's health further");
        }
        return damageType(context, template) == null
                ? AiActionValidation.reject("Damage type is not registered on this server")
                : AiActionValidation.accept();
    }

    private static AiActionExecution execute(AiActionContext context, AiActionProposal proposal) {
        PlayerDamageTemplate template = AiActionParameters.template(proposal, PlayerDamageTemplate.class);
        if (template == null || !context.targetPlayer().isAlive()) {
            return AiActionExecution.rejected("Damage template or target is no longer available");
        }
        Holder.Reference<DamageType> type = damageType(context, template);
        if (type == null) {
            return AiActionExecution.rejected("Damage type is no longer registered");
        }

        float healthBefore = context.targetPlayer().getHealth();
        float requested = requestedDamage(template, healthBefore, context.targetPlayer().getMaxHealth());
        if (!template.allowDeath()) {
            requested = Math.min(requested, healthBefore - 1.0F);
        }
        if (!(requested > 0.0F)) {
            return AiActionExecution.rejected("Damage would have no valid effect");
        }

        boolean applied = context.targetPlayer().hurt(new DamageSource(type), requested);
        if (!applied) {
            return AiActionExecution.rejected("Player rejected or was immune to this damage source");
        }
        float healthAfter = context.targetPlayer().getHealth();
        return AiActionExecution.executed(Map.of(
                "template_id", template.id().toString(),
                "damage_mode", template.damageMode().name().toLowerCase(java.util.Locale.ROOT),
                "damage_type", template.damageTypeId().toString(),
                "requested_damage", Float.toString(requested),
                "health_before", Float.toString(healthBefore),
                "health_after", Float.toString(healthAfter),
                "allow_death", Boolean.toString(template.allowDeath())));
    }

    private static Holder.Reference<DamageType> damageType(AiActionContext context,
            PlayerDamageTemplate template) {
        ResourceKey<DamageType> key = ResourceKey.create(Registries.DAMAGE_TYPE, template.damageTypeId());
        return context.server().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE)
                .getHolder(key).orElse(null);
    }

    private static float requestedDamage(PlayerDamageTemplate template, float currentHealth, float maxHealth) {
        return switch (template.damageMode()) {
            case FLAT -> (float) template.amount();
            case MAX_HEALTH_FRACTION -> (float) (maxHealth * template.amount());
            case CURRENT_HEALTH_FRACTION -> (float) (currentHealth * template.amount());
            case LETHAL -> Float.MAX_VALUE;
        };
    }
}
