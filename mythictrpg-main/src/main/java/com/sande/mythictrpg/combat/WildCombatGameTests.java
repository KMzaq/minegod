package com.sande.mythictrpg.combat;

import com.sande.mythictrpg.party.PartyService;
import com.sande.mythictrpg.recording.channel.FtbChannelGameTestActions;
import com.sande.mythictrpg.skills.SkillCastEvents;
import com.sande.mythictrpg.skills.SkillExecutor;
import com.sande.mythictrpg.skills.SkillMana;
import com.sande.mythictrpg.weaponskill.WeaponSkillDefinition;
import com.sande.mythictrpg.weaponskill.WeaponSkillRuntime;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@GameTestHolder("mythictrpg_wild_combat")
@PrefixGameTestTemplate(false)
public final class WildCombatGameTests {
    @GameTest(templateNamespace="mythictrpg_wild_combat",template="empty",batch="wild_target_policy",timeoutTicks=100)
    public static void ownTamedAnimalsAreProtectedButVillagersAndOtherOwnersAnimalsRemainTargets(GameTestHelper h) {
        try (var f = new Fixture(h)) {
            var owner = f.player("WildOwner",0);
            var stranger = f.player("WildOther",20);
            var wolf = f.wolf(owner,2);
            var horse = f.spawn(EntityType.HORSE,4); horse.setOwnerUUID(owner.getUUID()); horse.setTamed(true);
            var otherWolf = f.wolf(stranger,6);
            var villager = f.spawn(EntityType.VILLAGER,8);
            h.assertTrue(!CombatTargeting.canDamage(owner,wolf) && !CombatTargeting.canDamage(owner,horse),"owned wolf or horse escaped ownership guard");
            var attack = new AttackEntityEvent(owner,wolf); NeoForge.EVENT_BUS.post(attack);
            h.assertTrue(attack.isCanceled(),"owned animal basic attack was not rejected before interaction effects");
            float health = wolf.getHealth();
            h.assertTrue(!wolf.hurt(owner.damageSources().playerAttack(owner),3) && wolf.getHealth()==health,"owned animal took melee damage");
            h.assertTrue(!wolf.hurt(owner.damageSources().indirectMagic(owner,owner),3) && wolf.getHealth()==health,"owned animal took skill damage");
            h.assertTrue(CombatTargeting.canDamage(owner,otherWolf) && CombatTargeting.canDamage(owner,villager),"villager or another owner's animal was blanket protected");
            h.assertTrue(villager.hurt(owner.damageSources().playerAttack(owner),3),"villager actual hurt path rejected PvE");
            wolf.invulnerableTime=0;
            h.assertTrue(wolf.hurt(wolf.damageSources().generic(),2) && wolf.getHealth()<health,"ownership created environmental invulnerability");
            h.succeed();
        }
    }

    @GameTest(templateNamespace="mythictrpg_wild_combat",template="empty",batch="wild_target_policy",timeoutTicks=100)
    public static void realFtbPartyBlocksMeleeMagicAndPetsWithoutProtectingOutsiders(GameTestHelper h) throws Exception {
        try (var f = new Fixture(h)) {
            var owner = f.player("PartyAttack",0); var mate=f.player("PartyFriend",3); var outsider=f.player("PartyStranger",20);
            try(var party=FtbChannelGameTestActions.open(owner,mate)) {
                h.assertTrue(PartyService.sameParty(owner,mate),"fixture did not use the real FTB party");
                h.assertTrue(!CombatTargeting.canDamage(owner,mate) && !CombatTargeting.canDamage(mate,owner),"party protection was one-directional");
                var melee=f.incoming(mate,owner.damageSources().playerAttack(owner));
                var magic=f.incoming(mate,owner.damageSources().indirectMagic(owner,owner));
                h.assertTrue(melee.isCanceled() && magic.isCanceled(),"party melee or magic damage was accepted");
                var mateWolf=f.wolf(mate,4);
                h.assertTrue(!CombatTargeting.canDamage(owner,mateWolf),"party pet did not inherit owner protection");
                h.assertTrue(CombatTargeting.canDamage(owner,outsider),"unrelated player was incorrectly protected with PvP enabled");
                float before=outsider.getHealth();
                h.assertTrue(outsider.hurt(owner.damageSources().playerAttack(owner),3) && outsider.getHealth()<before,"unrelated PvP was not executable");
                h.assertTrue(!f.incoming(owner,outsider.damageSources().mobAttack(f.spawn(EntityType.HUSK,9))).isCanceled(),"ordinary mob attack was mistaken for party damage");
            }
            h.assertTrue(CombatTargeting.canDamage(owner,mate),"disbanded party left stale friendly-fire protection");
            h.succeed();
        }
    }

