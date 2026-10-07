package com.sande.mythictrpg.recording.server;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.gameplay.ledger.AsyncActionLedger;
import com.sande.mythictrpg.gameplay.ledger.server.ActionLedgerService;
import com.sande.mythictrpg.rumor.RumorSavedData;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.IOUtilities;
import net.neoforged.neoforge.event.server.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.util.*;
import java.util.concurrent.*;

/** Actual server lifecycle, inactive by default. M2 capture adapters are deliberately a separate boundary. */
@EventBusSubscriber(modid = MythicTrpg.MOD_ID)
public final class RecordingRuntime {
    private static final Map<MinecraftServer, Entry> ENTRIES = new IdentityHashMap<>();
    private static final class Entry {
        final ExecutorService bootstrap = Executors.newSingleThreadExecutor(task -> {
            var thread = new Thread(task, "myth-recording-config"); thread.setDaemon(true); return thread;
        });
        volatile boolean stopped;
        volatile WorldRecordingService service;
        volatile CompletableFuture<WorldRecordingService> opening;
        RoomRecordingCapture roomCapture;
        WatchRecordingCapture watchCapture;
        WatchKnowledgeReconciler watchReconciler;
        com.sande.mythictrpg.gameplay.watch.GameWatchGateway reconciliationGateway;
        RumorRecordingCapture rumorCapture;
        RumorSavedData rumorData;
        volatile boolean rumorEnabled;
        RumorSavedData.RecordingStatus rumorStatus;
        String watchReconciliationStatus="";
        com.sande.mythictrpg.recording.channel.ChannelRecordingCapture channelCapture;
        final java.util.concurrent.atomic.AtomicLong captureFailures = new java.util.concurrent.atomic.AtomicLong();
        long reportedCaptureFailures;
        long unavailableBeforeCapture;
        long unavailableBeforeChannel;
        RecordingSettings settings;
        RecordingRetrievalSettings.Policy retrievalPolicy;
        boolean projectionEnabled;
        boolean embeddingEnabled;
        String state = "LOADING_CONFIG", reported = "";
        long lastCapacityNotice;
    }
    private RecordingRuntime() { }
    public static Optional<WorldRecordingService> current(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Recording lifecycle requires game thread");
        var entry = ENTRIES.get(server); return entry == null || entry.stopped ? Optional.empty() : Optional.ofNullable(entry.service);
    }
    /** Content-free policy diagnostic, not archive health or a permission to invoke NEW foreground retrieval. */
    public static String retrievalState(MinecraftServer server) {
        if(!server.isSameThread())throw new IllegalStateException("RETRIEVAL_POLICY_REQUIRES_GAME_THREAD");
        var entry=ENTRIES.get(server);
        if(entry==null)return "DEFAULT_LEGACY";
        if(entry.stopped)return "RETRIEVAL_RUNTIME_STOPPED";
        return entry.retrievalPolicy==null?(entry.state.equals("INVALID_RECORDING_CONFIG")
                ?"BLOCKED_ARCHIVE_CONFIG_INVALID":"RETRIEVAL_CONFIG_LOADING"):entry.retrievalPolicy.state().name();
    }
    /** NEW and malformed explicit policies must not silently fall back to excluded legacy memories. */
    public static boolean retrievalForegroundBlocked(MinecraftServer server) {
        if(!server.isSameThread())throw new IllegalStateException("RETRIEVAL_POLICY_REQUIRES_GAME_THREAD");
        var entry=ENTRIES.get(server);
        return entry!=null&&(entry.stopped||entry.retrievalPolicy==null||entry.retrievalPolicy.foregroundBlocked());
    }
    /** Game-owned policy gate for the independent derived-memory worker, never a foreground read grant. */
    public static boolean projectionEnabled(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("PROJECTION_POLICY_REQUIRES_GAME_THREAD");
        var entry = ENTRIES.get(server);
        return entry != null && !entry.stopped && entry.projectionEnabled && entry.settings != null
                && entry.settings.archiveMode() != RecordingSettings.Mode.OFF && entry.service != null
                && entry.service.health().state() == WorldRecordingService.State.READY;
    }
    public record ProjectionAccess(UUID runtimeEpoch, com.sande.mythictrpg.recording.api.MemoryProjectionPort port,
            com.sande.mythictrpg.recording.api.ProjectionWorkerCapability worker, boolean backgroundAllowed) { }
    /** Native semantic comparison is independent of both legacy semanticMode and projectionMode. */
    public static boolean embeddingEnabled(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("EMBEDDING_POLICY_REQUIRES_GAME_THREAD");
        var entry = ENTRIES.get(server);
        return entry != null && !entry.stopped && entry.embeddingEnabled && entry.settings != null
                && entry.settings.archiveMode() == RecordingSettings.Mode.SHADOW && entry.service != null
                && entry.service.health().state() == WorldRecordingService.State.READY;
    }
    public record EmbeddingAccess(UUID runtimeEpoch, com.sande.mythictrpg.recording.api.MemoryEmbeddingPort port,
            com.sande.mythictrpg.recording.api.EmbeddingWorkerCapability worker, boolean backgroundAllowed) { }
    public static Optional<EmbeddingAccess> embeddingAccess(MinecraftServer server,
            com.sande.mythictrpg.recording.api.EmbeddingRecords.ModelSpace space) {
        if (!embeddingEnabled(server)) return Optional.empty();
        var service = ENTRIES.get(server).service;
        var worker = service.registerEmbeddingWorker("mythai-recorded-embedding-v1", space);
        boolean allowed = server.getPlayerCount() == 0
                && com.sande.mythictrpg.ai.memorycontract.MemoryFoundationSettings.mode()
                != com.sande.mythictrpg.ai.memorycontract.MemoryFoundationSettings.Mode.OFF
                && service.quotaSnapshot().filter(q -> Set.of("READY", "WARNING").contains(q.state())).isPresent();
        return Optional.of(new EmbeddingAccess(service.runtimeEpoch(), service.embeddingPort(), worker, allowed));
    }
    /** The AI consumer receives a restricted port/handle, not a producer or generic archive writer. */
    public static Optional<ProjectionAccess> projectionAccess(MinecraftServer server, String extractorVersion) {
        if (!projectionEnabled(server)) return Optional.empty();
        var service = ENTRIES.get(server).service;
        var capability = service.registerProjectionWorker("mythai-recorded-projection-v1", extractorVersion);
        boolean allowed = server.getPlayerCount() == 0
                && com.sande.mythictrpg.ai.memorycontract.MemoryFoundationSettings.mode()
                != com.sande.mythictrpg.ai.memorycontract.MemoryFoundationSettings.Mode.OFF
                && service.quotaSnapshot().filter(q -> Set.of("READY", "WARNING").contains(q.state())).isPresent();
        return Optional.of(new ProjectionAccess(service.runtimeEpoch(), service.projectionPort(), capability, allowed));
    }
    /** Existing game watch proofs only; no generic raw-ledger browser is exposed to AI. */
    public static Optional<WatchRecordingCapture> watchCapture(MinecraftServer server) {
        if (!server.isSameThread()) return Optional.empty();
        var entry = ENTRIES.get(server);
        if (entry == null || entry.stopped || entry.service == null || entry.service.health().state() != WorldRecordingService.State.READY
                || entry.settings == null || entry.settings.archiveMode() == RecordingSettings.Mode.OFF) return Optional.empty();
        if (entry.watchCapture == null) entry.watchCapture = new WatchRecordingCapture(entry.service);
        return Optional.of(entry.watchCapture);
    }
    /** No world identity lookup, producer registration or body allocation when recording is OFF. */
    public static Optional<com.sande.mythictrpg.recording.channel.ChannelRecordingCapture> channelCapture(MinecraftServer server) {
        if (!server.isSameThread()) return Optional.empty();
        var entry = ENTRIES.get(server);
        if (entry == null || entry.stopped || entry.settings == null || entry.settings.archiveMode() == RecordingSettings.Mode.OFF) return Optional.empty();
        if (entry.service == null || entry.service.health().state() != WorldRecordingService.State.READY) {
            entry.captureFailures.incrementAndGet();
            if (entry.unavailableBeforeChannel < Long.MAX_VALUE) entry.unavailableBeforeChannel++;
            return Optional.empty();
        }
        if (entry.channelCapture == null) entry.channelCapture = new com.sande.mythictrpg.recording.channel.ChannelRecordingCapture(entry.service);
        if (entry.unavailableBeforeChannel != 0) {
            entry.channelCapture.gap("CHANNEL_BEFORE_STORAGE_READY", entry.unavailableBeforeChannel); entry.unavailableBeforeChannel = 0;
        }
        return Optional.of(entry.channelCapture);
    }
    public static boolean channelCurrent(MinecraftServer server, com.sande.mythictrpg.recording.channel.ChannelRecordingCapture capture) {
        var entry = ENTRIES.get(server);
        return server.isSameThread() && entry != null && !entry.stopped && entry.channelCapture == capture;
    }
    public static void captureChannel(MinecraftServer server, com.sande.mythictrpg.recording.channel.ChannelRecordingCapture capture,
            com.sande.mythictrpg.recording.channel.ChannelRecordingCapture.Occurrence occurrence) {
        var entry = ENTRIES.get(server);
        capture.capture(occurrence, channelCurrent(server, capture)).whenComplete((result, failure) -> {
            if (entry != null && (failure != null || result == null || result.status() != com.sande.mythictrpg.recording.api.RecordingRecords.Status.STORED
                    && result.status() != com.sande.mythictrpg.recording.api.RecordingRecords.Status.DUPLICATE && !result.reasonCode().equals("NO_DISPATCH")))
                entry.captureFailures.incrementAndGet();
        });
    }
    /** Called by the game fan-out before any optional AI publication observer. Never installs or waits for AI. */
    public static void captureRoom(MinecraftServer server, com.sande.mythictrpg.ai.api.RoomDialogueEvent event) {
        if (!server.isSameThread()) throw new IllegalStateException("ROOM_CAPTURE_REQUIRES_GAME_THREAD");
        if (!event.recordingScope().recordingAllowed()) return;
        var entry = ENTRIES.get(server);
        if (entry == null || entry.stopped || entry.settings == null || entry.settings.archiveMode() == RecordingSettings.Mode.OFF) return;
        var service = entry.service;
        if (service == null || service.health().state() != WorldRecordingService.State.READY) {
            entry.captureFailures.incrementAndGet();
            if (entry.roomCapture != null) entry.roomCapture.gap("CAPTURE_STORE_UNAVAILABLE", 1);
            else if (entry.unavailableBeforeCapture < Long.MAX_VALUE) entry.unavailableBeforeCapture++;
            return;
        }
        try {
            if (entry.roomCapture == null) {
                entry.roomCapture = new RoomRecordingCapture(service, com.sande.mythictrpg.ai.memorycontract.MemoryFoundationSettings.mode().name());
                entry.roomCapture.gap("CAPTURE_BEFORE_STORAGE_READY", entry.unavailableBeforeCapture);
                entry.unavailableBeforeCapture = 0;
            }
            entry.roomCapture.capture(event, com.sande.mythictrpg.ai.server.ConversationRooms.INSTANCE.isCurrent(event.roomId(), event.revision()))
                    .whenComplete((outcome, failure) -> { if (failure != null || outcome == null || !outcome.complete()) entry.captureFailures.incrementAndGet(); });
        } catch (RuntimeException unavailable) {
            entry.captureFailures.incrementAndGet();
            if (entry.roomCapture != null) entry.roomCapture.gap("CAPTURE_ADAPTER_UNAVAILABLE", 1);
        }
    }
    @SubscribeEvent public static void started(ServerStartedEvent event) {
        var server = event.getServer(); var entry = new Entry();
        if (ENTRIES.putIfAbsent(server, entry) != null) { entry.bootstrap.shutdown(); return; }
        var config = server.getServerDirectory().resolve("config/mythictrpg/recording-v2.json");
        entry.bootstrap.execute(() -> {
            try {
                var settings = RecordingSettings.load(config);
                RecordingRetrievalSettings.Policy retrieval;
                try {
                    retrieval=RecordingRetrievalSettings.resolve(settings.archiveMode(),
                            RecordingRetrievalSettings.load(config.resolveSibling("recording-retrieval.json")));
                } catch(java.io.IOException invalidRetrieval) {
                    retrieval=RecordingRetrievalSettings.invalid();
                    MythicTrpg.LOGGER.warn("Native retrieval blocked: INVALID_RETRIEVAL_CONFIG. Archive policy is unchanged.");
                }
                final var retrievalPolicy=retrieval;
                boolean projectionEnabled = false;
                try {
                    projectionEnabled = RecordingProjectionSettings.load(config.resolveSibling("recording-projection.json"))
                            .projectionMode() == RecordingProjectionSettings.Mode.ON;
                } catch (java.io.IOException invalidProjection) {
                    MythicTrpg.LOGGER.warn("Recording projection disabled: INVALID_PROJECTION_CONFIG. Archive policy is unchanged.");
                }
                final boolean projectionAllowed = projectionEnabled;
                boolean embeddingEnabled = false;
                try {
                    embeddingEnabled = RecordingEmbeddingSettings.load(config.resolveSibling("recording-embedding.json"))
                            .embeddingMode() == RecordingEmbeddingSettings.Mode.SHADOW;
                } catch (java.io.IOException invalidEmbedding) {
                    MythicTrpg.LOGGER.warn("Native semantic comparison disabled: INVALID_EMBEDDING_CONFIG. Archive policy is unchanged.");
                }
                final boolean embeddingAllowed = embeddingEnabled;
                server.execute(() -> { if (!entry.stopped) {
                    entry.projectionEnabled = projectionAllowed;
                    entry.embeddingEnabled = embeddingAllowed;
                    entry.retrievalPolicy = retrievalPolicy;
                    if(retrievalPolicy.foregroundBlocked())MythicTrpg.LOGGER.warn("Native retrieval blocked: {}",retrievalPolicy.state().name());
                    entry.settings = settings; entry.state = settings.archiveMode() == RecordingSettings.Mode.OFF ? "OFF" : "WAITING_FOR_GAME_SOURCES";
                    if (settings.archiveMode() != RecordingSettings.Mode.OFF) tryOpen(server, entry);
                } });
            } catch (Exception failure) { server.execute(() -> { if (!entry.stopped) entry.state = "INVALID_RECORDING_CONFIG"; }); }
            finally { entry.bootstrap.shutdown(); }
        });
    }
    private static void tryOpen(MinecraftServer server, Entry entry) {
        if (entry.stopped || entry.opening != null || entry.settings == null || entry.settings.archiveMode() == RecordingSettings.Mode.OFF) return;
        var raw = ActionLedgerService.current(server);
        // Capture only an actual durable game cursor. Do not substitute clock time, a queued sequence or a guessed watch cursor.
        if (raw != null && (raw.reason().equals("STARTING") || raw.ledger() != null && raw.ledger().status().state() == AsyncActionLedger.State.STARTING)) return;
        try {
            var gameWorld = RumorSavedData.get(server);
            if (!gameWorld.ready()) { entry.state = "UNAVAILABLE_GAME_WORLD_IDENTITY"; return; }
            entry.rumorData = gameWorld;
            configureRumor(server, entry);
            var cursors = new LinkedHashMap<String, Long>();
            if (raw != null && raw.ledger() != null && raw.ledger().status().state() == AsyncActionLedger.State.READY)
                cursors.put("action-ledger-v1", raw.ledger().status().committedSequence());
            entry.state = "OPENING";
            entry.opening = WorldRecordingService.open(server.getWorldPath(LevelResource.ROOT), gameWorld.worldId(), entry.settings,
                    new WorldRecordingService.CutoverBoundary("recording-schema-2", cursors),entry.retrievalPolicy);
            entry.opening.whenComplete((service, failure) -> {
                if (service != null) entry.service = service;
                // No Minecraft object is read on this worker. The server tick reports immutable health.
            });
        } catch (RuntimeException unavailable) { entry.state = "UNAVAILABLE_GAME_SOURCE_SNAPSHOT"; }
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        var server = event.getServer(); var entry = ENTRIES.get(server);
        if (entry == null || entry.stopped || server.getTickCount() % 20 != 0) return;
        if (entry.state.equals("WAITING_FOR_GAME_SOURCES")) tryOpen(server, entry);
        String state = entry.service == null ? entry.state : entry.service.health().reasonCode();
        if (!state.equals(entry.reported) && !Set.of("OFF", "LOADING_CONFIG", "WAITING_FOR_GAME_SOURCES", "OPENING", "READY").contains(state)) {
            entry.reported = state; notifyAdmins(server, "기록 v2 상태: " + state + ". 게임 진행과 기존 데이터는 유지됩니다.");
        }
        if (entry.service == null) return;
        captureRumor(server, entry);
        reconcileWatch(server, entry);
        // Optional lexical maintenance uses the same reserved writer as RAW capture. The pump
        // only schedules a bounded batch; no SQL, source text or future waiting happens on tick.
        // SHADOW is a separate opt-in, and actual dialogue/model behaviour remains unchanged.
        if (entry.settings != null && entry.settings.archiveMode() == RecordingSettings.Mode.SHADOW
                && server.getPlayerCount() == 0) entry.service.pumpLexicalIndex();
        long failedCaptures = entry.captureFailures.get();
        if (failedCaptures != entry.reportedCaptureFailures && (entry.lastCapacityNotice == 0 || System.currentTimeMillis() - entry.lastCapacityNotice >= 1_800_000)) {
            entry.reportedCaptureFailures = failedCaptures;
            entry.lastCapacityNotice = System.currentTimeMillis();
            notifyAdmins(server, "대화 기록 누락/부분 전달 진단=" + failedCaptures + ". 대화와 게임 진행은 유지됩니다.");
        }
        var quota = entry.service.quotaSnapshot().orElse(null);
        if (quota == null) return;
        long now = System.currentTimeMillis();
        if (!quota.state().equals("READY") && (entry.lastCapacityNotice == 0 || now - entry.lastCapacityNotice >= 1_800_000)) {
            entry.lastCapacityNotice = now;
            notifyAdmins(server, "기록 합산 용량 상태=" + quota.state() + ", used=" + quota.usedPhysicalBytes()
                    + ", reserved=" + quota.outstandingReservations() + ", limit=" + quota.limitBytes() + " bytes. 자동 원문 삭제는 하지 않습니다.");
        }
    }
    @SubscribeEvent public static void stopping(ServerStoppingEvent event) {
        NativeRoomEvidence.clear(event.getServer());
        var entry = ENTRIES.get(event.getServer()); if (entry != null) {
            entry.stopped = true;
            if(entry.watchReconciler!=null)entry.watchReconciler.close();
        }
    }
    @SubscribeEvent(priority = EventPriority.LOWEST) public static void stopped(ServerStoppedEvent event) {
        var entry = ENTRIES.remove(event.getServer()); if (entry == null) return;
        entry.stopped = true; entry.bootstrap.shutdown();
        if (entry.opening == null) return;
        var flushed = new CompletableFuture<Void>();
        // Existing action/watch stop listeners run before LOWEST. The NeoForge save chain fences actual SavedData I/O.
        IOUtilities.withIOWorker(() -> entry.opening.whenComplete((service, failure) -> {
            if (service == null) { flushed.complete(null); return; }
            // Save callbacks have already published their exact immutable checkpoints. No final tick is required.
            // This worker does not read a live ledger, query Minecraft, or enqueue another SavedData write.
            var view = entry.rumorEnabled && entry.rumorData != null ? entry.rumorData.recordingSnapshot() : Optional.<com.sande.mythictrpg.rumor.RumorRecordingState.DurableView>empty();
            CompletableFuture<?> reconciliation = entry.rumorCapture != null && view.isPresent()
                    ? entry.rumorCapture.afterPending(view.get()).thenAccept(outcome -> {
                        if (!outcome.complete()) entry.captureFailures.incrementAndGet();
                    }) : CompletableFuture.completedFuture(null);
            reconciliation.whenComplete((done, captureFailure) -> service.closeAsync().whenComplete((ignored, closeFailure) -> {
                if (closeFailure == null) flushed.complete(null); else flushed.completeExceptionally(closeFailure);
            }));
        }));
        try { flushed.get(10, TimeUnit.SECONDS); } // Shutdown only, after ticks stop; never block a live game tick on disk.
        catch (Exception failure) { MythicTrpg.LOGGER.error("Recording shutdown did not confirm a clean durable marker; possible gap on restart", failure); }
    }
    private static void notifyAdmins(MinecraftServer server, String message) {
        MythicTrpg.LOGGER.warn(message);
        server.getPlayerList().getPlayers().stream().filter(player -> player.hasPermissions(2))
                .forEach(player -> player.sendSystemMessage(Component.literal("[MythicTRPG] " + message)));
    }

