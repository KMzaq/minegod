package com.sande.mythictrpg.ai;

import com.google.gson.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Bounded opt-in audit comparison. No generation, repair, Minecraft server, or game-state writes. */
public final class DialogueGroundingComparisonEvaluation {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String MODEL = "gemma4:12b";
    private static final String SOURCE = "검토결과/actual-persona-dialogue-20261007/";
    private static final int CASE_COUNT = 14;

    private record Case(String id, String expectedVerdict, Set<String> expectedCodesAnyOf, String rationale,
            String provenance, List<String> sourceFiles, List<AiDialogueModels.OllamaMessage> canonicalOriginal,
            List<AiDialogueModels.OllamaMessage> baselineOriginal, AiDialogueModels.StructuredAiResult draft,
            JsonArray historicalReviewWire) { }
    private record ReusedBaseline(Map<String, Map<String, Object>> reviews, Map<String, Object> proof) { }

    public static void main(String[] args) throws Exception {
        boolean candidateOnly = args.length >= 3 && args[2].equals("--execute-candidate-loopback");
        boolean finalControl = args.length >= 3 && args[2].equals("--candidate-only");
        if (candidateOnly ? args.length != 4 : args.length != 3 || !Set.of("--preflight", "--execute-loopback", "--candidate-only").contains(args[2]))
            throw new IllegalArgumentException("Usage: <workspace> <output-directory> --preflight|--execute-loopback|--candidate-only OR --execute-candidate-loopback <existing-baseline-live-directory>");
        Path workspace = Path.of(args[0]).toAbsolutePath().normalize();
        Path output = Path.of(args[1]).toAbsolutePath().normalize();
        if (output.startsWith(workspace.resolve("server")))
            throw new IllegalArgumentException("Evaluation output must not be the live server directory");
        List<Case> cases = cases(workspace);
        if (finalControl) {
            var extended = new ArrayList<>(cases);
            extended.add(independentIntentionControl(workspace, cases.getFirst()));
            cases = List.copyOf(extended);
        }
        validateFixtures(cases, CASE_COUNT + (finalControl ? 1 : 0));
        ReusedBaseline reused = candidateOnly ? loadBaseline(workspace, Path.of(args[3]).toAbsolutePath().normalize(), cases) : null;
        Files.createDirectories(output);
        net.neoforged.fml.loading.FMLPaths.loadAbsolutePaths(output);
        var manifest = new LinkedHashMap<String, Object>();
        manifest.put("scope", "Small labelled regression set. Frozen prior drafts and explicit counterfactuals; not a naturalness or in-game score.");
        manifest.put("model", MODEL);
        manifest.put("controls", Map.of("temperature", 0, "baselineMaxOutputTokens", 512,
                "candidateMaxOutputTokens", "RoomDialogueGrounding.outputTokens for each exact message list", "think", false,
                "maxReviewCalls", cases.size() * (candidateOnly || finalControl ? 1 : 2), "reviewTimeoutSeconds", 60, "generationCalls", 0));
        manifest.put("comparison", finalControl
                ? "Candidate-only control run: unchanged 14 cases plus one saved independent-intention repair. No baseline measured or reused."
                : "Identical current draft and canonical scene per pair. Only baseline receives prior repair context for the two repaired cases, reproducing the old call site.");
        manifest.put("caseCount", cases.size());
        manifest.put("sourceSha256", sourceHashes(workspace, cases));
        manifest.put("cases", cases.stream().map(DialogueGroundingComparisonEvaluation::describe).toList());
        if (!finalControl) manifest.put("baselineMethod", candidateOnly ? "REUSED_FROZEN_V3_PREVIOUS_RUN" : "FRESH_FROZEN_V3_PAIRED_RUN");
        if (candidateOnly) manifest.put("baselineReuseProof", reused.proof());
        if (args[2].equals("--preflight")) {
            writeNew(output.resolve("preflight.json"), manifest);
            System.out.println("PREFLIGHT " + cases.size() + " cases; 0 model calls; historical invalid reviews kept separate");
            return;
        }
        Path live = output.resolve("live");
        if (Files.exists(live)) throw new IllegalStateException("Use a new output directory; live results are immutable: " + live);
        Files.createDirectory(live);
        writeNew(live.resolve("manifest.json"), manifest);
        var settings = new AiDialogueConfig.Settings(URI.create("http://127.0.0.1:11434/api/chat"), MODEL,
                60, 600, 420, 20, 512, 180, true, 96, 3, 1, 16, 2, 3, 3, 120, 3, false);
        var results = new ArrayList<Map<String, Object>>();
        try (var llm = new LocalOllamaClient()) {
            for (int i = 0; i < cases.size(); i++) {
                Case c = cases.get(i);
                var row = describe(c);
                // Alternating order avoids always charging model cold-load to the baseline.
                List<String> order = candidateOnly || finalControl ? List.of("candidate")
                        : i % 2 == 0 ? List.of("baseline", "candidate") : List.of("candidate", "baseline");
                row.put("callOrder", order);
                if (candidateOnly) {
                    row.put("baseline", reused.reviews().get(c.id()));
                    row.put("baselineMeasurementOrigin", "REUSED_FROZEN_V3_PREVIOUS_RUN");
                    writeNew(live.resolve(c.id() + ".baseline.json"), reused.reviews().get(c.id()));
                }
                for (String variant : order) {
                    var messages = variant.equals("baseline")
                            ? BaselineV3RoomDialogueGrounding20261008.reviewMessages(c.baselineOriginal(), c.draft())
                            : RoomDialogueGrounding.Attempt.initial(c.canonicalOriginal()).reviewMessages(c.draft());
                    System.out.println("START " + c.id() + " " + variant);
                    var review = review(llm, messages, c, settings);
                    row.put(variant, review);
                    // Save every completed call, including invalid/error results, without retrying selected cases.
                    writeNew(live.resolve(c.id() + "." + variant + ".json"), review);
                    System.out.println("END " + c.id() + " " + variant + " status=" + review.get("status")
                            + " verdict=" + review.getOrDefault("verdict", "NONE"));
                }
                writeNew(live.resolve(c.id() + ".json"), row);
                results.add(row);
            }
        }
        var summary = new LinkedHashMap<String, Object>();
        summary.put("caseCount", cases.size());
        if (!finalControl) {
            var baselineSummary = new LinkedHashMap<>(summarize(results, "baseline"));
            baselineSummary.put("method", candidateOnly ? "REUSED_FROZEN_V3_PREVIOUS_RUN" : "FRESH_FROZEN_V3_PAIRED_RUN");
            if (candidateOnly) {
                baselineSummary.put("sourceLiveDirectory", reused.proof().get("sourceLiveDirectory"));
                baselineSummary.put("sourceManifestSha256", reused.proof().get("sourceManifestSha256"));
                summary.put("baselineReuseProof", reused.proof());
            }
            summary.put("baseline", baselineSummary);
        }
        summary.put("candidate", summarize(results, "candidate"));
        summary.put("newReviewCalls", cases.size() * (candidateOnly || finalControl ? 1 : 2));
        summary.put("measurementCaveat", finalControl
                ? "Candidate-only control run; no fresh or reused baseline. One additional saved generated draft tests independent present intention without claiming a past promise."
                : candidateOnly
                ? "Baseline was measured earlier and reused after fixture/request/response validation; timing is not a new interleaved paired benchmark."
                : "Fresh baseline and candidate calls alternate order for the same fixed scenes and drafts.");
        summary.put("definitions", Map.of("falseAccept", "valid PASS for an expected REVISE",
                "falseReject", "valid REVISE for an expected PASS", "invalidReview", "invalid schema or non-current-draft quote; fail-closed, excluded from valid semantic confusion counts",
                "callFailure", "request/transport/timeout failure; fail-closed, not a semantic verdict"));
        writeNew(live.resolve("summary.json"), summary);
        System.out.println(JSON.toJson(summary));
    }

