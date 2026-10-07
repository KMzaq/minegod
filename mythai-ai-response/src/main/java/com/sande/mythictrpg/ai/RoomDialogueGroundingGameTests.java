package com.sande.mythictrpg.ai;

import com.mojang.authlib.GameProfile;
import com.sande.mythai.response.memory.DerivedSettings;
import com.sande.mythai.response.memory.MemoryIndexSettings;
import com.sande.mythictrpg.ai.api.RoomConversationEngine;
import com.sande.mythictrpg.ai.api.RoomConversationEngineRouter;
import com.sande.mythictrpg.ai.api.RoomDialogueEvent;
import com.sande.mythictrpg.ai.example.DialogueExampleTag;
import com.sande.mythictrpg.ai.intent.ConversationIntent;
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
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Real room/engine/publication path with a socket-free, manually completed generation/review transport. */
@GameTestHolder("mythai_dialogue_guard")
@PrefixGameTestTemplate(false)
public final class RoomDialogueGroundingGameTests {
    private static final String BATCH = "room_dialogue_grounding_runtime";
    private static final ResourceLocation GOD = ResourceLocation.parse("mythictrpg:demeter");
    private static final String BAD = "보상을 네 손에 지급했어.";
    private static final String SAFE = "보상은 아직 지급되지 않았어. 먼저 네 부탁을 들어 보지.";
    private static Fixture active;

    @GameTest(templateNamespace = "mythai_dialogue_guard", template = "empty", timeoutTicks = 1000, batch = BATCH)
    public static void reviewRepairFailureAndStalePublicationAreIsolated(GameTestHelper helper) {
        if (active != null) throw new IllegalStateException("Grounding fixture requires an isolated serial batch");
        var fixture = new Fixture(helper); active = fixture;
        try { fixture.start(); helper.onEachTick(fixture::tick); }
        catch (Throwable failure) { fixture.fail(failure); }
    }

    @AfterBatch(batch = BATCH)
    public static void restoreAfterFailureOrTimeout(ServerLevel level) { if (active != null) active.close(); }

    private static final class Fixture implements AutoCloseable {
        final GameTestHelper helper;
        final MinecraftServer server;
        final Observer observer = new Observer();
        final ScriptedLlm llm = new ScriptedLlm(observer);
        Field clientField, engineField, installedField;
        Object previousClient, previousEngine;
        boolean previousInstalled, clientChanged, engineChanged, roomsCreated, closed;
        ServerPlayer player;
        EmbeddedChannel channel;
        UUID roomId;
        int scenario, phase, initialPublished, initialResults, initialDelivered;

        Fixture(GameTestHelper helper) { this.helper = helper; server = helper.getLevel().getServer(); }

