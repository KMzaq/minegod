package com.sande.mythictrpg.ai.action;

import java.util.Map;

/** Consumes an authored item request only after the player declared readiness and confirmed it. */
final class ItemRequestAiAction {
    private ItemRequestAiAction() {
    }

    static AiActionDefinition definition() {
        return new AiActionDefinition(AiActionTypes.ITEM_REQUEST,
                AiActionDefinition.ConfirmationPolicy.PLAYER_CONFIRMATION_REQUIRED,
                ItemRequestAiAction::validate, ItemRequestAiAction::execute);
    }

    private static AiActionValidation validate(AiActionContext context, AiActionProposal proposal) {
        if (!AiActionParameters.hasOnly(proposal, "template_id", "player_ready")
                || !"true".equals(proposal.parameters().get("player_ready"))) {
            return AiActionValidation.reject("Item transfer is allowed only after the player says the item is ready");
        }
        ItemRequestTemplate template = AiActionParameters.template(proposal, ItemRequestTemplate.class);
        if (template == null) {
            return AiActionValidation.reject("Item request template is not registered for this God");
        }
        InventoryItemTransferService.Availability available = InventoryItemTransferService
                .availability(context.targetPlayer(), template.itemId());
        return available.total() >= template.count() ? AiActionValidation.accept()
                : AiActionValidation.reject("Required items were not found in the inventory or looked-at container");
    }

    private static AiActionExecution execute(AiActionContext context, AiActionProposal proposal) {
        ItemRequestTemplate template = AiActionParameters.template(proposal, ItemRequestTemplate.class);
        if (template == null || !InventoryItemTransferService.consume(
                context.targetPlayer(), template.itemId(), template.count())) {
            return AiActionExecution.rejected("Required items changed before the transfer was confirmed");
        }
        return AiActionExecution.executed(Map.of(
                "template_id", template.id().toString(),
                "item_id", template.itemId().toString(),
                "count", Integer.toString(template.count())));
    }
}
