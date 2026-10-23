# 장비·스킬·장신구 작성 규격 보완

2026-10-22 · `behaivior`의 `mythictrpg-main` 개발 소스 기준. 기존 [스킬·장신구 기획 전달서](../skills-accessories-special-planning-20261022.md)와 [시스템 작성 가이드](../../mythictrpg-main/docs/SKILLS_ACCESSORIES_SPECIAL.md)는 계속 유효하다. 이 문서는 기존 전달서에 빠진 장비 식별·실행 연결과 정상 형식 예시를 보충한다. **게임 코드를 새로 구현하거나 정식 인물·보상 수치를 배정한 자료가 아니다.** 서버 배포·실제 클라이언트 조작 확인과 개발 구현 상태는 구분한다.

## 1. 장비 7등급: 현재 이름·저장값 없음

이전 가호 전달서의 ‘장비 7등급’은 기획에서 언급된 성장 구분이다. 현재 게임의 장비·보상·전투력 파서에는 **장비 7등급 enum, 명칭표, 전용 저장 필드가 구현되어 있지 않다.** 따라서 지금 제공할 수 있는 공식 이름·저장값 대응표는 없다. 임의로 일반·희귀·전설 등의 명칭이나 `grade: 1`을 만들어 넣으면 안 된다. 장비 등록 항목은 허용되지 않은 필드를 거절한다.

| 구분 | 현재 상태 |
|---|---|
| 장비 7등급의 이름·저장값·등급별 능력치 자동 적용 | 미구현. 명칭과 규칙은 추후 기획 필요 |
| 신 6등급 `god_rank` | 구현. `lowest / low / middle / high / highest / transcendent` |
| 보상표 `tier` | 구현. 1부터 연속되는 보상 묶음 번호. 장비·신 등급이 아님 |
| 아이템 `minecraft:rarity` | Minecraft 기본 표시 희귀도. 이 프로젝트의 장비 7등급과 연결되지 않음 |

현재 장비는 **실제 Minecraft 아이템·컴포넌트로 만들고**, 그 변형을 전투력 정책에 등록한다. 전용 장비 정의 폴더나 장비 ID 레지스트리는 아직 없다. `itemId + components`가 등록 변형의 식별 기준이다.

## 2. 방어구·무기 작성: 실제 아이템과 전투력 등록을 함께

장비 등록 위치는 `data/<namespace>/mythictrpg/combat_power/<policy>.json`의 `items` 배열이다. 기본 파일은 [combat_power/default.json](reference/mythictrpg-main/src/main/resources/data/mythictrpg/mythictrpg/combat_power/default.json)이며 현재 `items: []`다. 정식 보상 장비 카탈로그가 아직 비어 있다는 뜻이다. 아래는 **해당 배열에 넣을 항목 예시**이며 독립 정책 파일 전체가 아니다. 여러 정책을 동시에 만드는 대신 기존 정책의 계수·항목을 포함한 하나의 유효 정책에 합쳐야 한다.

### 방어구 등록 항목 예시

```json
{
  "itemId": "minecraft:iron_chestplate",
  "components": {
    "minecraft:custom_model_data": 2101,
    "minecraft:enchantments": {"levels": {"minecraft:protection": 4}}
  },
  "classification": "BOUND_REWARD_GEAR",
  "slot": "chest",
  "intrinsicPower": 12,
  "attackDamage": 0,
  "sharpnessLevel": 0,
  "protectionLevel": 4,
  "skills": {}
}
```

### 무기 등록 항목 예시

```json
{
  "itemId": "minecraft:diamond_sword",
  "components": {
    "minecraft:custom_model_data": 2001,
    "minecraft:enchantments": {"levels": {"minecraft:sharpness": 5}}
  },
  "classification": "BOUND_REWARD_GEAR",
  "slot": "weapon",
  "intrinsicPower": 20,
  "attackDamage": 6,
  "sharpnessLevel": 5,
  "protectionLevel": 0,
  "skills": {"example:weapon_spark": 10},
  "rightClickSkill": "example:weapon_spark"
}
```

