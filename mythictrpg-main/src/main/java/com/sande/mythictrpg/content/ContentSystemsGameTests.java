package com.sande.mythictrpg.content;

import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.sande.mythictrpg.condition.builtin.*;
import com.sande.mythictrpg.story.api.StoryStateView.*;
import com.sande.mythictrpg.story.definition.*;
import com.sande.mythictrpg.story.definition.StoryDefinitions.*;
import com.sande.mythictrpg.story.runtime.StoryEventService;
import com.sande.mythictrpg.story.signal.StorySignalTypes;
import com.sande.mythictrpg.story.state.*;
import com.sande.mythictrpg.quest.reward.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.*;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Field;
import java.util.*;

@GameTestHolder("mythictrpg_content_systems")
@PrefixGameTestTemplate(false)
public final class ContentSystemsGameTests {
    @GameTest(templateNamespace="mythictrpg_content_systems", template="empty", timeoutTicks=200)
    public static void investigationUsesRealSiteAndKeepsKnowledgeAndRewardPersonal(GameTestHelper h) throws Exception {
        var channels = new ArrayList<EmbeddedChannel>();
        var a = connect(h, "ClueOwner", channels); var b = connect(h, "ClueOther", channels);
        var before = ContentDefinitions.INSTANCE.snapshot(); var storyBefore = StoryDefinitionManager.INSTANCE.snapshot();
        try {
            var fact = id("clue"); var eventId = id("investigation"); var siteId = id("site");
            var rewardEffect = id("reward");
            var event = event(eventId, false, List.of(new Effect.GrantKnowledge(id("learn"), HolderSelector.TRIGGER_PLAYER,
                    Optional.empty(), fact, 1, ResourceLocation.parse("mythictrpg:full")),
                    new Effect.GrantRewards(rewardEffect, ResourceLocation.parse("mythictrpg:demeter"), List.of(new NpcRewardEntry(ResourceLocation.parse("minecraft:gold_nugget"), 2)))));
            var facts = new HashMap<>(storyBefore.facts());
            facts.put(fact, new FactDefinition(fact, Set.of(ScopeType.PLAYER), false, "Never expose this admin summary",
                    List.of(new FactKnowledgeLevel(1, "First learned clue"), new FactKnowledgeLevel(2, "Secret unlearned clue")), Set.of()));
            swapStory(new StoryDefinitionSnapshot(storyBefore.actors(), storyBefore.locations(), facts, storyBefore.coverStories(),
                    storyBefore.disclosurePolicies(), Map.of(eventId, event), Map.of(), Map.of(), Map.of(), storyBefore.generation()+1));
            BlockPos pos = h.absolutePos(new BlockPos(2, 2, 2));
            h.getLevel().setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
            a.setPos(pos.getX()+0.5, pos.getY(), pos.getZ()+2.5);
            a.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.FEATHER));
            var site = new ContentDefinitions.Investigation(siteId, a.level().dimension().location(), pos,
                    ResourceLocation.parse("minecraft:stone"), 4, eventId, Optional.of(ResourceLocation.parse("minecraft:feather")),
                    1, 20, new AlwaysCondition(BuiltinConditionTypes.ALWAYS));
            ContentDefinitions.INSTANCE.apply(new ContentDefinitions.Snapshot(Map.of(siteId, site), Map.of(), Map.of()), null, null);
            check(!ContentSystemsRuntime.investigate(b, siteId), "remote player investigated site");
            a.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            check(!ContentSystemsRuntime.investigate(a, siteId), "missing hand item accepted");
            a.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.FEATHER));
            a.getMainHandItem().set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,net.minecraft.network.chat.Component.literal("Other variant"));
            check(!ContentSystemsRuntime.investigate(a,siteId),"different components impersonated the required clue item");
            a.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.FEATHER));
            h.getLevel().setBlockAndUpdate(pos, Blocks.DIRT.defaultBlockState());
            check(!ContentSystemsRuntime.investigate(a, siteId), "replaced block accepted");
            h.getLevel().setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
            int goldBefore = a.getInventory().countItem(Items.GOLD_NUGGET);
            check(ContentSystemsRuntime.investigate(a, siteId), "valid investigation failed");
            check(a.getInventory().countItem(Items.GOLD_NUGGET) == goldBefore + 2, "actual reward not delivered");
            check(!ContentSystemsRuntime.investigate(a, siteId), "duplicate investigation accepted");
            check(a.getInventory().countItem(Items.GOLD_NUGGET) == goldBefore + 2, "reward duplicated");
            check(ContentSystemsRuntime.read(a, fact) == 1 && ContentSystemsRuntime.read(b, fact) == 0, "knowledge crossed players");
            var saved = StoryRuntimeState.get(a.server).save(new CompoundTag(), a.registryAccess());
            var restored = StoryRuntimeState.load(saved, a.registryAccess());
            check(restored.isReady() && restored.knowledgeLevel(StoryKnowledgeHolder.player(a.getUUID()), fact) == 1, "knowledge did not persist");
            check(restored.knowledgeLevel(StoryKnowledgeHolder.player(b.getUUID()), fact) == 0, "bystander inherited clue");
            var instance = restored.eventInstances().values().stream().filter(e -> e.eventId().equals(eventId)).findFirst().orElseThrow();
            var source = StoryEventService.rewardSource(instance, rewardEffect);
            var result = RewardClaimService.INSTANCE.issue(a, ResourceLocation.parse("mythictrpg:demeter"), source,
                    new ResolvedQuestReward("retry", List.of(new NpcRewardEntry(ResourceLocation.parse("minecraft:gold_nugget"), 2)), List.of()));
            check(result.succeeded() && a.getInventory().countItem(Items.GOLD_NUGGET) == goldBefore + 2, "receipt replay granted twice");
            var liveState=StoryRuntimeState.get(a.server);
            var live=liveState.eventInstances().get(instance.instanceId());
            liveState.putEvent(live.withAppliedEffects(Set.of(event.outcomes().getFirst().effects().getFirst().effectId()),
                    StoryRuntimeModels.EventStatus.EXTERNAL_EFFECT_PENDING));
            var retry=StoryEventService.class.getDeclaredMethod("retryExternalEffects",net.minecraft.server.MinecraftServer.class,boolean.class);
            retry.setAccessible(true);retry.invoke(StoryEventService.INSTANCE,a.server,true);
            check(liveState.eventInstances().get(instance.instanceId()).status()==StoryRuntimeModels.EventStatus.RECOVERY_REQUIRED
                    && a.getInventory().countItem(Items.GOLD_NUGGET)==goldBefore+2,"ambiguous crash receipt was silently completed or repaid");
            h.succeed();
        } finally { ContentDefinitions.INSTANCE.apply(before, null, null); swapStory(storyBefore); disconnect(a, channels); disconnect(b, List.of()); }
    }

    @GameTest(templateNamespace="mythictrpg_content_systems", template="empty", timeoutTicks=200)
    public static void reservationsPersistAndDoNotBlockIndependentEvents(GameTestHelper h) throws Exception {
        var channels = new ArrayList<EmbeddedChannel>(); var a = connect(h, "ResourceA", channels); var b = connect(h, "ResourceB", channels);
        var before = ContentDefinitions.INSTANCE.snapshot(); var storyBefore = StoryDefinitionManager.INSTANCE.snapshot();
        try {
            var resource = id("shared_altar"); var fact = id("choice_fact");
            var left = event(id("left"), true, List.of(new Effect.SetFact(id("left_effect"), fact, ScopeSelector.TRIGGER_PLAYER, true)));
            var right = event(id("right"), true, List.of(new Effect.SetFact(id("right_effect"), fact, ScopeSelector.TRIGGER_PLAYER, true)));
            var independent = event(id("independent"), false, List.of(new Effect.SetFact(id("independent_effect"), fact, ScopeSelector.TRIGGER_PLAYER, true)));
            var facts = new HashMap<>(storyBefore.facts());
            facts.put(fact, new FactDefinition(fact, Set.of(ScopeType.PLAYER), false, "choice", List.of(new FactKnowledgeLevel(1,"choice")), Set.of()));
            swapStory(new StoryDefinitionSnapshot(storyBefore.actors(), storyBefore.locations(), facts, storyBefore.coverStories(), storyBefore.disclosurePolicies(),
                    Map.of(left.id(),left,right.id(),right,independent.id(),independent), Map.of(), Map.of(), Map.of(), storyBefore.generation()+1));
            var policy = new ContentDefinitions.Admission(true, Set.of(resource));
            ContentDefinitions.INSTANCE.apply(new ContentDefinitions.Snapshot(Map.of(), Map.of(left.id(),policy,right.id(),policy), Map.of()),null,null);
            check(StoryEventService.INSTANCE.triggerRegistered(a.server,left.id(),Optional.of(a)).status()==StoryEventService.Status.STARTED,"first choice failed");
            check(StoryEventService.INSTANCE.triggerRegistered(a.server,right.id(),Optional.of(b)).status()==StoryEventService.Status.REJECTED,"shared resource was used twice");
            check(StoryEventService.INSTANCE.triggerRegistered(a.server,independent.id(),Optional.of(b)).status()==StoryEventService.Status.STARTED,"independent event globally blocked");
            var state = StoryRuntimeState.get(a.server);
            var instance = state.eventInstances().values().stream().filter(e->e.eventId().equals(left.id())).findFirst().orElseThrow();
            var saved = state.save(new CompoundTag(),a.registryAccess()); var restored=StoryRuntimeState.load(saved,a.registryAccess());
            check(restored.isReady()&&!StoryAdmission.available(Set.of(resource),restored.eventInstances().values()),"restart released occupied resource");
            check(instance.withStatus(StoryRuntimeModels.EventStatus.RECOVERY_REQUIRED).reservedResources().contains(resource),"status change lost reservation");
            check(!StoryEventService.INSTANCE.choose(b,instance.instanceId(),instance.revision(),left.outcomes().getFirst().id()).accepted(),"outsider made choice");
            check(StoryEventService.INSTANCE.choose(a,instance.instanceId(),instance.revision(),left.outcomes().getFirst().id()).accepted(),"owner choice failed");
            check(StoryEventService.INSTANCE.triggerRegistered(a.server,right.id(),Optional.of(b)).status()==StoryEventService.Status.STARTED,"completed event did not release resource");
            var rightInstance=state.eventInstances().values().stream().filter(e->e.eventId().equals(right.id())).findFirst().orElseThrow();
            StoryEventService.INSTANCE.choose(b,rightInstance.instanceId(),rightInstance.revision(),right.outcomes().getFirst().id());
            var corrupt = saved.copy(); corrupt.getList("events", Tag.TAG_COMPOUND).getCompound(0).putInt("reservedResources", 4);
            check(!StoryRuntimeState.load(corrupt,a.registryAccess()).isReady(),"corrupt reservation was ignored");
            var wrongType=saved.copy();var wrongList=new ListTag();wrongList.add(new CompoundTag());
            wrongType.getList("events",Tag.TAG_COMPOUND).getCompound(0).put("reservedResources",wrongList);
            check(!StoryRuntimeState.load(wrongType,a.registryAccess()).isReady(),"wrong list type silently removed reservations");
            h.succeed();
        } finally { ContentDefinitions.INSTANCE.apply(before,null,null);swapStory(storyBefore);disconnect(a,channels);disconnect(b,List.of()); }
    }

    @GameTest(templateNamespace="mythictrpg_content_systems", template="empty", timeoutTicks=100)
    public static void strictDefinitionsAndVisitPreferences(GameTestHelper h) throws Exception {
        var player=UUID.randomUUID(); var other=UUID.randomUUID();
        var preferences=new HomeVisitPreferences(); check(preferences.allowed(player),"legacy default changed");
        check(preferences.setAllowed(player,false)&&!preferences.allowed(player)&&preferences.allowed(other),"visit setting crossed player");
        var saved=preferences.save(new CompoundTag(),h.getLevel().registryAccess());
        check(!HomeVisitPreferences.load(saved,h.getLevel().registryAccess()).allowed(player),"visit setting lost on restart");
        saved.putInt("version",99); var rejected=HomeVisitPreferences.load(saved,h.getLevel().registryAccess());
        check(!rejected.allowed(other)&&!rejected.setAllowed(player,true)&&rejected.save(new CompoundTag(),h.getLevel().registryAccess()).equals(saved),"corrupt preferences silently reset");
        rejects(()->ContentDefinitions.admission(JsonParser.parseString("{\"schemaVersion\":1,\"playerExclusive\":true,\"resources\":[\"test:a\",\"test:a\"]}").getAsJsonObject()));
        rejects(()->ContentDefinitions.admission(JsonParser.parseString("{\"schemaVersion\":1,\"playerExclusive\":true,\"resources\":[\"mythictrpg:content_player/forged\"]}").getAsJsonObject()));
        rejects(()->ContentDefinitions.countermeasure(id("bad"),JsonParser.parseString("{\"schemaVersion\":1,\"raid\":\"test:r\",\"damageType\":\"minecraft:mob_attack\",\"item\":\"minecraft:feather\",\"consume\":true,\"multiplier\":1}").getAsJsonObject()));
        var parsed=ContentDefinitions.countermeasure(id("valid"),JsonParser.parseString("{\"schemaVersion\":1,\"raid\":\"test:r\",\"damageType\":\"minecraft:mob_attack\",\"item\":\"minecraft:feather\",\"components\":{\"minecraft:custom_model_data\":31},\"consume\":true,\"multiplier\":0.5}").getAsJsonObject());
        check(parsed.components().getInt("minecraft:custom_model_data")==31,"tool JSON lost component identity");
        var site=ContentDefinitions.investigation(id("valid_site"),JsonParser.parseString("{\"schemaVersion\":1,\"dimension\":\"minecraft:overworld\",\"position\":[1,64,1],\"block\":\"minecraft:stone\",\"radius\":4,\"event\":\"test:trace\",\"heldItem\":\"minecraft:feather\",\"heldCount\":1,\"cooldownTicks\":200,\"components\":{\"minecraft:custom_model_data\":31},\"condition\":{\"type\":\"mythictrpg:always\"}}").getAsJsonObject());
        check(site.components().getInt("minecraft:custom_model_data")==31,"investigation JSON lost required variant");
        var effectParser=StoryDefinitionManager.class.getDeclaredMethod("parseEffect",com.google.gson.JsonObject.class);effectParser.setAccessible(true);
        var reward=effectParser.invoke(null,JsonParser.parseString("{\"effectId\":\"test:reward\",\"type\":\"GRANT_REWARDS\",\"god\":\"mythictrpg:demeter\",\"rewards\":[{\"type\":\"item\",\"itemId\":\"minecraft:gold_nugget\",\"count\":1}]}").getAsJsonObject());
        check(reward instanceof Effect.GrantRewards,"authored Story reward effect was not parsed");
        var recovery=effectParser.invoke(null,JsonParser.parseString("{\"effectId\":\"test:recovery\",\"type\":\"RECOVER_REPUTATION\",\"god\":\"mythictrpg:fortuna\",\"policy\":\"test:policy\",\"reason\":\"test:repair\"}").getAsJsonObject());
        check(recovery instanceof Effect.RecoverReputation,"authored Story recovery effect was not parsed");
        h.succeed();
    }
    public static EventDefinition event(ResourceLocation id, boolean choice, List<Effect> effects) {
        var outcome=new OutcomeDefinition(ResourceLocation.fromNamespaceAndPath(id.getNamespace(),id.getPath()+"_outcome"),1,Optional.empty(),effects);
        return new EventDefinition(id,NarrativeRole.SIDE,ScopeType.PLAYER,
                List.of(new TriggerDefinition(StorySignalTypes.ADMIN_TRIGGERED,Optional.of(id),TriggerMode.IMMEDIATE,0)),
                new AlwaysCondition(BuiltinConditionTypes.ALWAYS),List.of(),new RepeatPolicy(RepeatType.ONCE,1,0),
                choice?ResolutionPolicy.PLAYER_CHOICE:ResolutionPolicy.FIXED,false,List.of(outcome),
                choice?Optional.of(new ChoicePolicy(-1,Optional.empty())):Optional.empty(),FailurePolicy.BLOCK_AND_REPORT,id.toString());
    }
    public static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath("test",path+"_"+UUID.randomUUID()); }
    public static void check(boolean value,String message) { if(!value)throw new AssertionError(message); }
    static void rejects(Runnable action) { try {action.run();}catch(IllegalArgumentException expected){return;}throw new AssertionError("invalid definition accepted"); }
    public static void swapStory(StoryDefinitionSnapshot snapshot) throws Exception {
        Field field=StoryDefinitionManager.class.getDeclaredField("snapshot");field.setAccessible(true);field.set(StoryDefinitionManager.INSTANCE,snapshot);
    }
    public static ServerPlayer connect(GameTestHelper helper,String name,List<EmbeddedChannel> channels) {
        var cookie=net.minecraft.server.network.CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(),name),false);
        var player=new ServerPlayer(helper.getLevel().getServer(),helper.getLevel(),cookie.gameProfile(),cookie.clientInformation());
        var connection=new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        channels.add(new EmbeddedChannel(connection));net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(connection);
        player.server.getPlayerList().placeNewPlayer(connection,player,cookie); return player;
    }
    public static void disconnect(ServerPlayer player,List<EmbeddedChannel> channels) {
        player.server.getPlayerList().remove(player); channels.forEach(EmbeddedChannel::finishAndReleaseAll);
    }
}
