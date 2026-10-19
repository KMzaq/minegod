package com.sande.mythictrpg.raid;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Installed command-tree checks using only a newly connected mock player's own inventory. */
@GameTestHolder("mythictrpg_raid_trial")
@PrefixGameTestTemplate(false)
public final class TrialRaidCommandGameTests {
    private static final String KIT = "mythraid trial kit";
    private static final int[] KIT_SLOTS = {0, 2, 4, 6, 8, 10, 12, 14, 16};

    private TrialRaidCommandGameTests() { }

    @GameTest(templateNamespace = "mythictrpg_raid", template = "empty", timeoutTicks = 100)
    public static void kitPermissionCapacityAndExistingInventory(GameTestHelper helper) {
        List<EmbeddedChannel> channels = new ArrayList<>();
        ServerPlayer player = connect(helper, channels);
        try {
            var dispatcher = player.server.getCommands().getDispatcher();
            var root = dispatcher.getRoot().getChild("mythraid");
            helper.assertTrue(root != null, "installed mythraid command root is missing");
            helper.assertTrue(root.getChild("trial") != null && root.getChild("create") != null
                    && root.getChild("ui") != null, "trial registration replaced existing create/ui command children");
            var kit = root.getChild("trial").getChild("kit");
            helper.assertTrue(kit != null, "installed trial kit command is missing");

            Inventory inventory = player.getInventory();
            inventory.clearContent();
            List<ItemStack> emptyInventory = snapshot(inventory);
            CommandSourceStack ordinary = player.createCommandSourceStack().withPermission(0);
            helper.assertTrue(!kit.canUse(ordinary), "kit command requirement permits permission level zero");
            boolean refused;
            try { refused = dispatcher.execute(KIT, ordinary) == 0; }
            catch (CommandSyntaxException denied) { refused = true; }
            helper.assertTrue(refused, "ordinary player's kit command was accepted");
            assertInventory(helper, inventory, emptyInventory, "permission rejection");
            helper.assertTrue(inventory.isEmpty(), "permission rejection delivered an item");

            // Named, differently sized stacks make replacement, merging and component loss observable.
            for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
                ItemStack existing = new ItemStack(Items.COBBLESTONE, 1 + slot % 17);
                existing.set(DataComponents.CUSTOM_NAME, Component.literal("Retained trial slot " + slot));
                inventory.setItem(slot, existing);
            }
            for (int index = 0; index < 8; index++) inventory.setItem(KIT_SLOTS[index], ItemStack.EMPTY);
            List<ItemStack> eightSpaces = snapshot(inventory);
            CommandSourceStack operator = player.createCommandSourceStack().withPermission(2);
            helper.assertTrue(kit.canUse(operator), "kit command requirement rejects the operator fixture");
            helper.assertValueEqual(dispatcher.execute(KIT, operator), 0, "eight free slots permitted a partial kit");
            assertInventory(helper, inventory, eightSpaces, "insufficient-space rejection");

            inventory.setItem(KIT_SLOTS[8], ItemStack.EMPTY);
            List<ItemStack> beforeGrant = snapshot(inventory);
            helper.assertValueEqual(dispatcher.execute(KIT, operator), 1, "nine free slots did not accept the full kit");
            List<ItemStack> expected = List.of(new ItemStack(Items.IRON_SWORD), new ItemStack(Items.IRON_HELMET),
                    new ItemStack(Items.IRON_CHESTPLATE), new ItemStack(Items.IRON_LEGGINGS),
                    new ItemStack(Items.IRON_BOOTS), new ItemStack(Items.SHIELD),
                    new ItemStack(Items.COOKED_BEEF, 32), new ItemStack(Items.BOW), new ItemStack(Items.ARROW, 32));
            for (int index = 0; index < KIT_SLOTS.length; index++) {
                assertStack(helper, inventory.getItem(KIT_SLOTS[index]), expected.get(index),
                        "kit entry " + index + " at main slot " + KIT_SLOTS[index]);
            }
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                if (!isKitSlot(slot)) assertStack(helper, inventory.getItem(slot), beforeGrant.get(slot),
                        "existing slot " + slot + " changed during kit grant");
            }
            int deliveredStacks = 0;
            for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
                if (beforeGrant.get(slot).isEmpty() && !inventory.getItem(slot).isEmpty()) deliveredStacks++;
            }
            helper.assertValueEqual(deliveredStacks, 9, "kit did not deliver exactly nine main-inventory stacks");
            for (int slot = Inventory.INVENTORY_SIZE; slot < inventory.getContainerSize(); slot++) {
                helper.assertTrue(inventory.getItem(slot).isEmpty(), "kit automatically equipped armor or offhand slot " + slot);
            }

            List<ItemStack> fullInventory = snapshot(inventory);
            helper.assertValueEqual(dispatcher.execute(KIT, operator), 0, "repeat kit command accepted a full main inventory");
            assertInventory(helper, inventory, fullInventory, "repeat insufficient-space rejection");
            helper.succeed();
        } catch (CommandSyntaxException | RuntimeException failure) {
            helper.fail("Installed trial kit command failed: " + failure.getMessage());
        } finally {
            player.server.getPlayerList().remove(player);
            channels.forEach(EmbeddedChannel::finishAndReleaseAll);
        }
    }

    private static boolean isKitSlot(int slot) {
        for (int candidate : KIT_SLOTS) if (candidate == slot) return true;
        return false;
    }

    private static List<ItemStack> snapshot(Inventory inventory) {
        List<ItemStack> result = new ArrayList<>(inventory.getContainerSize());
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) result.add(inventory.getItem(slot).copy());
        return List.copyOf(result);
    }

    private static void assertInventory(GameTestHelper helper, Inventory inventory,
            List<ItemStack> expected, String operation) {
        helper.assertValueEqual(inventory.getContainerSize(), expected.size(), operation + " changed inventory size");
        for (int slot = 0; slot < expected.size(); slot++) assertStack(helper, inventory.getItem(slot),
                expected.get(slot), operation + " changed slot " + slot);
    }

    private static void assertStack(GameTestHelper helper, ItemStack actual, ItemStack expected, String message) {
        helper.assertTrue(actual.isEmpty() && expected.isEmpty()
                || ItemStack.isSameItemSameComponents(actual, expected) && actual.getCount() == expected.getCount(), message);
    }

    private static ServerPlayer connect(GameTestHelper helper, List<EmbeddedChannel> channels) {
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(
                new GameProfile(UUID.randomUUID(), "TrialKitFixture"), false);
        var player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(),
                cookie.gameProfile(), cookie.clientInformation());
        var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        channels.add(new EmbeddedChannel(connection));
        net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(connection);
        player.server.getPlayerList().placeNewPlayer(connection, player, cookie);
        return player;
    }
}
