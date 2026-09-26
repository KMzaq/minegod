package com.sande.mythictrpg.quest;

import com.sande.mythictrpg.ai.api.AiConversationEngineRouter;
import com.sande.mythictrpg.ai.api.AiQuestCompletionContext;
import com.sande.mythictrpg.ai.server.AiConversationRuntimeService;
import com.sande.mythictrpg.ai.server.ConversationRooms;
import com.sande.mythictrpg.ai.action.AiActionScope;
import com.sande.mythictrpg.data.world.MythicWorldState;
import com.sande.mythictrpg.dialogue.api.GodDialogueRequest;
import com.sande.mythictrpg.dialogue.server.DialoguePresentationService;
import com.sande.mythictrpg.gameplay.observation.GameplayObservation;
import com.sande.mythictrpg.quest.reward.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.time.Instant;
import java.util.*;

/** Game-owned enrollment, per-player objectives and settlement of explicitly typed quests. */
public final class QuestParticipationService {
    public static final QuestParticipationService INSTANCE = new QuestParticipationService();
    private record Pending(ResourceLocation quest, ResourceLocation god, QuestEnrollment enrollment,
            Map<UUID, UUID> generations, long bindingGeneration, boolean roomAction) {}
    private final Map<UUID, Pending> pending = new LinkedHashMap<>();
    private final Map<UUID, Long> lastSettlementWarning = new HashMap<>();

    private QuestParticipationService() {}

    public QuestOperationResult offer(ServerPlayer selected, FtbQuestBinding binding, ResourceLocation god) {
        return offer(selected, binding, god, null);
    }

    public QuestOperationResult offerRoom(ServerPlayer selected, FtbQuestBinding binding, ResourceLocation god,
            AiActionScope roomScope) {
        requireThread(selected.server);
        if (roomScope == null || !roomScope.actingGodId().equals(god)
                || !ConversationRooms.INSTANCE.actionCurrent(selected, roomScope.sessionId(), god)) {
            return result(QuestOperationResult.Status.NOT_ASSIGNED, binding.questId(), "The quest offer room is no longer current");
        }
        return offer(selected, binding, god, roomScope);
    }

