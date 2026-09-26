package com.sande.mythictrpg.gameplay.ledger.server;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.gameplay.ledger.*;
import com.sande.mythictrpg.rumor.RumorSavedData;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/** Lifecycle and diagnostics only. No subscriptions to quests, rumors, AI or rewards. */
public final class ActionLedgerService {
    private static final Map<MinecraftServer, Runtime> RUNTIMES = new IdentityHashMap<>();
    private ActionLedgerService() {}
    public static final class Runtime {
        private final UUID captureSession = UUID.randomUUID();
        private long captureOrder, missedAtStartup;
        private long lastReportedGap, lastGapNoticeUtc;
        private String reason = "STARTING", lastFailure = "";
        private boolean closed;
        private UUID worldId;
        private AsyncActionLedger ledger;
        private com.sande.mythictrpg.gameplay.ledger.detail.DetailSettings detailSettings = com.sande.mythictrpg.gameplay.ledger.detail.DetailSettings.OFF;
        private final com.sande.mythictrpg.gameplay.ledger.detail.MovementSamples movement = new com.sande.mythictrpg.gameplay.ledger.detail.MovementSamples();
        public com.sande.mythictrpg.gameplay.ledger.detail.DetailSettings detailSettings() { return detailSettings; }
        public com.sande.mythictrpg.gameplay.ledger.detail.MovementSamples movement() { return movement; }
        private final LedgerCapacityWarning warning = new LedgerCapacityWarning();
        private final ExecutorService bootstrap = Executors.newSingleThreadExecutor(job -> {
            Thread thread = new Thread(job, "mythictrpg-ledger-config"); thread.setDaemon(true); return thread;
        });
        public UUID captureSession() { return captureSession; }
        public long nextCaptureOrder() { return ++captureOrder; }
        public UUID worldId() { return worldId; }
        public AsyncActionLedger ledger() { return ledger; }
        public String reason() { return ledger == null ? reason : ledger.status().state() + ":" + ledger.status().reason(); }
        public boolean acceptsCapture() {
            if (closed) return false;
            if (ledger != null) return true; // submit reports STARTING/FAILED with a gap, not a fake success.
            if (reason.equals("STARTING")) missedAtStartup++;
            return false;
        }
    }
    public static Runtime current(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Ledger snapshot requires server thread");
        return RUNTIMES.get(server);
    }
    public static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        Runtime runtime = new Runtime();
        if (RUNTIMES.putIfAbsent(server, runtime) != null) { runtime.bootstrap.shutdownNow(); return; }
        Path config = server.getServerDirectory().resolve("config/mythictrpg/action-ledger.json");
        Path directory = server.getWorldPath(LevelResource.ROOT).resolve("mythictrpg-action-ledger-v1");
        runtime.bootstrap.submit(() -> {
            var loaded = ActionLedgerSettings.load(config);
            var detailSettings = com.sande.mythictrpg.gameplay.ledger.detail.DetailSettings.load(config.resolveSibling("action-detail.json"));
            var watchSettings = com.sande.mythictrpg.gameplay.watch.WatchTrialSettings.load(
                    config.resolveSibling("watch-trial.json"));
            com.sande.mythictrpg.gameplay.watch.RewardWatchSettings rewardWatch;
            boolean watchConfigValid;
            try {
                rewardWatch = com.sande.mythictrpg.gameplay.watch.RewardWatchSettings.load(config.resolveSibling("reward-watch.json"));
                watchConfigValid = true;
            } catch (IllegalArgumentException invalid) {
                rewardWatch = com.sande.mythictrpg.gameplay.watch.RewardWatchSettings.OFF;
                watchConfigValid = false; // Do not let a trial bypass malformed occlusion rules.
                MythicTrpg.LOGGER.error("Remote observation disabled; raw recording unaffected", invalid);
            }
            final var rewardWatchSettings = rewardWatch;
            final boolean allowWatch = watchConfigValid;
            server.execute(() -> {
                if (runtime.closed || RUNTIMES.get(server) != runtime) return;
                runtime.reason = loaded.reason();
                runtime.detailSettings = detailSettings;
                if (!loaded.settings().enabled()) {
                    MythicTrpg.LOGGER.info("Action ledger {}", runtime.reason); return;
                }
                try {
                    var memoryWorld = RumorSavedData.get(server);
                    if (!memoryWorld.ready()) { runtime.reason = "UNAVAILABLE_WORLD_ID"; return; }
                    runtime.worldId = memoryWorld.worldId(); // Existing authoritative game world identity, not a second ID system.
                    runtime.ledger = new AsyncActionLedger(directory, runtime.worldId, loaded.settings().limits(), loaded.settings().queueCapacity());
                    runtime.ledger.gap("STARTUP_CAPTURE_GAP", runtime.missedAtStartup);
                    runtime.ledger.ready().thenRun(() -> server.execute(() -> {
                        if (!runtime.closed && RUNTIMES.get(server) == runtime && allowWatch)
                            com.sande.mythictrpg.gameplay.watch.GodWatchRuntime.install(server, watchSettings, rewardWatchSettings);
                    }));
                } catch (RuntimeException failure) {
                    runtime.reason = "UNAVAILABLE_INITIALIZATION";
                    MythicTrpg.LOGGER.error("Action ledger unavailable; existing game services remain unchanged", failure);
                }
            });
            runtime.bootstrap.shutdown();
        });
    }
    public static void onServerTickPost(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer(); Runtime runtime = current(server);
        if (runtime == null || runtime.closed || runtime.ledger == null || server.getTickCount() % 20 != 0) return;
        com.sande.mythictrpg.gameplay.watch.GodWatchRuntime.tick(server);
        var status = runtime.ledger.status();
        if (runtime.warning.shouldNotify(status.usedBytes(), status.maxBytes(), System.currentTimeMillis())) {
            String notice = "[MythicTRPG] 행동 기록 저장소가 보관 용량의 90% 이상 사용 중입니다. 관리자는 action-ledger.json의 maxStorageBytes를 늘리고 정상 재시작해 주세요. 기록을 자동 삭제하지 않습니다.";
            server.getPlayerList().broadcastSystemMessage(Component.literal(notice), false);
            MythicTrpg.LOGGER.warn("{} used={} limit={}", notice, status.usedBytes(), status.maxBytes());
        }
        if (status.state() == AsyncActionLedger.State.FAILED && !runtime.lastFailure.equals(status.reason())) {
            runtime.lastFailure = status.reason();
            String notice = "[MythicTRPG] 행동 기록 중단: " + status.reason() + ". 관리자 확인 필요. 게임 진행은 유지되지만 이 구간은 기록되지 않습니다.";
            MythicTrpg.LOGGER.error(notice);
            server.getPlayerList().getPlayers().stream().filter(p -> p.hasPermissions(2))
                    .forEach(p -> p.sendSystemMessage(Component.literal(notice)));
        }
        long now = System.currentTimeMillis();
        if (status.state() == AsyncActionLedger.State.READY && status.rejected() > runtime.lastReportedGap
                && (runtime.lastReportedGap == 0 || now - runtime.lastGapNoticeUtc >= 300_000)) {
            runtime.lastReportedGap = status.rejected(); runtime.lastGapNoticeUtc = now;
            String notice = "[MythicTRPG] 행동 원장에 저장 거절/누락 진단이 있습니다(누적 " + status.rejected()
                    + "). 관리자는 /mythadmin ledger status로 확인하세요. 완전한 행동 기록이 아닐 수 있습니다.";
            MythicTrpg.LOGGER.warn(notice);
            server.getPlayerList().getPlayers().stream().filter(p -> p.hasPermissions(2))
                    .forEach(p -> p.sendSystemMessage(Component.literal(notice)));
        }
    }
    public static void onServerStopped(ServerStoppedEvent event) {
        com.sande.mythictrpg.gameplay.watch.GodWatchRuntime.stop(event.getServer());
        Runtime runtime = RUNTIMES.remove(event.getServer()); if (runtime == null) return;
        runtime.closed = true; runtime.bootstrap.shutdownNow();
        if (runtime.ledger != null && !runtime.ledger.awaitClose(5000))
            MythicTrpg.LOGGER.error("Action ledger drain failed/timed out; pending writes are not confirmed durable");
    }
}
