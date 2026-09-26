package com.sande.mythictrpg.ai;

import com.google.gson.Gson;
import com.mojang.authlib.GameProfile;
import com.sande.mythai.response.memory.DerivedSettings;
import com.sande.mythai.response.memory.MemoryIndexSettings;
import com.sande.mythai.response.memory.RoomMemoryBridge;
import com.sande.mythai.response.memory.RoomMemoryStore;
import com.sande.mythictrpg.ai.api.RoomConversationEngine;
import com.sande.mythictrpg.ai.api.RoomConversationEngineRouter;
import com.sande.mythictrpg.ai.api.RoomDialogueEvent;
import com.sande.mythictrpg.ai.intent.ConversationIntent;
import com.sande.mythictrpg.ai.memorycontract.MemoryFoundationSettings;
import com.sande.mythictrpg.ai.room.RecordingScope;
import com.sande.mythictrpg.ai.room.RoomType;
import com.sande.mythictrpg.ai.server.ConversationRooms;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Offline three-module integration. Only the language-model transport is replaced, never its engine/result path. */
@GameTestHolder("mythai_room_full_path")
@PrefixGameTestTemplate(false)
public final class RoomFullPathGameTests {
    private static final String BATCH = "ai_room_live_modules";
    private static final List<ResourceLocation> GODS = List.of(ResourceLocation.parse("mythictrpg:demeter"),
            ResourceLocation.parse("mythictrpg:fortuna"));
    private static final String PRIMARY = "황혼 항구의 등대를 기억해 두자.";
    private static final String REACTION = "그 황혼 항구의 등대라면 나도 그 말을 들었어.";
    private static final String SECRET = "이 폐허의 전말을 아는 신격과 그들이 감추는 사실이 따로 있다.";
    private static Fixture active;

    @GameTest(templateNamespace = "mythai_room_full_path", template = "empty", timeoutTicks = 500, batch = BATCH)
    public static void productionEnginePublishesAndRecallsHeardSpeech(GameTestHelper helper) {
        if (active != null) throw new IllegalStateException("Full-path fixture must run in its own serial batch");
        var fixture = new Fixture(helper);
        active = fixture; // Set before the first mutation: @AfterBatch also covers setup failure/native timeout.
        try {
            fixture.start();
            helper.onEachTick(fixture::tick);
        } catch (Throwable failure) { fixture.fail(failure); }
    }

    @AfterBatch(batch = BATCH)
    public static void restoreAfterFailureOrTimeout(ServerLevel level) {
        if (active != null) active.close();
    }

    private static final class Fixture implements AutoCloseable {
        final GameTestHelper helper;
        final MinecraftServer server;
        final List<ServerPlayer> players = new ArrayList<>();
        final List<EmbeddedChannel> channels = new ArrayList<>();
        final ObservingEngine observer = new ObservingEngine(MythAiRoomConversationEngine.INSTANCE);
        final OfflineLlm llm = new OfflineLlm(observer);
        Field clientField, engineField, installedField, modeField;
        Object previousClient, previousEngine, previousMode;
        boolean previousInstalled, clientChanged, engineChanged, modeChanged, roomsCreated, closed;
        ServerPlayer owner, peer;
        UUID roomId;
        RoomDialogueEvent primary, secondary;
        RoomMemoryStore store;
        CompletableFuture<Boolean> durable;
        CompletableFuture<RoomMemoryBridge.Recall> recall;
        int phase;

        Fixture(GameTestHelper helper) { this.helper = helper; server = helper.getLevel().getServer(); }

