package com.sande.mythictrpg.skills;

import com.sande.mythictrpg.prototype.PrototypeCombatAccess;
import com.sande.mythictrpg.weaponskill.WeaponSkillDefinition;
import com.sande.mythictrpg.weaponskill.WeaponSkillRuntime;
import com.sande.mythictrpg.weaponskill.WeaponSkillState;
import com.sande.mythictrpg.weaponskill.PrototypeInteractionPriority;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.*;

/** Runs production pre-tick movement and damage, with real player identities and registered trial targets. */
@GameTestHolder("mythictrpg_combat_prototype")
@PrefixGameTestTemplate(false)
public final class CombatCastGameTests {
    private static final ResourceLocation WAVE=ResourceLocation.parse("proto_cbt:blade_wave");
    private static final WeaponSkillDefinition.BladeWave ACTION=new WeaponSkillDefinition.BladeWave(8,4,1,10,0.25,3,4,6);

    @GameTest(templateNamespace="mythictrpg_combat_prototype",template="empty",batch="prototype_cast_01",timeoutTicks=120)
    public static void wildernessCastIsPaidAndOldSkillsRemainUsable(GameTestHelper h) {
        try(var f=new Fixture(h)) {
            double before=SkillMana.mana(f.player);
            var scope=PrototypeCombatAccess.scope(f.player).orElseThrow();
            h.assertTrue(PrototypeCombatAccess.wilderness(f.player,scope),"unregistered world did not provide wilderness scope");
            h.assertValueEqual(f.cast(),WeaponSkillRuntime.Result.SUCCESS,"wilderness rejected prototype cast");
            h.assertTrue(SkillMana.mana(f.player)==before-24&&CombatCastRuntime.locked(f.player),"wilderness cast did not preserve payment and recovery");
            CombatCastRuntime.cancel(f.player);
            var legacy=f.connect("LegacyCaster");legacy.setHealth(10);
            h.assertValueEqual(SkillExecutor.cast(legacy,SkillCastEvents.Source.LEARNED,ResourceLocation.parse("test:old_heal"),"old heal",5,10,
                    new WeaponSkillDefinition.SelfHeal(2)),WeaponSkillRuntime.Result.SUCCESS,"prototype restriction leaked into existing skills");
            h.assertTrue(legacy.getHealth()==12&&SkillMana.mana(legacy)==195&&SkillMana.mana(f.player)==176,"existing paid skill behavior changed or leaked between players");
            h.succeed();
        }
    }

    @GameTest(templateNamespace="mythictrpg_combat_prototype",template="empty",batch="prototype_cast_02",timeoutTicks=120)
    public static void wildernessWaveDamagesVillagerAndHostileWithoutArenaRegistration(GameTestHelper h) {
        var f=new Fixture(h);var villager=EntityType.VILLAGER.create(h.getLevel());
        if(villager==null)throw new IllegalStateException("wild villager fixture");
        villager.setNoAi(true);villager.setNoGravity(true);villager.moveTo(f.origin.add(0,0,2));h.getLevel().addFreshEntity(villager);
        f.extras.add(villager);var hostile=f.enemy(4);
        h.startSequence().thenExecute(()->{
            try{h.assertValueEqual(f.cast(),WeaponSkillRuntime.Result.SUCCESS,"wilderness wave was rejected");}catch(Throwable failure){f.close();throw failure;}
        }).thenIdle(16).thenExecute(()->{
            try {
                h.assertTrue(villager.getHealth()==12&&hostile.getHealth()==36,"wilderness wave did not apply first/next damage to villager and hostile");
                h.assertTrue(f.observer.hits.size()==2&&f.observer.finished.size()==1&&!f.observer.finished.getFirst().cancelled(),"wilderness cast lifecycle changed");
            }finally{f.close();}
        }).thenSucceed();
    }

