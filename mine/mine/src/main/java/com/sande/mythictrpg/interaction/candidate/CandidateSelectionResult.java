package com.sande.mythictrpg.interaction.candidate;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record CandidateSelectionResult(
        Status status,
        List<GodCandidate> candidates,
        long godDefinitionGeneration,
        long interactionRuleGeneration,
        Optional<ResourceLocation> reason,
        Optional<Trace> trace
) {
    public static final int MAX_SHORTLIST = 8;
    public static final int MAX_TRACE_ENTRIES = 64;
    public static final int MAX_TRACE_UNRESOLVED_IDS = 16;

    public CandidateSelectionResult {
        Objects.requireNonNull(status, "status");
        candidates = List.copyOf(candidates);
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(trace, "trace");
        if (candidates.size() > MAX_SHORTLIST) {
            throw new IllegalArgumentException("Candidate shortlist exceeds " + MAX_SHORTLIST);
        }
        if ((status == Status.CANDIDATES) != !candidates.isEmpty()) {
            throw new IllegalArgumentException("Candidate status and shortlist disagree");
        }
    }

    public enum Status {
        CANDIDATES,
        NO_CANDIDATE,
        CONTEXT_UNAVAILABLE
    }

    public record Trace(int seedCount, List<TraceEntry> entries,
            List<ResourceLocation> unresolvedExactGods) {
        public Trace {
            entries = entries.stream().limit(MAX_TRACE_ENTRIES).toList();
            unresolvedExactGods = unresolvedExactGods.stream()
                    .limit(MAX_TRACE_UNRESOLVED_IDS).toList();
        }
    }

    public record TraceEntry(ResourceLocation godId, boolean accepted,
            Optional<ResourceLocation> rejectionReason, long score,
            List<ScorerTrace> scorers) {
        public TraceEntry {
            Objects.requireNonNull(godId, "godId");
            Objects.requireNonNull(rejectionReason, "rejectionReason");
            scorers = List.copyOf(scorers);
        }
    }

    public record ScorerTrace(ResourceLocation scorerId, long score,
            List<ResourceLocation> reasons, List<ScorePartTrace> parts) {
        public ScorerTrace {
            reasons = List.copyOf(reasons);
            parts = parts.stream().limit(MAX_TRACE_ENTRIES).toList();
        }
    }

    public record ScorePartTrace(long score, ResourceLocation reason) {
    }
}
