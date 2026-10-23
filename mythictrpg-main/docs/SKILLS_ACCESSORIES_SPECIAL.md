# 스킬·장신구·레이드 특수지원 작성 가이드

2026-10-22 게임1.0.40 후속: [외형 재사용·장비 정의](EQUIPMENT_APPEARANCES.md). 같은 외형에 이름·속성·무기 인챈트·스킬이 다른 정의를 연결한다. 장신구 미리보기와 지급 실물은 같은 표시 구성요소를 사용하며, 장신구 `components`는 lore·반짝임·희귀도·custom_model_data 네 항목만 허용한다. 장착 능력은 `effects`에서 실행한다.

2026-10-22 게임1.0.39 후속: [장신구 아이템 분류·외형4종](ACCESSORY_ITEMS.md)을 등록했다. 정의의 `itemId`가 해당 장신구이면 지급 실물·미리보기·착용 칸에 그 외형을 사용한다. 일반 Minecraft 표시 아이템으로 작성한 기존 정의는 귀속 토큰을 사용한다. 권리 UUID와 효과 수치는 유지한다.

2026-10-22 게임1.0.38 후속: [E 인벤토리 장신구 두 칸](ACCESSORY_INVENTORY.md). 본인 장신구를 일반 인벤토리에서 옮겨 장착·해제하며 Shift 클릭을 지원한다. 기존 O 화면은 보유 목록·재지급·연계 대상 관리에도 사용한다. 효과·권리 UUID·쿨타임은 유지한다.

2026-10-22 게임1.0.37 후속: [야생 전투·기존 FTB 파티 확장](WILDERNESS_COMBAT_AND_PARTY.md). 검기·패링·대쉬 야생 사용, PvP/주민 공격과 자기·파티 동물/파티원 보호를 적용한다. 인물 특수지원은 기존 레이드 전용 규칙을 유지한다.

2026-10-22 `behaivior` 개발 소스. [기획자 전달서](../../전달자료/skills-accessories-special-planning-20261022.md)에 확정 규칙·효과별 수치·신별 제출 양식이 있다. [검증·배포 기록](../../인수인계/브랜치실험/behaivior/SKILLS_ACCESSORIES_SPECIAL_20261022.md)을 함께 본다. 아래 JSON은 예시이며 실제 인물에 자동 배정하지 않는다.

## 일반 플레이

| 기능 | 조작 |
|---|---|
| 무기 스킬 | 지정 보상 무기 주 손 우클릭. Shift+우클릭은 기본 상호작용 |
| 습득 스킬 목록·선택 | 기본 K 또는 `/mythskills` |
| 선택한 습득 스킬 사용 | 기본 J 또는 `/mythskills use` |
| 장신구 장착 두 칸 | E 인벤토리의 캐릭터 오른쪽 두 칸, Shift 클릭 지원 |
| 장신구 보유 목록·관리 | 기본 O, 인벤토리 장신구 관리 버튼, `/mythaccessory` |
| 연계 대상 | 화면 또는 `/mythaccessory link <습득스킬ID>`, 해제는 `/mythaccessory unlink` |
| 장신구 재지급 | 화면의 재지급 또는 `/mythaccessoryreissue <획득권리UUID>` |
| 특수스킬 | `/mythspecial list`, `/mythspecial use <ID>` |
| 본인 마나 | `/mythmana` |

키 설정에서 재지정 가능하다. 습득 스킬 선택 한 칸은 초기 조작 방식이며 보유 수 제한이 아니다. 서버가 현재 접속 객체·소유권·상태·자원·대상을 재검사한다.

## 습득 스킬

`data/<namespace>/mythictrpg/learned_skills/<id>.json`:

```json
{"schemaVersion":1,"name":"회복 예시","manaCost":30,"cooldownTicks":100,"intrinsicPower":0,"action":{"type":"SELF_HEAL","amount":4}}
```

무기 스킬은 기존 `weapon_skills/`와 선택 필드 `rightClickSkill` 연결을 유지한다. 공통 행동:

