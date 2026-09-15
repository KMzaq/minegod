# AI Conversation System 입출력 연동 계약

**대상 독자:** MythicTRPG의 퀘스트, 보상, 상호작용, 관계, HUD, 네트워크 기능을 담당하는 제작자  
**적용 대상:** Local LLM 기반 God NPC 대화 시스템  
**현재 구현 기준:** Java/NeoForge 모듈의 AI Conversation Engine

이 문서는 게임 시스템과 AI 대화 시스템 사이에서 주고받는 데이터와 책임을 정의한다. 구현의 원본 타입은 Java 클래스이며, 아래 JSON은 다른 제작자가 구조를 빠르게 이해하기 위한 **언어 중립 표현**이다. 이것은 HTTP API나 별도 서버 프로토콜이 아니다.

더 넓은 책임 경계와 설정 설명은 [AI_CONVERSATION_INTEGRATION.md](AI_CONVERSATION_INTEGRATION.md)를 참고한다.

## 1. 핵심 원칙

~~~text
Minecraft / RPG System
        │  immutable snapshot / event / interaction context
        ▼
AI Conversation Engine
        │  speech + non-authoritative proposal
        ▼
Game Validator
        │  authoritative validation + mutation
        ▼
Minecraft / RPG System
~~~

AI는 대사와 제안을 만든다. 게임 시스템만 실제 퀘스트, 보상, 가호, 아이템, 관계, 월드 상태를 변경할 수 있다.

따라서 다음 두 규칙은 예외가 없다.

1. AI Proposal을 받았다고 게임 상태를 바로 바꾸면 안 된다.
2. 게임 검증기는 AI가 만들 때 사용한 Snapshot이 아니라 **현재의 authoritative state**를 다시 검증해야 한다.

## 2. ID와 공통 값 규칙

| 값 | 형식 | 소유자 | 비고 |
|---|---|---|---|
| player UUID | UUID | Minecraft/Game | 플레이어 식별자 |
| God ID | ResourceLocation, 예: mythictrpg:lubras | 기존 MythicTRPG God Definition | AI가 새로 만들지 않음 |
| interactionId | UUID 또는 없음 | Interaction/Encounter 시스템 | AI는 참조만 보관 |
| sessionId | UUID | AI Conversation Engine | 하나의 독립 대화 단위 |
| requestId | UUID | AI Conversation Engine | 하나의 Local LLM 요청 식별자 |
| participantId | player:UUID 또는 divine:namespace:path | AI Conversation Engine | LLM 결과의 대상 지정에 사용 |

예시:

~~~text
player UUID:       013563d6-15a4-4c7a-8094-eb2cd60c8be4
player participantId: player:013563d6-15a4-4c7a-8094-eb2cd60c8be4
God ID:            mythictrpg:lubras
divine participantId: divine:mythictrpg:lubras
~~~

참가자 상태는 게임 시스템이 판정해 AI에 전달한다.

| 상태 | 의미 | AI의 사용 방식 |
|---|---|---|
| ACTIVE | 직접 대화 중 | 발화·Proposal 대상이 될 수 있음 |
| LISTENER | 듣기만 하는 중 | 청중·비밀 공개 판단에 포함 |
| OUTSIDE | 대화 범위 밖 | 들을 수 없고 대상이 될 수 없음 |

AI는 거리, 시야, 음성 거리, 파티 여부를 직접 계산하지 않는다.

## 3. Game → AI: 세션 제어 입력

### 3.1 Interaction에서 세션 시작

기존 Interaction/Encounter 시스템이 참가자와 신을 확정한 뒤 호출한다.

~~~java
GodAiDialogueService.StartConversationResult result =
    GodAiDialogueService.INSTANCE.startConversation(
        approvedPlayers,
        approvedGodIds,
        interactionId
    );
~~~

입력 의미:

| 인수 | 타입 | 필수 | 설명 |
|---|---|---|---|
| approvedPlayers | Collection of ServerPlayer | 예 | 게임이 ACTIVE로 승인한 플레이어 |
| approvedGodIds | Collection of ResourceLocation | 예 | 기존 God Definition의 God ID |
| interactionId | UUID | 아니오 | 기존 Interaction/Encounter의 참조 ID |

결과 상태:

