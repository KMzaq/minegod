# AI Conversation Engine 통합 규격

이 문서는 Mythic TRPG의 게임/RPG 코드와 Local LLM 기반 AI Conversation Engine을 연결하기 위한 계약이다.

## 책임 경계

```text
Game/RPG System -- immutable snapshot/event --> AI Conversation Engine
AI Conversation Engine -- Dialogue + Proposal --> Game/RPG Validator
Game/RPG Validator -- authoritative mutation --> Game/RPG System
```

AI는 캐릭터 해석, 대사, 기억/지식 검색, 다자간 대화 판단, 퀘스트·보상·관계·보증 제안을 담당한다.

게임은 신 등장, 참가자·청중 판정, 실제 퀘스트/보상/가호/월드 변경, 진행도, 관계 원본 저장, HUD 및 네트워크를 담당한다.

AI Proposal을 수신했다고 해서 게임 상태가 바뀌지 않는다. 게임 검증기가 명시적으로 승인하고 자기 시스템에 적용할 때만 변경된다.

## Game → AI 입력

### ConversationSession 생성

게임의 Interaction/Encounter가 이미 참가자를 승인한 뒤 다음 API를 호출한다.

```java
GodAiDialogueService.INSTANCE.startConversation(players, godIds, interactionId);
```

- `interactionId`는 게임이 생성한 참조 ID이며 AI가 생성하지 않는다.
- 플레이어와 신의 ID는 기존 `UUID`와 `ResourceLocation`을 그대로 사용한다.
- `ACTIVE`, `LISTENER`, `OUTSIDE` 판정은 게임이 제공한다. AI는 거리·좌표·물리적 청취 여부를 판정하지 않는다.

### Turn snapshot

매 처리 turn마다 AI는 `ConversationTurnContextSnapshot`을 만든다. 이 값은 다음을 함께 고정한다.

- `sessionId`, `interactionId`, `turnId`, `generation`, `requestId`
- 참가자·대화 이력·현재 주제의 `SessionSnapshot`
- `GameConversationSnapshot`

`GameConversationSnapshot`에는 게임이 허용한 관계 prompt view, 게임 상태 관찰값, `QuestRewardContext`만 들어간다. `ConversationTurnContextSnapshot`에는 그 turn에서만 유효한 AI-owned `PendingVouchInteraction`도 함께 고정된다. Minecraft 런타임 객체는 LLM worker로 전달하지 않는다.

### 관계와 감정

- canonical affinity는 `PlayerMythProfile`이 원본이다.
- `trust`, `respect`, `caution`은 `AdditionalRelationshipAxesProvider` 같은 게임 제공 Provider가 결정한다.
- 현재 감정은 관계와 분리된 `EmotionSnapshotProvider` 입력이다.
- AI는 `RelationshipChangeProposal`만 만든다. AI가 관계 원본을 직접 변경하지 않는다.

### Quest/Reward 제약과 검증 피드백

게임은 `QuestRewardContextProvider`를 설치한다.

```java
GodAiDialogueService.INSTANCE.installQuestRewardContextProvider((session, triggeringParticipantId) ->
    new QuestRewardContext(constraints, validatorFeedback));
```

`QuestRewardConstraints`에는 예를 들어 최대 난이도, 허용 Objective type, 허용 보상 category, 최대 power level, 구체 아이템 카탈로그를 넣는다.

`GameProposalValidationFeedback`은 이전 Proposal의 `ACCEPTED`, `REJECTED`, `MODIFIED` 결과와 허용 조정값을 다음 대화로 전달한다. 이 Provider는 반드시 `sessionId`를 기준으로 값을 선택해야 한다. Session A의 제약이나 피드백을 Session B에 재사용하면 안 된다.

## AI → Game 출력

AI 출력은 `AiProposalGateway`를 통해 전달된다.

```java
AiProposalGateway.INSTANCE.installValidator(envelope -> {
    // 게임의 현재 authoritative state를 다시 검증한다.
    // 승인할 경우에만 게임 시스템의 API로 적용한다.
    return AiProposalGateway.ProposalDecision.accepted("...");
});
```

