package com.sande.mythictrpg.raid;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.condition.builtin.AlwaysCondition;
import com.sande.mythictrpg.condition.builtin.BuiltinConditionTypes;
import com.sande.mythictrpg.godavatar.GodAvatarDefinition;
import com.sande.mythictrpg.godavatar.GodAvatarDefinitionManager;
import com.sande.mythictrpg.godavatar.GodAvatarEntity;
import com.sande.mythictrpg.godavatar.GodAvatarRegistryState;
import com.sande.mythictrpg.godavatar.GodAvatarService;
import com.sande.mythictrpg.quest.reward.CurrencyRewardEntry;
import com.sande.mythictrpg.quest.reward.RewardClaimState;
import io.netty.channel.embedded.EmbeddedChannel;
import net.neoforged.neoforge.common.damagesource.DamageContainer;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Synthetic, content-neutral fixture for the explicit queue and actual boss-death path. */
@GameTestHolder("mythictrpg_raid")
@PrefixGameTestTemplate(false)
public final class RaidGameTests {
    private static final ResourceLocation RAID = id("test_raid_runtime");
    private static final ResourceLocation ARENA = id("test_raid_arena");
    private static final ResourceLocation GOD_RAID = id("test_god_raid_runtime");
    private static final ResourceLocation GOD_ARENA = id("test_god_raid_arena");
    private static final ResourceLocation GOD = id("demeter");
    private RaidGameTests() { }

