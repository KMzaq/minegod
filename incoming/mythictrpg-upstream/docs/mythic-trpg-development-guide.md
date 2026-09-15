# Mythic TRPG 개발 가이드

## 1. 문서 목적

이 문서는 Minecraft Java 1.21.1 기반 `Mythic TRPG` NeoForge 모드의 현재 상태를 설명한다.
새 개발자가 프로젝트를 실행하고, 데이터 구조와 처리 흐름을 이해하고, 다음 PHASE를 기존 설계 원칙에 맞게 이어가는 것이 목적이다.

현재 기능 구현의 정상 Git 기준점은 다음과 같다.

```text
4e20737fea42160c52c52cf8b95588f0fbf1971b
Phase 4B1 Demeter wheat harvest binding
```

이 기능 기준점에서 이전에 실행한 전체 GameTest는 `158/158` 통과했다. 이후 문서만 따로 커밋하면 최신 Git HEAD와 기능 기준 commit은 서로 다를 수 있다.

## 2. 프로젝트 개요

| 항목 | 값 |
| --- | --- |
| Minecraft | Java Edition 1.21.1 |
| NeoForge | 21.1.248 |
| 빌드 시스템 | ModDevGradle 2.0.144 |
| Gradle | Wrapper 9.2.1 |
| Java | JDK 21.0.11 |
| 프로젝트 경로 | `E:\mine` |
| Mod ID | `mythictrpg` |
| 표시 이름 | `Mythic TRPG` |
| Java package | `com.sande.mythictrpg` |
| 서버 성격 | Private multiplayer server |

이 프로젝트는 시스템 전역 Java 8 설정을 변경하지 않는다. 프로젝트 내부의 `gradle.properties`가 다음 JDK를 Gradle 전용으로 지정한다.

```text
C:\Program Files\Java\jdk-21.0.11
```

## 3. 실행과 검증

### 기본 명령

```powershell
.\gradlew.bat build --no-daemon
.\gradlew.bat runGameTestServer --no-daemon
.\gradlew.bat runClient --no-daemon
.\gradlew.bat runServer --no-daemon
```