| status | 의미 | 게임 측 처리 권장 |
|---|---|---|
| STARTED | 세션 생성 성공 | sessionId를 interaction 또는 UI 상태에 보관 |
| UNKNOWN_GOD | God Definition이 없음 | 잘못된 God ID를 수정 |
| PERSONA_MISSING | 해당 God ID의 AI persona가 없음 | AI 콘텐츠 설정 추가 |
| NPC_BUSY | NPC의 동시 대화 정책 한도 초과 | 잠시 후 재시도 또는 안내 표시 |
| INVALID_PARTICIPANTS | 플레이어 또는 신 목록이 비어 있음 | Interaction 입력 수정 |

세션을 시작하면 전달한 플레이어는 ACTIVE가 된다. 한 플레이어는 현재 구현에서 동시에 하나의 AI 대화 세션에만 참가한다. 새 세션을 시작하면 기존 세션에서 분리된다.

### 3.2 청중과 참가자 상태 변경

근처 플레이어를 대화 청자로 넣을 때:

~~~java
GodAiDialogueService.INSTANCE.addListener(sessionId, nearbyPlayer);
~~~

게임의 거리·시야·인스턴스 판정에 따라 상태를 바꿀 때:

~~~java
GodAiDialogueService.INSTANCE.setParticipantState(
    server,
    sessionId,
    playerId,
    AiDialogueModels.ParticipantState.LISTENER
);
~~~

LISTENER가 메시지를 보내면 ACTIVE로 승격된다. OUTSIDE가 된 플레이어의 대기 turn은 취소되며, 마지막 visible player가 나가면 세션은 닫힌다.

플레이어가 자발적으로 나갈 때:

~~~java
GodAiDialogueService.INSTANCE.leave(player);
~~~

로그아웃 이벤트에서는 다음을 호출한다.

~~~java
GodAiDialogueService.INSTANCE.onPlayerLoggedOut(player);
~~~

### 3.3 플레이어 발화 입력

활성 세션의 플레이어 채팅은 기존 채팅 훅에서 아래 메서드로 전달된다.

~~~java
GodAiDialogueService.INSTANCE.handlePlayerText(player, text);
~~~

AI 엔진은 해당 세션 안에서 발화를 FIFO 순서로 처리한다. 같은 세션의 이전 LLM 응답이 끝나기 전에는 다음 turn의 Snapshot을 만들지 않는다.

일반 공개 채팅과 AI 대화 입력을 구분하는 규칙은 기존 채팅 시스템이 소유한다. 현재 기본 UX는 AI 대화 중 일반 채팅이 AI 입력이며, 느낌표로 시작한 메시지는 공개 채팅이다.

## 4. Game → AI: Turn Snapshot 입력

AI는 매 플레이어 발화마다 한 번의 immutable Turn Snapshot을 만든다. 이 Snapshot은 대기열에 들어간 turn의 세션·관계·게임 컨텍스트를 고정해, 다른 세션의 정보가 섞이지 않게 한다.

Java 원본 타입:

~~~java
ConversationTurnContextSnapshot(
    UUID sessionId,
    long turnId,
    long generation,
    UUID requestId,
    Instant queuedAt,
    Instant dispatchedAt,
    String triggeringParticipantId,
    UUID triggeringPlayerId,
    SessionSnapshot session,
    GameConversationSnapshot gameSnapshot,
    Optional<PendingVouchInteraction> pendingVouchInteraction
)
~~~

개념적 형태:

~~~json
{
  "sessionId": "b31c8d1a-...",
  "turnId": 4,
  "generation": 1,
  "requestId": "fe9de65c-...",
  "triggeringParticipantId": "player:013563d6-...",
  "triggeringPlayerId": "013563d6-...",
  "session": {
    "interactionId": "optional-game-owned-uuid",
    "participants": [],
    "location": {
      "dimension": "minecraft:overworld",
      "x": 120,
      "y": 64,
      "z": -30
    },
    "history": [],
    "currentTopic": "",
    "pendingInteraction": ""
  },
  "gameSnapshot": {
    "relationshipsByPlayerParticipantId": {},
    "gameState": {},
    "questRewardContext": {}
  }
}
~~~

