package com.sande.mythaiaicontent.content;

import java.util.Arrays;
import java.util.Locale;

/**
 * Auxiliary, participant-to-participant relation tags. Unlike RelationshipTier, these are not an affinity scale and
 * may be attached in combination. Direction is supplied by SocialRelation's participantA/participantB fields.
 */
public enum SocialRelationTag {
    FATHER("RT_FATHER"),
    MOTHER("RT_MOTHER"),
    CHILD("RT_CHILD"),
    BROTHER("RT_BROTHER"),
    SISTER("RT_SISTER"),
    TWIN("RT_TWIN"),
    SPOUSE("RT_SPOUSE"),
    LOVER("RT_LOVER"),
    MASTER("RT_MASTER"),
    SERVANT("RT_SERVANT"),
    NEMESIS("RT_NEMESIS"),
    RIVAL("RT_RIVAL"),
    COMRADE("RT_COMRADE"),
    ENEMY("RT_ENEMY"),
    BLOOD_RELATION("RT_BLOOD_RELATION"),
    CREATOR("RT_CREATOR"),
    CREATION("RT_CREATION");

    private final String tag;

    SocialRelationTag(String tag) {
        this.tag = tag;
    }

    public String tag() {
        return tag;
    }

    public static SocialRelationTag fromTag(String rawTag) {
        if (rawTag == null || rawTag.isBlank()) {
            throw new IllegalArgumentException("Social relation tag must not be blank");
        }
        String normalized = rawTag.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values()).filter(value -> value.tag.equals(normalized)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown social relation tag '" + rawTag + "'"));
    }
}
