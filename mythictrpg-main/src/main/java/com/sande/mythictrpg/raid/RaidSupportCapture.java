package com.sande.mythictrpg.raid;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.understanding.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.util.*;

/** Read-only production-raid telemetry. No damage, boss, rewards, roster or encounter rules are changed. */
@EventBusSubscriber(modid=MythicTrpg.MOD_ID)
public final class RaidSupportCapture {
    private record Guard(UUID run,boolean raised) { }
    private static final Map<MinecraftServer,Map<UUID,Guard>> GUARDS=new IdentityHashMap<>();
    private RaidSupportCapture() { }
    private static RaidState.Attempt active(ServerPlayer p) {
        if(!p.server.isSameThread()||!MinecraftActivityCapture.enrolled(p))return null;
        var state=RaidState.get(p.server);if(!state.ready())return null;
        var a=state.membership(p.getUUID()).orElse(null);
        if(a==null||a.status()!=RaidState.Status.ACTIVE||a.arena==null||a.definition().id().equals(SealedGuardianEncounter.RAID_ID)
                ||a.members.get(p.getUUID()).eliminated||!p.isAlive()
                ||!p.serverLevel().dimension().location().equals(a.arena.dimension())||!a.arena.bounds().contains(p.position()))return null;
        return a;
    }
    @SubscribeEvent public static void damage(LivingDamageEvent.Post event) {
        if(!(event.getSource().getEntity() instanceof ServerPlayer p)||!Float.isFinite(event.getNewDamage())||event.getNewDamage()<=0)return;
        var a=active(p);if(a==null||!a.id().equals(RaidRuntime.raidTag(event.getEntity())))return;
        MinecraftActivityCapture.recordSupport(p,SupportSignal.Kind.RAID_ATTACK,a.id(),"실제 레이드 공격 적중 · "+a.definition().id());
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        var server=event.getServer();if(server.getTickCount()%10!=0)return;
        var guards=GUARDS.computeIfAbsent(server,s->new HashMap<>());var present=new HashSet<UUID>();
        for(var p:server.getPlayerList().getPlayers()) {
            var a=active(p);if(a==null){guards.remove(p.getUUID());continue;}present.add(p.getUUID());
            boolean raised=p.isBlocking();var old=guards.put(p.getUUID(),new Guard(a.id(),raised));
            // Initial/reconnect sample and holding a key while idle are not repeated defensive choices.
            if(old==null||!old.run().equals(a.id())||old.raised()||!raised)continue;
            var boss=a.bossId==null?null:p.serverLevel().getEntity(a.bossId);
            if(boss!=null&&boss.isAlive()&&p.distanceToSqr(boss)<256)
                MinecraftActivityCapture.recordSupport(p,SupportSignal.Kind.RAID_GUARD,a.id(),"보스 근처 방패 방어 자세 선택 · "+a.definition().id());
        }
        guards.keySet().retainAll(present);
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) {GUARDS.remove(event.getServer());}
}
