package com.sande.mythictrpg.ai.action;

import com.sande.mythictrpg.raid.RaidRuntime;
import net.minecraft.resources.ResourceLocation;
import java.util.Map;

/** Confirmation authorizes only formation. Every other participant must join, and the leader starts the queue. */
final class RaidOfferAiAction {
    private RaidOfferAiAction() { }
    static AiActionDefinition definition() {
        return new AiActionDefinition(AiActionTypes.RAID_OFFER,
                AiActionDefinition.ConfirmationPolicy.PLAYER_CONFIRMATION_REQUIRED,
                RaidOfferAiAction::validate, RaidOfferAiAction::execute);
    }
    private static AiActionValidation validate(AiActionContext context, AiActionProposal proposal) {
        ResourceLocation raid = raid(proposal);
        if (raid == null) return AiActionValidation.reject("Raid offer requires only an exact namespaced raid_id");
        var result = RaidRuntime.INSTANCE.validateOffer(context.targetPlayer(), proposal.actingGodId(), raid);
        return result.succeeded() ? AiActionValidation.accept() : AiActionValidation.reject(result.message());
    }
    private static AiActionExecution execute(AiActionContext context, AiActionProposal proposal) {
        var validation = validate(context, proposal);
        if (!validation.accepted()) return AiActionExecution.rejected(validation.reason());
        var result = RaidRuntime.INSTANCE.create(context.targetPlayer(), raid(proposal));
        if (!result.succeeded()) return AiActionExecution.rejected(result.message());
        var attempt = result.attemptId().orElseThrow();
        context.targetPlayer().sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "[레이드] 모집을 만들었습니다: " + attempt + ". 다른 참가자는 /mythraid join " + attempt
                        + ", 준비된 리더는 /mythraid start " + attempt + " 를 사용하세요."));
        return new AiActionExecution(true, "모집만 생성됨 (FORMING). 전투·이동·보상은 아직 실행되지 않음.", Map.of("raid_id", raid(proposal).toString(),
                "attempt_id", attempt.toString(), "status", "FORMING", "combat_started", "false",
                "next_step", "Players explicitly join; leader explicitly starts the queue. No teleport yet."));
    }
    private static ResourceLocation raid(AiActionProposal proposal) {
        if (!proposal.parameters().keySet().equals(java.util.Set.of("raid_id"))) return null;
        String raw = proposal.parameters().get("raid_id");
        ResourceLocation id = ResourceLocation.tryParse(raw);
        return id != null && id.toString().equals(raw) ? id : null;
    }
}
