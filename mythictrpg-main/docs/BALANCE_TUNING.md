# 에이전트용 밸런스 조정 안내

수치 변경 작업은 이 문서에서 시작한다. 조정 원본은 아래 JSON과 공용 상수다. 문서·기획 ZIP·전달자료 사본·`build` 산출물을 설정 원본으로 수정하지 않는다. **현재 밸런스를 바꾸라는 요청이 없으면 값은 유지한다.** 인게임 편집기나 추가 관리 명령은 사용하지 않는다.

## 현재 값 빠르게 확인

프로젝트 루트에서 실행한다. 파일을 읽기만 하며 서버·월드·설정·아이템을 변경하지 않는다.

```powershell
.\dev-tools\Get-CombatBalance.ps1
.\dev-tools\Get-CombatBalance.ps1 -Json
.\dev-tools\Get-CombatBalance.ps1 -CheckOnly
```

기본 출력은 마나·시제품 스킬3개·장신구2개·장비11변형·전투력 계수다. `-Json`은 파일 경로와 모든 행동/효과 필드를 구조화해서 출력한다. `-CheckOnly`는 JSON 읽기, 장비 ID 중복, 실제 공격/보호/날카로움과 평가 필드 일치, 우클릭 연결을 진단한다. 실제 Java 스키마·권한·전투 테스트를 대체하지 않는다. **개발 소스 기본값**이므로 실행 중 서버의 다른 JAR·외부 데이터팩 적용값을 확인한 것으로 보고하지 않는다.

## 실제 편집 위치

`src/main/...`는 모두 `mythictrpg-main/` 아래다. 파일 이름과 ID로 검색할 수 있으며, 수치용 사본을 따로 만들지 않는다.

| 조정 대상 | 원본 | 필드·단위 |
|---|---|---|
| 기본 최대/신규 계정 마나, 자동 회복률 | [ManaTuning.java](../src/main/java/com/sande/mythictrpg/skills/ManaTuning.java) | `BASE_MAX_MANA` 마나점, `REGEN_PERCENT_PER_SECOND` **퍼센트/초**. `1.0`은1%, `0.5`는0.5% |
| 검기 | [blade_wave.json](../src/main/resources/data/proto_cbt/mythictrpg/weapon_skills/blade_wave.json) | `manaCost`, `cooldownTicks`; `action.firstDamage/subsequentDamage`, `speed` 블록/tick, `range/radius` 블록, `maximumTargets`, `lifetimeTicks`, `windupTicks/recoveryTicks` |
| 패링 | [timed_parry.json](../src/main/resources/data/proto_cbt/mythictrpg/weapon_skills/timed_parry.json) | 비용/쿨타임; `windupTicks/activeTicks/recoveryTicks`, `halfAngleDegrees` 한쪽 각도, `range` 블록 |
| 대쉬 | [short_dodge.json](../src/main/resources/data/proto_cbt/mythictrpg/learned_skills/short_dodge.json) | 비용/쿨타임/`intrinsicPower`; `windupTicks/movementTicks/evasionTicks/recoveryTicks`, `distance/minimumDistance` 블록 |
| 마나석 | [clear_mana_stone.json](../src/main/resources/data/proto_cbt/mythictrpg/accessories/clear_mana_stone.json) | `effects[type=MAX_MANA].amount` 마나점, `fixedPower` 고정 점수 |
| 매듭 | [blade_knot.json](../src/main/resources/data/proto_cbt/mythictrpg/accessories/blade_knot.json) | `effects[type=LINK_KNOT].amount` **0~1 비율**, `fixedPower` 고정 점수 |
| 장비 실물 속성·평가 점수·무기 스킬 연결 | [combat_power/default.json](../src/main/resources/data/mythictrpg/mythictrpg/combat_power/default.json)의 `items` | `components.minecraft:custom_data.prototype_id`로 해당 행 검색. 아래 동기 수정 규칙 필수 |
| 외형을 공유하는 신규 무기 정의 | `data/<namespace>/mythictrpg/equipment/`; 사용자 그림 예시는 `reuse_demo`, Simply Swords 예시는 `swords_demo` | `attackDamage` 아이템 공격력 기여값, `attackSpeed` 총 공격 속도, `durability`, `enchantments`, `intrinsicPower`, `skills`, `rightClickSkill`. [작성 규격](EQUIPMENT_APPEARANCES.md) |
| Simply Swords 외형별 Better Combat 평타 | `data/mythictrpg/weapon_attributes/ss_*.json` | 원본 동작 데이터 참조. 같은 외형의 모든 정의에 영향. 연결·추가 외형·전투력 제한은 [연동 안내](SIMPLY_SWORDS_INTEGRATION.md) |
| 전체 전투력 및 레이드 힘·보호 계수 | 같은 파일 `model` | `offenseCoefficient`, `strengthWeaponFractionPerLevel`, `protectionHalfValue` 등. [계산·실전 영향 설명](BLESSING_REWARD_POWER_V2.md) 참조 |
| 신규 일반 스킬 | `src/main/resources/data/<namespace>/mythictrpg/weapon_skills/` 또는 `learned_skills/` | [작성 규격](SKILLS_ACCESSORIES_SPECIAL.md). 공통 비용·시간·효과는 각 정의에 작성 |
| 신규 장신구 | `data/<namespace>/mythictrpg/accessories/` | 종류별 `amount`, `cooldownMillis`, `durationTicks` 등. 정의의 단위를 확인 |
| 신별 특수지원 | `data/<namespace>/mythictrpg/special_skills/` | `manaCost`, `limits.perRaidUses/cooldownSeconds/consumables`, `action.amount/range/radius/stunTicks`. 아직 인물별 정의 미배정 |

