package com.sande.mythictrpg.condition.api;

public enum ConditionResult {
    MATCH,
    NO_MATCH,
    UNKNOWN;

    public ConditionResult negate() {
        return switch (this) {
            case MATCH -> NO_MATCH;
            case NO_MATCH -> MATCH;
            case UNKNOWN -> UNKNOWN;
        };
    }
}
