package com.sande.mythictrpg.power;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/** Isolated acquisition-evidence storage; creates no player rewards or alternate reward authority. */
@GameTestHolder("mythictrpg_power")
@PrefixGameTestTemplate(false)
public final class RewardPowerCapacityGameTests {
    private RewardPowerCapacityGameTests() { }

    @GameTest(templateNamespace="mythictrpg_power",template="empty",batch="power_history_capacity",timeoutTicks=100)
    public static void aThousandDistinctVariantsRemainCompleteAcrossSaveLoadAndRepeatedAcquisition(GameTestHelper h) {
        var state=new RewardPowerState();UUID player=UUID.randomUUID(),session=UUID.randomUUID();
        state.beginPlayer(player,true);state.cleanSession(session);
        CompoundTag base=RewardPowerState.identity(new ItemStack(Items.PAPER),h.getLevel().registryAccess());
        for(int variant=0;variant<1024;variant++)state.record(player,identity(base,variant));
        var before=state.history(player).orElseThrow();
        h.assertTrue(before.complete()&&before.items().size()==1024
                        &&before.items().contains(identity(base,512))&&before.items().contains(identity(base,1023)),
                "The 513th or later authored variant disabled complete acquisition evidence");
        state.record(player,identity(base,512));
        h.assertTrue(state.history(player).orElseThrow().items().size()==1024,"Repeated acquisition stored a duplicate variant");
        var saved=state.save(new CompoundTag(),h.getLevel().registryAccess());
        h.assertTrue(saved.getInt("schemaVersion")==1,"Capacity expansion unnecessarily changed the storage schema");
        var restored=RewardPowerState.load(saved,h.getLevel().registryAccess());
        var history=restored.history(player).orElseThrow();
        h.assertTrue(restored.ready()&&history.complete()&&history.reason().equals(before.reason())
                        &&history.items().equals(before.items())&&session.equals(restored.cleanSession()),
                "Save/load lost complete variant history, coverage reason or clean-session evidence");
        restored.record(player,identity(base,1023));
        h.assertTrue(restored.history(player).orElseThrow().complete()
                        &&restored.history(player).orElseThrow().items().size()==1024,"Reload changed variant deduplication");
        h.succeed();
    }

    @GameTest(templateNamespace="mythictrpg_power",template="empty",batch="power_history_capacity",timeoutTicks=100)
    public static void fullPersonalHistoryRejectsOnlyTheNextVariantAndPreservesAllEarlierEvidence(GameTestHelper h) {
        UUID player=UUID.randomUUID();CompoundTag base=RewardPowerState.identity(new ItemStack(Items.PAPER),h.getLevel().registryAccess());
        // Build a valid existing save directly so this boundary test does not repeatedly copy 4096 growing histories.
        var saved=saveFixture(player,base,RewardPowerState.MAX_VARIANTS,true,"COMPLETE_FROM_FIRST_JOIN");
        var state=RewardPowerState.load(saved,h.getLevel().registryAccess());
        h.assertTrue(state.ready()&&state.history(player).orElseThrow().complete(),"A full supported history was rejected while loading");
        var before=state.history(player).orElseThrow().items();
        state.record(player,identity(base,RewardPowerState.MAX_VARIANTS-1));
        h.assertTrue(state.history(player).orElseThrow().complete(),"A repeated owned variant exhausted capacity");
        state.record(player,identity(base,RewardPowerState.MAX_VARIANTS));
        var after=state.history(player).orElseThrow();
        h.assertTrue(state.ready()&&!after.complete()&&after.reason().equals("ACQUISITION_HISTORY_CAPACITY")
                        &&after.items().equals(before),"Capacity overflow pruned earlier gear or falsely retained complete coverage");
        var restored=RewardPowerState.load(state.save(new CompoundTag(),h.getLevel().registryAccess()),h.getLevel().registryAccess());
        h.assertTrue(restored.ready()&&!restored.history(player).orElseThrow().complete()
                        &&restored.history(player).orElseThrow().items().equals(before),"Overflow changed existing evidence after save/load");
        var corrupt=saveFixture(UUID.randomUUID(),base,RewardPowerState.MAX_VARIANTS+1,true,"COMPLETE_FROM_FIRST_JOIN");
        var rejected=RewardPowerState.load(corrupt,h.getLevel().registryAccess());
        h.assertTrue(!rejected.ready()&&rejected.save(new CompoundTag(),h.getLevel().registryAccess()).equals(corrupt),
                "An oversized save was accepted or its original rejected contents were discarded");
        h.succeed();
    }

    @GameTest(templateNamespace="mythictrpg_power",template="empty",batch="power_history_capacity",timeoutTicks=100)
    public static void oldIncompleteCoverageIsNotPromotedByExpandedCapacity(GameTestHelper h) {
        UUID player=UUID.randomUUID();CompoundTag base=RewardPowerState.identity(new ItemStack(Items.PAPER),h.getLevel().registryAccess());
        var old=saveFixture(player,base,512,false,"ACQUISITION_HISTORY_CAPACITY");
        var state=RewardPowerState.load(old,h.getLevel().registryAccess());
        state.record(player,identity(base,512));state.beginPlayer(player,true);
        var history=state.history(player).orElseThrow();
        h.assertTrue(state.ready()&&!history.complete()&&history.reason().equals("ACQUISITION_HISTORY_CAPACITY")
                        &&history.items().size()==513,"Increasing capacity invented evidence for an existing historical gap");
        h.succeed();
    }

    private static CompoundTag identity(CompoundTag base,int variant) {
        CompoundTag identity=base.copy(),components=new CompoundTag(),data=new CompoundTag();
        data.putInt("capacity_fixture_variant",variant);components.put("minecraft:custom_data",data);identity.put("components",components);return identity;
    }
    private static CompoundTag saveFixture(UUID player,CompoundTag base,int size,boolean complete,String reason) {
        CompoundTag root=new CompoundTag(),row=new CompoundTag();ListTag players=new ListTag(),items=new ListTag();
        for(int variant=0;variant<size;variant++)items.add(identity(base,variant));
        row.putUUID("player",player);row.putBoolean("complete",complete);row.putString("reason",reason);row.put("items",items);players.add(row);
        root.putInt("schemaVersion",1);root.put("players",players);return root;
    }
}