    @GameTest(templateNamespace="mythictrpg_wild_combat",template="empty",batch="wild_target_policy",timeoutTicks=100)
    public static void arrowsAndHarmfulEffectsRespectPartyWhileBeneficialEffectsAndMobArrowsWork(GameTestHelper h) throws Exception {
        try (var f = new Fixture(h)) {
            var owner=f.player("ArrowOwner",0); var mate=f.player("ArrowFriend",3);
            try(var party=FtbChannelGameTestActions.open(owner,mate)) {
                Arrow arrow=f.arrow(owner);
                var impact=new ProjectileImpactEvent(arrow,new EntityHitResult(mate)); NeoForge.EVENT_BUS.post(impact);
                h.assertTrue(impact.isCanceled(),"party arrow impact could still ignite or push a member");
                h.assertTrue(f.incoming(mate,owner.damageSources().arrow(arrow,owner)).isCanceled(),"party arrow damage bypassed source ownership");
                h.assertTrue(!mate.addEffect(new MobEffectInstance(MobEffects.POISON,100),owner) && !mate.hasEffect(MobEffects.POISON),"party poison could later deal unowned damage");
                h.assertTrue(mate.addEffect(new MobEffectInstance(MobEffects.REGENERATION,100),owner),"beneficial party potion was blocked");
                var skeleton=f.spawn(EntityType.SKELETON,6); var hostileArrow=f.arrow(skeleton);
                h.assertTrue(!f.incoming(mate,owner.damageSources().arrow(hostileArrow,skeleton)).isCanceled(),"skeleton arrow was classified as a player attack");
                // Restore a projectile from NBT to remove its cached shooter, then disconnect the shooter.
                CompoundTag saved=new CompoundTag(); arrow.saveWithoutId(saved);
                Arrow orphan=EntityType.ARROW.create(h.getLevel()); orphan.load(saved);
                f.extras.add(orphan); f.disconnect(owner);
                h.assertTrue(f.incoming(mate,mate.damageSources().arrow(orphan,null)).isCanceled(),"logged-out shooter's saved UUID lost party protection");
                var pet=f.wolf(owner,5);
                h.assertTrue(f.incoming(pet,mate.damageSources().arrow(orphan,null)).isCanceled(),"logged-out shooter could damage their own pet");
            }
            h.succeed();
        }
    }

    @GameTest(templateNamespace="mythictrpg_wild_combat",template="empty",batch="wild_target_policy",timeoutTicks=100)
    public static void lingeringCloudTicksProtectPartyAndPetsEvenAfterLogoutWithoutBlockingMobClouds(GameTestHelper h) throws Exception {
        try(var f=new Fixture(h)) {
            var owner=f.player("CloudOwner",0);var mate=f.player("CloudFriend",3);var outsider=f.player("CloudOther",4.5);
            try(var party=FtbChannelGameTestActions.open(owner,mate)) {
                var pet=f.wolf(owner,3.5);
                var poison=f.cloud(owner,mate.position(),new MobEffectInstance(MobEffects.POISON,100));
                // Invoke the production five-tick application branch, including its real addEffect(effect, cloud).
                poison.tickCount=5;poison.tick();
                h.assertTrue(!mate.hasEffect(MobEffects.POISON) && !pet.hasEffect(MobEffects.POISON),"player lingering cloud poisoned a party member or owned pet");
                h.assertTrue(outsider.hasEffect(MobEffects.POISON),"player lingering cloud incorrectly protected an unrelated player");
                var helpful=f.cloud(owner,mate.position(),new MobEffectInstance(MobEffects.REGENERATION,100));
                helpful.tickCount=5;helpful.tick();
                h.assertTrue(mate.hasEffect(MobEffects.REGENERATION),"beneficial lingering cloud was blocked for a party member");
                CompoundTag saved=new CompoundTag();poison.saveWithoutId(saved);f.disconnect(owner);
                var orphan=EntityType.AREA_EFFECT_CLOUD.create(h.getLevel());orphan.load(saved);f.extras.add(orphan);
                h.assertTrue(orphan.getOwner()==null,"offline cloud fixture retained its live player owner");
                orphan.tickCount=5;orphan.tick();
                h.assertTrue(!mate.hasEffect(MobEffects.POISON) && !pet.hasEffect(MobEffects.POISON),"restored offline-player cloud lost party or pet protection");
                var witch=f.spawn(EntityType.WITCH,8);
                var hostile=f.cloud(witch,mate.position(),new MobEffectInstance(MobEffects.POISON,100));
                hostile.tickCount=5;hostile.tick();
                h.assertTrue(mate.hasEffect(MobEffects.POISON),"mob-owned lingering cloud was classified as friendly player damage");
                mate.removeEffect(MobEffects.POISON);hostile.saveWithoutId(saved);witch.discard();
                var unloadedMob=EntityType.AREA_EFFECT_CLOUD.create(h.getLevel());unloadedMob.load(saved);f.extras.add(unloadedMob);
                h.assertTrue(unloadedMob.getOwner()==null,"unloaded mob cloud fixture retained its live mob owner");
                unloadedMob.tickCount=5;unloadedMob.tick();
                h.assertTrue(mate.hasEffect(MobEffects.POISON),"unloaded mob UUID was mistaken for a logged-out player");
            }
            h.succeed();
        }
    }

