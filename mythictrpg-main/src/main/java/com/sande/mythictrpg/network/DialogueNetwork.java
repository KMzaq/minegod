package com.sande.mythictrpg.network;

import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.minecraft.server.level.ServerPlayer;
import com.sande.mythictrpg.ai.action.AiActionGateway;
import com.sande.mythictrpg.ai.action.AiActionResult;
import com.sande.mythictrpg.quest.reward.RewardClaimService;
import net.minecraft.ChatFormatting;
import com.sande.mythictrpg.shop.ShopProductKey;
import com.sande.mythictrpg.shop.ShopService;
import com.sande.mythictrpg.shop.ShopTransactionService;
import com.sande.mythictrpg.story.runtime.StoryChoiceUiService;
import com.sande.mythictrpg.story.runtime.StoryEventService;

public final class DialogueNetwork {
    public static final String PROTOCOL_VERSION = "10";

    private DialogueNetwork() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);
        RaidNetwork.register(registrar);
        registrar.playToClient(ConversationRoomsPayload.TYPE, ConversationRoomsPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> ClientConversationRoomsBridge.accept(payload)));
        registrar.playToClient(ClientDialoguePayload.TYPE, ClientDialoguePayload.STREAM_CODEC,
                DialogueNetwork::handleClientDialogue);
        registrar.playToClient(AiConversationStatePayload.TYPE, AiConversationStatePayload.STREAM_CODEC,
                DialogueNetwork::handleAiConversationState);
        registrar.playToClient(AiActionConfirmationPayload.TYPE, AiActionConfirmationPayload.STREAM_CODEC,
                DialogueNetwork::handleAiActionConfirmation);
        registrar.playToServer(AiActionDecisionPayload.TYPE, AiActionDecisionPayload.STREAM_CODEC,
                DialogueNetwork::handleAiActionDecision);
        registrar.playToClient(RewardChoicePayload.TYPE, RewardChoicePayload.STREAM_CODEC,
                DialogueNetwork::handleRewardChoice);
        registrar.playToServer(RewardChoiceSelectionPayload.TYPE, RewardChoiceSelectionPayload.STREAM_CODEC,
                DialogueNetwork::handleRewardChoiceSelection);
        registrar.playToClient(ShopCatalogPayload.TYPE, ShopCatalogPayload.STREAM_CODEC,
                DialogueNetwork::handleShopCatalog);
        registrar.playToServer(ShopTransactionPayload.TYPE, ShopTransactionPayload.STREAM_CODEC,
                DialogueNetwork::handleShopTransaction);
        registrar.playToClient(StoryChoicePagePayload.TYPE, StoryChoicePagePayload.STREAM_CODEC,
                DialogueNetwork::handleStoryChoicePage);
        registrar.playToServer(StoryChoiceBrowsePayload.TYPE, StoryChoiceBrowsePayload.STREAM_CODEC,
                DialogueNetwork::handleStoryChoiceBrowse);
        registrar.playToServer(StoryChoiceSelectionPayload.TYPE, StoryChoiceSelectionPayload.STREAM_CODEC,
                DialogueNetwork::handleStoryChoiceSelection);
    }

    private static void handleClientDialogue(ClientDialoguePayload payload, IPayloadContext context) {
        try {
            if (!ClientDialogueBridge.accept(payload)) {
                MythicTrpg.LOGGER.warn("Dialogue payload {} arrived before the client HUD receiver was installed",
                        payload.messageId());
            }
        } catch (RuntimeException exception) {
            MythicTrpg.LOGGER.warn("Rejected dialogue payload {} from source {}: {}",
                    payload.messageId(), payload.source(), exception.getMessage());
            context.disconnect(Component.translatable("disconnect.mythictrpg.invalid_dialogue_payload"));
        }
    }

    private static void handleAiConversationState(AiConversationStatePayload payload,
            IPayloadContext context) {
        if (!ClientAiConversationBridge.accept(payload)) {
            MythicTrpg.LOGGER.warn("AI conversation state arrived before the client receiver was installed");
        }
    }

    private static void handleAiActionConfirmation(AiActionConfirmationPayload payload,
            IPayloadContext context) {
        if (!ClientAiActionConfirmationBridge.accept(payload)) {
            MythicTrpg.LOGGER.warn("AI action confirmation arrived before the client receiver was installed");
        }
    }

    private static void handleAiActionDecision(AiActionDecisionPayload payload,
            IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        context.enqueueWork(() -> {
            if (!payload.accepted()) {
                if (AiActionGateway.cancel(player, payload.proposalId())) {
                    player.sendSystemMessage(Component.translatable("message.mythictrpg.ai_action_cancelled")
                            .withStyle(ChatFormatting.GRAY));
                }
                return;
            }
            AiActionResult result = AiActionGateway.confirm(player, payload.proposalId());
            String key = result.succeeded() ? "message.mythictrpg.ai_action_executed"
                    : "message.mythictrpg.ai_action_rejected";
            player.sendSystemMessage(Component.translatable(key, result.actionType(), result.reason())
                    .withStyle(result.succeeded() ? ChatFormatting.GREEN : ChatFormatting.RED));
        });
    }

    private static void handleRewardChoice(RewardChoicePayload payload, IPayloadContext context) {
        try {
            if (!ClientRewardChoiceBridge.accept(payload)) {
                MythicTrpg.LOGGER.warn("Reward choice {} arrived before the client receiver was installed",
                        payload.claimId());
            }
        } catch (RuntimeException exception) {
            MythicTrpg.LOGGER.warn("Rejected reward choice payload {}: {}",
                    payload.claimId(), exception.getMessage());
            context.disconnect(Component.translatable("disconnect.mythictrpg.invalid_reward_choice_payload"));
        }
    }

    private static void handleRewardChoiceSelection(RewardChoiceSelectionPayload payload,
            IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        context.enqueueWork(() -> {
            RewardClaimService.Result result = RewardClaimService.INSTANCE.choose(
                    player, payload.claimId(), payload.optionId());
            if (!result.succeeded()) {
                player.sendSystemMessage(Component.translatable(
                        "message.mythictrpg.reward_choice_rejected", result.reason())
                        .withStyle(ChatFormatting.RED));
            }
        });
    }

    private static void handleShopCatalog(ShopCatalogPayload payload, IPayloadContext context) {
        try {
            if (!ClientShopBridge.accept(payload)) {
                MythicTrpg.LOGGER.warn("Shop catalog arrived before the client receiver was installed");
            }
        } catch (RuntimeException exception) {
            MythicTrpg.LOGGER.warn("Rejected shop catalog payload: {}", exception.getMessage());
            context.disconnect(Component.translatable("disconnect.mythictrpg.invalid_shop_payload"));
        }
    }

    private static void handleShopTransaction(ShopTransactionPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        context.enqueueWork(() -> {
            ShopTransactionService.Result result = ShopTransactionService.INSTANCE.transact(player,
                    payload.shopType(), new ShopProductKey(payload.shopId(), payload.productId()), payload.lots());
            if (result.succeeded()) {
                player.sendSystemMessage(Component.literal((payload.shopType() == com.sande.mythictrpg.shop.ShopType.BUY
                        ? "구매 완료: " : "판매 완료: ") + result.itemCount() + "개 / " + result.price() + "골드")
                        .withStyle(ChatFormatting.GREEN));
            } else {
                player.sendSystemMessage(Component.literal("거래 실패: " + result.reason())
                        .withStyle(ChatFormatting.RED));
            }
            ShopService.INSTANCE.open(player, payload.shopType());
        });
    }

    private static void handleStoryChoicePage(StoryChoicePagePayload payload, IPayloadContext context) {
        try {
            if (!ClientStoryChoiceBridge.accept(payload)) {
                MythicTrpg.LOGGER.warn("Story choice page arrived before the client receiver was installed");
            }
        } catch (RuntimeException exception) {
            MythicTrpg.LOGGER.warn("Rejected Story choice page: {}", exception.getMessage());
            context.disconnect(Component.literal("Invalid Story choice payload"));
        }
    }

    private static void handleStoryChoiceBrowse(StoryChoiceBrowsePayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        context.enqueueWork(() -> StoryChoiceUiService.INSTANCE.browsePage(player, payload.page()));
    }

    private static void handleStoryChoiceSelection(StoryChoiceSelectionPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        context.enqueueWork(() -> {
            try {
                StoryEventService.StoryChoiceResult result = StoryEventService.INSTANCE.choose(
                        player, payload.instanceId(), payload.revision(), payload.outcomeId());
                player.sendSystemMessage(Component.literal(result.accepted()
                        ? "[스토리] " + result.reason() : "[스토리] 선택 거절: " + result.reason())
                        .withStyle(result.accepted() ? ChatFormatting.GREEN : ChatFormatting.RED));
                if (!result.accepted()) StoryChoiceUiService.INSTANCE.sendPage(player, 0, true);
            } catch (RuntimeException exception) {
                MythicTrpg.LOGGER.warn("Story choice submission failed for {}", player.getUUID(), exception);
                player.sendSystemMessage(Component.literal("[스토리] 선택 처리에 실패했습니다. 다시 확인해 주세요.")
                        .withStyle(ChatFormatting.RED));
                StoryChoiceUiService.INSTANCE.sendPage(player, 0, true);
            }
        });
    }

}
