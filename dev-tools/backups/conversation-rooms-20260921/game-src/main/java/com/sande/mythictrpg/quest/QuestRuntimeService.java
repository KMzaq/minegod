package com.sande.mythictrpg.quest;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.ai.api.AiConversationEngineRouter;
import com.sande.mythictrpg.ai.api.AiQuestCompletionContext;
import com.sande.mythictrpg.data.world.MythicWorldState;
import com.sande.mythictrpg.data.player.PlayerMythDataService;
import com.sande.mythictrpg.data.player.PlayerMythProfile;
import com.sande.mythictrpg.dialogue.api.GodDialogueRequest;
import com.sande.mythictrpg.dialogue.server.DialoguePresentationService;
import com.sande.mythictrpg.dialogue.server.DialogueSendStatus;
import com.sande.mythictrpg.interaction.api.InteractionMode;
import com.sande.mythictrpg.quest.reward.QuestRewardResolver;
import com.sande.mythictrpg.quest.reward.ResolvedQuestReward;
import com.sande.mythictrpg.quest.reward.RewardClaimService;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.sande.mythictrpg.story.runtime.StoryEventService;
import com.sande.mythictrpg.story.signal.StorySignal;
import com.sande.mythictrpg.story.signal.StorySignalTypes;

/** Authoritative quest assignment, completion policy and global-progress coordinator. */
public final class QuestRuntimeService {
    public static final QuestRuntimeService INSTANCE = new QuestRuntimeService();

    private QuestRuntimeService() {
    }

    public void registerFtbEvents() {
        FtbQuestAdapter.INSTANCE.registerEvents();
    }

    public QuestOperationResult assign(ServerPlayer player, ResourceLocation questId,
            ResourceLocation giverGodId) {
        requireServerThread(player.server);
        QuestAssignmentValidation validation = validateAssignment(player, questId, giverGodId);
        if (!validation.allowed()) {
            return result(validation.rejectionStatus(), questId, validation.reason());
        }
        FtbQuestBinding binding = FtbQuestBindingManager.INSTANCE.find(questId).orElse(null);
        if (binding.participation().isPresent()) return QuestParticipationService.INSTANCE.offer(player, binding, giverGodId);
        if (!FtbQuestAdapter.INSTANCE.activate(player, binding)) {
            return result(QuestOperationResult.Status.FTB_DEFINITION_MISSING, questId,
                    "The mapped FTB quest or assignment marker is not loaded");
        }
        MythicQuestState state = MythicQuestState.get(player.server);
        Instant assignedAt = Instant.now();
        if (!state.assign(questId, player.getUUID(), giverGodId, assignedAt)) {
            return result(QuestOperationResult.Status.ALREADY_COMPLETED, questId,
                    "The quest became unavailable before assignment committed");
        }
        if (binding.narrativeRole() == QuestNarrativeRole.MAIN_ENTRY) {
            GodAttentionState.get(player.server).recordEntryAssignment(
                    giverGodId, questId, player.getUUID(), assignedAt);
        }
        com.sande.mythictrpg.gameplay.ledger.detail.ImportantEvents.transition(player.server,player.getUUID(),questId,
                "ASSIGNED",assignedAt.toString(),assignedAt,null);
        QuestReminderState.get(player.server).ensure(questId, player.getUUID(),
                player.server.overworld().getGameTime());
        player.sendSystemMessage(Component.literal("[퀘스트 수주] " + questId)
                .withStyle(ChatFormatting.GOLD));
        return result(QuestOperationResult.Status.ASSIGNED, questId, "");
    }

