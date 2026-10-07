package com.sande.mythictrpg.recording.server;

import com.google.gson.*;
import com.sande.mythictrpg.ai.experiencecontract.ExperienceRoomEvidence;
import com.sande.mythictrpg.ai.api.RoomEvidenceReference;
import com.sande.mythictrpg.gameplay.watch.*;
import com.sande.mythictrpg.recording.api.RecordingRecords.*;
import net.minecraft.server.MinecraftServer;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import static com.sande.mythictrpg.gameplay.watch.WatchContract.*;

/** Game-only repair of already archived knowledge. Never enumerates new proofs or replays game actions.
 * One bounded page per pump; old game proof guards remain authoritative while reconciliation is pending. */
public final class WatchKnowledgeReconciler {
    public static final int PAGE_SIZE = 32;
    public record Entry(SourceRef source, KnowledgeReceipt knowledge) {
        public Entry { Objects.requireNonNull(source); Objects.requireNonNull(knowledge); }
    }
    /** Entries sorted by canonical receipt ID; nextAfterReceiptId is the last entry ID, or empty at end. */
    public record Page(List<Entry> entries, String nextAfterReceiptId) {
        public Page {
            entries = List.copyOf(entries); Objects.requireNonNull(nextAfterReceiptId);
            if (entries.size() > PAGE_SIZE || !nextAfterReceiptId.isEmpty() && entries.isEmpty()) throw new IllegalArgumentException("watch page budget");
        }
    }
    public record Checkpoint(long watchRevision, long archiveWatermark) {
        public Checkpoint { if (watchRevision < 0 || archiveWatermark < 0) throw new IllegalArgumentException("watch checkpoint"); }
    }
    /** Implemented by the game storage owner. Page reads must enforce the fixed committed watermark.
     * Checkpoint housekeeping must not advance committedWatermark itself (otherwise it triggers itself forever). */
    public interface ArchiveAccess {
        UUID worldId();
        UUID datasetId();
        long committedWatermark();
        CompletableFuture<Optional<Checkpoint>> loadCheckpoint();
        CompletableFuture<Page> page(long fixedWatermark, String afterReceiptId, int limit);
        CompletableFuture<WriteReceipt> invalidate(KnowledgeInvalidation invalidation);
        CompletableFuture<Boolean> checkpoint(Checkpoint checkpoint);
    }
    public interface Probe {
        boolean current();
        long durableRevision();
        CompletableFuture<AsyncGodWatch.ReconciliationSnapshot> reconcile(Audience audience, Set<UUID> observations);
        void dispatch(Runnable task);
    }
    public record Status(String state, boolean busy, long completedWatchRevision, long completedArchiveWatermark, long checked, long withdrawn) { }
    private record Descriptor(UUID worldId, String godId, UUID subjectId, UUID observationId, String sha256) { }
    private record Pending(Entry entry, Descriptor descriptor, JsonObject evidence, Audience audience) { }
    private static final Gson JSON = new Gson();
    private final ArchiveAccess archive;
    private final Probe probe;
    private final Thread owner = Thread.currentThread();
    private Checkpoint completed = new Checkpoint(0, 0), pass;
    private boolean loaded, closed, regressed;
    private String after = "", state = "NOT_STARTED";
    private String uncertain = "";
    private CompletableFuture<Void> active;
    private long checked, withdrawn;

