package com.sande.mythictrpg.gameplay.watch;

import com.sande.mythictrpg.gameplay.ledger.ActionRecord;
import java.util.*;

/** Game-owned values only. No AI provider, relationship database, or automatic content rules. */
public final class WatchContract {
    private WatchContract() {}
    public enum State { ACTIVE, PAUSED, ENDED }
    public enum Field { ACTOR, ACTION, SUBJECT_TYPE, SUBJECT_ID, LOCATION, TIME, OUTCOME }
    public record Ref(String id, long revision) {
        public Ref { text(id); if (revision < 1) throw new IllegalArgumentException("revision"); }
    }
    public record Key(String godId, UUID playerId) {
        public Key { resource(godId); Objects.requireNonNull(playerId); }
    }
    public record Area(String dimensionId, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        public Area { resource(dimensionId); if (minX > maxX || minY > maxY || minZ > maxZ) throw new IllegalArgumentException("area"); }
        boolean contains(ActionRecord.Draft e) {
            var p = e.position();
            return p != null && dimensionId.equals(e.dimensionId()) && p.x() >= minX && p.x() <= maxX
                    && p.y() >= minY && p.y() <= maxY && p.z() >= minZ && p.z() <= maxZ;
        }
    }
    /** All three constraints are explicit, including empty (= deny). No omniscient defaults. */
    public record Policy(Ref ref, String godId, String power, String domain, List<Area> places, Set<Field> fields) {
        public Policy {
            Objects.requireNonNull(ref); resource(godId); resource(power); resource(domain);
            places = List.copyOf(places); fields = Set.copyOf(fields);
            if (places.size() > 32) throw new IllegalArgumentException("area budget");
        }
    }
    /** Issued only after explicit game validation; eligibility is not itself a watch. */
    public record Approval(UUID worldId, Key key, Ref eligibility, Ref cause, Policy policy) {
        public Approval {
            Objects.requireNonNull(worldId); Objects.requireNonNull(key); Objects.requireNonNull(eligibility);
            Objects.requireNonNull(cause); Objects.requireNonNull(policy);
            if (!key.godId().equals(policy.godId())) throw new IllegalArgumentException("policy owner");
        }
    }
    public record Watch(UUID id, Approval approval, State state, long from, Long until, long revision, Ref cause) {
        public Watch {
            Objects.requireNonNull(id); Objects.requireNonNull(approval); Objects.requireNonNull(state); Objects.requireNonNull(cause);
            if (from < 1 || revision < 1 || (state == State.ACTIVE) != (until == null)
                    || until != null && until < from) throw new IllegalArgumentException("watch interval");
        }
    }
    /** Covers every player affected by this field; each must have an independently validated disclosure rule. */
    public record Visibility(boolean observable, Map<UUID, Ref> subjectRules) {
        public Visibility { subjectRules = Map.copyOf(subjectRules); if (subjectRules.isEmpty() || subjectRules.size() > 16) throw new IllegalArgumentException("subjects"); }
    }
    /** Frozen on the server thread at the occurrence, never reconstructed from today's world on replay. */
    public record Scene(UUID eventId, UUID captureSession, long captureOrder, Ref context,
                        Set<String> powers, Set<String> domains, boolean occluded, boolean privateScene,
                        Map<Field, Visibility> visibility, Set<String> blockedGods) {
        public Scene(UUID eventId, UUID captureSession, long captureOrder, Ref context,
                Set<String> powers, Set<String> domains, boolean occluded, boolean privateScene,
                Map<Field, Visibility> visibility) {
            this(eventId, captureSession, captureOrder, context, powers, domains, occluded, privateScene, visibility, Set.of());
        }
        public Scene {
            Objects.requireNonNull(eventId); Objects.requireNonNull(captureSession); Objects.requireNonNull(context);
            if (captureOrder < 1) throw new IllegalArgumentException("capture order");
            powers = Set.copyOf(powers); domains = Set.copyOf(domains); visibility = Map.copyOf(visibility);
            blockedGods = blockedGods == null ? Set.of() : Set.copyOf(blockedGods);
            if (blockedGods.size() > 128) throw new IllegalArgumentException("blocked gods budget");
            blockedGods.forEach(WatchContract::resource);
            if (powers.size() > 64 || domains.size() > 64) throw new IllegalArgumentException("scope budget");
            powers.forEach(WatchContract::resource); domains.forEach(WatchContract::resource);
        }
    }
    public record Value(String text, Map<UUID, Ref> subjectRules) {
        public Value { WatchContract.text(text); subjectRules = Map.copyOf(subjectRules); if (subjectRules.isEmpty() || subjectRules.size() > 16) throw new IllegalArgumentException("subjects"); }
    }
    public record Proof(UUID id, UUID worldId, UUID eventId, int sourceRevision, String sourceRef,
                        UUID observerTarget, String observerGodId, UUID watchId, Ref policy, Ref context,
                        long observedAtSequence, Map<Field, Value> visibleProjection, long revision) {
        public Proof {
            Objects.requireNonNull(id); Objects.requireNonNull(worldId); Objects.requireNonNull(eventId);
            Objects.requireNonNull(observerTarget); Objects.requireNonNull(watchId); resource(observerGodId);
            Objects.requireNonNull(policy); Objects.requireNonNull(context); resource(sourceRef);
            visibleProjection = Map.copyOf(visibleProjection);
            if (sourceRevision != 1 || observedAtSequence < 1 || revision < 1 || visibleProjection.isEmpty()) throw new IllegalArgumentException("proof");
        }
        public String acquisitionKind() { return "DIRECT_WATCH"; }
    }
    /** Trusted game session supplies audience; an empty audience is NOT a public capability. */
    public record Audience(UUID worldId, Key key, Set<UUID> players, Ref session) {
        public Audience {
            Objects.requireNonNull(worldId); Objects.requireNonNull(key); Objects.requireNonNull(session);
            players = Set.copyOf(players); if (players.isEmpty() || players.size() > 16) throw new IllegalArgumentException("audience");
        }
    }
    public record Disclosure(Ref ref, Set<UUID> allowedAudience) {
        public Disclosure { Objects.requireNonNull(ref); allowedAudience = Set.copyOf(allowedAudience); if (allowedAudience.size() > 16) throw new IllegalArgumentException("audience budget"); }
    }
    public record View(boolean available, String reason, List<Proof> proofs) {
        public View { proofs = List.copyOf(proofs); if (!available && !proofs.isEmpty()) throw new IllegalArgumentException("unavailable contents"); }
    }
    static void text(String s) { if (s == null || s.isBlank() || s.length() > 512) throw new IllegalArgumentException("text"); }
    static void resource(String s) { if (s == null || s.length() > 256 || !s.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) throw new IllegalArgumentException("resource"); }
}
