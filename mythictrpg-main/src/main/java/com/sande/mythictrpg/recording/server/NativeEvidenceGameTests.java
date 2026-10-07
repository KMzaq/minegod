package com.sande.mythictrpg.recording.server;

import com.sande.mythictrpg.ai.api.*;
import com.sande.mythictrpg.ai.api.RoomConversationEngine.*;
import com.sande.mythictrpg.ai.memorycontract.MemoryFoundationSettings;
import com.sande.mythictrpg.ai.room.*;
import com.sande.mythictrpg.ai.server.ConversationRooms;
import com.sande.mythictrpg.recording.api.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Actual runtime proof lifecycle; receipt withdrawal is covered by separate SQLite fixtures, not privileged here.
 * No real model, external network or operating world. */
@GameTestHolder("mythictrpg_native_evidence")
@PrefixGameTestTemplate(false)
public final class NativeEvidenceGameTests {
    private static final String BATCH = "native_evidence_runtime";
    private static final String MARKER = "native_seal_runtime_original";
    private static Fixture active;
    private NativeEvidenceGameTests() { }

    @GameTest(templateNamespace = "mythictrpg_native_evidence", template = "empty", timeoutTicks = 20000, batch = BATCH)
    public static void actualSealSurvivesNewRequestButNotClosedRoom(GameTestHelper helper) {
        if (active != null) throw new IllegalStateException("Native evidence fixture requires an isolated serial batch");
        active = new Fixture(helper);
        helper.onEachTick(active::tick);
    }
    @AfterBatch(batch = BATCH)
    public static void restoreAfterTimeout(ServerLevel level) { if (active != null) active.close(); }

    private static final class Fixture implements AutoCloseable {
        final GameTestHelper helper;
        final MinecraftServer server;
        final HoldingEngine engine = new HoldingEngine();
        ServerPlayer player;
        io.netty.channel.embedded.EmbeddedChannel channel;
        RoomConversationEngine original;
        boolean originallyInstalled, closed;
        WorldRecordingService store;
        ConversationRoomSnapshot room;
        Request first, later;
        MemoryReadSession access, latest;
        MemoryReadSession.Page page;
        NativeMemorySeal seal;
        List<RoomEvidenceReference> refs;
        CompletableFuture<MemoryReadSession.Page> reading;
        CompletableFuture<Optional<NativeMemorySeal>> sealing;
        CompletableFuture<Boolean> preparing;
        long beforeCapture;
        int phase, queryAttempts;

