# MythicTRPG 통합 퀘스트 보상 가이드

> 2026-10-21 `behaivior` 개발 후속: Story의 `GRANT_REWARDS`도 기존 보상 codec·claim 지급을 사용한다. [직접 사건 보상과 불명확한 영수증 처리](CONTENT_SYSTEMS_GUIDE.md)를 따른다. 정식 보상표·귀속장비 재지급·운영 서버는 이번 작업에서 변경하지 않았다.

> **2026-10-21 가호·행운 후속:** 명시적으로 작성한 `adaptive_blessing`과 `lucky_item`만 상태별 후보 선택·확률 보상을 사용한다. 저장은 기존 claim v6에 통합하며 실제 획득 경로를 함께 보존한다. [v2 정의·정책·전투력 가이드](BLESSING_REWARD_POWER_V2.md)를 함께 읽는다. 실제 신 등급이나 운영 보상 풀을 자동 배정하지 않는다.

> **2026-10-02 HanesTest에서 추가한 영구 보유:** `blessing`의 `durationTicks: -1`은 무한 효과와 별도 영구 보유권을 지급한다. 우유/효과 해제로 버프를 지운 상태는 재접속에도 유지하며 `/mythblessing reapply` 또는 사망 후 부활로 다시 적용한다. 동일 효과는 보유 최고 단계 하나로 적용하고 출처는 각각 보존한다. 현재 저장 v6·부활·가호 고유 점수와 활성 효과 분리는 [영구가호 가이드](PERMANENT_BLESSINGS.md)를 따른다. 기존 임시가호를 자동으로 영구가호로 바꾸거나 기존 보상표를 수정하지 않았다.

> **2026-09-20 주시 보상 추가(게임 1.0.8, 미배포):** `{"type":"watch","godId":"mythictrpg:fortuna","displayName":"포르투나"}`는 등록된 신의 개인 주시 획득 보상이다. 실제 자동 지급/선택 claim 처리 때만 획득하고, 같은 신 중복은 최초 근거를 유지한다. `/mythquest watches`로 본인 획득을 조회한다. 관측은 별도 정책/차폐를 통과해야 한다. AI_ACTION으로 직접 지급하지 못하며 기존 퀘스트 정의에는 추가하지 않았다. 저장 버전/예시/실행 한계는 [최신 구현 기록](../../docs/MEMORY_POLICY_IMPLEMENTATION_20260920.md)을 따른다.

> 2026-09-20: [참여 유형](QUEST_PARTICIPATION_GUIDE.md)이 있는 퀘스트는 종료 시 기존 claim 저장소에 참가자 전체의 지급권을 검증·생성한다. 오프라인 지급은 재접속, 일시 지급 실패는 `/mythquest rewards`로 재시도한다. 랭킹은 작성된 순위·점수 구간으로 보상 단계를 선택하며 FTB 보상 버튼과 중복 지급하지 않는다. 별도 보상 DB는 만들지 않았다.

## 소유권과 적용 범위

보상 판정·저장·지급은 MythicTRPG 서버가 소유한다. AI는 등록된 퀘스트나 보상을 제안할 수
있지만 보상 타입, 수량, 등급, 선택 결과를 직접 확정하지 않는다. 이번 구현에서는 요청에 따라
캐릭터별 보상표를 만들지 않았으며, 구성 계층은 다음 두 개뿐이다.

```text
신 기본 보상표
        +
퀘스트 직접 보상(ADD) 또는 퀘스트 직접 보상으로 대체(REPLACE)
```

## 신 기본 보상표

경로는 `data/<namespace>/mythictrpg/reward_tables/*.json`이다. 신규 표는 schemaVersion 2를
사용한다. 한 신에게 `defaultForGod: true`인 표는 하나만 허용되며, 등급은 1부터 연속되어야 한다.

```json
{
  "schemaVersion": 2,
  "godId": "mythictrpg:fortuna",
  "defaultForGod": true,
  "tiers": [
    {
      "tier": 1,
      "rewards": [
        {"type": "item", "itemId": "minecraft:gold_ingot", "count": 1},
        {"type": "affinity", "amount": 10}
      ]
    }
  ]
}
```

기존 schemaVersion 1의 `npcId`·아이템 전용 표도 호환 목적으로 읽지만, 새 콘텐츠에는
schemaVersion 2와 `godId`를 사용한다.

## 보상 타입

