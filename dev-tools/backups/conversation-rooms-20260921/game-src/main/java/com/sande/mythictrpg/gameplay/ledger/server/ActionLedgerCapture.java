package com.sande.mythictrpg.gameplay.ledger.server;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.gameplay.ledger.ActionRecord;
import com.sande.mythictrpg.gameplay.observation.GameplayObservationAdapters;
import com.sande.mythictrpg.gameplay.observation.MatureCropHarvestClassifier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import java.util.Map;
import java.util.UUID;

/** Read-only success hooks. Legacy event publication/coalescing/counters are deliberately untouched. */
public final class ActionLedgerCapture {
    private ActionLedgerCapture() {}
    public static void cropRemoved(ServerLevel level, ServerPlayer player, BlockPos pos, BlockState state, boolean removed) {
        if (!removed) return;
        try {
            var crop = MatureCropHarvestClassifier.classify(state);
            if (crop.isEmpty()) return;
            var runtime = ActionLedgerService.current(level.getServer());
            if (runtime == null || !runtime.acceptsCapture() || !routineNeeded(level)) return;
            publish(runtime, player, level, pos,
                    new ActionRecord.Subject("BLOCK", crop.orElseThrow().toString(), null), ActionRecord.Type.MATURE_CROP_REMOVED,
                    "mythictrpg:crop_remove_commit", Map.of("quantity", "1", "result", "block_removed_not_item_acquisition",
                            "gameMode", player.isCreative() ? "CREATIVE" : "SURVIVAL_OR_ADVENTURE"));
        } catch (RuntimeException failure) { failed(level, failure); }
    }
    public static void deathCommitted(LivingEntity victim, DamageSource source) {
        if (!(victim.level() instanceof ServerLevel level) || victim instanceof Player) return;
        try {
            var responsible = GameplayObservationAdapters.playerResponsibleForKill(source);
            if (responsible.isEmpty()) return;
            com.sande.mythictrpg.gameplay.ledger.detail.ImportantEvents.namedDeath(responsible.orElseThrow(),victim);
            var runtime = ActionLedgerService.current(level.getServer());
            if (runtime == null || !runtime.acceptsCapture() || !routineNeeded(level)) return;
            publish(runtime, responsible.orElseThrow(), level,
                    victim.blockPosition(), new ActionRecord.Subject("ENTITY", BuiltInRegistries.ENTITY_TYPE.getKey(victim.getType()).toString(),
                            victim.getUUID()), ActionRecord.Type.ENTITY_KILLED, "mythictrpg:living_death_commit",
                    Map.of("result", "death_committed_not_loot_awarded"));
        } catch (RuntimeException failure) { failed(level, failure); }
    }
    private static void publish(ActionLedgerService.Runtime runtime, ServerPlayer actor, ServerLevel level,
                                BlockPos pos, ActionRecord.Subject subject, ActionRecord.Type type,
                                String source, Map<String, String> payload) {
        var draft = new ActionRecord.Draft(UUID.randomUUID(), runtime.captureSession(), runtime.nextCaptureOrder(), source, 1,
                actor.getUUID(), subject, System.currentTimeMillis(), level.getGameTime(), level.getDayTime(),
                level.dimension().location().toString(), new ActionRecord.Position(pos.getX(), pos.getY(), pos.getZ()),
                type, "COMPLETED", payload, "mythictrpg:admin_only_unprojected");
        com.sande.mythictrpg.gameplay.watch.GodWatchRuntime.activity(level.getServer(),draft);
        if(runtime.detailSettings().routineDiagnostics()) {
            var receipt = runtime.ledger().submit(draft);
            com.sande.mythictrpg.gameplay.watch.GodWatchRuntime.observed(level.getServer(), draft, receipt);
        }
    }
    private static boolean routineNeeded(ServerLevel level) {
        var r=ActionLedgerService.current(level.getServer());
        return r.detailSettings().routineDiagnostics() || com.sande.mythictrpg.gameplay.watch.GodWatchRuntime.activityEnabled(level.getServer());
    }
    public static void blockRemoved(ServerPlayer player,BlockPos pos,BlockState state,boolean success) {
        if(!success)return;
        var level=player.serverLevel();var r=ActionLedgerService.current(player.server);
        if(r==null||!r.acceptsCapture()||!com.sande.mythictrpg.gameplay.watch.GodWatchRuntime.activityEnabled(player.server))return;
        var draft=new ActionRecord.Draft(UUID.randomUUID(),r.captureSession(),r.nextCaptureOrder(),"mythictrpg:detail/block_removed",1,
                player.getUUID(),new ActionRecord.Subject("BLOCK",BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(),null),
                System.currentTimeMillis(),level.getGameTime(),level.getDayTime(),level.dimension().location().toString(),
                new ActionRecord.Position(pos.getX(),pos.getY(),pos.getZ()),ActionRecord.Type.BLOCK_REMOVED,"COMPLETED",Map.of(),"mythictrpg:admin_only_unprojected");
        com.sande.mythictrpg.gameplay.watch.GodWatchRuntime.activity(player.server,draft);
    }
    private static void failed(ServerLevel level, RuntimeException failure) {
        if (level.getServer().isSameThread()) {
            var runtime = ActionLedgerService.current(level.getServer());
            if (runtime != null && runtime.ledger() != null) runtime.ledger().gap("CAPTURE_FAILED");
        }
        MythicTrpg.LOGGER.error("Action ledger capture failed; existing game result is unchanged", failure);
    }
}
