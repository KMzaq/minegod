package com.sande.mythictrpg.ai;

import com.sande.mythictrpg.ai.intent.ConversationIntent;

import java.util.List;
import java.util.UUID;

/**
 * Backend boundary for local structured generation. Ollama is one implementation; llama.cpp, vLLM, or an
 * OpenAI-compatible local endpoint can implement the same contract without entering conversation/game logic.
 */
interface LocalLlmClient extends AutoCloseable {
    LocalLlmRequestScheduler.ScheduledRequest<AiDialogueModels.StructuredAiResult> submit(UUID requestId,
            List<AiDialogueModels.OllamaMessage> messages, AiDialogueConfig.Settings settings);

    /** Small first-pass routing request used only for an ambiguous player turn. */
    default LocalLlmRequestScheduler.ScheduledRequest<ConversationIntent> submitIntent(UUID requestId,
            List<AiDialogueModels.OllamaMessage> messages, AiDialogueConfig.Settings settings) {
        throw new UnsupportedOperationException("This local LLM backend does not support intent routing");
    }

    @Override
    void close();
}
