package com.sande.mythictrpg.ai;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sande.mythictrpg.ai.example.DialogueExampleTag;
import com.sande.mythictrpg.ai.intent.ConversationAct;
import com.sande.mythictrpg.ai.intent.ConversationIntent;
import com.sande.mythictrpg.ai.intent.ConversationIntentRouter;
import com.sande.mythictrpg.ai.intent.TurnInterpretation;
import com.sande.mythictrpg.ai.intent.TurnInterpretation.Mode;
import com.sande.mythictrpg.ai.intent.TurnInterpretation.Referent;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

/** Fixed wire/meaning fixtures only: no model, server or claim that a classifier inferred these readings. */
public final class TurnInterpretationTest {
    private static final Gson JSON = new Gson();
    private static int checks;

    public static void main(String[] args) throws Exception {
        backwardsCompatibility();
        attributedMeaningFixtures();
        malformedOptionalValues();
        evidenceBoundaries();
        localClientWireParser();
        routingCompatibility();
        System.out.println("TurnInterpretationTest: PASS (" + checks + " checks; fixed fixtures, no model or server)");
    }

    private static void backwardsCompatibility() {
        var legacy = new ConversationIntent(Set.of(DialogueExampleTag.S_CHAT), Set.of(), Set.of(),
                ConversationAct.CASUAL_BANTER, 81, ConversationIntent.Source.LOCAL_LLM);
        check(legacy.turnInterpretation().isEmpty(), "six-argument constructor has no inferred reading");
        check(new ConversationIntent(Set.of(), Set.of(), 50, ConversationIntent.Source.LOCAL_LLM)
                .turnInterpretation().isEmpty(), "four-argument constructor stays compatible");
        check(new ConversationIntent(Set.of(), 50, ConversationIntent.Source.HEURISTIC)
                .turnInterpretation().isEmpty(), "three-argument constructor stays compatible");
        check(ConversationIntent.heuristicFallback().turnInterpretation().isEmpty(), "fallback invents no roles");
        check(legacy.withValidatedInterpretation("hello").equals(legacy), "missing extension preserves legacy tags and confidence");
    }

    private static void attributedMeaningFixtures() {
        var fixtures = List.of(
                fixture("네가 나를 도와주는 거야?", Referent.CURRENT_NPC, Referent.CURRENT_PLAYER, Mode.QUESTION,
                        "신이 플레이어를 도와주는지 묻는다"),
                fixture("내가 너를 도와주는 거야?", Referent.CURRENT_PLAYER, Referent.CURRENT_NPC, Mode.QUESTION,
                        "플레이어가 신을 도와주는지 묻는다"),
                fixture("아니, 내가 아니라 네가 무섭냐고", Referent.CURRENT_NPC, Referent.UNSPECIFIED, Mode.CORRECTION,
                        "두려운 쪽이 신인지 묻는 말이었다고 정정한다"),
                fixture("그가 나한테 검을 달라고 했어", Referent.OTHER, Referent.CURRENT_PLAYER, Mode.QUOTED,
                        "다른 사람의 요청을 전한다"),
                fixture("내가 왕이라면 네게 검을 주겠지", Referent.CURRENT_PLAYER, Referent.CURRENT_NPC, Mode.HYPOTHETICAL,
                        "왕이라는 가정 아래 검을 주는 상황을 상상한다"),
                fixture("네가 내게 검을 주면 좋겠어", Referent.CURRENT_NPC, Referent.CURRENT_PLAYER, Mode.WISH,
                        "검을 받기를 바란다"),
                fixture("나한테 검을 줘", Referent.CURRENT_NPC, Referent.CURRENT_PLAYER, Mode.REQUEST,
                        "신에게 검을 달라고 요청한다"),
                fixture("농담이었어, 내 검까지 바치겠냐", Referent.CURRENT_PLAYER, Referent.CURRENT_NPC, Mode.BANTER,
                        "검을 바치겠다는 말을 농담으로 돌린다"),
                fixture("내 검은 안 줄 거야", Referent.CURRENT_PLAYER, Referent.CURRENT_NPC, Mode.REFUSAL,
                        "자기 검을 주기를 거절한다"));
        for (var fixture : fixtures) {
            var decoded = TurnInterpretation.fromJson(JSON.toJsonTree(fixture)).validatedFor(fixture.evidence());
            check(decoded.equals(fixture), "wire preserves role direction and mode: " + fixture.mode());
            var intent = intent(fixture);
            check(intent.withConversationAct(ConversationAct.CORRECTING_NPC).turnInterpretation().equals(fixture),
                    "conversation act normalization does not erase roles: " + fixture.mode());
            check(intent.withValidatedInterpretation(fixture.evidence()).turnInterpretation().equals(fixture),
                    "current line retains its bounded reading: " + fixture.mode());
        }
        check(!fixtures.get(0).subject().equals(fixtures.get(1).subject()), "opposite Korean role fixtures remain distinct");
        check(!JSON.toJson(fixtures).contains("participantId"), "OTHER cannot become a participant identity");
    }

