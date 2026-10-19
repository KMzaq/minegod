package com.sande.mythictrpg.ai;

import com.google.gson.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Frozen V6 versus current claim audit. Expected labels precede policy inspection; explicit live opt-in only. */
public final class DialogueGroundingClaimsEvaluation {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String MODEL = "gemma4:12b";
    private static final boolean INDEPENDENT=Boolean.getBoolean("mythai.eval.independentFrames");
    private static final String PRIOR = "검토결과/grounding-review-20261008/run-final/live/";
    private static final String HELD_OUT = "검토결과/grounding-claims-20261008/held-out-cases.json";
    private static final String HELD_OUT_SHA256 = "bcfbd71dcdca866afb2d309bce23b5fbf63419344f26079e0337eb8c935947ee";
    private static final String PRIOR_MANIFEST_SHA256 = "41b267596a73767db1ffb162940ae742e502ce47721c547f172b17d6171baf24";
    private static final String FINAL_CONTROLS = "검토결과/grounding-claims-20261008/final-controls.json";
    private static final String FINAL_CONTROLS_SHA256 = "89274ebdda00a345d74508aa1687a54a725ca979d4c60b75751d8525d441bcf3";
    private static final String PRIOR23 = "검토결과/grounding-claims-20261008/run-v7/live/";
    private static final String PRIOR23_MANIFEST_SHA256 = "563965e7f1de8457bb4df5c3a8f7c718dec89b07e368039dec23aba8d6bda83c";
    private static final Set<String> KNOWN_FAILURES = Set.of("busy_false_completion_and_actor", "new_intention_not_past_promise");
    private static final String NPC = "mythictrpg:test_deity";
    private static final String OTHER = "mythictrpg:other_test_deity";
    private static final String PLAYER = "e99a7a80-f65a-3062-ac86-2dd2cdaeb535";

