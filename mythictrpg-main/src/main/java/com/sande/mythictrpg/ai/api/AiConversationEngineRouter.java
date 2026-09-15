package com.sande.mythictrpg.ai.api;

import java.util.Objects;
import java.util.UUID;

/** Configure-once router that keeps MythicTRPG independent from a concrete LLM implementation. */
public final class AiConversationEngineRouter implements AiConversationEngine {
    public static final AiConversationEngineRouter INSTANCE = new AiConversationEngineRouter();

    private AiConversationEngine productionEngine = AiConversationEngine.UNAVAILABLE;
    private boolean configured;

    private AiConversationEngineRouter() {
    }

    public synchronized void configureProductionEngine(AiConversationEngine engine) {
        Objects.requireNonNull(engine, "engine");
        if (!configured) {
            productionEngine = engine;
            configured = true;
            return;
        }
        if (productionEngine != engine) {
            throw new IllegalStateException("AI conversation production engine is already configured");
        }
    }

    @Override
    public void onConversationStarted(AiConversationStartContext context) {
        engine().onConversationStarted(context);
    }

    @Override
    public boolean onPlayerText(UUID playerId, String text) {
        return engine().onPlayerText(playerId, text);
    }

    @Override
    public boolean onQuestCompleted(AiQuestCompletionContext context) {
        return engine().onQuestCompleted(context);
    }

    @Override
    public boolean onQuestEvaluated(AiQuestEvaluationContext context) {
        return engine().onQuestEvaluated(context);
    }

    @Override
    public void onEnabledChanged(AiConversationControlContext context) {
        engine().onEnabledChanged(context);
    }

    @Override
    public void onPlayerLoggedOut(UUID playerId) {
        engine().onPlayerLoggedOut(playerId);
    }

    @Override
    public void onServerStopped() {
        engine().onServerStopped();
    }

    private synchronized AiConversationEngine engine() {
        return productionEngine;
    }
}