프로젝트 전용 PowerShell 실행 스크립트도 사용할 수 있다.

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\run-gradle.ps1 build --no-daemon
```

실제로 사용되는 Java는 다음 명령으로 확인한다.

```powershell
.\gradlew.bat --version
```

정상 출력에는 다음 내용이 포함되어야 한다.

```text
Launcher JVM: 21.0.11
Daemon JVM: C:\Program Files\Java\jdk-21.0.11
```

### Git에서 제외되는 실행 산출물

`.gitignore`는 최소한 다음 항목을 제외한다.

- `.gradle/`
- `build/`
- `run/`
- 로그 및 테스트 월드
- IDE 임시 파일
- 로컬 비밀 설정과 API 키 파일

커밋 전에는 항상 아래를 확인한다.

```powershell
git status --short
git diff --check
git diff --cached --check
```

## 4. 핵심 설계 원칙

### 서버 권위

다음 판단은 Minecraft 서버가 담당한다.

- God unlock 및 appearance legality
- 플레이어 knowledge와 encounter
- condition 평가
- interaction candidate 선택
- interaction 시작 검증과 commit
- gameplay observation의 promotion 가능 여부

AI 또는 클라이언트는 authoritative gameplay rule을 결정하지 않는다.

### Data-driven 구조

God, interaction rule, gameplay promotion은 `ResourceLocation` ID와 datapack JSON을 중심으로 설계한다.
origin, faction, category도 Java enum이 아니라 namespaced ID다.

### Transactional reload

reload 가능한 데이터는 모두 임시 snapshot에 파싱하고 검증한다.
모든 파일이 정상일 때만 새 snapshot과 generation을 교체한다. 하나라도 실패하면 이전 정상 snapshot을 유지한다.

### 단일 source of truth

플레이어 영구 데이터의 원본은 `PlayerMythDataRepository`다.
게임 시스템은 mutable 데이터를 직접 수정하지 않고 service를 통해 변경한다.

### 제한된 처리 비용

- 800 God 전체를 매 tick 또는 매 observation마다 스캔하지 않는다.
- condition과 promotion은 dependency/index 기반으로 후보를 줄인다.
- Vanilla Statistics는 필요한 source만 일정 주기로 sampling한다.
- runtime 상태에는 명시적인 상한과 정리 정책을 둔다.
- raw 위치나 tick별 무한 로그는 영구 저장하지 않는다.

## 5. 패키지 구조

```text
com.sande.mythictrpg
|- client/dialogue       클라이언트 중앙 Dialogue HUD
|- command               OP 전용 관리 명령
|- condition             condition API, parser, evaluator, dependency
|- data/god              God definition, unlock, appearance, identity
|- data/player           플레이어 canonical repository와 service
|- data/world            World SavedData
|- dialogue              dialogue 요청, 검증, presentation
|- gameplay/activity     ACTIVE/IDLE runtime view
|- gameplay/metric       gameplay metric ID
|- gameplay/observation  typed observation과 ingress queue
|- gameplay/promotion    datapack promotion과 signal 실행 runtime
|- gameplay/sampling     Vanilla Statistics watch와 threshold sampling
|- gameplay/stat         Minecraft Statistics read-only boundary
|- interaction           candidate, plan, content, start, commit pipeline
|  |- preview            commit 없는 관리자 interaction preview
|  `- spontaneous        provider resolver, in-flight permit, submission
`- network               Dialogue HUD payload
```

## 6. 영구 데이터 구조

### World Shared Data

`MythicWorldState extends SavedData`

```text
dataVersion = 1
unlockedGods: Set<ResourceLocation>
```

- 변경은 `MythicWorldState` 메서드만 수행한다.
- 실제 변경이 있을 때 해당 메서드가 `setDirty()`를 호출한다.
- 알 수 없는 버전은 초기화하지 않고 원본 NBT를 보존한 채 read-only로 거부한다.

### Player Personal Data

현재 `PlayerMythProfile` 버전은 `dataVersion = 3`이다.

```text
participationStatus: ACTIVE | ARCHIVED
affinities: Map<GodId, int>
encounteredGods: Set<GodId>
identifiedGods: Set<GodId>
obtainedItems: Set<ItemId>
customGameplayCounters: Map<GameplayMetricKey, long>
```

- v1 데이터는 item history 없이, v2 데이터는 custom counter 없이 v3로 migration된다.
- 미래 버전은 조용히 삭제하거나 초기화하지 않는다.
- attachment는 온라인 접근 경계이며 repository와 별개의 authoritative 원본으로 취급하지 않는다.
- 사망, 재접속, 서버 재시작 persistence가 GameTest로 검증되어 있다.

### Participation

- `ACTIVE`: 온라인 여부와 무관하게 전체 플레이어 condition 평가에 참여한다.
- `ARCHIVED`: 세계 condition 평가에서는 제외하지만 데이터는 삭제하지 않는다.
- 자동 archive 정책은 아직 없다.

## 7. God 데이터와 Condition

### God JSON 경로

```text
data/<namespace>/mythictrpg/gods/<path>.json
```

파일 경로가 God ID가 된다.

```text
data/exampleaddon/mythictrpg/gods/custom_god.json
-> exampleaddon:custom_god
```

현재 God schema는 `schema_version = 2`다. 표시 이름은 literal 또는 translation Component를 지원한다.

```json
{
  "schema_version": 2,
  "display_name": { "translate": "god.mythictrpg.demeter" },
  "origin": "mythictrpg:greek",
  "faction": "mythictrpg:benevolent",
  "categories": ["mythictrpg:agriculture"]
}
```

현재 production 리소스에는 Demeter, Aphrodite, Lubras 세 definition이 있다. Demeter는 `appearance_conditions: { "type": "mythictrpg:always" }`로 spontaneous appearance에 참여한다. Aphrodite와 Lubras는 appearance condition이 없으므로 `EXPLICIT_ONLY`다. 세 God 모두 별도 unlock condition은 아직 없다.

### Condition 의미

condition 결과는 다음 3상태다.

```text
MATCH
NO_MATCH
UNKNOWN
```

`ALL`, `ANY`, `NOT`은 short-circuit하며 빈 `ALL`과 `ANY`는 validation error다.

조건 필드가 없을 때의 의미는 서로 다르다.

- `unlock_conditions` 없음: 별도 unlock이 필요하지 않음
- `appearance_conditions` 없음: 자동 또는 자발적 출현 대상이 아님
- `identification_conditions` 없음: condition engine을 통한 자동 식별 없음
- 명시적인 무조건 참: `mythictrpg:always`

현재 built-in condition에는 always, biome, time range, Y range, God unlock, God affinity, item history와 composite 조건이 있다.

플레이어 scope는 `PLAYER`, `ANY_PLAYER`, `ALL_PLAYERS`, `ANY_ONLINE_PLAYER`를 구분한다. ACTIVE 플레이어가 0명이면 `ALL_PLAYERS`는 `NO_MATCH`다.

## 8. Unlock, Appearance, Knowledge

### Automatic Unlock

condition dependency index가 관련 God만 재평가한다. 매 이벤트마다 모든 God을 검사하지 않는다.

```text
상태/이력 변경
-> ConditionChangeDispatcher
-> dependency index
-> GodUnlockService
-> MythicWorldState.unlockGod
```

### Appearance

`GodAppearanceService`는 unlock 상태와 appearance policy/condition을 읽어 자동 출현 가능 여부를 평가한다.
appearance 평가는 read-only이며 encounter나 knowledge를 변경하지 않는다.

### Knowledge

플레이어별로 encounter와 identification을 분리한다.

- encountered: 신을 실제로 경험함
- identified: 신의 정체를 앎

`GodIdentityService`는 knowledge에 따라 실제 이름 또는 미확인 표시를 선택한다.

## 9. 아이템 이력과 Custom Counter

### Item History

`PlayerMythHistoryService.recordItemObtained(...)`가 유일한 기록 진입점이다.
Set 기반이므로 이미 기록된 아이템은 dirty 처리와 invalidation을 반복하지 않는다.

현재 자동 감지 범위:

- ground pickup
- crafting
- smelting
- 명시적 history API 호출

현재 자동 감지하지 않는 범위:

- chest/container 이동
- `/give`
- creative inventory
- trade/container result
- 다른 모드의 직접 inventory insert

실제 God progression에 item history를 사용하기 전 `Inventory Acquisition Coverage` 정책을 별도로 확정해야 한다.

### Custom Gameplay Counter

Minecraft Statistics로 안정적으로 얻을 수 없는 값만 profile v3 counter에 저장한다.
현재 구현된 production metric은 mature crop harvest다.

Vanilla travel/playtime 값은 profile에 복제하지 않고 `ServerStatsCounter`를 source of truth로 유지한다.

## 10. Dialogue와 Interaction Pipeline

### Dialogue HUD

PHASE 3-A에서 중앙 Dialogue HUD와 network payload가 구현됐다.

- 서버가 검증된 dialogue payload를 전송한다.
- 클라이언트는 queue/playback 상태를 관리하고 HUD를 렌더링한다.
- 표시 길이, timing, priority와 text sanitization 경계가 있다.

관리 명령으로 HUD를 직접 시험할 수 있다.

```text
/mythadmin dialogue god <player> <god> <text>
```

### Interaction Candidate와 Plan

PHASE 3-B의 흐름은 다음과 같다.

```text
InteractionSignal
-> immutable InteractionContext
-> interaction rule seed/index
-> legality filters
-> scorers
-> deterministic shortlist
-> InteractionPlan
```

- spontaneous는 appearance legality를 요구한다.
- explicit God call은 appearance condition을 요구하지 않지만 unlock과 explicit policy를 검사한다.
- 후보 정렬은 `score DESC -> God ID ASC`다.
- selector와 director는 side-effect free다.
- Primary 1명, Secondary 최대 2명 구조지만 자동 secondary 선택은 아직 없다.

### Interaction Commit

PHASE 3-C에서 다음 흐름이 연결됐다.

```text
InteractionPlan
-> content preparation
-> generation/audience/start validation
-> interaction runtime reserve
-> encounter commit
-> cooldown commit
-> Dialogue HUD delivery
```

관리자가 text를 직접 입력하는 explicit interaction은 `ScriptedInteractionContentPreparer`를 사용한다. Production spontaneous content resolver에는 아직 provider가 등록되지 않았고, AI content provider도 연결되지 않았다.

관리 명령으로 explicit interaction을 시험할 수 있다.

```text
/mythadmin interaction god <player> <god> <text>
```

### Spontaneous Submission과 Read-only Preview

production spontaneous 요청은 다음 경계를 통과한다.

```text
GameplaySignalSinkRouter
-> GameplaySpontaneousInteractionSink
-> SpontaneousInteractionSubmissionService
-> InteractionContentPreparerResolverRouter.resolve(signal)
-> available preparer가 있을 때만 InteractionOrchestrator
```

현재 production resolver는 등록되지 않아 `UNAVAILABLE`이며, 실제 spontaneous interaction이나 Dialogue HUD 출력은 시작되지 않는다. Resolver가 available인 경우 runtime-only in-flight permit은 player당 1개, server당 최대 1,024개이며 기본 timeout은 1,200 ticks다. Logout, server stop, timeout 이후 돌아오는 stale 응답은 interaction을 시작하지 못한다.

관리자 preview는 planning과 resolver availability만 조회한다.

```text
/mythadmin interaction dry-run god <player> <god>
```

이 명령은 `mythictrpg:explicit_god_call`을 사용하며 encounter, knowledge, profile, cooldown, content preparation, HUD/network를 변경하지 않는다. Production Demeter gameplay signal인 `mythictrpg:demeter_harvest`와는 서로 다른 resolver 입력이다.

## 11. Gameplay Observation

### 공통 흐름

```text
NeoForge server event 또는 stat sampling
-> typed GameplayObservation<P>
-> GameplayIngressService.accept(...)
-> bounded/coalesced tick queue
-> GameplayObservationSink
```

payload에는 `ServerPlayer`, `Entity`, `ItemStack`, raw Map 같은 live mutable Minecraft 객체를 넣지 않는다.

현재 observation type:

- `mythictrpg:block_broken`
- `mythictrpg:animal_bred`
- `mythictrpg:entity_killed`
- `mythictrpg:player_died`
- `mythictrpg:item_first_obtained`
- `mythictrpg:mature_crop_harvested`
- `mythictrpg:vanilla_stat_threshold_crossed`
- `mythictrpg:animal_fed`

### Vanilla Statistics Sampling

promotion definition이 필요한 watch를 compile한다.

```text
gameplay promotion reload
-> WatchedMetricSnapshot
-> online player의 필요한 stat source만 sampling
-> runtime baseline/cursor
-> milestone ThresholdCrossing
-> vanilla_stat_threshold_crossed observation
```

- 첫 sampling은 baseline만 만든다.
- 서버 재시작 후 과거 threshold catch-up은 하지 않는다.
- stat 감소는 reset으로 보고 새 baseline을 만든다.
- persistent cursor와 repeated interval crossing은 아직 없다.
- 기본 interval은 100 ticks, 허용 범위는 20~12,000 ticks다.
- watch 최대 256개, unique source 최대 64개다.

### Animal Feeding

동물 interaction 후보를 잡은 뒤 tick에서 상태 변화를 확인한다.

- `LOVE_MODE`
- `GROWTH_ACCELERATED`

단순히 먹이를 들고 우클릭한 것만으로 성공 observation을 만들지 않는다. 같은 tick의 같은 player/entity type 결과는 deterministic batch와 count로 보존한다.

## 12. Player Activity Runtime

`PlayerActivityService`는 영구 AFK 통계가 아니라 runtime gate를 제공한다.

```text
PlayerActivityState = ACTIVE | IDLE
기본 idle threshold = 6,000 ticks
```

- 로그인 직후 ACTIVE로 시작한다.
- 위치, yaw/pitch, `getLastActionTime` 변화 등을 관찰한다.
- 활동이 다시 감지되면 즉시 ACTIVE로 복귀한다.
- logout과 server stop에서 runtime 상태를 정리한다.
- profile과 SavedData에는 저장하지 않는다.
- `player_activity_changed` observation은 현재 구현하지 않았다.

## 13. Gameplay Promotion

### Datapack 경로

```text
data/<namespace>/mythictrpg/gameplay_promotions/<path>.json
```

파일 경로가 promotion rule ID이며 Vanilla threshold watch ID도 같은 ID를 사용한다.

기본 형태:

```json
{
  "schema_version": 1,
  "observation": "mythictrpg:block_broken",
  "signal": "example:stone_event",
  "priority": 100,
  "attempt_cooldown_ticks": 1200,
  "match": {
    "block": "minecraft:stone",
    "dimension": "minecraft:overworld"
  }
}
```

subject 필드를 생략하면 해당 observation의 wildcard다. 명시적인 `"*"`는 사용하지 않는다.

지원 adapter:

- Vanilla stat milestone
- block broken
- mature crop harvested
- entity killed
- item first obtained
- animal fed
- animal bred
- player died

definition은 `priority DESC -> rule ID ASC`로 정렬된다. exact와 wildcard bucket을 index에서 병합하며 runtime에서 전체 promotion을 scan하지 않는다.

상한:

- 전체 promotion 512개
- effective runtime candidate bucket 64개
- sampling watch 256개

reload는 definition, index, watch, dynamic signal type과 generation을 하나의 immutable snapshot으로 교체한다.

### Typed Signal과 Evidence

선택된 promotion은 `GameplayActionPayload`를 가진 spontaneous `InteractionSignal`로 변환할 수 있다.
각 observation은 전용 typed evidence를 사용한다. position, UUID, raw Map, live Minecraft 객체는 evidence에 포함하지 않는다.

### Promotion Execution Runtime

PHASE 4-A-3b2a에서 다음 service가 구현됐고, PHASE 4-A-3b2b에서 production ingress와 lifecycle에 연결됐다.

```text
GameplayObservation
-> current GameplayPromotionSnapshot
-> online + ACTIVE gate
-> indexed ordered match
-> first cooldown-eligible rule
-> GameplayPromotionSignalFactory
-> attempt cooldown reserve
-> GameplaySignalSink
```

attempt cooldown은 기존 interaction cooldown과 별개의 runtime-only 상태다.

- player당 최대 256개
- server당 최대 8,192개
- generation 변경 시 기존 attempt cooldown 전체 초기화
- 정확히 `currentTick >= nextEligibleTick`일 때 재허용
- 만료 entry는 priority queue를 통해 lazy cleanup
- signal 생성 실패는 cooldown을 소비하지 않음
- sink의 ACCEPTED/UNAVAILABLE/REJECTED/FAILED 및 예외는 cooldown 유지
- 첫 eligible rule 처리 실패 후 하위 rule로 fallback하지 않음

production `GameplayIngressService`는 이 service에 연결돼 있으며, player logout과 server stop에서 promotion runtime 상태를 정리한다. Signal sink는 `GameplaySpontaneousInteractionSink`에 연결돼 있고, 그 다음 단계의 content resolver만 현재 `UNAVAILABLE`이다.

### Production Demeter Gameplay Binding

현재 production promotion은 `mythictrpg:demeter_wheat_harvest` 하나다.

```json
{
  "schema_version": 1,
  "observation": "mythictrpg:mature_crop_harvested",
  "signal": "mythictrpg:demeter_harvest",
  "priority": 10,
  "attempt_cooldown_ticks": 1200,
  "match": {
    "crop": "minecraft:wheat"
  }
}
```

성숙한 `minecraft:wheat`만 일치한다. 당근, 감자, 비트, 네더 와트와 코코아는 일치하지 않는다. Provider가 unavailable이어도 promotion attempt cooldown 1,200 ticks는 소비되지만 encounter, knowledge, 실제 interaction cooldown 및 HUD delivery는 발생하지 않는다.

## 14. Interaction Rule

경로:

```text
data/<namespace>/mythictrpg/interaction_rules/<path>.json
```

형태:

```json
{
  "schema_version": 1,
  "signal": "example:stone_event",
  "bindings": [
    {
      "god_category": "mythictrpg:earth",
      "score": 100,
      "reason": "example:earth_affinity"
    }
  ]
}
```

binding은 `god` 또는 `god_category` 중 정확히 하나를 가진다. 현재 production interaction rule은 `mythictrpg:demeter_harvest` 하나이며, 같은 ID의 signal을 `mythictrpg:demeter`에 직접 연결한다.

```json
{
  "schema_version": 1,
  "signal": "mythictrpg:demeter_harvest",
  "bindings": [
    {
      "god": "mythictrpg:demeter",
      "score": 10,
      "reason": "mythictrpg:demeter_wheat_harvest"
    }
  ]
}