    @GameTest(templateNamespace="mythictrpg_combat_prototype",template="empty",batch="prototype_cast_03",timeoutTicks=120)
    public static void joiningPartyAfterWaveLaunchProtectsPlayerWithoutConsumingPierce(GameTestHelper h) {
        var f=new Fixture(h);var peer=f.connect("WaveFutureAlly");peer.moveTo(f.origin.add(0,0,6));var hostile=f.enemy(8);
        h.startSequence().thenExecute(()->{
            try {
                var scope=PrototypeCombatAccess.scope(f.player).orElseThrow();
                boolean previousPvp=f.player.server.isPvpAllowed();
                String policy="pvp="+previousPvp+", protected="+com.sande.mythictrpg.combat.CombatTargeting.protectedFromPlayer(f.player,peer)
                        +", canHarm="+f.player.canHarmPlayer(peer)+"/"+peer.canHarmPlayer(f.player)
                        +", wilderness="+PrototypeCombatAccess.wilderness(f.player,scope)+", inside="+scope.bounds().contains(peer.position());
                org.slf4j.LoggerFactory.getLogger(CombatCastGameTests.class).info("Wilderness PvP fixture before explicit enable: {}",policy);
                f.resources.add(()->f.player.server.setPvpAllowed(previousPvp));
                if(!previousPvp)h.assertTrue(!PrototypeCombatAccess.enemy(f.player,scope,peer),"server PvP=false did not protect the unrelated player");
                // GameTestServer does not read a dedicated server's pvp=true property.
                f.player.server.setPvpAllowed(true);
                h.assertTrue(PrototypeCombatAccess.enemy(f.player,scope,peer),"unrelated player was excluded after enabling fixture PvP; previous "+policy);
                h.assertValueEqual(f.cast(),WeaponSkillRuntime.Result.SUCCESS,"wilderness wave was rejected");
            }catch(Throwable failure){f.close();throw failure;}
        }).thenIdle(5).thenExecute(()->{
            try {f.resources.add(com.sande.mythictrpg.recording.channel.FtbChannelGameTestActions.open(f.player,peer));}
            catch(Exception failure){f.close();throw new IllegalStateException(failure);}
        }).thenIdle(11).thenExecute(()->{
            try {
                h.assertTrue(peer.getHealth()==20&&hostile.getHealth()==32,"live party change failed to protect peer or consumed the first pierce");
                h.assertTrue(f.observer.hits.size()==1&&f.observer.hits.getFirst().target()==hostile,"party joining fabricated a player hit");
            }finally{f.close();}
        }).thenSucceed();
    }

    @GameTest(templateNamespace="mythictrpg_combat_prototype",template="empty",batch="prototype_cast_04",timeoutTicks=120)
    public static void leavingTrialBoundsCannotFallBackToWildernessOrReplaceMembership(GameTestHelper h) {
        try(var f=new Fixture(h)) {
            f.register();var trial=PrototypeCombatAccess.scope(f.player).orElseThrow();
            var outsider=f.connect("TrialOutsider");outsider.moveTo(f.origin.add(2,0,0));
            h.assertTrue(PrototypeCombatAccess.scope(outsider).isEmpty(),"uninvited player acquired a wilderness scope inside reserved practice bounds");
            h.assertTrue(PrototypeCombatAccess.joinTrial(outsider,f.player),"explicit practice join rejected a player because wilderness is now available");
            h.assertTrue(PrototypeCombatAccess.scope(outsider).filter(trial::equals).isPresent(),"practice join did not use the exact owner's scope");
            f.player.moveTo(f.origin.add(10,0,0));double before=SkillMana.mana(f.player);
            h.assertTrue(PrototypeCombatAccess.scope(f.player).isEmpty(),"leaving an active trial silently acquired wilderness authority");
            h.assertValueEqual(f.cast(),WeaponSkillRuntime.Result.UNAVAILABLE,"outside trial accepted a replacement wilderness cast");
            h.assertTrue(SkillMana.mana(f.player)==before&&!CombatCastRuntime.locked(f.player),"invalid trial cast consumed resources");
            AABB replacement=f.player.getBoundingBox().inflate(2);
            h.assertTrue(!PrototypeCombatAccess.registerTrial(f.player,replacement,Set.of()),"outside trial replaced its still-active membership");
            PrototypeCombatAccess.clearTrial(f.player);
            h.assertTrue(PrototypeCombatAccess.scope(f.player).filter(s->PrototypeCombatAccess.wilderness(f.player,s)).isPresent(),"ending trial did not restore normal wilderness access");
            h.succeed();
        }
    }

