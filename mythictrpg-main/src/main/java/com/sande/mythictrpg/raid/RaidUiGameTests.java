package com.sande.mythictrpg.raid;

import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.sande.mythictrpg.network.RaidPagePayload;
import com.sande.mythictrpg.network.RaidRequestPayload;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
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
            RaidPagePayload create = ui.page(leader, 0, false, "");
            int createRow = row(create, RaidRequestPayload.Action.CREATE);
            h.assertTrue(createRow >= 0, "authored create missing");
            int baseline = state.attempts().size();
            ui.handle(other, request(create, RaidRequestPayload.Action.CREATE, createRow));
            h.assertValueEqual(state.attempts().size(), baseline, "another player's grant created a raid");
            RaidPagePayload formed = ui.handle(leader, request(create, RaidRequestPayload.Action.CREATE, createRow));
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
            ui.handle(member, request(freshJoin, RaidRequestPayload.Action.JOIN, row(freshJoin, RaidRequestPayload.Action.JOIN)));
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
            ui.handle(leader, request(start, RaidRequestPayload.Action.START, row(start, RaidRequestPayload.Action.START)));
            h.assertValueEqual(state.find(run).orElseThrow().status, RaidState.Status.QUEUED, "UI start did not queue");
            ui.handle(member, request(beforeQueue, RaidRequestPayload.Action.LEAVE, row(beforeQueue, RaidRequestPayload.Action.LEAVE)));
            h.assertTrue(state.find(run).orElseThrow().playerIds().contains(member.getUUID()), "stale leave changed frozen roster");
            h.assertTrue(!RaidRuntime.INSTANCE.leave(member, run).succeeded(), "queued command leave was accepted");
            RaidPagePayload queued = ui.page(member, 0, false, "");
            h.assertTrue(row(queued, RaidRequestPayload.Action.LEAVE) < 0 && queued.rows().stream().anyMatch(r -> r.status().equals("QUEUED") && r.details().contains("대기 제한")), "queue state/disabled controls absent");
            state.find(run).orElseThrow().status = RaidState.Status.ACTIVE;
            h.assertTrue(!RaidRuntime.INSTANCE.leave(member, run).succeeded(), "active roster leave was accepted");
            state.find(run).orElseThrow().status = RaidState.Status.QUEUED;
            var cancel = ui.page(leader, 0, false, "");
            ui.handle(leader, request(cancel, RaidRequestPayload.Action.CANCEL, row(cancel, RaidRequestPayload.Action.CANCEL)));
            h.assertTrue(state.find(run).orElseThrow().terminal(), "leader UI cancel did not settle");

            var privateRun = state.create(leader.getUUID(), leader.getUUID(), definition("PARTY_ISOLATED"), Set.of(leader.getUUID()), leader.server.overworld().getGameTime());
            owned.add(privateRun.id);
            RaidPagePayload hidden = ui.page(other, 0, false, "");
            h.assertTrue(hidden.rows().stream().noneMatch(r -> r.key().equals("a:" + privateRun.id)), "uninvited private lobby leaked");
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