    @GameTest(templateNamespace = "mythictrpg_raid", template = "empty", timeoutTicks = 100)
    public static void queueBossDeathReturnAndCorruptSnapshot(GameTestHelper helper) {
        var channels = new ArrayList<EmbeddedChannel>();
        ServerPlayer player = connect(helper, "RaidFixture", channels);
        ServerPlayer outsider = connect(helper, "RaidOutsider", channels);
        RaidCatalog.Snapshot previous = RaidCatalog.INSTANCE.snapshot();
        Vec3 original;
        try {
            BlockPos center = helper.absolutePos(new BlockPos(2, 2, 2));
            Vec3 anchor = Vec3.atBottomCenterOf(center);
            player.setPos(anchor.add(14, 0, 0));
            outsider.setPos(anchor.add(16, 0, 0));
            original = player.position();
            for (int x = -3; x <= 6; x++) for (int z = -3; z <= 6; z++) {
                BlockPos floor = center.offset(x, -1, z);
                helper.getLevel().setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
                helper.getLevel().setBlockAndUpdate(floor.above(), Blocks.AIR.defaultBlockState());
                helper.getLevel().setBlockAndUpdate(floor.above(2), Blocks.AIR.defaultBlockState());
            }
            var arena = new RaidDefinition.Arena(ARENA, helper.getLevel().dimension().location(),
                    new AABB(anchor.x - 2, anchor.y - 1, anchor.z - 2,
                            anchor.x + 5, anchor.y + 4, anchor.z + 5),
                    anchor, anchor.add(2, 0, 2), anchor.add(12, 0, 0),
                    arenaJson(anchor, helper.getLevel().dimension().location()));
            // The runtime stores this authored snapshot, independent of later catalog reloads.
            var definition = new RaidDefinition(RAID, "Synthetic raid fixture", RaidDefinition.Mode.PUBLIC_WORLD,
                    List.of(ARENA), 1, 1, 120, 120, 1, 20,
                    new RaidDefinition.Boss(Optional.of(ResourceLocation.parse("minecraft:zombie")), Optional.empty()),
                    List.of(), new AlwaysCondition(BuiltinConditionTypes.ALWAYS),
                    RaidDefinition.RewardEligibility.ALL_FROZEN_ROSTER, Set.of(), id("demeter"),
                    List.of(new CurrencyRewardEntry(1)), fixtureJson());
            swap(new RaidCatalog.Snapshot(Map.of(RAID, definition), Map.of(ARENA, arena)));
            var created = RaidRuntime.INSTANCE.create(player, RAID);
            helper.assertTrue(created.succeeded(), "raid creation failed: " + created.message());
            UUID run = created.attemptId().orElseThrow();
            helper.assertTrue(RaidRuntime.INSTANCE.start(player, run).succeeded(), "leader could not queue raid");
            RaidRuntime.tick(new ServerTickEvent.Post(() -> true, player.server));
            RaidState.Attempt attempt = RaidState.get(player.server).find(run).orElseThrow();
            helper.assertValueEqual(attempt.status, RaidState.Status.ACTIVE, "boss did not start");
            helper.assertTrue(arena.bounds().contains(player.position()), "player was not teleported to arena");
            LivingEntity boss = RaidRuntime.living(helper.getLevel().getEntity(attempt.bossId));
            helper.assertTrue(boss != null && run.equals(RaidRuntime.raidTag(boss)), "boss was not tagged to attempt");
            var outsiderAttack = new LivingIncomingDamageEvent(boss,
                    new DamageContainer(boss.damageSources().playerAttack(outsider), 1));
            RaidProtection.damage(outsiderAttack);
            helper.assertTrue(outsiderAttack.isCanceled(), "outsider attack on raid boss was accepted");
            var unfairAttack = new LivingIncomingDamageEvent(outsider,
                    new DamageContainer(outsider.damageSources().playerAttack(player), 1));
            RaidProtection.damage(unfairAttack);
            helper.assertTrue(unfairAttack.isCanceled(), "raid member could attack an outsider with immunity");
            var waiting = RaidRuntime.INSTANCE.create(outsider, RAID);
            helper.assertTrue(waiting.succeeded(), "second party could not form");
            UUID waitingRun = waiting.attemptId().orElseThrow();
            helper.assertTrue(RaidRuntime.INSTANCE.start(outsider, waitingRun).succeeded(), "second party could not queue");
            RaidRuntime.tick(new ServerTickEvent.Post(() -> true, player.server));
            helper.assertValueEqual(RaidState.get(player.server).find(waitingRun).orElseThrow().status,
                    RaidState.Status.QUEUED, "occupied arena was assigned twice");
            helper.assertTrue(boss.hurt(player.damageSources().playerAttack(player), 1000), "boss ignored valid roster damage");
            RaidRuntime.tick(new ServerTickEvent.Post(() -> true, player.server));
            helper.assertValueEqual(attempt.status, RaidState.Status.SUCCEEDED, "confirmed death did not settle victory");
            ResourceLocation source = id("raid/" + run);
            UUID receipt = RewardClaimState.get(player.server).findBySource(player.getUUID(), source)
                    .orElseThrow().claimId();
            RaidRuntime.tick(new ServerTickEvent.Post(() -> true, player.server));
            helper.assertValueEqual(RewardClaimState.get(player.server).findBySource(player.getUUID(), source)
                    .orElseThrow().claimId(), receipt, "duplicate raid reward receipt");
            helper.assertTrue(player.position().distanceToSqr(original) < 4, "player was not returned to origin");
            helper.assertTrue(RaidState.get(player.server).membership(player.getUUID()).isEmpty(), "finished raid held membership");
            RaidRuntime.tick(new ServerTickEvent.Post(() -> true, player.server));
            helper.assertValueEqual(RaidState.get(player.server).find(waitingRun).orElseThrow().status,
                    RaidState.Status.ACTIVE, "waiting party did not acquire the released arena");
            helper.assertTrue(RaidRuntime.INSTANCE.cancel(outsider, waitingRun).succeeded(), "waiting party cleanup failed");
            CompoundTag saved = RaidState.get(player.server).save(new CompoundTag(), player.registryAccess());
            helper.assertValueEqual(RaidState.load(saved, player.registryAccess()).find(run).orElseThrow().status,
                    RaidState.Status.SUCCEEDED, "raid outcome did not survive persistence");
            CompoundTag corrupt = new CompoundTag(); corrupt.putInt("dataVersion", 999); corrupt.putLong("sentinel", 73);
            var rejected = RaidState.load(corrupt, player.registryAccess());
            helper.assertTrue(!rejected.ready(), "corrupt raid state was accepted");
            helper.assertValueEqual(rejected.save(new CompoundTag(), player.registryAccess()).getLong("sentinel"),
                    73L, "corrupt original was not preserved");
            var malformed = saved.copy();
            var wrongList = new net.minecraft.nbt.ListTag(); wrongList.add(net.minecraft.nbt.StringTag.valueOf("not a raid"));
            malformed.put("attempts", wrongList);
            var wrongType = RaidState.load(malformed, player.registryAccess());
            helper.assertTrue(!wrongType.ready(), "wrong typed attempts list silently became empty state");
            helper.assertValueEqual(wrongType.save(new CompoundTag(), player.registryAccess()), malformed,
                    "wrong typed raid list was overwritten");
            helper.succeed();
        } catch (RuntimeException failure) {
            helper.fail("Raid lifecycle failed: " + failure.getMessage());
        } finally {
            swap(previous);
            RaidState.get(player.server).membership(player.getUUID()).ifPresent(attempt ->
                    RaidRuntime.INSTANCE.cancel(player, attempt.id));
            RaidState.get(outsider.server).membership(outsider.getUUID()).ifPresent(attempt ->
                    RaidRuntime.INSTANCE.cancel(outsider, attempt.id));
            player.server.getPlayerList().remove(player);
            outsider.server.getPlayerList().remove(outsider);
            channels.forEach(EmbeddedChannel::finishAndReleaseAll);
        }
    }

