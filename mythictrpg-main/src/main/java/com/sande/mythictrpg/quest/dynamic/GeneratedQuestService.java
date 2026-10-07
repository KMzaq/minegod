package com.sande.mythictrpg.quest.dynamic;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.data.world.MythicWorldState;
import com.sande.mythictrpg.gameplay.observation.GameplayObservation;
import com.sande.mythictrpg.gameplay.observation.GameplayObservationSink;
import com.sande.mythictrpg.quest.GeneratedQuestFtbDisplay;
import com.sande.mythictrpg.quest.QuestCompletionMode;
import com.sande.mythictrpg.quest.QuestContactLocation;
import com.sande.mythictrpg.quest.QuestContactService;
import com.sande.mythictrpg.quest.reward.NpcRewardGrantService;
import com.sande.mythictrpg.quest.reward.NpcRewardTableManager;
import com.sande.mythictrpg.quest.reward.RewardClaimService;
import com.sande.mythictrpg.quest.reward.ResolvedQuestReward;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.List;
import java.util.UUID;
import java.util.Optional;
import java.util.LinkedHashMap;
import java.util.Map;
import com.sande.mythictrpg.ai.action.AiActionScope;

/** Authoritative generated SIDE quest lifecycle, independent of FTB completion state. */
public final class GeneratedQuestService implements GameplayObservationSink {
    public static final GeneratedQuestService INSTANCE = new GeneratedQuestService();
    private static final long EXPIRY_CHECK_INTERVAL_TICKS = 20L;
    private static final Map<OutcomeKey, Outcome> outcomes = new LinkedHashMap<>();
    private static MinecraftServer outcomeServer;
    private record OutcomeKey(UUID player, AiActionScope scope) { }
    private record Outcome(UUID instanceId, ResourceLocation templateId, String status, long expires) { }

    private GeneratedQuestService() {
    }

    public GeneratedQuestValidation validate(ServerPlayer player, GeneratedQuestTemplate template) {
        requireServerThread(player.server);
        GeneratedQuestState state = GeneratedQuestState.get(player.server);
        if (!state.isWritable()) {
            return GeneratedQuestValidation.reject(state.rejectionReason()
                    .orElse("Generated quest state is read-only"));
        }
        if (state.active(player.getUUID()).isPresent()) {
            return GeneratedQuestValidation.reject("The player already has an active generated SIDE quest");
        }
        MythicWorldState world = MythicWorldState.get(player.server);
        if (world.isRejected()) {
            return GeneratedQuestValidation.reject("World progression data is unavailable");
        }
        int progress = world.questProgress(template.progressTrackId());
        if (!template.permitsWorldProgress(progress)) {
            return GeneratedQuestValidation.reject("The template does not match current world progression");
        }
        long gameTime = player.server.overworld().getGameTime();
        if (!state.cooldownReady(player.getUUID(), template.id(), gameTime, template.cooldownTicks())) {
            return GeneratedQuestValidation.reject("The generated quest template is on cooldown for this player");
        }
        int highestPossibleTier = Math.min(template.maximumRewardTier(),
                template.baseRewardTier() + template.catchUpMaximumBonus());
        for (int tier = template.baseRewardTier(); tier <= highestPossibleTier; tier++) {
            NpcRewardGrantService.Validation reward = NpcRewardGrantService.validate(
                    template.godId(), template.rewardTableId(), tier);
            if (!reward.allowed()) {
                return GeneratedQuestValidation.reject("Generated quest reward is invalid: " + reward.reason());
            }
        }
        return GeneratedQuestValidation.allow();
    }

