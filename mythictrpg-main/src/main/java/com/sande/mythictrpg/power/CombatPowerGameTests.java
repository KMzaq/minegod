package com.sande.mythictrpg.power;

import com.google.gson.JsonParser;
import com.sande.mythictrpg.quest.dynamic.*;
import com.sande.mythictrpg.quest.reward.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.*;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.IOUtilities;
import net.neoforged.neoforge.gametest.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

@GameTestHolder("mythictrpg_power")
@PrefixGameTestTemplate(false)
public final class CombatPowerGameTests {
    private static final String POLICY = """
        {"schemaVersion":1,"aggregation":"MAX_ALL","basePower":2,
         "equipmentCoefficient":1,"effectCoefficient":1,"permanentCoefficient":1,
         "permanentMode":"ACTUAL_PERMANENT_MODIFIERS_ONLY","lowRatioExclusive":0.5,
         "items":[{"itemId":"minecraft:iron_sword","components":{},"classification":"GEAR","slot":"weapon","score":12},
           {"itemId":"minecraft:stone_sword","components":{},"classification":"GEAR","slot":"weapon","score":5}],
         "effects":[{"effectId":"minecraft:strength","amplifier":0,"score":3}],
         "permanentModifiers":[{"attributeId":"minecraft:generic.attack_damage","modifierId":"test:earned",
           "operation":"ADD_VALUE","coefficient":2}],
         "recommendations":[{"progress":{"test:story":{"minimum":0,"maximum":10}},"recommendedPower":40}]}
        """;
    private static CombatPowerPolicy policy() { return CombatPowerPolicy.parse("test:synthetic", JsonParser.parseString(POLICY).getAsJsonObject()); }
    @GameTest(templateNamespace = "mythictrpg_power", template = "empty", batch = "power_v2", timeoutTicks = 300)
    public static void evaluatedStateUsesAcquisitionsAndActualEffects(GameTestHelper helper) {
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(CombatPowerRuntime.captureReady(helper.getLevel().getServer()), "power fence startup"))
                .thenExecute(() -> verifyEvaluatedState(helper)).thenSucceed();
    }
    private static void verifyEvaluatedState(GameTestHelper helper) {
        var previous = CombatPowerPolicyManager.INSTANCE.current();
        List<io.netty.channel.embedded.EmbeddedChannel> channels = new ArrayList<>();
        var player = connect(helper,"V2Owner",channels); var other = connect(helper,"V2Other",channels);
        ResourceLocation borrowed = ResourceLocation.parse("test:borrowed_health");
        try {
            com.google.gson.JsonObject json;
            try (var input = CombatPowerGameTests.class.getResourceAsStream("/data/mythictrpg/mythictrpg/combat_power/default.json")) {
                if (input == null) throw new IllegalStateException("missing v2 fixture policy");
                json = JsonParser.parseString(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (java.io.IOException invalid) { throw new java.io.UncheckedIOException(invalid); }
            var swordRule = JsonParser.parseString("""
                    {"itemId":"minecraft:iron_sword","components":{},"classification":"BOUND_REWARD_GEAR","slot":"weapon",
                     "intrinsicPower":12,"attackDamage":6,"sharpnessLevel":0,"protectionLevel":0,"skills":{}}
                    """).getAsJsonObject();
            json.getAsJsonArray("items").add(swordRule);
            var ignoredRule = JsonParser.parseString("""
                    {"itemId":"minecraft:paper","components":{},"classification":"IGNORE","slot":"none",
                     "intrinsicPower":0,"attackDamage":0,"sharpnessLevel":0,"protectionLevel":0,"skills":{}}
                    """).getAsJsonObject();
            json.getAsJsonArray("items").add(ignoredRule);
            var policy = CombatPowerPolicy.parse("test:v2",json);
            CombatPowerPolicyManager.INSTANCE.apply(new CombatPowerPolicyManager.Loaded(policy,"READY"),null,null);
            var state = RewardPowerState.get(player.server);
            var reward = new ResolvedQuestReward("fixture",List.of(new NpcRewardEntry(ResourceLocation.parse("minecraft:iron_sword"),1),
                    new NpcRewardEntry(ResourceLocation.parse("minecraft:paper"),1)),List.of());
            double promised = CombatPowerRuntime.previewScorer(player,List.of(new NpcRewardEntry(ResourceLocation.parse("minecraft:iron_sword"),1),
                    new NpcRewardEntry(ResourceLocation.parse("minecraft:paper"),1))).applyAsDouble(Map.of());
            helper.assertTrue(state.history(player.getUUID()).orElseThrow().items().isEmpty(),"hypothetical bundle wrote acquisition evidence");
            boolean unknownPromiseRejected = false;
            try { CombatPowerRuntime.previewScorer(player,List.of(new NpcRewardEntry(ResourceLocation.parse("minecraft:diamond_sword"),1))); }
            catch (RuntimeException unavailable) { unknownPromiseRejected = true; }
            helper.assertTrue(unknownPromiseRejected,"unknown promised equipment was silently omitted from candidate score");
            var receipt = RewardClaimService.INSTANCE.issue(player,ResourceLocation.parse("mythictrpg:fortuna"),ResourceLocation.parse("test:v2/"+UUID.randomUUID()),reward);
            helper.assertTrue(receipt.succeeded(),"actual v2 reward failed: "+receipt.reason());
            helper.assertValueEqual(CombatPowerService.INSTANCE.assess(player).actualPower(),promised,"promised bundle preview differed from actual delivery");
            helper.assertValueEqual(CombatPowerRuntime.previewScorer(player,List.of(new NpcRewardEntry(ResourceLocation.parse("minecraft:iron_sword"),1)))
                    .applyAsDouble(Map.of()),promised,"already acquired promised variant was counted twice");
            player.getAttribute(Attributes.MAX_HEALTH).setBaseValue(40);
            var clean = CombatPowerRuntime.captureState(state,policy,player);
            double expected = CombatPowerEvaluator.evaluate(clean,policy.model()).total();
            helper.assertValueEqual(CombatPowerService.INSTANCE.assess(player).actualPower(),expected,"registered reward/ignored counterpart");
            helper.assertTrue(clean.acquiredGear().size()==1,"IGNORE supplied gear stats");
            ItemStack acquired = new ItemStack(Items.IRON_SWORD); acquired.setDamageValue(12);
            helper.assertTrue(CombatPowerRuntime.registeredAcquiredGear(player,acquired).isPresent(),"durability invalidated acquired gear authentication");
            helper.assertTrue(CombatPowerRuntime.registeredAcquiredGear(other,acquired).isEmpty(),"borrowed gear inherited owner's authentication");
            helper.assertTrue(CombatPowerRuntime.registeredAcquiredGear(player,new ItemStack(Items.PAPER)).isEmpty(),"ignored tool authenticated as bound gear");
            player.getInventory().clearContent(); player.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.DIAMOND_SWORD));
            player.getAttribute(Attributes.MAX_HEALTH).addTransientModifier(new AttributeModifier(borrowed,1000,AttributeModifier.Operation.ADD_VALUE));
            helper.assertValueEqual(CombatPowerService.INSTANCE.assess(player).actualPower(),expected,"worn/transient stats changed historical v2 power");
            player.addEffect(new MobEffectInstance(MobEffects.HEALTH_BOOST,200,2));
            player.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST,200,0));
            Map<ResourceLocation,Integer> active = Map.of(ResourceLocation.parse("minecraft:health_boost"),3,ResourceLocation.parse("minecraft:strength"),1);
            var preview = CombatPowerRuntime.previewScorer(player);
            helper.assertValueEqual(preview.applyAsDouble(Map.of()),expected,"preview baseline retained temporary health/strength modifiers");
            double proposed = preview.applyAsDouble(active);
            helper.assertValueEqual(CombatPowerService.INSTANCE.assess(player).actualPower(),proposed,"candidate and real applied state diverged");
            helper.assertValueEqual(CombatPowerRuntime.captureState(state,policy,player).baseMaxHealth(),40.0,"health effect counted twice");
            player.removeAllEffects();
            helper.assertValueEqual(CombatPowerService.INSTANCE.assess(player).actualPower(),expected,"expired active effect retained v2 contribution");
            var restored = RewardPowerState.load(state.save(new CompoundTag(),player.registryAccess()),player.registryAccess());
            helper.assertValueEqual(CombatPowerRuntime.assessInputs(restored,new CombatPowerPolicyManager.Loaded(policy,"READY"),player,Map.of()).actualPower(),expected,"reload changed v2 acquired state");
            CompoundTag components = new CompoundTag(); components.putInt("minecraft:custom_model_data",222);
            helper.assertTrue(RewardExecutionService.grant(player,ResourceLocation.parse("mythictrpg:fortuna"),
                    List.of(new NpcRewardEntry(ResourceLocation.parse("minecraft:iron_sword"),1,components)),RewardGrantPurpose.QUEST).granted(),"unknown variant blocked reward");
            helper.assertValueEqual(CombatPowerService.INSTANCE.assess(player).status(),CombatPowerAssessment.Status.UNAVAILABLE,"unknown reward variant silently scored as registered sword");
            boolean rejected = false;
            try { CombatPowerRuntime.previewScorer(player); } catch (RuntimeException expectedFailure) { rejected = true; }
            helper.assertTrue(rejected,"adaptive preview guessed missing gear history");
        } finally {
            player.getAttribute(Attributes.MAX_HEALTH).removeModifier(borrowed); player.removeAllEffects();
            CombatPowerPolicyManager.INSTANCE.apply(previous,null,null);
            player.server.getPlayerList().remove(player); other.server.getPlayerList().remove(other);
            channels.forEach(io.netty.channel.embedded.EmbeddedChannel::finishAndReleaseAll);
        }
    }
    @GameTest(templateNamespace = "mythictrpg_power", template = "empty", timeoutTicks = 300)
    public static void actualRewardProviderDoesNotFollowWornGear(GameTestHelper helper) {
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(CombatPowerRuntime.captureReady(helper.getLevel().getServer()), "power fence startup"))
                .thenExecute(() -> verifyActualProvider(helper)).thenSucceed();
    }
    private static void verifyActualProvider(GameTestHelper helper) {
        var previous = CombatPowerPolicyManager.INSTANCE.current();
        CombatPowerPolicyManager.INSTANCE.apply(new CombatPowerPolicyManager.Loaded(policy(), "READY"), null, null);
        List<io.netty.channel.embedded.EmbeddedChannel> channels = new ArrayList<>();
        var player = connect(helper, "PowerOwner", channels); var other = connect(helper, "PowerOther", channels);
        var attribute = player.getAttribute(Attributes.ATTACK_DAMAGE);
        ResourceLocation earned = ResourceLocation.parse("test:earned"), worn = ResourceLocation.parse("test:worn");
        try {
            var state = RewardPowerState.get(player.server);
            helper.assertTrue(state.history(player.getUUID()).orElseThrow().complete(), "new login coverage was not established before profile creation");
            var reward = new ResolvedQuestReward("fixture", List.of(new NpcRewardEntry(ResourceLocation.parse("minecraft:iron_sword"), 1)), List.of());
            ResourceLocation source = ResourceLocation.parse("test:power_reward/" + UUID.randomUUID());
            var first = RewardClaimService.INSTANCE.issue(player, ResourceLocation.parse("mythictrpg:fortuna"), source, reward);
            helper.assertTrue(first.succeeded(), "actual claimed reward failed: " + first.reason());
            RewardClaimService.INSTANCE.issue(player, ResourceLocation.parse("mythictrpg:fortuna"), source, reward);
            helper.assertValueEqual(state.history(player.getUUID()).orElseThrow().items().size(), 1, "duplicate reward acquisition evidence");
            helper.assertValueEqual(CombatPowerService.INSTANCE.assess(player).actualPower(), 14.0, "historical reward provider");
            helper.assertValueEqual(CombatPowerService.INSTANCE.assess(other).actualPower(), 2.0, "player history isolation");
            other.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.PAPER, 1));
            RewardExecutionService.grant(other, ResourceLocation.parse("mythictrpg:fortuna"),
                    List.of(new NpcRewardEntry(ResourceLocation.parse("minecraft:paper"), 1)), RewardGrantPurpose.QUEST);
            helper.assertValueEqual(other.getOffhandItem().getCount(), 2, "fixture did not exercise offhand stack merge");
            helper.assertTrue(state.history(other.getUUID()).orElseThrow().complete()
                    && state.history(other.getUUID()).orElseThrow().items().size() == 1, "offhand merge lost acquisition coverage");
            player.getInventory().clearContent(); player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_SWORD));
            attribute.addTransientModifier(new AttributeModifier(worn, 999, AttributeModifier.Operation.ADD_VALUE));
            helper.assertTrue(CombatPowerRuntime.permanentModifiers(player).isEmpty(), "equipment-like transient modifier entered permanent input");
            helper.assertValueEqual(CombatPowerService.INSTANCE.assess(player).actualPower(), 14.0, "borrowed/worn stronger gear altered reward history");
            player.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY); attribute.removeModifier(worn);
            helper.assertValueEqual(CombatPowerService.INSTANCE.assess(player).actualPower(), 14.0, "removing gear manufactured catch-up");
            player.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, 200, 0));
            attribute.addPermanentModifier(new AttributeModifier(earned, 2, AttributeModifier.Operation.ADD_VALUE));
            helper.assertValueEqual(CombatPowerRuntime.permanentModifiers(player),
                    Map.of("minecraft:generic.attack_damage#test:earned#ADD_VALUE", 2.0), "temporary effect modifier mixed into permanent input");
            var adjusted = CombatPowerService.INSTANCE.assess(player);
            helper.assertTrue(adjusted.status() == CombatPowerAssessment.Status.AVAILABLE, "adjusted power unavailable: " + adjusted.evidence());
            helper.assertValueEqual(adjusted.actualPower(), 21.0, "actual active effect and permanent modifier");
            player.removeEffect(MobEffects.DAMAGE_BOOST);
            helper.assertValueEqual(CombatPowerService.INSTANCE.assess(player).actualPower(), 18.0, "expired/removed effect still counted");
            attribute.removeModifier(earned);
            var saved = state.save(new CompoundTag(), player.registryAccess()); var restored = RewardPowerState.load(saved, player.registryAccess());
            helper.assertValueEqual(restored.history(player.getUUID()).orElseThrow().items(), state.history(player.getUUID()).orElseThrow().items(), "variant persistence");
            var revised = JsonParser.parseString(POLICY).getAsJsonObject();
            revised.getAsJsonArray("items").get(0).getAsJsonObject().addProperty("score", 30);
            helper.assertValueEqual(CombatPowerRuntime.assessInputs(restored, new CombatPowerPolicyManager.Loaded(CombatPowerPolicy.parse("test:rescored", revised), "READY"), player, Map.of()).actualPower(), 32.0, "policy reload did not re-score retained reward evidence");
            revised.getAsJsonArray("items").remove(0);
            helper.assertValueEqual(CombatPowerRuntime.assessInputs(restored, new CombatPowerPolicyManager.Loaded(CombatPowerPolicy.parse("test:changed", revised), "READY"), player, Map.of()).status(), CombatPowerAssessment.Status.UNAVAILABLE, "policy change discarded unknown historical strong gear");
            CompoundTag components = new CompoundTag(); components.putInt("minecraft:custom_model_data", 12345);
            var unknown = new NpcRewardEntry(ResourceLocation.parse("minecraft:iron_sword"), 1, components);
            helper.assertTrue(RewardExecutionService.grant(player, ResourceLocation.parse("mythictrpg:fortuna"), List.of(unknown), RewardGrantPurpose.QUEST).granted(), "unknown power variant blocked actual reward");
            helper.assertValueEqual(CombatPowerService.INSTANCE.assess(player).status(), CombatPowerAssessment.Status.UNAVAILABLE, "same item ID with unknown components inherited plain-item power");
            RewardPowerState legacy = new RewardPowerState(); legacy.beginPlayer(player.getUUID(), false);
            helper.assertValueEqual(CombatPowerRuntime.assessInputs(legacy, new CombatPowerPolicyManager.Loaded(policy(), "READY"), player, Map.of()).status(), CombatPowerAssessment.Status.UNAVAILABLE, "legacy obtainedItems/profile inferred complete reward history");
        } finally {
            attribute.removeModifier(earned); attribute.removeModifier(worn); player.removeAllEffects();
            CombatPowerPolicyManager.INSTANCE.apply(previous, null, null);
            player.server.getPlayerList().remove(player); other.server.getPlayerList().remove(other);
            channels.forEach(io.netty.channel.embedded.EmbeddedChannel::finishAndReleaseAll);
        }
    }
    private static net.minecraft.server.level.ServerPlayer connect(GameTestHelper helper, String name,
            List<io.netty.channel.embedded.EmbeddedChannel> channels) {
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(
                new com.mojang.authlib.GameProfile(UUID.randomUUID(), name), false);
        var player = new net.minecraft.server.level.ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        channels.add(new io.netty.channel.embedded.EmbeddedChannel(connection));
        net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(connection);
        player.server.getPlayerList().placeNewPlayer(connection, player, cookie); return player;
    }
    @GameTest(templateNamespace = "mythictrpg_power", template = "empty", timeoutTicks = 300)
    public static void nativePersistenceAndUncleanSessionFence(GameTestHelper helper) {
        try {
            Path root = helper.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("power-fixtures"); Files.createDirectories(root);
            Path directory = Files.createTempDirectory(root, "acquisition-").toAbsolutePath().normalize();
            UUID session = UUID.randomUUID(), player = UUID.randomUUID(); RewardPowerState state = new RewardPowerState();
            state.beginPlayer(player, true); state.record(player, RewardPowerState.identity(new ItemStack(Items.IRON_SWORD), helper.getLevel().registryAccess()));
            state.cleanSession(session); Path file = directory.resolve(RewardPowerState.FILE_NAME + ".dat");
            RewardPowerDurability.write(directory, session, false); state.save(file.toFile(), helper.getLevel().registryAccess());
            AtomicReference<String> result = new AtomicReference<>();
            IOUtilities.withIOWorker(() -> {
                try {
                    var saved = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()).getCompound("data");
                    var restored = RewardPowerState.load(saved, null);
                    if (!restored.ready() || restored.history(player).orElseThrow().items().size() != 1) throw new AssertionError("native save/reload lost evidence");
                    if (RewardPowerDurability.previouslyClean(directory, restored.cleanSession())) throw new AssertionError("unclean accepted");
                    RewardPowerDurability.write(directory, session, true);
                    if (!RewardPowerDurability.previouslyClean(directory, restored.cleanSession())) throw new AssertionError("clean matching NBT rejected");
                    if (RewardPowerDurability.previouslyClean(directory, UUID.randomUUID())) throw new AssertionError("wrong saved session accepted");
                    RewardPowerDurability.write(directory, UUID.randomUUID(), false);
                    if (RewardPowerDurability.previouslyClean(directory, session)) throw new AssertionError("interrupted next session accepted");
                    result.set("OK");
                } catch (Throwable failure) { result.set(failure.toString()); }
            });
            helper.startSequence().thenWaitUntil(() -> helper.assertTrue(result.get() != null, "native save completion pending"))
                    .thenExecute(() -> helper.assertValueEqual(result.get(), "OK", "native persistence/fence")).thenSucceed();
        } catch (Exception failure) { helper.fail("Power persistence fixture failed: " + failure); }
    }
}
