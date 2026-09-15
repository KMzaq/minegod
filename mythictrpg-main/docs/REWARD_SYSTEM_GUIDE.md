# MythicTRPG 통합 퀘스트 보상 가이드

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

| 타입 | JSON | 제한 |
|---|---|---|
| 아이템 | `{"type":"item","itemId":"minecraft:emerald","count":3}` | 등록 아이템, 수량 1–64 |
| 호감도 | `{"type":"affinity","amount":75}` | 퀘스트 보상 +1–+200, 최종값 -1000–1000 |
| 가호 | `{"type":"blessing","effectId":"minecraft:speed","durationTicks":1200,"amplifier":1}` | 등록된 Minecraft 효과, 20–72000틱, 증폭 0–4 |
| 칭호 | `{"type":"title","titleId":"mythictrpg:swift","displayName":"신속한 자"}` | 영구 해금 ID, 표시명 1–80자 |
| 화폐 | `{"type":"currency","amount":100}` | 플레이어 개인 골드, 최종 잔액 0–9,000,000,000 |
| 상점 상품 해금 | `{"type":"unlock_shop_product","shopId":"mythictrpg:witch_buy","productId":"mythictrpg:witch_healing_potion"}` | 등록 상품을 월드 전역으로 해금 |

AI의 일반 관계 변경 액션은 기존 규칙대로 1회 -50–+50이다. 퀘스트의 호감도 보상만 한 항목당
최대 +200을 허용한다. 가호는 현재 효과보다 약하거나 남은 시간이 짧으면 중복으로 덮어쓰지 않는다.
칭호는 플레이어 프로필의 `unlockedTitles`에 영구 저장되며, 장착·표시 UI는 별도 표현 계층이다.

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

1. 퀘스트 완료가 권위 있게 확정된다.
2. 자동 보상은 서버가 한 번만 지급한다.
3. 선택지 2–6개를 클라이언트 화면에 표시한다.
4. 서버는 claim ID, 플레이어 UUID, 퀘스트 ID, 전체 선택지를 `mythictrpg_reward_claims`에 저장한다.
5. 클라이언트가 보낸 선택이 해당 claim과 선택지에 실제로 속하는지 서버가 다시 검증한다.
6. 선택 결과를 먼저 영구 기록한 뒤 해당 묶음을 한 번만 지급한다.

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
