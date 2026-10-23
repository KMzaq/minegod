package com.sande.mythictrpg.content;

import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import com.sande.mythictrpg.quest.reward.RewardClaimState;
import com.sande.mythictrpg.gameplay.observation.GameplayIngressService;
import com.sande.mythictrpg.story.api.StoryStateView.StoryKnowledgeHolder;
import com.sande.mythictrpg.story.definition.StoryDefinitionManager;
import com.sande.mythictrpg.story.definition.StoryDefinitionSnapshot;
import com.sande.mythictrpg.story.state.StoryRuntimeState;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static com.sande.mythictrpg.content.ContentSystemsGameTests.*;

/** Exercises authored resources and real non-operator entry points instead of injected runtime definitions. */
@GameTestHolder("mythictrpg_content_systems")
@PrefixGameTestTemplate(false)
public final class ExplorationGameplayGameTests {
    @GameTest(templateNamespace = "mythictrpg_content_systems", template = "empty", timeoutTicks = 200)
    public static void authoredInvestigationsReachOrdinaryCommandsAndBlockUse(GameTestHelper helper) throws Exception {
        var channels = new ArrayList<EmbeddedChannel>();
        var owner = connect(helper, "ExploreOwner", channels);
        var other = connect(helper, "ExploreOther", channels);
        var priorContent = ContentDefinitions.INSTANCE.snapshot();
        var priorStory = StoryDefinitionManager.INSTANCE.snapshot();
        var server = owner.server;
        var initialStoryState = StoryRuntimeState.get(server);
        var initialClaims = RewardClaimState.get(server);
        try {
            owner.setGameMode(GameType.SURVIVAL);
            other.setGameMode(GameType.SURVIVAL);
            owner.getInventory().selected = 8;
            check(!server.getPlayerList().isOp(owner.getGameProfile()), "fixture player is an operator");
            var site = id("ordinary_investigation");
            var alias = id("same_event_other_site");
            var alternate = id("alternate_material_site");
            var firstEvent = id("ordinary_material_event");
            var alternateEvent = id("alternate_material_event");
            var fact = id("personal_clue");
            var disclosure = id("personal_clue_policy");
            BlockPos position = helper.absolutePos(new BlockPos(2, 2, 2));
            BlockPos alternatePosition = position.east(2);
            helper.getLevel().setBlockAndUpdate(position, Blocks.STONE.defaultBlockState());
            helper.getLevel().setBlockAndUpdate(alternatePosition, Blocks.STONE.defaultBlockState());
            helper.getLevel().setBlockAndUpdate(alternatePosition.above(), Blocks.AIR.defaultBlockState());
            owner.setPos(position.getX() + 0.5, position.getY(), position.getZ() + 2.5);
            other.setPos(position.getX() + 30.5, position.getY(), position.getZ() + 2.5);
            var files = new LinkedHashMap<ResourceLocation, String>();
            files.put(file("investigations", site), siteJson(position, firstEvent));
            files.put(file("investigations", alias), siteJson(position, firstEvent));
            files.put(file("investigations", alternate), siteJson(alternatePosition, alternateEvent));
            files.put(file("story_facts", fact), """
                    {"schemaVersion":1,"allowedScopes":["PLAYER"],"defaultValue":false,
                     "adminSummary":"Hidden author notes","keywords":[],
                     "levels":[{"level":1,"canonicalTranslationKey":"Learned field clue"},
                               {"level":2,"canonicalTranslationKey":"Unlearned field clue"}]}
                    """);
            files.put(file("story_disclosure_policies", disclosure), """
                    {"schemaVersion":1,"mode":"FULL"}
                    """);
            files.put(file("story_events", firstEvent), rewardEventJson(firstEvent, fact, disclosure, 2));
            files.put(file("story_events", alternateEvent), rewardEventJson(alternateEvent, fact, disclosure, 3));
            var resources = new OverlayResources(server.getResourceManager(), files);
            reloadStory(resources);
            ContentDefinitions.INSTANCE.apply(ContentDefinitions.INSTANCE.prepare(resources, InactiveProfiler.INSTANCE),
                    resources, InactiveProfiler.INSTANCE);
            check(ContentDefinitions.INSTANCE.snapshot().investigations().containsKey(site),
                    "authored investigation path was not loaded");

            var dispatcher = server.getCommands().getDispatcher();
            var ordinary = owner.createCommandSourceStack().withPermission(0);
            check(dispatcher.execute("mythinvestigate " + site, other.createCommandSourceStack().withPermission(0)) == 0,
                    "ordinary distant command bypassed physical site");
            int before = owner.getInventory().countItem(Items.GOLD_NUGGET);
            check(dispatcher.execute("mythinvestigate " + site, ordinary) == 1,
                    "registered non-operator investigation command failed");
            check(owner.getInventory().countItem(Items.GOLD_NUGGET) == before + 2,
                    "JSON-authored event did not deliver actual material");
            check(dispatcher.execute("mythclues", ordinary) == 1
                    && dispatcher.execute("mythclues read " + fact, ordinary) == 1,
                    "non-operator cannot reread learned clue");
            check(dispatcher.execute("mythclues read " + fact, other.createCommandSourceStack().withPermission(0)) == 0,
                    "clue command exposed another player's knowledge");

            // A second authored route grants the same material through the actual vanilla interaction pipeline.
            owner.setPos(alternatePosition.getX() + 0.5, alternatePosition.getY(), alternatePosition.getZ() + 2.5);
            owner.setShiftKeyDown(true);
            owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIRT, 4));
            var interaction = owner.gameMode.useItemOn(owner, helper.getLevel(), owner.getMainHandItem(),
                    InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(alternatePosition), Direction.UP, alternatePosition, false));
            check(interaction.consumesAction(), "registered block investigation did not consume interaction");
            check(owner.getInventory().countItem(Items.GOLD_NUGGET) == before + 5,
                    "alternate physical route did not grant the same material");
            check(owner.getMainHandItem().getCount() == 4 && helper.getLevel().getBlockState(alternatePosition.above()).isAir(),
                    "successful investigation also placed or consumed the held item");
            check(other.getInventory().countItem(Items.GOLD_NUGGET) == 0, "material leaked to bystander");
            owner.setShiftKeyDown(false);

            // Restore both actual stores from NBT; an unused site alias avoids the transient attempt cooldown.
            var savedStory = StoryRuntimeState.get(server).save(new CompoundTag(), owner.registryAccess());
            var restoredStory = StoryRuntimeState.load(savedStory, owner.registryAccess());
            var loadClaims = RewardClaimState.class.getDeclaredMethod("load", CompoundTag.class,
                    net.minecraft.core.HolderLookup.Provider.class);
            loadClaims.setAccessible(true);
            var restoredClaims = (RewardClaimState) loadClaims.invoke(null, RewardClaimState.get(server)
                    .save(new CompoundTag(), owner.registryAccess()), owner.registryAccess());
            check(restoredStory.isReady() && restoredClaims.isWritable(), "gameplay stores did not restore");
            server.overworld().getDataStorage().set("mythictrpg_story", restoredStory);
            server.overworld().getDataStorage().set("mythictrpg_reward_claims", restoredClaims);
            owner.setPos(position.getX() + 0.5, position.getY(), position.getZ() + 2.5);
            check(dispatcher.execute("mythinvestigate " + alias, owner.createCommandSourceStack().withPermission(0)) == 0,
                    "restored ONCE event was replayable through a different investigation");
            check(owner.getInventory().countItem(Items.GOLD_NUGGET) == before + 5,
                    "restoring gameplay stores duplicated material");
            check(restoredStory.knowledgeLevel(StoryKnowledgeHolder.player(owner.getUUID()), fact) == 1
                    && restoredStory.knowledgeLevel(StoryKnowledgeHolder.player(other.getUUID()), fact) == 0,
                    "restored clue ownership or learned level changed");
            check(dispatcher.execute("mythclues read " + fact, owner.createCommandSourceStack().withPermission(0)) == 1,
                    "restored clue was not accessible through its ordinary command");
            helper.succeed();
        } finally {
            server.overworld().getDataStorage().set("mythictrpg_story", initialStoryState);
            server.overworld().getDataStorage().set("mythictrpg_reward_claims", initialClaims);
            ContentDefinitions.INSTANCE.apply(priorContent, null, null);
            swapStory(priorStory);
            disconnect(owner, channels);
            disconnect(other, List.of());
        }
    }

    @GameTest(templateNamespace = "mythictrpg_content_systems", template = "empty", timeoutTicks = 100)
    public static void malformedAuthoredReloadKeepsTheLastUsableSnapshot(GameTestHelper helper) {
        var prior = ContentDefinitions.INSTANCE.snapshot();
        try {
            var site = id("reload_site");
            var resources = new OverlayResources(helper.getLevel().getServer().getResourceManager(),
                    Map.of(file("investigations", site), siteJson(new BlockPos(0, 64, 0), id("target"))));
            var prepared = ContentDefinitions.INSTANCE.prepare(resources, InactiveProfiler.INSTANCE);
            ContentDefinitions.INSTANCE.apply(prepared, resources, InactiveProfiler.INSTANCE);
            var invalid = new OverlayResources(helper.getLevel().getServer().getResourceManager(),
                    Map.of(file("investigations", site), "{\"schemaVersion\":1,\"unexpected\":true}"));
            boolean rejected = false;
            try {
                var invalidPrepared = ContentDefinitions.INSTANCE.prepare(invalid, InactiveProfiler.INSTANCE);
                ContentDefinitions.INSTANCE.apply(invalidPrepared, invalid, InactiveProfiler.INSTANCE);
            } catch (IllegalArgumentException expected) {
                rejected = true;
            }
            check(rejected && ContentDefinitions.INSTANCE.snapshot() == prepared,
                    "invalid authored reload discarded last usable snapshot");
            helper.succeed();
        } finally {
            ContentDefinitions.INSTANCE.apply(prior, null, null);
        }
    }

    @GameTest(templateNamespace = "mythictrpg_content_systems", template = "empty", timeoutTicks = 200)
    public static void ordinaryHarvestReachesAuthoredMaterialRewardThroughProductionObservation(GameTestHelper helper)
            throws Exception {
        var channels = new ArrayList<EmbeddedChannel>();
        var player = connect(helper, "HarvestRoute", channels);
        var priorStory = StoryDefinitionManager.INSTANCE.snapshot();
        var ingress = GameplayIngressService.INSTANCE;
        var override = GameplayIngressService.class.getDeclaredField("testOverride");
        override.setAccessible(true);
        var previousOverride = override.get(ingress);
        try {
            player.setGameMode(GameType.SURVIVAL);
            // Other observation tests may have installed a capture sink. Exercise the configured
            // production chain here, then restore that fixture state without replacing the chain.
            ingress.clearSinkOverrideForTesting();
            var event = id("harvest_material_route");
            var fact = id("harvest_route_fact");
            var disclosure = id("harvest_disclosure");
            var eventJson = com.google.gson.JsonParser.parseString(rewardEventJson(event, fact, disclosure, 2)).getAsJsonObject();
            var trigger = eventJson.getAsJsonArray("triggers").get(0).getAsJsonObject();
            trigger.addProperty("signal", "mythictrpg:mature_crop_harvested");
            trigger.addProperty("subject", "minecraft:wheat");
            var files = Map.of(
                    file("story_events", event), eventJson.toString(),
                    file("story_facts", fact), """
                            {"schemaVersion":1,"allowedScopes":["PLAYER"],"defaultValue":false,
                             "adminSummary":"Harvest route author notes","keywords":[],
                             "levels":[{"level":1,"canonicalTranslationKey":"Learned harvest route"}]}
                            """,
                    file("story_disclosure_policies", disclosure), "{\"schemaVersion\":1,\"mode\":\"FULL\"}");
            reloadStory(new OverlayResources(player.server.getResourceManager(), files));
            var crop = helper.absolutePos(new BlockPos(3, 2, 3));
            player.setPos(crop.getX() + 0.5, crop.getY(), crop.getZ() + 2.5);
            helper.getLevel().setBlockAndUpdate(crop.below(), Blocks.FARMLAND.defaultBlockState());
            helper.getLevel().setBlockAndUpdate(crop, Blocks.WHEAT.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.CropBlock.AGE, 0));
            check(player.gameMode.destroyBlock(crop), "ordinary immature crop removal failed");
            ingress.drain(player.server);
            check(player.getInventory().countItem(Items.GOLD_NUGGET) == 0, "immature harvest granted material");

            helper.getLevel().setBlockAndUpdate(crop, Blocks.WHEAT.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.CropBlock.AGE, 7));
            check(player.gameMode.destroyBlock(crop), "ordinary mature crop removal failed");
            ingress.drain(player.server);
            check(player.getInventory().countItem(Items.GOLD_NUGGET) == 2,
                    "real harvest did not reach authored Story material reward through production observation sink");
            check(StoryRuntimeState.get(player.server).knowledgeLevel(
                    StoryKnowledgeHolder.player(player.getUUID()), fact) == 1, "harvest event did not grant its authored knowledge");

            helper.getLevel().setBlockAndUpdate(crop, Blocks.WHEAT.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.CropBlock.AGE, 7));
            check(player.gameMode.destroyBlock(crop), "repeated mature crop removal failed");
            ingress.drain(player.server);
            check(player.getInventory().countItem(Items.GOLD_NUGGET) == 2,
                    "ordinary repeated harvest bypassed event ONCE material policy");
            helper.succeed();
        } finally {
            override.set(ingress, previousOverride);
            swapStory(priorStory);
            disconnect(player, channels);
        }
    }

    private static ResourceLocation file(String family, ResourceLocation id) {
        return ResourceLocation.fromNamespaceAndPath(id.getNamespace(), "mythictrpg/" + family + "/" + id.getPath() + ".json");
    }

    private static String siteJson(BlockPos position, ResourceLocation event) {
        return """
                {"schemaVersion":1,"dimension":"minecraft:overworld","position":[%d,%d,%d],
                 "block":"minecraft:stone","radius":4,"event":"%s","heldItem":"NONE",
                 "heldCount":1,"cooldownTicks":20,"condition":{"type":"mythictrpg:always"}}
                """.formatted(position.getX(), position.getY(), position.getZ(), event);
    }

    private static String rewardEventJson(ResourceLocation event, ResourceLocation fact,
            ResourceLocation disclosure, int amount) {
        return """
                {"schemaVersion":1,"narrativeRole":"SIDE","scope":"PLAYER",
                 "triggers":[{"signal":"mythictrpg:admin_triggered","subject":"%s","mode":"IMMEDIATE"}],
                 "prerequisites":{"type":"mythictrpg:always"},"actors":[],
                 "repeatPolicy":{"type":"ONCE"},"resolutionPolicy":"FIXED",
                 "failurePolicy":"BLOCK_AND_REPORT",
                 "outcomes":[{"id":"%s_outcome","effects":[
                   {"effectId":"%s_learn","type":"GRANT_KNOWLEDGE","holder":"TRIGGER_PLAYER",
                    "fact":"%s","level":1,"disclosurePolicy":"%s"},
                   {"effectId":"%s_reward","type":"GRANT_REWARDS","god":"mythictrpg:demeter",
                    "rewards":[{"type":"item","itemId":"minecraft:gold_nugget","count":%d}]}]}]}
                """.formatted(event, event, event, fact, disclosure, event, amount);
    }

    private static void reloadStory(ResourceManager resources) throws Exception {
        var manager = StoryDefinitionManager.INSTANCE;
        var prepare = StoryDefinitionManager.class.getDeclaredMethod("prepare", ResourceManager.class, ProfilerFiller.class);
        var apply = StoryDefinitionManager.class.getDeclaredMethod("apply", StoryDefinitionSnapshot.class,
                ResourceManager.class, ProfilerFiller.class);
        prepare.setAccessible(true);
        apply.setAccessible(true);
        var prepared = (StoryDefinitionSnapshot) prepare.invoke(manager, resources, InactiveProfiler.INSTANCE);
        apply.invoke(manager, prepared, resources, InactiveProfiler.INSTANCE);
    }

    /** Supplies in-memory datapack files through the same ResourceManager contract as a real pack. */
    private static final class OverlayResources implements ResourceManager {
        private final ResourceManager delegate;
        private final Map<ResourceLocation, Resource> files;

        private OverlayResources(ResourceManager delegate, Map<ResourceLocation, String> json) {
            this.delegate = delegate;
            var pack = delegate.listPacks().findFirst().orElseThrow();
            var values = new LinkedHashMap<ResourceLocation, Resource>();
            json.forEach((id, text) -> values.put(id,
                    new Resource(pack, () -> new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)))));
            files = Map.copyOf(values);
        }
        @Override public Optional<Resource> getResource(ResourceLocation id) {
            return Optional.ofNullable(files.get(id)).or(() -> delegate.getResource(id));
        }
        @Override public Set<String> getNamespaces() {
            var result = new LinkedHashSet<>(delegate.getNamespaces());
            files.keySet().stream().map(ResourceLocation::getNamespace).forEach(result::add);
            return Set.copyOf(result);
        }
        @Override public List<Resource> getResourceStack(ResourceLocation id) {
            return files.containsKey(id) ? List.of(files.get(id)) : delegate.getResourceStack(id);
        }
        @Override public Map<ResourceLocation, Resource> listResources(String path, Predicate<ResourceLocation> filter) {
            var result = new LinkedHashMap<>(delegate.listResources(path, filter));
            files.forEach((id, value) -> { if (id.getPath().startsWith(path + "/") && filter.test(id)) result.put(id, value); });
            return result;
        }
        @Override public Map<ResourceLocation, List<Resource>> listResourceStacks(String path, Predicate<ResourceLocation> filter) {
            var result = new LinkedHashMap<>(delegate.listResourceStacks(path, filter));
            files.forEach((id, value) -> { if (id.getPath().startsWith(path + "/") && filter.test(id)) result.put(id, List.of(value)); });
            return result;
        }
        @Override public Stream<PackResources> listPacks() { return delegate.listPacks(); }
    }
}
