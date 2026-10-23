# Simply Swords 외형과 Better Combat 전투 연동

게임 1.0.41 개발 작업의 작성·시험 안내다. 사용자가 정한 방식은 **Simply Swords의 외형과 공격 동작을 활용하고, 보상 무기의 이름·능력·인챈트·스킬은 우리 기획으로 정하는 것**이다. 같은 외형을 여러 무기 정의가 공유할 수 있다. 장신구는 기존 사용자 그림과 장신구 시스템을 계속 사용한다.

이 문서의 사용법은 해당 버전의 코드와 모드를 함께 준비했을 때의 기준이다. `external-mods/combat-compat`는 개발·호환 검증용 의존성 보관 위치이며, 실제 서버나 개인 런처에 설치한 위치가 아니다. 빌드·자동 검증·실제 화면 확인·배포 상태는 브랜치 인수인계의 최신 기록을 확인한다.

## 적용 구조

| 구성 | 실제 역할 |
|---|---|
| Simply Swords | 원본 아이템의 모델·텍스처와 Better Combat 무기 동작 데이터 제공 |
| Better Combat | 평타 동작, 연속 공격, 무기 동작 데이터의 사거리·공격 배율 처리 |
| MythicTRPG 외형 아이템 | 원본 리소스를 참조하는 별도 `SwordItem`. 원본 고유 무기 실행 클래스를 상속하지 않음 |
| MythicTRPG 장비 정의 | 실제 이름·공격력·공격 속도·내구도·인챈트·고유 점수·우클릭 스킬 선택 |
| 기존 보상·스킬 시스템 | 개인 지급 영수증과 획득 이력, 마나·쿨타임, 검기·패링 등 실제 실행 권한 |

예를 들어 Stormbringer의 외형을 쓰는 시험용 검 두 개도 서로 다른 우리 장비다. 외형 때문에 원본 Stormbringer의 번개·고유 패시브가 붙지는 않는다. 원본 리소스를 ID로 참조하므로 Simply Swords의 이미지나 모델을 우리 JAR로 복사해 재배포하지 않는다.

Simply Swords 1.70.2는 다른 모드의 검도 이름으로 분류해 고유 패시브를 자동 부여한다. 실제 모드를 넣은 초기 검사에서 우리 철 장검에도 `weapon_implicit` / `longsword_deflect`가 붙는 것을 확인했다. 이를 막기 위해 공통 초기화에서 Simply Swords의 공개 API `WeaponImplicitRegistry.registerWeaponType`로 우리 외형 두 개를 예약 유형 `mythictrpg:authored_reward_no_native_implicit`에 등록한다. 이 유형에는 원본 고유 능력 정의를 연결하지 않는다. 사후에 아이템 구성요소를 지우거나 지급 정체성 검사를 느슨하게 만드는 방식은 사용하지 않는다.

Simply Swords의 `additionalGemSocketItems` 설정에도 우리 보상 외형을 추가하지 않는다. 기본 목록은 비어 있다. 이를 추가하면 원본 보석 기능이 개입할 수 있으므로 우리 기획만 적용한다는 현재 구성과 달라진다.

**Simply Swords의 원본 아이템 전체에서 능력을 지우는 기능은 아니다.** 원본 아이템을 원본 제작법·전리품·명령으로 얻으면 원본 모드의 능력과 설정을 따른다. 우리 보상은 아래 `mythictrpg:` 외형과 `equipment` 정의 참조로 지급한다. 원본 전리품·제작법까지 제한하는 것은 별도 콘텐츠 결정이다.

## 필요한 모드 조합

기준 환경은 Minecraft **1.21.1 / NeoForge**다. 다음은 이번 개발에서 고정한 조합이며, 다른 버전으로 바꿀 때는 리소스 ID와 API 호환을 다시 확인한다. 파일명·다운로드 출처·해시는 [개발 의존성 목록](../../external-mods/combat-compat/manifest.json)을 기준으로 한다.

| 모드 | 개발 기준 버전 |
|---|---|
| MythicTRPG | 1.0.41 |
| Simply Swords | 1.70.2-1.21.1 |
| Better Combat | 2.4.0+1.21.1, NeoForge |
| Player Animator | 2.0.4+1.21.1, Forge/NeoForge용 파일 |
| Cloth Config | 15.0.140, NeoForge |
| Simply Tooltips | 0.1.5, NeoForge |
| Fzzy Config | 0.7.7+1.21, NeoForge |
| Kotlin for Forge | 5.12.0 |
| Architectury | 기존 프로젝트의 13.0.11 |