| type | 필드 |
|---|---|
| `AIMED_DAMAGE` | `amount`, `range` |
| `AREA_DAMAGE` | `amount`, `radius`, `maximumTargets` |
| `PERIODIC_DAMAGE` | `amount`, `range`, `pulses`, `intervalTicks` |
| `SELF_HEAL` | `amount` |
| `SELF_EFFECT` | `effectId`, `level`, `durationTicks` |
| `BLADE_WAVE` | `firstDamage`, `subsequentDamage`, `speed`, `range`, `radius`, `maximumTargets`, `windupTicks`, `recoveryTicks`, 선택 `lifetimeTicks`(기본10) |
| `TIMED_PARRY` | `windupTicks`, `activeTicks`, `recoveryTicks`, `halfAngleDegrees`, `range` |
| `SHORT_DASH` | `windupTicks`, `movementTicks`, `evasionTicks`, `recoveryTicks`, `distance`, `minimumDistance` |

2026-10-22 후속 세 행동은 [전투 1차 안내](FIRST_COMBAT_PROTOTYPE.md)를 따른다. 검기는 플레이어 귀속 물리 투사체이며 기존 마법 피해 행동과 구분한다. 패링·회피는 비공격 행동이고 장신구 적중을 만들지 않는다. 시전 잠금과 레이드/시험장 격리를 유지하며1.0.37부터 일반 야생에서도 사용한다.

```json
{"schemaVersion":1,"name":"범위 공격 예시","manaCost":20,"cooldownTicks":400,"intrinsicPower":0,"action":{"type":"AREA_DAMAGE","amount":6,"radius":4,"maximumTargets":10}}
```

피해는 플레이어 귀속 마법 피해다. 범위 공격은 본인 중심, 반복 피해는 처음 조준한 대상에 적용한다. 일반 쿨타임0~72,000틱, 마나 작성 상한1,000,000이다. 실제 최대 마나는 기본200+착용 장신구 증가분이며 틱당 최대치의1/2000(초당1%)을 회복한다. 최대치 증가로 즉시 충전하지 않는다. 정상 공격의 빗나감은 비용을 지불하고, 시전 전 거절·불필요한 자기 회복/버프는 무소모다.

`SkillCastEvents`는 UUID·플레이어·출처(WEAPON/LEARNED/SPECIAL)·스킬ID·마나·기본 쿨타임·공격 여부를 제공한다. Start→유효 피해마다 Hit→한 번의 Finished. 광역·다단·지속 피해도 같은UUID이며 종료 시 적중0이면 환급을 판정한다. 사망·로그아웃·차원 이동으로 중단된 반복 시전은 취소 종료하여 환급하지 않는다.

## 장신구

`data/<namespace>/mythictrpg/accessories/<id>.json`:

```json
{"schemaVersion":1,"name":"마나 예시 장신구","itemId":"minecraft:heart_of_the_sea","components":{},"fixedPower":0,"effects":[{"type":"MAX_MANA","amount":100}]}
```

```json
{"schemaVersion":1,"name":"연계 예시 장신구","itemId":"minecraft:string","components":{},"fixedPower":0,"effects":[{"type":"LINK_KNOT","amount":0.1}]}
```

효과는 최대12종, 같은type 중복 작성 금지다. 효과 항목은 `type/amount`, 선택 필드 `durationTicks/cooldownSeconds`를 사용한다.

| type | amount·추가 필드 |
|---|---|
| `MAX_MANA` | 추가 최대 마나 |
| `LINK_KNOT` | 기본 쿨타임 감소 비율. 합의한 기본0.1 |
| `CLEANSE_CC` | 양수 표시값, 통상1. cooldownSeconds 기본300 |
| `EMERGENCY_HEAL` | 최대 체력 대비 총 회복 비율. durationTicks 필수, cooldownSeconds 기본86400 |
| `MISS_REFUND` | 마나 환급 비율 |
| `LUCK` | 추가 행운 |
| `DAMAGE_FLAT` / `DAMAGE_REDUCTION_FLAT` | 추가/감소 체력 피해 |
| `SPEED_FLAT` | 추가 이동속도 속성값 |
| `EXTRA_JUMPS` | 추가 공중 점프 횟수, 정수 |
| `HEALTH_REGEN_PER_SECOND` | 초당 회복 체력 |
| `GOLD_BONUS_FRACTION` | 추가 골드 비율.5%는0.05 |

