package com.sande.mythictrpg.godavatar;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Synthetic avatar definition only; installed God identity and world content are untouched. */
@GameTestHolder("mythictrpg_god_avatar")
@PrefixGameTestTemplate(false)
public final class GodAvatarGameTests {
    private static final ResourceLocation GOD = ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, "demeter");
    private static final ResourceLocation OTHER_GOD = ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, "fortuna");
    private GodAvatarGameTests() {}

    @GameTest(templateNamespace = "mythictrpg_god_avatar", template = "empty", timeoutTicks = 120)
    public static void onePhysicalAvatarEncounterAndRaidLease(GameTestHelper helper) {
        var level = helper.getLevel();
        helper.assertTrue(com.sande.mythictrpg.data.god.GodDefinitionManager.INSTANCE.find(GOD).isPresent(),
                "The installed God fixture is unavailable");
        BlockPos feet = helper.absolutePos(new BlockPos(2, 2, 2));
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            BlockPos floor = feet.offset(dx, -1, dz);
            level.setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(floor.above(), Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(floor.above(2), Blocks.AIR.defaultBlockState());
        }
        Field definitions;
        Field generation;
        Map<?, ?> previousDefinitions;
        long previousGeneration;
        try {
            definitions = GodAvatarDefinitionManager.class.getDeclaredField("definitions");
            generation = GodAvatarDefinitionManager.class.getDeclaredField("generation");
            definitions.setAccessible(true);
            generation.setAccessible(true);
            previousDefinitions = (Map<?, ?>) definitions.get(GodAvatarDefinitionManager.INSTANCE);
            previousGeneration = generation.getLong(GodAvatarDefinitionManager.INSTANCE);
            definitions.set(GodAvatarDefinitionManager.INSTANCE, Map.of(GOD, fixture()));
            generation.setLong(GodAvatarDefinitionManager.INSTANCE, previousGeneration + 1);
        } catch (ReflectiveOperationException exception) {
            helper.fail("Could not install synthetic avatar fixture: " + exception.getMessage());
            return;
        }
        UUID attempt = UUID.randomUUID();
        var service = GodAvatarService.INSTANCE;
        try {
            Vec3 location = Vec3.atBottomCenterOf(feet);
            var first = service.spawn(level, GOD, location).orElseThrow();
            helper.assertValueEqual(first.godId().orElseThrow(), GOD, "canonical God ID");
            helper.assertTrue(first.hasAuthoritativeBinding(), "persistent registry binding");
            helper.assertTrue(service.spawn(level, GOD, location).isEmpty(), "duplicate avatar spawned");
            CompoundTag saved = first.saveWithoutId(new CompoundTag());
            helper.assertValueEqual(saved.getString("GodId"), GOD.toString(), "entity God ID persistence");
            helper.assertTrue(!service.acquireRaidLease(level.getServer(), GOD, attempt),
                    "raid acquired an already manifested God");
            helper.assertTrue(service.despawn(first), "OP despawn failed");
            helper.assertTrue(GodAvatarRegistryState.get(level.getServer()).find(GOD).isEmpty(),
                    "despawn left a duplicate-prevention reservation");

            helper.assertTrue(service.acquireRaidLease(level.getServer(), GOD, attempt), "raid lease acquire");
            helper.assertTrue(service.spawn(level, GOD, location).isEmpty(), "normal spawn bypassed raid lease");
            var boss = service.spawnForRaid(level, GOD, location, attempt).orElseThrow();
            helper.assertTrue(service.spawnForRaid(level, GOD, location, attempt).isEmpty(),
                    "same raid spawned a second physical God");
            var player = helper.makeMockServerPlayerInLevel();
            player.setPos(location.add(1, 0, 0));
            helper.assertTrue(!service.targetForRaid(boss, player, UUID.randomUUID()),
                    "wrong raid owner controlled avatar target");
            helper.assertTrue(service.targetForRaid(boss, player, attempt), "lease owner could not direct attack");
            service.clearRaidTarget(boss);
            helper.assertTrue(service.despawn(boss), "raid avatar despawn failed");
            helper.assertTrue(service.spawn(level, GOD, location).isEmpty(), "raid lease vanished before release");
            service.releaseRaidLease(level.getServer(), GOD, attempt);

            player.setPos(location);
            service.onEncounterCommitted(level.getServer(), player.getUUID(), Set.of(GOD));
            var encounter = service.findLoaded(level.getServer(), GOD).orElseThrow();
            helper.assertTrue(encounter.getUUID() != null, "committed encounter did not manifest an avatar");
            helper.assertTrue(service.despawn(encounter), "encounter avatar cleanup failed");
        } catch (RuntimeException exception) {
            helper.fail("God avatar lifecycle failed: " + exception.getMessage());
            return;
        } finally {
            service.findLoaded(level.getServer(), GOD).ifPresent(service::despawn);
            service.releaseRaidLease(level.getServer(), GOD, attempt);
            try {
                definitions.set(GodAvatarDefinitionManager.INSTANCE, previousDefinitions);
                generation.setLong(GodAvatarDefinitionManager.INSTANCE, previousGeneration);
            } catch (ReflectiveOperationException exception) {
                helper.fail("Could not restore synthetic avatar fixture: " + exception.getMessage());
            }
        }
        helper.succeed();
    }

    private static GodAvatarDefinition fixture() {
        return new GodAvatarDefinition(GOD, new GodAvatarDefinition.Appearance(0, 1),
                new GodAvatarDefinition.Stats(20, 0.25, 2, 0, 24),
                new GodAvatarDefinition.Movement(true, false, true, 1, 16, 16),
                new GodAvatarDefinition.Combat(true, true, false, true),
                new GodAvatarDefinition.Placement(true, 2), 4);
    }

    @GameTest(templateNamespace = "mythictrpg_god_avatar", template = "empty")
    public static void playerSkinModelParsingIsStrictAndBackwardsCompatible(GameTestHelper helper) {
        var legacy = GodAvatarDefinitionManager.decode(GOD, json());
        helper.assertValueEqual(legacy.appearance().model(), GodAvatarDefinition.SkinModel.CLASSIC,
                "old JSON without model must remain classic");
        helper.assertValueEqual(new GodAvatarDefinition.Appearance(0, 1).model(), GodAvatarDefinition.SkinModel.CLASSIC,
                "old Java constructor changed its model");
        for (var model : GodAvatarDefinition.SkinModel.values()) {
            var input = json(); input.getAsJsonObject("appearance").addProperty("model", model.name().toLowerCase(java.util.Locale.ROOT));
            input.getAsJsonObject("appearance").addProperty("textureVariant", 255);
            var parsed = GodAvatarDefinitionManager.decode(GOD, input);
            helper.assertValueEqual(parsed.appearance().model(), model, "valid model rejected");
            helper.assertValueEqual(parsed.appearance().textureVariant(), 255, "texture range changed with model");
            helper.assertValueEqual(parsed.godId(), GOD, "appearance changed canonical God ID");
        }
        for (String invalid : List.of("null", "1", "true", "[]", "{}", "\"unknown\"", "\"SLIM\"", "\"\"")) {
            var input = json(); input.getAsJsonObject("appearance").add("model", JsonParser.parseString(invalid));
            boolean rejected = false;
            try { GodAvatarDefinitionManager.decode(GOD, input); }
            catch (IllegalArgumentException expected) { rejected = true; }
            helper.assertTrue(rejected, "invalid model admitted: " + invalid);
        }
        for (int invalid : List.of(-1, 256)) {
            var input = json(); input.getAsJsonObject("appearance").addProperty("textureVariant", invalid);
            boolean rejected = false;
            try { GodAvatarDefinitionManager.decode(GOD, input); }
            catch (IllegalArgumentException expected) { rejected = true; }
            helper.assertTrue(rejected, "invalid texture variant admitted");
        }
        boolean rejectedNull = false;
        try { new GodAvatarDefinition.Appearance(0, 1, null); }
        catch (NullPointerException | IllegalArgumentException expected) { rejectedNull = true; }
        helper.assertTrue(rejectedNull, "Java appearance accepted an undefined model");
        helper.succeed();
    }

    @GameTest(templateNamespace = "mythictrpg_god_avatar", template = "empty")
    @SuppressWarnings("unchecked")
    public static void playerSkinMetadataReloadAndNbtFollowEachGodDefinition(GameTestHelper helper) throws ReflectiveOperationException {
        var manager = GodAvatarDefinitionManager.INSTANCE;
        var definitions = GodAvatarDefinitionManager.class.getDeclaredField("definitions"); definitions.setAccessible(true);
        var generation = GodAvatarDefinitionManager.class.getDeclaredField("generation"); generation.setAccessible(true);
        var previous = (Map<ResourceLocation, GodAvatarDefinition>) definitions.get(manager);
        long previousGeneration = manager.generation();
        helper.assertTrue(com.sande.mythictrpg.data.god.GodDefinitionManager.INSTANCE.find(GOD).isPresent()
                        && com.sande.mythictrpg.data.god.GodDefinitionManager.INSTANCE.find(OTHER_GOD).isPresent(),
                "installed identity fixtures missing");
        // These entities never enter the level or the authoritative single-God registry.
        var slim = avatar(helper); var classic = avatar(helper); var replica = avatar(helper); var restored = avatar(helper);
        try {
            manager.apply(Map.of(GOD, appearance(GOD, 11, GodAvatarDefinition.SkinModel.SLIM),
                    OTHER_GOD, appearance(OTHER_GOD, 255, GodAvatarDefinition.SkinModel.CLASSIC)), null, null);
            slim.bind(GOD); classic.bind(OTHER_GOD);
            helper.assertTrue(slim.slimModel(), "SLIM definition did not reach entity metadata");
            helper.assertFalse(classic.slimModel(), "CLASSIC definition leaked another God's model");
            helper.assertValueEqual(slim.textureVariant(), 11, "slim variant lost");
            helper.assertValueEqual(classic.textureVariant(), 255, "classic variant lost");
            helper.assertFalse(slim.hasAuthoritativeBinding(), "appearance alone acquired game authority");
            var metadata = slim.getEntityData().getNonDefaultValues();
            helper.assertTrue(metadata != null, "appearance metadata is absent");
            replica.getEntityData().assignValues(metadata);
            helper.assertTrue(replica.slimModel() && replica.textureVariant() == 11, "synced model/variant did not round trip");
            helper.assertTrue(replica.godId().isEmpty(), "visual metadata leaked server God identity");
            helper.assertTrue(slim.getCustomName() == null, "skin created a globally visible identity name");
            CompoundTag saved = slim.saveWithoutId(new CompoundTag());
            helper.assertValueEqual(saved.getString("GodId"), GOD.toString(), "NBT lost server canonical identity");
            restored.load(saved);
            refresh(restored);
            helper.assertTrue(restored.slimModel() && restored.textureVariant() == 11, "NBT load did not reapply current skin");
            helper.assertFalse(restored.hasAuthoritativeBinding(), "loading visual fixture acquired authority");

            long beforeReload = manager.generation();
            manager.apply(Map.of(GOD, appearance(GOD, 7, GodAvatarDefinition.SkinModel.CLASSIC),
                    OTHER_GOD, appearance(OTHER_GOD, 1, GodAvatarDefinition.SkinModel.SLIM)), null, null);
            helper.assertTrue(manager.generation() > beforeReload, "definition reload did not change generation");
            refresh(slim); refresh(classic); refresh(restored);
            helper.assertFalse(slim.slimModel() || restored.slimModel(), "reload retained previous slim model");
            helper.assertTrue(classic.slimModel(), "second God's reload was not independent");
            helper.assertValueEqual(slim.textureVariant(), 7, "reload retained old variant");
            helper.assertValueEqual(classic.textureVariant(), 1, "second God's reload retained old variant");
            restored.load(saved); refresh(restored);
            helper.assertFalse(restored.slimModel(), "old NBT overrode latest authored model");
            helper.assertValueEqual(restored.textureVariant(), 7, "old NBT overrode latest authored variant");
            helper.assertValueEqual(restored.godId().orElseThrow(), GOD, "appearance reload rebound identity");

            manager.apply(Map.of(), null, null); refresh(slim); refresh(classic);
            helper.assertFalse(slim.slimModel() || classic.slimModel(), "removed definition retained slim appearance");
            helper.assertTrue(slim.textureVariant() == 0 && classic.textureVariant() == 0, "removed definition did not reset variant");
        } finally {
            definitions.set(manager, previous); generation.setLong(manager, previousGeneration);
        }
        helper.succeed();
    }

    private static GodAvatarEntity avatar(GameTestHelper helper) {
        var value = GodAvatarEntities.GOD_AVATAR.get().create(helper.getLevel());
        if (value == null) throw new IllegalStateException("avatar type could not create fixture");
        value.setNoAi(true); value.setPos(Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(2, 2, 2))));
        return value;
    }

    private static void refresh(GodAvatarEntity avatar) {
        // ServerLevel, not Entity.tick(), increments tickCount before invoking the entity tick.
        // Exercise that production boundary rather than calling the private definition refresher.
        avatar.tickCount = 19;
        ((net.minecraft.server.level.ServerLevel) avatar.level()).tickNonPassenger(avatar);
    }

    private static GodAvatarDefinition appearance(ResourceLocation god, int variant, GodAvatarDefinition.SkinModel model) {
        var base = fixture();
        return new GodAvatarDefinition(god, new GodAvatarDefinition.Appearance(variant, 1, model), base.stats(),
                base.movement(), base.combat(), base.placement(), base.interactionRange());
    }

    private static JsonObject json() {
        return JsonParser.parseString("""
                {"formatVersion":1,"appearance":{"textureVariant":0,"scale":1},
                 "stats":{"maxHealth":20,"movementSpeed":0.25,"attackDamage":2,"armor":0,"followRange":24},
                 "movement":{"enabled":true,"wander":false,"visit":true,"navigationSpeed":1,"maxCommandDistance":16,"maxVisitDistance":16},
                 "combat":{"enabled":true,"damageable":true,"retaliate":false,"raidControl":true},
                 "placement":{"onEncounter":true,"spawnRadius":2},"interactionRange":4}
                """).getAsJsonObject();
    }
}
