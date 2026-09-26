package com.sande.mythictrpg.ai.memorycontract;

import com.mojang.authlib.GameProfile;
import com.sande.mythictrpg.ai.server.AiConversationRuntimeService;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/** Focused game-thread authority regression; the dedicated run has no AI/LLM mod or real server world. */
@GameTestHolder("mythictrpg_dialogue_test")
@PrefixGameTestTemplate(false)
public final class DialogueTestScopeGameTests {
    private static final List<ResourceLocation> GODS = List.of(ResourceLocation.parse("mythictrpg:demeter"),
            ResourceLocation.parse("mythictrpg:fortuna"), ResourceLocation.parse("mythictrpg:lubras"));

    @GameTest(templateNamespace="minecraft", template="bastion/mobs/empty")
    public static void testLeasesSeparateReadableMemoryFromRecordingAndGameActions(GameTestHelper helper) {
        MemoryFoundationSettings.withModeForTest(MemoryFoundationSettings.Mode.PERSONAL, () -> {
            ServerPlayer player = connectedPlayer(helper, "DialogueOff");
            ServerPlayer other = connectedPlayer(helper, "DialogueOn");
            var runtime = AiConversationRuntimeService.INSTANCE;
            try {
                helper.assertTrue(runtime.beginTestConversation(player,
                        List.of(ResourceLocation.parse("mythictrpg:missing_test_god")), false).isEmpty(), "unknown game God accepted");
                UUID lease = runtime.beginTestConversation(player, GODS, false).orElseThrow();
                UUID otherLease = runtime.beginTestConversation(other, GODS, true).orElseThrow();
                var firstContext = runtime.memoryContext(player, GODS.getFirst()).orElseThrow();
                var thirdContext = runtime.memoryContext(player, GODS.get(2)).orElseThrow();
                var otherContext = runtime.memoryContext(other, GODS.get(2)).orElseThrow();
                helper.assertValueEqual(runtime.conversationGods(player), GODS, "all three approved Gods present");
                helper.assertTrue(!firstContext.equals(thirdContext), "God memory contexts collapsed");
                for (ResourceLocation god : GODS) {
                    var offContext = runtime.memoryContext(player, god).orElseThrow();
                    var onContext = runtime.memoryContext(other, god).orElseThrow();
                    helper.assertTrue(runtime.memoryContextCurrent(player, offContext), "off lost readable memories");
                    helper.assertTrue(!runtime.recordingAllowed(player, offContext), "off permitted a write");
                    helper.assertTrue(runtime.recordingAllowed(other, onContext), "concurrent on God cannot record");
                    helper.assertTrue(!runtime.recordingAllowed(player, onContext)
                            && !runtime.recordingAllowed(other, offContext), "cross-player recording scope leaked");
                }
                helper.assertTrue(runtime.currentActionScope(player).isEmpty()
                        && runtime.currentActionScope(other).isEmpty(), "test granted uncommitted game action scope");
                helper.assertTrue(!runtime.joinConversation(other, player), "test became shared production conversation");
                helper.assertTrue(runtime.beginTestConversation(player, GODS, true).isEmpty(), "start overwrote active lease");
                runtime.endTestConversation(player, UUID.randomUUID());
                helper.assertTrue(runtime.memoryContextCurrent(player, firstContext), "stale close affected current lease");
                runtime.endTestConversation(player, lease);
                runtime.endTestConversation(player, lease);
                helper.assertTrue(runtime.testConversationId(player).isEmpty()
                        && !runtime.memoryContextCurrent(player, firstContext), "closed lease stayed valid");
                helper.assertTrue(runtime.recordingAllowed(other, otherContext), "closing off damaged concurrent on session");
                UUID next = runtime.beginTestConversation(player, GODS, true).orElseThrow();
                helper.assertTrue(runtime.recordingAllowed(player, runtime.memoryContext(player, GODS.get(2)).orElseThrow()),
                        "replacement on third God cannot record");
                helper.assertTrue(!runtime.recordingAllowed(player, thirdContext), "old off context revived as on");
                runtime.endTestConversation(player, lease);
                helper.assertValueEqual(runtime.testConversationId(player).orElseThrow(), next, "old stop closed replacement");
                runtime.endTestConversation(player, next);
                runtime.endTestConversation(other, otherLease);
                MemoryFoundationSettings.withModeForTest(MemoryFoundationSettings.Mode.OFF, () -> {
                    helper.assertTrue(runtime.beginTestConversation(player, GODS, true).isEmpty(), "global OFF silently accepted recording on");
                    UUID off = runtime.beginTestConversation(player, GODS, false).orElseThrow();
                    helper.assertTrue(runtime.memoryContext(player).isEmpty(), "global OFF exposed memories");
                    runtime.endTestConversation(player, off);
                });
            } finally {
                for (ServerPlayer cleanup : List.of(player, other)) {
                    runtime.onPlayerLoggedOut(new PlayerEvent.PlayerLoggedOutEvent(cleanup));
                    if (cleanup.server.getPlayerList().getPlayer(cleanup.getUUID()) == cleanup)
                        cleanup.server.getPlayerList().remove(cleanup);
                }
            }
        });
        helper.succeed();
    }

    private static ServerPlayer connectedPlayer(GameTestHelper helper, String name) {
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), name), false);
        var player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(connection);
        player.server.getPlayerList().placeNewPlayer(connection, player, cookie);
        return player;
    }
}
