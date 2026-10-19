package com.sande.mythictrpg.ai;

import com.google.gson.*;
import com.sande.mythictrpg.ai.api.RoomConversationEngine.*;
import net.minecraft.resources.ResourceLocation;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Two opt-in repair probes, at most four HTTP calls. Candidates require human review; no game publication. */
public final class GroundingRepairFollowupEvaluation {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final List<String> CASES = List.of("other_god_promise", "busy_false_completion_and_actor");

    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !args[2].equals("--execute-loopback"))
            throw new IllegalArgumentException("Usage: <existing run/live> <new output within that run> --execute-loopback");
        Path input = Path.of(args[0]).toAbsolutePath().normalize(), output = Path.of(args[1]).toAbsolutePath().normalize();
        if (!Files.isDirectory(input) || !output.startsWith(input.getParent()) || output.startsWith(input))
            throw new IllegalArgumentException("Use a separate output directory within the source evaluation run");
        for (String id : CASES) if (Files.exists(output.resolve(id + ".json")))
            throw new FileAlreadyExistsException(output.resolve(id + ".json").toString());
        Files.createDirectories(output);
        net.neoforged.fml.loading.FMLPaths.loadAbsolutePaths(output);
        var settings = new AiDialogueConfig.Settings(URI.create("http://127.0.0.1:11434/api/chat"), "gemma4:12b",
                180,600,420,20,260,180,true,96,3,1,16,2,3,3,120,3,false);
        var calls = new AtomicInteger();
        try (var llm = new LocalOllamaClient()) {
            for (String id : CASES) {
                var row = new LinkedHashMap<String, Object>(); int before = calls.get();
                row.put("source", input.resolve(id + ".json").toString()); row.put("model", settings.ollamaModel());
                row.put("scope", "One repair of a saved VALID REVISE; no Minecraft execution, publication or naturalness proof.");
                row.put("humanReviewNeeded", true); row.put("verdict", "NOT_REVIEWED");
                var controls = new LinkedHashMap<String, Object>(Map.of("generationTemperature", 0.6, "generationMaxTokens", 260,
                        "reviewTemperature", 0, "reviewTokenPolicy", "RoomDialogueGrounding.outputTokens(actualReviewMessages)",
                        "maximumTotalHttpCalls", 4, "transportRetries", false));
                row.put("controls", controls);
                try {
                    var source = JsonParser.parseString(Files.readString(input.resolve(id + ".json"))).getAsJsonObject();
                    var first = source.getAsJsonObject("candidate"); row.put("savedCandidateReview", first);
                    if (!first.get("status").getAsString().equals("VALID")) row.put("status", "SKIP_FIRST_REVIEW_NOT_VALID");
                    else {
                        var review = JSON.fromJson(first.get("parsedReview"), RoomDialogueGrounding.Review.class);
                        if (review.pass()) row.put("status", "SKIP_FIRST_REVIEW_PASS");
                        else {
                            var original = List.of(JSON.fromJson(source.get("canonicalOriginalMessages"), AiDialogueModels.OllamaMessage[].class));
                            var previous = JSON.fromJson(source.get("unchangedCurrentDraft"), AiDialogueModels.StructuredAiResult.class);
                            review.validateAgainst(previous);
                            var attempt = RoomDialogueGrounding.Attempt.initial(original).repair(previous, review);
                            var request = structureRequest(original);
                            row.put("canonicalOriginalMessages", original); row.put("previousDraft", previous);
                            row.put("generationMessages", attempt.generationMessages());
                            var candidate = (AiDialogueModels.StructuredAiResult) call(llm, attempt.generationMessages(), settings, row, "generation", calls, false);
                            row.put("generatedCandidate", candidate);
                            String issue = RoomPersonaPrompt.validationIssue(request, candidate); row.put("structuralIssue", issue);
                            if (!issue.isEmpty()) row.put("status", "STRUCTURE_REJECTED");
                            else {
                                var messages = RoomDialogueGrounding.reviewMessages(attempt.evidenceMessages(), candidate);
                                row.put("reviewMessages", messages);
                                controls.put("reviewMaxTokens", RoomDialogueGrounding.outputTokens(messages));
                                var verdict = (RoomDialogueGrounding.Review) call(llm, messages, settings, row, "review", calls, true);
                                row.put("review", verdict); verdict.validateAgainst(candidate);
                                row.put("verdict", verdict.pass() ? "PASS" : "REVISE"); row.put("status", "REVIEWED_CANDIDATE");
                            }
                        }
                    }
                } catch (Exception failure) { row.put("status", "FAILED"); row.put("error", failure.toString()); }
                row.put("httpCalls", calls.get() - before);
                Files.writeString(output.resolve(id + ".json"), JSON.toJson(row), StandardOpenOption.CREATE_NEW);
                System.out.println("FOLLOWUP " + id + " status=" + row.get("status") + " verdict=" + row.get("verdict") + " humanReviewNeeded=true");
            }
        }
    }

    private static Object call(LocalOllamaClient llm, List<AiDialogueModels.OllamaMessage> messages,
            AiDialogueConfig.Settings settings, Map<String, Object> row, String key, AtomicInteger calls, boolean review) throws Exception {
        UUID id = UUID.randomUUID(); var sent = new AtomicInteger();
        int expectedTokens = review ? RoomDialogueGrounding.outputTokens(messages) : settings.maxOutputTokens();
        List<Map<String, String>> wire = Collections.synchronizedList(new ArrayList<>()); row.put(key + "Wire", wire);
        LocalOllamaClient.installWireObserver(id, new LocalOllamaClient.WireObserver() {
            public void onRequest(String body) {
                // The production generation client retries parsing failures. Stop that retry BEFORE another HTTP request.
                if (sent.getAndIncrement() != 0 || calls.get() >= 4) throw new IllegalStateException("FOLLOWUP_HTTP_BUDGET_EXHAUSTED");
                wire.add(Map.of("request", body));
                var request = JsonParser.parseString(body).getAsJsonObject(); var options = request.getAsJsonObject("options");
                if (options.get("num_predict").getAsInt() != expectedTokens
                        || Double.compare(options.get("temperature").getAsDouble(), review ? 0.0 : 0.6) != 0
                        || request.get("think").getAsBoolean() || !request.get("model").getAsString().equals(settings.ollamaModel())
                        || !request.get("messages").equals(JSON.toJsonTree(messages))
                        || review && !request.get("format").equals(JSON.toJsonTree(RoomDialogueGrounding.schema(messages))))
                    throw new IllegalStateException("FOLLOWUP_WIRE_CONTROLS_DIFFER");
                calls.incrementAndGet();
            }
            public void onResponse(String body) { wire.add(Map.of("response", body)); }
            public void onFailure(Throwable failure) { wire.add(Map.of("error", failure.toString())); }
        });
        try {
            return review ? llm.submitReview(id, messages, settings).completion().get(125, TimeUnit.SECONDS).value()
                    : llm.submit(id, messages, settings).completion().get(190, TimeUnit.SECONDS).value();
        } finally { LocalOllamaClient.removeWireObserver(id); synchronized (wire) { row.put(key + "Wire", List.copyOf(wire)); } }
    }

    private static Request structureRequest(List<AiDialogueModels.OllamaMessage> original) {
        var scenes = original.stream().filter(m -> m.role().equals("user"))
                .map(m -> JsonParser.parseString(m.content()).getAsJsonObject()).filter(s -> s.has("authorizedSpeakerId")).toList();
        if (scenes.size() != 1) throw new IllegalArgumentException("Expected one unchanged canonical scene");
        var scene = scenes.getFirst(); var room = scene.getAsJsonObject("room"); var player = scene.getAsJsonObject("currentPlayer");
        var god = ResourceLocation.parse(scene.get("authorizedSpeakerId").getAsString());
        return new Request(UUID.fromString(room.get("id").getAsString()), room.get("revision").getAsLong(),
                UUID.fromString(room.get("turnId").getAsString()), UUID.fromString(player.get("id").getAsString()), player.get("name").getAsString(),
                Arrays.stream(JSON.fromJson(scene.get("participatingGodIds"), String[].class)).map(ResourceLocation::parse).toList(), god,
                scene.get("CURRENT_PLAYER_MESSAGE").getAsString(), List.of(JSON.fromJson(scene.get("RECENT_CONVERSATION"), HistoryLine[].class)),
                room.get("readOnly").getAsBoolean(), false, room.get("publicRoom").getAsBoolean(),
                List.of(new GodState(god, scene.getAsJsonObject("ownRelationship").get("tier").getAsString(),
                        scene.get("ownCurrentEmotion").getAsString(), scene.get("gameContextWithSourceBoundaries").getAsString(), null)),
                false, Set.of(JSON.fromJson(scene.get("audiencePlayerIds"), UUID[].class)),
                List.of(JSON.fromJson(scene.get("gameConfirmedActionOutcomes"), ActionOutcome[].class)));
    }
}
