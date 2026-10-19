package com.sande.mythictrpg.raid;

import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.sande.mythictrpg.network.RaidPagePayload;
import com.sande.mythictrpg.network.RaidRequestPayload;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.*;

/** Real server authority and NBT round trip; does not claim graphical client validation. */
@GameTestHolder("mythictrpg_raid_ui")
@PrefixGameTestTemplate(false)
public final class RaidUiGameTests {
    private static final ResourceLocation RAID = ResourceLocation.parse("mythictrpg:ui_fixture");
    private static final ResourceLocation ARENA = ResourceLocation.parse("mythictrpg:ui_fixture_arena");
    private RaidUiGameTests() { }

    @GameTest(templateNamespace = "mythictrpg_raid_ui", template = "empty", timeoutTicks = 100)
    public static void viewGrantsLeavePersistenceAndFrozenRoster(GameTestHelper h) {
        List<EmbeddedChannel> channels = new ArrayList<>();
        ServerPlayer leader = connect(h, "RaidUiLead", channels), member = connect(h, "RaidUiMember", channels), other = connect(h, "RaidUiOther", channels);
        RaidCatalog.Snapshot old = RaidCatalog.INSTANCE.snapshot();
        RaidState state = RaidState.get(leader.server);
        Set<UUID> owned = new HashSet<>();
        try {
            RaidDefinition definition = definition("PUBLIC_WORLD");
            var arena = RaidDefinition.parseArena(ARENA, JsonParser.parseString("{\"schemaVersion\":1,\"dimension\":\"minecraft:overworld\",\"minimum\":[0,60,0],\"maximum\":[10,70,10],\"entry\":[2,62,2],\"bossSpawn\":[5,62,5],\"exit\":[15,62,15]}").getAsJsonObject());
            swap(new RaidCatalog.Snapshot(Map.of(RAID, definition), Map.of(ARENA, arena)));
            RaidUiService ui = RaidUiService.INSTANCE;
            assertViewCodecRoundTrips(h, ui, leader);
            RaidPagePayload create = ui.page(leader, 0, false, "");
            h.assertValueEqual(create.view(), RaidPagePayload.View.RECRUITING, "default page did not select recruiting");
            int createRow = row(create, RaidRequestPayload.Action.CREATE);
            h.assertTrue(createRow >= 0, "authored create missing");
            int baseline = state.attempts().size();
            ui.handle(other, request(create, RaidRequestPayload.Action.CREATE, createRow));
            h.assertValueEqual(state.attempts().size(), baseline, "another player's grant created a raid");
            RaidPagePayload formed = ui.handle(leader, request(create, RaidRequestPayload.Action.CREATE, createRow));
            h.assertValueEqual(formed.view(), RaidPagePayload.View.RECRUITING, "successful create left recruiting view");
            h.assertTrue(formed.rows().stream().allMatch(r -> r.status().equals("FORMING") || r.key().startsWith("d:")), "recruiting mixed in non-forming attempts");
            UUID run = state.membership(leader.getUUID()).orElseThrow().id;
            owned.add(run);
            h.assertValueEqual(state.attempts().size(), baseline + 1, "UI create did not execute");
            ui.handle(leader, request(create, RaidRequestPayload.Action.CREATE, createRow));
            h.assertValueEqual(state.attempts().size(), baseline + 1, "replayed create executed twice");
            h.assertTrue(!RaidRuntime.INSTANCE.leave(leader, run).succeeded(), "leader was silently removed");

            RaidPagePayload staleJoin = ui.page(member, 0, false, "");
            int joinRow = row(staleJoin, RaidRequestPayload.Action.JOIN);
            h.assertTrue(joinRow >= 0, "eligible public lobby absent");
            h.assertTrue(RaidRuntime.INSTANCE.join(other, run).succeeded(), "roster fixture setup failed");
            ui.handle(member, request(staleJoin, RaidRequestPayload.Action.JOIN, joinRow));
            h.assertTrue(state.membership(member.getUUID()).isEmpty(), "stale roster UI joined");
            RaidPagePayload freshJoin = ui.page(member, 0, false, "");
            var joined = ui.handle(member, request(freshJoin, RaidRequestPayload.Action.JOIN, row(freshJoin, RaidRequestPayload.Action.JOIN)));
            h.assertValueEqual(joined.view(), RaidPagePayload.View.RECRUITING, "join changed the current recruiting category");
            h.assertTrue(state.membership(member.getUUID()).isPresent(), "fresh UI join failed");

            RaidPagePayload leave = ui.page(member, 0, false, "");
            int leaveRow = row(leave, RaidRequestPayload.Action.LEAVE);
            h.assertTrue(leaveRow >= 0 && row(leave, RaidRequestPayload.Action.CANCEL) < 0, "nonleader action hints wrong");
            ui.handle(member, request(leave, RaidRequestPayload.Action.CANCEL, leaveRow));
            h.assertValueEqual(state.find(run).orElseThrow().status, RaidState.Status.FORMING, "forged leader action applied");
            leave = ui.page(member, 0, false, ""); leaveRow = row(leave, RaidRequestPayload.Action.LEAVE);
            state.setDirty(false);
            ui.handle(member, request(leave, RaidRequestPayload.Action.LEAVE, leaveRow));
            h.assertTrue(state.isDirty() && state.membership(member.getUUID()).isEmpty(), "FORMING leave not persisted/released");
            var loaded = RaidState.load(state.save(new CompoundTag(), leader.server.registryAccess()), leader.server.registryAccess());
            h.assertTrue(loaded.ready() && loaded.find(run).orElseThrow().playerIds().contains(leader.getUUID())
                    && !loaded.find(run).orElseThrow().playerIds().contains(member.getUUID()), "leave NBT round trip failed");
            var another = RaidRuntime.INSTANCE.create(member, RAID);
            h.assertTrue(another.succeeded(), "departed member remained locked out of new raid");
            owned.add(another.attemptId().orElseThrow());
            h.assertTrue(RaidRuntime.INSTANCE.cancel(member, another.attemptId().orElseThrow()).succeeded(), "new lobby cancel failed");
            h.assertTrue(RaidRuntime.INSTANCE.join(member, run).succeeded(), "departed member could not rejoin");

            RaidPagePayload beforeQueue = ui.page(member, 0, false, "");
            RaidPagePayload start = ui.page(leader, 0, false, "");
            var started = ui.handle(leader, request(start, RaidRequestPayload.Action.START, row(start, RaidRequestPayload.Action.START)));
            h.assertValueEqual(started.view(), RaidPagePayload.View.IN_PROGRESS, "successful start did not select in-progress category");
            h.assertTrue(started.rows().stream().allMatch(r -> !r.key().startsWith("d:") && !r.status().equals("FORMING") && !terminalStatus(r.status())), "in-progress mixed in forming/definitions/history");
            h.assertValueEqual(state.find(run).orElseThrow().status, RaidState.Status.QUEUED, "UI start did not queue");
            ui.handle(member, request(beforeQueue, RaidRequestPayload.Action.LEAVE, row(beforeQueue, RaidRequestPayload.Action.LEAVE)));
            h.assertTrue(state.find(run).orElseThrow().playerIds().contains(member.getUUID()), "stale leave changed frozen roster");
            h.assertTrue(!RaidRuntime.INSTANCE.leave(member, run).succeeded(), "queued command leave was accepted");
            RaidPagePayload queued = ui.page(member, RaidPagePayload.View.IN_PROGRESS, 0, false, "");
            h.assertTrue(row(queued, RaidRequestPayload.Action.LEAVE) < 0 && queued.rows().stream().anyMatch(r -> r.status().equals("QUEUED") && r.details().contains("대기 제한")), "queue state/disabled controls absent");
            var refreshedQueue = ui.handle(member, request(queued, RaidRequestPayload.Action.REFRESH, -1));
            h.assertValueEqual(refreshedQueue.view(), RaidPagePayload.View.IN_PROGRESS, "refresh forgot in-progress grant category");
            h.assertTrue(refreshedQueue.rows().stream().anyMatch(r -> r.key().equals("a:" + run)), "refresh lost the queued attempt");
            state.find(run).orElseThrow().status = RaidState.Status.ACTIVE;
            h.assertTrue(!RaidRuntime.INSTANCE.leave(member, run).succeeded(), "active roster leave was accepted");
            state.find(run).orElseThrow().status = RaidState.Status.QUEUED;
            var cancel = ui.page(leader, RaidPagePayload.View.IN_PROGRESS, 0, false, "");
            var cancelled = ui.handle(leader, request(cancel, RaidRequestPayload.Action.CANCEL, row(cancel, RaidRequestPayload.Action.CANCEL)));
            h.assertTrue(state.find(run).orElseThrow().terminal(), "leader UI cancel did not settle");
            h.assertValueEqual(cancelled.view(), RaidPagePayload.View.HISTORY, "settled cancel did not select history category");
            h.assertTrue(cancelled.rows().stream().allMatch(r -> terminalStatus(r.status()) && r.actions() == 0), "settled history retained executable controls");
            assertCategoriesPaginationAndOldTokens(h, ui, leader, state, definition, owned);

            var privateRun = state.create(leader.getUUID(), leader.getUUID(), definition("PARTY_ISOLATED"), Set.of(leader.getUUID()), leader.server.overworld().getGameTime());
            owned.add(privateRun.id);
            RaidPagePayload hidden = ui.page(other, 0, false, "");
            h.assertTrue(hidden.rows().stream().noneMatch(r -> r.key().equals("a:" + privateRun.id)), "uninvited private lobby leaked");
            privateRun.status = RaidState.Status.QUEUED;
            h.assertTrue(!containsAttempt(ui, other, RaidPagePayload.View.IN_PROGRESS, privateRun.id), "uninvited private progress leaked");
            privateRun.status = RaidState.Status.FORMING;
            h.assertTrue(RaidRuntime.INSTANCE.cancel(leader, privateRun.id).succeeded(), "private fixture did not settle for history check");
            h.assertTrue(!containsAttempt(ui, other, RaidPagePayload.View.HISTORY, privateRun.id), "uninvited private history leaked");
            var close = ui.page(member, 0, false, "");
            h.assertTrue(ui.handle(member, request(close, RaidRequestPayload.Action.CLOSE, -1)) == null, "close generated another page");
            int count = state.attempts().size();
            ui.handle(member, request(close, RaidRequestPayload.Action.CREATE, Math.max(0, row(close, RaidRequestPayload.Action.CREATE))));
            h.assertValueEqual(state.attempts().size(), count, "closed token remained executable");
            h.succeed();
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        finally {
            for (UUID id : owned) RaidRuntime.INSTANCE.cancelAdministrative(leader.server, id);
            try { swap(old); } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
            leader.server.getPlayerList().remove(leader); member.server.getPlayerList().remove(member); other.server.getPlayerList().remove(other);
            channels.forEach(EmbeddedChannel::finishAndReleaseAll);
        }
    }

    private static void assertViewCodecRoundTrips(GameTestHelper h, RaidUiService ui, ServerPlayer player) {
        for (RaidPagePayload.View view : RaidPagePayload.View.values()) {
            RaidPagePayload payload = ui.page(player, view, 0, false, "view codec fixture");
            var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), player.registryAccess());
            try {
                RaidPagePayload.STREAM_CODEC.encode(buffer, payload);
                h.assertValueEqual(RaidPagePayload.STREAM_CODEC.decode(buffer), payload, "raid view payload codec lost " + view);
                h.assertValueEqual(buffer.readableBytes(), 0, "raid view payload left unread bytes " + view);
            } finally { buffer.release(); }
        }
    }

    private static void assertCategoriesPaginationAndOldTokens(GameTestHelper h, RaidUiService ui,
            ServerPlayer leader, RaidState state, RaidDefinition definition, Set<UUID> owned) {
        RaidState.Status[] terminals = {RaidState.Status.SUCCEEDED, RaidState.Status.FAILED, RaidState.Status.CANCELLED};
        for (int i = 0; i < RaidPagePayload.PAGE_SIZE + 1; i++) {
            // Only UI presentation fixtures: gameplay victory/rewards are exercised by RaidGameTests.
            RaidState.Attempt history = state.create(leader.getUUID(), leader.getUUID(), definition,
                    Set.of(leader.getUUID()), leader.server.overworld().getGameTime());
            history.status = terminals[i % terminals.length];
            history.reason = "UI_CATEGORY_FIXTURE";
            owned.add(history.id);
        }
        state.setDirty();
        int before = state.attempts().size();
        RaidPagePayload recruiting = ui.page(leader, 0, false, "");
        int createRow = row(recruiting, RaidRequestPayload.Action.CREATE);
        h.assertTrue(createRow >= 0 && recruiting.page() == 0 && recruiting.pages() == 1,
                "more than six history rows pushed authored recruitment off the first page");
        h.assertTrue(recruiting.rows().stream().allMatch(r -> r.status().equals("FORMING") || r.key().startsWith("d:")),
                "history remained mixed into recruiting pagination");
        RaidPagePayload history = ui.handle(leader, request(recruiting, RaidRequestPayload.Action.SHOW_HISTORY, -1));
        h.assertValueEqual(history.view(), RaidPagePayload.View.HISTORY, "history tab did not change grant category");
        h.assertTrue(history.page() == 0 && history.pages() >= 2, "history was not paginated after category filtering");
        RaidPagePayload rejectedOldCreate = ui.handle(leader, request(recruiting, RaidRequestPayload.Action.CREATE, createRow));
        h.assertValueEqual(state.attempts().size(), before, "old recruiting token executed after changing tabs");
        h.assertValueEqual(rejectedOldCreate.view(), RaidPagePayload.View.HISTORY, "stale recruiting token reset current history view");
        history = rejectedOldCreate;
        Set<String> foundStatuses = new HashSet<>();
        for (int i = 0; i < history.pages(); i++) {
            RaidPagePayload page = ui.page(leader, RaidPagePayload.View.HISTORY, i, false, "");
            h.assertTrue(page.rows().stream().allMatch(r -> terminalStatus(r.status()) && r.actions() == 0),
                    "history page mixed in recruitment/progress or exposed actions");
            page.rows().forEach(r -> foundStatuses.add(r.status()));
        }
        h.assertTrue(foundStatuses.containsAll(Set.of("SUCCEEDED", "FAILED", "CANCELLED")), "history omitted a terminal outcome category");

        history = ui.page(leader, RaidPagePayload.View.HISTORY, 0, false, "");
        var next = ui.handle(leader, request(history, RaidRequestPayload.Action.NEXT, -1));
        h.assertTrue(next.view() == RaidPagePayload.View.HISTORY && next.page() == 1, "next forgot history view/page");
        var refresh = ui.handle(leader, request(next, RaidRequestPayload.Action.REFRESH, -1));
        h.assertTrue(refresh.view() == RaidPagePayload.View.HISTORY && refresh.page() == 1
                && refresh.rows().equals(next.rows()), "refresh changed history category/page/content");
        var previous = ui.handle(leader, request(refresh, RaidRequestPayload.Action.PREVIOUS, -1));
        h.assertTrue(previous.view() == RaidPagePayload.View.HISTORY && previous.page() == 0, "previous forgot history view/page");
        CompoundTag beforeForgedHistoryAction = state.save(new CompoundTag(), leader.registryAccess());
        var forgedCancel = ui.handle(leader, request(previous, RaidRequestPayload.Action.CANCEL, 0));
        h.assertValueEqual(state.attempts().size(), before, "history action changed stored attempts");
        h.assertValueEqual(state.save(new CompoundTag(), leader.registryAccess()), beforeForgedHistoryAction,
                "forged history action changed authoritative persisted state");
        h.assertTrue(owned.stream().map(state::find).map(Optional::orElseThrow).allMatch(RaidState.Attempt::terminal),
                "forged history action changed a terminal attempt");
        var progress = ui.handle(leader, request(forgedCancel, RaidRequestPayload.Action.SHOW_IN_PROGRESS, -1));
        h.assertTrue(progress.view() == RaidPagePayload.View.IN_PROGRESS && progress.page() == 0 && progress.rows().isEmpty(),
                "finished attempts leaked into empty in-progress tab");
        var back = ui.handle(leader, request(progress, RaidRequestPayload.Action.SHOW_RECRUITING, -1));
        h.assertTrue(back.view() == RaidPagePayload.View.RECRUITING && back.page() == 0
                && row(back, RaidRequestPayload.Action.CREATE) >= 0, "return to recruiting did not restore create choices");
    }

    private static boolean terminalStatus(String status) {
        return status.equals("SUCCEEDED") || status.equals("FAILED") || status.equals("CANCELLED");
    }

    private static boolean containsAttempt(RaidUiService ui, ServerPlayer player, RaidPagePayload.View view, UUID id) {
        RaidPagePayload first = ui.page(player, view, 0, false, "");
        for (int page = 0; page < first.pages(); page++) {
            RaidPagePayload current = page == 0 ? first : ui.page(player, view, page, false, "");
            if (current.rows().stream().anyMatch(r -> r.key().equals("a:" + id))) return true;
        }
        return false;
    }
    private static int row(RaidPagePayload p, RaidRequestPayload.Action a) {
        for (int i = 0; i < p.rows().size(); i++) if (p.rows().get(i).allows(a)) return i;
        return -1;
    }
    private static RaidRequestPayload request(RaidPagePayload p, RaidRequestPayload.Action a, int row) { return new RaidRequestPayload(p.token(), a, row); }
    private static RaidDefinition definition(String mode) {
        return RaidDefinition.parse(RAID, JsonParser.parseString("{\"schemaVersion\":1,\"displayName\":\"UI fixture\",\"mode\":\"" + mode + "\",\"arenas\":[\"mythictrpg:ui_fixture_arena\"],\"minimumPlayers\":1,\"maximumPlayers\":4,\"queueTimeoutTicks\":120,\"combatTimeoutTicks\":120,\"livesPerPlayer\":2,\"disconnectGraceTicks\":20,\"boss\":{\"entityType\":\"minecraft:zombie\"},\"phases\":[],\"entryCondition\":{\"type\":\"mythictrpg:always\"},\"rewardEligibility\":\"ALL_FROZEN_ROSTER\",\"rewardGodId\":\"mythictrpg:demeter\",\"rewards\":[]}").getAsJsonObject());
    }
    private static ServerPlayer connect(GameTestHelper h, String name, List<EmbeddedChannel> channels) {
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), name), false);
        var p = new ServerPlayer(h.getLevel().getServer(), h.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        channels.add(new EmbeddedChannel(connection));
        net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(connection);
        p.server.getPlayerList().placeNewPlayer(connection, p, cookie);
        return p;
    }
    private static void swap(RaidCatalog.Snapshot snapshot) throws ReflectiveOperationException {
        var field = RaidCatalog.class.getDeclaredField("snapshot"); field.setAccessible(true); field.set(RaidCatalog.INSTANCE, snapshot);
    }
}
