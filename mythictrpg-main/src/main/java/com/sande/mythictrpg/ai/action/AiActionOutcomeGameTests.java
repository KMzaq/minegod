package com.sande.mythictrpg.ai.action;

import com.sande.mythictrpg.ai.api.RoomConversationEngineRouter;
import com.sande.mythictrpg.ai.api.RoomConversationEngine;
import com.sande.mythictrpg.ai.room.ConversationRoomSnapshot;
import com.sande.mythictrpg.ai.room.RecordingScope;
import com.sande.mythictrpg.ai.room.RoomType;
import com.sande.mythictrpg.ai.server.ConversationRooms;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;

/** Actual room + UI-issued pending token + inventory/effect execution, without a model call. */
@GameTestHolder("mythictrpg_action_outcome")
@PrefixGameTestTemplate(false)
public final class AiActionOutcomeGameTests {
    private static final ResourceLocation GOD = ResourceLocation.parse("mythictrpg:fortuna");
    private static final ResourceLocation ITEM = ResourceLocation.parse("mythictrpg:outcome_gold");
    private static final ResourceLocation BLESSING = ResourceLocation.parse("mythictrpg:outcome_blessing");

    @SuppressWarnings("unchecked")
    @GameTest(templateNamespace = "mythictrpg_action_confirmation", template = "empty")
    public static void confirmationReturnsActualOutcomeOnlyToItsOriginalRoom(GameTestHelper helper) throws Exception {
        var router = RoomConversationEngineRouter.INSTANCE;
        var installed = RoomConversationEngineRouter.class.getDeclaredField("installed"); installed.setAccessible(true);
        boolean previousInstalled = installed.getBoolean(router);
        var templates = AiActionTemplateManager.class.getDeclaredField("templates"); templates.setAccessible(true);
        var previousTemplates = (Map<ResourceLocation, AiActionTemplate>) templates.get(AiActionTemplateManager.INSTANCE);
        var authored = new LinkedHashMap<>(previousTemplates);
        authored.put(ITEM, new ItemRequestTemplate(ITEM, GOD, ResourceLocation.parse("minecraft:gold_ingot"), 1));
        authored.put(BLESSING, new BlessingOfferTemplate(BLESSING, GOD, ResourceLocation.parse("minecraft:luck"), 1200, 0));
        var actor = player(helper, "OutcomeOwner"); var other = player(helper, "OutcomeOther");
        var player = actor.player();
        try {
            installed.setBoolean(router, true); // Transport available; never call the model.
            AiActionTemplateManager.INSTANCE.apply(authored, null, null);
            player.getInventory().add(new ItemStack(Items.GOLD_INGOT, 5));
            var rooms = ConversationRooms.INSTANCE;
            var a = rooms.create(player, RoomType.PRIVATE, List.of(GOD), RecordingScope.STANDARD);
            var b = rooms.create(player, RoomType.PRIVATE, List.of(GOD), RecordingScope.STANDARD);
            rooms.selectPrivate(player, b.roomId());
            var emptyA = capture(player, a);
            var emptyB = capture(player, b);
            helper.assertTrue(rooms.actionOutcomesCurrent(player, emptyA), "empty source-room evidence is current");

            var pending = submit(player, a, "item_request", ITEM);
            equal(helper, pending.status(), AiActionResult.Status.PENDING_CONFIRMATION, "item request pending");
            helper.assertValueEqual(player.getInventory().countItem(Items.GOLD_INGOT), 5, "proposal consumed items before confirmation");
            helper.assertTrue(line(player, a, pending).contains("PENDING_CONFIRMATION"), "original room lacks pending result");
            helper.assertTrue(rooms.actionFeedbackFor(player, b.roomId(), b.revision(), GOD).isEmpty(), "pending leaked to selected room B");
            var pendingA = capture(player, a);
            helper.assertTrue(!rooms.actionOutcomesCurrent(player, emptyA), "new pending outcome did not invalidate older A snapshot");
            helper.assertTrue(rooms.actionOutcomesCurrent(player, emptyB), "room A pending incorrectly invalidated B snapshot");
            helper.assertTrue(pendingA.actionOutcomes().size() == 1
                    && pendingA.actionOutcomes().getFirst().status() == RoomConversationEngine.ActionStatus.PENDING_CONFIRMATION
                    && pendingA.actionOutcomes().getFirst().details().isEmpty(), "pending typed result fabricated execution details");
            helper.assertTrue(!rooms.actionOutcomesCurrent(other.player(), pendingA), "foreign player borrowed source-room result");
            equal(helper, AiActionGateway.confirm(other.player(), pending.proposalId()).status(), AiActionResult.Status.REJECTED, "foreign confirmation rejected");
            helper.assertTrue(!AiActionGateway.cancel(other.player(), pending.proposalId()), "foreign cancellation consumed token");
            var done = AiActionGateway.confirm(player, pending.proposalId());
            equal(helper, done.status(), AiActionResult.Status.EXECUTED, "authored item transfer executed");
            helper.assertTrue(!rooms.actionOutcomesCurrent(player, pendingA), "confirmation during generation did not stale pending snapshot");
            helper.assertTrue(rooms.actionOutcomesCurrent(player, emptyB), "room A confirmation incorrectly invalidated B snapshot");
            var doneA = capture(player, a);
            helper.assertTrue(rooms.actionOutcomesCurrent(player, doneA), "new terminal snapshot is not current");
            var typed = doneA.actionOutcomes().getFirst();
            helper.assertTrue(typed.proposalId().equals(done.proposalId()) && typed.status() == RoomConversationEngine.ActionStatus.EXECUTED
                    && typed.details().get("item_id").equals("minecraft:gold_ingot") && typed.details().get("count").equals("1"),
                    "typed terminal result missing actual receipt identity or committed details");
            helper.assertValueEqual(player.getInventory().countItem(Items.GOLD_INGOT), 4, "actual item consumption absent or repeated");
            var report = line(player, a, done);
            helper.assertTrue(report.contains("status=EXECUTED") && report.contains("item_id=\"minecraft:gold_ingot\"")
                    && report.contains("count=\"1\"") && !report.contains("PENDING_CONFIRMATION"), "terminal did not replace pending with bounded actual details");
            var beforeDuplicate = rooms.actionFeedbackFor(player, a.roomId(), a.revision(), GOD);
            equal(helper, AiActionGateway.confirm(player, done.proposalId()).status(), AiActionResult.Status.REJECTED, "duplicate confirmation accepted");
            helper.assertTrue(rooms.actionOutcomesCurrent(player, doneA), "duplicate confirmation changed typed terminal evidence");
            helper.assertValueEqual(player.getInventory().countItem(Items.GOLD_INGOT), 4, "duplicate confirmation consumed another item");
            helper.assertValueEqual(rooms.actionFeedbackFor(player, a.roomId(), a.revision(), GOD), beforeDuplicate, "duplicate changed authoritative feedback");
            helper.assertTrue(rooms.actionFeedbackFor(player, b.roomId(), b.revision(), GOD).isEmpty(), "execution leaked to B");

            var blessing = submit(player, a, "blessing_offer", BLESSING);
            equal(helper, blessing.status(), AiActionResult.Status.PENDING_CONFIRMATION, "blessing pending");
            helper.assertTrue(AiActionGateway.cancel(player, blessing.proposalId()), "owner could not decline blessing");
            helper.assertTrue(line(player, a, blessing).contains("status=CANCELLED"), "cancellation not returned to A");
            helper.assertTrue(player.getActiveEffects().isEmpty(), "declined blessing applied an effect");
            blessing = submit(player, a, "blessing_offer", BLESSING);
            var blessed = AiActionGateway.confirm(player, blessing.proposalId());
            equal(helper, blessed.status(), AiActionResult.Status.EXECUTED, "confirmed blessing failed");
            helper.assertTrue(!player.getActiveEffects().isEmpty() && line(player, a, blessed).contains("duration_ticks=\"1200\""), "actual effect/result missing");

            // B has an independent limit. A definition replacement must reject, not consume the new quantity.
            var changed = submit(player, b, "item_request", ITEM);
            var beforeBChangeA = capture(player, a);
            var beforeBChangeB = capture(player, b);
            authored.put(ITEM, new ItemRequestTemplate(ITEM, GOD, ResourceLocation.parse("minecraft:gold_ingot"), 2));
            AiActionTemplateManager.INSTANCE.apply(authored, null, null);
            equal(helper, AiActionGateway.confirm(player, changed.proposalId()).status(), AiActionResult.Status.REJECTED, "changed preview terms executed");
            helper.assertValueEqual(player.getInventory().countItem(Items.GOLD_INGOT), 4, "term drift consumed items");
            helper.assertTrue(line(player, b, changed).contains("terms changed"), "live rejection did not reach source B");
            helper.assertTrue(rooms.actionOutcomesCurrent(player, beforeBChangeA), "B result changed A result evidence");
            helper.assertTrue(!rooms.actionOutcomesCurrent(player, beforeBChangeB), "rejected confirmation left pending B evidence current");

            var stale = submit(player, b, "blessing_offer", BLESSING);
            equal(helper, stale.status(), AiActionResult.Status.PENDING_CONFIRMATION, "stale fixture pending");
            var endedB = capture(player, b);
            rooms.leave(player, b, "FIXTURE_END");
            helper.assertTrue(!rooms.actionOutcomesCurrent(player, endedB), "ended generation retained typed result authority");
            var retainedA = rooms.actionFeedbackFor(player, a.roomId(), a.revision(), GOD);
            equal(helper, AiActionGateway.confirm(player, stale.proposalId()).status(), AiActionResult.Status.REJECTED, "ended-room token executed");
            helper.assertValueEqual(rooms.actionFeedbackFor(player, a.roomId(), a.revision(), GOD), retainedA, "ended-room result fell back to A");
            helper.assertTrue(rooms.actionFeedbackFor(player, b.roomId(), b.revision(), GOD).isEmpty(), "removed room kept feedback authority");
            helper.succeed();
        } finally {
            for (var room : ConversationRooms.INSTANCE.memberships(player)) ConversationRooms.INSTANCE.leave(player, room, "FIXTURE_END");
            AiActionGateway.discardPlayer(player.getUUID()); AiActionGateway.discardPlayer(other.player().getUUID());
            AiActionTemplateManager.INSTANCE.apply(previousTemplates, null, null);
            installed.setBoolean(router, previousInstalled);
            actor.close(); other.close();
        }
    }

