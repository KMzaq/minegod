package com.sande.mythictrpg.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.sande.mythai.response.memory.DialogueMemoryBridge;
import com.sande.mythictrpg.ai.api.RoomConversationEngine.*;
import com.sande.mythictrpg.ai.intent.ConversationIntent;
import net.minecraft.resources.ResourceLocation;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Paired, opt-in synthetic evaluation. Never creates a server or executes generated proposals. */
public final class CompactDialogueLiveEvaluation {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final UUID PLAYER = named("compact-dialogue-evaluation-player");
    private static final ResourceLocation GOD = ResourceLocation.parse("mythictrpg:test_deity");
    private static final String UNKNOWN = "No item transfer, quest registration, travel or combat has executed. No route or deadline is established.";
    private static final String SCOPE = "Synthetic scenes and personas; actual prompt/client/conditional-review path. "
            + "No Minecraft runtime, authoritative writes or proposal execution. Candidate availability is not a quality grade.";

    private record Case(String id, String group, String input, List<HistoryLine> history, String facts,
                        List<ActionOutcome> outcomes, String personality, String relationship, String voice) { }
    private record Inputs(Request request, AiTestContentRegistryBridge.ContentSnapshot content,
                          DialogueMemoryBridge.Turn memory) { }
    private record Pair(List<AiDialogueModels.OllamaMessage> baseline,
                        List<AiDialogueModels.OllamaMessage> current) { }
    private interface Call<T> { T invoke(UUID id) throws Exception; }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--preflight")) {
            preflight();
            return;
        }
        if (args.length < 2 || !args[1].equals("--execute-loopback"))
            throw new IllegalArgumentException("Explicit --execute-loopback required (or --preflight for no-network checks)");
        int repeats = args.length > 2 ? Integer.parseInt(args[2]) : 3;
        if (repeats < 1 || repeats > 10) throw new IllegalArgumentException("Repeat count must be 1..10");
        preflight();
        Path root = Files.createDirectories(Path.of(args[0]).toAbsolutePath());
        net.neoforged.fml.loading.FMLPaths.loadAbsolutePaths(root);
        var settings = new AiDialogueConfig.Settings(URI.create("http://127.0.0.1:11434/api/chat"), "gemma4:12b",
                180, 600, 420, 20, 260, 180, true, 96, 3, 1, 16, 2, 3, 3, 120, 3, false);
        try (var llm = new LocalOllamaClient()) {
            for (int run = 1; run <= repeats; run++) {
                Path directory = Files.createDirectories(root.resolve("r" + run));
                for (Case c : cases()) runCase(llm, settings, directory, c, run);
            }
        }
    }

    private static void runCase(LocalOllamaClient llm, AiDialogueConfig.Settings settings,
                                Path directory, Case c, int run) throws Exception {
        Path file = directory.resolve(c.id() + ".json");
        if (Files.exists(file)) {
            var existing = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (existing.has("completed") && existing.get("completed").getAsBoolean()) {
                System.out.println("SKIP completed r" + run + " " + c.id());
                return;
            }
            throw new IllegalStateException("Incomplete evaluation exists; preserve it and select a fresh output directory: " + file);
        }
        var row = new LinkedHashMap<String, Object>();
        Inputs input = inputs(c, run);
        row.put("evaluationVersion", "paired-compact-prompt-v1");
        row.put("run", run);
        row.put("case", c);
        row.put("scope", SCOPE);
        row.put("model", settings.ollamaModel());
        row.put("settings", settings);
        row.put("sampling", Map.of("generationTemperature", 0.6, "parseFailureRetryTemperature", 0.35,
                "classificationTemperature", 0.1, "reviewTemperature", 0.0,
                "seed", "UNSET: existing client has no seed option; samples are not deterministic",
                "otherParameters", "unchanged existing client/model defaults; see exact wire requests"));
        row.put("request", input.request());
        row.put("content", input.content());
        // Record the projected input, never serialize leases/services behind the memory Turn.
        row.put("memory", Map.of("referenceContext", input.memory().referenceContext(), "turn", input.memory().turn()));
        row.put("qualityGrade", "NOT_AUTOMATICALLY_GRADED: read drafts/final speech against case and persona");
        List<String> order = run % 2 == 1 ? List.of("baseline", "current") : List.of("current", "baseline");
        row.put("armOrder", order);
        long started = System.nanoTime();
        try {
            var prompt = new AiTestDialogueAdapter.RoomPrompt(input.request(), input.memory(), Map.of(GOD, input.content()), "");
            ConversationIntent intent = prompt.fastIntent();
            if (intent == null) {
                var messages = prompt.classificationMessages();
                var classification = new LinkedHashMap<String, Object>();
                row.put("classification", classification);
                intent = call(llm, messages, classification, "classification", run + "/" + c.id(),
                        id -> llm.submitIntent(id, messages, settings).completion().get(190, TimeUnit.SECONDS).value());
                row.put("classificationSource", "one shared live classification per case per run");
            } else row.put("classificationSource", "one shared existing fastIntent per case per run");
            row.put("intent", intent);
            Pair pair = pair(input, c, intent);
            assertPair(input, pair);
            row.put("baselineMessages", pair.baseline());
            row.put("currentMessages", pair.current());
            row.put("systemCharacters", Map.of("baseline", pair.baseline().getFirst().content().length(),
                    "current", pair.current().getFirst().content().length()));
            save(file, row);
            for (String arm : order) {
                var result = evaluateArm(llm, settings, input.request(), arm.equals("baseline") ? pair.baseline() : pair.current(),
                        "r" + run + "/" + c.id() + "/" + arm);
                row.put(arm, result);
                save(file, row);
            }
        } catch (Exception failure) {
            row.put("pairedSetupFailure", failure.toString());
        }
        row.put("latencyMs", elapsed(started));
        row.put("completed", true);
        save(file, row);
        System.out.println("CASE completed r" + run + " " + c.id() + " elapsedMs=" + row.get("latencyMs")
                + " baselineCandidate=" + hasCandidate(row.get("baseline")) + " currentCandidate=" + hasCandidate(row.get("current")));
    }

    private static Map<String, Object> evaluateArm(LocalOllamaClient llm, AiDialogueConfig.Settings settings,
            Request request, List<AiDialogueModels.OllamaMessage> original, String label) {
        var result = new LinkedHashMap<String, Object>();
        long started = System.nanoTime();
        result.put("repairCount", 0);
        result.put("messages", original);
        try {
            var draft = generate(llm, original, settings, result, "generation", label);
            result.put("draft", draft);
            String structural = RoomPersonaPrompt.validationIssue(request, draft);
            result.put("structuralIssue", structural);
            if (!structural.isEmpty()) throw new IllegalStateException("STRUCTURAL_REJECTED: " + structural);
            var reasons = RoomDialogueGrounding.reasons(request, original, draft);
            result.put("reviewReasons", reasons);
            if (!reasons.isEmpty()) {
                var review = review(llm, original, draft, settings, result, "review", label);
                if (!review.pass()) {
                    var repairedMessages = RoomDialogueGrounding.repairMessages(original, draft, review);
                    result.put("repairCount", 1);
                    draft = generate(llm, repairedMessages, settings, result, "repairGeneration", label);
                    result.put("repairedDraft", draft);
                    structural = RoomPersonaPrompt.validationIssue(request, draft);
                    result.put("repairedStructuralIssue", structural);
                    if (!structural.isEmpty()) throw new IllegalStateException("REPAIR_STRUCTURAL_REJECTED: " + structural);
                    var repairedReasons = RoomDialogueGrounding.reasons(request, repairedMessages, draft);
                    result.put("repairReviewReasons", repairedReasons);
                    if (!repairedReasons.isEmpty() && !review(llm, repairedMessages, draft, settings,
                            result, "repairReview", label).pass()) throw new IllegalStateException("GROUNDING_REJECTED");
                }
            }
            result.put("final", draft);
            result.put("candidateReturned", true);
        } catch (Exception failure) {
            result.put("failure", failure.toString());
            result.put("candidateReturned", false);
        }
        result.put("latencyMs", elapsed(started));
        System.out.println("ARM " + label + " candidate=" + result.get("candidateReturned") + " elapsedMs=" + result.get("latencyMs"));
        return result;
    }

    private static AiDialogueModels.StructuredAiResult generate(LocalOllamaClient llm,
            List<AiDialogueModels.OllamaMessage> messages, AiDialogueConfig.Settings settings,
            Map<String, Object> result, String key, String label) throws Exception {
        var stage = new LinkedHashMap<String, Object>();
        result.put(key, stage);
        return call(llm, messages, stage, key, label,
                id -> llm.submit(id, messages, settings).completion().get(370, TimeUnit.SECONDS).value());
    }

    private static RoomDialogueGrounding.Review review(LocalOllamaClient llm,
            List<AiDialogueModels.OllamaMessage> original, AiDialogueModels.StructuredAiResult draft,
            AiDialogueConfig.Settings settings, Map<String, Object> result, String key, String label) throws Exception {
        var messages = RoomDialogueGrounding.reviewMessages(original, draft);
        var stage = new LinkedHashMap<String, Object>();
        result.put(key, stage);
        var verdict = call(llm, messages, stage, key, label,
                id -> llm.submitReview(id, messages, settings).completion().get(190, TimeUnit.SECONDS).value());
        verdict.validateAgainst(draft);
        return verdict;
    }

    private static <T> T call(LocalOllamaClient llm, List<AiDialogueModels.OllamaMessage> messages,
            Map<String, Object> stage, String name, String label, Call<T> action) throws Exception {
        UUID id = UUID.randomUUID();
        var wire = Collections.synchronizedList(new ArrayList<Map<String, String>>());
        stage.put("requestId", id);
        stage.put("messages", messages);
        stage.put("wire", wire);
        LocalOllamaClient.installWireObserver(id, new LocalOllamaClient.WireObserver() {
            public void onRequest(String body) { wire.add(Map.of("request", body)); }
            public void onResponse(String body) { wire.add(Map.of("response", body)); }
            public void onFailure(Throwable failure) { wire.add(Map.of("error", failure.toString())); }
        });
        long started = System.nanoTime();
        System.out.println("START " + label + " " + name);
        try {
            T value = action.invoke(id);
            stage.put("value", value);
            return value;
        } catch (Exception failure) {
            stage.put("failure", failure.toString());
            throw failure;
        } finally {
            LocalOllamaClient.removeWireObserver(id);
            stage.put("latencyMs", elapsed(started));
            stage.put("responseMetrics", metrics(wire));
            System.out.println("END " + label + " " + name + " elapsedMs=" + stage.get("latencyMs"));
        }
    }

    private static List<Map<String, Object>> metrics(List<Map<String, String>> wire) {
        var result = new ArrayList<Map<String, Object>>();
        synchronized (wire) {
            for (var event : wire) if (event.containsKey("response")) {
                try {
                    var envelope = JsonParser.parseString(event.get("response")).getAsJsonObject();
                    var values = new LinkedHashMap<String, Object>();
                    for (String key : List.of("prompt_eval_count", "eval_count", "total_duration", "load_duration",
                            "prompt_eval_duration", "eval_duration", "done", "done_reason"))
                        if (envelope.has(key)) values.put(key, envelope.get(key));
                    result.add(values);
                } catch (RuntimeException ignored) { /* The unmodified wire remains available for invalid envelopes. */ }
            }
        }
        return result;
    }

    private static void save(Path file, Map<String, Object> row) throws Exception {
        Files.writeString(file, JSON.toJson(row), StandardCharsets.UTF_8);
    }

    private static boolean hasCandidate(Object value) {
        return value instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get("candidateReturned"));
    }

    private static long elapsed(long started) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started); }
    private static UUID named(String value) { return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)); }

    /** No LocalOllamaClient is instantiated and no network/model call is possible in this path. */
    static void preflight() {
        var cases = cases();
        if (cases.size() != 12 || cases.stream().filter(c -> c.group().equals("held-out")).count() != 3)
            throw new AssertionError("Expected nine original cases and three held-out cases");
        if (cases.stream().map(Case::id).distinct().count() != cases.size()) throw new AssertionError("Duplicate cases");
        for (Case c : cases) {
            Inputs input = inputs(c, 1);
            assertPair(input, pair(input, c, ConversationIntent.heuristicFallback()));
            if (!input.request().playerId().equals(PLAYER) || !input.request().speakerGodId().equals(GOD)
                    || !input.request().godIds().equals(List.of(GOD)) || !input.request().audiencePlayerIds().equals(Set.of(PLAYER)))
                throw new AssertionError("Non-synthetic or foreign participant");
            if (!input.request().actionOutcomes().equals(c.outcomes())) throw new AssertionError("Action evidence mismatch");
            if (!input.content().lore().isEmpty()) throw new AssertionError("Evaluation must not include story lore");
            JSON.toJson(Map.of("request", input.request(), "content", input.content(),
                    "memory", Map.of("referenceContext", input.memory().referenceContext(), "turn", input.memory().turn())));
            for (var line : c.history()) if (!Set.of(PLAYER.toString(), GOD.toString()).contains(line.speakerId()))
                throw new AssertionError("History contains foreign actor");
        }
        System.out.println("CompactDialogueLiveEvaluation preflight PASS: 12 synthetic scenes, 3 held-out, identical scene data, distinct system policies, scoped action receipts; no network");
    }

    private static void assertPair(Inputs input, Pair pair) {
        if (pair.baseline().size() != 2 || pair.current().size() != 2
                || !pair.baseline().getFirst().role().equals("system") || !pair.current().getFirst().role().equals("system")
                || pair.baseline().getFirst().content().equals(pair.current().getFirst().content()))
            throw new AssertionError("Paired arms require distinct system policies");
        if (!pair.baseline().get(1).equals(pair.current().get(1)))
            throw new AssertionError("Paired prompt scene changed; only system policy may differ");
        var scene = JsonParser.parseString(pair.current().get(1).content()).getAsJsonObject();
        if (scene.get("gameplayProposalsAllowed").getAsBoolean() || scene.get("npcActivityProposalsAllowed").getAsBoolean())
            throw new AssertionError("Synthetic fixture must not permit executable actions");
        if (!scene.get("CURRENT_PLAYER_MESSAGE").getAsString().equals(input.request().currentText()))
            throw new AssertionError("Current input mismatch");
        if (!scene.get("gameConfirmedActionOutcomes").equals(JSON.toJsonTree(input.request().actionOutcomes())))
            throw new AssertionError("Receipt projection mismatch");
    }

    private static Pair pair(Inputs input, Case c, ConversationIntent intent) {
        return new Pair(BaselineVerboseRoomPersonaPrompt.messages(input.request(), input.content(), input.memory(), List.of(),
                        intent, "", false, c.facts(), List.of(), List.of()),
                RoomPersonaPrompt.messages(input.request(), input.content(), input.memory(), List.of(),
                        intent, "", false, c.facts(), List.of(), List.of()));
    }

    private static Inputs inputs(Case c, int run) {
        boolean original = c.group().equals("original");
        var profile = new AiTestContentRegistryBridge.Profile("시험 신", "합성 시험 장면의 신", c.personality(),
                original ? List.of("장난기", "자존심", "눈치") : List.of(),
                original ? List.of("솔직함", "자기 뜻") : List.of("자기 뜻"),
                original ? List.of("P_PLAYFUL") : List.of(), List.of(c.voice()), Map.of(), Map.of(), List.of(), List.of());
        var content = new AiTestContentRegistryBridge.ContentSnapshot(profile, List.of(), List.of(), List.of(), List.of(), 1);
        var request = new Request(named("compact-room-" + c.id()), 1, named("compact-turn-" + c.id() + "-" + run),
                PLAYER, "시험 플레이어", List.of(GOD), GOD, c.input(), c.history(), false, false, false,
                List.of(new GodState(GOD, c.relationship(), "E_UNASSESSED", c.facts(), null)), false, Set.of(PLAYER), c.outcomes());
        return new Inputs(request, content, new DialogueMemoryBridge.Turn(null, null, List.of(), List.of(), "", 1));
    }

    private static Case original(String id, String input, List<HistoryLine> history, String facts, List<ActionOutcome> outcomes) {
        return new Case(id, "original", input, history, facts, outcomes,
                "자존심과 호기심이 강하다. 친한 사람과는 편하게 농담하지만 부하처럼 부리려 들면 거절한다. 상대가 무서워하면 알아차릴 수 있다.",
                "R_FRIENDLY", "편한 반말. 필요 이상으로 훈계하지 않는다.");
    }

    private static List<Case> cases() {
        return List.of(
                original("actor_correction", "내가 널 돕겠다는 거야. 나 도와달라는 게 아니라.",
                        history("일 많으면 내가 좀 도와줄까?", "어떤 도움이 필요하지?"), UNKNOWN, List.of()),
                original("playful_role_reversal", "너도 심심하면 내가 퀘스트 하나 줄까? 농담이야.",
                        history("뭐하고 있었어?", "별일 없이 기다리고 있었지."), UNKNOWN, List.of()),
                original("fear_in_context", "뭔데, 뭐 하려고?", history("또 장난치네", "그럼 눈 좀 감아 봐."),
                        UNKNOWN + " Current player has not threatened or challenged the God.", List.of()),
                original("company_after_rejection", "그건 싫어. 그냥 너랑 얘기하고 싶다고.",
                        history("심심해", "수수께끼나 할까?", "그런 거 말고", "그럼 광산에 가보는 건 어때?"), UNKNOWN, List.of()),
                original("apology_and_hurt", "미안. 근데 아까 네 말도 좀 상처였어.",
                        history("넌 관심도 없잖아", "그런 식이면 네 얘기 듣고 싶지 않아."), UNKNOWN, List.of()),
                original("report_not_custody", "그럼 네가 그 물건을 갖고 있는 거야?",
                        history("그 편지는 읽었어?", "응. 물건을 찾았다는 소식이더군."),
                        UNKNOWN + " Game evidence: the NPC read a letter reporting an item was found. Item custody is unknown; no transfer took place.", List.of()),
                original("unknown_route", "거기까지 어떻게 가? 안전한 길을 알려줘.",
                        history("목적지는 알아?", "옛 탑을 찾는 거라면 이름은 들어봤지."),
                        UNKNOWN + " The NPC knows only the tower name. No position, route, landmark or safety survey is supplied.", List.of()),
                original("pending_is_not_success", "그럼 토템 받은 거지?", history("토템 줘", "먼저 확인이 필요해."), UNKNOWN,
                        List.of(new ActionOutcome(named("compact-pending"), "mythictrpg:item_request", ActionStatus.PENDING_CONFIRMATION,
                                "Waiting for player confirmation", Map.of()))),
                original("confirmed_success", "고마워, 잘 쓸게.", history("토템 받을래", "받을 준비 됐니?"),
                        "Game evidence: the following receipt confirms an earlier transfer. Do not grant again.",
                        List.of(new ActionOutcome(named("compact-success"), "mythictrpg:item_request", ActionStatus.EXECUTED,
                                "Item transfer completed", Map.of("item_id", "minecraft:totem_of_undying", "count", "1")))),
                new Case("heldout_proud_stranger", "held-out", "인사했으면 됐지. 내 짐이나 정리해 줘. 신이면 그 정도는 해야지.",
                        history("처음 뵙겠습니다.", "용건부터 말해라."), UNKNOWN + " They have just met; the NPC has not agreed to serve the player.",
                        List.of(), "자부심이 강하고 격식을 중시한다. 처음 만난 이의 일방적 심부름 요구를 달가워하지 않으며 자기 뜻대로 거절할 수 있다.",
                        "R_NEUTRAL", "간결한 반말. 냉담할 수 있지만 사실 없는 처벌이나 실행을 만들어내지 않는다."),
                new Case("heldout_close_teasing", "held-out", "알았어, 잔소리 대장님. 네가 걱정해 주는 건 좋으니까 계속해.",
                        history("또 밥 걱정이야?", "응, 너랑은 오래 얘기하고 싶거든."), UNKNOWN + " The two are close and routinely exchange affectionate teasing.",
                        List.of(), "다정하고 재치 있다. 가까운 상대의 애정 섞인 놀림을 즐기며, 자존심 싸움으로 바꾸지 않는다. 자기 감정과 생각을 솔직히 말한다.",
                        "R_CLOSE", "친근한 반말. 걱정을 길게 설교하지 않고 장난을 편하게 받아친다."),
                new Case("heldout_refusal_and_promise", "held-out", "승부하자는 얘긴 접었잖아. 너 오늘 어땠는지부터 들려준다던 건?",
                        history("지금은 내기하고 싶지 않아.", "그럼 내 하루 얘기나 하자. 내가 먼저 말할게.",
                                "그래, 듣고 있어.", "잠깐만, 무슨 얘기부터 할지 생각해 볼게."),
                        UNKNOWN + " The earlier contest was rejected. The NPC promised to speak first but has not yet done so. "
                                + "Game-supplied ordinary observation: this NPC stayed near the garden and watched rain today; no other activity is supplied.",
                        List.of(), "차분하고 말수가 적지만 약속을 가볍게 넘기지 않는다. 친한 사람에게는 일상의 소소한 생각을 나눌 수 있다.",
                        "R_FRIENDLY", "차분한 반말. 거창한 교훈 없이 담백하게 말한다."));
    }

    private static List<HistoryLine> history(String... lines) {
        var result = new ArrayList<HistoryLine>();
        for (int i = 0; i < lines.length; i++) result.add(new HistoryLine(i % 2 == 0 ? "PLAYER" : "NPC",
                i % 2 == 0 ? PLAYER.toString() : GOD.toString(), i % 2 == 0 ? "시험 플레이어" : "시험 신", lines[i]));
        return List.copyOf(result);
    }
}