비율형 개당0초과~1, 추가 점프 개당1~8, 응급 회복 지속1~72,000틱이다. 정화·응급 회복만 자체 현실 쿨타임을 가진다. 합산·횟수·공중 교체·진행 회복 규칙은 기획 전달서의 초기 처리 절을 따른다.

슬롯은 기존 보상 원장의 획득권리UUID 두 개를 참조한다. 동일UUID를 두 칸에 넣을 수 없다. 별도로 얻은 같은 정의의 두 UUID는 가능하다. 지급·재지급은 정의의 `itemId`가 등록 장신구4종이면 그 전용 아이템을 사용하고, 다른 기존 표시용 아이템이면 `mythictrpg:bound_accessory`를 사용한다. 일반 재료를 복제하지 않는다. 장착 칸·커서·인벤토리에 이미 같은 권리가 있으면 추가하지 않으며, 새 실물의 인벤토리 여유가 없으면 거절한다. 같은 권리의 아이템 복사본은 새 효과·새 쿨타임을 만들지 않는다. 표시용 components와 실제 보상 소유권을 구분한다.

시전 시작 시 착용 권리·연계 대상을 고정한다. 연계는 무기 출처에만, 환급은 일반 공격 출처에만 적용한다. 환급 합계는 지불 마나100%가 상한이다. 중복 종료·지연 적중은 새 발동을 만들지 않는다.

## 특수스킬: 기획자가 제한을 선택·조합

`data/<namespace>/mythictrpg/special_skills/<id>.json`:

```json
{"schemaVersion":1,"name":"레이드 지원 예시","providerGodId":"example:deity","manaCost":0,"intrinsicPower":0,"limits":{"perRaidUses":1},"action":{"type":"LIGHTNING_STRIKE","amount":30,"range":16,"radius":3,"stunTicks":20}}
```

limits는 아래 중 **최소 하나**를 작성한다. 여러 필드는 AND이며 작성하지 않은 제한을 자동으로 붙이지 않는다.

| 필드 | 선택 값 |
|---|---|
| `perRaidUses` |1~100. 플레이어·스킬·도전UUID별 횟수 |
| `cooldownSeconds` |1~31,536,000. 사용 시점부터 현실 시간.24시간=86400 |
| `consumables` | 최대16개 정확한 아이템·컴포넌트·수량 |

아래는 limits 필드의 값 예시다.

```json
{"perRaidUses":1,"cooldownSeconds":86400,"consumables":[{"itemId":"minecraft:echo_shard","count":1,"components":{}}]}
```

셋을 모두 작성하면 횟수·현실 쿨타임·재료를 전부 만족해야 한다. 마나 비용은 별도이며0도 가능하다. 겹치는 재료 요구가 같은 스택을 두 번 계산하지 않는다. 사용 전 실패는 무소모, 정상 시전 후 빗나감/피해 거절은 사용으로 기록한다. 특수스킬은 연계·환급에서 제외한다.

행동은 LIGHTNING_STRIKE / AREA_DAMAGE / HEAL_ROSTER. 모두 amount/range/radius를 작성한다. 공격의 stunTicks는0~1200, 회복은0이다. 기술적 작성 상한은 피해·회복100,000, 거리64, 반경32다. 실제 신의 최종 밸런스가 아니다. 선택 필드 condition은 기존 조건 트리로 발동 순간 검사한다.

제공 인물은 최상급 이상이고 지급 God ID와 providerGodId가 같아야 한다. 실제 ACTIVE 도전·참가자·탈락/부활 대기·차원·영역을 확인한다. 공격은 같은 도전의 전투 개체, 회복은 해당 도전의 유효 참가자에 적용한다. 관계 제한은 condition으로 별도 작성한다.

