package com.sande.mythictrpg.control;

import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.util.UUID;

/** Explicit CC provenance. Mechanic locks are never removed by ordinary cleansing. */
@EventBusSubscriber(modid = MythicTrpg.MOD_ID)
public final class CrowdControlService {
    private static final String KEY="mythictrpg_crowd_control";
    public static final String IMMUNITY_TAG="mythictrpg_stun_immune";
    private CrowdControlService() { }
    public static boolean applyStun(LivingEntity entity, int ticks, boolean dispellable, boolean mechanic) {
        if (!(entity.level() instanceof ServerLevel level) || !level.getServer().isSameThread()
                || !entity.isAlive() || ticks<1 || ticks>72000 || !mechanic && (entity.getPersistentData().getBoolean(IMMUNITY_TAG)
                    ||CrowdControlImmunities.INSTANCE.immune(entity))) return false;
        var root=entity.getPersistentData().getCompound(KEY); var list=root.getList("sources",Tag.TAG_COMPOUND);
        prune(list,level.getServer().overworld().getGameTime());
        if (list.size()>=32) return false;
        var source=new CompoundTag(); source.putUUID("id",UUID.randomUUID());
        source.putLong("end",level.getServer().overworld().getGameTime()+ticks);
        source.putBoolean("dispellable",dispellable); source.putBoolean("mechanic",mechanic);
        list.add(source); root.put("sources",list);
        root.putString("dimension",level.dimension().location().toString());
        // A shared anchor is stable across simultaneous normal/mechanic sources.
        if (!root.contains("x",Tag.TAG_DOUBLE)) {
            root.putDouble("x",entity.getX()); root.putDouble("y",entity.getY()); root.putDouble("z",entity.getZ());
        }
        entity.getPersistentData().put(KEY,root);
        entity.stopUsingItem(); if(entity instanceof Mob mob)mob.getNavigation().stop();
        NeoForge.EVENT_BUS.post(new CrowdControlAppliedEvent(entity,dispellable,mechanic));
        return true;
    }
    public static boolean isStunned(LivingEntity entity) {
        if (!(entity.level() instanceof ServerLevel level)) return false;
        if (!(entity.getPersistentData().get(KEY) instanceof CompoundTag root)) return false;
        if(!entity.isAlive()||entity.isSpectator()||!root.getString("dimension").equals(level.dimension().location().toString())) {
            entity.getPersistentData().remove(KEY);return false;
        }
        var list=root.getList("sources",Tag.TAG_COMPOUND);
        prune(list,level.getServer().overworld().getGameTime());
        if(list.isEmpty()) { entity.getPersistentData().remove(KEY); return false; }
        root.put("sources",list); return true;
    }
    public static boolean cleanseOne(LivingEntity entity) {
        if (!(entity.level() instanceof ServerLevel level) || !level.getServer().isSameThread() || !isStunned(entity)) return false;
        var root=entity.getPersistentData().getCompound(KEY); var list=root.getList("sources",Tag.TAG_COMPOUND);
        for(int i=0;i<list.size();i++) {
            var source=list.getCompound(i);
            if(source.getBoolean("dispellable")&&!source.getBoolean("mechanic")) {
                list.remove(i); if(list.isEmpty())entity.getPersistentData().remove(KEY); else root.put("sources",list);
                return true;
            }
        }
        return false;
    }
    private static void prune(ListTag sources,long now) { sources.removeIf(tag -> !(tag instanceof CompoundTag value)
            || !value.contains("end",Tag.TAG_LONG) || value.getLong("end")<=now); }
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void attack(AttackEntityEvent event) {
        if(isStunned(event.getEntity()))event.setCanceled(true);
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void outgoing(LivingIncomingDamageEvent event) {
        if(event.getSource().getDirectEntity()==event.getSource().getEntity()
                &&event.getSource().getEntity() instanceof LivingEntity attacker&&isStunned(attacker))event.setCanceled(true);
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void item(PlayerInteractEvent.RightClickItem event) {
        if(isStunned(event.getEntity()))event.setCanceled(true);
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void block(PlayerInteractEvent.RightClickBlock event) {
        if(isStunned(event.getEntity()))event.setCanceled(true);
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void entity(PlayerInteractEvent.EntityInteract event) {
        if(isStunned(event.getEntity()))event.setCanceled(true);
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void specific(PlayerInteractEvent.EntityInteractSpecific event) {
        if(isStunned(event.getEntity()))event.setCanceled(true);
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        for(var level:event.getServer().getAllLevels()) for(var entity:level.getAllEntities()) {
            if(!(entity instanceof LivingEntity living)||!isStunned(living))continue;
            var root=living.getPersistentData().getCompound(KEY);
            var anchor=new Vec3(root.getDouble("x"),root.getDouble("y"),root.getDouble("z"));
            living.setDeltaMovement(Vec3.ZERO); living.fallDistance=0; living.stopUsingItem();
            if(living instanceof Mob mob) { mob.getNavigation().stop(); mob.setTarget(null); }
            if(living.position().distanceToSqr(anchor)>1.0E-6) {
                if(living instanceof ServerPlayer player)player.connection.teleport(anchor.x,anchor.y,anchor.z,player.getYRot(),player.getXRot());
                else living.setPos(anchor);
            }
        }
    }
}
