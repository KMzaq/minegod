# 퀘스트 참여 유형과 공동 대화

> 후속 게임 1.0.8/AI 0.1.9는 이 참여 유형과 정산 소유권을 유지하고, 실제 지급 claim의 `watch` 보상·개인 획득 및 중요 상태 이력을 연결했다. 공동 완료/랭킹 제출 자체가 주시 지급은 아니다. 채굴형 보상 목표는 출처가 인정된 성공 실적을 사용한다. [후속 구현·검증](../../docs/MEMORY_POLICY_IMPLEMENTATION_20260920.md)을 함께 확인한다. 미배포 상태는 유지한다.

2026-09-20 개발본: 게임 1.0.7 / AI 0.1.8. 운영 서버에는 미배포.

## 적용 범위

`FtbQuestBinding`에 선택적 `participation`을 추가했다. **참여 유형**, **목표**, **완료 확인 방식**은 별도 축이다.

| 참여 유형 | 수주/종료 |
|---|---|
| `SOLO` | 대화 참가자 중 수주 조건을 만족하는 한 명을 선택하고 그 사람의 동의를 받는다. 같은 논리 퀘스트의 복수 개인 수주는 불가. |
| `GROUP` | 현재 대화 참가자 모두의 네/아니요를 기다린 뒤 네라고 한 사람만 확정한다. 한 명도 가능. 확정 참가자 **각자** 목표 달성·제출을 마쳐야 모두 완료된다. |
| `COMPETITIVE` | 같은 동의 절차로 참가자를 확정한다. 서버가 먼저 유효하다고 판정한 최종 제출 한 명만 완료·보상. 같은 틱이어도 서버 처리 순서로 확정한다. |
| `RANKING` | 같은 동의 절차로 참가자를 확정하고 서버 점수로 순위/보상 단계를 결정한다. `TIME_LIMIT` 또는 `ALL_SUBMITTED`로 종료한다. |

기존 `completionMode`(AUTO/플레이어 귀환/NPC 방문), `narrativeRole`(입문 메인/메인/사이드), 평가, 재촉과 보상 설정은 별도로 유지한다.
보상 정책 원본은 [보상 가이드](REWARD_SYSTEM_GUIDE.md), 기존 바인딩은 [FTB 가이드](FTB_QUESTS_INTEGRATION_GUIDE.md)를 따른다.

**호환성:** `participation`이 없는 바인딩은 기존 전역 최초 완료/FTB 목표 경로 그대로다. 기존 퀘스트를 자동으로 새 유형으로 이관하지 않았다.
기존 `GeneratedQuestTemplate`/`generated_quest_offer`의 즉석 SIDE 생성 경로도 기존 개인형 그대로이며, 이 경로의 다인 유형 생성까지 완료한 것은 아니다.
등록된 바인딩을 AI가 `quest_offer`로 제안하는 경로에는 새 유형이 적용된다.

## 직접 대화 참여와 동의

- 기존 명령/조우로 NPC 대화를 시작한다. 다른 플레이어는 `/mythtalk join <대화 중인 플레이어>`로 직접 합류한다.
- 서버가 같은 차원, 대상 플레이어와 16블록 이내, 활성 NPC 대화, 최대 16명을 확인한다. 근처에 서 있기만 한 사람은 포함하지 않는다.
- 현재 신은 물리 NPC 엔티티가 아니라 게임 상호작용의 God ID다. 거리는 그 NPC와 대화 중인 플레이어 기준이며, NPC 엔티티 거리 감지의 구현을 주장하지 않는다.
- 다른 신과 대화 중이면 먼저 `/mythtalk leave`를 사용한다. 기존 HUD 끄기/켜기는 일시정지/재개이며 명시적 나가기와 구별한다.
- 합류 시 새로운 공유 세션을 만들고 기존 비동기 응답을 무효화한다. 합류 이전의 개인 대화 히스토리는 공유하지 않는다. 합류 후 플레이어 발언과 NPC 응답을 같은 세션 청중에 전달한다.
- 질문에는 `네`/`예`/`yes`, `아니요`/`아니오`/`no` 또는 클릭 버튼으로 답한다. 클릭 명령은 `/mythquest answer <offer UUID> yes|no`다.
- 전원 명시적 응답 전에는 수주/아이템 소비/보상이 없다. 수주 조건 미충족자는 네를 받을 수 없고 아니요 응답을 기다린다. 전원 거절이면 미수주다.
- 모집 도중 이탈/로그아웃/세션 변경/바인딩 reload는 질문을 취소한다. 지난 질문의 답을 새 수주에 재사용하지 않는다. 자동 동의·자동 타임아웃은 없다.
- 수주 확정 뒤에는 roster가 고정된다. 대화에서 나가거나 로그아웃해도 퀘스트 참가자는 빠지지 않는다. `GROUP`/`ALL_SUBMITTED`는 그 사람의 복귀·제출을 기다린다.

