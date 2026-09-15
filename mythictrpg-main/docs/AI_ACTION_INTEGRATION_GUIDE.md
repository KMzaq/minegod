# MythicTRPG AI 액션 연동 가이드

## 1. 원칙

AI 응답은 대사와 비권위적인 액션 제안만 생성한다. 실제 게임 상태 변경은
`AiActionGateway`에 등록된 MythicTRPG Validator와 Executor만 수행한다.

2026-09-14: 게임 1.0.1의 선택적 기억 기반에는 `RUMOR_TEST` 시험 모드가 추가됐다.
이 모드에서만 Gateway가 모든 AI Proposal을 실행 전에 거절하고 대화 action scope를
비운다. 기존 `OFF`/`PERSONAL` 모드의 권한·보상 소유권은 유지한다. 기존 Snapshot/
Proposal 생성자는 변경하지 않으며 기억용 월드·신·플레이어·청중·generation은 게임이
발급하는 별도 읽기 계약이다. 설정·한계·소문 저장 IO·검증은
[LP 기억 기반 가이드](../../docs/LP_MEMORY_FOUNDATION_GUIDE.md)를 따른다.
후속 사용자 요청으로 실제 테스트 서버에는 새 JAR과 PERSONAL 설정을 배치했다.
서버 부팅/LLM 검증은 하지 않았으며 RUMOR_TEST의 행동 차단을 활성화한 상태가 아니다.
시작 시 패키지 충돌 수정으로 현재 게임/AI 버전은 1.0.2/0.1.2다. 게임 기억 계약은
`com.sande.mythictrpg.ai.memorycontract`로 이동했고 기존 AI의 `ai.memory`와 분리한다.
필드/저장 형식/권한은 변경하지 않았다. [수정 기록](../../docs/MEMORY_PACKAGE_FIX_20260914.md) 참조.

```text
LLM Proposal
    ↓
AI 응답 모드 정규화
    ↓
AiActionGateway
    ├─ 서버가 현재 대화 세션·행동 신·대상 플레이어 확정
    ├─ 등록된 액션 타입인지 확인
    ├─ 게임 측 Validator 실행
    ├─ 필요하면 플레이어 확인 대기
    └─ 게임 측 Executor 실행
```

모델 출력은 세션 ID나 대상 플레이어를 지정할 수 없다. 이 값은
`AiConversationRuntimeService`의 현재 서버 상태에서만 가져온다. 모델이 지정한 신 ID도
현재 서버가 허용한 대화 상대와 일치해야 한다.

## 2. 현재 등록된 액션

| 프로토콜 타입 | 게임 ID | 실행 정책 | 상태 |
|---|---|---|---|
| `quest_offer` | `mythictrpg:quest_offer` | 즉시 실행 | 등록된 퀘스트 바인딩과 현재 진행 상태를 재검증한 뒤 수주 |
| `item_request` | `mythictrpg:item_request` | 플레이어 확인 | 현재 발화에서 준비 의사를 확인한 뒤 인벤토리와 시선 앞 컨테이너를 재검증·소비 |
| `reward_proposal` | `mythictrpg:reward_proposal` | 즉시 실행 | 행동 신 소유의 등록된 NPC 보상표와 등급만 지급 |
| `relationship_change` | `mythictrpg:relationship_change` | 즉시 실행 | 행동 신에 대한 호감도만 1회 최대 ±50, 전체 -1000~1000 범위에서 변경 |
| `blessing_offer` | `mythictrpg:blessing_offer` | 플레이어 확인 | 데이터팩에 등록된 임시 Minecraft 효과만 적용 |
| `world_interaction` | `mythictrpg:world_interaction` | 플레이어 확인 | 플레이어 위치에 등록된 sound/simple-particle 이벤트만 실행 |
| `player_damage` | `mythictrpg:player_damage` | 즉시 실행 | NPC별 등록 피해 템플릿에 따라 고정·체력 비례·치명 피해 적용 |
| `generated_quest_offer` | `mythictrpg:generated_quest_offer` | 즉시 실행 | 등록된 SIDE 퀘스트 템플릿만 현재 진행도·쿨다운·보상표를 재검증해 생성 |
| `god_relation_transition` | `mythictrpg:god_relation_transition` | 플레이어 확인 | AI 사용이 허용된 등록 전이 ID만 현재 월드 상태·적용 횟수를 재검증해 원자적으로 적용 |
| `npc_visit_request` | `mythictrpg:npc_visit_request` | 미등록 | 외부 물리 NPC 모드 연동 전까지 항상 거절 |

