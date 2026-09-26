package com.sande.mythictrpg.gameplay.watch;

import com.sande.mythictrpg.gameplay.ledger.ActionRecord;
import static com.sande.mythictrpg.gameplay.watch.WatchContract.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/** Single worker owns all state. Raw receipts precede transactions; replay does not recalculate observations. */
final class WatchStore implements AutoCloseable {
    record Change(String kind, Watch watch, List<Proof> proofs, UUID eventId, long eventSequence,
                  Ref ref, UUID proofId, Disclosure disclosure) {}
    private record Seen(long sequence, List<UUID> proofs) {}
    private final UUID world;
    private final WatchJournal journal;
    private final Map<UUID, Watch> watches = new LinkedHashMap<>();
    private final Map<UUID, Map<String, UUID>> active = new HashMap<>();
    private final Map<UUID, Proof> proofs = new LinkedHashMap<>();
    private final Map<Key, List<UUID>> byObserver = new HashMap<>();
    private final Map<UUID, Seen> seen = new HashMap<>();
    private final Set<UUID> revokedEvents = new HashSet<>(), revokedProofs = new HashSet<>();
    private final Set<Ref> revokedRefs = new HashSet<>();
    private final Map<String, Disclosure> disclosures = new HashMap<>();
    private long cursor;
    private boolean hasEntries, cleanShutdown;

