# 조건 기반 스토리 사건 엔진 가이드

## 1. 구현 범위

이 엔진은 신들끼리 장시간 AI 대화를 돌리지 않고도 플레이어 행동과 기존 시스템의 확정 결과가
후속 세계 사건을 일으키게 한다. 사건의 원인·결과·사실·행위자 상태·지식·예약 시각은 서버가
결정하고 `mythictrpg_story.dat`에 저장한다. AI가 없거나 대화 전달이 실패해도 사건 진행과 정식
fallback 문구는 동작한다.

현재 구현된 핵심은 다음과 같다.

- 하나의 strict/atomic reload snapshot에 actor, location, fact, cover story, disclosure policy,
  event, Hook, presentation을 로드한다.
- SERVER, PLAYER, TEAM 범위를 구분한다. TEAM은 사건 발생 시점 구성원을 고정한다.
- 신·미지 존재·세력을 같은 Story Actor로 다루고 존재 상태, 대화 가능 여부, 논리적 장소를 저장한다.
- 신호 역색인으로 관련 사건만 평가하며 예약 사건은 서버 game time으로 처리한다.
- FIXED, FIRST_MATCH, WEIGHTED_ONCE, PLAYER_CHOICE 결과 정책과 ONCE, LIMITED, COOLDOWN 반복 정책을
  지원한다.
- 확률 결과와 definition fingerprint, 선택 결과, 적용 effect ID를 사건 인스턴스에 저장한다.
- 실제 사실과 플레이어·신의 지식을 분리하고 FULL, PARTIAL, WITHHOLD, CONDITIONAL, COVER_STORY
  공개 정책을 적용한다.
- 관계 전이는 외부 effect로 분리하여 실패 시 같은 결과를 유지한 채 재시도한다.
- 플레이어가 접속하지 않은 동안 생긴 presentation은 접속 시 전달한다.

상세 설계와 모든 확정 정책은 저장소 상위의
`추가개발/03_분기형_스토리_이벤트_엔진.md`를 기준으로 한다.

## 2. 데이터팩 경로

모든 경로의 `<namespace>`는 콘텐츠 소유 namespace다. 가운데 `mythictrpg` 폴더는 엔진 경로다.

```text
data/<namespace>/mythictrpg/story_actors/*.json
data/<namespace>/mythictrpg/story_locations/*.json
data/<namespace>/mythictrpg/story_facts/*.json
data/<namespace>/mythictrpg/story_cover_stories/*.json
data/<namespace>/mythictrpg/story_disclosure_policies/*.json
data/<namespace>/mythictrpg/story_events/*.json
data/<namespace>/mythictrpg/story_hooks/*.json
data/<namespace>/mythictrpg/story_presentations/*.json
```

모든 파일은 `schemaVersion: 1`을 요구한다. 알 수 없는 필드·enum·ID, 중복 effect ID, 빠진 참조,
Disclosure 순환, Hook과 대상 사건의 scope/trigger 불일치는 reload 전체를 거부한다. 실패한 reload는
기존 snapshot을 교체하지 않는다.

## 3. Actor, Location, Fact

Actor 예시:

```json
{
  "schemaVersion": 1,
  "type": "ENTITY",
  "initialExistence": "SEALED",
  "initialAvailability": "ABSENT",
  "initialLocationId": "example:old_seal",
  "tags": ["example:unknown_entity"]
}
```

- `type`: `GOD`, `ENTITY`, `FACTION`
- `GOD`은 실제 God Registry ID인 `godId`가 필수다.
- `initialExistence`: `SEALED`, `ACTIVE`, `DEFEATED`, `DESTROYED`
- `initialAvailability`: `AVAILABLE`, `ABSENT`
- 봉인·패배·소멸 상태는 반드시 `ABSENT`다.
- 위치는 블록 좌표가 아니라 `example:hell_base` 같은 논리적 장소다.

Location 예시:

```json
{
  "schemaVersion": 1,
  "titleTranslationKey": "story.location.example.old_seal",
  "dimensionId": "minecraft:the_nether",
  "tags": ["example:hell"]
}
```

Fact 예시:

