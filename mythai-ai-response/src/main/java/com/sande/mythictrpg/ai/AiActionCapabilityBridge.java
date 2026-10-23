package com.sande.mythictrpg.ai;

import com.sande.mythictrpg.ai.action.AiActionCapability;
import com.sande.mythictrpg.ai.action.AiActionCapabilityService;
import com.sande.mythictrpg.relation.GodRelationTransition;
import com.sande.mythictrpg.relation.GodRelationTransitionManager;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/** Prompt and normalization boundary for game-owned AI action capabilities. */
final class AiActionCapabilityBridge {
    private AiActionCapabilityBridge() {
    }

    static void appendPrompt(StringBuilder context, ResourceLocation godId) {
        appendCapabilities(context, AiActionCapabilityService.capabilitiesFor(godId));
    }

    /** Room callers must supply the exact game-issued participants, not model-selected targets. */
    static void appendPrompt(StringBuilder context, ResourceLocation godId, List<ResourceLocation> participants) {
        appendCapabilities(context, roomCapabilities(AiActionCapabilityService.capabilitiesFor(godId), godId,
                participants, GodRelationTransitionManager.INSTANCE::find));
    }

    static void appendCapabilities(StringBuilder context, List<AiActionCapability> capabilities) {
        if (capabilities.isEmpty()) {
            return;
        }
        context.append("\n[SERVER_AUTHORIZED_ACTION_CAPABILITIES]\n");
        for (AiActionCapability capability : capabilities) {
            context.append("- ").append(capability.promptSummary()).append("\n");
        }
        context.append("Proposals are not completed facts. Use exact listed types, keys, IDs and mechanics; invent no targets or values. ")
                .append("generated_quest_offer is SIDE only: title/summary may vary, never its objective/count/gate/reward. ")
                .append("player_damage needs a scene/persona/relationship motive and a matching damage_mode/allow_death template, not mere availability. ")
                .append("item_request needs a concrete conversational reason AND the current player explicitly ready to transfer prepared/brought items or items in the viewed container. ")
                .append("Questions, negotiation and future promises are not readiness. This gate applies ONLY to item_request; other actions remain independent. ")
                .append("A spoken request is not consumption. Confirmation offers remain pending until the player's UI choice; report only game-confirmed results.\n");
        if (capabilities.stream().anyMatch(AiActionCapabilityBridge::isRelationTransition)) {
            context.append("god_relation_transition accepts only parameters={transition_id: one exact listed ID} ")
                    .append("and an empty targetParticipantIds list. Never substitute template_id or provide scores, tags, ")
                    .append("God targets, results or other parameters. The authored transition supplies its participants ")
                    .append("and changes; only the game's confirmation and validation can apply it.\n");
        }
        if (capabilities.stream().anyMatch(c -> c.actionType().equals(ResourceLocation.parse("mythictrpg:npc_activity_request"))))
            context.append(NpcActivityPrompt.policy());
        if (capabilities.stream().anyMatch(c -> c.actionType().equals(ResourceLocation.parse("mythictrpg:npc_directive"))))
            context.append("npc_directive: parameters={choice_id: exact UUID from current NPC_DIRECTIVE_CONTEXT}, targetParticipantIds=[]. ")
                    .append("No choices means no order. Scene/motives may justify acting without a request tag. STARTED does not prove arrival or victory. ")
                    .append("Never invent targets/coordinates/commands; no bans, kicks or repeated spawn killing.\n");
        if (capabilities.stream().anyMatch(c -> c.actionType().equals(ResourceLocation.parse("mythictrpg:structure_evaluation_request"))))
            context.append("structure_evaluation_request accepts only parameters={quest_id: one exact listed quest ID} ")
                    .append("and empty targetParticipantIds. Request inspection when the player is submitting a finished build; ")
                    .append("only the game computes the evaluation, completion and reward.\n");
    }

    static AiDialogueModels.Proposal normalize(AiDialogueModels.Proposal proposal,
            ResourceLocation godId, String playerText) {
        return normalizeAuthorized(proposal, godId, playerText, AiActionCapabilityService.capabilitiesFor(godId),
                GodRelationTransitionManager.INSTANCE::find);
    }

    static AiDialogueModels.Proposal normalize(AiDialogueModels.Proposal proposal,
            ResourceLocation godId, String playerText, List<ResourceLocation> participants) {
        return normalizeAuthorized(proposal, godId, playerText,
                roomCapabilities(AiActionCapabilityService.capabilitiesFor(godId), godId, participants,
                        GodRelationTransitionManager.INSTANCE::find), GodRelationTransitionManager.INSTANCE::find);
    }

