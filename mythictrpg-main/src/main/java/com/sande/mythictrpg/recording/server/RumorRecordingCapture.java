package com.sande.mythictrpg.recording.server;

import com.google.gson.Gson;
import com.sande.mythictrpg.recording.api.ProducerCapability;
import com.sande.mythictrpg.recording.api.RecordingRecords.*;
import com.sande.mythictrpg.rumor.RumorLedger;
import com.sande.mythictrpg.rumor.RumorRecordingState;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Mirrors confirmed game receipts, never publishes, delivers or believes a rumor on behalf of a God. */
final class RumorRecordingCapture {
    static final String PRODUCER = "rumor-saved-data-v1";
    private static final Gson JSON = new Gson();
    record Outcome(Status status, long cursor, int receipts, String reason) {
        boolean complete() { return status == Status.STORED || status == Status.DUPLICATE; }
    }
    private record Failure(Status status, String reason) { }
    private record Batch(int receipts, Failure failure) { }
    private record Attempt(WriteReceipt receipt, int count) { }
    private final WorldRecordingService store;
    private final ProducerCapability producer;
    private CompletableFuture<Outcome> pending;
    private UUID completedLineage;
    private long completedCursor = -1;

    RumorRecordingCapture(WorldRecordingService store) {
        this.store = Objects.requireNonNull(store);
        producer = store.registerProducer(PRODUCER, Set.of(), Set.of(SourceKind.RUMOR_RECEIVED));
    }

    synchronized CompletableFuture<Outcome> afterPending(RumorRecordingState.DurableView view) {
        return pending == null || pending.isDone() ? capture(view)
                : pending.handle((ignored, failure) -> null).thenCompose(ignored -> capture(view));
    }

    /** No Minecraft access: the same immutable confirmed snapshot can be reconciled after the final save fence. */
    synchronized CompletableFuture<Outcome> capture(RumorRecordingState.DurableView view) {
        var state = view.metadata();
        if (!store.worldId().equals(state.worldId())) return failed(state.cursor(), "FOREIGN_RUMOR_WORLD");
        if (pending != null && !pending.isDone()) return failed(state.cursor(), "RUMOR_CAPTURE_BUSY");
        if (state.lineage().equals(completedLineage) && state.cursor() == completedCursor)
            return CompletableFuture.completedFuture(new Outcome(Status.DUPLICATE, state.cursor(), 0, "SNAPSHOT_ALREADY_CAPTURED"));
        var result = new CompletableFuture<Outcome>(); pending = result;
        try {
            store.registerSource(producer, new SourceRegistration(store.worldId(), store.datasetId().orElseThrow(),
                    PRODUCER, state.lineage(), state.cursor())).whenComplete((registered, failure) -> {
                if (failure != null || registered == null) { result.complete(unavailable(state.cursor(), "RUMOR_SOURCE_UNAVAILABLE")); return; }
                if (!success(registered.receipt()) || registered.cutoverCursor().isEmpty()) {
                    result.complete(new Outcome(registered.receipt().status(), state.cursor(), 0, registered.receipt().reasonCode())); return;
                }
                try {
                    var sources = new HashMap<UUID, RumorLedger.Evidence>();
                    view.snapshot().evidence().forEach(e -> sources.put(e.id(), e));
                    var claims = view.snapshot().claims().stream().filter(c -> {
                        var stamp = state.roots().get(c.rootId());
                        return stamp != null && stamp.bornCursor() > registered.cutoverCursor().getAsLong();
                    }).sorted(Comparator.comparing(RumorLedger.Claim::revoked).reversed()
                            .thenComparing(c -> c.rootId().toString())).toList();
                    captureClaims(view, sources, claims, result);
                } catch (RuntimeException invalid) { result.complete(unavailable(state.cursor(), "INVALID_DURABLE_RUMOR")); }
            });
        } catch (RuntimeException unavailable) { result.complete(unavailable(state.cursor(), "RUMOR_CAPTURE_UNAVAILABLE")); }
        return result;
    }

    private void captureClaims(RumorRecordingState.DurableView view, Map<UUID, RumorLedger.Evidence> sources,
            List<RumorLedger.Claim> claims, CompletableFuture<Outcome> result) {
        // Sequential bounded writes, but not fail-fast: a bad grant must never starve another source's withdrawal.
        // A future chain also avoids recursive synchronous completion when quota rejects thousands of grants.
        CompletableFuture<Batch> chain = CompletableFuture.completedFuture(new Batch(0, null));
        for (var claim : claims) chain = chain.thenCompose(batch -> attempt(view, sources.get(claim.rootId()), claim)
                .handle((attempt, failure) -> {
                    if (failure != null || attempt == null) return new Batch(batch.receipts(), batch.failure() != null
                            ? batch.failure() : new Failure(Status.UNAVAILABLE, "RUMOR_WRITE_UNAVAILABLE"));
                    var written = attempt.receipt();
                    if (!success(written)) return new Batch(batch.receipts(), batch.failure() != null
                            ? batch.failure() : new Failure(written.status(), written.reasonCode()));
                    return new Batch(batch.receipts() + attempt.count(), batch.failure());
                }).toCompletableFuture());
        chain.whenComplete((batch, failure) -> {
            if (failure != null || batch == null) { result.complete(unavailable(view.metadata().cursor(), "RUMOR_WRITE_UNAVAILABLE")); return; }
            if (batch.failure() != null) {
                result.complete(new Outcome(batch.failure().status(), view.metadata().cursor(), batch.receipts(), batch.failure().reason())); return;
            }
            synchronized (this) { completedLineage = view.metadata().lineage(); completedCursor = view.metadata().cursor(); }
            result.complete(new Outcome(Status.STORED, view.metadata().cursor(), batch.receipts(), "DURABLE_RUMOR_SNAPSHOT_CAPTURED"));
        });
    }

