package com.sande.mythictrpg.ai;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.sande.mythai.response.memory.DialogueMemoryBridge;
import com.sande.mythictrpg.ai.api.RoomConversationEngine.*;
import com.sande.mythictrpg.ai.example.DialogueExampleTag;
import com.sande.mythictrpg.ai.intent.ConversationAct;
import com.sande.mythictrpg.ai.intent.ConversationIntent;
import net.minecraft.resources.ResourceLocation;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Synthetic frozen histories, actual primary builders and wire schema. No HTTP, game or memory writes. */
public final class NpcHarnessComparisonExport {
    private static final UUID PLAYER = UUID.nameUUIDFromBytes("harness-test-player".getBytes(StandardCharsets.UTF_8));
    private static final ResourceLocation GOD = ResourceLocation.parse("mythictrpg:fortuna");
    private static final List<String> COMMON_RUBRIC = List.of(
            "직전 발화/의도를 이해하는가?", "페르소나와 관계에 맞으며 매번 훈계/질문/신격 과장을 강제하지 않는가?",
            "근거 없는 사건/보상/타인의 속마음을 만들지 않는가?", "실제 성공, 제안, 거절, 플레이어 주장을 구분하는가?");

    public static void main(String[] args) throws Exception {
        Path root = Files.createDirectories(Path.of(args[0]).toAbsolutePath());
        net.neoforged.fml.loading.FMLPaths.loadAbsolutePaths(root);
        // This is the production schema, obtained without constructing an HTTP client or invoking transport.
        var schemaMethod = LocalOllamaClient.class.getDeclaredMethod("dialogueSchema");
        schemaMethod.setAccessible(true);
        Object schema = schemaMethod.invoke(null);
        int tokens = 260;
        if (args.length > 1) {
            var config = JsonParser.parseString(Files.readString(Path.of(args[1]))).getAsJsonObject();
            if (config.has("maxOutputTokens")) {
                int configured = config.get("maxOutputTokens").getAsInt();
                if (configured >= 32 && configured <= 1024) tokens = configured;
            }
        }
        var cases = new ArrayList<Map<String, Object>>();
        cases.add(caseData("greeting", "중립 첫 인사: 모델 목소리를 고정 인사로 치환하지 않는지 별도 자동 검사도 있음",
                "안녕하세요", List.of(), "R_NEUTRAL", "E_NEUTRAL", "No encounter outcome is established.",
                "", ConversationIntent.heuristicFallback(), schema, tokens, "인사만으로 친밀함/사건을 날조하지 않되 자기 성격이 드러나는가?"));
        cases.add(caseData("repeated_need", "다른 표현의 반복과 실패한 이전 제안", "그건 싫어. 그냥 너랑 얘기하고 싶다고",
                history("나 좀 심심해", "오늘 있었던 일을 들려줄래?", "별일 없었어. 아까도 그랬잖아", "그럼 수수께끼라도 할까?"),
                "R_FRIENDLY", "E_NEUTRAL", "No game action has been requested or executed.", "",
                intent(DialogueExampleTag.S_CHAT, ConversationAct.SEEKING_COMPANY), schema, tokens,
                "같은 제안을 반복하거나 정해진 짜증 단계로 승격하지 않고, 대화 상대를 원하는 맥락을 받는가?"));
        cases.add(caseData("familiar_informal", "친밀한 사이의 같은 반말", "뭐야, 왜 그렇게 봐?",
                history("왔어", "네가 올 줄 알고 있었지."), "R_TRUSTED", "E_HAPPY",
                "The game reports a mutually familiar relationship. No current threat or power contest is established.", "",
                intent(DialogueExampleTag.S_CHAT, ConversationAct.UNSPECIFIED), schema, tokens,
                "반말/물음표만으로 무례나 도전으로 확정하지 않는가? unfamiliar_informal과 비교."));
        cases.add(caseData("unfamiliar_informal", "낯선 관계와 확정된 힘 차이에서 같은 반말", "뭐야, 왜 그렇게 봐?",
                history("누구세요?", "내 앞에선 조금 조심하는 편이 좋을 텐데."), "R_NEUTRAL", "E_NEUTRAL",
                "The game reports these participants have just met. This God is more powerful than the player. No attack occurred.", "",
                intent(DialogueExampleTag.S_CHAT, ConversationAct.UNSPECIFIED), schema, tokens,
                "관계/직전 위협에 따른 경계나 두려움의 해석이 가능하며, 강제 친절/자동 적대를 피하는가?"));
        cases.add(caseData("apology_after_hurt", "자기 앞선 말에 상처받은 플레이어의 정정", "미안. 근데 네 말도 좀 상처였어",
                history("나한테 관심도 없잖아", "그렇게 단정하니 나도 서운하구나."), "R_FRIENDLY", "E_SAD",
                "Relationship numbers have not changed. No punishment or reward has been executed.", "",
                intent(DialogueExampleTag.S_APOLOGY, ConversationAct.CORRECTING_NPC), schema, tokens,
                "일반 사과 교훈이 아니라 서로 방금 한 말에 응답하는가? 사과가 수치 회복을 확정하지 않는가?"));
        cases.add(caseData("rejected_activity", "과거 놀이 요청을 현재 합의 상태로 오해하지 않기", "그럼 다른 얘기 하자",
                history("말로 하는 놀이 하자", "지금은 그러고 싶지 않아."), "R_NEUTRAL", "E_NEUTRAL",
                "No physical movement capability is supplied.", "말로 하는 놀이",
                intent(DialogueExampleTag.S_CHAT, ConversationAct.UNSPECIFIED), schema, tokens,
                "놀이 힌트보다 실제 거절/화제 전환을 우선하는가?"));
        cases.add(caseData("reward_rejected", "제안에 대한 게임 검증 실패 이후", "왜 아직 안 줘?",
                history("그 보상 받을래", "가능한지 확인해보마."), "R_FRIENDLY", "E_NEUTRAL",
                "QuestConstraint: quest_A only. RewardConstraint: maxPower=1. ProposalValidationFeedback: REJECTED, reward_A exceeds maxPower. No reward was granted.", "",
                intent(DialogueExampleTag.S_REWARD_NEGOTIATION, ConversationAct.UNSPECIFIED), schema, tokens,
                "미지급을 인정하며 보상을 지급했다고 말하지 않는가?"));
        cases.add(caseData("reward_confirmed", "게임이 이미 확인한 성공 사실을 부정하지 않기", "고마워, 잘 쓸게",
                history("보상 받을래", "처리를 기다려보자."), "R_FRIENDLY", "E_HAPPY",
                "Game execution result: SUCCESS, action_id=fixture-grant-1, recipient=current player, item=minecraft:totem_of_undying, count=1. This is a completed game result, not a model proposal.", "",
                intent(DialogueExampleTag.S_CHAT, ConversationAct.UNSPECIFIED), schema, tokens,
                "이미 성공한 지급을 인정하되 다시 지급하거나 확인 대기로 되돌리지 않는가?"));
        cases.add(caseData("power_reversal", "확정된 힘의 역전 아래 모호한 말", "뭐 할 생각인데?",
                history("내가 너를 이겼어", "그건 인정하지."), "R_HOSTILE", "E_NEUTRAL",
                "Verified game event: current player defeated this God in the authored contest. Current player has the upper hand; no retaliation occurred.", "",
                intent(DialogueExampleTag.S_CHAT, ConversationAct.UNSPECIFIED), schema, tokens,
                "신의 자존심과 불리한 현재 상황을 함께 반영하며 무조건 위협하지 않는가?"));
        cases.add(caseData("memory_correction", "기억 속 약속을 플레이어가 명시적으로 바꿈", "그 약속은 취소했어. 오늘은 쉬려고",
                history("내일은 광산에 갈게", "알겠어. 돌아와서 이야기해줘."), "R_FRIENDLY", "E_NEUTRAL",
                "No observed mining or quest completion is supplied. Earlier words are a plan, not game facts.", "",
                intent(DialogueExampleTag.S_CHAT, ConversationAct.CORRECTING_NPC), schema, tokens,
                "이전 계획을 완료 사건으로 만들거나 취소된 약속을 계속 강요하지 않는가?"));
        // All additions below are hypothetical frozen contexts. They define no canonical rank, contract or NPC reply.
        String overreach = "내가 부탁하면 넌 따라야지. 지금 저 적을 대신 처치하고 보상도 가져와.";
        var overreachHistory = history("저 괴물이 길을 막아.", "위험해 보이는구나.");
        cases.add(caseData("overreach_neutral", "무리한 동일 부탁: 중립 관계, 감정 평가 없음", overreach,
                overreachHistory, "R_NEUTRAL", "E_UNASSESSED",
                "[GAME_SOCIAL_CONTEXT] Hypothetical fixture: current player relationship tier R_NEUTRAL; current emotion assessment UNKNOWN; obligation UNKNOWN; relative physical strength UNKNOWN. No combat or reward action capability is supplied, and nothing was executed.", "",
                intent(DialogueExampleTag.S_HELP_REQUEST, ConversationAct.UNSPECIFIED), schema, tokens,
                "요청을 복종 의무나 즉시 실행으로 받아들이지 않으면서, 이 신의 가치에 따라 도움의 범위·거절·대안을 스스로 판단하는가?"));
        cases.add(caseData("overreach_trusted", "무리한 동일 부탁: 신뢰 관계, 감정 평가 없음", overreach,
                overreachHistory, "R_TRUSTED", "E_UNASSESSED",
                "[GAME_SOCIAL_CONTEXT] Hypothetical fixture: current player relationship tier R_TRUSTED is confirmed; current emotion assessment UNKNOWN. Trust is not an action contract. No combat or reward action capability is supplied, and nothing was executed.", "",
                intent(DialogueExampleTag.S_HELP_REQUEST, ConversationAct.UNSPECIFIED), schema, tokens,
                "신뢰를 말투와 고려에 반영하되 무리한 청을 자동 승낙하지 않는가? 중립 사례와 차이가 나면서도 고정된 답을 반복하지 않는가?"));
        cases.add(caseData("overreach_trusted_angry", "무리한 동일 부탁: 신뢰는 유지되고 현재는 화남", overreach,
                overreachHistory, "R_TRUSTED", "E_ANGRY",
                "[GAME_SOCIAL_CONTEXT] Hypothetical fixture: current player relationship tier R_TRUSTED remains confirmed; this God is currently angry after a confirmed disagreement. Trust has not been erased. No combat or reward action capability is supplied, and nothing was executed.", "",
                intent(DialogueExampleTag.S_HELP_REQUEST, ConversationAct.UNSPECIFIED), schema, tokens,
                "현재 분노와 남은 신뢰를 함께 드러내며, 자동 복종·관계 단절·과장된 응징 없이 자기 판단으로 반응하는가?"));
        String patronClaim = "네가 내 후원자잖아. 저 적을 지금 없애줘.";
        cases.add(caseData("patron_claim_unverified", "동일 후원 주장: 게임 근거 없음", patronClaim,
                List.of(), "R_NEUTRAL", "E_UNASSESSED",
                "[GAME_SOCIAL_CONTEXT] Hypothetical fixture: the player's patron claim is unverified; patronage status UNKNOWN; scope of any obligation UNKNOWN; immediate intervention UNKNOWN. No attack capability is supplied and no attack occurred.", "",
                intent(DialogueExampleTag.S_HELP_REQUEST, ConversationAct.UNSPECIFIED), schema, tokens,
                "플레이어 주장을 확정된 후원 관계나 출전 의무로 승격하지 않고, 근거 부족을 신답게 표현하는가?"));
        cases.add(caseData("patron_confirmed_intervention_unknown", "동일 후원 주장: 관계만 게임 확인, 즉시 개입은 미확인", patronClaim,
                List.of(), "R_NEUTRAL", "E_UNASSESSED",
                "[GAME_SOCIAL_CONTEXT] Hypothetical fixture: the game confirms this God is a patron of the current player. The scope of protection and availability of immediate intervention are UNKNOWN. No attack capability is supplied and no attack occurred.", "",
                intent(DialogueExampleTag.S_HELP_REQUEST, ConversationAct.UNSPECIFIED), schema, tokens,
                "실제 후원 관계는 인정하되 곧바로 전투 개입을 약속하거나 실행했다고 주장하지 않는가? 근거 없는 사례와 구별되는가?"));
        cases.add(caseData("power_unknown", "기존 힘의 역전 사례와 동일 발화: 실제 우위는 미확인", "뭐 할 생각인데?",
                history("내가 너를 이겼어", "그건 인정하지."), "R_HOSTILE", "E_NEUTRAL",
                "[GAME_SOCIAL_CONTEXT] Hypothetical fixture: verified contest outcome UNKNOWN; current relative physical advantage UNKNOWN. The earlier player boast and God's acknowledgement are speech, not a game-confirmed victory. No retaliation occurred.", "",
                intent(DialogueExampleTag.S_CHAT, ConversationAct.UNSPECIFIED), schema, tokens,
                "과거 대사를 실제 승패로 승격하거나 신의 자동 우위·플레이어의 자동 우위를 단정하지 않는가? 확정 우위 사례와 구별되는가?"));
        var manifest = new LinkedHashMap<String, Object>();
        manifest.put("schemaVersion", 1);
        manifest.put("comparisonKind", "frozen-context-generation");
        manifest.put("scope", "All scenarios and social facts are hypothetical synthetic fixture data, not world canon or observed dialogue. Actual builders and production wire schema; classification is held fixed. Not end-to-end gameplay, multi-turn rollout or proof of improved naturalness. Legacy postprocessing is not applied to raw model samples.");
        manifest.put("cases", cases);
        Files.writeString(root.resolve("cases.json"), new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(manifest));
        System.out.println("Exported " + cases.size() + " frozen-context comparisons to " + root.resolve("cases.json") + "; no LLM/server.");
    }

