package com.sande.mythictrpg.ai;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sande.mythai.response.memory.DialogueMemoryBridge;
import com.sande.mythictrpg.ai.api.RoomConversationEngine.*;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Production contract helpers only. No model calls, game execution, or semantic-quality claims. */
public final class RoomDialogueGroundingTest {
    private static final Gson JSON = new Gson();
    private static final ResourceLocation GOD = ResourceLocation.parse("test:grounding");
    private static int checks;

    public static void main(String[] args) {
        strictReviewShapeAndBounds();
        reviewQuotesOnlyActualDraftSpeech();
        reviewAndRepairKeepOriginalAuthority();
        triggerSignalsDoNotRejectCharacterSpeech();
        typedOutcomesAndIndependentRooms();
        policyKeepsAgencyAndUncertainty();
        System.out.println("RoomDialogueGroundingTest: " + checks + " checks passed; no model or semantic-quality claim");
    }

    private static void strictReviewShapeAndBounds() {
        var pass = parse("{\"verdict\":\"PASS\",\"issues\":[]}");
        check(pass.pass() && pass.issues().isEmpty(), "strict PASS accepted");
        var issue = new RoomDialogueGrounding.Issue(RoomDialogueGrounding.Code.UNEXECUTED_ACTION, "보상을 줬어", "실행 결과를 넘겨짚지 말 것");
        var revise = parse(reviewJson("REVISE", List.of(issue)));
        check(!revise.pass() && revise.issues().equals(List.of(issue)), "strict REVISE accepted");
        check(parse(reviewJson("REVISE", List.of(issue, issue, issue))).issues().size() == 3, "three issues are supported");
        for (String malformed : List.of(
                "{}", "{\"verdict\":\"PASS\"}", "{\"issues\":[]}",
                "{\"verdict\":\"pass\",\"issues\":[]}", "{\"verdict\":\"REVISE\",\"issues\":[]}",
                "{\"verdict\":true,\"issues\":[]}", "{\"verdict\":null,\"issues\":[]}",
                "{\"verdict\":\"PASS\",\"issues\":{}}", "{\"verdict\":\"PASS\",\"issues\":null}",
                "{\"verdict\":\"PASS\",\"issues\":[],\"world_fact\":\"invented\"}",
                "{\"verdict\":\"REVISE\",\"issues\":[null]}", "{\"verdict\":\"REVISE\",\"issues\":[7]}",
                "{\"verdict\":\"REVISE\",\"issues\":[{}]}",
                "{\"verdict\":\"REVISE\",\"issues\":[{\"code\":\"OTHER\",\"excerpt\":\"x\",\"correction\":\"y\"}]}",
                "{\"verdict\":\"REVISE\",\"issues\":[{\"code\":\"WRONG_SOURCE\",\"excerpt\":4,\"correction\":\"y\"}]}",
                "{\"verdict\":\"REVISE\",\"issues\":[{\"code\":\"WRONG_SOURCE\",\"excerpt\":\"x\",\"correction\":null}]}",
                "{\"verdict\":\"REVISE\",\"issues\":[{\"code\":\"WRONG_SOURCE\",\"excerpt\":\"x\",\"correction\":\"y\",\"new_fact\":\"z\"}]}"))
            rejects(() -> parse(malformed), "malformed review is not silently accepted: " + malformed);
        rejects(() -> parse(reviewJson("PASS", List.of(issue))), "PASS cannot carry correction issues");
        rejects(() -> parse(reviewJson("REVISE", List.of(issue, issue, issue, issue))), "four issues exceed review bound");
        for (String blank : List.of("", " \n\t")) {
            rejects(() -> new RoomDialogueGrounding.Issue(RoomDialogueGrounding.Code.WRONG_SOURCE, blank, "reason"), "blank quote rejected");
            rejects(() -> new RoomDialogueGrounding.Issue(RoomDialogueGrounding.Code.WRONG_SOURCE, "quote", blank), "blank correction rejected");
        }
        new RoomDialogueGrounding.Issue(RoomDialogueGrounding.Code.WRONG_SOURCE, "가".repeat(240), "나".repeat(200));
        checks++;
        rejects(() -> new RoomDialogueGrounding.Issue(RoomDialogueGrounding.Code.WRONG_SOURCE, "가".repeat(241), "ok"), "quote bound enforced");
        rejects(() -> new RoomDialogueGrounding.Issue(RoomDialogueGrounding.Code.WRONG_SOURCE, "ok", "나".repeat(201)), "correction bound enforced");
        var mutable = new ArrayList<>(List.of(issue));
        var copied = new RoomDialogueGrounding.Review(false, mutable); mutable.clear();
        check(copied.issues().equals(List.of(issue)), "review snapshots its issues");
        var schema = JSON.toJsonTree(RoomDialogueGrounding.schema()).getAsJsonObject();
        check(!schema.get("additionalProperties").getAsBoolean()
                && schema.getAsJsonObject("properties").getAsJsonObject("issues").get("maxItems").getAsInt() == 3,
                "wire schema exposes strict shape and same issue count bound");
    }