## 15. 관리 명령

모든 명령은 OP permission level 2 이상이 필요하다.

```text
/mythadmin gods
/mythadmin god <id>
/mythadmin evaluate unlocks
/mythadmin appearance <player> <god>
/mythadmin encounter <player> <god>
/mythadmin identify <player> <god>
/mythadmin knowledge <player> <god>
/mythadmin dialogue god <player> <god> <text>
/mythadmin interaction god <player> <god> <text>
/mythadmin interaction dry-run god <player> <god>
```

## 16. 완료된 PHASE

| PHASE | 결과 |
| --- | --- |
| 0 | NeoForge/ModDevGradle/Java 21 프로젝트 기반 |
| 1-A | God reload, World SavedData, player persistence |
| 2-A | Condition engine, player repository, participation |
| 2-B | Gameplay condition과 item history |
| 2-C | Automatic God unlock pipeline |
| 2-D | Appearance와 God knowledge/identity |
| 3-A | 중앙 Dialogue HUD와 network |
| 3-B | Interaction candidate selection과 plan |
| 3-C | Content preparation과 interaction commit pipeline |
| 4-A-1 | Statistics read boundary와 typed observation ingress |
| 4-A-2-1 | Profile v3 custom counter와 mature crop metric |
| 4-A-2-2a | Runtime Vanilla stat sampling boundary |
| 4-A-2-2b | Threshold crossing observation |
| 4-A-2-3 | Animal feeding observation |
| 4-A-2-4a | Player ACTIVE/IDLE runtime |
| 4-A-3a1 | Promotion transactional reload foundation |
| 4-A-3a2 | Promotion-owned sampling watch compilation |
| 4-A-3a3a | Simple promotion adapters |
| 4-A-3a3b | Complex promotion adapters |
| 4-A-3b1 | Typed gameplay action signal과 evidence |
| 4-A-3b2a | Promotion execution runtime과 cooldown |
| 4-A-3b2b | Production gameplay ingress와 promotion runtime lifecycle 연결 |
| 4-A-4a | Spontaneous submission boundary, in-flight permit, stale response guard |
| 4-A-4b | Production spontaneous sink, resolver router, lifecycle wiring |
| 4-A-4c | Read-only interaction preview와 관리자 dry-run |
| 4-B-1 | Production Demeter mature wheat harvest binding |