Minecraft 런타임 객체, ServerPlayer, Entity, Level, Registry 접근 객체, 저장 핸들, 변경 가능한 컬렉션은 LLM worker로 전달하면 안 된다. 원시 값, 문자열, 불변 DTO만 전달한다.

### 4.1 SessionSnapshot

세션은 다음 정보를 가진다.

| 필드 | 설명 |
|---|---|
| sessionId | AI가 만든 독립 대화 ID |
| interactionId | 선택적 게임 소유 참조 ID |
| participants | Player/Divine, 상태, 표시 이름, ID |
| location | 세션 시작 위치의 dimension, x, y, z |
| createdAt | 생성 시각 |
| history | 최근 대화 기록 |
| currentTopic | AI가 유지하는 짧은 주제 |
| pendingInteraction | 호환용 대기 상태 문자열 |
| requestInFlight | 현재 LLM 요청 처리 여부 |

대화 기록 turn의 형태:

~~~json
{
  "messageId": "uuid",
  "createdAt": "ISO-8601 timestamp",
  "speakerId": "player:UUID 또는 divine:namespace:path",
  "text": "대화 내용",
  "listenerIds": ["player:UUID"]
}
~~~

### 4.2 관계와 현재 감정 입력

게임은 플레이어 참가자별로, 그 세션에 있는 각 신에 대한 관계와 감정을 제공한다.

~~~json
{
  "relationshipsByPlayerParticipantId": {
    "player:013563d6-15a4-4c7a-8094-eb2cd60c8be4": {
      "mythictrpg:lubras": {
        "metrics": {
          "affinity": 45,
          "trust": 20,
          "respect": 35,
          "caution": 10
        },
        "emotion": {
          "intensities": {
            "curiosity": 40,
            "anger": 0
          }
        },
        "derivedTags": ["R_ACQUAINTANCE", "E_CURIOUS"],
        "interpretation": ["아직 신뢰가 충분하지 않다."]
      }
    }
  }
}
~~~

범위:

| 값 | 허용 범위 | 원본 소유자 |
|---|---|---|
| affinity | -100 ~ 100, AI prompt view | 기존 PlayerMythProfile의 affinity |
| trust | -100 ~ 100 | 게임이 정한 저장소 |
| respect | -100 ~ 100 | 게임이 정한 저장소 |
| caution | 0 ~ 100 | 게임이 정한 저장소 |
| emotion intensity | 0 ~ 100 | 게임 이벤트 또는 감정 저장소 |

affinity, trust, respect, caution은 장기 관계다. anger, happiness, annoyance, curiosity, sadness, gratitude, disappointment, fear 등은 현재 감정이며 관계와 별개로 제공한다.

현재 기본 Adapter는 PlayerMythProfile의 affinity를 읽고, 추가 축은 AdditionalRelationshipAxesProvider, 감정은 EmotionSnapshotProvider에서 읽는다. 이 값들은 AI가 저장하거나 수정하지 않는다.

### 4.3 gameState 입력

gameState는 게임이 AI에게 알려 주기를 허용한 관찰값이다. 현재 Adapter가 제공하는 예시는 다음과 같다.

~~~json
{
  "available": true,
  "dimension": "minecraft:overworld",
  "x": 120,
  "y": 64,
  "z": -30,
  "dayTime": 6000,
  "raining": false,
  "thundering": false,
  "health": 18.0,
  "maxHealth": 20.0,
  "mainHandItem": "minecraft:iron_sword",
  "nearbyEntities": [
    "좀비 [EntityType[minecraft:zombie]]"
  ],
  "authority": "Minecraft server state is authoritative. The LLM cannot mutate it."
}
~~~

다른 RPG 시스템은 퀘스트 단계, 최근 전투, 관측 이벤트처럼 대화에 필요한 값만 추가할 수 있다. 다음은 전달하지 않는다.

- 실제 게임 객체와 API 핸들
- 비공개 플레이어 데이터
- AI가 볼 필요가 없는 전체 인벤토리·전체 월드 데이터
- 다른 세션·다른 플레이어를 위한 Constraint 또는 validator feedback

## 5. Game → AI: Quest/Reward Constraint와 검증 피드백

게임 담당자는 turn별 제약을 아래 Provider로 공급한다.

