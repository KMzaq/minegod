package com.sande.mythictrpg.ai.action;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Configure-once allow-list owned by MythicTRPG, never by model output. */
public final class AiActionRegistry {
    public static final AiActionRegistry INSTANCE = new AiActionRegistry();

    private final Map<net.minecraft.resources.ResourceLocation, AiActionDefinition> definitions =
            new LinkedHashMap<>();

    private AiActionRegistry() {
        register(QuestOfferAiAction.definition());
        register(QuestRosterAiAction.definition());
        register(ItemRequestAiAction.definition());
        register(RewardProposalAiAction.definition());
        register(RelationshipChangeAiAction.definition());
        register(BlessingOfferAiAction.definition());
        register(WorldInteractionAiAction.definition());
        register(PlayerDamageAiAction.definition());
        register(GeneratedQuestOfferAiAction.definition());
        register(RaidOfferAiAction.definition());
        register(NpcVisitAiAction.definition());
        register(NpcActivityAiAction.definition());
        register(StructureEvaluationRequestAiAction.definition());
        register(GodRelationTransitionAiAction.definition());
        register(StoryEventHookAiAction.definition());
    }

    public synchronized void register(AiActionDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        AiActionDefinition previous = definitions.putIfAbsent(definition.actionType(), definition);
        if (previous != null && previous != definition) {
            throw new IllegalStateException("AI action is already registered: " + definition.actionType());
        }
    }

    public synchronized Optional<AiActionDefinition> find(net.minecraft.resources.ResourceLocation actionType) {
        return Optional.ofNullable(definitions.get(actionType));
    }

    public synchronized Set<net.minecraft.resources.ResourceLocation> actionTypes() {
        return Set.copyOf(definitions.keySet());
    }
}