    public GeneratedQuestCreationResult create(ServerPlayer player, GeneratedQuestTemplate template,
            String title, String summary) {
        requireServerThread(player.server);
        GeneratedQuestValidation validation = validate(player, template);
        if (!validation.allowed()) {
            return GeneratedQuestCreationResult.rejected(validation.reason());
        }
        CombatPowerAssessment assessment = CombatPowerService.INSTANCE.assess(player);
        int boundedBonus = assessment.status() == CombatPowerAssessment.Status.AVAILABLE
                ? Math.min(template.catchUpMaximumBonus(), assessment.catchUpRewardTierBonus()) : 0;
        int tier = Math.min(template.maximumRewardTier(), template.baseRewardTier() + boundedBonus);
        long now = player.server.overworld().getGameTime();
        GeneratedQuestInstance instance = new GeneratedQuestInstance(UUID.randomUUID(), template.id(),
                player.getUUID(), template.godId(), title, summary, template.observationTypeId(),
                template.subjectId(), template.requiredCount(), 0, template.rewardTableId(), tier,
                boundedBonus > 0, now, now + template.expiresAfterTicks(), 0L, 0L, 0L);
        instance = instance.withContactRules(template.completionMode(),
                Optional.of(QuestContactLocation.capture(player)), template.returnLocation());
        instance = instance.withFrozenRewards(NpcRewardTableManager.INSTANCE.find(template.rewardTableId())
                .orElseThrow().tier(tier).orElseThrow().rewards());
        var preflight = RewardClaimService.INSTANCE.preflight(player, rewardSource(instance),
                new ResolvedQuestReward("즉석 의뢰 보상", instance.frozenRewards().orElseThrow(), List.of()));
        if (!preflight.succeeded()) return GeneratedQuestCreationResult.rejected(preflight.reason());
        GeneratedQuestState state = GeneratedQuestState.get(player.server);
        state.create(instance);
        recordTransition(player,instance,"ASSIGNED");
        GeneratedQuestFtbDisplay.Mirror mirror = GeneratedQuestFtbDisplay.create(player, instance).orElse(null);
        if (mirror != null) {
            instance = instance.withFtbMirror(mirror.questId(), mirror.markerQuestId(), mirror.taskId());
            state.replace(instance);
        } else {
            MythicTrpg.LOGGER.warn("Generated quest {} is active without an FTB display mirror",
                    instance.instanceId());
        }
        player.sendSystemMessage(Component.literal("[즉석 의뢰] "
                + (instance.title().isBlank() ? instance.templateId() : instance.title()))
                .withStyle(ChatFormatting.GOLD));
        player.sendSystemMessage(Component.literal("목표: " + instance.subjectId() + " "
                + instance.requiredCount() + "회 / 보상 단계 " + instance.rewardTier())
                .withStyle(ChatFormatting.GRAY));
        if (instance.catchUpApplied()) {
            player.sendSystemMessage(Component.literal("전투력 격차 보정으로 보상 단계가 1 상승했습니다.")
                    .withStyle(ChatFormatting.AQUA));
        }
        if (instance.completionMode() != QuestCompletionMode.AUTO)
            player.sendSystemMessage(Component.literal("목표 달성 후 의뢰한 신을 만나 확인받아야 완료됩니다.")
                    .withStyle(ChatFormatting.GRAY));
        return GeneratedQuestCreationResult.created(instance);
    }

    @Override
    public void accept(MinecraftServer server, GameplayObservation<?> observation) {
        requireServerThread(server);
        GeneratedQuestState state = GeneratedQuestState.get(server);
        if (!state.isWritable()) return;
        GeneratedQuestInstance current = state.active(observation.initiatingPlayerId()).orElse(null);
        if (current == null) {
            return;
        }
        ServerPlayer player = server.getPlayerList().getPlayer(current.playerId());
        if (player == null) {
            return;
        }
        if (current.objectivesCompleted()) return; // Retry via tick/login, never count or emit the transition again.
        if (observation.gameTime() >= current.expiresGameTime()) {
            expire(player, current, state);
            return;
        }
        ResourceLocation subject = observation.subjectId().orElse(null);
        if (observation.type().equals(com.sande.mythictrpg.gameplay.observation.GameplayObservationTypes.BLOCK_BROKEN)) return;
        var observedType = observation.type().equals(com.sande.mythictrpg.gameplay.observation.GameplayObservationTypes.ELIGIBLE_BLOCK_MINED)
                ? com.sande.mythictrpg.gameplay.observation.GameplayObservationTypes.BLOCK_BROKEN.id() : observation.type().id();
        if (subject == null || !current.matches(observedType, subject)) {
            return;
        }
        GeneratedQuestInstance progressed = current.withProgress(current.progress() + 1);
        state.replace(progressed);
        GeneratedQuestFtbDisplay.sync(player, progressed);
        if (progressed.progress() < progressed.requiredCount()) {
            player.sendSystemMessage(Component.literal("[즉석 의뢰] " + progressed.progress()
                    + "/" + progressed.requiredCount()).withStyle(ChatFormatting.GRAY));
            return;
        }
        recordTransition(player, progressed, "OBJECTIVES_COMPLETED");
        if (progressed.completionMode() == QuestCompletionMode.AUTO) {
            approveAndComplete(player, progressed, state, true);
        } else {
            player.sendSystemMessage(Component.literal("[즉석 의뢰] 목표를 달성했습니다. 의뢰한 신의 확인을 기다립니다.")
                    .withStyle(ChatFormatting.YELLOW));
        }
    }