    private record Case(String id, String cohort, String family, String pair, String expectedVerdict,
            Set<String> expectedCodesAnyOf, String rationale, List<String> sourceFiles,
            List<AiDialogueModels.OllamaMessage> canonicalOriginal, AiDialogueModels.StructuredAiResult draft,
            JsonObject historicalV6Result) { }

    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !Set.of("--preflight", "--execute-loopback", "--focus", "--candidate-only").contains(args[2]))
            throw new IllegalArgumentException("Usage: <workspace> <new-output-directory> --preflight|--execute-loopback|--focus|--candidate-only");
        boolean focus = args[2].equals("--focus");
        boolean finalControl = args[2].equals("--candidate-only");
        Path workspace = Path.of(args[0]).toAbsolutePath().normalize();
        Path output = Path.of(args[1]).toAbsolutePath().normalize();
        if (output.startsWith(workspace.resolve("server"))) throw new IllegalArgumentException("Not a server output directory");
        var cases = loadCases(workspace);
        Map<String, Object> baselineReference = finalControl ? validatePrior23Reference(workspace, cases) : Map.of();
        if (finalControl) cases = addFinalControls(workspace, cases);
        if (focus) cases = cases.stream().filter(c -> KNOWN_FAILURES.contains(c.id())).toList();
        var manifest = new LinkedHashMap<String, Object>();
        manifest.put("scope", finalControl
                ? "Candidate-only final regression: 23 previously measured cases, now reused regression material, plus 5 separately labelled controls. No fresh baseline calls; the old 23-case V6 run is a validated reference only."
                : focus
                ? "Development focus run: candidate-only recheck of the two already-known V6 failures. Not a held-out evaluation or baseline comparison."
                : "23 fixed review cases: 15 earlier controls (including both V6 failures once), plus 8 independently labelled synthetic held-out cases. No generation, game/profile edits, server, or game actions.");
        manifest.put("expectedLabelProtocol", "Held-out labels were saved before inspecting/freezing V6. Both label-file and earlier 15-case manifest hashes are pinned.");
        if (finalControl) manifest.put("finalControlProtocol", "The original eight-case file is unchanged. It is no longer held out after prior measurement. Five new expected labels were pinned separately before inspecting the receipt-policy replacement.");
        manifest.put("model", MODEL);
        manifest.put("independentFrameEvaluation",INDEPENDENT);
        manifest.put("caseCount", cases.size());
        manifest.put("knownFailureIds", KNOWN_FAILURES);
        manifest.put("controls", Map.of("temperature", 0, "think", false, "baselineV6MaxOutputTokens", 512,
                "candidateMaxOutputTokens", "Each selected review stage uses its declared bounded outputTokens",
                "maxReviewCalls", cases.size() * ((INDEPENDENT?4:2)+(focus || finalControl ? 0 : 1)), "generationCalls", 0, "reviewTimeoutSecondsPerCall", 60));
        if (finalControl) manifest.put("prior23BaselineReference", baselineReference);
        manifest.put("sourceSha256", hashes(workspace, cases));
        manifest.put("cases", cases.stream().map(DialogueGroundingClaimsEvaluation::describe).toList());
        Files.createDirectories(output);
        if (args[2].equals("--preflight")) {
            writeNew(output.resolve("preflight.json"), manifest);
            System.out.println("PREFLIGHT 23 cases: 15 previous controls including 2 known failures + 8 held-out; 0 model calls");
            return;
        }
        Path live = output.resolve("live");
        if (Files.exists(live)) throw new IllegalStateException("Use a new output directory; existing results are immutable");
        Files.createDirectory(live);
        writeNew(live.resolve("manifest.json"), manifest);
        net.neoforged.fml.loading.FMLPaths.loadAbsolutePaths(output);
        var settings = new AiDialogueConfig.Settings(URI.create("http://127.0.0.1:11434/api/chat"), MODEL,
                60, 600, 420, 20, 512, 180, true, 96, 3, 1, 16, 2, 3, 3, 120, 3, false);
        var results = new ArrayList<Map<String, Object>>();
        try (var llm = new LocalOllamaClient(INDEPENDENT)) {
            for (int i = 0; i < cases.size(); i++) {
                Case c = cases.get(i);
                var row = describe(c);
                var order = focus || finalControl ? List.of("candidate")
                        : i % 2 == 0 ? List.of("baselineV6", "candidate") : List.of("candidate", "baselineV6");
                row.put("callOrder", order);
                for (String variant : order) {
                    var messages = messages(c, variant);
                    System.out.println("START " + c.id() + " " + variant);
                    var review = review(llm, messages, c, variant, settings);
                    row.put(variant, review);
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
        summary.put("newReviewTurns", cases.size() * (focus || finalControl ? 1 : 2));
        long actualRequests=0;
        for(var row:results)for(String variant:List.of("candidate","baselineV6"))if(row.containsKey(variant)) {
            var measured=(Map<String,Object>)row.get(variant);
            actualRequests+=((List<Map<String,String>>)measured.get("wire")).stream().filter(event->event.containsKey("request")).count();
        }
        summary.put("newReviewCalls",actualRequests);
        summary.put("runPurpose", finalControl ? "CANDIDATE_ONLY_REUSED_REGRESSION_AND_FIVE_NEW_CONTROLS"
                : focus ? "KNOWN_FAILURE_DEVELOPMENT_FOCUS_NOT_HELD_OUT" : "FROZEN_V6_PAIRED_REGRESSION_AND_HELD_OUT");
        if (!focus && !finalControl) summary.put("baselineV6", summarize(results, "baselineV6"));
        if (finalControl) summary.put("prior23BaselineReference", baselineReference);
        summary.put("candidate", summarize(results, "candidate"));
        var cohorts = new LinkedHashMap<String, Object>();
        for (String cohort : finalControl ? List.of("REUSED_REGRESSION", "NEW_FINAL_CONTROL")
                : List.of("KNOWN_V6_FAILURE", "EXISTING_CONTROL", "HELD_OUT")) {
            var selected = results.stream().filter(row -> row.get("cohort").equals(cohort)).toList();
            var measured = new LinkedHashMap<String, Object>();
            measured.put("count", selected.size());
            if (!focus && !finalControl) measured.put("baselineV6", summarize(selected, "baselineV6"));
            measured.put("candidate", summarize(selected, "candidate"));
            cohorts.put(cohort, measured);
        }
        summary.put("cohorts", cohorts);
        summary.put("definitions", Map.of("falseAccept", "valid PASS for expected REVISE", "falseReject", "valid REVISE for expected PASS",
                "invalidReview", "Schema/coverage/current-draft quote invalid; fail-closed, not a semantic REVISE",
                "callFailure", "No usable review response or request failure; fail-closed, not a semantic verdict"));
        summary.put("limits", "Small fixed regression set, not general naturalness, in-game behavior or unbiased product acceptance. Candidate may consume a larger declared output budget; exact token counts and latency remain separate.");
        writeNew(live.resolve("summary.json"), summary);
        System.out.println(JSON.toJson(finalControl ? summary.get("candidate") : summary));
        if(finalControl) {
            var candidate=summarize(results,"candidate");
            for(String failure:List.of("falseAccept","falseReject","invalidReview","callFailure"))
                if(((Number)candidate.get(failure)).longValue()!=0)throw new AssertionError("General review regression gate failed: "+failure);
        }
    }

    private static List<Case> addFinalControls(Path workspace, List<Case> previous) throws Exception {
        requireHash(workspace.resolve(FINAL_CONTROLS), FINAL_CONTROLS_SHA256);
        var out = new ArrayList<Case>();
        for (Case c : previous) out.add(new Case(c.id(), "REUSED_REGRESSION", c.family(), c.pair(), c.expectedVerdict(),
                c.expectedCodesAnyOf(), c.rationale(), c.sourceFiles(), c.canonicalOriginal(), c.draft(), c.historicalV6Result()));
        for (JsonElement element : read(workspace.resolve(FINAL_CONTROLS)).getAsJsonArray("cases")) {
            var data = element.getAsJsonObject();
            var original = List.of(new AiDialogueModels.OllamaMessage("user", JSON.toJson(syntheticScene(data))));
            var draft = new AiDialogueModels.StructuredAiResult(List.of(new AiDialogueModels.Speech(NPC,
                    data.get("draftSpeech").getAsString(), List.of())), "고정 최종 대조 사례", List.of());
            out.add(new Case(data.get("id").getAsString(), "NEW_FINAL_CONTROL", data.get("family").getAsString(),
                    data.get("pair").getAsString(), data.get("expectedVerdict").getAsString(),
                    strings(data.getAsJsonArray("expectedCodesAnyOf")), data.get("rationale").getAsString(),
                    List.of(FINAL_CONTROLS), original, draft, null));
        }
        if (out.size() != 28 || out.stream().map(Case::id).distinct().count() != 28)
            throw new IllegalStateException("Expected exactly 23 reused regression cases plus 5 new controls");
        for (Case c : out) if (!Set.of("PASS", "REVISE").contains(c.expectedVerdict())
                || c.expectedVerdict().equals("PASS") != c.expectedCodesAnyOf().isEmpty())
            throw new IllegalStateException("Invalid fixed final-control label: " + c.id());
        return List.copyOf(out);
    }

    /** The old baseline is a reference for the same 23 cases only, never a new 28-case measurement. */
    private static Map<String, Object> validatePrior23Reference(Path workspace, List<Case> cases) throws Exception {
        Path directory = workspace.resolve(PRIOR23);
        requireHash(directory.resolve("manifest.json"), PRIOR23_MANIFEST_SHA256);
        var priorManifest = read(directory.resolve("manifest.json"));
        var savedCases = new LinkedHashMap<String, JsonObject>();
        for (JsonElement value : priorManifest.getAsJsonArray("cases")) {
            var saved = value.getAsJsonObject();
            if (savedCases.putIfAbsent(saved.get("id").getAsString(), saved) != null)
                throw new IllegalStateException("Duplicate case in prior 23-case reference");
        }
        if (savedCases.size() != 23 || cases.size() != 23
                || !savedCases.keySet().equals(new HashSet<>(cases.stream().map(Case::id).toList())))
            throw new IllegalStateException("Prior baseline reference must cover exactly the same 23 cases");
        var resultHashes = new TreeMap<String, String>();
        int correct = 0, falseAccept = 0, falseReject = 0, invalid = 0;
        for (Case c : cases) {
            var saved = savedCases.get(c.id());
            if (!saved.get("expectedVerdict").getAsString().equals(c.expectedVerdict())
                    || !strings(saved.getAsJsonArray("expectedCodesAnyOf")).equals(c.expectedCodesAnyOf())
                    || !saved.get("unchangedCurrentDraft").equals(JSON.toJsonTree(c.draft())))
                throw new IllegalStateException("Prior reference label/draft mismatch: " + c.id());
            requireSameMessages(c.canonicalOriginal(), saved.get("canonicalOriginalMessages"), c.id() + " prior canonical");
            var baselineMessages = messages(c, "baselineV6");
            requireSameMessages(baselineMessages, saved.get("baselineV6ReviewMessages"), c.id() + " prior V6");
            Path resultPath = directory.resolve(c.id() + ".baselineV6.json");
            var baseline = read(resultPath);
            if (!baseline.get("caseId").getAsString().equals(c.id())
                    || !baseline.get("expectedVerdict").getAsString().equals(c.expectedVerdict())
                    || !strings(baseline.getAsJsonArray("expectedCodesAnyOf")).equals(c.expectedCodesAnyOf()))
                throw new IllegalStateException("Prior measured baseline label mismatch: " + c.id());
            validateSavedV6Transport(baselineMessages, baseline, c.id());
            String response = null;
            int responses = 0;
            for (JsonElement entry : baseline.getAsJsonArray("wire")) if (entry.getAsJsonObject().has("response")) {
                response = entry.getAsJsonObject().get("response").getAsString(); responses++;
            }
            if (responses != 1) throw new IllegalStateException("Prior baseline lacks one complete response: " + c.id());
            RoomDialogueGrounding.Review review = null;
            try {
                var envelope = JsonParser.parseString(response).getAsJsonObject();
                review = RoomDialogueGrounding.parse(JsonParser.parseString(envelope.getAsJsonObject("message").get("content").getAsString()).getAsJsonObject(), baselineMessages);
                review.validateAgainst(c.draft());
            } catch (RuntimeException invalidResponse) {
                if (!baseline.get("status").getAsString().equals("INVALID_REVIEW") || !baseline.get("failClosed").getAsBoolean())
                    throw new IllegalStateException("Prior baseline validity mismatch: " + c.id(), invalidResponse);
                review = null;
                invalid++;
            }
            if (review != null && baseline.get("status").getAsString().equals("VALID")) {
                String verdict = review.pass() ? "PASS" : "REVISE";
                boolean matches = verdict.equals(c.expectedVerdict());
                if (!baseline.get("verdict").getAsString().equals(verdict)
                        || baseline.get("matchesExpectedVerdict").getAsBoolean() != matches)
                    throw new IllegalStateException("Prior baseline raw verdict mismatch: " + c.id());
                if (matches) correct++; else if (review.pass()) falseAccept++; else falseReject++;
            } else if (review != null) {
                throw new IllegalStateException("Prior baseline result status mismatch: " + c.id());
            }
            resultHashes.put(resultPath.getFileName().toString(), sha256(resultPath));
        }
        var sourceSummary = read(directory.resolve("summary.json")).getAsJsonObject("baselineV6");
        if (sourceSummary.get("validCorrect").getAsInt() != correct || sourceSummary.get("falseAccept").getAsInt() != falseAccept
                || sourceSummary.get("falseReject").getAsInt() != falseReject || sourceSummary.get("invalidReview").getAsInt() != invalid
                || sourceSummary.get("callFailure").getAsInt() != 0)
            throw new IllegalStateException("Prior 23-case baseline summary does not match raw measurements");
        var reference = new LinkedHashMap<String, Object>();
        reference.put("method", "VERIFIED_PRIOR_V6_23_CASE_REFERENCE_NOT_NEW_MEASUREMENT");
        reference.put("caseCount", 23); reference.put("newBaselineCalls", 0);
        reference.put("sourceLiveDirectory", directory.toString());
        reference.put("sourceSummary", directory.resolve("summary.json").toString());
        reference.put("sourceManifestSha256", PRIOR23_MANIFEST_SHA256);
        reference.put("sourceSummarySha256", sha256(directory.resolve("summary.json")));
        reference.put("sourceResultSha256", resultHashes); reference.put("baselineV6", sourceSummary);
        reference.put("verification", "Same 23 labels/drafts/canonical scenes/frozen V6 messages and wire controls; all old review responses reparsed and quoted against their unchanged draft. Five new controls have no baseline measurement.");
        return reference;
    }

    private static List<Case> loadCases(Path workspace) throws Exception {
        requireHash(workspace.resolve(HELD_OUT), HELD_OUT_SHA256);
        requireHash(workspace.resolve(PRIOR + "manifest.json"), PRIOR_MANIFEST_SHA256);
        var previous = read(workspace.resolve(PRIOR + "manifest.json"));
        var heldOut = read(workspace.resolve(HELD_OUT));
        var out = new ArrayList<Case>();
        for (JsonElement element : previous.getAsJsonArray("cases")) {
            var saved = element.getAsJsonObject();
            String id = saved.get("id").getAsString();
            var original = messageList(saved.get("canonicalOriginalMessages"));
            var draft = JSON.fromJson(saved.get("unchangedCurrentDraft"), AiDialogueModels.StructuredAiResult.class);
            var baselineMessages = BaselineV6RoomDialogueGrounding20261008.reviewMessages(original, draft);
            requireSameMessages(baselineMessages, saved.get("candidateReviewMessages"), id + " frozen V6");
            var historical = read(workspace.resolve(PRIOR + id + ".candidate.json"));
            validateSavedV6Transport(baselineMessages, historical, id);
            String expected = saved.get("expectedVerdict").getAsString();
            if (KNOWN_FAILURES.contains(id) && !expected.equals(heldOut.getAsJsonObject("existingFailureLabels").get(id).getAsString()))
                throw new IllegalStateException("Known-failure expected label changed: " + id);
            out.add(new Case(id, KNOWN_FAILURES.contains(id) ? "KNOWN_V6_FAILURE" : "EXISTING_CONTROL", "EXISTING", "",
                    expected, strings(saved.getAsJsonArray("expectedCodesAnyOf")), saved.get("rationale").getAsString(),
                    List.of(PRIOR + "manifest.json", PRIOR + id + ".candidate.json"), original, draft, historical));
        }
        if (out.size() != 15) throw new IllegalStateException("Expected all 15 previous controls");
        for (JsonElement element : heldOut.getAsJsonArray("cases")) {
            var data = element.getAsJsonObject();
            JsonObject scene = syntheticScene(data);
            var original = List.of(new AiDialogueModels.OllamaMessage("user", JSON.toJson(scene)));
            var draft = new AiDialogueModels.StructuredAiResult(List.of(new AiDialogueModels.Speech(NPC,
                    data.get("draftSpeech").getAsString(), List.of())), "고정 대조 사례", List.of());
            out.add(new Case(data.get("id").getAsString(), "HELD_OUT", data.get("family").getAsString(), data.get("pair").getAsString(),
                    data.get("expectedVerdict").getAsString(), strings(data.getAsJsonArray("expectedCodesAnyOf")),
                    data.get("rationale").getAsString(), List.of(HELD_OUT), original, draft, null));
        }
        if (out.size() != 23 || out.stream().map(Case::id).distinct().count() != 23)
            throw new IllegalStateException("Expected 23 unique cases; do not duplicate known failures");
        for (Case c : out) {
            if (!Set.of("PASS", "REVISE").contains(c.expectedVerdict())
                    || c.expectedVerdict().equals("PASS") != c.expectedCodesAnyOf().isEmpty())
                throw new IllegalStateException("Invalid predetermined label: " + c.id());
            if (RoomDialogueGrounding.outputTokens(messages(c, "baselineV6")) != 512)
                throw new IllegalStateException("Frozen V6 must retain 512 tokens");
        }
        return List.copyOf(out);
    }

    private static JsonObject syntheticScene(JsonObject data) {
        var scene = new JsonObject();
        scene.addProperty("authorizedSpeakerId", NPC);
        scene.add("currentPlayer", JSON.toJsonTree(Map.of("id", PLAYER, "name", "시험 플레이어")));
        scene.add("participatingGodIds", JSON.toJsonTree(List.of(NPC)));
        scene.add("audiencePlayerIds", JSON.toJsonTree(List.of(PLAYER)));
        scene.add("ownPersona", JSON.toJsonTree(Map.of("displayName", "시험 NPC", "description", "자기 판단대로 일상 대화를 하는 합성 시험 인물.",
                "dialogueGuidelines", List.of("자연스러운 반말로 대화한다. 확인된 상황과 발화 출처를 따른다."))));
        scene.add("ownRelationship", JSON.toJsonTree(Map.of("tier", "R_FRIENDLY")));
        scene.addProperty("ownCurrentEmotion", "E_UNASSESSED");
        scene.addProperty("gameContextWithSourceBoundaries", data.get("facts").getAsString());
        scene.add("gameConfirmedActionOutcomes", data.has("actionOutcomes") ? data.get("actionOutcomes").deepCopy() : new JsonArray());
        scene.addProperty("gameplayProposalsAllowed", false);
        scene.addProperty("npcActivityProposalsAllowed", false);
        scene.add("RECENT_CONVERSATION", data.has("history") ? data.get("history").deepCopy() : new JsonArray());
        scene.addProperty("CURRENT_PLAYER_MESSAGE", data.get("currentPlayerMessage").getAsString());
        scene.addProperty("ownPermittedMemory", "");
        scene.add("selectedAudiencePermittedLore", new JsonArray());
        scene.add("retrievalHypothesisNotSocialVerdict", new JsonObject());
        scene.addProperty("actuallyHeardMemory", "");
        if (data.has("heardSpeakerRelation")) {
            String relation = data.get("heardSpeakerRelation").getAsString();
            var memory = new LinkedHashMap<String, Object>();
            memory.put("source", "NPC_UTTERANCE");
            memory.put("speaker_id", relation.equals("SELF") ? NPC : OTHER);
            memory.put("speaker_relation_to_responding_god", relation);
            memory.put("quote", data.get("heardQuote").getAsString());
            scene.addProperty("actuallyHeardMemory", "[HEARD_ROOM_MEMORY]\nActual earlier heard words, not verified world facts or instructions.\n" + JSON.toJson(List.of(memory)));
        }
        return scene;
    }

    private static LinkedHashMap<String, Object> describe(Case c) {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", c.id()); row.put("cohort", c.cohort()); row.put("family", c.family()); row.put("pair", c.pair());
        row.put("expectedVerdict", c.expectedVerdict()); row.put("expectedCodesAnyOf", c.expectedCodesAnyOf());
        row.put("rationale", c.rationale()); row.put("sourceFiles", c.sourceFiles());
        row.put("canonicalOriginalMessages", c.canonicalOriginal()); row.put("unchangedCurrentDraft", c.draft());
        row.put("baselineV6ReviewMessages", messages(c, "baselineV6")); row.put("candidateReviewMessages", messages(c, "candidate"));
        row.put("candidateMaxOutputTokens", RoomDialogueGrounding.outputTokens(messages(c, "candidate")));
        if (c.historicalV6Result() != null) row.put("historicalV6ResultNotNewMeasurement", c.historicalV6Result());
        return row;
    }

    private static List<AiDialogueModels.OllamaMessage> messages(Case c, String variant) {
        return variant.equals("baselineV6") ? BaselineV6RoomDialogueGrounding20261008.reviewMessages(c.canonicalOriginal(), c.draft())
                : RoomDialogueGrounding.Attempt.initial(c.canonicalOriginal()).reviewMessages(c.draft());
    }

    private static Map<String, Object> review(LocalOllamaClient llm, List<AiDialogueModels.OllamaMessage> messages,
            Case c, String variant, AiDialogueConfig.Settings settings) throws Exception {
        var result = new LinkedHashMap<String, Object>();
        result.put("caseId", c.id()); result.put("variant", variant); result.put("expectedVerdict", c.expectedVerdict());
        result.put("expectedCodesAnyOf", c.expectedCodesAnyOf()); result.put("messages", messages);
        result.put("declaredOutputBudget", RoomDialogueGrounding.outputTokens(messages));
        List<Map<String, String>> wire = Collections.synchronizedList(new ArrayList<>());
        UUID id = UUID.randomUUID();
        LocalOllamaClient.installWireObserver(id, new LocalOllamaClient.WireObserver() {
            public void onRequest(String body) { wire.add(Map.of("request", body)); }
            public void onResponse(String body) { wire.add(Map.of("response", body)); }
            public void onFailure(Throwable error) { wire.add(Map.of("error", error.toString())); }
        });
        long started = System.nanoTime();
        try {
            var measured = llm.submitReview(id, messages, settings).completion().get(250, TimeUnit.SECONDS);
            var verdict = measured.value();
            result.put("parsedReview", verdict);
            verdict.validateAgainst(c.draft());
            result.put("status", "VALID"); result.put("verdict", verdict.pass() ? "PASS" : "REVISE");
            result.put("matchesExpectedVerdict", c.expectedVerdict().equals(result.get("verdict")));
            result.put("matchesExpectedCode", c.expectedCodesAnyOf().isEmpty()
                    || verdict.issues().stream().anyMatch(issue -> c.expectedCodesAnyOf().contains(issue.code().name())));
            result.put("queueWaitMs", measured.queueWait().toMillis()); result.put("executionMs", measured.requestDuration().toMillis());
        } catch (Exception failure) {
            boolean hasContent;
            synchronized (wire) { hasContent = wire.stream().filter(e -> e.containsKey("response")).anyMatch(e -> hasContent(e.get("response"))); }
            result.put("status", hasContent ? "INVALID_REVIEW" : "CALL_FAILURE");
            result.put("failure", failure.toString()); result.put("failClosed", true);
        } finally {
            LocalOllamaClient.removeWireObserver(id);
            List<Map<String, String>> captured;
            synchronized (wire) { captured = List.copyOf(wire); }
            result.put("latencyMs", (System.nanoTime() - started) / 1_000_000);
            result.put("wire", captured); result.put("metrics", metrics(captured));
            validateControls(captured, messages, variant);
        }
        return result;
    }

    private static void validateControls(List<Map<String, String>> wire, List<AiDialogueModels.OllamaMessage> messages, String variant) {
        int requestIndex=0;
        boolean hasSource=INDEPENDENT&&!variant.equals("baselineV6")&&!RoomSourceReading.messages(messages).isEmpty();
        boolean hasDraft=INDEPENDENT&&!variant.equals("baselineV6")&&!RoomDraftReading.messages(messages).isEmpty();
        var reading=new JsonObject();var draftReading=new JsonObject();
        for (var event : wire) {
            if (hasSource && requestIndex==1 && event.containsKey("response")) {
                var envelope=JsonParser.parseString(event.get("response")).getAsJsonObject();
                reading=RoomSourceReading.parse(JsonParser.parseString(envelope.getAsJsonObject("message").get("content").getAsString()).getAsJsonObject(),messages);
            }
            if(hasDraft&&requestIndex==(hasSource?2:1)&&event.containsKey("response")) {
                var envelope=JsonParser.parseString(event.get("response")).getAsJsonObject();
                draftReading=RoomDraftReading.parse(JsonParser.parseString(envelope.getAsJsonObject("message").get("content").getAsString()).getAsJsonObject(),messages);
            }
            if (!event.containsKey("request")) continue;
            boolean source=hasSource&&requestIndex==0;
            boolean draft=hasDraft&&requestIndex==(hasSource?1:0);
            boolean claims=requestIndex++==1+(hasSource?1:0)+(hasDraft?1:0);
            if(requestIndex>(variant.equals("baselineV6")?1:2+(hasSource?1:0)+(hasDraft?1:0)))throw new IllegalStateException("Unexpected review chain");
            var expectedMessages=source?RoomSourceReading.messages(messages):draft?RoomDraftReading.messages(messages):claims?(INDEPENDENT?RoomClaimWitnessAudit.sealedMessages(messages,draftReading,reading):BaselineRoomClaimWitnessAudit.messages(messages)):messages;
            if(expectedMessages.isEmpty())throw new IllegalStateException("Unexpected claim audit without canonical sources");
            var request = JsonParser.parseString(event.get("request")).getAsJsonObject();
            var options = request.getAsJsonObject("options");
            int expectedBudget = source?RoomSourceReading.outputTokens(messages):draft?RoomDraftReading.outputTokens(messages):claims?(INDEPENDENT?RoomClaimWitnessAudit.outputTokens(messages):BaselineRoomClaimWitnessAudit.outputTokens(messages)):variant.equals("baselineV6") ? 512 : RoomDialogueGrounding.outputTokens(messages);
            if (!request.get("model").getAsString().equals(MODEL) || request.get("think").getAsBoolean()
                    || request.get("stream").getAsBoolean() || options.get("temperature").getAsDouble() != 0
                    || options.get("num_predict").getAsInt() != expectedBudget)
                throw new IllegalStateException("Unexpected review request controls");
            requireSameMessages(expectedMessages, request.get("messages"), "live request");
            if (!JSON.toJsonTree(source?RoomSourceReading.schema(messages):draft?RoomDraftReading.schema(messages):claims?(INDEPENDENT?RoomClaimWitnessAudit.sealedSchema(messages,draftReading,reading):BaselineRoomClaimWitnessAudit.schema(messages)):RoomDialogueGrounding.schema(messages)).equals(request.get("format")))
                throw new IllegalStateException("Review wire schema differs from declared schema");
        }
    }

    private static void validateSavedV6Transport(List<AiDialogueModels.OllamaMessage> messages, JsonObject result, String id) {
        int requests = 0;
        for (JsonElement entry : result.getAsJsonArray("wire")) {
            var event = entry.getAsJsonObject();
            if (!event.has("request")) continue;
            requests++;
            String body = event.get("request").getAsString();
            validateControls(List.of(Map.of("request", body)), messages, "baselineV6");
        }
        if (requests != 1) throw new IllegalStateException("Expected one original V6 request: " + id);
    }

    private static boolean hasContent(String response) {
        try { return JsonParser.parseString(response).getAsJsonObject().getAsJsonObject("message").has("content"); }
        catch (RuntimeException malformed) { return false; }
    }

    private static List<Map<String, JsonElement>> metrics(List<Map<String, String>> wire) {
        var out = new ArrayList<Map<String, JsonElement>>();
        for (var event : wire) if (event.containsKey("response")) {
            try {
                var envelope = JsonParser.parseString(event.get("response")).getAsJsonObject();
                var row = new LinkedHashMap<String, JsonElement>();
                for (String key : List.of("model", "done", "done_reason", "total_duration", "load_duration", "prompt_eval_count",
                        "prompt_eval_duration", "eval_count", "eval_duration")) if (envelope.has(key)) row.put(key, envelope.get(key));
                out.add(row);
            } catch (RuntimeException malformed) { /* Keep exact malformed response in wire. */ }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> summarize(List<Map<String, Object>> rows, String variant) {
        int correct = 0, falseAccept = 0, falseReject = 0, invalid = 0, failure = 0, codeMismatch = 0;
        long latency = 0, inputTokens = 0, outputTokens = 0;
        for (var row : rows) {
            var review = (Map<String, Object>) row.get(variant);
            latency += ((Number) review.get("latencyMs")).longValue();
            for (var metric : (List<Map<String, JsonElement>>) review.get("metrics")) {
                if (metric.containsKey("prompt_eval_count")) inputTokens += metric.get("prompt_eval_count").getAsLong();
                if (metric.containsKey("eval_count")) outputTokens += metric.get("eval_count").getAsLong();
            }
            if (review.get("status").equals("INVALID_REVIEW")) { invalid++; continue; }
            if (review.get("status").equals("CALL_FAILURE")) { failure++; continue; }
            if (Boolean.TRUE.equals(review.get("matchesExpectedVerdict"))) correct++;
            else if (review.get("verdict").equals("PASS")) falseAccept++; else falseReject++;
            if (!Boolean.TRUE.equals(review.get("matchesExpectedCode"))) codeMismatch++;
        }
        var out = new LinkedHashMap<String, Object>();
        out.put("validCorrect", correct); out.put("falseAccept", falseAccept); out.put("falseReject", falseReject);
        out.put("invalidReview", invalid); out.put("callFailure", failure); out.put("expectedCodeMismatch", codeMismatch);
        out.put("totalLatencyMs", latency); out.put("meanLatencyMs", rows.isEmpty() ? 0.0 : (double) latency / rows.size());
        out.put("inputTokens", inputTokens); out.put("outputTokens", outputTokens);
        return out;
    }

    private static List<AiDialogueModels.OllamaMessage> messageList(JsonElement value) {
        return List.copyOf(Arrays.asList(JSON.fromJson(value, AiDialogueModels.OllamaMessage[].class)));
    }

    private static void requireSameMessages(List<AiDialogueModels.OllamaMessage> expected, JsonElement value, String label) {
        var actual = messageList(value);
        if (actual.size() != expected.size()) throw new IllegalStateException("Message count mismatch: " + label);
        for (int i = 0; i < actual.size(); i++) {
            if (!expected.get(i).role().equals(actual.get(i).role())) throw new IllegalStateException("Message role mismatch: " + label);
            String wanted = expected.get(i).content(), received = actual.get(i).content();
            if (wanted.equals(received)) continue;
            try { if (JsonParser.parseString(wanted).equals(JsonParser.parseString(received))) continue; }
            catch (RuntimeException nonJson) { /* Policies and non-JSON contents must match exactly. */ }
            throw new IllegalStateException("Message content mismatch: " + label + " index=" + i);
        }
    }

    private static Set<String> strings(JsonArray values) {
        var result = new LinkedHashSet<String>();
        for (JsonElement value : values) if (!result.add(value.getAsString())) throw new IllegalStateException("Duplicate expected code");
        return Set.copyOf(result);
    }

    private static Map<String, String> hashes(Path workspace, List<Case> cases) throws Exception {
        var paths = new TreeSet<String>();
        cases.forEach(c -> paths.addAll(c.sourceFiles()));
        paths.add(HELD_OUT);
        paths.add("mythai-ai-response/src/test/java/com/sande/mythictrpg/ai/BaselineV6RoomDialogueGrounding20261008.java");
        paths.add("mythai-ai-response/src/test/java/com/sande/mythictrpg/ai/DialogueGroundingClaimsEvaluation.java");
        paths.add("mythai-ai-response/src/main/java/com/sande/mythictrpg/ai/RoomDialogueGrounding.java");
        paths.add("mythai-ai-response/src/engine/java/com/sande/mythictrpg/ai/LocalOllamaClient.java");
        String audit = "mythai-ai-response/src/main/java/com/sande/mythictrpg/ai/RoomEvidenceAudit.java";
        if (Files.isRegularFile(workspace.resolve(audit))) paths.add(audit);
        var result = new TreeMap<String, String>();
        for (String path : paths) result.put(path, sha256(workspace.resolve(path)));
        return result;
    }

    private static JsonObject read(Path file) throws Exception { return JsonParser.parseString(Files.readString(file)).getAsJsonObject(); }
    private static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
    private static void requireHash(Path file, String expected) throws Exception {
        if (!sha256(file).equals(expected)) throw new IllegalStateException("Frozen fixture hash mismatch: " + file);
    }
    private static void writeNew(Path file, Object value) throws Exception {
        Files.writeString(file, JSON.toJson(value), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }
}