`ProposalEnvelope`에는 원본 wire proposal, 해당 세션/컨텍스트 snapshot, 그리고 유효할 때만 `typedProposal`이 포함된다.

게임 검증기로 전달하는 authoritative-effect 후보는 다음 셋이다.

| Type | Typed value | 게임이 담당하는 실제 작업 |
| --- | --- | --- |
| `quest_proposal` | `QuestProposal` | Quest ID 생성, 등록, 목표/완료/진행도 |
| `reward_proposal` | `RewardProposal` | 아이템/가호 매핑, 밸런스 검증, 지급 |
| `relationship_change_proposal` | `RelationshipChangeProposal` | 관계 원본 검증·저장 |

다음 셋은 AI Conversation Engine이 보관하는 **비실행 대화 상태/판단**이다. 게임 상태를 변경하지 않고, 관련 관계 변화나 Quest/Reward가 필요할 때에만 위 표의 별도 Proposal을 통해 게임에 제안한다.

| Type | Typed value | AI 내부 처리 |
| --- | --- | --- |
| `vouch_request` | `VouchRequestProposal` | 보증자 답변을 기다리는 session-local pending state 생성 |
| `vouch_resolution` | `VouchResolutionProposal` | 이름이 지정된 보증자의 SUPPORT/DECLINE/UNCLEAR 답변 해석 및 AI memory 기록 |
| `request_judgment` | `RequestJudgmentProposal` | ACCEPT/REJECT/NEGOTIATE/ASK_FOR_VOUCH 등의 NPC 서사적 판단 기록 |

다른 action type, 현재 세션에 없는 신/플레이어 ID, 허용되지 않은 아이템·Objective·보상 category/power level은 AI 단계에서 거절된다. 그래도 게임 검증기는 항상 자신의 최신 상태로 다시 검증해야 한다.

검증 결과를 다음 대화로 보낼 때는 다음처럼 변환할 수 있다.

```java
GameProposalValidationFeedback feedback = decision.asConversationFeedback(proposal.type());
```

## ConversationSession lifecycle

1. 게임이 Interaction/Encounter를 승인하고 Session을 생성한다.
2. `ACTIVE` 플레이어의 채팅은 해당 Session의 FIFO turn queue에 들어간다.
3. 같은 Session은 앞 turn의 LLM 응답 처리까지 끝난 뒤 다음 turn snapshot을 만든다.
4. 서로 다른 Session은 LLM scheduler에서 설정된 동시성만큼 독립 실행될 수 있다.
5. 플레이어가 `OUTSIDE`가 되거나 Session이 종료되면 관련 대기 turn은 취소된다.
6. 취소/종료된 Session의 늦은 응답은 `turnId`/`generation`/현재 active turn 검사에서 버려진다.
7. 보증 요청은 한 Session 안에 하나만 pending으로 유지된다. 보증자·요청자가 `OUTSIDE`가 되거나 `vouchRequestTimeoutSeconds`가 지나면 자동으로 사라진다.

대기 상태의 turn은 다른 Session의 history, 관계, 기억, Example, Quest/Reward Context를 공유하지 않는다.

## 캐릭터·예시·지식

- 신의 기존 `ResourceLocation`과 Datapack God Definition이 identity 원본이다.
- AI persona, character tag, speech style, NPC agent 데이터는 기존 설정/Adapter에서 읽는다.
- Reaction Guideline은 immutable turn snapshot의 체력·날씨·관계 태그·현재 발화만 사용해 별도 상한 안에서 선택된다. 가이드라인은 상황별 반응 방향을 제공하며 실제 게임 행동을 실행하지 않는다.
- Example Retriever는 성격·관계·감정·상황·대화 맥락 태그를 weighted matching으로 비교해 상위 예시를 선택한다.
- Memory는 최근 이력과 장기 중요 기억을 분리해 관련 항목만 넣는다.
- Knowledge는 NPC가 아는 항목과 공개 정책을 통과한 항목만 넣는다.

## 다자간 대화와 보증

Session에는 임의의 플레이어와 신이 참가할 수 있다. AI는 제공된 `ACTIVE`/`LISTENER`/`OUTSIDE` 정보로 대화·비밀 공개·발화자를 판단한다.

