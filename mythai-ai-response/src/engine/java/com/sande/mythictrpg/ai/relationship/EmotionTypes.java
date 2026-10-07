package com.sande.mythictrpg.ai.relationship;

import java.util.Set;

/**
 * Standard current-emotion candidates for the v1 AI/game contract. The set is advisory, not an enum: a game may
 * send additional valid emotion IDs such as {@code remorse} without an AI code release.
 */
public final class EmotionTypes {
    public static final String ANGER = "anger";
    public static final String HAPPINESS = "happiness";
    public static final String ANNOYANCE = "annoyance";
    public static final String CURIOSITY = "curiosity";
    public static final String SADNESS = "sadness";
    public static final String GRATITUDE = "gratitude";
    public static final String DISAPPOINTMENT = "disappointment";
    public static final String FEAR = "fear";

    private static final Set<String> STANDARD = Set.of(ANGER, HAPPINESS, ANNOYANCE, CURIOSITY, SADNESS,
            GRATITUDE, DISAPPOINTMENT, FEAR);

    private EmotionTypes() {
    }

    public static Set<String> standard() {
        return STANDARD;
    }

    public static String requireEmotionId(String emotionId) {
        String normalized = emotionId == null ? "" : emotionId.trim();
        if (!normalized.matches("[a-zA-Z][a-zA-Z0-9_-]{0,31}")) {
            throw new IllegalArgumentException("Invalid emotion name: " + emotionId);
        }
        return normalized;
    }
}
