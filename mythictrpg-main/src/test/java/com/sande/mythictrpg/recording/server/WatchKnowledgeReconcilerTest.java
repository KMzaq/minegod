package com.sande.mythictrpg.recording.server;

import com.google.gson.Gson;
import com.sande.mythictrpg.ai.experiencecontract.ExperienceRoomEvidence;
import com.sande.mythictrpg.gameplay.watch.AsyncGodWatch;
import com.sande.mythictrpg.recording.api.RecordingRecords.*;
import static com.sande.mythictrpg.gameplay.watch.WatchContract.*;
import static com.sande.mythictrpg.recording.server.WatchKnowledgeReconciler.*;
import java.util.*;
import java.util.concurrent.*;

/** Deterministic game-owner scheduler test. Real Watch journal states are exercised by WatchExactEvidenceTest. */
public final class WatchKnowledgeReconcilerTest {
    private static final Gson JSON = new Gson();
    private static final UUID WORLD = UUID.randomUUID(), DATASET = UUID.randomUUID(), PLAYER = UUID.randomUUID(), EVENT = UUID.randomUUID();
    private static final String G = "mythictrpg:fortuna", H = "mythictrpg:demeter";
    private static int checks;
    private static final class FakeArchive implements ArchiveAccess {
        final List<Entry> entries = new ArrayList<>(); final Map<UUID, KnowledgeInvalidation> tombstones = new HashMap<>();
        long watermark; Checkpoint checkpoint; int pages, checkpoints; boolean deny;
        CompletableFuture<Boolean> heldCheckpoint;
        public UUID worldId() { return WORLD; }
        public UUID datasetId() { return DATASET; }
        public long committedWatermark() { return watermark; }
        public CompletableFuture<Optional<Checkpoint>> loadCheckpoint() { return CompletableFuture.completedFuture(Optional.ofNullable(checkpoint)); }
        public CompletableFuture<Page> page(long fixed, String after, int limit) {
            pages++; check(limit == PAGE_SIZE && fixed <= watermark, "bounded immutable page request");
            var selected = entries.stream().filter(e -> !tombstones.containsKey(e.knowledge().receiptId()) && e.knowledge().receiptId().toString().compareTo(after) > 0)
                    .sorted(Comparator.comparing(e -> e.knowledge().receiptId().toString())).limit(limit + 1L).toList();
            boolean more = selected.size() > limit; var page = selected.stream().limit(limit).toList();
            return CompletableFuture.completedFuture(new Page(page, more ? page.getLast().knowledge().receiptId().toString() : ""));
        }
        public CompletableFuture<WriteReceipt> invalidate(KnowledgeInvalidation value) {
            if (deny) return CompletableFuture.completedFuture(WriteReceipt.failed(com.sande.mythictrpg.recording.api.RecordingRecords.Status.UNAVAILABLE, value.receiptId().toString(), "TEST_DENIED"));
            boolean duplicate = tombstones.putIfAbsent(value.receiptId(), value) != null;
            return CompletableFuture.completedFuture(new WriteReceipt(duplicate ? com.sande.mythictrpg.recording.api.RecordingRecords.Status.DUPLICATE
                    : com.sande.mythictrpg.recording.api.RecordingRecords.Status.STORED, OptionalLong.of(watermark), value.receiptId().toString(), "TEST_ACK"));
        }
        public CompletableFuture<Boolean> checkpoint(Checkpoint value) {
            checkpoints++;
            if (heldCheckpoint != null) return heldCheckpoint.thenApply(okay -> { if (okay) checkpoint = value; return okay; });
            checkpoint = value; return CompletableFuture.completedFuture(true);
        }
    }
    private static final class FakeProbe implements Probe {
        final Queue<Runnable> callbacks = new ArrayDeque<>(); final Map<UUID, AsyncGodWatch.ReconciledProof> proofs = new HashMap<>();
        long revision = 10; boolean live = true, rejectDispatch;
        public boolean current() { return live; }
        public long durableRevision() { return revision; }
        public CompletableFuture<AsyncGodWatch.ReconciliationSnapshot> reconcile(Audience audience, Set<UUID> observations) {
            var found = observations.stream().map(id -> proofs.getOrDefault(id, new AsyncGodWatch.ReconciledProof(id, AsyncGodWatch.ProofState.UNKNOWN, null, null))).toList();
            return CompletableFuture.completedFuture(new AsyncGodWatch.ReconciliationSnapshot(WORLD, audience, revision, true, "READY", found));
        }
        public void dispatch(Runnable callback) { if (rejectDispatch) throw new RejectedExecutionException("test"); callbacks.add(callback); }
        void drain() { int remaining = 10000; while (!callbacks.isEmpty() && remaining-- > 0) callbacks.remove().run(); check(remaining > 0, "callbacks bounded"); }
    }
    private static Entry add(FakeArchive archive, FakeProbe probe, String god) {
        UUID observation = UUID.randomUUID(); Ref eligibility = new Ref("test:eligibility/" + god, 1);
        Proof proof = new Proof(observation, WORLD, EVENT, 1, "test:source", PLAYER, god, UUID.randomUUID(),
                new Ref("test:policy/" + god, 1), new Ref("test:scene", 1), 1,
                Map.of(Field.ACTION, new Value("MATURE_CROP_REMOVED", Map.of(PLAYER, new Ref("test:disclosure", 1)))), 1);
        probe.proofs.put(observation, new AsyncGodWatch.ReconciledProof(observation, AsyncGodWatch.ProofState.CURRENT, proof, eligibility));
        var audience = new Audience(WORLD, new Key(god, PLAYER), Set.of(PLAYER), new Ref("test:scope", 1));
        var snapshot = new AsyncGodWatch.ReadSnapshot(audience, new View(true, "READY", List.of(proof)), Map.of(observation, eligibility));
        var source = new SourceRef(WORLD, DATASET, SourceKind.ACTION_OBSERVED, "action-ledger-v1", EVENT.toString(), 1, "a".repeat(64));
        String projection = JSON.toJson(Map.of("memoryMode", "PERSONAL", "experience", Map.of("observationId", observation,
                "eventId", EVENT, "sourceRevision", 1), "evidence", ExperienceRoomEvidence.capture(snapshot).get(observation)));
        var knowledge = new KnowledgeReceipt(UUID.randomUUID(), god, "DIRECT_WATCH", projection,
                Set.of(new ActorRef(ActorKind.GOD, god), new ActorRef(ActorKind.PLAYER, PLAYER.toString())), 1);
        var entry = new Entry(source, knowledge); archive.entries.add(entry); archive.watermark++; return entry;
    }
    private static UUID observation(Entry entry) {
        return UUID.fromString(com.google.gson.JsonParser.parseString(entry.knowledge().permittedProjection()).getAsJsonObject()
                .getAsJsonObject("experience").get("observationId").getAsString());
    }
    private static void runPass(WatchKnowledgeReconciler service, FakeProbe probe) {
        for (int i = 0; i < 10; i++) { service.pump(); probe.drain(); if (service.status().state().equals("CURRENT")) return; }
    }
    public static void run() {
        var archive = new FakeArchive(); var probe = new FakeProbe(); var first = add(archive, probe, G); var second = add(archive, probe, H);
        var service = new WatchKnowledgeReconciler(archive, probe); runPass(service, probe);
        check(archive.tombstones.isEmpty() && archive.checkpoint.equals(new Checkpoint(10, 2)), "current exact receipts complete without rewriting sources");
        int pages = archive.pages; service.pump(); probe.drain(); check(archive.pages == pages, "unchanged checkpoint does not rescan itself");
        UUID firstId = observation(first); var original = probe.proofs.get(firstId);
        probe.proofs.put(firstId, new AsyncGodWatch.ReconciledProof(firstId, AsyncGodWatch.ProofState.REVOKED, null, original.eligibility())); probe.revision++;
        archive.deny = true; service.pump(); probe.drain();
        check(archive.checkpoint.watchRevision() == 10 && archive.tombstones.isEmpty(), "failed withdrawal cannot advance checkpoint");
        archive.deny = false; runPass(service, probe);
        check(archive.tombstones.keySet().equals(Set.of(first.knowledge().receiptId())), "one God's proof withdrawal never invalidates other God's shared source receipt");
        check(archive.tombstones.get(first.knowledge().receiptId()).stateVersion() == probe.revision, "tombstone uses durable watch revision");
        var late = add(archive, probe, G); UUID lateId = observation(late);
        probe.proofs.put(lateId, new AsyncGodWatch.ReconciledProof(lateId, AsyncGodWatch.ProofState.REVOKED, null, new Ref("test:eligibility", 1)));
        runPass(service, probe);
        check(archive.tombstones.containsKey(late.knowledge().receiptId()) && archive.checkpoint.archiveWatermark() == 3, "late archive receipt triggers reconciliation even without a new watch revision");
        var missing = add(archive, probe, G); probe.proofs.remove(observation(missing)); service.pump(); probe.drain();
        check(service.status().state().equals("WATCH_PROOF_UNKNOWN") && archive.checkpoint.archiveWatermark() == 3
                && !archive.tombstones.containsKey(missing.knowledge().receiptId()), "unknown proof never becomes a permanent revocation or successful checkpoint");
        archive.entries.remove(missing); runPass(service, probe);
        UUID secondId = observation(second); var other = probe.proofs.get(secondId);
        probe.proofs.put(secondId, new AsyncGodWatch.ReconciledProof(secondId, AsyncGodWatch.ProofState.DISCLOSURE_CHANGED, null, other.eligibility())); probe.revision++;
        runPass(service, probe); check(archive.tombstones.get(second.knowledge().receiptId()).reasonCode().equals("WATCH_PROJECTION_CHANGED"), "disclosure change withdraws only captured projection");
        service.close(); var restarted = new WatchKnowledgeReconciler(archive, probe); runPass(restarted, probe);
        check(restarted.status().completedArchiveWatermark() == archive.watermark, "restart loads durable reconciliation checkpoint");
        probe.revision = archive.checkpoint.watchRevision() - 1; restarted.pump(); probe.drain();
        check(restarted.status().state().equals("DURABLE_CURSOR_REGRESSED"), "rolled-back watch journal does not silently bootstrap a new baseline"); restarted.close();
        boundedAndStale();
        System.out.println("WatchKnowledgeReconcilerTest: " + checks + " checks passed; no LLM/network");
    }
    private static void boundedAndStale() {
        var archive = new FakeArchive(); var probe = new FakeProbe(); for (int i = 0; i < 35; i++) add(archive, probe, G);
        var service = new WatchKnowledgeReconciler(archive, probe); service.pump(); probe.drain(); service.pump(); probe.drain();
        check(service.status().state().equals("PARTIAL") && archive.checkpoints == 0, "one page does not checkpoint the entire archive");
        service.pump(); probe.drain(); check(archive.checkpoints == 1 && archive.checkpoint.archiveWatermark() == 35, "all page acknowledgements are required before checkpoint");
        probe.revision++; archive.heldCheckpoint = new CompletableFuture<>(); service.pump(); probe.drain(); service.pump(); probe.drain();
        check(service.status().busy(), "checkpoint future remains pending"); probe.live = false; archive.heldCheckpoint.complete(true); probe.drain();
        check(!service.status().busy() && !service.status().state().equals("CURRENT"), "late checkpoint cannot mark stale runtime current"); service.close();
        var dispatchArchive = new FakeArchive(); var dispatchProbe = new FakeProbe(); var dispatch = new WatchKnowledgeReconciler(dispatchArchive, dispatchProbe);
        dispatchProbe.rejectDispatch = true; dispatch.pump(); dispatchProbe.rejectDispatch = false; runPass(dispatch, dispatchProbe);
        check(dispatch.status().state().equals("CURRENT"), "dispatch rejection releases slot on next owner-thread pump"); dispatch.close();
        var mixedArchive = new FakeArchive(); var mixedProbe = new FakeProbe();
        for (int i = 0; i < 35; i++) add(mixedArchive, mixedProbe, i % 2 == 0 ? G : H);
        var ordered = mixedArchive.entries.stream().sorted(Comparator.comparing(e -> e.knowledge().receiptId().toString())).toList();
        var unknown = ordered.getFirst(); var revoked = ordered.getLast();
        mixedProbe.proofs.remove(observation(unknown));
        mixedProbe.proofs.put(observation(revoked), new AsyncGodWatch.ReconciledProof(observation(revoked), AsyncGodWatch.ProofState.REVOKED, null, new Ref("test:eligibility", 1)));
        var mixed = new WatchKnowledgeReconciler(mixedArchive, mixedProbe);
        mixed.pump(); mixedProbe.drain(); mixed.pump(); mixedProbe.drain();
        check(mixed.status().state().equals("PARTIAL"), "unknown early proof does not prevent later-page reconciliation");
        mixed.pump(); mixedProbe.drain();
        check(mixedArchive.tombstones.containsKey(revoked.knowledge().receiptId())
                        && !mixedArchive.tombstones.containsKey(unknown.knowledge().receiptId()),
                "unknown first receipt does not prevent revocation of another God's later receipt");
        check(mixedArchive.checkpoint == null && mixedArchive.checkpoints == 0 && mixed.status().state().equals("WATCH_PROOF_UNKNOWN"),
                "uncertain multi-page pass never advances complete checkpoint"); mixed.close();
    }
    private static void check(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }
}
