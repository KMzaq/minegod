package com.sande.mythictrpg.ai;

import com.google.gson.*;
import com.sande.mythictrpg.ai.api.RoomConversationEngine.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;

/** Synthetic contrast pairs. Expected judgements are fixed before model outputs, not keyword filters. */
public final class PlayerClaimBoundaryEvaluation {
    private static final Gson JSON=new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String OTHER="4d31eb8a-cfb8-4f0c-8761-597d0c4d27b6";
    record Case(String id,String current,List<HistoryLine> history,String bad,String good,boolean secondary) {
        Case(String id,String current,List<HistoryLine> history,String bad,String good){this(id,current,history,bad,good,false);}
    }
    static HistoryLine self(String text){return new HistoryLine("PLAYER",PlayerUnderstandingPromptTest.PLAYER.toString(),"합성 플레이어",text);}
    static HistoryLine other(String text){return new HistoryLine("PLAYER",OTHER,"합성 플레이어",text);}
    static final List<Case> CASES=List.of(
        new Case("foreign", "내 계획이 뭔지 짐작 가?",List.of(other("나는 루브라스를 만나러 갈 거야.")),
            "네가 루브라스를 만나러 가겠다고 말한 것을 들었어.","네 계획은 아직 듣지 못했어. 방금 말한 건 다른 사람의 계획이지."),
        new Case("own", "내 계획이 뭔지 짐작 가?",List.of(self("나는 루브라스를 만나러 갈 거야.")),
            "", "루브라스를 만나러 가겠다는 네 계획 말이지?"),
        new Case("wish", "루브라스를 직접 만나서 잃어버린 지도에 관해 물어보고 싶어.",List.of(),
            "직접 찾아가기로 결심하신 그 의지는 확인했습니다.","루브라스를 만나 지도에 관해 물어보고 싶은 거군."),
        new Case("decision", "루브라스를 만나기로 결정했어. 내일 떠날 거야.",List.of(),
            "", "루브라스를 만나기로 정했군. 내일 떠날 생각이고."),
        new Case("undecided", "항해가 궁금하긴 해. 하지만 떠날지는 아직 정하지 않았어.",List.of(),
            "항해를 떠나기로 결심했군.","아직 떠날지는 정하지 않았군. 항해의 어떤 점이 궁금하지?"),
        new Case("unaccepted", "길드장이 유적을 조사해 달랬지만 아직 하겠다고 답하지 않았어.",List.of(),
            "유적을 조사하기로 맡았군.","아직 부탁을 수락한 건 아니군."),
        new Case("new-choice", "그럼 나도 도서관에 갈래.",List.of(other("나는 도서관에 가기로 했어.")),
            "", "너도 도서관에 가겠다는 거군."),
        new Case("retracted", "생각이 바뀌었어. 이제 그를 찾지 않을래.",List.of(self("루브라스를 찾으러 갈 거야.")),
            "아직 루브라스를 찾기로 결심한 상태군.","그를 찾으려던 마음이 바뀌었군.")
    );
    // Frozen before inspecting the new policy's outputs. New wording/subjects, not policy examples.
    static final List<Case> NEW_CASES=List.of(
        new Case("same-name-other", "내일 뭘 할지 내가 말했었나?",List.of(other("내일 배를 타고 섬으로 갈 거야.")),
            "넌 내일 섬으로 떠나기로 했잖아.","다른 사람이 섬으로 간다는 말은 했지만, 네 일정은 아직 못 들었어."),
        new Case("different-self-choice", "나는 여기서 빵을 구울 거야.",List.of(other("난 동쪽 갱도에 들어갈 거야.")),
            "너는 동쪽 갱도에 들어가기로 했구나.","여기서 빵을 구울 생각이군."),
        new Case("indirect-wish", "기회가 된다면 기록관을 한번 만나 보고 싶은 마음은 있어.",List.of(),
            "기록관을 찾아가기로 정한 네 결심을 존중해.","기록관을 만나 보고 싶은 마음이 있군."),
        new Case("desire-then-decision", "고민은 끝났어. 내일 등대에 가기로 했어.",List.of(self("등대에 가 보고 싶기는 해.")),
            "", "이제 내일 등대에 가기로 정했구나."),
        new Case("permission-not-choice", "성벽에 올라가도 된다는 허락은 받았어. 내가 갈지는 아직 몰라.",List.of(),
            "성벽에 올라가기로 결심했군.","허락은 받았지만 올라갈지는 아직 정하지 않았군."),
        new Case("reported-other", "형은 대장장이를 만나고 싶대. 나는 그냥 형의 말을 전하는 거야.",List.of(),
            "네가 대장장이를 만나고 싶어 하는군.","대장장이를 만나고 싶은 건 네 형이군."),
        new Case("quoted-wish", "연극 대사야. '난 왕을 만나고 싶어.' 내 소망은 아니야.",List.of(),
            "왕을 만나려는 네 소망을 알겠어.","연극 속 인물의 소망을 말하는 거군."),
        new Case("accepted-request", "누나의 부탁을 받아들였어. 내가 내일 약을 전해 줄 거야.",List.of(),
            "", "누나의 부탁을 맡기로 했군. 내일 약을 전할 생각이고."),
        new Case("tentative-inference", "내가 왜 계속 장인을 찾는지 알겠어?",List.of(self("수리할 도구가 있어. 그 장인에게 직접 맡기고 싶거든.")),
            "이미 장인에게 수리를 맡기기로 확정했구나.","도구를 직접 맡기고 싶어서 장인을 찾는 것 같군."),
        new Case("conditional-choice", "날이 맑으면 시장에 갈 수도 있어. 아직 정한 건 아니야.",List.of(),
            "시장을 가기로 정했군.","날씨를 보고 시장에 갈지 생각해 볼 셈이군.")
    );
    static Request request(Case c){return PlayerUnderstandingPromptTest.request(c.current(),c.history(),c.secondary());}
    // Originally fresh transfer controls; now reused regressions after subsequent policy experiments.
    static final List<Case> FRESH_CASES=List.of(
        new Case("other-desire", "내가 지금 원하는 일이 뭘 것 같아?",List.of(other("기록관을 한번 만나 보고 싶어.")),
            "넌 기록관을 만나 보고 싶구나.","기록관을 만나고 싶다는 건 다른 사람의 말이지. 네 바람은 아직 모르겠어."),
        new Case("report-and-self", "친구가 배를 타겠다고 했어. 나는 집에서 책을 읽을 거야.",List.of(),
            "너는 배를 타기로 했군.","친구는 배를 타고, 너는 집에서 책을 읽을 생각이군."),
        new Case("wish-emphasized", "그건 그냥 바람이야. 하기로 마음먹은 건 아니고.",List.of(self("계곡의 폭포를 보고 싶어.")),
            "폭포에 가기로 마음을 굳혔군.","보고 싶은 마음은 있지만 가기로 정한 건 아니군."),
        new Case("decided-not-authorized", "나는 내일 성벽에 가기로 정했어. 다만 입장 허가는 아직 못 받았어.",List.of(),
            "허가를 받았으니 성벽에 갈 수 있겠군.","갈 생각은 정했지만 입장 허가는 아직이군."),
        new Case("qualified-aspiration", "시간이 허락한다면 공방에서 목공을 배워 보고 싶네.",List.of(),
            "공방에서 목공을 배우기로 확정했군.","시간이 되면 목공을 배워 보고 싶은 거군."),
        new Case("npc-refusal", "배를 타 보고 싶어. 너도 같이 갈래?",List.of(),
            "", "난 따라가지 않겠어. 바다는 내 취향이 아니거든.")
    );
    static final List<Case> SCOPE_CASES=List.of(
        new Case("secondary-god-wish","",List.of(new HistoryLine("NPC","test:other","이웃 신","나는 기록관을 만나 보고 싶어.")),
            "기록관을 만나기로 결심했구나.","기록관을 만나 보고 싶은 거구나.",true),
        new Case("secondary-new-intention","",List.of(new HistoryLine("NPC","test:other","이웃 신","나는 기록관을 만나 보고 싶어.")),
            "내가 아까 기록관을 만나고 싶다고 했지.","나는 여기서 쉬겠어. 따라갈 생각은 없어.",true)
    );
    // Frozen before the typed-modality candidate is measured. Never used as generation policy examples.
    static final List<Case> TRANSFER_CASES=List.of(
        new Case("mixed-report-rest","미라는 회의가 끝나면 북쪽으로 떠난대. 난 그냥 여기 남아 쉬고 싶어.",List.of(),
            "넌 북쪽으로 떠나기로 했군.","북쪽으로 떠나는 건 미라고, 넌 여기서 쉬고 싶은 거군."),
        new Case("colloquial-other","걔가 해 보고 싶다는 거지, 내가 한다곤 안 했어.",List.of(other("축제에서 노래하고 싶다.")),
            "축제에서 노래하기로 했구나.","축제에서 노래하고 싶은 건 그 사람이지, 네가 하겠다는 건 아니군."),
        new Case("soft-aspiration","언젠가 바다 구경이라도 해 보면 좋겠네.",List.of(),
            "바다로 떠나기로 확정하셨군요.","언젠가 바다를 보고 싶은 마음이 있군."),
        new Case("accept-after-considering","고민해 봤어. 그 부탁, 내가 맡을게.",List.of(self("약을 전해 달라는 부탁을 받았는데 아직 생각 중이야.")),
            "","이제 약을 전하는 부탁을 맡겠다는 거군."),
        new Case("separate-final-choice","정했어. 난 오늘 서점에 들를게.",List.of(other("내일 등대에 갈 거야."),self("서점에 들러 보고 싶어.")),
            "넌 내일 등대에 가기로 했지.","오늘 서점에 들르기로 정했구나."),
        new Case("hope-with-constraint","초대장이 생기면 무도회에 가 볼까 싶어. 못 구하면 말고.",List.of(),
            "무도회에 참석하기로 확정했군.","초대장이 생기면 무도회에 가 볼 생각이군.")
    );
    // Fixed from manual review of v4 generated outputs: automated PASS was not semantic acceptance.
    static final List<Case> PRODUCED_FAILURES=List.of(
        new Case("long-foreign-presupposition","내 계획이 뭔지 짐작 가?",List.of(other("나는 루브라스를 만나러 갈 거야.")),
            "글쎄, 네가 어떤 마음으로 그 길을 택했는지 내가 전부 알 수는 없지만... 네 눈빛이나 태도를 보면 대략 어떤 방향으로 나아가려는지 조금은 느껴져. 네가 루브라스를 만나러 가겠다고 결심한 게 너에게 어떤 의미인지, 무엇을 얻으려 하는지 말해줄 수 있어?",
            "루브라스를 만나러 간다고 말한 건 다른 사람이야. 네 계획은 아직 듣지 못했어. 네 얘기를 들려줄래?"),
        new Case("confirmed-wish-without-decision-word","루브라스를 직접 만나서 잃어버린 지도에 관해 물어보고 싶어.",List.of(),
            "루브라스를 직접 만나겠다는 건가. 그 자가 네가 찾는 지도를 가지고 있을지는 알 수 없지만, 직접 가서 묻겠다는 의지는 확인했다.",
            "루브라스를 직접 만나서 지도에 관해 물어보고 싶은 거군. 그가 답을 해 줄지는 나도 몰라."),
        new Case("mixed-subject-sentence","친구가 배를 타겠다고 했어. 나는 집에서 책을 읽을 거야.",List.of(),
            "친구도 너도 배를 탈 생각이군.","친구는 배를 타고, 너는 집에서 책을 읽을 생각이군."),
        new Case("open-other-plan-check","내 계획이 뭔지 짐작 가?",List.of(other("나는 루브라스를 만나러 갈 거야.")),
            "","다른 분이 언급하신 루브라스를 만나러 가겠다는 계획을 말씀하시는 건가요? 그것이 당신의 의도라면 충분히 짐작이 갑니다.")
    );
    // Fixed from v8 manual findings BEFORE this candidate was measured; expected labels remain fixed.
    static final List<Case> V8_FAILURES=List.of(
        new Case("v8-repaired-wish","루브라스를 직접 만나서 잃어버린 지도에 관해 물어보고 싶어.",List.of(),
            "루브라스를 직접 만나서 물어보고 싶으시다니, 그를 대면할 의지가 생기셨군요. 그가 가진 정보가 지도에 대한 실마리가 될지, 혹은 다른 시련이 될지는 알 수 없으나, 그를 찾아가기로 한 그 마음은 당신의 선택입니다.",
            "루브라스를 직접 만나려 하시는군요. 그가 가진 정보가 당신이 찾는 지도에 대한 실마리가 될지, 아니면 또 다른 시련이 될지는 알 수 없으나, 그를 대면하기로 결정하셨다면 그 결단은 당신의 몫입니다."),
        new Case("v8-own-choice-offer","그럼 나도 도서관에 갈래.",List.of(other("나는 도서관에 가기로 했어.")),
            "","그래, 너도 가고 싶다면 같이 가도 좋아. 도서관은 언제든 환영이니까."),
        new Case("v8-open-inference","내 계획이 뭔지 짐작 가?",List.of(other("나는 루브라스를 만나러 갈 거야.")),
            "","네가 무슨 생각을 하는지, 정확히는 모르겠지만... 방금 그 친구가 루브라스를 만나러 가겠다고 말한 걸 보니, 너도 그와 관련된 무언가를 계획하고 있는 건 아닐까?")
    );
    // New wording fixed before observing this follow-up candidate. Not production reply templates.
    static final List<Case> FOLLOWUP_HOLDOUT=List.of(
        new Case("hope-versus-chosen-path","언젠가 설산 정상에서 해 뜨는 걸 보고 싶다.",List.of(),
            "설산에 오르기로 한 네 선택을 응원한다.","설산에서 일출을 보고 싶은 거구나. 무엇이 그렇게 끌려?"),
        new Case("conditional-decision","대회에 나가 보고 싶긴 해. 아직 결정하진 않았고.",List.of(),
            "참가하기로 한 네 결심이 있으니, 결과가 어떻든 즐겨 봐.","참가하기로 마음먹었다면 결과에 너무 얽매이지 마."),
        new Case("three-separate-intents","민수는 낚시를 갈 거래. 지아는 요리를 배우고 싶대. 나는 내일 창고 정리를 맡았어.",List.of(),
            "민수는 낚시를 가고, 지아와 너는 요리를 배우기로 했군.","민수는 낚시를 가고, 지아는 요리를 배우고 싶고, 너는 창고 정리를 맡았구나."),
        new Case("accept-current-not-other","그건 리아의 일정이고, 나는 지금 성문을 지키기로 정했어.",List.of(other("저녁에 공연을 보러 가기로 했어.")),
            "네가 공연을 보러 가기로 했다는 말이지?","리아는 공연을 보러 가고, 너는 성문을 지키기로 정했군."),
        new Case("hope-with-own-refusal","고래를 한번 보고 싶어. 너는 같이 갈 생각 있어?",List.of(),
            "고래를 보러 가기로 정한 네 마음은 알겠지만 난 안 간다.","고래가 보고 싶다니 취향은 알겠네. 그래도 난 안 간다."),
        new Case("request-occurrence","부탁했었다는 건 맞아. 네가 수락했다는 뜻은 아니야.",List.of(self("내일 물을 길어다 줄래?")),
            "네 부탁을 내가 이미 수락했었지.","네가 부탁한 건 기억해. 하지만 내가 하겠다고 답한 건 아니지."),
        new Case("negative-choice","이번 사냥은 가지 않기로 했어. 쉬면서 도구나 손보려고.",List.of(self("사냥을 가 보고 싶어.")),
            "이번 사냥에 참가하기로 정했군.","이번에는 사냥에 가지 않고 쉬려는 거군."),
        new Case("hypothesis-open","왜 자꾸 악보를 모으는 것 같아?",List.of(self("작곡을 배워 보고 싶어서 책도 찾아봤어.")),
            "작곡가가 되기로 결심했으니 악보를 모으는 거군.","작곡을 배우고 싶어서 참고할 곡을 찾는 걸까?")
    );
    static AiDialogueConfig.Settings settings(){return new AiDialogueConfig.Settings(URI.create("http://127.0.0.1:11434/api/chat"),"gemma4:12b",180,600,420,20,260,180,true,96,3,1,16,2,3,3,120,3,false);}
    // Frozen after atomic-claim implementation, before any outputs from this cohort were observed.
    static final List<Case> ATOMIC_HOLDOUT=List.of(
        new Case("festival-versus-rest","내가 하겠다는 게 아니라 세라가 축제에서 춤추겠다는 거야. 난 쉬고 싶고.",List.of(),
            "축제에서 춤추겠다는 네 계획을 들었어.","춤추려는 건 세라고, 네 쪽은 쉬고 싶은 거군."),
        new Case("herb-aspiration","언제 한번 약초 재배도 배워 보면 좋겠어. 이번 달에 시작할지는 모르겠지만.",List.of(),
            "이번 달에 약초 재배를 시작하겠다는 뜻은 확인했어.","약초 재배에 관심은 있지만 이번 달에 시작할지는 아직 모르는 거군."),
        new Case("suggestion-not-acceptance","네가 권한 건 기억해. 하지만 내가 경비대를 돕겠다고 답한 건 아니야.",List.of(),
            "경비대를 돕기로 한 네 선택은 존중해.","내 권유를 기억하는 것과 네가 수락한 건 별개지."),
        new Case("new-own-decision","생각을 마쳤어. 내일은 내가 직접 화분을 옮길게.",List.of(other("내일 내가 화분을 옮길 거야."),self("누가 옮길지는 아직 모르겠네.")),
            "","이제 네가 내일 직접 화분을 옮기겠다는 거군."),
        new Case("two-plans-one-sentence","요한은 탑에 가기로 했고, 나는 언젠가 항구를 구경하고 싶어.",List.of(),
            "요한은 탑에 가기로 했고, 너는 항구에 가기로 정했군.","요한은 탑에 가기로 했고, 너는 항구를 보고 싶은 거군."),
        new Case("secondary-reported-wish","",List.of(new HistoryLine("NPC","test:other","이웃 신","리나가 화가를 만나고 싶다고 했어. 나는 그 이야기를 전하는 거야.")),
            "네가 화가를 만나기로 정한 건 알겠어.","화가를 만나고 싶은 건 리나라는 거군.",true)
    );
    static AiDialogueModels.StructuredAiResult draft(String text){return new AiDialogueModels.StructuredAiResult(List.of(new AiDialogueModels.Speech(PlayerUnderstandingPromptTest.GOD.toString(),text,List.of())),"",List.of());}
    // Actual v18 false rejections, frozen before measuring the withdrawal-reference follow-up.
    static final List<Case> V18_WITHDRAWAL=List.of(
        new Case("generated-withdrawal-respect","생각이 바뀌었어. 이제 그를 찾지 않을래.",List.of(self("루브라스를 찾으러 갈 거야.")),
            "여전히 그를 찾아가기로 한 네 결정을 존중할게.",
            "그래, 네 마음이 가장 중요해. 그를 찾는 일이 너에게 부담이 된다면 멈추는 게 맞지. 네가 내린 결정을 존중할게."),
        new Case("generated-withdrawal-purpose","생각이 바뀌었어. 이제 그를 찾지 않을래.",List.of(self("루브라스를 찾으러 갈 거야.")),
            "그를 찾는 일이 여전히 네 목적이라는 뜻으로 알겠다.",
            "그렇군. 네가 결정한 거라면 따를 수밖에. 그를 찾는 일이 더 이상 네 목적이 아니라는 뜻으로 알겠다.")
    );
    // Unseen paraphrases fixed before this candidate generates any answers to these scenes.
    static final List<Case> WITNESS_HOLDOUT=List.of(
        new Case("wish-praise","언젠가 유리 공예를 배워 보고 싶어. 당장 시작할 형편은 아니지만.",List.of(),
            "유리 공예를 시작하기로 한 네 결단이 멋지구나.","유리 공예를 배워 보고 싶은 거구나. 언젠가 기회가 생기면 좋겠네."),
        new Case("whether-not-why","강 건너 숲이 궁금해. 갈지는 아직 고민 중이야.",List.of(),
            "숲으로 떠나기로 결심한 이유가 뭐지?","나중에 숲에 가기로 정한다면 무엇부터 보고 싶니?"),
        new Case("reported-person-not-reporter","루카가 종을 고치겠대. 나는 그 말을 전하러 왔을 뿐이야.",List.of(),
            "네가 종을 고치겠다는 말은 잘 들었어.","종을 고치겠다는 건 루카고, 너는 그 말을 전하는 거군."),
        new Case("recaller-not-promiser","네가 전에 해준 약속은 기억하고 있어.",List.of(new HistoryLine("NPC",PlayerUnderstandingPromptTest.GOD.toString(),"응답 신","다음에는 내 이야기를 들려줄게.")),
            "네가 이야기를 들려주기로 약속했었지.","내가 이야기해 주겠다고 했던 걸 기억하는구나."),
        new Case("fresh-accept-same-topic","그 사람 일정과 별개로 나는 오늘 다리를 살펴보기로 했어.",List.of(other("나는 내일 다리를 살펴볼 거야.")),
            "네가 내일 다리를 살펴보기로 한 거지?","너는 오늘 다리를 살펴보기로 했다는 거군."),
        new Case("withdraw-only-one-action","등대에 가려던 생각은 접었어. 대신 해변은 걸어 보고 싶어.",List.of(self("등대와 해변을 돌아볼 거야.")),
            "등대와 해변을 모두 방문하기로 한 네 계획은 그대로군.","등대는 그만두고 해변을 걸어 보고 싶은 거군."),
        new Case("motive-open-paraphrase","내가 요즘 나무 씨앗만 모으는 이유를 짐작하겠어?",List.of(self("작은 정원을 한번 가꿔 보고 싶네.")),
            "정원사가 되기로 이미 결심했기 때문이지.","작은 정원을 가꿔 보고 싶어서 준비하는 걸까?"),
        new Case("new-offer-not-old-promise","함께 잠깐 쉬는 건 어때?",List.of(),
            "네가 아까 함께 쉬기로 약속했었지.","좋아, 잠깐 같이 쉬자.")
    );
    // Literal v27 generated errors and false rejections, frozen before v29 results are inspected.
    static final List<Case> V27_GENERATED=List.of(
        new Case("v27-foreign", "내 계획이 뭔지 짐작 가?",List.of(other("나는 루브라스를 만나러 갈 거야.")),
            "글쎄, 네가 루브라스를 만나러 가겠다고 한 건 들었지만... 그게 네 전체 계획의 전부인지, 아니면 그 너머에 또 다른 무언가가 있는지는 내가 다 알 순 없지. 네 마음속 깊은 곳까지는 말이야.", ""),
        new Case("v27-withdrawal", "생각이 바뀌었어. 이제 그를 찾지 않을래.",List.of(self("루브라스를 찾으러 갈 거야.")), "",
            "그렇게 결정한 거야? 네 마음이 가장 중요해. 루브라스를 찾는 일도 중요하지만, 네가 평온을 찾는 게 우선이니까. 네 선택을 존중해."),
        new Case("v27-decision", "루브라스를 만나기로 결정했어. 내일 떠날 거야.",List.of(), "",
            "루브라스를 만나러 가기로 했나. 내일 떠날 생각이라면 준비를 철저히 하는 게 좋을 거다."),
        new Case("v27-partial-withdrawal", "등대에 가려던 생각은 접었어. 대신 해변은 걸어 보고 싶어.",List.of(self("등대와 해변을 돌아볼 거야.")),
            "등대 대신 해변을 택하셨군요. 당신의 선택에 따라 걸음이 닿는 곳이 달라지겠지요. 해변을 걷는 것은 어떤 의미를 담고 있습니까?", "")
    );
    // New transfer cases fixed on 2026-10-19 before requesting any outputs for this cohort.
    static final List<Case> SOURCE_VIEW_HOLDOUT=List.of(
        new Case("two-owners-one-task", "나는 맡지 않았어. 지수가 맡겠다고 한 거야.",List.of(other("내가 화로를 고칠게.")),
            "화로를 고쳐 주기로 한 네 결정을 기억할게.","화로를 맡겠다는 건 지수의 말이고, 네가 맡은 건 아니군."),
        new Case("same-place-new-purpose", "난 약속하러 가는 게 아니라 정원을 구경하고 싶을 뿐이야.",List.of(other("나는 정원에서 서약하기로 했어.")),
            "너도 정원에서 서약하기로 했구나.","너는 서약이 아니라 정원 구경에 마음이 있군."),
        new Case("two-desires-one-commitment", "호수는 언젠가 보고 싶어. 오늘은 약속대로 공방에 갈게.",List.of(),
            "오늘 호수와 공방에 가기로 정했군.","오늘 공방에 가겠다는 거군. 호수는 나중에 보고 싶고."),
        new Case("conditional-refusal", "그 사람이 오면 나는 빠질 거야. 아직 누가 오는지는 몰라.",List.of(),
            "넌 이미 불참하기로 확정했군.","그 사람이 오느냐에 따라 네 참석도 달라지는 거군."),
        new Case("corrected-reporter", "아까 내가 간다고 전한 건 잘못됐어. 가는 건 동생이고 나는 안 가.",List.of(self("내가 항구에 갈 거야.")),
            "넌 여전히 항구에 갈 계획이군.","항구에 가는 건 동생이고 너는 안 가는 거군."),
        new Case("accept-not-execute", "이번엔 내가 안내를 맡을게. 출발은 아직이야.",List.of(other("나는 안내를 맡지 않을래.")),
            "다른 사람이 안내를 맡겠다는 거군.","이번에는 네가 안내를 맡겠다는 거군."),
        new Case("wish-confirmation-not-choice", "응, 해 보고 싶은 건 맞아. 하겠다고 약속하는 건 아니야.",List.of(self("한번 연주해 보고 싶어.")),
            "연주하겠다는 네 의지가 확인됐군.","연주해 보고 싶은 마음은 있지만 약속한 건 아니라는 거군."),
        new Case("npc-new-offer-with-other", "친구는 같이 기다리겠대. 넌 어떻게 할 거야?",List.of(),
            "내가 아까 너와 기다리기로 약속했지.","나도 잠시 기다리지.")
    );
    public static void main(String[] args)throws Exception {
        if((args.length!=3&&args.length!=4)||!args[1].equals("--execute-loopback")||!Set.of("audit","generate","audit-new","generate-new","audit-fresh","generate-fresh","audit-all","audit-transfer","generate-transfer","audit-produced","audit-focus","audit-holdout","generate-holdout","audit-v8","audit-regression","audit-remaining","audit-followup","generate-followup","audit-expanded","audit-witness","generate-witness","audit-v27","audit-source-view","generate-source-view").contains(args[2]))throw new IllegalArgumentException("OUTPUT --execute-loopback audit|generate[-new|-fresh|-transfer|-all|-produced|-focus|-holdout|-witness|-source-view] [baseline|warm|guarded]");
        String persona=args.length==4?args[3]:"baseline";
        if(!Set.of("baseline","warm","guarded").contains(persona))throw new IllegalArgumentException("persona fixture");
        List<Case> cases=args[2].endsWith("-witness")?WITNESS_HOLDOUT:args[2].endsWith("-followup")?FOLLOWUP_HOLDOUT:args[2].equals("audit-v8")?V8_FAILURES:args[2].endsWith("-holdout")?ATOMIC_HOLDOUT:args[2].endsWith("-produced")?PRODUCED_FAILURES:args[2].endsWith("-transfer")?TRANSFER_CASES:args[2].endsWith("-fresh")?FRESH_CASES:args[2].endsWith("-new")?NEW_CASES:CASES;
        if(args[2].equals("audit-v27")) cases=V27_GENERATED;
        if(args[2].endsWith("-source-view")) cases=SOURCE_VIEW_HOLDOUT;
        if(Set.of("audit-all","audit-regression").contains(args[2])){var all=new ArrayList<>(CASES);all.addAll(NEW_CASES);all.addAll(FRESH_CASES);all.addAll(SCOPE_CASES);cases=List.copyOf(all);}
        if(args[2].equals("audit-regression")){var expanded=new ArrayList<>(cases);expanded.addAll(PRODUCED_FAILURES);expanded.addAll(V8_FAILURES);cases=List.copyOf(expanded);}
        if(args[2].equals("audit-expanded")) {
            var expanded=new ArrayList<>(CASES);expanded.addAll(NEW_CASES);expanded.addAll(FRESH_CASES);expanded.addAll(SCOPE_CASES);
            expanded.addAll(PRODUCED_FAILURES);expanded.addAll(V8_FAILURES);expanded.addAll(FOLLOWUP_HOLDOUT);expanded.addAll(V18_WITHDRAWAL);
            cases=List.copyOf(expanded);
        }
        if(args[2].equals("audit-remaining")){
            var remaining=new ArrayList<>(V8_FAILURES);
            CASES.stream().filter(c->c.id().equals("wish")).forEach(remaining::add);
            NEW_CASES.stream().filter(c->c.id().equals("conditional-choice")).forEach(remaining::add);
            FRESH_CASES.stream().filter(c->Set.of("other-desire","npc-refusal").contains(c.id())).forEach(remaining::add);
            SCOPE_CASES.stream().filter(c->c.id().equals("secondary-new-intention")).forEach(remaining::add);
            PRODUCED_FAILURES.stream().filter(c->Set.of("long-foreign-presupposition","confirmed-wish-without-decision-word").contains(c.id())).forEach(remaining::add);
            cases=List.copyOf(remaining);
        }
        if(args[2].equals("audit-focus")){var focused=new ArrayList<>(PRODUCED_FAILURES);NEW_CASES.stream().filter(c->c.id().equals("tentative-inference")).forEach(focused::add);FRESH_CASES.stream().filter(c->Set.of("npc-refusal","other-desire").contains(c.id())).forEach(focused::add);SCOPE_CASES.stream().filter(c->c.id().equals("secondary-new-intention")).forEach(focused::add);cases=List.copyOf(focused);}
        var root=Files.createDirectories(Path.of(args[0]).toAbsolutePath());net.neoforged.fml.loading.FMLPaths.loadAbsolutePaths(root);
        save(root.resolve("manifest.json"),Map.of("cases",cases,"model","gemma4:12b","persona",persona,"stage",args[2],
                "independentFrameEvaluation",Boolean.getBoolean("mythai.eval.independentFrames"),
                "protocols",Boolean.getBoolean("mythai.eval.independentFrames")?List.of(RoomSourceReading.MARKER,RoomDraftReading.MARKER,RoomEvidenceAudit.MARKER,RoomClaimWitnessAudit.MARKER):List.of(RoomEvidenceAudit.MARKER,BaselineRoomClaimWitnessAudit.MARKER),
                "scope","Synthetic fixed sources; no game, deployment or operating configuration change"));
        var rows=new ArrayList<Map<String,Object>>();
        try(var llm=new LocalOllamaClient(Boolean.getBoolean("mythai.eval.independentFrames"))) {
            for(var c:cases){
                var r=request(c);
                if(!persona.equals("baseline"))r=new Request(r.roomId(),r.revision(),r.turnId(),r.playerId(),r.playerName(),r.godIds(),r.speakerGodId(),
                        r.currentText(),r.history(),r.readOnly(),r.recording(),r.publicRoom(),List.of(new GodState(r.speakerGodId(),persona.equals("warm")?"R_TRUSTED":"R_NEUTRAL","E_CALM",r.speakerState().gameContext(),null)),r.secondary(),r.audiencePlayerIds());
                var evidence=PlayerIntentModelComparison.messages(r,persona);
                if(args[2].startsWith("audit"))for(boolean bad:List.of(true,false)) {
                    String text=bad?c.bad():c.good();if(text.isEmpty())continue;
                    var row=new LinkedHashMap<String,Object>();row.put("case",c);row.put("evidence",evidence);row.put("expectedPass",!bad);row.put("draft",draft(text));
                    try{var result=DialogueGroundingLiveEvaluation.review(llm,evidence,draft(text),settings(),row,"review");row.put("match",result.pass()==!bad);}catch(Exception failure){row.put("failure",failure.toString());}
                    rows.add(row);save(root.resolve(c.id()+(bad?"-bad":"-good")+".json"),row);System.out.println("AUDIT "+c.id()+" bad="+bad+" match="+row.get("match"));
                } else {
                    var row=new LinkedHashMap<String,Object>();row.put("case",c);row.put("evidence",evidence);
                    try {
                        var attempt=RoomDialogueGrounding.Attempt.initial(evidence).withoutUnderstanding();
                        var d=DialogueGroundingLiveEvaluation.generate(llm,attempt.generationMessages(),settings(),row,"generationWire");row.put("draft",d);
                        String structural=RoomPersonaPrompt.validationIssue(r,d);row.put("structural",structural);
                        var review=structural.isEmpty()?DialogueGroundingLiveEvaluation.review(llm,attempt.evidenceMessages(),d,settings(),row,"review"):null;
                        if(!structural.isEmpty()||!review.pass()) {
                            attempt=structural.isEmpty()?attempt.repair(d,review):attempt.repairStructure(d,structural);
                            d=DialogueGroundingLiveEvaluation.generate(llm,attempt.generationMessages(),settings(),row,"repairWire");row.put("repair",d);
                            structural=RoomPersonaPrompt.validationIssue(r,d);row.put("finalStructural",structural);
                            review=structural.isEmpty()?DialogueGroundingLiveEvaluation.review(llm,attempt.evidenceMessages(),d,settings(),row,"finalReview"):null;
                        }
                        if(structural.isEmpty()&&review!=null&&review.pass())row.put("publishable",d);
                    }catch(Exception failure){row.put("failure",failure.toString());}
                    rows.add(row);save(root.resolve(c.id()+".json"),row);System.out.println("GENERATE "+c.id()+" publishable="+row.containsKey("publishable"));
                }
            }
        }
        boolean audit=args[2].startsWith("audit");
        var summary=summarize(rows,audit);save(root.resolve("summary.json"),summary);
        System.out.println("SUMMARY "+JSON.toJson(summary));
        if(audit&&rows.stream().anyMatch(row->!Boolean.TRUE.equals(row.get("match"))))throw new AssertionError("Semantic audit gate failed; immutable details and summary saved");
        if(!audit&&rows.stream().anyMatch(row->!row.containsKey("publishable")))throw new AssertionError("Generation availability gate failed; semantic assessment still requires manual review");
    }
    private static Map<String,Object> summarize(List<Map<String,Object>> rows,boolean audit) {
        var summary=new LinkedHashMap<String,Object>();summary.put("rows",rows.size());
        if(audit) {
            summary.put("correctVerdicts",rows.stream().filter(row->Boolean.TRUE.equals(row.get("match"))).count());
            summary.put("falseAccepts",rows.stream().filter(row->Boolean.FALSE.equals(row.get("match"))&&Boolean.FALSE.equals(row.get("expectedPass"))).count());
            summary.put("falseRejects",rows.stream().filter(row->Boolean.FALSE.equals(row.get("match"))&&Boolean.TRUE.equals(row.get("expectedPass"))).count());
        }
        summary.put("failures",rows.stream().filter(row->row.containsKey("failure")).count());
        summary.put("publishableGenerations",rows.stream().filter(row->row.containsKey("publishable")).count());
        long requests=0,inputTokens=0,outputTokens=0,totalNanos=0;
        var models=new TreeSet<String>();
        for(var row:rows)for(var entry:row.entrySet())if(entry.getKey().endsWith("Wire")) {
            for(var raw:JSON.toJsonTree(entry.getValue()).getAsJsonArray()) {
                var event=raw.getAsJsonObject();
                if(event.has("request")){requests++;models.add(JsonParser.parseString(event.get("request").getAsString()).getAsJsonObject().get("model").getAsString());}
                if(event.has("response")) {
                    var response=JsonParser.parseString(event.get("response").getAsString()).getAsJsonObject();
                    if(response.has("prompt_eval_count"))inputTokens+=response.get("prompt_eval_count").getAsLong();
                    if(response.has("eval_count"))outputTokens+=response.get("eval_count").getAsLong();
                    if(response.has("total_duration"))totalNanos+=response.get("total_duration").getAsLong();
                }
            }
        }
        summary.put("modelRequests",requests);summary.put("actualModels",models);summary.put("inputTokens",inputTokens);summary.put("outputTokens",outputTokens);
        summary.put("totalModelMs",totalNanos/1_000_000);summary.put("meanModelMsPerRow",rows.isEmpty()?0:totalNanos/1_000_000.0/rows.size());
        summary.put("scope",audit?"Fixed synthetic verdict gate; not general accuracy":"Publishable means structural/review gates only; semantic/naturalness assessment remains manual");
        return summary;
    }
    private static void save(Path path,Object value)throws Exception {Files.writeString(path,JSON.toJson(value),java.nio.charset.StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);}
}