    /** Checks every side-effect-free assignment condition used by AI action validation. */
    public QuestAssignmentValidation validateAssignment(ServerPlayer player, ResourceLocation questId,
            ResourceLocation giverGodId) {
        requireServerThread(player.server);
        if (giverGodId == null) {
            return QuestAssignmentValidation.reject(QuestOperationResult.Status.UNKNOWN_QUEST,
                    "Quest giver ID is missing");
        }
        FtbQuestBinding binding = FtbQuestBindingManager.INSTANCE.find(questId).orElse(null);
        if (binding == null) {
            return QuestAssignmentValidation.reject(QuestOperationResult.Status.UNKNOWN_QUEST,
                    "No MythicTRPG to FTB quest binding exists");
        }
        MythicQuestState state = MythicQuestState.get(player.server);
        if (!state.isWritable()) return QuestAssignmentValidation.reject(QuestOperationResult.Status.INTERNAL_ERROR,
                "Quest state is unavailable");
        if (binding.participation().isPresent() && (state.participationRun(questId).isPresent()
                || !state.assignedPlayers(questId).isEmpty()))
            return QuestAssignmentValidation.reject(QuestOperationResult.Status.PARTICIPATION_CLOSED,
                    "이 퀘스트의 수주자가 이미 확정됐습니다.");
        if (state.isCompleted(questId)) {
            return QuestAssignmentValidation.reject(QuestOperationResult.Status.ALREADY_COMPLETED,
                    "The server-wide quest has already been completed");
        }
        if (state.isAssigned(questId, player.getUUID())) {
            return QuestAssignmentValidation.reject(QuestOperationResult.Status.ALREADY_ASSIGNED,
                    "The quest is already assigned to this player");
        }
        int affinity = PlayerMythDataService.get(player.server).find(player.getUUID())
                .orElseGet(PlayerMythProfile::createActive)
                .affinities().getOrDefault(giverGodId, 0);
        if (affinity < binding.minimumAffinity()) {
            return QuestAssignmentValidation.reject(QuestOperationResult.Status.AFFINITY_TOO_LOW,
                    "The player's affinity with this God is below the authored quest threshold");
        }
        GodAttentionState attention = GodAttentionState.get(player.server);
        if (!attention.isWritable()) {
            return QuestAssignmentValidation.reject(QuestOperationResult.Status.ATTENTION_STATE_UNAVAILABLE,
                    "God attention state is unavailable: "
                            + attention.rejectionReason().orElse("unknown persistence error"));
        }
        if (binding.narrativeRole() == QuestNarrativeRole.MAIN
                && !attention.isFocused(giverGodId, player.getUUID())) {
            return QuestAssignmentValidation.reject(QuestOperationResult.Status.MAIN_QUEST_RESTRICTED,
                    "This main quest is reserved for players currently focused by this God");
        }
        if (binding.narrativeRole() == QuestNarrativeRole.MAIN_ENTRY) {
            Optional<GodAttentionRecord> existing = attention.record(giverGodId);
            if (existing.isPresent()
                    && !existing.orElseThrow().entryQuestId().equals(questId)) {
                return QuestAssignmentValidation.reject(QuestOperationResult.Status.MAIN_QUEST_RESTRICTED,
                        "This God already chose players through a different first main quest");
            }
        }
        if (!FtbQuestAdapter.INSTANCE.definitionsPresent(binding)) {
            return QuestAssignmentValidation.reject(QuestOperationResult.Status.FTB_DEFINITION_MISSING,
                    "The mapped FTB quest or assignment marker is not loaded");
        }
        return QuestAssignmentValidation.allow();
    }