    private CompletionStage<Attempt> attempt(RumorRecordingState.DurableView view, RumorLedger.Evidence evidence, RumorLedger.Claim claim) {
        try {
            var stamp = view.metadata().roots().get(claim.rootId());
            CompletionStage<WriteReceipt> write;
            int count;
            if (claim.revoked()) {
                // Game revoke increments the original claim revision; a pre-publication tombstone remains revision 1.
                // Withdrawal needs only the original source key, never a still-readable proof or claim-body projection.
                long revision = Math.max(1, claim.revision() - 1);
                var source = new SourceRef(store.worldId(), store.datasetId().orElseThrow(), SourceKind.RUMOR_RECEIVED,
                        PRODUCER, claim.rootId().toString(), revision,
                        com.sande.mythictrpg.recording.api.RecordingRecords.sha256(view.metadata().lineage() + "/withdraw/" + claim.rootId() + "/" + revision));
                write = store.invalidate(producer, new SourceInvalidation(source, stamp.claimCursor(), "GAME_RUMOR_REVOKED")); count = 0;
            } else {
                // Unproven observations are reported as incomplete, never grants, without blocking unrelated sources.
                if (evidence == null || evidence.proof() == null) return failedAttempt(claim, "RUMOR_SOURCE_PROOF_UNAVAILABLE");
                var acquired = new ArrayList<AcquiredKnowledge>();
                for (var receipt : view.snapshot().receipts()) {
                    if (!receipt.rootId().equals(claim.rootId()) || receipt.revision() != claim.revision()) continue;
                    var grant = view.receipt(receipt.rootId(), receipt.godId(), receipt.revision()).orElse(null);
                    if (grant == null || grant.acquiredCursor() <= 0) continue; // OFF/legacy reception is never retroactive.
                    acquired.add(new AcquiredKnowledge(knowledge(view, evidence, claim, receipt), grant.acquiredCursor()));
                }
                write = store.appendKnowledge(producer, new SourceKnowledgeCapture(source(view, evidence, claim, claim.revision()),
                        view.metadata().lineage(), stamp.bornCursor(), stamp.claimCursor(), acquired)); count = acquired.size();
            }
            return write.thenApply(written -> new Attempt(written, count));
        } catch (RuntimeException invalid) { return failedAttempt(claim, "INVALID_DURABLE_RUMOR"); }
    }
    private static CompletionStage<Attempt> failedAttempt(RumorLedger.Claim claim, String reason) {
        return CompletableFuture.completedFuture(new Attempt(WriteReceipt.failed(Status.UNAVAILABLE, claim.rootId().toString(), reason), 0));
    }

    private SourceRef source(RumorRecordingState.DurableView view, RumorLedger.Evidence evidence, RumorLedger.Claim claim, long revision) {
        // Do not copy the original world-event payload, coordinates or another God's assessment into the archive.
        String hash = com.sande.mythictrpg.recording.api.RecordingRecords.sha256(JSON.toJson(List.of(
                view.metadata().lineage(), evidence.id(), evidence.subject(), evidence.proof().sourceId(),
                evidence.proof().sourceRevision(), evidence.proof().excerptHash(), revision, claim.text(), claim.epithet(),
                evidence.disclosureAudience().stream().map(UUID::toString).sorted().toList())));
        return new SourceRef(store.worldId(), store.datasetId().orElseThrow(), SourceKind.RUMOR_RECEIVED,
                PRODUCER, evidence.id().toString(), revision, hash);
    }

    private KnowledgeReceipt knowledge(RumorRecordingState.DurableView view, RumorLedger.Evidence evidence,
            RumorLedger.Claim claim, RumorLedger.Receipt received) {
        String stable = store.datasetId().orElseThrow() + "/" + PRODUCER + "/" + view.metadata().lineage()
                + "/" + claim.rootId() + "/" + claim.revision() + "/" + received.godId();
        UUID receiptId = UUID.nameUUIDFromBytes(stable.getBytes(StandardCharsets.UTF_8));
        var audience = new HashSet<ActorRef>();
        evidence.disclosureAudience().forEach(p -> audience.add(new ActorRef(ActorKind.PLAYER, p.toString())));
        audience.add(new ActorRef(ActorKind.GOD, received.godId()));
        var projection = new TreeMap<String, Object>();
        projection.put("memoryMode", "RUMOR_TEST"); projection.put("rootId", claim.rootId());
        projection.put("subjectPlayerId", evidence.subject()); projection.put("godId", received.godId());
        projection.put("claimRevision", claim.revision()); projection.put("claim", claim.text()); projection.put("epithet", claim.epithet());
        projection.put("acquisition", "RUMOR_RECEIVED");
        projection.put("assessmentStatus", "CURRENT_GAME_LOOKUP_REQUIRED");
        // Reputation commits independently. An unknown durable assessment is not an actual UNASSESSED judgment.
        return new KnowledgeReceipt(receiptId, received.godId(), "RUMOR_RECEIVED", JSON.toJson(projection), audience, claim.revision());
    }

    private static boolean success(WriteReceipt receipt) { return receipt.status() == Status.STORED || receipt.status() == Status.DUPLICATE; }
    private static Outcome unavailable(long cursor, String reason) { return new Outcome(Status.UNAVAILABLE, cursor, 0, reason); }
    private static CompletableFuture<Outcome> failed(long cursor, String reason) { return CompletableFuture.completedFuture(unavailable(cursor, reason)); }
}
