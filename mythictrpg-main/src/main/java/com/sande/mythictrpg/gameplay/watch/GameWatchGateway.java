package com.sande.mythictrpg.gameplay.watch;

import com.sande.mythictrpg.data.god.GodDefinitionManager;
import com.sande.mythictrpg.gameplay.ledger.ActionRecord;
import com.sande.mythictrpg.gameplay.ledger.AsyncActionLedger;
import com.sande.mythictrpg.gameplay.ledger.server.ActionLedgerService;
import static com.sande.mythictrpg.gameplay.watch.WatchContract.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Opt-in game adapter. Only the server-local explicit trial runtime installs it; no automatic content thresholds.
 * The existing GodAttentionState is an eligibility input ONLY; it is never auto-converted to ACTIVE.
 */
public final class GameWatchGateway implements AutoCloseable {
    public interface Rules {
        Optional<Approval> approve(MinecraftServer server, UUID world, Key target);
        Scene captureScene(MinecraftServer server, ActionRecord.Draft event);
    }
    private final MinecraftServer server;
    private final ActionLedgerService.Runtime runtime;
    private final Rules rules;
    private final long definitionsGeneration;
    private final AsyncGodWatch watch;
    private final Map<UUID, ServerPlayer> targetSessions = new HashMap<>();
    private final Map<UUID, CompletableFuture<List<Watch>>> suspending = new HashMap<>();

    public GameWatchGateway(MinecraftServer server, Rules explicitlyApprovedRules, AsyncGodWatch.Limits limits) {
        this.server = Objects.requireNonNull(server); requireServerThread();
        rules = Objects.requireNonNull(explicitlyApprovedRules);
        runtime = Objects.requireNonNull(ActionLedgerService.current(server), "raw runtime unavailable");
        if (runtime.worldId() == null || runtime.ledger() == null) throw new IllegalStateException("raw ledger disabled");
        definitionsGeneration = GodDefinitionManager.INSTANCE.generation();
        watch = new AsyncGodWatch(server.getWorldPath(LevelResource.ROOT).resolve("mythictrpg-god-watch-v1"),
                runtime.worldId(), runtime.captureSession(), runtime.ledger(), limits);
    }
    public CompletableFuture<Watch> start(ResourceLocation god, UUID target) {
        requireCurrent();
        var suspension = suspending.get(target);
        if (suspension != null) {
            if (!suspension.isDone() || suspension.isCompletedExceptionally() || suspension.isCancelled())
                return CompletableFuture.failedFuture(new IllegalStateException("suspension not durably confirmed"));
            suspending.remove(target); targetSessions.remove(target);
        }
        if (GodDefinitionManager.INSTANCE.find(god).isEmpty() || server.getPlayerList().getPlayer(target) == null)
            return CompletableFuture.failedFuture(new IllegalArgumentException("unknown god/offline target"));
        if (targetSessions.containsKey(target) && targetSessions.get(target) != server.getPlayerList().getPlayer(target))
            return CompletableFuture.failedFuture(new IllegalStateException("suspend previous target session first"));
        Key key = new Key(god.toString(), target);
        Optional<Approval> approval = rules.approve(server, runtime.worldId(), key);
        if (approval.isEmpty() || !approval.get().key().equals(key) || !approval.get().worldId().equals(runtime.worldId()))
            return CompletableFuture.failedFuture(new IllegalArgumentException("watch not explicitly approved"));
        targetSessions.put(target, server.getPlayerList().getPlayer(target));
        return watch.start(UUID.randomUUID(), approval.get());
    }
    /** Future completion-hook routing replaces raw.submit, never calls both for one occurrence. */
    public AsyncGodWatch.Capture capture(ActionRecord.Draft draft) {
        requireCurrent();
        ServerPlayer approvedSession = targetSessions.get(draft.actorId());
        if (suspending.containsKey(draft.actorId()) || approvedSession != null && server.getPlayerList().getPlayer(draft.actorId()) != approvedSession)
            throw new IllegalStateException("target session requires suspension/revalidation");
        return watch.capture(draft, rules.captureScene(server, draft));
    }
    public CompletableFuture<List<Proof>> observeSubmitted(ActionRecord.Draft draft, AsyncActionLedger.Submission raw) {
        requireCurrent();
        ServerPlayer session = targetSessions.get(draft.actorId());
        if (suspending.containsKey(draft.actorId()) || session != null && server.getPlayerList().getPlayer(draft.actorId()) != session)
            return CompletableFuture.failedFuture(new IllegalStateException("target requires revalidation"));
        return watch.observeSubmitted(draft, rules.captureScene(server, draft), raw);
    }
    /** Future logout/respawn integration must call this before approving a new player session. */
    public CompletableFuture<List<Watch>> suspendTarget(UUID playerId, Ref cause) {
        requireCurrent();
        var receipt = watch.suspendTarget(playerId, cause); suspending.put(playerId, receipt); return receipt;
    }
    public CompletableFuture<Watch> stop(UUID watchId, long revision, State state, Ref cause) {
        requireCurrent(); return watch.transition(watchId, revision, state, cause);
    }
    public AsyncGodWatch.Status status() { requireServerThread(); return watch.status(); }
    public CompletableFuture<AsyncGodWatch.ReadSnapshot> read(Audience audience) { requireCurrent(); return watch.read(audience, 16); }
    public CompletableFuture<AsyncGodWatch.ReadSnapshot> readExact(Audience audience, Set<UUID> observationIds) {
        requireCurrent(); return watch.readExact(audience, observationIds);
    }
    public CompletableFuture<AsyncGodWatch.ReconciliationSnapshot> reconcileExact(Audience audience, Set<UUID> observationIds) {
        requireCurrent(); return watch.reconcileExact(audience, observationIds);
    }
    public long committedRevision() { requireCurrent(); return watch.committedRevision(); }
    public boolean current(AsyncGodWatch.ReadSnapshot snapshot, Audience audience) {
        try { requireCurrent(); return watch.current(snapshot, audience); } catch (IllegalStateException stale) { return false; }
    }
    public CompletableFuture<Void> disclose(Disclosure disclosure) { requireCurrent(); return watch.disclose(disclosure); }
    public boolean gameCurrent() { try { requireCurrent(); return true; } catch (IllegalStateException stale) { return false; } }
    public boolean currentWatch(Watch value, ServerPlayer player) {
        return gameCurrent() && value != null && !suspending.containsKey(player.getUUID())
                && targetSessions.get(player.getUUID()) == player
                && value.approval().key().playerId().equals(player.getUUID()) && watch.currentWatch(value);
    }
    public void requestClose() { requireServerThread(); watch.requestClose(); }
    public CompletableFuture<Void> ready() { return watch.ready(); }
    private void requireCurrent() {
        requireServerThread();
        if (ActionLedgerService.current(server) != runtime || GodDefinitionManager.INSTANCE.generation() != definitionsGeneration)
            throw new IllegalStateException("stale game watch gateway: rebuild/revalidate after reload or restart");
    }
    private void requireServerThread() { if (!server.isSameThread()) throw new IllegalStateException("server thread required"); }
    @Override public void close() { requireServerThread(); watch.close(); }
}