    private static AiActionResult submit(ServerPlayer p, ConversationRoomSnapshot room, String type, ResourceLocation template) {
        return AiActionGateway.submitRoom(p, room.roomId(), room.revision(), GOD, type, "not mechanics", "not game truth",
                Map.of("template_id", template.toString()), true);
    }
    private static String line(ServerPlayer player, ConversationRoomSnapshot room, AiActionResult result) {
        return ConversationRooms.INSTANCE.actionFeedbackFor(player, room.roomId(), room.revision(), GOD).stream()
                .filter(value -> value.contains(result.proposalId().toString())).findFirst().orElse("");
    }
    private static RoomConversationEngine.Request capture(ServerPlayer player, ConversationRoomSnapshot room) {
        return new RoomConversationEngine.Request(room.roomId(), room.revision(), UUID.randomUUID(), player.getUUID(),
                player.getGameProfile().getName(), List.of(GOD), GOD, "fixture", List.of(), false, false, false,
                List.of(new RoomConversationEngine.GodState(GOD, "R_NEUTRAL", "UNASSESSED", "", null)), false, Set.of(player.getUUID()),
                ConversationRooms.INSTANCE.actionOutcomesFor(player, room.roomId(), room.revision(), GOD));
    }
    private static void equal(GameTestHelper helper, Object actual, Object expected, String message) {
        helper.assertValueEqual(actual, expected, message);
    }
    private static Connected player(GameTestHelper helper, String name) {
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(new com.mojang.authlib.GameProfile(UUID.randomUUID(), name), false);
        var p = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        var channel = new io.netty.channel.embedded.EmbeddedChannel(connection);
        net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(connection);
        p.server.getPlayerList().placeNewPlayer(connection, p, cookie);
        return new Connected(p, channel);
    }
    private record Connected(ServerPlayer player, io.netty.channel.embedded.EmbeddedChannel channel) {
        void close() { player.server.getPlayerList().remove(player); channel.finishAndReleaseAll(); }
    }
}
