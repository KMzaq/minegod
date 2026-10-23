# 무기·장신구 외형 재사용과 장비 정의

2026-10-22 게임 **1.0.40 개발 소스** 기준. 외형 아이템 하나를 여러 장비 정의에서 재사용하고, 각 정의의 이름·능력·인챈트·스킬을 따로 작성한다. 개별 신이나 정식 보상을 이번 예시에 배정하지 않는다. 실제 검증·JAR 배포·화면 확인 상태는 [브랜치 인수인계](../../인수인계/브랜치실험/behaivior/PROJECT_HANDOFF.md)의 해당 작업 기록을 따른다.

## 외형 ID와 정의 ID

| 구분 | 예시 | 역할 |
|---|---|---|
| 무기 외형 아이템 ID | `mythictrpg:steel_blade` | 넓은 검 그림과 공통 `SwordItem` 종류 |
| 같은 외형의 장비 정의 ID | `reuse_demo:steel_training`, `reuse_demo:steel_wave` | 서로 다른 실제 장비의 이름·속성·인챈트·스킬·전투력 입력 |
| 장신구 외형 아이템 ID | `mythictrpg:amethyst_pendant` | 펜던트 그림과 장신구 아이템 종류 |
| 같은 외형의 장신구 정의 ID | `reuse_demo:mana_pendant`, `reuse_demo:link_pendant` | 최대 마나 증가 또는 연계 효과를 가진 서로 다른 보상 |

외형을 바꿀 때는 정의의 `itemId`를, 이름을 바꿀 때는 `name`을 수정한다. 스킬이나 수치를 바꾼다고 PNG나 Java 아이템 클래스를 복제하지 않는다. 장비 정의 ID는 파일 경로로 정해지며 아이템 ID나 God ID와 다르다.

새 무기 외형은 다음 두 종류다. 크리에이티브의 **무기 외형** 탭(`mythictrpg:weapon_appearances`)에 기본 외형 두 개가 있다.

| 기본 이름 | 아이템 ID | 모델·텍스처 파일 이름 |
|---|---|---|
| 강철검 외형 | `mythictrpg:steel_blade` | `steel_blade.json`, `steel_blade.png` |
| 결투검 외형 | `mythictrpg:duelist_blade` | `duelist_blade.json`, `duelist_blade.png` |

공통 등록은 [EquipmentItems.java](../src/main/java/com/sande/mythictrpg/equipment/EquipmentItems.java), 모델은 `assets/mythictrpg/models/item/`, PNG는 `assets/mythictrpg/textures/item/`에 있다. 아이템 태그 `mythictrpg:weapons`와 `minecraft:swords`에 두 종류를 포함한다. 추가 장신구 외형 네 종류는 [장신구 아이템 안내](ACCESSORY_ITEMS.md)를 따른다.

기본 검은 철 도구 등급의 공통 검 동작을 사용하지만 아이템 자체의 공격 속성 보너스는 없다. 플레이어 고유 공격력은 남아 있으므로 완전한 공격 금지용 아이템은 아니다. 크리에이티브 기본 외형이나 일반 `/give`만으로 보상 획득 이력·무기 스킬·장신구 권리를 만들지는 않는다. 실제 시험 장비는 아래 지급 명령을 사용한다.

## 실제 지급·시험

게임 1.0.41의 선택 연동으로 Simply Swords 외형 두 개를 사용하는 별도 보상 예시 네 개가 추가된다. 필요 모드·`requiredMods`·`/mythequipment grant swords`·원본 고유 능력과의 구분은 [Simply Swords / Better Combat 안내](SIMPLY_SWORDS_INTEGRATION.md)를 따른다. 아래 `grant examples` 여섯 개는 기존 사용자 그림 예시다.

치트 허용 또는 OP 권한 2 이상인 플레이어 본인이 실행한다. 처음 여섯 예시를 모두 받으려면 일반 인벤토리 빈 칸 여섯 개가 필요하다.

```mcfunction
/mythequipment list
/mythequipment grant examples
```

`list`는 현재 등록된 장비·장신구 정의를 나열한다. `grant examples`는 `reuse_demo` 네임스페이스의 예시를 지급한다. 개별 정의도 선택할 수 있다.

```mcfunction
/mythequipment grant reuse_demo:steel_wave
/mythequipment grant reuse_demo:mana_pendant
```