    WatchStore(Path path, UUID world, long maxBytes, int maxEntries, WatchJournal.Faults faults) throws IOException {
        this.world = world; journal = new WatchJournal(path, world, maxBytes, maxEntries, faults);
        try { journal.replay(this::apply); }
        catch (IOException failure) { journal.close(); throw failure; }
    }
    void recover(long rawHead) throws IOException {
        if (cursor > rawHead || watches.values().stream().anyMatch(w -> w.from() > rawHead + 1 || w.until() != null && w.until() > rawHead + 1))
            throw new IOException("WATCH_SOURCE_AHEAD");
        for (Watch w : List.copyOf(watches.values())) if (w.state() == State.ACTIVE)
            transition(w.id(), w.revision(), State.PAUSED, rawHead + 1, new Ref("mythictrpg:restart_requires_revalidation", 1));
    }
    void beginRun(long rawHead) throws IOException {
        // A killed process may have acknowledged an in-memory revocation whose write was still queued.
        // Until an administrator reviews that uncertainty, do not expose old facts after restart.
        if (hasEntries && !cleanShutdown) throw new IOException("UNCLEAN_WATCH_REQUIRES_REVIEW");
        recover(rawHead);
        commit(new Change("BOOT", null, null, null, 0, null, null, null));
    }
    void markClean() throws IOException { commit(new Change("CLEAN_CLOSE", null, null, null, 0, null, null, null)); }
    Watch start(UUID id, Approval approval, long from) throws IOException {
        if (!world.equals(approval.worldId())) throw new IllegalArgumentException("world");
        Watch old = watches.get(id);
        if (old != null) {
            if (!old.approval().equals(approval) || old.from() != from) throw new IllegalArgumentException("watch ID conflict");
            return old; // Retrying an ended watch never reactivates it.
        }
        if (active.getOrDefault(approval.key().playerId(), Map.of()).containsKey(approval.key().godId())) throw new IllegalArgumentException("already active");
        if (revokedRefs.contains(approval.policy().ref()) || revokedRefs.contains(approval.eligibility())) throw new IllegalArgumentException("revoked approval");
        Watch w = new Watch(id, approval, State.ACTIVE, from, null, 1, approval.cause());
        commit(new Change("WATCH", w, null, null, 0, null, null, null)); return w;
    }
    Watch transition(UUID id, long expectedRevision, State next, long until, Ref cause) throws IOException {
        Watch old = Objects.requireNonNull(watches.get(id), "unknown watch");
        if (old.revision() != expectedRevision || old.state() != State.ACTIVE || next == State.ACTIVE)
            throw new IllegalArgumentException("stale/invalid watch transition");
        Watch w = new Watch(id, old.approval(), next, old.from(), until, old.revision() + 1, cause);
        commit(new Change("WATCH", w, null, null, 0, null, null, null)); return w;
    }
    List<Watch> suspendTarget(UUID target, long until, Ref cause) throws IOException {
        List<Watch> changed = new ArrayList<>();
        for (UUID id : List.copyOf(active.getOrDefault(target, Map.of()).values())) {
            Watch w = watches.get(id); changed.add(transition(id, w.revision(), State.PAUSED, until, cause));
        }
        return List.copyOf(changed);
    }
    List<Proof> observe(ActionRecord raw, Scene scene) throws IOException {
        var e = raw.event();
        if (!world.equals(raw.worldId()) || !e.occurrenceId().equals(scene.eventId()) || !e.captureSession().equals(scene.captureSession())
                || e.captureOrder() != scene.captureOrder()) throw new IllegalArgumentException("capture scope mismatch");
        Seen old = seen.get(e.occurrenceId());
        if (old != null) {
            if (old.sequence != raw.sequence()) throw new IllegalArgumentException("source conflict");
            return old.proofs.stream().map(proofs::get).toList(); // Never project again using new permissions.
        }
        if (raw.sequence() <= cursor) throw new IllegalArgumentException("late source replay");
        List<Proof> result = new ArrayList<>();
        for (UUID watchId : active.getOrDefault(e.actorId(), Map.of()).values()) {
            Watch w = watches.get(watchId); Policy p = w.approval().policy();
            if (raw.sequence() < w.from() || revokedRefs.contains(p.ref()) || revokedRefs.contains(w.approval().eligibility())
                    || revokedRefs.contains(scene.context()) || revokedEvents.contains(e.occurrenceId())
                    || scene.occluded() || scene.privateScene() || scene.blockedGods().contains(p.godId())
                    || !scene.powers().contains(p.power()) || !scene.domains().contains(p.domain())
                    || p.places().stream().noneMatch(place -> place.contains(e))) continue;
            if(e.type()==ActionRecord.Type.OBSERVED_ACTIVITY_SUMMARY && !w.id().toString().equals(e.payload().get("watch_id")))continue;
            Map<Field, Value> projection = new EnumMap<>(Field.class);
            for (Field field : p.fields()) {
                Visibility v = scene.visibility().get(field);
                // The actor is a minimum affected subject, not an implicit permission for other subjects.
                if (v == null || !v.observable() || !v.subjectRules().containsKey(e.actorId())) continue;
                String value = value(e, field);
                if (value != null) projection.put(field, new Value(value, v.subjectRules()));
            }
            if (projection.isEmpty()) continue;
            UUID proofId = UUID.nameUUIDFromBytes((world + "/" + e.dedupKey() + "/" + watchId).getBytes(StandardCharsets.UTF_8));
            result.add(new Proof(proofId, world, e.occurrenceId(), e.sourceRevision(), e.sourceRef(), e.actorId(), p.godId(), watchId,
                    p.ref(), scene.context(), raw.sequence(), projection, 1));
        }
        // Includes zero-proof receipts: no future watch can fill this already-processed occurrence.
        commit(new Change("OBSERVE", null, List.copyOf(result), e.occurrenceId(), raw.sequence(), null, null, null));
        return List.copyOf(result);
    }
    private static String value(ActionRecord.Draft e, Field field) {
        return switch (field) {
            case ACTOR -> e.actorId().toString();
            case ACTION -> e.type().name();
            case SUBJECT_TYPE -> e.subject().typeId();
            case SUBJECT_ID -> e.subject().entityId() == null ? null : e.subject().entityId().toString();
            case LOCATION -> e.position() == null ? "UNKNOWN" : e.dimensionId() + ":" + e.position().x() + "," + e.position().y() + "," + e.position().z()
                    + (e.details() != null && e.details().biomeId() != null ? ";biome=" + e.details().biomeId() : "");
            case TIME -> "utc=" + e.occurredAtUtc() + ";tick=" + e.gameTick() + ";dayTime=" + e.gameDayTime();
            case OUTCOME -> switch (e.type()) {
                case MATURE_CROP_REMOVED -> "BLOCK_REMOVED_NOT_ITEM_ACQUISITION";
                case ENTITY_KILLED -> "DEATH_COMMITTED_NOT_LOOT_AWARDED";
                case BATTLE_RESULT -> e.payload().getOrDefault("battle_result","UNSPECIFIED");
                case OBSERVED_ACTIVITY_SUMMARY -> "VISIBLE_SAMPLES=" + e.payload().get("count") + ";ACTIVITY=" + e.payload().get("activity");
                default -> e.outcome();
            };
        };
    }
    void revokeEvent(UUID event) throws IOException { Objects.requireNonNull(event); if (!revokedEvents.contains(event)) commit(new Change("REVOKE_EVENT", null, null, event, 0, null, null, null)); }
    void revokeProof(UUID id) throws IOException { Objects.requireNonNull(id); if (!revokedProofs.contains(id)) commit(new Change("REVOKE_PROOF", null, null, null, 0, null, id, null)); }
    void revokeRef(Ref ref) throws IOException { Objects.requireNonNull(ref); if (!revokedRefs.contains(ref)) commit(new Change("REVOKE_REF", null, null, null, 0, ref, null, null)); }
    void disclose(Disclosure rule) throws IOException {
        Disclosure old = disclosures.get(rule.ref().id());
        if (rule.equals(old)) return;
        if (old != null && old.ref().revision() >= rule.ref().revision()) throw new IllegalArgumentException("stale disclosure");
        commit(new Change("DISCLOSURE", null, null, null, 0, null, null, rule));
    }
    View view(Audience audience, int limit) {
        if (!world.equals(audience.worldId())) return new View(false, "WORLD_MISMATCH", List.of());
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit");
        List<Proof> result = new ArrayList<>();
        List<UUID> candidates = byObserver.getOrDefault(audience.key(), List.of());
        // Bounded by max journal entries and per-observer index, never all gods x entire raw history.
        for (int i = candidates.size() - 1; i >= 0 && result.size() < limit; i--) {
            Proof p = proofs.get(candidates.get(i));
            if (!valid(p)) continue;
            Map<Field, Value> visible = new EnumMap<>(Field.class);
            p.visibleProjection().forEach((field, value) -> {
                boolean allowed = value.subjectRules().values().stream().allMatch(ref -> {
                    Disclosure rule = disclosures.get(ref.id());
                    return !revokedRefs.contains(ref) && rule != null && rule.ref().equals(ref) && rule.allowedAudience().containsAll(audience.players());
                });
                if (allowed) visible.put(field, value);
            });
            if (!visible.isEmpty()) result.add(new Proof(p.id(), p.worldId(), p.eventId(), p.sourceRevision(), p.sourceRef(), p.observerTarget(),
                    p.observerGodId(), p.watchId(), p.policy(), p.context(), p.observedAtSequence(), visible, p.revision()));
        }
        return new View(true, "READY", result);
    }
    /** Exact persisted references, not the recent-N window. Missing/revoked/foreign IDs fail the whole proof set. */
    View viewExact(Audience audience, Set<UUID> observationIds) {
        observationIds = Set.copyOf(observationIds);
        if (observationIds.size() > 100) throw new IllegalArgumentException("exact observation budget");
        if (!world.equals(audience.worldId())) return new View(false, "WORLD_MISMATCH", List.of());
        List<Proof> result = new ArrayList<>();
        for (UUID id : observationIds.stream().sorted().toList()) {
            Proof p = proofs.get(id);
            if (p == null || !p.observerGodId().equals(audience.key().godId())
                    || !p.observerTarget().equals(audience.key().playerId()) || !valid(p))
                return new View(false, "REFERENCE_UNAVAILABLE", List.of());
            Map<Field, Value> visible = new EnumMap<>(Field.class);
            p.visibleProjection().forEach((field, value) -> {
                boolean allowed = value.subjectRules().values().stream().allMatch(ref -> {
                    Disclosure rule = disclosures.get(ref.id());
                    return !revokedRefs.contains(ref) && rule != null && rule.ref().equals(ref)
                            && rule.allowedAudience().containsAll(audience.players());
                });
                if (allowed) visible.put(field, value);
            });
            if (visible.isEmpty()) return new View(false, "REFERENCE_NOT_DISCLOSED", List.of());
            result.add(new Proof(p.id(), p.worldId(), p.eventId(), p.sourceRevision(), p.sourceRef(), p.observerTarget(),
                    p.observerGodId(), p.watchId(), p.policy(), p.context(), p.observedAtSequence(), visible, p.revision()));
        }
        return new View(true, "READY", result);
    }
    boolean valid(Proof p) {
        Watch w = watches.get(p.watchId());
        return !revokedEvents.contains(p.eventId()) && !revokedProofs.contains(p.id()) && !revokedRefs.contains(p.policy())
                && !revokedRefs.contains(p.context()) && !revokedRefs.contains(w.approval().eligibility());
    }
    List<Watch> states() { return List.copyOf(watches.values()); }
    Map<UUID, Ref> eligibilityRefs(List<Proof> selected) {
        Map<UUID, Ref> result = new HashMap<>();
        for (Proof p : selected) result.put(p.id(), watches.get(p.watchId()).approval().eligibility());
        return Map.copyOf(result);
    }
    long cursor() { return cursor; }
    long bytes() { return journal.bytes(); }
    long maxBytes() { return journal.maxBytes(); }
    private void commit(Change c) throws IOException { journal.append(c); apply(c); }
    private void apply(Change c) {
        hasEntries = true; cleanShutdown = false;
        switch (Objects.requireNonNull(c.kind)) {
            case "BOOT" -> { }
            case "CLEAN_CLOSE" -> cleanShutdown = true;
            case "WATCH" -> {
                Watch w = Objects.requireNonNull(c.watch); if (!world.equals(w.approval().worldId())) throw new IllegalArgumentException("world");
                Watch old = watches.get(w.id()); Key k = w.approval().key();
                var index = active.computeIfAbsent(k.playerId(), unused -> new LinkedHashMap<>());
                if (old == null) {
                    if (w.state() != State.ACTIVE || w.revision() != 1 || index.containsKey(k.godId())) throw new IllegalArgumentException("watch start");
                    index.put(k.godId(), w.id());
                } else {
                    if (old.state() != State.ACTIVE || w.state() == State.ACTIVE || w.revision() != old.revision() + 1
                            || !w.approval().equals(old.approval()) || w.from() != old.from()) throw new IllegalArgumentException("watch transition");
                    index.remove(k.godId(), w.id());
                }
                watches.put(w.id(), w);
            }
            case "OBSERVE" -> {
                Objects.requireNonNull(c.eventId); Objects.requireNonNull(c.proofs);
                if (c.eventSequence <= cursor || seen.containsKey(c.eventId)) throw new IllegalArgumentException("observation order");
                for (Proof p : c.proofs) {
                    Watch w = watches.get(p.watchId());
                    if (!world.equals(p.worldId()) || !c.eventId.equals(p.eventId()) || c.eventSequence != p.observedAtSequence()
                            || w == null || w.state() != State.ACTIVE || p.observedAtSequence() < w.from()
                            || !w.approval().key().equals(new Key(p.observerGodId(), p.observerTarget())) || !w.approval().policy().ref().equals(p.policy())
                            || proofs.putIfAbsent(p.id(), p) != null) throw new IllegalArgumentException("proof reference");
                    byObserver.computeIfAbsent(new Key(p.observerGodId(), p.observerTarget()), unused -> new ArrayList<>()).add(p.id());
                }
                seen.put(c.eventId, new Seen(c.eventSequence, c.proofs.stream().map(Proof::id).toList())); cursor = c.eventSequence;
            }
            case "REVOKE_EVENT" -> revokedEvents.add(Objects.requireNonNull(c.eventId));
            case "REVOKE_PROOF" -> revokedProofs.add(Objects.requireNonNull(c.proofId));
            case "REVOKE_REF" -> revokedRefs.add(Objects.requireNonNull(c.ref));
            case "DISCLOSURE" -> {
                Disclosure next = Objects.requireNonNull(c.disclosure), old = disclosures.get(next.ref().id());
                if (old != null && old.ref().revision() >= next.ref().revision()) throw new IllegalArgumentException("disclosure revision");
                disclosures.put(next.ref().id(), next);
            }
            default -> throw new IllegalArgumentException("unknown watch operation");
        }
    }
    @Override public void close() throws IOException { journal.close(); }
}