    /** Called only after the shared contact service has issued a current game-owned contact. */
    public void onNpcContact(ServerPlayer player, ResourceLocation godId) {
        requireServerThread(player.server);
        GeneratedQuestState state = GeneratedQuestState.get(player.server);
        if (!state.isWritable()) return;
        GeneratedQuestInstance instance = state.active(player.getUUID()).orElse(null);
        if (instance == null || !instance.godId().equals(godId) || !instance.objectivesCompleted()) return;
        var contactScope = QuestContactService.currentScope(player, godId);
        if (instance.completionApproved()) {
            complete(player, instance, state, true);
        } else if (currentContactPolicy(instance) && QuestContactService.canConfirm(player, godId, instance.origin(),
                instance.destination(), instance.completionMode())) {
            approveAndComplete(player, instance, state, true);
        }
        if (state.active(player.getUUID()).filter(q -> q.instanceId().equals(instance.instanceId())
                && !q.completionApproved()).isEmpty()) {
            contactScope.ifPresent(scope -> rememberOutcome(player, scope, instance,
                    state.active(player.getUUID()).isEmpty() ? "COMPLETED" : "CONFIRMED_REWARD_PENDING"));
        }
    }

    /** Uses the existing per-player quest context; final feedback is restricted to its original scope. */
    public String contextFor(ServerPlayer player, ResourceLocation godId, UUID roomSessionId) {
        requireServerThread(player.server);
        attachOutcomes(player.server);
        StringBuilder text = new StringBuilder();
        var instance = GeneratedQuestState.get(player.server).active(player.getUUID())
                .filter(q -> q.godId().equals(godId)).orElse(null);
        if (instance != null) {
            String status = instance.completionApproved() ? "CONFIRMED_REWARD_PENDING"
                    : instance.awaitingConfirmation() ? "AWAITING_NPC_CONFIRMATION" : "OBJECTIVES_IN_PROGRESS";
            text.append("\n[GENERATED_QUEST_SERVER_STATE]\n").append(new com.google.gson.Gson().toJson(Map.of(
                    "instance_id", instance.instanceId().toString(), "template_id", instance.templateId().toString(),
                    "status", status, "progress", instance.progress(), "required_count", instance.requiredCount(),
                    "completion_mode", instance.completionMode().name())))
                    .append("\nObjective completion alone is not NPC approval or reward delivery. Do not claim a visit, confirmation or payment unless this server state says it occurred.\n[/GENERATED_QUEST_SERVER_STATE]\n");
        }
        Optional<AiActionScope> scope = roomSessionId == null
                ? com.sande.mythictrpg.ai.server.AiConversationRuntimeService.INSTANCE.currentActionScope(player)
                    .filter(value -> value.actingGodId().equals(godId))
                : Optional.of(new AiActionScope(roomSessionId, godId));
        scope.filter(value -> scopeCurrent(player, value)).ifPresent(value -> {
            var outcome = outcomes.get(new OutcomeKey(player.getUUID(), value));
            if (outcome != null && player.server.overworld().getGameTime() < outcome.expires())
                text.append("\n[GENERATED_QUEST_LAST_RESULT]\n").append(new com.google.gson.Gson().toJson(Map.of(
                        "instance_id", outcome.instanceId().toString(), "template_id", outcome.templateId().toString(),
                        "status", outcome.status())))
                        .append("\nThis is the recorded result of this room's contact, not permission for a new reward or action.\n[/GENERATED_QUEST_LAST_RESULT]\n");
        });
        return text.toString();
    }