같은 정의를 다시 요청하면 기존 지급 영수증·장신구 권리를 재사용하며, 필요한 실물이 없을 때 복구한다. 현재 마나·쿨타임·기존 장비 이력을 초기화하지 않는다. 일반 퀘스트·레이드 보상의 신 등급·소유권 검증을 이 운영자 시험 명령의 처리로 바꾸지 않는다.

무기는 주 손에 들고 우클릭으로 해당 정의의 무기 스킬을 사용한다. 무기 스킬이 없는 정의는 우클릭 공격을 추가하지 않는다. 장신구는 **E 인벤토리의 두 칸**에 옮겨 장착한다. 조작은 [E 장신구 안내](ACCESSORY_INVENTORY.md), 야생·PvP·파티 보호는 [야생 전투 안내](WILDERNESS_COMBAT_AND_PARTY.md)를 따른다.

연계 장신구를 시험하려면 먼저 습득 스킬이 필요하다. 기존 시제품 습득 스킬만 추가하는 방법은 다음과 같다.

```mcfunction
/mythprototype grant proto_cbt:short_dodge
/mythskills select proto_cbt:short_dodge
/mythaccessory link proto_cbt:short_dodge
```

회피를 사용한 뒤 검기를 적에게 맞히면 연계 효과를 확인할 수 있다. 인벤토리에 장신구를 보관하는 것만으로는 효과가 발생하지 않는다.

## 무기 정의 작성

파일 위치는 `data/<namespace>/mythictrpg/equipment/<path>.json`이다. 예를 들어 `data/reuse_demo/mythictrpg/equipment/steel_wave.json`의 정의 ID는 `reuse_demo:steel_wave`다.

| 필드 | 의미·단위 |
|---|---|
| `schemaVersion` | 현재 `1` |
| `requiredMods` | 선택 항목. 모두 설치됐을 때 정의를 로드할 모드 ID 목록. 선택 연동 예시는 `["simplyswords", "bettercombat"]` |
| `name` | 실제 장비 이름. 같은 외형이라도 각 정의에서 다르게 작성 |
| `itemId` | 등록된 `SwordItem` 검 외형 아이템 ID. 비검 아이템은 로드 시 거절 |
| `attackDamage` | 아이템이 더하는 공격력. 플레이어 고유 공격력을 포함한 총합이 아님 |
| `attackSpeed` | 총 공격 속도. 속성 수정자에서는 플레이어 기준 속도 4와의 차이로 변환 |
| `durability` | 실제 최대 내구도 |
| `enchantments` | 실제 Minecraft 인챈트 ID와 단계의 맵 |
| `intrinsicPower` | 장비 고유 전투력 점수 |
| `skills` | 스킬 ID → 고유 전투력 점수. 이것만 넣어 실행 스킬로 삼지 않음 |
| `rightClickSkill` | 선택 항목. 등록된 무기 스킬 ID. 없는 장비에서는 생략 |
| `components` | 표시용 `minecraft:lore`, `minecraft:enchantment_glint_override`, `minecraft:rarity`, `minecraft:custom_model_data`만 허용 |

로더가 `name`·속성·내구도·인챈트와 `minecraft:custom_data.equipment_definition`을 생성하고 전투력 정책에 연결한다. 같은 외형의 두 장비라도 이 정의 정체성과 실제 구성요소를 구분한다. 자동 생성되는 `custom_data`, `custom_name`, `attribute_modifiers`, `enchantments`, `max_damage`, `damage`, `max_stack_size`를 표시 구성요소로 덮어쓰거나 `!` 제거 문법으로 우회하지 않는다.

실제 무기 스킬 정의는 기존 `weapon_skills` JSON을 재사용하며, 마나·쿨타임·검기·패링 행동은 그 스킬 파일에서 수정한다.

`enchantments`에 작성한 날카로움 등은 실제 바닐라 인챈트다. 설명 문구만 추가한 효과가 아니다. 날카로움 증가를 `attackDamage`에 다시 더해 중복 계산하지 않는다.

손에 든 검의 보호 인챈트는 방어구처럼 피해를 줄이지 않으므로, 검 정의의 **양수 단계 `minecraft:protection`은 거절**한다. 현재 전투력 공식이 직접 반영하는 무기 수치는 공격력과 날카로움이다. 다른 인챈트의 효과·공격 속도·내구도 차이를 별도 전투력으로 평가하려면 기획에서 `intrinsicPower`를 정한다. 이러한 차이를 반영한다며 기존 전투력 공식을 임의로 바꾸거나, 설명 문구만으로 효과를 구현한 것으로 취급하지 않는다.

실제 예시는 다음 파일을 편집 원본으로 사용한다.