    private QuestOperationResult offer(ServerPlayer selected, FtbQuestBinding binding, ResourceLocation god,
            AiActionScope roomScope) {
        requireThread(selected.server);
        var validation = QuestRuntimeService.INSTANCE.validateAssignment(selected, binding.questId(), god);
        if (!validation.allowed()) return result(validation.rejectionStatus(), binding.questId(), validation.reason());
        String problem = configurationProblem(binding, god);
        if (!problem.isEmpty()) return result(QuestOperationResult.Status.REWARD_INVALID, binding.questId(), problem);
        var runtime = AiConversationRuntimeService.INSTANCE;
        var scope = roomScope == null ? runtime.currentActionScope(selected).orElse(null) : roomScope;
        if (scope == null || !scope.actingGodId().equals(god))
            return result(QuestOperationResult.Status.NOT_ASSIGNED, binding.questId(), "수주에는 해당 신과의 대화가 필요합니다.");
        if (pending.values().stream().anyMatch(p -> p.quest().equals(binding.questId())
                && !p.enrollment().conversationId().equals(scope.sessionId())))
            return result(QuestOperationResult.Status.PARTICIPATION_CLOSED, binding.questId(), "다른 대화에서 참가 여부를 확인 중입니다.");
        if (pending.values().stream().anyMatch(p -> p.enrollment().conversationId().equals(scope.sessionId())))
            return result(QuestOperationResult.Status.WAITING_FOR_PARTICIPANTS, binding.questId(), "참가자 응답을 기다리고 있습니다.");
        // SOLO addresses the selected eligible speaker only, never assigns all nearby players.
        Set<UUID> audience = binding.participation().orElseThrow().type() == QuestParticipationType.SOLO
                ? Set.of(selected.getUUID()) : roomScope == null ? runtime.conversationPlayers(selected)
                        : ConversationRooms.INSTANCE.actionPlayers(selected, scope.sessionId(), god);
        if (audience.isEmpty()) return result(QuestOperationResult.Status.NOT_ASSIGNED, binding.questId(), "대화 참가자를 다시 확인해주세요.");
        Set<UUID> eligible = new LinkedHashSet<>();
        Map<UUID, UUID> generations = new LinkedHashMap<>();
        for (UUID id : audience) {
            ServerPlayer player = selected.server.getPlayerList().getPlayer(id);
            if (player == null) return result(QuestOperationResult.Status.NOT_ASSIGNED, binding.questId(), "대화 참가자를 다시 확인해주세요.");
            if (roomScope != null) {
                if (!ConversationRooms.INSTANCE.actionCurrent(player, scope.sessionId(), god))
                    return result(QuestOperationResult.Status.NOT_ASSIGNED, binding.questId(), "대화방 참가 상태가 변경되었습니다.");
                generations.put(id, scope.sessionId());
            } else {
                var generation = runtime.conversationGeneration(id);
                if (generation.isEmpty()) return result(QuestOperationResult.Status.NOT_ASSIGNED, binding.questId(), "대화 참가자를 다시 확인해주세요.");
                generations.put(id, generation.orElseThrow());
            }
            if (QuestRuntimeService.INSTANCE.validateAssignment(player, binding.questId(), god).allowed()) eligible.add(id);
        }
        UUID offer = UUID.randomUUID();
        var enrollment = new QuestEnrollment(offer, scope.sessionId(), audience, eligible);
        pending.put(offer, new Pending(binding.questId(), god, enrollment, Map.copyOf(generations),
                FtbQuestBindingManager.INSTANCE.snapshot().generation(), roomScope != null));
        for (UUID id : audience) {
            ServerPlayer player = selected.server.getPlayerList().getPlayer(id);
            String text = eligible.contains(id) ? "이 퀘스트에 참여하겠습니까? 네 / 아니요로 답해주세요. 모두의 답변을 기다리겠습니다."
                    : "현재 수주 조건을 충족하지 못했습니다. 이번 참여에는 아니요로 답해주세요.";
            npc(player, god, text);
            player.sendSystemMessage(Component.literal("[" + binding.questId() + "] ")
                    .append(Component.literal("[네]").withStyle(style -> style.withClickEvent(new net.minecraft.network.chat.ClickEvent(
                            net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND, "/mythquest answer " + offer + " yes"))))
                    .append(" ").append(Component.literal("[아니요]").withStyle(style -> style.withClickEvent(new net.minecraft.network.chat.ClickEvent(
                            net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND, "/mythquest answer " + offer + " no")))));
        }
        return result(QuestOperationResult.Status.WAITING_FOR_PARTICIPANTS, binding.questId(), "전원 응답 전에는 수주되지 않습니다.");
    }

    public boolean handleAnswer(ServerPlayer player, String text) {
        var offer = pending.values().stream().filter(p -> !p.roomAction()
                && p.enrollment().audience().contains(player.getUUID())).findFirst();
        return handleAnswer(player, text, offer);
    }

    /** Plain yes/no is routed only through the room in which it was actually spoken. */
    public boolean handleRoomAnswer(ServerPlayer player, UUID sessionId, ResourceLocation god, String text) {
        requireThread(player.server);
        var offer = pending.values().stream().filter(p -> p.roomAction()
                && p.enrollment().conversationId().equals(sessionId) && p.god().equals(god)
                && p.enrollment().audience().contains(player.getUUID())).findFirst();
        return handleAnswer(player, text, offer);
    }

    private boolean handleAnswer(ServerPlayer player, String text, Optional<Pending> offer) {
        if (offer.isEmpty()) return false;
        String normalized = text.trim().toLowerCase(Locale.ROOT);
        QuestEnrollment.Answer answer = switch (normalized) {
            case "네", "예", "yes" -> QuestEnrollment.Answer.YES;
            case "아니요", "아니오", "no" -> QuestEnrollment.Answer.NO;
            default -> null;
        };
        if (answer == null) return false;
        answer(player, offer.orElseThrow().enrollment().offerId(), answer);
        return true;
    }

