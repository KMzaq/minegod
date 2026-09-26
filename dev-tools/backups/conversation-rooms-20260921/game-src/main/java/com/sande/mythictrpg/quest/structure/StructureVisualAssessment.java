package com.sande.mythictrpg.quest.structure;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Strict, bounded advisory result returned by a visual analyzer. */
public record StructureVisualAssessment(BuildingType buildingType, String subtype,
        List<String> styles, double confidence, int visualQualityScore,
        int godPreferenceScore, int completenessScore, List<String> evidence,
        List<String> concerns, String analyzer) {
    public StructureVisualAssessment {
        Objects.requireNonNull(buildingType);
        subtype = bounded(subtype, 80);
        styles = boundedList(styles, 6, 48);
        confidence = clamp(confidence, 0.0D, 1.0D);
        visualQualityScore = clamp(visualQualityScore, 0, 100);
        godPreferenceScore = clamp(godPreferenceScore, 0, 100);
        completenessScore = clamp(completenessScore, 0, 100);
        evidence = boundedList(evidence, 6, 180);
        concerns = boundedList(concerns, 4, 180);
        analyzer = bounded(analyzer, 80);
    }

    public boolean isConfident(double minimumConfidence) {
        return buildingType != BuildingType.UNKNOWN && confidence >= minimumConfidence;
    }

    private static List<String> boundedList(List<String> values, int maximumItems, int maximumLength) {
        if (values == null) return List.of();
        return values.stream().filter(Objects::nonNull).map(value -> bounded(value, maximumLength))
                .filter(value -> !value.isBlank()).limit(maximumItems).toList();
    }

    private static String bounded(String value, int maximum) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= maximum ? normalized : normalized.substring(0, maximum);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Double.isFinite(value) ? Math.max(minimum, Math.min(maximum, value)) : minimum;
    }

    public enum BuildingType {
        HOUSE, TEMPLE, ARENA_AMPHITHEATER, FORTRESS_CASTLE, TOWER, FARM,
        WORKSHOP, BRIDGE, MONUMENT, PUBLIC_BUILDING, SHIP, OTHER, UNKNOWN;

        public static BuildingType parse(String raw) {
            if (raw == null) return UNKNOWN;
            try {
                return valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                return UNKNOWN;
            }
        }
    }
}