```json
{
  "schemaVersion": 1,
  "allowedScopes": ["SERVER"],
  "defaultValue": false,
  "adminSummary": "봉인된 존재가 풀려났는가",
  "levels": [
    {"level": 1, "canonicalTranslationKey": "story.fact.example.released.level1"},
    {"level": 2, "canonicalTranslationKey": "story.fact.example.released.level2"}
  ],
  "keywords": ["example:seal", "example:release"]
}
```

Fact의 참/거짓은 세계 진실이며 `levels`는 그 진실을 얼마만큼 자세히 알 수 있는지 나타낸다.
Fact가 참이어도 지식 grant를 받지 않은 플레이어나 신에게는 전달되지 않는다.

## 4. Event

축약 예시:

```json
{
  "schemaVersion": 1,
  "narrativeRole": "WORLD",
  "scope": "SERVER",
  "triggers": [
    {
      "signal": "mythictrpg:scheduled_due",
      "subject": "example:occupy_empty_base",
      "mode": "IMMEDIATE"
    }
  ],
  "prerequisites": {
    "type": "mythictrpg:story_location_empty",
    "location": "example:hell_base"
  },
  "actors": [
    {"role": "intruder", "actor": "example:wanderer"}
  ],
  "repeatPolicy": {"type": "ONCE"},
  "resolutionPolicy": "FIXED",
  "outcomes": [
    {
      "id": "example:occupied",
      "effects": [
        {
          "effectId": "example:move_wanderer",
          "type": "MOVE_ACTOR",
          "actor": "example:wanderer",
          "location": "example:hell_base"
        }
      ]
    }
  ],
  "failurePolicy": "BLOCK_AND_REPORT"
}
```

### 사건 역할과 범위

- `narrativeRole`: `SIDE`, `MAIN_ENTRY`, `MAIN`, `WORLD`, `ENDING`
- `scope`: `SERVER`, `PLAYER`, `TEAM`
- SERVER 사건은 월드에서 한 번, PLAYER 사건은 플레이어별, TEAM 사건은 고정된 팀별로 기록된다.

### 트리거

- `mode: IMMEDIATE`: 현재 전제조건이 맞지 않으면 해당 신호를 소비하고 종료한다.
- `mode: LATCHED`: 전제조건이 나중에 맞을 때까지 신호를 저장한다.
- LATCHED는 `expiresAfterTicks`에 양수 또는 무기한 `-1`을 사용한다.

기본 신호 ID는 다음과 같다.

```text
mythictrpg:gameplay_observed
mythictrpg:quest_completed
mythictrpg:god_unlocked
mythictrpg:god_identified
mythictrpg:relation_transitioned
mythictrpg:fact_changed
mythictrpg:actor_state_changed
mythictrpg:event_resolved
mythictrpg:scheduled_due
mythictrpg:story_hook_accepted
mythictrpg:admin_triggered
```

Gameplay Observation은 관찰 타입 자체도 신호 타입으로 보존하므로 데이터팩은 실제 관찰 ID를
`signal`에 직접 사용할 수 있다. 퀘스트 완료, 신 해금·식별, 관계 전이는 권위 상태 커밋 뒤에만
Story 신호가 발생한다.

### 반복과 결과

- `ONCE`: 범위별 1회
- `LIMITED`: `maximumApplications`까지
- `COOLDOWN`: `maximumApplications`와 `cooldownTicks`를 모두 검사
- `FIXED`: 결과 정확히 하나
- `FIRST_MATCH`: 추가 조건이 맞는 첫 결과
- `WEIGHTED_ONCE`: 작성된 weight로 한 번만 추첨하고 roll을 저장
- `PLAYER_CHOICE`: 권위 API가 instance revision, audience, 현재 조건을 재검증해 한 번만 커밋

`MAIN`과 `ENDING`에 `WEIGHTED_ONCE`를 쓰려면 `randomNarrativeExplicitlyAllowed: true`를 반드시
명시해야 한다. `PLAYER_CHOICE`에는 `choicePolicy`가 필요하며, 시간 제한이 있으면
`timeoutTicks`와 `defaultOutcomeId`를 함께 작성한다.

### Effect