`example:*`와 수치·모델 번호는 작성 예시다. `custom_model_data`는 변형 구분용이며 외형을 직접 생성하지 않는다. 다른 모양을 표시하려면 별도 리소스팩 모델 연결이 필요하다.

| 필드 | 의미·주의 |
|---|---|
| `components` | Minecraft 1.21.1 아이템 컴포넌트. 지급 보상에도 같은 값을 작성한다 |
| `classification` | 전투력 대상 `BOUND_REWARD_GEAR`, 대응 도구 등 제외 항목 `IGNORE` |
| `slot` | 전투력 최고 장비를 고르는 그룹. 파서는 소문자·숫자·밑줄 1~32자 허용. `head/chest/legs/feet/weapon`처럼 일관되게 작성한다. 실제 Minecraft 장착 부위를 바꾸지 않는다 |
| `intrinsicPower` | 장비의 별도 고유 점수. 기본 정책에서는 그대로 더한다. 등급 자동 환산값이 아님 |
| `attackDamage` | 전투력 평가에 더할 무기의 공격력 입력. 예시의 다이아 검 6은 플레이어 기본 공격력 1에 더하는 무기 기여분이다. 날카로움은 별도이므로 여기서 중복 가산하지 않는다 |
| `sharpnessLevel/protectionLevel` | 해당 아이템 컴포넌트의 실제 인챈트 단계와 같아야 한다. 값만 적어 인챈트를 부여하지 않는다 |
| `skills` | `스킬 ID: 고유 전투력` 표. 실행 기능을 직접 만들지 않는다 |
| `rightClickSkill` | 해당 무기의 실제 우클릭 실행 ID. `skills`에도 같은 ID가 있어야 한다. **스킬 없는 무기는 이 필드를 생략한다** |

전투력 정책은 **점수 계산과 실행 연결 규격**이다. `intrinsicPower`나 `attackDamage`를 바꾸어도 실제 아이템의 방어력·공격력이 바뀌지 않는다. 실제 능력은 바닐라 아이템과 `minecraft:attribute_modifiers` 같은 Minecraft 컴포넌트가 결정한다. 기본 전투력 모델은 방어구의 바닐라 방어력·강인함을 자동 추출하지 않는다. 고유 점수와 보호 단계를 설계할 때 [실제 계산식](01-combat-power.md)을 함께 본다.

`IGNORE` 항목은 `slot:"none"`, `intrinsicPower/attackDamage/sharpnessLevel/protectionLevel:0`, `skills:{}`이어야 하고 `rightClickSkill`을 붙일 수 없다.

### 같은 장비를 지급하는 기존 RewardEntry 예시

다음 항목들을 기존 보상표·퀘스트·Story의 보상 배열에 넣는다. 장비 등록 항목을 보상으로 그대로 넣는 형식이 아니다.

```json
[
  {
    "type": "item",
    "itemId": "minecraft:iron_chestplate",
    "count": 1,
    "components": {
      "minecraft:custom_model_data": 2101,
      "minecraft:enchantments": {"levels": {"minecraft:protection": 4}}
    }
  },
  {
    "type": "item",
    "itemId": "minecraft:diamond_sword",
    "count": 1,
    "components": {
      "minecraft:custom_model_data": 2001,
      "minecraft:enchantments": {"levels": {"minecraft:sharpness": 5}}
    }
  }
]
```

지급 이력에 등록되지 않은 변형이 남으면 계산 불가가 될 수 있다. 이름·인챈트·모델 등 다른 컴포넌트가 추가되면 등록과 지급을 같이 맞춰야 한다. 내구도 변화만 식별 비교에서 제외한다. `BOUND_REWARD_GEAR`라는 분류만으로 거래·투척을 전부 금지하는 별도 아이템 잠금이 생기지는 않는다. 실제 우클릭 스킬은 **본인의 정당한 획득 이력과 현재 손의 등록 변형**을 대조한다.

## 3. 무기에 붙는 스킬과 별도 습득 스킬

### 무기 스킬 정의

`data/example/mythictrpg/weapon_skills/weapon_spark.json` → ID `example:weapon_spark`:

