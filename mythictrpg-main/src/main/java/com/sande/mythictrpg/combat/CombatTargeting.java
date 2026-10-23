package com.sande.mythictrpg.combat;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.party.PartyService;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.TraceableEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.phys.EntityHitResult;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;

import java.util.UUID;

/** Shared player attack policy. Raid isolation and vanilla damage rules remain additional restrictions. */
@EventBusSubscriber(modid = MythicTrpg.MOD_ID)
public final class CombatTargeting {
    private CombatTargeting() { }

    /** A target-selection filter, not permission to bypass vanilla hurt or the raid damage guard. */
    public static boolean canDamage(ServerPlayer attacker, LivingEntity target) {
        return attacker != target && target.isAlive() && !target.isSpectator() && target.level() == attacker.level()
                && !protectedFromPlayer(attacker, target)
                && (!(target instanceof ServerPlayer victim) || attacker.server.isPvpAllowed()
                && attacker.canHarmPlayer(victim) && victim.canHarmPlayer(attacker));
    }

    public static boolean protectedFromPlayer(ServerPlayer attacker, LivingEntity target) {
        return protectedFromPlayer(attacker.server, attacker.getUUID(), target);
    }

    private static boolean protectedFromPlayer(MinecraftServer server, UUID attacker, LivingEntity target) {
        // Self-inflicted TNT and environmental damage keep their normal behavior. Skills exclude self separately.
        if (target instanceof ServerPlayer victim && !attacker.equals(victim.getUUID()))
            return PartyService.relation(server, attacker, victim.getUUID()) != PartyService.Relation.DIFFERENT;
        if (target instanceof OwnableEntity owned && owned.getOwnerUUID() != null) {
            UUID owner = owned.getOwnerUUID();
            return attacker.equals(owner) || PartyService.relation(server, attacker, owner) != PartyService.Relation.DIFFERENT;
        }
        return false;
    }

    private static boolean blocked(MinecraftServer server, UUID attacker, LivingEntity target) {
        if (protectedFromPlayer(server, attacker, target)) return true;
        if (!(target instanceof ServerPlayer victim) || attacker.equals(victim.getUUID())) return false;
        if (!server.isPvpAllowed()) return true;
        ServerPlayer online = server.getPlayerList().getPlayer(attacker);
        if (online != null) return !online.canHarmPlayer(victim) || !victim.canHarmPlayer(online);
        // The shooter can log out while an arrow is still in flight. Keep vanilla scoreboard friendly-fire too.
        var cache = server.getProfileCache();
        var profile = cache == null ? java.util.Optional.<com.mojang.authlib.GameProfile>empty() : cache.get(attacker);
        var victimTeam = victim.getTeam();
        var shooterTeam = profile.map(p -> server.getScoreboard().getPlayersTeam(p.getName())).orElse(null);
        return victimTeam != null && victimTeam.isAlliedTo(shooterTeam) && !victimTeam.isAllowFriendlyFire();
    }

    /** Resolve player ownership without treating ordinary skeleton arrows as player attacks. */
    private static UUID playerActor(MinecraftServer server, Entity source, int depth) {
        if (source == null || depth > 3) return null;
        if (source instanceof ServerPlayer player) return player.getUUID();
        if (source instanceof OwnableEntity owned) return owned.getOwnerUUID();
        if (!(source instanceof TraceableEntity traceable)) return null;
        Entity owner = traceable.getOwner();
        if (owner != null) return playerActor(server, owner, depth + 1);
        // Projectiles and lingering-potion clouds retain this UUID when their live owner is unavailable.
        CompoundTag saved = new CompoundTag();
        source.saveWithoutId(saved);
        if (!saved.hasUUID("Owner")) return null;
        UUID ownerId = saved.getUUID("Owner");
        if (server.getPlayerList().getPlayer(ownerId) != null) return ownerId;
        if (PartyService.knownPlayer(server, ownerId)) return ownerId;
        var cache = server.getProfileCache();
        return cache != null && cache.get(ownerId).isPresent() ? ownerId : null;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void attack(AttackEntityEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && event.getTarget() instanceof LivingEntity target
                && blocked(player.server, player.getUUID(), target)) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void incoming(LivingIncomingDamageEvent event) {
        if (!(event.getEntity().level() instanceof ServerLevel level)) return;
        UUID actor = playerActor(level.getServer(), event.getSource().getEntity(), 0);
        if (actor == null) actor = playerActor(level.getServer(), event.getSource().getDirectEntity(), 0);
        if (actor != null && blocked(level.getServer(), actor, event.getEntity())) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void arrowImpact(ProjectileImpactEvent event) {
        if (!(event.getProjectile() instanceof AbstractArrow) || !(event.getProjectile().level() instanceof ServerLevel level)
                || !(event.getRayTraceResult() instanceof EntityHitResult hit) || !(hit.getEntity() instanceof LivingEntity target)) return;
        UUID actor = playerActor(level.getServer(), event.getProjectile(), 0);
        if (actor != null && blocked(level.getServer(), actor, target)) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void harmfulEffect(MobEffectEvent.Applicable event) {
        if (!(event.getEntity().level() instanceof ServerLevel level)
                || event.getEffectInstance().getEffect().value().isBeneficial()) return;
        UUID actor = playerActor(level.getServer(), event.getEffectSource(), 0);
        if (actor != null && blocked(level.getServer(), actor, event.getEntity()))
            event.setResult(MobEffectEvent.Applicable.Result.DO_NOT_APPLY);
    }
}