        void start() throws Exception {
            for (String id : List.of("mythictrpg", "mythai_ai_response", "mythaiaicontent"))
                check(ModList.get().isLoaded(id), "Required real module is not loaded: " + id);
            check(RoomConversationEngineRouter.INSTANCE.engine() == MythAiRoomConversationEngine.INSTANCE,
                    "Production room engine was not installed by the AI module");
            var config = server.getServerDirectory().resolve("config/mythictrpg");
            check(!MemoryIndexSettings.load(config.resolve("ai-memory-index.json")).enabled()
                    && !DerivedSettings.load(config.resolve("ai-derived-memory.json")).semanticRetrieval(),
                    "Offline fixture requires background semantic-memory models disabled (use an isolated GameTest run)");

            owner = connect("HeardOwner"); peer = connect("HeardPeer");
            owner.setPos(helper.absolutePos(BlockPos.ZERO).getCenter()); peer.setPos(owner.position());
            // First attachment stops the prior room engine. Install the fake only AFTER this normal lifecycle step.
            ConversationRooms.INSTANCE.memberships(owner);
            modeField = field(MemoryFoundationSettings.class, "mode");
            previousMode = modeField.get(null); modeField.set(null, MemoryFoundationSettings.Mode.PERSONAL); modeChanged = true;
            roomId = ConversationRooms.INSTANCE.create(owner, RoomType.PUBLIC_MOBILE, GODS, RecordingScope.TEST_RECORDING).roomId();
            roomsCreated = true;
            clientField = field(MythAiRoomConversationEngine.class, "llm");
            previousClient = clientField.get(MythAiRoomConversationEngine.INSTANCE);
            clientField.set(MythAiRoomConversationEngine.INSTANCE, llm); clientChanged = true;
            engineField = field(RoomConversationEngineRouter.class, "engine");
            installedField = field(RoomConversationEngineRouter.class, "installed");
            previousEngine = engineField.get(RoomConversationEngineRouter.INSTANCE);
            previousInstalled = installedField.getBoolean(RoomConversationEngineRouter.INSTANCE);
            engineField.set(RoomConversationEngineRouter.INSTANCE, observer); engineChanged = true;
            installedField.setBoolean(RoomConversationEngineRouter.INSTANCE, true);

            // The actual registry supplies this definition; the test does not invent a safe reflection stub.
            var registry = Class.forName("com.sande.mythaiaicontent.content.AiContentRegistry");
            var raw = (Optional<?>) registry.getMethod("findLoreDefinition", ResourceLocation.class)
                    .invoke(registry.getField("INSTANCE").get(null), ResourceLocation.parse("mythaiaicontent:dragon_ruin"));
            check(raw.isPresent() && new Gson().toJson(raw.get()).contains(SECRET), "Bundled DIVINE lore fixture was not loaded");
            check(ConversationRooms.INSTANCE.publicText(peer, "mythictrpg:demeter 용의 폐허에 대해 알려줘."),
                    "Actual public input was not routed");
        }

