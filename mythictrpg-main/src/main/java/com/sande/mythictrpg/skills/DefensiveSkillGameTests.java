package com.sande.mythictrpg.skills;

import com.sande.mythictrpg.prototype.PrototypeCombatAccess;
import com.sande.mythictrpg.weaponskill.WeaponSkillDefinition;
import com.sande.mythictrpg.weaponskill.WeaponSkillRuntime;
import com.sande.mythictrpg.weaponskill.WeaponSkillState;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.*;

/** Real paid casts, world ticks, collision shapes, and LivingEntity.hurt; no fabricated successful hits. */
@GameTestHolder("mythictrpg_prototype_combat")
@PrefixGameTestTemplate(false)
public final class DefensiveSkillGameTests {
    private static final ResourceLocation PARRY=id("timed_parry"),DASH=id("short_dodge");
    private static final WeaponSkillDefinition.TimedParry PARRY_ACTION=new WeaponSkillDefinition.TimedParry(2,4,8,60,4);
    private static final WeaponSkillDefinition.ShortDash DASH_ACTION=new WeaponSkillDefinition.ShortDash(1,4,3,3,3,.25);

    @GameTest(templateNamespace="mythictrpg_prototype_combat",template="empty",batch="prototype_defense_01",timeoutTicks=100)
    public static void parryWindowStartsAtTwoAndConsumesOnlyOneOfTwoSameTickHits(GameTestHelper h) {
        var f=new Fixture(h);f.guarded(()->{
            f.parry();f.hit("test_melee",1);h.assertTrue(f.player.getHealth()==19,"parry incorrectly covered its windup");
            f.after(2,()->{
                f.resetDamage();f.hit("test_melee",4);h.assertTrue(f.player.getHealth()==20,"first eligible hit in [2,6) was not parried");
                h.assertTrue(f.player.invulnerableTime==0,"parry fabricated or extended vanilla hurt immunity");
                f.hit("test_melee",4);h.assertTrue(f.player.getHealth()==16,"same-tick second attack was also parried");
                h.assertTrue(CombatCastRuntime.locked(f.player),"successful parry removed recovery lock");
            });
            f.after(14,()->{
                h.assertTrue(!CombatCastRuntime.locked(f.player),"parry lock did not end at tick 14");
                h.assertTrue(SkillMana.mana(f.player)==180,"successful parry refunded or double charged mana");
                f.close();h.succeed();
            });
        });
    }

    @GameTest(templateNamespace="mythictrpg_prototype_combat",template="empty",batch="prototype_defense_02",timeoutTicks=100)
    public static void parryEndTickSixIsExcluded(GameTestHelper h) {
        var f=new Fixture(h);f.guarded(()->{f.parry();f.after(6,()->{
            f.hit("test_melee",4);h.assertTrue(f.player.getHealth()==16,"parry included the exclusive end tick 6");
            h.assertTrue(CombatCastRuntime.locked(f.player),"parry recovery disappeared after its active window");f.close();h.succeed();
        });});
    }

    @GameTest(templateNamespace="mythictrpg_prototype_combat",template="empty",batch="prototype_defense_03",timeoutTicks=100)
    public static void parryRejectsUnknownProjectileRearWallAndOutOfRangeWithoutConsumingWindow(GameTestHelper h) {
        var f=new Fixture(h);f.guarded(()->{f.parry();f.after(2,()->{
            for(String attack:List.of("unclassified","test_projectile","test_area","test_mechanic")) {
                f.resetDamage();f.hit(attack,3);h.assertTrue(f.player.getHealth()==17,"parry accepted ineligible attack "+attack);
            }
            f.resetDamage();Vec3 front=f.enemy.position();f.enemy.moveTo(f.start.add(0,0,-2));
            f.hit("test_melee",3);h.assertTrue(f.player.getHealth()==17,"rear attack was parried");
            f.enemy.moveTo(front);f.resetDamage();
            BlockPos wall=BlockPos.containing(f.start.add(0,0,1));f.set(wall,Blocks.STONE.defaultBlockState());
            f.set(wall.above(),Blocks.STONE.defaultBlockState());f.hit("test_melee",3);
            h.assertTrue(f.player.getHealth()==17,"parry crossed a blocking wall");
            f.set(wall,Blocks.AIR.defaultBlockState());f.set(wall.above(),Blocks.AIR.defaultBlockState());f.resetDamage();
            f.enemy.moveTo(f.start.add(0,0,5));f.hit("test_melee",3);h.assertTrue(f.player.getHealth()==17,"parry exceeded four-block range");
            f.enemy.moveTo(front);f.resetDamage();f.hit("test_melee",3);
            h.assertTrue(f.player.getHealth()==20,"ineligible hits consumed the remaining parry window");f.close();h.succeed();
        });});
    }

