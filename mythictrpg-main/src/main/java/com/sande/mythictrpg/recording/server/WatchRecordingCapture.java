package com.sande.mythictrpg.recording.server;

import com.google.gson.*;
import com.sande.mythictrpg.ai.experiencecontract.*;
import com.sande.mythictrpg.ai.memorycontract.MemoryFoundationSettings;
import com.sande.mythictrpg.gameplay.ledger.ActionRecord;
import com.sande.mythictrpg.gameplay.ledger.server.ActionLedgerService;
import com.sande.mythictrpg.gameplay.watch.*;
import com.sande.mythictrpg.recording.api.ProducerCapability;
import com.sande.mythictrpg.recording.api.RecordingRecords;
import com.sande.mythictrpg.recording.api.RecordingRecords.*;
import net.minecraft.server.MinecraftServer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Game-owned post-commit adapter. Never reads the raw archive as knowledge or starts/replays a watch.
 * This records existing PERSONAL projections only; it does not itself expose a new reader or authorize later disclosure.
 * Individual proof revocation must not be translated into invalidating the shared multi-God raw source.
 */
public final class WatchRecordingCapture {
    static final String PRODUCER = "action-ledger-v1";
    private static final Gson JSON = new Gson();
    public record Outcome(Status status, int knowledgeCount, String reason) {
        public boolean complete() { return status == Status.STORED || status == Status.DUPLICATE; }
    }
    interface Probe {
        boolean current();
        CompletableFuture<AsyncGodWatch.ReadSnapshot> read(WatchContract.Audience audience, UUID observation);
        boolean current(AsyncGodWatch.ReadSnapshot snapshot, WatchContract.Audience audience);
        void dispatch(Runnable task);
    }
    private record Selected(AsyncGodWatch.ReadSnapshot snapshot, WatchContract.Audience audience,
                            KnowledgeReceipt knowledge) { }
    private final WorldRecordingService store;
    private final ProducerCapability producer;
    private final AtomicInteger pending = new AtomicInteger();