```text
SET_FACT
SET_ACTOR_STATE
MOVE_ACTOR
GRANT_KNOWLEDGE
SCHEDULE_EVENT
RELATION_TRANSITION
EMIT_PRESENTATION
```

모든 canonical effect를 먼저 검증한 뒤 적용한다. `RELATION_TRANSITION`은 기존 동적 신 관계 시스템의
등록 transition ID만 실행한다. 외부 적용 실패 시 사건 결과는 바꾸지 않고
`EXTERNAL_EFFECT_PENDING`으로 저장해 서버 시작 시와 100틱마다 재시도한다.

## 5. Story 조건

기존 Condition Tree 안에서 다음 타입을 사용할 수 있다.

```text
mythictrpg:story_fact
mythictrpg:story_event
mythictrpg:story_actor_state
mythictrpg:story_actor_location
mythictrpg:story_location_empty
mythictrpg:story_knowledge
```

예시:

```json
{
  "type": "mythictrpg:all",
  "conditions": [
    {
      "type": "mythictrpg:story_fact",
      "fact": "example:wanderer_released",
      "scope": "SERVER",
      "expected": true
    },
    {
      "type": "mythictrpg:story_actor_state",
      "actor": "example:lubras",
      "existence": "ACTIVE",
      "availability": "ABSENT"
    }
  ]
}
```

알 수 없거나 읽을 수 없는 상태는 성공으로 간주하지 않고 `UNKNOWN`으로 닫힌다.

## 6. Knowledge와 공개

`GRANT_KNOWLEDGE`의 holder는 다음 중 하나다.

```text
TRIGGER_PLAYER
ACTOR
TRIGGER_TEAM
ALL_PLAYERS_CURRENT_AND_FUTURE
```

팀 지식은 팀 공유 레코드가 아니라 사건 당시 고정된 각 플레이어에게 개별 grant한다.
`ALL_PLAYERS_CURRENT_AND_FUTURE`만 현재·미래 모든 플레이어가 아는 공공지식으로 저장한다.

공개 정책은 다음 다섯 종류다.

- `FULL`: 보유 단계까지 사실 공개
- `PARTIAL`: 작성된 최대 단계까지만 공개
- `WITHHOLD`: 공개하지 않음
- `CONDITIONAL`: 조건 충족 시 공개, 실패 시 작성된 잠금 정책으로 이동
- `COVER_STORY`: 등록된 화자만 작성된 위장 문구를 말함

`StoryDisclosureService.resolve`는 말할 내용을 계산할 뿐 플레이어 지식을 올리지 않는다. 대사나
자막이 실제 전달된 뒤 `commitTransfer`를 호출해야 플레이어가 그 사실을 알게 된다. Cover Story는
실제 Fact로 저장되지 않는다.

## 7. Story Hook과 Presentation

Hook은 누가 어떤 개인·팀 사건을 제안할 수 있는지 제한한다.

```json
{
  "schemaVersion": 1,
  "targetEventId": "example:investigate_base",
  "allowedSpeakerActorIds": ["example:aphrodite"],
  "availabilityConditions": {"type": "mythictrpg:always"},
  "targetScope": "PLAYER",
  "titleTranslationKey": "story.hook.example.investigate.title",
  "summaryTranslationKey": "story.hook.example.investigate.summary",
  "cooldownTicks": 0,
  "maximumAcceptances": 1,
  "confirmationRequired": true
}
```

서버는 수락 시 화자 허용 목록, 확인 여부, 현재 조건, 범위, 횟수, cooldown을 다시 검사한다.
04 AI 모듈은 나중에 자연어 대신 이 등록 Hook ID만 제안하게 연결하면 된다.

Presentation은 정사 결정이 아니라 전달 기회다. 온라인이면 등록 speaker의 신 대화 채널을 먼저
사용하고 실패하면 즉시 시스템 메시지 fallback을 보낸다. 오프라인 플레이어의 기회는 PENDING으로
저장하고 다음 로그인에 전달한다.

## 8. 루브라스 수직 예시

기본 데이터팩의 `demo_*` 파일은 운영 스토리가 아니라 엔진 검증용 축소 사례다. 실제 프로필과
관계도를 받은 뒤 같은 구조로 정식 namespace와 내용을 작성해야 한다.