        void start() throws Exception {
            check(RoomConversationEngineRouter.INSTANCE.engine() == MythAiRoomConversationEngine.INSTANCE,
                    "Real AI engine was not installed");
            var config = server.getServerDirectory().resolve("config/mythictrpg");
            check(!MemoryIndexSettings.load(config.resolve("ai-memory-index.json")).enabled()
                    && !DerivedSettings.load(config.resolve("ai-derived-memory.json")).semanticRetrieval(),
                    "Background model workers must be OFF in this isolated fixture");
            var cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "GroundingProbe"), false);
            player = new ServerPlayer(server, helper.getLevel(), cookie.gameProfile(), cookie.clientInformation());
            var connection = new Connection(PacketFlow.SERVERBOUND);
            channel = new EmbeddedChannel(connection);
            NetworkRegistry.configureMockConnection(connection);
            server.getPlayerList().placeNewPlayer(connection, player, cookie);
            player.setPos(helper.absolutePos(BlockPos.ZERO).getCenter());
            // First attach invokes the real old engine's stop; install the fake transport afterwards.
            ConversationRooms.INSTANCE.memberships(player);
            clientField = field(MythAiRoomConversationEngine.class, "llm");
            previousClient = clientField.get(MythAiRoomConversationEngine.INSTANCE);
            clientField.set(MythAiRoomConversationEngine.INSTANCE, llm); clientChanged = true;
            engineField = field(RoomConversationEngineRouter.class, "engine");
            installedField = field(RoomConversationEngineRouter.class, "installed");
            previousEngine = engineField.get(RoomConversationEngineRouter.INSTANCE);
            previousInstalled = installedField.getBoolean(RoomConversationEngineRouter.INSTANCE);
            engineField.set(RoomConversationEngineRouter.INSTANCE, observer); engineChanged = true;
            installedField.setBoolean(RoomConversationEngineRouter.INSTANCE, true);
            beginScenario();
        }

        void beginScenario() {
            phase = 0;
            llm.begin(scenario);
            initialPublished = observer.npcPublished.size();
            initialResults = observer.results.size();
            initialDelivered = observer.delivered;
            roomId = ConversationRooms.INSTANCE.create(player, RoomType.PUBLIC_MOBILE, List.of(GOD),
                    RecordingScope.TEST_EPHEMERAL).roomId();
            roomsCreated = true;
            check(ConversationRooms.INSTANCE.publicText(player, GOD + " 보상에 대해 물어볼게."),
                    "Actual public player text was not routed");
        }

        void tick() {
            if (closed) return;
            try {
                check(helper.getTick() < 900, "Guard async deadline: scenario=" + scenario + ", phase=" + phase
                        + ", generation=" + llm.generations + ", review=" + llm.reviews.size());
                check(observer.npcPublished.stream().noneMatch(e -> e.text().equals(BAD)),
                        "Rejected draft escaped into actual Minecraft publication");
                if (phase == 0 && !llm.reviews.isEmpty()) {
                    assertPending();
                    check(llm.generations == 1, "Initial draft requested an unexpected generation");
                    if (scenario == 2) llm.failReview(0);
                    else if (scenario == 3) llm.completeReview(0, revise("THIS_QUOTE_IS_NOT_IN_THE_DRAFT"));
                    else if (scenario == 4) {
                        // Actual game lifecycle invalidation, not a test-only token replacement.
                        var room = ConversationRooms.INSTANCE.resolveMember(player, roomId.toString()).orElseThrow();
                        ConversationRooms.INSTANCE.leave(player, room, "TEST_PLAYER_LEFT_WHILE_REVIEWING");
                        check(ConversationRooms.INSTANCE.resolveMember(player, roomId.toString()).isEmpty(),
                                "Actual stale-room setup did not remove player membership");
                        llm.completeReview(0, RoomDialogueGrounding.Review.accepted());
                    } else llm.completeReview(0, revise(BAD));
                    phase = (scenario <= 1) ? 1 : 2;
                } else if (phase == 1 && llm.reviews.size() == 2) {
                    assertPending();
                    check(llm.generations == 2, "REVISE must cause exactly one new generation");
                    check(llm.generationMessages.get(1).stream().anyMatch(m -> m.content().contains(BAD)),
                            "Repair did not receive the rejected draft as context");
                    llm.completeReview(1, scenario == 0 ? RoomDialogueGrounding.Review.accepted() : revise(BAD));
                    phase = 2;
                } else if (phase == 2 && observer.results.size() > initialResults) {
                    var result = observer.results.getLast();
                    if (scenario == 0) {
                        if (observer.delivered == initialDelivered) return;
                        check(result.failure().isBlank() && result.speech().size() == 1
                                && result.speech().getFirst().text().equals(SAFE), "Repaired reply was not accepted intact");
                        check(observer.npcPublished.size() == initialPublished + 1
                                && observer.npcPublished.getLast().text().equals(SAFE)
                                && observer.npcPublished.getLast().fullTextReceiverIds().contains(player.getUUID()),
                                "Exactly the reviewed repair must reach actual game publication");
                    } else {
                        check(!result.failure().isBlank() && result.speech().isEmpty()
                                && result.controls().isEmpty() && result.proposalsJson().equals("[]"),
                                "Rejected/error/stale review produced a deliverable reply or action");
                        check(observer.npcPublished.size() == initialPublished && observer.delivered == initialDelivered,
                                "Rejected/error/stale review published speech or committed emotion/delivery");
                    }
                    check(llm.generations == (scenario <= 1 ? 2 : 1)
                            && llm.reviews.size() == (scenario <= 1 ? 2 : 1), "Repair/review exceeded the bounded attempt count");
                    if (scenario != 4) {
                        var room = ConversationRooms.INSTANCE.resolveMember(player, roomId.toString()).orElseThrow();
                        // The rejected draft contains conversation_leave, so surviving membership also proves
                        // its room-control proposal never executed before review or survived a rejected draft.
                        ConversationRooms.INSTANCE.leave(player, room, "NEXT_GROUNDING_SCENARIO");
                    }
                    if (++scenario == 5) {
                        close();
                        com.sande.mythictrpg.MythicTrpg.LOGGER.info("Dialogue grounding runtime: 5 scenarios passed (repair, repeated rejection, transport failure, invalid quote, stale room)");
                        helper.succeed();
                    } else beginScenario();
                }
            } catch (Throwable failure) { fail(failure); }
        }

        void assertPending() {
            check(observer.npcPublished.size() == initialPublished && observer.results.size() == initialResults
                    && observer.delivered == initialDelivered, "Draft was exposed before its review completed");
            check(ConversationRooms.INSTANCE.resolveMember(player, roomId.toString()).isPresent(),
                    "Unreviewed conversation_leave proposal executed");
        }

        void fail(Throwable failure) {
            try { close(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
            helper.fail("Production dialogue grounding scenario=" + scenario + ": " + failure);
        }

        @Override public void close() {
            if (closed) return; closed = true;
            Throwable failure = null;
            try { if (roomsCreated) ConversationRooms.INSTANCE.clear(); } catch (Throwable e) { failure = e; }
            try { if (player != null) server.getPlayerList().remove(player); } catch (Throwable e) { failure = combine(failure, e); }
            try { if (channel != null) channel.finishAndReleaseAll(); } catch (Throwable e) { failure = combine(failure, e); }
            try { if (clientChanged) clientField.set(MythAiRoomConversationEngine.INSTANCE, previousClient); }
            catch (Throwable e) { failure = combine(failure, e); }
            try { if (engineChanged) {
                engineField.set(RoomConversationEngineRouter.INSTANCE, previousEngine);
                installedField.setBoolean(RoomConversationEngineRouter.INSTANCE, previousInstalled);
            } } catch (Throwable e) { failure = combine(failure, e); }
            llm.close();
            if (active == this) active = null;
            if (failure != null) throw new IllegalStateException("Grounding fixture cleanup failed", failure);
        }
    }

    /** Only observes; production preparePublication/delivery/evidence hooks remain unchanged. */
    private static final class Observer implements RoomConversationEngine {
        final RoomConversationEngine delegate = MythAiRoomConversationEngine.INSTANCE;
        final List<Result> results = new ArrayList<>();
        final List<RoomDialogueEvent> npcPublished = new ArrayList<>();
        Request current;
        int delivered;
        public CompletableFuture<Result> respond(Request request) {
            current = request;
            return delegate.respond(request).whenComplete((result, failure) -> { if (result != null) results.add(result); });
        }
        public CompletableFuture<SplitResult> chooseSplit(SplitRequest request) { return delegate.chooseSplit(request); }
        public void invalidate(UUID roomId) { delegate.invalidate(roomId); }
        public void delivered(Request request, Result result) { delegate.delivered(request, result); delivered++; }
        public RoomDialogueEvent preparePublication(RoomDialogueEvent event) { return delegate.preparePublication(event); }
        public void dialogueObserved(RoomDialogueEvent event) {
            delegate.dialogueObserved(event);
            // TEST_EPHEMERAL intentionally suppresses the persistence callback, but actual chat/HUD
            // dispatch receipts still arrive here. Do not enable recording merely to observe delivery.
            if (event.role().equals("NPC")) npcPublished.add(event);
        }
        public void dialoguePublished(RoomDialogueEvent event) { delegate.dialoguePublished(event); }
        public void stop() { delegate.stop(); }
    }

    private static final class ScriptedLlm implements LocalLlmClient {
        final Observer observer;
        final List<CompletableFuture<LocalLlmRequestScheduler.ScheduledResult<RoomDialogueGrounding.Review>>> reviews = new ArrayList<>();
        final List<List<AiDialogueModels.OllamaMessage>> generationMessages = new ArrayList<>();
        int scenario, generations;
        boolean closed;
        ScriptedLlm(Observer observer) { this.observer = observer; }
        void begin(int scenario) {
            check(reviews.stream().allMatch(CompletableFuture::isDone), "A previous review is still pending");
            this.scenario = scenario; generations = 0; reviews.clear(); generationMessages.clear();
        }
        public LocalLlmRequestScheduler.ScheduledRequest<AiDialogueModels.StructuredAiResult> submit(UUID id,
                List<AiDialogueModels.OllamaMessage> messages, AiDialogueConfig.Settings settings) {
            check(!closed && observer.current != null && generations < 2, "Unexpected or excess generation in guard fixture");
            generationMessages.add(List.copyOf(messages));
            generations++;
            boolean repaired = scenario == 0 && generations == 2;
            var proposal = new AiDialogueModels.Proposal("conversation_leave", "Unreviewed control", "leave", List.of(), Map.of());
            var draft = new AiDialogueModels.StructuredAiResult(List.of(new AiDialogueModels.Speech(GOD.toString(),
                    repaired ? SAFE : BAD, List.of())), "reward", repaired ? List.of() : List.of(proposal), "test-only feeling");
            return completed(id, draft);
        }
        public LocalLlmRequestScheduler.ScheduledRequest<ConversationIntent> submitIntent(UUID id,
                List<AiDialogueModels.OllamaMessage> messages, AiDialogueConfig.Settings settings) {
            check(!closed, "Classifier used after fixture close");
            return completed(id, new ConversationIntent(Set.of(DialogueExampleTag.S_REWARD_NEGOTIATION), Set.of("보상"), 90,
                    ConversationIntent.Source.LOCAL_LLM));
        }
        public LocalLlmRequestScheduler.ScheduledRequest<RoomDialogueGrounding.Review> submitReview(UUID id,
                List<AiDialogueModels.OllamaMessage> messages, AiDialogueConfig.Settings settings) {
            check(!closed && reviews.size() < 2, "Unexpected or excess review in guard fixture");
            var future = new CompletableFuture<LocalLlmRequestScheduler.ScheduledResult<RoomDialogueGrounding.Review>>();
            reviews.add(future);
            return new LocalLlmRequestScheduler.ScheduledRequest<>(id, Instant.now(), future);
        }
        void completeReview(int index, RoomDialogueGrounding.Review review) {
            reviews.get(index).complete(new LocalLlmRequestScheduler.ScheduledResult<>(review, Duration.ZERO, Duration.ZERO));
        }
        void failReview(int index) { reviews.get(index).completeExceptionally(new IllegalStateException("OFFLINE_REVIEW_FAILURE")); }
        public void close() { closed = true; for (var future : reviews) if (!future.isDone()) future.cancel(false); }
    }

    private static RoomDialogueGrounding.Review revise(String excerpt) {
        return new RoomDialogueGrounding.Review(false, List.of(new RoomDialogueGrounding.Issue(
                RoomDialogueGrounding.Code.UNEXECUTED_ACTION, excerpt, "No supplied execution result confirms a completed reward.")));
    }
    private static <T> LocalLlmRequestScheduler.ScheduledRequest<T> completed(UUID id, T value) {
        return new LocalLlmRequestScheduler.ScheduledRequest<>(id, Instant.now(), CompletableFuture.completedFuture(
                new LocalLlmRequestScheduler.ScheduledResult<>(value, Duration.ZERO, Duration.ZERO)));
    }
    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        var field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static Throwable combine(Throwable prior, Throwable next) {
        if (prior == null) return next; prior.addSuppressed(next); return prior;
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
