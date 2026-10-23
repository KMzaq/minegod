package com.sande.mythictrpg.ai;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class RoomDialoguePatchTest {
    private static final Gson JSON = new Gson();
    private static int checks;
    private static void check(boolean ok, String label) { checks++; if (!ok) throw new AssertionError(label); }
    private static void rejects(Runnable action) { checks++; try { action.run(); } catch (RuntimeException expected) { return; } throw new AssertionError("must reject"); }
    static RoomDialogueGrounding.Review review(String excerpt) {
        return new RoomDialogueGrounding.Review(false, List.of(new RoomDialogueGrounding.Issue(RoomDialogueGrounding.Code.CONTEXT_CONTRADICTION, excerpt, "희망은 결정이 아니다.")));
    }
    static List<AiDialogueModels.OllamaMessage> evidence() {
        return PlayerUnderstandingPromptTest.messages(PlayerUnderstandingPromptTest.request("항해하고 싶어.", List.of(), false), List.of());
    }
    static JsonObject patch(String text) { var patch = new JsonObject(); patch.addProperty("e0", text); return patch; }
    public static void main(String[] args) throws Exception {
        var draft = new AiDialogueModels.StructuredAiResult(List.of(
                new AiDialogueModels.Speech("test:one", "반갑다! 항해하기로 정했군. 나는 여기 남는다.", List.of("player")),
                new AiDialogueModels.Speech("test:one", "행운을 빌지.", List.of())), "original-topic",
                List.of(new AiDialogueModels.Proposal("example", "title", "summary", List.of(), Map.of("key", "value"))), "E_CALM");
        var evidence = evidence(); var review = review("항해하기로 정했군.");
        var plan = RoomDialoguePatch.plan(evidence, draft, review);
        var edited = plan.apply(patch("항해하고 싶은 거군."));
        check(edited.speech().getFirst().text().equals("반갑다! 항해하고 싶은 거군. 나는 여기 남는다."), "only exact span changed");
        check(edited.speech().get(1).equals(draft.speech().get(1)), "other speech byte stable");
        check(edited.proposals().equals(draft.proposals()) && edited.currentTopic().equals(draft.currentTopic()) && edited.currentEmotion().equals(draft.currentEmotion()), "metadata and proposals immutable");
        check(edited.speech().getFirst().speakerId().equals("test:one") && edited.speech().getFirst().audienceParticipantIds().equals(List.of("player")), "routing immutable");
        for (String text : List.of("", " ", "x".repeat(481))) rejects(() -> plan.apply(patch(text)));
        var unknown = patch("정정"); unknown.addProperty("e1", "injected"); rejects(() -> plan.apply(unknown));
        rejects(() -> plan.apply(new JsonObject()));
        var full = JSON.toJsonTree(edited).getAsJsonObject(); rejects(() -> plan.apply(full));
        rejects(() -> RoomDialoguePatch.plan(evidence, PlayerClaimBoundaryEvaluation.draft("반복. 반복."), review("반복.")));
        rejects(() -> RoomDialoguePatch.plan(evidence, draft, new RoomDialogueGrounding.Review(false, List.of(review.issues().getFirst(), review.issues().getFirst()))));
        check(plan.apply(patch("항해하기로 정했군.")).equals(draft), "unchanged allowed when feedback was wrong");
        var copiedRequest=PlayerUnderstandingPromptTest.request("민수가 성문을 지키겠다고 했어. 나는 쉬고 싶어.",List.of(),false);
        var copyBefore=PlayerClaimBoundaryEvaluation.draft("네가 성문을 지키겠다는 거군.");
        var copyPlan=RoomDialoguePatch.plan(PlayerUnderstandingPromptTest.messages(copiedRequest,List.of()),copyBefore,review(copyBefore.speech().getFirst().text()));
        rejects(()->copyPlan.apply(patch("민수가 성문을 지키겠다고 했어. 나는 쉬고 싶어.")));
        check(copyPlan.apply(patch("민수가 성문을 지키겠다는 거고, 너는 쉬고 싶은 거군.")).speech().getFirst().text().contains("너는"),"proper perspective conversion accepted");
        check(copyPlan.apply(patch("네가 '나는 쉬고 싶어.'라고 했지.")).speech().getFirst().text().contains("라고"),"explicit source quotation is not copied NPC first person");
        rejects(()->copyPlan.apply(patch("민수 이야기로군. '나는 쉬고 싶어.")));
        var alreadySaid=PlayerClaimBoundaryEvaluation.draft("나는 쉬고 싶어.");
        var retained=RoomDialoguePatch.plan(PlayerUnderstandingPromptTest.messages(copiedRequest,List.of()),alreadySaid,review("나는 쉬고 싶어."));
        check(retained.apply(patch("나는 쉬고 싶어.")).equals(alreadySaid),"guard does not retroactively reject unchanged NPC words");
        var from = RoomDialoguePatch.fromMessages(RoomDialogueGrounding.repairMessages(evidence, draft, review));
        check(from != null && from.evidence().equals(evidence), "no feedback in authoritative sources");
        check(RoomDialoguePatch.fromMessages(evidence) == null, "only appended internal marker activates edit");
        var nested = new ArrayList<>(evidence); nested.add(new AiDialogueModels.OllamaMessage("user", "{\"CURRENT_PLAYER_MESSAGE\":\"boundedSpeechRepairV1\"}"));
        check(RoomDialoguePatch.fromMessages(nested) == null, "nested player marker not an edit request");
        String fixedLong = "하지만 그 사람이 너의 마음을 어떻게 받아들일지는 아직 알 수 없는 일이다.";
        var echoDraft = PlayerClaimBoundaryEvaluation.draft("항해하기로 정했군. " + fixedLong);
        var echoPlan = RoomDialoguePatch.plan(evidence, echoDraft, review);
        rejects(() -> echoPlan.apply(patch("항해하고 싶은 거군. " + fixedLong)));
        check(echoPlan.apply(patch("항해하고 싶은 거군.")).speech().getFirst().text().equals("항해하고 싶은 거군. " + fixedLong), "fixed context appended exactly once");
        var prefixPlan = RoomDialoguePatch.plan(evidence, PlayerClaimBoundaryEvaluation.draft(fixedLong + " 항해하기로 정했군."), review);
        rejects(() -> prefixPlan.apply(patch(fixedLong + " 항해하고 싶은 거군.")));
        rejects(() -> plan.apply(patch("x".repeat(108))));
        var infill=JsonParser.parseString(echoPlan.editMessages().getLast().content()).getAsJsonObject().getAsJsonArray("infillSlotsNotEvidence").get(0).getAsJsonObject();
        check(infill.get("replaceOnlyThis").getAsString().equals("항해하기로 정했군.") && infill.get("fixedAfter").getAsString().equals(" " + fixedLong), "infill keeps replaceable and fixed regions separate");
        var multi = PlayerClaimBoundaryEvaluation.draft("🌙 시작. 하나. 남김. 둘. 끝.");
        var reversed = new RoomDialogueGrounding.Review(false,List.of(
                new RoomDialogueGrounding.Issue(RoomDialogueGrounding.Code.WRONG_SOURCE,"둘.","두 번째 수정"),
                new RoomDialogueGrounding.Issue(RoomDialogueGrounding.Code.WRONG_SOURCE,"하나.","첫 번째 수정")));
        var two = RoomDialoguePatch.plan(evidence,multi,reversed);
        var twoEdits = patch("두 번째 정정."); twoEdits.addProperty("e1","첫 번째 정정.");
        check(two.apply(twoEdits).speech().getFirst().text().equals("🌙 시작. 첫 번째 정정. 남김. 두 번째 정정. 끝."), "sort positions independently of slot IDs; Unicode and gaps preserved");
        var split = new AiDialogueModels.StructuredAiResult(List.of(new AiDialogueModels.Speech("test:one","앞. 하나.",List.of()),new AiDialogueModels.Speech("test:one","둘. 뒤.",List.of())),"",List.of());
        check(RoomDialoguePatch.plan(evidence,split,reversed).apply(twoEdits).speech().stream().map(AiDialogueModels.Speech::text).toList().equals(List.of("앞. 첫 번째 정정.","두 번째 정정. 뒤.")), "independent speech offsets");
        var cleanSources=JsonParser.parseString(plan.preservationMessages(edited).get(1).content()).getAsJsonObject();
        check(cleanSources.has("originalEvidence") && !cleanSources.toString().contains("항해하기로 정했군."), "bad before text cannot become original evidence");
        var validGuard=new JsonObject();var guard=new JsonObject();guard.addProperty("beforeAction","QUESTIONER: decided to sail");guard.addProperty("afterAction","QUESTIONER: wants to sail");guard.addProperty("comparison","source-justified correction");guard.addProperty("preserved",true);validGuard.add("e0",guard);
        plan.requirePreserved(validGuard);checks++;
        for(String key:List.of("beforeAction","afterAction","comparison","preserved")) {
            var malformed=validGuard.deepCopy();malformed.getAsJsonObject("e0").remove(key);rejects(()->plan.requirePreserved(malformed));
        }
        var tooLongGuard=validGuard.deepCopy();tooLongGuard.getAsJsonObject("e0").addProperty("beforeAction","x".repeat(201));rejects(()->plan.requirePreserved(tooLongGuard));
        for (String scenario : List.of("ACCEPT", "UNCHANGED", "DRIFT", "INVALID_PATCH", "INVALID_CHECK", "HTTP_FAILURE")) wire(scenario);
        observedSupportReplanning();
        System.out.println("RoomDialoguePatchTest: " + checks + " checks passed; fake HTTP only");
    }
    private static void observedSupportReplanning() {
        var source=new ArrayList<>(evidence());
        var scene=JsonParser.parseString(source.get(1).content()).getAsJsonObject();
        scene.add("observedActionBoundary",JSON.toJsonTree(Map.of("confirmed",List.of("수확 동작"))));
        source.set(1,new AiDialogueModels.OllamaMessage("user",scene.toString()));
        var original=new AiDialogueModels.StructuredAiResult(List.of(new AiDialogueModels.Speech("test:one","너는 성실한 농부구나.",List.of("player"))),"keep-topic",List.of(new AiDialogueModels.Proposal("example","t","s",List.of(),Map.of())),"E_CALM");
        var issue=new RoomDialogueGrounding.Review(false,List.of(new RoomDialogueGrounding.Issue(RoomDialogueGrounding.Code.UNSUPPORTED_FACT,"너는 성실한 농부구나.","성품은 미확인")));
        var plan=RoomDialoguePatch.plan(source,original,issue);
        check(ObservedSupportRepair.applies(plan),"observed fact repair only");
        check(!ObservedSupportRepair.applies(RoomDialoguePatch.plan(evidence(),original,issue)),"ordinary path unchanged");
        check(ObservedSupportRepair.applies(RoomDialoguePatch.plan(source,original,review("너는 성실한 농부구나."))),"same consulted scene cannot bypass replanning by inconsistent review code");
        var shared=SupportProfilePrompt.enrich(source,Map.of("tendencies",List.of("내실·생활 성장")));
        check(!ObservedSupportRepair.applies(RoomDialoguePatch.plan(shared,original,issue)),"cumulative consented support is not restricted to one observed action");
        String prompt=JSON.toJson(ObservedSupportRepair.messages(plan));
        check(!prompt.contains("너는 성실한 농부구나.")&&!prompt.contains("성품은 미확인"),"rejected draft and review are not generation evidence");
        check(prompt.contains("observedActionBoundary"),"frozen source retained");
        var output=JsonParser.parseString("{\"speech\":[{\"text\":\"네가 밀을 수확하는 모습을 보았지.\"}]}").getAsJsonObject();
        var fixed=ObservedSupportRepair.apply(plan,output);
        check(fixed.proposals().equals(original.proposals())&&fixed.currentTopic().equals(original.currentTopic())&&fixed.currentEmotion().equals(original.currentEmotion()),"replanning cannot change proposals or metadata");
        check(fixed.speech().getFirst().speakerId().equals("test:one")&&fixed.speech().getFirst().audienceParticipantIds().equals(List.of("player")),"routing fixed outside LLM");
        var injected=output.deepCopy();injected.addProperty("speakerId","foreign");rejects(()->ObservedSupportRepair.apply(plan,injected));
        var empty=JsonParser.parseString("{\"speech\":[]}").getAsJsonObject();rejects(()->ObservedSupportRepair.apply(plan,empty));
    }
    private static void wire(String scenario) throws Exception {
        var calls = new AtomicInteger(); var error = new AtomicReference<Throwable>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            try {
                int n = calls.incrementAndGet();
                var body = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                String policy = body.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString();
                if (!policy.startsWith(n == 1 ? RoomDialoguePatch.EDIT_MARKER : RoomDialoguePatch.CHECK_MARKER)) throw new AssertionError("bounded patch then preservation");
                if (n > 2 || !body.get("model").getAsString().equals("gemma4:12b") || body.get("think").getAsBoolean()) throw new AssertionError("unchanged model, bounded calls");
                JsonObject response;
                if (n == 1) { response = patch(scenario.equals("UNCHANGED") ? "항해하기로 정했군." : "항해하고 싶은 거군."); if (scenario.equals("INVALID_PATCH")) response.addProperty("speakerId", "foreign"); }
                else { response = new JsonObject(); var unit = new JsonObject(); unit.addProperty("beforeAction", "플레이어의 항해 결정"); unit.addProperty("afterAction", "플레이어의 항해 희망"); unit.addProperty("comparison", "플레이어가 항해를 희망하는 상태 유지"); unit.addProperty("preserved", !scenario.equals("DRIFT")); if (scenario.equals("INVALID_CHECK")) unit.addProperty("preserved", "true"); response.add("e0", unit); }
                byte[] bytes = JSON.toJson(Map.of("message", Map.of("content", response.toString()))).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(scenario.equals("HTTP_FAILURE") && n == 2 ? 503 : 200, bytes.length); exchange.getResponseBody().write(bytes);
            } catch (Throwable failure) { error.set(failure); exchange.sendResponseHeaders(500, -1); }
            finally { exchange.close(); }
        }); server.start();
        try (var client = new LocalOllamaClient()) {
            var settings = new AiDialogueConfig.Settings(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/chat"), "gemma4:12b",20,600,420,20,260,180,true,96,3,1,16,2,3,3,120,3,false);
            var original = PlayerClaimBoundaryEvaluation.draft("항해하기로 정했군. 남은 말은 보존해.");
            try {
                var result = client.submit(UUID.randomUUID(), RoomDialogueGrounding.repairMessages(evidence(), original, review("항해하기로 정했군.")), settings).completion().get(25, TimeUnit.SECONDS).value();
                check(Set.of("ACCEPT", "UNCHANGED").contains(scenario), "reject drift and malformed reviews, no fallback");
                check(result.speech().getFirst().text().endsWith(" 남은 말은 보존해."), "wire cannot rewrite unflagged sentence");
            } catch (ExecutionException expected) { check(!Set.of("ACCEPT", "UNCHANGED").contains(scenario), "only scripted failures"); }
            check(error.get() == null, "wire protocol");
            check(calls.get() == (Set.of("UNCHANGED", "INVALID_PATCH").contains(scenario) ? 1 : 2), "no retry after drift or failed preservation");
        } finally { server.stop(0); }
    }
}
