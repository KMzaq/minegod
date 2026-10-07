# NPC 생활활동 JSON 작성 가이드

`HanesTest` 작업본 · 2026-09-30. 이 문서는 데이터 작성/허용 목록/실행 경계용이다. 실제 빌드·GameTest·서버 배포 결과는 브랜치 인수인계를 따른다. 여기의 예제가 존재하는 것과 실제 서버에서 신이 활동하는 것은 다르다.

## 1. 활동과 신별 정책은 별개

공유 활동 정의는 게임 모드의 다음 위치에 둔다.

```text
data/<namespace>/mythictrpg/npc_activities/<activity_path>.json
data/<God namespace>/mythictrpg/god_activities/<God path>.json
```

활동 파일의 경로가 활동 ID다. 예를 들어 `data/mythictrpg/mythictrpg/npc_activities/real/craft_oak_planks.json`의 ID는 `mythictrpg:real/craft_oak_planks`다. 내부 `id` 필드는 넣지 않는다.

신별 정책의 경로는 **이미 존재하는 실제 게임 God ID**와 일치해야 한다. `mythictrpg:fortuna`의 정책 위치는 `data/mythictrpg/mythictrpg/god_activities/fortuna.json`이지만, 이 문서를 위해 그 정책을 생성하거나 기존 신 설정을 변경하지는 않았다. 새 신 identity나 프로필을 대신 만드는 파일이 아니다.

현재 JAR 원본 리소스에는 다음만 추가했다.

- `decorative/` 공유 활동 22개: 모든 ActivityKind의 연출/행동 예제.
- `real/` 자원 작업 7개: 명시적인 vanilla 재료·레시피 예제.
- **활성 `god_activities` 정책은 추가하지 않았다.** 운영자가 허용 목록을 작성하기 전에는 이 라이브러리가 기존 신을 자율 행동시키지 않는다.

## 2. 활동 정의

```json
{
  "formatVersion": 1,
  "kind": "CRAFT",
  "mode": "REAL",
  "siteTags": ["crafting"],
  "durationTicks": 100,
  "parameters": {
    "recipe_id": "minecraft:oak_planks",
    "grid": "minecraft:oak_log,_,_,_,_,_,_,_,_",
    "prop": "minecraft:oak_planks",
    "guidance": "실제 재료를 사용한다. 성공 결과를 받기 전 완성됐다고 말하지 않는다."
  }
}
```

| 필드 | 규칙 |
|---|---|
| `formatVersion` | 정수 `1` |
| `kind` | 아래 표의 정확한 대문자 enum |
| `mode` | `DECORATIVE` 또는 `REAL` |
| `siteTags` | 1~16개. 각 태그는 영문 소문자와 `_`, 1~40자. 장소 태그와 하나 이상 일치해야 후보가 된다. |
| `durationTicks` | 정수 20~24000. Minecraft 20tick이 통상 1초지만 서버 지연 시 실제 시간은 달라진다. 이동 시간과 별개다. |
| `parameters` | 문자열 값의 object. 최대 16개, 값마다 최대 2048자. `count`도 `"1"`처럼 문자열이다. 알 수 없는 키는 거절한다. |

공통 `parameters`는 `prop`와 `guidance`다. `prop`는 잠시 보이는 소품용 **아이템 ID**이며 아이템을 생성·소유·지급한 증거가 아니다. `guidance`는 활동 판단에 전달되는 제작자의 설명이지 실행 권한이 아니다. 실행은 kind, mode, 실제 재료와 시설, 접근 정책으로 다시 검증한다.

### 활동 종류와 기본 장소