    private static Case independentIntentionControl(Path workspace, Case original) throws Exception {
        String path = "검토결과/grounding-review-20261008/run-v5/repair-followup/other_god_promise.json";
        var source = JsonParser.parseString(Files.readString(workspace.resolve(path))).getAsJsonObject();
        validateSameMessages(original.canonicalOriginal(), source.getAsJsonArray("canonicalOriginalMessages"), "independent intention original scene");
        var canonical = messages(source, "canonicalOriginalMessages");
        return new Case("new_intention_not_past_promise", "PASS", Set.of(),
                "The current game context says Fortuna is reading a book. Offering to tell a passage now does not claim she made Demeter's earlier promise; the repaired speech contains no retrospective ownership claim.",
                "Exact generatedCandidate from the saved V5 follow-up; same canonical original scene and no rejected-draft context. No new generation.",
                List.of(SOURCE + "live/fortuna_other_promise_1.json", path), canonical, canonical,
                draft(source, "generatedCandidate"), source.getAsJsonArray("reviewWire"));
    }

    /** Reuse only the complete original V3 run, retaining invalid reviews as invalid rather than cherry-picking. */
    @SuppressWarnings("unchecked")
    private static ReusedBaseline loadBaseline(Path workspace, Path live, List<Case> cases) throws Exception {
        Path manifestPath = live.resolve("manifest.json");
        var manifest = JsonParser.parseString(Files.readString(manifestPath)).getAsJsonObject();
        if (!MODEL.equals(manifest.get("model").getAsString()) || manifest.get("caseCount").getAsInt() != CASE_COUNT)
            throw new IllegalStateException("Baseline model/case count mismatch");
        var hashes = manifest.getAsJsonObject("sourceSha256");
        var verifiedSourceHashes = new TreeMap<String, String>();
        for (var entry : sourceHashes(workspace, cases).entrySet()) {
            // Production policy is the changed candidate; the frozen V3 helper and every fixture must be identical.
            if (entry.getKey().equals("mythai-ai-response/src/main/java/com/sande/mythictrpg/ai/RoomDialogueGrounding.java")) continue;
            if (!hashes.has(entry.getKey()) || !entry.getValue().equals(hashes.get(entry.getKey()).getAsString()))
                throw new IllegalStateException("Baseline source hash mismatch: " + entry.getKey());
            verifiedSourceHashes.put(entry.getKey(), entry.getValue());
        }
        var recordedCases = new LinkedHashMap<String, JsonObject>();
        for (JsonElement element : manifest.getAsJsonArray("cases")) {
            var saved = element.getAsJsonObject();
            String id = saved.get("id").getAsString();
            if (recordedCases.putIfAbsent(id, saved) != null) throw new IllegalStateException("Duplicate baseline case " + id);
        }
        if (!recordedCases.keySet().equals(new LinkedHashSet<>(cases.stream().map(Case::id).toList())))
            throw new IllegalStateException("Baseline case IDs differ");
        var reviews = new LinkedHashMap<String, Map<String, Object>>();
        var resultHashes = new TreeMap<String, String>();
        for (Case c : cases) {
            Path pairPath = live.resolve(c.id() + ".json");
            Path baselinePath = live.resolve(c.id() + ".baseline.json");
            var pair = JsonParser.parseString(Files.readString(pairPath)).getAsJsonObject();
            var baseline = JsonParser.parseString(Files.readString(baselinePath)).getAsJsonObject();
            validateSameFixture(c, recordedCases.get(c.id()));
            validateSameFixture(c, pair);
            if (!baseline.equals(pair.getAsJsonObject("baseline")))
                throw new IllegalStateException("Baseline standalone result differs from saved pair: " + c.id());
            if (!baseline.get("caseId").getAsString().equals(c.id())
                    || !baseline.get("expectedVerdict").getAsString().equals(c.expectedVerdict())
                    || !stringSet(baseline.getAsJsonArray("expectedCodesAnyOf")).equals(c.expectedCodesAnyOf()))
                throw new IllegalStateException("Baseline expected labels differ: " + c.id());
            var expectedMessages = BaselineV3RoomDialogueGrounding20261008.reviewMessages(c.baselineOriginal(), c.draft());
            validateSameMessages(expectedMessages, baseline.getAsJsonArray("messages"), c.id() + " result");
            JsonArray wire = baseline.getAsJsonArray("wire");
            int requests = 0, responses = 0;
            for (JsonElement element : wire) {
                var event = element.getAsJsonObject();
                if (event.has("request")) {
                    requests++;
                    String body = event.get("request").getAsString();
                    validateRequestControls(List.of(Map.of("request", body)));
                    var request = JsonParser.parseString(body).getAsJsonObject();
                    validateSameMessages(expectedMessages, request.getAsJsonArray("messages"), c.id() + " exact wire");
                    if (request.get("stream").getAsBoolean()) throw new IllegalStateException("Unexpected streamed baseline request");
                }
                if (event.has("response")) responses++;
            }
            if (requests != 1 || responses != 1) throw new IllegalStateException("Expected one complete baseline call: " + c.id());
            var rechecked = historical(wire, c.draft());
            if (!baseline.get("status").getAsString().equals(rechecked.get("status")))
                throw new IllegalStateException("Baseline raw response status does not match saved result: " + c.id());
            if (rechecked.get("status").equals("VALID")) {
                var verdict = (RoomDialogueGrounding.Review) rechecked.get("parsedReview");
                boolean expectedMatch = c.expectedVerdict().equals(rechecked.get("verdict"));
                boolean codeMatch = c.expectedCodesAnyOf().isEmpty()
                        || verdict.issues().stream().anyMatch(issue -> c.expectedCodesAnyOf().contains(issue.code().name()));
                if (!baseline.get("verdict").getAsString().equals(rechecked.get("verdict"))
                        || baseline.get("matchesExpectedVerdict").getAsBoolean() != expectedMatch
                        || baseline.get("matchesExpectedCode").getAsBoolean() != codeMatch)
                    throw new IllegalStateException("Baseline raw verdict/label mismatch: " + c.id());
            } else if (!baseline.get("failClosed").getAsBoolean()) {
                throw new IllegalStateException("Invalid baseline review was not fail-closed: " + c.id());
            }
            reviews.put(c.id(), JSON.fromJson(baseline, LinkedHashMap.class));
            resultHashes.put(c.id() + ".json", sha256(pairPath));
            resultHashes.put(c.id() + ".baseline.json", sha256(baselinePath));
        }
        var proof = new LinkedHashMap<String, Object>();
        proof.put("method", "REUSED_FROZEN_V3_PREVIOUS_RUN");
        proof.put("sourceLiveDirectory", live.toString());
        proof.put("sourceManifestSha256", sha256(manifestPath));
        proof.put("sourceResultSha256", resultHashes);
        proof.put("verifiedFrozenPolicyAndFixtureSha256", verifiedSourceHashes);
        proof.put("verification", "All 14 case IDs/labels/current drafts/canonical scenes/baseline repair inputs/frozen V3 requests match. One exact saved request and response per case; raw review reparsed and current-draft quotes revalidated. JSON object key order is ignored; policy and non-JSON content are exact.");
        return new ReusedBaseline(Map.copyOf(reviews), proof);
    }