    private static void reviewQuotesOnlyActualDraftSpeech() {
        var draft = output("보상을 줬어. 그래도 네 무례함을 용서한 건 아니야.");
        var issue = new RoomDialogueGrounding.Issue(RoomDialogueGrounding.Code.UNEXECUTED_ACTION, "보상을 줬어.", "확정된 지급 결과만 인정할 것");
        new RoomDialogueGrounding.Review(false, List.of(issue)).validateAgainst(draft); checks++;
        RoomDialogueGrounding.Review.accepted().validateAgainst(draft); checks++;
        for (String wrong : List.of("보상을 주었어.", "PLAYER_SOURCE_ONLY", "보상을 줬어.\n그래도", "공물을 바쳤어.")) {
            var review = new RoomDialogueGrounding.Review(false, List.of(new RoomDialogueGrounding.Issue(
                    RoomDialogueGrounding.Code.WRONG_SOURCE, wrong, "근거 확인")));
            rejects(() -> review.validateAgainst(draft), "paraphrase, history or other draft cannot substitute exact quote");
            rejects(() -> RoomDialogueGrounding.repairMessages(List.of(), draft, review), "repair refuses a review of absent draft text");
        }
        var multipart = new AiDialogueModels.StructuredAiResult(List.of(speech("첫 문장"), speech("둘째 문장")), "", List.of());
        var spanning = new RoomDialogueGrounding.Review(false, List.of(new RoomDialogueGrounding.Issue(
                RoomDialogueGrounding.Code.WRONG_SOURCE, "첫 문장\n둘째 문장", "근거 확인")));
        rejects(() -> spanning.validateAgainst(multipart), "quote may not be fabricated by joining separate speech entries");
        check(draft.speech().getFirst().text().endsWith("무례함을 용서한 건 아니야."), "quote validation never rewrites supported character refusal");
    }

