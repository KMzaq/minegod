package com.sande.mythictrpg.ai.action;

import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

import java.util.Map;

/** Executes only safe, position-bounded presentation events authored in a Datapack template. */
final class WorldInteractionAiAction {
    private WorldInteractionAiAction() {
    }

    static AiActionDefinition definition() {
        return new AiActionDefinition(AiActionTypes.WORLD_INTERACTION,
                AiActionDefinition.ConfirmationPolicy.PLAYER_CONFIRMATION_REQUIRED,
                WorldInteractionAiAction::validate, WorldInteractionAiAction::execute);
    }

    private static AiActionValidation validate(AiActionContext context, AiActionProposal proposal) {
        if (!AiActionParameters.hasOnly(proposal, "template_id")) {
            return AiActionValidation.reject("World interaction requires only 'template_id'");
        }
        WorldInteractionTemplate template = AiActionParameters.template(proposal, WorldInteractionTemplate.class);
        return template == null ? AiActionValidation.reject(
                "Safe world interaction template is not registered for this God") : AiActionValidation.accept();
    }

    private static AiActionExecution execute(AiActionContext context, AiActionProposal proposal) {
        WorldInteractionTemplate template = AiActionParameters.template(proposal, WorldInteractionTemplate.class);
        if (template == null) {
            return AiActionExecution.rejected("World interaction template is no longer registered");
        }
        if (template.eventKind() == WorldInteractionTemplate.EventKind.SOUND) {
            SoundEvent sound = BuiltInRegistries.SOUND_EVENT.get(template.eventId());
            context.targetPlayer().serverLevel().playSound(null, context.targetPlayer().getX(),
                    context.targetPlayer().getY(), context.targetPlayer().getZ(), sound,
                    SoundSource.MASTER, template.volume(), template.pitch());
        } else {
            Object raw = BuiltInRegistries.PARTICLE_TYPE.get(template.eventId());
            if (!(raw instanceof SimpleParticleType particle)) {
                return AiActionExecution.rejected("Safe particle type is no longer registered");
            }
            context.targetPlayer().serverLevel().sendParticles(particle,
                    context.targetPlayer().getX(), context.targetPlayer().getY() + 1.0D,
                    context.targetPlayer().getZ(), template.count(), template.spread(),
                    template.spread(), template.spread(), template.speed());
        }
        return AiActionExecution.executed(Map.of(
                "template_id", template.id().toString(),
                "event_type", template.eventKind().name().toLowerCase(java.util.Locale.ROOT),
                "event_id", template.eventId().toString()));
    }
}
