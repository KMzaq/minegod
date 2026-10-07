package com.sande.mythictrpg.ai.social;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Optional game-owned evidence adapter. Read existing authoritative services on the game thread;
 * never call an LLM, block on I/O, add participants, or create a second truth store here.
 * Provider revisions must change on relevant updates, including changes later reverted.
 */
@FunctionalInterface
public interface RoomSocialContextProvider {
    ProviderSnapshot capture(RoomSocialContext.Scope scope);

    enum Kind { POWER, PATRONAGE, OBLIGATION, REPUTATION }

    /** Explicit publication permission is separate from whether the speaking God knows a fact. */
    record Publication(boolean publicAllowed, Set<UUID> playerIds, Set<ResourceLocation> godIds) {
        public Publication {
            playerIds = Set.copyOf(playerIds);
            godIds = Set.copyOf(godIds);
            if (playerIds.size() > RoomSocialContext.MAX_AUDIENCE || godIds.size() > 64)
                throw new IllegalArgumentException("Social fact publication scope too large");
        }
        boolean permits(RoomSocialContext.Scope scope) {
            if (scope.roomType().isPublic()) return publicAllowed;
            return publicAllowed || playerIds.containsAll(scope.audiencePlayerIds())
                    && godIds.containsAll(scope.participantGodIds());
        }
    }

    /**
     * A confirmed, attributable fact, including an explicit negative statement when verified.
     * Missing facts mean UNKNOWN, never that a player is weak, unprotected, free of debts, or unimportant.
     * referencedGodId is a reference (e.g. a patron), never an instruction to join the room.
     */
    record Fact(String id, UUID subjectPlayerId, Kind kind, String statement,
            ResourceLocation sourceId, String evidenceId, long evidenceRevision,
            Set<ResourceLocation> knownByGodIds, Publication publication, Optional<ResourceLocation> referencedGodId) {
        public Fact {
            id = bounded(id, 96, "fact ID");
            Objects.requireNonNull(subjectPlayerId);
            Objects.requireNonNull(kind);
            statement = bounded(statement, 256, "statement");
            Objects.requireNonNull(sourceId);
            if (sourceId.toString().length() > 128) throw new IllegalArgumentException("Social fact source ID too long");
            evidenceId = bounded(evidenceId, 160, "evidence ID");
            if (evidenceRevision < 1) throw new IllegalArgumentException("Social fact requires an evidence revision");
            knownByGodIds = Set.copyOf(knownByGodIds);
            if (knownByGodIds.isEmpty() || knownByGodIds.size() > 64)
                throw new IllegalArgumentException("Social fact requires bounded God knowledge ownership");
            Objects.requireNonNull(publication);
            Objects.requireNonNull(referencedGodId);
            if (referencedGodId.map(Object::toString).map(String::length).orElse(0) > 128)
                throw new IllegalArgumentException("Referenced God ID too long");
        }
    }

    /** A provider must echo the exact issued scope; it cannot retarget a cached result from another room. */
    record ProviderSnapshot(ResourceLocation providerId, long revision, RoomSocialContext.Scope scope, List<Fact> facts) {
        public ProviderSnapshot {
            Objects.requireNonNull(providerId);
            Objects.requireNonNull(scope);
            facts = List.copyOf(facts);
            if (revision < 1 || facts.size() > 64 || facts.stream().map(Fact::id).distinct().count() != facts.size())
                throw new IllegalArgumentException("Invalid social provider revision, size or duplicate fact ID");
            if (facts.stream().anyMatch(fact -> !fact.sourceId().equals(providerId)))
                throw new IllegalArgumentException("Social provider does not own a fact's source");
        }
    }

    private static String bounded(String value, int limit, String label) {
        if (value == null || value.isBlank() || value.length() > limit)
            throw new IllegalArgumentException("Invalid social " + label);
        return value;
    }
}