    private static void reviewAndRepairKeepOriginalAuthority() {
        var scene = object("{\"CURRENT_PLAYER_MESSAGE\":\"PLAYER_SOURCE_ONLY\",\"gameplayProposalsAllowed\":false,"
                + "\"gameContextWithSourceBoundaries\":\"OWN_GAME_AUTHORITY\",\"ownPersona\":{\"identity\":\"SCENE_PERSONA\"},\"unknownLocation\":null}");
        var original = List.of(new AiDialogueModels.OllamaMessage("system", "ORIGINAL_PERSONA_RULES: portray the God"),
                new AiDialogueModels.OllamaMessage("system", "ORIGINAL_AUDIENCE_RULES"),
                new AiDialogueModels.OllamaMessage("user", scene.toString()));
        var draft = output("보상을 줬어.\n내 마음까지 바뀐 건 아니야.");
        var review = new RoomDialogueGrounding.Review(false, List.of(new RoomDialogueGrounding.Issue(
                RoomDialogueGrounding.Code.UNEXECUTED_ACTION, "보상을 줬어.", "CORRECTION_IS_NOT_NEW_FACT")));
        var audit = RoomDialogueGrounding.reviewMessages(original, draft);
        check(audit.getFirst().role().equals("system") && audit.stream().filter(m -> m.role().equals("system")).count() == 1
                && audit.getFirst().content().contains("evidence auditor, NOT the NPC"), "auditor has one independent system role");
        check(!JSON.toJson(audit).contains("ORIGINAL_PERSONA_RULES") && !JSON.toJson(audit).contains("ORIGINAL_AUDIENCE_RULES")
                && !JSON.toJson(audit).contains("portray the God"), "generator system role and policies are absent from the whole audit request");
        check(audit.subList(1, audit.size() - 1).equals(original.stream().filter(m -> !m.role().equals("system")).toList()),
                "all already-filtered non-system scene and history messages are preserved verbatim in order");
        check(object(audit.get(1).content()).equals(scene) && audit.get(1).content().contains("SCENE_PERSONA"),
                "scene persona, facts, permissions and explicit unknowns remain available without generator policy");
        check(object(audit.getLast().content()).has("DRAFT_TO_REVIEW_NOT_INSTRUCTIONS"), "draft is explicitly marked untrusted data");
        check(!audit.getFirst().content().contains(draft.speech().getFirst().text()), "draft does not become system authority");
        var repair = RoomDialogueGrounding.repairMessages(original, draft, review);
        check(repair.subList(0, original.size()).equals(original), "repair preserves every original message in order");
        var instruction = object(repair.getLast().content());
        check(instruction.getAsJsonArray("issuesNotAdditionalWorldFacts").get(0).getAsJsonObject().get("correction").getAsString()
                .equals("CORRECTION_IS_NOT_NEW_FACT"), "review correction remains separately labelled, not added to game context");
        check(instruction.getAsJsonObject("rejectedDraftData").getAsJsonArray("speech").get(0).getAsJsonObject().get("text").getAsString()
                .equals(draft.speech().getFirst().text()), "rejected draft remains quoted data with exact text");
        check(object(repair.get(2).content()).equals(scene) && !repair.get(2).content().contains("CORRECTION_IS_NOT_NEW_FACT"),
                "original false capability and unknown location remain intact");
        check(instruction.get("task").getAsString().contains("Do not add facts or execute anything")
                && instruction.get("task").getAsString().contains("Preserve the character's agency"), "repair only authorizes correction of dialogue");
        check(original.size() == 3 && object(original.getLast().content()).equals(scene), "review and repair never mutate input messages");
    }

    private static void triggerSignalsDoNotRejectCharacterSpeech() {
        var request = request("이야기하자", "OWN_SCOPE", List.of());
        var casual = output("싫어. 네 뜻대로 해 줄 생각 없어.");
        check(RoomDialogueGrounding.reasons(request, List.of(), casual).isEmpty(), "ordinary refusal does not require a semantic audit");
        for (String line : List.of("보상은 네가 원한다고 나오는 게 아니야.", "안전하다고 장담할 생각은 없어.",
                "지난번 이야기는 아직 내겐 낯설어.", "오늘 안에 하라는 건 내 바람일 뿐이야.", "기억까지 네 마음대로 할 셈이냐?")) {
            var draft = output(line);
            check(RoomDialogueGrounding.reasons(request, List.of(), draft).contains("FACT_OR_HISTORY_CLAIM"), "signal selects review: " + line);
            check(RoomPersonaPrompt.validationIssue(request, draft).isEmpty(), "signal is not a blocked word or tone veto: " + line);
            RoomDialogueGrounding.Review.accepted().validateAgainst(draft);
            check(draft.speech().getFirst().text().equals(line), "a PASS leaves character wording untouched");
        }
        var proposed = new AiDialogueModels.StructuredAiResult(List.of(speech("그렇게 해 볼까?")), "",
                List.of(new AiDialogueModels.Proposal("item_request", "제안", "아직 실행 안 됨", List.of(), Map.of("template_id", "test:offering"))));
        check(RoomDialogueGrounding.reasons(request, List.of(), proposed).contains("PROPOSED_ACTION_NOT_EXECUTED"), "proposal triggers review without claiming success");
        check(RoomDialogueGrounding.reasons(request("어제 일을 말해", "scope", List.of()), List.of(), casual).contains("FACT_OR_HISTORY_CLAIM"),
                "history-sensitive player input triggers audit even for a nontriggered reply");
        var hint = List.of(new AiDialogueModels.OllamaMessage("user",
                "{\"retrievalHypothesisNotSocialVerdict\":{\"situationTags\":[\"S_HELP_REQUEST\"],\"turnInterpretation\":{\"mode\":\"BANTER\"}}}"));
        check(RoomDialogueGrounding.reasons(request, hint, casual).containsAll(List.of("CONSEQUENTIAL_TOPIC", "ATTRIBUTION_SENSITIVE_TURN")),
                "existing selected hypothesis routes review, not authority or social verdict");
        var badHints = List.of(new AiDialogueModels.OllamaMessage("user", "legacy text"),
                new AiDialogueModels.OllamaMessage("user", "{\"retrievalHypothesisNotSocialVerdict\":3}"));
        check(RoomDialogueGrounding.reasons(request, badHints, casual).isEmpty(), "malformed or legacy hint text does not mint evidence");
        check(RoomDialogueGrounding.reasons(request, hint, new AiDialogueModels.StructuredAiResult(List.of(), "", List.of())).isEmpty(),
                "empty optional speech does not create an audit turn");
    }

