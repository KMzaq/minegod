package com.sande.mythictrpg.data.player;

public enum ParticipationStatus {
    ACTIVE,
    ARCHIVED;

    public static ParticipationStatus parse(String value) {
        try {
            return valueOf(value.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown participation status: " + value, exception);
        }
    }
}
