package com.sande.mythictrpg.data.player;

import com.mojang.authlib.GameProfile;
import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.gameplay.metric.GameplayMetricKey;
import com.sande.mythictrpg.gameplay.metric.GameplayMetricTypes;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@GameTestHolder(MythicTrpg.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PhaseFourATwoOneProfileGameTests {
    private static final String TEMPLATE = "bastion/mobs/empty";
    private static final UUID RESTART_PLAYER_ID = UUID.fromString("9932737c-77f3-430d-902a-20047a5c663e");
    private static final ResourceLocation TEST_GOD = id("migration_god");
    private static final ResourceLocation TEST_ITEM = id("migration_item");

    private PhaseFourATwoOneProfileGameTests() {
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void affinityRangeIsBoundedToOneThousand(GameTestHelper helper) {
        PlayerMythProfile profile = PlayerMythProfile.createActive()
                .withAffinity(TEST_GOD, PlayerMythProfile.MIN_AFFINITY)
                .withAffinity(TEST_GOD, PlayerMythProfile.MAX_AFFINITY);
        helper.assertValueEqual(profile.affinities().get(TEST_GOD), PlayerMythProfile.MAX_AFFINITY,
                "boundary affinity was not accepted");
        try {
            profile.withAffinity(TEST_GOD, PlayerMythProfile.MAX_AFFINITY + 1);
            helper.fail("out-of-range affinity was accepted");
            return;
        } catch (IllegalArgumentException expected) {
            // Expected bounded affinity rejection.
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void profileV1V2MigrationAndV4RoundTrip(GameTestHelper helper) {
        PlayerMythProfile v1 = PlayerMythProfile.load(profileTag(1, false));
        helper.assertValueEqual(v1.dataVersion(), 4, "V1 profile did not migrate to V4");
        assertPreHistoryFields(helper, v1, "V1 migration");
        helper.assertTrue(v1.obtainedItems().isEmpty(), "V1 migration invented item history");
        helper.assertTrue(v1.customGameplayCounters().isEmpty(), "V1 migration invented counters");

        PlayerMythProfile v2 = PlayerMythProfile.load(profileTag(2, true));
        assertLegacyFields(helper, v2, "V2 migration");
        helper.assertTrue(v2.customGameplayCounters().isEmpty(), "V2 migration invented counters");

        GameplayMetricKey aggregate = GameplayMetricKey.aggregate(GameplayMetricTypes.MATURE_CROP_HARVESTED);
        GameplayMetricKey addon = GameplayMetricKey.subject(
                ResourceLocation.fromNamespaceAndPath("exampleaddon", "custom_metric"),
                ResourceLocation.fromNamespaceAndPath("exampleaddon", "custom_subject"));
        PlayerMythProfile original = v2.withCustomGameplayCounters(Map.of(aggregate, 4L, addon, 9L));
        CompoundTag saved = original.save();
        helper.assertValueEqual(saved.getInt("dataVersion"), 4, "V4 profile version was not saved");
        helper.assertTrue(saved.contains("customGameplayCounters"), "V4 counter list was omitted");
        helper.assertTrue(saved.contains("unlockedTitles"), "V4 title list was omitted");

        PlayerMythProfile loaded = PlayerMythProfile.load(saved);
        assertLegacyFields(helper, loaded, "V4 round trip");
        helper.assertValueEqual(loaded.customGameplayCounters(), original.customGameplayCounters(),
                "V4 counters changed during round trip");
        try {
            loaded.customGameplayCounters().put(aggregate, 99L);
            helper.fail("Counter map was mutable");
            return;
        } catch (UnsupportedOperationException expected) {
            // Immutable view required.
        }

        expectRejected(helper, withVersion(saved, 5), "future profile version");
        expectRejected(helper, withCounter(saved, "invalid", null, 1L, false), "invalid metric ID");
        expectRejected(helper, withCounter(saved, "exampleaddon:valid", "invalid", 1L, false),
                "invalid subject ID");
        expectRejected(helper, withCounter(saved, "exampleaddon:negative", null, -1L, false),
                "negative counter");
        expectRejected(helper, withCounter(saved, aggregate.metricType().toString(), null, 2L, false),
                "duplicate counter key");
        helper.assertValueEqual(PlayerMythProfile.load(profileWithCounterCount(256))
                .customGameplayCounters().size(), 256, "256 serialized counters");
        expectRejected(helper, profileWithCounterCount(257), "257 serialized counters");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void counterMutationsAreAtomicAndBounded(GameTestHelper helper) {
        PlayerMythDataService service = PlayerMythDataService.get(helper.getLevel().getServer());
        UUID playerId = UUID.randomUUID();
        GameplayMetricKey first = key("first");
        GameplayMetricKey second = key("second");

        helper.assertValueEqual(service.incrementGameplayCounters(playerId, Map.of(first, 1L)),
                GameplayCounterMutationResult.UPDATED, "single increment");
        helper.assertValueEqual(service.incrementGameplayCounters(playerId, Map.of(first, 2L, second, 5L)),
                GameplayCounterMutationResult.UPDATED, "batch increment");
        PlayerMythProfile changed = service.find(playerId).orElseThrow();
        helper.assertValueEqual(changed.customGameplayCounter(first), 3L, "first counter value");
        helper.assertValueEqual(changed.customGameplayCounter(second), 5L, "second counter value");

        PlayerMythProfile beforeInvalid = changed;
        helper.assertValueEqual(service.incrementGameplayCounters(playerId, Map.of(first, 0L)),
                GameplayCounterMutationResult.REJECTED_NON_POSITIVE_DELTA, "zero delta");
        helper.assertValueEqual(service.incrementGameplayCounters(playerId, Map.of(first, -1L)),
                GameplayCounterMutationResult.REJECTED_NON_POSITIVE_DELTA, "negative delta");
        helper.assertValueEqual(service.incrementGameplayCounters(playerId, Map.of()),
                GameplayCounterMutationResult.REJECTED_EMPTY_BATCH, "empty batch");
        helper.assertTrue(service.find(playerId).orElseThrow() == beforeInvalid,
                "Rejected mutation replaced the stored profile");

        UUID overflowPlayer = UUID.randomUUID();
        GameplayMetricKey maximum = key("maximum");
        GameplayMetricKey untouched = key("untouched");
        helper.assertValueEqual(service.incrementGameplayCounters(overflowPlayer, Map.of(maximum, Long.MAX_VALUE)),
                GameplayCounterMutationResult.UPDATED, "maximum value setup");
        PlayerMythProfile beforeOverflow = service.find(overflowPlayer).orElseThrow();
        helper.assertValueEqual(service.incrementGameplayCounters(overflowPlayer,
                        Map.of(maximum, 1L, untouched, 1L)),
                GameplayCounterMutationResult.REJECTED_OVERFLOW, "overflow batch");
        PlayerMythProfile afterOverflow = service.find(overflowPlayer).orElseThrow();
        helper.assertTrue(afterOverflow == beforeOverflow, "Overflow replaced the stored profile");
        helper.assertValueEqual(afterOverflow.customGameplayCounter(untouched), 0L,
                "Overflow batch partially mutated another key");

        UUID boundedPlayer = UUID.randomUUID();
        Map<GameplayMetricKey, Long> maximumKeys = new LinkedHashMap<>();
        for (int index = 0; index < PlayerMythProfile.MAX_CUSTOM_GAMEPLAY_COUNTERS; index++) {
            maximumKeys.put(key("bounded_" + index), 1L);
        }
        helper.assertValueEqual(service.incrementGameplayCounters(boundedPlayer, maximumKeys),
                GameplayCounterMutationResult.UPDATED, "256-key batch");
        PlayerMythProfile beforeLimit = service.find(boundedPlayer).orElseThrow();
        helper.assertValueEqual(beforeLimit.customGameplayCounters().size(), 256, "stored key count");
        helper.assertValueEqual(service.incrementGameplayCounters(boundedPlayer,
                        Map.of(key("bounded_0"), 1L, key("bounded_256"), 1L)),
                GameplayCounterMutationResult.REJECTED_KEY_LIMIT, "257th key");
        PlayerMythProfile afterLimit = service.find(boundedPlayer).orElseThrow();
        helper.assertTrue(afterLimit == beforeLimit, "Key-limit rejection replaced the stored profile");
        helper.assertValueEqual(afterLimit.customGameplayCounter(key("bounded_0")), 1L,
                "Key-limit batch partially incremented an existing key");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void countersSurviveReconnectAndRestart(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        PlayerMythDataService service = PlayerMythDataService.get(server);
        GameplayMetricKey key = key("restart_probe");
        var existing = service.find(RESTART_PLAYER_ID);
        if (existing.isPresent() && existing.orElseThrow().customGameplayCounter(key) > 0) {
            helper.assertValueEqual(existing.orElseThrow().dataVersion(), 4,
                    "Restarted counter profile was not V4");
            MythicTrpg.LOGGER.info("PHASE 4-A-2-1 counter persistence probe verified after server restart.");
        } else {
            helper.assertValueEqual(service.incrementGameplayCounters(RESTART_PLAYER_ID, Map.of(key, 1L)),
                    GameplayCounterMutationResult.UPDATED, "restart probe setup");
            server.saveEverything(false, true, false);
            MythicTrpg.LOGGER.info("PHASE 4-A-2-1 counter persistence probe initialized; run GameTestServer again.");
        }

        FakePlayer reconnected = new FakePlayer(helper.getLevel(), new GameProfile(RESTART_PLAYER_ID, "CounterReconnect"));
        helper.assertTrue(service.find(reconnected.getUUID()).orElseThrow().customGameplayCounter(key) > 0,
                "Reconnect lost the custom counter");
        helper.succeed();
    }

    private static void assertLegacyFields(GameTestHelper helper, PlayerMythProfile profile, String stage) {
        assertPreHistoryFields(helper, profile, stage);
        helper.assertTrue(profile.obtainedItems().contains(TEST_ITEM), stage + ": item history");
    }

    private static void assertPreHistoryFields(GameTestHelper helper, PlayerMythProfile profile, String stage) {
        helper.assertValueEqual(profile.dataVersion(), 4, stage + ": dataVersion");
        helper.assertValueEqual(profile.participationStatus(), ParticipationStatus.ARCHIVED,
                stage + ": participation");
        helper.assertValueEqual(profile.affinities().get(TEST_GOD), 17, stage + ": affinity");
        helper.assertTrue(profile.encounteredGods().contains(TEST_GOD), stage + ": encountered God");
        helper.assertTrue(profile.identifiedGods().contains(TEST_GOD), stage + ": identified God");
    }

    private static CompoundTag profileTag(int version, boolean includeObtainedItems) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("dataVersion", version);
        tag.putString("participationStatus", ParticipationStatus.ARCHIVED.name());
        CompoundTag affinity = new CompoundTag();
        affinity.putString("god", TEST_GOD.toString());
        affinity.putInt("value", 17);
        ListTag affinities = new ListTag();
        affinities.add(affinity);
        tag.put("affinities", affinities);
        tag.put("encounteredGods", idList(TEST_GOD));
        tag.put("identifiedGods", idList(TEST_GOD));
        if (includeObtainedItems) {
            tag.put("obtainedItems", idList(TEST_ITEM));
        }
        return tag;
    }

    private static ListTag idList(ResourceLocation id) {
        ListTag list = new ListTag();
        list.add(StringTag.valueOf(id.toString()));
        return list;
    }

    private static CompoundTag withVersion(CompoundTag source, int version) {
        CompoundTag changed = source.copy();
        changed.putInt("dataVersion", version);
        return changed;
    }

    private static CompoundTag profileWithCounterCount(int count) {
        CompoundTag profile = PlayerMythProfile.createActive().save();
        ListTag counters = new ListTag();
        for (int index = 0; index < count; index++) {
            CompoundTag counter = new CompoundTag();
            counter.putString("metric_type", "exampleaddon:serialized_" + index);
            counter.putLong("value", 1L);
            counters.add(counter);
        }
        profile.put("customGameplayCounters", counters);
        return profile;
    }

    private static CompoundTag withCounter(CompoundTag source, String metricType, String subject,
            long value, boolean replaceCounters) {
        CompoundTag changed = source.copy();
        ListTag counters = replaceCounters ? new ListTag()
                : changed.getList("customGameplayCounters", net.minecraft.nbt.Tag.TAG_COMPOUND).copy();
        CompoundTag counter = new CompoundTag();
        counter.putString("metric_type", metricType);
        if (subject != null) {
            counter.putString("subject", subject);
        }
        counter.putLong("value", value);
        counters.add(counter);
        changed.put("customGameplayCounters", counters);
        return changed;
    }

    private static void expectRejected(GameTestHelper helper, CompoundTag tag, String stage) {
        try {
            PlayerMythProfile.load(tag);
            helper.fail(stage + " was accepted");
        } catch (IllegalArgumentException expected) {
            // Expected validation rejection.
        }
    }

    private static GameplayMetricKey key(String path) {
        return GameplayMetricKey.aggregate(ResourceLocation.fromNamespaceAndPath("exampleaddon", path));
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, path);
    }
}