| 정의 ID | 실제 이름 / 외형 | 실제 인챈트 / 우클릭 | 편집 원본 |
|---|---|---|---|
| `reuse_demo:steel_training` | 수련용 강철검 / 강철검 | 없음 / 없음 | [steel_training.json](../src/main/resources/data/reuse_demo/mythictrpg/equipment/steel_training.json) |
| `reuse_demo:steel_wave` | 파문의 강철검 / 강철검 | 날카로움 I / `proto_cbt:blade_wave` 검기 | [steel_wave.json](../src/main/resources/data/reuse_demo/mythictrpg/equipment/steel_wave.json) |
| `reuse_demo:duelist_training` | 연습용 결투검 / 결투검 | 없음 / 없음 | [duelist_training.json](../src/main/resources/data/reuse_demo/mythictrpg/equipment/duelist_training.json) |
| `reuse_demo:duelist_guard` | 응수의 결투검 / 결투검 | 날카로움 II / `proto_cbt:timed_parry` 패링 | [duelist_guard.json](../src/main/resources/data/reuse_demo/mythictrpg/equipment/duelist_guard.json) |

새 밸런스 값을 문서 사본에 따로 관리하지 않는다. 이 JSON과 [밸런스 조정 원칙](BALANCE_TUNING.md)을 함께 확인한다.

## 퀘스트·스토리·레이드 보상에서 참조

기존 공통 보상 목록에서 무기를 다음처럼 참조한다. 개별 퀘스트·스토리·레이드의 소유권과 지급 조건은 기존 시스템이 검사한다.

```json
{"type":"equipment","definitionId":"reuse_demo:steel_wave"}
```

공통 보상 파서가 정의 참조를 받고, 온라인 claim은 지급 전에 자동 보상·선택 보상의 실제 아이템 구성요소를 영수증에 확정한다. 오프라인 대기 보상은 대기 영수증을 생성하기 전에 장비 구성요소를 고정한다. 모두 기존 지급 경로를 사용하며, 영수증 정리·재접속·같은 명령 재실행으로 동일 출처 보상을 새 획득권리처럼 반복 지급하지 않는다.

발급 이후 정의를 바꿨다는 이유로 과거 영수증의 실물이나 획득 이력을 새 장비로 덮어쓰지 않는다. 외형 아이템 ID만 `item` 보상에 넣어서는 이 장비 정의의 능력·스킬이 자동 적용되지 않는다.

장신구는 기존 보상 유형을 사용한다.

```json
{"type":"accessory","definitionId":"reuse_demo:mana_pendant"}
```

장신구는 서버가 발급한 본인 획득권리와 실물을 연결한다. 크리에이티브 기본 외형에 이름·설명을 붙이는 것만으로 이 권리를 만들 수 없다. `/mythequipment`는 등록 정의를 시험 지급하는 명령이며, 손에 든 일반 검을 임의로 강화하는 명령은 아니다.

## 장신구의 이름·반짝임·효과

장신구 정의는 기존 `data/<namespace>/mythictrpg/accessories/<path>.json` 양식이다. 같은 `itemId`를 재사용해 `name`, 표시 구성요소, `fixedPower`, `effects`를 각각 다르게 작성한다.

| 정의 ID | 이름·외형 | 실제 장착 효과 |
|---|---|---|
| `reuse_demo:mana_pendant` | 비전의 펜던트 / 자수정 펜던트 | `MAX_MANA`, +40 |
| `reuse_demo:link_pendant` | 연계의 펜던트 / 자수정 펜던트 | `LINK_KNOT`, 기본 쿨타임의 10% 감소 |

두 예시는 고정 전투력 12를 사용한다. [mana_pendant.json](../src/main/resources/data/reuse_demo/mythictrpg/accessories/mana_pendant.json)과 [link_pendant.json](../src/main/resources/data/reuse_demo/mythictrpg/accessories/link_pendant.json)에서 같은 외형에 서로 다른 효과·설명·반짝임을 붙이는 방법을 확인한다.

장신구 장착 효과는 `effects`가 실행한다. 장신구의 `components`에는 표시용 `minecraft:lore`, `minecraft:enchantment_glint_override`, `minecraft:rarity`, `minecraft:custom_model_data`만 허용한다. `enchantment_glint_override`는 반짝임 표시이며 실제 인챈트 능력이 아니다.