    @GameTest(templateNamespace="mythictrpg_combat_prototype",template="empty",batch="prototype_cast_13",timeoutTicks=120)
    public static void returnedRaidMemberCanUseWildernessWhileVictoryRewardsRemainPending(GameTestHelper h) throws Exception {
        try(var f=new Fixture(h)) {
            var state=com.sande.mythictrpg.raid.RaidState.get(f.player.server);
            var definition=com.sande.mythictrpg.raid.RaidCatalog.INSTANCE.find(ResourceLocation.parse("mythictrpg:trial/sealed_guardian")).orElseThrow();
            var attempt=state.create(f.player.getUUID(),UUID.randomUUID(),definition,Set.of(f.player.getUUID()),SkillMana.now(f.player));
            var entries=com.sande.mythictrpg.raid.RaidState.class.getDeclaredField("attempts");entries.setAccessible(true);
            f.resources.add(()->{((Map<?,?>)entries.get(state)).remove(attempt.id());state.setDirty();});
            var status=attempt.getClass().getDeclaredField("status");status.setAccessible(true);
            var arena=attempt.getClass().getDeclaredField("arena");arena.setAccessible(true);
            AABB bounds=new AABB(f.origin.add(-4,-2,-2),f.origin.add(4,6,14));
            arena.set(attempt,new com.sande.mythictrpg.raid.RaidDefinition.Arena(definition.arenaIds().getFirst(),
                    f.player.serverLevel().dimension().location(),bounds,f.origin,f.origin.add(0,0,2),f.origin.add(12,0,0),"{}"));
            status.set(attempt,com.sande.mythictrpg.raid.RaidState.Status.ACTIVE);
            var active=PrototypeCombatAccess.scope(f.player).orElseThrow();
            h.assertTrue(active.id().equals(attempt.id())&&!PrototypeCombatAccess.wilderness(f.player,active),"active raid did not own the player's scope");
            f.player.moveTo(f.origin.add(12,0,0));
            h.assertTrue(PrototypeCombatAccess.scope(f.player).isEmpty(),"active raid member escaped bounds through a wilderness fallback");
            status.set(attempt,com.sande.mythictrpg.raid.RaidState.Status.CLEANUP_PENDING);
            h.assertTrue(PrototypeCombatAccess.scope(f.player).isEmpty(),"unfinished cleanup released its scope too early");
            status.set(attempt,com.sande.mythictrpg.raid.RaidState.Status.VICTORY_PENDING);
            var returned=attempt.member(f.player.getUUID()).orElseThrow().getClass().getDeclaredField("returned");returned.setAccessible(true);
            returned.setBoolean(attempt.member(f.player.getUUID()).orElseThrow(),true);
            h.assertTrue(!attempt.ownsArena()&&!attempt.occupiesPlayerSlot(),"reward-only pending state still owns arena or freezes player membership");
            h.assertTrue(PrototypeCombatAccess.scope(f.player).filter(s->PrototypeCombatAccess.wilderness(f.player,s)).isPresent(),
                    "returned player lost wilderness skills because victory reward settlement is pending");
            h.assertTrue(!PrototypeCombatAccess.valid(f.player,active),"completed raid's old scope remained valid");
            h.assertTrue(PrototypeCombatAccess.registerTrial(f.player,f.player.getBoundingBox().inflate(2),Set.of()),
                    "reward settlement pending prevented a returned player from registering a normal practice trial");
            h.succeed();
        }
    }

