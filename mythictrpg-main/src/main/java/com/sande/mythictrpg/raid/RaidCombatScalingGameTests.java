package com.sande.mythictrpg.raid;

import com.google.gson.JsonParser;
import com.sande.mythictrpg.content.ContentDefinitions;
import com.sande.mythictrpg.power.CombatPowerPolicy;
import com.sande.mythictrpg.power.CombatPowerPolicyManager;
import com.sande.mythictrpg.power.CombatPowerRuntime;
import com.sande.mythictrpg.quest.reward.*;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.sande.mythictrpg.content.ContentSystemsGameTests.connect;
import static com.sande.mythictrpg.content.ContentSystemsGameTests.disconnect;

@GameTestHolder("mythictrpg_power")
@PrefixGameTestTemplate(false)
public final class RaidCombatScalingGameTests {
    private RaidCombatScalingGameTests() { }

    @GameTest(templateNamespace = "mythictrpg_power", template = "empty", batch = "combat_scaling", timeoutTicks = 300)
    public static void realMeleePreservesChargeCriticalAndRaidBoundaries(GameTestHelper helper) {
        ready(helper, () -> {
            try (var fixture = new Fixture(helper)) {
                var player = fixture.player;
                var target = fixture.target;
                ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
                sword.enchant(player.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS), 255);
                player.setItemSlot(EquipmentSlot.MAINHAND, sword);
                refreshEquipment(player);
                player.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, -1, 1));
                double vanillaAttack = player.getAttributeValue(Attributes.ATTACK_DAMAGE);
                var model = CombatPowerRuntime.publicModel();
                double sharpness = model.sharpnessBase() + model.sharpnessPerLevel() * 255;
                double extra = (vanillaAttack - 6 + sharpness) * model.strengthWeaponFractionPerLevel() * 2;
                fixture.attempt.status = RaidState.Status.FORMING;
                target.getPersistentData().remove(RaidRuntime.RAID_TAG);
                close(helper, attack(player, target, 100, false), vanillaAttack + sharpness,
                        "non-raid melee changed or sharpness was applied twice");
                fixture.attempt.status = RaidState.Status.ACTIVE;
                target.getPersistentData().putUUID(RaidRuntime.RAID_TAG, fixture.attempt.id);
                close(helper, attack(player, target, 100, false), vanillaAttack + sharpness + extra,
                        "active public raid strength bonus missing/doubled");
                close(helper, attack(player, target, 100, true), vanillaAttack * 1.5 + sharpness + extra * 1.5,
                        "crit scaled vanilla sharpness or omitted the strength bonus");
                setCharge(player, 2);
                double charge = player.getAttackStrengthScale(0.5F), scaled = 0.2 + charge * charge * 0.8;
                close(helper, attack(player, target, 2, false), vanillaAttack * scaled + sharpness * charge + extra * scaled,
                        "partial attack did not preserve vanilla recharge factors");

                target.setHealth(1000); target.invulnerableTime = 0;
                target.hurt(player.damageSources().playerAttack(player), 10);
                close(helper, 1000 - target.getHealth(), 10, "lookalike damage without Player.attack received strength bonus");
                var arrow = EntityType.ARROW.create(helper.getLevel());
                target.setHealth(1000); target.invulnerableTime = 0;
                target.hurt(player.damageSources().arrow(arrow, player), 10);
                close(helper, 1000 - target.getHealth(), 10, "projectile received melee strength bonus");
                arrow.discard();

                helper.assertTrue(RaidCombatScaling.eligibleRaidTarget(player, target), "active roster fixture not eligible");
                fixture.attempt.status = RaidState.Status.STARTING;
                helper.assertFalse(RaidCombatScaling.eligibleRaidTarget(player, target), "starting raid got combat scaling");
                fixture.attempt.status = RaidState.Status.ACTIVE;
                var member = fixture.attempt.members.get(player.getUUID());
                member.eliminated = true;
                helper.assertFalse(RaidCombatScaling.eligibleRaidTarget(player, target), "eliminated member got scaling");
                member.eliminated = false; member.awaitingRespawn = true;
                helper.assertFalse(RaidCombatScaling.eligibleRaidTarget(player, target), "respawning member got scaling");
                member.awaitingRespawn = false;
                fixture.attempt.spawnedEntities.remove(target.getUUID());
                helper.assertFalse(RaidCombatScaling.eligibleRaidTarget(player, target), "tag-only mob was trusted");
                fixture.attempt.spawnedEntities.add(target.getUUID());
                target.getPersistentData().putUUID(RaidRuntime.RAID_TAG, UUID.randomUUID());
                helper.assertFalse(RaidCombatScaling.eligibleRaidTarget(player, target), "another raid's actor was trusted");
                target.getPersistentData().putUUID(RaidRuntime.RAID_TAG, fixture.attempt.id);
                Vec3 original = target.position(); target.setPos(original.add(100, 0, 0));
                helper.assertFalse(RaidCombatScaling.eligibleRaidTarget(player, target), "outside-arena actor was trusted");
                target.setPos(original);
                helper.assertFalse(RaidCombatScaling.eligibleRaidTarget(fixture.other, target), "outsider got raid strength");
                helper.assertFalse(RaidCombatScaling.eligibleRaidTarget(player, fixture.other), "PvP received raid strength");

                fixture.replaceMode(RaidDefinition.Mode.PARTY_ISOLATED);
                close(helper, attack(player, target, 100, false), vanillaAttack + sharpness + extra,
                        "fixed party raid did not receive the same strength policy");
                helper.succeed();
            } catch (Exception failure) { throw fixtureFailure("melee combat fixture", failure); }
        });
    }

    @GameTest(templateNamespace = "mythictrpg_power", template = "empty", batch = "combat_scaling", timeoutTicks = 300)
    public static void actualProtectionRequiresOwnedRegisteredWornVariantAndHonorsBypass(GameTestHelper helper) {
        ready(helper, () -> {
            try (var fixture = new Fixture(helper)) {
                fixture.attempt.status = RaidState.Status.FORMING;
                fixture.target.getPersistentData().remove(RaidRuntime.RAID_TAG);
                var player = fixture.player;
                ItemStack boots = armor(player, Items.LEATHER_BOOTS, 255);
                fixture.policy(boots);
                player.setItemSlot(EquipmentSlot.FEET, boots.copy()); refreshEquipment(player);
                close(helper, damage(player, player.damageSources().magic(), 20), 4, "registered but unowned item received custom protection");
                grantItem(helper, player, boots);
                double remaining = 1 / (1 + 255 / CombatPowerRuntime.publicModel().protectionHalfValue());
                close(helper, damage(player, player.damageSources().magic(), 20), 20 * remaining,
                        "high protection was added after vanilla instead of replacing it");
                player.getItemBySlot(EquipmentSlot.FEET).setDamageValue(7);
                close(helper, damage(player, player.damageSources().magic(), 20), 20 * remaining,
                        "ordinary durability changes disabled earned protection");
                fixture.other.setItemSlot(EquipmentSlot.FEET, boots.copy()); refreshEquipment(fixture.other);
                close(helper, damage(fixture.other, player.damageSources().magic(), 20), 4, "borrowed variant inherited another player's ownership");
                ItemStack changed = boots.copy(); changed.set(DataComponents.CUSTOM_MODEL_DATA, new net.minecraft.world.item.component.CustomModelData(902));
                player.setItemSlot(EquipmentSlot.FEET, changed); refreshEquipment(player);
                close(helper, damage(player, player.damageSources().magic(), 20), 4, "changed variant impersonated bound reward gear");
                player.setItemSlot(EquipmentSlot.FEET, ItemStack.EMPTY);
                player.setItemSlot(EquipmentSlot.OFFHAND, boots.copy()); refreshEquipment(player);
                close(helper, damage(player, player.damageSources().magic(), 10), 10, "held armor applied equipped protection");
                player.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
                player.setItemSlot(EquipmentSlot.FEET, boots.copy()); refreshEquipment(player);
                player.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, -1, 3));
                close(helper, damage(player, player.damageSources().magic(), 20), 20 * 0.2 * remaining,
                        "resistance was lost or counted twice");
                close(helper, damage(player, player.damageSources().fellOutOfWorld(), 4), 4,
                        "bypass damage received custom protection/resistance");
                player.removeAllEffects();
                ItemStack helmet = new ItemStack(Items.LEATHER_HELMET);
                helmet.set(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
                helmet.enchant(player.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.FIRE_PROTECTION), 4);
                player.setItemSlot(EquipmentSlot.HEAD, helmet); refreshEquipment(player);
                DamageSource fire = player.damageSources().inFire();
                double fireDamage = afterVanillaArmor(player, fire, 20) * remaining * (1 - 8 / 25.0);
                close(helper, damage(player, fire, 20), fireDamage,
                        "nonregistered specific enchantment mitigation was lost or counted twice");
                helper.succeed();
            } catch (Exception failure) { throw fixtureFailure("protection combat fixture", failure); }
        });
    }

    @GameTest(templateNamespace = "mythictrpg_power", template = "empty", batch = "combat_scaling", timeoutTicks = 300)
    public static void protectionCountermeasureAndAbsorptionApplyOnceInRealDamageOrder(GameTestHelper helper) {
        ready(helper, () -> {
            try (var fixture = new Fixture(helper)) {
                var player = fixture.player;
                ItemStack boots = armor(player, Items.LEATHER_BOOTS, 120);
                fixture.policy(boots); grantItem(helper, player, boots);
                player.setItemSlot(EquipmentSlot.FEET, boots); refreshEquipment(player);
                player.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, -1, 3));
                fixture.attempt.countermeasures = List.of(new ContentDefinitions.Countermeasure(id("prepared"), fixture.attempt.definition.id(),
                        ResourceLocation.withDefaultNamespace("mob_attack"), ResourceLocation.withDefaultNamespace("feather"), true, 0.5, new CompoundTag()));
                player.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.FEATHER, 2));
                DamageSource source = player.damageSources().mobAttack(fixture.target);
                // EMPTY modifiers retain the item's default armor in NeoForge; that phase precedes resistance.
                helper.assertValueEqual(player.getArmorValue(), 1, "fixture boots must retain their vanilla armor");
                double expected = afterVanillaArmor(player, source, 20) * 0.2
                        / (1 + 120 / CombatPowerRuntime.publicModel().protectionHalfValue()) * 0.5;
                close(helper, damage(player, source, 20), expected,
                        "countermeasure and custom protection were not composed once");
                helper.assertValueEqual(player.getOffhandItem().getCount(), 1, "countermeasure consumed more than once");
                player.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, -1, 0));
                player.setAbsorptionAmount(1);
                close(helper, damage(player, source, 20), 0, "absorption did not follow the complete reduction chain");
                close(helper, player.getAbsorptionAmount(), 1 - expected, "absorption spent the wrong post-reduction amount");
                helper.assertTrue(player.getOffhandItem().isEmpty(), "second real hit did not consume one tool");
                helper.succeed();
            } catch (Exception failure) { throw fixtureFailure("damage order fixture", failure); }
        });
    }

    private static void ready(GameTestHelper helper, Runnable test) {
        helper.startSequence().thenWaitUntil(() -> {
            try {
                var method = CombatPowerRuntime.class.getDeclaredMethod("captureReady", net.minecraft.server.MinecraftServer.class);
                method.setAccessible(true);
                helper.assertTrue((boolean) method.invoke(null, helper.getLevel().getServer()), "acquisition fence not ready");
            } catch (ReflectiveOperationException failure) { throw fixtureFailure("acquisition fence fixture", failure); }
        }).thenExecute(test);
    }
    @GameTest(templateNamespace="mythictrpg_power",template="empty",batch="combat_scaling",timeoutTicks=300)
    public static void brokenRegisteredArmorDoesNotSurviveIntoEnchantmentPhase(GameTestHelper helper) {
        ready(helper,()-> {
            try (var fixture = new Fixture(helper)) {
                var player = fixture.player;
                ItemStack boots = armor(player,Items.LEATHER_BOOTS,120);
                fixture.policy(boots); grantItem(helper,player,boots);
                boots.setDamageValue(boots.getMaxDamage()-1);
                player.setItemSlot(EquipmentSlot.FEET,boots);
                ItemStack helmet = new ItemStack(Items.LEATHER_HELMET);
                helmet.set(DataComponents.ATTRIBUTE_MODIFIERS,ItemAttributeModifiers.EMPTY);
                helmet.enchant(player.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.PROTECTION),4);
                player.setItemSlot(EquipmentSlot.HEAD,helmet); refreshEquipment(player);
                // Armor wear happens before enchantment mitigation. Only the surviving unregistered helmet counts.
                DamageSource source = player.damageSources().mobAttack(fixture.target);
                double expected = afterVanillaArmor(player, source, 4) * (1 - 4 / 25.0);
                close(helper,damage(player,source,4),expected,
                        "destroyed bound armor still contributed protection or changed the surviving helmet");
                helper.assertTrue(player.getItemBySlot(EquipmentSlot.FEET).isEmpty(),"fixture armor did not break during the real hit");
                helper.succeed();
            } catch(Exception failure){throw fixtureFailure("breaking armor fixture",failure);}
        });
    }
    @GameTest(templateNamespace="mythictrpg_power",template="empty",batch="combat_scaling",timeoutTicks=300)
    public static void invalidItemDefinitionLeavesActualDamageVanilla(GameTestHelper helper) {
        ready(helper,()-> {
            try (var fixture = new Fixture(helper)) {
                var player = fixture.player; ItemStack boots = armor(player,Items.LEATHER_BOOTS,120);
                fixture.policy(boots); grantItem(helper,player,boots);
                player.setItemSlot(EquipmentSlot.FEET,boots); refreshEquipment(player);
                var good = CombatPowerPolicyManager.INSTANCE.current().policy();
                for (boolean unknownItem : List.of(true,false)) {
                    CompoundTag invalidComponents = new CompoundTag();
                    if (!unknownItem) invalidComponents.putString("minecraft:enchantments","not_an_enchantment_component");
                    var invalidRule = new CombatPowerPolicy.ItemRule(unknownItem ? ResourceLocation.parse("test:missing_item")
                            : ResourceLocation.withDefaultNamespace("leather_boots"),invalidComponents,"feet",1,false,0,0,0,Map.of());
                    var rules = new ArrayList<>(good.items()); rules.add(invalidRule);
                    var invalidPolicy = new CombatPowerPolicy(good.id(),good.aggregation(),good.basePower(),good.equipmentCoefficient(),
                            good.effectCoefficient(),good.permanentCoefficient(),good.lowRatioExclusive(),rules,good.effects(),good.permanent(),
                            good.recommendations(),good.model());
                    fixture.policyField.set(CombatPowerPolicyManager.INSTANCE,new CombatPowerPolicyManager.Loaded(invalidPolicy,"READY"));
                    helper.assertTrue(CombatPowerRuntime.registeredAcquiredGear(player,boots).isEmpty(),"invalid rule did not fail closed");
                    close(helper,damage(player,player.damageSources().magic(),20),4,"invalid definition interrupted or changed real damage");
                }
                helper.succeed();
            } catch(Exception failure){throw fixtureFailure("invalid policy damage fixture",failure);}
        });
    }
    private static void close(GameTestHelper helper, double actual, double expected, String message) {
        helper.assertTrue(Math.abs(actual - expected) < 0.002, message + ": expected=" + expected + ", actual=" + actual);
    }
    private static GameTestAssertException fixtureFailure(String description, Exception failure) {
        if (failure instanceof GameTestAssertException assertion) return assertion;
        var assertion = new GameTestAssertException(description + ": " + failure);
        assertion.initCause(failure);
        return assertion;
    }
    private static double afterVanillaArmor(ServerPlayer player, DamageSource source, float amount) {
        return CombatRules.getDamageAfterAbsorb(player, amount, source, player.getArmorValue(),
                (float) player.getAttributeValue(Attributes.ARMOR_TOUGHNESS));
    }
    private static void setCharge(ServerPlayer player, int ticks) throws Exception {
        Field field = LivingEntity.class.getDeclaredField("attackStrengthTicker"); field.setAccessible(true); field.setInt(player, ticks);
    }
    private static void refreshEquipment(LivingEntity entity) throws Exception {
        var method = LivingEntity.class.getDeclaredMethod("detectEquipmentUpdates"); method.setAccessible(true); method.invoke(entity);
    }
    private static double attack(ServerPlayer player, Zombie target, int charge, boolean critical) throws Exception {
        target.setHealth(1000); target.invulnerableTime = 0;
        player.setSprinting(false); player.setOnGround(!critical); player.fallDistance = critical ? 1 : 0;
        setCharge(player, charge); player.attack(target);
        return 1000 - target.getHealth();
    }
    private static double damage(ServerPlayer player, DamageSource source, float amount) {
        player.setHealth(20); player.invulnerableTime = 0;
        if (!player.hurt(source, amount)) throw new GameTestAssertException("actual hurt path rejected " + source);
        return 20 - player.getHealth();
    }
    private static ItemStack armor(ServerPlayer player, net.minecraft.world.item.Item item, int level) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        stack.set(DataComponents.CUSTOM_MODEL_DATA, new net.minecraft.world.item.component.CustomModelData(901));
        stack.enchant(player.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.PROTECTION), level);
        return stack;
    }
    private static void grantItem(GameTestHelper helper, ServerPlayer player, ItemStack stack) {
        var entry = new NpcRewardEntry(BuiltInRegistries.ITEM.getKey(stack.getItem()), 1,
                ((CompoundTag) stack.save(player.registryAccess())).getCompound("components"));
        var result = RewardClaimService.INSTANCE.issue(player, ResourceLocation.parse("mythictrpg:fortuna"), id("reward"),
                new ResolvedQuestReward("combat fixture", List.of(entry), List.of()));
        helper.assertTrue(result.succeeded(), "authored item delivery failed: " + result.reason());
        helper.assertTrue(CombatPowerRuntime.registeredAcquiredGear(player, stack).isPresent(), "delivered item was not authenticated");
    }
    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath("test", path + "_" + UUID.randomUUID()); }

    private static final class Fixture implements AutoCloseable {
        final GameTestHelper helper;
        final ArrayList<EmbeddedChannel> channels = new ArrayList<>();
        final ServerPlayer player, other;
        final Zombie target;
        final CombatPowerPolicyManager.Loaded previous;
        final Field policyField;
        final RaidState state;
        RaidState.Attempt attempt;
        Fixture(GameTestHelper helper) throws Exception {
            this.helper = helper;
            previous = CombatPowerPolicyManager.INSTANCE.current();
            policyField = CombatPowerPolicyManager.class.getDeclaredField("current"); policyField.setAccessible(true);
            policy(null);
            player = connect(helper, "CombatOwner", channels); other = connect(helper, "CombatOther", channels);
            for (ServerPlayer connected : List.of(player, other)) {
                connected.setGameMode(GameType.SURVIVAL); connected.setInvulnerable(false); connected.getAbilities().invulnerable = false;
                var field = ServerPlayer.class.getDeclaredField("spawnInvulnerableTime"); field.setAccessible(true); field.setInt(connected, 0);
            }
            Vec3 anchor = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(2, 2, 2)));
            player.setPos(anchor); other.setPos(anchor.add(0, 0, 1));
            target = EntityType.ZOMBIE.create(helper.getLevel()); target.setNoAi(true); target.setPersistenceRequired();
            target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000); target.getAttribute(Attributes.ARMOR).setBaseValue(0);
            target.setHealth(1000); target.setPos(anchor.add(1, 0, 0)); helper.getLevel().addFreshEntity(target);
            state = RaidState.get(player.server); replaceMode(RaidDefinition.Mode.PUBLIC_WORLD);
        }
        void policy(ItemStack gear) throws Exception {
            List<CombatPowerPolicy.ItemRule> items = gear == null ? List.of() : List.of(new CombatPowerPolicy.ItemRule(
                    BuiltInRegistries.ITEM.getKey(gear.getItem()), ((CompoundTag) gear.save(helper.getLevel().registryAccess())).getCompound("components"),
                    "feet", 1, false, 0, 0,
                    net.minecraft.world.item.enchantment.EnchantmentHelper.getItemEnchantmentLevel(
                            helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.PROTECTION), gear), Map.of()));
            var policy = new CombatPowerPolicy("test:actual_combat", CombatPowerPolicy.Aggregation.SUM_SLOT_BEST,
                    0, 1, 1, 1, 0.5, items, Map.of(), Map.of(), List.of(), CombatPowerPolicyManager.initialProposalModel());
            policyField.set(CombatPowerPolicyManager.INSTANCE, new CombatPowerPolicyManager.Loaded(policy, "READY"));
        }
        void replaceMode(RaidDefinition.Mode mode) {
            if (attempt != null) state.attempts.remove(attempt.id);
            ResourceLocation arena = id("arena");
            var json = JsonParser.parseString(RaidGameTests.fixtureJson()).getAsJsonObject(); json.addProperty("mode", mode.name());
            json.getAsJsonArray("arenas").set(0, new com.google.gson.JsonPrimitive(arena.toString()));
            var definition = RaidDefinition.parse(id("raid"), json);
            attempt = state.create(player.getUUID(), UUID.randomUUID(), definition, Set.of(player.getUUID()), player.server.overworld().getGameTime());
            attempt.status = RaidState.Status.ACTIVE;
            attempt.members.get(player.getUUID()).returnPoint = RaidState.ReturnPoint.of(player);
            attempt.arena = RaidDefinition.parseArena(arena, JsonParser.parseString(
                    RaidGameTests.arenaJson(player.position(), helper.getLevel().dimension().location())).getAsJsonObject());
            attempt.spawnedEntities.add(target.getUUID()); attempt.bossId = target.getUUID();
            target.getPersistentData().putUUID(RaidRuntime.RAID_TAG, attempt.id); state.changed();
        }
        @Override public void close() throws Exception {
            policyField.set(CombatPowerPolicyManager.INSTANCE, previous);
            if (attempt != null) { state.attempts.remove(attempt.id); state.changed(); }
            target.discard(); disconnect(player, channels); disconnect(other, List.of());
        }
    }
}