```json
{
  "schemaVersion": 1,
  "name": "조준 전격 작성 예시",
  "manaCost": 25,
  "cooldownTicks": 100,
  "action": {"type": "AIMED_DAMAGE", "amount": 6, "range": 8}
}
```

이 파일에는 **`intrinsicPower` 필드가 없다.** 무기 스킬 고유 점수는 앞의 장비 등록 항목 `skills`에서 적는다. 해당 장비를 기존 `item` 보상으로 지급하면 획득 이력에 연결되며, 주 손 우클릭으로 사용한다. 무기 스킬을 별도 `learn_skill` 보상으로 지급하지 않는다.

### 습득 스킬 정의·지급

`data/example/mythictrpg/learned_skills/learned_heal.json` → ID `example:learned_heal`:

```json
{
  "schemaVersion": 1,
  "name": "자가 회복 작성 예시",
  "manaCost": 30,
  "cooldownTicks": 400,
  "intrinsicPower": 15,
  "action": {"type": "SELF_HEAL", "amount": 4}
}
```

기존 보상 위치에 다음 **한 항목**을 넣는다.

```json
{"type": "learn_skill", "definitionId": "example:learned_heal"}
```

습득 스킬은 개인 보유권에 저장한다. 무기를 들지 않아도 K로 선택하고 J 또는 `/mythskills use`로 사용한다. 퀘스트·탐사·이벤트도 기존 보상 claim 경로로 지급한다. 인물 등급 하급 이상을 검사하므로, 실제 지급 God ID에 `god_rank`가 작성되어 있어야 한다.