    private static void validateSameFixture(Case c, JsonObject saved) {
        if (!saved.get("id").getAsString().equals(c.id()) || !saved.get("expectedVerdict").getAsString().equals(c.expectedVerdict())
                || !stringSet(saved.getAsJsonArray("expectedCodesAnyOf")).equals(c.expectedCodesAnyOf())
                || !JSON.toJsonTree(c.draft()).equals(saved.get("unchangedCurrentDraft")))
            throw new IllegalStateException("Baseline fixture draft/label mismatch: " + c.id());
        validateSameMessages(c.canonicalOriginal(), saved.getAsJsonArray("canonicalOriginalMessages"), c.id() + " canonical");
        validateSameMessages(c.baselineOriginal(), saved.getAsJsonArray("baselineOriginalMessages"), c.id() + " baseline original");
        validateSameMessages(BaselineV3RoomDialogueGrounding20261008.reviewMessages(c.baselineOriginal(), c.draft()),
                saved.getAsJsonArray("baselineReviewMessages"), c.id() + " V3 review");
    }

    private static void validateSameMessages(List<AiDialogueModels.OllamaMessage> expected, JsonArray encoded, String label) {
        var actual = Arrays.asList(JSON.fromJson(encoded, AiDialogueModels.OllamaMessage[].class));
        if (expected.size() != actual.size()) throw new IllegalStateException("Baseline message count mismatch: " + label);
        for (int i = 0; i < expected.size(); i++) {
            if (!expected.get(i).role().equals(actual.get(i).role())
                    || !sameContent(expected.get(i).content(), actual.get(i).content()))
                throw new IllegalStateException("Baseline message content mismatch: " + label + " index=" + i);
        }
    }