`AiActionTypes`에는 이후 구현할 액션의 안정적인 ID도 예약되어 있다. 예약 ID는 등록된
실행기가 아니며, `AiActionRegistry`에 Definition이 등록되기 전에는 항상 거절된다.

- `mythictrpg:npc_visit_request`

## 3. 공통 계약

- `AiActionProposal`: 액션 ID, 서버 세션, 행동 신, 대상 플레이어, 설명, 제한된 문자열 매개변수
- `AiActionDefinition`: 액션 ID, 확인 정책, Validator, Executor
- `AiActionValidation`: 부작용 없는 허용·거절 결과
- `AiActionExecution`: 서버 커밋 결과와 제한된 결과 상세
- `AiActionResult`: AI 모드에 반환되는 `EXECUTED`, `PENDING_CONFIRMATION`, `REJECTED`, `FAILED`
- `AiActionRegistry`: 게임이 허용한 액션 타입만 보관하는 configure-once allowlist
- `AiActionGateway`: 모든 제안의 단일 진입점

제목, 설명, 매개변수 개수·키·값 길이는 Gateway 진입 전에 제한된다. 등록되지 않은 타입,
비활성 대화, 다른 신 사칭, Validator 거절, Executor 예외는 게임 상태를 바꾸지 않는다.

## 4. 새 액션 추가 순서

1. `AiActionTypes`에 안정적인 `ResourceLocation` ID를 추가한다.
2. 액션 전용 입력을 `proposal.parameters()`에서 엄격하게 해석한다. 필수 값, ID, 수량,
   허용 범위를 모두 검사하고 알 수 없는 자유 형식 명령은 받지 않는다.
3. 부작용 없는 Validator를 작성한다. 현재 인벤토리·관계·퀘스트·쿨다운·권한 등은
   MythicTRPG의 authoritative 상태에서 조회한다.
4. Executor를 작성한다. 실행 직전에도 필요한 상태를 다시 확인하고 기존 게임 서비스로
   원자적으로 커밋한다.
5. `AiActionRegistry.INSTANCE.register(...)`로 게임 초기화 시 한 번만 등록한다.
6. 플레이어 선택이나 아이템 소비처럼 명시적 동의가 필요한 액션은
   `PLAYER_CONFIRMATION_REQUIRED`를 사용한다. Gateway는 60초 동안 최대 8개를 대기시키며,
   `confirm(player, proposalId)` 호출 시 현재 세션과 조건을 다시 검증한다.
7. AI 프롬프트에는 실제로 등록되고 현재 상황에서 허용 가능한 액션과 정확한 ID만 제공한다.
8. 정상 실행, 잘못된 ID, 다른 신 사칭, 중복·만료, 상태 변경 후 재검증을 테스트한다.

AI 모드에 게임 상태 변경 코드를 추가하거나 자유 형식 명령을 Executor로 전달하지 않는다.

## 5. 현재 퀘스트 액션

`QuestOfferAiAction`은 `quest_id` 하나를 받는다. Validator는 다음을 확인한다.

- MythicTRPG ↔ FTB 바인딩 존재
- 서버 전역 완료 여부
- 플레이어의 기존 수주 여부
- FTB 퀘스트와 수주 마커 정의 존재

검증을 통과하면 기존 `QuestRuntimeService.assign(...)`을 호출한다. 이 서비스가 같은 조건을
다시 확인한 뒤 FTB 활성화와 MythicTRPG 수주 상태를 커밋한다.

## 6. 데이터팩 액션 템플릿

`item_request`, `reward_proposal`, `blessing_offer`, `world_interaction`, `player_damage`는
`data/<namespace>/mythictrpg/ai_actions/*.json`에 등록된 `template_id`만 받는다.
AI가 아이템·수량·효과·지속시간·이벤트 ID를 직접 지정할 수 없다.

- `item_request`: `itemId`, `count`. 현재 플레이어 발화가 준비·전달 의사를 명확히 표시해야 하며,
  확인 버튼을 누르는 시점에 인벤토리와 플레이어가 바라보는 블록 컨테이너를 다시 검사한다.