    @GameTest(templateNamespace = "mythictrpg_raid", template = "empty", timeoutTicks = 100)
    public static void globalGodLeaseWaitsThenRunsWithoutDuplication(GameTestHelper helper) {
        var channels = new ArrayList<EmbeddedChannel>();
        ServerPlayer player = connect(helper, "GodRaidFixture", channels);
        RaidCatalog.Snapshot previousCatalog = RaidCatalog.INSTANCE.snapshot();
        Map<?, ?> previousDefinitions;
        long previousGeneration;
        Field definitionsField;
        Field generationField;
        UUID externalLease = UUID.randomUUID();
        try {
            helper.assertTrue(com.sande.mythictrpg.data.god.GodDefinitionManager.INSTANCE.find(GOD).isPresent(),
                    "installed God fixture missing");
            definitionsField = GodAvatarDefinitionManager.class.getDeclaredField("definitions");
            generationField = GodAvatarDefinitionManager.class.getDeclaredField("generation");
            definitionsField.setAccessible(true); generationField.setAccessible(true);
            previousDefinitions = (Map<?, ?>) definitionsField.get(GodAvatarDefinitionManager.INSTANCE);
            previousGeneration = generationField.getLong(GodAvatarDefinitionManager.INSTANCE);
            var patched = new java.util.LinkedHashMap<ResourceLocation, GodAvatarDefinition>();
            for (var entry : previousDefinitions.entrySet())
                patched.put((ResourceLocation) entry.getKey(), (GodAvatarDefinition) entry.getValue());
            patched.put(GOD, new GodAvatarDefinition(GOD,
                    new GodAvatarDefinition.Appearance(0, 1),
                    new GodAvatarDefinition.Stats(20, 0.25, 2, 0, 24),
                    new GodAvatarDefinition.Movement(false, false, false, 0, 0, 0),
                    new GodAvatarDefinition.Combat(true, true, false, true),
                    new GodAvatarDefinition.Placement(false, 0), 4));
            definitionsField.set(GodAvatarDefinitionManager.INSTANCE, Map.copyOf(patched));
            generationField.setLong(GodAvatarDefinitionManager.INSTANCE, previousGeneration + 1);
        } catch (ReflectiveOperationException failure) {
            player.server.getPlayerList().remove(player);
            channels.forEach(EmbeddedChannel::finishAndReleaseAll);
            helper.fail("God raid fixture setup failed: " + failure.getMessage());
            return;
        }
        try {
            BlockPos center = helper.absolutePos(new BlockPos(2, 2, 2));
            Vec3 anchor = Vec3.atBottomCenterOf(center);
            player.setPos(anchor.add(14, 0, 0));
            for (int x = -3; x <= 6; x++) for (int z = -3; z <= 6; z++) {
                BlockPos floor = center.offset(x, -1, z);
                helper.getLevel().setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
                helper.getLevel().setBlockAndUpdate(floor.above(), Blocks.AIR.defaultBlockState());
                helper.getLevel().setBlockAndUpdate(floor.above(2), Blocks.AIR.defaultBlockState());
            }
            var arena = new RaidDefinition.Arena(GOD_ARENA, helper.getLevel().dimension().location(),
                    new AABB(anchor.x - 2, anchor.y - 1, anchor.z - 2,
                            anchor.x + 5, anchor.y + 4, anchor.z + 5),
                    anchor, anchor.add(2, 0, 2), anchor.add(12, 0, 0),
                    arenaJson(anchor, helper.getLevel().dimension().location()));
            var definition = new RaidDefinition(GOD_RAID, "Synthetic God raid fixture", RaidDefinition.Mode.PUBLIC_WORLD,
                    List.of(GOD_ARENA), 1, 1, 120, 120, 1, 20,
                    new RaidDefinition.Boss(Optional.empty(), Optional.of(GOD)), List.of(),
                    new AlwaysCondition(BuiltinConditionTypes.ALWAYS),
                    RaidDefinition.RewardEligibility.SURVIVING_PRESENT, Set.of(GOD), GOD, List.of(), godFixtureJson());
            swap(new RaidCatalog.Snapshot(Map.of(GOD_RAID, definition), Map.of(GOD_ARENA, arena)));
            helper.assertTrue(GodAvatarService.INSTANCE.acquireRaidLease(player.server, GOD, externalLease),
                    "synthetic external God lease unavailable");
            var externalGod = GodAvatarService.INSTANCE.spawnForRaid(helper.getLevel(), GOD,
                    arena.bossSpawn(), externalLease).orElseThrow();
            helper.assertTrue(!RaidRuntime.INSTANCE.validateOffer(player, id("unauthorized_god"), GOD_RAID).succeeded(),
                    "unauthorized God acquired raid offer authority");
            helper.assertTrue(RaidRuntime.INSTANCE.validateOffer(player, GOD, GOD_RAID).succeeded(),
                    "authored God offer was unavailable before creation");
            helper.assertTrue(RaidState.get(player.server).membership(player.getUUID()).isEmpty(),
                    "offer validation created a raid attempt");
            var created = RaidRuntime.INSTANCE.create(player, GOD_RAID);
            helper.assertTrue(created.succeeded(), "God raid create failed: " + created.message());
            UUID run = created.attemptId().orElseThrow();
            helper.assertTrue(RaidRuntime.INSTANCE.validateOffer(player, GOD, GOD_RAID).succeeded() == false,
                    "offer validation ignored existing active membership");
            helper.assertTrue(RaidRuntime.INSTANCE.start(player, run).succeeded(), "God raid queue failed");
            RaidRuntime.tick(new ServerTickEvent.Post(() -> true, player.server));
            RaidState.Attempt attempt = RaidState.get(player.server).find(run).orElseThrow();
            helper.assertValueEqual(attempt.status, RaidState.Status.QUEUED, "busy God was duplicated or moved");
            helper.assertTrue(RaidRuntime.INSTANCE.cancel(player, run).succeeded(), "waiting God raid could not cancel");
            helper.assertValueEqual(attempt.status, RaidState.Status.CANCELLED,
                    "unowned external God held cancelled party in cleanup");
            helper.assertTrue(externalGod.isAlive(), "cancelling waiting party removed another God's instance");
            helper.assertValueEqual(GodAvatarRegistryState.get(player.server).raidOwner(GOD).orElseThrow(), externalLease,
                    "waiting party cancellation revoked another owner");
            created = RaidRuntime.INSTANCE.create(player, GOD_RAID);
            helper.assertTrue(created.succeeded(), "cancelled waiting party remained locked");
            run = created.attemptId().orElseThrow();
            helper.assertTrue(RaidRuntime.INSTANCE.start(player, run).succeeded(), "replacement God queue failed");
            RaidRuntime.tick(new ServerTickEvent.Post(() -> true, player.server));
            attempt = RaidState.get(player.server).find(run).orElseThrow();
            helper.assertValueEqual(attempt.status, RaidState.Status.QUEUED, "replacement duplicated external God");
            externalGod.discard();
            GodAvatarService.INSTANCE.releaseRaidLease(player.server, GOD, externalLease);
            RaidRuntime.tick(new ServerTickEvent.Post(() -> true, player.server));
            helper.assertValueEqual(attempt.status, RaidState.Status.ACTIVE, "released God did not enter raid");
            helper.assertTrue(helper.getLevel().getEntity(attempt.bossId) instanceof GodAvatarEntity,
                    "God boss was not the physical avatar type");
            helper.assertValueEqual(GodAvatarRegistryState.get(player.server).raidOwner(GOD).orElseThrow(), run,
                    "raid did not hold the God lease");
            CompoundTag activeSnapshot = RaidState.get(player.server).save(new CompoundTag(), player.registryAccess());
            helper.assertValueEqual(RaidState.load(activeSnapshot, player.registryAccess()).find(run).orElseThrow().status,
                    RaidState.Status.ACTIVE, "active attempt did not persist its frozen state");
            RaidRuntime.INSTANCE.simulateRuntimeRestartForTesting(player.server, run);
            helper.assertValueEqual(attempt.status, RaidState.Status.FAILED,
                    "runtime restart did not abort an active combat");
            helper.assertValueEqual(attempt.reason, "SERVER_RESTART", "restart result reason");
            helper.assertTrue(player.position().distanceToSqr(anchor.add(14, 0, 0)) < 4,
                    "restart recovery did not return the player");
            helper.assertTrue(GodAvatarRegistryState.get(player.server).raidOwner(GOD).isEmpty(),
                    "God lease remained after cleanup");
            helper.assertTrue(GodAvatarRegistryState.get(player.server).find(GOD).isEmpty(),
                    "raid God entity remained registered after cleanup");
            helper.succeed();
        } catch (RuntimeException failure) {
            helper.fail("God raid lifecycle failed: " + failure.getMessage());
        } finally {
            if (GodAvatarRegistryState.get(player.server).raidOwner(GOD).filter(externalLease::equals).isPresent())
                GodAvatarService.INSTANCE.findLoaded(player.server, GOD).ifPresent(avatar -> avatar.discard());
            GodAvatarService.INSTANCE.releaseRaidLease(player.server, GOD, externalLease);
            RaidState.get(player.server).membership(player.getUUID()).ifPresent(attempt ->
                    RaidRuntime.INSTANCE.cancel(player, attempt.id));
            swap(previousCatalog);
            try {
                definitionsField.set(GodAvatarDefinitionManager.INSTANCE, previousDefinitions);
                generationField.setLong(GodAvatarDefinitionManager.INSTANCE, previousGeneration);
            } catch (ReflectiveOperationException failure) { helper.fail("God raid fixture restore failed: " + failure.getMessage()); }
            player.server.getPlayerList().remove(player);
            channels.forEach(EmbeddedChannel::finishAndReleaseAll);
        }
    }

