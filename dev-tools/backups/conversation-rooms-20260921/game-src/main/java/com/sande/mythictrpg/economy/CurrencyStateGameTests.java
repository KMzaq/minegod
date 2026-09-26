package com.sande.mythictrpg.economy;

import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

@GameTestHolder(MythicTrpg.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CurrencyStateGameTests {
    private static final String TEMPLATE = "bastion/mobs/empty";

    private CurrencyStateGameTests() {
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void personalBalancesRoundTripWithoutCrossPlayerLeakage(GameTestHelper helper) {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        CurrencyState state = new CurrencyState();
        helper.assertTrue(state.credit(first, 1234L), "first balance credit failed");
        helper.assertTrue(state.credit(second, 77L), "second balance credit failed");
        CompoundTag saved = state.save(new CompoundTag(), helper.getLevel().registryAccess());
        CurrencyState loaded = CurrencyState.load(saved, helper.getLevel().registryAccess());
        helper.assertValueEqual(loaded.balance(first), 1234L, "first balance was not persisted");
        helper.assertValueEqual(loaded.balance(second), 77L, "second balance was not persisted");
        helper.assertValueEqual(loaded.balance(UUID.randomUUID()), 0L, "new player did not start at zero");
        helper.succeed();
    }
}