모든 보상표, 퀘스트 직접 보상, 선택지에서 같은 타입을 사용한다.

2026-10-22 추가: 공통 무기 외형을 재사용하는 장비는 `{"type":"equipment","definitionId":"reuse_demo:steel_wave"}`로 참조한다. 정의에서 실제 이름·공격 속성·인챈트·무기 스킬과 전투력 입력을 함께 생성한다. 온라인 claim은 지급 전에, 오프라인 지급 대기는 저장 전에 구체적인 아이템 구성요소를 고정한다. 이후 정의 변경으로 이미 약속한 보상을 바꾸지 않는다. 작성 규격·예시·기존 장비 이력 호환 주의는 [외형 재사용 안내](EQUIPMENT_APPEARANCES.md)를 따른다.

| 타입 | JSON | 제한 |
|---|---|---|
| 아이템 | `{"type":"item","itemId":"minecraft:emerald","count":3}` | 등록 아이템, 수량 1–64 |
| 호감도 | `{"type":"affinity","amount":75}` | 퀘스트 보상 +1–+200, 최종값 -1000–1000 |
| 고정 가호 | `{"type":"blessing","effectId":"minecraft:speed","durationTicks":1200,"amplifier":1}` | 등록 효과, 20–72000틱 또는 영구 `-1`. 저항 증폭 0–3, 기타 0–16. 영구 즉시효과 거부 |
| 상태별 가호 | `{"type":"adaptive_blessing","definitionId":"example:sample_blessing"}` | 같은 신의 작성된 정의·신 등급·허용 효과·단계 상한 필요. 첫 수령 시 확정 |
| 행운 아이템 | `{"type":"lucky_item","poolId":"example:raid_materials"}` | 작성된 아이템 풀 필요. 첫 수령 시 후보·수량·components·행운 단계를 확정 |
| 칭호 | `{"type":"title","titleId":"mythictrpg:swift","displayName":"신속한 자"}` | 영구 해금 ID, 표시명 1–80자 |
| 화폐 | `{"type":"currency","amount":100}` | 플레이어 개인 골드, 최종 잔액 0–9,000,000,000 |
| 상점 상품 해금 | `{"type":"unlock_shop_product","shopId":"mythictrpg:witch_buy","productId":"mythictrpg:witch_healing_potion"}` | 등록 상품을 월드 전역으로 해금 |

AI의 일반 관계 변경 액션은 기존 규칙대로 1회 -50–+50이다. 퀘스트의 호감도 보상만 한 항목당
최대 +200을 허용한다. 같은 효과의 임시가호는 현재 활성 단계를 더하지 않는다. 더 약하지만 오래가는 효과는 자신의 지속 시간이 남아 있으면 숨은 하위 효과로 보존한다.
영구가호는 기존 게임 보상 저장소에 출처별 보유를 저장하고 동일 효과 최고 단계만 Minecraft 무한 효과로 적용한다.
더 강한 임시 포션은 끝날 때 영구가호 단계로 복귀한다. 우유로 지웠으면 같은 효과를 추가 보상으로 받아도 제거 상태를 유지한다.
칭호는 플레이어 프로필의 `unlockedTitles`에 영구 저장되며, 장착·표시 UI는 별도 표현 계층이다.

## 명시적 상태별 보상의 검증과 저장

`adaptive_blessing` 정의와 정책은 `mythictrpg/blessings`, `lucky_item` 풀과 계수는 `mythictrpg/luck` 데이터팩 경로에 작성한다. 신의 `god_rank` 6종은 보상표 `tier` 1~100이나 장비 등급과 별개다. 정의가 지급 신에게 속하는지, 신 등급·신별 정책·등급별 상한이 작성됐는지를 퀘스트 보상 구성, NPC 지급, 오프라인 지급 대기 생성과 Story 효과 검증에서 확인한다. 실제 플레이어 상태에 맞는 후보 선택은 첫 수령 시 수행한다. 정적 참조 오류와 `fallback:"none"`의 유효 후보 없음에 따른 수령 보류는 구분한다.

Story JSON을 읽는 준비 단계에서는 보상 구조·기본 등록 아이템/효과만 검사한다. 다른 reload listener의 아직 적용되지 않은 정의 캐시를 요구하지 않는다. 실행 전에는 위의 현재 정의·소유 신 검증을 다시 수행하므로 잘못된 참조가 실제 지급 권한을 얻지 않는다.