    private static String fixtureJson() {
        return "{\"schemaVersion\":1,\"displayName\":\"Synthetic raid fixture\",\"mode\":\"PUBLIC_WORLD\","
                + "\"arenas\":[\"mythictrpg:test_raid_arena\"],\"minimumPlayers\":1,\"maximumPlayers\":1,"
                + "\"queueTimeoutTicks\":120,\"combatTimeoutTicks\":120,\"livesPerPlayer\":1,"
                + "\"disconnectGraceTicks\":20,\"boss\":{\"entityType\":\"minecraft:zombie\"},"
                + "\"phases\":[],\"entryCondition\":{\"type\":\"mythictrpg:always\"},"
                + "\"rewardEligibility\":\"ALL_FROZEN_ROSTER\",\"rewardGodId\":\"mythictrpg:demeter\","
                + "\"rewards\":[{\"type\":\"currency\",\"amount\":1}]}";
    }

    private static String godFixtureJson() {
        return "{\"schemaVersion\":1,\"displayName\":\"Synthetic God raid fixture\",\"mode\":\"PUBLIC_WORLD\","
                + "\"arenas\":[\"mythictrpg:test_god_raid_arena\"],\"minimumPlayers\":1,\"maximumPlayers\":1,"
                + "\"queueTimeoutTicks\":120,\"combatTimeoutTicks\":120,\"livesPerPlayer\":1,"
                + "\"disconnectGraceTicks\":20,\"boss\":{\"godId\":\"mythictrpg:demeter\"},"
                + "\"phases\":[],\"entryCondition\":{\"type\":\"mythictrpg:always\"},"
                + "\"rewardEligibility\":\"SURVIVING_PRESENT\",\"offerGodIds\":[\"mythictrpg:demeter\"],"
                + "\"rewardGodId\":\"mythictrpg:demeter\",\"rewards\":[]}";
    }