    public boolean answer(ServerPlayer player, UUID offerId, QuestEnrollment.Answer answer) {
        requireThread(player.server);
        Pending offer = pending.get(offerId);
        if (offer == null || !current(player.server, offer)) {
            if (offer != null) cancel(player.server, offer, "대화 또는 퀘스트 설정이 바뀌어 수주 질문을 취소했습니다.");
            return false;
        }
        if (!offer.enrollment().answer(offerId, offer.enrollment().conversationId(), player.getUUID(), answer)) {
            npc(player, offer.god(), "이미 답했거나 수주 조건이 맞지 않아 그 응답을 적용할 수 없습니다.");
            return false;
        }
        if (!offer.enrollment().ready()) {
            npc(player, offer.god(), "답변을 확인했습니다. 남은 " + offer.enrollment().waitingFor().size() + "명의 답변을 기다리겠습니다.");
            return true;
        }
        Set<UUID> accepted = offer.enrollment().accepted();
        FtbQuestBinding binding = FtbQuestBindingManager.INSTANCE.find(offer.quest()).orElseThrow();
        for (UUID id : accepted) {
            ServerPlayer candidate = player.server.getPlayerList().getPlayer(id);
            if (candidate == null || !QuestRuntimeService.INSTANCE.validateAssignment(candidate, offer.quest(), offer.god()).allowed()) {
                cancel(player.server, offer, "수주 조건이 변경되어 다시 확인해야 합니다.");
                return false;
            }
        }
        pending.remove(offerId);
        if (accepted.isEmpty()) {
            offer.enrollment().audience().forEach(id -> npc(player.server.getPlayerList().getPlayer(id), offer.god(), "이번 의뢰는 맡기지 않겠습니다."));
            return true;
        }
        QuestParticipationRun run = new QuestParticipationRun(UUID.randomUUID(), offer.quest().toString(), offer.god().toString(),
                binding.participation().orElseThrow(), accepted, player.server.overworld().getGameTime());
        MythicQuestState.get(player.server).addParticipationRun(run);
        for (UUID id : accepted) {
            ServerPlayer member = player.server.getPlayerList().getPlayer(id);
            com.sande.mythictrpg.gameplay.ledger.detail.ImportantEvents.transition(player.server,id,offer.quest(),
                    "ASSIGNED",run.snapshot().runId().toString(),Instant.now(),run.snapshot().runId());
            QuestReminderState.get(player.server).ensure(offer.quest(), id, run.snapshot().startedAt());
            if (binding.narrativeRole() == QuestNarrativeRole.MAIN_ENTRY)
                GodAttentionState.get(player.server).recordEntryAssignment(offer.god(), offer.quest(), id, Instant.now());
            FtbQuestAdapter.INSTANCE.syncParticipation(member, binding, run);
            npc(member, offer.god(), "[퀘스트를 수주했습니다] 참여 인원: " + accepted.size());
        }
        return true;
    }

    private boolean current(MinecraftServer server, Pending offer) {
        if (offer.bindingGeneration() != FtbQuestBindingManager.INSTANCE.snapshot().generation()) return false;
        var runtime = AiConversationRuntimeService.INSTANCE;
        for (var entry : offer.generations().entrySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (offer.roomAction()) {
                if (player == null || !entry.getValue().equals(offer.enrollment().conversationId())
                        || !ConversationRooms.INSTANCE.actionCurrent(player, offer.enrollment().conversationId(), offer.god())) return false;
                continue;
            }
            if (player == null || runtime.conversationGeneration(entry.getKey()).filter(entry.getValue()::equals).isEmpty()
                    || runtime.currentActionScope(player).filter(s -> s.sessionId().equals(offer.enrollment().conversationId())
                        && s.actingGodId().equals(offer.god())).isEmpty()) return false;
        }
        return true;
    }

    public void cancelForPlayer(MinecraftServer server, UUID player) {
        for (Pending offer : List.copyOf(pending.values())) if (!offer.roomAction() && offer.enrollment().audience().contains(player))
            cancel(server, offer, "대화 참가 상태가 바뀌어 수주 질문을 취소했습니다. 참여자를 다시 확인해주세요.");
    }
    /** Logout is an explicit all-conversation event; ordinary legacy toggles are not. */
    public void cancelAllForPlayer(MinecraftServer server, UUID player) {
        for (Pending offer : List.copyOf(pending.values())) if (offer.enrollment().audience().contains(player))
            cancel(server, offer, "접속 상태가 바뀌어 수주 질문을 취소했습니다. 참여자를 다시 확인해주세요.");
    }
    private void cancel(MinecraftServer server, Pending offer, String message) {
        pending.remove(offer.enrollment().offerId());
        for (UUID id : offer.enrollment().audience()) npc(server.getPlayerList().getPlayer(id), offer.god(), message);
    }
    public void clear() { pending.clear(); lastSettlementWarning.clear(); }

