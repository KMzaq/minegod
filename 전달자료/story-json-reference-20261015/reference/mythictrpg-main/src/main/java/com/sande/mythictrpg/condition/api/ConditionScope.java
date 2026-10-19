package com.sande.mythictrpg.condition.api;

import java.util.Locale;

public enum ConditionScope {
    PLAYER,
    ANY_PLAYER,
    ALL_PLAYERS,
    ANY_ONLINE_PLAYER,
    WORLD,
    SERVER;

    public static ConditionScope parse(String value) {
        try {
            return valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown condition scope: " + value, exception);
        }
    }

    public String serializedName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
