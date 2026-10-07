package com.sande.mythictrpg.ai.knowledge;

import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Small deterministic normalizer for lore retrieval. It is deliberately local and dependency-free: it improves
 * Korean particles and common English variants without making an external NLP service part of the game server.
 */
final class KnowledgeSearchNormalizer {
    private static final List<String> KOREAN_SUFFIXES = List.of(
            "으로부터", "에게서", "한테서", "으로는", "에서는", "에게는", "한테는",
            "으로", "에서", "에게", "한테", "께서", "처럼", "보다", "까지", "부터",
            "이며", "이고", "이나", "거나", "와는", "과는", "에는", "에는",
            "은", "는", "이", "가", "을", "를", "의", "에", "와", "과", "도", "만", "로", "랑");

    private KnowledgeSearchNormalizer() {
    }

    static Set<String> terms(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        String normalized = normalizeText(text);
        for (String token : normalized.split("[^\\p{L}\\p{N}_-]+")) {
            addForms(result, token);
            for (String part : token.split("[_-]+")) {
                addForms(result, part);
            }
        }
        return Set.copyOf(result);
    }

    static String normalizeText(String text) {
        return Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).trim();
    }

    private static void addForms(Set<String> target, String raw) {
        String token = raw == null ? "" : raw.trim();
        if (token.length() < 2) {
            return;
        }
        target.add(token);
        String particleStripped = stripKoreanSuffix(token);
        if (particleStripped.length() >= 2) {
            target.add(particleStripped);
        }
        if (token.endsWith("'s") && token.length() > 3) {
            target.add(token.substring(0, token.length() - 2));
        } else if (token.endsWith("s") && token.length() > 4 && token.chars().allMatch(Character::isLetter)) {
            target.add(token.substring(0, token.length() - 1));
        }
    }

    private static String stripKoreanSuffix(String token) {
        for (String suffix : KOREAN_SUFFIXES) {
            if (token.endsWith(suffix) && token.length() - suffix.length() >= 2) {
                return token.substring(0, token.length() - suffix.length());
            }
        }
        return token;
    }
}
