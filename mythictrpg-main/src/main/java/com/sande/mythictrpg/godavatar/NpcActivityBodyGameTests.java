package com.sande.mythictrpg.godavatar;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.godavatar.activity.NpcActivityBody;
import com.sande.mythictrpg.godavatar.activity.NpcSparring;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.lang.reflect.Field;
import java.util.Map;

@GameTestHolder("mythictrpg_npc_activity_body")
@PrefixGameTestTemplate(false)
public final class NpcActivityBodyGameTests {
    private static final ResourceLocation GOD = ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, "demeter");
    private NpcActivityBodyGameTests() {}

    @GameTest(templateNamespace = "mythictrpg_npc_activity_body", template = "empty", timeoutTicks = 120)
    public static void inventorySeatingAndConsensualPractice(GameTestHelper helper) throws Exception {
        var level = helper.getLevel();
        Field definitions = GodAvatarDefinitionManager.class.getDeclaredField("definitions"); definitions.setAccessible(true);
        Field generation = GodAvatarDefinitionManager.class.getDeclaredField("generation"); generation.setAccessible(true);
        Object previous = definitions.get(GodAvatarDefinitionManager.INSTANCE);
        long previousGeneration = generation.getLong(GodAvatarDefinitionManager.INSTANCE);
        var definition = new GodAvatarDefinition(GOD, new GodAvatarDefinition.Appearance(0, 1),
                new GodAvatarDefinition.Stats(20, .25, 2, 0, 24),
                new GodAvatarDefinition.Movement(true, false, true, 1, 16, 16),
                new GodAvatarDefinition.Combat(true, true, false, true), new GodAvatarDefinition.Placement(false, 0), 4);
        GodAvatarEntity avatar = null;
        var player = helper.makeMockServerPlayerInLevel();
        try {
            definitions.set(GodAvatarDefinitionManager.INSTANCE, Map.of(GOD, definition));
            generation.setLong(GodAvatarDefinitionManager.INSTANCE, previousGeneration + 1);
            BlockPos feet = helper.absolutePos(new BlockPos(2, 2, 2));
            for (int x = -2; x <= 3; x++) for (int z = -2; z <= 3; z++) {
                level.setBlockAndUpdate(feet.offset(x, -1, z), Blocks.STONE.defaultBlockState());
                for (int y = 0; y < 4; y++) level.setBlockAndUpdate(feet.offset(x, y, z), Blocks.AIR.defaultBlockState());
            }
            avatar = GodAvatarService.INSTANCE.spawn(level, GOD, Vec3.atBottomCenterOf(feet)).orElseThrow();
            avatar.activityInventory().setItem(0, new ItemStack(Items.DIAMOND, 3));
            avatar.activityInventory().setItem(26, new ItemStack(Items.APPLE, 2));
            avatar.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
            ItemStack privateProp = new ItemStack(Items.BOOK);
            privateProp.set(DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("not public"));
            privateProp.set(DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(new CompoundTag()));
            privateProp.set(DataComponents.CUSTOM_MODEL_DATA, new net.minecraft.world.item.component.CustomModelData(7));
            float standing = avatar.getBbHeight();
            helper.assertTrue(NpcActivityBody.begin(avatar, feet, "READ", privateProp), "read positioning rejected");
            helper.assertTrue(avatar.getBbHeight() < standing && avatar.activityProp().is(Items.BOOK), "seating or prop not synced");
            helper.assertTrue(!avatar.activityProp().has(DataComponents.CUSTOM_NAME) && !avatar.activityProp().has(DataComponents.CUSTOM_DATA)
                    && avatar.activityProp().get(DataComponents.CUSTOM_MODEL_DATA).value() == 7, "cosmetic metadata whitelist failed");
            helper.assertTrue(avatar.getMainHandItem().is(Items.IRON_SWORD), "cosmetic prop replaced real equipment");
            helper.assertTrue(NpcActivityBody.canContinue(avatar, feet), "valid read placement lost");
            CompoundTag saved = avatar.saveWithoutId(new CompoundTag());
            var restored = GodAvatarEntities.GOD_AVATAR.get().create(level);
            restored.load(saved);
            helper.assertTrue(restored.activityInventory().getItem(0).getCount() == 3
                    && restored.activityInventory().getItem(26).getCount() == 2, "inventory slots did not roundtrip");
            helper.assertTrue(restored.activityPose().equals("NONE") && restored.activityProp().isEmpty(), "cosmetic state survived save");
            NpcActivityBody.end(avatar);
            helper.assertTrue(avatar.getBbHeight() == standing, "stand-up dimensions did not restore");
            BlockPos seat = feet.east();
            level.setBlockAndUpdate(seat, Blocks.OAK_SLAB.defaultBlockState());
            helper.assertTrue(NpcActivityBody.begin(avatar, seat, "SIT", ItemStack.EMPTY), "nearby slab seat rejected");
            helper.assertTrue(Math.abs(avatar.getY() - (seat.getY() + .5)) < .01, "slab seat top was not used");
            level.setBlockAndUpdate(seat, Blocks.AIR.defaultBlockState());
            helper.assertTrue(!NpcActivityBody.canContinue(avatar, seat), "removed seat remained valid");
            NpcActivityBody.end(avatar);
            avatar.setPos(Vec3.atBottomCenterOf(feet));
            level.setBlockAndUpdate(seat, Blocks.OAK_STAIRS.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.StairBlock.FACING, net.minecraft.core.Direction.NORTH));
            helper.assertTrue(NpcActivityBody.begin(avatar, seat, "SIT", ItemStack.EMPTY), "lower stair chair seat rejected");
            helper.assertTrue(Math.abs(avatar.getY() - (seat.getY() + .5)) < .01
                    && avatar.getZ() > seat.getZ() + .75, "stair chair used the high back instead of front tread");
            helper.assertTrue(NpcActivityBody.canContinue(avatar, seat), "stair front seat invalid after placement");
            NpcActivityBody.end(avatar);
            level.setBlockAndUpdate(seat, Blocks.AIR.defaultBlockState());
            avatar.setPos(Vec3.atBottomCenterOf(feet));
            helper.assertTrue(!NpcActivityBody.begin(avatar, feet.offset(8, 0, 0), "SIT", ItemStack.EMPTY), "remote seating teleported");

            player.setPos(avatar.position().add(0, 0, 2));
            player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_SWORD));
            float health = avatar.getHealth(), playerHealth = player.getHealth();
            var spar = NpcSparring.INSTANCE;
            helper.assertTrue(!spar.accept(player).success(), "practice started without invite");
            helper.assertTrue(spar.invite(player, avatar, 2).success() && !spar.active(avatar), "invite itself started practice");
            helper.assertTrue(spar.decline(player).success() && !spar.accept(player).success(), "declined invitation replayed");
            helper.assertTrue(spar.invite(player, avatar, 2).success() && spar.accept(player).success(), "explicit acceptance failed");
            helper.assertTrue(!spar.accept(player).success(), "accept replay accepted");
            player.attack(avatar); // real Player.attack -> actual NeoForge early cancellation, not a test-only shortcut
            helper.assertTrue(avatar.getHealth() == health && player.getHealth() == playerHealth, "practice changed real health");
            helper.assertTrue(player.getMainHandItem().getDamageValue() == 0 && avatar.getMainHandItem().getDamageValue() == 0,
                    "practice damaged equipment");
            helper.assertValueEqual(spar.score(player).orElseThrow().npcRemaining(), 1, "actual melee was not scored");
            player.attack(avatar);
            helper.assertValueEqual(spar.score(player).orElseThrow().npcRemaining(), 1, "same-tick hit spam scored twice");
            helper.assertTrue(!avatar.hurt(level.damageSources().playerAttack(player), 1000) && spar.active(avatar), "paired direct damage escaped");
            avatar.hurt(level.damageSources().generic(), 2);
            helper.assertTrue(!spar.active(avatar) && avatar.getHealth() < health, "outside damage became invulnerability");
            helper.assertTrue(spar.invite(player, avatar).success() && spar.accept(player).success(), "could not restart practice");
            avatar.moveTo(feet.west());
            helper.assertTrue(!spar.active(avatar), "movement order did not interrupt practice");
            avatar.clearOrder();
            helper.assertTrue(spar.invite(player, avatar).success() && spar.accept(player).success(), "third practice failed");
            generation.setLong(GodAvatarDefinitionManager.INSTANCE, previousGeneration + 2);
            // ServerLevel increments tickCount before Entity.tick; this direct fixture call must set the due tick itself.
            avatar.tickCount = 20; avatar.tick();
            helper.assertTrue(!spar.active(avatar), "definition reload did not end practice");

            avatar.setActivityVisual("WORK", new ItemStack(Items.NETHERITE_AXE));
            var box = avatar.getBoundingBox().inflate(4);
            avatar.invulnerableTime = 0;
            avatar.hurt(level.damageSources().generic(), 10000);
            helper.assertTrue(!avatar.isAlive(), "real lethal path did not complete");
            avatar.dropEquipment(); // duplicate callback after actual LivingEntity death must not redrop inventory
            var drops = level.getEntitiesOfClass(ItemEntity.class, box);
            helper.assertValueEqual(drops.stream().filter(item -> item.getItem().is(Items.DIAMOND)).mapToInt(item -> item.getItem().getCount()).sum(), 3,
                    "real inventory dropped other than once");
            helper.assertValueEqual(drops.stream().filter(item -> item.getItem().is(Items.APPLE)).mapToInt(item -> item.getItem().getCount()).sum(), 2,
                    "last inventory slot drop missing");
            helper.assertTrue(drops.stream().noneMatch(item -> item.getItem().is(Items.NETHERITE_AXE)), "cosmetic prop dropped real loot");
            helper.assertTrue(avatar.activityInventory().isEmpty(), "death drop left retained inventory");
            CompoundTag drained = avatar.saveWithoutId(new CompoundTag()); restored.load(drained);
            helper.assertTrue(restored.activityInventory().isEmpty(), "saved death inventory resurrected items");
        } finally {
            NpcSparring.INSTANCE.leave(player);
            if (avatar != null) GodAvatarService.INSTANCE.despawn(avatar);
            definitions.set(GodAvatarDefinitionManager.INSTANCE, previous);
            generation.setLong(GodAvatarDefinitionManager.INSTANCE, previousGeneration);
        }
        helper.succeed();
    }
}