    public Optional<UUID> pendingOffer(UUID player) {
        return pending.values().stream().filter(p -> p.enrollment().audience().contains(player))
                .map(p -> p.enrollment().offerId()).findFirst();
    }

    /** Bounded facts for the current player's quests, not other players' private assignments. */
    public String contextFor(ServerPlayer player, ResourceLocation npc) {
        return contextFor(player, npc, null);
    }

    /** Pending recruitment from another private/public room is not context for this room. */
    public String contextForRoom(ServerPlayer player, ResourceLocation npc, UUID sessionId) {
        if (!ConversationRooms.INSTANCE.actionCurrent(player, sessionId, npc)) return "";
        return contextFor(player, npc, sessionId);
    }

    private String contextFor(ServerPlayer player, ResourceLocation npc, UUID roomSessionId) {
        requireThread(player.server);
        StringBuilder text = new StringBuilder("\n[QUEST_PARTICIPATION_SERVER_STATE]\n");
        for (Pending offer : pending.values()) if ((roomSessionId == null ? !offer.roomAction()
                : offer.roomAction() && offer.enrollment().conversationId().equals(roomSessionId)) && offer.god().equals(npc)
                && offer.enrollment().audience().contains(player.getUUID()))
            text.append("quest=").append(offer.quest()).append(" status=WAITING_FOR_ANSWERS; waiting=")
                    .append(offer.enrollment().waitingFor().size()).append("; NOT_ASSIGNED_YET\n");
        int shown = 0;
        var runs = MythicQuestState.get(player.server).participationRuns();
        for (int i = runs.size() - 1; i >= 0 && shown < 8; i--) {
            var run = runs.get(i);
            var binding = FtbQuestBindingManager.INSTANCE.find(ResourceLocation.parse(run.snapshot().questId())).orElse(null);
            if (!run.participants().contains(player.getUUID()) || binding == null
                    || !binding.acceptsCompletionNpc(ResourceLocation.parse(run.snapshot().giverId()), npc)) continue;
            shown++;
            text.append("quest=").append(binding.questId()).append(" type=").append(run.snapshot().type())
                    .append(" status=").append(run.snapshot().closed() ? (run.winners().contains(player.getUUID()) ? "COMPLETED" : "CLOSED_NOT_COMPLETED")
                            : run.snapshot().submissions().containsKey(player.getUUID()) ? "SUBMITTED_WAITING" : "ACTIVE")
                    .append(" personal_progress=").append(run.total(player.getUUID())).append('/').append(run.maximum())
                    .append(" accepted_count=").append(run.participants().size())
                    .append(" submitted_count=").append(run.snapshot().submissions().size());
            var submission = run.snapshot().submissions().get(player.getUUID());
            if (submission != null) text.append(" verified_score=").append(submission.score())
                    .append(" submission_elapsed_seconds=").append((submission.submittedAt() - run.snapshot().startedAt()) / 20);
            if (run.snapshot().ranking() != null) {
                text.append(" end_mode=").append(run.snapshot().ranking().endMode());
                if (run.snapshot().closed() && submission != null)
                    text.append(" final_rank=").append(run.snapshot().ranking().rank(player.getUUID(), run.scores()));
            }
            text.append('\n');
        }
        return text.append("Never treat a consent question or a submitted entry as completed. Do not invent other players' scores or rewards.\n").toString();
    }

    public void accept(MinecraftServer server, GameplayObservation<?> observation) {
        requireThread(server);
        if (observation.type().equals(com.sande.mythictrpg.gameplay.observation.GameplayObservationTypes.BLOCK_BROKEN)) return;
        String observedType = observation.type().equals(com.sande.mythictrpg.gameplay.observation.GameplayObservationTypes.ELIGIBLE_BLOCK_MINED)
                ? "mythictrpg:block_broken" : observation.type().id().toString();
        var state = MythicQuestState.get(server);
        if (!state.isWritable()) return;
        for (QuestParticipationRun run : state.participationRuns()) {
            if (!run.accepting(observation.initiatingPlayerId(), observation.gameTime())) continue;
            if (FtbQuestBindingManager.INSTANCE.find(ResourceLocation.parse(run.snapshot().questId()))
                    .filter(b -> matches(b, run)).isEmpty()) continue;
            boolean changed = false;
            for (int i = 0; i < run.snapshot().objectives().size(); i++) {
                var goal = run.snapshot().objectives().get(i);
                if (goal.kind() == QuestParticipationPolicy.ObjectiveKind.OBSERVATION
                        && goal.observation().equals(observedType)
                        && observation.subjectId().map(Object::toString).filter(goal.subject()::equals).isPresent())
                    changed |= run.advance(observation.initiatingPlayerId(), i, 1, observation.gameTime());
            }
            if (!changed) continue;
            state.setDirty();
            ServerPlayer player = server.getPlayerList().getPlayer(observation.initiatingPlayerId());
            if (player != null) update(player, run);
        }
    }

