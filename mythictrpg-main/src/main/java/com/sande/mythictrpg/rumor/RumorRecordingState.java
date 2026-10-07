package com.sande.mythictrpg.rumor;

import com.google.gson.*;
import com.sande.mythictrpg.recording.server.LegacyRecordingQuota;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Optional archive provenance, saved atomically beside (not inside) the v1/v2 game snapshot. */
public final class RumorRecordingState {
    public static final int VERSION = 1;
    public static final String NBT_KEY = "recordingMetadata";
    public static final int MAX_METADATA_BYTES = 49_152;
    private static final int MAX_COMBINED_BYTES = 120_000;
    private static final Gson JSON = new Gson();
    private RumorRecordingState() { }

    public enum Status { ACTIVE, UNAVAILABLE_LIMIT, UNAVAILABLE_MISMATCH, UNAVAILABLE_CURSOR }
    public record RootStamp(UUID rootId, long bornCursor, long claimRevision, long claimCursor) {
        public RootStamp {
            Objects.requireNonNull(rootId);
            if (bornCursor < 1 || claimRevision < 0 || claimCursor < 0
                    || (claimRevision == 0) != (claimCursor == 0) || claimCursor > 0 && claimCursor < bornCursor)
                throw new IllegalArgumentException("Invalid recording root cursor");
        }
    }
    public record ReceiptStamp(UUID rootId, String godId, long claimRevision, long acquiredCursor) {
        public ReceiptStamp {
            Objects.requireNonNull(rootId); Objects.requireNonNull(godId);
            if (!godId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || godId.length() > 256
                    || claimRevision < 1 || acquiredCursor < 1)
                throw new IllegalArgumentException("Invalid recording receipt cursor");
        }
    }
    public record State(int version, UUID worldId, UUID lineage, long cursor, Status status,
            Map<UUID, RootStamp> roots, List<ReceiptStamp> receipts, String stateHash) {
        public State {
            Objects.requireNonNull(worldId); Objects.requireNonNull(lineage); Objects.requireNonNull(status);
            if (version != VERSION || cursor < 0 || stateHash == null || !stateHash.matches("[a-f0-9]{64}"))
                throw new IllegalArgumentException("Invalid recording metadata");
            roots = Collections.unmodifiableMap(new TreeMap<>(roots));
            receipts = receipts.stream().sorted(Comparator.comparing(RumorRecordingState::receiptKey)).toList();
            if (roots.size() > RumorLedger.LIMIT || receipts.size() > RumorLedger.LIMIT)
                throw new IllegalArgumentException("Oversized recording metadata");
            for (var entry : roots.entrySet()) {
                RootStamp root = entry.getValue();
                if (!entry.getKey().equals(root.rootId()) || root.bornCursor() > cursor || root.claimCursor() > cursor)
                    throw new IllegalArgumentException("Recording root does not belong to cursor");
            }
            Set<String> unique = new HashSet<>();
            for (ReceiptStamp receipt : receipts) {
                RootStamp root = roots.get(receipt.rootId());
                if (!unique.add(receiptKey(receipt)) || root == null || receipt.acquiredCursor() > cursor
                        || receipt.acquiredCursor() < root.bornCursor() || receipt.claimRevision() > root.claimRevision())
                    throw new IllegalArgumentException("Recording receipt does not belong to root");
            }
            if (status != Status.ACTIVE && (!roots.isEmpty() || !receipts.isEmpty()))
                throw new IllegalArgumentException("Unavailable metadata must not expose provenance");
        }
    }
    /** Only RumorSavedData publishes this after a disk load or a real COMMITTED write callback. */
    public record DurableView(State metadata, RumorLedger.Snapshot snapshot) {
        public DurableView {
            Objects.requireNonNull(metadata); snapshot = immutable(snapshot);
            validate(metadata, snapshot);
            if (metadata.status() != Status.ACTIVE) throw new IllegalArgumentException("Recording unavailable");
        }
        public Optional<RootStamp> root(UUID rootId) { return Optional.ofNullable(metadata.roots().get(rootId)); }
        public Optional<ReceiptStamp> receipt(UUID rootId, String godId, long claimRevision) {
            return metadata.receipts().stream().filter(r -> r.rootId().equals(rootId)
                    && r.godId().equals(godId) && r.claimRevision() == claimRevision).findFirst();
        }
    }

