package com.sande.mythictrpg.godavatar.activity;

/** Activities are intentions with game-owned executors, not arbitrary commands. */
public enum ActivityKind {
    OBSERVE, REST, READ, STROLL, CONVERSE, LISTEN, FOLLOW, GUIDE, WITHDRAW, INSPECT,
    EAT, DRINK, TRAIN, CRAFT, REPAIR, COOK, FARM, RITUAL, OFFERING, PLAY, PERFORM, SOCIAL;

    public boolean work() {
        return switch (this) {
            case EAT, DRINK, CRAFT, REPAIR, COOK, FARM, RITUAL, OFFERING -> true;
            default -> false;
        };
    }
    public String pose() {
        return switch (this) {
            case REST -> "SIT";
            case READ -> "READ";
            case EAT -> "EAT";
            case DRINK -> "DRINK";
            case TRAIN -> "TRAIN";
            case CRAFT, REPAIR, COOK, FARM, OFFERING -> "WORK";
            case RITUAL -> "RITUAL";
            case PLAY -> "PLAY";
            case PERFORM -> "PERFORM";
            case SOCIAL, CONVERSE, LISTEN -> "TALK";
            default -> "OBSERVE";
        };
    }
}
