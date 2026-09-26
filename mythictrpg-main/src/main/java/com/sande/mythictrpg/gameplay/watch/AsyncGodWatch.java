package com.sande.mythictrpg.gameplay.watch;

import com.sande.mythictrpg.gameplay.ledger.*;
import static com.sande.mythictrpg.gameplay.watch.WatchContract.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/** Game-side ordered coordinator. One bounded worker, no Minecraft state access on its worker. */
public final class AsyncGodWatch implements AutoCloseable {
    public record Limits(long maxBytes, int maxEntries, int queueCapacity) {
        public Limits {
            if (maxBytes < WatchJournal.MAX_FRAME + 8 || maxBytes > (1L << 40) || maxEntries < 1 || maxEntries > 100_000
                    || queueCapacity < 1 || queueCapacity > 4096) throw new IllegalArgumentException("watch limits");
        }
    }
    public record Status(String state, long usedBytes, long maxBytes, long observationCursor, long rejected, String reason) {}
    public record ReadSnapshot(Audience audience, View view, Map<UUID, Ref> eligibilityRefs) {
        public ReadSnapshot { eligibilityRefs = Map.copyOf(eligibilityRefs); }
        public ReadSnapshot(Audience audience, View view) { this(audience, view, Map.of()); }
    }
    public record Capture(AsyncActionLedger.Submission raw, CompletableFuture<List<Proof>> observed) {}
    private interface Operation<T> { T run(WatchStore store) throws Exception; }
    private interface Work { void run(WatchStore store) throws Exception; void fail(Throwable t); }
    private final Thread owner = Thread.currentThread();
    private final UUID world, captureSession;
    private final AsyncActionLedger raw;
    private final BlockingQueue<Work> queue;
    private final CompletableFuture<Void> ready = new CompletableFuture<>(), stopped = new CompletableFuture<>();
    private volatile boolean closing;
    private volatile Status status;
    private long rejected, lastCaptureOrder;
    // Fail closed immediately on the game thread, before asynchronous mutation commits.
    private final Set<UUID> invalidEvents = new HashSet<>(), invalidProofs = new HashSet<>();
    private final Set<Ref> invalidRefs = new HashSet<>();
    private final Map<String, Disclosure> changedDisclosure = new HashMap<>();