        Fixture(GameTestHelper helper) { this.helper = helper; server = helper.getLevel().getServer(); }
        void tick() {
            if (closed) return;
            java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
            try { advance(); }
            catch (Exception | AssertionError failure) {
                int failedPhase = phase; close(); helper.fail("Native evidence phase " + failedPhase + ": " + failure);
            }
        }
        void advance() throws Exception {
            if (phase == 0) {
                Path world = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
                require(world.toString().replace('\\', '/').contains("/build/"), "Build fixture only");
                store = RecordingRuntime.current(server).orElse(null);
                if (RecordedMemoryAccess.readableDataset(store).isEmpty()) return;
                require(MemoryFoundationSettings.mode() == MemoryFoundationSettings.Mode.PERSONAL, "PERSONAL fixture required");
                require(!RecordingRuntime.projectionEnabled(server) && !RecordingRuntime.embeddingEnabled(server), "No background model workers");
                require(!RecordingRuntime.retrievalForegroundBlocked(server), "SHADOW fixture, never NEW activation");
                var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(
                        new com.mojang.authlib.GameProfile(UUID.randomUUID(), "NativeSealReader"), false);
                player = new ServerPlayer(server, helper.getLevel(), cookie.gameProfile(), cookie.clientInformation());
                var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
                channel = new io.netty.channel.embedded.EmbeddedChannel(connection);
                net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(connection);
                server.getPlayerList().placeNewPlayer(connection, player, cookie);
                ConversationRooms.INSTANCE.memberships(player);
                original = RoomConversationEngineRouter.INSTANCE.engine(); originallyInstalled = RoomConversationEngineRouter.INSTANCE.available();
                setEngine(engine, true);
                room = createRoom(); beforeCapture = store.health().highWatermark();
                ConversationRooms.INSTANCE.privateText(player, MARKER);
                require(engine.requests.size() == 1, "First real request issued");
                first = engine.requests.getFirst();
                require(ConversationRooms.INSTANCE.memoryReadCurrent(server, first), "Issued request current");
                phase = 1;
            } else if (phase == 1) {
                if (store.health().highWatermark() <= beforeCapture) return;
                queryOriginal(first); phase = 2;
            } else if (phase == 2) {
                if (!reading.isDone()) return;
                page = reading.join();
                // A cold bounded SQL scan may expire; each retry is another real game-issued read.
                if (page.entries().isEmpty() && queryAttempts < 8) { queryOriginal(first); return; }
                require(page.entries().size() == 1 && page.entries().getFirst().text().equals(MARKER), "Committed real input found: " + page.status());
                require(engine.publications.stream().anyMatch(e -> e.messageId().equals(page.entries().getFirst().messageId())
                        && e.role().equals("PLAYER") && e.text().equals(MARKER)), "Actual game publication, not fabricated source");
                require(access.current(page), "Issued RAW page current");
                require(!access.current(new MemoryReadSession.Page(page.status(), page.entries(), page.next())), "Cloned RAW page denied");
                sealing = access.seal(List.of(page), List.of()); phase = 3;
            } else if (phase == 3) {
                if (!sealing.isDone()) return;
                seal = sealing.join().orElseThrow(() -> new AssertionError("Actual source seal was not committed"));
                require(access.current(seal), "Issued seal current");
                require(!access.current(NativeMemorySeal.unregistered(seal.reference())), "Copied descriptor cannot mint seal");
                refs = NativeRoomEvidence.references(server, first, access, seal);
                require(refs.size() == 1 && NativeRoomEvidence.current(server, first, refs.getFirst()), "Fresh actual seal registered");
                preparing = NativeRoomEvidence.prepare(server, first, refs); phase = 4;
            } else if (phase == 4) {
                if (!preparing.isDone()) return;
                require(preparing.join() && NativeRoomEvidence.current(server, first, refs.getFirst()), "Same-request persisted prepare/current");
                for (int mutation = 0; mutation < 4; mutation++) {
                    var foreign = forged(first, mutation);
                    require(!NativeRoomEvidence.current(server, foreign, refs.getFirst()), "Foreign request cannot use cached reference");
                    var denied = NativeRoomEvidence.prepare(server, foreign, refs);
                    require(denied.isDone() && !denied.join(), "Foreign request denied before async archive work");
                    boolean rejected = false;
                    try { NativeRoomEvidence.references(server, foreign, access, seal); }
                    catch (IllegalArgumentException expected) { rejected = true; }
                    require(rejected, "Foreign request cannot republish issued seal");
                }
                ConversationRooms.INSTANCE.leave(player, room, "FIXTURE_END_FIRST_ROOM");
                require(!access.current(page) && !access.current(seal) && !NativeRoomEvidence.current(server, first, refs.getFirst()), "Room end revokes fresh page, seal and guard");
                room = createRoom();
                ConversationRooms.INSTANCE.privateText(player, "native_seal_runtime_new_room");
                require(engine.requests.size() == 2, "New room request issued"); later = engine.requests.getLast();
                require(!later.roomId().equals(first.roomId()) && !later.turnId().equals(first.turnId()), "New room and turn, same permitted audience");
                require(!NativeRoomEvidence.current(server, later, refs.getFirst()), "No implicit cache inheritance into new request");
                preparing = NativeRoomEvidence.prepare(server, later, refs); phase = 5;
            } else if (phase == 5) {
                if (!preparing.isDone()) return;
                require(preparing.join() && NativeRoomEvidence.current(server, later, refs.getFirst()), "Persisted proof revalidated after original room ended");
                require(!NativeRoomEvidence.current(server, first, refs.getFirst()), "New guard cannot revive old request");
                latest = RecordedMemoryAccess.open(server, later).orElseThrow();
                reading = latest.query(query(), Optional.empty(), new MemoryReadSession.Budget(2, 4096)); phase = 6;
            } else {
                if (!reading.isDone()) return;
                var replay = reading.join();
                require(replay.entries().size() == 1 && replay.entries().getFirst().messageId().equals(page.entries().getFirst().messageId())
                        && latest.current(replay), "Original still readable in new permitted room");
                ConversationRooms.INSTANCE.leave(player, room, "FIXTURE_END_SECOND_ROOM");
                require(!NativeRoomEvidence.current(server, later, refs.getFirst()) && !latest.current(replay), "Ending new room revokes newly prepared guards too");
                var ended = NativeRoomEvidence.prepare(server, later, refs);
                require(ended.isDone() && !ended.join() && RecordedMemoryAccess.open(server, later).isEmpty(), "Ended request cannot prepare or reopen authority");
                close(); helper.succeed();
            }
        }
        private ConversationRoomSnapshot createRoom() {
            var created = ConversationRooms.INSTANCE.create(player, RoomType.PRIVATE,
                    List.of(ResourceLocation.parse("mythictrpg:demeter")), RecordingScope.TEST_RECORDING);
            ConversationRooms.INSTANCE.selectPrivate(player, created.roomId()); return created;
        }
        private void queryOriginal(Request request) {
            queryAttempts++; access = RecordedMemoryAccess.open(server, request).orElseThrow();
            reading = access.query(query(), Optional.empty(), new MemoryReadSession.Budget(2, 4096));
        }
        @Override public void close() {
            if (closed) return; closed = true;
            try {
                NativeRoomEvidence.clear(server);
                if (original != null) {
                    ConversationRooms.INSTANCE.clear(); setEngine(original, originallyInstalled); original = null;
                }
                engine.stop();
            } finally {
                try {
                    if (player != null && server.getPlayerList().getPlayer(player.getUUID()) == player) server.getPlayerList().remove(player);
                } finally {
                    if (channel != null) channel.finishAndReleaseAll();
                    if (active == this) active = null;
                }
            }
        }
    }
    private static MemoryReadSession.Query query() { return new MemoryReadSession.Query(MARKER, Optional.empty(), Optional.empty()); }
    private static Request forged(Request r, int mutation) {
        return new Request(mutation == 0 ? UUID.randomUUID() : r.roomId(), r.revision(), mutation == 1 ? UUID.randomUUID() : r.turnId(),
                r.playerId(), r.playerName(), r.godIds(), r.speakerGodId(), r.currentText(), r.history(), r.readOnly(), r.recording(),
                mutation == 2 || r.publicRoom(), List.of(new GodState(r.speakerGodId(), r.speakerState().relationshipTier(),
                        r.speakerState().emotionTag(), "fixture", null)), mutation == 3 || r.secondary(), r.audiencePlayerIds());
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
        final List<RoomDialogueEvent> publications = new ArrayList<>();
        public CompletableFuture<Result> respond(Request request) { requests.add(request); var result = new CompletableFuture<Result>(); pending.add(result); return result; }
        public CompletableFuture<SplitResult> chooseSplit(SplitRequest r) { return CompletableFuture.completedFuture(new SplitResult(r.roomId(), r.revision(), r.godId(), "", "", "FIXTURE")); }
        public void dialoguePublished(RoomDialogueEvent event) { publications.add(event); }
        public void stop() { for (int i = 0; i < pending.size(); i++) pending.get(i).complete(Result.failed(requests.get(i), "FIXTURE_END")); }
    }
    private static void require(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
}
