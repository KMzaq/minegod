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
import com.sande.mythictrpg.data.player.PlayerMythDataService;
import com.sande.mythictrpg.godavatar.activity.ActivityRoomExperience;
import com.sande.mythictrpg.godavatar.activity.NpcActivityMemory;
import com.sande.mythictrpg.godavatar.activity.NpcActivityWorldState;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.nbt.CompoundTag;
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
    private static final String PRIMARY_EMOTION = "PRIMARY_EMOTION_ONLY", SECONDARY_EMOTION = "SECONDARY_EMOTION_ONLY";
    private static final List<String> ACTIVITY_KINDS = List.of("READ", "FARM");
    private static final List<String> ACTIVITY_HINTS = List.of("PRIMARY_ACTIVITY_AFFECT_ONLY", "SECONDARY_ACTIVITY_AFFECT_ONLY");
    private static final String PRIVATE_ACTIVITY_SPEECH = "PRIVATE_ACTIVITY_SPEECH_", PRIVATE_ACTIVITY_DETAIL = "PRIVATE_ACTIVITY_DETAIL_";
    private static final String SECRET = "이 폐허의 전말을 아는 신격과 그들이 감추는 사실이 따로 있다.";
    private static final boolean NATIVE_SHADOW = Boolean.getBoolean("mythai.fixture.nativeRetrievalShadow");
    private static Fixture active;

    @GameTest(templateNamespace = "mythai_room_full_path", template = "empty", timeoutTicks = 20000, batch = BATCH)
    public static void productionEnginePublishesAndRecallsHeardSpeech(GameTestHelper helper) {
        if (active != null) throw new IllegalStateException("Full-path fixture must run in its own serial batch");
        var fixture = new Fixture(helper);
        active = fixture; // Set before the first mutation: @AfterBatch also covers setup failure/native timeout.
        try {
            if (NATIVE_SHADOW) helper.onEachTick(fixture::waitForNativeStoreThenTick);
            else { fixture.start(); helper.onEachTick(fixture::tick); }
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
        final ObservingEngine observer = new ObservingEngine(MythAiRoomConversationEngine.INSTANCE, this::beforeResponse);
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
        CompletableFuture<Void> recallGate;
        RoomConversationEngine.Request recallRequest;
        CompoundTag previousActivityData;
        final List<UUID> activityEventIds = new ArrayList<>();
        int phase;
        boolean started;
        final long priorBundles = com.sande.mythai.response.memory.RecordedRetrievalShadow.diagnostics().get("boundedCompleted");

        Fixture(GameTestHelper helper) { this.helper = helper; server = helper.getLevel().getServer(); }

        void waitForNativeStoreThenTick() {
            if (closed) return;
            // GameTest advances synthetic ticks without real-time pacing; let the real SQLite worker run.
            java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
            try {
                if (!started) {
                    check(helper.getTick() < 10000, "Native SHADOW store did not become ready");
                    var nativeStore = com.sande.mythictrpg.recording.server.RecordingRuntime.current(server);
                    if (nativeStore.isEmpty() || nativeStore.get().health().state()
                            != com.sande.mythictrpg.recording.server.WorldRecordingService.State.READY) return;
                    check("SHADOW".equals(com.sande.mythictrpg.recording.server.RecordingRuntime.retrievalState(server)),
                            "Native fixture must explicitly select SHADOW, never NEW");
                    start(); started = true;
                }
                tick();
            } catch (Throwable failure) { fail(failure); }
        }

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
            // Actual game authority -> per-current-player tier -> registry guideline selection.
            // Distinct owners and speakers catch both owner-for-current-player and primary-for-secondary leakage.
            var playerData = PlayerMythDataService.get(server);
            playerData.setAffinity(owner.getUUID(), GODS.getFirst(), -400);
            playerData.setAffinity(peer.getUUID(), GODS.getFirst(), 650);
            playerData.setAffinity(owner.getUUID(), GODS.get(1), 650);
            playerData.setAffinity(peer.getUUID(), GODS.get(1), -650);
            owner.setPos(helper.absolutePos(BlockPos.ZERO).getCenter()); peer.setPos(owner.position());
            // First attachment stops the prior room engine. Install the fake only AFTER this normal lifecycle step.
            ConversationRooms.INSTANCE.memberships(owner);
            modeField = field(MemoryFoundationSettings.class, "mode");
            previousMode = modeField.get(null); modeField.set(null, MemoryFoundationSettings.Mode.PERSONAL); modeChanged = true;
            seedActivityExperience();
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

        void seedActivityExperience() {
            // Only the isolated GameTest world is seeded; preserve its complete real SavedData for cleanup.
            var state = NpcActivityWorldState.get(server);
            check(state.ready(), "Actual activity memory bank is unavailable");
            previousActivityData = state.save(new CompoundTag(), server.registryAccess()).copy();
            var bank = state.activityMemory();
            var godAudience = GODS.stream().map(ResourceLocation::toString).collect(java.util.stream.Collectors.toSet());
            var playerAudience = Set.of(owner.getUUID(), peer.getUUID());
            for (int i = 0; i < GODS.size(); i++) {
                String god = GODS.get(i).toString(), kind = ACTIVITY_KINDS.get(i);
                UUID run = UUID.randomUUID(), actor = UUID.randomUUID(), event = UUID.randomUUID();
                String activity = "mythictrpg:decorative/" + kind.toLowerCase(Locale.ROOT);
                activityEventIds.add(event);
                check(bank.record(new NpcActivityMemory.Event(event, run, god, actor, server.overworld().getGameTime(),
                        activity, kind, "DECORATIVE", "COMPLETED", PRIVATE_ACTIVITY_DETAIL + i, "", "", Set.of(), Set.of())),
                        "Actual lifecycle experience was not recorded for " + god);
                // Interpret only the public lifecycle input, before adding a private speech dependency.
                var view = bank.view(god, godAudience, playerAudience, kind);
                check(bank.applyAffect(view, new NpcActivityMemory.Affect(ACTIVITY_HINTS.get(i), List.of(event)),
                        godAudience, playerAudience), "Actual activity affect was not applied for " + god);
                check(bank.record(new NpcActivityMemory.Event(UUID.randomUUID(), run, god, actor, server.overworld().getGameTime(),
                        activity, kind, "DECORATIVE", "SPEECH", "", god, PRIVATE_ACTIVITY_SPEECH + i,
                        Set.of(god), Set.of(owner.getUUID()))), "Private activity speech was not recorded for " + god);
            }
        }

        CompletableFuture<Void> beforeResponse(RoomConversationEngine.Request request) {
            if (recallGate == null || recall != null) return CompletableFuture.completedFuture(null);
            check(request.playerId().equals(owner.getUUID()) && request.speakerGodId().equals(GODS.get(1))
                    && !request.secondary() && ConversationRooms.INSTANCE.memoryReadCurrent(server, request),
                    "Cross-player recall must use an actual live owner/Fortuna turn");
            // Preserve every issued authority field; omit history only to rule out a history shortcut.
            recallRequest = new RoomConversationEngine.Request(request.roomId(), request.revision(), request.turnId(),
                    request.playerId(), request.playerName(), request.godIds(), request.speakerGodId(), request.currentText(),
                    List.of(), request.readOnly(), request.recording(), request.publicRoom(), request.godStates(),
                    request.secondary(), request.audiencePlayerIds());
            var activityEvidence = primary.evidenceRefs().stream().filter(ref -> ref.kind().equals(ActivityRoomExperience.KIND))
                    .findFirst().orElseThrow();
            var unissued = new RoomConversationEngine.Request(request.roomId(), request.revision(), UUID.randomUUID(),
                    request.playerId(), request.playerName(), request.godIds(), request.speakerGodId(), request.currentText(),
                    List.of(), request.readOnly(), request.recording(), request.publicRoom(), request.godStates(),
                    request.secondary(), request.audiencePlayerIds());
            check(ActivityRoomExperience.current(server, recallRequest, activityEvidence)
                    && !ActivityRoomExperience.current(server, unissued, activityEvidence),
                    "Original activity evidence must require the actual issued recall turn, not caller-created IDs");
            check(RoomMemoryBridge.sourceCurrent(server, recallRequest, primary.messageId()),
                    "Actual recall turn must approve the original publication's complete source evidence");
            recall = RoomMemoryBridge.recall(owner, recallRequest);
            // Keep the real game lease live until the probe is checked, then continue the production engine.
            return recallGate;
        }

        void tick() {
            if (closed) return;
            try {
                // Fail before native timeout, so even a permanently incomplete callback restores every fixture override.
                check(helper.getTick() < (NATIVE_SHADOW ? 18000 : 440), "Full-path async deadline: phase=" + phase + ", requests=" + observer.requests.size()
                        + ", results=" + observer.results.size() + ", published=" + observer.published.size()
                        + ", bundle=" + com.sande.mythai.response.memory.RecordedRetrievalShadow.diagnostics());
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
                        UUID ownActivity = activityEventIds.get(GODS.indexOf(ResourceLocation.parse(event.speakerId())));
                        check(event.evidenceRefs().stream().anyMatch(r -> r.kind().equals(ActivityRoomExperience.KIND)
                                && r.payload().contains(ownActivity.toString())), "Own portable activity provenance was not published");
                        check(observer.observed.stream().anyMatch(e -> e.messageId().equals(event.messageId())), "Actual observed callback missing");
                    }
                    check(secondary.sourceMessageIds().contains(primary.messageId()), "Reaction lost actual primary history ancestry");
                    check(observer.requests.get(1).history().stream().anyMatch(h -> primary.messageId().equals(h.messageId()) && PRIMARY.equals(h.text())),
                            "Reaction did not receive the published primary message");
                    for (int i = 0; i < 2; i++) {
                        var request = observer.requests.get(i);
                        var content = RoomKnowledgeContext.load(request); // Actual audienceContentFor reflection/conversion, not test replacement.
                        String expectedTier = i == 0 ? "R_TRUSTED" : "R_HOSTILE";
                        check(request.playerId().equals(peer.getUUID())
                                && request.speakerState().relationshipTier().equals(expectedTier),
                                "Game affinity did not select this current player's own God tier: " + expectedTier);
                        check(request.speakerState().emotionTag().equals("E_UNASSESSED"),
                                "Absent game emotion was incorrectly asserted as neutral");
                        check(!content.relationshipGuidance().isEmpty(), "Authored relationship guidance missing for " + expectedTier);
                        check(!content.profile().identity().isBlank(), "Real registry profile missing after audience reflection");
                        String prompt = llm.generations.get(i).stream().map(AiDialogueModels.OllamaMessage::content)
                                .collect(java.util.stream.Collectors.joining("\n"));
                        String decodedPrompt = prompt.replace("\\", "");
                        check(prompt.contains("[NPC_ACTIVITY_EXPERIENCE]") && prompt.contains(ACTIVITY_HINTS.get(i))
                                && !prompt.contains(ACTIVITY_HINTS.get(1 - i))
                                && decodedPrompt.contains("\"kind\":\"" + ACTIVITY_KINDS.get(i) + "\"")
                                && !decodedPrompt.contains("\"kind\":\"" + ACTIVITY_KINDS.get(1 - i) + "\"")
                                && decodedPrompt.contains("\"phase\":\"COMPLETED\"")
                                && prompt.contains("PRIOR_INTERPRETATION_NOT_CURRENT_MOOD"),
                                "Actual speaker prompt lost or mixed its own activity experience/affect");
                        check(!prompt.contains(PRIVATE_ACTIVITY_SPEECH) && !prompt.contains(PRIVATE_ACTIVITY_DETAIL),
                                "Private activity speech or unprojected lifecycle detail entered actual LLM input");
                        check(prompt.contains(content.profile().identity()), "Real safe profile did not reach the LLM prompt");
                        check(prompt.contains("GAME_SOCIAL_CONTEXT") && prompt.contains("DIVINE_SOCIAL_JUDGEMENT")
                                && prompt.contains(expectedTier), "Game social projection/shared judgement missing in speaker prompt");
                        for (String guideline : content.relationshipGuidance())
                            check(prompt.contains(guideline), "Selected authored tier guidance did not reach actual LLM boundary");
                        check(!prompt.contains(SECRET) && !new Gson().toJson(content).contains(SECRET), "Non-public DIVINE lore entered public prompt");
                        check(!prompt.contains("SERVER_AUTHORIZED_ACTION_CAPABILITIES"), "Read-only prompt included unusable gameplay capabilities");
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
                    // Peer asked originally; OWNER now opens a real Fortuna turn to recall Demeter.
                    recallGate = new CompletableFuture<>();
                    phase = 2;
                    check(ConversationRooms.INSTANCE.publicText(owner, "mythictrpg:fortuna에게 다른 신이 황혼 항구를 뭐라고 했지?"),
                            "Actual different-player recall input was not routed");
                } else if (phase == 2 && recall != null && recall.isDone()) {
                    var answer = recall.join();
                    check(ConversationRooms.INSTANCE.memoryReadCurrent(server, recallRequest),
                            "Actual owner/Fortuna recall turn expired before verification");
                    check(answer.sourceMessageIds().contains(primary.messageId()) && !answer.sourceMessageIds().contains(secondary.messageId())
                            && answer.context().contains(PRIMARY) && answer.context().contains(GODS.getFirst().toString())
                            && answer.context().contains("NPC_UTTERANCE"), "Cross-player, other-God actual recall failed: " + answer);
                    check(llm.generations.size() == 2 && llm.unexpectedCalls == 0, "Recall unexpectedly requested model generation");
                    phase = 3;
                    recallGate.complete(null);
                } else if (phase == 3 && observer.delivered.size() >= 4) {
                    check(observer.requests.size() == 4 && llm.generations.size() == 4, "Owner recall turn unexpectedly added a model request");
                    for (int i = 2; i < 4; i++) {
                        String prompt = llm.generations.get(i).stream().map(AiDialogueModels.OllamaMessage::content)
                                .collect(java.util.stream.Collectors.joining("\n"));
                        check(observer.requests.get(i).playerId().equals(owner.getUUID())
                                && !prompt.contains(PRIMARY_EMOTION) && !prompt.contains(SECONDARY_EMOTION),
                                "Another player's private interpretation leaked into the actual prompt");
                    }
                    check(ConversationRooms.INSTANCE.publicText(peer, "mythictrpg:demeter 그 이야기를 조금 더 이어 가자."),
                            "Actual follow-up input was not routed");
                    phase = 4;
                } else if (phase == 4 && observer.delivered.size() >= 6) {
                    check(observer.requests.size() == 6 && llm.generations.size() == 6 && llm.unexpectedCalls == 0,
                            "Emotion continuity added a model request or missed a reaction");
                    for (int i = 4; i < 6; i++) {
                        String prompt = llm.generations.get(i).stream().map(AiDialogueModels.OllamaMessage::content)
                                .collect(java.util.stream.Collectors.joining("\n"));
                        String own = i == 4 ? PRIMARY_EMOTION : SECONDARY_EMOTION, other = i == 4 ? SECONDARY_EMOTION : PRIMARY_EMOTION;
                        check(observer.requests.get(i).playerId().equals(peer.getUUID())
                                && prompt.contains("NPC_SESSION_EMOTION") && prompt.contains("NPC_INTERPRETATION_NOT_GAME_TRUTH")
                                && prompt.contains(own) && !prompt.contains(other), "Delivered own emotion failed to reach actual next speaker prompt");
                        check(observer.requests.get(i).speakerState().emotionTag().equals("E_UNASSESSED"),
                                "AI interpretation overwrote game-owned emotion");
                    }
                    if (NATIVE_SHADOW) {
                        if (com.sande.mythai.response.memory.RecordedRetrievalShadow.diagnostics().get("boundedCompleted") <= priorBundles) return;
                        for (var generation : llm.generations) for (var message : generation)
                            check(!message.content().contains("UNTRUSTED_RECORDED_DATA"), "SHADOW rendered cards entered foreground generation");
                    }
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
            if (recallGate != null && !recallGate.isDone()) recallGate.cancel(false);
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
            try { if (previousActivityData != null) {
                var load = NpcActivityWorldState.class.getDeclaredMethod("load", CompoundTag.class, HolderLookup.Provider.class);
                load.setAccessible(true);
                var restored = (NpcActivityWorldState) load.invoke(null, previousActivityData, server.registryAccess());
                check(restored.ready(), "Original activity SavedData could not be restored");
                // Force the original snapshot back to disk even if a test-world autosave ran during the fixture.
                restored.setDirty();
                server.overworld().getDataStorage().set("mythictrpg_npc_activities", restored);
            } } catch (Throwable error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
            try { if (modeChanged) modeField.set(null, previousMode); }
                catch (Throwable error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
            finally { active = null; }
            if (failure != null) throw new IllegalStateException("Full-path fixture cleanup failed", failure);
        }
    }

    /** One read probe may delay a live turn; every production operation, callback and guard is delegated unchanged. */
    private static final class ObservingEngine implements RoomConversationEngine {
        final RoomConversationEngine delegate;
        final java.util.function.Function<Request, CompletableFuture<Void>> beforeResponse;
        final List<Request> requests = new ArrayList<>();
        final List<Result> results = new ArrayList<>(), delivered = new ArrayList<>();
        final List<RoomDialogueEvent> observed = new ArrayList<>(), published = new ArrayList<>();
        Request current;
        ObservingEngine(RoomConversationEngine delegate, java.util.function.Function<Request, CompletableFuture<Void>> beforeResponse) {
            this.delegate = delegate; this.beforeResponse = beforeResponse;
        }
        public CompletableFuture<Result> respond(Request request) {
            requests.add(request); current = request;
            return beforeResponse.apply(request).thenCompose(ignored -> delegate.respond(request))
                    .whenComplete((result, failure) -> { if (result != null) results.add(result); });
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
            if (closed || observer.current == null || generations.size() >= 6) {
                unexpectedCalls++; throw new IllegalStateException("Unexpected generation in offline fixture");
            }
            generations.add(List.copyOf(messages));
            var request = observer.current;
            var speech = new AiDialogueModels.Speech(request.speakerGodId().toString(), request.secondary() ? REACTION : PRIMARY, List.of());
            var value = new AiDialogueModels.StructuredAiResult(List.of(speech), "황혼 항구", List.of(),
                    request.secondary() ? SECONDARY_EMOTION : PRIMARY_EMOTION);
            if (NATIVE_SHADOW) {
                // Simulate a pending transport without a socket. Instant completion would correctly expire
                // every fire-and-forget read lease before the real SQLite worker could compare anything.
                var future = new CompletableFuture<LocalLlmRequestScheduler.ScheduledResult<AiDialogueModels.StructuredAiResult>>();
                CompletableFuture.delayedExecutor(1, java.util.concurrent.TimeUnit.SECONDS).execute(() ->
                        future.complete(new LocalLlmRequestScheduler.ScheduledResult<>(value, Duration.ZERO, Duration.ofSeconds(1))));
                return new LocalLlmRequestScheduler.ScheduledRequest<>(id, Instant.now(), future);
            }
            return completed(id, value);
        }
        public LocalLlmRequestScheduler.ScheduledRequest<ConversationIntent> submitIntent(UUID id,
                List<AiDialogueModels.OllamaMessage> messages, AiDialogueConfig.Settings settings) {
            if (closed) { unexpectedCalls++; throw new IllegalStateException("Classifier used after fixture close"); }
            return completed(id, new ConversationIntent(Set.of(), Set.of("용", "폐허"), 100, ConversationIntent.Source.LOCAL_LLM));
        }
        public LocalLlmRequestScheduler.ScheduledRequest<RoomDialogueGrounding.Review> submitReview(UUID id,
                List<AiDialogueModels.OllamaMessage> messages, AiDialogueConfig.Settings settings) {
            if (closed) { unexpectedCalls++; throw new IllegalStateException("Review used after fixture close"); }
            return completed(id, RoomDialogueGrounding.Review.accepted());
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