~~~java
GodAiDialogueService.INSTANCE.installQuestRewardContextProvider(
    (session, triggeringParticipantId) -> new QuestRewardContext(
        constraintsFor(session.sessionId(), triggeringParticipantId),
        feedbackFor(session.sessionId())
    )
);
~~~

입력 DTO:

~~~json
{
  "constraints": {
    "quest": {
      "maxDifficulty": 3,
      "allowedObjectiveTypes": ["kill", "collect"],
      "allowedConcreteItemIds": ["minecraft:blaze_rod"]
    },
    "reward": {
      "maxPowerLevel": 1,
      "allowedCategories": ["material", "cosmetic"],
      "allowedConcreteItemIds": ["minecraft:iron_ingot"]
    }
  },
  "validationFeedback": [
    {
      "proposalType": "reward_proposal",
      "status": "REJECTED",
      "reason": "현재 단계에서는 번개창 보상을 줄 수 없습니다.",
      "allowedAdjustments": {
        "maxPowerLevel": "1",
        "allowedCategories": "material,cosmetic"
      }
    }
  ]
}
~~~

제약 의미:

| 제약 | 설명 |
|---|---|
| quest.maxDifficulty | 0~100. AI가 제안할 수 있는 최대 난이도 |
| quest.allowedObjectiveTypes | 게임이 지원하는 Objective type allow-list |
| quest.allowedConcreteItemIds | 퀘스트에 언급 가능한 구체 item ID allow-list |
| reward.maxPowerLevel | 0~100. AI가 제안할 수 있는 최대 보상 세기 |
| reward.allowedCategories | 허용 보상 범주 allow-list |
| reward.allowedConcreteItemIds | 보상에 언급 가능한 구체 item ID allow-list |

빈 allow-list와 최대값 0은 안전한 기본값이다. AI는 구체적인 퀘스트/보상 대신 서사적 개념만 제안하거나 거절해야 한다.

### 반드시 지킬 세션 격리 규칙

Provider는 반드시 **sessionId 기준**으로 제약과 피드백을 반환한다.

~~~text
Session A: A + Hephaestus, reward.maxPowerLevel = 3
Session B: B + Athena,     reward.maxPowerLevel = 1
~~~

이 경우 Session A의 제약 또는 “번개창 거절” 피드백이 Session B의 Athena 대화에 들어가면 안 된다. 전역 최근 피드백 변수, 마지막 퀘스트 변수, 단일 캐시를 공유하지 않는다.

권장 저장 형태:

~~~text
Map<UUID, QuestRewardContext> bySessionId
Map<UUID, List<GameProposalValidationFeedback>> feedbackBySessionId
~~~

## 6. AI 내부 → Local LLM 출력 형식

이 부분은 로컬 모델을 교체하거나 프롬프트·응답 파서를 수정하는 제작자를 위한 구조다. Local LLM은 아래 JSON 객체만 반환해야 한다.

~~~json
{
  "speech": [
    {
      "speakerId": "divine:mythictrpg:lubras",
      "text": "그 힘을 원한다면, 먼저 네 의지를 보여라.",
      "audienceParticipantIds": [
        "player:013563d6-15a4-4c7a-8094-eb2cd60c8be4"
      ]
    }
  ],
  "currentTopic": "힘을 얻기 위한 시험",
  "proposals": []
}
~~~

| 필드 | 타입 | 설명 |
|---|---|---|
| speech | 배열 | 0개 이상의 NPC 대사 |
| speech.speakerId | String | 현재 Session의 divine participantId만 허용 |
| speech.text | String | NPC 대사 |
| speech.audienceParticipantIds | String 배열 | 현재 visible player participantId만 허용 |
| currentTopic | String | 짧은 현재 주제. 비어 있어도 됨 |
| proposals | 배열 | 아래 7절의 비권위적 Proposal |

응답은 JSON 하나만 반환해야 한다. 마크다운, 코드 펜스, 설명문을 붙이지 않는다. 기본 LocalOllamaClient는 Ollama chat API에 JSON format과 think false를 요청한다.

AI 엔진은 speakerId, audience, 대사 길이, Proposal 타입을 다시 검증한다. 유효하지 않은 발화는 HUD에 표시되지 않는다.