    public WatchKnowledgeReconciler(ArchiveAccess archive, Probe probe) {
        this.archive = Objects.requireNonNull(archive); this.probe = Objects.requireNonNull(probe);
    }
    /** Factory performs no I/O and does not start, resume or otherwise authorize a watch. */
    public static Probe gameProbe(MinecraftServer server, GameWatchGateway gateway, BooleanSupplier archiveCurrent) {
        return new Probe() {
            public boolean current() {
                if (!server.isSameThread() || !archiveCurrent.getAsBoolean()) return false;
                var runtime = GodWatchRuntime.current(server);
                return runtime != null && runtime.gateway() == gateway && gateway.gameCurrent() && gateway.status().state().equals("READY");
            }
            public long durableRevision() { return gateway.committedRevision(); }
            public CompletableFuture<AsyncGodWatch.ReconciliationSnapshot> reconcile(Audience audience, Set<UUID> ids) {
                return gateway.reconcileExact(audience, ids);
            }
            public void dispatch(Runnable task) { server.execute(task); }
        };
    }
    public void pump() {
        requireOwner();
        if (active != null && active.isDone()) { active = null; pass = null; after = ""; uncertain = ""; state = "DISPATCH_UNAVAILABLE"; }
        if (closed || regressed || active != null || !probe.current()) return;
        var token = new CompletableFuture<Void>(); active = token;
        try {
            if (!loaded) {
                state = "LOADING_CHECKPOINT";
                receive(token, archive.loadCheckpoint(), checkpoint -> {
                    completed = checkpoint.orElse(new Checkpoint(0, 0)); loaded = true;
                    finish(token, "CHECKPOINT_LOADED");
                }); return;
            }
            long revision = probe.durableRevision(), watermark = archive.committedWatermark();
            if (revision < completed.watchRevision() || watermark < completed.archiveWatermark()
                    || pass != null && (revision < pass.watchRevision() || watermark < pass.archiveWatermark())) {
                regressed = true; finish(token, "DURABLE_CURSOR_REGRESSED"); return;
            }
            if (pass == null) {
                if (revision == completed.watchRevision() && watermark == completed.archiveWatermark()) { finish(token, "CURRENT"); return; }
                pass = new Checkpoint(revision, watermark); after = ""; uncertain = "";
            }
            state = "READING_ARCHIVE";
            receive(token, archive.page(pass.archiveWatermark(), after, PAGE_SIZE), page -> page(token, page));
        } catch (RuntimeException unavailable) { failed(token, "RECONCILIATION_UNAVAILABLE"); }
    }
    private void page(CompletableFuture<Void> token, Page page) {
        String previous = after;
        var pending = new ArrayList<Pending>();
        for (var entry : page.entries()) {
            String key = entry.knowledge().receiptId().toString();
            if (key.compareTo(previous) <= 0) throw new IllegalArgumentException("unordered archive receipts");
            previous = key;
            try { pending.add(parse(entry)); }
            catch (RuntimeException invalid) { uncertain("INVALID_WATCH_RECEIPT"); }
        }
        if (!page.nextAfterReceiptId().isEmpty() && !page.nextAfterReceiptId().equals(previous))
            throw new IllegalArgumentException("invalid archive next cursor");
        next(token, page, pending, 0);
    }
    private Pending parse(Entry entry) {
        var source = entry.source(); var knowledge = entry.knowledge();
        if (!archive.worldId().equals(source.worldId()) || !archive.datasetId().equals(source.datasetId())
                || !source.owner().equals("action-ledger-v1")
                || !Set.of(SourceKind.ACTION_OBSERVED, SourceKind.ACTIVITY_OBSERVED).contains(source.kind())
                || !knowledge.acquisition().equals("DIRECT_WATCH") || knowledge.permittedProjection().length() > 32768)
            throw new IllegalArgumentException("foreign watch receipt");
        var projection = JsonParser.parseString(knowledge.permittedProjection()).getAsJsonObject();
        if (!projection.keySet().equals(Set.of("memoryMode", "experience", "evidence"))
                || !projection.get("memoryMode").getAsString().equals("PERSONAL")) throw new IllegalArgumentException("unknown watch projection");
        var evidence = projection.getAsJsonObject("evidence");
        if (!evidence.keySet().equals(Set.of("kind", "payload")) || !evidence.get("kind").getAsString().equals(ExperienceRoomEvidence.KIND))
            throw new IllegalArgumentException("unknown watch evidence");
        var descriptorJson = JsonParser.parseString(evidence.get("payload").getAsString()).getAsJsonObject();
        if (!descriptorJson.keySet().equals(Set.of("worldId", "godId", "subjectId", "observationId", "sha256")))
            throw new IllegalArgumentException("unknown watch descriptor");
        var descriptor = JSON.fromJson(descriptorJson, Descriptor.class);
        Objects.requireNonNull(descriptor.subjectId()); Objects.requireNonNull(descriptor.observationId());
        if (!source.worldId().equals(descriptor.worldId()) || !knowledge.godId().equals(descriptor.godId())
                || descriptor.sha256() == null || !descriptor.sha256().matches("[0-9a-f]{64}")
                || !knowledge.permittedAudience().equals(Set.of(new ActorRef(ActorKind.GOD, descriptor.godId()),
                    new ActorRef(ActorKind.PLAYER, descriptor.subjectId().toString())))) throw new IllegalArgumentException("foreign watch scope");
        var experience = projection.getAsJsonObject("experience");
        if (!experience.get("observationId").getAsString().equals(descriptor.observationId().toString())
                || !experience.get("eventId").getAsString().equals(source.sourceId())
                || experience.get("sourceRevision").getAsLong() != source.revision()) throw new IllegalArgumentException("source mismatch");
        var audience = new Audience(source.worldId(), new Key(descriptor.godId(), descriptor.subjectId()), Set.of(descriptor.subjectId()),
                new Ref("recording-reconcile/" + archive.datasetId(), 1));
        return new Pending(entry, descriptor, descriptorJson, audience);
    }
    private void next(CompletableFuture<Void> token, Page page, List<Pending> entries, int index) {
        if (active != token || !probe.current()) { failed(token, "WATCH_RUNTIME_STALE"); return; }
        if (index == entries.size()) {
            if (!page.nextAfterReceiptId().isEmpty()) { after = page.nextAfterReceiptId(); finish(token, "PARTIAL"); return; }
            if (!uncertain.isEmpty()) {
                String reason = uncertain; pass = null; after = ""; uncertain = ""; finish(token, reason); return;
            }
            var checkpoint = pass;
            receive(token, archive.checkpoint(checkpoint), accepted -> {
                if (!accepted) { failed(token, "CHECKPOINT_UNCONFIRMED"); return; }
                completed = checkpoint; pass = null; after = ""; finish(token, "CURRENT");
            }); return;
        }
        Pending pending = entries.get(index); state = "CHECKING_EXACT_PROOF";
        receive(token, probe.reconcile(pending.audience(), Set.of(pending.descriptor().observationId())), snapshot -> {
            if (!snapshot.available() || !snapshot.worldId().equals(archive.worldId()) || !snapshot.audience().equals(pending.audience())
                    || snapshot.committedRevision() < pass.watchRevision() || snapshot.proofs().size() != 1
                    || !snapshot.proofs().getFirst().observationId().equals(pending.descriptor().observationId())) {
                failed(token, "WATCH_PROOF_UNAVAILABLE"); return;
            }
            var result = snapshot.proofs().getFirst();
            if (result.state() == AsyncGodWatch.ProofState.UNKNOWN) {
                uncertain("WATCH_PROOF_UNKNOWN"); next(token, page, entries, index + 1); return;
            }
            boolean revoke = result.state() != AsyncGodWatch.ProofState.CURRENT;
            if (!revoke) {
                Proof proof = result.proof();
                if (!proof.worldId().equals(archive.worldId()) || !proof.observerGodId().equals(pending.descriptor().godId())
                        || !proof.observerTarget().equals(pending.descriptor().subjectId())
                        || !proof.eventId().toString().equals(pending.entry().source().sourceId())
                        || proof.sourceRevision() != pending.entry().source().revision()) {
                    uncertain("WATCH_SOURCE_MISMATCH"); next(token, page, entries, index + 1); return;
                }
                var view = new AsyncGodWatch.ReadSnapshot(pending.audience(), new View(true, "READY", List.of(proof)),
                        Map.of(proof.id(), result.eligibility()));
                RoomEvidenceReference reference = ExperienceRoomEvidence.capture(view).get(proof.id());
                revoke = !JsonParser.parseString(reference.payload()).equals(pending.evidence());
            }
            checked++;
            if (!revoke) { next(token, page, entries, index + 1); return; }
            if (snapshot.committedRevision() <= 0) { failed(token, "WATCH_REVISION_UNKNOWN"); return; }
            var invalidation = new KnowledgeInvalidation(pending.entry().source(), pending.entry().knowledge().receiptId(),
                    snapshot.committedRevision(), result.state() == AsyncGodWatch.ProofState.REVOKED ? "WATCH_PROOF_REVOKED" : "WATCH_PROJECTION_CHANGED");
            state = "WITHDRAWING_RECEIPT";
            receive(token, archive.invalidate(invalidation), written -> {
                if (written.status() != com.sande.mythictrpg.recording.api.RecordingRecords.Status.STORED
                        && written.status() != com.sande.mythictrpg.recording.api.RecordingRecords.Status.DUPLICATE) {
                    uncertain("WATCH_WITHDRAWAL_UNCONFIRMED"); next(token, page, entries, index + 1); return;
                }
                withdrawn++; next(token, page, entries, index + 1);
            });
        });
    }
    private <T> void receive(CompletableFuture<Void> token, CompletableFuture<T> future, Consumer<T> use) {
        // Timeout releases only this adapter slot. A late underlying write remains idempotent and may finish safely.
        future.copy().orTimeout(10, TimeUnit.SECONDS).whenComplete((value, error) -> {
            try { probe.dispatch(() -> {
                if (active != token || closed) return;
                try {
                    if (error != null || value == null || !probe.current()) { failed(token, "RECONCILIATION_UNAVAILABLE"); return; }
                    use.accept(value);
                } catch (RuntimeException invalid) { failed(token, "INVALID_RECONCILIATION_DATA"); }
            }); } catch (RuntimeException stopped) { token.complete(null); }
        });
    }
    private void uncertain(String reason) { if (uncertain.isEmpty()) uncertain = reason; }
    private void failed(CompletableFuture<Void> token, String reason) { if (active == token) { pass = null; after = ""; uncertain = ""; finish(token, reason); } }
    private void finish(CompletableFuture<Void> token, String reason) {
        if (active == token) { state = reason; active = null; token.complete(null); }
    }
    public Status status() { requireOwner(); return new Status(state, active != null, completed.watchRevision(), completed.archiveWatermark(), checked, withdrawn); }
    public void close() { requireOwner(); closed = true; if (active != null) { active.complete(null); active = null; } pass = null; state = "CLOSED"; }
    private void requireOwner() { if (Thread.currentThread() != owner) throw new IllegalStateException("reconciler requires game owner thread"); }
}
