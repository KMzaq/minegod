package com.sande.mythictrpg.recording.server;

import com.sande.mythictrpg.ai.api.*;
import com.sande.mythictrpg.ai.api.RoomConversationEngine.*;
import com.sande.mythictrpg.ai.room.*;
import com.sande.mythictrpg.ai.server.ConversationRooms;
import com.sande.mythictrpg.recording.api.MemoryReadSession;
import com.sande.mythictrpg.recording.api.InterpretationReadRecords;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.*;
import java.util.*;
import java.util.concurrent.*;

/** Actual room issuance, delivery and SQLite proof. Never calls a model or touches an operating world. */
@GameTestHolder("mythictrpg_recording_read")
@PrefixGameTestTemplate(false)
public final class RecordedRoomAuthorityGameTests {
    private RecordedRoomAuthorityGameTests() { }
    @GameTest(templateNamespace = "mythictrpg_recording_read", template = "empty", timeoutTicks = 20000)
    public static void issuedTurnIsRequiredAndSupersededReadsAreClosed(GameTestHelper helper) {
        var fixture = new Fixture(helper);
        helper.onEachTick(() -> {
            if (fixture.done) return;
            java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
            try { fixture.tick(); }
            catch (Exception | AssertionError failure) { fixture.close(); helper.fail("Recorded room authority: " + failure); }
        });
    }
    private static final class Fixture {
        final GameTestHelper helper;
        final HoldingEngine engine = new HoldingEngine();
        ServerPlayer player;
        io.netty.channel.embedded.EmbeddedChannel channel;
        RoomConversationEngine original;
        boolean originallyInstalled, done;
        ConversationRoomSnapshot room;
        Request first;
        MemoryReadSession access;
        CompletableFuture<MemoryReadSession.Page> pending;
        MemoryReadSession.Page page;
        CompletableFuture<InterpretationReadRecords.Page> pendingInterpretations;
        InterpretationReadRecords.Page interpretations;
        int phase;
        long watermark;
        Fixture(GameTestHelper helper) { this.helper = helper; }
        void tick() throws Exception {
            var server = helper.getLevel().getServer();
            if (phase == 0) {
                var root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().toString().replace('\\', '/');
                require(root.contains("/build/"), "Build fixture only");
                var store = RecordingRuntime.current(server).orElse(null);
                if (RecordedMemoryAccess.readableDataset(store).isEmpty()) return;
                require(com.sande.mythictrpg.ai.memorycontract.MemoryFoundationSettings.mode()
                        == com.sande.mythictrpg.ai.memorycontract.MemoryFoundationSettings.Mode.PERSONAL, "PERSONAL fixture config required");
                var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(
                        new com.mojang.authlib.GameProfile(UUID.randomUUID(), "ArchiveReader"), false);
                player = new ServerPlayer(server, helper.getLevel(), cookie.gameProfile(), cookie.clientInformation());
                var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
                channel = new io.netty.channel.embedded.EmbeddedChannel(connection);
                net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(connection);
                server.getPlayerList().placeNewPlayer(connection, player, cookie);
                ConversationRooms.INSTANCE.memberships(player); // Attach before installing the held fixture engine.
                original = RoomConversationEngineRouter.INSTANCE.engine(); originallyInstalled = RoomConversationEngineRouter.INSTANCE.available();
                setEngine(engine, true);
                room = ConversationRooms.INSTANCE.create(player, RoomType.PRIVATE,
                        List.of(ResourceLocation.parse("mythictrpg:demeter")), RecordingScope.TEST_RECORDING);
                ConversationRooms.INSTANCE.selectPrivate(player, room.roomId());
                watermark = store.health().highWatermark();
                ConversationRooms.INSTANCE.privateText(player, "archive_authority_marker");
                require(engine.requests.size() == 1, "Game did not issue a turn"); first = engine.requests.getFirst();
                require(ConversationRooms.INSTANCE.memoryReadCurrent(server, first), "Actual issued turn rejected");
                phase = 1;
            } else if (phase == 1) {
                var store = RecordingRuntime.current(server).orElseThrow();
                if (store.health().highWatermark() <= watermark) return;
                access = RecordedMemoryAccess.open(server, first).orElseThrow();
                pending = access.query(new MemoryReadSession.Query("archive_authority_marker", Optional.empty(), Optional.empty()),
                        Optional.empty(), new MemoryReadSession.Budget(4, 4096)); phase = 2;
            } else if (phase == 2) {
                if (!pending.isDone()) return; page = pending.join();
                require(page.entries().size() == 1 && page.entries().getFirst().text().equals("archive_authority_marker"), "Actual published input missing: " + page.status());
                require(access.current(page), "Current delivered proof rejected");
                require(!access.current(new MemoryReadSession.Page(page.status(), page.entries(), page.next())), "Fabricated page accepted");
                var semanticQuery = new MemoryReadSession.Query("옛 약속 기억해?", Optional.empty(), Optional.empty());
                var semanticSpace = new com.sande.mythictrpg.recording.api.EmbeddingRecords.ModelSpace("fixture:embedding",
                        "a".repeat(64), 2, com.sande.mythictrpg.recording.api.EmbeddingRecords.ENCODER_VERSION);
                var disabledSemantic = access.semantic(semanticQuery,
                        new com.sande.mythictrpg.recording.api.EmbeddingRecords.QueryVector(semanticSpace,
                                com.sande.mythictrpg.recording.api.RecordingRecords.sha256(semanticQuery.text()), new float[]{1, 0}),
                        Optional.empty(), new MemoryReadSession.Budget(1, 4096));
                require(disabledSemantic.isDone() && disabledSemantic.join().status() == MemoryReadSession.Status.UNAVAILABLE,
                        "Native semantic OFF must not inherit archive SHADOW or legacy model policy");
                require(!access.current(disabledSemantic.join()) && access.current(page), "Disabled semantic page must not invalidate RAW");
                for (int mutation = 0; mutation < 6; mutation++)
                    require(RecordedMemoryAccess.open(server, forged(first, mutation)).isEmpty(), "Forged room authority accepted: " + mutation);
                pendingInterpretations = access.interpretations(page, Optional.empty(), new MemoryReadSession.Budget(4, 4096));
                phase = 3;
            } else if (phase == 3) {
                if (!pendingInterpretations.isDone()) return;
                interpretations = pendingInterpretations.join();
                require(interpretations.status() == MemoryReadSession.Status.PARTIAL && interpretations.entries().isEmpty(),
                        "Projection-OFF archive must return partial empty native interpretations, never fabricated memory");
                require(access.current(interpretations), "Issued native interpretation page rejected");
                require(!access.current(new InterpretationReadRecords.Page(interpretations.status(), interpretations.entries(), interpretations.next())),
                        "Fabricated native interpretation page accepted");
                ConversationRooms.INSTANCE.privateText(player, "archive_new_turn");
                require(engine.requests.size() == 2, "Second turn not issued");
                require(!access.current(page) && RecordedMemoryAccess.open(server, first).isEmpty(), "Superseded turn retained read authority");
                require(!access.current(interpretations), "Superseded turn retained native interpretation authority");
                require(RecordedMemoryAccess.open(server, engine.requests.getLast()).isPresent(), "New current turn rejected");
                pending = access.query(new MemoryReadSession.Query("marker", Optional.empty(), Optional.empty()),
                        page.next(), new MemoryReadSession.Budget(4, 4096)); phase = 4;
            } else {
                if (!pending.isDone()) return;
                require(pending.join().status() == MemoryReadSession.Status.STALE, "Old cursor queried after supersession");
                var last = engine.requests.getLast(); var latest = RecordedMemoryAccess.open(server, last).orElseThrow();
                ConversationRooms.INSTANCE.leave(player, room, "FIXTURE_END");
                require(RecordedMemoryAccess.open(server, last).isEmpty(), "Left room retained read authority");
                require(latest.query(new MemoryReadSession.Query("", Optional.empty(), Optional.empty()), Optional.empty(),
                        new MemoryReadSession.Budget(1, 256)).join().status() == MemoryReadSession.Status.STALE, "Closed room queried");
                close(); helper.succeed();
            }
        }
        void close() {
            done = true;
            if (original != null) { ConversationRooms.INSTANCE.clear(); setEngine(original, originallyInstalled); original = null; }
            if (player != null && player.server.getPlayerList().getPlayer(player.getUUID()) == player) player.server.getPlayerList().remove(player);
            if (channel != null) channel.finishAndReleaseAll();
        }
    }
    private static Request forged(Request r, int mutation) {
        var gods = mutation == 5 ? List.of(r.speakerGodId(), ResourceLocation.parse("mythictrpg:fortuna")) : r.godIds();
        return new Request(mutation == 0 ? UUID.randomUUID() : r.roomId(), mutation == 1 ? r.revision() + 1 : r.revision(),
                mutation == 2 ? UUID.randomUUID() : r.turnId(), r.playerId(), r.playerName(), gods, r.speakerGodId(),
                r.currentText(), r.history(), r.readOnly(), r.recording(), mutation == 3 || r.publicRoom(),
                List.of(new GodState(r.speakerGodId(), r.speakerState().relationshipTier(), r.speakerState().emotionTag(), "fixture", null)),
                mutation == 4 || r.secondary(), r.audiencePlayerIds());
    }
    private static void setEngine(RoomConversationEngine value, boolean installed) {
        try {
            var field = RoomConversationEngineRouter.class.getDeclaredField("engine"); field.setAccessible(true); field.set(RoomConversationEngineRouter.INSTANCE, value);
            field = RoomConversationEngineRouter.class.getDeclaredField("installed"); field.setAccessible(true); field.set(RoomConversationEngineRouter.INSTANCE, installed);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }
    private static final class HoldingEngine implements RoomConversationEngine {
        final List<Request> requests = new ArrayList<>();
        final List<CompletableFuture<Result>> pending = new ArrayList<>();
        public CompletableFuture<Result> respond(Request request) { requests.add(request); var f = new CompletableFuture<Result>(); pending.add(f); return f; }
        public CompletableFuture<SplitResult> chooseSplit(SplitRequest r) { return CompletableFuture.completedFuture(new SplitResult(r.roomId(), r.revision(), r.godId(), "", "", "FIXTURE")); }
        public void stop() { for (int i = 0; i < pending.size(); i++) pending.get(i).complete(Result.failed(requests.get(i), "FIXTURE_END")); }
    }
    private static void require(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
}
