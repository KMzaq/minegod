package com.sande.mythictrpg.ai;

import com.sande.mythictrpg.ai.proposal.QuestRewardContextProvider;
import com.sande.mythictrpg.ai.tone.SocialAuthorityContextProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Compatibility facade consumed by the new MythicTRPG reflection bridge.
 *
 * <p>The implementation deliberately delegates to the previously tested content-registry
 * + two-stage Ollama adapter. It accepts one player and one server-approved God;
 * authoritative gameplay and participant selection remain in MythicTRPG.</p>
 */
public final class GodAiDialogueService {
    public static final GodAiDialogueService INSTANCE = new GodAiDialogueService();

    private GodAiDialogueService() {
    }

    public StartConversationResult startConversation(Collection<ServerPlayer> players,
            Collection<ResourceLocation> godIds, UUID interactionId) {
        if (players == null || players.size() != 1 || godIds == null || godIds.size() != 1) {
            return new StartConversationResult(StartConversationStatus.INVALID_PARTICIPANTS, null);
        }
        ServerPlayer player = players.iterator().next();
        ResourceLocation godId = godIds.iterator().next();
        if (AiTestDialogueAdapter.INSTANCE.isActive(player)) {
            AiTestDialogueAdapter.INSTANCE.stop(player);
        }
        AiTestDialogueAdapter.StartResult result = AiTestDialogueAdapter.INSTANCE.start(player, godId);
        if (result != AiTestDialogueAdapter.StartResult.STARTED) {
            return new StartConversationResult(StartConversationStatus.CONTENT_FAILURE, null);
        }
        UUID sessionId = interactionId == null ? UUID.randomUUID() : interactionId;
        com.sande.mythai.response.memory.DialogueMemoryBridge.bind(player, godId, sessionId);
        return new StartConversationResult(StartConversationStatus.STARTED, sessionId);
    }

    public boolean isActive(ServerPlayer player) {
        return AiTestDialogueAdapter.INSTANCE.isActive(player);
    }

    public void handlePlayerText(ServerPlayer player, String rawText) {
        AiTestDialogueAdapter.INSTANCE.handlePlayerText(player, rawText);
    }

    public void seedNpcTurn(ServerPlayer player, ResourceLocation godId, String text) {
        AiTestDialogueAdapter.INSTANCE.seedNpcTurn(player, godId, text);
    }

    /** Queues an AI-authored NPC completion narration without ending the session. */
    public boolean requestQuestCompletionDialogue(ServerPlayer player, ResourceLocation godId,
            ResourceLocation questId) {
        return AiTestDialogueAdapter.INSTANCE.generateQuestCompletionDialogue(player, godId, questId);
    }

    public boolean requestQuestEvaluationDialogue(ServerPlayer player, ResourceLocation godId,
            ResourceLocation questId, int score, int passingScore, boolean passed, int rewardTier,
            String summary, List<String> grantedRewards) {
        return AiTestDialogueAdapter.INSTANCE.generateQuestEvaluationDialogue(player, godId, questId,
                score, passingScore, passed, rewardTier, summary, grantedRewards);
    }

    public void stopConversation(ServerPlayer player) {
        AiTestDialogueAdapter.INSTANCE.stop(player);
    }

    public void discardPlayer(UUID playerId) {
        AiTestDialogueAdapter.INSTANCE.discardPlayer(playerId);
    }

    public void onPlayerLoggedOut(ServerPlayer player) {
        AiTestDialogueAdapter.INSTANCE.onPlayerLoggedOut(player);
    }

    public void stop() {
        AiTestDialogueAdapter.INSTANCE.stop();
        com.sande.mythai.response.memory.DialogueMemoryBridge.close();
    }

    public void installQuestRewardContextProvider(QuestRewardContextProvider provider) {
        Objects.requireNonNull(provider, "provider");
    }

    public void installSocialAuthorityContextProvider(SocialAuthorityContextProvider provider) {
        Objects.requireNonNull(provider, "provider");
    }

    public enum StartConversationStatus {
        STARTED,
        INVALID_PARTICIPANTS,
        CONTENT_FAILURE
    }

    public record StartConversationResult(StartConversationStatus status, UUID sessionId) {
    }
}