선택 의존성 메타데이터의 허용 범위는 Better Combat `2.4.0+1.21.1` 이상·`2.5` 미만, Simply Swords `1.70.2-1.21.1` 이상·`1.71` 미만이다. 범위 안의 모든 조합을 검증했다는 뜻은 아니며, 실제 호환 검사 대상은 표의 고정 버전이다.

실제 서버와 접속 클라이언트는 MythicTRPG와 외형·전투 모드 조합을 맞춘다. 서버만 설치하면 클라이언트가 필요한 모델·전투 동작을 얻는 구조가 아니다. AI 응답·콘텐츠 레지스트리 등 기존 서버 전용 모드를 이 목록 때문에 클라이언트에 추가하지 않는다.

이번 무기 스킬 통신은 `weapon-skills-3`이다. 시전 중 공격 잠금 상태를 서버에서 클라이언트로 전달하므로 이전 MythicTRPG 클라이언트 JAR과 섞어 쓰지 않는다.

Simply Swords와 Better Combat이 모두 있을 때 외형 두 개와 예시 네 개를 활성화한다. 모드가 없는 설치에서도 기존 사용자 그림의 검·장신구와 기존 게임 기능을 사용할 수 있다. 이미 새 외형을 지급한 월드에서 모드를 제거하면 등록 아이템을 잃을 수 있으므로, 무조건 제거·재시작으로 이전 상태를 복구하지 않는다.

## 직접 시험

새 조합으로 게임을 시작한 뒤 치트 허용 또는 OP 권한 2 이상의 플레이어가 실행한다. 처음 네 개를 모두 받으려면 일반 인벤토리 빈 칸 네 개가 필요하다.

```mcfunction
/mythequipment list
/mythequipment grant swords
```

`grant swords`는 `swords_demo` 예시만 지급한다. 기존 `grant examples`는 사용자 검 그림을 이용한 무기 네 개와 펜던트 두 개, 총 여섯 개의 `reuse_demo` 예시를 지급한다. 두 묶음을 혼동하지 않는다.

| 장비 정의 ID / 이름 | 공유하는 외형 | 우클릭 |
|---|---|---|
| `swords_demo:longsword_training` / 수련용 철 장검 | `mythictrpg:ss_iron_longsword` | 스킬 없음 |
| `swords_demo:longsword_wave` / 파문의 철 장검 | `mythictrpg:ss_iron_longsword` | 검기 `proto_cbt:blade_wave` |
| `swords_demo:storm_training` / 수련용 폭풍검 | `mythictrpg:ss_stormbringer` | 스킬 없음 |
| `swords_demo:storm_guard` / 응수의 폭풍검 | `mythictrpg:ss_stormbringer` | 패링 `proto_cbt:timed_parry` |

개별 지급도 가능하다.

```mcfunction
/mythequipment grant swords_demo:longsword_wave
/mythequipment grant swords_demo:storm_guard
```

평타는 좌클릭, 우리 무기 스킬은 **주 손에 들고 우클릭**이다. 스킬 없는 예시에 임의의 우클릭 능력이 생기지는 않는다. 같은 정의를 다시 요청하면 기존 영수증을 사용해 부족한 실물을 복구하며, 마나·쿨타임·획득 이력을 초기화하지 않는다. 외형 아이템을 일반 `/give`로 얻는 것만으로는 우리 보상 스킬 권한이 생기지 않는다.

기존 장신구는 [E 인벤토리 두 칸](ACCESSORY_INVENTORY.md)에 장착한다. 연계 장신구는 평타가 아니라 **무기 우클릭 스킬의 적중**을 사용하며, 지정한 습득 스킬이 쿨타임일 때만 작동한다. 야생·PvP·파티와 소유 동물 보호 규칙은 [야생 전투 안내](WILDERNESS_COMBAT_AND_PARTY.md)를 따른다.

서버는 우리 시전·기절에 의한 공격 잠금 동안 Better Combat 평타 요청을 거절한다. 클라이언트도 새 공격 입력을 막고 Better Combat의 준비 중인 휘두르기를 취소한다. 파티원·보호 대상 동물은 Better Combat이 피격 무적 시간을 해제하거나 광역 피해 배율을 계산하기 전에 대상 목록에서 제외한다. 이후 실제 피해 단계에서도 기존 PvP·파티 보호·패링·회피 판정을 사용한다. 환경 피해나 기믹을 평타로 취급해 면제하지 않는다.

