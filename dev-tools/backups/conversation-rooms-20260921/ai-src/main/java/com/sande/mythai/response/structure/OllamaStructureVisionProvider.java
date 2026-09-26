package com.sande.mythai.response.structure;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sande.mythictrpg.ai.AiDialogueConfig;
import com.sande.mythictrpg.quest.structure.StructureVisualAssessment;
import com.sande.mythictrpg.quest.structure.StructureVisualEvaluationGateway;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Independent vision request path which shares the configured Ollama process and model. */
public final class OllamaStructureVisionProvider implements StructureVisualEvaluationGateway.Provider {
    public static final OllamaStructureVisionProvider INSTANCE = new OllamaStructureVisionProvider();
    private static final Set<String> RESPONSE_FIELDS = Set.of("buildingType", "subtype", "styles", "confidence",
            "visualQualityScore", "godPreferenceScore", "completenessScore", "evidence", "concerns");
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), runnable -> {
                Thread thread = new Thread(runnable, "mythai-structure-vision"); thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    private OllamaStructureVisionProvider() {}

    @Override
    public CompletionStage<StructureVisualAssessment> evaluate(StructureVisualEvaluationGateway.Request request) {
        var admission = com.sande.mythai.response.memory.ModelAdmission.foreground();
        try {
            var settings = AiDialogueConfig.INSTANCE.settings();
            var result = CompletableFuture.supplyAsync(() -> com.sande.mythai.response.memory.ModelAdmission.run(
                    admission, settings.maxConcurrentLlmRequests(), settings.requestTimeoutSeconds() * 1000L,
                    () -> call(request)), worker);
            result.whenComplete((value, failure) -> admission.close());
            return result;
        } catch (RuntimeException exception) {
            admission.close();
            return CompletableFuture.failedFuture(exception);
        }
    }

    private StructureVisualAssessment call(StructureVisualEvaluationGateway.Request request) {
        AiDialogueConfig.Settings settings = AiDialogueConfig.INSTANCE.settings();
        JsonObject body = new JsonObject(); body.addProperty("model", settings.ollamaModel());
        body.add("messages", messages(request)); body.add("format", schema());
        body.addProperty("stream", false); body.addProperty("think", false); body.addProperty("keep_alive", "10m");
        JsonObject options = new JsonObject(); options.addProperty("temperature", 0);
        options.addProperty("num_predict", Math.max(384, Math.min(768, settings.maxOutputTokens() * 2)));
        body.add("options", options);
        HttpRequest httpRequest = HttpRequest.newBuilder(settings.ollamaChatUrl())
                .timeout(Duration.ofSeconds(settings.requestTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
        try {
            HttpResponse<String> response = client.send(httpRequest,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Ollama returned HTTP " + response.statusCode());
            }
            JsonObject envelope = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonObject message = requiredObject(envelope, "message");
            String content = requiredString(message, "content");
            return parseAssessment(JsonParser.parseString(content).getAsJsonObject(), settings.ollamaModel());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("Structure vision request interrupted", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("Structure vision request failed", exception);
        }
    }

    private static JsonArray messages(StructureVisualEvaluationGateway.Request request) {
        JsonArray messages = new JsonArray();
        JsonObject system = new JsonObject(); system.addProperty("role", "system");
        system.addProperty("content", """
                You are a cautious Minecraft architecture image evaluator. The images are authoritative software voxel
                projections of player/team-placed blocks: four isometric directions followed by a top view. Terrain,
                entities, textures, stairs/slabs geometry, and some interior detail may be absent. Use the supplied
                objective evidence together with all views. Never infer hidden rooms or functions from the structure
                name alone. Return only the required JSON. Scores are integers from 0 to 100 and confidence is 0 to 1.
                buildingType must be exactly one of HOUSE, TEMPLE, ARENA_AMPHITHEATER, FORTRESS_CASTLE, TOWER, FARM,
                WORKSHOP, BRIDGE, MONUMENT, PUBLIC_BUILDING, SHIP, OTHER, UNKNOWN. subtype and styles may be flexible,
                concise labels. Use UNKNOWN and low confidence when the visual evidence is insufficient.
                """); messages.add(system);
        JsonObject user = new JsonObject(); user.addProperty("role", "user");
        user.addProperty("content", prompt(request));
        JsonArray images = new JsonArray();
        request.views().forEach(view -> images.add(Base64.getEncoder().encodeToString(view.pngBytes())));
        user.add("images", images); messages.add(user); return messages;
    }

    private static String prompt(StructureVisualEvaluationGateway.Request request) {
        StringBuilder features = new StringBuilder();
        request.objectiveFeatures().entrySet().stream().sorted(Map.Entry.comparingByKey()).limit(48)
                .forEach(entry -> features.append(entry.getKey()).append('=').append(round(entry.getValue())).append('\n'));
        return """
                [UNTRUSTED_PLAYER_LABEL]
                %s
                [POLICY]
                policy=%s
                god=%s
                preferred_styles=%s
                favored_types=%s
                visual_preference_guidance=%s
                [OBJECTIVE_ANALYSIS]
                score=%d
                build_score=%s
                environment_score=%s
                evidence=%s
                features:
                %s
                [VIEW_ORDER]
                %s
                [TASK]
                1. Classify the visible construction's primary type. Do not use its player label as evidence.
                2. Give a flexible subtype and style labels only when visually supported.
                3. Score visible design quality, apparent completeness, and fit with this God's stated preferences.
                4. Give concise Korean evidence and concerns tied to visible shape, proportion, palette, facade, roof,
                   circulation/opening cues, or objective features. Do not award points merely for block quantity.
                """.formatted(request.structureName(), request.policyId(), request.godId(),
                request.visualProfile().preferredStyles(), request.visualProfile().favoredTypes(),
                request.visualProfile().guidance(), request.objectiveScore(), round(request.buildScore()),
                round(request.environmentScore()), request.objectiveEvidence(), features,
                request.views().stream().map(StructureVisualEvaluationGateway.RenderedView::name).toList());
    }

    private static StructureVisualAssessment parseAssessment(JsonObject json, String model) {
        if (!json.keySet().equals(RESPONSE_FIELDS)) throw new IllegalArgumentException("Unexpected visual response fields");
        return new StructureVisualAssessment(StructureVisualAssessment.BuildingType.parse(requiredString(json,"buildingType")),
                requiredString(json,"subtype"), strings(json,"styles",6), number(json,"confidence"),
                integer(json,"visualQualityScore"), integer(json,"godPreferenceScore"),
                integer(json,"completenessScore"), strings(json,"evidence",6), strings(json,"concerns",4),
                "ollama:" + model);
    }

    private static JsonObject schema() {
        JsonObject root = new JsonObject(); root.addProperty("type", "object");
        JsonObject properties = new JsonObject();
        properties.add("buildingType", enumString(List.of("HOUSE","TEMPLE","ARENA_AMPHITHEATER",
                "FORTRESS_CASTLE","TOWER","FARM","WORKSHOP","BRIDGE","MONUMENT","PUBLIC_BUILDING",
                "SHIP","OTHER","UNKNOWN")));
        properties.add("subtype", typed("string")); properties.add("styles", stringArray(6));
        properties.add("confidence", rangedNumber("number",0,1));
        properties.add("visualQualityScore", rangedNumber("integer",0,100));
        properties.add("godPreferenceScore", rangedNumber("integer",0,100));
        properties.add("completenessScore", rangedNumber("integer",0,100));
        properties.add("evidence", stringArray(6)); properties.add("concerns", stringArray(4));
        root.add("properties", properties); JsonArray required = new JsonArray();
        RESPONSE_FIELDS.forEach(required::add); root.add("required", required); root.addProperty("additionalProperties", false);
        return root;
    }

    private static JsonObject typed(String type) { JsonObject value=new JsonObject();value.addProperty("type",type);return value; }
    private static JsonObject enumString(List<String> values){JsonObject value=typed("string");JsonArray array=new JsonArray();values.forEach(array::add);value.add("enum",array);return value;}
    private static JsonObject stringArray(int maximum){JsonObject value=typed("array");value.add("items",typed("string"));value.addProperty("maxItems",maximum);return value;}
    private static JsonObject rangedNumber(String type,int minimum,int maximum){JsonObject value=typed(type);value.addProperty("minimum",minimum);value.addProperty("maximum",maximum);return value;}
    private static JsonObject requiredObject(JsonObject json,String key){JsonElement value=json.get(key);if(value==null||!value.isJsonObject())throw new IllegalArgumentException("Missing object "+key);return value.getAsJsonObject();}
    private static String requiredString(JsonObject json,String key){JsonElement value=json.get(key);if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString())throw new IllegalArgumentException("Missing string "+key);return value.getAsString();}
    private static double number(JsonObject json,String key){JsonElement value=json.get(key);if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("Missing number "+key);double result=value.getAsDouble();if(!Double.isFinite(result))throw new IllegalArgumentException("Non-finite "+key);return result;}
    private static int integer(JsonObject json,String key){double value=number(json,key);if(value!=Math.rint(value))throw new IllegalArgumentException(key+" must be integer");return (int)value;}
    private static List<String> strings(JsonObject json,String key,int maximum){JsonElement raw=json.get(key);if(raw==null||!raw.isJsonArray())throw new IllegalArgumentException("Missing array "+key);List<String> values=new ArrayList<>();for(JsonElement value:raw.getAsJsonArray()){if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString())throw new IllegalArgumentException(key+" must contain strings");values.add(value.getAsString());if(values.size()>maximum)throw new IllegalArgumentException(key+" is too large");}return List.copyOf(values);}
    private static String round(double value){return String.format(java.util.Locale.ROOT,"%.3f",value);}
}