시간은 일반 스킬20tick=1초다. 특수지원 `cooldownSeconds`는 현실 초, 장신구 `cooldownMillis`는 현실 밀리초다. `%` 필드와0~1 비율을 혼용하지 않는다. 피해량과 전투력 고유 점수는 서로 다른 값이다.

기존 시제품 외 Java 조정 지점도 새로 발견하면 이 표에 추가한다. 현재 석상 시련의 체력/패턴 수치는 [SealedGuardianEncounter.java](../src/main/java/com/sande/mythictrpg/raid/SealedGuardianEncounter.java)에 있다(`MAX_HEALTH`, `SWEEP_RANGE`, `RING_INNER`, `nextAttack`, `Pattern`으로 검색). 정화/응급/더블점프 등 기존 장신구의 정의 밖 공통 판정은 [AccessoryRuntime.java](../src/main/java/com/sande/mythictrpg/accessory/AccessoryRuntime.java)의 `tryCleanse`/`damaged`/`jump` 경로를 확인한다. 이 기존 값들을 이번 작업에서 일괄 재설계하거나 변경하지 않았다. 앞으로 해당 기능을 조정할 때 코드에 남은 실제 밸런스 값은 이름 있는 기능별 설정/상수로 모으고 이 문서를 갱신한다.

## 함께 수정해야 하는 값

**무기 실제 공격과 평가 입력:** `items[].attackDamage`는 전투력 입력이다. 실물 공격은 같은 행 `components.minecraft:attribute_modifiers.modifiers`의 `minecraft:generic.attack_damage`/`amount`가 정한다. 시제품은 두 값을 함께 바꾼다. `minecraft:generic.attack_speed`는 별도 실물 속성이며 플레이어 기본 속도에 더해지는 기여값이다. 표시 총 공격과 기여값도 구분한다.

**신규 `equipment` 정의:** `attackDamage`·`attackSpeed`·`enchantments`에서 실물 구성요소와 전투력 규칙을 함께 생성한다. 기존 시제품 방식처럼 별도 `combat_power.items` 행을 중복 작성하지 않는다. Better Combat의 연속 공격 배율·사거리·쌍수 공격은 현재 전투력 자동 평가 항목이 아니다. 외형의 평타 동작을 바꿨다고 기존 전투력 공식이나 인물별 기준을 임의로 조정하지 않는다.

**보호 인챈트와 평가 입력:** `protectionLevel`과 `components.minecraft:enchantments.levels.minecraft:protection`을 함께 바꾼다. 기존 vanilla 재질의 방어/강인함/내구는 아이템 자체 속성이므로 평가 필드만 바꿔도 실물이 바뀌지는 않는다.

**날카로움 인챈트와 평가 입력:** `sharpnessLevel`과 `components.minecraft:enchantments.levels.minecraft:sharpness`를 함께 바꾼다. 날카로움 증가를 무기 기본 `attackDamage`에도 중복 가산하지 않는다.

**스킬 실행 연결과 점수:** 무기 `rightClickSkill`은 실행 ID, `skills`는 점수다. 같은 ID를 연결하되 실제 효과를 점수 필드로 바꾸려고 하지 않는다. 스킬 피해를 수정한다고 전투력 고유 점수가 자동으로 바뀌지 않는다. 매듭은 지정 습득 스킬의 기본 쿨타임 비율을 사용하므로 대상 스킬의 `cooldownTicks`를 바꾸면 감소 tick도 바뀐다.