| 종류 | 라이브러리 ID 접미사 / 장소 태그 | 의미 |
|---|---|---|
| `OBSERVE` | `observe` / observe, idle | 주변 관찰 |
| `REST` | `rest` / seat, idle | 휴식 자세 |
| `READ` | `read` / books | 책 시설 탐색과 읽는 자세. 장식 책장에서 책 내용이 생성되지는 않는다. |
| `STROLL` | `stroll` / scenery | 선택한 근처 경관으로 산책 |
| `CONVERSE` | `converse` / company | 근처 실제 상대에게 말 걸기 |
| `LISTEN` | `listen` / company | 근처 상대에게 주의 기울이기 |
| `FOLLOW` | `follow` / company | 실제 상대 따라가기 |
| `GUIDE` | `guide` / guide | 제작자가 등록한 안내 목적지로 이동 |
| `WITHDRAW` | `withdraw` / company | 상대와 거리 두기 |
| `INSPECT` | `inspect` / observe, storage, crafting | 시설 살펴보기 |
| `EAT` | `eat` / seat, idle | 먹는 연출 또는 실제 음식 소비 |
| `DRINK` | `drink` / seat, idle | 마시는 연출 또는 실제 음료 소비 |
| `TRAIN` | `train` / training, idle | 기본 예제는 혼자 연습. 실제 플레이어 대련 초대 예제는 별도다. |
| `CRAFT` | `craft` / crafting | 제작 연출 또는 실제 일반 제작 |
| `REPAIR` | `repair` / repair, crafting | 수리 연출 또는 실제 두 도구 합치기 |
| `COOK` | `cook` / cooking | 조리 연출 또는 실제 음식 레시피 |
| `FARM` | `farm` / crop | 작물 살피기 또는 실제 수확·재파종 |
| `RITUAL` | `ritual` / ritual | 기본 예제는 자세 연출. 실제 소리/입자 템플릿 예제는 별도다. |
| `OFFERING` | `offering` / storage, ritual | 공물 바라보기 또는 실제 NPC 보관함으로 이전 |
| `PLAY` | `play` / play, company | 가벼운 대화형 놀이 |
| `PERFORM` | `perform` / music, idle | 짧은 음표 연출 |
| `SOCIAL` | `social` / npc_company | 실제 근처 신들과 교류 |

위 ID는 모두 `mythictrpg:decorative/<접미사>`이다. DECORATIVE는 재료 소비/생산/수리/가호를 하지 않는다는 뜻이지, 실제 이동·자세·소리·공개 발화까지 없다는 뜻은 아니다. 같은 활동을 REAL로 바꿨다고 임의의 효과나 보상이 추가되지는 않는다.

`READ`의 REAL은 실제 전달받은 책 텍스트가 있는 후보만 사용한다. 책 텍스트는 제작자/플레이어가 쓴 자료일 뿐 세계관의 권위 있는 사실이 아니다. `PLAY`의 `game`은 현재 `conversation` 또는 `dice`를 사용한다. 주사위는 실제 1~6 난수 표시이며 판돈·아이템 보상은 없다. `PERFORM`에는 `notes`(0~24 음표의 쉼표 목록, 최대 64개), `note_interval_ticks`(4~200)를 지정할 수 있다.

## 3. 실제 자원 작업

| REAL 라이브러리 ID | 필수 파라미터 | 실제 결과/제약 |
|---|---|---|
| `mythictrpg:real/craft_oak_planks` | `recipe_id`, `grid` | 작업대에서 원목 1 → 판자 4. 실제 RecipeManager의 일반 shaped/shapeless 레시피로 판정. |
| `mythictrpg:real/cook_baked_potato` | `recipe_id`, `input_id`, `fuel_id` | 화로에서 감자 1 + 석탄 1 → 구운 감자 1. 이 예제는 석탄 한 개를 통째로 소비한다. |
| `mythictrpg:real/repair_iron_pickaxe` | `item_id` | 손상된 같은 철 곡괭이 2 → 수리된 1. Vanilla 두 아이템 수리 규칙이며 강화가 아니다. |
| `mythictrpg:real/eat_apple` | `item_id` | 사과 1개 실제 소비. NPC용 별도 허기/회복 수치를 발명하지 않는다. |
| `mythictrpg:real/drink_potion` | `item_id` | 일반 포션 1개 소비, 그 포션 효과를 NPC에게 적용, 유리병 1 반환. |
| `mythictrpg:real/farm_wheat` | 선택 `crop_id` | 성숙한 밀 수확 후 종자 하나로 age 0 재파종. |
| `mythictrpg:real/offering_apple` | `item_id`, `count` | 주변 저장소의 사과 1개를 NPC 보관함으로 이전. 관계/퀘스트/보상은 바뀌지 않는다. |

### 재료와 보관함

