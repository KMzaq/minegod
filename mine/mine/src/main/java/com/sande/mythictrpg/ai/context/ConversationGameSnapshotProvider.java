package com.sande.mythictrpg.ai.context;

import com.sande.mythictrpg.ai.AiDialogueModels;

/**
 * Game-to-AI boundary. The RPG/Minecraft layer decides which participants, relationship snapshots, and observed
 * game values are visible to one conversation turn.
 */
@FunctionalInterface
public interface ConversationGameSnapshotProvider {
    GameConversationSnapshot capture(AiDialogueModels.SessionSnapshot session, String triggeringParticipantId);
}