    private static void malformedOptionalValues() {
        for (String raw : List.of("null", "[]", "true", "7", "\"request\"", "{}"))
            check(TurnInterpretation.fromJson(JsonParser.parseString(raw)).isEmpty(), "malformed optional object ignored: " + raw);
        var valid = fixture("나한테 알려줘", Referent.CURRENT_NPC, Referent.CURRENT_PLAYER, Mode.REQUEST, "정보를 요청한다");
        for (String field : List.of("subject", "target", "mode", "meaning", "evidence")) {
            for (String raw : List.of("{}", "[]", "false", "8")) {
                JsonObject value = JSON.toJsonTree(valid).getAsJsonObject();
                value.add(field, JsonParser.parseString(raw));
                check(TurnInterpretation.fromJson(value).isEmpty(), "nested/non-string field ignored: " + field + "=" + raw);
            }
        }
        for (String field : List.of("subject", "target", "mode")) {
            JsonObject value = JSON.toJsonTree(valid).getAsJsonObject();
            value.addProperty(field, "NEW_AUTHORITY_OR_EMOTION");
            check(TurnInterpretation.fromJson(value).isEmpty(), "unknown role/mode invalidates reading: " + field);
        }
        for (String field : List.of("meaning", "evidence")) {
            for (String text : List.of("", " ", "가".repeat(161), "one\ntwo", "one\ttwo")) {
                JsonObject value = JSON.toJsonTree(valid).getAsJsonObject();
                value.addProperty(field, text);
                check(TurnInterpretation.fromJson(value).isEmpty(), "empty, unbounded or multi-line field ignored: " + field);
            }
        }
        JsonObject extra = JSON.toJsonTree(valid).getAsJsonObject();
        extra.addProperty("reasoning", "long hidden reasoning must not cross the contract");
        extra.addProperty("confirmedConsent", true);
        extra.addProperty("npcEmotion", "ANGRY");
        check(TurnInterpretation.fromJson(extra).equals(valid), "unexpected analysis/emotion/consent fields are discarded");
        var boundary = fixture("가".repeat(160), Referent.UNSPECIFIED, Referent.UNSPECIFIED, Mode.UNSPECIFIED, "나".repeat(160));
        check(!TurnInterpretation.fromJson(JSON.toJsonTree(boundary)).isEmpty(), "exact text limit remains usable");
    }

    private static void evidenceBoundaries() {
        var value = fixture("네가 나한테 화났냐고", Referent.CURRENT_NPC, Referent.CURRENT_PLAYER, Mode.CORRECTION,
                "신이 플레이어에게 화가 났는지를 물었다고 정정한다");
        check(value.validatedFor("아니, 네가 나한테 화났냐고 물었어").equals(value), "contiguous original excerpt accepted");
        for (String other : new String[] { null, "", "내가 너한테 화났냐고", "전혀 다른 방의 말", "네가  나한테 화났냐고" })
            check(value.validatedFor(other).isEmpty(), "different current turn cannot reuse interpretation evidence");
        var original = intent(value);
        var filtered = original.withValidatedInterpretation("다른 플레이어의 말");
        check(filtered.turnInterpretation().isEmpty(), "foreign interpretation omitted");
        check(filtered.tags().equals(original.tags()) && filtered.source() == original.source()
                && filtered.confidence() == original.confidence(), "rejecting optional semantics preserves base classification");
        check(original.turnInterpretation().equals(value), "validation does not mutate another turn's immutable intent");
    }

