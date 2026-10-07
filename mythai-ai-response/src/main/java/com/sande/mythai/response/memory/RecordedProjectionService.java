package com.sande.mythai.response.memory;

import com.sande.mythictrpg.recording.server.RecordingRuntime;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.util.*;
import java.util.concurrent.*;

/** Game tick supplies eligibility only; configuration, extraction and persistence run off-thread. */
public final class RecordedProjectionService {
    private static final Map<MinecraftServer, Entry> ENTRIES = new IdentityHashMap<>();
    private record Loaded(MemoryIndexSettings settings, RecordedProjectionExtractor extractor) { }
    private static final class Entry {
        UUID runtimeEpoch;
        final CompletableFuture<Loaded> loaded;
        final ExecutorService loader;
        RecordedProjectionRuntime runtime;
        String state = "LOADING_MODEL_CONFIGURATION";
        Entry(java.nio.file.Path config) {
            loader = Executors.newSingleThreadExecutor(task -> { var thread = new Thread(task, "mythai-projection-config"); thread.setDaemon(true); return thread; });
            loaded = CompletableFuture.supplyAsync(() -> {
                var settings = MemoryIndexSettings.load(config);
                return new Loaded(settings, settings.enabled() && settings.consolidate()
                        ? new RecordedProjectionExtractor(settings, new OllamaMemoryBackend(settings)) : null);
            }, loader);
            loaded.whenComplete((value, failure) -> loader.shutdown());
        }
        void close() { if (runtime != null) runtime.close(); loaded.cancel(false); loader.shutdown(); }
    }
    private RecordedProjectionService() { }

    public static void tick(ServerTickEvent.Post event) {
        var server = event.getServer();
        if (server.getTickCount() % 20 != 0) return;
        // This gate must be explicitly ON in the game configuration. SHADOW by itself grants nothing.
        var entry = ENTRIES.get(server);
        if (!RecordingRuntime.projectionEnabled(server)) { if (entry != null && entry.runtime != null) entry.runtime.eligible(false); return; }
        if (entry == null) {
            entry = new Entry(server.getServerDirectory().resolve("config/mythictrpg/ai-memory-index.json"));
            ENTRIES.put(server, entry);
        }
        if (!entry.loaded.isDone()) return;
        try {
            var loaded = entry.loaded.join(); // Already complete; never waits on the tick.
            if (loaded.extractor() == null) { entry.state = "MODEL_CONFIG_OFF_OR_INVALID"; return; }
            var access = RecordingRuntime.projectionAccess(server, RecordedProjectionExtractor.version(loaded.settings()));
            if (access.isEmpty()) { if (entry.runtime != null) entry.runtime.eligible(false); return; }
            var issued = access.get();
            if (entry.runtime != null && !issued.runtimeEpoch().equals(entry.runtimeEpoch)) {
                entry.runtime.close(); entry.runtime = null;
            }
            if (entry.runtime == null) {
                entry.runtimeEpoch = issued.runtimeEpoch();
                entry.runtime = new RecordedProjectionRuntime(issued.port(), issued.worker(), loaded.extractor()::extract);
            }
            entry.runtime.eligible(issued.backgroundAllowed());
            if (issued.backgroundAllowed() && RecordedEmbeddingService.backgroundTurn(false, RecordedEmbeddingService.backgroundReady(server),
                    entry.runtime.running() || RecordedEmbeddingService.backgroundRunning(server))) entry.runtime.pump();
            entry.state = issued.backgroundAllowed() ? entry.runtime.diagnostic() : "DEFERRED_PLAYERS_POLICY_OR_QUOTA";
        } catch (RuntimeException unavailable) {
            if (entry.runtime != null) entry.runtime.eligible(false);
            entry.state = "PROJECTION_INITIALIZATION_UNAVAILABLE";
        }
    }
    public static void stopping(ServerStoppingEvent event) {
        var entry = ENTRIES.remove(event.getServer()); if (entry != null) entry.close();
    }
    static boolean backgroundReady(MinecraftServer server) {
        var entry = ENTRIES.get(server); return entry != null && entry.runtime != null && RecordingRuntime.projectionEnabled(server);
    }
    static boolean backgroundRunning(MinecraftServer server) { var entry = ENTRIES.get(server); return entry != null && entry.runtime != null && entry.runtime.running(); }
    /** Content-free, operator-only caller. Never discloses source identities, text or private membership. */
    public static String diagnostic(MinecraftServer server) {
        if (!server.isSameThread()) return "WRONG_THREAD";
        var entry = ENTRIES.get(server);
        return entry == null ? "OFF_OR_STARTING" : entry.state;
    }
}
