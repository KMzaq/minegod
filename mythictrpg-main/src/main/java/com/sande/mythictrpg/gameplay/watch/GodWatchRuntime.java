package com.sande.mythictrpg.gameplay.watch;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.gameplay.ledger.*;
import com.sande.mythictrpg.gameplay.ledger.server.ActionLedgerService;
import static com.sande.mythictrpg.gameplay.watch.WatchContract.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import java.util.*;
import java.util.concurrent.*;

/** Optional game-only lifecycle. AI absence/failure never controls raw recording or watch storage. */
public final class GodWatchRuntime {
    private static final Map<MinecraftServer, GodWatchRuntime> RUNTIMES = new IdentityHashMap<>();
    private final RewardWatchRules rules;
    private final boolean trialEnabled;
    private final Map<Key,UUID> started = new HashMap<>();
    private final Map<Key,Long> retryAfter = new HashMap<>();
    private final Map<Key,Watch> acquiredActive=new HashMap<>();
    private final ObservedActivity activity=new ObservedActivity();
    private final GameWatchGateway gateway;
    private final UUID world;
    private final LedgerCapacityWarning capacity = new LedgerCapacityWarning();
    private String lastFailure = "";
    private long rejected, lastGapNotice;
    private GodWatchRuntime(MinecraftServer server, WatchTrialSettings settings, RewardWatchSettings rewards) {
        rules = new RewardWatchRules(rewards); trialEnabled = settings.enabled();
        world = ActionLedgerService.current(server).worldId();
        gateway = new GameWatchGateway(server, rules, rewards.enabled() ? rewards.limits() : settings.limits());
    }
    public static GodWatchRuntime current(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("watch runtime requires server thread");
        return RUNTIMES.get(server);
    }
    public static void install(MinecraftServer server, WatchTrialSettings settings) {
        install(server, settings, RewardWatchSettings.OFF);
    }
    public static void install(MinecraftServer server, WatchTrialSettings settings, RewardWatchSettings rewards) {
        if ((!settings.enabled() && !rewards.enabled()) || current(server) != null) return;
        try { RUNTIMES.put(server, new GodWatchRuntime(server, settings, rewards)); }
        catch (RuntimeException failure) { MythicTrpg.LOGGER.error("Watch trial unavailable; raw ledger remains independent", failure); }
    }
    public GameWatchGateway gateway() { return gateway; }
    public CompletableFuture<Watch> startTrial(MinecraftServer server, ResourceLocation god, ServerPlayer player, int radius) {
        if (current(server) != this || !gateway.gameCurrent()) return CompletableFuture.failedFuture(new IllegalStateException("stale runtime"));
        if (!trialEnabled) return CompletableFuture.failedFuture(new IllegalStateException("development trial disabled"));
        rules.trials.register(world, god, player, radius);
        // Queue in order; no server-thread join. A failed disclosure cannot grant public access.
        gateway.disclose(new Disclosure(RewardWatchRules.disclosure(player.getUUID()), Set.of(player.getUUID())));
        return gateway.start(god, player.getUUID());
    }
    public CompletableFuture<List<Watch>> suspend(UUID player) {
        rules.trials.remove(player); started.keySet().removeIf(key -> key.playerId().equals(player));
        retryAfter.keySet().removeIf(key -> key.playerId().equals(player));
        acquiredActive.entrySet().removeIf(e->{if(e.getKey().playerId().equals(player)){activity.discard(e.getValue().id());return true;}return false;});
        return gateway.suspendTarget(player, new Ref("watch:target_suspended", 1));
    }
    public static void observed(MinecraftServer server, ActionRecord.Draft draft, AsyncActionLedger.Submission receipt) {
        var runtime = current(server); if (runtime == null) return;
        try {
            runtime.gateway.observeSubmitted(draft, receipt).whenComplete((proofs, failure) -> {
                if (failure != null) server.execute(() -> { if (current(server) == runtime) runtime.rejected++; });
            });
        } catch (RuntimeException unavailable) { runtime.rejected++; }
    }
    public static void tick(MinecraftServer server) {
        var r = current(server); if (r == null) return;
        if (!r.gateway.gameCurrent()) { r.activity.clear(); r.acquiredActive.clear(); r.gateway.requestClose(); } // Reload never broadens old policies.
        var s = r.gateway.status(); long now = System.currentTimeMillis();
        if (r.gateway.gameCurrent() && s.state().equals("READY")) { r.resumeAcquired(server); r.flushActivity(server); }
        if (r.capacity.shouldNotify(s.usedBytes(), s.maxBytes(), now)) {
            String file = r.rules.settings.enabled() ? "reward-watch.json" : "watch-trial.json";
            String msg = "[MythicTRPG] 관찰 증명 저장소가 보관 용량의 90% 이상입니다. 관리자는 " + file + "의 maxStorageBytes를 늘린 뒤 정상 재시작해 주세요. 자동 삭제하지 않습니다.";
            server.getPlayerList().broadcastSystemMessage(Component.literal(msg), false);
            var raw = ActionLedgerService.current(server).ledger().status();
            MythicTrpg.LOGGER.warn("{} watch={}/{} total={}/{} bytes", msg, s.usedBytes(), s.maxBytes(),
                    raw.usedBytes()+s.usedBytes(), raw.maxBytes()+s.maxBytes());
        }
        String failure = !r.gateway.gameCurrent() ? "DEFINITION_RELOAD_REQUIRES_RESTART" : s.state().equals("FAILED") ? s.reason() : "";
        if (!failure.isEmpty() && !failure.equals(r.lastFailure)) {
            r.lastFailure = failure; notifyAdmins(server, "관찰 저장/조회 중단: " + failure + ". 원본 기록과 게임 진행은 별개입니다.");
        }
        if (r.rejected > 0 && (r.lastGapNotice == 0 || now - r.lastGapNotice >= 300_000)) {
            r.lastGapNotice = now; notifyAdmins(server, "관찰 저장 거절/누락 진단 " + r.rejected + "건. /mythadmin watch status로 확인하세요. 목격하지 못한 과거를 소급 생성하지 않습니다.");
        }
    }
    private static void notifyAdmins(MinecraftServer server, String message) {
        MythicTrpg.LOGGER.warn(message); server.getPlayerList().getPlayers().stream().filter(p -> p.hasPermissions(2))
                .forEach(p -> p.sendSystemMessage(Component.literal("[MythicTRPG] " + message)));
    }
    private void resumeAcquired(MinecraftServer server) {
        if (!rules.settings.enabled()) return;
        var claims = com.sande.mythictrpg.quest.reward.RewardClaimState.get(server);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            for (var acquired : claims.watchesFor(player.getUUID())) {
                Key key = new Key(acquired.godId().toString(), player.getUUID());
                long tick=server.overworld().getGameTime();
                if (rules.settings.rule(key.godId()).isEmpty() || started.containsKey(key)
                        || tick<retryAfter.getOrDefault(key,0L)) continue;
                UUID attempt=UUID.randomUUID();started.put(key,attempt);
                gateway.disclose(new Disclosure(RewardWatchRules.disclosure(player.getUUID()), Set.of(player.getUUID())));
                gateway.start(acquired.godId(), player.getUUID()).whenComplete((watch, failure) -> {
                    server.execute(() -> {
                        if (current(server) != this || !attempt.equals(started.get(key)))return;
                        if(failure==null && server.getPlayerList().getPlayer(key.playerId())==player) acquiredActive.put(key,watch);
                        else {
                            rejected++; started.remove(key);
                            retryAfter.put(key,server.overworld().getGameTime()+600); // bounded technical retry, not a content threshold
                        }
                    });
                });
            }
        }
    }
    /** Only a successfully committed action can call this; no vanilla total-difference inference. */
    public static boolean activityEnabled(MinecraftServer server) {
        var r=current(server);return r!=null&&r.rules.settings.activityWindowTicks()>0&&!r.acquiredActive.isEmpty()&&r.gateway.gameCurrent();
    }
    public static void activity(MinecraftServer server,ActionRecord.Draft event) {
        var r=current(server);if(r==null||r.rules.settings.activityWindowTicks()==0||!r.gateway.gameCurrent())return;
        var scene=r.rules.captureScene(server,event);
        for(var w:r.acquiredActive.values())if(w.approval().key().playerId().equals(event.actorId()))r.activity.accept(w,event,scene);
    }
    private void flushActivity(MinecraftServer server) {
        var raw=ActionLedgerService.current(server);
        for(var summary:activity.due(server.overworld().getGameTime(),rules.settings.activityWindowTicks())) {
            var w=summary.watch();var last=summary.last();var player=server.getPlayerList().getPlayer(last.actorId());
            if(player==null||!w.equals(acquiredActive.get(w.approval().key())))continue;
            // Revalidate current location as well as every sample. Never bridge a current hidden interval.
            var pos=player.blockPosition();
            var current=new ActionRecord.Draft(UUID.randomUUID(),raw.captureSession(),raw.nextCaptureOrder(),last.sourceRef(),1,last.actorId(),last.subject(),
                    System.currentTimeMillis(),server.overworld().getGameTime(),player.level().getDayTime(),player.level().dimension().location().toString(),
                    new ActionRecord.Position(pos.getX(),pos.getY(),pos.getZ()),last.type(),last.outcome(),last.payload(),last.visibilityRef());
            var check=new ObservedActivity();if(!check.accept(w,current,rules.captureScene(server,current)))continue;
            var draft=new ActionRecord.Draft(UUID.randomUUID(),raw.captureSession(),raw.nextCaptureOrder(),"mythictrpg:detail/observed_activity_summary",1,
                    last.actorId(),new ActionRecord.Subject("ACTIVITY",last.subject().typeId(),null),last.occurredAtUtc(),last.gameTick(),last.gameDayTime(),
                    last.dimensionId(),last.position(),ActionRecord.Type.OBSERVED_ACTIVITY_SUMMARY,"COMPLETED",
                    Map.of("observer_god",w.approval().key().godId(),"watch_id",w.id().toString(),"count",Integer.toString(summary.count()),
                        "activity",last.type().name(),"first_tick",Long.toString(summary.firstTick()),"coverage","VISIBLE_COMMITTED_SAMPLES_ONLY_NOT_TOTAL_STATS"),
                    "mythictrpg:admin_only_unprojected",last.details());
            var receipt=raw.ledger().submit(draft);observed(server,draft,receipt);
        }
    }
    public String status() { var s = gateway.status(); return s + "; capture gaps=" + rejected
            + "; reward watch=" + rules.settings.enabled() + "; trial=" + trialEnabled + "; relationship threshold OFF"; }
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) { unavailable(event); }
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) { unavailable(event); }
    private static void unavailable(PlayerEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        var r = current(player.server); if (r != null) try { r.suspend(player.getUUID()); } catch (RuntimeException failure) { r.gateway.requestClose(); }
    }
    public static void stop(MinecraftServer server) {
        com.sande.mythictrpg.ai.experiencecontract.ExperienceRoomEvidence.clear(server);
        var r = RUNTIMES.remove(server);
        if (r != null) {
            r.gateway.close();
            if (!r.gateway.status().state().equals("CLOSED")) MythicTrpg.LOGGER.error("Watch shutdown not confirmed clean; pending observations/revocations require review");
        }
    }
}