    private static void localClientWireParser() throws Exception {
        var client = new LocalOllamaClient();
        Method parse = LocalOllamaClient.class.getDeclaredMethod("parseIntent", String.class);
        parse.setAccessible(true);
        JsonObject legacy = JsonParser.parseString("""
                {"primarySituation":"S_CHAT","secondarySituations":[],"knowledgeKeywords":["검"],
                 "playerToneTags":["T_INFORMAL"],"conversationAct":"CASUAL_BANTER","confidence":0.83}
                """).getAsJsonObject();
        var parsedLegacy = parse(client, parse, legacy);
        check(parsedLegacy.turnInterpretation().isEmpty() && parsedLegacy.confidence() == 83,
                "actual client parses old JSON without extension");
        var fixture = fixture("그건 농담이야", Referent.CURRENT_PLAYER, Referent.UNSPECIFIED, Mode.BANTER, "앞말이 농담임을 밝힌다");
        legacy.add("turnInterpretation", JSON.toJsonTree(fixture));
        var parsed = parse(client, parse, legacy);
        check(parsed.turnInterpretation().equals(fixture), "actual client carries interpretation extension");
        for (String raw : List.of("[]", "false", "{\"mode\":{}}", "{\"meaning\":\"x\",\"evidence\":\"y\",\"mode\":\"EXECUTE\"}")) {
            legacy.add("turnInterpretation", JsonParser.parseString(raw));
            var fallback = parse(client, parse, legacy);
            check(fallback.turnInterpretation().isEmpty() && fallback.tags().equals(parsedLegacy.tags())
                    && fallback.conversationAct() == parsedLegacy.conversationAct(),
                    "invalid optional extension does not reject valid legacy classification");
        }
    }

    private static void routingCompatibility() {
        var router = new ConversationIntentRouter();
        check(!router.shouldRoute("안녕") && !router.shouldRoute("심심해"), "existing low-risk route stays fast");
        check(router.shouldRoute("아니, 네가 나한테 화났냐고"), "correction still uses context classifier");
        var value = fixture("그건 농담이야", Referent.CURRENT_PLAYER, Referent.UNSPECIFIED, Mode.BANTER, "앞말이 농담임을 밝힌다");
        check(router.mergeStrongHeuristicTones(intent(value), value.evidence()).turnInterpretation().equals(value),
                "tone merging retains grounded semantics");
        check(router.mergeStrongHeuristicTones(intent(value), "다른 말").turnInterpretation().isEmpty(),
                "tone merging cannot copy semantics to another input");
        String instruction = TurnInterpretation.classificationInstruction();
        for (String required : List.of("not merely who is speaking", "corrections", "WISH", "HYPOTHETICAL", "QUOTED",
                "exact, contiguous quote", "NPC's emotions", "consent", "game still validates", "Do not add reasoning"))
            check(instruction.contains(required), "shared classifier explains meaning boundary: " + required);
    }

    private static ConversationIntent parse(LocalOllamaClient client, Method parse, JsonObject content) throws Exception {
        JsonObject message = new JsonObject();
        message.addProperty("content", JSON.toJson(content));
        JsonObject response = new JsonObject();
        response.add("message", message);
        return (ConversationIntent) parse.invoke(client, JSON.toJson(response));
    }

    private static TurnInterpretation fixture(String evidence, Referent subject, Referent target, Mode mode, String meaning) {
        return new TurnInterpretation(subject, target, mode, meaning, evidence);
    }

    private static ConversationIntent intent(TurnInterpretation interpretation) {
        return new ConversationIntent(Set.of(DialogueExampleTag.S_CHAT), Set.of(), Set.of(), ConversationAct.UNSPECIFIED,
                80, ConversationIntent.Source.LOCAL_LLM, interpretation);
    }

    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
}