    /** Existing roots/receipts are absent (origin zero), never relabelled as new on later delivery. */
    static State begin(RumorLedger.Snapshot snapshot) {
        return new State(VERSION, snapshot.worldId(), UUID.randomUUID(), 0, Status.ACTIVE,
                Map.of(), List.of(), fingerprint(snapshot));
    }

    /** Called on the prospective game draft; the caller installs it only after quota admission succeeds. */
    static State advance(State state, RumorLedger.Snapshot before, RumorLedger.Snapshot after, boolean enabled) {
        if (state.status() != Status.ACTIVE) return unavailable(state, state.status(), after);
        if (!state.worldId().equals(before.worldId()) || !state.stateHash().equals(fingerprint(before))
                || !before.worldId().equals(after.worldId())) return unavailable(state, Status.UNAVAILABLE_MISMATCH, after);
        final long cursor;
        try { cursor = Math.incrementExact(state.cursor()); }
        catch (ArithmeticException overflow) { return unavailable(state, Status.UNAVAILABLE_CURSOR, after); }
        Map<UUID, RootStamp> roots = new TreeMap<>(state.roots());
        Set<UUID> previousRoots = new HashSet<>(); before.evidence().forEach(e -> previousRoots.add(e.id()));
        Set<UUID> nextRoots = new HashSet<>(); after.evidence().forEach(e -> nextRoots.add(e.id()));
        if (!nextRoots.containsAll(previousRoots)) return unavailable(state, Status.UNAVAILABLE_MISMATCH, after);
        if (enabled) for (UUID root : nextRoots) if (!previousRoots.contains(root))
            roots.put(root, new RootStamp(root, cursor, 0, 0));
        Map<UUID, RumorLedger.Claim> oldClaims = claims(before), newClaims = claims(after);
        for (RootStamp root : List.copyOf(roots.values())) {
            RumorLedger.Claim claim = newClaims.get(root.rootId());
            if (!Objects.equals(oldClaims.get(root.rootId()), claim)) {
                if (claim == null) return unavailable(state, Status.UNAVAILABLE_MISMATCH, after);
                // Even while OFF, a revocation of a previously tracked claim must survive restart.
                roots.put(root.rootId(), new RootStamp(root.rootId(), root.bornCursor(), claim.revision(), cursor));
            }
        }
        Map<String, RumorLedger.Receipt> oldReceipts = gameReceipts(before), newReceipts = gameReceipts(after);
        if (!newReceipts.keySet().containsAll(oldReceipts.keySet())) return unavailable(state, Status.UNAVAILABLE_MISMATCH, after);
        Map<String, ReceiptStamp> receipts = new TreeMap<>();
        state.receipts().forEach(r -> receipts.put(receiptKey(r), r));
        if (enabled) for (var entry : newReceipts.entrySet()) {
            RumorLedger.Receipt r = entry.getValue();
            if (!oldReceipts.containsKey(entry.getKey()) && roots.containsKey(r.rootId())) {
                ReceiptStamp stamp = new ReceiptStamp(r.rootId(), r.godId(), r.revision(), cursor);
                receipts.put(receiptKey(stamp), stamp);
            }
        }
        State next = new State(VERSION, state.worldId(), state.lineage(), cursor, Status.ACTIVE,
                roots, List.copyOf(receipts.values()), fingerprint(after));
        return supported(JSON.toJson(after), encode(next)) ? next : unavailable(next, Status.UNAVAILABLE_LIMIT, after);
    }

