package com.sande.mythictrpg.prototype;

import com.google.gson.JsonParser;
import com.sande.mythictrpg.accessory.AccessoryDefinitions;
import com.sande.mythictrpg.accessory.AccessoryEffect;
import com.sande.mythictrpg.accessory.AccessoryRuntime;
import com.sande.mythictrpg.data.god.GodDefinitionManager;
import com.sande.mythictrpg.power.CombatPowerPolicy;
import com.sande.mythictrpg.power.CombatPowerRuntime;
import com.sande.mythictrpg.power.RewardPowerState;
import com.sande.mythictrpg.quest.dynamic.CombatPowerAssessment;
import com.sande.mythictrpg.quest.dynamic.CombatPowerService;
import com.sande.mythictrpg.quest.reward.ProgressionRewardEntry;
import com.sande.mythictrpg.quest.reward.RewardClaim;
import com.sande.mythictrpg.quest.reward.RewardClaimState;
import com.sande.mythictrpg.quest.reward.RewardEntry;
import com.sande.mythictrpg.quest.reward.RewardExecutionService;
import com.sande.mythictrpg.quest.reward.RewardGrantPurpose;
import com.sande.mythictrpg.quest.reward.RewardOrigin;
import com.sande.mythictrpg.skills.LearnedSkillDefinitions;
import com.sande.mythictrpg.skills.SkillCastEvents;
import com.sande.mythictrpg.skills.SkillExecutor;
import com.sande.mythictrpg.skills.SkillMana;
import com.sande.mythictrpg.weaponskill.WeaponSkillDefinition;
import com.sande.mythictrpg.weaponskill.WeaponSkillDefinitions;
import com.sande.mythictrpg.weaponskill.WeaponSkillState;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Isolated prototype accounts; never clears existing player acquisitions or blessing ownership. */
@GameTestHolder("mythictrpg_prototype_catalog")
@PrefixGameTestTemplate(false)
public final class PrototypeCatalogGameTests {
    private static final ResourceLocation PROVIDER = PrototypeGrantService.TECHNICAL_SOURCE;
    private static final List<ResourceLocation> WEAPONS = List.of(PrototypeCatalog.STEADY_BLADE,
            PrototypeCatalog.WAVE_BLADE, PrototypeCatalog.GUARD_BLADE);
    private static final String[] ARMOR_PARTS = {"head", "chest", "legs", "feet"};
    private static final EquipmentSlot[] ARMOR_SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST,
            EquipmentSlot.LEGS, EquipmentSlot.FEET};

    @GameTest(templateNamespace = "mythictrpg_prototype_catalog", template = "empty", batch = "prototype_catalog_identity")
    public static void exactVariantsHaveRealAttributesAndVanillaDurability(GameTestHelper h) {
        try (var fixture = new Fixture(h)) {
            var player = fixture.connect();
            Set<Integer> models = new HashSet<>();
            var policy = bundledPolicy();
            h.assertValueEqual(PrototypeCatalog.items().size(), 11, "prototype catalog incomplete");
            for (var item : PrototypeCatalog.items()) {
                var stack = item.reward().createStack(player.registryAccess());
                h.assertTrue(models.add(stack.get(DataComponents.CUSTOM_MODEL_DATA).value()), "prototype model collision");
                h.assertTrue(stack.get(DataComponents.LORE).lines().stream().anyMatch(line -> line.getString().contains("희귀")), "rare display missing");
                var matched = policy.stateGear(List.of(RewardPowerState.identity(stack, player.registryAccess())), player.registryAccess());
                h.assertValueEqual(matched.size(), 1, "reward and policy component identities differ");
                h.assertTrue(stack.isDamageableItem(), "prototype stripped durability");
            }
            for (int i = 0; i < WEAPONS.size(); i++) {
                var stack = PrototypeCatalog.item(WEAPONS.get(i)).reward().createStack(player.registryAccess());
                player.setItemSlot(EquipmentSlot.MAINHAND, stack);
                player.doTick();
                close(h, player.getAttributeValue(Attributes.ATTACK_DAMAGE), i == 0 ? 9 : 7, "actual weapon attack");
                close(h, player.getAttributeValue(Attributes.ATTACK_SPEED), 1.6, "actual weapon speed");
                h.assertValueEqual(stack.getMaxDamage(), 1561, "diamond sword durability changed");
                h.assertValueEqual(stack.get(DataComponents.ATTRIBUTE_MODIFIERS).modifiers().size(), 2, "vanilla weapon modifiers doubled");
            }
            player.setItemSlot(EquipmentSlot.MAINHAND, net.minecraft.world.item.ItemStack.EMPTY);
            for (String material : List.of("iron", "diamond")) {
                equipArmor(player, material);
                player.doTick();
                close(h, player.getAttributeValue(Attributes.ARMOR), material.equals("iron") ? 15 : 20, "actual armor total");
                close(h, player.getAttributeValue(Attributes.ARMOR_TOUGHNESS), material.equals("iron") ? 0 : 8, "actual armor toughness");
                var protection = player.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.PROTECTION);
                int sum = 0;
                for (var slot : ARMOR_SLOTS) {
                    var stack = player.getItemBySlot(slot);
                    int level = EnchantmentHelper.getItemEnchantmentLevel(protection, stack);
                    h.assertValueEqual(level, material.equals("iron") ? 2 : 0, "actual protection differs from policy");
                    sum += level;
                }
                h.assertValueEqual(sum, material.equals("iron") ? 8 : 0, "protection total");
            }
        }
        h.succeed();
    }

    @GameTest(templateNamespace = "mythictrpg_prototype_catalog", template = "empty", batch = "prototype_catalog_definitions")
    public static void definitionsConnectExactPrototypeSkillsAndAccessoryEffects(GameTestHelper h) {
        var wave = WeaponSkillDefinitions.INSTANCE.find(PrototypeCatalog.BLADE_WAVE).orElseThrow();
        var parry = WeaponSkillDefinitions.INSTANCE.find(PrototypeCatalog.TIMED_PARRY).orElseThrow();
        var dodge = LearnedSkillDefinitions.INSTANCE.find(PrototypeCatalog.SHORT_DODGE).orElseThrow();
        h.assertTrue(wave.action() instanceof WeaponSkillDefinition.BladeWave action && action.firstDamage() == 8
                && action.subsequentDamage() == 4 && action.maximumTargets() == 3 && action.windupTicks() == 4
                && action.recoveryTicks() == 6 && action.range() == 10 && action.speed() == 1 && action.radius() == .25,
                "wave definition differs from the prototype");
        h.assertTrue(wave.manaCost() == 24 && wave.cooldownTicks() == 160, "wave cost");
        h.assertTrue(parry.action() instanceof WeaponSkillDefinition.TimedParry action && action.windupTicks() == 2
                && action.activeTicks() == 4 && action.recoveryTicks() == 8 && action.range() == 4
                && action.halfAngleDegrees() == 60, "parry definition");
        h.assertTrue(parry.manaCost() == 20 && parry.cooldownTicks() == 120, "parry cost");
        h.assertTrue(dodge.action() instanceof WeaponSkillDefinition.ShortDash action && action.windupTicks() == 1
                && action.movementTicks() == 4 && action.evasionTicks() == 3 && action.recoveryTicks() == 3
                && action.distance() == 3 && action.minimumDistance() == .25, "dodge definition");
        h.assertTrue(dodge.manaCost() == 30 && dodge.cooldownTicks() == 200 && dodge.intrinsicPower() == 15, "dodge cost/power");
        var mana = AccessoryDefinitions.INSTANCE.find(PrototypeCatalog.CLEAR_MANA_STONE).orElseThrow();
        var knot = AccessoryDefinitions.INSTANCE.find(PrototypeCatalog.BLADE_KNOT).orElseThrow();
        h.assertTrue(mana.fixedPower() == 12 && mana.effects().size() == 1
                && mana.effect(AccessoryEffect.Kind.MAX_MANA).orElseThrow().amount() == 40, "mana stone");
        h.assertTrue(knot.fixedPower() == 12 && knot.effects().size() == 1
                && knot.effect(AccessoryEffect.Kind.LINK_KNOT).orElseThrow().amount() == .1, "blade knot");
        try (var input = PrototypeCatalog.class.getResourceAsStream("/data/mythictrpg/mythictrpg/combat_power/default.json")) {
            if (input == null) throw new IllegalStateException("missing policy");
            var json = JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            for (var value : json.getAsJsonArray("items")) {
                var row = value.getAsJsonObject();
                String catalogId = row.getAsJsonObject("components").getAsJsonObject("minecraft:custom_data").get("prototype_id").getAsString();
                if (catalogId.equals(PrototypeCatalog.STEADY_BLADE.toString()))
                    h.assertTrue(!row.has("rightClickSkill") && row.getAsJsonObject("skills").size() == 0, "steady blade acquired an invented skill");
                if (catalogId.equals(PrototypeCatalog.WAVE_BLADE.toString()))
                    h.assertValueEqual(row.get("rightClickSkill").getAsString(), PrototypeCatalog.BLADE_WAVE.toString(), "wave binding");
                if (catalogId.equals(PrototypeCatalog.GUARD_BLADE.toString()))
                    h.assertValueEqual(row.get("rightClickSkill").getAsString(), PrototypeCatalog.TIMED_PARRY.toString(), "parry binding");
            }
        } catch (IOException invalid) { throw new IllegalStateException(invalid); }
        h.succeed();
    }

    @GameTest(templateNamespace = "mythictrpg_prototype_catalog", template = "empty", batch = "prototype_catalog_power", timeoutTicks = 300)
    public static void isolatedAcquisitionLedgersMatchAllSixPowerExamples(GameTestHelper h) {
        h.startSequence().thenWaitUntil(() -> h.assertTrue(captureReady(h), "power startup fence"))
                .thenExecute(() -> {
                    try (var fixture = new Fixture(h)) {
                        for (String material : List.of("iron", "diamond")) {
                            for (ResourceLocation weapon : WEAPONS) {
                                var player = fixture.connect();
                                h.assertTrue(RewardPowerState.get(player.server).history(player.getUUID()).orElseThrow().items().isEmpty(), "isolated player was not fresh");
                                fixture.grant(player, List.of(PrototypeCatalog.item(weapon).reward()));
                                assessed(h, player, 120, "weapon-only power " + weapon);
                                List<RewardEntry> rewards = new ArrayList<>();
                                for (String part : ARMOR_PARTS) rewards.add(PrototypeCatalog.item(material + "_guard_" + part).reward());
                                rewards.add(new ProgressionRewardEntry(ProgressionRewardEntry.Kind.LEARNED, PrototypeCatalog.SHORT_DODGE));
                                rewards.add(new ProgressionRewardEntry(ProgressionRewardEntry.Kind.ACCESSORY, PrototypeCatalog.CLEAR_MANA_STONE));
                                rewards.add(new ProgressionRewardEntry(ProgressionRewardEntry.Kind.ACCESSORY, PrototypeCatalog.BLADE_KNOT));
                                fixture.grant(player, rewards);
                                double expected = material.equals("iron") ? 176.90890230020665 : 187;
                                assessed(h, player, expected, material + " acquisition power " + weapon);
                                player.getInventory().clearContent();
                                player.doTick();
                                assessed(h, player, expected, "unequipped historical power");
                                equipArmor(player, material);
                                player.doTick();
                                assessed(h, player, expected, "worn gear double-counted historical power");
                            }
                        }
                    }
                }).thenSucceed();
    }

    @GameTest(templateNamespace = "mythictrpg_prototype_catalog", template = "empty", batch = "prototype_catalog_accessories")
    public static void legalStoneCopiesStackWithoutInstantManaAndRegenerateOnePercent(GameTestHelper h) {
        try (var fixture = new Fixture(h)) {
            for (int copies = 0; copies <= 2; copies++) {
                var player = fixture.connect();
                for (int i = 0; i < copies; i++)
                    fixture.grant(player, List.of(new ProgressionRewardEntry(ProgressionRewardEntry.Kind.ACCESSORY, PrototypeCatalog.CLEAR_MANA_STONE)));
                var rights = AccessoryRuntime.entitlements(player);
                for (int i = 0; i < copies; i++)
                    h.assertTrue(AccessoryRuntime.equip(player, i, rights.get(i).id()), "legitimate stone did not equip");
                if (copies == 1) h.assertTrue(!AccessoryRuntime.equip(player, 1, rights.getFirst().id()), "one entitlement occupied two slots");
                close(h, AccessoryRuntime.maximumMana(player), 200 + copies * 40, "stone maximum");
                close(h, SkillMana.mana(player), 200, "equip created current mana");
                var state = WeaponSkillState.get(player.server);
                long now = SkillMana.now(player);
                state.trySpend(player.getUUID(), "test:prototype_mana_check", 50, 0, now);
                double before = SkillMana.mana(player);
                for (int i = 1; i <= 20; i++) state.regenerate(player.getUUID(), now + i, AccessoryRuntime.maximumMana(player));
                close(h, SkillMana.mana(player) - before, (200 + copies * 40) * .01, "one-second mana regen");
                if (copies > 0) {
                    double beforeSwap = SkillMana.mana(player);
                    AccessoryRuntime.equip(player, 0, null);
                    AccessoryRuntime.equip(player, 0, rights.getFirst().id());
                    close(h, SkillMana.mana(player), beforeSwap, "reequip created mana");
                }
            }
        }
        h.succeed();
    }

    @GameTest(templateNamespace = "mythictrpg_prototype_catalog", template = "empty", batch = "prototype_catalog_grant", timeoutTicks = 300)
    public static void operatorGrantAndReissueKeepRightsCooldownHistoryAndNpcRegistry(GameTestHelper h) {
        h.startSequence().thenWaitUntil(() -> h.assertTrue(captureReady(h), "power startup fence"))
                .thenExecute(() -> {
                    try (var fixture = new Fixture(h)) {
                        var player = fixture.connect();
                        var ordinary = player.createCommandSourceStack().withPermission(0);
                        var operator = player.createCommandSourceStack().withPermission(2);
                        var gods = GodDefinitionManager.INSTANCE.progressionSnapshot();
                        var claims = RewardClaimState.get(player.server);
                        h.assertTrue(!PrototypeGrantService.grantKit(ordinary).succeeded(), "ordinary player obtained an OP prototype kit");
                        h.assertTrue(!PrototypeGrantService.grant(operator, ResourceLocation.parse("minecraft:netherite_sword")).succeeded(), "unlisted item bypassed developer whitelist");
                        h.assertTrue(claims.progressionFor(player.getUUID()).isEmpty(), "rejected grant minted ownership");
                        var first = PrototypeGrantService.grantKit(operator);
                        h.assertTrue(first.succeeded() && first.delivered() == 16, "prototype kit: " + first.message());
                        var rights = claims.progressionFor(player.getUUID());
                        h.assertValueEqual(rights.size(), 5, "kit must provide learned one and two independent copies of each accessory");
                        h.assertValueEqual(rights.stream().map(RewardClaimState.ProgressionEntitlement::entitlementId).distinct().count(), 5L, "duplicate prototype entitlement");
                        h.assertTrue(rights.stream().allMatch(right -> right.godId().equals(PrototypeGrantService.TECHNICAL_SOURCE)), "prototype created a fictional NPC provider");
                        h.assertValueEqual(RewardPowerState.get(player.server).history(player.getUUID()).orElseThrow().items().size(), 11, "prototype item acquisition was not recorded");
                        h.assertTrue(!claims.hasNonRaidBlessing(player.getUUID()), "equipment provisioning became a route-blocking blessing");
                        h.assertTrue(GodDefinitionManager.INSTANCE.progressionSnapshot() == gods, "prototype grant changed NPC identities or ranks");

                        var skillState = WeaponSkillState.get(player.server);
                        long now = SkillMana.now(player);
                        String cooldown = SkillExecutor.cooldownKey(SkillCastEvents.Source.LEARNED, PrototypeCatalog.SHORT_DODGE);
                        h.assertValueEqual(skillState.trySpend(player.getUUID(), cooldown, 30, 200, now), WeaponSkillState.Result.SUCCESS, "fixture cooldown");
                        double mana = SkillMana.mana(player);
                        int count = player.getInventory().items.stream().mapToInt(net.minecraft.world.item.ItemStack::getCount).sum();
                        var wave = PrototypeCatalog.item(PrototypeCatalog.WAVE_BLADE).reward().createStack(player.registryAccess());
                        player.getInventory().items.stream().filter(stack -> net.minecraft.world.item.ItemStack.isSameItemSameComponents(stack, wave)).findFirst().orElseThrow().setDamageValue(37);
                        var repeated = PrototypeGrantService.grantKit(operator);
                        h.assertTrue(repeated.succeeded() && repeated.delivered() == 0, "repeated grant duplicated a damaged weapon or existing reward");
                        h.assertValueEqual(player.getInventory().items.stream().mapToInt(net.minecraft.world.item.ItemStack::getCount).sum(), count, "repeated kit grew inventory");

                        UUID token = rights.stream().filter(right -> right.kind() == ProgressionRewardEntry.Kind.ACCESSORY).findFirst().orElseThrow().entitlementId();
                        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                            var stack = player.getInventory().getItem(i);
                            var data = stack.getOrDefault(DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
                            if (data.getString("prototype_id").equals(PrototypeCatalog.WAVE_BLADE.toString())
                                    || data.hasUUID("accessoryEntitlement") && data.getUUID("accessoryEntitlement").equals(token))
                                player.getInventory().setItem(i, net.minecraft.world.item.ItemStack.EMPTY);
                        }
                        var recovered = PrototypeGrantService.grantKit(operator);
                        h.assertTrue(recovered.succeeded() && recovered.delivered() == 2, "lost prototype equipment/token did not restore: " + recovered.message());
                        h.assertValueEqual(claims.progressionFor(player.getUUID()), rights, "reissue minted fresh accessory rights");
                        h.assertValueEqual(RewardPowerState.get(player.server).history(player.getUUID()).orElseThrow().items().size(), 11, "reissue grew power acquisition history");
                        close(h, SkillMana.mana(player), mana, "reissue refunded spent mana");
                        h.assertValueEqual(skillState.account(player.getUUID()).orElseThrow().remainingTicks(cooldown, now), 200L, "reissue reset learned skill cooldown");
                        h.assertTrue(com.sande.mythictrpg.accessory.AccessoryInventory.legacyEquip(player, 0, token), "owned prototype accessory could not enter inventory equipment slot");
                        for (int i = 0; i < player.getInventory().items.size(); i++)
                            if (player.getInventory().items.get(i).isEmpty()) player.getInventory().items.set(i, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIRT, 64));
                        var wornRepeat = PrototypeGrantService.grantKit(operator);
                        h.assertTrue(wornRepeat.succeeded() && wornRepeat.delivered() == 0, "worn accessory was treated as missing and required another free inventory slot");
                        h.assertValueEqual(claims.progressionFor(player.getUUID()), rights, "worn kit check minted new rights");
                    }
                }).thenSucceed();
    }

    @GameTest(templateNamespace = "mythictrpg_prototype_catalog", template = "empty", batch = "prototype_catalog_grant_full", timeoutTicks = 300)
    public static void fullInventoryRejectsKitBeforeReceiptsOrOwnership(GameTestHelper h) {
        h.startSequence().thenWaitUntil(() -> h.assertTrue(captureReady(h), "power startup fence"))
                .thenExecute(() -> {
                    try (var fixture = new Fixture(h)) {
                        var player = fixture.connect();
                        for (int i = 0; i < player.getInventory().items.size(); i++)
                            player.getInventory().items.set(i, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIRT, 64));
                        var result = PrototypeGrantService.grantKit(player.createCommandSourceStack().withPermission(2));
                        h.assertTrue(!result.succeeded() && result.delivered() == 0, "full inventory kit was partially granted or dropped");
                        var claims = RewardClaimState.get(player.server);
                        h.assertTrue(claims.progressionFor(player.getUUID()).isEmpty(), "full inventory rejection consumed progression rights");
                        h.assertTrue(RewardPowerState.get(player.server).history(player.getUUID()).orElseThrow().items().isEmpty(), "full inventory rejection recorded fake deliveries");
                        h.assertTrue(player.getInventory().items.stream().allMatch(stack -> stack.is(net.minecraft.world.item.Items.DIRT) && stack.getCount() == 64), "full inventory rejection altered existing stacks");
                    }
                }).thenSucceed();
    }

    private static void assessed(GameTestHelper h, ServerPlayer player, double expected, String reason) {
        var value = CombatPowerService.INSTANCE.assess(player);
        h.assertValueEqual(value.status(), CombatPowerAssessment.Status.AVAILABLE, reason + ": " + value.evidence());
        close(h, value.actualPower(), expected, reason);
    }

    private static void equipArmor(ServerPlayer player, String material) {
        for (int i = 0; i < ARMOR_PARTS.length; i++) player.setItemSlot(ARMOR_SLOTS[i],
                PrototypeCatalog.item(material + "_guard_" + ARMOR_PARTS[i]).reward().createStack(player.registryAccess()));
    }

    private static void close(GameTestHelper h, double actual, double expected, String reason) {
        h.assertTrue(Math.abs(actual - expected) < 0.000001, reason + ": actual=" + actual + ", expected=" + expected);
    }

    private static CombatPowerPolicy bundledPolicy() {
        try (var input = PrototypeCatalog.class.getResourceAsStream("/data/mythictrpg/mythictrpg/combat_power/default.json")) {
            if (input == null) throw new IllegalStateException("missing policy");
            return CombatPowerPolicy.parse("mythictrpg:default", JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
        } catch (IOException invalid) { throw new IllegalStateException(invalid); }
    }

    private static final class Fixture implements AutoCloseable {
        final GameTestHelper h;
        final List<ServerPlayer> players = new ArrayList<>();
        final List<EmbeddedChannel> channels = new ArrayList<>();

        Fixture(GameTestHelper h) {
            this.h = h;
        }

        ServerPlayer connect() {
            var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(
                    new com.mojang.authlib.GameProfile(UUID.randomUUID(), "ProtoCatalog" + players.size()), false);
            var player = new ServerPlayer(h.getLevel().getServer(), h.getLevel(), cookie.gameProfile(), cookie.clientInformation());
            var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
            channels.add(new EmbeddedChannel(connection));
            net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(connection);
            player.server.getPlayerList().placeNewPlayer(connection, player, cookie);
            player.setNoGravity(true);
            players.add(player);
            return player;
        }

        void grant(ServerPlayer player, List<? extends RewardEntry> entries) {
            List<RewardEntry> rewards = List.copyOf(entries);
            var preflight = RewardExecutionService.validateGrant(player, rewards, RewardGrantPurpose.QUEST);
            h.assertTrue(preflight.allowed(), "prototype acquisition preflight failed: " + preflight.reason());
            var state = RewardClaimState.get(player.server);
            var claim = state.create(new RewardClaim(UUID.randomUUID(), player.getUUID(), PROVIDER,
                    PrototypeCatalog.id("test_receipt/" + UUID.randomUUID()), "isolated prototype fixture", rewards, List.of(),
                    false, Optional.empty(), player.server.overworld().getGameTime(), RewardOrigin.LEGACY_UNSPECIFIED));
            state.markAutomaticGranted(claim.claimId());
            var result = RewardExecutionService.grant(player, PROVIDER, rewards, RewardGrantPurpose.QUEST);
            h.assertTrue(result.granted(), "prototype acquisition failed: " + result.reason());
        }

        @Override public void close() {
            players.forEach(player -> player.server.getPlayerList().remove(player));
            channels.forEach(EmbeddedChannel::finishAndReleaseAll);
        }
    }

    static boolean captureReady(GameTestHelper h) {
        try {
            var method = CombatPowerRuntime.class.getDeclaredMethod("captureReady", net.minecraft.server.MinecraftServer.class);
            method.setAccessible(true);
            return (boolean) method.invoke(null, h.getLevel().getServer());
        } catch (ReflectiveOperationException invalid) { throw new IllegalStateException(invalid); }
    }
}
