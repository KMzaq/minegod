package com.sande.mythictrpg.quest.reward;

import com.google.gson.JsonParser;
import com.sande.mythictrpg.equipment.EquipmentCatalog;
import com.sande.mythictrpg.power.CombatPowerRuntime;
import com.sande.mythictrpg.prototype.PrototypeGrantService;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.*;

/** Existing claim/executor path owns both authored references and developer provisioning. */
@GameTestHolder("mythictrpg_prototype_catalog")
@PrefixGameTestTemplate(false)
public final class EquipmentRewardGameTests {
    private static final ResourceLocation PROVIDER=PrototypeGrantService.TECHNICAL_SOURCE;
    private static final ResourceLocation TRAINING=id("steel_training"), WAVE=id("steel_wave"), GUARD=id("duelist_guard");
    private EquipmentRewardGameTests() { }

    @GameTest(templateNamespace="mythictrpg_prototype_catalog",template="empty",batch="equipment_reward")
    public static void codecRequiresReferenceAndClaimRejectsUnresolvedOrSubstitutedEquipment(GameTestHelper h) {
        var authored=new EquipmentRewardEntry(WAVE);
        h.assertValueEqual(RewardEntryCodec.parse(JsonParser.parseString("{\"type\":\"equipment\",\"definitionId\":\"reuse_demo:steel_wave\"}").getAsJsonObject(),"test"),authored,"Equipment JSON codec");
        h.assertValueEqual(RewardEntryCodec.load(RewardEntryCodec.save(authored)),authored,"Equipment NBT codec");
        try(var f=new Fixture(h,UUID.randomUUID())) {
            h.assertFalse(RewardExecutionService.grant(f.player,PROVIDER,List.of(authored),RewardGrantPurpose.QUEST).granted(),"Unfrozen reference granted directly");
            h.assertFalse(RewardExecutionService.validate(List.of(new EquipmentRewardEntry(id("missing"))),RewardGrantPurpose.QUEST).allowed(),"Missing definition accepted at runtime");
            h.assertTrue(RewardExecutionService.validateAuthored(List.of(new EquipmentRewardEntry(id("missing"))),RewardGrantPurpose.QUEST).allowed(),"Reload preparation consulted another listener snapshot");
            var state=RewardClaimState.get(f.player.server);
            var claim=state.create(claim(f.player,List.of(authored),List.of()));
            boolean blocked=false;
            try { state.markAutomaticGranted(claim.claimId()); } catch(IllegalArgumentException|IllegalStateException expected){blocked=true;}
            h.assertTrue(blocked,"Unresolved automatic equipment was committed");
            blocked=false;
            try { state.freezeAdaptiveRewards(claim.claimId(),new ResolvedQuestReward("test",List.of(new NpcRewardEntry(ResourceLocation.withDefaultNamespace("diamond"),1,new CompoundTag())),List.of())); }
            catch(IllegalArgumentException expected){blocked=true;}
            h.assertTrue(blocked,"Equipment freeze allowed substitution");
            h.assertFalse(state.find(claim.claimId()).orElseThrow().automaticGranted(),"Rejected freeze consumed receipt");
        }
        h.succeed();
    }