`support_avatar`는 같은 인물ID·등록 외형을 참조하는 비영속 연출 실체다. 한 행동 후40틱 내 퇴장하며 실제 NPC의 위치·대화·관계·퀘스트·봉인 권한을 복제하지 않는다. 전격은 파티클+마법 피해로 화재나 자연 번개를 만들지 않는다. 사용 기록을 먼저 확정하고 재시작 후 행동을 재생하지 않는다.

## CC·면역

`CrowdControlService.applyStun(entity,ticks,dispellable,mechanic)`는 출처별 잠금을 기록한다. 일반 기절은 이동·직접 공격·사용을 막으며 정화 가능 여부를 따른다. mechanic=true는 일반 정화로 제거하지 않는다. 현재 공통 CC 행동은 기절이며 해로운 포션 전체를 CC로 간주하지 않는다.

`data/<namespace>/mythictrpg/control_immunities/<id>.json`:

```json
{"schemaVersion":1,"godIds":["example:boss"],"entityTypes":["minecraft:ender_dragon"]}
```

## 기존 보상 연결·저장

기존 RewardEntry 위치에 아래 타입을 작성한다. 퀘스트·탐사 사건·스토리·NPC 표·레이드 모두 기존 claim을 통해 지급한다.

```json
{"type":"learn_skill","definitionId":"example:sample_skill"}
```

```json
{"type":"accessory","definitionId":"example:sample_accessory"}
```

```json
{"type":"special_skill","definitionId":"example:sample_support"}
```

신규 종류는 기존 제공 God ID의 god_rank를 필수로 작성하고 하급/상급/최상급 최소 등급을 검사한다. 등급이 작성된 제공자의 방어구/무기는 하급/중급 제한을 검사한다. 기존 등급 미작성 일반 장비는 레거시 호환으로 읽으며 정식 신규 콘텐츠는 이 예외에 의존하지 않는다. 정확히 IGNORE로 등록된 대응 도구는 장비 분류에서 제외한다.

소유권은 자동·선택 영수증과 같은 SavedData에서 확정한다. 권리ID는 claim+묶음+항목 순서로 결정하며 선택하지 않은 보상은 소유권을 만들지 않는다. 완료 영수증 정리 후에도 권리·출처가 남는다. QUEST/RAID_DIRECT 같은 출처를 보존하고 새 종류를 가호 보유권으로 기록하지 않는다.

행운은 활성 가호+장신구 합산 후 기존17 상한을 적용한다. 골드 추가분은 보상 확정 시 한 번 계산·소수점 버림·영수증 저장하며 선택 후 교체로 다시 계산하지 않는다. 일반 잔액 credit·송금·상점 정산·제출품 환불에는 보너스를 붙이지 않는다.

전투력은 기존 장비 스킬ID 집계에 습득/특수 스킬 점수를 합쳐 같은ID의 최대값을 사용한다. 장신구는 보유한 합법적 권리 중 고정 점수 상위2개를 그대로 더하고 착용 효과를 다시 점수화하지 않는다. 필요한 정의가 사라지면 임의 점수로 계산하지 않는다.

보상v7은 v1~v6을, 마나v2는 v1을 이관한다. 장신구는 두 슬롯·권리별 현실 쿨타임·진행 회복·공중 횟수·연계 대상을, 특수스킬은 현실 만료·도전별 횟수를 저장한다. 손상 자료는 원본을 보존하고 사용을 거절한다. AI에 독립 마나·소유권 원장이나 자유 실행 권한을 주지 않는다. 새 네트워크·아이템·엔티티가 있어 서버/클라이언트 게임 JAR을 함께 맞춰야 한다.

신별 특수스킬·장신구·습득 목록은 비어 있다. 기획 정의를 연결한 뒤 실제 클라이언트 조작·연출·밸런스를 별도로 확인한다.
