package com.sande.mythictrpg.ai.knowledge;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Retrieves only relevant knowledge that is both known by the NPC and approved for the current audience. */
public final class KnowledgeRetriever {
    private final KnowledgeRepository repository;
    private final KnowledgeDisclosurePolicy policy;

    public KnowledgeRetriever(KnowledgeRepository repository, KnowledgeDisclosurePolicy policy) {
        this.repository = java.util.Objects.requireNonNull(repository, "repository");
        this.policy = java.util.Objects.requireNonNull(policy, "policy");
    }

    public KnowledgeRetrievalResult retrieve(KnowledgeQuery query) {
        Set<String> primaryTerms = KnowledgeSearchNormalizer.terms(query.currentText());
        Set<String> supplementalTerms = query.supplementalSearchTexts().stream()
                .flatMap(text -> KnowledgeSearchNormalizer.terms(text).stream()).collect(java.util.stream.Collectors.toSet());
        List<ScoredEntry> relevant = repository.all().stream()
                .map(entry -> new ScoredEntry(entry, score(entry, primaryTerms, supplementalTerms)))
                .filter(scored -> (scored.entry().alwaysActive() && primaryTerms.isEmpty() && supplementalTerms.isEmpty())
                        || scored.score() > 0.0D)
                .sorted(Comparator.comparingDouble(ScoredEntry::score).reversed()).toList();
        // Keep a bounded diagnostic window, but do not let an unknown NPC's high-scoring entry consume the slots
        // intended for permitted context. Disclosure is still evaluated for diagnostics; only allowed snippets are
        // capped by the caller's prompt budget.
        List<ScoredEntry> diagnosticWindow = relevant.stream().limit(Math.min(32, query.maximumResults() * 4L)).toList();
        List<KnowledgeDisclosureDecision> decisions = diagnosticWindow.stream()
                .map(scored -> policy.decide(scored.entry(), query.access())).toList();
        List<KnowledgeSnippet> allowed = relevant.stream()
                .filter(scored -> policy.decide(scored.entry(), query.access()).allowed())
                .limit(query.maximumResults()).map(scored -> KnowledgeSnippet.from(scored.entry())).toList();
        return new KnowledgeRetrievalResult(allowed, decisions);
    }

    private static double score(KnowledgeEntry entry, Set<String> primaryTerms, Set<String> supplementalTerms) {
        double primary = scoreTerms(entry, primaryTerms);
        double supplemental = scoreTerms(entry, supplementalTerms) * 0.45D;
        // Priority only resolves near ties. A weakly related high-priority entry never beats a strong keyword match.
        return primary + supplemental + (entry.priority() * 0.05D);
    }

    private static double scoreTerms(KnowledgeEntry entry, Set<String> terms) {
        if (terms.isEmpty()) {
            return 0.0D;
        }
        String id = KnowledgeSearchNormalizer.normalizeText(entry.id().replace('_', ' '));
        String title = KnowledgeSearchNormalizer.normalizeText(entry.title());
        String content = KnowledgeSearchNormalizer.normalizeText(entry.content());
        List<String> keys = entry.keys().stream().map(KnowledgeSearchNormalizer::normalizeText).toList();
        double score = 0.0D;
        for (String term : terms) {
            if (keys.stream().anyMatch(key -> key.equals(term))) {
                score += 5.0D;
            } else if (keys.stream().anyMatch(key -> key.contains(term) || term.contains(key))) {
                score += 3.5D;
            }
            if (title.contains(term)) {
                score += 3.0D;
            }
            if (id.contains(term)) {
                score += 2.0D;
            }
            if (content.contains(term)) {
                score += 1.0D;
            }
        }
        return score;
    }

    private record ScoredEntry(KnowledgeEntry entry, double score) {
    }
}