```text
관리자/게임 원인으로 미지 존재의 봉인 해제
→ 20틱 뒤 비어 있는 루브라스 본거지 점유
→ 실제 Fact 변경, 루브라스만 전체 진실 습득
→ 루브라스 ABSENT, 이유를 숨긴 채 사라졌다는 fallback 표시
→ 아프로디테 Hook에서 플레이어가 1단계 단서 습득
→ 40틱 뒤 루브라스가 AVAILABLE로 귀환
→ 루브라스 Hook에서 플레이어가 2단계 전체 사실 습득
```

수동 시작:

```text
/mythadmin story event trigger-for mythictrpg:demo_release_wanderer <player>
```

## 9. 운영 명령

```text
/mythadmin story health
/mythadmin story event list
/mythadmin story event get <eventId>
/mythadmin story event trigger <eventId>
/mythadmin story event trigger-for <eventId> <player>
/mythadmin story fact get <factId>
/mythadmin story actor get <actorId>
/mythadmin story knowledge get-player <player> <factId>
/mythadmin story schedules
/mythadmin story recovery
```

PLAYER 또는 TEAM 사건은 initiating player가 필요하므로 `trigger-for`를 사용한다. 운영 명령은
진단과 명시적 시작만 제공하며 저장 상태를 임의로 덮어쓰는 편집 명령은 제공하지 않는다.

## 10. 저장과 복구

- SavedData 이름: `mythictrpg_story`
- 데이터 버전: 1
- 정의가 바뀐 진행 중 choice는 fingerprint 불일치로 `RECOVERY_REQUIRED`가 된다.
- 예약 큐는 저장된 schedule로 서버 시작 시 재구성한다.
- 진행 중 외부 관계 effect는 저장된 effect receipt를 기준으로 재시도한다.
- 지원하지 않는 SavedData 버전이나 깨진 필드는 원본 NBT를 보존하고 상태를 READ_ONLY로 닫는다.
- 정상 저장·재시작은 지원하지만 Minecraft 저장 tick 사이 프로세스 강제 종료까지 무손실을
  보장하지 않는다. 그 수준은 별도 write-ahead journal이 필요하다.

## 11. 현재 경계

- PLAYER_CHOICE의 서버 권위 API와 timeout 처리는 구현되어 있으나 전용 선택 GUI/네트워크 화면은
  아직 없다. 콘텐츠 UI가 `StoryEventService.choose`를 호출하도록 연결해야 실제 플레이어 선택 화면을
  사용할 수 있다.
- Hook은 서버 서비스까지 구현되어 있으나 일반 대화가 자동으로 Hook 버튼을 표시하는 04 연동은
  아직 없다. 현재는 테스트·향후 UI/AI 연동 API다.
- Story Actor의 location은 논리 상태이며 실제 NPC 이동이나 경로 탐색을 실행하지 않는다.
- 관계 transition은 외부 레지스트리 reload 순서 때문에 Story 정의 로드 시 레지스트리가 비어 있으면
  존재 검사를 유예한다. 실제 실행 시 없는 transition은 recovery 대상이 된다.
- 데이터팩 reload 도중 이미 RESOLVED인 사건 결과는 바뀌지 않는다. 진행 중 choice의 정의 변경은
  자동 재해석하지 않고 recovery로 보낸다.
- 04의 AI paraphrase, 비밀 제거 Snapshot, opaque Hook token은 이번 03 구현 범위가 아니다.

## 12. 검증

`StoryEngineGameTests`는 다음을 검증한다.

- strict 데이터 전체 로드와 신호 역색인
- 지원하지 않는 SavedData의 원본 보존·fail-closed
- 봉인 해제부터 예약 점유, 행위자 지식, Cover Story, 조사 Hook, 예약 귀환, 전체 사실 공개까지의
  수직 흐름
- 일회 Hook 중복 거부, 온라인 fallback 전달, 저장·로드 후 Fact·Knowledge·Hook 수락 유지

전체 검증 명령:

```text
./gradlew runGameTestServer
./gradlew build
```