AI `quest_offer`의 `recipient_id`는 선택적 플레이어 UUID다. 서버가 현재 대화 청중인지와 개인 수주 조건을 다시 검사한다.
SOLO는 한 명을 선택한다. 나머지 유형에서도 발화자가 수주 조건을 충족하지 않으면 조건을 충족하는 참가자를 지정해야 한다.
`WAITING_FOR_PARTICIPANTS`는 **질문을 열었다**는 결과이며 수주 성공으로 말하지 않는다.

## 목표 작성

바인딩 JSON 루트에 다음을 추가한다. 나머지 필드와 FTB 본체/숨김 마커 ID는 기존 규칙을 유지한다.

```json
{
  "participation": {
    "type": "GROUP",
    "objectives": [
      {"kind": "ITEM_SUBMISSION", "subject": "minecraft:heart_of_the_sea", "count": 2}
    ]
  },
  "rewards": {
    "mode": "REPLACE",
    "automaticRewards": [{"type": "affinity", "amount": 20}]
  }
}
```

이 조각은 완전한 바인딩이 아니다. `schemaVersion`, `questId`, FTB ID, 완료 방식 등의 필수 필드를 함께 작성한다.
운영 중/이미 완료한 퀘스트 대신 새 논리 ID로 시험한다.

| kind | 판정 |
|---|---|
| `ITEM_SUBMISSION` | `/mythquest submit <questId>`로 인벤토리에서 아직 필요한 수량만 실제 차감. 다른 사람의 아이템·상자는 자동 소비하지 않는다. |
| `ITEM_DONATION` | 같은 명령으로 해당 아이템을 반복 기부. `count`는 최소 수량이며 목표당 누적 상한은 1,000,000개. NPC 최종 확인 시 수량을 동결한다. |
| `OBSERVATION` | 등록된 서버 관찰의 행위자/타입/대상을 대조해 개인별 누적. AI 주장이나 다른 팀원의 행동은 실적이 아니다. |
| `EVALUATION` | `count: 1`; 기존 `evaluation` 정책과 서버 `QuestEvaluationGateway`의 통과 판정이 필요하다. 플레이어 명령으로 점수를 정하지 않는다. |

목표는 1~16개, `count`는 정수 1~1,000,000이다. 아이템 ID는 실제 등록 ID여야 한다. 현재 이 경로에는 아이템 태그/NBT 매칭이 없다.
서로 같은 아이템의 목표를 중복 작성하면 앞 목표부터 소비되므로 별도 의도가 없다면 한 목표로 합친다.

관찰 목표 예:

```json
{"kind":"OBSERVATION","observation":"mythictrpg:entity_killed","subject":"minecraft:zombie","count":10}
```

지원 관찰은 `mythictrpg:entity_killed`, `block_broken`, `mature_crop_harvested`, `animal_fed`, `animal_bred`다.
향후 인정 실적/출처 정책 전체를 구현한 것은 아니며 현재 게임 관찰 계약을 소비한다.

