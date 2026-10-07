package com.sande.mythictrpg.raid;

import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.living.LivingExperienceDropEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.level.PistonEvent;

import java.util.UUID;

/** Protects active authored arenas without editing persistent claims or forced chunks. */
@EventBusSubscriber(modid = MythicTrpg.MOD_ID)
public final class RaidProtection {
    private RaidProtection() { }

    @SubscribeEvent public static void damage(LivingIncomingDamageEvent event) {
        LivingEntity victim = event.getEntity();
        if (!(victim.level() instanceof ServerLevel level)) return;
        UUID victimRun = RaidRuntime.raidTag(victim);
        Entity attacker = event.getSource().getEntity();
        UUID attackerRun = attacker == null ? null : RaidRuntime.raidTag(attacker);
        RaidState state = RaidState.get(level.getServer());
        if (victimRun != null) {
            RaidState.Attempt attempt = state.find(victimRun).orElse(null);
            if (attempt != null && attempt.status == RaidState.Status.ACTIVE) {
                if (!(attacker instanceof ServerPlayer player) || !eligibleAttacker(attempt, player)) event.setCanceled(true);
            } else event.setCanceled(true);
            return;
        }
        if (attackerRun != null) {
            RaidState.Attempt attempt = state.find(attackerRun).orElse(null);
            if (attempt == null || attempt.status != RaidState.Status.ACTIVE
                    || !(victim instanceof ServerPlayer player) || !eligibleAttacker(attempt, player))
                event.setCanceled(true);
            return;
        }
        if (victim instanceof ServerPlayer player) {
            RaidState.Attempt attempt = state.membership(player.getUUID()).orElse(null);
            if (attempt != null && attempt.status == RaidState.Status.ACTIVE && attacker != null)
                event.setCanceled(true); // Only this attempt's marked boss/adds may damage a participant.
        }
        if (attacker instanceof ServerPlayer player) {
            RaidState.Attempt attempt = state.membership(player.getUUID()).orElse(null);
            if (attempt != null && attempt.status == RaidState.Status.ACTIVE)
                event.setCanceled(true); // Roster cannot harm spectators, outsiders or unrelated mobs.
        }
    }

    private static boolean eligibleAttacker(RaidState.Attempt attempt, ServerPlayer player) {
        RaidState.Member member = attempt.members.get(player.getUUID());
        return member != null && !member.eliminated && !member.awaitingRespawn
                && attempt.arena != null && player.serverLevel().dimension().location().equals(attempt.arena.dimension())
                && attempt.arena.bounds().contains(player.position());
    }

    @SubscribeEvent public static void drops(LivingDropsEvent event) {
        if (RaidRuntime.raidTag(event.getEntity()) != null) event.setCanceled(true);
    }
    @SubscribeEvent public static void experience(LivingExperienceDropEvent event) {
        if (RaidRuntime.raidTag(event.getEntity()) != null) event.setDroppedExperience(0);
    }
    @SubscribeEvent public static void breakBlock(BlockEvent.BreakEvent event) {
        if (event.getLevel() instanceof ServerLevel level && protectedBlock(level, event.getPos())) event.setCanceled(true);
    }
    @SubscribeEvent public static void place(BlockEvent.EntityPlaceEvent event) {
        if (event.getLevel() instanceof ServerLevel level && protectedBlock(level, event.getPos())) event.setCanceled(true);
    }
    @SubscribeEvent public static void fluid(BlockEvent.FluidPlaceBlockEvent event) {
        if (event.getLevel() instanceof ServerLevel level && protectedBlock(level, event.getPos())) event.setCanceled(true);
    }
    @SubscribeEvent public static void trample(BlockEvent.FarmlandTrampleEvent event) {
        if (event.getLevel() instanceof ServerLevel level && protectedBlock(level, event.getPos())) event.setCanceled(true);
    }
    @SubscribeEvent public static void use(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel() instanceof ServerLevel level && protectedBlock(level, event.getPos())) event.setCanceled(true);
    }
    @SubscribeEvent public static void piston(PistonEvent.Pre event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        BlockPos pos = event.getPos();
        for (RaidState.Attempt attempt : RaidState.get(level.getServer()).attempts()) {
            if (attempt.status == RaidState.Status.ACTIVE && attempt.arena != null
                    && attempt.arena.dimension().equals(level.dimension().location())
                    && attempt.arena.bounds().inflate(2).contains(Vec3.atCenterOf(pos))) {
                event.setCanceled(true); return;
            }
        }
    }
    @SubscribeEvent public static void explosion(ExplosionEvent.Detonate event) {
        if (event.getLevel() instanceof ServerLevel level)
            event.getAffectedBlocks().removeIf(pos -> protectedBlock(level, pos));
    }

    static boolean protectedBlock(ServerLevel level, BlockPos pos) {
        for (RaidState.Attempt attempt : RaidState.get(level.getServer()).attempts()) {
            if (attempt.status != RaidState.Status.ACTIVE && attempt.status != RaidState.Status.STARTING) continue;
            if (attempt.arena != null && attempt.arena.dimension().equals(level.dimension().location())
                    && attempt.arena.bounds().contains(Vec3.atCenterOf(pos))) return true;
        }
        return false;
    }
}
