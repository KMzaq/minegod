package com.sande.mythictrpg.weaponskill;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sande.mythictrpg.power.CombatPowerPolicy;
import com.sande.mythictrpg.power.CombatPowerPolicyManager;
import com.sande.mythictrpg.power.CombatPowerRuntime;
import com.sande.mythictrpg.quest.reward.NpcRewardEntry;
import com.sande.mythictrpg.quest.reward.ResolvedQuestReward;
import com.sande.mythictrpg.quest.reward.RewardClaimService;
import com.sande.mythictrpg.quest.reward.RewardClaimState;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@GameTestHolder("mythictrpg_weapon_skills")
@PrefixGameTestTemplate(false)
public final class WeaponSkillGameplayGameTests {
    private static final ResourceLocation HEAL = id("heal"), SMALL_HEAL = id("small_heal"), HIT = id("hit"), BUFF = id("buff");

    @GameTest(templateNamespace = "mythictrpg_weapon_skills", template = "empty", batch = "weapon_skill_gameplay", timeoutTicks = 300)
    public static void onlyExplicitlyBoundOwnedHeldWeaponsExecute(GameTestHelper h) {
        ready(h, () -> {
            try (var f = new Fixture(h)) {
                var player = f.player;
                player.setHealth(10);
                player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
                h.assertValueEqual(f.cast(player, HEAL), WeaponSkillRuntime.Result.NOT_BOUND, "unearned matching weapon was trusted");
                f.grant(player, "iron_sword", "stone_sword", "golden_sword");
                player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.STONE_SWORD));
                h.assertValueEqual(f.cast(player, HEAL), WeaponSkillRuntime.Result.NOT_BOUND,
                        "combat-power skill points alone enabled right-click casting");
                f.other.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
                h.assertValueEqual(f.cast(f.other, HEAL), WeaponSkillRuntime.Result.NOT_BOUND, "another owner's reward history was used");
                var changed = new ItemStack(Items.IRON_SWORD);
                changed.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(7182));
                player.setItemSlot(EquipmentSlot.MAINHAND, changed);
                h.assertValueEqual(f.cast(player, HEAL), WeaponSkillRuntime.Result.NOT_BOUND, "unearned item variant authenticated");
                var worn = new ItemStack(Items.IRON_SWORD); worn.setDamageValue(12);
                player.setItemSlot(EquipmentSlot.MAINHAND, worn);
                h.assertValueEqual(f.cast(player, HEAL), WeaponSkillRuntime.Result.SUCCESS, "normal weapon wear disabled earned skill");
                h.assertTrue(player.getHealth() == 14 && f.mana(player) == 170, "successful heal or mana cost was not applied");
                h.assertTrue(f.state().account(player.getUUID()).orElseThrow().remainingTicks(HEAL.toString(), f.tick()) == 80,
                        "configured cooldown was not applied");
                h.assertValueEqual(f.cast(player, HEAL), WeaponSkillRuntime.Result.DUPLICATE, "same-tick packet replay applied twice");
                h.assertTrue(player.getHealth() == 14 && f.mana(player) == 170, "replay altered health or mana");
            }
        });
    }

    @GameTest(templateNamespace = "mythictrpg_weapon_skills", template = "empty", batch = "weapon_skill_gameplay", timeoutTicks = 300)
    public static void staleSlotsOffhandAndSneakingCannotTrigger(GameTestHelper h) {
        ready(h, () -> {
            try (var f = new Fixture(h)) {
                var player = f.player; f.grant(player, "iron_sword"); player.setHealth(10);
                player.getInventory().selected = 0;
                player.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
                player.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.IRON_SWORD));
                h.assertValueEqual(f.cast(player, HEAL), WeaponSkillRuntime.Result.NOT_BOUND, "offhand weapon activated a mainhand skill");
                player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
                h.assertValueEqual(WeaponSkillRuntime.cast(player, 1, HEAL), WeaponSkillRuntime.Result.STALE_REQUEST,
                        "stale selected slot was trusted");
                h.assertValueEqual(f.cast(player, SMALL_HEAL), WeaponSkillRuntime.Result.STALE_REQUEST,
                        "client-selected skill overrode the actual held binding");
                player.setShiftKeyDown(true);
                h.assertValueEqual(f.cast(player, HEAL), WeaponSkillRuntime.Result.INVALID_PLAYER, "sneaking did not preserve normal item interactions");
                player.setShiftKeyDown(false);
                h.assertTrue(f.mana(player) == 200 && player.getHealth() == 10, "rejected input had side effects");
                h.assertValueEqual(f.cast(player, HEAL), WeaponSkillRuntime.Result.SUCCESS, "valid mainhand request failed after rejected inputs");
            }
        });
    }

    @GameTest(templateNamespace = "mythictrpg_weapon_skills", template = "empty", batch = "weapon_skill_gameplay", timeoutTicks = 300)
    public static void individualCostsCooldownsAndFailuresUseActualState(GameTestHelper h) {
        ready(h, () -> {
            try (var f = new Fixture(h)) {
                var player = f.player; var other = f.other;
                f.grant(player, "iron_sword", "golden_sword"); f.grant(other, "golden_sword");
                player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
                player.setHealth(player.getMaxHealth());
                h.assertValueEqual(f.cast(player, HEAL), WeaponSkillRuntime.Result.NO_EFFECT, "full-health heal charged or reported success");
                h.assertTrue(f.mana(player) == 200, "unsuccessful heal consumed mana");
                player.setHealth(10);
                h.assertValueEqual(f.state().trySpend(player.getUUID(), HEAL.toString(), 30, 80, f.tick() - 1),
                        WeaponSkillState.Result.SUCCESS, "prior successful-cast fixture failed");
                h.assertValueEqual(f.cast(player, HEAL), WeaponSkillRuntime.Result.COOLDOWN, "active cooldown did not block runtime cast");
                h.assertTrue(f.mana(player) == 170 && player.getHealth() == 10, "cooldown failure changed state");
                player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.GOLDEN_SWORD));
                h.assertValueEqual(f.cast(player, SMALL_HEAL), WeaponSkillRuntime.Result.SUCCESS, "separate skill inherited another skill's cooldown");
                h.assertTrue(f.mana(player) == 163 && player.getHealth() == 12, "second skill did not use its own cost/effect");
                h.assertTrue(f.state().account(player.getUUID()).orElseThrow().remainingTicks(SMALL_HEAL.toString(), f.tick()) == 13,
                        "second skill did not use its own cooldown");
                other.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.GOLDEN_SWORD)); other.setHealth(10);
                f.state().trySpend(other.getUUID(), "test:unrelated", 200, 0, f.tick() - 1);
                h.assertValueEqual(f.cast(other, SMALL_HEAL), WeaponSkillRuntime.Result.NO_MANA, "insufficient mana allowed a real effect");
                h.assertTrue(f.mana(other) == 0 && other.getHealth() == 10, "no-mana failure changed health or mana");
            }
        });
    }

    @GameTest(templateNamespace = "mythictrpg_weapon_skills", template = "empty", batch = "weapon_skill_gameplay", timeoutTicks = 300)
    public static void validAttackMissesPayButWallsAndRaidProtectionStillPreventDamage(GameTestHelper h) {
        ready(h, () -> {
            try (var f = new Fixture(h)) {
                var casters=List.of(f.player,f.connect("SkillWall"),f.connect("SkillProtected"),f.connect("SkillHit"));
                var origin = h.absolutePos(new BlockPos(1, 3, 1));
                for(var caster:casters){f.grant(caster,"diamond_sword");caster.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.DIAMOND_SWORD));
                    caster.moveTo(origin.getX()+20,origin.getY(),origin.getZ());}
                f.other.moveTo(origin.getX() + 20, origin.getY(), origin.getZ());
                BlockPos wall = origin.offset(0, 1, 2);
                var originalWall = h.getLevel().getBlockState(wall);
                Zombie target = EntityType.ZOMBIE.create(h.getLevel());
                h.assertTrue(target != null, "zombie fixture creation failed");
                try {
                    casters.get(0).moveTo(origin.getX()+0.5,origin.getY(),origin.getZ()+0.5,0,0);
                    h.assertValueEqual(f.cast(casters.get(0), HIT), WeaponSkillRuntime.Result.SUCCESS, "valid empty-space attack was not committed");
                    h.assertTrue(f.mana(casters.get(0))==175,"empty-space attack did not pay its cost");
                    casters.get(0).moveTo(origin.getX()+20,origin.getY(),origin.getZ());
                    target.setNoAi(true); target.moveTo(origin.getX() + 0.5, origin.getY(), origin.getZ() + 4.5, 0, 0);
                    h.getLevel().addFreshEntity(target);
                    h.getLevel().setBlockAndUpdate(wall, Blocks.STONE.defaultBlockState());
                    casters.get(1).moveTo(origin.getX()+0.5,origin.getY(),origin.getZ()+0.5,0,0);
                    h.assertValueEqual(f.cast(casters.get(1), HIT), WeaponSkillRuntime.Result.SUCCESS, "wall-blocked attack was not committed");
                    h.assertTrue(target.getHealth()==target.getMaxHealth()&&f.mana(casters.get(1))==175,"wall failed to block damage or cast was free");
                    casters.get(1).moveTo(origin.getX()+20,origin.getY(),origin.getZ());
                    h.getLevel().setBlockAndUpdate(wall, Blocks.AIR.defaultBlockState());
                    target.getPersistentData().putUUID("mythictrpg_raid_attempt", UUID.randomUUID());
                    casters.get(2).moveTo(origin.getX()+0.5,origin.getY(),origin.getZ()+0.5,0,0);
                    h.assertValueEqual(f.cast(casters.get(2), HIT), WeaponSkillRuntime.Result.SUCCESS, "protected attack was not committed");
                    h.assertTrue(target.getHealth() == target.getMaxHealth() && f.mana(casters.get(2)) == 175,
                            "protected attack hurt the target or bypassed its own cost");
                    casters.get(2).moveTo(origin.getX()+20,origin.getY(),origin.getZ());
                    target.getPersistentData().remove("mythictrpg_raid_attempt");
                    casters.get(3).moveTo(origin.getX()+0.5,origin.getY(),origin.getZ()+0.5,0,0);
                    h.assertValueEqual(f.cast(casters.get(3), HIT), WeaponSkillRuntime.Result.SUCCESS, "unblocked aimed hit did not execute");
                    h.assertTrue(target.getHealth() < target.getMaxHealth() && f.mana(casters.get(3)) == 175,
                            "aimed hit did not damage and charge exactly once");
                } finally { target.discard(); h.getLevel().setBlockAndUpdate(wall, originalWall); }
            }
        });
    }

    @GameTest(templateNamespace = "mythictrpg_weapon_skills", template = "empty", batch = "weapon_skill_gameplay", timeoutTicks = 300)
    public static void temporarySkillEffectNeverCreatesPermanentBlessingOwnership(GameTestHelper h) {
        ready(h, () -> {
            try (var f = new Fixture(h)) {
                f.grant(f.player, "wooden_sword"); f.grant(f.other, "wooden_sword");
                f.player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.WOODEN_SWORD));
                f.other.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.WOODEN_SWORD));
                h.assertValueEqual(f.cast(f.player, BUFF), WeaponSkillRuntime.Result.SUCCESS, "self-effect skill did not execute");
                var active = f.player.getEffect(MobEffects.MOVEMENT_SPEED);
                h.assertTrue(active != null && active.getAmplifier() == 1 && active.getDuration() == 100,
                        "skill effect level or duration was not applied");
                h.assertTrue(f.mana(f.player) == 188, "self-effect skill used wrong mana cost");
                f.other.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 200, 1));
                h.assertValueEqual(f.cast(f.other, BUFF), WeaponSkillRuntime.Result.NO_EFFECT,
                        "already-covered effect was charged or reported as a new application");
                h.assertTrue(f.mana(f.other) == 200, "unchanged existing effect consumed mana");
                var claims = RewardClaimState.get(f.player.server);
                for (var player : List.of(f.player, f.other)) {
                    h.assertTrue(claims.permanentBlessingsFor(player.getUUID()).isEmpty()
                            && claims.acquiredBlessingsFor(player.getUUID()).isEmpty()
                            && !claims.hasNonRaidBlessing(player.getUUID()),
                            "temporary weapon skill created permanent ownership or blocked the human route");
                }
                f.player.removeAllEffects();
                h.assertTrue(!claims.hasNonRaidBlessing(f.player.getUUID()), "removing temporary effects changed ownership");
            }
        });
    }

    @GameTest(templateNamespace = "mythictrpg_weapon_skills", template = "empty", batch = "weapon_skill_tick_and_relogin", timeoutTicks = 300)
    public static void actualPlayerTicksRecoverImmediatelyAndRelogPreservesAccount(GameTestHelper h) {
        h.startSequence().thenWaitUntil(() -> h.assertTrue(captureReady(h.getLevel().getServer())
                && h.getLevel().getServer().overworld().getGameTime() > 1, "acquisition capture startup"))
                .thenExecute(() -> {
                    var f = new Fixture(h);
                    boolean[] closed = {false};
                    try {
                        f.player.setNoGravity(true); f.player.setInvulnerable(true);
                        var safe = h.absolutePos(new BlockPos(1, 3, 1));
                        f.player.moveTo(safe.getX() + 0.5, safe.getY(), safe.getZ() + 0.5, 0, 0);
                        f.state().trySpend(f.player.getUUID(), HEAL.toString(), 40, 100, f.tick());
                        // Embedded test connections have no ServerGamePacketListenerImpl tick pump. Drive its real
                        // player tick entry point, which posts NeoForge events; never call the mana handler directly.
                        f.player.doTick();
                        h.assertTrue(f.mana(f.player) == 160.1, "first PlayerTick recovery: actual=" + f.mana(f.player));
                        f.player.doTick();
                        h.assertTrue(f.mana(f.player) == 160.1, "duplicate PlayerTick recovery: actual=" + f.mana(f.player));
                        for (int index = 1; index <= 19; index++) {
                            final int tickIndex = index;
                            h.runAfterDelay(index, () -> {
                                if (closed[0]) return;
                                try {
                                    f.player.doTick();
                                    double expected = 160 + (tickIndex + 1) / 10.0;
                                    h.assertTrue(f.mana(f.player) == expected,
                                            "actual PlayerTick " + (tickIndex + 1) + " recovery: expected=" + expected + ", actual=" + f.mana(f.player));
                                    if (tickIndex != 19) return;
                                    long remaining = f.state().account(f.player.getUUID()).orElseThrow().remainingTicks(HEAL.toString(), f.tick());
                                    var original = f.player; var uuid = original.getUUID();
                                    f.disconnect(original);
                                    h.assertValueEqual(f.cast(original, HEAL), WeaponSkillRuntime.Result.INVALID_PLAYER,
                                            "a disconnected player object remained authorized to cast");
                                    var rejoined = f.connect("SkillReturn", uuid);
                                    h.assertTrue(rejoined != original && f.mana(rejoined) == 162,
                                            "actual reconnect changed mana: actual=" + f.mana(rejoined));
                                    h.assertTrue(f.state().account(uuid).orElseThrow().remainingTicks(HEAL.toString(), f.tick()) == remaining,
                                            "actual reconnect reset the saved cooldown");
                                    h.assertValueEqual(f.cast(original, HEAL), WeaponSkillRuntime.Result.INVALID_PLAYER,
                                            "replaced player object impersonated the rejoined player");
                                    closed[0] = true; f.close(); h.succeed();
                                } catch (RuntimeException | Error failure) {
                                    if (!closed[0]) { closed[0] = true; f.close(); }
                                    throw failure;
                                }
                            });
                        }
                    } catch (RuntimeException | Error failure) {
                        if (!closed[0]) { closed[0] = true; f.close(); }
                        throw failure;
                    }
                });
    }

    private static void ready(GameTestHelper h, Runnable action) {
        h.startSequence().thenWaitUntil(() -> h.assertTrue(captureReady(h.getLevel().getServer())
                && h.getLevel().getServer().overworld().getGameTime() > 1, "acquisition capture startup"))
                .thenExecute(action).thenSucceed();
    }
    private static boolean captureReady(MinecraftServer server) {
        try {
            var method = CombatPowerRuntime.class.getDeclaredMethod("captureReady", MinecraftServer.class);
            method.setAccessible(true); return (boolean) method.invoke(null, server);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }
    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath("test", "weapon_" + path); }

    private static final class Fixture implements AutoCloseable {
        private final GameTestHelper h;
        private final CombatPowerPolicyManager.Loaded previousPolicy = CombatPowerPolicyManager.INSTANCE.current();
        private final Map<ResourceLocation, WeaponSkillDefinition> previousSkills;
        private final List<EmbeddedChannel> channels = new ArrayList<>();
        private final List<ServerPlayer> players = new ArrayList<>();
        final ServerPlayer player, other;
        @SuppressWarnings("unchecked") Fixture(GameTestHelper h) {
            this.h = h;
            try {
                Field field = WeaponSkillDefinitions.class.getDeclaredField("snapshot"); field.setAccessible(true);
                previousSkills = (Map<ResourceLocation, WeaponSkillDefinition>) field.get(WeaponSkillDefinitions.INSTANCE);
            } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
            try {
                var skills = Map.of(HEAL, new WeaponSkillDefinition(HEAL, "fixture heal", 30, 80, new WeaponSkillDefinition.SelfHeal(4)),
                        SMALL_HEAL, new WeaponSkillDefinition(SMALL_HEAL, "fixture small heal", 7, 13, new WeaponSkillDefinition.SelfHeal(2)),
                        HIT, new WeaponSkillDefinition(HIT, "fixture hit", 25, 40, new WeaponSkillDefinition.AimedDamage(6, 8)),
                        BUFF, new WeaponSkillDefinition(BUFF, "fixture buff", 12, 20,
                                new WeaponSkillDefinition.SelfEffect(ResourceLocation.parse("minecraft:speed"), 2, 100)));
                WeaponSkillDefinitions.INSTANCE.apply(skills, null, null);
                JsonObject json;
                try (var input = WeaponSkillGameplayGameTests.class.getResourceAsStream("/data/mythictrpg/mythictrpg/combat_power/default.json")) {
                    if (input == null) throw new IllegalStateException("missing v2 policy fixture");
                    json = JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                }
                json.getAsJsonArray("items").add(rule("iron_sword", HEAL, true));
                json.getAsJsonArray("items").add(rule("stone_sword", HEAL, false));
                json.getAsJsonArray("items").add(rule("golden_sword", SMALL_HEAL, true));
                json.getAsJsonArray("items").add(rule("diamond_sword", HIT, true));
                json.getAsJsonArray("items").add(rule("wooden_sword", BUFF, true));
                swapPolicy(new CombatPowerPolicyManager.Loaded(CombatPowerPolicy.parse("test:weapon_skills", json), "READY"));
                player = connect("SkillOwner"); other = connect("SkillOther");
            } catch (Exception failure) { close(); throw new IllegalStateException("weapon skill fixture", failure); }
        }
        private JsonObject rule(String item, ResourceLocation skill, boolean bound) {
            var rule = JsonParser.parseString("""
                {"itemId":"minecraft:iron_sword","components":{},"classification":"BOUND_REWARD_GEAR","slot":"weapon",
                 "intrinsicPower":12,"attackDamage":6,"sharpnessLevel":0,"protectionLevel":0,"skills":{}}
                """).getAsJsonObject();
            rule.addProperty("itemId", "minecraft:" + item); rule.getAsJsonObject("skills").addProperty(skill.toString(), 5);
            if (bound) rule.addProperty("rightClickSkill", skill.toString());
            return rule;
        }
        private ServerPlayer connect(String name) {
            return connect(name, UUID.randomUUID());
        }
        private ServerPlayer connect(String name, UUID uuid) {
            var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(new com.mojang.authlib.GameProfile(uuid, name), false);
            var player = new ServerPlayer(h.getLevel().getServer(), h.getLevel(), cookie.gameProfile(), cookie.clientInformation());
            var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
            channels.add(new EmbeddedChannel(connection));
            net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(connection);
            player.server.getPlayerList().placeNewPlayer(connection, player, cookie); players.add(player); return player;
        }
        private void disconnect(ServerPlayer player) {
            player.server.getPlayerList().remove(player); players.remove(player);
        }
        void grant(ServerPlayer player, String... itemNames) {
            var rewards = java.util.Arrays.stream(itemNames).map(item -> new NpcRewardEntry(ResourceLocation.parse("minecraft:" + item), 1))
                    .map(entry -> (com.sande.mythictrpg.quest.reward.RewardEntry) entry).toList();
            var result = RewardClaimService.INSTANCE.issue(player, ResourceLocation.parse("mythictrpg:fortuna"),
                    id("receipt_" + UUID.randomUUID()), new ResolvedQuestReward("fixture", rewards, List.of()));
            h.assertTrue(result.succeeded(), "actual item reward delivery failed: " + result.reason());
        }
        WeaponSkillRuntime.Result cast(ServerPlayer player, ResourceLocation skill) {
            return WeaponSkillRuntime.cast(player, player.getInventory().selected, skill);
        }
        WeaponSkillState state() { return WeaponSkillState.get(h.getLevel().getServer()); }
        double mana(ServerPlayer player) { return state().account(player.getUUID()).orElseThrow().mana(); }
        long tick() { return h.getLevel().getServer().overworld().getGameTime(); }
        @Override public void close() {
            swapPolicy(previousPolicy); WeaponSkillDefinitions.INSTANCE.apply(previousSkills, null, null);
            players.forEach(player -> player.server.getPlayerList().remove(player));
            channels.forEach(EmbeddedChannel::finishAndReleaseAll);
        }
        private static void swapPolicy(CombatPowerPolicyManager.Loaded policy) {
            try {
                Field field = CombatPowerPolicyManager.class.getDeclaredField("current"); field.setAccessible(true);
                field.set(CombatPowerPolicyManager.INSTANCE, policy);
            } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
        }
    }
}