    @GameTest(templateNamespace="mythictrpg_wild_combat",template="empty",batch="wild_target_policy",timeoutTicks=100)
    public static void serverPvpFlagStillBlocksDamageAndTargetSelection(GameTestHelper h) {
        try(var f=new Fixture(h)) {
            var attacker=f.player("PvpSource",0); var victim=f.player("PvpVictim",3);
            h.assertTrue(CombatTargeting.canDamage(attacker,victim),"enabled PvP not selectable");
            attacker.server.setPvpAllowed(false);
            h.assertTrue(!CombatTargeting.canDamage(attacker,victim) && f.incoming(victim,attacker.damageSources().playerAttack(attacker)).isCanceled(),"server PvP disabled setting was bypassed");
            h.assertTrue(CombatTargeting.canDamage(attacker,f.spawn(EntityType.VILLAGER,5)),"server PvP flag incorrectly blocked villagers");
            h.succeed();
        }
    }

    @GameTest(templateNamespace="mythictrpg_wild_combat",template="empty",batch="wild_target_policy",timeoutTicks=100)
    public static void vanillaSwordSweepSkipsPartyAndOwnedPetsBeforeKnockback(GameTestHelper h) throws Exception {
        try(var f=new Fixture(h)) {
            var attacker=f.player("SweepSource",0);var mate=f.player("SweepFriend",2.5);
            try(var party=FtbChannelGameTestActions.open(attacker,mate)) {
                var primary=f.spawn(EntityType.HUSK,2);var secondary=f.spawn(EntityType.HUSK,2.5);var pet=f.wolf(attacker,2.5);
                mate.setPos(f.origin.x-0.6,f.origin.y,f.origin.z+2.5);pet.setPos(f.origin.x+0.5,f.origin.y,f.origin.z+2.5);
                secondary.setPos(f.origin.x+1,f.origin.y,f.origin.z+2.5);
                attacker.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,new ItemStack(Items.DIAMOND_SWORD));attacker.setOnGround(true);
                Class<?> owner=net.minecraft.world.entity.player.Player.class;
                while(owner!=null) {
                    try {var charge=owner.getDeclaredField("attackStrengthTicker");charge.setAccessible(true);charge.setInt(attacker,100);break;}
                    catch(NoSuchFieldException missing){owner=owner.getSuperclass();}
                }
                h.assertTrue(owner!=null && attacker.getAttackStrengthScale(0)==1,"sweep fixture did not use a charged real attack");
                float petHp=pet.getHealth();
                // Better Combat replaces vanilla sweeping. Exercise its actual executor when installed,
                // including protected candidates, instead of expecting a disabled vanilla mechanic.
                if(net.neoforged.fml.ModList.get().isLoaded("bettercombat"))
                    BetterCombatSweep.attack(attacker,primary,List.of(primary,secondary,mate,pet));
                else attacker.attack(primary);
                h.assertTrue(primary.getHealth()<primary.getMaxHealth() && secondary.getHealth()<secondary.getMaxHealth(),"fixture did not damage both enemies through the active sword sweep executor");
                h.assertTrue(mate.getHealth()==20 && pet.getHealth()==petHp,"sweep damaged a protected member or pet");
                h.assertTrue(mate.getDeltaMovement().lengthSqr()==0 && pet.getDeltaMovement().lengthSqr()==0,"sweep applied knockback before canceling protected damage");
            }
            h.succeed();
        }
    }

    /** Optional API linkage stays isolated so the same test runs without Better Combat installed. */
    private static final class BetterCombatSweep {
        static void attack(ServerPlayer attacker,Entity primary,List<Entity> targets) {
            ((net.bettercombat.mixin.player.LivingEntityAccessor)attacker).setLastAttackedTicks(1000);
            var request=new net.bettercombat.network.Packets.C2S_AttackRequest(
                    0,false,attacker.getInventory().selected,primary,targets);
            net.bettercombat.network.ServerNetwork.handleAttackRequest(request,attacker.server,attacker,attacker.connection);
        }
    }

    @GameTest(templateNamespace="mythictrpg_wild_combat",template="empty",batch="wild_target_policy",timeoutTicks=100)
    public static void areaSkillUsesSamePartyAndOwnershipFilterAndOnlyPublishesRealEnemyHits(GameTestHelper h) throws Exception {
        try(var f=new Fixture(h)) {
            var attacker=f.player("AreaSource",0); var mate=f.player("AreaFriend",2); var outsider=f.player("AreaEnemy",4);
            try(var party=FtbChannelGameTestActions.open(attacker,mate)) {
                var own=f.wolf(attacker,2.5);var friendly=f.wolf(mate,3);var villager=f.spawn(EntityType.VILLAGER,3.5);var other=f.wolf(outsider,4.5);
                float ownHp=own.getHealth(),friendlyHp=friendly.getHealth(),mateHp=mate.getHealth(),otherHp=other.getHealth(),villagerHp=villager.getHealth();
                var result=SkillExecutor.cast(attacker,SkillCastEvents.Source.LEARNED,ResourceLocation.parse("test:wild_area"),"wild area",20,60,new WeaponSkillDefinition.AreaDamage(2,6,8));
                h.assertValueEqual(result,WeaponSkillRuntime.Result.SUCCESS,"area skill failed in wilderness");
                h.assertTrue(own.getHealth()==ownHp && friendly.getHealth()==friendlyHp && mate.getHealth()==mateHp,"area skill damaged protected targets");
                h.assertTrue(villager.getHealth()<villagerHp && other.getHealth()<otherHp && outsider.getHealth()<20,"area skill missed permitted villager, other pet, or player");
                h.assertTrue(f.hits.size()==3 && f.finished.size()==1 && f.finished.getFirst().hitCount()==3,"protected targets published accessory hit events");
                h.assertTrue(SkillMana.mana(attacker)==180,"area skill charged mana per target or skipped payment");
            }
            h.succeed();
        }
    }

    @GameTest(templateNamespace="mythictrpg_wild_combat",template="empty",batch="wild_periodic_policy",timeoutTicks=100)
    public static void joiningPartyCancelsRemainingPeriodicHitsWithoutRefund(GameTestHelper h) {
        var f=new Fixture(h);
        try {
            var attacker=f.player("PulseSource",0);var target=f.player("PulseTarget",4);
            var result=SkillExecutor.cast(attacker,SkillCastEvents.Source.LEARNED,ResourceLocation.parse("test:wild_pulse"),"wild periodic",20,80,new WeaponSkillDefinition.PeriodicDamage(2,8,3,24));
            h.assertValueEqual(result,WeaponSkillRuntime.Result.SUCCESS,"periodic PvP did not cast");
            h.assertTrue(target.getHealth()==18 && f.hits.size()==1,"periodic first hit was not real PvP damage");
            f.party=FtbChannelGameTestActions.open(attacker,target);
            h.runAfterDelay(3,()->{
                try {
                    h.assertTrue(target.getHealth()==18 && f.hits.size()==1,"new party member was hit by an old cast");
                    h.assertTrue(f.finished.size()==1 && f.finished.getFirst().cancelled() && f.finished.getFirst().hitCount()==1,"periodic cancellation did not settle once with its original hit count");
                    h.assertTrue(SkillMana.mana(attacker)>=180 && SkillMana.mana(attacker)<181,"party change refunded a committed offensive cast");
                    h.succeed();
                } finally {f.close();}
            });
        } catch(Exception failure) {f.close();h.fail("periodic party fixture: "+failure);}
          catch(Error failure) {f.close();throw failure;}
    }

    private static final class Fixture implements AutoCloseable {
        final GameTestHelper helper;
        final Vec3 origin;
        final List<ServerPlayer> players=new ArrayList<>();
        final List<EmbeddedChannel> channels=new ArrayList<>();
        final List<Entity> extras=new ArrayList<>();
        final List<SkillCastEvents.Hit> hits=new ArrayList<>();
        final List<SkillCastEvents.Finished> finished=new ArrayList<>();
        final Observer observer=new Observer();
        final boolean previousPvp;
        FtbChannelGameTestActions.Fixture party;
        boolean closed;
        Fixture(GameTestHelper h) {
            helper=h;origin=Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(1,3,1)));
            previousPvp=h.getLevel().getServer().isPvpAllowed();h.getLevel().getServer().setPvpAllowed(true);
            for(int x=((int)origin.x-16)>>4;x<=((int)origin.x+16)>>4;x++)for(int z=((int)origin.z-16)>>4;z<=((int)origin.z+24)>>4;z++)h.getLevel().getChunk(x,z);
            NeoForge.EVENT_BUS.register(observer);
        }
        ServerPlayer player(String name,double z) {
            var cookie=net.minecraft.server.network.CommonListenerCookie.createInitial(new com.mojang.authlib.GameProfile(UUID.randomUUID(),name),false);
            var player=new ServerPlayer(helper.getLevel().getServer(),helper.getLevel(),cookie.gameProfile(),cookie.clientInformation());
            var connection=new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
            channels.add(new EmbeddedChannel(connection));net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(connection);
            player.server.getPlayerList().placeNewPlayer(connection,player,cookie);players.add(player);
            player.setGameMode(GameType.SURVIVAL);player.setNoGravity(true);player.setInvulnerable(false);player.getAbilities().invulnerable=false;
            try {var field=ServerPlayer.class.getDeclaredField("spawnInvulnerableTime");field.setAccessible(true);field.setInt(player,0);}
            catch(ReflectiveOperationException failure){throw new IllegalStateException(failure);}
            player.moveTo(origin.x,origin.y,origin.z+z,0,0);return player;
        }
        void disconnect(ServerPlayer player){player.server.getPlayerList().remove(player);players.remove(player);}
        <T extends LivingEntity> T spawn(EntityType<T> type,double z) {
            T entity=type.create(helper.getLevel());if(entity==null)throw new IllegalStateException("test spawn "+type);
            entity.setNoGravity(true);if(entity instanceof Mob mob)mob.setNoAi(true);
            entity.moveTo(origin.x,origin.y,origin.z+z,0,0);helper.getLevel().addFreshEntity(entity);extras.add(entity);
            var armor=entity.getAttribute(Attributes.ARMOR);if(armor!=null)armor.setBaseValue(0);return entity;
        }
        Wolf wolf(ServerPlayer owner,double z){Wolf wolf=spawn(EntityType.WOLF,z);wolf.setOwnerUUID(owner.getUUID());wolf.setTame(true,false);wolf.setHealth(wolf.getMaxHealth());return wolf;}
        Arrow arrow(LivingEntity owner){var arrow=new Arrow(helper.getLevel(),owner,new ItemStack(Items.ARROW),null);extras.add(arrow);return arrow;}
        AreaEffectCloud cloud(LivingEntity owner,Vec3 position,MobEffectInstance effect){
            var cloud=new AreaEffectCloud(helper.getLevel(),position.x,position.y,position.z);cloud.setOwner(owner);
            cloud.setRadius(2);cloud.setWaitTime(0);cloud.setDuration(200);cloud.addEffect(effect);extras.add(cloud);return cloud;
        }
        LivingIncomingDamageEvent incoming(LivingEntity victim,DamageSource source){var event=new LivingIncomingDamageEvent(victim,new net.neoforged.neoforge.common.damagesource.DamageContainer(source,3));NeoForge.EVENT_BUS.post(event);return event;}
        final class Observer {
            @SubscribeEvent public void hit(SkillCastEvents.Hit event){if(players.contains(event.context().player()))hits.add(event);}
            @SubscribeEvent public void finished(SkillCastEvents.Finished event){if(players.contains(event.context().player()))finished.add(event);}
        }
        @Override public void close(){if(closed)return;closed=true;
            try {if(party!=null)party.close();}catch(Exception failure){throw new IllegalStateException(failure);}
            finally {NeoForge.EVENT_BUS.unregister(observer);extras.forEach(Entity::discard);players.forEach(p->p.server.getPlayerList().remove(p));channels.forEach(EmbeddedChannel::finishAndReleaseAll);helper.getLevel().getServer().setPvpAllowed(previousPvp);}}
    }
}