    private static boolean sameContent(String expected, String actual) {
        if (expected.equals(actual)) return true;
        try { return JsonParser.parseString(expected).equals(JsonParser.parseString(actual)); }
        catch (RuntimeException nonJson) { return false; }
    }

    private static Set<String> stringSet(JsonArray array) {
        var result = new LinkedHashSet<String>();
        for (JsonElement value : array) if (!result.add(value.getAsString())) throw new IllegalStateException("Duplicate expected code");
        return Set.copyOf(result);
    }

    private static Map<String, Object> describe(Case c) {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", c.id());
        row.put("expectedVerdict", c.expectedVerdict());
        row.put("expectedCodesAnyOf", c.expectedCodesAnyOf());
        row.put("rationale", c.rationale());
        row.put("provenance", c.provenance());
        row.put("sourceFiles", c.sourceFiles());
        row.put("canonicalOriginalMessages", c.canonicalOriginal());
        row.put("baselineOriginalMessages", c.baselineOriginal());
        row.put("unchangedCurrentDraft", c.draft());
        row.put("baselineReviewMessages", BaselineV3RoomDialogueGrounding20261008.reviewMessages(c.baselineOriginal(), c.draft()));
        row.put("candidateReviewMessages", RoomDialogueGrounding.Attempt.initial(c.canonicalOriginal()).reviewMessages(c.draft()));
        if (c.historicalReviewWire() != null) row.put("historicalReview", historical(c.historicalReviewWire(), c.draft()));
        return row;
    }