    @GameTest(templateNamespace="mythictrpg_combat_prototype",template="empty",batch="prototype_cast_05",timeoutTicks=120)
    public static void windupSwapCancelsWithoutRefundAndRecoveryBlocksAttackAndItems(GameTestHelper h) {
        var f=new Fixture(h);var target=f.enemy(4);f.register();
        h.startSequence().thenExecute(()->{
            try {
                h.assertValueEqual(f.cast(),WeaponSkillRuntime.Result.SUCCESS,"registered cast failed");
                h.assertTrue(SkillMana.mana(f.player)==176&&CombatCastRuntime.locked(f.player),"payment and lock were not committed together");
                var attack=new AttackEntityEvent(f.player,target);NeoForge.EVENT_BUS.post(attack);
                h.assertTrue(attack.isCanceled(),"basic attack escaped shared lock");
                var item=new LivingEntityUseItemEvent.Start(f.player,new ItemStack(Items.SHIELD),InteractionHand.OFF_HAND,72000);NeoForge.EVENT_BUS.post(item);
                h.assertTrue(item.isCanceled(),"shield or food escaped shared lock");
                h.assertValueEqual(SkillExecutor.cast(f.player,SkillCastEvents.Source.LEARNED,ResourceLocation.parse("test:locked_heal"),"heal",5,10,
                        new WeaponSkillDefinition.SelfHeal(2)),WeaponSkillRuntime.Result.INVALID_PLAYER,"second input escaped recovery lock");
                f.player.getInventory().setItem(1,new ItemStack(Items.IRON_SWORD));
                f.player.connection.handleSetCarriedItem(new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(1));
                f.player.connection.handleSetCarriedItem(new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(0));
                h.assertTrue(f.player.getMainHandItem().is(Items.DIAMOND_SWORD)&&f.observer.finished.size()==1&&f.observer.finished.getFirst().cancelled(),
                        "same-tick packet swap and restore revived the pending cast");
                h.assertTrue(f.player.getAttackStrengthScale(0)<0.1,"same-tick swap and restore bypassed normal attack charge reset");
            }catch(Throwable failure){f.close();throw failure;}
        }).thenIdle(3).thenExecute(()->{
            try {
                h.assertTrue(target.getHealth()==40&&CombatCastRuntime.locked(f.player),"windup cancellation erased remaining recovery");
                h.assertTrue(f.observer.finished.size()==1&&f.observer.finished.getFirst().cancelled(),"cancelled cast did not settle exactly once");
                h.assertTrue(f.remaining()>145&&SkillMana.mana(f.player)<180,"cancelled cast refunded cost or cooldown");
            }catch(Throwable failure){f.close();throw failure;}
        }).thenIdle(8).thenExecute(()->{
            try {
                h.assertTrue(!CombatCastRuntime.locked(f.player)&&target.getHealth()==40,"cancelled projectile appeared or recovery never ended");
                h.assertTrue(f.remaining()>130&&f.observer.finished.size()==1,"weapon swap reset cooldown or duplicated settlement");
            }finally{f.close();}
        }).thenSucceed();
    }

    @GameTest(templateNamespace="mythictrpg_combat_prototype",template="empty",batch="prototype_cast_06",timeoutTicks=120)
    public static void movingWavePiercesOnlyThreeDistinctEnemiesAndPassesAllies(GameTestHelper h) {
        var f=new Fixture(h);var first=f.enemy(2);var second=f.enemy(4);var third=f.enemy(6);var fourth=f.enemy(8);
        var ally=f.connect("WaveAlly");ally.moveTo(f.origin.x,f.origin.y,f.origin.z+1,0,0);f.register();
        h.startSequence().thenExecute(()->{
            try {
                h.assertValueEqual(f.cast(),WeaponSkillRuntime.Result.SUCCESS,"wave cast rejected");
                f.player.setYRot(180); // Authored direction belongs to approval, not to launch/current view.
                h.assertTrue(first.getHealth()==40&&f.observer.hits.isEmpty(),"moving wave became an instant aimed attack");
            }catch(Throwable failure){f.close();throw failure;}
        }).thenIdle(16).thenExecute(()->{
            try {
                h.assertTrue(first.getHealth()==32&&second.getHealth()==36&&third.getHealth()==36&&fourth.getHealth()==40,
                        "wave did not resolve 8/4/4 to the first three targets");
                h.assertTrue(ally.getHealth()==ally.getMaxHealth(),"ally collision consumed or took wave damage");
                h.assertTrue(first.getDeltaMovement().horizontalDistanceSqr()<1.0E-9,"blade wave added knockback");
                h.assertTrue(f.observer.starts==1&&f.observer.hits.size()==3&&f.observer.finished.size()==1
                        &&f.observer.finished.getFirst().hitCount()==3&&!f.observer.finished.getFirst().cancelled(),"wave lifecycle was duplicated or incomplete");
            }finally{f.close();}
        }).thenSucceed();
    }