        void tick() {
            if (closed) return;
            try {
                // Fail before native timeout, so even a permanently incomplete callback restores every fixture override.
                check(helper.getTick() < 440, "Full-path async deadline: phase=" + phase + ", requests=" + observer.requests.size()
                        + ", results=" + observer.results + ", published=" + observer.published.size());
                for (var result : observer.results) check(result.failure().isBlank(), "Production engine rejected fixture: " + result.failure());
                if (phase == 0) {
                    var npcs = observer.published.stream().filter(e -> e.role().equals("NPC")).toList();
                    if (npcs.size() < 2 || observer.delivered.size() < 2) return;
                    check(observer.requests.size() == 2 && llm.generations.size() == 2, "Expected exactly one primary and one reaction");
                    check(!observer.requests.getFirst().secondary() && observer.requests.get(1).secondary(), "Sequential reaction roles lost");
                    check(observer.requests.getFirst().speakerGodId().equals(GODS.getFirst())
                            && observer.requests.get(1).speakerGodId().equals(GODS.get(1)), "Game did not preserve actual speaker identity");
                    check(observer.requests.getFirst().audiencePlayerIds().equals(Set.of(owner.getUUID(), peer.getUUID())),
                            "Actual online player audience was not captured");
                    primary = npcs.stream().filter(e -> e.speakerId().equals(GODS.getFirst().toString())).findFirst().orElseThrow();
                    secondary = npcs.stream().filter(e -> e.speakerId().equals(GODS.get(1).toString())).findFirst().orElseThrow();
                    check(primary.text().equals(PRIMARY) && secondary.text().equals(REACTION), "LLM output did not reach actual game publication");
                    for (var event : List.of(primary, secondary)) {
                        check(event.worldId() != null && event.heardGodIds().equals(Set.of(GODS.getFirst().toString(), GODS.get(1).toString()))
                                && event.fullTextReceiverIds().equals(Set.of(owner.getUUID(), peer.getUUID())), "Actual dispatch/listener receipt missing");
                        check(event.evidenceRefs().stream().anyMatch(r -> r.kind().equals(RoomKnowledgeContext.EVIDENCE_KIND)),
                                "Audience-safe static content provenance was not attached");
                        check(observer.observed.stream().anyMatch(e -> e.messageId().equals(event.messageId())), "Actual observed callback missing");
                    }
                    check(secondary.sourceMessageIds().contains(primary.messageId()), "Reaction lost actual primary history ancestry");
                    check(observer.requests.get(1).history().stream().anyMatch(h -> primary.messageId().equals(h.messageId()) && PRIMARY.equals(h.text())),
                            "Reaction did not receive the published primary message");
                    for (int i = 0; i < 2; i++) {
                        var request = observer.requests.get(i);
                        var content = RoomKnowledgeContext.load(request); // Actual audienceContentFor reflection/conversion, not test replacement.
                        check(!content.profile().identity().isBlank(), "Real registry profile missing after audience reflection");
                        String prompt = llm.generations.get(i).stream().map(AiDialogueModels.OllamaMessage::content)
                                .collect(java.util.stream.Collectors.joining("\n"));
                        check(prompt.contains(content.profile().identity()), "Real safe profile did not reach the LLM prompt");
                        check(!prompt.contains(SECRET) && !new Gson().toJson(content).contains(SECRET), "Non-public DIVINE lore entered public prompt");
                        String direction = request.speakerGodId() + " -> " + GODS.get(1 - i);
                        check(prompt.contains("STATIC_DIRECTIONAL_GOD_RELATIONS") && prompt.replace("\\", "").replace("u003e", ">").contains(direction),
                                "Per-speaker authored relationship direction missing: " + direction);
                    }
                    store = actualStore(server);
                    // Real writer force/fence, never blocking the game thread and never faking a memory backend.
                    durable = CompletableFuture.supplyAsync(() -> {
                        try { return store.awaitIdle(Duration.ofSeconds(5)); }
                        catch (Exception failure) { throw new java.util.concurrent.CompletionException(failure); }
                    });
                    phase = 1;
                } else if (phase == 1 && durable.isDone()) {
                    check(durable.join() && store.ready() && store.capacity().usedBytes() > 0, "Heard memory writer did not commit: " + store.failureReason());
                    for (var event : List.of(primary, secondary)) {
                        var row = store.record(event.messageId()).orElseThrow(() -> new AssertionError("Actual published speech not persisted"));
                        check(row.text().equals(event.text()) && row.heardGodIds().equals(event.heardGodIds())
                                && row.fullPlayerAudience().equals(event.fullTextReceiverIds()) && row.evidenceRefs().equals(event.evidenceRefs())
                                && row.sourceMessageIds().equals(event.sourceMessageIds()), "Durable row changed original content/provenance");
                    }
                    var room = ConversationRooms.INSTANCE.resolveMember(owner, roomId.toString()).orElseThrow();
                    var state = observer.requests.get(1).speakerState();
                    // Peer asked the original question; OWNER now asks Fortuna to recall Demeter, with no history shortcut.
                    var query = new RoomConversationEngine.Request(roomId, room.revision(), UUID.randomUUID(), owner.getUUID(),
                            owner.getGameProfile().getName(), GODS, GODS.get(1), "다른 신이 황혼 항구를 뭐라고 했지?", List.of(), true, true, true,
                            List.of(new RoomConversationEngine.GodState(GODS.get(1), state.relationshipTier(), state.emotionTag(), "",
                                    ConversationRooms.INSTANCE.memoryContext(owner, room, GODS.get(1)))), false,
                            Set.of(owner.getUUID(), peer.getUUID()));
                    recall = RoomMemoryBridge.recall(owner, query);
                    phase = 2;
                } else if (phase == 2 && recall.isDone()) {
                    var answer = recall.join();
                    check(answer.sourceMessageIds().contains(primary.messageId()) && !answer.sourceMessageIds().contains(secondary.messageId())
                            && answer.context().contains(PRIMARY) && answer.context().contains(GODS.getFirst().toString())
                            && answer.context().contains("NPC_UTTERANCE"), "Cross-player, other-God actual recall failed: " + answer);
                    check(llm.generations.size() == 2 && llm.unexpectedCalls == 0, "Recall unexpectedly requested model generation");
                    close(); helper.succeed();
                }
            } catch (Throwable failure) { fail(failure); }
        }