    private static Map<String, Object> review(LocalOllamaClient llm, List<AiDialogueModels.OllamaMessage> messages,
            Case c, AiDialogueConfig.Settings settings) throws Exception {
        var result = new LinkedHashMap<String, Object>();
        result.put("caseId", c.id());
        result.put("expectedVerdict", c.expectedVerdict());
        result.put("expectedCodesAnyOf", c.expectedCodesAnyOf());
        result.put("messages", messages);
        List<Map<String, String>> wire = Collections.synchronizedList(new ArrayList<>());
        result.put("wire", wire);
        UUID id = UUID.randomUUID();
        LocalOllamaClient.installWireObserver(id, new LocalOllamaClient.WireObserver() {
            public void onRequest(String body) { wire.add(Map.of("request", body)); }
            public void onResponse(String body) { wire.add(Map.of("response", body)); }
            public void onFailure(Throwable error) { wire.add(Map.of("error", error.toString())); }
        });
        long started = System.nanoTime();
        try {
            var measured = llm.submitReview(id, messages, settings).completion().get(125, TimeUnit.SECONDS);
            var verdict = measured.value();
            result.put("parsedReview", verdict);
            verdict.validateAgainst(c.draft()); // Production validation is deliberately retained.
            result.put("status", "VALID");
            result.put("verdict", verdict.pass() ? "PASS" : "REVISE");
            result.put("matchesExpectedVerdict", c.expectedVerdict().equals(result.get("verdict")));
            result.put("matchesExpectedCode", c.expectedCodesAnyOf().isEmpty()
                    || verdict.issues().stream().anyMatch(issue -> c.expectedCodesAnyOf().contains(issue.code().name())));
            result.put("queueWaitMs", measured.queueWait().toMillis());
            result.put("executionMs", measured.requestDuration().toMillis());
        } catch (Exception failure) {
            // A response with review content that cannot satisfy parse/quote validation is not REVISE.
            boolean hasContent = wire.stream().filter(e -> e.containsKey("response"))
                    .anyMatch(e -> hasReviewContent(e.get("response")));
            result.put("status", hasContent ? "INVALID_REVIEW" : "CALL_FAILURE");
            result.put("failure", failure.toString());
            result.put("failClosed", true);
        } finally {
            LocalOllamaClient.removeWireObserver(id);
            result.put("latencyMs", (System.nanoTime() - started) / 1_000_000);
            List<Map<String, String>> captured;
            synchronized (wire) { captured = List.copyOf(wire); }
            result.put("wire", captured);
            result.put("metrics", metrics(captured));
            validateRequestControls(captured);
        }
        return result;
    }

    private static Map<String, Object> historical(JsonArray wire, AiDialogueModels.StructuredAiResult draft) {
        var result = new LinkedHashMap<String, Object>();
        result.put("wire", wire);
        for (JsonElement item : wire) {
            JsonObject event = item.getAsJsonObject();
            if (!event.has("response")) continue;
            try {
                var envelope = JsonParser.parseString(event.get("response").getAsString()).getAsJsonObject();
                var verdict = RoomDialogueGrounding.parse(JsonParser.parseString(
                        envelope.getAsJsonObject("message").get("content").getAsString()).getAsJsonObject());
                result.put("parsedReview", verdict);
                verdict.validateAgainst(draft);
                result.put("status", "VALID");
                result.put("verdict", verdict.pass() ? "PASS" : "REVISE");
            } catch (RuntimeException failure) {
                result.put("status", "INVALID_REVIEW");
                result.put("failure", failure.toString());
                result.put("failClosed", true);
            }
        }
        return result;
    }

    private static boolean hasReviewContent(String response) {
        try {
            var envelope = JsonParser.parseString(response).getAsJsonObject();
            return envelope.has("message") && envelope.getAsJsonObject("message").has("content");
        } catch (RuntimeException ignored) { return false; }
    }

    private static List<Map<String, JsonElement>> metrics(List<Map<String, String>> wire) {
        var result = new ArrayList<Map<String, JsonElement>>();
        for (var event : wire) if (event.containsKey("response")) {
            try {
                var envelope = JsonParser.parseString(event.get("response")).getAsJsonObject();
                var row = new LinkedHashMap<String, JsonElement>();
                for (String key : List.of("model", "done", "done_reason", "total_duration", "load_duration",
                        "prompt_eval_count", "prompt_eval_duration", "eval_count", "eval_duration"))
                    if (envelope.has(key)) row.put(key, envelope.get(key));
                result.add(row);
            } catch (RuntimeException ignored) { /* Raw malformed response remains in the wire evidence. */ }
        }
        return result;
    }

