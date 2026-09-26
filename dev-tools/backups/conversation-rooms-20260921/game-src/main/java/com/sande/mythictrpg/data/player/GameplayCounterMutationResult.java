package com.sande.mythictrpg.data.player;

public enum GameplayCounterMutationResult {
    UPDATED,
    REJECTED_EMPTY_BATCH,
    REJECTED_NON_POSITIVE_DELTA,
    REJECTED_OVERFLOW,
    REJECTED_KEY_LIMIT;

    public boolean updated() {
        return this == UPDATED;
    }
}