    public QuestOperationResult confirm(ServerPlayer player, ResourceLocation quest) {
        var scope = AiConversationRuntimeService.INSTANCE.currentActionScope(player).orElse(null);
        return confirm(player, quest, scope);
    }

    /** The command supplies an explicit game-issued room scope; selected private rooms are not ambient authority. */
    public QuestOperationResult confirmRoom(ServerPlayer player, ResourceLocation quest, AiActionScope roomScope) {
        requireThread(player.server);
        if (roomScope == null || !ConversationRooms.INSTANCE.actionCurrent(player, roomScope.sessionId(), roomScope.actingGodId())) {
            return result(QuestOperationResult.Status.WRONG_INTERACTION_MODE, quest, "The selected quest confirmation room is no longer current");
        }
        return confirm(player, quest, roomScope);
    }

    private QuestOperationResult confirm(ServerPlayer player, ResourceLocation quest, AiActionScope scope) {
        var binding = FtbQuestBindingManager.INSTANCE.find(quest).orElse(null);
        if (scope == null || binding == null || binding.participation().isEmpty()
                || binding.evaluationPolicy().isPresent() || binding.completionMode() != QuestCompletionMode.PLAYER_RETURN_TO_NPC)
            return result(QuestOperationResult.Status.WRONG_INTERACTION_MODE, quest,
                    "아이템/행동형 귀환 퀘스트만 대화 중 확인할 수 있습니다. 평가형은 서버 평가가 필요합니다.");
        return submit(player, binding, scope.actingGodId(), 0);
    }

    /** Explicit inventory consumption; never assumes an AI assertion is a submitted item. */
    public int submitItems(ServerPlayer player, ResourceLocation quest) {
        requireThread(player.server);
        if (!MythicQuestState.get(player.server).isWritable()) return 0;
        var run = MythicQuestState.get(player.server).participationRun(quest).orElse(null);
        var binding = FtbQuestBindingManager.INSTANCE.find(quest).orElse(null);
        long now = player.server.overworld().getGameTime();
        if (run == null || binding == null || !matches(binding, run) || !run.accepting(player.getUUID(), now)) return 0;
        int consumed = consumeItems(player, run, now);
        if (consumed > 0) QuestReminderState.get(player.server).observe(quest, player.getUUID(), now, true);
        MythicQuestState.get(player.server).setDirty();
        update(player, run);
        return consumed;
    }

    static int consumeItems(ServerPlayer player, QuestParticipationRun run, long now) {
        requireThread(player.server);
        if (!run.accepting(player.getUUID(), now)) return 0;
        int consumed = 0;
        for (int i = 0; i < run.snapshot().objectives().size(); i++) {
            var goal = run.snapshot().objectives().get(i);
            if (goal.kind() != QuestParticipationPolicy.ObjectiveKind.ITEM_SUBMISSION
                    && goal.kind() != QuestParticipationPolicy.ObjectiveKind.ITEM_DONATION) continue;
            int missing = goal.maximumProgress() - run.progress(player.getUUID(), i);
            for (int slot = 0; slot < player.getInventory().getContainerSize() && missing > 0; slot++) {
                var stack = player.getInventory().getItem(slot);
                if (stack.isEmpty() || !net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(goal.subject())) continue;
                int count = Math.min(missing, stack.getCount());
                if (run.advance(player.getUUID(), i, count, now)) {
                    stack.shrink(count); consumed += count; missing -= count;
                }
            }
        }
        player.getInventory().setChanged();
        return consumed;
    }

