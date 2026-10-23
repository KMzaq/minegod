package com.sande.mythictrpg.specialskill;

import com.google.gson.*;
import com.sande.mythictrpg.control.CrowdControlService;
import com.sande.mythictrpg.data.god.*;
import com.sande.mythictrpg.godavatar.*;
import com.sande.mythictrpg.quest.reward.*;
import com.sande.mythictrpg.quest.reward.blessing.GodRank;
import com.sande.mythictrpg.raid.*;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.gametest.*;
import java.lang.reflect.Field;
import java.util.*;

import static com.sande.mythictrpg.content.ContentSystemsGameTests.connect;
import static com.sande.mythictrpg.content.ContentSystemsGameTests.disconnect;

@GameTestHolder("mythictrpg_special_skills")
@PrefixGameTestTemplate(false)
public final class SpecialSkillGameTests {
    private static ResourceLocation id(String name) { return ResourceLocation.fromNamespaceAndPath("test","special/"+name); }
    @GameTest(templateNamespace="mythictrpg_special_skills",template="empty")
    public static void optionalAndCombinedRestrictionsParse(GameTestHelper h) {
        for(String limits:new String[]{"{\"perRaidUses\":1}","{\"cooldownSeconds\":86400}",
                "{\"consumables\":[{\"itemId\":\"minecraft:paper\",\"count\":1,\"components\":{}}]}",
                "{\"perRaidUses\":1,\"cooldownSeconds\":86400,\"consumables\":[{\"itemId\":\"minecraft:paper\",\"count\":1,\"components\":{}}]}"}) {
            var json=definitionJson();json.add("limits",JsonParser.parseString(limits));
            h.assertTrue(SpecialSkillDefinition.parse(id("parse"),json)!=null,"valid selectable restriction rejected");
        }
        for(String limits:new String[]{"{}","{\"perRaidUses\":0}","{\"cooldownSeconds\":-1}","{\"surprise\":1}"}) {
            var json=definitionJson();json.add("limits",JsonParser.parseString(limits));
            boolean rejected=false;try{SpecialSkillDefinition.parse(id("invalid"),json);}catch(RuntimeException expected){rejected=true;}
            h.assertTrue(rejected,"invalid/absent use restriction silently accepted");
        }h.succeed();
    }
    @GameTest(templateNamespace="mythictrpg_special_skills",template="empty")
    public static void realTimeAndPerRaidLimitsRemainIndependent(GameTestHelper h) {
        var state=new SpecialSkillState();UUID player=UUID.randomUUID(),raid=UUID.randomUUID(),otherRaid=UUID.randomUUID();
        var limits=new SpecialSkillDefinition.UseLimits(1,86400,List.of());long now=1_000_000;
        h.assertValueEqual(state.commit(player,id("combined"),raid,limits,now,10),SpecialSkillState.Result.SUCCESS,"combined use failed");
        h.assertValueEqual(state.canUse(player,id("combined"),raid,limits,now+1,11),SpecialSkillState.Result.COOLDOWN,"real cooldown missing");
        h.assertValueEqual(state.canUse(player,id("combined"),otherRaid,limits,now+1,11),SpecialSkillState.Result.COOLDOWN,"new raid bypassed cooldown");
        h.assertValueEqual(state.canUse(player,id("combined"),raid,limits,now+86_400_000,12),SpecialSkillState.Result.RAID_LIMIT,"elapsed time reset same-raid usage");
        h.assertValueEqual(state.canUse(player,id("combined"),otherRaid,limits,now+86_400_000,12),SpecialSkillState.Result.READY,"new raid after real cooldown failed");
        h.assertValueEqual(state.canUse(UUID.randomUUID(),id("combined"),raid,limits,now,10),SpecialSkillState.Result.READY,"another player inherited use");
        h.succeed();
    }
    @GameTest(templateNamespace="mythictrpg_special_skills",template="empty")
    public static void savedUseDoesNotResetOnReconnectOrRestart(GameTestHelper h) {
        var state=new SpecialSkillState();UUID player=UUID.randomUUID(),raid=UUID.randomUUID();
        var limits=new SpecialSkillDefinition.UseLimits(1,86400,List.of());
        state.commit(player,id("saved"),raid,limits,1000,5);
        var restored=SpecialSkillState.load(state.save(new CompoundTag(),h.getLevel().registryAccess()),h.getLevel().registryAccess());
        h.assertTrue(restored.isReady(),"valid special use save rejected");
        h.assertTrue(restored.remainingMillis(player,id("saved"),2000)==86_399_000,"wall clock stopped during offline/restart interval");
        h.assertTrue(restored.raidUses(player,id("saved"),raid)==1,"per-attempt use vanished on reload");
        h.assertValueEqual(restored.canUse(player,id("saved"),raid,limits,2000,5),SpecialSkillState.Result.DUPLICATE,"same-tick replay accepted after reload");
        var raw=restored.save(new CompoundTag(),h.getLevel().registryAccess());raw.putInt("dataVersion",99);
        var rejected=SpecialSkillState.load(raw,h.getLevel().registryAccess());
        h.assertTrue(!rejected.isReady()&&rejected.save(new CompoundTag(),h.getLevel().registryAccess()).equals(raw),"invalid save reset free use or lost original");
        h.succeed();
    }
    @GameTest(templateNamespace="mythictrpg_special_skills",template="empty")
    public static void individualLimitModesAndDuplicateGuard(GameTestHelper h) {
        var state=new SpecialSkillState();var player=UUID.randomUUID();var raid=UUID.randomUUID();
        var perRaid=new SpecialSkillDefinition.UseLimits(2,0,List.of());
        state.commit(player,id("raid"),raid,perRaid,1000,0);
        h.assertValueEqual(state.canUse(player,id("raid"),raid,perRaid,1001,0),SpecialSkillState.Result.DUPLICATE,"zero tick replay accepted");
        h.assertValueEqual(state.commit(player,id("raid"),raid,perRaid,1001,1),SpecialSkillState.Result.SUCCESS,"second configured charge blocked");
        h.assertValueEqual(state.canUse(player,id("raid"),raid,perRaid,1002,2),SpecialSkillState.Result.RAID_LIMIT,"third raid use accepted");
        var realOnly=new SpecialSkillDefinition.UseLimits(0,1,List.of());state.commit(player,id("time"),raid,realOnly,5000,4);
        h.assertValueEqual(state.canUse(player,id("time"),raid,realOnly,6000,5),SpecialSkillState.Result.READY,"real-only cooldown inherited per-raid gate");
        var itemOnly=new SpecialSkillDefinition.UseLimits(0,0,List.of(new NpcRewardEntry(ResourceLocation.parse("minecraft:paper"),1)));
        state.commit(player,id("items"),raid,itemOnly,6000,6);
        h.assertValueEqual(state.canUse(player,id("items"),raid,itemOnly,6001,7),SpecialSkillState.Result.READY,"item-only rule acquired hidden cooldown");h.succeed();
    }
    @GameTest(templateNamespace="mythictrpg_special_skills",template="empty")
    public static void normalAndMechanicCrowdControlRemainSeparate(GameTestHelper h) {
        Zombie target=EntityType.ZOMBIE.create(h.getLevel());h.assertTrue(target!=null,"zombie fixture");
        try {
            h.assertTrue(CrowdControlService.applyStun(target,100,true,false),"normal stun failed");
            h.assertTrue(CrowdControlService.applyStun(target,200,false,true),"mechanic lock failed");
            h.assertTrue(CrowdControlService.cleanseOne(target),"normal stun was not cleansed");
            h.assertTrue(CrowdControlService.isStunned(target)&&!CrowdControlService.cleanseOne(target),"mechanic lock was cleansed");
            target.getPersistentData().putBoolean(CrowdControlService.IMMUNITY_TAG,true);
            h.assertTrue(!CrowdControlService.applyStun(target,10,true,false),"authored normal-stun immunity ignored");
            h.assertTrue(CrowdControlService.applyStun(target,10,false,true),"mechanic accidentally blocked by normal immunity");
        }finally{target.discard();}h.succeed();
    }
    @GameTest(templateNamespace="mythictrpg_special_skills",template="empty")
    public static void stunStopsRealMovementAndDirectAttackDamage(GameTestHelper h) {
        Zombie attacker=EntityType.ZOMBIE.create(h.getLevel()),victim=EntityType.ZOMBIE.create(h.getLevel());
        h.assertTrue(attacker!=null&&victim!=null,"CC combat fixtures");
        var anchor=Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(2,3,2)));
        try {
            attacker.setNoAi(true);victim.setNoAi(true);attacker.setPos(anchor);victim.setPos(anchor.add(2,0,0));
            h.getLevel().addFreshEntity(attacker);h.getLevel().addFreshEntity(victim);
            CrowdControlService.applyStun(attacker,100,true,false);attacker.setPos(anchor.add(1,0,0));
            CrowdControlService.tick(new net.neoforged.neoforge.event.tick.ServerTickEvent.Post(() -> true,h.getLevel().getServer()));
            h.assertTrue(attacker.position().distanceToSqr(anchor)<.00001,"stunned actor was not held at its server position");
            h.assertTrue(!victim.hurt(victim.damageSources().mobAttack(attacker),5),"stunned actor applied direct attack damage");
            CrowdControlService.cleanseOne(attacker);
            h.assertTrue(victim.hurt(victim.damageSources().mobAttack(attacker),5),"cleansing did not release attack restriction");
        }finally{attacker.discard();victim.discard();}h.succeed();
    }
    @GameTest(templateNamespace="mythictrpg_special_skills",template="empty")
    public static void exactConsumablesShareInventoryAvailability(GameTestHelper h) {
        var channels=new ArrayList<EmbeddedChannel>();var player=connect(h,"SpecialCost",channels);
        try {
            player.getInventory().clearContent();var components=new CompoundTag();components.putInt("minecraft:custom_model_data",731);
            var custom=new NpcRewardEntry(ResourceLocation.parse("minecraft:paper"),2,components);
            player.getInventory().setItem(0,custom.createStack(player.registryAccess()));
            h.assertTrue(SpecialSkillRuntime.itemCosts(player,List.of(custom))!=null,"matching components not recognized");
            h.assertTrue(SpecialSkillRuntime.itemCosts(player,List.of(custom,custom))==null,"same stack counted twice across requirements");
            h.assertTrue(SpecialSkillRuntime.itemCosts(player,List.of(new NpcRewardEntry(ResourceLocation.parse("minecraft:paper"),1)))==null,
                    "component-free requirement accepted a different component variant");
            var wrong=new ItemStack(Items.PAPER,2);wrong.set(DataComponents.CUSTOM_MODEL_DATA,new CustomModelData(732));player.getInventory().setItem(0,wrong);
            h.assertTrue(SpecialSkillRuntime.itemCosts(player,List.of(custom))==null,"different custom item paid the cost");
        }finally{disconnect(player,channels);}h.succeed();
    }
    @GameTest(templateNamespace="mythictrpg_special_skills",template="empty",batch="special_gameplay",timeoutTicks=300)
    public static void actualRewardRaidSupportAndOneShotVisualLifecycle(GameTestHelper h) {
        try(var f=new Fixture(h)) {
            h.assertValueEqual(SpecialSkillRuntime.cast(f.player,f.skill.id()),SpecialSkillRuntime.Result.NOT_OWNED,"unearned support accepted");
            var reward=RewardClaimService.INSTANCE.issue(f.player,f.skill.providerGodId(),id("receipt/"+UUID.randomUUID()),
                    new ResolvedQuestReward("fixture",List.of(new ProgressionRewardEntry(ProgressionRewardEntry.Kind.SPECIAL,f.skill.id())),List.of()));
            h.assertTrue(reward.succeeded(),"actual special reward failed: "+reward.reason());
            var original=f.player.position();f.player.setPos(original.add(30,0,0));
            h.assertValueEqual(SpecialSkillRuntime.cast(f.player,f.skill.id()),SpecialSkillRuntime.Result.NOT_IN_ACTIVE_RAID,"outside-arena support accepted");
            f.player.setPos(original);CrowdControlService.applyStun(f.player,100,true,false);
            h.assertValueEqual(SpecialSkillRuntime.cast(f.player,f.skill.id()),SpecialSkillRuntime.Result.INVALID_PLAYER,"stunned caster used support");
            CrowdControlService.cleanseOne(f.player);
            h.assertValueEqual(SpecialSkillRuntime.cast(f.player,f.skill.id()),SpecialSkillRuntime.Result.MISSING_ITEM,"missing consumable allowed support");
            h.assertTrue(SpecialSkillState.get(f.player.server).raidUses(f.player.getUUID(),f.skill.id(),f.attempt.id())==0,"pre-cast failures consumed raid use");
            var bindingBefore=GodAvatarRegistryState.get(f.player.server).find(f.skill.providerGodId());
            f.player.getInventory().setItem(0,new ItemStack(Items.PAPER));
            float before=f.target.getHealth();
            h.assertValueEqual(SpecialSkillRuntime.cast(f.player,f.skill.id()),SpecialSkillRuntime.Result.SUCCESS,"owned raid support failed");
            h.assertTrue(f.target.getHealth()<before,"support did not apply actual protected-raid damage");
            h.assertTrue(CrowdControlService.isStunned(f.target),"support stun was not applied");
            h.assertTrue(f.player.getInventory().getItem(0).isEmpty(),"successful support did not consume exact item");
            h.assertTrue(SpecialSkillState.get(f.player.server).raidUses(f.player.getUUID(),f.skill.id(),f.attempt.id())==1,"raid use not committed");
            h.assertTrue(GodAvatarRegistryState.get(f.player.server).find(f.skill.providerGodId()).equals(bindingBefore),"temporary support changed persistent NPC identity");
            var avatars=h.getLevel().getEntitiesOfClass(SupportAvatarEntity.class,f.arena.bounds());
            h.assertTrue(avatars.size()==1&&avatars.getFirst().providerGodId().orElseThrow().equals(f.skill.providerGodId()),"temporary appearance lost canonical provider");
            var avatar=avatars.getFirst();h.assertTrue(!avatar.getType().canSerialize(),"support appearance would survive restart and replay");
            h.assertTrue(!avatar.isPickable()&&!avatar.hurt(f.player.damageSources().playerAttack(f.player),10),"support avatar gained combat/reward authority");
            avatar.tickCount=39;h.getLevel().tickNonPassenger(avatar);
            h.assertTrue(avatar.isRemoved(),"support did not leave after one action");
            h.assertTrue(f.target.getHealth()<before,"visual cleanup reset damage");
            h.assertValueEqual(SpecialSkillRuntime.cast(f.player,f.skill.id()),SpecialSkillRuntime.Result.DUPLICATE,"same cast was replayed");
        }catch(Exception failure){if(failure instanceof GameTestAssertException assertion)throw assertion;throw new IllegalStateException(failure);}h.succeed();
    }
    private static JsonObject definitionJson() {
        return JsonParser.parseString("""
            {"schemaVersion":1,"name":"fixture support","providerGodId":"test:special/provider","manaCost":10,
             "limits":{"perRaidUses":1,"cooldownSeconds":86400,"consumables":[{"itemId":"minecraft:paper","count":1,"components":{}}]},
             "action":{"type":"LIGHTNING_STRIKE","amount":8,"range":3,"radius":4,"stunTicks":10},"intrinsicPower":0}
            """).getAsJsonObject();
    }
    private static Field field(Class<?> type,String name)throws Exception{var value=type.getDeclaredField(name);value.setAccessible(true);return value;}
    private static final class Fixture implements AutoCloseable {
        final GameTestHelper h;final ServerPlayer player;final Zombie target;final SpecialSkillDefinition skill;
        final RaidState state;final RaidState.Attempt attempt;final RaidDefinition.Arena arena;
        final List<EmbeddedChannel> channels=new ArrayList<>();final Object oldGod,oldAppearance;final Map<ResourceLocation,SpecialSkillDefinition> oldSkills;
        @SuppressWarnings("unchecked")Fixture(GameTestHelper h)throws Exception {
            this.h=h;oldGod=field(GodDefinitionManager.class,"snapshot").get(GodDefinitionManager.INSTANCE);
            oldAppearance=field(GodAvatarDefinitionManager.class,"definitions").get(GodAvatarDefinitionManager.INSTANCE);
            oldSkills=SpecialSkillDefinitions.INSTANCE.snapshot();skill=SpecialSkillDefinition.parse(id("actual"),definitionJson());
            var previous=(GodDefinitionManager.ProgressionSnapshot)oldGod;
            var god=new GodDefinition(1,Component.literal("Fixture Provider"),id("origin"),id("faction"),Set.of(),Optional.empty(),Optional.empty(),Optional.empty(),Optional.of(GodRank.HIGHEST));
            field(GodDefinitionManager.class,"snapshot").set(GodDefinitionManager.INSTANCE,new GodDefinitionManager.ProgressionSnapshot(Map.of(skill.providerGodId(),god),
                    previous.unlockIndex(),previous.appearanceIndex(),previous.categoryIndex(),previous.generation()+1));
            var appearance=new GodAvatarDefinition(skill.providerGodId(),new GodAvatarDefinition.Appearance(0,1),
                    new GodAvatarDefinition.Stats(20,0,0,0,16),new GodAvatarDefinition.Movement(false,false,false,0,0,0),
                    new GodAvatarDefinition.Combat(false,false,false,false),new GodAvatarDefinition.Placement(false,0),4);
            field(GodAvatarDefinitionManager.class,"definitions").set(GodAvatarDefinitionManager.INSTANCE,Map.of(skill.providerGodId(),appearance));
            SpecialSkillDefinitions.INSTANCE.apply(Map.of(skill.id(),skill),null,null);
            player=connect(h,"SpecialOwner",channels);var anchor=Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(2,3,2)));player.moveTo(anchor.x,anchor.y,anchor.z,0,0);
            for(int x=(int)Math.floor(anchor.x-8)>>4;x<=((int)Math.floor(anchor.x+8)>>4);x++)
                for(int z=(int)Math.floor(anchor.z-8)>>4;z<=((int)Math.floor(anchor.z+8)>>4);z++)h.getLevel().getChunk(x,z);
            target=EntityType.ZOMBIE.create(h.getLevel());target.setNoAi(true);target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);target.setHealth(1000);
            target.setPos(anchor.add(0,0,3));h.getLevel().addFreshEntity(target);
            var json=JsonParser.parseString("""
                {"schemaVersion":1,"displayName":"support fixture","mode":"PUBLIC_WORLD","arenas":["test:special/arena"],
                 "minimumPlayers":1,"maximumPlayers":1,"queueTimeoutTicks":1200,"combatTimeoutTicks":1200,"livesPerPlayer":1,
                 "disconnectGraceTicks":20,"boss":{"entityType":"minecraft:zombie"},"phases":[],
                 "entryCondition":{"type":"mythictrpg:always"},"rewardEligibility":"ALL_FROZEN_ROSTER","rewardGodId":"test:special/provider","rewards":[]}
                """).getAsJsonObject();
            state=RaidState.get(player.server);attempt=state.create(player.getUUID(),UUID.randomUUID(),RaidDefinition.parse(id("raid"),json),Set.of(player.getUUID()),player.server.overworld().getGameTime());
            arena=new RaidDefinition.Arena(id("arena"),h.getLevel().dimension().location(),new AABB(anchor.add(-8,-4,-8),anchor.add(8,8,8)),anchor,anchor.add(0,0,3),anchor.add(12,0,0),"{}");
            field(RaidState.Attempt.class,"status").set(attempt,RaidState.Status.ACTIVE);field(RaidState.Attempt.class,"arena").set(attempt,arena);
            field(RaidState.Attempt.class,"bossId").set(attempt,target.getUUID());
            ((Set<UUID>)field(RaidState.Attempt.class,"spawnedEntities").get(attempt)).add(target.getUUID());
            target.getPersistentData().putUUID("mythictrpg_raid_attempt",attempt.id());
        }
        @SuppressWarnings("unchecked")public void close()throws Exception {
            field(GodDefinitionManager.class,"snapshot").set(GodDefinitionManager.INSTANCE,oldGod);
            field(GodAvatarDefinitionManager.class,"definitions").set(GodAvatarDefinitionManager.INSTANCE,oldAppearance);SpecialSkillDefinitions.INSTANCE.apply(oldSkills,null,null);
            ((Map<UUID,RaidState.Attempt>)field(RaidState.class,"attempts").get(state)).remove(attempt.id());
            h.getLevel().getEntitiesOfClass(SupportAvatarEntity.class,arena.bounds()).forEach(SupportAvatarEntity::discard);
            target.discard();disconnect(player,channels);
        }
    }
}
