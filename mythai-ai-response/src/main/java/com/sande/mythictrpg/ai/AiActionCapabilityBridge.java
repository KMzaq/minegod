package com.sande.mythictrpg.ai;

import com.sande.mythictrpg.ai.action.AiActionCapability;
import com.sande.mythictrpg.ai.action.AiActionCapabilityService;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Prompt and normalization boundary for game-owned AI action capabilities. */
final class AiActionCapabilityBridge {
    private AiActionCapabilityBridge() {
    }

    static void appendPrompt(StringBuilder context, ResourceLocation godId) {
        List<AiActionCapability> capabilities = AiActionCapabilityService.capabilitiesFor(godId);
        if (capabilities.isEmpty()) {
            return;
        }
        context.append("\n[SERVER_AUTHORIZED_ACTION_CAPABILITIES]\n");
        for (AiActionCapability capability : capabilities) {
            context.append("- ").append(capability.promptSummary()).append("\n");
        }
        context.append("These are proposals, not completed facts. Use exactly the listed type and template_id. ")
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
    }

    static AiDialogueModels.Proposal normalize(AiDialogueModels.Proposal proposal,
            ResourceLocation godId, String playerText) {
        String type = canonicalType(proposal.type());
        List<AiActionCapability> capabilities = AiActionCapabilityService.capabilitiesFor(godId);
        if ("relationship_change".equals(type)) {
            if (capabilities.stream().noneMatch(capability -> capability.actionType().getPath().equals(type))) {
                return null;
            }
            Integer delta = integer(proposal.parameters().get("affinity_delta"));
            if (delta == null || delta == 0 || Math.abs(delta) > 50) {
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
        String normalized = text == null ? "" : text.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
        return containsAny(normalized, "준비됐", "준비했", "준비 끝", "가져왔", "챙겨왔", "다 모았",
                "여기 있어", "여기있어", "건넬게", "건네줄게", "받아", "넣어뒀", "넣어 놨", "상자에 넣었");
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
