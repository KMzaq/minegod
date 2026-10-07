package com.sande.mythictrpg.ai.action;

import com.google.gson.JsonParser;
import com.sande.mythictrpg.ai.api.RoomConversationEngineRouter;
import com.sande.mythictrpg.ai.room.RecordingScope;
import com.sande.mythictrpg.ai.room.RoomType;
import com.sande.mythictrpg.ai.server.ConversationRooms;
import com.sande.mythictrpg.raid.*;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.*;

/** Actual room authority + gateway + formation, no model/teleport/raid execution requested. */
@GameTestHolder("mythictrpg_raid_offer")
@PrefixGameTestTemplate(false)
public final class RaidOfferGameTests {
    private static final ResourceLocation GOD = ResourceLocation.parse("mythictrpg:demeter");
    private static final ResourceLocation RAID = ResourceLocation.parse("mythictrpg:offer_fixture");
    private static final String JSON = """
            {"schemaVersion":1,"displayName":"Offer fixture","mode":"PUBLIC_WORLD",
            "arenas":["mythictrpg:fixture_arena"],"minimumPlayers":1,"maximumPlayers":2,
            "queueTimeoutTicks":100,"combatTimeoutTicks":100,"livesPerPlayer":1,"disconnectGraceTicks":20,
            "boss":{"entityType":"minecraft:zombie"},"entryCondition":{"type":"mythictrpg:always"},
            "rewardEligibility":"ALL_FROZEN_ROSTER","offerGodIds":["mythictrpg:demeter"],
            "rewardGodId":"mythictrpg:demeter","rewards":[]}
            """;

    @GameTest(templateNamespace = "mythictrpg_raid_offer", template = "empty")
    public static void confirmationRechecksRoomAndAuthorBeforeFormation(GameTestHelper helper) throws Exception {
        var router = RoomConversationEngineRouter.INSTANCE;
        var installed = RoomConversationEngineRouter.class.getDeclaredField("installed"); installed.setAccessible(true);
        boolean priorInstalled = installed.getBoolean(router);
        var catalogField = RaidCatalog.class.getDeclaredField("snapshot"); catalogField.setAccessible(true);
        var priorCatalog = RaidCatalog.INSTANCE.snapshot();
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(
                new com.mojang.authlib.GameProfile(UUID.randomUUID(), "RaidOffer"), false);
        var player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        var channel = new io.netty.channel.embedded.EmbeddedChannel(connection);
        net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(connection);
        player.server.getPlayerList().placeNewPlayer(connection, player, cookie);
        try {
            installed.setBoolean(router, true); // Enable room transport only; never invoke the unavailable engine.
            var authored = RaidDefinition.parse(RAID, JsonParser.parseString(JSON).getAsJsonObject());
            var arenaId = ResourceLocation.parse("mythictrpg:fixture_arena");
            var arena = RaidDefinition.parseArena(arenaId, JsonParser.parseString("""
                    {"schemaVersion":1,"dimension":"minecraft:overworld",
                    "minimum":[1000,64,1000],"maximum":[1020,84,1020],
                    "entry":[1003,66,1003],"bossSpawn":[1010,66,1010],"exit":[1030,66,1030]}
                    """).getAsJsonObject());
            var available = new RaidCatalog.Snapshot(Map.of(RAID, authored), Map.of(arenaId, arena));
            catalogField.set(RaidCatalog.INSTANCE, available);
            var room = ConversationRooms.INSTANCE.create(player, RoomType.PRIVATE, List.of(GOD), RecordingScope.STANDARD);
            var before = player.position();
            var parameters = Map.of("raid_id", RAID.toString());
            var invalid = AiActionGateway.submitRoom(player, room.roomId(), room.revision(), GOD, "raid_offer", "fixture", "fixture",
                    Map.of("raid_id", RAID.toString(), "start", "true"), false);
            helper.assertValueEqual(invalid.status(), AiActionResult.Status.REJECTED, "arbitrary mechanic parameter accepted");
            var pending = AiActionGateway.submitRoom(player, room.roomId(), room.revision(), GOD, "raid_offer", "fixture", "fixture", parameters, false);
            helper.assertValueEqual(pending.status(), AiActionResult.Status.PENDING_CONFIRMATION, "offer skipped confirmation: " + pending.reason());
            helper.assertTrue(RaidState.get(player.server).membership(player.getUUID()).isEmpty(), "unconfirmed offer created gameplay");
            ConversationRooms.INSTANCE.leave(player, room, "FIXTURE");
            helper.assertValueEqual(AiActionGateway.confirm(player, pending.proposalId()).status(), AiActionResult.Status.REJECTED,
                    "expired room confirmation executed");
            room = ConversationRooms.INSTANCE.create(player, RoomType.PRIVATE, List.of(GOD), RecordingScope.STANDARD);
            pending = AiActionGateway.submitRoom(player, room.roomId(), room.revision(), GOD, "raid_offer", "fixture", "fixture", parameters, false);
            catalogField.set(RaidCatalog.INSTANCE, new RaidCatalog.Snapshot(Map.of(), Map.of()));
            helper.assertValueEqual(AiActionGateway.confirm(player, pending.proposalId()).status(), AiActionResult.Status.REJECTED,
                    "removed authored definition survived confirmation");
            catalogField.set(RaidCatalog.INSTANCE, available);
            pending = AiActionGateway.submitRoom(player, room.roomId(), room.revision(), GOD, "raid_offer", "fixture", "fixture", parameters, false);
            var confirmed = AiActionGateway.confirm(player, pending.proposalId());
            helper.assertValueEqual(confirmed.status(), AiActionResult.Status.EXECUTED, "valid offer was not applied: " + confirmed.reason());
            helper.assertValueEqual(confirmed.details().get("status"), "FORMING", "offer claimed combat started");
            var run = RaidState.get(player.server).membership(player.getUUID()).orElseThrow();
            helper.assertValueEqual(run.status(), RaidState.Status.FORMING, "confirmation started or queued combat");
            helper.assertValueEqual(player.position(), before, "formation teleported player");
            helper.assertValueEqual(AiActionGateway.confirm(player, pending.proposalId()).status(), AiActionResult.Status.REJECTED,
                    "duplicate confirmation executed");
            helper.succeed();
        } finally {
            for (var room : ConversationRooms.INSTANCE.memberships(player)) ConversationRooms.INSTANCE.leave(player, room, "FIXTURE_END");
            RaidState.get(player.server).membership(player.getUUID()).ifPresent(run -> RaidRuntime.INSTANCE.cancel(player, run.id()));
            catalogField.set(RaidCatalog.INSTANCE, priorCatalog); installed.setBoolean(router, priorInstalled);
            player.server.getPlayerList().remove(player); channel.finishAndReleaseAll();
        }
    }
}
