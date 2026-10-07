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
