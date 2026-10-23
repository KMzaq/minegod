package com.sande.mythictrpg.combat;

import com.sande.mythictrpg.control.CrowdControlService;
import com.sande.mythictrpg.recording.channel.FtbChannelGameTestActions;
import com.sande.mythictrpg.skills.CombatCastRuntime;
import com.sande.mythictrpg.skills.SkillCastEvents;
import com.sande.mythictrpg.skills.SkillExecutor;
import com.sande.mythictrpg.weaponskill.WeaponSkillDefinition;
import com.sande.mythictrpg.weaponskill.WeaponSkillRuntime;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.*;

/** Deliberately invokes the installed mod's real packet executor, not a substitute combat engine. */
@GameTestHolder("mythictrpg_combat_compat")
@PrefixGameTestTemplate(false)
public final class BetterCombatGameTests {
    @GameTest(templateNamespace="mythictrpg_combat_compat",template="empty",batch="combat_compat_05",timeoutTicks=300)
    public static void grantedSimplySwordsAppearanceKeepsRewardIdentityAfterRealAttack(GameTestHelper h) {
        h.startSequence().thenWaitUntil(()->h.assertTrue(acquisitionReady(h),"acquisition startup fence")).thenExecute(()->{
            try (var f=new Fixture(h)) {
                var player=f.player("BCReward",0);var enemy=f.spawn(EntityType.HUSK,2);
                var id=ResourceLocation.parse("swords_demo:longsword_wave");
                var definition=com.sande.mythictrpg.equipment.EquipmentCatalog.find(id).orElseThrow();
                var granted=com.sande.mythictrpg.prototype.PrototypeGrantService.grantEquipment(player.createCommandSourceStack().withPermission(2),id);
                h.assertTrue(granted.succeeded()&&granted.delivered()==1,"real equipment receipt did not grant facade weapon");
                var expected=definition.reward().createStack(player.registryAccess());int found=-1;
                for(int i=0;i<36;i++)if(ItemStack.isSameItemSameComponents(expected,player.getInventory().getItem(i))){found=i;break;}
                h.assertTrue(found>=0,"granted canonical weapon absent");
                var held=player.getInventory().getItem(found);player.getInventory().setItem(found,ItemStack.EMPTY);
                player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,held);
                h.assertTrue(WeaponSkillRuntime.heldSkill(player).isPresent(),"receipt did not authenticate weapon skill");
                h.assertTrue(Api.hasAttributes(held),"facade did not inherit Simply Swords Better Combat motion");
                float before=enemy.getHealth();Api.attack(player,0,enemy,List.of(enemy));
                h.assertTrue(enemy.getHealth()<before,"facade's real Better Combat attack did not damage enemy");
                var after=player.getMainHandItem().copy();after.remove(net.minecraft.core.component.DataComponents.DAMAGE);
                var normalized=expected.copy();normalized.remove(net.minecraft.core.component.DataComponents.DAMAGE);
                h.assertTrue(ItemStack.isSameItemSameComponents(normalized,after),"An attack changed canonical facade components beyond durability");
                h.assertTrue(com.sande.mythictrpg.power.CombatPowerRuntime.registeredAcquiredGear(player,player.getMainHandItem()).isPresent()
                                &&WeaponSkillRuntime.heldSkill(player).map(s->s.id().equals(ResourceLocation.parse("proto_cbt:blade_wave"))).orElse(false),
                        "actual Better Combat hit invalidated reward acquisition/skill identity");
            }
        }).thenSucceed();
    }

    @GameTest(templateNamespace="mythictrpg_combat_compat",template="empty",batch="combat_compat_01",timeoutTicks=120)
    public static void realPacketDamagesEnemiesButPreservesPartyAndPetHurtTimers(GameTestHelper h) throws Exception {
        try (var f = new Fixture(h)) {
            var owner=f.player("BCOwner",0);var mate=f.player("BCFriend",2);var enemy=f.spawn(EntityType.HUSK,2);
            var ownPet=f.wolf(owner,2);var matePet=f.wolf(mate,2);
            try (var party=FtbChannelGameTestActions.open(owner,mate)) {
                mate.invulnerableTime=13;ownPet.invulnerableTime=13;matePet.invulnerableTime=13;
                float mateHealth=mate.getHealth(),ownHealth=ownPet.getHealth(),petHealth=matePet.getHealth();
                var ownMotion=ownPet.getDeltaMovement();var mateMotion=mate.getDeltaMovement();
                float before=enemy.getHealth();
                Api.attack(owner,0,enemy,List.of(mate,ownPet,matePet,enemy));
                h.assertTrue(enemy.getHealth()<before,"real Better Combat request did not damage hostile target");
                h.assertTrue(mate.getHealth()==mateHealth && ownPet.getHealth()==ownHealth && matePet.getHealth()==petHealth,
                        "Better Combat damaged party member or protected pet");
                h.assertTrue(mate.invulnerableTime==13 && ownPet.invulnerableTime==13 && matePet.invulnerableTime==13,
                        "protected target hurt cooldown was reset before our damage event");
                h.assertTrue(ownMotion.equals(ownPet.getDeltaMovement()) && mateMotion.equals(mate.getDeltaMovement()),
                        "protected target was pushed by the sweep");
                float groupDamage=before-enemy.getHealth();enemy.setHealth(before);enemy.invulnerableTime=0;
                Api.attack(owner,0,enemy,List.of(enemy));
                h.assertTrue(Math.abs((before-enemy.getHealth())-groupDamage)<0.001,
                        "protected targets still counted in the Better Combat sweep damage penalty");
            }
            h.succeed();
        }
    }

    @GameTest(templateNamespace="mythictrpg_combat_compat",template="empty",batch="combat_compat_02",timeoutTicks=120)
    public static void realPacketAndVanillaAttacksRespectCastRecoveryAndStun(GameTestHelper h) {
        try (var f = new Fixture(h)) {
            var player=f.player("BCLocked",0);var enemy=f.spawn(EntityType.HUSK,2);
            var cast=SkillExecutor.cast(player,SkillCastEvents.Source.WEAPON,id("lock_wave"),"lock fixture",24,160,
                    new WeaponSkillDefinition.BladeWave(8,4,1,10,.25,3,4,6));
            h.assertValueEqual(cast,WeaponSkillRuntime.Result.SUCCESS,"paid cast was rejected");
            h.assertTrue(WeaponSkillRuntime.actionLocked(player),"server snapshot omitted cast recovery lock");
            float before=enemy.getHealth();enemy.invulnerableTime=13;
            Api.attack(player,0,enemy,List.of(enemy));player.attack(enemy);
            h.assertTrue(enemy.getHealth()==before && enemy.invulnerableTime==13,
                    "locked Better Combat/vanilla attack dealt damage or reset hurt cooldown");
            CombatCastRuntime.cancel(player);
            h.assertTrue(CrowdControlService.applyStun(player,40,true,false),"fixture stun failed");
            h.assertTrue(WeaponSkillRuntime.actionLocked(player),"server snapshot omitted stun lock");
            Api.attack(player,0,enemy,List.of(enemy));
            h.assertTrue(enemy.getHealth()==before && enemy.invulnerableTime==13,"stunned packet changed combat state");
            h.assertTrue(CrowdControlService.cleanseOne(player),"fixture cleanse failed");
            h.assertTrue(!WeaponSkillRuntime.actionLocked(player),"cleansed/cancelled action lock persisted");
            enemy.invulnerableTime=0;Api.attack(player,0,enemy,List.of(enemy));
            h.assertTrue(enemy.getHealth()<before,"cleansed player could not resume Better Combat attacks");
            h.succeed();
        }
    }

    @GameTest(templateNamespace="mythictrpg_combat_compat",template="empty",batch="combat_compat_03",timeoutTicks=120)
    public static void realMeleeStillUsesSingleParryAndDashWindows(GameTestHelper h) {
        var f = new Fixture(h);
        try {
            var defender=f.player("BCDefender",0);var attacker=f.player("BCAttacker",2);attacker.setYRot(180);
            h.assertValueEqual(SkillExecutor.cast(defender,SkillCastEvents.Source.WEAPON,id("parry"),"parry fixture",20,120,
                    new WeaponSkillDefinition.TimedParry(1,4,4,60,5)),WeaponSkillRuntime.Result.SUCCESS,"parry fixture failed");
            h.startSequence().thenIdle(1).thenExecute(()->f.checked(()->{
            Api.attack(attacker,0,defender,List.of(defender));
            h.assertTrue(defender.getHealth()==20,"Better Combat direct melee did not enter existing parry defense");
            Api.attack(attacker,0,defender,List.of(defender));
            h.assertTrue(defender.getHealth()<20,"parry protected more than one accepted hit");
            CombatCastRuntime.cancel(defender);defender.setHealth(20);defender.invulnerableTime=0;
            defender.setDeltaMovement(Vec3.ZERO);defender.moveTo(f.origin.x,f.origin.y,f.origin.z,0,0);defender.setOnGround(true);
            h.assertValueEqual(SkillExecutor.cast(defender,SkillCastEvents.Source.LEARNED,id("dash"),"dash fixture",30,200,
                    new WeaponSkillDefinition.ShortDash(1,4,3,3,2,.1)),WeaponSkillRuntime.Result.SUCCESS,"dash fixture failed");
            })).thenIdle(1).thenExecute(()->f.checked(()->{
            Api.attack(attacker,0,defender,List.of(defender));
            h.assertTrue(defender.getHealth()==20,"Better Combat direct melee bypassed dash evasion window");
            defender.invulnerableTime=0;defender.hurt(defender.damageSources().generic(),2);
            h.assertTrue(defender.getHealth()==18,"Better Combat integration made dash immune to environmental damage");
            f.close();
            })).thenSucceed();
        } catch(RuntimeException|Error failure) {f.close();throw failure;}
    }

    @GameTest(templateNamespace="mythictrpg_combat_compat",template="empty",batch="combat_compat_04",timeoutTicks=120)
    public static void realDualWieldPacketHonorsPvpPolicyAndStillHitsVillagers(GameTestHelper h) {
        try (var f = new Fixture(h)) {
            var attacker=f.player("BCDual",0);var victim=f.player("BCVictim",2);
            attacker.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND,new ItemStack(Items.IRON_SWORD));
            h.assertTrue(Api.offhandAttack(attacker,1),"fixture did not exercise Better Combat offhand attack");
            attacker.server.setPvpAllowed(false);victim.invulnerableTime=13;
            Api.attack(attacker,1,victim,List.of(victim));
            h.assertTrue(victim.getHealth()==20 && victim.invulnerableTime==13,"offhand packet bypassed server PvP policy");
            attacker.server.setPvpAllowed(true);victim.invulnerableTime=0;
            Api.attack(attacker,1,victim,List.of(victim));
            h.assertTrue(victim.getHealth()<20,"unrelated PvP was blanket blocked");
            var villager=f.spawn(EntityType.VILLAGER,2);float before=villager.getHealth();
            Api.attack(attacker,0,villager,List.of(villager));
            h.assertTrue(villager.getHealth()<before,"directly targeted villager was blanket protected");
            h.succeed();
        }
    }

    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath("compat_test",path); }

    private static boolean acquisitionReady(GameTestHelper h) {
        try {
            var method=com.sande.mythictrpg.power.CombatPowerRuntime.class.getDeclaredMethod("captureReady",net.minecraft.server.MinecraftServer.class);
            method.setAccessible(true);return (boolean)method.invoke(null,h.getLevel().getServer());
        } catch(ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }

    /** External types stay in a nested class so unrelated GameTest discovery works without optional mods. */
    private static final class Api {
        static boolean fastAttacks() { return net.bettercombat.BetterCombatMod.config.allow_fast_attacks; }
        static void fastAttacks(boolean enabled) { net.bettercombat.BetterCombatMod.config.allow_fast_attacks=enabled; }
        static boolean hasAttributes(ItemStack stack) { return net.bettercombat.logic.WeaponRegistry.getAttributes(stack)!=null; }
        static boolean offhandAttack(ServerPlayer player,int combo) {
            var hand=net.bettercombat.logic.PlayerAttackHelper.getCurrentAttack(player,combo);return hand!=null&&hand.isOffHand();
        }
        static void attack(ServerPlayer player,int combo,Entity cursor,List<Entity> targets) {
            ((net.bettercombat.mixin.player.LivingEntityAccessor)player).setLastAttackedTicks(1000);
            var request=new net.bettercombat.network.Packets.C2S_AttackRequest(combo,false,player.getInventory().selected,cursor,targets);
            net.bettercombat.network.ServerNetwork.handleAttackRequest(request,player.server,player,player.connection);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final GameTestHelper h;final Vec3 origin;final boolean oldPvp,oldFast;
        final List<ServerPlayer> players=new ArrayList<>();final List<EmbeddedChannel> channels=new ArrayList<>();
        final List<Entity> entities=new ArrayList<>();final Map<BlockPos,BlockState> blocks=new LinkedHashMap<>();
        private boolean closed;
        Fixture(GameTestHelper h) {
            this.h=h;
            if (!ModList.get().isLoaded("bettercombat")) throw new IllegalStateException("Run combat compatibility tests with -PcombatMods=true");
            origin=Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(5,3,5)));
            oldPvp=h.getLevel().getServer().isPvpAllowed();h.getLevel().getServer().setPvpAllowed(true);
            oldFast=Api.fastAttacks();Api.fastAttacks(true);
            for(int x=-6;x<=6;x++)for(int z=-6;z<=8;z++) {
                var pos=BlockPos.containing(origin).offset(x,-1,z);blocks.put(pos,h.getLevel().getBlockState(pos));h.getLevel().setBlockAndUpdate(pos,Blocks.STONE.defaultBlockState());
                for(int y=0;y<3;y++){var air=pos.above(y+1);blocks.put(air,h.getLevel().getBlockState(air));h.getLevel().setBlockAndUpdate(air,Blocks.AIR.defaultBlockState());}
            }
        }
        ServerPlayer player(String name,double z) {
            var cookie=net.minecraft.server.network.CommonListenerCookie.createInitial(new com.mojang.authlib.GameProfile(UUID.randomUUID(),name),false);
            var player=new ServerPlayer(h.getLevel().getServer(),h.getLevel(),cookie.gameProfile(),cookie.clientInformation());
            var connection=new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
            channels.add(new EmbeddedChannel(connection));net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(connection);
            player.server.getPlayerList().placeNewPlayer(connection,player,cookie);players.add(player);
            player.setGameMode(GameType.SURVIVAL);player.setNoGravity(true);player.setInvulnerable(false);player.getAbilities().invulnerable=false;
            try {var field=ServerPlayer.class.getDeclaredField("spawnInvulnerableTime");field.setAccessible(true);field.setInt(player,0);}
            catch(ReflectiveOperationException failure){throw new IllegalStateException(failure);}
            player.moveTo(origin.x,origin.y,origin.z+z,0,0);player.setOnGround(true);
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,new ItemStack(Items.IRON_SWORD));
            player.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(7);
            return player;
        }
        <T extends LivingEntity> T spawn(EntityType<T> type,double z) {
            T entity=Objects.requireNonNull(type.create(h.getLevel()));entity.setNoGravity(true);if(entity instanceof Mob mob)mob.setNoAi(true);
            entity.moveTo(origin.x,origin.y,origin.z+z,0,0);h.getLevel().addFreshEntity(entity);entities.add(entity);
            var armor=entity.getAttribute(Attributes.ARMOR);if(armor!=null)armor.setBaseValue(0);return entity;
        }
        Wolf wolf(ServerPlayer owner,double z) {var wolf=spawn(EntityType.WOLF,z);wolf.setOwnerUUID(owner.getUUID());wolf.setTame(true,false);wolf.setHealth(wolf.getMaxHealth());return wolf;}
        void checked(Runnable step) {try{step.run();}catch(RuntimeException|Error failure){close();throw failure;}}
        @Override public void close() {
            if(closed)return;closed=true;
            players.forEach(p->{CombatCastRuntime.cancel(p);p.server.getPlayerList().remove(p);});
            entities.forEach(Entity::discard);channels.forEach(EmbeddedChannel::finishAndReleaseAll);
            blocks.forEach((pos,state)->h.getLevel().setBlockAndUpdate(pos,state));
            h.getLevel().getServer().setPvpAllowed(oldPvp);Api.fastAttacks(oldFast);
        }
    }
}