    @GameTest(templateNamespace="mythictrpg_combat_prototype",template="empty",batch="prototype_cast_07",timeoutTicks=120)
    public static void weaponSwapAfterLaunchKeepsOriginalProjectileAndCast(GameTestHelper h) {
        var f=new Fixture(h);var target=f.enemy(8);f.register();
        h.startSequence().thenExecute(()->{
            try{h.assertValueEqual(f.cast(),WeaponSkillRuntime.Result.SUCCESS,"wave cast rejected");}catch(Throwable failure){f.close();throw failure;}
        }).thenIdle(5).thenExecute(()->{
            try {
                f.player.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.IRON_SWORD));CombatCastRuntime.heldChanged(f.player);
                h.assertTrue(CombatCastRuntime.locked(f.player)&&f.observer.finished.isEmpty(),"post-launch swap removed lock or cancelled emitted wave");
            }catch(Throwable failure){f.close();throw failure;}
        }).thenIdle(10).thenExecute(()->{
            try {
                h.assertTrue(target.getHealth()==32&&f.observer.hits.size()==1&&f.observer.finished.size()==1&&!f.observer.finished.getFirst().cancelled(),
                        "emitted projectile lost its original paid cast after weapon swap");
            }finally{f.close();}
        }).thenSucceed();
    }

    @GameTest(templateNamespace="mythictrpg_combat_prototype",template="empty",batch="prototype_cast_08",timeoutTicks=120)
    public static void encounterRevocationRemovesAlreadyEmittedWaveWithoutRefund(GameTestHelper h) {
        var f=new Fixture(h);var target=f.enemy(8);f.register();
        h.startSequence().thenExecute(()->{
            try{h.assertValueEqual(f.cast(),WeaponSkillRuntime.Result.SUCCESS,"wave cast rejected");}catch(Throwable failure){f.close();throw failure;}
        }).thenIdle(5).thenExecute(()->PrototypeCombatAccess.clearTrial(f.player)).thenIdle(10).thenExecute(()->{
            try {
                h.assertTrue(target.getHealth()==40&&f.observer.hits.isEmpty()&&f.observer.finished.size()==1&&f.observer.finished.getFirst().cancelled(),
                        "projectile remained authoritative after encounter ended");
                h.assertTrue(f.remaining()>130&&SkillMana.mana(f.player)<180,"encounter cancellation reset paid resources");
            }finally{f.close();}
        }).thenSucceed();
    }

    @GameTest(templateNamespace="mythictrpg_combat_prototype",template="empty",batch="prototype_cast_09",timeoutTicks=120)
    public static void glassStopsWaveBeforeTargetBehindIt(GameTestHelper h){wall(h,Blocks.GLASS.defaultBlockState());}
    @GameTest(templateNamespace="mythictrpg_combat_prototype",template="empty",batch="prototype_cast_10",timeoutTicks=120)
    public static void fenceCollisionShapeStopsWave(GameTestHelper h){wall(h,Blocks.OAK_FENCE.defaultBlockState());}
    private static void wall(GameTestHelper h,BlockState block) {
        var f=new Fixture(h);var target=f.enemy(4);f.block(BlockPos.containing(f.origin).offset(0,1,2),block);f.register();
        h.startSequence().thenExecute(()->{
            try{h.assertValueEqual(f.cast(),WeaponSkillRuntime.Result.SUCCESS,"wall cast should be paid");}catch(Throwable failure){f.close();throw failure;}
        }).thenIdle(16).thenExecute(()->{
            try {
                h.assertTrue(target.getHealth()==40&&f.observer.hits.isEmpty(),"swept wave tunneled through a thin/partial collision shape");
                h.assertTrue(f.observer.finished.size()==1&&!f.observer.finished.getFirst().cancelled()&&f.remaining()>130,"wall collision was refunded or left a live projectile");
            }finally{f.close();}
        }).thenSucceed();
    }

    @GameTest(templateNamespace="mythictrpg_combat_prototype",template="empty",batch="prototype_cast_11",timeoutTicks=120)
    public static void zeroHealthDamageConsumesPierceButDoesNotPublishAccessoryHit(GameTestHelper h) {
        var f=new Fixture(h);var immune=f.enemy(2);immune.setInvulnerable(true);var absorbed=f.enemy(4);
        absorbed.getAttribute(Attributes.MAX_ABSORPTION).setBaseValue(20);absorbed.setAbsorptionAmount(20);
        var third=f.enemy(6);var fourth=f.enemy(8);f.register();
        h.startSequence().thenExecute(()->{
            try {
                h.assertTrue(absorbed.getAbsorptionAmount()==20,"absorption fixture was clamped before cast: "+absorbed.getAbsorptionAmount()+" / "+absorbed.getMaxAbsorption());
                h.assertValueEqual(f.cast(),WeaponSkillRuntime.Result.SUCCESS,"zero-damage trial cast rejected");
            }catch(Throwable failure){f.close();throw failure;}
        }).thenIdle(16).thenExecute(()->{
            try {
                h.assertTrue(immune.getHealth()==40&&absorbed.getHealth()==40&&absorbed.getAbsorptionAmount()==16,
                        "immunity or absorption semantics changed: immuneHp="+immune.getHealth()+", absorbedHp="+absorbed.getHealth()+", absorption="+absorbed.getAbsorptionAmount());
                h.assertTrue(third.getHealth()==36&&fourth.getHealth()==40,"blocked enemy collision did not consume a piercing slot");
                h.assertTrue(f.observer.hits.size()==1&&f.observer.hits.getFirst().target()==third&&f.observer.finished.size()==1
                        &&f.observer.finished.getFirst().hitCount()==1,"zero health damage fabricated an accessory hit");
            }finally{f.close();}
        }).thenSucceed();
    }

    @GameTest(templateNamespace="mythictrpg_combat_prototype",template="empty",batch="prototype_cast_12",timeoutTicks=120)
    public static void directInteractionCapabilityIsSideEffectFreeAndNoninteractiveStoneRemainsSkillCandidate(GameTestHelper h) {
        try(var f=new Fixture(h)) {
            BlockPos target=BlockPos.containing(f.origin).offset(0,1,2);
            f.block(target,Blocks.CHEST.defaultBlockState());
            h.assertTrue(PrototypeInteractionPriority.directlyTargeted(f.player),"direct chest target did not receive interaction priority");
            h.assertTrue(f.player.containerMenu==f.player.inventoryMenu,"capability probe opened a menu");
            f.block(target.below(),Blocks.STONE.defaultBlockState());
            f.block(target,Blocks.OAK_DOOR.defaultBlockState());
            f.block(target.above(),Blocks.OAK_DOOR.defaultBlockState().setValue(net.minecraft.world.level.block.DoorBlock.HALF,
                    net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER));
            h.assertTrue(PrototypeInteractionPriority.directlyTargeted(f.player),"direct door target lost interaction priority");
            h.assertTrue(!h.getLevel().getBlockState(target).getValue(net.minecraft.world.level.block.DoorBlock.OPEN),"capability probe toggled door");
            f.block(target,Blocks.STONE.defaultBlockState());
            h.assertTrue(!PrototypeInteractionPriority.directlyTargeted(f.player),"plain stone incorrectly consumed skill input");
            var villager=EntityType.VILLAGER.create(h.getLevel());
            h.assertTrue(villager!=null&&PrototypeInteractionPriority.entity(villager),"villager trading was not recognized");
            f.block(target,Blocks.AIR.defaultBlockState());f.block(target.above(),Blocks.AIR.defaultBlockState());
            villager.setNoAi(true);villager.setNoGravity(true);villager.moveTo(f.origin.add(0,0,2));h.getLevel().addFreshEntity(villager);f.extras.add(villager);
            h.assertTrue(!PrototypeInteractionPriority.directlyTargeted(f.player),"villager targeting consumed weapon skill input instead of leaving trading to Shift");
            h.assertTrue(!PrototypeInteractionPriority.entity(f.enemy(4)),"ordinary hostile enemy was treated as an NPC interaction");
            h.succeed();
        }
    }

    private static final class Observer {
        final ServerPlayer player;int starts;final List<SkillCastEvents.Hit> hits=new ArrayList<>();final List<SkillCastEvents.Finished> finished=new ArrayList<>();
        Observer(ServerPlayer player){this.player=player;}
        @SubscribeEvent public void start(SkillCastEvents.Start event){if(event.context().player()==player)starts++;}
        @SubscribeEvent public void hit(SkillCastEvents.Hit event){if(event.context().player()==player)hits.add(event);}
        @SubscribeEvent public void finish(SkillCastEvents.Finished event){if(event.context().player()==player)finished.add(event);}
    }
    private static final class Fixture implements AutoCloseable {
        final GameTestHelper h;final Vec3 origin;final ServerPlayer player;final Observer observer;
        final List<ServerPlayer> players=new ArrayList<>();final List<EmbeddedChannel> channels=new ArrayList<>();
        final List<AutoCloseable> resources=new ArrayList<>();
        final List<Zombie> enemies=new ArrayList<>();final List<net.minecraft.world.entity.Entity> extras=new ArrayList<>();final Map<BlockPos,BlockState> blocks=new LinkedHashMap<>();boolean closed;
        Fixture(GameTestHelper h) {
            this.h=h;var pos=h.absolutePos(new BlockPos(1,3,1));origin=new Vec3(pos.getX()+0.5,pos.getY(),pos.getZ()+0.5);
            for(int x=(pos.getX()-2)>>4;x<=(pos.getX()+2)>>4;x++)for(int z=(pos.getZ()-2)>>4;z<=(pos.getZ()+12)>>4;z++)h.getLevel().getChunk(x,z);
            player=connect("WaveCaster");player.moveTo(origin.x,origin.y,origin.z,0,0);player.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.DIAMOND_SWORD));
            observer=new Observer(player);NeoForge.EVENT_BUS.register(observer);
        }
        ServerPlayer connect(String name) {
            if(name.length()>16)throw new IllegalArgumentException("Mock profile name exceeds vanilla's 16-character limit: "+name);
            var cookie=net.minecraft.server.network.CommonListenerCookie.createInitial(new com.mojang.authlib.GameProfile(UUID.randomUUID(),name),false);
            var p=new ServerPlayer(h.getLevel().getServer(),h.getLevel(),cookie.gameProfile(),cookie.clientInformation());
            var connection=new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
            channels.add(new EmbeddedChannel(connection));net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(connection);
            p.server.getPlayerList().placeNewPlayer(connection,p,cookie);
            // GameTest's default player mode is creative, whose damage legitimately bypasses entity invulnerability.
            p.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);p.setNoGravity(true);p.setInvulnerable(false);p.getAbilities().invulnerable=false;
            try{var field=ServerPlayer.class.getDeclaredField("spawnInvulnerableTime");field.setAccessible(true);field.setInt(p,0);}
            catch(ReflectiveOperationException failure){throw new IllegalStateException(failure);}
            players.add(p);return p;
        }
        Zombie enemy(double distance) {
            var mob=EntityType.HUSK.create(h.getLevel());if(mob==null)throw new IllegalStateException("wave target");
            mob.setNoAi(true);mob.setNoGravity(true);mob.getAttribute(Attributes.ARMOR).setBaseValue(0);mob.getAttribute(Attributes.MAX_HEALTH).setBaseValue(40);mob.setHealth(40);
            mob.moveTo(origin.x,origin.y,origin.z+distance,0,0);h.getLevel().addFreshEntity(mob);enemies.add(mob);return mob;
        }
        void register(){try{h.assertTrue(PrototypeCombatAccess.registerTrial(player,new AABB(origin.x-4,origin.y-2,origin.z-2,origin.x+4,origin.y+6,origin.z+14),
                enemies.stream().map(Zombie::getUUID).toList()),"test arena registration failed");}catch(RuntimeException|Error failure){close();throw failure;}}
        void block(BlockPos position,BlockState state){blocks.putIfAbsent(position,h.getLevel().getBlockState(position));h.getLevel().setBlock(position,state,3);}
        WeaponSkillRuntime.Result cast(){return SkillExecutor.cast(player,SkillCastEvents.Source.WEAPON,WAVE,"wave",24,160,ACTION);}
        long remaining(){return WeaponSkillState.get(player.server).account(player.getUUID()).orElseThrow().remainingTicks(WAVE.toString(),SkillMana.now(player));}
        @Override public void close(){if(closed)return;closed=true;NeoForge.EVENT_BUS.unregister(observer);
            for(var resource:resources)try{resource.close();}catch(Exception failure){throw new IllegalStateException(failure);}
            players.forEach(p->{CombatCastRuntime.cancel(p);PrototypeCombatAccess.clearTrial(p);p.server.getPlayerList().remove(p);});enemies.forEach(Zombie::discard);extras.forEach(net.minecraft.world.entity.Entity::discard);blocks.forEach((pos,state)->h.getLevel().setBlock(pos,state,3));channels.forEach(EmbeddedChannel::finishAndReleaseAll);}
    }
}
