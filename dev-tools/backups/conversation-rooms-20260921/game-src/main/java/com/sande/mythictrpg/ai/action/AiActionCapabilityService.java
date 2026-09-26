package com.sande.mythictrpg.ai.action;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import com.sande.mythictrpg.quest.dynamic.GeneratedQuestTemplate;
import com.sande.mythictrpg.quest.dynamic.GeneratedQuestTemplateManager;
import com.sande.mythictrpg.quest.FtbQuestBindingManager;
import com.sande.mythictrpg.quest.structure.StructureEvaluationPolicyManager;
import com.sande.mythictrpg.relation.GodRelationTransitionManager;

/** Supplies the AI layer with exact currently authored choices, never free-form execution fields. */
public final class AiActionCapabilityService {
    private AiActionCapabilityService() {
    }

    public static List<AiActionCapability> capabilitiesFor(ResourceLocation godId) {
        List<AiActionCapability> result = new ArrayList<>();
        if (AiActionRegistry.INSTANCE.find(AiActionTypes.RELATIONSHIP_CHANGE).isPresent()) {
            result.add(new AiActionCapability(AiActionTypes.RELATIONSHIP_CHANGE, Optional.empty(),
                    "type=relationship_change, parameters={affinity_delta: integer -50..50 excluding 0}; "
                            + "use only when the current player conduct gives a concrete relationship reason"));
        }
        for (AiActionTemplate template : AiActionTemplateManager.INSTANCE.templatesFor(godId)) {
            if (AiActionRegistry.INSTANCE.find(template.actionType()).isEmpty()) {
                continue;
            }
            result.add(new AiActionCapability(template.actionType(), Optional.of(template.id()), summary(template)));
        }
        if (AiActionRegistry.INSTANCE.find(AiActionTypes.GENERATED_QUEST_OFFER).isPresent()) {
            for (GeneratedQuestTemplate template : GeneratedQuestTemplateManager.INSTANCE.templatesFor(godId)) {
                result.add(new AiActionCapability(AiActionTypes.GENERATED_QUEST_OFFER,
                        Optional.of(template.id()), generatedQuestSummary(template)));
            }
        }
        if (AiActionRegistry.INSTANCE.find(AiActionTypes.STRUCTURE_EVALUATION_REQUEST).isPresent()) {
            FtbQuestBindingManager.INSTANCE.snapshot().byQuestId().values().stream()
                    .filter(binding -> binding.structureEvaluationPolicyId().isPresent())
                    .filter(binding -> StructureEvaluationPolicyManager.INSTANCE
                            .find(binding.structureEvaluationPolicyId().orElseThrow())
                            .map(policy -> policy.godId().equals(godId)).orElse(false))
                    .forEach(binding -> result.add(new AiActionCapability(
                            AiActionTypes.STRUCTURE_EVALUATION_REQUEST, Optional.empty(),
                            "type=structure_evaluation_request, parameters={quest_id: "
                                    + binding.questId() + "}; use only when the player clearly requests submission "
                                    + "of the finished build; never provide score, evidence, pass/fail, or reward fields")));
        }
        if (AiActionRegistry.INSTANCE.find(AiActionTypes.GOD_RELATION_TRANSITION).isPresent()) {
            GodRelationTransitionManager.INSTANCE.aiTransitionsFor(godId).forEach(transition ->
                    result.add(new AiActionCapability(AiActionTypes.GOD_RELATION_TRANSITION,
                            Optional.of(transition.id()),
                            "type=god_relation_transition, parameters={transition_id: " + transition.id()
                                    + "}; server-wide directional God relationship change: "
                                    + transition.summary() + "; this always requires explicit player confirmation; "
                                    + "never invent a transition ID, score, tag, participant, or result")));
        }
        return List.copyOf(result);
    }

    private static String generatedQuestSummary(GeneratedQuestTemplate template) {
        return "type=generated_quest_offer, template_id=" + template.id()
                + ", role=side, objective=" + template.observationTypeId() + ":"
                + template.subjectId() + " x" + template.requiredCount()
                + ", world_progress=" + template.progressTrackId() + " "
                + template.minimumWorldProgress() + ".." + template.maximumWorldProgress()
                + ", reward_tier=" + template.baseRewardTier() + ".." + template.maximumRewardTier()
                + "; choose only when this bounded side quest fits the live scene; title and summary may narrate it, "
                + "but never claim it is a main quest or alter objective/reward mechanics";
    }

    private static String summary(AiActionTemplate template) {
        String prefix = "type=" + template.actionType().getPath() + ", template_id=" + template.id();
        return switch (template) {
            case ItemRequestTemplate item -> prefix + ", item=" + item.itemId() + " x" + item.count()
                    + "; emit the action only when the player's current message clearly says the requested items "
                    + "are prepared, brought, or ready for transfer; merely asking for or discussing the request is not readiness";
            case RewardProposalTemplate reward -> prefix + ", reward_table=" + reward.rewardTableId()
                    + ", tier=" + reward.tier() + "; never invent an item or count";
            case BlessingOfferTemplate blessing -> prefix + ", effect=" + blessing.effectId()
                    + ", duration_ticks=" + blessing.durationTicks() + ", amplifier=" + blessing.amplifier()
                    + "; this is an offer requiring player confirmation";
            case WorldInteractionTemplate event -> prefix + ", safe_event="
                    + event.eventKind().name().toLowerCase(java.util.Locale.ROOT) + ":" + event.eventId()
                    + "; this is a bounded presentation event requiring player confirmation";
            case PlayerDamageTemplate damage -> prefix + ", damage_mode="
                    + damage.damageMode().name().toLowerCase(java.util.Locale.ROOT)
                    + (damage.damageMode() == PlayerDamageTemplate.DamageMode.LETHAL
                            ? "" : ", amount=" + damage.amount())
                    + ", damage_type=" + damage.damageTypeId() + ", allow_death=" + damage.allowDeath()
                    + ", max_uses_per_session=" + damage.maxUsesPerSession()
                    + ", cooldown_ticks=" + damage.cooldownTicks()
                    + "; immediate server action; select only when the live scene, this NPC's established personality, "
                    + "relationship, and motive clearly justify an in-character hit; never use merely because it is available";
        };
    }
}