    @GameTest(templateNamespace="mythictrpg_prototype_combat",template="empty",batch="prototype_defense_04",timeoutTicks=100)
    public static void earlierCancellationAndVanillaImmunityDoNotConsumeParry(GameTestHelper h) {
        var f=new Fixture(h);var cancel=new CancelOnce(f.player);f.observers.add(cancel);NeoForge.EVENT_BUS.register(cancel);
        f.guarded(()->{f.parry();f.after(2,()->{
            cancel.armed=true;f.hit("test_melee",3);h.assertTrue(f.player.getHealth()==20,"earlier cancellation was ignored");
            f.player.setInvulnerable(true);f.hit("test_melee",3);f.player.setInvulnerable(false);
            f.hit("test_melee",3);h.assertTrue(f.player.getHealth()==20,"cancelled/invulnerable hit consumed parry");
            f.hit("test_melee",3);h.assertTrue(f.player.getHealth()==17,"second real eligible hit was still parried");f.close();h.succeed();
        });});
    }

    @GameTest(templateNamespace="mythictrpg_prototype_combat",template="empty",batch="prototype_defense_05",timeoutTicks=100)
    public static void existingHurtCooldownWinsBeforeParryAndLargerDamageStillCanBeParried(GameTestHelper h) {
        var f=new Fixture(h);f.guarded(()->{
            f.hit("test_melee",3);h.assertTrue(f.player.getHealth()==17&&f.player.invulnerableTime>10,"vanilla immunity fixture failed");
            f.parry();f.after(2,()->{
                f.hit("test_melee",3);h.assertTrue(f.player.getHealth()==17,"equal damage bypassed existing hurt immunity");
                f.hit("test_melee",4);h.assertTrue(f.player.getHealth()==17,"prior ignored hit consumed parry for a stronger hit");
                f.hit("test_melee",5);h.assertTrue(f.player.getHealth()==15,"consumed parry blocked subsequent stronger damage");
                f.close();h.succeed();
            });
        });
    }

