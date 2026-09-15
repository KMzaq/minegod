package com.sande.mythictrpg.ai.relationship;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Transient-or-persistent current emotion, kept independently from relationship metrics.
 * Values are intensities from 0 to 100; an empty map means calm/unspecified.
 */
public record CurrentEmotion(Map<String, Integer> intensities) {
    public static final int MIN_INTENSITY = 0;
    public static final int MAX_INTENSITY = 100;

    public CurrentEmotion {
        Objects.requireNonNull(intensities, "intensities");
        Map<String, Integer> checked = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : intensities.entrySet()) {
            String name = EmotionTypes.requireEmotionId(entry.getKey());
            Integer value = entry.getValue();
            if (value == null || value < MIN_INTENSITY || value > MAX_INTENSITY) {
                throw new IllegalArgumentException("Emotion '" + name + "' must be between " + MIN_INTENSITY
                        + " and " + MAX_INTENSITY + ": " + value);
            }
            checked.put(name, value);
        }
        intensities = Map.copyOf(checked);
    }

    public static CurrentEmotion calm() {
        return new CurrentEmotion(Map.of());
    }
}
