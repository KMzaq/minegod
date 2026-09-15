package com.sande.mythictrpg.ai.proposal;

import com.sande.mythictrpg.ai.AiDialogueModels;

/** Optional game boundary for per-turn Quest/Reward constraints and prior validator feedback. */
@FunctionalInterface
public interface QuestRewardContextProvider {
    QuestRewardContext capture(AiDialogueModels.SessionSnapshot session, String triggeringParticipantId);

    static QuestRewardContextProvider none() {
        return (session, triggeringParticipantId) -> QuestRewardContext.safeDefaults();
    }
}