- NPC의 실제 27칸 활동 보관함과 작업 지점 각 축 ±2블록의 vanilla 상자·통·셜커 상자만 사용한다. 주변 저장소는 최대 8개다.
- 플레이어 인벤토리는 소유자/주변 플레이어를 포함해 **읽거나 소비하지 않는다**. 화로 안의 슬롯, 모드 기계, 임의 IItemHandler를 일반 보관함처럼 사용하지 않는다.
- 잠긴 보관함, 아직 loot table이 열리지 않은 보관함, OP 금지 구역, 로드되지 않은 청크는 사용하지 않는다.
- 실제 실행 시 NPC가 지점에서 5블록 이내여야 한다. 재료·출력/빈 용기 공간·접근 권한을 다시 검사한다.
- 정상 실패 시 재료와 결과를 원상복구한다. 외부 모드 콜백이 거래 중 상태/접근을 비정상적으로 바꿔 회수할 수 없으면 성공으로 표시하지 않으며, 이미 남은 출력에 대한 재료까지 환불해 복제하지 않는다. 이 특수 실패는 `EXTERNAL_REENTRY_INTERRUPTED_*_NO_REFUND`로 구분된다.

### 작업별 세부 규칙

`grid`는 쉼표로 구분한 정확히 9칸이며 빈칸은 `_`다. 최소 하나의 재료가 필요하며 `minecraft:air`나 빈 문자열은 빈칸 표현으로 인정하지 않는다. 비어 있지 않은 칸마다 지정 아이템 1개를 사용한다. 모든 ID는 namespace가 포함된 소문자 canonical ID여야 하며 앞뒤 공백은 허용하지 않는다. `recipe_id`/`grid`는 함께 지정한다. 일반 기본 component 아이템을 매칭하며, 특수 NBT/component 레시피를 임의로 추측하지 않는다. 출력과 우유 양동이 같은 제작 잔여물을 함께 보관할 수 있어야 한다.

조리는 실제 smelting/smoking/blasting 타입에 맞는 화로/훈연기/용광로가 필요하며 결과에 FOOD component가 있어야 한다. `durationTicks`가 레시피 조리 시간보다 짧으면 거절한다. 선택한 연료 1개의 연소 시간이 그 조리에 충분해야 한다. 용암 양동이는 빈 양동이를 반환한다. **연료 한 개의 남은 연소 시간을 저장해 다음 조리에 쓰는 화로 자동화 시스템은 아니다.**

REPAIR에서 `recipe_id`와 `grid`를 지정하면 위의 일반 제작 경로를 쓴다. `item_id`만 지정하면 vanilla 두 도구 수리를 사용하므로 일반 인챈트 보존을 약속하지 않는다. 두 방식을 동시에 지정하면 reload에서 거절한다. EAT/DRINK는 `item_id`를 사용하며 recipe/grid를 받지 않는다. 정수 문자열은 `"1"`처럼 쓰며 `"01"`, `"+1"`, `"1.0"`은 허용하지 않는다.

일반 음식의 FoodProperties, 꿀병의 독 해제/유리병, 수상한 스튜의 전용 효과/그릇, 우유의 실제 MILK cure/양동이, 일반 포션 효과/유리병을 처리한다. 후렴과의 순간이동이나 알 수 없는 커스텀 `finishUsingItem` 구현은 지원한 척하지 않고 **아이템을 소비하지 않은 채 거절**한다. 모든 모드 음식·특수 레시피·자동 기계의 범용 지원을 의미하지 않는다.

농사는 vanilla 성숙 작물(밀/당근/감자/비트 계열)과 mobGriefing 허용이 필요하다. 수확물을 아이템 엔티티로도 떨어뜨리는 이중 지급을 하지 않는다. 다시 심을 씨앗과 전체 저장 공간이 확보되지 않으면 수확하지 않는다.

### 의식과 대련 예제

`docs/examples/npc-activities/ritual.registered-template-example.json`은 미활성 예제다. `template_id`를 **해당 신과 일치하는 이미 등록된** `world_interaction` 소리/입자 템플릿으로 바꿔 활동 리소스에 넣는다. 샘플의 `your_namespace:your_existing_ritual_template`은 실존 ID가 아니다. 플레이어 가호·아이템·보상·퀘스트를 의식으로 우회 실행할 수 없다. 그것은 기존 Gateway의 확인/실행 흐름이다.