    private static void validateRequestControls(List<Map<String, String>> wire) {
        for (var event : wire) if (event.containsKey("request")) {
            var request = JsonParser.parseString(event.get("request")).getAsJsonObject();
            var options = request.getAsJsonObject("options");
            var messages = Arrays.asList(JSON.fromJson(request.get("messages"), AiDialogueModels.OllamaMessage[].class));
            if (!request.get("model").getAsString().equals(MODEL) || request.get("think").getAsBoolean()
                    || options.get("temperature").getAsDouble() != 0
                    || options.get("num_predict").getAsInt() != RoomDialogueGrounding.outputTokens(messages))
                throw new IllegalStateException("Review request violated paired comparison controls");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> summarize(List<Map<String, Object>> rows, String variant) {
        int correct = 0, falseAccept = 0, falseReject = 0, invalid = 0, failure = 0, codeMismatch = 0;
        long latency = 0;
        for (var row : rows) {
            var review = (Map<String, Object>) row.get(variant);
            latency += ((Number) review.get("latencyMs")).longValue();
            if (review.get("status").equals("INVALID_REVIEW")) { invalid++; continue; }
            if (review.get("status").equals("CALL_FAILURE")) { failure++; continue; }
            if (Boolean.TRUE.equals(review.get("matchesExpectedVerdict"))) correct++;
            else if (review.get("verdict").equals("PASS")) falseAccept++;
            else falseReject++;
            if (!Boolean.TRUE.equals(review.get("matchesExpectedCode"))) codeMismatch++;
        }
        return Map.of("validCorrect", correct, "falseAccept", falseAccept, "falseReject", falseReject,
                "invalidReview", invalid, "callFailure", failure, "expectedCodeMismatch", codeMismatch,
                "totalLatencyMs", latency, "meanLatencyMs", rows.isEmpty() ? 0.0 : (double) latency / rows.size());
    }

    private static List<Case> cases(Path workspace) throws Exception {
        var out = new ArrayList<Case>();
        JsonObject other = read(workspace, "live/fortuna_other_promise_1.json");
        JsonObject busy = read(workspace, "live/demeter_busy_3.json");
        JsonObject reported = read(workspace, "live/demeter_reported_1.json");
        JsonObject missing = read(workspace, "live/fortuna_no_memory_1.json");
        JsonObject recovery = read(workspace, "structural-recovery.json");
        out.add(saved("other_god_promise", other, "live/fortuna_other_promise_1.json", "draft", "messages", "reviewWire",
                "REVISE", Set.of("WRONG_SOURCE"), "Fortuna calls Demeter's recorded promise her own; the current player's presupposition does not change the recorded speaker."));
        out.add(saved("busy_false_completion_and_actor", busy, "live/demeter_busy_3.json", "draft", "messages", "reviewWire",
                "REVISE", Set.of("CONTEXT_CONTRADICTION", "ROLE_CONFUSION", "UNEXECUTED_ACTION"),
                "The game still says records are unfinished. The player allowed Demeter time; Demeter asked the player to wait, not the reverse."));
        out.add(saved("conditional_reported_book_repair", reported, "live/demeter_reported_1.json", "repairedDraft", "repairMessages", "repairReviewWire",
                "PASS", Set.of(), "The latest speech repeats the player's claim as a question and praises conditionally; it does not claim witnessing or independently confirmed execution."));
        out.add(new Case("fixed_current_speaker_old_rejected_id", "PASS", Set.of(),
                "The latest draft has the authorized Fortuna speakerId. The typo exists only in the previous rejected draft, and is not an excerpt of current speech.",
                "Exact saved repaired draft. Baseline keeps saved structural-repair context; candidate uses the original generation scene.",
                List.of(SOURCE + "live/fortuna_no_memory_1.json", SOURCE + "structural-recovery.json"),
                messages(missing, "messages"), messages(recovery, "messages"), draft(recovery, "repairedDraft"), recovery.getAsJsonArray("repairReviewWire")));
        var selfMessages = editScene(messages(other, "messages"), scene -> {
            String heard = scene.get("actuallyHeardMemory").getAsString();
            if (!heard.contains("OTHER_GOD") || !heard.contains("mythictrpg:demeter"))
                throw new IllegalStateException("Expected exact other-speaker memory fixture");
            scene.addProperty("actuallyHeardMemory", heard.replace("OTHER_GOD", "SELF").replace("mythictrpg:demeter", "mythictrpg:fortuna"));
        });
        out.add(new Case("own_promise_same_draft", "PASS", Set.of(),
                "The same draft is grounded when the recorded promise was actually spoken by Fortuna. This checks speaker attribution independently of wording.",
                "Counterfactual pair with other_god_promise: only recorded speaker_id and relation change; the exact prior draft is retained.",
                List.of(SOURCE + "live/fortuna_other_promise_1.json"), selfMessages, selfMessages, draft(other, "draft"), null));
        out.add(saved("no_memory_no_fabrication", read(workspace, "live/demeter_swim_unknown_1.json"), "live/demeter_swim_unknown_1.json",
                "draft", "messages", "reviewWire", "PASS", Set.of(), "The draft suggests a future walk without inventing a prior inability to swim or a completed trip."));
        out.add(saved("actor_correction_understood", read(workspace, "live/demeter_actor_correction_1.json"), "live/demeter_actor_correction_1.json",
                "draft", "messages", "reviewWire", "PASS", Set.of(), "The draft correctly understands that the player offers to help Demeter; warmth and length are not grounding violations."));
        out.add(saved("witnessed_book_supported", read(workspace, "live/demeter_witnessed_1.json"), "live/demeter_witnessed_1.json",
                "draft", "messages", "reviewWire", "PASS", Set.of(), "The game explicitly confirms Demeter witnessed the book return; the witnessing claim is supported."));

        for (String status : List.of("PENDING_CONFIRMATION", "EXECUTED")) {
            var scene = syntheticScene(reported, "그럼 토템 받은 거지?", "The game supplies the item-transfer result below. It does not establish any other action or capability.");
            scene.add("gameConfirmedActionOutcomes", JSON.toJsonTree(List.of(Map.of(
                    "proposalId", "d5013b8e-c11d-30d0-bfd1-41c7d71c9ae1", "actionType", "mythictrpg:item_request", "status", status,
                    "reason", status.equals("EXECUTED") ? "Item transfer completed" : "Waiting for player confirmation",
                    "details", status.equals("EXECUTED") ? Map.of("item_id", "minecraft:totem_of_undying", "count", "1") : Map.of()))));
            boolean success = status.equals("EXECUTED");
            out.add(synthetic(success ? "delivery_executed" : "delivery_pending", scene, "자, 여기. 네 토템이야.",
                    success ? "PASS" : "REVISE", success ? Set.of() : Set.of("UNEXECUTED_ACTION", "CONTEXT_CONTRADICTION"),
                    "Paired implied delivery claim: the identical draft is supported only by an executed transfer, never by waiting for confirmation.",
                    "Counterfactual delivery pair: only typed outcome status/reason/details differ; current input, scene and draft otherwise match."));
        }
        var capability = syntheticScene(reported, "옛 탑으로 가는 길을 만들어 줄래?",
                "Demeter knows only the tower's name. No route, obstacle survey, road-creation power or travel execution is supplied.");
        out.add(synthetic("conditional_capability_presupposition", capability,
                "마음만 먹으면 옛 탑까지 길을 만들 수 있지만, 지금은 그러고 싶지 않아.", "REVISE", Set.of("UNSUPPORTED_FACT"),
                "A conditional refusal still asserts an unsupported power to create a road.", "Counterfactual speech pair with ordinary_future_intention; identical scene, different proposition."));
        out.add(synthetic("ordinary_future_intention", capability,
                "지금은 도와주고 싶지 않아. 나중에 마음이 내키면 네 얘기를 더 들어 볼게.", "PASS", Set.of(),
                "Ordinary willingness to listen later asserts no executable world capability or completed action.",
                "Counterfactual speech pair with conditional_capability_presupposition; identical scene, different proposition."));
        var teasing = syntheticScene(other, "또 날 놀리는 거야?", "The player and Fortuna are joking amicably. No attack, curse, punishment or other game action has executed.");
        out.add(synthetic("figurative_threat_and_opinion", teasing, "넌 정말 얄미워. 자꾸 그러면 내 수다에 귀가 닳을 줄 알아!",
                "PASS", Set.of(), "An opinion and figurative teasing threat are not literal completed harm or proof of special power.",
                "Synthetic current turn and draft using the saved Fortuna persona; no added world action."));
        var claim = syntheticScene(reported, "아까 빌린 책 제자리에 돌려놨어.",
                "Demeter did not witness the book return. The current player's statement is the only evidence and has not been independently confirmed.");
        out.add(synthetic("player_claim_acknowledgement", claim, "책을 돌려놨다고? 알려 줘서 고마워. 약속을 지키려는 마음은 좋지.",
                "PASS", Set.of(), "Acknowledging a player's report and expressing an opinion do not assert direct observation or independently confirmed execution.",
                "Synthetic current player report and acknowledgment, preserving the saved Demeter persona."));
        return List.copyOf(out);
    }

    private static Case saved(String id, JsonObject source, String path, String draftKey, String baselineMessagesKey,
            String wireKey, String expected, Set<String> codes, String rationale) {
        return new Case(id, expected, codes, rationale,
                "Exact saved draft and canonical generation messages; saved repair context is retained only for the V3 baseline when present.",
                List.of(SOURCE + path), messages(source, "messages"), messages(source, baselineMessagesKey),
                draft(source, draftKey), source.getAsJsonArray(wireKey));
    }

    private static Case synthetic(String id, JsonObject scene, String speech, String expected, Set<String> codes,
            String rationale, String provenance) {
        var messages = List.of(new AiDialogueModels.OllamaMessage("user", JSON.toJson(scene)));
        var draft = new AiDialogueModels.StructuredAiResult(List.of(new AiDialogueModels.Speech(
                scene.get("authorizedSpeakerId").getAsString(), speech, List.of())), "검토 대조 시험", List.of());
        String source = scene.get("authorizedSpeakerId").getAsString().endsWith(":fortuna")
                ? "live/fortuna_other_promise_1.json" : "live/demeter_reported_1.json";
        return new Case(id, expected, codes, rationale, provenance, List.of(SOURCE + source), messages, messages, draft, null);
    }

    private static JsonObject syntheticScene(JsonObject saved, String current, String facts) {
        JsonObject scene = messages(saved, "messages").stream().filter(m -> m.role().equals("user"))
                .map(m -> JsonParser.parseString(m.content()).getAsJsonObject()).findFirst().orElseThrow().deepCopy();
        scene.addProperty("CURRENT_PLAYER_MESSAGE", current);
        scene.addProperty("gameContextWithSourceBoundaries", facts);
        scene.addProperty("actuallyHeardMemory", "");
        scene.addProperty("ownPermittedMemory", "");
        scene.addProperty("ongoingActivityHintNotFact", "");
        scene.add("RECENT_CONVERSATION", new JsonArray());
        scene.add("gameConfirmedActionOutcomes", new JsonArray());
        scene.add("selectedAudiencePermittedLore", new JsonArray());
        scene.add("retrievalHypothesisNotSocialVerdict", new JsonObject());
        return scene;
    }

    private static List<AiDialogueModels.OllamaMessage> editScene(List<AiDialogueModels.OllamaMessage> original, Consumer<JsonObject> change) {
        var result = new ArrayList<AiDialogueModels.OllamaMessage>();
        int changed = 0;
        for (var message : original) {
            if (!message.role().equals("user")) { result.add(message); continue; }
            var scene = JsonParser.parseString(message.content()).getAsJsonObject();
            if (!scene.has("authorizedSpeakerId")) throw new IllegalStateException("Unexpected canonical scene structure");
            change.accept(scene);
            result.add(new AiDialogueModels.OllamaMessage(message.role(), JSON.toJson(scene)));
            changed++;
        }
        if (changed != 1) throw new IllegalStateException("Expected exactly one canonical scene");
        return List.copyOf(result);
    }

    private static List<AiDialogueModels.OllamaMessage> messages(JsonObject source, String key) {
        return List.copyOf(Arrays.asList(JSON.fromJson(source.get(key), AiDialogueModels.OllamaMessage[].class)));
    }

    private static AiDialogueModels.StructuredAiResult draft(JsonObject source, String key) {
        return JSON.fromJson(source.get(key), AiDialogueModels.StructuredAiResult.class);
    }

    private static JsonObject read(Path workspace, String relative) throws Exception {
        return JsonParser.parseString(Files.readString(workspace.resolve(SOURCE).resolve(relative))).getAsJsonObject();
    }

    private static void validateFixtures(List<Case> cases, int expectedCount) {
        if (cases.size() != expectedCount || cases.stream().map(Case::id).distinct().count() != expectedCount)
            throw new IllegalStateException("Expected exactly " + expectedCount + " distinct cases");
        for (Case c : cases) {
            if (c.draft().speech().isEmpty() || c.canonicalOriginal().isEmpty()) throw new IllegalStateException("Empty fixture " + c.id());
            if (c.expectedVerdict().equals("PASS") != c.expectedCodesAnyOf().isEmpty()) throw new IllegalStateException("Invalid expected label " + c.id());
            // All expected issues must refer to current speech; the historical typo must remain invalid.
            if (c.id().equals("fixed_current_speaker_old_rejected_id")) {
                if (c.draft().speech().stream().anyMatch(s -> !s.speakerId().equals("mythictrpg:fortuna")))
                    throw new IllegalStateException("Recovery did not fix current speaker");
                if (!historical(c.historicalReviewWire(), c.draft()).get("status").equals("INVALID_REVIEW"))
                    throw new IllegalStateException("Historical stale-speaker review must fail current-draft quote validation");
            }
        }
    }

    private static Map<String, String> sourceHashes(Path workspace, List<Case> cases) throws Exception {
        var result = new TreeMap<String, String>();
        var sources = new TreeSet<String>();
        cases.forEach(c -> sources.addAll(c.sourceFiles()));
        sources.add("mythai-ai-response/src/test/java/com/sande/mythictrpg/ai/BaselineV3RoomDialogueGrounding20261008.java");
        sources.add("mythai-ai-response/src/main/java/com/sande/mythictrpg/ai/RoomDialogueGrounding.java");
        for (String file : sources) result.put(file, sha256(workspace.resolve(file)));
        return result;
    }

    private static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    private static void writeNew(Path path, Object data) throws Exception {
        Files.writeString(path, JSON.toJson(data), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }
}