    private static void configureRumor(MinecraftServer server, Entry entry) {
        if (entry.rumorData == null) return;
        entry.rumorEnabled = entry.settings != null && entry.settings.archiveMode() != RecordingSettings.Mode.OFF
                && com.sande.mythictrpg.ai.memorycontract.MemoryFoundationSettings.mode()
                == com.sande.mythictrpg.ai.memorycontract.MemoryFoundationSettings.Mode.RUMOR_TEST;
        entry.rumorData.recordingEnabled(entry.rumorEnabled);
        var status=entry.rumorData.recordingStatus();
        if(status!=entry.rumorStatus) {
            entry.rumorStatus=status;
            if(status.name().startsWith("UNAVAILABLE"))entry.captureFailures.incrementAndGet();
        }
    }

    private static void captureRumor(MinecraftServer server, Entry entry) {
        try {
            configureRumor(server, entry);
            if (entry.rumorData == null
                    || !Set.of(WorldRecordingService.State.READY, WorldRecordingService.State.FULL).contains(entry.service.health().state())) return;
            // Install the shared quota before scheduling an optional legacy metadata save. Starting
            // this write in tryOpen can race quota installation and leave the archive unavailable.
            // One bounded managed save at a time; queue admission never advances archive authority.
            entry.rumorData.flushRecordingSnapshot(server);
            if (!entry.rumorEnabled) return;
            if (entry.rumorCapture == null) {
                if (entry.service.health().state() != WorldRecordingService.State.READY) return;
                entry.rumorCapture = new RumorRecordingCapture(entry.service);
            }
            entry.rumorData.recordingSnapshot().ifPresent(view -> entry.rumorCapture.capture(view).whenComplete((outcome, failure) -> {
                if (failure != null || outcome == null || !outcome.complete() && !outcome.reason().equals("RUMOR_CAPTURE_BUSY"))
                    entry.captureFailures.incrementAndGet();
            }));
        } catch (RuntimeException unavailable) { entry.captureFailures.incrementAndGet(); }
    }