`train.player-invitation-example.json`은 `REAL + TRAIN + player_company`의 미활성 초대 예제다. 실제 플레이어 수락 전 대련을 시작하지 않는다. 비살상 점수·종료 정책은 게임 대련 시스템이 소유하며 이 활동 JSON에 임의 피해량이나 보상량을 넣지 않는다.

## 4. 기존 신에 허용 목록 연결

`docs/examples/npc-activities/god-policy.decorative-example.json`을 **검토한 뒤** 원하는 실제 God ID 경로의 정책으로 복사한다. 이 예제는 22개 연출 활동을 모두 열거하고 자율 실행은 꺼 두었다.

```json
{
  "formatVersion": 1,
  "activities": [
    "mythictrpg:decorative/observe",
    "mythictrpg:decorative/read",
    "mythictrpg:real/craft_oak_planks"
  ],
  "autonomous": false,
  "radius": 8,
  "decisionIntervalTicks": 400
}
```

`activities`는 실제 존재하는 ID 1~64개이며 중복을 허용하지 않는다. `radius`는 2~16, `decisionIntervalTicks`는 100~24000이다. `autonomous:false`에서도 허용된 활동에 대한 운영자/대화 요청 경로는 사용할 수 있다. 자율 판단을 원하면 검토 후 true로 바꾸며, 실제 AI planner가 등록되어 있어야 한다. `true`는 소환·순간이동·레이드 중단·행동 성공을 보장하는 스위치가 아니다.

신의 성격과 다른 대사 스크립트를 이 파일에 복제할 필요는 없다. 허용 활동/시설/재료는 게임 데이터, 그 중 무엇을 할지와 반응은 페르소나·현재 상황을 받은 판단의 영역이다. 자연어로 “그 물건 쓰지 마”라는 부탁은 사회적 요청이고 신이 성격에 따라 거절할 수 있다. 절대 금지는 아래 OP 명령으로 설정한다.

## 5. 장소 지정과 수동 확인

태그만 붙였다고 실제 작업대·화로·성숙 작물이 생기지는 않는다. 자동 시설 인식은 증거 기반 후보이며, 제작자가 직접 장소를 지정할 수도 있다.

```text
/mythnpc place add reading_corner books,seat
/mythnpc place add garden_view scenery,guide
/mythnpc activity start <기존_God_ID> mythictrpg:decorative/observe
/mythnpc activity status <기존_God_ID>
/mythnpc activity stop <기존_God_ID>
/mythnpc deny private_storage 3
/mythnpc allow private_storage
```

place/deny의 위치는 명령을 실행한 플레이어의 현재 위치다. `deny`는 각 축 ±반경인 구역이며 `allow`는 그 이름의 설정만 제거한다. 장소 등록은 블록/건축물을 생성하지 않는다. 활동 시작 명령도 실체가 없는 신을 자동 소환하지 않는다. 안내 지점은 `guide`, 경관은 `scenery`, 보관함은 `storage` 등 목적에 맞는 태그를 사용한다.

## 6. 수정 후 적용

소스 JAR 내부 리소스를 수정하면 JAR 재빌드·교체 절차가 필요하다. 서버의 기존 활성 datapack에 같은 `data/...` 경로로 추가/덮어쓰기하면 코드 재빌드 없이 datapack reload로 정의를 다시 읽을 수 있다. **서버 JAR을 이 문서 작성 과정에서 배포하거나 실제 월드를 변경하지 않았다.**

정의/정책을 reload하면 generation이 바뀌어 오래된 AI 선택·진행 중 활동을 그대로 확정하지 않는다. 원자적으로 읽은 전체 정의에서 잘못된 필드, 없는 활동 참조, 중복 목록 등을 검증한다. `/reload` 성공과 실제 재료 소모/애니메이션/대화 테스트 성공은 별도로 확인한다.

지도마다 다른 시설 등록·OP 금지·활동 이력은 월드 SavedData이며 정적 공유 라이브러리와 다르다. 기본 라이브러리는 JAR 리소스이므로 새 월드에 29개 JSON을 매번 복사할 필요가 없다. 반대로 월드 datapack override와 등록 시설은 그 월드의 설정이다.