    static State unavailable(State previous, Status reason, RumorLedger.Snapshot snapshot) {
        if (reason == Status.ACTIVE) throw new IllegalArgumentException("Missing failure reason");
        return new State(VERSION, previous.worldId(), previous.lineage(), previous.cursor(), reason,
                Map.of(), List.of(), fingerprint(snapshot));
    }
    static String encode(State state) { return JSON.toJson(state); }
    static State decode(String json, RumorLedger.Snapshot snapshot) {
        if (!supported(JSON.toJson(snapshot), json)) throw new IllegalArgumentException("Recording metadata size");
        State state = JSON.fromJson(json, State.class); validate(state, snapshot); return state;
    }
    static boolean supported(String gameJson, String metadataJson) {
        int gameBytes = modifiedUtfBytes(gameJson), metadataBytes = modifiedUtfBytes(metadataJson);
        return LegacyRecordingQuota.SavedDataGate.supportedJson(gameJson)
                && metadataBytes <= MAX_METADATA_BYTES && gameBytes + (long) metadataBytes <= MAX_COMBINED_BYTES;
    }
    private static int modifiedUtfBytes(String value) {
        if (value == null || value.length() > MAX_COMBINED_BYTES) return Integer.MAX_VALUE;
        int result = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i); result += c >= 1 && c <= 127 ? 1 : c > 2047 ? 3 : 2;
        }
        return result;
    }
    private static void validate(State state, RumorLedger.Snapshot snapshot) {
        Objects.requireNonNull(state); Objects.requireNonNull(snapshot);
        if (!state.worldId().equals(snapshot.worldId()) || !state.stateHash().equals(fingerprint(snapshot)))
            throw new IllegalArgumentException("Recording snapshot binding mismatch");
        Set<UUID> evidence = new HashSet<>(); snapshot.evidence().forEach(e -> evidence.add(e.id()));
        Map<UUID, RumorLedger.Claim> claims = claims(snapshot);
        Map<String, RumorLedger.Receipt> receipts = gameReceipts(snapshot);
        for (RootStamp root : state.roots().values()) {
            RumorLedger.Claim claim = claims.get(root.rootId());
            if (!evidence.contains(root.rootId()) || root.claimRevision() != (claim == null ? 0 : claim.revision()))
                throw new IllegalArgumentException("Recording root snapshot mismatch");
        }
        for (ReceiptStamp receipt : state.receipts()) if (!receipts.containsKey(receiptKey(receipt)))
            throw new IllegalArgumentException("Recording receipt snapshot mismatch");
    }
    private static Map<UUID, RumorLedger.Claim> claims(RumorLedger.Snapshot snapshot) {
        Map<UUID, RumorLedger.Claim> result = new HashMap<>(); snapshot.claims().forEach(c -> result.put(c.rootId(), c)); return result;
    }
    private static Map<String, RumorLedger.Receipt> gameReceipts(RumorLedger.Snapshot snapshot) {
        Map<String, RumorLedger.Receipt> result = new HashMap<>();
        snapshot.receipts().forEach(r -> result.put(receiptKey(r.rootId(), r.godId(), r.revision()), r)); return result;
    }
    private static String receiptKey(ReceiptStamp receipt) { return receiptKey(receipt.rootId(), receipt.godId(), receipt.claimRevision()); }
    private static String receiptKey(UUID root, String god, long revision) { return root + "/" + god + "/" + revision; }
    static RumorLedger.Snapshot immutable(RumorLedger.Snapshot snapshot) {
        return new RumorLedger.Snapshot(snapshot.version(), snapshot.worldId(), List.copyOf(snapshot.couriers()),
                List.copyOf(snapshot.evidence()), List.copyOf(snapshot.claims()), List.copyOf(snapshot.pending()), List.copyOf(snapshot.receipts()));
    }
    /** Collections here are game facts, not recency order. Canonical sorting avoids Set iteration changes on restart. */
    static String fingerprint(RumorLedger.Snapshot snapshot) {
        JsonObject tree = JSON.toJsonTree(snapshot).getAsJsonObject(); tree.remove("version");
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(canonical(tree).toString().getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static JsonElement canonical(JsonElement value) {
        if (value.isJsonObject()) {
            JsonObject object = new JsonObject();
            new TreeSet<>(value.getAsJsonObject().keySet()).forEach(key -> object.add(key, canonical(value.getAsJsonObject().get(key))));
            return object;
        }
        if (value.isJsonArray()) {
            List<JsonElement> entries = new ArrayList<>(); value.getAsJsonArray().forEach(v -> entries.add(canonical(v)));
            entries.sort(Comparator.comparing(JsonElement::toString)); JsonArray array = new JsonArray(); entries.forEach(array::add); return array;
        }
        return value;
    }
}