    private void update(ServerPlayer player, QuestParticipationRun run) {
        var binding = FtbQuestBindingManager.INSTANCE.find(ResourceLocation.parse(run.snapshot().questId())).orElse(null);
        if (binding == null) return;
        FtbQuestAdapter.INSTANCE.syncParticipation(player, binding, run);
        if (binding.completionMode() == QuestCompletionMode.AUTO && run.ready(player.getUUID()))
            submit(player, binding, ResourceLocation.parse(run.snapshot().giverId()), run.total(player.getUUID()));
    }

    public QuestOperationResult submit(ServerPlayer player, FtbQuestBinding binding, ResourceLocation npc, int score) {
        requireThread(player.server);
        var state = MythicQuestState.get(player.server);
        var run = state.participationRun(binding.questId()).orElse(null);
        long now = player.server.overworld().getGameTime();
        if (!state.isWritable() || run == null || !matches(binding, run)
                || !binding.acceptsCompletionNpc(ResourceLocation.parse(run.snapshot().giverId()), npc))
            return result(QuestOperationResult.Status.NOT_ASSIGNED, binding.questId(), "유효한 수주 또는 확인 NPC가 없습니다.");
        if (!run.accepting(player.getUUID(), now))
            return result(QuestOperationResult.Status.PARTICIPATION_CLOSED, binding.questId(), "참가자가 아니거나 이미 제출/마감되었습니다.");
        int verifiedScore = binding.evaluationPolicy().isEmpty() ? run.score(player.getUUID()) : score;
        if (binding.evaluationPolicy().isPresent() && !binding.evaluationPolicy().orElseThrow().passes(verifiedScore))
            return result(QuestOperationResult.Status.OBJECTIVES_NOT_READY, binding.questId(), "서버 평가 통과가 필요합니다.");
        if (!run.submit(player.getUUID(), verifiedScore, npc.toString(), now))
            return result(QuestOperationResult.Status.OBJECTIVES_NOT_READY, binding.questId(), "목표 부족, 이미 제출, 또는 제출 마감입니다.");
        state.setDirty();
        com.sande.mythictrpg.gameplay.ledger.detail.ImportantEvents.transition(player.server,player.getUUID(),binding.questId(),
                "SUBMITTED",run.snapshot().runId().toString(),Instant.now(),run.snapshot().runId());
        settle(player.server, binding, run);
        if (!run.snapshot().closed()) npc(player, npc, run.shouldClose(now)
                ? "[제출이 확인되었습니다] 보상 설정 확인 후 완료 처리됩니다."
                : "[제출이 확인되었습니다] 다른 참여자의 제출 또는 마감 시점을 기다립니다.");
        return result(run.snapshot().closed() ? QuestOperationResult.Status.COMPLETED : QuestOperationResult.Status.SUBMITTED,
                binding.questId(), "");
    }

    public QuestOperationResult evaluate(ServerPlayer player, FtbQuestBinding binding, ResourceLocation npc, int score) {
        requireThread(player.server);
        var run = MythicQuestState.get(player.server).participationRun(binding.questId()).orElse(null);
        if (run == null || !matches(binding, run) || !MythicQuestState.get(player.server).isWritable()
                || !run.accepting(player.getUUID(), player.server.overworld().getGameTime()))
            return result(QuestOperationResult.Status.NOT_ASSIGNED, binding.questId(), "평가 제출이 불가능합니다.");
        for (int i = 0; i < run.snapshot().objectives().size(); i++) {
            if (run.snapshot().objectives().get(i).kind() == QuestParticipationPolicy.ObjectiveKind.EVALUATION)
                run.advance(player.getUUID(), i, 1, player.server.overworld().getGameTime());
        }
        MythicQuestState.get(player.server).setDirty();
        return submit(player, binding, npc, score);
    }