    private static void reconcileWatch(MinecraftServer server,Entry entry) {
        var watch=com.sande.mythictrpg.gameplay.watch.GodWatchRuntime.current(server);
        boolean enabled=com.sande.mythictrpg.ai.memorycontract.MemoryFoundationSettings.mode()
                ==com.sande.mythictrpg.ai.memorycontract.MemoryFoundationSettings.Mode.PERSONAL;
        if(!enabled||watch==null||watch.gateway()==null) {
            if(entry.watchReconciler!=null)entry.watchReconciler.close();
            entry.watchReconciler=null;entry.reconciliationGateway=null;return;
        }
        try {
            if(entry.watchReconciler==null||entry.reconciliationGateway!=watch.gateway()) {
                if(entry.watchReconciler!=null)entry.watchReconciler.close();
                if(entry.watchCapture==null) {
                    if(entry.service.health().state()!=WorldRecordingService.State.READY)return;
                    entry.watchCapture=new WatchRecordingCapture(entry.service);
                }
                entry.reconciliationGateway=watch.gateway();
                entry.watchReconciler=new WatchKnowledgeReconciler(entry.watchCapture.reconciliationArchive(),
                        WatchKnowledgeReconciler.gameProbe(server,watch.gateway(),()->!entry.stopped
                                && current(server).filter(s->s==entry.service).isPresent()
                                && com.sande.mythictrpg.ai.memorycontract.MemoryFoundationSettings.mode()
                                ==com.sande.mythictrpg.ai.memorycontract.MemoryFoundationSettings.Mode.PERSONAL));
            }
            entry.watchReconciler.pump();
            String status=entry.watchReconciler.status().state();
            if(!status.equals(entry.watchReconciliationStatus)) {
                entry.watchReconciliationStatus=status;
                if(status.contains("UNAVAILABLE")||status.contains("REGRESSED")||status.contains("UNCONFIRMED")||status.contains("INVALID")
                        ||status.contains("UNKNOWN")||status.contains("MISMATCH"))
                    entry.captureFailures.incrementAndGet();
            }
        } catch(RuntimeException unavailable) { entry.captureFailures.incrementAndGet(); }
    }
}