NPC 대사는 현재 기존 DialoguePresentationService를 통해 HUD로 전송된다. 별도의 게임 측 대사 렌더러 콜백은 현재 공개 I/O 계약에 포함되지 않는다. HUD를 교체하려면 AI 결과를 직접 실행하는 것이 아니라 별도 presentation adapter를 합의하여 추가한다.

## 7. AI → Game: Proposal Envelope

LLM의 raw Proposal은 먼저 allow-list와 구조 검증을 통과한 뒤 typed Proposal으로 변환된다. 퀘스트·보상·관계 변화 Proposal만 게임 검증기로 전달된다.

게임 검증기가 받는 Java 타입:

~~~java
AiDialogueModels.ProposalEnvelope(
    UUID sessionId,
    Proposal proposal,
    SessionSnapshot session,
    ConversationContext context,
    Optional<AiGameProposal> typedProposal
)
~~~

개념적 형태:

~~~json
{
  "sessionId": "b31c8d1a-...",
  "proposal": {
    "type": "quest_proposal",
    "title": "화산 재 수집",
    "summary": "북쪽 균열에서 화산 재를 모아 달라는 제안",
    "targetParticipantIds": [
      "player:013563d6-15a4-4c7a-8094-eb2cd60c8be4"
    ],
    "parameters": {
      "giverNpcIds": "mythictrpg:lubras",
      "shared": "false",
      "targetConcept": "북쪽 균열의 화산 재",
      "narrativeReason": "루브라스가 재료의 가치를 시험하려 한다."
    }
  },
  "typedProposal": "QuestProposal after AI-side decode",
  "session": "immutable session snapshot",
  "context": "immutable prompt context"
}
~~~

공통 raw Proposal 제약:

| 항목 | 규칙 |
|---|---|
| type | 현재 turn에 허용된 type만 사용 |
| title | 필수, 최대 120 code point |
| summary | 필수, 최대 600 code point |
| targetParticipantIds | 최대 8개, 반드시 현재 Session 참가자 ID |
| parameters | 최대 16개 |
| parameter key | 영문자로 시작하는 영숫자 key |
| parameter value | 최대 360 code point 문자열 |

게임 측에서는 raw parameters를 다시 임의 파싱하지 말고, 가능한 한 typedProposal을 기준으로 처리한다.

## 8. Proposal 타입별 입출력 양식

### 8.1 quest_proposal

**게임 검증기로 전달됨. 실제 Quest 생성 권한은 없음.**

~~~json
{
  "type": "quest_proposal",
  "title": "화산 재 수집",
  "summary": "북쪽 균열의 재를 모아 달라는 서사적 의뢰",
  "targetParticipantIds": [
    "player:013563d6-15a4-4c7a-8094-eb2cd60c8be4"
  ],
  "parameters": {
    "giverNpcIds": "mythictrpg:lubras",
    "shared": "false",
    "targetConcept": "북쪽 균열의 화산 재",
    "narrativeReason": "신이 플레이어의 끈기를 시험하려 한다.",
    "objectiveType": "collect",
    "concreteItemId": "minecraft:blaze_rod",
    "suggestedAmount": "3",
    "difficulty": "2",
    "collaborationReason": ""
  }
}
~~~

| parameter | 필수 | 규칙 |
|---|---|---|
| giverNpcIds | 예 | 쉼표로 구분한 현재 Session의 divine God ID |
| shared | 예 | true 또는 false |
| targetConcept | 예 | 게임 독립적인 목표 설명 |
| narrativeReason | 예 | NPC가 제안한 서사적 이유 |
| objectiveType | 아니오 | 게임 allow-list에 있을 때만 |
| concreteItemId | 아니오 | 게임 item allow-list에 있을 때만 |
| suggestedAmount | 아니오 | 0~9999 정수. objectiveType이 있을 때만 의미 있음 |
| difficulty | 아니오 | 0~100. 게임 최대 난이도 이하 |
| collaborationReason | 조건부 | giver가 둘 이상이면 필수 |

targetParticipantIds는 ACTIVE 플레이어만 포함할 수 있다. shared 값은 대상 플레이어가 둘 이상인지와 정확히 일치해야 한다.

### 8.2 reward_proposal

**게임 검증기로 전달됨. 실제 보상 지급 권한은 없음.**