    /** Called only after an interaction was committed by the existing interaction system. */
    public List<QuestOperationResult> onNpcInteraction(MinecraftServer server, UUID playerId,
            ResourceLocation npcId, InteractionMode interactionMode) {
        requireServerThread(server);
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null) {
            return List.of();
        }
        List<QuestOperationResult> results = new ArrayList<>();
        for (QuestAssignment assignment : MythicQuestState.get(server).assignmentsFor(playerId)) {
            FtbQuestBinding binding = FtbQuestBindingManager.INSTANCE.find(assignment.questId()).orElse(null);
            if (binding == null || binding.completionMode() == QuestCompletionMode.AUTO
                    || binding.evaluationPolicy().isPresent()
                    || !binding.acceptsCompletionNpc(assignment.giverGodId(), npcId)) {
                continue;
            }
            boolean correctMode = binding.completionMode() == QuestCompletionMode.PLAYER_RETURN_TO_NPC
                    ? interactionMode == InteractionMode.EXPLICIT
                    : interactionMode == InteractionMode.SPONTANEOUS;
            if (!correctMode) {
                continue;
            }
            if (!FtbQuestAdapter.INSTANCE.objectivesReady(player, binding)) {
                results.add(result(QuestOperationResult.Status.OBJECTIVES_NOT_READY,
                        binding.questId(), "FTB objective tasks are not complete"));
                sendNpcDialogue(player, npcId,
                        "아직 네가 맡은 일을 모두 확인하지 못했다. 준비가 되면 다시 찾아와라.");
                continue;
            }
            if (binding.participation().isPresent()) {
                var run = MythicQuestState.get(server).participationRun(binding.questId()).orElseThrow();
                results.add(QuestParticipationService.INSTANCE.submit(player, binding, npcId, run.total(playerId)));
            } else results.add(commit(player, binding, Optional.of(npcId), Instant.now(), true, true, true));
        }
        return List.copyOf(results);
    }

    /** Invoked by the FTB completion event for native AUTO quests. */
    void onFtbQuestCompleted(long ftbQuestId, List<ServerPlayer> onlineMembers, Instant time) {
        FtbQuestBinding binding = FtbQuestBindingManager.INSTANCE.findByFtbQuestId(ftbQuestId).orElse(null);
        if (binding == null || binding.participation().isPresent() || binding.completionMode() != QuestCompletionMode.AUTO
                || onlineMembers.isEmpty()) {
            return;
        }
        MinecraftServer server = onlineMembers.getFirst().server;
        requireServerThread(server);
        MythicQuestState state = MythicQuestState.get(server);
        ServerPlayer actor = onlineMembers.stream()
                .filter(player -> state.isAssigned(binding.questId(), player.getUUID()))
                .findFirst().orElse(null);
        if (actor == null) {
            MythicTrpg.LOGGER.warn("Ignoring unassigned FTB AUTO completion for logical quest {}",
                    binding.questId());
            return;
        }
        commit(actor, binding, Optional.empty(), time, false, true, true);
    }

    public List<QuestAssignment> readyForNpcVisit(ServerPlayer player) {
        requireServerThread(player.server);
        return MythicQuestState.get(player.server).assignmentsFor(player.getUUID()).stream()
                .filter(assignment -> FtbQuestBindingManager.INSTANCE.find(assignment.questId())
                        .filter(binding -> binding.completionMode() == QuestCompletionMode.NPC_VISIT_PLAYER)
                        .filter(binding -> binding.evaluationPolicy().isEmpty())
                        .filter(binding -> FtbQuestAdapter.INSTANCE.objectivesReady(player, binding))
                        .isPresent())
                .toList();
    }

    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        MythicQuestState state = MythicQuestState.get(player.server);
        for (QuestAssignment assignment : state.assignmentsFor(player.getUUID())) {
            FtbQuestBindingManager.INSTANCE.find(assignment.questId())
                    .ifPresent(binding -> FtbQuestAdapter.INSTANCE.activate(player, binding));
        }
        for (FtbQuestBinding binding : FtbQuestBindingManager.INSTANCE.snapshot().byQuestId().values()) {
            state.completion(binding.questId()).ifPresent(record -> {
                if (record.assignedPlayersAtCompletion().contains(player.getUUID())
                        && !record.completedBy().equals(player.getUUID())) {
                    FtbQuestAdapter.INSTANCE.hideInvalidated(player, binding);
                }
            });
        }
    }

    QuestOperationResult completeEvaluation(ServerPlayer player, FtbQuestBinding binding,
            ResourceLocation completionNpcId) {
        return commit(player, binding, Optional.of(completionNpcId), Instant.now(), true, false);
    }

    private QuestOperationResult commit(ServerPlayer player, FtbQuestBinding binding,
            Optional<ResourceLocation> completionNpcId, Instant time, boolean completeInFtb,
            boolean narrateCompletion) {
        return commit(player, binding, completionNpcId, time, completeInFtb, narrateCompletion, false);
    }

    private QuestOperationResult commit(ServerPlayer player, FtbQuestBinding binding,
            Optional<ResourceLocation> completionNpcId, Instant time, boolean completeInFtb,
            boolean narrateCompletion, boolean issueBindingRewards) {
        MythicQuestState state = MythicQuestState.get(player.server);
        if (!state.isAssigned(binding.questId(), player.getUUID())) {
            return result(QuestOperationResult.Status.NOT_ASSIGNED, binding.questId(),
                    "The quest is not assigned to this player");
        }
        if (!FtbQuestAdapter.INSTANCE.definitionsPresent(binding)) {
            return result(QuestOperationResult.Status.FTB_DEFINITION_MISSING, binding.questId(),
                    "The mapped FTB definitions are not loaded");
        }
        QuestAssignment assignment = state.assignmentsFor(player.getUUID()).stream()
                .filter(candidate -> candidate.questId().equals(binding.questId()))
                .findFirst().orElseThrow();
        Optional<ResolvedQuestReward> resolvedReward = Optional.empty();
        if (issueBindingRewards) {
            QuestRewardResolver.Resolution resolution = QuestRewardResolver.resolve(
                    assignment.giverGodId(), binding.rewardPolicy(), Optional.empty());
            if (resolution.status() == QuestRewardResolver.Status.REJECTED) {
                return result(QuestOperationResult.Status.REWARD_INVALID, binding.questId(), resolution.reason());
            }
            resolvedReward = resolution.reward();
            if (resolvedReward.isPresent()) {
                RewardClaimService.Result preflight = RewardClaimService.INSTANCE.preflight(
                        player, binding.questId(), resolvedReward.orElseThrow());
                if (!preflight.succeeded()) {
                    return result(QuestOperationResult.Status.REWARD_INVALID,
                            binding.questId(), preflight.reason());
                }
            }
        }
        Optional<QuestCompletionRecord> committed = state.tryComplete(binding.questId(), player.getUUID(),
                completionNpcId, time);
        if (committed.isEmpty()) {
            return result(QuestOperationResult.Status.ALREADY_COMPLETED, binding.questId(),
                    "Another player already completed this server-wide quest");
        }

        com.sande.mythictrpg.gameplay.ledger.detail.DetailEvents.quest(player, committed.orElseThrow());
        MythicWorldState.get(player.server).applyQuestClearProgress(
                binding.progressTrackId(), binding.progressOnClear());
        if (completeInFtb && !FtbQuestAdapter.INSTANCE.complete(player, binding)) {
            MythicTrpg.LOGGER.error("Quest {} committed authoritatively but FTB completion sync failed; "
                    + "it will be retried on player login", binding.questId());
        }
        for (UUID participant : committed.orElseThrow().assignedPlayersAtCompletion()) {
            QuestReminderState.get(player.server).remove(binding.questId(), participant);
            if (participant.equals(player.getUUID())) {
                continue;
            }
            com.sande.mythictrpg.gameplay.ledger.detail.ImportantEvents.transition(player.server,participant,binding.questId(),
                    "INVALIDATED_BY_GLOBAL_COMPLETION",time.toString(),time,null);
            ServerPlayer other = player.server.getPlayerList().getPlayer(participant);
            if (other != null) {
                FtbQuestAdapter.INSTANCE.hideInvalidated(other, binding);
                other.sendSystemMessage(Component.literal("[퀘스트 종료] 다른 플레이어가 먼저 완료했습니다: "
                        + binding.questId()).withStyle(ChatFormatting.GRAY));
            }
        }
        MythicTrpg.LOGGER.info("Committed global quest {} by {} (track {} +{})",
                binding.questId(), player.getUUID(), binding.progressTrackId(), binding.progressOnClear());
        StoryEventService.INSTANCE.submit(player.server, StorySignal.of(StorySignalTypes.QUEST_COMPLETED,
                Optional.of(player.getUUID()), Optional.of(binding.questId()),
                player.server.overworld().getGameTime()));
        resolvedReward.ifPresent(reward -> {
            RewardClaimService.Result issued = RewardClaimService.INSTANCE.issue(
                    player, assignment.giverGodId(), binding.questId(), reward);
            if (!issued.succeeded()) {
                MythicTrpg.LOGGER.error("Quest {} completed but reward claim failed: {}",
                        binding.questId(), issued.reason());
            }
        });
        if (narrateCompletion) {
            completionNpcId.ifPresent(npcId -> narrateCompletion(player, npcId, binding.questId()));
        }
        return result(QuestOperationResult.Status.COMPLETED, binding.questId(), "");
    }

    /** Sends an NPC-HUD fallback only when no AI completion narration can be queued. */
    private static void narrateCompletion(ServerPlayer player, ResourceLocation npcId, ResourceLocation questId) {
        if (AiConversationEngineRouter.INSTANCE.onQuestCompleted(
                new AiQuestCompletionContext(player.getUUID(), npcId, questId))) {
            return;
        }
        sendNpcDialogue(player, npcId, "[퀘스트가 완료되었습니다]");
    }

    private static boolean sendNpcDialogue(ServerPlayer player, ResourceLocation npcId, String text) {
        return DialoguePresentationService.INSTANCE.sendTo(player,
                GodDialogueRequest.literal(npcId, text)).status() == DialogueSendStatus.SENT;
    }

    private static QuestOperationResult result(QuestOperationResult.Status status,
            ResourceLocation questId, String reason) {
        return new QuestOperationResult(status, questId, reason);
    }

    private static void requireServerThread(MinecraftServer server) {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Quest state may only change on the server thread");
        }
    }
}
