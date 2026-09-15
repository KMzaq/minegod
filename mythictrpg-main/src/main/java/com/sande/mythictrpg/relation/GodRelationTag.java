package com.sande.mythictrpg.relation;

import java.util.Arrays;
import java.util.Locale;

/** Bounded, current-world relation states. Static kinship and origin remain in Content Registry RT_* tags. */
public enum GodRelationTag {
    ALLIED(true),
    TRUCE(true),
    AT_WAR(true),
    HOSTILE(false),
    FEARFUL(false),
    RESENTFUL(false),
    INDIFFERENT(false),
    RESPECTFUL(false),
    OWES_DEBT(false),
    PROTECTIVE(false),
    WATCHFUL(false);

    private final boolean symmetric;

    GodRelationTag(boolean symmetric) {
        this.symmetric = symmetric;
    }

    public boolean symmetric() {
        return symmetric;
    }

    public String serializedName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static GodRelationTag parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("God relation tag must not be blank");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values()).filter(tag -> tag.name().equals(normalized)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown God relation tag '" + value + "'"));
    }
}