`AUTO`는 목표 달성 시 개인 최종 제출도 자동이다. 기부형과 평가형은 NPC 확인을 필요로 하므로 AUTO로 작성할 수 없다.
귀환형은 준 NPC 또는 `completionNpcIds`에 지정한 상대에게 다시 대화를 시작하면 확인한다.
이미 해당 NPC와 대화 중이면 `/mythquest confirm <questId>`로 확인할 수 있다. 평가형은 이 명령으로 완료하지 못한다.
평가형 미달은 기존 재도전 AI 대사를 유지하고, 합격 후에도 공동 종료 조건 전에는 `SUBMITTED`이지 완료가 아니다.

## 랭킹 설정

```json
{
  "type": "RANKING",
  "objectives": [{"kind":"ITEM_DONATION","subject":"minecraft:heart_of_the_sea","count":1}],
  "ranking": {
    "endMode": "TIME_LIMIT",
    "durationTicks": 12000,
    "rewards": [
      {"maximumRank":1,"minimumScore":5,"rewardTier":3},
      {"maximumRank":3,"minimumScore":1,"rewardTier":1}
    ]
  }
}
```

- 위 예는 `participation` 값이다. 바인딩에는 `rewards.mode: ADD`와 신 기본 보상표 또는 `evaluation.rewardTableId`가 필요하다.
- `TIME_LIMIT`은 수주 확정부터 서버 게임 틱 기준이다. 12,000틱은 정상 20 TPS에서 10분이며 서버가 꺼진 현실 시간은 세지 않는다. 전원이 빨리 제출해도 마감까지 기다린다.
- `ALL_SUBMITTED`는 `durationTicks`를 생략하거나 0으로 두고 마지막 참가자 제출 시 종료한다.
- 최종 제출은 한 번만 가능하다. 기부를 더 할 계획이면 NPC 최종 확인 전에 제출 명령으로 누적한다. 마감 시각과 같거나 이후인 제출은 거절한다.
- 점수: 평가형은 서버 평가 0~100 중 합격 점수, 기부형은 실제 누적 기부 수량 합계, 그 외는 완료한 목표 수량 합계다. 고정 수량 목표만 있으면 동점이 되므로 수량 랭킹에는 기부형을 사용한다.
- 동점은 공동 순위(예: 1, 1, 3)와 같은 보상이다. UUID 순서로 동점을 깨지 않는다.
- `maximumRank` 오름차순으로 작성한다. 순위 상한·최소 점수를 모두 만족하는 첫 구간의 `rewardTier`를 쓴다. 어느 구간에도 들지 못하면 보상 없음. 이때 퀘스트 직접 추가 보상도 지급하지 않는다.
- 평가형과 조합하면 각 랭킹 단계는 퀘스트의 `minimumRewardTier..maximumRewardTier` 범위 안이어야 한다. 테이블에 없는 단계/다른 신의 표는 거절한다.
- 미제출자는 보상/개인 완료 이력에서 제외한다. 아무도 제출하지 않은 시간 마감은 종료만 기록하고 월드 진행도·완료 사건은 발생시키지 않는다.

## 보상·FTB·저장 경계

> **9/20 후속 주시 설계 — 미구현:** [단일 정책](../../docs/ACTION_RECORDING_POLICY_20260917.md)에 따라 ‘OOO의 주시’는 퀘스트 보상으로 실제 획득한 플레이어에게 기록한다. 아래 유형별 개인 완료 명단과 주시 획득 명단은 다를 수 있다. 특히 RANKING의 유효 제출 완료자라도 보상 구간 밖이면 자동 지급하지 않으며 선택 보상도 실제 선택/지급 전에는 획득하지 않는다. 첫/메인 완료 자동 주시는 대체됐지만 이 문서의 기존 완료·정산·수주 규칙은 바꾸지 않았다. 현재 주시 보상 타입/활성화는 미구현이고 기존 퀘스트에 자동 추가하지 않았다.