    @GameTest(templateNamespace="mythictrpg_prototype_combat",template="empty",batch="prototype_defense_06",timeoutTicks=100)
    public static void weaponSwapCancelsParryImmediatelyButRetainsPaidRecovery(GameTestHelper h) {
        var f=new Fixture(h);f.guarded(()->{f.parry();f.after(2,()->{
            f.player.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.IRON_SWORD));f.hit("test_melee",4);
            h.assertTrue(f.player.getHealth()==16,"same-tick swap retained parry");
            h.assertTrue(CombatCastRuntime.locked(f.player)&&SkillMana.mana(f.player)==180,"swap reset paid recovery or mana");
        });f.after(14,()->{
            h.assertTrue(!CombatCastRuntime.locked(f.player),"cancelled parry retained lock beyond original deadline");
            long remaining=WeaponSkillState.get(f.player.server).account(f.player.getUUID()).orElseThrow().remainingTicks(PARRY.toString(),SkillMana.now(f.player));
            h.assertTrue(remaining==106,"swap reset parry cooldown");f.close();h.succeed();
        });});
    }

    @GameTest(templateNamespace="mythictrpg_prototype_combat",template="empty",batch="prototype_defense_07",timeoutTicks=100)
    public static void dashMovesFourStepsAndOnlyEvadesTicksOneThroughThree(GameTestHelper h) {
        var f=new Fixture(h);f.guarded(()->{
            // Keep the enemy out of the movement lane; attack origins remain authoritative but do not steer this dash.
            f.enemy.moveTo(f.start.add(2,0,1));f.dash();
            f.after(1,()->{
                near(h,f.player.getZ()-f.start.z,.75,"first dash step");f.hit("test_melee",3);
                h.assertTrue(f.player.getHealth()==20,"dash did not evade at tick 1");
                f.player.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.IRON_SWORD));
            });
            f.after(3,()->{
                near(h,f.player.getZ()-f.start.z,2.25,"third dash step after weapon swap");f.hit("test_projectile",3);
                h.assertTrue(f.player.getHealth()==20,"dash did not evade allowed projectile at tick 3");
                f.hit("test_mechanic",1);h.assertTrue(f.player.getHealth()==19,"dash suppressed a mechanic");
            });
            f.after(4,()->{
                near(h,f.player.getZ()-f.start.z,3,"fourth dash step");f.hit("test_melee",3);
                h.assertTrue(f.player.getHealth()==17,"dash evasion incorrectly included tick 4");
            });
            f.after(8,()->{
                h.assertTrue(!CombatCastRuntime.locked(f.player)&&SkillMana.mana(f.player)==170,"dash payment or lock deadline changed");
                near(h,f.player.getZ()-f.start.z,3,"dash extended after movement ended");f.close();h.succeed();
            });
        });
    }

    @GameTest(templateNamespace="mythictrpg_prototype_combat",template="empty",batch="prototype_defense_08",timeoutTicks=100)
    public static void dashPreflightRejectsBlockedAirButAllowsWilderness(GameTestHelper h) {
        try(var f=new Fixture(h)) {
            BlockPos wall=BlockPos.containing(f.start.add(0,0,.5));
            f.set(wall,Blocks.STONE.defaultBlockState());f.set(wall.above(),Blocks.STONE.defaultBlockState());
            h.assertTrue(f.castDash()!=WeaponSkillRuntime.Result.SUCCESS&&SkillMana.mana(f.player)==200,"blocked first movement charged mana");
            f.set(wall,Blocks.AIR.defaultBlockState());f.set(wall.above(),Blocks.AIR.defaultBlockState());f.player.setOnGround(false);
            h.assertTrue(f.castDash()!=WeaponSkillRuntime.Result.SUCCESS&&SkillMana.mana(f.player)==200,"airborne dash was accepted");
            f.player.setOnGround(true);PrototypeCombatAccess.clearTrial(f.player);
            h.assertTrue(f.castDash()==WeaponSkillRuntime.Result.SUCCESS&&SkillMana.mana(f.player)==170,"grounded wilderness dash was rejected or did not pay");
        }h.succeed();
    }

    @GameTest(templateNamespace="mythictrpg_prototype_combat",template="empty",batch="prototype_defense_09",timeoutTicks=100)
    public static void obstacleAppearingAfterApprovalStopsMovementAndEvasionWithoutRefund(GameTestHelper h) {
        var f=new Fixture(h);f.guarded(()->{
            f.enemy.moveTo(f.start.add(2,0,1));f.dash();BlockPos wall=BlockPos.containing(f.start.add(0,0,.5));
            f.set(wall,Blocks.STONE.defaultBlockState());f.set(wall.above(),Blocks.STONE.defaultBlockState());
            f.after(1,()->{
                h.assertTrue(f.player.getZ()-f.start.z<.25,"dash crossed a wall inserted after approval");f.hit("test_melee",3);
                h.assertTrue(f.player.getHealth()==17,"blocked dash retained evasion");
                h.assertTrue(CombatCastRuntime.locked(f.player)&&SkillMana.mana(f.player)==170,"blocked approved dash refunded or lost recovery");
            });
            f.after(8,()->{
                h.assertTrue(!CombatCastRuntime.locked(f.player)&&f.player.getZ()-f.start.z<.25,"stopped dash resumed or exceeded recovery");f.close();h.succeed();
            });
        });
    }

    @GameTest(templateNamespace="mythictrpg_prototype_combat",template="empty",batch="prototype_defense_10",timeoutTicks=100)
    public static void collisionSweepStopsAtEntitiesEdgesPartialBlocksAndScopeBoundary(GameTestHelper h) {
        try(var f=new Fixture(h)) {
            var scope=PrototypeCombatAccess.scope(f.player).orElseThrow();
            f.enemy.moveTo(f.start.add(0,0,1));Vec3 entity=DashCollision.safeDelta(f.player,scope,new Vec3(0,0,.75));
            h.assertTrue(entity.z>.25&&entity.z<.41,"living entity sweep failed: "+entity);
            f.enemy.moveTo(f.start.add(3,0,0));
            BlockPos partial=BlockPos.containing(f.start.add(0,0,.5));f.set(partial,Blocks.OAK_FENCE.defaultBlockState());
            Vec3 fence=DashCollision.safeDelta(f.player,scope,new Vec3(0,0,.75));h.assertTrue(fence.z<.75,"fence shape was crossed");
            f.set(partial,Blocks.AIR.defaultBlockState());
            for(int x=-1;x<=1;x++)for(int z=1;z<=4;z++)f.set(BlockPos.containing(f.start).offset(x,-1,z),Blocks.AIR.defaultBlockState());
            Vec3 edge=DashCollision.safeDelta(f.player,scope,new Vec3(0,0,1.5));h.assertTrue(edge.z<1.5,"dash swept over unsupported floor");
            h.assertTrue(DashCollision.supported(f.player,f.player.getBoundingBox().move(edge)),"dash returned an unsupported last position");
            Vec3 boundary=DashCollision.safeDelta(f.player,scope,new Vec3(0,0,32));h.assertTrue(boundary.z<32,"scope boundary was ignored");
        }h.succeed();
    }

    @GameTest(templateNamespace="mythictrpg_prototype_combat",template="empty",batch="prototype_defense_11",timeoutTicks=100)
    public static void fivePlayersKeepSharedArenaManaCooldownsAndDefensiveWindowsIndependent(GameTestHelper h) {
        var f=new Fixture(h);f.guarded(()->{
            f.player.moveTo(f.start.add(3,0,0),0,0);f.player.setOnGround(true);
            f.enemy.moveTo(f.start.add(3,0,3));
            var otherParry=f.mate("OtherParry",f.start.add(0,0,3),-90);
            var firstDash=f.mate("FirstDash",f.start.add(6,0,0),0);
            var secondDash=f.mate("SecondDash",f.start.add(0,0,6),-90);
            var unprotected=f.mate("Unprotected",f.start.add(6,0,6),0);
            f.parry();
            h.assertValueEqual(SkillExecutor.cast(otherParry,SkillCastEvents.Source.WEAPON,PARRY,"Other parry",20,120,PARRY_ACTION),WeaponSkillRuntime.Result.SUCCESS,"second player's same-ID parry");
            for(var p:List.of(firstDash,secondDash))h.assertValueEqual(SkillExecutor.cast(p,SkillCastEvents.Source.LEARNED,DASH,"Other dash",30,200,DASH_ACTION),WeaponSkillRuntime.Result.SUCCESS,"same-tick independent dash");
            h.assertTrue(SkillMana.mana(f.player)==180&&SkillMana.mana(otherParry)==180
                    &&SkillMana.mana(firstDash)==170&&SkillMana.mana(secondDash)==170&&SkillMana.mana(unprotected)==200,"five-player costs leaked across accounts");
            f.after(2,()->{
                f.hit("test_melee",3);f.hit("test_melee",3);
                h.assertTrue(f.player.getHealth()==17,"one parry did not consume exactly its own first hit");
                for(var p:List.of(otherParry,firstDash,secondDash)) {
                    PrototypeCombatAccess.hurt(p,f.enemy,id("test_melee"),f.enemy.getBoundingBox().getCenter(),3);
                    h.assertTrue(p.getHealth()==20,"one player's consumed parry leaked into another player's window: "+p.getGameProfile().getName());
                }
                PrototypeCombatAccess.hurt(unprotected,f.enemy,id("test_melee"),f.enemy.getBoundingBox().getCenter(),3);
                h.assertTrue(unprotected.getHealth()==17,"another player's defense protected the unprotected fifth member");
            });
            f.after(8,()->{
                near(h,firstDash.getZ()-f.start.z,3,"first independent dash distance");
                near(h,secondDash.getX()-f.start.x,3,"second independent dash distance");
                h.assertTrue(!CombatCastRuntime.locked(firstDash)&&!CombatCastRuntime.locked(secondDash)
                        &&CombatCastRuntime.locked(f.player)&&CombatCastRuntime.locked(otherParry),"per-player recovery deadlines leaked");
                f.close();h.succeed();
            });
        });
    }

    @GameTest(templateNamespace="mythictrpg_prototype_combat",template="empty",batch="prototype_defense_12",timeoutTicks=100)
    public static void wildernessParryRecognizesVanillaMeleeButDoesNotSuppressEnvironment(GameTestHelper h) {
        var f=new Fixture(h);f.guarded(()->{
            PrototypeCombatAccess.clearTrial(f.player);f.parry();f.after(2,()->{
                f.player.hurt(f.player.damageSources().magic(),3);
                h.assertTrue(f.player.getHealth()==17,"wilderness parry suppressed unrelated magic");
                f.resetDamage();f.player.hurt(f.player.damageSources().onFire(),1);
                h.assertTrue(f.player.getHealth()==19,"wilderness parry suppressed environmental fire");
                f.resetDamage();f.player.hurt(f.player.damageSources().mobAttack(f.enemy),3);
                h.assertTrue(f.player.getHealth()==20,"wilderness parry rejected direct vanilla hostile melee");
                f.player.hurt(f.player.damageSources().mobAttack(f.enemy),3);
                h.assertTrue(f.player.getHealth()==17,"wilderness parry canceled more than one eligible hit");
                f.close();h.succeed();
            });
        });
    }

    @GameTest(templateNamespace="mythictrpg_prototype_combat",template="empty",batch="prototype_defense_13",timeoutTicks=100)
    public static void wildernessDashEvadesVanillaArrowAndMeleeButNotFireOrUnknownMagic(GameTestHelper h) {
        var f=new Fixture(h);f.guarded(()->{
            f.enablePvp();
            var shooter=f.mate("WildWaveShooter",f.start.add(6,0,6),0);
            h.assertTrue(com.sande.mythictrpg.combat.CombatTargeting.canDamage(shooter,f.player),"blade-wave dodge fixture did not permit actual PvP damage");
            PrototypeCombatAccess.clearTrial(f.player);f.enemy.moveTo(f.start.add(2,0,1));
            var arrow=EntityType.ARROW.create(h.getLevel());if(arrow==null)throw new IllegalStateException("arrow fixture");
            arrow.setOwner(f.enemy);arrow.moveTo(f.start.add(1,0,0));f.extras.add(arrow);f.dash();
            f.after(1,()->{
                f.player.hurt(f.player.damageSources().arrow(arrow,f.enemy),3);
                h.assertTrue(f.player.getHealth()==20,"wilderness dash rejected a vanilla arrow");
                f.player.hurt(f.player.damageSources().mobAttack(f.enemy),3);
                h.assertTrue(f.player.getHealth()==20,"wilderness dash rejected vanilla melee");
                var blade=new DamageSource(f.player.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.DAMAGE_TYPE)
                        .getHolderOrThrow(CombatCastRuntime.BLADE_DAMAGE),null,shooter,shooter.position());
                f.player.hurt(blade,3);h.assertTrue(f.player.getHealth()==20,"wilderness dash rejected the explicitly authored blade projectile");
                f.player.hurt(f.player.damageSources().onFire(),1);
                h.assertTrue(f.player.getHealth()==19,"wilderness evasion suppressed fire damage");
                f.resetDamage();f.player.hurt(f.player.damageSources().magic(),3);
                h.assertTrue(f.player.getHealth()==17,"wilderness evasion suppressed unclassified magic");
                f.resetDamage();
            });
            f.after(4,()->{
                f.player.hurt(f.player.damageSources().arrow(arrow,f.enemy),3);
                h.assertTrue(f.player.getHealth()==17,"wilderness evasion included its exclusive end tick");f.close();h.succeed();
            });
        });
    }

    @GameTest(templateNamespace="mythictrpg_prototype_combat",template="empty",batch="prototype_defense_14",timeoutTicks=100)
    public static void trialDoesNotInheritWildernessVanillaAttackPermissions(GameTestHelper h) {
        var f=new Fixture(h);f.guarded(()->{
            f.parry();f.after(2,()->{
                f.player.hurt(f.player.damageSources().mobAttack(f.enemy),3);
                h.assertTrue(f.player.getHealth()==17,"trial implicitly allowed an unclassified vanilla melee hit");
                f.resetDamage();f.hit("test_melee",3);
                h.assertTrue(f.player.getHealth()==20,"trial's authored token no longer allowed parry");f.close();h.succeed();
            });
        });
    }

    @GameTest(templateNamespace="mythictrpg_prototype_combat",template="empty",batch="prototype_defense_15",timeoutTicks=100)
    public static void wildernessParryAcceptsAnUnrelatedPlayersDirectMelee(GameTestHelper h) {
        var f=new Fixture(h);f.guarded(()->{
            f.enablePvp();
            var attacker=f.mate("WildMeleeHit",f.start.add(0,0,3),180);
            h.assertTrue(com.sande.mythictrpg.combat.CombatTargeting.canDamage(attacker,f.player),"parry fixture did not permit actual PvP damage");
            PrototypeCombatAccess.clearTrial(f.player);f.parry();f.after(2,()->{
                f.player.hurt(f.player.damageSources().playerAttack(attacker),3);
                h.assertTrue(f.player.getHealth()==20,"front-facing unrelated PvP melee was not parried");
                f.player.hurt(f.player.damageSources().playerAttack(attacker),3);
                h.assertTrue(f.player.getHealth()==17,"PvP parry consumed no window or the second attack was ignored");f.close();h.succeed();
            });
        });
    }

    private static ResourceLocation id(String path){return ResourceLocation.fromNamespaceAndPath("proto_cbt",path);}
    private static void near(GameTestHelper h,double actual,double expected,String label){h.assertTrue(Math.abs(actual-expected)<.002,label+": "+actual+" != "+expected);}
    public static final class CancelOnce {
        final ServerPlayer player;boolean armed;CancelOnce(ServerPlayer player){this.player=player;}
        @SubscribeEvent(priority=EventPriority.HIGHEST) public void hit(LivingIncomingDamageEvent event){if(armed&&event.getEntity()==player){armed=false;event.setCanceled(true);}}
    }
    private static final class Fixture implements AutoCloseable {
        final GameTestHelper h;final ServerPlayer player;final Zombie enemy;final EmbeddedChannel channel;final Vec3 start;
        final Map<BlockPos,BlockState> previous=new LinkedHashMap<>();final List<Object> observers=new ArrayList<>();final List<Runnable> cleanup=new ArrayList<>();
        final List<ServerPlayer> mates=new ArrayList<>();final List<net.minecraft.world.entity.Entity> extras=new ArrayList<>();final List<EmbeddedChannel> mateChannels=new ArrayList<>();boolean closed;
        Fixture(GameTestHelper h) {
            this.h=h;
            var cookie=net.minecraft.server.network.CommonListenerCookie.createInitial(new com.mojang.authlib.GameProfile(UUID.randomUUID(),"DefenseFixture"),false);
            player=new ServerPlayer(h.getLevel().getServer(),h.getLevel(),cookie.gameProfile(),cookie.clientInformation());
            var connection=new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);channel=new EmbeddedChannel(connection);
            net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(connection);
            player.server.getPlayerList().placeNewPlayer(connection,player,cookie);player.setGameMode(GameType.SURVIVAL);player.setNoGravity(true);
            player.setInvulnerable(false);player.getAbilities().invulnerable=false;
            try {var field=ServerPlayer.class.getDeclaredField("spawnInvulnerableTime");field.setAccessible(true);field.setInt(player,0);}
            catch(ReflectiveOperationException failure){throw new IllegalStateException(failure);}
            BlockPos origin=h.absolutePos(new BlockPos(2,3,2));
            for(int x=0;x<=12;x++)for(int z=0;z<=12;z++) {
                set(origin.offset(x,-1,z),Blocks.STONE.defaultBlockState());
                for(int y=0;y<3;y++)set(origin.offset(x,y,z),Blocks.AIR.defaultBlockState());
            }
            start=Vec3.atBottomCenterOf(origin.offset(3,0,3));player.moveTo(start.x,start.y,start.z,0,0);player.setOnGround(true);
            player.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.DIAMOND_SWORD));
            enemy=EntityType.HUSK.create(h.getLevel());if(enemy==null)throw new IllegalStateException("enemy fixture");
            enemy.setNoAi(true);enemy.setNoGravity(true);enemy.moveTo(start.add(0,0,3));h.getLevel().addFreshEntity(enemy);
            AABB bounds=new AABB(origin.getX(),origin.getY()-1,origin.getZ(),origin.getX()+13,origin.getY()+5,origin.getZ()+13);
            if(!PrototypeCombatAccess.registerTrial(player,bounds,Set.of(enemy.getUUID())))throw new IllegalStateException("trial fixture registration");
        }
        void set(BlockPos position,BlockState state){previous.putIfAbsent(position.immutable(),h.getLevel().getBlockState(position));h.getLevel().setBlockAndUpdate(position,state);}
        void guarded(Runnable work){try{work.run();}catch(RuntimeException|Error failure){close();throw failure;}}
        void after(int ticks,Runnable work){h.runAfterDelay(ticks,()->{if(!closed)guarded(work);});}
        void parry(){h.assertValueEqual(SkillExecutor.cast(player,SkillCastEvents.Source.WEAPON,PARRY,"Parry fixture",20,120,PARRY_ACTION),WeaponSkillRuntime.Result.SUCCESS,"paid parry cast");}
        WeaponSkillRuntime.Result castDash(){return SkillExecutor.cast(player,SkillCastEvents.Source.LEARNED,DASH,"Dash fixture",30,200,DASH_ACTION);}
        void dash(){h.assertValueEqual(castDash(),WeaponSkillRuntime.Result.SUCCESS,"paid dash cast");}
        void hit(String attack,float amount){PrototypeCombatAccess.hurt(player,enemy,id(attack),enemy.getBoundingBox().getCenter(),amount);}
        void resetDamage(){player.setHealth(20);player.invulnerableTime=0;player.setDeltaMovement(Vec3.ZERO);}
        void enablePvp(){boolean previous=player.server.isPvpAllowed();cleanup.add(()->player.server.setPvpAllowed(previous));player.server.setPvpAllowed(true);}
        ServerPlayer mate(String name,Vec3 position,float yaw) {
            if(name.length()>16)throw new IllegalArgumentException("Mock profile name exceeds vanilla's 16-character limit: "+name);
            var cookie=net.minecraft.server.network.CommonListenerCookie.createInitial(new com.mojang.authlib.GameProfile(UUID.randomUUID(),name),false);
            var p=new ServerPlayer(h.getLevel().getServer(),h.getLevel(),cookie.gameProfile(),cookie.clientInformation());
            var connection=new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
            mateChannels.add(new EmbeddedChannel(connection));net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(connection);
            p.server.getPlayerList().placeNewPlayer(connection,p,cookie);mates.add(p);p.setGameMode(GameType.SURVIVAL);p.setNoGravity(true);
            p.setInvulnerable(false);p.getAbilities().invulnerable=false;
            try{var field=ServerPlayer.class.getDeclaredField("spawnInvulnerableTime");field.setAccessible(true);field.setInt(p,0);}
            catch(ReflectiveOperationException failure){throw new IllegalStateException(failure);}
            p.moveTo(position,yaw,0);p.setOnGround(true);p.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.DIAMOND_SWORD));
            h.assertTrue(PrototypeCombatAccess.joinTrial(p,player),"five-player practice membership failed");return p;
        }
        @Override public void close(){if(closed)return;closed=true;cleanup.forEach(Runnable::run);observers.forEach(NeoForge.EVENT_BUS::unregister);CombatCastRuntime.cancel(player);
            mates.forEach(p->{CombatCastRuntime.cancel(p);p.server.getPlayerList().remove(p);});mateChannels.forEach(EmbeddedChannel::finishAndReleaseAll);
            PrototypeCombatAccess.clearTrial(player);enemy.discard();player.server.getPlayerList().remove(player);channel.finishAndReleaseAll();
            extras.forEach(net.minecraft.world.entity.Entity::discard);
            previous.forEach((position,state)->h.getLevel().setBlockAndUpdate(position,state));}
    }
}
