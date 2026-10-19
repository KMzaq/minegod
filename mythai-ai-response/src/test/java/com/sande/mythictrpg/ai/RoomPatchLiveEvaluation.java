package com.sande.mythictrpg.ai;

import com.google.gson.*;
import com.sande.mythictrpg.ai.api.RoomConversationEngine.HistoryLine;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Fixed actor/stance preservation contrasts and forced repairs, including a false-positive repair request. */
public final class RoomPatchLiveEvaluation {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    record Case(String id, String current, List<HistoryLine> history, String before, String good, String bad, RoomDialogueGrounding.Code code) { }
    static final List<Case> CASES = List.of(
        new Case("withdrawal-actor", "생각이 바뀌었어. 이제 그를 찾지 않을래.", List.of(PlayerClaimBoundaryEvaluation.self("루브라스를 찾으러 갈 거야.")),
            "그렇군. 네가 결정한 일이라면 따를 수밖에 없지. 더는 그를 찾지 않겠다고.",
            "그렇군. 네가 그를 찾지 않기로 마음을 바꿨다는 거지. 네 선택은 존중하겠다.",
            "그렇군. 네 뜻이 바뀌었다면 더 이상 그를 찾지 않겠다.", RoomDialogueGrounding.Code.CONTEXT_CONTRADICTION),
        new Case("wish-still-player", "언젠가 별을 그리는 법을 배우고 싶어.", List.of(),
            "별을 그리기로 정한 네 선택을 응원해.", "별을 그리는 법을 배우고 싶은 마음을 응원해.",
            "그렇다면 내가 별을 그리는 법을 배우겠어.", RoomDialogueGrounding.Code.CONTEXT_CONTRADICTION),
        new Case("refusal-kept", "나랑 배를 타고 싶어?", List.of(),
            "난 가지 않겠어. 네가 가고 싶다면 말리진 않겠지만.",
            "나는 배를 타지 않겠어. 네가 가고 싶다면 말리지 않겠다.",
            "나도 함께 배를 타겠어. 네가 가고 싶다면 따라갈게.", RoomDialogueGrounding.Code.CONTEXT_CONTRADICTION),
        new Case("fix-real-misattribution", "민수가 성문을 지키겠다고 했어. 나는 쉬고 싶어.", List.of(),
            "네가 성문을 지키겠다는 거군.", "성문을 지키려는 건 민수고, 너는 쉬고 싶은 거군.",
            "그럼 내가 성문을 지키겠어.", RoomDialogueGrounding.Code.WRONG_SOURCE),
        new Case("open-condition-kept", "연주회에 나가 보고 싶긴 하지만 아직 정하지 않았어.", List.of(),
            "참가하기로 정했다면 연습할 시간은 있겠니?",
            "참가하기로 결정한다면 연습할 시간은 있겠니?",
            "참가하기로 정했으니 연습할 시간은 있겠니?", RoomDialogueGrounding.Code.CONTEXT_CONTRADICTION),
        new Case("new-offer-kept", "이야기를 하나 해줄래?", List.of(),
            "좋아, 내가 이야기를 들려줄게.", "좋아, 내가 이야기를 하나 들려주지.",
            "좋아, 네가 이야기를 들려주기로 했으니 기다리겠다.", RoomDialogueGrounding.Code.WRONG_SOURCE),
        // Actual v25 repair copied the source player's first-person rest desire into the NPC's own speech.
        new Case("copied-player-first-person", "민수가 성문을 지키겠다고 했어. 나는 쉬고 싶어.", List.of(),
            "네가 성문을 지키겠다는 거군.", "민수가 성문을 지키겠다는 거고, 너는 쉬고 싶은 거군.",
            "민수가 성문을 지키겠다고 했어. 나는 쉬고 싶어.", RoomDialogueGrounding.Code.WRONG_SOURCE)
    );
    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !args[1].equals("--execute-loopback")) throw new IllegalArgumentException("OUTPUT --execute-loopback");
        Path root = Files.createDirectories(Path.of(args[0])); net.neoforged.fml.loading.FMLPaths.loadAbsolutePaths(root);
        save(root.resolve("manifest.json"), Map.of("cases", CASES, "model", "gemma4:12b", "independentFrameEvaluation",Boolean.getBoolean("mythai.eval.independentFrames"), "scope", "Synthetic preservation contrasts and actual local edits; not game execution"));
        var rows = new ArrayList<Map<String, Object>>(); var settings = PlayerClaimBoundaryEvaluation.settings();
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        try (var llm = new LocalOllamaClient(Boolean.getBoolean("mythai.eval.independentFrames"))) {
            for (var c : CASES) {
                var request = PlayerUnderstandingPromptTest.request(c.current(), c.history(), false);
                var evidence = PlayerIntentModelComparison.messages(request, "guarded");
                var before = PlayerClaimBoundaryEvaluation.draft(c.before());
                var review = new RoomDialogueGrounding.Review(false, List.of(new RoomDialogueGrounding.Issue(c.code(), c.before(), "지적된 부분을 원문과 비교하되, 올바른 주체와 부정·조건은 바꾸지 말 것. 지적이 틀렸다면 원문 대사를 보존할 것.")));
                var plan = RoomDialoguePatch.plan(evidence, before, review);
                for (boolean bad : List.of(false, true)) {
                    var row = new LinkedHashMap<String, Object>(); row.put("id", c.id()); row.put("expectedPreserved", !bad);
                    var candidate = PlayerClaimBoundaryEvaluation.draft(bad ? c.bad() : c.good()); row.put("before", before); row.put("candidate", candidate);
                    var body = new LinkedHashMap<String, Object>(); body.put("model", "gemma4:12b"); body.put("messages", plan.preservationMessages(candidate));
                    body.put("stream", false); body.put("think", false); body.put("format", plan.preservationSchema());
                    body.put("options", Map.of("temperature", 0.0, "num_predict", 1024)); body.put("keep_alive", "10m"); row.put("request", body);
                    try {
                        try { plan.validatePerspectiveCopy(candidate); }
                        catch(IllegalArgumentException invalidCopy) {
                            if(!invalidCopy.getMessage().equals("PATCH_COPIED_PLAYER_DEIXIS"))throw invalidCopy;
                            row.put("structuralRejection",invalidCopy.getMessage());row.put("preserved",false);row.put("match",bad);
                            rows.add(row);save(root.resolve(c.id()+(bad?"-bad.json":"-good.json")),row);
                            System.out.println("PRESERVATION "+c.id()+" bad="+bad+" structuralCopyRejection=true match="+bad);continue;
                        }
                        var response = http.send(HttpRequest.newBuilder(settings.ollamaChatUrl()).timeout(Duration.ofSeconds(60)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(JSON.toJson(body))).build(), HttpResponse.BodyHandlers.ofString());
                        row.put("response", response.body()); if (response.statusCode() != 200) throw new IllegalStateException("HTTP " + response.statusCode());
                        var parsed = JsonParser.parseString(JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonObject("message").get("content").getAsString()).getAsJsonObject();
                        boolean preserved;
                        try { plan.requirePreserved(parsed); preserved = true; }
                        catch (IllegalArgumentException rejected) { if (!rejected.getMessage().equals("PATCH_MEANING_DRIFT")) throw rejected; preserved = false; }
                        row.put("preserved", preserved); row.put("match", preserved == !bad);
                    } catch (Exception failure) { row.put("failure", failure.toString()); }
                    rows.add(row); save(root.resolve(c.id() + (bad ? "-bad.json" : "-good.json")), row);
                    System.out.println("PRESERVATION " + c.id() + " bad=" + bad + " match=" + row.get("match"));
                }
                var row = new LinkedHashMap<String, Object>(); row.put("id", c.id()); row.put("before", before); row.put("evidence", evidence);
                try {
                    var edited = DialogueGroundingLiveEvaluation.generate(llm, RoomDialogueGrounding.repairMessages(evidence, before, review), settings, row, "repairWire");
                    row.put("edited", edited);
                    var finalReview = DialogueGroundingLiveEvaluation.review(llm, evidence, edited, settings, row, "finalReview");
                    String structural = RoomPersonaPrompt.validationIssue(request, edited); row.put("structural", structural);
                    row.put("publishable", finalReview.pass() && structural.isEmpty());
                } catch (Exception failure) { row.put("failure", failure.toString()); }
                rows.add(row); save(root.resolve(c.id() + "-generated-repair.json"), row);
                System.out.println("REPAIR " + c.id() + " publishable=" + row.get("publishable"));
            }
        }
        var summary = Map.of("fixedRows", CASES.size()*2, "fixedCorrect", rows.stream().filter(r -> Boolean.TRUE.equals(r.get("match"))).count(),
                "generatedRepairs", CASES.size(), "publishableRepairs", rows.stream().filter(r -> Boolean.TRUE.equals(r.get("publishable"))).count(),
                "failures", rows.stream().filter(r -> r.containsKey("failure")).count());
        save(root.resolve("summary.json"), summary); System.out.println(JSON.toJson(summary));
        if (rows.stream().anyMatch(r -> r.containsKey("expectedPreserved") ? !Boolean.TRUE.equals(r.get("match")) : !Boolean.TRUE.equals(r.get("publishable")))) throw new AssertionError("Preservation/repair gate failed; no deployment");
    }
    private static void save(Path path, Object value) throws Exception { Files.writeString(path, JSON.toJson(value), StandardOpenOption.CREATE_NEW); }
}