    private static String arenaJson(Vec3 anchor, ResourceLocation dimension) {
        var json = new JsonObject();
        json.addProperty("schemaVersion", 1);
        json.addProperty("dimension", dimension.toString());
        json.add("minimum", vector(anchor.add(-2, -1, -2)));
        json.add("maximum", vector(anchor.add(5, 4, 5)));
        json.add("entry", vector(anchor));
        json.add("bossSpawn", vector(anchor.add(2, 0, 2)));
        json.add("exit", vector(anchor.add(12, 0, 0)));
        return json.toString();
    }

    private static JsonArray vector(Vec3 position) {
        var array = new JsonArray();
        array.add(position.x); array.add(position.y); array.add(position.z);
        return array;
    }

    private static ServerPlayer connect(GameTestHelper helper, String name, List<EmbeddedChannel> channels) {
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(
                new GameProfile(UUID.randomUUID(), name), false);
        var player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        channels.add(new EmbeddedChannel(connection));
        net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(connection);
        player.server.getPlayerList().placeNewPlayer(connection, player, cookie);
        return player;
    }

    private static void swap(RaidCatalog.Snapshot snapshot) {
        try {
            Field field = RaidCatalog.class.getDeclaredField("snapshot");
            field.setAccessible(true); field.set(RaidCatalog.INSTANCE, snapshot);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Raid fixture catalog", failure); }
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, path);
    }
}
