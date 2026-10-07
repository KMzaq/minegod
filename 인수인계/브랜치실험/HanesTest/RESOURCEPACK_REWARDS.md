# 리소스팩 장비의 보상 데이터 보존

2026-09-29 / 개발 브랜치. 운영 콘텐츠/장비 밸런스를 추가한 문서가 아니다.

## 역할

리소스팩은 외형을 제공한다. 서버가 발급하는 실제 아이템은 기존 Minecraft/추가 모드의 item ID와
Minecraft 1.21.1 data components로 구성한다. 상점은 이미 components를 지원했다.
이번 변경은 같은 방식의 커스텀 모델·이름·속성 등이 퀘스트/신 보상과 선택 보상에도 유지되게 한다.

기존 보상표/직접 보상의 `type: item`에 선택적 `components` 객체를 추가한다.
이 객체가 없으면 기존 `itemId`/`count` 동작과 Java 2인자 생성자는 그대로다.

```json
{
  "type": "item",
  "itemId": "minecraft:iron_sword",
  "count": 1,
  "components": {
    "minecraft:custom_model_data": 912345,
    "minecraft:custom_name": "{\"text\":\"개발 검증용 검\"}"
  }
}
```

위 숫자/이름은 합성 예시이며 실제 서버 보상으로 배치하지 않았다.
리소스팩에서 같은 기본 아이템과 CustomModelData에 대응하는 모델을 작성한다.
Minecraft 1.21.1 형식이며 이후 버전의 custom model data 객체 형식과 혼용하지 않는다.
능력치를 넣을 때는 해당 버전의 `minecraft:attribute_modifiers` 컴포넌트를 사용한다.
공격력·강화 단계·등급·가격은 이 작업에서 정하지 않았다.

## 실제 경로

`RewardEntryCodec → NpcRewardEntry → reward claim 저장 → RewardExecutionService → 실제 ItemStack`

- 16,384자 이내의 authored component 객체를 방어 복사한다. 일반 LLM 응답에 임의 components 지급 권한을 추가하지 않는다.
- 서버 registry를 사용해 완성 ItemStack을 검증한 뒤 지급한다. 잘못된 component 타입이나 미등록 아이템이면 보상 전표를 소모하기 전에 거부한다.
- 개인 사전검사/직접 발급/오프라인 batch 예약에서 자동 보상과 모든 선택지를 확인한다.
- 전표 안에 component snapshot을 저장한다. 수령을 기다리는 동안 원본 JSON이 바뀌어도 이미 예약된 장비를 재해석하지 않는다.
- 기존 claim 소유권·중복 지급 방지·선택권·재접속 경로를 사용한다. 별도 보상 DB는 없다.

## 저장 호환성

`mythictrpg_reward_claims` 저장 버전은 **3**이다. 새 코드는 버전 1/2의 plain-item 전표를 읽는다.
구 버전 게임 JAR은 버전 3을 읽기 전용/거부 상태로 취급하므로,
새 버전으로 저장한 월드에서 JAR만 이전 버전으로 내리는 것을 지원한다고 보장하지 않는다.
배포 시 정상 종료된 월드/전표 백업을 함께 보존한다. 이번 개발 중 실제 서버 월드는 변경하지 않았다.

## 범위 밖

새 장비 목록·리소스팩 미술·옵션 추첨·성장 곡선은 추가하지 않았다. 사용자는 **더 좋은 장비를 얻어 교체하는 방식**만 선택했으므로 강화·개조는 범위 밖이다. 전투력 공급자 연결은 별도 [전투력 문서](COMBAT_POWER.md)를 따른다.

`ResourcePackRewardGameTests` 3개로 component 실제 지급·claim 저장/재로드·잘못된 component 거절·선택 보상 동결을 확인했다. 실제 클라이언트 리소스팩 렌더와 운영 서버 배포는 미실시다. 수치는 fixture일 뿐 운영 장비/밸런스를 자동 작성하지 않는다.