## 기획자가 작성할 항목

새 장비는 기존 `data/<namespace>/mythictrpg/equipment/<path>.json` 정의를 사용한다. 전체 필드와 보상 양식은 [장비 외형·개별 정의 안내](EQUIPMENT_APPEARANCES.md)를 따른다. 이번 외형을 사용하는 정의에는 다음 두 항목이 필요하다.

```json
"requiredMods": ["simplyswords", "bettercombat"],
"itemId": "mythictrpg:ss_iron_longsword"
```

위는 정의에 넣는 필드 일부다. 완전한 파일은 `schemaVersion`, `name`, `attackDamage`, `attackSpeed`, `durability`, `enchantments`, `intrinsicPower`, `skills`, `components` 등 기존 필수 필드를 모두 작성한다. `rightClickSkill`은 선택 사항이다.

`requiredMods`는 선택 모드가 없을 때 해당 정의만 제외하는 로더 메타데이터다. 저장 아이템에 붙는 능력이나 지급 권한이 아니다. 잘못된 필드·형식을 모드 부재로 숨기지 않으며, 모드가 있는데 외형이나 참조 데이터가 없으면 로드 오류를 해결해야 한다. 외형을 여러 보상에 재사용할 때 `itemId`는 같아도 **정의 ID는 각각 다르게** 정한다.

| 기획 입력 | 결정하는 것 |
|---|---|
| 정의 ID와 이름 | 서로 다른 보상의 정체성·표시 이름 |
| 외형 `itemId` | 원본 모델과 연결된 평타 동작 선택 |
| `attackDamage`, `attackSpeed`, `durability` | 실제 아이템 공격력 기여값·총 공격 속도·내구도 |
| `enchantments` | 실제 Minecraft 인챈트와 단계 |
| `rightClickSkill` | 우리 무기 스킬 연결. 없으면 생략 |
| `intrinsicPower`, `skills` | 고유 전투력 평가 점수. 스킬 실행 수치와 별도 |
| `components` | 기존 허용 목록의 설명·희귀도·반짝임 등 표시 |

보상에서는 원본 Simply Swords ID를 직접 넣지 않고 다음처럼 참조한다.

```json
{"type":"equipment","definitionId":"swords_demo:longsword_wave"}
```

장비 정의의 `itemId`에 원본 `simplyswords:*`를 직접 지정하면 파서가 거절한다. 원본 실행 능력까지 붙는 지급 경로를 피하기 위한 규칙이다. 현재 지원되는 연동 외형은 `mythictrpg:ss_iron_longsword`와 `mythictrpg:ss_stormbringer` 두 개다.

실제 인물에게 지급할 정의는 `swords_demo` 시험 이름을 정사로 삼지 않고 기획에 맞는 별도 ID로 작성한다. 신 등급·수주 조건·퀘스트·레이드 지급 조건은 기존 시스템에서 설정한다.

## 에이전트가 조정할 위치

| 조정 대상 | 실제 편집 원본 | 영향 |
|---|---|---|
| 새 시험 무기의 이름·실물 수치·인챈트·스킬 연결·고유 점수 | `src/main/resources/data/swords_demo/mythictrpg/equipment/*.json` | 해당 개별 무기 정의 |
| 검기 피해·마나·쿨타임·거리 | [blade_wave.json](../src/main/resources/data/proto_cbt/mythictrpg/weapon_skills/blade_wave.json) | 같은 스킬 ID를 사용하는 모든 무기 |
| 패링 마나·쿨타임·판정 시간 | [timed_parry.json](../src/main/resources/data/proto_cbt/mythictrpg/weapon_skills/timed_parry.json) | 같은 스킬 ID를 사용하는 모든 무기 |
| 외형별 Better Combat 평타 연결 | `src/main/resources/data/mythictrpg/weapon_attributes/ss_*.json` | 같은 외형을 사용하는 모든 정의의 평타 |
| 원본 모델 참조 | `src/main/resources/assets/mythictrpg/models/item/ss_*.json` | 같은 외형을 사용하는 모든 정의의 표시 |

외형 등록과 원본 참조 목록은 [SimplySwordAppearances.java](../src/main/java/com/sande/mythictrpg/equipment/SimplySwordAppearances.java)의 `APPEARANCES`다. 각 항목의 `itemPath`는 우리 외형 ID, `sourcePath`는 원본 Simply Swords 아이템·모델·동작 데이터 경로를 가리킨다. 활성 연동은 해당 원본 아이템·모델과 Better Combat 동작 데이터가 존재하는지도 검사한다.

