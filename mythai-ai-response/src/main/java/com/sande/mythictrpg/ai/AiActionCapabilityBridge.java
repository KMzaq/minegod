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
        context.append("These are proposals, not completed facts. Use exactly the listed type and declared parameter keys. ")
                .append("Do not invent an item, reward, effect, event, count, duration, or target. ")
                .append("generated_quest_offer is always a SIDE quest: select only a listed template, use title and ")
                .append("summary for fitting narration, and never alter or invent its objective, count, progress gate, or reward. ")
                .append("For player_damage, never invent damage values and never select it merely because it is listed. ")
                .append("Select an authored damage template only when the immediate scene and the NPC's established ")
                .append("personality, relationship, motive, and recent dialogue make a physical hit narratively coherent. ")
                .append("A playful non-lethal hit, an ominous encounter strike, and a lethal hostile act are distinct; ")
                .append("use only a listed template whose damage_mode and allow_death match the intended scene. ")
                .append("Do not claim the hit succeeded in speech; the server executes or rejects it. ")
                .append("Do not introduce an item request merely because a capability is listed; request it only when the ")
                .append("live dialogue, an authored quest, or the established relationship context gives a concrete reason. ")
                .append("item_request is special: the NPC may verbally request an authored item when context warrants it, ")
                .append("but proposals must remain empty until the player's CURRENT message clearly says the items are ")
                .append("prepared, brought, placed in the container they are looking at, or otherwise ready to transfer. ")
                .append("A question, negotiation, promise to get it later, or discussion of the item is not readiness. ")
                .append("Confirmation-required actions are only offers until the server receives the player's UI choice.\n");
        if (capabilities.stream().anyMatch(AiActionCapabilityBridge::isRelationTransition)) {
            context.append("god_relation_transition accepts only parameters={transition_id: one exact listed ID} ")
                    .append("and an empty targetParticipantIds list. Never substitute template_id or provide scores, tags, ")
                    .append("God targets, results or other parameters. The authored transition supplies its participants ")
                    .append("and changes; only the game's confirmation and validation can apply it.\n");
        }
        if (capabilities.stream().anyMatch(c -> c.actionType().equals(ResourceLocation.parse("mythictrpg:npc_activity_request"))))
            context.append(NpcActivityPrompt.policy());
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