    public void clear() { outcomes.clear(); outcomeServer = null; }

    private static boolean scopeCurrent(ServerPlayer player, AiActionScope scope) {
        return com.sande.mythictrpg.ai.server.ConversationRooms.INSTANCE.actionCurrent(player, scope.sessionId(), scope.actingGodId())
                || !com.sande.mythictrpg.ai.server.ConversationRooms.enabled()
                    && com.sande.mythictrpg.ai.server.AiConversationRuntimeService.INSTANCE.currentActionScope(player).filter(scope::equals).isPresent();
    }

    private static void attachOutcomes(MinecraftServer server) {
        if (outcomeServer != server) { outcomes.clear(); outcomeServer = server; }
    }

    private static void rememberOutcome(ServerPlayer player, AiActionScope scope,
            GeneratedQuestInstance instance, String status) {
        attachOutcomes(player.server);
        if (!scopeCurrent(player, scope)) return;
        long now = player.server.overworld().getGameTime();
        outcomes.values().removeIf(outcome -> now >= outcome.expires());
        if (outcomes.size() >= 1024) outcomes.remove(outcomes.keySet().iterator().next());
        outcomes.put(new OutcomeKey(player.getUUID(), scope),
                new Outcome(instance.instanceId(), instance.templateId(), status, now + 1200));
    }

    private static boolean currentContactPolicy(GeneratedQuestInstance instance) {
        return GeneratedQuestTemplateManager.INSTANCE.find(instance.templateId(), instance.godId())
                .filter(template -> template.completionMode() == instance.completionMode()
                        && template.returnLocation().equals(instance.destination())).isPresent();
    }

    private static void approveAndComplete(ServerPlayer player, GeneratedQuestInstance instance,
            GeneratedQuestState state, boolean notifyFailure) {
        if (!instance.objectivesCompleted()) return;
        if (!instance.completionApproved()) {
            instance = instance.approveCompletion();
            state.replace(instance); // Record approval in the saved state before attempting any payout.
            GeneratedQuestFtbDisplay.sync(player, instance);
            recordTransition(player, instance, "COMPLETION_APPROVED");
        }
        complete(player, instance, state, notifyFailure);
    }

    public void onServerTickPost(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        requireServerThread(server);
        long now = server.overworld().getGameTime();
        if (now % EXPIRY_CHECK_INTERVAL_TICKS != 0L) {
            return;
        }
        GeneratedQuestState state = GeneratedQuestState.get(server);
        if (!state.isWritable()) return;
        for (GeneratedQuestInstance instance : state.activeQuests()) {
            if (!instance.objectivesCompleted() && now < instance.expiresGameTime()) {
                continue;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(instance.playerId());
            if (player != null) {
                if (instance.completionApproved()) complete(player, instance, state, false);
                else if (instance.objectivesCompleted() && instance.completionMode() == QuestCompletionMode.AUTO)
                    approveAndComplete(player, instance, state, false);
                else expire(player, instance, state);
            }
        }
    }

    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        requireServerThread(player.server);
        GeneratedQuestState state = GeneratedQuestState.get(player.server);
        if (!state.isWritable()) return;
        GeneratedQuestInstance instance = state.active(player.getUUID()).orElse(null);
        if (instance == null) {
            return;
        }
        if (!instance.objectivesCompleted() && player.server.overworld().getGameTime() >= instance.expiresGameTime()) {
            expire(player, instance, state);
            return;
        }
        if (!GeneratedQuestFtbDisplay.restore(player, instance)) {
            GeneratedQuestFtbDisplay.Mirror mirror = GeneratedQuestFtbDisplay.create(player, instance).orElse(null);
            if (mirror != null) {
                state.replace(instance.withFtbMirror(mirror.questId(), mirror.markerQuestId(), mirror.taskId()));
            }
        }
        instance = state.active(player.getUUID()).orElseThrow();
        if (instance.completionApproved()) complete(player, instance, state, true);
        else if (instance.objectivesCompleted() && instance.completionMode() == QuestCompletionMode.AUTO)
            approveAndComplete(player, instance, state, true);
        else if (instance.awaitingConfirmation())
            player.sendSystemMessage(Component.literal("[즉석 의뢰] 목표 달성 상태를 복원했습니다. 의뢰한 신의 확인이 필요합니다.")
                    .withStyle(ChatFormatting.YELLOW));
    }