    public AsyncGodWatch(Path path, UUID world, UUID captureSession, AsyncActionLedger raw, Limits limits) {
        this(path, world, captureSession, raw, limits, point -> {});
    }
    AsyncGodWatch(Path path, UUID world, UUID captureSession, AsyncActionLedger raw, Limits limits, WatchJournal.Faults faults) {
        this.world = Objects.requireNonNull(world); this.captureSession = Objects.requireNonNull(captureSession); this.raw = Objects.requireNonNull(raw);
        queue = new ArrayBlockingQueue<>(limits.queueCapacity()); status = new Status("STARTING", 0, limits.maxBytes(), 0, 0, "OPENING");
        var fence = raw.fence();
        Thread worker = new Thread(() -> run(path, limits, faults, fence), "mythictrpg-god-watch"); worker.setDaemon(true); worker.start();
    }
    /** Called in capture order on the same server thread as raw submits. Does not wait for either disk. */
    public Capture capture(ActionRecord.Draft draft, Scene scene) {
        requireOwner();
        validateCapture(draft, scene);
        var submission = raw.submit(draft);
        return new Capture(submission, observeSubmitted(draft, scene, submission));
    }
    /** Raw is submitted exactly once by the completion hook even if observing is disabled/fails. */
    public CompletableFuture<List<Proof>> observeSubmitted(ActionRecord.Draft draft, Scene scene, AsyncActionLedger.Submission submission) {
        requireOwner();
        validateCapture(draft, scene);
        lastCaptureOrder = draft.captureOrder();
        return submit(store -> {
            ActionRecord committed = await(submission.durable());
            if(submission.transitionRetry() && !committed.event().equals(draft)
                    && ActionRecord.sameTransition(committed.event(),draft)) return List.of(); // No retroactive Scene on retry/relogin.
            if (!committed.event().equals(draft)) throw new IllegalArgumentException("receipt does not match captured event");
            return store.observe(committed, scene);
        });
    }
    private void validateCapture(ActionRecord.Draft draft, Scene scene) {
        // This API handles new occurrences only. Replay uses the persisted receipt, never a new Scene.
        if (!captureSession.equals(draft.captureSession()) || draft.captureOrder() <= lastCaptureOrder
                || !draft.occurrenceId().equals(scene.eventId()) || !draft.captureSession().equals(scene.captureSession())
                || draft.captureOrder() != scene.captureOrder()) throw new IllegalArgumentException("stale capture/session");
    }
    public CompletableFuture<Watch> start(UUID watchId, Approval approval) {
        requireOwner(); var fence = raw.fence();
        return submit(store -> store.start(watchId, approval, Math.addExact(await(fence), 1)));
    }
    public CompletableFuture<Watch> transition(UUID id, long expectedRevision, State next, Ref cause) {
        requireOwner(); var fence = raw.fence();
        return submit(store -> store.transition(id, expectedRevision, next, Math.addExact(await(fence), 1), cause));
    }
    /** Logout/target-generation invalidation is technical suspension, not an authored relationship threshold. */
    public CompletableFuture<List<Watch>> suspendTarget(UUID target, Ref cause) {
        requireOwner(); var fence = raw.fence();
        return submit(store -> store.suspendTarget(target, Math.addExact(await(fence), 1), cause));
    }
    public CompletableFuture<Void> revokeEvent(UUID eventId) { requireOwner(); invalidEvents.add(eventId); return submit(store -> { store.revokeEvent(eventId); return null; }); }
    public CompletableFuture<Void> revokeProof(UUID proofId) { requireOwner(); invalidProofs.add(proofId); return submit(store -> { store.revokeProof(proofId); return null; }); }
    public CompletableFuture<Void> revokeRef(Ref ref) { requireOwner(); invalidRefs.add(ref); return submit(store -> { store.revokeRef(ref); return null; }); }
    public CompletableFuture<Void> disclose(Disclosure rule) { requireOwner(); changedDisclosure.put(rule.ref().id(), rule); return submit(store -> { store.disclose(rule); return null; }); }
    public CompletableFuture<List<Watch>> states() { return submit(WatchStore::states); }
    public CompletableFuture<ReadSnapshot> read(Audience audience, int limit) {
        return submit(store -> {
            View view = checkedView(store, audience, limit);
            return new ReadSnapshot(audience, view, store.eligibilityRefs(view.proofs()));
        });
    }
    public CompletableFuture<ReadSnapshot> readExact(Audience audience, Set<UUID> observationIds) {
        Set<UUID> ids = Set.copyOf(observationIds);
        if (ids.size() > 64) throw new IllegalArgumentException("exact observation budget");
        return submit(store -> {
            View view = checkedView(store, store.viewExact(audience, ids));
            return new ReadSnapshot(audience, view, store.eligibilityRefs(view.proofs()));
        });
    }
    /** Synchronous, memory-only final-use guard: no tick blocking and no read/commit TOCTOU window. */
    public boolean current(ReadSnapshot snapshot, Audience audience) {
        requireOwner();
        if (closing || !status.state().equals("READY") || raw.status().state() != AsyncActionLedger.State.READY
                || !snapshot.audience().equals(audience) || !snapshot.view().available()) return false;
        for (Proof p : snapshot.view().proofs()) {
            Ref eligibility = snapshot.eligibilityRefs().get(p.id());
            if (eligibility == null || invalidEvents.contains(p.eventId()) || invalidProofs.contains(p.id())
                    || invalidRefs.contains(p.policy()) || invalidRefs.contains(p.context()) || invalidRefs.contains(eligibility)) return false;
            for (Value v : p.visibleProjection().values()) for (Ref ref : v.subjectRules().values()) {
                Disclosure changed = changedDisclosure.get(ref.id());
                if (invalidRefs.contains(ref) || changed != null && (!changed.ref().equals(ref)
                        || !changed.allowedAudience().containsAll(audience.players()))) return false;
            }
        }
        return true;
    }
    /** Relevant evidence/fields + exact game session, not an unrelated player's global revision. Stage4 must call before use. */
    public CompletableFuture<Boolean> revalidate(ReadSnapshot previous, Audience current) {
        return submit(store -> {
            if (!previous.audience().equals(current) || !previous.view().available()) return false;
            View now = checkedView(store, store.viewExact(current, previous.view().proofs().stream()
                    .map(Proof::id).collect(java.util.stream.Collectors.toSet())));
            if (!now.available()) return false;
            for (Proof proof : previous.view().proofs()) {
                if (!now.proofs().contains(proof)) return false;
            }
            return true;
        });
    }
    private View checkedView(WatchStore store, Audience audience, int limit) throws Exception {
        return checkedView(store, store.view(audience, limit));
    }
    private View checkedView(WatchStore store, View view) throws Exception {
        if (raw.status().state() != AsyncActionLedger.State.READY) return new View(false, "SOURCE_UNAVAILABLE", List.of());
        if (!view.available()) return view;
        for (Proof proof : view.proofs()) {
            ActionLedgerStore.Page page;
            try { page = await(raw.after(new ActionLedgerStore.Cursor(world, proof.observedAtSequence() - 1), proof.observerTarget(), 1)); }
            catch (Exception failure) { return new View(false, "SOURCE_UNAVAILABLE", List.of()); }
            if (page.records().isEmpty() || page.records().getFirst().sequence() != proof.observedAtSequence()
                    || !page.records().getFirst().event().occurrenceId().equals(proof.eventId())
                    || page.records().getFirst().event().sourceRevision() != proof.sourceRevision()
                    || !page.records().getFirst().event().sourceRef().equals(proof.sourceRef())) {
                store.revokeEvent(proof.eventId()); return new View(false, "SOURCE_INVALID", List.of());
            }
        }
        return view;
    }
    private synchronized <T> CompletableFuture<T> submit(Operation<T> operation) {
        requireOwner();
        if (closing || !status.state().equals("READY")) { rejected++; return CompletableFuture.failedFuture(new IOException("WATCH_UNAVAILABLE")); }
        CompletableFuture<T> result = new CompletableFuture<>();
        Work work = new Work() {
            public void run(WatchStore store) throws Exception {
                T value;
                try { value = operation.run(store); }
                catch (IllegalArgumentException | NullPointerException invalid) { result.completeExceptionally(invalid); return; }
                publish(store, "READY", "READY"); result.complete(value);
            }
            public void fail(Throwable t) { result.completeExceptionally(t); }
        };
        if (!queue.offer(work)) { rejected++; result.completeExceptionally(new IOException("WATCH_QUEUE_FULL")); }
        return result;
    }
    private void run(Path path, Limits limits, WatchJournal.Faults faults, CompletableFuture<Long> fence) {
        WatchStore store = null; Work current = null;
        try {
            long head = await(fence);
            store = new WatchStore(path, world, limits.maxBytes(), limits.maxEntries(), faults);
            store.beginRun(head); publish(store, "READY", "READY"); ready.complete(null);
            while (!closing || !queue.isEmpty()) {
                current = queue.poll(100, TimeUnit.MILLISECONDS);
                if (current != null) { current.run(store); current = null; }
            }
            store.markClean(); store.close(); publish(store, "CLOSED", "DRAINED"); stopped.complete(null);
        } catch (Throwable failure) {
            synchronized (this) { closing = true; rejected++; }
            if (current != null) current.fail(failure);
            Work remaining; while ((remaining = queue.poll()) != null) { synchronized (this) { rejected++; } remaining.fail(failure); }
            if (store != null) try { store.close(); } catch (IOException ignored) { }
            publish(store, "FAILED", String.valueOf(failure.getMessage())); ready.completeExceptionally(failure); stopped.completeExceptionally(failure);
        }
    }
    private synchronized void publish(WatchStore store, String state, String reason) {
        status = new Status(state, store == null ? status.usedBytes() : store.bytes(), status.maxBytes(), store == null ? status.observationCursor() : store.cursor(), rejected, reason);
    }
    public synchronized Status status() { return new Status(status.state(), status.usedBytes(), status.maxBytes(), status.observationCursor(), rejected, status.reason()); }
    public CompletableFuture<Void> ready() { return ready; }
    private static <T> T await(CompletableFuture<T> value) throws Exception { return value.get(5, TimeUnit.SECONDS); }
    private void requireOwner() { if (Thread.currentThread() != owner) throw new IllegalStateException("Watch input requires owning game thread"); }
    public synchronized void requestClose() { requireOwner(); closing = true; }
    public boolean awaitClose(long millis) {
        requestClose();
        try { stopped.get(millis, TimeUnit.MILLISECONDS); return true; }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
        catch (ExecutionException | TimeoutException failure) { return false; }
    }
    @Override public void close() { awaitClose(5000); }
}