## 17. 현재 테스트 기준

기능 기준 commit에서 이전에 검증된 결과이며, 문서 작업 중 다시 실행한 결과는 아니다.

```text
gradlew.bat build --no-daemon
-> BUILD SUCCESSFUL

gradlew.bat runGameTestServer --no-daemon
-> 158/158 required tests passed

Java
-> 21.0.11
```

GameTest는 repository, migration, reload transaction, condition, unlock, knowledge, HUD/network, interaction plan/commit, observation, sampling, activity, promotion adapter/signal/runtime, production ingress, spontaneous submission, read-only preview와 Demeter production binding을 검증한다.

잘못된 JSON을 의도적으로 넣어 transactional rejection을 검증하는 테스트가 있으므로 GameTest 로그에 예상된 ERROR stack trace가 나타날 수 있다. 최종 test summary와 Gradle exit status로 성공 여부를 판단한다.

## 18. AI 연동 원칙

AI/RisuAI/local LLM 구현은 다른 PC에서 별도로 진행한다.
현재 repository의 상세 연동 원칙은 `docs/ai-dialogue-integration-bridge.md`를, 다른 PC 실행과 인수인계 절차는 `docs/other-pc-ai-handoff.md`를 참고한다.

핵심 원칙:

- AI는 대화 텍스트만 제안한다.
- unlock, appearance, God 선택, encounter commit을 AI가 결정하지 않는다.
- Minecraft live 객체 대신 bounded plain data request/response를 사용한다.
- API key와 machine-specific endpoint를 source에 저장하지 않는다.
- 최초 연동은 fake provider와 dry-run부터 시작한다.