    @GameTest(templateNamespace="mythictrpg_prototype_catalog",template="empty",batch="equipment_reward",timeoutTicks=300)
    public static void automaticAndChoiceRewardsFreezeConcreteItemsPersistAndDeliverOnce(GameTestHelper h) {
        h.startSequence().thenWaitUntil(()->h.assertTrue(ready(h),"acquisition startup fence")).thenExecute(()->{
            try(var f=new Fixture(h,UUID.randomUUID())) {
                var source=source();var first=new EquipmentRewardEntry(TRAINING);var second=new EquipmentRewardEntry(WAVE);
                var choices=List.of(new RewardChoiceOption(id("choice_wave"),"wave",List.of(second)),
                        new RewardChoiceOption(id("choice_guard"),"guard",List.of(new EquipmentRewardEntry(GUARD))));
                var offer=new ResolvedQuestReward("test",List.of(first),choices);
                var issued=RewardClaimService.INSTANCE.issue(f.player,PROVIDER,source,offer);
                h.assertTrue(issued.succeeded(),"Equipment claim failed: "+issued.reason());
                var state=RewardClaimState.get(f.player.server);var stored=state.findBySource(f.player.getUUID(),source).orElseThrow();
                h.assertValueEqual(stored.automaticRewards(),List.of(first.resolve()),"Automatic item not frozen");
                h.assertTrue(stored.choices().stream().allMatch(c->c.rewards().getFirst() instanceof NpcRewardEntry),"Choice definitions remained live references");
                h.assertValueEqual(count(f.player),1,"Automatic reward delivery");
                var restored=RewardClaimState.load(state.save(new CompoundTag(),f.player.registryAccess()),f.player.registryAccess());
                h.assertTrue(restored.isWritable(),"Frozen equipment receipt failed reload");
                h.assertValueEqual(restored.find(stored.claimId()).orElseThrow().choices(),stored.choices(),"Persisted choice components changed");
                var selected=RewardClaimService.INSTANCE.choose(f.player,stored.claimId(),id("choice_wave"));
                h.assertTrue(selected.succeeded(),"Equipment choice failed: "+selected.reason());
                h.assertValueEqual(count(f.player),2,"Choice not delivered exactly once");
                RewardClaimService.INSTANCE.choose(f.player,stored.claimId(),id("choice_wave"));
                RewardClaimService.INSTANCE.issue(f.player,PROVIDER,source,offer);
                h.assertValueEqual(count(f.player),2,"Repeated claim duplicated concrete equipment");
                var settled=RewardClaimState.load(state.save(new CompoundTag(),f.player.registryAccess()),f.player.registryAccess());
                try {
                    var claims=RewardClaimState.class.getDeclaredField("claims");claims.setAccessible(true);
                    ((Map<?,?>)claims.get(settled)).remove(stored.claimId());
                } catch(ReflectiveOperationException invalid){throw new IllegalStateException(invalid);}
                h.assertFalse(settled.canCreate(f.player.getUUID(),source),"Completed equipment source was lost after receipt pruning");
            }
        }).thenSucceed();
    }

    @GameTest(templateNamespace="mythictrpg_prototype_catalog",template="empty",batch="equipment_reward",timeoutTicks=300)
    public static void offlineEquipmentReferenceFreezesBeforeQueueAndRetainsAcquisition(GameTestHelper h) {
        h.startSequence().thenWaitUntil(()->h.assertTrue(ready(h),"acquisition startup fence")).thenExecute(()->{
            UUID playerId=UUID.randomUUID();var source=source();var authored=new EquipmentRewardEntry(WAVE);
            var queued=RewardClaimService.INSTANCE.queueBatch(h.getLevel().getServer(),PROVIDER,source,
                    Map.of(playerId,new ResolvedQuestReward("test",List.of(authored),List.of())));
            h.assertTrue(queued.succeeded(),"Offline equipment queue rejected: "+queued.reason());
            var state=RewardClaimState.get(h.getLevel().getServer());
            h.assertValueEqual(state.findBySource(playerId,source).orElseThrow().automaticRewards(),List.of(authored.resolve()),"Offline queue did not freeze static equipment");
            var loaded=RewardClaimState.load(state.save(new CompoundTag(),h.getLevel().registryAccess()),h.getLevel().registryAccess());
            h.assertTrue(loaded.isWritable(),"Pending equipment reference was not persistable");
            try(var f=new Fixture(h,playerId)) {
                RewardClaimService.INSTANCE.deliverQueued(f.player);
                var claim=state.findBySource(playerId,source).orElseThrow();
                h.assertTrue(claim.automaticGranted()&&claim.automaticRewards().equals(List.of(authored.resolve())),"Offline delivery failed to freeze");
                h.assertValueEqual(count(f.player),1,"Offline equipment delivered more than once");
                var stack=f.player.getInventory().items.stream().filter(s->!s.isEmpty()).findFirst().orElseThrow();
                h.assertTrue(CombatPowerRuntime.registeredAcquiredGear(f.player,stack).isPresent(),"Frozen reward did not enter authoritative acquisition history");
            }
        }).thenSucceed();
    }

