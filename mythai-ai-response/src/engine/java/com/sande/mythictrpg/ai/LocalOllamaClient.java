package com.sande.mythictrpg.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sande.mythictrpg.ai.example.DialogueExampleTag;
import com.sande.mythictrpg.ai.intent.ConversationIntent;
import com.sande.mythictrpg.ai.intent.ConversationAct;
import com.sande.mythictrpg.ai.intent.ConversationIntentRouter;
import com.sande.mythictrpg.ai.tone.PlayerSpeechTone;
import com.sande.mythictrpg.MythicTrpg;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Direct Ollama client. Request concurrency/queueing are delegated to {@link LocalLlmRequestScheduler}. */
final class LocalOllamaClient implements LocalLlmClient {
    private static final Map<UUID, WireObserver> WIRE_OBSERVERS = new ConcurrentHashMap<>();
    private static final Map<String, Object> STRUCTURED_DIALOGUE_SCHEMA = dialogueSchema();
    private final Gson gson = new Gson();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final LocalLlmRequestScheduler scheduler = new LocalLlmRequestScheduler();

    @Override
    public LocalLlmRequestScheduler.ScheduledRequest<AiDialogueModels.StructuredAiResult> submit(UUID requestId,
            List<AiDialogueModels.OllamaMessage> messages,
            AiDialogueConfig.Settings settings) {
        return scheduler.submit(requestId, settings, () -> request(requestId, messages, settings));
    }

    @Override
    public LocalLlmRequestScheduler.ScheduledRequest<ConversationIntent> submitIntent(UUID requestId,
            List<AiDialogueModels.OllamaMessage> messages, AiDialogueConfig.Settings settings) {
        return scheduler.submit(requestId, settings, () -> requestIntent(requestId, messages, settings));
    }

    @Override
    public LocalLlmRequestScheduler.ScheduledRequest<RoomDialogueGrounding.Review> submitReview(UUID requestId,
            List<AiDialogueModels.OllamaMessage> messages, AiDialogueConfig.Settings settings) {
        return scheduler.submit(requestId, settings, () -> {
            try {
                var envelope = JsonParser.parseString(requestBody(requestId, messages, settings, 0.0D, 512,
                        Math.min(settings.requestTimeoutSeconds(), 60), RoomDialogueGrounding.schema())).getAsJsonObject();
                return RoomDialogueGrounding.parse(JsonParser.parseString(
                        envelope.getAsJsonObject("message").get("content").getAsString()).getAsJsonObject());
            } finally { WIRE_OBSERVERS.remove(requestId); }
        });
    }

    /** Test-only diagnostic hook. Production callers need not register and no payload is retained by default. */
    static void installWireObserver(UUID requestId, WireObserver observer) {
        if (requestId != null && observer != null) {
            WIRE_OBSERVERS.put(requestId, observer);
        }
    }

    static void removeWireObserver(UUID requestId) {
        WIRE_OBSERVERS.remove(requestId);
    }

    private AiDialogueModels.StructuredAiResult request(UUID requestId, List<AiDialogueModels.OllamaMessage> messages,
            AiDialogueConfig.Settings settings) {
        try {
            return parse(requestBody(requestId, messages, settings, 0.60D, settings.maxOutputTokens(),
                    settings.requestTimeoutSeconds(), STRUCTURED_DIALOGUE_SCHEMA));
        } catch (RuntimeException firstFailure) {
            try {
                return parse(requestBody(requestId, messages, settings, 0.35D, settings.maxOutputTokens(),
                        settings.requestTimeoutSeconds(), STRUCTURED_DIALOGUE_SCHEMA));
            } catch (RuntimeException retryFailure) {
                retryFailure.addSuppressed(firstFailure);
                throw retryFailure;
            }
        } finally {
            WIRE_OBSERVERS.remove(requestId);
        }
    }

    private ConversationIntent requestIntent(UUID requestId, List<AiDialogueModels.OllamaMessage> messages,
            AiDialogueConfig.Settings settings) {
        try {
            // Old routing schemas remain small. The optional attribution result needs room to finish its JSON.
            int budget = messages.stream().anyMatch(m -> m.role().equals("system") && m.content().contains("TURN_INTERPRETATION_V1"))
                    ? Math.max(384, settings.intentRoutingMaxOutputTokens()) : settings.intentRoutingMaxOutputTokens();
            return parseIntent(requestBody(requestId, messages, settings, 0.1D, budget,
                    Math.min(settings.requestTimeoutSeconds(), 45), "json"));
        } finally {
            WIRE_OBSERVERS.remove(requestId);
        }
    }

    private String requestBody(UUID requestId, List<AiDialogueModels.OllamaMessage> messages, AiDialogueConfig.Settings settings,
            double temperature, int maximumTokens) {
        return requestBody(requestId, messages, settings, temperature, maximumTokens, settings.requestTimeoutSeconds(),
                STRUCTURED_DIALOGUE_SCHEMA);
    }

