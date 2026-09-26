package com.sande.mythictrpg.gameplay.observation;

import com.sande.mythictrpg.data.player.ItemHistoryRecordResult;
import com.sande.mythictrpg.data.player.PlayerMythDataService;
import com.sande.mythictrpg.gameplay.metric.GameplayMetricKey;
import com.sande.mythictrpg.gameplay.metric.GameplayMetricTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.entity.living.BabyEntitySpawnEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class GameplayObservationAdapters {
    private GameplayObservationAdapters() {
    }

    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.isCanceled() || !(event.getPlayer() instanceof ServerPlayer player)
                || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        var payload = new BlockBrokenPayload(
                BuiltInRegistries.BLOCK.getKey(event.getState().getBlock()),
                level.dimension().location(),
                event.getPos());
        emit(player, level, GameplayObservationTypes.BLOCK_BROKEN, payload);
    }
    public static void eligibleBlockMined(ServerPlayer player, ResourceLocation block, net.minecraft.core.BlockPos pos) {
        // No coalescing: two successful removals are two eligible credits, even within one tick.
        var occurrence = new GameplayObservation<>(GameplayObservationTypes.ELIGIBLE_BLOCK_MINED,player.getUUID(),
                player.level().getGameTime(),new BlockBrokenPayload(block,player.level().dimension().location(),pos));
        com.sande.mythictrpg.quest.dynamic.GeneratedQuestService.INSTANCE.accept(player.server,occurrence);
        com.sande.mythictrpg.quest.QuestParticipationService.INSTANCE.accept(player.server,occurrence);
    }

    public static void onMatureCropBreak(BlockEvent.BreakEvent event) {
        if (event.isCanceled() || !(event.getPlayer() instanceof ServerPlayer player)
                || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        MatureCropHarvestClassifier.classify(event.getState()).ifPresent(cropId -> {
            var aggregate = GameplayMetricKey.aggregate(GameplayMetricTypes.MATURE_CROP_HARVESTED);
            var subject = GameplayMetricKey.subject(GameplayMetricTypes.MATURE_CROP_HARVESTED, cropId);
            var result = PlayerMythDataService.get(player.server).incrementGameplayCounters(
                    player.getUUID(), Map.of(aggregate, 1L, subject, 1L));
            if (result.updated()) {
                emit(player, level, GameplayObservationTypes.MATURE_CROP_HARVESTED,
                        new MatureCropHarvestPayload(cropId));
            }
        });
    }

    public static void onBabyEntitySpawn(BabyEntitySpawnEvent event) {
        if (event.isCanceled() || !(event.getCausedByPlayer() instanceof ServerPlayer player)
                || !(event.getParentA().level() instanceof ServerLevel level) || event.getChild() == null) {
            return;
        }
        var payload = new AnimalBredPayload(
                BuiltInRegistries.ENTITY_TYPE.getKey(event.getParentA().getType()),
                BuiltInRegistries.ENTITY_TYPE.getKey(event.getParentB().getType()),
                BuiltInRegistries.ENTITY_TYPE.getKey(event.getChild().getType()));
        emit(player, level, GameplayObservationTypes.ANIMAL_BRED, payload);
    }

    public static void onLivingDeath(LivingDeathEvent event) {
        if (event.isCanceled() || !(event.getEntity().level() instanceof ServerLevel level)) {
            return;
        }
        LivingEntity victim = event.getEntity();
        if (victim instanceof ServerPlayer player) {
            emit(player, level, GameplayObservationTypes.PLAYER_DIED,
                    new PlayerDiedPayload(damageTypeId(event.getSource())));
            return;
        }
        playerResponsibleForKill(event.getSource()).ifPresent(player -> emit(player, level,
                GameplayObservationTypes.ENTITY_KILLED,
                new EntityKilledPayload(BuiltInRegistries.ENTITY_TYPE.getKey(victim.getType()),
                        level.dimension().location())));
    }

    public static void onItemHistoryRecorded(ServerPlayer player, ResourceLocation itemId,
            ItemHistoryRecordResult result) {
        if (result != ItemHistoryRecordResult.NEW_RECORD) {
            return;
        }
        emit(player, (ServerLevel) player.level(), GameplayObservationTypes.ITEM_FIRST_OBTAINED,
                new ItemFirstObtainedPayload(itemId));
    }

    public static Optional<ServerPlayer> playerResponsibleForKill(DamageSource source) {
        Entity direct = source.getDirectEntity();
        if (direct instanceof ServerPlayer player) {
            return Optional.of(player);
        }
        Entity causing = source.getEntity();
        if (causing instanceof ServerPlayer player) {
            return Optional.of(player);
        }
        if (direct instanceof Projectile projectile && projectile.getOwner() instanceof ServerPlayer player) {
            return Optional.of(player);
        }
        return Optional.empty();
    }

    private static Optional<ResourceLocation> damageTypeId(DamageSource source) {
        return source.typeHolder().unwrapKey().map(key -> key.location());
    }

    private static <P extends GameplayObservationPayload> void emit(ServerPlayer player, Level level,
            GameplayObservationType<P> type, P payload) {
        GameplayIngressService.INSTANCE.accept(player.server,
                new GameplayObservation<>(type, player.getUUID(), level.getGameTime(), payload));
    }
}
