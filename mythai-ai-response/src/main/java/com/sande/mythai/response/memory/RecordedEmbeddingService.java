package com.sande.mythai.response.memory;

import com.sande.mythictrpg.ai.api.RoomConversationEngine.Request;
import com.sande.mythictrpg.recording.api.MemoryReadSession;
import com.sande.mythictrpg.recording.server.*;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Independent OFF-by-default policy, same model/backend/admission as existing memory work. */
public final class RecordedEmbeddingService {
    private static final Map<MinecraftServer, Entry> ENTRIES = new IdentityHashMap<>();
    private static boolean embeddingTurn;
    private record Loaded(MemoryIndexSettings settings, RecordedEmbeddingModel model) { }
    private static final class Entry {
        final ExecutorService loader;
        final ThreadPoolExecutor queries;
        final CompletableFuture<Loaded> loaded;
        final AtomicBoolean querying = new AtomicBoolean();
        volatile boolean closed;
        UUID epoch;
        RecordedEmbeddingRuntime runtime;
        String state = "LOADING_MODEL_CONFIGURATION";
        Entry(java.nio.file.Path config) {
            loader = Executors.newSingleThreadExecutor(task -> daemon(task, "mythai-embedding-config"));
            queries = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1), task -> daemon(task, "mythai-semantic-shadow"));
            loaded = CompletableFuture.supplyAsync(() -> {
                var settings = MemoryIndexSettings.load(config);
                return new Loaded(settings, settings.enabled() && settings.semanticMode() != MemoryIndexSettings.Mode.OFF
                        ? new RecordedEmbeddingModel(settings, new OllamaMemoryBackend(settings)) : null);
            }, loader);
            loaded.whenComplete((value, failure) -> loader.shutdown());
        }
        void close() { closed = true; if (runtime != null) runtime.close(); queries.shutdownNow(); loaded.cancel(false); loader.shutdown(); }
    }
    private static Thread daemon(Runnable task, String name) { var thread = new Thread(task, name); thread.setDaemon(true); return thread; }
    private RecordedEmbeddingService() { }
    /** Alternate admitted jobs, not clock slots: a two-second task must not win every even tick. */
    static synchronized boolean backgroundTurn(boolean embedding, boolean otherReady, boolean eitherRunning) {
        var status = ModelAdmission.status();
        if (eitherRunning || status.onlinePlayers() != 0 || status.foregroundActive() != 0
                || status.foregroundPending() != 0 || status.optionalActive()) return false;
        if (otherReady && embeddingTurn != embedding) return false;
        embeddingTurn = !embedding; return true;
    }
    static boolean backgroundReady(MinecraftServer server) {
        var entry = ENTRIES.get(server); return entry != null && !entry.closed && entry.runtime != null && RecordingRuntime.embeddingEnabled(server);
    }
    static boolean backgroundRunning(MinecraftServer server) { var entry = ENTRIES.get(server); return entry != null && entry.runtime != null && entry.runtime.running(); }
    public static void tick(ServerTickEvent.Post event) {
        var server = event.getServer(); if (server.getTickCount() % 20 != 0) return;
        var entry = ENTRIES.get(server);
        if (!RecordingRuntime.embeddingEnabled(server)) { if (entry != null && entry.runtime != null) entry.runtime.eligible(false); return; }
        if (entry == null) { entry = new Entry(server.getServerDirectory().resolve("config/mythictrpg/ai-memory-index.json")); ENTRIES.put(server, entry); }
        if (!entry.loaded.isDone()) return;
        try {
            var loaded = entry.loaded.join();
            if (loaded.model() == null) { entry.state = "MODEL_CONFIG_OFF_OR_INVALID"; return; }
            var access = RecordingRuntime.embeddingAccess(server, loaded.model().space);
            if (access.isEmpty()) { if (entry.runtime != null) entry.runtime.eligible(false); return; }
            var issued = access.get();
            if (entry.runtime != null && !issued.runtimeEpoch().equals(entry.epoch)) { entry.runtime.close(); entry.runtime = null; }
            if (entry.runtime == null) {
                entry.epoch = issued.runtimeEpoch();
                entry.runtime = new RecordedEmbeddingRuntime(issued.port(), issued.worker(), loaded.model()::embed);
            }
            entry.runtime.eligible(issued.backgroundAllowed());
            if (issued.backgroundAllowed() && backgroundTurn(true, RecordedProjectionService.backgroundReady(server),
                    entry.runtime.running() || RecordedProjectionService.backgroundRunning(server))) entry.runtime.pump();
            entry.state = issued.backgroundAllowed() ? entry.runtime.diagnostic() : "DEFERRED_PLAYERS_POLICY_OR_QUOTA";
        } catch (RuntimeException unavailable) { if (entry.runtime != null) entry.runtime.eligible(false); entry.state = "EMBEDDING_INITIALIZATION_UNAVAILABLE"; }
    }
    /** Explicit recall only. No inference queue, no wait on the game thread, no effect on normal generation. */
    public static void compare(MinecraftServer server, Request request) {
        compare(server, request, Optional.empty());
    }
    public static void compare(MinecraftServer server, Request request, Optional<RecallQuery> carried) {
        try {
            if (!server.isSameThread() || !RecordingRuntime.embeddingEnabled(server)) return;
            var planned = RecordedRecallQuery.prepare(request, carried);
            if (planned.isEmpty() || !planned.orElseThrow().semanticEligible()) return;
            var query = planned.orElseThrow().query();
            var entry = ENTRIES.get(server);
            if (entry == null || entry.closed || !entry.loaded.isDone()) return;
            var loaded = entry.loaded.join(); if (loaded.model() == null) return;
            var session = RecordedMemoryAccess.open(server, request); if (session.isEmpty()) return;
            if (!entry.querying.compareAndSet(false, true)) return;
            try { entry.queries.execute(() -> {
                try {
                    if (entry.closed) return;
                    com.sande.mythictrpg.recording.api.EmbeddingRecords.QueryVector vector;
                    // Unlike optional(false), followup retains cooperative foreground preemption.
                    try (var permit = ModelAdmission.followup()) {
                        if (permit == null) return;
                        vector = loaded.model().query(query.text());
                    }
                    if (entry.closed || Thread.currentThread().isInterrupted()) return;
                    server.execute(() -> {
                        try { if (!entry.closed && RecordingRuntime.embeddingEnabled(server))
                            RecordedSemanticShadow.compare(session.get(), query,
                                    vector, loaded.settings().execution().minimumSimilarity(), task -> server.execute(task));
                        } catch (RuntimeException unavailable) { RecordedSemanticShadow.failed(); }
                    });
                } catch (Exception unavailable) { RecordedSemanticShadow.failed(); }
                finally { Thread.interrupted(); entry.querying.set(false); }
            }); } catch (RejectedExecutionException unavailable) { entry.querying.set(false); }
        } catch (RuntimeException unavailable) { RecordedSemanticShadow.failed(); }
    }

    /** One optional vector for the shared SHADOW collector. Does not open another read session. */
    static CompletableFuture<Optional<RecordedRetrievalCoordinator.SemanticQuery>> queryVector(
            MinecraftServer server, RecordedRecallQuery.Prepared prepared) {
        try {
            if (!server.isSameThread() || !prepared.semanticEligible() || !RecordingRuntime.embeddingEnabled(server))
                return CompletableFuture.completedFuture(Optional.empty());
            var entry = ENTRIES.get(server);
            if (entry == null || entry.closed || !entry.loaded.isDone())
                return CompletableFuture.completedFuture(Optional.empty());
            var loaded = entry.loaded.join();
            if (loaded.model() == null) return CompletableFuture.completedFuture(Optional.empty());
            return submitQuery(entry.queries, entry.querying, () -> !entry.closed,
                    () -> !entry.closed && RecordingRuntime.embeddingEnabled(server), () -> {
                        try (var permit = ModelAdmission.followup()) {
                            if (permit == null) return Optional.empty();
                            return Optional.of(new RecordedRetrievalCoordinator.SemanticQuery(
                                    loaded.model().query(prepared.query().text()), loaded.settings().execution().minimumSimilarity()));
                        }
                    }, server::execute);
        } catch (RuntimeException unavailable) { return CompletableFuture.completedFuture(Optional.empty()); }
    }

    /** Existing bounded executor/admission only. Cancellation never admits an overlapping model call. */
    static CompletableFuture<Optional<RecordedRetrievalCoordinator.SemanticQuery>> submitQuery(
            Executor executor, AtomicBoolean querying, java.util.function.BooleanSupplier workerAvailable,
            java.util.function.BooleanSupplier currentOnGameThread,
            Callable<Optional<RecordedRetrievalCoordinator.SemanticQuery>> compute,
            java.util.function.Consumer<Runnable> dispatch) {
        var result = new CompletableFuture<Optional<RecordedRetrievalCoordinator.SemanticQuery>>();
        if (!querying.compareAndSet(false, true)) {
            result.complete(Optional.empty()); return result;
        }
        try {
            executor.execute(() -> {
                Optional<RecordedRetrievalCoordinator.SemanticQuery> value = Optional.empty();
                try {
                    if (!result.isDone() && workerAvailable.getAsBoolean()) value = Objects.requireNonNull(compute.call());
                    if (Thread.currentThread().isInterrupted() || !workerAvailable.getAsBoolean()) value = Optional.empty();
                } catch (Exception unavailable) {
                    value = Optional.empty();
                } finally {
                    // Keep the permit until the running backend actually returns, even if its caller cancelled.
                    querying.set(false);
                }
                var completed = value;
                if (result.isDone()) return;
                try {
                    dispatch.accept(() -> {
                        if (result.isDone()) return;
                        try { result.complete(currentOnGameThread.getAsBoolean() ? completed : Optional.empty()); }
                        catch (RuntimeException unavailable) { result.complete(Optional.empty()); }
                    });
                } catch (RuntimeException unavailable) { result.complete(Optional.empty()); }
            });
        } catch (RuntimeException unavailable) {
            querying.set(false); result.complete(Optional.empty());
        }
        return result;
    }
    public static void stopping(ServerStoppingEvent event) { var entry = ENTRIES.remove(event.getServer()); if (entry != null) entry.close(); }
    public static String diagnostic(MinecraftServer server) {
        if (!server.isSameThread()) return "WRONG_THREAD";
        var entry = ENTRIES.get(server); return (entry == null ? "OFF_OR_STARTING" : entry.state) + ":" + RecordedSemanticShadow.diagnostics();
    }
}
