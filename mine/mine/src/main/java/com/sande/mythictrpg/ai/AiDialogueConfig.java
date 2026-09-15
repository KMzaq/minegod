package com.sande.mythictrpg.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sande.mythictrpg.MythicTrpg;
import net.neoforged.fml.loading.FMLPaths;

import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

/** Server-local configuration for the direct Ollama conversation transport. */
public final class AiDialogueConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final AiDialogueConfig INSTANCE = new AiDialogueConfig();

    private final Path file = FMLPaths.CONFIGDIR.get().resolve("mythictrpg/ai-dialogue.json");
    private volatile Settings settings = Settings.defaults();

    private AiDialogueConfig() {
    }

    public synchronized Settings load() {
        RawConfig raw = null;
        try {
            Files.createDirectories(file.getParent());
            if (Files.exists(file)) {
                try (Reader reader = Files.newBufferedReader(file)) {
                    raw = GSON.fromJson(reader, RawConfig.class);
                }
            }
            if (raw == null) {
                raw = RawConfig.defaults();
                write(raw);
            }
        } catch (Exception exception) {
            MythicTrpg.LOGGER.error("Could not load AI dialogue config {}; using safe defaults.", file, exception);
            raw = RawConfig.defaults();
        }
        settings = Settings.from(raw);
        MythicTrpg.LOGGER.info("AI dialogue config loaded (Ollama={}, model={}).",
                settings.ollamaChatUrl(), settings.ollamaModel());
        return settings;
    }

    public Settings settings() {
        return settings;
    }

    public Path file() {
        return file;
    }

    private void write(RawConfig raw) throws Exception {
        try (Writer writer = Files.newBufferedWriter(file)) {
            GSON.toJson(raw, writer);
        }
    }

    public record Settings(URI ollamaChatUrl, String ollamaModel, int requestTimeoutSeconds,
            int maxPromptCharacters, int maxResponseCharacters, int transcriptMessages,
            int maxOutputTokens, int responseChunkCharacters, boolean hybridIntentRoutingEnabled,
            int intentRoutingMaxOutputTokens, int exampleRetrievalLimit,
            int maxConcurrentLlmRequests, int llmQueueCapacity, int maxNpcResponsesPerTurn,
            int maxRetrievedMemories, int maxRetrievedKnowledge, int vouchRequestTimeoutSeconds,
            int maxSelectedReactionGuidelines, boolean debugLogging) {
        private static Settings defaults() {
            return from(RawConfig.defaults());
        }

        private static Settings from(RawConfig raw) {
            RawConfig fallback = RawConfig.defaults();
            URI endpoint;
            try {
                endpoint = URI.create(nonBlank(raw.ollamaChatUrl, fallback.ollamaChatUrl));
                if (!("http".equalsIgnoreCase(endpoint.getScheme()) || "https".equalsIgnoreCase(endpoint.getScheme()))
                        || endpoint.getHost() == null || !endpoint.getPath().endsWith("/api/chat")) {
                    throw new IllegalArgumentException("ollamaChatUrl must be an absolute /api/chat URL");
                }
            } catch (RuntimeException exception) {
                MythicTrpg.LOGGER.warn("Invalid Ollama URL '{}'; using {}.", raw.ollamaChatUrl, fallback.ollamaChatUrl);
                endpoint = URI.create(fallback.ollamaChatUrl);
            }
            return new Settings(endpoint, nonBlank(raw.ollamaModel, fallback.ollamaModel),
                    bounded(raw.requestTimeoutSeconds, 15, 600, fallback.requestTimeoutSeconds),
                    bounded(raw.maxPromptCharacters, 32, 2_000, fallback.maxPromptCharacters),
                    bounded(raw.maxResponseCharacters, 32, 1_024, fallback.maxResponseCharacters),
                    bounded(raw.transcriptMessages, 2, 64, fallback.transcriptMessages),
                    bounded(raw.maxOutputTokens, 32, 1_024, fallback.maxOutputTokens),
                    bounded(raw.responseChunkCharacters, 48, 600, fallback.responseChunkCharacters),
                    raw.hybridIntentRoutingEnabled == null ? fallback.hybridIntentRoutingEnabled
                            : raw.hybridIntentRoutingEnabled,
                    bounded(raw.intentRoutingMaxOutputTokens, 24, 192, fallback.intentRoutingMaxOutputTokens),
                    bounded(raw.exampleRetrievalLimit, 3, 5, fallback.exampleRetrievalLimit),
                    bounded(raw.maxConcurrentLlmRequests, 1, 8, fallback.maxConcurrentLlmRequests),
                    bounded(raw.llmQueueCapacity, 1, 256, fallback.llmQueueCapacity),
                    bounded(raw.maxNpcResponsesPerTurn, 1, 8, fallback.maxNpcResponsesPerTurn),
                    bounded(raw.maxRetrievedMemories, 0, 8, fallback.maxRetrievedMemories),
                    bounded(raw.maxRetrievedKnowledge, 0, 8, fallback.maxRetrievedKnowledge),
                    bounded(raw.vouchRequestTimeoutSeconds, 30, 3_600, fallback.vouchRequestTimeoutSeconds),
                    bounded(raw.maxSelectedReactionGuidelines, 0, 5, fallback.maxSelectedReactionGuidelines),
                    raw.debugLogging);
        }

        private static int bounded(int value, int minimum, int maximum, int fallback) {
            return value < minimum || value > maximum ? fallback : value;
        }

        private static String nonBlank(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value.trim();
        }
    }

    private static final class RawConfig {
        private String ollamaChatUrl;
        private String ollamaModel;
        private int requestTimeoutSeconds;
        private int maxPromptCharacters;
        private int maxResponseCharacters;
        private int transcriptMessages;
        private int maxOutputTokens;
        private int responseChunkCharacters;
        private Boolean hybridIntentRoutingEnabled;
        private int intentRoutingMaxOutputTokens;
        private int exampleRetrievalLimit;
        private int maxConcurrentLlmRequests;
        private int llmQueueCapacity;
        private int maxNpcResponsesPerTurn;
        private int maxRetrievedMemories;
        private int maxRetrievedKnowledge;
        private int vouchRequestTimeoutSeconds;
        private int maxSelectedReactionGuidelines;
        private boolean debugLogging;

        private static RawConfig defaults() {
            RawConfig config = new RawConfig();
            config.ollamaChatUrl = "http://127.0.0.1:11434/api/chat";
            config.ollamaModel = "qwen3-14b";
            config.requestTimeoutSeconds = 180;
            config.maxPromptCharacters = 600;
            config.maxResponseCharacters = 420;
            config.transcriptMessages = 20;
            config.maxOutputTokens = 260;
            config.responseChunkCharacters = 180;
            config.hybridIntentRoutingEnabled = true;
            config.intentRoutingMaxOutputTokens = 96;
            config.exampleRetrievalLimit = 3;
            config.maxConcurrentLlmRequests = 1;
            config.llmQueueCapacity = 32;
            config.maxNpcResponsesPerTurn = 2;
            config.maxRetrievedMemories = 3;
            config.maxRetrievedKnowledge = 3;
            config.vouchRequestTimeoutSeconds = 300;
            config.maxSelectedReactionGuidelines = 3;
            config.debugLogging = false;
            return config;
        }
    }
}
