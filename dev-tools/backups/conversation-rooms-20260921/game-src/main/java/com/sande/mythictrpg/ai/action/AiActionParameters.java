package com.sande.mythictrpg.ai.action;

import net.minecraft.resources.ResourceLocation;

import java.util.Set;

final class AiActionParameters {
    private AiActionParameters() {
    }

    static boolean hasOnly(AiActionProposal proposal, String... keys) {
        return proposal.parameters().keySet().equals(Set.of(keys));
    }

    static ResourceLocation templateId(AiActionProposal proposal) {
        String raw = proposal.parameters().getOrDefault("template_id", "");
        ResourceLocation parsed = ResourceLocation.tryParse(raw);
        return parsed != null && raw.contains(":") ? parsed : null;
    }

    static <T extends AiActionTemplate> T template(AiActionProposal proposal, Class<T> type) {
        ResourceLocation templateId = templateId(proposal);
        if (templateId == null) {
            return null;
        }
        AiActionTemplate template = AiActionTemplateManager.INSTANCE
                .find(templateId, proposal.actionType(), proposal.actingGodId()).orElse(null);
        return type.isInstance(template) ? type.cast(template) : null;
    }
}