~~~json
{
  "type": "reward_proposal",
  "title": "화염 재료 보상 제안",
  "summary": "현재 공적에 맞는 낮은 단계 재료 보상을 제안",
  "targetParticipantIds": [
    "player:013563d6-15a4-4c7a-8094-eb2cd60c8be4"
  ],
  "parameters": {
    "negotiationDecision": "OFFER",
    "narrativeReason": "시험의 첫 단계를 통과했기 때문이다.",
    "category": "material",
    "theme": "화염과 대장",
    "description": "다음 제작 단계에 쓸 수 있는 소량의 재료",
    "suggestedName": "잿불의 조각",
    "concreteItemId": "minecraft:iron_ingot",
    "powerLevel": "1"
  }
}
~~~

| parameter | 필수 | 규칙 |
|---|---|---|
| negotiationDecision | 예 | OFFER, ACCEPT, REJECT, NEGOTIATE, DOWNGRADE, OFFER_ALTERNATIVE, ASK_FOR_MORE 중 하나 |
| narrativeReason | 예 | 서사적 판단 이유 |
| category | OFFER류일 때 | 게임 reward category allow-list에 있어야 함 |
| theme | category가 있을 때 | 보상 테마 |
| description | category가 있을 때 | 게임 독립적 보상 설명 |
| suggestedName | 아니오 | 서사적 이름 |
| concreteItemId | 아니오 | 게임 item allow-list에 있을 때만 |
| powerLevel | category가 있을 때 | 0~100, 게임 최대값 이하 |

REJECT, NEGOTIATE, ASK_FOR_MORE는 보상 concept 없이 사용 가능하다. 어떤 경우에도 Proposal 자체가 인벤토리를 변경하지 않는다.

### 8.3 relationship_change_proposal

**게임 검증기로 전달됨. 관계 원본을 직접 쓰지 않음.**

~~~json
{
  "type": "relationship_change_proposal",
  "title": "신뢰 변화 제안",
  "summary": "위험을 감수하고 동료를 구한 행동에 대한 긍정적 평가",
  "targetParticipantIds": [
    "player:013563d6-15a4-4c7a-8094-eb2cd60c8be4"
  ],
  "parameters": {
    "npcId": "mythictrpg:lubras",
    "targetPlayerId": "013563d6-15a4-4c7a-8094-eb2cd60c8be4",
    "changes": "trust:4,respect:2",
    "reason": "위험한 상황에서 동료를 버리지 않았다."
  }
}
~~~

규칙:

- targetParticipantIds는 ACTIVE 플레이어 정확히 한 명이다.
- npcId는 현재 Session의 divine 참가자여야 한다.
- targetPlayerId는 대상 플레이어 UUID와 일치해야 한다.
- changes는 쉼표로 구분한 axis:delta 문자열이다.
- delta는 0이 아닌 정수다.
- 현재 기본 축은 affinity, trust, respect, caution이다.

게임 검증기는 행동 로그, 쿨다운, 관계 범위, 스토리 조건 등을 다시 확인하고 승인할 때만 authoritative storage에 반영한다.

### 8.4 request_judgment

**AI 내부의 서사 판단이다. 게임 검증기로 전달되지 않으며 게임 상태를 바꾸지 않는다.**

~~~json
{
  "type": "request_judgment",
  "title": "요청 판단",
  "summary": "NPC가 정보 요청을 보류하고 증거를 요구함",
  "targetParticipantIds": [
    "player:013563d6-15a4-4c7a-8094-eb2cd60c8be4"
  ],
  "parameters": {
    "npcId": "mythictrpg:lubras",
    "requesterPlayerId": "013563d6-15a4-4c7a-8094-eb2cd60c8be4",
    "requestKind": "INFORMATION",
    "disposition": "ASK_FOR_PROOF",
    "reason": "현재 제공된 근거만으로는 판단하기 어렵다."
  }
}
~~~

requestKind: INFORMATION, ITEM, BLESSING, QUEST, REWARD, GENERIC  
disposition: ACCEPT, REJECT, NEGOTIATE, ASK_FOR_VOUCH, OFFER_QUEST, ASK_FOR_PROOF, DEFER

ACCEPT는 “NPC가 긍정적으로 답하려 한다”는 서사 판단일 뿐, 아이템·퀘스트·보상을 허용하거나 실행하지 않는다. ASK_FOR_VOUCH는 다른 ACTIVE 플레이어가 같은 Session에 있을 때만 가능하다.