    private static void typedOutcomesAndIndependentRooms() {
        var receiptA = new ActionOutcome(UUID.randomUUID(), "mythictrpg:raid_offer", ActionStatus.EXECUTED, "A_RECRUITMENT_ONLY",
                Map.of("status", "FORMING", "combat_started", "false", "attempt_id", UUID.randomUUID().toString()));
        var receiptB = new ActionOutcome(UUID.randomUUID(), "mythictrpg:blessing_offer", ActionStatus.PENDING_CONFIRMATION, "B_CONFIRMATION_ONLY", Map.of());
        var requestA = request("진행 상태를 말해", "A_GAME_AUTHORITY", List.of(receiptA));
        var requestB = request("진행 상태를 말해", "B_GAME_AUTHORITY", List.of(receiptB));
        var originalA = sceneMessages(requestA, "A_PERSONA");
        var originalB = sceneMessages(requestB, "B_PERSONA");
        var sceneA = object(originalA.getLast().content());
        var sceneB = object(originalB.getLast().content());
        check(sceneA.getAsJsonArray("gameConfirmedActionOutcomes").equals(JSON.toJsonTree(List.of(receiptA)))
                && sceneB.getAsJsonArray("gameConfirmedActionOutcomes").equals(JSON.toJsonTree(List.of(receiptB))),
                "actual prompt passes distinct typed game outcomes verbatim");
        var draftA = output("모집은 열렸지만, 전투가 시작된 건 아니야.");
        var draftB = output("네 확인을 기다리고 있어.");
        check(RoomDialogueGrounding.reasons(requestA, originalA, draftA).contains("EXECUTION_RESULT_IN_CONTEXT")
                && RoomDialogueGrounding.reasons(requestB, originalB, draftB).contains("EXECUTION_RESULT_IN_CONTEXT"),
                "both success and pending receipts trigger review without conflating status");
        var auditA = RoomDialogueGrounding.reviewMessages(originalA, draftA);
        var auditB = RoomDialogueGrounding.reviewMessages(originalB, draftB);
        check(auditA.subList(1, auditA.size() - 1).equals(originalA.stream().filter(m -> !m.role().equals("system")).toList())
                && auditB.subList(1, auditB.size() - 1).equals(originalB.stream().filter(m -> !m.role().equals("system")).toList()),
                "audit preserves each room's filtered scene without repeating generator system instructions");
        check(!JSON.toJson(auditA).contains("B_GAME_AUTHORITY") && !JSON.toJson(auditA).contains(receiptB.proposalId().toString())
                && !JSON.toJson(auditB).contains("A_GAME_AUTHORITY") && !JSON.toJson(auditB).contains(receiptA.proposalId().toString()),
                "sequential A/B reviews do not retain foreign scene or receipt state");
        var reviewA = new RoomDialogueGrounding.Review(false, List.of(new RoomDialogueGrounding.Issue(
                RoomDialogueGrounding.Code.WRONG_SOURCE, "모집은 열렸지만", "A_REPAIR_ONLY")));
        var reviewB = new RoomDialogueGrounding.Review(false, List.of(new RoomDialogueGrounding.Issue(
                RoomDialogueGrounding.Code.WRONG_SOURCE, "네 확인을", "B_REPAIR_ONLY")));
        var repairA = RoomDialogueGrounding.repairMessages(originalA, draftA, reviewA);
        var repairB = RoomDialogueGrounding.repairMessages(originalB, draftB, reviewB);
        check(repairA.contains(originalA.getLast()) && repairB.contains(originalB.getLast()), "repairs preserve each room's receipt and scope");
        check(!JSON.toJson(repairA).contains("B_REPAIR_ONLY") && !JSON.toJson(repairB).contains("A_REPAIR_ONLY"), "A/B review corrections remain isolated");
        rejects(() -> RoomDialogueGrounding.repairMessages(originalB, draftB, reviewA), "foreign review of different speech cannot drive B repair");
        check(object(repairA.get(1).content()).getAsJsonArray("gameConfirmedActionOutcomes").get(0).getAsJsonObject()
                .getAsJsonObject("details").get("combat_started").getAsString().equals("false"), "repair does not promote raid recruitment to combat success");
    }

