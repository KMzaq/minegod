package com.sande.mythictrpg.ai;

import com.google.gson.JsonParser;
import com.sande.mythai.response.memory.RecordedForegroundMemory;
import com.sande.mythictrpg.ai.api.*;
import com.sande.mythictrpg.ai.api.RoomConversationEngine.*;
import com.sande.mythictrpg.ai.memorycontract.MemoryFoundationSettings;
import com.sande.mythictrpg.ai.room.*;
import com.sande.mythictrpg.ai.server.ConversationRooms;
import com.sande.mythictrpg.recording.api.*;
import com.sande.mythictrpg.recording.server.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/** Three real modules and the real content evidence owner; only generated speech is an offline fixture.
 * Does not modify registry definitions, bypass canceled publication, activate NEW, or call a model. */
@GameTestHolder("mythai_native_content_evidence")
@PrefixGameTestTemplate(false)
public final class NativeContentEvidenceGameTests {
    private static final String BATCH = "native_content_evidence_runtime";
    private static final String VALID = "native_content_allowed_marker";
    private static final String TAMPERED = "native_content_tampered_marker";
    private static final String UNKNOWN = "native_content_unknown_marker";
    private static final String REPLY = "native_content_reply_marker";
    private static Fixture active;
    private NativeContentEvidenceGameTests() { }

    @GameTest(templateNamespace = "mythai_native_content_evidence", template = "empty", timeoutTicks = 20000, batch = BATCH)
    public static void actualContentOwnerGatesPublicationAndNativeSeal(GameTestHelper helper) {
        if (active != null) throw new IllegalStateException("Content evidence fixture requires an isolated serial batch");
        active = new Fixture(helper); helper.onEachTick(active::tick);
    }
    @AfterBatch(batch = BATCH)
    public static void restoreAfterTimeout(ServerLevel level) { if (active != null) active.close(); }

    private static final class Fixture implements AutoCloseable {
        final GameTestHelper helper;
        final MinecraftServer server;
        final OfflineEngine engine = new OfflineEngine();
        ServerPlayer player;
        io.netty.channel.embedded.EmbeddedChannel channel;
        RoomConversationEngine original;
        boolean originallyInstalled, closed;
        WorldRecordingService store;
        ConversationRoomSnapshot room;
        Request readingRequest, laterRequest, publicRequest;
        Request descendantRequest;
        MemoryReadSession access;
        MemoryReadSession descendantAccess;
        MemoryReadSession.Page page;
        NativeMemorySeal seal;
        List<RoomEvidenceReference> references;
        CompletableFuture<MemoryReadSession.Page> reading;
        CompletableFuture<Optional<NativeMemorySeal>> sealing;
        CompletableFuture<Boolean> preparing;
        CompletableFuture<Optional<RecordedForegroundMemory.Prepared>> foregroundPreparing;
        RecordedForegroundMemory.Prepared foreground;
        int phase, readAttempts;
        int descendantAttempts;

