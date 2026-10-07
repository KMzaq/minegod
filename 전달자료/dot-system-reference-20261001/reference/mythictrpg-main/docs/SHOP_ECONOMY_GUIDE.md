# MythicTRPG 상점·경제 시스템 가이드

## 확정 모델

- 화폐 표시명은 `골드`, 신규 플레이어 잔액은 0, 최대 잔액은 9,000,000,000이다.
- 잔액은 플레이어 UUID별 `mythictrpg_currency` SavedData에 저장한다.
- 상점 목록과 가격은 Datapack 정의이며 재고는 무제한이다.
- 구매 상점과 판매 상점은 별도 정의이고 서로 다른 상품을 가질 수 있다.
- 잠긴 상품의 해금은 `mythictrpg_shops` SavedData에 월드 전역으로 저장한다. 한 플레이어가
  퀘스트로 해금하면 모든 플레이어의 상점에 표시된다.
- 클라이언트는 상품·수량만 요청한다. 가격, 해금, 잔액, 아이템과 component는 서버가 다시 검증한다.

## 플레이어 명령

```text
/money
/shop buy
/shop sell
```

상점 화면은 한 번에 1~64묶음을 거래한다. 구매는 인벤토리 공간과 잔액을 먼저 확인한 뒤 골드를
차감하고 아이템을 넣는다. 판매는 ID뿐 아니라 모든 Minecraft data component가 같은 아이템만
세고 제거한 뒤 골드를 지급한다. 방어구·보조손은 자동 판매 대상이 아니며 기본 인벤토리 36칸만
거래한다.

## 관리자 명령

```text
/mythadmin economy balance get <player>
/mythadmin economy balance set <player> <amount>
/mythadmin economy balance add <player> <amount>
/mythadmin economy balance remove <player> <amount>
/mythadmin economy shop list
/mythadmin economy shop unlock <shopId> <productId>
/mythadmin economy shop lock <shopId> <productId>
```

`unlockedByDefault: true`인 상품은 Datapack 기본 상품이므로 관리자 명령으로 잠글 수 없다.

## 상점 Datapack

경로는 `data/<namespace>/mythictrpg/shops/*.json`이다. 파일에서 유도한 ID가 상점 ID다.

```json
{
  "schemaVersion": 1,
  "type": "BUY",
  "displayName": "마녀의 구매 상점",
  "products": [
    {
      "id": "mythictrpg:witch_healing_potion",
      "displayName": "회복의 물약",
      "item": {
        "id": "minecraft:potion",
        "count": 1,
        "components": {
          "minecraft:potion_contents": {"potion": "minecraft:healing"}
        }
      },
      "price": 25,
      "unlockedByDefault": false
    }
  ]
}
```

`type`은 `BUY` 또는 `SELL`이다. `item.components`는 선택 사항이며 Minecraft ItemStack의 component
형식을 사용한다. 따라서 같은 `minecraft:potion`이라도 효과가 다르면 다른 상품이고, 향후 모드에
`mythictrpg:instant_healing_potion` 같은 커스텀 아이템을 등록하면 해당 ID를 그대로 상품에 쓸 수 있다.

번들 샘플은 다음과 같다.

- `mythictrpg:general_sell`: 기본 광물 10종 판매
- `mythictrpg:witch_buy`: 기본 잠금 상태의 회복 물약 구매 상품

## 퀘스트 보상

기존 보상표·FTB 바인딩의 `automaticRewards`·선택 보상에서 다음 타입을 사용할 수 있다.

```json
{"type":"currency","amount":100}
```

```json
{
  "type":"unlock_shop_product",
  "shopId":"mythictrpg:witch_buy",
  "productId":"mythictrpg:witch_healing_potion"
}
```

화폐 보상은 해당 플레이어에게만 지급된다. 상품 해금은 전역이며 반복 지급해도 결과는 이미 해금된
상태로 유지된다. 등록되지 않은 상품, 최대 잔액 초과, 읽기 전용 저장 상태는 퀘스트 보상 커밋 전에
거부한다. AI가 상점 내용을 자유 형식으로 만들지 않으며, 작성된 퀘스트 또는 서버가 허용한 보상
정의만 실행한다.

## 검증과 운영

- `ShopEconomyGameTests`: 판매·구매·잔액·component 보존·잠금·전역 해금 영속성
- `CurrencyStateGameTests`: 플레이어별 잔액 격리와 저장·복원
- `RewardSystemGameTests`: 화폐·상품 해금 보상 JSON 계약
- 2026-09-02 기준 전체 필수 GameTest 190개 통과

실제 클라이언트에서는 `/shop buy`, `/shop sell` 화면의 작은 해상도 배치, 수량 버튼, ESC 닫기와
구매 후 갱신을 수동 확인한다.