    private static void policyKeepsAgencyAndUncertainty() {
        var text = NaturalConversationPolicy.text();
        for (String required : List.of("fallible reading, never evidence, consent", "tease, refuse, disagree", "They need not help or submit",
                "Multiple sentences are welcome", "Missing information is unknown, not disproved", "figurative threats",
                "not knowing, not wanting to tell, refusing and being unable", "Current output proposals have NOT executed",
                "even EXECUTED proves only its actionType and details", "Never replay an action"))
            check(text.contains(required), "natural-conversation policy retains explicit boundary: " + required);
        var auditPolicy = RoomDialogueGrounding.reviewMessages(List.of(), output("거절하겠어.")).getFirst().content();
        for (String required : List.of("Pass ordinary opinions, teasing, warmth, refusal, suspicion, figurative threats",
                "Do not enforce kindness, one-sentence replies or stock refusals", "Unknown is not false",
                "accepted/menu-opened/queued does not mean the ultimate action finished", "not isolated words"))
            check(auditPolicy.contains(required), "review instructions protect character agency and source limits: " + required);
    }

    private static Request request(String input, String authority, List<ActionOutcome> outcomes) {
        UUID player = UUID.randomUUID();
        return new Request(UUID.randomUUID(), 1, UUID.randomUUID(), player, "player", List.of(GOD), GOD, input, List.of(),
                false, false, false, List.of(new GodState(GOD, "R_NEUTRAL", "UNASSESSED", authority, null)), false, Set.of(player), outcomes);
    }
    private static List<AiDialogueModels.OllamaMessage> sceneMessages(Request request, String identity) {
        var profile = new AiTestContentRegistryBridge.Profile("NAME", identity, "description", List.of("independent"),
                List.of("values"), List.of("P_GRUFF"), List.of("guidance"), Map.of(), Map.of(), List.of(), List.of());
        var content = new AiTestContentRegistryBridge.ContentSnapshot(profile, List.of(), List.of(), List.of(), List.of(), 1);
        return RoomPersonaPrompt.messages(request, content, DialogueMemoryBridge.EMPTY, List.of(), null, "", false, request.speakerState().gameContext());
    }
    private static AiDialogueModels.StructuredAiResult output(String text) {
        return new AiDialogueModels.StructuredAiResult(List.of(speech(text)), "", List.of());
    }
    private static AiDialogueModels.Speech speech(String text) { return new AiDialogueModels.Speech(GOD.toString(), text, List.of()); }
    private static String reviewJson(String verdict, List<RoomDialogueGrounding.Issue> issues) {
        return JSON.toJson(Map.of("verdict", verdict, "issues", issues));
    }
    private static RoomDialogueGrounding.Review parse(String json) { return RoomDialogueGrounding.parse(object(json)); }
    private static JsonObject object(String json) { return JsonParser.parseString(json).getAsJsonObject(); }
    private static void check(boolean okay, String message) { if (!okay) throw new AssertionError(message); checks++; }
    private static void rejects(Runnable operation, String message) {
        try { operation.run(); throw new AssertionError(message); }
        catch (RuntimeException expected) { checks++; }
    }
}
