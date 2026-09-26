package com.sande.mythictrpg.ai;

import com.sande.mythictrpg.ai.api.AiConversationControlContext;
import com.sande.mythictrpg.ai.api.AiConversationEngine;
import com.sande.mythictrpg.ai.api.AiConversationStartContext;
import com.sande.mythictrpg.ai.api.AiQuestCompletionContext;
import com.sande.mythictrpg.ai.api.AiQuestEvaluationContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.List;
import java.util.UUID;

/** Minecraft integration adapter around the tested local conversation engine. */
public final class MythAiConversationEngine implements AiConversationEngine {
    public static final MythAiConversationEngine INSTANCE = new MythAiConversationEngine();
    private final AiTestContentRegistryBridge contentRegistry = new AiTestContentRegistryBridge();

    private MythAiConversationEngine() {
    }

    @Override
    public void onConversationStarted(AiConversationStartContext context) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || context.godIds().isEmpty()) {
            return;
        }
        var primaryGod = context.godIds().getFirst();
        for (UUID playerId : context.audiencePlayerIds()) {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null) {
                continue;
            }
            var result = GodAiDialogueService.INSTANCE.startConversation(
                    List.of(player), List.of(primaryGod), context.interactionId());
            if (result.status() != GodAiDialogueService.StartConversationStatus.STARTED) {
                continue;
            }
            context.initialTurns().forEach(turn -> {
                GodAiDialogueService.INSTANCE.seedNpcTurn(player, turn.speakerGodId(), turn.text());
                try {
                    var content = contentRegistry.load(turn.speakerGodId(), "R_NEUTRAL", context.godIds());
                    SpokenIdentityReveal.commitIfNameWasSpoken(player, turn.speakerGodId(),
                            content.profile().displayName(), turn.text());
                } catch (RuntimeException ignored) {
                    // Missing optional static content must not invalidate an already committed interaction.
                }
            });
        }
    }

    @Override
    public boolean onPlayerText(UUID playerId, String text) {
        ServerPlayer player = player(playerId);
        if (player == null || !GodAiDialogueService.INSTANCE.isActive(player)) {
            return false;
        }
        GodAiDialogueService.INSTANCE.handlePlayerText(player, text);
        return true;
    }

    @Override
    public boolean onQuestCompleted(AiQuestCompletionContext context) {
        ServerPlayer player = player(context.playerId());
        return player != null && GodAiDialogueService.INSTANCE.requestQuestCompletionDialogue(player,
                context.npcId(), context.questId());
    }

    @Override
    public boolean onQuestEvaluated(AiQuestEvaluationContext context) {
        ServerPlayer player = player(context.playerId());
        return player != null && GodAiDialogueService.INSTANCE.requestQuestEvaluationDialogue(player,
                context.npcId(), context.questId(), context.score(), context.passingScore(), context.passed(),
                context.rewardTier().orElse(0), context.evaluationSummary(), context.grantedRewards());
    }

    @Override
    public void onEnabledChanged(AiConversationControlContext context) {
        ServerPlayer player = player(context.playerId());
        if (player == null) {
            return;
        }
        if (!context.enabled()) {
            GodAiDialogueService.INSTANCE.stopConversation(player);
            return;
        }
        context.currentGodId().ifPresent(godId -> {
            if (!GodAiDialogueService.INSTANCE.isActive(player)) {
                GodAiDialogueService.INSTANCE.startConversation(
                        List.of(player), List.of(godId), com.sande.mythictrpg.ai.server.AiConversationRuntimeService.INSTANCE
                                .memoryContext(player).map(com.sande.mythictrpg.ai.memorycontract.ConversationMemoryContext::interactionId)
                                .orElseGet(UUID::randomUUID));
            }
        });
    }

    @Override
    public void onPlayerLoggedOut(UUID playerId) {
        GodAiDialogueService.INSTANCE.discardPlayer(playerId);
    }

    @Override
    public void onServerStopped() {
        GodAiDialogueService.INSTANCE.stop();
    }

    private static ServerPlayer player(UUID playerId) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server == null ? null : server.getPlayerList().getPlayer(playerId);
    }
}