`vouch_request`는 신이 보증자에게 의견을 묻도록 하는 AI-owned 대화 상태다. 지정된 보증자의 다음 명확한 답변만 `vouch_resolution`으로 해석한다. 보증은 절대적인 우회 수단이 아니며, 실제 관계 변화·퀘스트/보상 허용은 별도 Proposal과 게임 검증기가 판단한다.

보증 결과는 AI 장기 기억으로만 남을 수 있다. 후속 게임 이벤트에서 신뢰를 조정하려면 게임이 event/snapshot을 제공하고 AI가 `relationship_change_proposal`을 제안한 뒤 게임이 검증해야 한다.

## Local LLM 연결

`LocalLlmClient`는 대화 로직과 로컬 추론 backend의 경계다. 기본 구현인 `LocalOllamaClient`는 Ollama `/api/chat`에 JSON structured output을 요청한다. llama.cpp·vLLM·OpenAI-compatible local endpoint는 같은 interface 구현으로 교체할 수 있다.

- Thinking은 `think: false`로 요청한다.
- `requestTimeoutSeconds`가 HTTP 요청 시간 제한이다.
- `maxConcurrentLlmRequests`는 backend 동시 worker 수다. 기본값은 `1`이다.
- `llmQueueCapacity`를 넘는 요청은 안전하게 실패하며 Session은 다음 turn을 처리할 수 있다.
- malformed JSON, HTTP 실패, backend 미실행, timeout은 Session을 종료하거나 게임 상태를 바꾸지 않는다.

## 설정

설정 파일은 `config/mythictrpg/ai-dialogue.json`이다.

| 설정 | 역할 | 기본값 |
| --- | --- | --- |
| `transcriptMessages` | 현재 대화 이력 상한 (`maxConversationHistory`) | 20 |
| `exampleRetrievalLimit` | 선택 Example 상한 (`maxSelectedExamples`) | 3 |
| `maxRetrievedMemories` | Memory 검색 상한 | 3 |
| `maxRetrievedKnowledge` | Knowledge 검색 상한 | 3 |
| `maxSelectedReactionGuidelines` | turn별 Reaction Guideline 상한 | 3 |
| `maxNpcResponsesPerTurn` | 한 turn의 NPC 대사 상한 | 2 |
| `vouchRequestTimeoutSeconds` | 보증 대기 상태 유지 시간 | 300 |
| `maxConcurrentLlmRequests` | Local LLM 동시 worker 수 | 1 |
| `llmQueueCapacity` | Local LLM 대기열 상한 | 32 |
| `requestTimeoutSeconds` | Local LLM HTTP timeout | 180 |
| `debugLogging` | 개발용 진단 로그 | false |

`debugLogging`은 session/turn/request ID, 참가자 상태, 선택 Example/Memory/Knowledge ID, Quest/Reward 제약, LLM queue·요청 시간, 구조화 결과와 Proposal 검증 결과를 로그에 기록한다. HUD에는 최종 대사와 게임이 허용한 결과만 표시되며, system prompt나 내부 계산은 표시되지 않는다.

## 대화 기록

대화 transcript는 `mythictrpg-dialogue-logs/<player>_<uuid>/`에 플레이어별로 저장된다. 파일명은 생성 날짜·시간과 참여 신 이름이다.

## 연결 시 주의사항

- AI가 제안한 Minecraft item/entity ID를 바로 신뢰하지 않는다. 게임이 준 allow-list에 있는지 확인한다.
- AI가 제안한 관계 변화·보상·퀘스트를 곧바로 저장/지급하지 않는다.
- Quest/Reward Provider와 validator feedback은 session별 snapshot을 반환한다.
- NPC agent의 `conversation` 설정은 동일 물리 NPC의 동시 Session 정책을 선언한다. 기본값은 `allowSimultaneousSessions: false`, `maxSessions: 1`이며, 원격 대화·분신 같은 설정만 명시적으로 여러 Session을 허용한다.
- AI Domain에는 Minecraft runtime object나 mutation handle을 전달하지 않는다.
