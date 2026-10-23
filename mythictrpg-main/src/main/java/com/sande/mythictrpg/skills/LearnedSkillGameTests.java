package com.sande.mythictrpg.skills;

import com.sande.mythictrpg.data.god.*;
import com.sande.mythictrpg.quest.reward.*;
import com.sande.mythictrpg.quest.reward.blessing.GodRank;
import com.sande.mythictrpg.weaponskill.*;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.gametest.*;
import java.lang.reflect.Field;
import java.util.*;

@GameTestHolder("mythictrpg_weapon_skills")
@PrefixGameTestTemplate(false)
public final class LearnedSkillGameTests {
    private static final ResourceLocation GOD=id("provider"),HEAL=id("heal"),MISS=id("miss"),AREA=id("area"),DOT=id("dot");
    private static ResourceLocation id(String path){return ResourceLocation.fromNamespaceAndPath("test","learned_"+path);}

    @GameTest(templateNamespace="mythictrpg_weapon_skills",template="empty",batch="learned_authority",timeoutTicks=100)
    public static void committedRewardOwnsSkillAndSelectionManaCooldownSurviveReconnect(GameTestHelper h){
        h.startSequence().thenExecute(()->{
            try(var f=new Fixture(h)){
                var p=f.connect("LearnedOwner",UUID.randomUUID());var stranger=f.connect("LearnedStranger",UUID.randomUUID());
                p.setHealth(10);stranger.setHealth(10);
                h.assertValueEqual(LearnedSkillRuntime.cast(p,HEAL),WeaponSkillRuntime.Result.NOT_BOUND,"ungranted skill executed");
                h.assertTrue(!LearnedSkillRuntime.select(p,HEAL)&&SkillMana.mana(p)==200,"unowned selection or cast changed state");
                f.grant(p,HEAL);
                h.assertTrue(LearnedSkillRuntime.ownedSkills(p).equals(List.of(HEAL))&&LearnedSkillRuntime.select(p,HEAL),"real reward did not unlock selectable learned skill");
                h.assertValueEqual(LearnedSkillRuntime.cast(stranger,HEAL),WeaponSkillRuntime.Result.NOT_BOUND,"one player's skill entitlement leaked to another");
                h.assertValueEqual(LearnedSkillRuntime.cast(p,HEAL),WeaponSkillRuntime.Result.SUCCESS,"owned learned skill did not execute");
                h.assertTrue(p.getHealth()==14&&SkillMana.mana(p)==170&&LearnedSkillRuntime.remainingTicks(p,HEAL)==100,"learned cast did not apply and charge its own cost/deadline");
                h.assertTrue(LearnedSkillRuntime.reduceCooldown(p,HEAL,10)==10&&LearnedSkillRuntime.remainingTicks(p,HEAL)==90,"selected learned cooldown reduction failed");
                h.assertTrue(LearnedSkillRuntime.reduceCooldown(stranger,HEAL,10)==0,"unowned cooldown reduction was accepted");
                UUID uuid=p.getUUID();f.disconnect(p);var replacement=f.connect("LearnedReturn",uuid);
                h.assertTrue(LearnedSkillRuntime.selected(replacement).orElseThrow().equals(HEAL)&&SkillMana.mana(replacement)==170
                        &&LearnedSkillRuntime.remainingTicks(replacement,HEAL)==90,"reconnect reset selection, mana, or reduced cooldown");
                h.assertValueEqual(LearnedSkillRuntime.cast(p,HEAL),WeaponSkillRuntime.Result.INVALID_PLAYER,"stale player object remained authorized");
            }
        }).thenSucceed();
    }

