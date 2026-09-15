package com.sande.mythictrpg.ai.relationship;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Portable current-emotion payload for one NPC/player pair. It is intentionally independent from
 * {@link RelationshipSnapshot}; an affectionate relationship can therefore coexist with strong anger.
 */
public record EmotionSnapshot(String npcId, String playerId, Map<String, Integer> intensities) {
    public EmotionSnapshot {
        npcId = requireIdentifier(npcId, "npcId");
        playerId = requireIdentifier(playerId, "playerId");
        Objects.requireNonNull(intensities, "intensities");
        Map<String, Integer> checked = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : intensities.entrySet()) {
            String emotionId = EmotionTypes.requireEmotionId(entry.getKey());
            Integer intensity = entry.getValue();
            if (intensity == null || intensity < CurrentEmotion.MIN_INTENSITY
                    || intensity > CurrentEmotion.MAX_INTENSITY) {
                throw new IllegalArgumentException("Emotion '" + emotionId + "' must be between "
                        + CurrentEmotion.MIN_INTENSITY + " and " + CurrentEmotion.MAX_INTENSITY + ": " + intensity);
            }
            checked.put(emotionId, intensity);
        }
        intensities = Map.copyOf(checked);
    }

    public OptionalInt intensity(String emotionId) {
        Integer value = intensities.get(EmotionTypes.requireEmotionId(emotionId));
        return value == null ? OptionalInt.empty() : OptionalInt.of(value);
    }

    private static String requireIdentifier(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
