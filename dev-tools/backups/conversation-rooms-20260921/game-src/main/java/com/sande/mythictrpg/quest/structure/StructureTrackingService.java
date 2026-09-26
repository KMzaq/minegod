package com.sande.mythictrpg.quest.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

import java.util.Comparator;

/** Captures only post-confirmation participant changes into the sparse ledger. */
public final class StructureTrackingService {
    public static final StructureTrackingService INSTANCE = new StructureTrackingService();
    private StructureTrackingService() {}

    public void onBlockPlaced(BlockEvent.EntityPlaceEvent event) {
        com.sande.mythictrpg.gameplay.metric.ProvenanceTracking.placement(event);
    }

    public void onBlockBroken(BlockEvent.BreakEvent event) {
        // A BreakEvent is cancellable and not proof of removal. The committed block-change hook
        // updates both existing construction ledgers after the actual world change instead.
    }

    public void onToolModified(BlockEvent.BlockToolModificationEvent event) {
        if (event.isSimulated() || event.getFinalState() == null
                || !(event.getLevel() instanceof ServerLevel level)
                || !(event.getPlayer() instanceof ServerPlayer player)) return;
        StructureEvaluationState.get(level.getServer()).recordBlock(level.dimension(), event.getPos(),
                player.getUUID(), PlacementSource.DERIVED, level.getGameTime());
        PlayerConstructionState.get(level.getServer()).recordBlock(level.dimension(), event.getPos(),
                player.getUUID(), PlacementSource.DERIVED, level.getGameTime());
    }

    /** Bucket placement has no placer on FluidPlaceBlockEvent, so capture the player intent here. */
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)
                || player.getItemInHand(event.getHand()).getItem() != Items.WATER_BUCKET) return;
        BlockPos target = event.getPos().relative(event.getFace());
        StructureEvaluationState.get(player.server).recordBlock(player.serverLevel().dimension(), target,
                player.getUUID(), PlacementSource.DERIVED, player.serverLevel().getGameTime());
        PlayerConstructionState.get(player.server).recordBlock(player.serverLevel().dimension(), target,
                player.getUUID(), PlacementSource.DERIVED, player.serverLevel().getGameTime());
    }

    public void onEntityJoined(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !isDecoration(event.getEntity())) return;
        Entity entity = event.getEntity();
        StructureEvaluationState state = StructureEvaluationState.get(level.getServer());
        state.builds().stream()
                .filter(build -> build.region().dimension().equals(level.dimension()))
                .filter(build -> build.region().contains(entity.blockPosition()))
                .forEach(build -> level.getServer().getPlayerList().getPlayers().stream()
                        .filter(player -> build.eligibleContributors().contains(player.getUUID()))
                        .filter(player -> player.distanceToSqr(entity) <= 36.0D)
                        .min(Comparator.comparingDouble(player -> player.distanceToSqr(entity)))
                        .ifPresent(player -> state.recordDecoration(build, entity.getUUID(),
                                player.getUUID(), level.getGameTime())));
        PlayerConstructionState freeState = PlayerConstructionState.get(level.getServer());
        freeState.structures().stream()
                .filter(build -> build.region().dimension().equals(level.dimension()))
                .filter(build -> build.region().contains(entity.blockPosition()))
                .forEach(build -> level.getServer().getPlayerList().getPlayers().stream()
                        .filter(player -> build.eligibleContributors().contains(player.getUUID()))
                        .filter(player -> player.distanceToSqr(entity) <= 36.0D)
                        .min(Comparator.comparingDouble(player -> player.distanceToSqr(entity)))
                        .ifPresent(player -> freeState.recordDecoration(build, entity.getUUID(),
                                player.getUUID(), level.getGameTime())));
    }

    private static boolean isDecoration(Entity entity) {
        return entity instanceof HangingEntity || entity instanceof ArmorStand;
    }
}