    @GameTest(templateNamespace="mythictrpg_prototype_catalog",template="empty",batch="equipment_reward",timeoutTicks=300)
    public static void developmentExamplesRequireOperatorRestoreMissingAndPreserveCursorAndRights(GameTestHelper h) {
        h.startSequence().thenWaitUntil(()->h.assertTrue(ready(h),"acquisition startup fence")).thenExecute(()->{
            try(var f=new Fixture(h,UUID.randomUUID())) {
                h.assertFalse(PrototypeGrantService.grantEquipmentExamples(f.player.createCommandSourceStack().withPermission(0)).succeeded(),"Nonoperator obtained examples");
                var operator=f.player.createCommandSourceStack().withPermission(2);
                try {
                    int result=f.player.server.getCommands().getDispatcher().execute("mythequipment grant examples",operator);
                    h.assertValueEqual(result,1,"Actual equipment examples command failed");
                    result=f.player.server.getCommands().getDispatcher().execute("mythequipment grant reuse_demo:steel_wave",operator);
                    h.assertValueEqual(result,1,"Namespaced equipment command ID was not parsed");
                } catch(com.mojang.brigadier.exceptions.CommandSyntaxException invalid){throw new IllegalStateException(invalid);}
                var rights=RewardClaimState.get(f.player.server).progressionFor(f.player.getUUID());
                h.assertValueEqual(rights.size(),2,"Example accessory rights");
                h.assertValueEqual(count(f.player),6,"Example physical count");
                h.assertValueEqual(PrototypeGrantService.grantEquipmentExamples(operator).delivered(),0,"Repeated examples duplicated items");
                int slot=-1;var expected=EquipmentCatalog.find(WAVE).orElseThrow().reward().createStack(f.player.registryAccess());
                for(int i=0;i<36;i++)if(ItemStack.isSameItemSameComponents(expected,f.player.getInventory().getItem(i))){slot=i;break;}
                h.assertTrue(slot>=0,"Wave example absent");
                f.player.inventoryMenu.setCarried(f.player.getInventory().getItem(slot));f.player.getInventory().setItem(slot,ItemStack.EMPTY);
                h.assertValueEqual(PrototypeGrantService.grantEquipment(operator,WAVE).delivered(),0,"Cursor equipment duplicated");
                f.player.inventoryMenu.setCarried(ItemStack.EMPTY);
                var restored=PrototypeGrantService.grantEquipment(operator,WAVE);
                h.assertTrue(restored.succeeded()&&restored.delivered()==1,"Missing owned example did not restore");
                h.assertValueEqual(RewardClaimState.get(f.player.server).progressionFor(f.player.getUUID()),rights,"Restore issued new accessory rights");
            }
            try(var f=new Fixture(h,UUID.randomUUID())) {
                for(int i=0;i<36;i++)f.player.getInventory().setItem(i,new ItemStack(Items.STONE,64));
                var result=PrototypeGrantService.grantEquipmentExamples(f.player.createCommandSourceStack().withPermission(2));
                h.assertFalse(result.succeeded(),"Full inventory consumed example provisioning");
                h.assertTrue(RewardClaimState.get(f.player.server).progressionFor(f.player.getUUID()).isEmpty(),"Failed provisioning committed accessory rights");
            }
        }).thenSucceed();
    }

