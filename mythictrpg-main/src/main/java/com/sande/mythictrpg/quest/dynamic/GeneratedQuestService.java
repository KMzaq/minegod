package com.sande.mythictrpg.quest.dynamic;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.data.world.MythicWorldState;
import com.sande.mythictrpg.gameplay.observation.GameplayObservation;
import com.sande.mythictrpg.gameplay.observation.GameplayObservationSink;
import com.sande.mythictrpg.quest.GeneratedQuestFtbDisplay;
import com.sande.mythictrpg.quest.reward.NpcRewardGrantService;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.List;
import java.util.UUID;

/** Authoritative generated SIDE quest lifecycle, independent of FTB completion state. */
public final class GeneratedQuestService implements GameplayObservationSink {
    public static final GeneratedQuestService INSTANCE = new GeneratedQuestService();
    private static final long EXPIRY_CHECK_INTERVAL_TICKS = 20L;

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
        GeneratedQuestState state = GeneratedQuestState.get(player.server);
        state.create(instance);
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
        return GeneratedQuestCreationResult.created(instance);
    }

    @Override
    public void accept(MinecraftServer server, GameplayObservation<?> observation) {
        requireServerThread(server);
        GeneratedQuestState state = GeneratedQuestState.get(server);
        GeneratedQuestInstance current = state.active(observation.initiatingPlayerId()).orElse(null);
        if (current == null) {
            return;
        }
        ServerPlayer player = server.getPlayerList().getPlayer(current.playerId());
        if (player == null) {
            return;
        }
        if (observation.gameTime() >= current.expiresGameTime()) {
            expire(player, current, state);
            return;
        }
        ResourceLocation subject = observation.subjectId().orElse(null);
        if (subject == null || !current.matches(observation.type().id(), subject)) {
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
        complete(player, progressed, state);
    }

    public void onServerTickPost(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        requireServerThread(server);
        long now = server.overworld().getGameTime();
        if (now % EXPIRY_CHECK_INTERVAL_TICKS != 0L) {
            return;
        }
        GeneratedQuestState state = GeneratedQuestState.get(server);
        for (GeneratedQuestInstance instance : state.activeQuests()) {
            if (now < instance.expiresGameTime()) {
                continue;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(instance.playerId());
            if (player != null) {
                expire(player, instance, state);
            }
        }
    }

    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        requireServerThread(player.server);
        GeneratedQuestState state = GeneratedQuestState.get(player.server);
        GeneratedQuestInstance instance = state.active(player.getUUID()).orElse(null);
        if (instance == null) {
            return;
        }
        if (player.server.overworld().getGameTime() >= instance.expiresGameTime()) {
            expire(player, instance, state);
            return;
        }
        if (!GeneratedQuestFtbDisplay.restore(player, instance)) {
            GeneratedQuestFtbDisplay.Mirror mirror = GeneratedQuestFtbDisplay.create(player, instance).orElse(null);
            if (mirror != null) {
                state.replace(instance.withFtbMirror(mirror.questId(), mirror.markerQuestId(), mirror.taskId()));
            }
        }
    }

    private static void complete(ServerPlayer player, GeneratedQuestInstance instance,
            GeneratedQuestState state) {
        NpcRewardGrantService.Result reward = NpcRewardGrantService.grant(player, instance.godId(),
                instance.rewardTableId(), instance.rewardTier());
        if (!reward.granted()) {
            MythicTrpg.LOGGER.error("Generated quest {} reached completion but reward grant failed: {}",
                    instance.instanceId(), reward.reason());
            player.sendSystemMessage(Component.literal("[즉석 의뢰] 목표는 달성했지만 보상 지급에 실패했습니다. "
                    + "관리자에게 알려주세요.").withStyle(ChatFormatting.RED));
            return;
        }
        state.remove(instance.playerId(), instance.instanceId());
        player.sendSystemMessage(Component.literal("[즉석 의뢰 완료] 보상: "
                + String.join(", ", reward.rewards())).withStyle(ChatFormatting.GREEN));
        MythicTrpg.LOGGER.info("Completed generated SIDE quest {} for {} with reward tier {}",
                instance.instanceId(), instance.playerId(), instance.rewardTier());
    }

    private static void expire(ServerPlayer player, GeneratedQuestInstance instance,
            GeneratedQuestState state) {
        if (!state.remove(instance.playerId(), instance.instanceId())) {
            return;
        }
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
}