        ServerPlayer connect(String name) {
            var cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), name), false);
            var player = new ServerPlayer(server, helper.getLevel(), cookie.gameProfile(), cookie.clientInformation());
            var connection = new Connection(PacketFlow.SERVERBOUND);
            channels.add(new EmbeddedChannel(connection));
            NetworkRegistry.configureMockConnection(connection);
            players.add(player); // Also clean up a partially failed placeNewPlayer.
            server.getPlayerList().placeNewPlayer(connection, player, cookie);
            return player;
        }

        void fail(Throwable failure) {
            try { close(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
            helper.fail("Offline production room pipeline: " + failure);
        }

        @Override public void close() {
            if (closed) return;
            closed = true;
            Throwable failure = null;
            try { if (roomsCreated) ConversationRooms.INSTANCE.clear(); } catch (Throwable error) { failure = error; }
            for (var player : players) try { server.getPlayerList().remove(player); }
                catch (Throwable error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
            for (var channel : channels) try { channel.finishAndReleaseAll(); }
                catch (Throwable error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
            try { if (clientChanged) clientField.set(MythAiRoomConversationEngine.INSTANCE, previousClient); }
                catch (Throwable error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
            try { if (engineChanged) {
                engineField.set(RoomConversationEngineRouter.INSTANCE, previousEngine);
                installedField.setBoolean(RoomConversationEngineRouter.INSTANCE, previousInstalled);
            } } catch (Throwable error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
            try { if (modeChanged) modeField.set(null, previousMode); }
                catch (Throwable error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
            finally { active = null; }
            if (failure != null) throw new IllegalStateException("Full-path fixture cleanup failed", failure);
        }
    }

    /** Observation only: every production operation, callback, guard and failure is delegated unchanged. */
    private static final class ObservingEngine implements RoomConversationEngine {
        final RoomConversationEngine delegate;
        final List<Request> requests = new ArrayList<>();
        final List<Result> results = new ArrayList<>(), delivered = new ArrayList<>();
        final List<RoomDialogueEvent> observed = new ArrayList<>(), published = new ArrayList<>();
        Request current;
        ObservingEngine(RoomConversationEngine delegate) { this.delegate = delegate; }
        public CompletableFuture<Result> respond(Request request) {
            requests.add(request); current = request;
            return delegate.respond(request).whenComplete((result, failure) -> { if (result != null) results.add(result); });
        }
        public CompletableFuture<SplitResult> chooseSplit(SplitRequest request) { return delegate.chooseSplit(request); }
        public void invalidate(UUID room) { delegate.invalidate(room); }
        public void delivered(Request request, Result result) { delegate.delivered(request, result); delivered.add(result); }
        public RoomDialogueEvent preparePublication(RoomDialogueEvent event) { return delegate.preparePublication(event); }
        public void dialogueObserved(RoomDialogueEvent event) { delegate.dialogueObserved(event); observed.add(event); }
        public void dialoguePublished(RoomDialogueEvent event) { delegate.dialoguePublished(event); published.add(event); }
        public void stop() { delegate.stop(); }
    }

    /** No socket/client creation or fallback to Ollama exists in this fake transport. */
    private static final class OfflineLlm implements LocalLlmClient {
        final ObservingEngine observer;
        final List<List<AiDialogueModels.OllamaMessage>> generations = new ArrayList<>();
        int unexpectedCalls;
        boolean closed;
        OfflineLlm(ObservingEngine observer) { this.observer = observer; }
        public LocalLlmRequestScheduler.ScheduledRequest<AiDialogueModels.StructuredAiResult> submit(UUID id,
                List<AiDialogueModels.OllamaMessage> messages, AiDialogueConfig.Settings settings) {
            if (closed || observer.current == null || generations.size() >= 2) {
                unexpectedCalls++; throw new IllegalStateException("Unexpected generation in offline fixture");
            }
            generations.add(List.copyOf(messages));
            var request = observer.current;
            var speech = new AiDialogueModels.Speech(request.speakerGodId().toString(), request.secondary() ? REACTION : PRIMARY, List.of());
            return completed(id, new AiDialogueModels.StructuredAiResult(List.of(speech), "황혼 항구", List.of()));
        }
        public LocalLlmRequestScheduler.ScheduledRequest<ConversationIntent> submitIntent(UUID id,
                List<AiDialogueModels.OllamaMessage> messages, AiDialogueConfig.Settings settings) {
            if (closed) { unexpectedCalls++; throw new IllegalStateException("Classifier used after fixture close"); }
            return completed(id, new ConversationIntent(Set.of(), Set.of("용", "폐허"), 100, ConversationIntent.Source.LOCAL_LLM));
        }
        public void close() { closed = true; }
    }

    private static <T> LocalLlmRequestScheduler.ScheduledRequest<T> completed(UUID id, T value) {
        return new LocalLlmRequestScheduler.ScheduledRequest<>(id, Instant.now(), CompletableFuture.completedFuture(
                new LocalLlmRequestScheduler.ScheduledResult<>(value, Duration.ZERO, Duration.ZERO)));
    }
    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        var field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
    @SuppressWarnings("unchecked")
    private static RoomMemoryStore actualStore(MinecraftServer server) throws ReflectiveOperationException {
        var stores = (Map<MinecraftServer, EnumMap<MemoryFoundationSettings.Mode, RoomMemoryStore>>) field(RoomMemoryBridge.class, "STORES").get(null);
        return Objects.requireNonNull(stores.get(server).get(MemoryFoundationSettings.Mode.PERSONAL), "Production heard-memory store missing");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