**마나:** 새 계정은 공통 기본 마나로 시작한다. 기존 계정의 현재 마나·쿨타임을 새 기본값으로 초기화하지 않는다. 최대치 상승은 즉시 충전하지 않으며 하락은 기존 clamp를 따른다. `ManaAccount.MAX_MANA/MAX_UNITS`와 Snapshot 구 생성자의200은 **이전 저장/통신 호환값**이다. 기본 마나 조정 대상으로 수정하지 않는다. `REGEN_DENOMINATOR`, 저장 나머지, 미세 단위, 데이터 버전도 그대로 둔다. 회복률0~100% 범위 안에서 공용 퍼센트 상수만 조정한다.

## 적용과 기존 보유품

- **소스 JSON/Java를 편집:** 빌드와 JAR 교체·재시작이 필요하다. 소스 파일을 저장한 뒤 게임의 `/reload`만으로 적용됐다고 보고하지 않는다. 서버/클라이언트 공통 코드 변경은 양쪽 JAR을 맞춘다.
- **외부 데이터팩으로 스킬/장신구/전투력 정책을 덮어씀:** 기존 로더의 `/reload` 경로다. 서버팩이 번들 값을 가릴 수 있으므로 출처를 확인한다. 마나 공용 상수에는 이 경로가 없다.
- **OP 시제품 실물 카탈로그:** `PrototypeCatalog.Bundled`는 JAR의 기본 `items.components`를 한 번 읽는다. 외부 데이터팩의 컴포넌트를 바꿔도 OP 지급 카탈로그가 함께 바뀌지 않는다. 정체성 불일치 시 지급 거절이 정상이다.
- **실물 컴포넌트 변경:** 이름·lore·속성·인챈트도 장비 정체성에 포함된다. 기존 실물/영수증은 자동 갱신되지 않는다. 별도 버전 시제품 ID·카탈로그 연결 또는 명시적 이관을 설계한다. ID 문자열만 바꿔 새 지급/스킬 연결이 자동 완성된다고 가정하지 않는다. 현재 장비11개 카탈로그 검증과 기획 항목도 함께 확인한다.
- 전투력은 획득 이력의 최고 장비를 사용한다. 장착만 바꾸거나 기존 가호/원장을 초기화하여 비교하지 않는다. 실제 수주/입장 기준을 수치 조정 과정에서 임의로 신설하지 않는다.

안전 상한·권한·적 대상·기믹/환경 피해 예외·충돌 epsilon·고정 저장 버전을 밸런스 상수처럼 완화하지 않는다. 파서 상한에 막히는 새 수치는 별도 기능/안전성 변경으로 검토한다.

## 변경 후 검증

1. 현재값 도구와 `-CheckOnly`로 변경 위치·중복 필드를 확인한다.
2. Java의 조정 상수/로직을 바꾸면 해당 모듈 컴파일·관련 테스트를 수행한다. JSON만 바꿔도 실제 정의 파서와 연결 검증을 확인한다.
3. **기준 밸런스 검증**은 `PrototypeCatalogGameTests`/`PrototypeAccessoryIntegrationGameTests`/`WeaponSkillStateGameTests` 등에 초기 확정값 기대치가 있다. 의도한 변경에 해당하는 기대치만 갱신하고 계산 근거를 기록한다. 읽어온 현재값을 그대로 기대값으로 사용해 검증을 무의미하게 만들지 않는다.
4. `CombatCastGameTests`/`DefensiveSkillGameTests`의 충돌·시간 경계·취소·권한·5인 격리 fixture와 저장 손상 거절 검증은 기능 계약이다. 별도 숫자로 작성된 fixture를 운영 튜닝에 맞추려고 무조건 수정하지 않는다.
5. 관련 GameTest namespace는 `dev-tools/Test-OfflineGameMatrix.ps1 -ListOnly`로 확인한다. 변경에 맞는 묶음만 먼저 실행하고 불필요한 전체 반복을 피한다. 결과·수치 변경 이유·기존 보유품 영향·배포 여부를 브랜치 인수인계에 기록한다.

이번 구조 정리는 밸런스 값 자체를 변경하지 않았다. [직접 시험 안내](FIRST_COMBAT_PROTOTYPE.md)의 초기값과 사용법은 유지한다.
