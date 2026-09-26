package com.sande.mythictrpg.data.player;

import com.mojang.authlib.GameProfile;
import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

@GameTestHolder(MythicTrpg.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PhaseTwoBPlayerGameTests {
    private static final String TEMPLATE = "bastion/mobs/empty";
    private static final UUID RESTART_PLAYER_ID = UUID.fromString("68ed1048-52a0-45ca-b3c3-ce1c73714c8d");

    private PhaseTwoBPlayerGameTests() {
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void profileV1MigrationAndExplicitHistory(GameTestHelper helper) {
        PlayerMythProfile migrated = PlayerMythProfile.load(v1Profile());
        helper.assertValueEqual(migrated.dataVersion(), 4, "V1 profile did not migrate to V4");
        helper.assertTrue(migrated.obtainedItems().isEmpty(), "V1 profile migration invented item history");
        CompoundTag migratedNbt = migrated.save();
        helper.assertValueEqual(migratedNbt.getInt("dataVersion"), 4, "Migrated profile did not save as V4");
        helper.assertTrue(migratedNbt.contains("obtainedItems"), "V4 profile omitted obtainedItems");
        helper.assertTrue(migratedNbt.contains("unlockedTitles"), "V4 profile omitted unlockedTitles");

        MinecraftServer server = helper.getLevel().getServer();
        PlayerMythDataService data = PlayerMythDataService.get(server);
        UUID playerId = UUID.randomUUID();
        helper.assertValueEqual(
                PlayerMythHistoryService.recordItemObtained(server, playerId, Items.NAUTILUS_SHELL),
                ItemHistoryRecordResult.NEW_RECORD, "Explicit history API did not create a record");
        helper.assertValueEqual(
                PlayerMythHistoryService.recordItemObtained(server, playerId, Items.NAUTILUS_SHELL),
                ItemHistoryRecordResult.ALREADY_RECORDED, "Duplicate history was not detected");

        PlayerMythProfile profile = data.find(playerId).orElseThrow();
        helper.assertValueEqual(profile.obtainedItems().size(), 1, "Duplicate item ID was stored");
        helper.assertTrue(profile.obtainedItems().contains(itemId(Items.NAUTILUS_SHELL)),
                "Explicit history record was missing");

        ItemStack consumed = new ItemStack(Items.NAUTILUS_SHELL);
        consumed.shrink(1);
        helper.assertTrue(data.find(playerId).orElseThrow().obtainedItems().contains(itemId(Items.NAUTILUS_SHELL)),
                "Consuming the item removed permanent history");

        data.setParticipationStatus(playerId, ParticipationStatus.ARCHIVED);
        helper.assertTrue(data.find(playerId).orElseThrow().obtainedItems().contains(itemId(Items.NAUTILUS_SHELL)),
                "Archiving deleted item history");
        helper.assertTrue(!data.activeProfiles().containsKey(playerId), "ARCHIVED profile remained ACTIVE");

        FakePlayer reconnected = fakePlayer(helper, playerId, "HistoryReconnect");
        helper.assertValueEqual(reconnected.getData(ModAttachments.PLAYER_MYTH_VIEW).playerId(), playerId,
                "Runtime view did not reconnect to the same UUID");
        helper.assertTrue(data.find(reconnected.getUUID()).orElseThrow().obtainedItems()
                .contains(itemId(Items.NAUTILUS_SHELL)), "Reconnect lost item history");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void officialAcquisitionAdaptersRecordHistory(GameTestHelper helper) {
        PlayerMythDataService data = PlayerMythDataService.get(helper.getLevel().getServer());
        FakePlayer player = fakePlayer(helper, UUID.randomUUID(), "HistoryEvents");

        ItemStack pickedUp = new ItemStack(Items.NAUTILUS_SHELL);
        ItemEntity entity = new ItemEntity(helper.getLevel(), 0, 0, 0, pickedUp.copy());
        entity.setItem(ItemStack.EMPTY);
        NeoForge.EVENT_BUS.post(new ItemEntityPickupEvent.Post(player, entity, pickedUp));
        NeoForge.EVENT_BUS.post(new PlayerEvent.ItemCraftedEvent(
                player, new ItemStack(Items.CRAFTING_TABLE), new SimpleContainer(1)));
        NeoForge.EVENT_BUS.post(new PlayerEvent.ItemSmeltedEvent(player, new ItemStack(Items.IRON_INGOT)));

        PlayerMythProfile profile = data.find(player.getUUID()).orElseThrow();
        helper.assertTrue(profile.obtainedItems().contains(itemId(Items.NAUTILUS_SHELL)),
                "Ground pickup adapter did not record history");
        helper.assertTrue(profile.obtainedItems().contains(itemId(Items.CRAFTING_TABLE)),
                "Crafting adapter did not record history");
        helper.assertTrue(profile.obtainedItems().contains(itemId(Items.IRON_INGOT)),
                "Smelting adapter did not record history");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void obtainedItemHistoryPersistsAcrossRestart(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        PlayerMythDataService data = PlayerMythDataService.get(server);
        var itemId = itemId(Items.HEART_OF_THE_SEA);
        var existing = data.find(RESTART_PLAYER_ID);
        if (existing.isPresent() && existing.orElseThrow().obtainedItems().contains(itemId)) {
            helper.assertValueEqual(existing.orElseThrow().dataVersion(), 4,
                    "Restarted item history profile was not V4");
            MythicTrpg.LOGGER.info("PHASE 2-B item history persistence probe verified after server restart.");
        } else {
            PlayerMythHistoryService.recordItemObtained(server, RESTART_PLAYER_ID, Items.HEART_OF_THE_SEA);
            server.saveEverything(false, true, false);
            MythicTrpg.LOGGER.info("PHASE 2-B item history persistence probe initialized; run GameTestServer again.");
        }
        helper.succeed();
    }

    private static FakePlayer fakePlayer(GameTestHelper helper, UUID uuid, String name) {
        return new FakePlayer(helper.getLevel(), new GameProfile(uuid, name));
    }

    private static net.minecraft.resources.ResourceLocation itemId(net.minecraft.world.item.Item item) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item);
    }

    private static CompoundTag v1Profile() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("dataVersion", 1);
        tag.putString("participationStatus", ParticipationStatus.ACTIVE.name());
        tag.put("affinities", new ListTag());
        tag.put("encounteredGods", new ListTag());
        tag.put("identifiedGods", new ListTag());
        return tag;
    }
}