    private static void complete(ServerPlayer player, GeneratedQuestInstance instance,
            GeneratedQuestState state, boolean notifyFailure) {
        if (!instance.objectivesCompleted() || !instance.completionApproved()) return;
        if (instance.frozenRewards().isEmpty()) {
            // Historical v1 did not save a reward snapshot. Preserve its table resolution, then freeze once.
            var validation = NpcRewardGrantService.validate(instance.godId(), instance.rewardTableId(), instance.rewardTier());
            if (!validation.allowed()) {
                if (notifyFailure) MythicTrpg.LOGGER.warn("Generated quest reward unavailable: {}", validation.reason());
                return;
            }
            instance = instance.withFrozenRewards(NpcRewardTableManager.INSTANCE.find(instance.rewardTableId())
                    .orElseThrow().tier(instance.rewardTier()).orElseThrow().rewards());
            state.replace(instance);
        }
        var bundle = instance.frozenRewards().orElseThrow();
        var source = rewardSource(instance);
        var reward = RewardClaimService.INSTANCE.issue(player, instance.godId(), source,
                new ResolvedQuestReward("즉석 의뢰 보상", bundle, List.of()));
        if (!reward.succeeded()) {
            if (notifyFailure) MythicTrpg.LOGGER.warn("Generated quest {} reached completion but reward grant failed: {}",
                    instance.instanceId(), reward.reason());
            if (notifyFailure) player.sendSystemMessage(Component.literal("[즉석 의뢰] 완료 확인을 받았습니다. "
                    + "보상은 수령 조건이 해결되면 다시 지급합니다. 문제가 지속되면 관리자에게 알려주세요.")
                    .withStyle(ChatFormatting.YELLOW));
            return;
        }
        state.remove(instance.playerId(), instance.instanceId());
        attachOutcomes(player.server);
        var completedInstance = instance;
        outcomes.replaceAll((key, value) -> key.player().equals(player.getUUID())
                && value.instanceId().equals(completedInstance.instanceId())
                ? new Outcome(value.instanceId(), value.templateId(), "COMPLETED", value.expires()) : value);
        recordTransition(player,instance,"COMPLETED");
        player.sendSystemMessage(Component.literal("[즉석 의뢰 완료] " + instance.title()).withStyle(ChatFormatting.GREEN));
        MythicTrpg.LOGGER.info("Completed generated SIDE quest {} for {} with reward tier {}",
                instance.instanceId(), instance.playerId(), instance.rewardTier());
    }

    private static void expire(ServerPlayer player, GeneratedQuestInstance instance,
            GeneratedQuestState state) {
        if (instance.objectivesCompleted()) return;
        if (!state.remove(instance.playerId(), instance.instanceId())) {
            return;
        }
        recordTransition(player,instance,"EXPIRED");
        GeneratedQuestFtbDisplay.hide(player, instance);
        player.sendSystemMessage(Component.literal("[즉석 의뢰 만료] "
                + (instance.title().isBlank() ? instance.templateId() : instance.title()))
                .withStyle(ChatFormatting.DARK_GRAY));
    }

    private static void requireServerThread(MinecraftServer server) {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Generated quests may only run on the server thread");
        }
    }
    private static ResourceLocation rewardSource(GeneratedQuestInstance instance) {
        return ResourceLocation.fromNamespaceAndPath("mythictrpg", "generated/" + instance.instanceId());
    }
    private static void recordTransition(ServerPlayer player, GeneratedQuestInstance instance, String transition) {
        com.sande.mythictrpg.gameplay.ledger.detail.ImportantEvents.transition(player.server,player.getUUID(),instance.templateId(),
                transition,instance.instanceId().toString(),java.time.Instant.now(),instance.instanceId());
    }
}