오프라인 claim은 미확정 참조를 보관하며 첫 수령 시 자동 보상과 모든 선택지의 결과를 기존 claim에 한 번 저장한다. 선택하지 않은 가호는 아직 보유권이 아니다. 이후 선택·재접속·재시도는 저장된 결과를 사용한다. `resolved_blessing`과 `resolved_lucky_item`은 내부 저장 타입으로, 작성 JSON에 직접 넣어 확정 결과를 주입할 수 없다. 같은 가호 정의를 자동 보상과 선택 보상에 함께 넣거나 같은 묶음 안에서 중복하면 거부하며, 서로 배타적인 선택지 사이의 재사용은 허용한다.

v5에서 추가된 명명 가호 보유와 완료된 확률 보상 source는 현재 v6에도 유지되며 완료 claim 정리 후에도 남는다. 같은 source를 다시 열어 가호·아이템을 중복 수령하지 않는다. 동적 보상이 포함된 NPC 표는 플레이어·표·tier마다 한 번 처리하고, 재호출은 이미 수령했다고 알린다. 순수 고정 보상표의 기존 반복 정책은 유지한다. receipt 기록과 플레이어 인벤토리 파일은 디스크 단위 원자 트랜잭션이 아니므로 비정상 종료까지 포함한 무손실 지급을 보장하지 않는다.