### 8.5 vouch_request와 vouch_resolution

둘 다 AI 내부의 session-local 보증 대화 상태다. 게임 검증기로 전달되지 않으며, 관계나 보상을 직접 바꾸지 않는다.

보증 요청:

~~~json
{
  "type": "vouch_request",
  "title": "보증 요청",
  "summary": "NPC가 한 플레이어에게 다른 플레이어를 보증할 수 있는지 질문",
  "targetParticipantIds": [
    "player:beneficiary-uuid"
  ],
  "parameters": {
    "npcId": "mythictrpg:lubras",
    "sponsorPlayerId": "sponsor-uuid",
    "beneficiaryPlayerId": "beneficiary-uuid",
    "reason": "당신의 판단이 이 사람의 신뢰성을 판단하는 데 도움이 됩니다."
  }
}
~~~

보증 응답:

~~~json
{
  "type": "vouch_resolution",
  "title": "보증 응답 해석",
  "summary": "보증자가 신뢰를 보증함",
  "targetParticipantIds": [
    "player:beneficiary-uuid"
  ],
  "parameters": {
    "npcId": "mythictrpg:lubras",
    "sponsorPlayerId": "sponsor-uuid",
    "beneficiaryPlayerId": "beneficiary-uuid",
    "stance": "SUPPORT",
    "reason": "그는 위험한 순간에도 동료를 버리지 않았습니다."
  }
}
~~~

규칙:

- sponsor와 beneficiary는 서로 달라야 한다.
- 두 플레이어와 NPC는 현재 Session의 ACTIVE 참가자여야 한다.
- vouch_resolution은 대기 중인 요청과 정확히 일치해야 한다.
- 지정된 sponsor가 보낸 다음 명확한 응답만 resolution으로 처리한다.
- stance는 SUPPORT, DECLINE, UNCLEAR 중 하나다.
- pending 보증은 세션마다 하나만 존재하며, 만료·참가자 이탈 시 사라진다.

보증 결과는 대화 기억으로 남을 수 있지만, 관계 변화가 필요하면 별도의 relationship_change_proposal을 만들고 게임 검증기가 승인해야 한다.

## 9. Game Validator의 입력과 응답

게임 기능 담당자는 다음 검증기를 설치한다.

~~~java
AiProposalGateway.INSTANCE.installValidator(envelope -> {
    AiGameProposal typed = envelope.typedProposal().orElse(null);

    // 1. 현재 게임의 authoritative state를 재조회
    // 2. type, 대상, 진행도, 밸런스, 쿨다운, 아이템 ID를 검증
    // 3. 승인 시에만 기존 Quest/Reward/Relationship API 호출
    // 4. 결과만 반환

    return AiProposalGateway.ProposalDecision.accepted("게임 시스템이 승인했습니다.");
});
~~~

반환 형식:

~~~json
{
  "status": "ACCEPTED | REJECTED | MODIFIED",
  "reason": "플레이어와 운영자가 이해할 수 있는 짧은 이유",
  "allowedAdjustments": {
    "선택적 제한 이름": "다음 AI turn에 알려 줄 값"
  }
}
~~~

| status | 의미 | 게임 측 행동 |
|---|---|---|
| ACCEPTED | 현재 상태에서 실행 가능 | 게임의 기존 API로 실제 적용 가능 |
| REJECTED | 실행 불가 | 적용하지 않음 |
| MODIFIED | 원안을 그대로는 실행 불가하지만 조정 가능 | 게임이 조정한 내용만 적용 가능 |

중요: 검증기는 typedProposal이 비어 있거나 모르는 타입이면 반드시 REJECTED로 처리한다. 예외가 발생해도 안전하게 REJECTED로 처리하며 부분 적용을 하지 않는다.

검증 후, 다음 대화에서 NPC가 자연스럽게 협상·거절·대안을 제시하도록 피드백을 session별로 보관할 수 있다.

~~~java
AiProposalGateway.ProposalDecision decision = ...;
GameProposalValidationFeedback feedback =
    decision.asConversationFeedback(envelope.proposal().type());