- `reward_proposal`: `rewardTableId`, `tier`. 행동 신이 해당 보상표의 `npcId`와 일치해야 한다.
- `blessing_offer`: `effectId`, `durationTicks`, `amplifier`. 등록된 Minecraft MobEffect만 허용한다.
- `world_interaction`: `eventType=sound|particle`. 명령어, 블록 변경, 임의 좌표, 몹 소환은 지원하지 않는다.
- `player_damage`: `damageMode=flat|max_health_fraction|current_health_fraction|lethal`,
  `damageType`, 선택 모드의 `amount`, `allowDeath`, `maxUsesPerSession`, `cooldownTicks`.
  AI는 피해 수치를 직접 출력하지 않고 등록된 템플릿 하나만 선택한다. 확인창 없이 즉시 실행되므로
  NPC 성향·관계·현재 대화·조우 동기가 물리 행동을 뒷받침할 때만 제안하도록 프롬프트에서 제한한다.
  `allowDeath=false`인 템플릿은 체력 1을 남기며, `lethal` 모드는 반드시 `allowDeath=true`여야 한다.
- `relationship_change`: 템플릿 대신 `affinity_delta`만 받으며 0을 제외한 -50~50 정수로 제한한다.
- `god_relation_transition`: `data/<namespace>/mythictrpg/god_relation_transitions/*.json`에 등록된
  `transition_id`만 받는다. 점수·태그·대상 신은 데이터에서 고정되며 항상 플레이어 확인이 필요하다.
  자세한 형식은 `GOD_RELATION_SYSTEM_GUIDE.md`를 참고한다.

확인 화면은 60초 후 자동 거절되며, 서버는 승인 순간 Validator를 다시 실행한다.

피해 템플릿 예시는 다음과 같다.

```json
{
  "schemaVersion": 1,
  "type": "player_damage",
  "godId": "mythictrpg:fortuna",
  "damageMode": "max_health_fraction",
  "damageType": "minecraft:generic",
  "amount": 0.05,
  "allowDeath": false,
  "maxUsesPerSession": 3,
  "cooldownTicks": 40
}
```

- 가벼운 장난: `flat` 또는 작은 `max_health_fraction`, `allowDeath=false`
- 위압적인 조우 공격: `max_health_fraction`의 `0.333333`, `allowDeath=false`
- 적대적 처형: `lethal`, `allowDeath=true`, `amount` 필드 생략

`amount`는 `flat`에서 최대 2048, 체력 비례 모드에서 `0 초과 1 이하`다.
`maxUsesPerSession`은 1~16, `cooldownTicks`는 0~72000 범위다. 피해 타입은 실행 시점의
Minecraft 동적 `DamageType` 레지스트리에 실제 등록되어 있어야 한다.

## 7. 아직 구현하지 않은 부분

- `npc_visit_request`의 외부 물리 NPC 모드 연결
- 서버 재시작을 넘기는 pending action 저장

서버 재시작 시 확인 대기 액션은 안전하게 폐기된다.

## 8. 즉석 SIDE 퀘스트 액션

`generated_quest_offer`는 `template_id` 하나만 받는다. AI가 작성할 수 있는 값은 제한된
제목과 요약뿐이며, 목표 유형·대상·횟수·진행도 구간·보상표·보상 단계·쿨다운·만료 시간은
`data/<namespace>/mythictrpg/generated_quest_templates/*.json`에서 서버가 로드한다.

- 생성 퀘스트는 항상 `SIDE`다. `MAIN_ENTRY`나 `MAIN`으로 승격할 수 없고 주목 대상을 바꾸지 않는다.
- 플레이어당 활성 즉석 퀘스트는 하나다.
- 목표는 엔티티 처치, 블록 파괴, 성숙 작물 수확, 동물 먹이주기·번식 관찰만 허용한다.
- 보상은 행동 신이 소유한 `NpcRewardTable`의 등록 단계만 지급한다.
- 전투력 보정은 최대 +1단계이며 템플릿의 `maximumTier`를 넘지 않는다.
- 전투력 제공자가 없는 현재 빌드에서는 수치를 추측하지 않고 보정을 적용하지 않는다.
- MythicTRPG SavedData가 진행·만료·쿨다운·지급의 원본이며 FTB Quests는 표시 미러다.

AI 응답 프롬프트에도 위 템플릿의 정확한 ID와 고정된 기계적 조건만 노출된다. 현재 월드
진행도에 맞지 않거나 보상표가 바뀐 제안은 실행 직전 Validator에서 거절된다.
