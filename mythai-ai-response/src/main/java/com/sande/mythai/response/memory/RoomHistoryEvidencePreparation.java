package com.sande.mythai.response.memory;

import com.sande.mythictrpg.ai.api.RoomEvidenceReference;
import com.sande.mythictrpg.ai.experiencecontract.ExperienceRoomEvidence;
import com.sande.mythictrpg.recording.api.NativeMemoryEvidence;
import com.sande.mythictrpg.recording.api.NativeInterpretationEvidence;
import java.util.*;
import java.util.function.Function;

/** Pure discovery of warm-up candidates from actual visible receipts. This never grants history/read authority. */
final class RoomHistoryEvidencePreparation {
    static final int MAX_ROOTS = 128, MAX_NODES = 512, MAX_REFERENCES = 64;
    record Selection(List<RoomEvidenceReference> references, Set<UUID> eligibleRoots, boolean partial) {
        Selection { references = List.copyOf(references); eligibleRoots = Set.copyOf(eligibleRoots); }
        @Override public String toString() {
            return "HistoryEvidenceSelection[roots=" + eligibleRoots.size() + ",references=" + references.size() + ",partial=" + partial + "]";
        }
    }
    private RoomHistoryEvidencePreparation() { }

    /**
     * The lookup must be the bridge's actual durable/volatile receipt lookup, never history/model-created metadata.
     * Each root is atomic; failed/cyclic/incomplete roots cannot contribute references. Final currentSources must
     * run again after asynchronous preparation: eligibility here only permits a game-owned proof lookup.
     */
    static Selection discover(RoomMemoryStore.Scope scope, Collection<UUID> roots,
            Function<UUID,RoomMemoryEvidence.Receipt> lookup) {
        Objects.requireNonNull(scope); Objects.requireNonNull(roots); Objects.requireNonNull(lookup);
        if (roots.size() > MAX_ROOTS) return new Selection(List.of(), Set.of(), true);
        var snapshots = new HashMap<UUID,Optional<RoomMemoryEvidence.Receipt>>();
        var references = new LinkedHashSet<RoomEvidenceReference>();
        var allowed = new LinkedHashSet<UUID>();
        boolean partial = false;
        Function<UUID,RoomMemoryEvidence.Receipt> bounded = id -> {
            if (id == null) return null;
            var cached = snapshots.get(id);
            if (cached != null) return cached.orElse(null);
            if (snapshots.size() >= MAX_NODES) return null;
            RoomMemoryEvidence.Receipt receipt;
            try {
                receipt = lookup.apply(id);
                if (receipt != null && (receipt.revision() < 0 || receipt.parents().size() > 256 || receipt.refs().size() > MAX_REFERENCES
                        || receipt.players().size() > RoomMemoryStore.MAX_PLAYERS || receipt.gods().size() > RoomMemoryStore.MAX_GODS)) receipt = null;
            } catch (RuntimeException unavailable) { receipt = null; }
            snapshots.put(id, Optional.ofNullable(receipt));
            return receipt;
        };
        for (UUID root : roots) {
            if (root == null) { partial = true; continue; }
            if (allowed.contains(root)) continue;
            var found = new LinkedHashSet<RoomEvidenceReference>();
            boolean valid;
            try {
                valid = RoomMemoryEvidence.current(scope, List.of(), Set.of(root), bounded, ref -> {
                    if (NativeMemoryEvidence.KIND.equals(ref.kind())) {
                        NativeMemoryEvidence.decode(ref); // Syntax only, not a stored issuance or live permission.
                        found.add(ref);
                    } else if (NativeInterpretationEvidence.KIND.equals(ref.kind())) {
                        NativeInterpretationEvidence.decode(ref); // Actual job, inputs and observer remain game-owned checks.
                        found.add(ref);
                    } else if (ExperienceRoomEvidence.KIND.equals(ref.kind())) {
                        // Watch owns descriptor decoding/proof validation in its asynchronous preparer.
                        found.add(ref);
                    }
                    return found.size() <= MAX_REFERENCES;
                });
            } catch (RuntimeException malformed) { valid = false; }
            if (!valid) { partial = true; continue; }
            var combined = new LinkedHashSet<>(references); combined.addAll(found);
            if (combined.size() > MAX_REFERENCES) { partial = true; continue; }
            references.addAll(found); allowed.add(root);
        }
        return new Selection(List.copyOf(references), allowed, partial);
    }
}