    WatchRecordingCapture(WorldRecordingService store) {
        this.store = Objects.requireNonNull(store);
        producer = store.registerProducer(PRODUCER, Set.of(), Set.of(SourceKind.ACTION_OBSERVED, SourceKind.ACTIVITY_OBSERVED));
    }
    WatchKnowledgeReconciler.ArchiveAccess reconciliationArchive() { return new WatchRecordingArchive(store, producer); }
    /** Invoke on the game thread only after BOTH the existing raw and watch writes completed. */
    public CompletableFuture<Outcome> capture(MinecraftServer server, GameWatchGateway gateway,
            ActionRecord raw, List<WatchContract.Proof> observed) {
        if (!server.isSameThread()) throw new IllegalStateException("Watch recording requires game thread");
        return capture(raw, observed, new Probe() {
            @Override public boolean current() {
                var watch = GodWatchRuntime.current(server); var ledger = ActionLedgerService.current(server);
                return MemoryFoundationSettings.mode() == MemoryFoundationSettings.Mode.PERSONAL
                        && RecordingRuntime.current(server).filter(s -> s == store).isPresent()
                        && watch != null && watch.gateway() == gateway && gateway.gameCurrent()
                        && ledger != null && ledger.captureSession().equals(raw.event().captureSession());
            }
            @Override public CompletableFuture<AsyncGodWatch.ReadSnapshot> read(WatchContract.Audience audience, UUID observation) {
                return gateway.readExact(audience, Set.of(observation));
            }
            @Override public boolean current(AsyncGodWatch.ReadSnapshot snapshot, WatchContract.Audience audience) {
                return gateway.current(snapshot, audience);
            }
            @Override public void dispatch(Runnable task) { server.execute(task); }
        });
    }
    CompletableFuture<Outcome> capture(ActionRecord raw, List<WatchContract.Proof> observed, Probe probe) {
        if (observed.isEmpty()) return CompletableFuture.completedFuture(unavailable("NO_DIRECT_OBSERVATION"));
        if (observed.size() > 64 || pending.incrementAndGet() > 16) {
            if (observed.size() <= 64) pending.decrementAndGet();
            gap("WATCH_CAPTURE_BUDGET"); return CompletableFuture.completedFuture(unavailable("WATCH_CAPTURE_BUDGET"));
        }
        var result = new CompletableFuture<Outcome>();
        result.whenComplete((done, failure) -> {
            pending.decrementAndGet();
            if (done != null && "WATCH_CAPTURE_TIMEOUT".equals(done.reason())) gap("WATCH_CAPTURE_TIMEOUT");
        });
        try {
            var proofs = observed.stream().sorted(Comparator.comparing(p -> p.observerGodId())).toList();
            if (!store.worldId().equals(raw.worldId()) || proofs.stream().map(WatchContract.Proof::observerGodId).distinct().count() != proofs.size()
                    || proofs.stream().anyMatch(p -> !matches(raw, p))) {
                result.complete(unavailable("FOREIGN_OBSERVATION")); return result;
            }
            next(raw, proofs, 0, new ArrayList<>(), probe, result);
        } catch (RuntimeException failure) { fail(result, "WATCH_CAPTURE_UNAVAILABLE"); }
        return result.completeOnTimeout(unavailable("WATCH_CAPTURE_TIMEOUT"), 2, TimeUnit.SECONDS);
    }
    private void next(ActionRecord raw, List<WatchContract.Proof> proofs, int index, List<Selected> selected,
            Probe probe, CompletableFuture<Outcome> result) {
        if (result.isDone()) return;
        try {
            if (!probe.current() || store.health().state() != WorldRecordingService.State.READY) {
                fail(result, "WATCH_CAPTURE_STALE"); return;
            }
            if (index == proofs.size()) {
                if (selected.isEmpty()) { result.complete(unavailable("NO_SUPPORTED_EXPERIENCE")); return; }
                if (selected.stream().anyMatch(s -> !probe.current(s.snapshot(), s.audience()))) { fail(result, "WATCH_PROOF_STALE"); return; }
                var source = source(raw);
                // One complete occurrence batch, not a per-God source retry that could conflict with its first receipt set.
                store.captureSource(producer, new SourceCapture(source, raw.sequence(), selected.stream().map(Selected::knowledge).toList()))
                        .whenComplete((written, failure) -> {
                            if (failure != null || written == null) { fail(result, "WATCH_WRITE_UNAVAILABLE"); return; }
                            if (written.status() != Status.STORED && written.status() != Status.DUPLICATE) gap(written.reasonCode());
                            result.complete(new Outcome(written.status(), written.status() == Status.STORED || written.status() == Status.DUPLICATE ? selected.size() : 0,
                                    written.reasonCode()));
                        });
                return;
            }
            var original = proofs.get(index);
            var audience = new WatchContract.Audience(raw.worldId(), new WatchContract.Key(original.observerGodId(), original.observerTarget()),
                    Set.of(original.observerTarget()), new WatchContract.Ref("recording-v2/" + store.runtimeEpoch(), 1));
            probe.read(audience, original.id()).whenComplete((snapshot, failure) -> {
                try { probe.dispatch(() -> {
                    if (result.isDone()) return;
                    try {
                        if (failure != null || snapshot == null || !probe.current() || !probe.current(snapshot, audience)
                                || snapshot.view().proofs().size() != 1 || !sameObservation(original, snapshot.view().proofs().getFirst())) {
                            fail(result, "WATCH_PROOF_UNAVAILABLE"); return;
                        }
                        var events = ExperienceProjection.project(snapshot.view(), raw.event().actorId(), ExperienceView.Relationship.UNKNOWN).events();
                        if (!events.isEmpty()) {
                            var proof = snapshot.view().proofs().getFirst();
                            var evidence = ExperienceRoomEvidence.capture(snapshot).get(proof.id());
                            String projection = JSON.toJson(new TreeMap<>(Map.of("memoryMode", "PERSONAL", "experience", events.getFirst(), "evidence", evidence)));
                            UUID id = UUID.nameUUIDFromBytes((store.datasetId().orElseThrow() + "/watch-knowledge/" + proof.id() + "/" + proof.revision()).getBytes(StandardCharsets.UTF_8));
                            var knowledge = new KnowledgeReceipt(id, proof.observerGodId(), proof.acquisitionKind(), projection,
                                    Set.of(new ActorRef(ActorKind.GOD, proof.observerGodId()), new ActorRef(ActorKind.PLAYER, proof.observerTarget().toString())), proof.policy().revision());
                            selected.add(new Selected(snapshot, audience, knowledge));
                        }
                        next(raw, proofs, index + 1, selected, probe, result);
                    } catch (RuntimeException unavailable) { fail(result, "WATCH_PROJECTION_UNAVAILABLE"); }
                }); } catch (RuntimeException unavailable) { fail(result, "WATCH_DISPATCH_UNAVAILABLE"); }
            });
        } catch (RuntimeException unavailable) { fail(result, "WATCH_CAPTURE_UNAVAILABLE"); }
    }
    private SourceRef source(ActionRecord raw) {
        var kind = raw.event().type() == ActionRecord.Type.OBSERVED_ACTIVITY_SUMMARY ? SourceKind.ACTIVITY_OBSERVED : SourceKind.ACTION_OBSERVED;
        var json = JSON.toJsonTree(raw).getAsJsonObject();
        // Details.participants is a set, unlike any ordered JSON array; give that set a stable hash order across JVMs.
        var event = json.getAsJsonObject("event");
        if (event.has("details") && !event.get("details").isJsonNull()) {
            var participants = new JsonArray(); raw.event().details().participants().stream().sorted().forEach(id -> participants.add(id.toString()));
            event.getAsJsonObject("details").add("participants", participants);
        }
        return new SourceRef(raw.worldId(), store.datasetId().orElseThrow(), kind, PRODUCER, raw.event().occurrenceId().toString(),
                raw.event().sourceRevision(), RecordingRecords.sha256(JSON.toJson(canonical(json))));
    }
    private static JsonElement canonical(JsonElement value) {
        if (value.isJsonObject()) { var sorted = new JsonObject(); value.getAsJsonObject().keySet().stream().sorted()
                .forEach(key -> sorted.add(key, canonical(value.getAsJsonObject().get(key)))); return sorted; }
        if (value.isJsonArray()) { var array = new JsonArray(); value.getAsJsonArray().forEach(item -> array.add(canonical(item))); return array; }
        return value;
    }
    private static boolean matches(ActionRecord raw, WatchContract.Proof proof) {
        return proof.worldId().equals(raw.worldId()) && proof.eventId().equals(raw.event().occurrenceId())
                && proof.sourceRevision() == raw.event().sourceRevision() && proof.sourceRef().equals(raw.event().sourceRef())
                && proof.observedAtSequence() == raw.sequence() && proof.observerTarget().equals(raw.event().actorId());
    }
    private static boolean sameObservation(WatchContract.Proof original, WatchContract.Proof current) {
        return original.visibleProjection().entrySet().containsAll(current.visibleProjection().entrySet())
                && original.equals(new WatchContract.Proof(current.id(), current.worldId(), current.eventId(), current.sourceRevision(), current.sourceRef(),
                current.observerTarget(), current.observerGodId(), current.watchId(), current.policy(), current.context(), current.observedAtSequence(),
                original.visibleProjection(), current.revision()));
    }
    private void gap(String code) { store.captureGap(producer, code); }
    private void fail(CompletableFuture<Outcome> result, String code) { gap(code); result.complete(unavailable(code)); }
    private static Outcome unavailable(String reason) { return new Outcome(Status.UNAVAILABLE, 0, reason); }
}
