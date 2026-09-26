package com.sande.mythictrpg.rumor;

import java.util.*;

/** Game-owned state machine. No AI proposal endpoint, entity polling, quest execution or affinity DB. */
public final class RumorLedger {
    public static final int VERSION = 2;
    public static final int LIMIT = 4096;
    public record Courier(UUID subject, UUID entity, UUID epoch, boolean blocked) {
        public Courier { Objects.requireNonNull(subject); Objects.requireNonNull(entity); Objects.requireNonNull(epoch); }
    }
    public record Evidence(UUID id, UUID subject, UUID observer, UUID epoch, String excerpt,
            Set<String> receivers, Set<UUID> disclosureAudience, CourierProof proof) {
        public Evidence(UUID id,UUID subject,UUID observer,UUID epoch,String excerpt,Set<String> receivers,Set<UUID> audience) {
            this(id,subject,observer,epoch,excerpt,receivers,audience,null);
        }
        public Evidence {
            Objects.requireNonNull(id); Objects.requireNonNull(subject); Objects.requireNonNull(observer); Objects.requireNonNull(epoch);
            excerpt = bounded(excerpt, 600);
            if(proof!=null&&!proof.excerptHash().equals(CourierSettings.hash(excerpt)))throw new IllegalArgumentException("witness excerpt changed");
            receivers = Set.copyOf(receivers); disclosureAudience = Set.copyOf(disclosureAudience);
            if (receivers.isEmpty() || receivers.size() > 64 || receivers.stream().anyMatch(s -> !s.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
                    || disclosureAudience.isEmpty() || disclosureAudience.size() > 16 || !disclosureAudience.contains(subject)) {
                throw new IllegalArgumentException("Invalid authored recipients/disclosure scope");
            }
        }
    }
    public record Claim(UUID rootId, long revision, String text, String epithet, boolean revoked) {
        public Claim { Objects.requireNonNull(rootId); text = bounded(text, 300); epithet = boundedOptional(epithet, 60);
            if (revision < 1) throw new IllegalArgumentException("Invalid revision"); }
    }
    public record Delivery(UUID rootId, long revision, String godId, UUID epoch) {
        public Delivery { Objects.requireNonNull(rootId); Objects.requireNonNull(godId); Objects.requireNonNull(epoch); }
    }
    public record Receipt(UUID rootId, long revision, String godId) {
        public Receipt { Objects.requireNonNull(rootId); Objects.requireNonNull(godId); }
    }
    public record HeardRumor(UUID rootId, long revision, String text, String epithet, String reception,
                             String assessment, long assessmentVersion) {
        public HeardRumor(UUID rootId,long revision,String text,String epithet,String reception){this(rootId,revision,text,epithet,reception,"UNASSESSED",0);}
        public HeardRumor(UUID rootId,long revision,String text,String epithet){this(rootId,revision,text,epithet,"UNSPECIFIED");}
        public HeardRumor {
            if(!Set.of("UNSPECIFIED","CAUTIOUS","INTERESTED").contains(reception))throw new IllegalArgumentException("reception");
            if (!Set.of("UNASSESSED","ACCEPTED","DOUBTFUL","IGNORED","DISPUTED","RECOVERED","RETRACTED").contains(assessment)
                    || assessmentVersion < 0 || assessment.equals("UNASSESSED") != (assessmentVersion == 0)) throw new IllegalArgumentException("assessment");
        }
    }
    public record Snapshot(int version, UUID worldId, List<Courier> couriers, List<Evidence> evidence,
            List<Claim> claims, List<Delivery> pending, List<Receipt> receipts) {}

    private final UUID worldId;
    private final Map<UUID, Courier> couriers = new LinkedHashMap<>();
    private final Map<UUID, Evidence> evidence = new LinkedHashMap<>();
    private final Map<UUID, Claim> claims = new LinkedHashMap<>();
    private final Map<String, Delivery> pending = new LinkedHashMap<>();
    private final Map<String, Receipt> receipts = new LinkedHashMap<>();
    private long revision;
    public RumorLedger() { this(UUID.randomUUID()); }
    private RumorLedger(UUID worldId) { this.worldId = Objects.requireNonNull(worldId); }
    public UUID worldId() { return worldId; }
    public long revision() { return revision; }
    Courier courier(UUID subject) { return couriers.get(subject); }
    Evidence evidence(UUID root) { return evidence.get(root); }
    boolean claimed(UUID root) { return claims.containsKey(root); }
    List<Evidence> evidence() { return List.copyOf(evidence.values()); }
    boolean hasCourier(UUID entity) { return couriers.values().stream().anyMatch(c->c.entity().equals(entity)&&!c.blocked()); }

    /** Explicit game lifecycle operation; unload/login are deliberately not callers. */
    public boolean bindCourier(UUID subject, UUID entity) {
        Objects.requireNonNull(subject); Objects.requireNonNull(entity);
        Courier old = couriers.get(subject);
        if (old != null && !old.blocked()) return false;
        if (couriers.values().stream().anyMatch(c -> c.entity().equals(entity))) return false;
        if (old == null && couriers.size() >= LIMIT) return false;
        couriers.put(subject, new Courier(subject, entity, UUID.randomUUID(), false)); revision++;
        return true;
    }

    /** The bound observation subject, not the killer or FTB team, determines suppression. */
    public boolean courierDied(UUID entity) {
        Courier courier = couriers.values().stream().filter(c -> c.entity().equals(entity) && !c.blocked()).findFirst().orElse(null);
        if (courier == null) return false;
        couriers.put(courier.subject(), new Courier(courier.subject(), entity, UUID.randomUUID(), true));
        pending.values().removeIf(d -> evidence.get(d.rootId()).subject().equals(courier.subject()));
        revision++;
        return true;
    }

    /** Only an already-authorized game observer calls this. Multi-subject excerpts fail closed in v1. */
    public boolean observe(UUID eventId, UUID subject, UUID observer, Set<UUID> mentionedSubjects,
            String excerpt, Set<String> authorizedGods, Set<UUID> disclosureAudience) {
        return observe(eventId,subject,observer,mentionedSubjects,excerpt,authorizedGods,disclosureAudience,null);
    }
    boolean observe(UUID eventId, UUID subject, UUID observer, Set<UUID> mentionedSubjects,
            String excerpt, Set<String> authorizedGods, Set<UUID> disclosureAudience,CourierProof proof) {
        Courier courier = couriers.get(subject);
        if (!Set.of(subject).equals(mentionedSubjects) || courier == null || courier.blocked()
                || !courier.entity().equals(observer) || evidence.containsKey(eventId) || evidence.size() >= LIMIT) return false;
        Evidence accepted = new Evidence(eventId, subject, observer, courier.epoch(), excerpt, authorizedGods, disclosureAudience,proof);
        evidence.put(eventId, accepted); revision++;
        return true;
    }

    /** Candidate text cannot create an observation, select receivers, or grant game effects. */
    public boolean publish(UUID rootId, String allegation, String epithet) {
        Evidence source = evidence.get(rootId);
        if (source == null || !valid(source) || claims.containsKey(rootId)) return false;
        Claim claim = new Claim(rootId, 1, allegation, epithet, false);
        if (pending.size() + source.receivers().size() > LIMIT) return false;
        claims.put(rootId, claim);
        source.receivers().stream().sorted().forEach(god -> pending.put(key(rootId, god), new Delivery(rootId, 1, god, source.epoch())));
        revision++;
        return true;
    }

    public List<Delivery> pending() { return List.copyOf(pending.values()); }
    boolean discard(Delivery delivery) {
        if(!pending.remove(key(delivery.rootId(),delivery.godId()),delivery))return false;
        revision++;return true;
    }
    public boolean deliver(Delivery delivery) {
        if (delivery == null || !delivery.equals(pending.get(key(delivery.rootId(), delivery.godId())))) return false;
        Evidence source = evidence.get(delivery.rootId());
        Claim claim = claims.get(delivery.rootId());
        if (source == null || claim == null || claim.revoked() || claim.revision() != delivery.revision()
                || !valid(source) || !source.epoch().equals(delivery.epoch()) || !source.receivers().contains(delivery.godId())) return false;
        String key = key(delivery.rootId(), delivery.godId());
        if (!receipts.containsKey(key) && receipts.size() >= LIMIT) return false;
        receipts.putIfAbsent(key, new Receipt(delivery.rootId(), delivery.revision(), delivery.godId()));
        pending.remove(key); revision++;
        return true;
    }

    /** Administrative invalidation, not in-world forgetting or a pigeon kill. */
    public boolean revoke(UUID rootId) {
        Claim old = claims.get(rootId);
        if (!evidence.containsKey(rootId) || old != null && old.revoked()) return false;
        // Tombstone even before publication: a delayed worker must not revive invalidated evidence.
        claims.put(rootId, old == null ? new Claim(rootId, 1, "INVALIDATED_BEFORE_PUBLICATION", "", true)
                : new Claim(rootId, old.revision() + 1, old.text(), old.epithet(), true));
        pending.values().removeIf(d -> d.rootId().equals(rootId)); revision++;
        return true;
    }

    public List<HeardRumor> heard(UUID subject, String god, Set<UUID> currentAudience) {
        if (currentAudience.isEmpty()) return List.of();
        return receipts.values().stream().filter(r -> r.godId().equals(god)).filter(r -> {
            Evidence e = evidence.get(r.rootId()); Claim c = claims.get(r.rootId());
            return e != null && c != null && !c.revoked() && r.revision() == c.revision() && e.subject().equals(subject)
                    && e.disclosureAudience().containsAll(currentAudience);
        }).map(r -> { Claim c = claims.get(r.rootId()); return new HeardRumor(r.rootId(), r.revision(), c.text(), c.epithet()); })
                .toList().reversed().stream().limit(64).toList();
    }

    /** Point query for game judgments: prompt top-N truncation must not randomly erase a standing. */
    Optional<HeardRumor> heardOne(UUID subject,String god,UUID root,Set<UUID> audience) {
        var receipt=receipts.get(key(root,god));var claim=claims.get(root);var source=evidence.get(root);
        if(receipt==null||claim==null||source==null||claim.revoked()||receipt.revision()!=claim.revision()
                ||!source.subject().equals(subject)||audience.isEmpty()||!source.disclosureAudience().containsAll(audience))return Optional.empty();
        return Optional.of(new HeardRumor(root,claim.revision(),claim.text(),claim.epithet()));
    }

    private boolean valid(Evidence source) {
        Courier courier = couriers.get(source.subject());
        return courier != null && !courier.blocked() && courier.entity().equals(source.observer()) && courier.epoch().equals(source.epoch());
    }
    public Snapshot snapshot() {
        return new Snapshot(VERSION, worldId, List.copyOf(couriers.values()), List.copyOf(evidence.values()),
                List.copyOf(claims.values()), List.copyOf(pending.values()), List.copyOf(receipts.values()));
    }
    public static RumorLedger restore(Snapshot snapshot) {
        if (snapshot.version() != 1 && snapshot.version() != VERSION) throw new IllegalArgumentException("Unknown rumor schema");
        RumorLedger result = new RumorLedger(snapshot.worldId());
        for (var list : List.of(snapshot.couriers(), snapshot.evidence(), snapshot.claims(), snapshot.pending(), snapshot.receipts())) {
            if (list.size() > LIMIT) throw new IllegalArgumentException("Oversized rumor state");
        }
        Set<UUID> entities = new HashSet<>();
        for (Courier c : snapshot.couriers()) { unique(result.couriers, c.subject(), c); if (!entities.add(c.entity())) throw new IllegalArgumentException("Duplicate courier"); }
        for (Evidence e : snapshot.evidence()) { unique(result.evidence, e.id(), e); if (!result.couriers.containsKey(e.subject())||snapshot.version()==1&&e.proof()!=null) throw new IllegalArgumentException("Missing subject/unsupported proof"); }
        for (Claim c : snapshot.claims()) { unique(result.claims, c.rootId(), c); if (!result.evidence.containsKey(c.rootId())) throw new IllegalArgumentException("Missing evidence"); }
        for (Receipt r : snapshot.receipts()) {
            Claim c = result.claims.get(r.rootId()); Evidence e = result.evidence.get(r.rootId());
            if (c == null || e == null || !e.receivers().contains(r.godId()) || r.revision() < 1 || r.revision() > c.revision()) throw new IllegalArgumentException("Invalid receipt");
            unique(result.receipts, key(r.rootId(), r.godId()), r);
        }
        for (Delivery d : snapshot.pending()) {
            Claim c = result.claims.get(d.rootId()); Evidence e = result.evidence.get(d.rootId());
            if (c == null || e == null || !e.receivers().contains(d.godId()) || d.revision() != c.revision()) throw new IllegalArgumentException("Invalid delivery");
            // Old epochs can never revive after restart, even if a stale reservation survived a crash.
            if (!c.revoked() && result.valid(e) && e.epoch().equals(d.epoch()) && !result.receipts.containsKey(key(d.rootId(), d.godId()))) unique(result.pending, key(d.rootId(), d.godId()), d);
        }
        return result;
    }
    private static <K,V> void unique(Map<K,V> map, K key, V value) { if (map.putIfAbsent(key, value) != null) throw new IllegalArgumentException("Duplicate rumor key"); }
    private static String key(UUID root, String god) { return root + "/" + god; }
    private static String bounded(String s, int limit) { if (s == null || s.isBlank() || s.length() > limit) throw new IllegalArgumentException("Invalid rumor text"); return s; }
    private static String boundedOptional(String s, int limit) { Objects.requireNonNull(s); if (s.length() > limit) throw new IllegalArgumentException("Oversized epithet"); return s; }
}