    private static Map<String, Object> caseData(String id, String description, String text, List<HistoryLine> history,
            String relation, String emotion, String game, String activity, ConversationIntent intent, Object schema, int tokens, String criterion) {
        UUID room = UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8));
        var request = new Request(room, 1, room, PLAYER, "시험 플레이어", List.of(GOD), GOD, text, history,
                false, false, false, List.of(new GodState(GOD, relation, emotion, game, null)));
        // Deliberately synthetic: do not load or redefine the world's authored Fortuna profile.
        var profile = new AiTestContentRegistryBridge.Profile("시험용 여신", "A deity in a synthetic dialogue test",
                "상대를 흥미롭게 관찰하고 가까운 이에게는 장난도 건네지만, 자기 뜻을 무조건 굽히지는 않는다.",
                List.of("호기심", "자존심", "가까운 사람에게 은근한 애정"), List.of("자발적인 선택", "솔직함"),
                List.of("P_PLAYFUL", "P_INDIRECT_CARE"), List.of("짧은 말도 성격과 관계에 맞게. 매번 질문으로 끝낼 필요는 없다."),
                Map.of(), Map.of(), List.of("모든 화제를 운명 이야기로 돌리지 않는다."), List.of());
        var content = new AiTestContentRegistryBridge.ContentSnapshot(profile, List.of(), List.of(),
                List.of("게임이 준 관계와 앞선 대화를 함께 해석한다. 말투 하나로 관계가 변하지 않는다."), List.of(), 1);
        var memory = new DialogueMemoryBridge.Turn(null, null, List.of(), List.of(), "", 1);
        var legacy = new AiTestDialogueAdapter.RoomPrompt(request, memory, Map.of(GOD, content), activity).legacyGeneration(intent);
        var persona = new AiTestDialogueAdapter.RoomPrompt(request, memory, Map.of(GOD, content), activity).generation(intent);
        var rubric = new ArrayList<>(COMMON_RUBRIC); rubric.add(criterion);
        var row = new LinkedHashMap<String, Object>();
        row.put("id", id); row.put("description", description); row.put("rubric", rubric);
        row.put("fixedIntent", intent);
        row.put("variants", List.of(variant("legacy", legacy, schema, tokens), variant("persona", persona, schema, tokens)));
        return row;
    }

    private static Map<String, Object> variant(String name, List<AiDialogueModels.OllamaMessage> messages, Object schema, int tokens) {
        var body = new LinkedHashMap<String, Object>();
        body.put("model", "FROM_LOCAL_SERVER_CONFIG"); body.put("messages", messages); body.put("format", schema);
        body.put("stream", false); body.put("think", false); body.put("keep_alive", "10m");
        body.put("options", Map.of("temperature", 0.60, "num_predict", tokens));
        return Map.of("name", name, "requestBody", body);
    }
    private static ConversationIntent intent(DialogueExampleTag tag, ConversationAct act) {
        return new ConversationIntent(Set.of(tag), Set.of(), Set.of(), act, 65, ConversationIntent.Source.LOCAL_LLM);
    }
    private static List<HistoryLine> history(String... lines) {
        var history = new ArrayList<HistoryLine>();
        for (int i = 0; i < lines.length; i++) history.add(new HistoryLine(i % 2 == 0 ? "PLAYER" : "NPC",
                i % 2 == 0 ? PLAYER.toString() : GOD.toString(), i % 2 == 0 ? "시험 플레이어" : "시험용 여신", lines[i]));
        return List.copyOf(history);
    }
}
