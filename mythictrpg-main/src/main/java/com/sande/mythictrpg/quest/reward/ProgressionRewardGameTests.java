package com.sande.mythictrpg.quest.reward;

import com.sande.mythictrpg.accessory.*;
import com.sande.mythictrpg.data.god.*;
import com.sande.mythictrpg.quest.reward.blessing.GodRank;
import com.sande.mythictrpg.skills.*;
import com.sande.mythictrpg.specialskill.*;
import com.sande.mythictrpg.weaponskill.WeaponSkillDefinition;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.gametest.*;
import java.lang.reflect.Field;
import java.util.*;

import static com.sande.mythictrpg.content.ContentSystemsGameTests.connect;
import static com.sande.mythictrpg.content.ContentSystemsGameTests.disconnect;

/** Receipt ownership tests are independent of skill execution and accessory UI fixtures. */
@GameTestHolder("mythictrpg_progression_rewards")
@PrefixGameTestTemplate(false)
public final class ProgressionRewardGameTests {
    private static final ResourceLocation GOD=id("provider"),LEARNED=id("learned"),ACCESSORY=id("accessory"),SPECIAL=id("special");
    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath("test","progression/"+path); }
    private static ProgressionRewardEntry reward(ProgressionRewardEntry.Kind kind) {
        return new ProgressionRewardEntry(kind,switch(kind){case LEARNED->LEARNED;case ACCESSORY->ACCESSORY;case SPECIAL->SPECIAL;});
    }
    private static RewardClaim claim(UUID player,String source,List<RewardEntry> automatic,List<RewardChoiceOption> choices) {
        return new RewardClaim(UUID.randomUUID(),player,GOD,id(source),"fixture",automatic,choices,false,Optional.empty(),0,RewardOrigin.QUEST);
    }
    @GameTest(templateNamespace="mythictrpg_progression_rewards",template="empty")
    public static void automaticOwnershipCommitsAllKindsOnce(GameTestHelper h) {
        var state=new RewardClaimState();var player=UUID.randomUUID();
        var entries=List.<RewardEntry>of(reward(ProgressionRewardEntry.Kind.LEARNED),reward(ProgressionRewardEntry.Kind.ACCESSORY),reward(ProgressionRewardEntry.Kind.SPECIAL));
        var receipt=state.create(claim(player,"automatic",entries,List.of()));
        h.assertTrue(state.progressionFor(player).isEmpty(),"offered rewards granted ownership before automatic receipt");
        state.markAutomaticGranted(receipt.claimId());state.markAutomaticGranted(receipt.claimId());
        h.assertValueEqual(state.progressionFor(player).size(),3,"receipt replay duplicated or omitted progression ownership");
        for(var kind:ProgressionRewardEntry.Kind.values())h.assertTrue(state.ownsProgression(player,kind,reward(kind).definitionId()),"committed kind missing "+kind);
        h.assertTrue(state.progressionFor(UUID.randomUUID()).isEmpty(),"another player inherited entitlements");
        h.assertTrue(!state.hasNonRaidBlessing(player),"skill or accessory ownership was silently treated as a divine blessing");
        var repeat=state.create(new RewardClaim(UUID.randomUUID(),player,GOD,receipt.sourceId(),"different",entries,List.of(),false,Optional.empty(),1));
        h.assertValueEqual(repeat.claimId(),receipt.claimId(),"source replay created an independent receipt");
        h.succeed();
    }
    @GameTest(templateNamespace="mythictrpg_progression_rewards",template="empty")
    public static void onlySelectedChoiceCreatesItsEntitlement(GameTestHelper h) {
        var state=new RewardClaimState();var player=UUID.randomUUID();
        var choices=List.of(new RewardChoiceOption(id("pick_accessory"),"accessory",List.of(reward(ProgressionRewardEntry.Kind.ACCESSORY))),
                new RewardChoiceOption(id("pick_special"),"special",List.of(reward(ProgressionRewardEntry.Kind.SPECIAL))));
        var receipt=state.create(claim(player,"choice",List.of(reward(ProgressionRewardEntry.Kind.LEARNED)),choices));
        rejected(h,()->state.markSelected(receipt.claimId(),choices.getFirst().optionId()),"choice was granted before automatic receipt");
        state.markAutomaticGranted(receipt.claimId());
        h.assertValueEqual(state.progressionFor(player).size(),1,"unchosen offered rewards acquired ownership");
        state.markSelected(receipt.claimId(),choices.getFirst().optionId());
        h.assertTrue(state.ownsProgression(player,ProgressionRewardEntry.Kind.ACCESSORY,ACCESSORY),"selected accessory not acquired");
        h.assertTrue(!state.ownsProgression(player,ProgressionRewardEntry.Kind.SPECIAL,SPECIAL),"unchosen special acquired");
        rejected(h,()->state.markSelected(receipt.claimId(),choices.getFirst().optionId()),"choice replay duplicated ownership");
        rejected(h,()->state.markSelected(receipt.claimId(),choices.get(1).optionId()),"second option was granted after selection");
        h.assertValueEqual(state.progressionFor(player).size(),2,"rejected selection changed ownership");h.succeed();
    }
    @GameTest(templateNamespace="mythictrpg_progression_rewards",template="empty")
    public static void twoLegitimateAccessoryAwardsStayDistinctAndSaved(GameTestHelper h) {
        var state=new RewardClaimState();var player=UUID.randomUUID();
        for(int i=0;i<2;i++) {
            var receipt=state.create(claim(player,"accessory_source_"+i,List.of(reward(ProgressionRewardEntry.Kind.ACCESSORY)),List.of()));
            state.markAutomaticGranted(receipt.claimId());
        }
        var owned=state.progressionFor(player);
        h.assertValueEqual(owned.size(),2,"independent same-definition rewards collapsed");
        h.assertTrue(!owned.get(0).entitlementId().equals(owned.get(1).entitlementId()),"distinct legitimate awards shared one entitlement");
        var restored=RewardClaimState.load(state.save(new CompoundTag(),h.getLevel().registryAccess()),h.getLevel().registryAccess());
        h.assertTrue(restored.isWritable(),"valid v7 ownership rejected: "+restored.rejectionReason());
        h.assertValueEqual(restored.progressionFor(player),owned,"save/reload altered entitlement identity or provenance");h.succeed();
    }
    @GameTest(templateNamespace="mythictrpg_progression_rewards",template="empty")
    public static void missingExtraAndMismatchedOwnershipRejectsWithoutReset(GameTestHelper h) {
        var state=new RewardClaimState();var player=UUID.randomUUID();
        var receipt=state.create(claim(player,"corruption",List.of(reward(ProgressionRewardEntry.Kind.ACCESSORY)),List.of()));state.markAutomaticGranted(receipt.claimId());
        var original=state.save(new CompoundTag(),h.getLevel().registryAccess());
        var missing=original.copy();missing.getList("progressionRewards",Tag.TAG_COMPOUND).clear();expectCorrupt(h,missing,"missing delivered entitlement");
        var extra=original.copy();var duplicate=extra.getList("progressionRewards",Tag.TAG_COMPOUND).getCompound(0).copy();duplicate.putUUID("id",UUID.randomUUID());
        extra.getList("progressionRewards",Tag.TAG_COMPOUND).add(duplicate);expectCorrupt(h,extra,"extra same-reward entitlement");
        var authority=original.copy();authority.getList("progressionRewards",Tag.TAG_COMPOUND).getCompound(0).putUUID("playerId",UUID.randomUUID());
        expectCorrupt(h,authority,"foreign owner entitlement");
        var origin=original.copy();origin.getList("progressionRewards",Tag.TAG_COMPOUND).getCompound(0).putString("origin","RAID_DIRECT");
        expectCorrupt(h,origin,"changed acquisition origin");
        var definition=original.copy();definition.getList("progressionRewards",Tag.TAG_COMPOUND).getCompound(0).putString("definitionId",SPECIAL.toString());
        expectCorrupt(h,definition,"changed definition");
        var missingList=original.copy();missingList.remove("progressionRewards");expectCorrupt(h,missingList,"missing v7 list");h.succeed();
    }
    @GameTest(templateNamespace="mythictrpg_progression_rewards",template="empty")
    public static void versionSixMigratesWithoutInventingNewProgression(GameTestHelper h) {
        var state=new RewardClaimState();var player=UUID.randomUUID();
        var receipt=state.create(claim(player,"legacy",List.of(new CurrencyRewardEntry(3)),List.of()));state.markAutomaticGranted(receipt.claimId());
        var old=state.save(new CompoundTag(),h.getLevel().registryAccess());old.putInt("dataVersion",6);old.remove("progressionRewards");
        var migrated=RewardClaimState.load(old,h.getLevel().registryAccess());
        h.assertTrue(migrated.isWritable(),"valid v6 receipt rejected");
        h.assertTrue(migrated.progressionFor(player).isEmpty(),"legacy receipt invented skill/accessory/special ownership");
        h.assertTrue(migrated.find(receipt.claimId()).orElseThrow().automaticGranted(),"migration lost original receipt");
        h.assertValueEqual(migrated.save(new CompoundTag(),h.getLevel().registryAccess()).getInt("dataVersion"),7,"migration did not save current format");h.succeed();
    }
    @GameTest(templateNamespace="mythictrpg_progression_rewards",template="empty",timeoutTicks=300)
    public static void realReceiptPruningRetainsProgressionAndDeclinedSourceTombstones(GameTestHelper h) {
        var state=new RewardClaimState();var player=UUID.randomUUID();
        var first=state.create(claim(player,"pruned_progression",List.of(reward(ProgressionRewardEntry.Kind.ACCESSORY)),List.of()));state.markAutomaticGranted(first.claimId());
        var declined=state.create(new RewardClaim(UUID.randomUUID(),player,GOD,id("declined_offer"),"choice",List.of(),
                List.of(new RewardChoiceOption(id("decline_accessory"),"accessory",List.of(reward(ProgressionRewardEntry.Kind.ACCESSORY))),
                        new RewardChoiceOption(id("take_currency"),"currency",List.of(new CurrencyRewardEntry(1)))),false,Optional.empty(),1));
        state.markAutomaticGranted(declined.claimId());state.markSelected(declined.claimId(),id("take_currency"));
        for(int i=0;i<4096;i++) {
            var filler=new RewardClaim(UUID.randomUUID(),player,GOD,id("filler/"+i),"filler",List.of(new CurrencyRewardEntry(1)),List.of(),false,Optional.empty(),i+2);
            state.create(filler);state.markAutomaticGranted(filler.claimId());
        }
        h.assertTrue(state.find(first.claimId()).isEmpty()&&state.find(declined.claimId()).isEmpty(),"test did not exercise actual receipt pruning");
        h.assertValueEqual(state.progressionFor(player).size(),1,"pruning removed acquired accessory or granted declined choice");
        rejected(h,()->state.create(first),"pruned delivered progression source was replayable");
        rejected(h,()->state.create(declined),"pruned declined progression offer could be rerolled");
        var restored=RewardClaimState.load(state.save(new CompoundTag(),h.getLevel().registryAccess()),h.getLevel().registryAccess());
        h.assertTrue(restored.isWritable(),"pruned retained ownership rejected: "+restored.rejectionReason());
        rejected(h,()->restored.create(first),"saved pruned source lost tombstone");h.succeed();
    }
    @GameTest(templateNamespace="mythictrpg_progression_rewards",template="empty",batch="progression_rank_claim",timeoutTicks=300)
    public static void actualClaimEnforcesRankAndProviderWithoutDuplicateOwnership(GameTestHelper h) {
        var channels=new ArrayList<EmbeddedChannel>();var player=connect(h,"ProgressionOwner",channels);
        try(var definitions=new DefinitionFixture()) {
            for(var kind:ProgressionRewardEntry.Kind.values()) {
                int minimum=switch(kind){case LEARNED->1;case ACCESSORY->3;case SPECIAL->4;};
                definitions.rank(GodRank.values()[minimum-1]);var entry=reward(kind);
                var bad=RewardClaimService.INSTANCE.issue(player,GOD,id("low/"+kind.name().toLowerCase(Locale.ROOT)+"/"+UUID.randomUUID()),new ResolvedQuestReward("low",List.of(entry),List.of()));
                h.assertTrue(!bad.succeeded(),"under-ranked provider granted "+kind);
                h.assertTrue(!RewardClaimState.get(player.server).ownsProgression(player.getUUID(),kind,entry.definitionId()),"rejected claim committed ownership");
                definitions.rank(GodRank.values()[minimum]);var source=id("allowed/"+kind.name().toLowerCase(Locale.ROOT)+"/"+UUID.randomUUID());
                var offer=new ResolvedQuestReward("allowed",List.of(entry),List.of());
                var good=RewardClaimService.INSTANCE.issue(player,GOD,source,offer);
                h.assertTrue(good.succeeded(),"eligible provider failed "+kind+": "+good.reason());
                int count=RewardClaimState.get(player.server).progressionFor(player.getUUID()).size();
                h.assertTrue(RewardClaimService.INSTANCE.issue(player,GOD,source,offer).succeeded(),"receipt replay failed");
                h.assertValueEqual(RewardClaimState.get(player.server).progressionFor(player.getUUID()).size(),count,"actual source replay duplicated ownership");
            }
            h.assertTrue(SkillRewardBridge.ownsLearned(player,LEARNED)&&SkillRewardBridge.ownsSpecial(player,SPECIAL),"public ownership bridge lost separate skill kinds");
            h.assertTrue(!SkillRewardBridge.ownsLearned(player,SPECIAL)&&!SkillRewardBridge.ownsSpecial(player,LEARNED),"skill kinds impersonated each other");
            h.assertTrue(!RewardClaimState.get(player.server).hasNonRaidBlessing(player.getUUID()),"progression rewards blocked human-route blessing condition");
            var foreign=RewardClaimService.INSTANCE.issue(player,id("other_provider"),id("foreign/"+UUID.randomUUID()),
                    new ResolvedQuestReward("foreign",List.of(reward(ProgressionRewardEntry.Kind.SPECIAL)),List.of()));
            h.assertTrue(!foreign.succeeded(),"special skill was granted by a different provider");
        }catch(Exception failure){if(failure instanceof GameTestAssertException assertion)throw assertion;throw new IllegalStateException(failure);}
        finally{disconnect(player,channels);}h.succeed();
    }
    @GameTest(templateNamespace="mythictrpg_progression_rewards",template="empty",batch="progression_rank_claim")
    public static void equipmentThresholdsAreCumulativeAndDoNotBlockOrdinaryMaterials(GameTestHelper h) {
        try(var definitions=new DefinitionFixture()) {
            var armor=new NpcRewardEntry(ResourceLocation.parse("minecraft:iron_chestplate"),1);
            var weapon=new NpcRewardEntry(ResourceLocation.parse("minecraft:iron_sword"),1);
            var material=new NpcRewardEntry(ResourceLocation.parse("minecraft:paper"),1);
            definitions.rank(GodRank.LOWEST);
            h.assertTrue(ProgressionRewards.equipmentError(GOD,armor)!=null&&ProgressionRewards.equipmentError(GOD,weapon)!=null,
                    "lowest rank granted armor or weapon");
            h.assertTrue(ProgressionRewards.equipmentError(GOD,material)==null,"category thresholds blocked ordinary materials");
            definitions.rank(GodRank.LOW);
            h.assertTrue(ProgressionRewards.equipmentError(GOD,armor)==null&&ProgressionRewards.equipmentError(GOD,weapon)!=null,
                    "low rank armor/weapon boundary wrong");
            definitions.rank(GodRank.MIDDLE);
            h.assertTrue(ProgressionRewards.equipmentError(GOD,armor)==null&&ProgressionRewards.equipmentError(GOD,weapon)==null,
                    "middle rank did not retain lower-rank armor permission");
            definitions.rank(GodRank.TRANSCENDENT);
            for(var kind:ProgressionRewardEntry.Kind.values())h.assertTrue(ProgressionRewards.error(GOD,reward(kind))==null,"transcendent lost cumulative permission "+kind);
        }catch(Exception failure){if(failure instanceof GameTestAssertException assertion)throw assertion;throw new IllegalStateException(failure);}h.succeed();
    }
    private static void rejected(GameTestHelper h,Runnable body,String reason) { boolean rejected=false;try{body.run();}catch(RuntimeException expected){rejected=true;}h.assertTrue(rejected,reason); }
    private static void expectCorrupt(GameTestHelper h,CompoundTag encoded,String reason) {
        var restored=RewardClaimState.load(encoded,h.getLevel().registryAccess());
        h.assertTrue(!restored.isWritable(),"accepted "+reason);
        h.assertValueEqual(restored.save(new CompoundTag(),h.getLevel().registryAccess()),encoded,"rejected "+reason+" lost original raw data");
    }
    private static Field field(Class<?> type,String name)throws Exception { var field=type.getDeclaredField(name);field.setAccessible(true);return field; }
    private static final class DefinitionFixture implements AutoCloseable {
        private final Object oldGod;private final Object oldLearned,oldAccessories,oldSpecial;
        DefinitionFixture()throws Exception {
            oldGod=field(GodDefinitionManager.class,"snapshot").get(GodDefinitionManager.INSTANCE);
            oldLearned=field(LearnedSkillDefinitions.class,"snapshot").get(LearnedSkillDefinitions.INSTANCE);
            oldAccessories=field(AccessoryDefinitions.class,"snapshot").get(AccessoryDefinitions.INSTANCE);
            oldSpecial=field(SpecialSkillDefinitions.class,"snapshot").get(SpecialSkillDefinitions.INSTANCE);
            field(LearnedSkillDefinitions.class,"snapshot").set(LearnedSkillDefinitions.INSTANCE,Map.of(LEARNED,
                    new LearnedSkillDefinition(LEARNED,"fixture learned",0,20,new WeaponSkillDefinition.SelfHeal(1),3)));
            field(AccessoryDefinitions.class,"snapshot").set(AccessoryDefinitions.INSTANCE,Map.of(ACCESSORY,
                    new AccessoryDefinition(ACCESSORY,"fixture accessory",ResourceLocation.parse("minecraft:paper"),new CompoundTag(),4,
                            List.of(new AccessoryEffect(AccessoryEffect.Kind.MAX_MANA,10,0,0)))));
            field(SpecialSkillDefinitions.class,"snapshot").set(SpecialSkillDefinitions.INSTANCE,Map.of(SPECIAL,
                    new SpecialSkillDefinition(SPECIAL,"fixture special",GOD,0,new SpecialSkillDefinition.UseLimits(1,0,List.of()),
                            new SpecialSkillDefinition.Action(SpecialSkillDefinition.ActionType.AREA_DAMAGE,1,1,1,0),Optional.empty(),5)));
        }
        void rank(GodRank rank)throws Exception {
            var old=(GodDefinitionManager.ProgressionSnapshot)oldGod;
            var god=new GodDefinition(1,Component.literal("fixture provider"),id("origin"),id("faction"),Set.of(),Optional.empty(),Optional.empty(),Optional.empty(),Optional.of(rank));
            var foreign=new GodDefinition(1,Component.literal("fixture other"),id("origin"),id("faction"),Set.of(),Optional.empty(),Optional.empty(),Optional.empty(),Optional.of(GodRank.TRANSCENDENT));
            field(GodDefinitionManager.class,"snapshot").set(GodDefinitionManager.INSTANCE,new GodDefinitionManager.ProgressionSnapshot(
                    Map.of(GOD,god,id("other_provider"),foreign),old.unlockIndex(),old.appearanceIndex(),old.categoryIndex(),old.generation()+1));
        }
        public void close()throws Exception {
            field(GodDefinitionManager.class,"snapshot").set(GodDefinitionManager.INSTANCE,oldGod);
            field(LearnedSkillDefinitions.class,"snapshot").set(LearnedSkillDefinitions.INSTANCE,oldLearned);
            field(AccessoryDefinitions.class,"snapshot").set(AccessoryDefinitions.INSTANCE,oldAccessories);
            field(SpecialSkillDefinitions.class,"snapshot").set(SpecialSkillDefinitions.INSTANCE,oldSpecial);
        }
    }
}