feedbackBySessionId
    .computeIfAbsent(envelope.sessionId(), ignored -> new ArrayList<>())
    .add(feedback);
~~~

이 feedback은 5절의 QuestRewardContextProvider가 **동일한 sessionId**에 대해서만 다시 제공한다.

## 10. 권장 Game Validator 분기

~~~text
typedProposal이 QuestProposal인가?
  → giver가 유효한가?
  → 현재 진행도에서 퀘스트 제안이 가능한가?
  → objective/item/difficulty가 현재 카탈로그와 밸런스에 맞는가?
  → 승인 시 기존 Quest Engine API 호출

typedProposal이 RewardProposal인가?
  → 수령자가 유효한가?
  → 보상 category/item/power가 현재 보상 규칙에 맞는가?
  → 승인 시 기존 Reward Engine API 호출

typedProposal이 RelationshipChangeProposal인가?
  → 행동 근거와 쿨다운이 유효한가?
  → 변화량과 범위가 적절한가?
  → 승인 시 기존 관계 원본 저장소 호출

그 외 또는 typedProposal 없음
  → REJECTED
~~~

게임 시스템은 필요하면 허용된 결과를 Dialogue HUD 또는 별도 알림으로 보여 줄 수 있다. 하지만 AI의 제안문만 보고 “보상이 지급되었다”고 표시해서는 안 된다.

## 11. 세션 격리·비동기 안전 규칙

다른 제작자가 Provider, validator, 캐시를 구현할 때 반드시 아래를 지킨다.

- sessionId가 다른 대화는 대화 이력, 청중, 관계, 감정, Quest/Reward 제약, 검증 피드백을 공유하지 않는다.
- player UUID가 같더라도 새로운 Session은 이전 Session의 pending 보증 상태를 재사용하지 않는다.
- LLM 요청이 끝날 때까지 live Minecraft object를 잡아 두지 않는다.
- 늦게 도착한 응답은 turnId, generation, 현재 active turn 검사에 실패하면 버린다.
- 게임 상태 변경은 반드시 서버 스레드와 기존 게임 API의 규칙을 따른다.
- AI는 Snapshot을 본 시점의 정보를 사용한다. Snapshot 이후 바뀐 체력, 퀘스트, 인벤토리, 관계는 validator가 최신 값으로 재검증한다.

## 12. 연결 전 체크리스트

- [ ] 기존 Interaction/Encounter가 참가자와 interactionId를 결정한다.
- [ ] God ID는 기존 Datapack God Definition의 ResourceLocation을 그대로 쓴다.
- [ ] Game Snapshot에는 불변 값만 들어가며 Minecraft 객체가 없다.
- [ ] 관계 원본은 기존 PlayerMythProfile 또는 게임 담당 저장소가 가진다.
- [ ] QuestRewardContextProvider는 sessionId별 제약과 feedback을 반환한다.
- [ ] Quest/Reward/Relationship validator가 설치되어 있다.
- [ ] validator는 AI Snapshot이 아닌 최신 authoritative state를 재검증한다.
- [ ] validator가 없을 때 Proposal이 안전하게 거절되는 것을 확인했다.
- [ ] Session A/B에 서로 다른 Constraint와 feedback을 넣어 누출이 없는지 확인했다.
- [ ] NPC 대사는 기존 DialoguePresentationService/HUD에서 정상 표시되는지 확인했다.

## 13. 구현 위치

| 역할 | 현재 코드 |
|---|---|
| 세션·대화 진입점 | GodAiDialogueService |
| Session 모델 | AiDialogueModels, ConversationSessionManager |
| Game → AI Snapshot 계약 | ConversationGameSnapshotProvider, GameConversationSnapshot |
| 기본 MythicTRPG Adapter | MythicTrpgConversationSnapshotProvider |
| Quest/Reward 입력 계약 | QuestRewardContextProvider, QuestRewardContext |
| LLM raw 응답 모델 | AiDialogueModels.StructuredAiResult |
| Proposal decoder | StructuredProposalDecoder |
| AI → Game gateway | AiProposalGateway |
| 실제 HUD 표시 | DialoguePresentationService |

이 계약의 목적은 AI 모듈을 기존 RPG 기능의 대체물이 아니라, 안전한 대화·판단·제안 모듈로 유지하는 것이다.
