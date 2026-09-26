package com.sande.mythai.response.memory;

import com.sande.mythictrpg.ai.api.RoomEvidenceReference;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;

/** Shared durable/volatile provenance check. Metadata contains no remembered dialogue text. */
final class RoomMemoryEvidence {
    record Receipt(UUID id, UUID world, UUID room, long revision, Set<UUID> players, Set<String> gods,
            boolean publicSpeech, List<RoomEvidenceReference> refs, Set<UUID> parents) {
        Receipt {
            Objects.requireNonNull(id); Objects.requireNonNull(world); Objects.requireNonNull(room);
            players = Set.copyOf(players); gods = Set.copyOf(gods); refs = List.copyOf(refs); parents = Set.copyOf(parents);
        }
        static Receipt from(RoomMemoryStore.Record row) {
            return new Receipt(row.messageId(), row.worldId(), row.sourceRoomId(), row.sourceRevision(),
                    row.fullPlayerAudience(), row.heardGodIds(), row.publicSpeech(), row.evidenceRefs(), row.sourceMessageIds());
        }
    }
    private RoomMemoryEvidence() { }

    static boolean visible(Receipt receipt, RoomMemoryStore.Scope scope, boolean heard) {
        return receipt.world().equals(scope.worldId()) && (!heard || receipt.gods().contains(scope.readerGodId()))
                && (receipt.publicSpeech() || !scope.publicRoom() && receipt.players().containsAll(scope.playerAudience())
                    && receipt.gods().containsAll(scope.godAudience()));
    }

    static boolean current(RoomMemoryStore.Scope scope, List<RoomEvidenceReference> refs, Set<UUID> sources,
            Function<UUID,Receipt> lookup, Predicate<RoomEvidenceReference> validator) {
        if (refs.size() > 64 || sources.size() > 256) return false;
        var evaluation = new Evaluation(scope, lookup, validator);
        return refs.stream().allMatch(evaluation::validReference) && sources.stream().allMatch(evaluation::validSource);
    }
    static Set<UUID> currentSources(RoomMemoryStore.Scope scope, Collection<UUID> sources,
            Function<UUID,Receipt> lookup, Predicate<RoomEvidenceReference> validator) {
        if (sources.size() > 256) return Set.of();
        var evaluation = new Evaluation(scope, lookup, validator);
        var allowed = new LinkedHashSet<UUID>();
        for (UUID source : sources) if (evaluation.validSource(source)) allowed.add(source);
        return Set.copyOf(allowed);
    }
    private static final class Evaluation {
        private final RoomMemoryStore.Scope scope;
        private final Function<UUID,Receipt> lookup;
        private final Predicate<RoomEvidenceReference> validator;
        private final Map<RoomEvidenceReference,Boolean> checkedRefs = new HashMap<>();
        private final Map<UUID,Integer> colors = new HashMap<>();
        private final Map<UUID,Optional<Receipt>> receipts = new HashMap<>();
        Evaluation(RoomMemoryStore.Scope scope, Function<UUID,Receipt> lookup, Predicate<RoomEvidenceReference> validator) {
            this.scope = scope; this.lookup = lookup; this.validator = validator;
        }
        private Receipt receipt(UUID id) { return receipts.computeIfAbsent(id, key -> Optional.ofNullable(lookup.apply(key))).orElse(null); }
        private boolean validReference(RoomEvidenceReference ref) {
            return checkedRefs.computeIfAbsent(ref, key -> {
                try { return validator.test(key); } catch (RuntimeException unavailable) { return false; }
            });
        }
        // Iterative, memoized DAG traversal: conversation length is not a recursion/depth limit.
        private record Frame(Receipt receipt, Iterator<UUID> parents) { }
        private boolean validSource(UUID source) {
            var stack = new ArrayDeque<Frame>();
            var first = receipt(source);
            if (first == null || !first.id().equals(source) || !visible(first, scope, true)) return false;
            if (colors.getOrDefault(source, 0) == 2) return true;
            if (colors.getOrDefault(source, 0) == 1 || first.refs().stream().anyMatch(ref -> !validReference(ref))) return false;
            colors.put(source, 1); stack.push(new Frame(first, first.parents().iterator()));
            while (!stack.isEmpty()) {
                var frame = stack.peek();
                if (!frame.parents().hasNext()) { colors.put(frame.receipt().id(), 2); stack.pop(); continue; }
                UUID parent = frame.parents().next(); int color = colors.getOrDefault(parent, 0);
                if (color == 1) return false;
                if (color == 2) continue;
                var earlier = receipt(parent);
                if (earlier == null || !earlier.id().equals(parent) || !visible(earlier, scope, false)
                        || earlier.refs().stream().anyMatch(ref -> !validReference(ref))) return false;
                colors.put(parent, 1); stack.push(new Frame(earlier, earlier.parents().iterator()));
            }
            return true;
        }
    }
}
