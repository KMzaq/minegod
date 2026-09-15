package com.sande.mythictrpg.ai.api;

import java.util.UUID;

/** Optional bounded boundary implemented by a separately deployed AI response mod. */
public interface AiConversationEngine {
    AiConversationEngine UNAVAILABLE = new AiConversationEngine() {
    };

    default void onConversationStarted(AiConversationStartContext context) {
    }

    /** Returns true only when an active AI conversation consumed the player message. */
    default boolean onPlayerText(UUID playerId, String text) {
        return false;
    }

    /**
     * Requests a completion narration after the quest has already been
     * authoritatively committed. Returns false when a HUD fallback is needed.
     */
    default boolean onQuestCompleted(AiQuestCompletionContext context) {
        return false;
    }

    /** Requests narration for a passed or rejected evaluation submission. */
    default boolean onQuestEvaluated(AiQuestEvaluationContext context) {
        return false;
    }

    default void onEnabledChanged(AiConversationControlContext context) {
    }

    default void onPlayerLoggedOut(UUID playerId) {
    }

    default void onServerStopped() {
    }
}