    private String requestBody(UUID requestId, List<AiDialogueModels.OllamaMessage> messages, AiDialogueConfig.Settings settings,
            double temperature, int maximumTokens, int timeoutSeconds, Object format) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", settings.ollamaModel());
        body.put("messages", messages);
        body.put("stream", false);
        body.put("think", false);
        body.put("format", format);
        body.put("options", Map.of("temperature", temperature, "num_predict", maximumTokens));
        body.put("keep_alive", "10m");
        WireObserver observer = WIRE_OBSERVERS.get(requestId);
        String encodedBody = gson.toJson(body);
        try {
            if (observer != null) {
                observer.onRequest(encodedBody);
            }
            HttpRequest request = HttpRequest.newBuilder(settings.ollamaChatUrl())
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(encodedBody))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (observer != null) {
                observer.onResponse(response.body());
            }
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("Ollama HTTP " + response.statusCode() + ": "
                        + abbreviate(response.body(), 400));
            }
            return response.body();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (observer != null) {
                observer.onFailure(exception);
            }
            throw new IllegalStateException("Ollama request interrupted", exception);
        } catch (Exception exception) {
            if (observer != null) {
                observer.onFailure(exception);
            }
            throw new IllegalStateException("Ollama request failed", exception);
        }
    }

    private static Map<String, Object> dialogueSchema() {
        Map<String, Object> string = Map.of("type", "string");
        Map<String, Object> stringArray = Map.of("type", "array", "items", string);
        Map<String, Object> parameters = Map.of("type", "object", "additionalProperties", string);
        Map<String, Object> speech = Map.of("type", "object", "additionalProperties", false,
                "properties", Map.of("speakerId", string, "text", string, "audienceParticipantIds", stringArray),
                "required", List.of("speakerId", "text", "audienceParticipantIds"));
        Map<String, Object> proposal = Map.of("type", "object", "additionalProperties", false,
                "properties", Map.of("type", string, "title", string, "summary", string,
                        "targetParticipantIds", stringArray, "parameters", parameters),
                "required", List.of("type", "title", "summary", "targetParticipantIds", "parameters"));
        return Map.of("type", "object", "additionalProperties", false,
                "properties", Map.of("speech", Map.of("type", "array", "items", speech),
                        "currentTopic", string, "currentEmotion", Map.of("type", "string", "maxLength", RoomEmotionState.MAX_HINT_CHARACTERS),
                        "proposals", Map.of("type", "array", "items", proposal)),
                "required", List.of("speech", "currentTopic", "proposals"));
    }

    private AiDialogueModels.StructuredAiResult parse(String body) {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        JsonObject message = root.has("message") && root.get("message").isJsonObject()
                ? root.getAsJsonObject("message") : null;
        if (message == null || !message.has("content")) {
            throw new IllegalStateException("Ollama response does not contain message.content");
        }
        String content = message.get("content").getAsString();
        JsonElement structured = JsonParser.parseString(stripCodeFence(content));
        return parseStructuredResult(structured);
    }

    private ConversationIntent parseIntent(String body) {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        JsonObject message = root.has("message") && root.get("message").isJsonObject()
                ? root.getAsJsonObject("message") : null;
        if (message == null || !message.has("content")) {
            throw new IllegalStateException("Ollama intent response does not contain message.content");
        }
        JsonElement structured = JsonParser.parseString(stripCodeFence(message.get("content").getAsString()));
        if (!structured.isJsonObject()) {
            throw new IllegalStateException("Ollama intent result must be a JSON object");
        }
        JsonObject result = structured.getAsJsonObject();
        EnumSet<DialogueExampleTag> tags = EnumSet.noneOf(DialogueExampleTag.class);
        addIntentTag(tags, result.get("primarySituation"), true);
        for (JsonElement entry : array(result, "secondarySituations")) {
            addIntentTag(tags, entry, true);
        }
        for (JsonElement entry : array(result, "conversationTags")) {
            addIntentTag(tags, entry, false);
        }
        int confidence = 0;
        JsonElement rawConfidence = result.get("confidence");
        if (rawConfidence != null && rawConfidence.isJsonPrimitive()) {
            try {
                double parsed = rawConfidence.getAsDouble();
                confidence = parsed <= 1D ? (int) Math.round(parsed * 100D) : (int) Math.round(parsed);
            } catch (RuntimeException ignored) {
                // A malformed confidence only reduces routing confidence; it never invalidates the player turn.
            }
        }
        java.util.LinkedHashSet<String> knowledgeKeywords = new java.util.LinkedHashSet<>();
        for (JsonElement entry : array(result, "knowledgeKeywords")) {
            String keyword = text(entry);
            if (!keyword.isEmpty()) {
                knowledgeKeywords.add(keyword);
            }
        }
        EnumSet<PlayerSpeechTone> playerToneTags = EnumSet.noneOf(PlayerSpeechTone.class);
        for (JsonElement entry : array(result, "playerToneTags")) {
            try {
                playerToneTags.add(PlayerSpeechTone.valueOf(text(entry).toUpperCase(java.util.Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // Tone labels are advisory and must remain within the small, explicit vocabulary.
            }
            if (playerToneTags.size() == 3) {
                break;
            }
        }
        return new ConversationIntent(tags, knowledgeKeywords, playerToneTags,
                ConversationAct.fromWire(text(result.get("conversationAct"))), confidence,
                ConversationIntent.Source.LOCAL_LLM,
                com.sande.mythictrpg.ai.intent.TurnInterpretation.fromJson(result.get("turnInterpretation")));
    }

    private static void addIntentTag(EnumSet<DialogueExampleTag> tags, JsonElement raw, boolean situation) {
        String value = text(raw);
        try {
            DialogueExampleTag tag = DialogueExampleTag.valueOf(value);
            if (ConversationIntentRouter.allowedTags().contains(tag)
                    && (situation == tag.name().startsWith("S_"))) {
                tags.add(tag);
            }
        } catch (IllegalArgumentException ignored) {
            // Local models are constrained in the prompt, but unknown labels are safely ignored.
        }
    }

    /**
     * Local models occasionally add an unknown proposal parameter whose value is an object or an array.  Proposal
     * parameters are deliberately transported as strings, so a strict Gson record decode would discard the whole
     * response (including valid speech) for that one advisory field.  Read the small wire schema defensively instead:
     * scalar values stay scalar strings and complex, unknown values become compact JSON strings for later validation.
     */
    private AiDialogueModels.StructuredAiResult parseStructuredResult(JsonElement structured) {
        if (structured == null || !structured.isJsonObject()) {
            throw new IllegalStateException("Ollama structured result must be a JSON object");
        }
        JsonObject result = structured.getAsJsonObject();
        List<AiDialogueModels.Speech> speech = new ArrayList<>();
        for (JsonElement entry : array(result, "speech")) {
            if (!entry.isJsonObject()) {
                continue;
            }
            JsonObject value = entry.getAsJsonObject();
            speech.add(new AiDialogueModels.Speech(text(value.get("speakerId")), text(value.get("text")),
                    textList(value.get("audienceParticipantIds"))));
        }

        List<AiDialogueModels.Proposal> proposals = new ArrayList<>();
        for (JsonElement entry : array(result, "proposals")) {
            if (!entry.isJsonObject()) {
                continue;
            }
            JsonObject value = entry.getAsJsonObject();
            Map<String, String> parameters = new LinkedHashMap<>();
            JsonElement rawParameters = value.get("parameters");
            if (rawParameters != null && rawParameters.isJsonObject()) {
                for (Map.Entry<String, JsonElement> parameter : rawParameters.getAsJsonObject().entrySet()) {
                    parameters.put(parameter.getKey(), parameterValue(parameter.getValue()));
                }
            }
            proposals.add(new AiDialogueModels.Proposal(text(value.get("type")), text(value.get("title")),
                    text(value.get("summary")), textList(value.get("targetParticipantIds")), parameters));
        }
        JsonElement emotion = result.get("currentEmotion");
        String currentEmotion = emotion != null && emotion.isJsonPrimitive() && emotion.getAsJsonPrimitive().isString()
                ? emotion.getAsString() : "";
        return new AiDialogueModels.StructuredAiResult(speech, text(result.get("currentTopic")), proposals, currentEmotion);
    }

    private static JsonArray array(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : new JsonArray();
    }

    private static List<String> textList(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return List.of();
        }
        if (value.isJsonPrimitive()) {
            return List.of(text(value));
        }
        if (!value.isJsonArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonElement entry : value.getAsJsonArray()) {
            String text = text(entry);
            if (!text.isEmpty()) {
                values.add(text);
            }
        }
        return List.copyOf(values);
    }

    private String parameterValue(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return "";
        }
        return value.isJsonPrimitive() ? text(value) : gson.toJson(value);
    }

    private static String text(JsonElement value) {
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    @Override
    public void close() {
        scheduler.close();
    }

    private static String stripCodeFence(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.startsWith("```")) {
            int firstNewline = value.indexOf('\n');
            int closing = value.lastIndexOf("```");
            if (firstNewline >= 0 && closing > firstNewline) {
                value = value.substring(firstNewline + 1, closing).trim();
            }
        }
        return value;
    }

    private static String abbreviate(String value, int maximum) {
        if (value == null || value.length() <= maximum) {
            return value == null ? "" : value;
        }
        return value.substring(0, maximum) + "…";
    }

    interface WireObserver {
        void onRequest(String json);

        void onResponse(String json);

        void onFailure(Throwable failure);
    }
}
