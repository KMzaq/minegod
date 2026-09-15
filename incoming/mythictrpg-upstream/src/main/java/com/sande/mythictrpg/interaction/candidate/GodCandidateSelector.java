package com.sande.mythictrpg.interaction.candidate;

import com.sande.mythictrpg.data.god.GodAccessService;
import com.sande.mythictrpg.data.god.GodAppearanceService;
import com.sande.mythictrpg.data.player.ParticipationStatus;
import com.sande.mythictrpg.interaction.api.ExplicitGodCallPayload;
import com.sande.mythictrpg.interaction.api.InteractionMode;
import com.sande.mythictrpg.interaction.api.InteractionSignalTypes;
import com.sande.mythictrpg.interaction.candidate.CandidateSelectionResult.ScorerTrace;
import com.sande.mythictrpg.interaction.candidate.CandidateSelectionResult.ScorePartTrace;
import com.sande.mythictrpg.interaction.candidate.CandidateSelectionResult.TraceEntry;
import com.sande.mythictrpg.interaction.context.InteractionContext;
import com.sande.mythictrpg.interaction.rule.InteractionRuleSeedIndex;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class GodCandidateSelector {
    public static final GodCandidateSelector INSTANCE = new GodCandidateSelector();
    private static final ResourceLocation RULE_BINDING_SCORER = CandidateReasons.id("rule_binding_score");

    private final List<GodCandidateFilter> spontaneousFilters;
    private final List<GodCandidateFilter> explicitFilters;
    private final List<GodCandidateScorer> scorers;

    public GodCandidateSelector() {
        this.spontaneousFilters = List.of(
                this::definitionAvailable,
                this::playerActive,
                this::automaticAppearanceEligible,
                this::cooldownAvailable,
                this::runtimeAvailable);
        this.explicitFilters = List.of(
                this::definitionAvailable,
                this::playerActive,
                this::supportedExplicitPolicy,
                this::effectivelyUnlocked,
                this::cooldownAvailable,
                this::runtimeAvailable);
        this.scorers = List.of(new GodCandidateScorer() {
            @Override
            public ResourceLocation id() {
                return RULE_BINDING_SCORER;
            }

            @Override
            public ScoreContribution score(InteractionContext context, CandidateSeed seed) {
                return new ScoreContribution(seed.baseScore(), seed.reasons(), seed.scoreParts().stream()
                        .map(part -> new ScoreDetail(part.score(), part.reason())).toList());
            }
        });
    }

    public CandidateSelectionResult select(InteractionContext context, CandidateTraceMode traceMode) {
        if (!context.ready()) {
            return result(CandidateSelectionResult.Status.CONTEXT_UNAVAILABLE, List.of(), context,
                    context.unavailableReason().or(() -> Optional.of(CandidateReasons.CONTEXT_UNAVAILABLE)),
                    traceMode == CandidateTraceMode.VERBOSE
                            ? Optional.of(new CandidateSelectionResult.Trace(0, List.of(), List.of()))
                            : Optional.empty());
        }

        SeedSource source = source(context);
        if (!source.mapped()) {
            return result(CandidateSelectionResult.Status.NO_CANDIDATE, List.of(), context,
                    Optional.of(CandidateReasons.NO_SIGNAL_BINDING), trace(traceMode, source, List.of()));
        }
        if (source.seeds().isEmpty()) {
            ResourceLocation reason = source.unresolvedExactGods().isEmpty()
                    ? CandidateReasons.NO_ELIGIBLE_CANDIDATE : CandidateReasons.DEFINITION_UNAVAILABLE;
            return result(CandidateSelectionResult.Status.NO_CANDIDATE, List.of(), context,
                    Optional.of(reason), trace(traceMode, source, List.of()));
        }

        List<GodCandidate> accepted = new ArrayList<>();
        List<TraceEntry> traceEntries = traceMode == CandidateTraceMode.VERBOSE
                ? new ArrayList<>() : List.of();
        List<GodCandidateFilter> filters = context.signal().mode() == InteractionMode.SPONTANEOUS
                ? spontaneousFilters : explicitFilters;

        source.seeds().stream().sorted(Comparator.comparing(CandidateSeed::godId)).forEach(seed -> {
            Optional<ResourceLocation> rejection = firstRejection(filters, context, seed);
            if (rejection.isPresent()) {
                if (traceMode == CandidateTraceMode.VERBOSE
                        && traceEntries.size() < CandidateSelectionResult.MAX_TRACE_ENTRIES) {
                    traceEntries.add(new TraceEntry(seed.godId(), false, rejection, 0, List.of()));
                }
                return;
            }

            long total = 0;
            Set<ResourceLocation> reasons = new LinkedHashSet<>();
            List<ScorerTrace> scorerTraces = traceMode == CandidateTraceMode.VERBOSE
                    ? new ArrayList<>() : List.of();
            for (GodCandidateScorer scorer : scorers) {
                GodCandidateScorer.ScoreContribution contribution = scorer.score(context, seed);
                total = Math.addExact(total, contribution.score());
                reasons.addAll(contribution.reasons());
                if (traceMode == CandidateTraceMode.VERBOSE) {
                    scorerTraces.add(new ScorerTrace(scorer.id(), contribution.score(),
                            contribution.reasons(), contribution.details().stream()
                                    .map(detail -> new ScorePartTrace(detail.score(), detail.reason()))
                                    .toList()));
                }
            }
            GodCandidate candidate = new GodCandidate(seed.godId(), total, List.copyOf(reasons));
            accepted.add(candidate);
            if (traceMode == CandidateTraceMode.VERBOSE
                    && traceEntries.size() < CandidateSelectionResult.MAX_TRACE_ENTRIES) {
                traceEntries.add(new TraceEntry(seed.godId(), true, Optional.empty(), total, scorerTraces));
            }
        });

        List<GodCandidate> ranked = accepted.stream()
                .sorted(Comparator.comparingLong(GodCandidate::totalScore).reversed()
                        .thenComparing(GodCandidate::godId))
                .limit(CandidateSelectionResult.MAX_SHORTLIST)
                .toList();
        CandidateSelectionResult.Status status = ranked.isEmpty()
                ? CandidateSelectionResult.Status.NO_CANDIDATE : CandidateSelectionResult.Status.CANDIDATES;
        Optional<ResourceLocation> reason = ranked.isEmpty()
                ? Optional.of(CandidateReasons.NO_ELIGIBLE_CANDIDATE) : Optional.empty();
        return result(status, ranked, context, reason, trace(traceMode, source, traceEntries));
    }

    private static SeedSource source(InteractionContext context) {
        if (context.signal().mode() == InteractionMode.SPONTANEOUS) {
            InteractionRuleSeedIndex.SeedResolution resolution = context.ruleSnapshot().seedIndex()
                    .resolve(context.signal().type().id(), context.godSnapshot());
            List<CandidateSeed> seeds = resolution.seeds().entrySet().stream()
                    .map(entry -> new CandidateSeed(entry.getKey(), entry.getValue().score(),
                            entry.getValue().reasons(), entry.getValue().parts()))
                    .toList();
            return new SeedSource(resolution.signalMapped(), seeds, resolution.unresolvedExactGods());
        }
        if (context.signal().type().id().equals(InteractionSignalTypes.EXPLICIT_GOD_CALL.id())
                && context.signal().payload() instanceof ExplicitGodCallPayload payload) {
            ResourceLocation target = payload.targetGodId();
            if (!context.godSnapshot().definitions().containsKey(target)) {
                return new SeedSource(true, List.of(), List.of(target));
            }
            return new SeedSource(true, List.of(CandidateSeed.explicit(target)), List.of());
        }
        return new SeedSource(false, List.of(), List.of());
    }

    private static Optional<ResourceLocation> firstRejection(List<GodCandidateFilter> filters,
            InteractionContext context, CandidateSeed seed) {
        for (GodCandidateFilter filter : filters) {
            GodCandidateFilter.FilterResult result = filter.evaluate(context, seed);
            if (!result.accepted()) {
                return result.rejectionReason();
            }
        }
        return Optional.empty();
    }

    private GodCandidateFilter.FilterResult definitionAvailable(
            InteractionContext context, CandidateSeed seed) {
        return context.godSnapshot().definitions().containsKey(seed.godId())
                ? GodCandidateFilter.FilterResult.accept()
                : GodCandidateFilter.FilterResult.reject(CandidateReasons.DEFINITION_UNAVAILABLE);
    }

    private GodCandidateFilter.FilterResult playerActive(InteractionContext context, CandidateSeed seed) {
        return context.initiatingPlayerProfile()
                .filter(profile -> profile.participationStatus() == ParticipationStatus.ACTIVE)
                .map(ignored -> GodCandidateFilter.FilterResult.accept())
                .orElseGet(() -> GodCandidateFilter.FilterResult.reject(CandidateReasons.PLAYER_NOT_ACTIVE));
    }

    private GodCandidateFilter.FilterResult automaticAppearanceEligible(
            InteractionContext context, CandidateSeed seed) {
        var evaluation = GodAppearanceService.INSTANCE.evaluateAutomaticAppearance(
                context.godSnapshot(), context.conditionContext(), true, seed.godId());
        if (evaluation.eligible()) {
            return GodCandidateFilter.FilterResult.accept();
        }
        ResourceLocation reason = switch (evaluation.reason()) {
            case UNKNOWN_GOD -> CandidateReasons.DEFINITION_UNAVAILABLE;
            case PLAYER_NOT_ACTIVE -> CandidateReasons.PLAYER_NOT_ACTIVE;
            case NOT_EFFECTIVELY_UNLOCKED -> CandidateReasons.NOT_EFFECTIVELY_UNLOCKED;
            case EXPLICIT_ONLY -> CandidateReasons.EXPLICIT_ONLY;
            case CONDITION_NO_MATCH -> CandidateReasons.APPEARANCE_NO_MATCH;
            case CONDITION_UNKNOWN -> CandidateReasons.APPEARANCE_UNKNOWN;
            case ELIGIBLE -> throw new IllegalStateException("Eligible appearance had an ineligible result");
        };
        return GodCandidateFilter.FilterResult.reject(reason);
    }

    private GodCandidateFilter.FilterResult supportedExplicitPolicy(
            InteractionContext context, CandidateSeed seed) {
        if (context.signal().payload() instanceof ExplicitGodCallPayload payload
                && payload.policyId().equals(InteractionSignalTypes.PLAYER_EXPLICIT_POLICY)) {
            return GodCandidateFilter.FilterResult.accept();
        }
        return GodCandidateFilter.FilterResult.reject(CandidateReasons.UNSUPPORTED_EXPLICIT_POLICY);
    }

    private GodCandidateFilter.FilterResult effectivelyUnlocked(
            InteractionContext context, CandidateSeed seed) {
        return GodAccessService.effectiveUnlockStatus(
                        seed.godId(), context.conditionContext().world(), context.conditionContext().gods())
                .filter(Boolean::booleanValue)
                .map(ignored -> GodCandidateFilter.FilterResult.accept())
                .orElseGet(() -> GodCandidateFilter.FilterResult.reject(
                        CandidateReasons.NOT_EFFECTIVELY_UNLOCKED));
    }

    private GodCandidateFilter.FilterResult cooldownAvailable(
            InteractionContext context, CandidateSeed seed) {
        return context.cooldowns().blockReason(
                        context.initiatingPlayerId(), seed.godId(), context.signal().mode())
                .map(GodCandidateFilter.FilterResult::reject)
                .orElseGet(GodCandidateFilter.FilterResult::accept);
    }

    private GodCandidateFilter.FilterResult runtimeAvailable(
            InteractionContext context, CandidateSeed seed) {
        return context.runtime().candidateBlockReason(
                        context.initiatingPlayerId(), seed.godId(), context.signal().mode())
                .map(GodCandidateFilter.FilterResult::reject)
                .orElseGet(GodCandidateFilter.FilterResult::accept);
    }

    private static CandidateSelectionResult result(CandidateSelectionResult.Status status,
            List<GodCandidate> candidates, InteractionContext context,
            Optional<ResourceLocation> reason, Optional<CandidateSelectionResult.Trace> trace) {
        return new CandidateSelectionResult(status, candidates, context.godSnapshot().generation(),
                context.ruleSnapshot().generation(), reason, trace);
    }

    private static Optional<CandidateSelectionResult.Trace> trace(CandidateTraceMode mode,
            SeedSource source, List<TraceEntry> entries) {
        return mode == CandidateTraceMode.VERBOSE
                ? Optional.of(new CandidateSelectionResult.Trace(
                        source.seeds().size(), entries, source.unresolvedExactGods()))
                : Optional.empty();
    }

    private record SeedSource(boolean mapped, List<CandidateSeed> seeds,
            List<ResourceLocation> unresolvedExactGods) {
        private SeedSource {
            seeds = List.copyOf(seeds);
            unresolvedExactGods = List.copyOf(unresolvedExactGods);
        }
    }
}