    @GameTest(templateNamespace="mythictrpg_combat_optional",template="empty",batch="swords_reward",timeoutTicks=300)
    public static void swordsCommandUsesRealReceiptsAndRestoresWithoutDuplicateAcquisition(GameTestHelper h) {
        h.startSequence().thenWaitUntil(()->h.assertTrue(ready(h),"acquisition startup fence")).thenExecute(()->{
            try(var f=new Fixture(h,UUID.randomUUID())) {
                var operator=f.player.createCommandSourceStack().withPermission(2);
                boolean installed=net.neoforged.fml.ModList.get().isLoaded("simplyswords")
                        &&net.neoforged.fml.ModList.get().isLoaded("bettercombat");
                try {
                    int result=f.player.server.getCommands().getDispatcher().execute("mythequipment grant swords",operator);
                    h.assertValueEqual(result,installed?1:0,"Optional swords command result");
                } catch(com.mojang.brigadier.exceptions.CommandSyntaxException invalid){throw new IllegalStateException(invalid);}
                if(!installed) {
                    h.assertValueEqual(count(f.player),0,"Missing mods produced substitute items");
                    return;
                }
                h.assertValueEqual(count(f.player),4,"Swords example physical count");
                var wave=ResourceLocation.parse("swords_demo:longsword_wave");
                var expected=EquipmentCatalog.find(wave).orElseThrow().reward().createStack(f.player.registryAccess());
                h.assertValueEqual(PrototypeGrantService.grantEquipmentExamples(operator,"swords_demo").delivered(),0,"Repeated swords duplicated items");
                int slot=-1;
                for(int i=0;i<36;i++)if(ItemStack.isSameItemSameComponents(expected,f.player.getInventory().getItem(i))){slot=i;break;}
                h.assertTrue(slot>=0,"Granted swords wave missing");
                f.player.getInventory().setItem(slot,ItemStack.EMPTY);
                var restored=PrototypeGrantService.grantEquipment(operator,wave);
                h.assertTrue(restored.succeeded()&&restored.delivered()==1,"Missing swords reward did not restore");
                h.assertValueEqual(count(f.player),4,"Restore changed example count");
                h.assertFalse(PrototypeGrantService.grantEquipmentExamples(f.player.createCommandSourceStack().withPermission(0),"swords_demo").succeeded(),"Nonoperator bypassed swords command");
            }
        }).thenSucceed();
    }

    private static RewardClaim claim(ServerPlayer player,List<RewardEntry> entries,List<RewardChoiceOption> choices) {
        return new RewardClaim(UUID.randomUUID(),player.getUUID(),PROVIDER,source(),"test",entries,choices,false,Optional.empty(),player.server.overworld().getGameTime(),RewardOrigin.LEGACY_UNSPECIFIED);
    }
    private static int count(ServerPlayer player){return player.getInventory().items.stream().filter(s->!s.isEmpty()).mapToInt(ItemStack::getCount).sum();}
    private static ResourceLocation id(String value){return ResourceLocation.fromNamespaceAndPath("reuse_demo",value);}
    private static ResourceLocation source(){return id("test/"+UUID.randomUUID());}
    private static boolean ready(GameTestHelper h) {
        try { var method=CombatPowerRuntime.class.getDeclaredMethod("captureReady",net.minecraft.server.MinecraftServer.class);method.setAccessible(true);return (boolean)method.invoke(null,h.getLevel().getServer()); }
        catch(ReflectiveOperationException invalid){throw new IllegalStateException(invalid);}
    }
    private static final class Fixture implements AutoCloseable {
        final ServerPlayer player;final EmbeddedChannel channel;
        Fixture(GameTestHelper h,UUID playerId) {
            var cookie=net.minecraft.server.network.CommonListenerCookie.createInitial(new com.mojang.authlib.GameProfile(playerId,"EquipmentTest"),false);
            player=new ServerPlayer(h.getLevel().getServer(),h.getLevel(),cookie.gameProfile(),cookie.clientInformation());
            var connection=new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);channel=new EmbeddedChannel(connection);
            net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(connection);
            player.server.getPlayerList().placeNewPlayer(connection,player,cookie);player.setNoGravity(true);
        }
        @Override public void close(){player.server.getPlayerList().remove(player);channel.finishAndReleaseAll();}
    }
}