    public void tick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        for (Pending offer : List.copyOf(pending.values())) if (!current(server, offer))
            cancel(server, offer, "대화 또는 퀘스트 설정이 바뀌어 질문이 취소되었습니다.");
        long now = server.overworld().getGameTime();
        if (now % 20 != 0) return;
        for (var run : MythicQuestState.get(server).participationRuns()) {
            var binding = FtbQuestBindingManager.INSTANCE.find(ResourceLocation.parse(run.snapshot().questId())).orElse(null);
            if (binding != null && binding.participation().isPresent()) settle(server, binding, run);
        }
    }

    private void settle(MinecraftServer server, FtbQuestBinding binding, QuestParticipationRun run) {
        var state = MythicQuestState.get(server);
        long now = server.overworld().getGameTime();
        if (!state.isWritable() || run.snapshot().closed() || !run.shouldClose(now)) return;
        ResourceLocation god = ResourceLocation.parse(run.snapshot().giverId());
        if (!matches(binding, run)) { warnSettlement(run, now, "Participation configuration changed; restore it before settling"); return; }
        String problem = configurationProblem(binding, god);
        if (!problem.isEmpty()) { warnSettlement(run, now, problem); return; }
        Map<UUID, ResolvedQuestReward> payouts = new LinkedHashMap<>();
        for (UUID id : run.snapshot().submissions().keySet()) {
            int selectedTier = binding.evaluationPolicy().map(p -> p.rewardTierForScore(run.scores().get(id))).orElse(0);
            if (run.snapshot().ranking() != null) {
                var ranking = run.snapshot().ranking();
                selectedTier = ranking.tier(ranking.rank(id, run.scores()), run.scores().get(id));
                if (selectedTier == 0) continue; // Authored rank/score cut-off: no payout for this submission.
            }
            var resolved = resolveReward(binding, god, selectedTier);
            if (resolved.status() != QuestRewardResolver.Status.RESOLVED) {
                warnSettlement(run, now, resolved.reason()); return;
            }
            payouts.put(id, resolved.reward().orElseThrow());
        }
        var receipt = RewardClaimService.INSTANCE.queueBatch(server, god, binding.questId(), payouts);
        if (!receipt.succeeded()) { warnSettlement(run, now, receipt.reason()); return; }
        // No callbacks or delivery until all receipts are frozen. The server thread serializes contenders.
        run.close(now);
        UUID representative = run.winners().stream().sorted().findFirst()
                .orElseGet(() -> run.participants().stream().sorted().findFirst().orElseThrow());
        ResourceLocation completionNpc = Optional.ofNullable(run.snapshot().submissions().get(representative))
                .map(s -> ResourceLocation.parse(s.npcId())).orElse(god);
        Optional<QuestCompletionRecord> committed;
        if (run.winners().isEmpty()) {
            state.closeUnsubmittedRun(binding.questId()); committed = Optional.empty();
        } else committed = state.tryComplete(binding.questId(), representative, Optional.of(completionNpc), Instant.now());
        if (committed.isPresent() && !run.winners().isEmpty()) {
            MythicWorldState.get(server).applyQuestClearProgress(binding.progressTrackId(), binding.progressOnClear());
            com.sande.mythictrpg.story.runtime.StoryEventService.INSTANCE.submit(server,
                    com.sande.mythictrpg.story.signal.StorySignal.of(
                        com.sande.mythictrpg.story.signal.StorySignalTypes.QUEST_COMPLETED,
                        Optional.of(representative), Optional.of(binding.questId()), now));
        }
        for (UUID id : run.winners()) run.awarded(id);
        state.setDirty();
        lastSettlementWarning.remove(run.snapshot().runId());
        for (UUID id : run.participants()) {
            QuestReminderState.get(server).remove(binding.questId(), id);
            // All authoritative participants, including offline completions and ranking non-recipients.
            com.sande.mythictrpg.gameplay.ledger.detail.ImportantEvents.transition(server,id,binding.questId(),
                    run.winners().contains(id)?"COMPLETED":run.snapshot().type()==QuestParticipationType.COMPETITIVE?"LOST_COMPETITION":"CLOSED_WITHOUT_SUBMISSION",
                    run.snapshot().runId().toString(),Instant.now(),run.snapshot().runId());
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player != null) {
                FtbQuestAdapter.INSTANCE.syncParticipation(player, binding, run);
                if (run.winners().contains(id)) {
                    RewardClaimService.INSTANCE.deliverQueued(player);
                    ResourceLocation verifier = ResourceLocation.parse(run.snapshot().submissions().get(id).npcId());
                    boolean narrated = false;
                    try { narrated = AiConversationEngineRouter.INSTANCE.onQuestCompleted(new AiQuestCompletionContext(id, verifier, binding.questId())); }
                    catch (RuntimeException failure) {
                        com.sande.mythictrpg.MythicTrpg.LOGGER.warn("Quest completion narration failed for {}", binding.questId(), failure);
                    }
                    if (!narrated) npc(player, verifier, "[퀘스트가 완료되었습니다]");
                } else npc(player, god, "[퀘스트가 종료되었습니다] " + (run.snapshot().type() == QuestParticipationType.COMPETITIVE
                        ? "다른 참가자가 먼저 완료했습니다." : "제출 기한이 지났습니다."));
            }
        }
    }

    private static boolean matches(FtbQuestBinding binding, QuestParticipationRun run) {
        return binding.participation().filter(p -> p.type() == run.snapshot().type()
                && p.objectives().equals(run.snapshot().objectives())
                && p.ranking().equals(Optional.ofNullable(run.snapshot().ranking()))).isPresent();
    }

    private static QuestRewardResolver.Resolution resolveReward(FtbQuestBinding binding, ResourceLocation god, int tier) {
        var table = binding.evaluationPolicy().flatMap(QuestEvaluationPolicy::rewardTableId)
                .or(() -> NpcRewardTableManager.INSTANCE.defaultForGod(god).map(NpcRewardTable::id));
        return QuestRewardResolver.resolve(god, binding.rewardPolicy(), tier > 0 && table.isPresent()
                ? Optional.of(new QuestRewardResolver.TableTier(table.orElseThrow(), tier)) : Optional.empty());
    }

    private static String configurationProblem(FtbQuestBinding binding, ResourceLocation god) {
        var policy = binding.participation().orElseThrow();
        for (var goal : policy.objectives()) {
            if (goal.kind() == QuestParticipationPolicy.ObjectiveKind.EVALUATION) continue;
            ResourceLocation subject = ResourceLocation.parse(goal.subject());
            boolean exists = goal.kind() == QuestParticipationPolicy.ObjectiveKind.ITEM_SUBMISSION
                    || goal.kind() == QuestParticipationPolicy.ObjectiveKind.ITEM_DONATION
                    ? net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(subject)
                    : goal.observation().equals("mythictrpg:block_broken") || goal.observation().equals("mythictrpg:mature_crop_harvested")
                        ? net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(subject)
                        : net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.containsKey(subject);
            if (!exists) return "등록되지 않은 목표: " + subject;
        }
        Set<Integer> tiers = new LinkedHashSet<>();
        if (policy.ranking().isPresent()) policy.ranking().orElseThrow().rewards().forEach(b -> tiers.add(b.rewardTier()));
        else if (binding.evaluationPolicy().isPresent()) {
            var evaluation = binding.evaluationPolicy().orElseThrow();
            if (evaluation.maximumRewardTier() > 100) return "평가 보상 단계는 100 이하여야 합니다.";
            for (int i = evaluation.minimumRewardTier(); i <= evaluation.maximumRewardTier(); i++) tiers.add(i);
        } else tiers.add(0);
        for (int tier : tiers) {
            if (policy.ranking().isPresent() && binding.evaluationPolicy().filter(p -> tier < p.minimumRewardTier()
                    || tier > p.maximumRewardTier()).isPresent()) return "랭킹 보상이 퀘스트의 허용 보상 범위를 벗어났습니다.";
            var resolved = resolveReward(binding, god, tier);
            if (resolved.status() != QuestRewardResolver.Status.RESOLVED) return resolved.reason();
        }
        return "";
    }

    private void warnSettlement(QuestParticipationRun run, long now, String problem) {
        long previous = lastSettlementWarning.getOrDefault(run.snapshot().runId(), Long.MIN_VALUE / 2);
        if (now - previous < 1200) return;
        lastSettlementWarning.put(run.snapshot().runId(), now);
        com.sande.mythictrpg.MythicTrpg.LOGGER.warn("Quest {} settlement waiting: {}", run.snapshot().questId(), problem);
    }

    private static void npc(ServerPlayer player, ResourceLocation god, String text) {
        if (player != null) DialoguePresentationService.INSTANCE.sendTo(player, GodDialogueRequest.literal(god, text));
    }
    private static QuestOperationResult result(QuestOperationResult.Status status, ResourceLocation quest, String reason) {
        return new QuestOperationResult(status, quest, reason);
    }
    private static void requireThread(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Quest participation requires server thread");
    }
}
