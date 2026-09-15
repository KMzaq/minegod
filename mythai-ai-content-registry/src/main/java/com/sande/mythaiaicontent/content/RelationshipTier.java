package com.sande.mythaiaicontent.content;

import java.util.Arrays;
import java.util.Locale;

/**
 * Fixed dialogue-facing relationship stages. MythicTRPG remains authoritative for the numeric relationship data and
 * decides which tier applies; this enum only standardizes the static-content and AI-response vocabulary.
 */
public enum RelationshipTier {
    EXTREME_HOSTILE(-4, "R_EXTREME_HOSTILE", "극도의 적대", "적극적으로 해치거나 제거하려 함"),
    HOSTILE(-3, "R_HOSTILE", "적대적", "강한 반감과 적의를 가짐"),
    DISLIKE(-2, "R_DISLIKE", "불쾌/반감", "싫어하고 부정적으로 평가함"),
    WARY(-1, "R_WARY", "비호감/경계", "꺼리거나 경계하지만 적극적으로 적대하지는 않음"),
    NEUTRAL(0, "R_NEUTRAL", "중립", "특별한 감정이나 관심이 없음"),
    FAVORABLE(1, "R_FAVORABLE", "관심/호의", "약간 긍정적으로 봄"),
    FRIENDLY(2, "R_FRIENDLY", "호감", "좋아하거나 편하게 여김"),
    TRUSTED(3, "R_TRUSTED", "강한 호감", "상당히 좋아하고 신뢰함"),
    DEEP_BOND(4, "R_DEEP_BOND", "극도의 호감/애정", "매우 깊이 좋아하거나 특별하게 여김");

    private final int affinityStage;
    private final String tag;
    private final String displayName;
    private final String meaning;

    RelationshipTier(int affinityStage, String tag, String displayName, String meaning) {
        this.affinityStage = affinityStage;
        this.tag = tag;
        this.displayName = displayName;
        this.meaning = meaning;
    }

    public int affinityStage() {
        return affinityStage;
    }

    public String tag() {
        return tag;
    }

    public String displayName() {
        return displayName;
    }

    public String meaning() {
        return meaning;
    }

    public static RelationshipTier fromTag(String rawTag) {
        if (rawTag == null || rawTag.isBlank()) {
            throw new IllegalArgumentException("Relationship tier tag must not be blank");
        }
        String normalized = rawTag.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values()).filter(tier -> tier.tag.equals(normalized)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown relationship tier tag '" + rawTag + "'"));
    }
}
