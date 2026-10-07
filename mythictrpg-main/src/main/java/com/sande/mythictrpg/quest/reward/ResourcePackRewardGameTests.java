package com.sande.mythictrpg.quest.reward;

import com.google.gson.JsonParser;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Synthetic item data only, never registers a production weapon or resource pack. */
@GameTestHolder("mythictrpg_component_rewards")
@PrefixGameTestTemplate(false)
public final class ResourcePackRewardGameTests {
    private static final String ITEM = """
            {"type":"item","itemId":"minecraft:iron_sword","count":1,"components":{
              "minecraft:custom_model_data":912345,
              "minecraft:attribute_modifiers":{"modifiers":[{
                "type":"minecraft:generic.attack_damage","id":"mythictrpg:test_power",
                "amount":3.5,"operation":"add_value","slot":"mainhand"}]}
            }}
            """;

    private static NpcRewardEntry entry() {
        var json = JsonParser.parseString(ITEM).getAsJsonObject();
        json.getAsJsonObject("components").addProperty("minecraft:custom_name", "{\"text\":\"Fixture sword\"}");
        return (NpcRewardEntry) RewardEntryCodec.parse(json, "fixture");
    }

    @GameTest(templateNamespace = "mythictrpg_component_rewards", template = "empty")
    public static void componentsSurviveGrantSaveAndDuplicateClaim(GameTestHelper helper) {
        try (var fixture = new Connected(helper)) {
        var player = fixture.player;
        var item = entry();
        var exposed = item.components();
        exposed.remove("minecraft:custom_model_data");
        helper.assertTrue(item.components().contains("minecraft:custom_model_data"), "component mutation leaked");
        var parsed = RewardEntryCodec.load(RewardEntryCodec.save(item));
        helper.assertValueEqual(parsed, item, "component reward NBT round trip");
        var stack = item.createStack(player.registryAccess());
        helper.assertValueEqual(stack.get(DataComponents.CUSTOM_MODEL_DATA).value(), 912345, "model data lost");
        helper.assertValueEqual(stack.getHoverName().getString(), "Fixture sword", "name lost");
        helper.assertValueEqual(stack.get(DataComponents.ATTRIBUTE_MODIFIERS).modifiers().size(), 1, "attributes lost");

        var source = id("component_reward/" + UUID.randomUUID());
        var reward = new ResolvedQuestReward("fixture", List.of(item), List.of());
        var result = RewardClaimService.INSTANCE.issue(player, id("fortuna"), source, reward);
        helper.assertTrue(result.succeeded(), "component reward issue failed: " + result.reason());
        var repeated = RewardClaimService.INSTANCE.issue(player, id("fortuna"), source, reward);
        helper.assertTrue(repeated.succeeded(), "repeat did not preserve receipt");
        int count = player.getInventory().items.stream().filter(s -> s.is(Items.IRON_SWORD))
                .mapToInt(s -> s.getCount()).sum();
        helper.assertValueEqual(count, 1, "reward repeated");
        var actual = player.getInventory().items.stream().filter(s -> s.is(Items.IRON_SWORD)).findFirst().orElseThrow();
        helper.assertValueEqual(actual.get(DataComponents.CUSTOM_MODEL_DATA).value(), 912345, "grant stripped model");
        var state = RewardClaimState.get(player.server);
        var stored = state.save(new CompoundTag(), player.registryAccess());
        helper.assertValueEqual(stored.getInt("dataVersion"), RewardClaimState.CURRENT_DATA_VERSION, "downgrade guard missing");
        var restored = RewardClaimState.load(stored, player.registryAccess());
        helper.assertTrue(restored.isWritable(), "reward state could not reload");
        helper.assertValueEqual(restored.findBySource(player.getUUID(), source).orElseThrow().automaticRewards(),
                List.of(item), "claim stripped components");
        helper.succeed();
        }
    }

    @GameTest(templateNamespace = "mythictrpg_component_rewards", template = "empty")
    public static void invalidComponentsCannotConsumeClaimOrQueueOffline(GameTestHelper helper) {
        try (var fixture = new Connected(helper)) {
        var player = fixture.player;
        CompoundTag invalid = new CompoundTag();
        invalid.putString("minecraft:custom_model_data", "not_an_integer");
        var bad = new NpcRewardEntry(id("minecraft", "iron_sword"), 1, invalid);
        var reward = new ResolvedQuestReward("fixture", List.of(bad), List.of());
        var source = id("component_invalid/" + UUID.randomUUID());
        helper.assertFalse(RewardClaimService.INSTANCE.preflight(player, source, reward).succeeded(), "invalid preflight accepted");
        helper.assertFalse(RewardClaimService.INSTANCE.issue(player, id("fortuna"), source, reward).succeeded(), "invalid grant accepted");
        helper.assertFalse(RewardClaimState.get(player.server).findBySource(player.getUUID(), source).isPresent(),
                "invalid item consumed a claim");
        helper.assertFalse(RewardClaimService.INSTANCE.queueBatch(player.server, id("fortuna"), source,
                Map.of(UUID.randomUUID(), reward)).succeeded(), "invalid offline claim queued");
        helper.succeed();
        }
    }

    /** Register the mock NeoForge channels before FTB's player-login listener sends its payloads. */
    private static final class Connected implements AutoCloseable {
        final net.minecraft.server.level.ServerPlayer player;
        final io.netty.channel.embedded.EmbeddedChannel channel;
        Connected(GameTestHelper h) {
            var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(
                    new com.mojang.authlib.GameProfile(UUID.randomUUID(), "RewardFixture"), false);
            player = new net.minecraft.server.level.ServerPlayer(h.getLevel().getServer(), h.getLevel(), cookie.gameProfile(), cookie.clientInformation());
            var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
            channel = new io.netty.channel.embedded.EmbeddedChannel(connection);
            net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(connection);
            player.server.getPlayerList().placeNewPlayer(connection, player, cookie);
        }
        @Override public void close() { player.server.getPlayerList().remove(player); channel.finishAndReleaseAll(); }
    }

    @GameTest(templateNamespace = "mythictrpg_component_rewards", template = "empty")
    public static void pendingChoicesFreezeComponentsAndReadLegacy(GameTestHelper helper) {
        var item = entry();
        UUID player = UUID.randomUUID();
        var source = id("choice_components");
        var claim = new RewardClaim(UUID.randomUUID(), player, id("fortuna"), source, "fixture", List.of(),
                List.of(new RewardChoiceOption(id("a"), "A", List.of(item)),
                        new RewardChoiceOption(id("b"), "B", List.of(new NpcRewardEntry(id("minecraft", "paper"), 1)))),
                true, Optional.empty(), 100);
        var state = new RewardClaimState();
        state.create(claim);
        var data = state.save(new CompoundTag(), helper.getLevel().registryAccess());
        var loaded = RewardClaimState.load(data, helper.getLevel().registryAccess());
        helper.assertValueEqual(loaded.pendingFor(player).getFirst().choices().getFirst().rewards(), List.of(item),
                "pending choice components not frozen");
        var old = new CompoundTag();
        old.putString("type", "item"); old.putString("itemId", "minecraft:paper"); old.putInt("count", 2);
        helper.assertValueEqual(RewardEntryCodec.load(old), new NpcRewardEntry(id("minecraft", "paper"), 2),
                "legacy item codec compatibility failed");
        helper.succeed();
    }

    private static ResourceLocation id(String path) { return id("mythictrpg", path); }
    private static ResourceLocation id(String namespace, String path) {
        return ResourceLocation.fromNamespaceAndPath(namespace, path);
    }
}