상세 조정 원칙은 [BALANCE_TUNING.md](BALANCE_TUNING.md)를 따른다. 같은 외형의 무기 두 개에서 **우클릭 스킬과 수치**는 정의마다 다르게 설정할 수 있다. 현재 평타 동작은 외형 아이템 ID에 연결되어 있으므로, 같은 외형이라도 서로 다른 연속 공격·사거리를 사용하려면 별도 외형 ID/동작 연결을 추가하는 작업이 필요하다.

기존 전투력 공식은 유지한다. 획득 이력에서 선택하는 최고 주 무기와 실제 공격력·날카로움·고유 점수 등을 평가하며, **Better Combat의 연속 공격 배율·사거리·쌍수 공격을 자동으로 별도 전투력 계수로 넣지 않는다.** 그러한 차이에 점수를 줄 때는 기획자가 고유 점수 기준을 정해야 한다. 원본 무기의 기본 능력이나 원본 표시 공격력을 그대로 우리 보상 평가에 합산하지 않는다.

이미 지급한 정의의 이름·외형·속성·인챈트·내구도 등을 바꾸면 실제 아이템 정체성이 달라질 수 있다. 기존 실물과 영수증을 자동 갱신하지 않으므로 **새 정의 ID 또는 명시적 이관**을 준비한다. 현재 정의와 과거 영수증이 다르면 OP 재지급도 거절한다. 같은 스킬 ID의 마나·쿨타임·행동만 조정하는 경우에는 실물 정체성을 바꾸지 않는다.

## 추가 외형과 검증 범위

개발 의존성이 없는 체크아웃에서는 프로젝트 루트에서 다음 명령으로 위 고정 버전의 공식 파일을 받고 SHA-512를 검증한다. 저장 위치는 `external-mods/combat-compat`이며, 실제 서버나 개인 Minecraft의 `mods` 폴더에 설치하는 명령은 아니다. 기존 프로젝트의 Architectury 등 기본 의존성도 필요하다.

```powershell
.\dev-tools\Get-CombatCompatibilityMods.ps1
```

현재 연결하는 외형은 철 장검과 Stormbringer 두 개다. Simply Swords의 모든 원본 아이템을 자동으로 우리 보상 외형으로 변환하지 않는다. 다음 외형은 원본 모델·텍스처·공격 동작 참조를 확인하고 별도 안전한 외형 아이템을 등록한 뒤 장비 정의에서 사용한다. 새 외형도 공개 API의 고유 패시브 제외 등록에 포함해야 한다. 원본 고유 실행 클래스를 상속하거나 예약 유형에 원본 고유 능력을 추가하지 않는다.

새 조합 검증에서는 실제 모드가 있는 경우와 없는 경우를 구분한다. 원본 고유 능력 미적용, 평타·검기·패링·대쉬의 비용과 공격 제한, 파티원·소유 동물 보호, 보상 재지급과 획득 이력을 확인한다. 자동 검사 통과와 실제 클라이언트의 모델·애니메이션·키 조작 확인은 별도다. 실제 서버·개인 런처 배포나 사람끼리의 밸런스 시험을 이 문서만으로 완료 처리하지 않는다.

관련 자동 검사는 `mythictrpg_combat_compat` 5개와 `mythictrpg_combat_optional` 6개다. 전자는 실제 모드 실행 경로이며 `Test-OfflineGameMatrix.ps1`에서 해당 묶음을 선택하면 전투 모드 조합을 자동으로 사용한다. 후자는 모드 유무 양쪽을 확인한다. 기존 회귀 묶음도 같은 조합에서 검사하려면 `-CombatMods`를 붙인다. 각 실행에는 서로 다른 `RunId`를 사용하고 실제 결과를 확인한다.

```powershell
.\dev-tools\Test-OfflineGameMatrix.ps1 -RunId swords_optional_without_mods -Only mythictrpg_combat_optional
.\dev-tools\Test-OfflineGameMatrix.ps1 -RunId swords_with_mods -Only mythictrpg_combat_optional,mythictrpg_combat_compat -CombatMods
```

위 명령은 프로젝트 루트에서 실행하는 검증 방법이며 성공 결과를 미리 뜻하지 않는다. 초기 실제 모드 검사에서 발견된 원본 패시브 자동 부여 등 결함의 수정 후 재검증 상태는 최신 브랜치 기록을 따른다.
