package com.sande.mythictrpg.weaponskill;

import io.netty.buffer.Unpooled;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomModelData;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("mythictrpg_weapon_skills")
@PrefixGameTestTemplate(false)
public final class WeaponSkillNetworkGameTests {
    private static final ResourceLocation SKILL = ResourceLocation.parse("test:weapon_skill");
    private WeaponSkillNetworkGameTests() { }

    @GameTest(templateNamespace = "mythictrpg_weapon_skills", template = "empty")
    public static void snapshotRoundTripPreservesVariantAndSkilllessState(GameTestHelper helper) {
        ItemStack source = new ItemStack(Items.IRON_SWORD);
        source.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(482));
        source.setDamageValue(17);
        var snapshot = new WeaponSkillSnapshot(174.5, 3, source, SKILL, "시험 스킬", 25, 37);
        source.setDamageValue(18);
        helper.assertTrue(snapshot.held().getDamageValue() == 17, "snapshot retained mutable source item");
        ItemStack exposed = snapshot.held(); exposed.setDamageValue(19);
        helper.assertTrue(snapshot.held().getDamageValue() == 17, "snapshot exposed mutable held item");
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        try {
            WeaponSkillSnapshot.CODEC.encode(buffer, snapshot);
            var decoded = WeaponSkillSnapshot.CODEC.decode(buffer);
            helper.assertTrue(decoded.matches(3, snapshot.held()) && decoded.mana() == 174.5
                    && decoded.skillName().equals("시험 스킬") && decoded.manaCost() == 25 && decoded.remainingTicks() == 37,
                    "bound snapshot round trip changed skill/item/accounting view");
            helper.assertTrue(!decoded.matches(2, snapshot.held()) && !decoded.matches(3, source)
                    && !decoded.matches(3, new ItemStack(Items.IRON_SWORD)), "stale slot/components claimed a current weapon");
            buffer.clear();
            WeaponSkillSnapshot.CODEC.encode(buffer, new WeaponSkillSnapshot(200, 0, ItemStack.EMPTY, null, "", 0, 0));
            var empty = WeaponSkillSnapshot.CODEC.decode(buffer);
            helper.assertTrue(empty.skillId() == null && empty.held().isEmpty() && !empty.matches(0, snapshot.held()),
                    "empty hand gained a skill through protocol");
            buffer.clear();
            WeaponSkillSnapshot.CODEC.encode(buffer, new WeaponSkillSnapshot(30, 3, snapshot.held(), null, "", 0, 0));
            var unbound = WeaponSkillSnapshot.CODEC.decode(buffer);
            helper.assertTrue(!unbound.matches(3, snapshot.held()), "unbound reward weapon intercepted vanilla use");
            buffer.clear();
            WeaponSkillSnapshot.CODEC.encode(buffer,new WeaponSkillSnapshot(325.125,400,3,snapshot.held(),SKILL,"large skill",300,20));
            var expanded=WeaponSkillSnapshot.CODEC.decode(buffer);
            helper.assertTrue(expanded.mana()==325.125&&expanded.maximumMana()==400&&expanded.manaCost()==300,
                    "dynamic maximum or costs above 200 did not survive the protocol");
            buffer.clear();
            WeaponSkillSnapshot.CODEC.encode(buffer,new WeaponSkillSnapshot(200,200,0,ItemStack.EMPTY,null,"",0,0,true));
            var locked=WeaponSkillSnapshot.CODEC.decode(buffer);
            helper.assertTrue(locked.actionLocked() && locked.skillId()==null && !expanded.actionLocked(),
                    "player action lock must be independent of the held weapon and survive the protocol");
            helper.succeed();
        } finally { buffer.release(); }
    }

    @GameTest(templateNamespace = "mythictrpg_weapon_skills", template = "empty")
    public static void requestProtocolRejectsNonHotbarSlotsAndInvalidSnapshotNumbers(GameTestHelper helper) {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        try {
            WeaponSkillRequest.CODEC.encode(buffer, new WeaponSkillRequest(8, SKILL));
            helper.assertTrue(WeaponSkillRequest.CODEC.decode(buffer).equals(new WeaponSkillRequest(8, SKILL)),
                    "request changed selection");
            for (int slot : new int[]{-1, 9, Integer.MAX_VALUE}) {
                buffer.clear(); buffer.writeVarInt(slot); buffer.writeResourceLocation(SKILL);
                boolean rejected = false;
                try { WeaponSkillRequest.CODEC.decode(buffer); } catch (IllegalArgumentException invalid) { rejected = true; }
                helper.assertTrue(rejected, "accepted non-hotbar request slot " + slot);
            }
            for (double mana : new double[]{-1, 200.01, Double.NaN, Double.POSITIVE_INFINITY}) {
                boolean rejected = false;
                try { new WeaponSkillSnapshot(mana, 0, ItemStack.EMPTY, null, "", 0, 0); }
                catch (IllegalArgumentException invalid) { rejected = true; }
                helper.assertTrue(rejected, "accepted invalid mana display " + mana);
            }
            boolean rejected = false;
            try { new WeaponSkillSnapshot(200, 0, ItemStack.EMPTY, SKILL, "skill", 0, 0); }
            catch (IllegalArgumentException invalid) { rejected = true; }
            helper.assertTrue(rejected, "accepted skill snapshot with no held item");
            helper.succeed();
        } finally { buffer.release(); }
    }
}