공통 행동 `AIMED_DAMAGE / AREA_DAMAGE / PERIODIC_DAMAGE / SELF_HEAL / SELF_EFFECT`의 전체 필드와 범위는 [기존 스킬 가이드](../../mythictrpg-main/docs/SKILLS_ACCESSORIES_SPECIAL.md#습득-스킬)를 그대로 따른다. 마나는 기본 200, 장신구 증가분 반영 후 최대치의 초당 1% 회복이다. 정상 공격이 빗나가도 마나·쿨타임은 소모하고, 시전 전 실패는 무소모다. 성급함이 스킬 쿨타임을 자동 줄이거나 힘·날카로움이 명시된 스킬 피해에 자동 더해지지는 않는다.

## 4. 장신구 정의 → 지급 권리 → 자유 슬롯 2칸 → 효과

`data/example/mythictrpg/accessories/mana_charm.json` → ID `example:mana_charm`:

```json
{
  "schemaVersion": 1,
  "name": "마나 장신구 작성 예시",
  "itemId": "minecraft:heart_of_the_sea",
  "components": {},
  "fixedPower": 20,
  "effects": [{"type": "MAX_MANA", "amount": 100}]
}
```

지급 항목:

```json
{"type": "accessory", "definitionId": "example:mana_charm"}
```

연결은 다음 순서다.

1. 서버가 실제 제공 God ID의 상급 이상 등급과 장신구 정의 존재를 검증한다.
2. 기존 보상 영수증에 **플레이어 소유 획득권리 UUID + 정의 ID**를 저장한다.
3. O, 인벤토리 장신구 버튼, `/mythaccessory`에서 보유 권리를 두 슬롯 중 하나에 장착한다.
4. 서버가 착용한 두 권리의 정의를 조회해 `effects`를 합산한다. 예시 하나 장착 시 최대 마나 300, 초당 회복 3이다. 장착 순간 현재 마나를 즉시 채우지는 않는다.

슬롯은 반지·목걸이 구분 없는 자유 두 칸이다. 전투 중 교체 가능하고 효과를 중첩한다. **동일 획득권리 UUID를 두 칸에 넣을 수 없으며**, 별도 보상으로 각각 획득한 동일 정의의 두 권리는 함께 착용 가능하다. 재지급·복사본은 새 권리나 새 쿨타임을 만들지 않는다.

정의의 `itemId/components`는 목록 표시용이다. 실제 재지급 물건은 `mythictrpg:bound_accessory`이며 이름·소유자·권리 UUID·정의 ID를 기록한다. **일반 바다의 심장을 들거나 갑옷칸에 넣는 것만으로 효과가 활성화되지는 않는다.** 효과 소유권은 아이템 외형이 아니라 위의 영수증 권리와 전용 슬롯에 연결된다. 전용 아이템을 우클릭하면 장신구 화면을 연다.

`fixedPower`는 실제 착용 효과와 별개다. 현재 전투력은 합법적으로 보유한 장신구 권리 중 점수가 높은 2개의 합을 사용하며, 효과는 실제 착용한 두 개에만 활성화한다. 효과를 다시 자동 점수화하지 않는다. 전체 12종 효과와 연계·정화·응급 회복 등 발동 규칙은 [기존 가이드](../../mythictrpg-main/docs/SKILLS_ACCESSORIES_SPECIAL.md#장신구)와 [기획 전달서](../skills-accessories-special-planning-20261022.md)를 따른다.

## 5. 현재 가능한 범위와 아직 없는 부분

| 항목 | 현재 개발 소스 상태 |
|---|---|
| 등록 보상 아이템·컴포넌트, 인챈트 일치 검증 | 구현 |
| 일부 등록 무기의 우클릭 스킬 | 구현. 실제 운영 무기 목록 미작성 |
| 개인 습득 스킬·마나·쿨타임·보상 지급 | 구현. 실제 인물별 스킬 목록 미작성 |
| 자유 장신구 2칸·중첩·보상 지급·재지급 | 구현. 실제 인물별 장신구 목록 미작성 |
| 장비 7등급 전용 명칭·저장값·자동 능력치 | 미구현 |
| 별도 커스텀 무기·방어구 정의 레지스트리 | 미구현. 현재는 Minecraft 아이템 컴포넌트와 전투력 등록을 조합 |
| 투사체·돌진·한 무기의 복수 입력 스킬 | 현재 공통 행동에 미구현. 요청별 후속 확장 필요 |
| 새 외형·신별 고유 연출·실제 운영 밸런스 | 개별 기획·리소스 작성·플레이 검증 후속 |

## 코드 확인 위치

- [CombatPowerPolicy.java](reference/mythictrpg-main/src/main/java/com/sande/mythictrpg/power/CombatPowerPolicy.java): 장비 등록 필드·실제 인챈트·우클릭 연결 검증.
- [RewardEntryCodec.java](reference/mythictrpg-main/src/main/java/com/sande/mythictrpg/quest/reward/RewardEntryCodec.java), [NpcRewardEntry.java](reference/mythictrpg-main/src/main/java/com/sande/mythictrpg/quest/reward/NpcRewardEntry.java): 지급 항목·Minecraft 아이템 컴포넌트 해석.
- [WeaponSkillDefinition.java](reference/mythictrpg-main/src/main/java/com/sande/mythictrpg/weaponskill/WeaponSkillDefinition.java), [LearnedSkillDefinition.java](reference/mythictrpg-main/src/main/java/com/sande/mythictrpg/skills/LearnedSkillDefinition.java): 각 스킬의 엄격한 필드 규격.
- [AccessoryDefinition.java](reference/mythictrpg-main/src/main/java/com/sande/mythictrpg/accessory/AccessoryDefinition.java), [AccessoryRuntime.java](reference/mythictrpg-main/src/main/java/com/sande/mythictrpg/accessory/AccessoryRuntime.java), [RewardAccessoryItems.java](reference/mythictrpg-main/src/main/java/com/sande/mythictrpg/accessory/RewardAccessoryItems.java): 표시 정의·두 슬롯·효과·실제 복구 아이템.
- [ProgressionRewards.java](reference/mythictrpg-main/src/main/java/com/sande/mythictrpg/quest/reward/ProgressionRewards.java): 방어구·무기·습득 스킬·장신구·특수스킬의 제공 인물 등급 검증.

예시는 현재 파서의 필드·연결과 대조했고 JSON 구문을 확인했다. 정식 God ID·등급 배정·외형 리소스·아이템 획득 이력을 포함한 운영 적용은 이 문서 작성으로 실행하지 않았다.