새 유형에서는 MythicTRPG가 목표와 보상을 소유한다. FTB 팀 진행도는 읽지 않고 참가자마다 ID가 다른 CustomTask 미러를 생성한다.
미러에는 목표 진행과 최종 완료 1칸만 있고 직접 수령할 FTB 보상은 복사하지 않는다. FTB 같은 팀 UI에 다른 참가자의 표시가 보일 수는 있으나 실적/수주/보상을 공유하지 않는다.
기존 FTB 템플릿과 제공 팩의 generated/internal chapter가 필요하다. 원본 템플릿의 FTB 목표/보상 버튼은 새 경로에서 활성화하지 않는다.

공동 종료 시 전체 보상 내용을 검증하고 기존 `RewardClaimState`에 참가자별 지급권을 묶어서 생성한 뒤 완료한다.
선택 보상은 기존 개인 claim UI를 사용한다. 오프라인 참가자는 다음 접속 때 지급한다. 잔액 한도 등의 일시적 실패는 지급권을 유지하며 `/mythquest rewards`로 재시도한다.
완료 AI 대사에는 서버 확인 상태/개인 점수/제출까지 걸린 시간/최종 순위를 제공한다. AI를 호출할 수 없거나 실패하면 NPC HUD의 `[퀘스트가 완료되었습니다]` fallback을 유지한다.
FTB 동기화나 완료 대사를 실제 사람 화면으로 확인한 것은 아니며 아래 검증 수준을 구분한다.

`mythictrpg_quests` v1에 선택적 `participationRuns` 확장을 저장한다. 기존 v1은 읽지만 새 자료를 이전 JAR로 다시 저장하는 다운그레이드는 지원하지 않는다.
진행/참가자/제출 점수/완료/미러/지급권 상태는 재시작 후 복원한다. 모집 중 질문은 휘발성이므로 재시작 후 다시 묻는다.
잘못된 저장 자료는 read-only로 보존한다. 실행 중 participation 설정을 바꾸면 판정/정산을 보류하므로 기존 설정 복원 또는 별도 이관이 필요하다.
기존 SavedData와 claim 영수증 방식의 중복 방지를 재사용했으며 월드 여러 파일과 인벤토리를 아우르는 강제 종료 시 완전한 트랜잭션 보장은 추가하지 않았다.

## 검증과 남은 일

- 상태 머신 오프라인 검사: 동의/중복/청중/개인 진행/선착순/마감/동점/복원/잘못된 설정.
- 별도 GameTest: 실제 인벤토리 소비, 다인 동의/이탈 취소, FTB 미러, 대화 중 완료, claim 저장/중복/오프라인/일시 지급 실패. 기존 게임 회귀와 함께 실행.
- AI 오프라인 회귀: 개인 회상·세션/경험 연결과 생성된 peer 수신/늦은 응답 guard 메서드 실행(외부 환경 stub). 실제 LLM 대화 품질·다인 HUD 사용성은 아직 미확인.
- 운영 서버·클라이언트 JAR 교체, 포르투나 기존 퀘스트/월드 이관, 기존 즉석 생성 템플릿의 다인화는 하지 않았다.

재현(현재 로컬 Java 21 경로는 cached Adoptium이며 기존 Android Studio 경로가 없다):

GameTest는 FTB 시험 파일을 쓰므로 아래처럼 매번 새 디렉터리를 지정한다. 운영 서버에서 실행하지 않는다.

```powershell
$env:JAVA_HOME = 'C:\Users\ADMIN\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2'
.\mythictrpg-main\gradlew.bat -p .\mythictrpg-main questParticipationTest jar --offline --no-daemon
$questQaDirectory = 'build/quest-participation-' + [Guid]::NewGuid().ToString('N')
.\mythictrpg-main\gradlew.bat -p .\mythictrpg-main runQuestParticipationGameTestServer "-PquestTestDirectory=$questQaDirectory" --offline --no-daemon
.\mythictrpg-main\gradlew.bat -p .\mythai-ai-response questParticipationDialogueTest memoryRecallTest recallStageTest experienceDialogueTest jar --offline --no-daemon '-Dorg.gradle.java.home=C:/Users/ADMIN/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2'
```