실제 `minecraft:enchantments`, 공격 속성, 소유권용 `custom_data`, `custom_name` 등을 장신구 표시 구성요소에 넣으면 거절한다. 이름은 `name`으로, 능력은 지원되는 `effects`로 작성한다. 지원 효과·단위·실행 제한은 [장신구 작성 규격](SKILLS_ACCESSORIES_SPECIAL.md#장신구)을 따른다. 새 효과 종류가 필요하면 실행 기능을 추가한 뒤 사용한다.

## 기존 시제품·조정 도구와의 관계

- 기존 `proto_cbt` 무기 3종·방어구 8부위·스킬 3개·장신구 2개는 같은 ID와 설정을 유지한다. 새 외형 재사용 예시는 `reuse_demo`에 따로 둔다.
- `dev-tools/Get-CombatBalance.ps1`은 `proto_cbt` 소스 기본값을 조회한다. 장비는 `components.minecraft:custom_data.prototype_id`가 `proto_cbt:`로 시작하는 기존 행만 읽으므로 신규 장비 정의 네 개를 기존 11변형 수에 섞지 않는다. 새 장비·장신구 수치는 위 개별 JSON을 확인한다.
- 소스 JSON 수정은 빌드·JAR 반영이 필요하다. 외부 데이터팩 사용 시 로더의 `/reload` 결과와 실제 적용 우선순위를 확인한다.
- 장비 구성요소 변경은 기존 실물·획득 이력의 정체성에 영향을 줄 수 있다. 기존 장비를 자동 변환하거나 같은 정의 ID로 새 소유권·쿨타임을 초기화하지 않는다. 변경된 실물과 기존 영수증이 다르면 별도 버전 정의나 명시적 이관이 필요하다.
- 이미 지급한 장비 정의의 이름·속성·인챈트 등을 바꿀 때는 새 정의 ID 또는 명시적 버전 이관을 준비한다. 현재 정의와 과거 영수증이 다르면 OP 재지급도 안전하게 거절한다. 반면 연결된 **스킬 파일의 마나·쿨타임·행동만** 조정하는 것은 장비 실물의 정체성을 바꾸지 않는다. 같은 스킬 ID를 유지하며 실행 수치를 조정할 수 있다.

## 여러 종류의 장비와 저장 한도

외형 그림 수와 장비 정의 수는 독립적이다. 같은 두 검 그림을 여러 정의에서 재사용할 수 있으며, 현재 구현은 다음 한도를 둔다. 이는 저장·검증 한도이며 밸런스 조정값이 아니다.

| 대상 | 현재 한도 | 실제 위치 |
|---|---|---|
| 등록할 무기 장비 정의 | 1,024개 | `EquipmentCatalog.MAX_DEFINITIONS` |
| 기존 명시 행과 신규 생성 행을 합친 전투력 규칙 | 4,096개 | `CombatPowerPolicy.withAdditionalItems` |
| 플레이어 한 명의 서로 다른 획득 장비 변형 | 4,096개 | `RewardPowerState.MAX_VARIANTS` |
| 서버 전체 플레이어의 획득 장비 변형 합계 | 65,536개 | `RewardPowerState.MAX_TOTAL_VARIANTS` |

기존 획득 데이터와 저장 형식은 유지하며 초기화하지 않는다. 과거 한도 초과나 이력 누락으로 이미 `incomplete`가 된 기록은 한도 상향만으로 완전한 기록으로 복구하지 않는다. 실제 획득 근거를 확인하는 별도 복구가 필요하다.

개인 512개·서버 전체 8,192개였던 구버전 한도를 넘긴 저장 데이터를 만든 뒤에는 이전 한도의 JAR로 되돌리는 것이 안전하지 않다. 구버전 로더가 새 데이터의 크기를 거절할 수 있으므로, 버전을 되돌릴 때는 그 버전에 맞는 사전 백업·이관 계획이 필요하다. 한도를 피하려고 기존 최고 장비 획득 이력을 지우지 않는다.

## 원본 그림 검증

두 검 PNG는 사용자 원본을 수정 없이 복사했다. 모두 **1254×1254**이며 완전 투명·부분 투명 픽셀이 남아 있다. 모델은 `minecraft:item/handheld`를 사용한다.

| 파일 | 원본과 일치하는 SHA-256 |
|---|---|
| `textures/item/steel_blade.png` | `0E20CFFA27E8A7014ED7B35B0C0C1CA698846E97BC6CA3338A67A8A05EFC2286` |
| `textures/item/duelist_blade.png` | `35AEA7D4CA3CD78D8798C9C9104FFF4F20CF00B68D781ECC812972DD3FC4499F` |