    @GameTest(templateNamespace="mythictrpg_weapon_skills",template="empty",batch="learned_cast_lifecycle",timeoutTicks=12000)
    public static void paidMissAreaAndPeriodicDamagePublishOneLifecyclePerCast(GameTestHelper h){
        h.startSequence().thenExecute(()->{
            var f=new Fixture(h);var observer=new Observer();NeoForge.EVENT_BUS.register(observer);boolean[] closed={false};
            Runnable close=()->{if(!closed[0]){closed[0]=true;NeoForge.EVENT_BUS.unregister(observer);f.close();}};
            try{
                var origin=h.absolutePos(new BlockPos(1,3,1));
                var miss=f.connect("LearnedMiss",UUID.randomUUID());var area=f.connect("LearnedArea",UUID.randomUUID());var dot=f.connect("LearnedDot",UUID.randomUUID());
                miss.moveTo(origin.getX()+0.5,origin.getY(),origin.getZ()+0.5,180,0);
                area.moveTo(origin.getX()+30,origin.getY(),origin.getZ(),0,0);
                dot.moveTo(origin.getX()+14.5,origin.getY(),origin.getZ()+0.5,0,0);
                f.grant(miss,MISS);f.grant(area,AREA);f.grant(dot,DOT);
                h.assertValueEqual(LearnedSkillRuntime.cast(miss,MISS),WeaponSkillRuntime.Result.SUCCESS,"valid miss was not committed");
                h.assertTrue(SkillMana.mana(miss)==180&&observer.finished.get(0).hitCount()==0&&!observer.finished.get(0).cancelled(),"miss was free or lacked final zero-hit settlement");
                miss.moveTo(origin.getX()+30,origin.getY(),origin.getZ());
                area.moveTo(origin.getX()+0.5,origin.getY(),origin.getZ()+0.5,0,0);
                var first=f.zombie(origin.getX()-0.5,origin.getY(),origin.getZ()+3.5);
                var second=f.zombie(origin.getX()+1.5,origin.getY(),origin.getZ()+3.5);
                h.assertValueEqual(LearnedSkillRuntime.cast(area,AREA),WeaponSkillRuntime.Result.SUCCESS,"area attack failed");
                h.assertTrue(first.getHealth()<first.getMaxHealth()&&second.getHealth()<second.getMaxHealth(),"area damage did not hit both valid targets");
                var periodic=f.zombie(origin.getX()+14.5,origin.getY(),origin.getZ()+4.5);
                f.holdEntityChunk(periodic.blockPosition());
                long readyStarted=System.nanoTime();int beforeTicks=periodic.tickCount;long[] castGameTick={-1};int[] castEntityTick={0};
                h.onEachTick(()->{
                    if(closed[0])return;
                    try{
                      if(castGameTick[0]<0){
                        var chunk=new net.minecraft.world.level.ChunkPos(periodic.blockPosition());
                        if(!h.getLevel().areEntitiesLoaded(chunk.toLong())||!h.getLevel().isPositionEntityTicking(periodic.blockPosition())||periodic.tickCount<=beforeTicks){
                            long elapsed=System.nanoTime()-readyStarted;
                            h.assertTrue(elapsed<java.util.concurrent.TimeUnit.SECONDS.toNanos(10),"DOT fixture never began real entity ticks after "+java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(elapsed)+" ms; entity ticks="+periodic.tickCount);
                            // GameTestServer runs unpaced. Let asynchronous entity-section loading progress.
                            java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);return;
                        }
                        castGameTick[0]=SkillMana.now(dot);castEntityTick[0]=periodic.tickCount;
                        h.assertValueEqual(LearnedSkillRuntime.cast(dot,DOT),WeaponSkillRuntime.Result.SUCCESS,"periodic attack failed");
                        h.assertTrue(observer.starts.size()==3&&observer.finished.size()==2,"DOT finished before its last pulse or start count was wrong");
                        return;
                      }
                        if(SkillMana.now(dot)-castGameTick[0]<50)return;
                        h.assertTrue(observer.starts.size()==3&&observer.finished.size()==3,"cast did not finish exactly once");
                        for(var start:observer.starts){
                            var context=start.context();var finishes=observer.finished.stream().filter(e->e.context().castId().equals(context.castId())).toList();
                            h.assertTrue(finishes.size()==1&&!finishes.get(0).cancelled(),"duplicate or cancelled completed lifecycle");
                            long hits=observer.hits.stream().filter(e->e.context().castId().equals(context.castId())).count();
                            int expected=context.skillId().equals(MISS)?0:context.skillId().equals(AREA)?2:3;
                            h.assertTrue(hits==expected&&finishes.get(0).hitCount()==expected,"multi-hit/DOT lifecycle changed cast ID or reported wrong hit count: "+context.skillId()+" "+hits+"; DOT entity ticks="+(periodic.tickCount-castEntityTick[0])+", iframe="+periodic.invulnerableTime+", health="+periodic.getHealth());
                        }
                        h.assertTrue(periodic.getHealth()==14&&SkillMana.mana(dot)==180,"DOT did not deal three pulses for one mana payment");
                        close.run();h.succeed();
                    }catch(RuntimeException|Error failure){close.run();h.fail("DOT lifecycle fixture: "+failure);}
                });
            }catch(RuntimeException|Error failure){close.run();throw failure;}
        });
    }

    public static final class Observer{
        final List<SkillCastEvents.Start> starts=new ArrayList<>();final List<SkillCastEvents.Hit> hits=new ArrayList<>();final List<SkillCastEvents.Finished> finished=new ArrayList<>();
        @SubscribeEvent public void start(SkillCastEvents.Start event){if(event.context().skillId().getPath().startsWith("learned_"))starts.add(event);}
        @SubscribeEvent public void hit(SkillCastEvents.Hit event){if(event.context().skillId().getPath().startsWith("learned_"))hits.add(event);}
        @SubscribeEvent public void finish(SkillCastEvents.Finished event){if(event.context().skillId().getPath().startsWith("learned_"))finished.add(event);}
    }
    private static final class Fixture implements AutoCloseable{
        final GameTestHelper h;final Map<ResourceLocation,LearnedSkillDefinition> previous=LearnedSkillDefinitions.INSTANCE.all();
        final GodDefinitionManager.ProgressionSnapshot gods=GodDefinitionManager.INSTANCE.progressionSnapshot();
        final List<ServerPlayer> players=new ArrayList<>();final List<EmbeddedChannel> channels=new ArrayList<>();final List<Zombie> mobs=new ArrayList<>();
        static final net.minecraft.server.level.TicketType<UUID> ENTITY_TEST_TICKET=net.minecraft.server.level.TicketType.create("mythictrpg_learned_skill_test",UUID::compareTo);
        final UUID ticketId=UUID.randomUUID();final List<net.minecraft.world.level.ChunkPos> chunks=new ArrayList<>();
        Fixture(GameTestHelper h){this.h=h;
            LearnedSkillDefinitions.INSTANCE.apply(Map.of(HEAL,new LearnedSkillDefinition(HEAL,"Heal fixture",30,100,new WeaponSkillDefinition.SelfHeal(4),10),
                    MISS,new LearnedSkillDefinition(MISS,"Miss fixture",20,60,new WeaponSkillDefinition.AimedDamage(2,8),10),
                    AREA,new LearnedSkillDefinition(AREA,"Area fixture",20,60,new WeaponSkillDefinition.AreaDamage(2,6,8),10),
                    DOT,new LearnedSkillDefinition(DOT,"DOT fixture",20,60,new WeaponSkillDefinition.PeriodicDamage(2,8,3,24),10)),null,null);
            var definitions=new HashMap<>(gods.definitions());definitions.put(GOD,new GodDefinition(1,Component.literal("Skill fixture provider"),id("origin"),id("faction"),Set.of(),Optional.empty(),Optional.empty(),Optional.empty(),Optional.of(GodRank.LOW)));
            setGods(new GodDefinitionManager.ProgressionSnapshot(definitions,gods.unlockIndex(),gods.appearanceIndex(),gods.generation()));
        }
        void grant(ServerPlayer p,ResourceLocation skill){var result=RewardClaimService.INSTANCE.issue(p,GOD,id("receipt_"+UUID.randomUUID()),
                new ResolvedQuestReward("learned fixture",List.of(new ProgressionRewardEntry(ProgressionRewardEntry.Kind.LEARNED,skill)),List.of()));
            h.assertTrue(result.succeeded(),"learned reward claim failed: "+result.reason());}
        ServerPlayer connect(String name,UUID uuid){var cookie=net.minecraft.server.network.CommonListenerCookie.createInitial(new com.mojang.authlib.GameProfile(uuid,name),false);
            var p=new ServerPlayer(h.getLevel().getServer(),h.getLevel(),cookie.gameProfile(),cookie.clientInformation());
            var connection=new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);channels.add(new EmbeddedChannel(connection));
            net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(connection);p.server.getPlayerList().placeNewPlayer(connection,p,cookie);p.setNoGravity(true);players.add(p);return p;}
        void disconnect(ServerPlayer p){p.server.getPlayerList().remove(p);players.remove(p);}
        void holdEntityChunk(BlockPos position){var chunk=new net.minecraft.world.level.ChunkPos(position);h.getLevel().getChunkSource().addRegionTicket(ENTITY_TEST_TICKET,chunk,2,ticketId);chunks.add(chunk);h.getLevel().getChunk(chunk.x,chunk.z);}
        Zombie zombie(double x,double y,double z){var mob=EntityType.HUSK.create(h.getLevel());if(mob==null)throw new IllegalStateException("zombie fixture");mob.setNoAi(true);mob.setNoGravity(true);
            mob.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1);mob.moveTo(x,y,z,0,0);h.getLevel().addFreshEntity(mob);mobs.add(mob);return mob;}
        @Override public void close(){mobs.forEach(Zombie::discard);players.forEach(p->p.server.getPlayerList().remove(p));channels.forEach(EmbeddedChannel::finishAndReleaseAll);chunks.forEach(chunk->h.getLevel().getChunkSource().removeRegionTicket(ENTITY_TEST_TICKET,chunk,2,ticketId));LearnedSkillDefinitions.INSTANCE.apply(previous,null,null);setGods(gods);}
        static void setGods(GodDefinitionManager.ProgressionSnapshot value){try{Field field=GodDefinitionManager.class.getDeclaredField("snapshot");field.setAccessible(true);field.set(GodDefinitionManager.INSTANCE,value);}catch(ReflectiveOperationException failure){throw new IllegalStateException(failure);}}
    }
}
