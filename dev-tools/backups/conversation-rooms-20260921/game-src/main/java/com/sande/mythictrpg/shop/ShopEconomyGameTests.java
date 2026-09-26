package com.sande.mythictrpg.shop;

import com.mojang.authlib.GameProfile;
import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.economy.CurrencyService;
import com.sande.mythictrpg.economy.CurrencyState;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

@GameTestHolder(MythicTrpg.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ShopEconomyGameTests {
    private static final String TEMPLATE = "bastion/mobs/empty";

    private ShopEconomyGameTests() {
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void sellAndBuyAreServerValidatedAndUseExactItemComponents(GameTestHelper helper) {
        FakePlayer player = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "ShopTester"));
        player.getInventory().add(new ItemStack(Items.COAL, 3));
        ShopProductKey coal = new ShopProductKey(id("general_sell"), id("sell_coal"));
        ShopTransactionService.Result sold = ShopTransactionService.INSTANCE.transact(
                player, ShopType.SELL, coal, 2);
        helper.assertTrue(sold.succeeded(), "valid coal sale failed: " + sold.reason());
        helper.assertValueEqual(player.getInventory().countItem(Items.COAL), 1, "sale removed wrong item count");
        helper.assertValueEqual(CurrencyService.INSTANCE.balance(player.server, player.getUUID()), 2L,
                "sale did not credit personal currency");

        ShopProductKey potion = new ShopProductKey(id("witch_buy"), id("witch_healing_potion"));
        ShopService.INSTANCE.unlock(player.server, potion);
        CurrencyService.INSTANCE.set(player.server, player.getUUID(), 100L);
        ShopTransactionService.Result bought = ShopTransactionService.INSTANCE.transact(
                player, ShopType.BUY, potion, 1);
        helper.assertTrue(bought.succeeded(), "component-bearing potion purchase failed: " + bought.reason());
        helper.assertValueEqual(CurrencyService.INSTANCE.balance(player.server, player.getUUID()), 75L,
                "purchase did not debit personal currency");
        ItemStack expected = ShopCatalogManager.INSTANCE.product(potion).orElseThrow()
                .createStack(player.registryAccess());
        boolean found = false;
        for (int slot = 0; slot < net.minecraft.world.entity.player.Inventory.INVENTORY_SIZE; slot++) {
            if (ItemStack.isSameItemSameComponents(player.getInventory().getItem(slot), expected)) {
                found = true;
                break;
            }
        }
        helper.assertTrue(found, "purchased potion lost its configured components");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void balanceLimitsAndLockedCatalogAreEnforced(GameTestHelper helper) {
        FakePlayer player = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "BalanceTester"));
        helper.assertTrue(CurrencyService.INSTANCE.set(player.server, player.getUUID(), CurrencyState.MAX_BALANCE),
                "could not set maximum balance");
        helper.assertFalse(CurrencyService.INSTANCE.credit(player.server, player.getUUID(), 1L),
                "currency exceeded maximum balance");
        helper.assertFalse(CurrencyService.INSTANCE.debit(player.server, player.getUUID(),
                CurrencyState.MAX_BALANCE + 1L), "currency allowed overdraft");

        ShopProductKey unknown = new ShopProductKey(id("witch_buy"), id("missing"));
        helper.assertFalse(ShopService.INSTANCE.unlock(player.server, unknown),
                "unknown product was globally unlocked");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void globalUnlocksRoundTripAsWorldSharedState(GameTestHelper helper) {
        ShopProductKey key = new ShopProductKey(id("witch_buy"), id("witch_healing_potion"));
        GlobalShopState state = new GlobalShopState();
        helper.assertTrue(state.unlock(key), "global product unlock failed");
        CompoundTag saved = state.save(new CompoundTag(), helper.getLevel().registryAccess());
        GlobalShopState loaded = GlobalShopState.load(saved, helper.getLevel().registryAccess());
        helper.assertTrue(loaded.isUnlocked(key), "global product unlock was not persisted");
        helper.succeed();
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, path);
    }
}