        Fixture(GameTestHelper helper) { this.helper = helper; server = helper.getLevel().getServer(); }
        void tick() {
            if (closed) return;
            java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
            try { require(helper.getTick() < 18000, "Bounded content evidence runtime deadline"); advance(); }
            catch (Exception | AssertionError failure) {
                int failedPhase = phase; close(); helper.fail("Native content evidence phase " + failedPhase + ": " + failure);
            }
        }
        void advance() throws Exception {
            if (phase == 0) {
                Path world = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
                require(world.toString().replace('\\', '/').contains("/build/"), "Build fixture only");
                for (String id : List.of("mythictrpg", "mythai_ai_response", "mythaiaicontent"))
                    require(ModList.get().isLoaded(id), "Actual module missing: " + id);
                store = RecordingRuntime.current(server).orElse(null);
                if (store == null || store.health().state() != WorldRecordingService.State.READY) return;
                require("SHADOW".equals(RecordingRuntime.retrievalState(server)), "Explicit SHADOW, never NEW");
                require(MemoryFoundationSettings.mode() == MemoryFoundationSettings.Mode.PERSONAL, "PERSONAL fixture required");
                require(!RecordingRuntime.projectionEnabled(server) && !RecordingRuntime.embeddingEnabled(server), "No model workers");
                var config = server.getServerDirectory().resolve("config/mythictrpg");
                require(!com.sande.mythai.response.memory.MemoryIndexSettings.load(config.resolve("ai-memory-index.json")).enabled()
                        && !com.sande.mythai.response.memory.DerivedSettings.load(config.resolve("ai-derived-memory.json")).semanticRetrieval(),
                        "No legacy background models in offline fixture");
                var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(
                        new com.mojang.authlib.GameProfile(UUID.randomUUID(), "ContentReader"), false);
                player = new ServerPlayer(server, helper.getLevel(), cookie.gameProfile(), cookie.clientInformation());
                var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
                channel = new io.netty.channel.embedded.EmbeddedChannel(connection);
                net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(connection);
                server.getPlayerList().placeNewPlayer(connection, player, cookie);
                ConversationRooms.INSTANCE.memberships(player);
                original = RoomConversationEngineRouter.INSTANCE.engine(); originallyInstalled = RoomConversationEngineRouter.INSTANCE.available();
                require(original == MythAiRoomConversationEngine.INSTANCE, "Actual AI evidence owner must be installed");
                setEngine(engine, true); room = createRoom(RoomType.PRIVATE);
                say("fixture first permitted answer"); phase = 1;
            } else if (phase == 1) {
                if (engine.publications.stream().noneMatch(e -> e.text().equals(VALID)) || currentLastTurn()) return;
                var publication = engine.publications.stream().filter(e -> e.text().equals(VALID)).findFirst().orElseThrow();
                require(publication.role().equals("NPC") && publication.evidenceRefs().equals(List.of(engine.contentReference)),
                        "Actual captured NPC publication contains exact real-owner reference");
                say("fixture attempt changed fingerprint"); phase = 2;
            } else if (phase == 2) {
                if (engine.tamperedRejected != 1 || currentLastTurn()) return;
                say("fixture attempt unsupported owner"); phase = 3;
            } else if (phase == 3) {
                if (engine.unknownRejected != 1 || currentLastTurn()) return;
                require(engine.publications.stream().noneMatch(e -> Set.of(TAMPERED, UNKNOWN).contains(e.text())),
                        "Denied publications never reach capture; no synthetic storage bypass");
                say("네가 " + VALID + " 이야기를 뭐라고 말했지?"); readingRequest = engine.requests.getLast();
                require(ConversationRooms.INSTANCE.memoryReadCurrent(server, readingRequest), "Game-issued read turn is live");
                query(); phase = 4;
            } else if (phase == 4) {
                if (!reading.isDone()) return; page = reading.join();
                if (page.entries().isEmpty() && readAttempts < 8) { query(); return; }
                require(page.entries().size() == 1 && page.entries().getFirst().text().equals(VALID), "Only permitted real NPC source is readable: " + page.status());
                require(access.current(page), "Actual content owner approves issued source now");
                require(engine.currentCalls > 0 && engine.prepareCalls > 0, "Production owner callbacks actually used");
                require(MythAiRoomConversationEngine.INSTANCE.recordedEvidenceCurrent(readingRequest, List.of(engine.contentReference)), "Real owner directly agrees");
                require(!MythAiRoomConversationEngine.INSTANCE.recordedEvidenceCurrent(readingRequest, List.of(tamper(engine.contentReference))), "Real owner rejects changed fingerprint");
                require(!MythAiRoomConversationEngine.INSTANCE.recordedEvidenceCurrent(readingRequest, List.of(unknown())), "Real owner rejects unknown kind");
                sealing = access.seal(List.of(page), List.of()); phase = 5;
            } else if (phase == 5) {
                if (!sealing.isDone()) return;
                seal = sealing.join().orElseThrow(() -> new AssertionError("Content-backed source seal was not issued"));
                require(access.current(seal), "Content-backed issued seal current");
                references = NativeRoomEvidence.references(server, readingRequest, access, seal);
                require(references.size() == 1, "Portable native reference issued");
                preparing = NativeRoomEvidence.prepare(server, readingRequest, references); phase = 6;
            } else if (phase == 6) {
                if (!preparing.isDone()) return;
                require(preparing.join() && NativeRoomEvidence.current(server, readingRequest, references.getFirst()), "Native prepare includes real CONTENT owner");
                foregroundPreparing = RecordedForegroundMemory.prepare(server, readingRequest); phase = 7;
            } else if (phase == 7) {
                if (!foregroundPreparing.isDone()) return;
                foreground = foregroundPreparing.join().orElseThrow(() -> new AssertionError("Actual foreground preparation unavailable"));
                var payload = foreground.payloadFor(server, readingRequest).orElseThrow(() -> new AssertionError("Current prepared payload unavailable"));
                require(payload.text().contains(VALID) && !payload.text().contains(TAMPERED) && !payload.text().contains(UNKNOWN),
                        "Bounded payload contains permitted NPC source only; private text is not printed");
                require(NativeMemoryEvidence.KIND.equals(payload.evidence().kind()), "Prepared payload carries actual native dependency");
                require(foreground.payloadFor(server, foreignTurn(readingRequest)).isEmpty(), "Different request cannot extract prepared text");
                var foreign = foreground.revalidateForPublication(server, foreignTurn(readingRequest));
                require(foreign.isDone() && !foreign.join(), "Different turn cannot refresh publication authority");
                preparing = foreground.revalidateForPublication(server, readingRequest); phase = 8;
            } else if (phase == 8) {
                if (!preparing.isDone()) return;
                require(preparing.join() && foreground.payloadFor(server, readingRequest).isPresent(), "Same live request revalidates original publication proof");
                // Model transport stays fake, but the prepared source goes through actual game publication/capture.
                engine.nativeReference = foreground.payloadFor(server, readingRequest).orElseThrow().evidence();
                engine.pending.getLast().complete(new Result(readingRequest.roomId(), readingRequest.revision(), readingRequest.turnId(),
                        List.of(new Speech(readingRequest.speakerGodId(), REPLY)), "[]", List.of(), ""));
                phase = 81;
            } else if (phase == 81) {
                if (engine.publications.stream().noneMatch(e -> e.text().equals(REPLY)) || currentLastTurn()) return;
                var publication = engine.publications.stream().filter(e -> e.text().equals(REPLY)).findFirst().orElseThrow();
                require(publication.evidenceRefs().contains(engine.nativeReference), "Actual generated fixture speech carries prepared portable native provenance");
                require(!foreground.payloadFor(server, readingRequest).isPresent(), "Completed turn cannot retain foreground text access");
                say("네가 방금 답했던 이야기를 다시 말해 줘"); descendantRequest = engine.requests.getLast();
                queryDescendant(); phase = 82;
            } else if (phase == 82) {
                if (!reading.isDone()) return;
                var descendant = reading.join();
                if (descendant.entries().isEmpty() && descendantAttempts < 8) { queryDescendant(); return; }
                require(descendant.entries().size() == 1 && descendant.entries().getFirst().text().equals(REPLY),
                        "New live turn re-reads actual dependent reply through native source and original CONTENT owner");
                require(descendantAccess.current(descendant), "Descendant page remains authorized at the next turn's use boundary");
                ConversationRooms.INSTANCE.leave(player, room, "FIXTURE_END_ORIGIN");
                require(!access.current(page) && !access.current(seal) && !NativeRoomEvidence.current(server, readingRequest, references.getFirst()), "Origin room authority expires");
                require(foreground.payloadFor(server, readingRequest).isEmpty(), "Ended room cannot extract prepared text");
                var ended = foreground.revalidateForPublication(server, readingRequest);
                require(ended.isDone() && !ended.join(), "Ended room cannot refresh foreground proof");
                room = createRoom(RoomType.PRIVATE); say("fixture same audience new room"); laterRequest = engine.requests.getLast();
                require(!NativeRoomEvidence.current(server, laterRequest, references.getFirst()), "No implicit cross-turn cache inheritance");
                require(foreground.payloadFor(server, laterRequest).isEmpty(), "Same audience in a new room cannot reuse old prepared selection");
                preparing = NativeRoomEvidence.prepare(server, laterRequest, references); phase = 9;
            } else if (phase == 9) {
                if (!preparing.isDone()) return;
                require(preparing.join() && NativeRoomEvidence.current(server, laterRequest, references.getFirst()), "New permitted request revalidates stored CONTENT dependency");
                ConversationRooms.INSTANCE.leave(player, room, "FIXTURE_END_READER");
                room = createRoom(RoomType.PUBLIC_MOBILE);
                require(ConversationRooms.INSTANCE.publicText(player, "fixture public disclosure attempt"), "Actual public turn routed");
                publicRequest = engine.requests.getLast();
                require(publicRequest.publicRoom() && ConversationRooms.INSTANCE.memoryReadCurrent(server, publicRequest), "Actual public request is live");
                preparing = NativeRoomEvidence.prepare(server, publicRequest, references); phase = 10;
            } else {
                if (!preparing.isDone()) return;
                require(!preparing.join() && !NativeRoomEvidence.current(server, publicRequest, references.getFirst()), "Private native source cannot be laundered into public room");
                require(engine.tamperedRejected == 1 && engine.unknownRejected == 1, "Both invalid owner attempts evaluated");
                close(); helper.succeed();
            }
        }
        private ConversationRoomSnapshot createRoom(RoomType type) {
            var created = ConversationRooms.INSTANCE.create(player, type,
                    List.of(ResourceLocation.parse("mythictrpg:demeter")), RecordingScope.TEST_RECORDING);
            if (type == RoomType.PRIVATE) ConversationRooms.INSTANCE.selectPrivate(player, created.roomId());
            return created;
        }
        private void say(String input) { ConversationRooms.INSTANCE.privateText(player, input); }
        private boolean currentLastTurn() {
            return !engine.requests.isEmpty() && ConversationRooms.INSTANCE.memoryReadCurrent(server, engine.requests.getLast());
        }
        private void query() {
            readAttempts++; access = RecordedMemoryAccess.open(server, readingRequest).orElseThrow();
            var actor = new RecordingRecords.ActorRef(RecordingRecords.ActorKind.GOD, readingRequest.speakerGodId().toString());
            var selection = new MemoryReadSession.ActorSelection(Optional.of(RecordingRecords.ActorKind.GOD), Set.of(actor), Set.of());
            reading = access.query(new MemoryReadSession.Query("native_content_", Optional.empty(), Optional.empty(), selection), Optional.empty(), new MemoryReadSession.Budget(4, 8192));
        }
        private void queryDescendant() {
            descendantAttempts++;
            descendantAccess = RecordedMemoryAccess.open(server, descendantRequest).orElseThrow();
            var actor = new RecordingRecords.ActorRef(RecordingRecords.ActorKind.GOD, descendantRequest.speakerGodId().toString());
            var selection = new MemoryReadSession.ActorSelection(Optional.of(RecordingRecords.ActorKind.GOD), Set.of(actor), Set.of());
            reading = descendantAccess.query(new MemoryReadSession.Query(REPLY, Optional.empty(), Optional.empty(), selection), Optional.empty(), new MemoryReadSession.Budget(4, 8192));
        }
        @Override public void close() {
            if (closed) return; closed = true;
            try {
                NativeRoomEvidence.clear(server);
                if (original != null) { ConversationRooms.INSTANCE.clear(); setEngine(original, originallyInstalled); original = null; }
                engine.stop();
            } finally {
                try { if (player != null && server.getPlayerList().getPlayer(player.getUUID()) == player) server.getPlayerList().remove(player); }
                finally { if (channel != null) channel.finishAndReleaseAll(); if (active == this) active = null; }
            }
        }
    }
    private static final class OfflineEngine implements RoomConversationEngine {
        final MythAiRoomConversationEngine owner = MythAiRoomConversationEngine.INSTANCE;
        final List<Request> requests = new ArrayList<>();
        final List<CompletableFuture<Result>> pending = new ArrayList<>();
        final List<RoomDialogueEvent> publications = new ArrayList<>();
        RoomEvidenceReference contentReference, nativeReference;
        int tamperedRejected, unknownRejected, prepareCalls, currentCalls;
        public CompletableFuture<Result> respond(Request request) {
            requests.add(request); var result = new CompletableFuture<Result>(); pending.add(result);
            if (requests.size() <= 3) {
                String text = requests.size() == 1 ? VALID : requests.size() == 2 ? TAMPERED : UNKNOWN;
                result.complete(new Result(request.roomId(), request.revision(), request.turnId(),
                        List.of(new Speech(request.speakerGodId(), text)), "[]", List.of(), ""));
            }
            return result;
        }
        public RoomDialogueEvent preparePublication(RoomDialogueEvent event) {
            if (event.role().equals("NPC") && event.text().equals(REPLY)) {
                var request = requests.stream().filter(r -> event.turnId().filter(r.turnId()::equals).isPresent()).findFirst().orElseThrow();
                require(nativeReference != null && owner.recordedEvidenceCurrent(request, List.of(nativeReference)), "Native generated fixture reply requires real current owner approval before capture");
                return owner.preparePublication(event.withEvidence(List.of(nativeReference), Set.of()));
            }
            if (!event.role().equals("NPC") || !Set.of(VALID, TAMPERED, UNKNOWN).contains(event.text())) return owner.preparePublication(event);
            var request = requests.stream().filter(r -> event.turnId().filter(r.turnId()::equals).isPresent()).findFirst().orElseThrow();
            var actual = RoomKnowledgeContext.evidence(request, RoomKnowledgeContext.load(request));
            var candidate = event.text().equals(TAMPERED) ? tamper(actual) : event.text().equals(UNKNOWN) ? unknown() : actual;
            // The real owner makes the decision BEFORE fan-out/capture; rejected sources are not inserted directly.
            if (!owner.recordedEvidenceCurrent(request, List.of(candidate))) {
                if (event.text().equals(TAMPERED)) tamperedRejected++;
                if (event.text().equals(UNKNOWN)) unknownRejected++;
                throw new IllegalStateException("Expected fixture owner rejection");
            }
            contentReference = candidate;
            return owner.preparePublication(event.withEvidence(List.of(candidate), Set.of()));
        }
        public CompletableFuture<Boolean> prepareRecordedEvidence(Request request, List<RoomEvidenceReference> refs) {
            prepareCalls++; return owner.prepareRecordedEvidence(request, refs);
        }
        public boolean recordedEvidenceCurrent(Request request, List<RoomEvidenceReference> refs) {
            currentCalls++; return owner.recordedEvidenceCurrent(request, refs);
        }
        public void dialoguePublished(RoomDialogueEvent event) { publications.add(event); owner.dialoguePublished(event); }
        public void dialogueObserved(RoomDialogueEvent event) { owner.dialogueObserved(event); }
        public void invalidate(UUID roomId) { owner.invalidate(roomId); }
        public CompletableFuture<SplitResult> chooseSplit(SplitRequest r) { return CompletableFuture.completedFuture(new SplitResult(r.roomId(), r.revision(), r.godId(), "", "", "FIXTURE")); }
        public void stop() { for (int i = 0; i < pending.size(); i++) pending.get(i).complete(Result.failed(requests.get(i), "FIXTURE_END")); owner.stop(); }
    }
    private static RoomEvidenceReference tamper(RoomEvidenceReference reference) {
        var value = JsonParser.parseString(reference.payload()).getAsJsonObject();
        String hash = value.get("sha256").getAsString(); value.addProperty("sha256", (hash.charAt(0) == '0' ? "1" : "0") + hash.substring(1));
        return new RoomEvidenceReference(reference.kind(), value.toString());
    }
    private static RoomEvidenceReference unknown() { return new RoomEvidenceReference("FIXTURE_UNKNOWN_OWNER_V1", "{}"); }
    private static Request foreignTurn(Request request) {
        return new Request(request.roomId(), request.revision(), UUID.randomUUID(), request.playerId(), request.playerName(),
                request.godIds(), request.speakerGodId(), request.currentText(), request.history(), request.readOnly(),
                request.recording(), request.publicRoom(), request.godStates(), request.secondary(), request.audiencePlayerIds());
    }
    private static void setEngine(RoomConversationEngine value, boolean installed) {
        try {
            var field = RoomConversationEngineRouter.class.getDeclaredField("engine"); field.setAccessible(true); field.set(RoomConversationEngineRouter.INSTANCE, value);
            field = RoomConversationEngineRouter.class.getDeclaredField("installed"); field.setAccessible(true); field.set(RoomConversationEngineRouter.INSTANCE, installed);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }
    private static void require(boolean condition, String reason) { if (!condition) throw new AssertionError(reason); }
}