    /** Pure filtering seam; production lookup always reads the game's current transition registry. */
    static List<AiActionCapability> roomCapabilities(List<AiActionCapability> capabilities, ResourceLocation godId,
            List<ResourceLocation> participants, Function<ResourceLocation, Optional<GodRelationTransition>> transitions) {
        if (participants == null || participants.isEmpty() || participants.size() > 16
                || participants.stream().anyMatch(java.util.Objects::isNull)
                || !participants.contains(godId) || Set.copyOf(participants).size() != participants.size()) return List.of();
        Set<ResourceLocation> present = Set.copyOf(participants);
        return capabilities.stream().filter(capability -> !isRelationTransition(capability)
                || capability.templateId().flatMap(transitions).filter(transition -> transition.aiEnabled()
                        && transition.actingGodId().equals(godId)
                        && transition.id().equals(capability.templateId().orElseThrow())
                        && transition.changes().stream().allMatch(change -> present.contains(change.sourceGodId())
                                && present.contains(change.targetGodId()))).isPresent()).toList();
    }

    static AiDialogueModels.Proposal normalizeAuthorized(AiDialogueModels.Proposal proposal,
            ResourceLocation godId, String playerText, List<AiActionCapability> capabilities,
            Function<ResourceLocation, Optional<GodRelationTransition>> transitions) {
        String type = canonicalType(proposal.type());
        if ("npc_directive".equals(type)) {
            if (!Set.of(type, "mythictrpg:" + type).contains(proposal.type().trim().toLowerCase(Locale.ROOT))
                    || !proposal.targetParticipantIds().isEmpty() || !proposal.parameters().keySet().equals(Set.of("choice_id"))
                    || capabilities.stream().noneMatch(c -> c.actionType().equals(ResourceLocation.parse("mythictrpg:" + type)))) return null;
            String choice = proposal.parameters().get("choice_id");
            try { if (choice == null || !java.util.UUID.fromString(choice).toString().equals(choice)) return null; }
            catch (IllegalArgumentException malformed) { return null; }
            // The game's choice registry validates room, revision, actor, player, target and freshness again.
            return new AiDialogueModels.Proposal(type, proposal.title(), proposal.summary(), List.of(), Map.of("choice_id", choice));
        }
        if ("structure_evaluation_request".equals(type)) {
            if (!Set.of(type, "mythictrpg:" + type).contains(proposal.type().trim().toLowerCase(Locale.ROOT))
                    || !proposal.targetParticipantIds().isEmpty() || !proposal.parameters().keySet().equals(Set.of("quest_id"))) return null;
            String rawId = proposal.parameters().get("quest_id");
            ResourceLocation id = rawId == null ? null : ResourceLocation.tryParse(rawId);
            if (id == null || !id.toString().equals(rawId) || capabilities.stream().noneMatch(c ->
                    c.actionType().equals(ResourceLocation.parse("mythictrpg:" + type))
                            && c.templateId().filter(id::equals).isPresent())) return null;
            return new AiDialogueModels.Proposal(type, proposal.title(), proposal.summary(), List.of(), Map.of("quest_id", rawId));
        }
        if ("quest_roster_request".equals(type)) {
            if (!Set.of(type, "mythictrpg:" + type).contains(proposal.type().trim().toLowerCase(Locale.ROOT))
                    || !proposal.targetParticipantIds().isEmpty() || !proposal.parameters().keySet().equals(Set.of("quest_id"))
                    || capabilities.stream().noneMatch(c -> c.actionType().equals(ResourceLocation.parse("mythictrpg:" + type)))) return null;
            String rawId = proposal.parameters().get("quest_id");
            var id = rawId == null ? null : ResourceLocation.tryParse(rawId);
            if (id == null || !id.toString().equals(rawId)) return null;
            return new AiDialogueModels.Proposal(type, proposal.title(), proposal.summary(), List.of(), Map.of("quest_id", rawId));
        }
        if ("npc_activity_request".equals(type)) {
            String rawType = proposal.type().trim().toLowerCase(Locale.ROOT);
            if (!(rawType.equals(type) || rawType.equals("mythictrpg:" + type))
                    || !proposal.targetParticipantIds().isEmpty() || !proposal.parameters().keySet().equals(Set.of("choice_id"))
                    || capabilities.stream().noneMatch(c -> c.actionType().equals(ResourceLocation.parse("mythictrpg:" + type)))) return null;
            String choice = proposal.parameters().get("choice_id");
            if (!"STOP".equals(choice) && !"CONTINUE".equals(choice)) {
                try { if (choice == null || !java.util.UUID.fromString(choice).toString().equals(choice)) return null; }
                catch (IllegalArgumentException malformed) { return null; }
            }
            // Structural validation only. The game binds opaque choices to this actor/room/revision and hard policy.
            return new AiDialogueModels.Proposal(type, proposal.title(), proposal.summary(), List.of(), Map.of("choice_id", choice));
        }
        if ("npc_visit_request".equals(type)) {
            if (!Set.of(type, "mythictrpg:" + type).contains(proposal.type().trim().toLowerCase(Locale.ROOT))
                    || !proposal.parameters().isEmpty() || !proposal.targetParticipantIds().isEmpty()
                    || capabilities.stream().noneMatch(c -> c.actionType().equals(ResourceLocation.parse("mythictrpg:" + type)))) return null;
            return new AiDialogueModels.Proposal(type, proposal.title(), proposal.summary(), List.of(), Map.of());
        }
        if ("raid_offer".equals(type)) {
            String rawType = proposal.type().trim().toLowerCase(Locale.ROOT);
            if (!(rawType.equals(type) || rawType.equals("mythictrpg:" + type))
                    || !proposal.targetParticipantIds().isEmpty()
                    || !proposal.parameters().keySet().equals(Set.of("raid_id"))) return null;
            String rawId = proposal.parameters().get("raid_id");
            ResourceLocation id = rawId == null ? null : ResourceLocation.tryParse(rawId);
            if (id == null || !id.toString().equals(rawId)) return null;
            boolean offered = capabilities.stream().anyMatch(capability ->
                    capability.actionType().equals(ResourceLocation.parse("mythictrpg:raid_offer"))
                            && capability.templateId().filter(id::equals).isPresent());
            if (!offered) return null;
            return new AiDialogueModels.Proposal(type, proposal.title(), proposal.summary(), List.of(),
                    Map.of("raid_id", id.toString()));
        }
        if ("god_relation_transition".equals(type)) {
            String rawType = proposal.type().trim().toLowerCase(Locale.ROOT);
            if (!(rawType.equals(type) || rawType.equals("mythictrpg:" + type))
                    || !proposal.targetParticipantIds().isEmpty()
                    || !proposal.parameters().keySet().equals(Set.of("transition_id"))) return null;
            String rawId = proposal.parameters().get("transition_id");
            ResourceLocation id = rawId == null ? null : ResourceLocation.tryParse(rawId);
            if (id == null || !id.toString().equals(rawId)) return null;
            boolean offered = capabilities.stream().anyMatch(capability -> isRelationTransition(capability)
                    && capability.templateId().filter(id::equals).isPresent());
            var transition = offered ? transitions.apply(id).orElse(null) : null;
            if (transition == null || !transition.id().equals(id) || !transition.aiEnabled()
                    || !transition.actingGodId().equals(godId)) return null;
            return new AiDialogueModels.Proposal(type, proposal.title(), proposal.summary(), List.of(),
                    Map.of("transition_id", id.toString()));
        }
        if ("relationship_change".equals(type)) {
            if (capabilities.stream().noneMatch(capability -> capability.actionType().getPath().equals(type))) {
                return null;
            }
            Integer delta = integer(proposal.parameters().get("affinity_delta"));
            if (delta == null || delta == 0 || delta < -50 || delta > 50) {
                return null;
            }
            return new AiDialogueModels.Proposal(type, proposal.title(), proposal.summary(),
                    proposal.targetParticipantIds(), Map.of("affinity_delta", Integer.toString(delta)));
        }
        if (!("item_request".equals(type) || "reward_proposal".equals(type)
                || "blessing_offer".equals(type) || "world_interaction".equals(type)
                || "player_damage".equals(type) || "generated_quest_offer".equals(type))) {
            return null;
        }
        if ("item_request".equals(type) && !playerDeclaredItemReady(playerText)) {
            return null;
        }
        String templateId = proposal.parameters().getOrDefault("template_id", "");
        boolean authorized = capabilities.stream().anyMatch(capability ->
                capability.actionType().getPath().equals(type)
                        && capability.templateId().map(ResourceLocation::toString).orElse("").equals(templateId));
        if (!authorized) {
            return null;
        }
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("template_id", templateId);
        return new AiDialogueModels.Proposal(type, proposal.title(), proposal.summary(),
                proposal.targetParticipantIds(), Map.copyOf(parameters));
    }

    static boolean playerDeclaredItemReady(String text) {
        return com.sande.mythictrpg.ai.action.ItemReadinessPolicy.declared(text);
    }

    private static boolean isRelationTransition(AiActionCapability capability) {
        return capability.actionType().equals(ResourceLocation.fromNamespaceAndPath("mythictrpg", "god_relation_transition"));
    }

    private static String canonicalType(String raw) {
        String normalized = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        int separator = normalized.indexOf(':');
        if (separator >= 0) {
            normalized = normalized.substring(separator + 1);
        }
        return "relationship_change_proposal".equals(normalized) ? "relationship_change" : normalized;
    }

    private static Integer integer(String value) {
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