v6의 `origin`은 작성자가 임의 지정하는 보상 JSON 필드가 아니라 권위 지급 경로가 정하는 값이다. 퀘스트는 레이드 성공으로 완료돼도 `QUEST`, 실제 RaidRuntime의 직접 보상만 `RAID_DIRECT`, NPC 직접 지급은 `NPC_DIRECT`, Story 직접 지급은 `STORY_DIRECT`다. 출처가 불명확한 이전 기록은 `LEGACY_UNSPECIFIED`로 보존한다. 가호 없는 루트의 예외는 `RAID_DIRECT`만 인정하므로 이전 기록을 이름이나 source ID로 추측해 레이드 보상으로 바꾸지 않는다. [Story 가호 조건](STORY_EVENT_ENGINE_GUIDE.md#51-레이드-직접-보상을-제외한-가호-획득-조건)에서 양수·부정 조건을 작성할 수 있다.

선택창은 확정 효과·단계·고유 점수 또는 선택된 아이템·수량을 표시한다. 전체 전투력 전후 비교 UI는 구현 범위에 포함되지 않는다. 상세 계수·작성 예시와 운영 전 기획 항목은 [v2 가이드](BLESSING_REWARD_POWER_V2.md)를 따른다.

## 과거 설계 기록: ‘OOO의 주시’ 보상

> 아래는 2026-09-20 구현 전 설계 기록이다. 현재는 문서 상단에 설명한 `watch` 보상 타입이 구현되어 있으며, 아래의 ‘미지원’ 설명을 현재 상태로 읽지 않는다.

2026-09-20 사용자가 메인 퀘스트 완료 자동 효과 대신 **퀘스트 보상으로 신별 주시를 획득하고 플레이어에 기록**하도록 변경했다. 단일 기준은 [기록·주시·기억 정책](../../docs/ACTION_RECORDING_POLICY_20260917.md)의 4절이다.

- 기존 자동/선택 보상·개인 claim·오프라인/재시도 소유권 안에서 실제 지급 근거와 신별 획득 상태를 연결하는 후속 설계다. 보상 지급권 예약과 실제 획득/관측 시작을 구분하며 메인/사이드나 공동 완료만으로 지급하지 않는다.
- 칭호 `title`의 표시명만 ‘OOO의 주시’로 바꾸거나 포션 `blessing`을 쓰는 것으로 대체하지 않는다. 기존 God ID를 사용하고 관측 상태/증명과 연결한다. 같은 신의 중복 지급으로 효과·관측을 중첩하지 않는다.
- 차폐 공간에서는 획득 기록을 유지하고 관측을 차단한다. 상세 권한/범위·표현/UI는 별도 계층이며 획득한 신이 모든 정보를 알게 되는 것은 아니다.
- 현재 `RewardEntry/RewardEntryCodec`에는 이 타입이 없다. 실행 가능한 JSON 예시/타입명으로 제시하지 않으며 구현 시 codec·검증·저장/replay·claim 실행·AI 보상 설명·수신 UI·주시 영속성 연결을 함께 검사한다. 기존 보상 항목과 사용자 퀘스트 정의는 이번에 변경하지 않았다.

## 퀘스트 직접 보상

FTB 바인딩의 선택적 `rewards` 필드에 작성한다.

### ADD

신 기본 표의 `baseTier` 보상에 퀘스트 직접 보상을 더한다.

```json
"rewards": {
  "mode": "ADD",
  "baseTier": 2,
  "selectionTitle": "포르투나의 보상을 선택하세요",
  "automaticRewards": [
    {"type": "affinity", "amount": 30}
  ],
  "choices": [
    {
      "optionId": "mythictrpg:fortuna/wealth",
      "displayName": "행운의 재화",
      "rewards": [
        {"type": "item", "itemId": "minecraft:emerald", "count": 3}
      ]
    },
    {
      "optionId": "mythictrpg:fortuna/favor",
      "displayName": "포르투나의 총애",
      "rewards": [
        {"type": "affinity", "amount": 75},
        {"type": "blessing", "effectId": "minecraft:luck", "durationTicks": 2400,
         "amplifier": 0}
      ]
    }
  ]
}
```

### REPLACE

신 기본 표를 사용하지 않고 퀘스트 직접 보상만 지급한다. `baseTier`는 생략하거나 0이어야 한다.

```json
"rewards": {
  "mode": "REPLACE",
  "automaticRewards": [
    {"type": "title", "titleId": "mythictrpg:night_witness", "displayName": "밤의 목격자"}
  ],
  "choices": []
}
```

`automaticRewards`는 0–32개다. 선택형 보상을 사용하면 `choices`는 2–6개이고 각 선택지는
1–16개 보상을 혼합할 수 있다. 선택지가 없다면 `choices`를 생략해도 된다.

평가형 퀘스트에서 ADD는 평가 점수로 결정된 표·등급을 기본 보상으로 사용하고 직접 보상을 더한다.
REPLACE는 평가 점수 등급을 지급하지 않고 직접 보상으로 대체한다. 보상표·효과·아이템·등급이나
선택지 정의가 잘못되면 서버는 보상 없는 완료를 만들지 않고 퀘스트 완료부터 거부한다.

## 선택 보상 수명주기

1. 퀘스트 완료 전에 보상 구성·참조·아이템 데이터와 저장 가능 여부를 검증한다.
2. 서버는 claim ID, 플레이어 UUID, source ID, 자동 보상과 전체 선택지를 `mythictrpg_reward_claims`에 저장한다. 오프라인 참가자의 claim은 수령을 기다린다.
3. 명시적 동적 보상은 첫 수령 처리에서 모든 결과를 확정·저장한다. 기존 고정 보상은 바꾸지 않는다.
4. 자동 지급 receipt·보유권을 기록한 뒤 자동 보상을 지급하고, 남은 선택지 2–6개를 클라이언트 화면에 표시한다.
5. 클라이언트가 보낸 선택이 해당 개인 claim과 저장된 선택지에 속하는지, 현재 실제 지급이 가능한지 서버가 다시 검증한다.
6. 선택 receipt·보유권을 먼저 기록한 뒤 해당 묶음을 한 번만 지급한다.

선택에는 만료 시간이 없다. ESC로 닫아도 권리는 사라지지 않으며 다음 접속 때 다시 표시된다.
이미 선택한 claim, 다른 플레이어의 claim, 목록에 없는 option ID는 거부된다. 여러 선택권이 있으면
클라이언트가 오래된 순서대로 화면을 하나씩 표시한다.

## 개발 검증

실제 클라이언트에서 다음 명령으로 아이템, 호감도+가호, 영구 칭호의 3개 선택지를 띄울 수 있다.

```text
/mythadmin reward test-choice <player> <godId>
```

검증 절차:

1. 선택 화면에서 각 보상명과 요약이 보이는지 확인한다.
2. ESC로 닫고 재접속하여 같은 선택권이 다시 보이는지 확인한다.
3. 하나를 선택해 아이템/효과/호감도/칭호 중 해당 결과가 적용되는지 확인한다.
4. 같은 네트워크 요청을 재전송하거나 재접속해도 두 번 지급되지 않는지 확인한다.

자동 검증은 `runGameTestServer`의 `RewardSystemGameTests`에 포함된다. 상점·화폐 상세 규격은
`SHOP_ECONOMY_GUIDE.md`를 따른다. 2026-09-02 기준 전체 190개 필수 GameTest가 통과했다.