## 19. 알려진 미완료 사항

- production resolver가 `UNAVAILABLE`이며 실제 external content/AI provider 없음
- Demeter 외 production gameplay promotion 및 interaction rule 없음
- gameplay counter 기반 production God unlock condition 없음
- Aphrodite와 Lubras의 production spontaneous appearance/binding 없음
- provider 미등록으로 실제 spontaneous gameplay dialogue 시작 없음
- player chat observation/listener 없음
- 자유로운 다회차 대화 시스템 없음
- secondary God 자동 선택 없음
- shared audience 없음
- persistent sampling cursor와 repeated interval crossing 없음
- inventory acquisition coverage 미완료
- affinity 실제 증감 규칙 없음
- 퀘스트, 공물, 레이드, NPC, BGM, token 없음
- AI/LLM, Memory/Lorebook 없음

## 20. 추천 다음 단계

Minecraft gameplay 개발과 외부 대화 provider 개발은 역할을 분리한다.

```text
Minecraft 모드
-> authoritative gameplay, God selection, planning, validation, commit

다른 PC의 대화 시스템
-> fake provider 검증
-> 안전한 AI dry-run
-> 별도 RisuAI/local LLM transport
-> resolver/preparer boundary를 통한 통합
```

첫 외부 연동 시나리오는 성숙한 `minecraft:wheat` 수확에서 `mythictrpg:demeter`까지 이미 연결된 production 경로를 사용한다. Provider 등록, 실제 AI transport, content preparation 확장은 별도 승인과 검증 후 진행한다.

## 21. 작업 규칙 체크리스트

새 PHASE를 시작할 때:

1. `git rev-parse HEAD`와 `git status --short`를 확인한다.
2. 기준점이 다르거나 working tree가 dirty면 임의로 진행하지 않는다.
3. 기존 package와 service 경계를 먼저 읽는다.
4. production 데이터와 GameTest fixture를 분리한다.
5. 전체 scan 대신 dependency/index 경로를 사용한다.
6. 미래 data version을 조용히 초기화하지 않는다.
7. 구현 후 build, 전체 GameTest, `git diff --check`를 실행한다.
8. 예상 파일만 stage하고 PHASE 단위로 커밋한다.
9. 다음 PHASE는 현재 정상 상태가 Git에 보존된 뒤 시작한다.
