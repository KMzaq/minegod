package com.sande.mythictrpg.gameplay.ledger.detail;

import com.sande.mythictrpg.gameplay.ledger.ActionRecord.Type;

/** Storage selection only. Never suppresses Minecraft stats or other observation consumers. */
public final class RecordingPolicy {
    private RecordingPolicy() { }
    public static boolean important(Type type) {
        return switch(type) {
            case BATTLE_RESULT, QUEST_COMPLETED, QUEST_EVALUATED, QUEST_TRANSITION, ADVANCEMENT_EARNED, OBSERVED_ACTIVITY_SUMMARY -> true;
            default -> false;
        };
    }
}
