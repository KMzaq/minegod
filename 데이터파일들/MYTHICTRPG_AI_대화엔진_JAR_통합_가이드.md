# MythicTRPG ↔ 로컬 AI 대화 엔진 통합 가이드

**대상:** MythicTRPG 게임 기능 담당자 / AI 대화 엔진 연동 담당자  
**Minecraft:** 1.21.1 / NeoForge 21.1.x  
**LLM:** 동일 PC의 Ollama (`http://127.0.0.1:11434/api/chat`)  
**관련 모드:**

- MythicTRPG — 게임 상태와 실제 기능의 권한 보유자
- MythAI Content Registry (`mythaiaicontent`) — 신 프로필·세계 지식·예시 대화의 읽기 전용 제공자
- AI 대화 엔진 — 대화 세션·LLM 호출·대사 생성·Proposal 생성 담당

## 0. 먼저 읽을 것

이 문서의 목적은 **다른 작업자가 제작한 MythicTRPG와 AI 대화 엔진을 결합**하는 것이다. AI 대화 엔진은 게임 기능을 대체하거나, 게임 상태의 별도 원본을 만들면 안 된다.

```text
MythicTRPG (authoritative game state)
        │ Snapshot / Event / approved Interaction
        ▼
AI 대화 엔진
        │ Dialogue + non-binding Proposal
        ▼
MythicTRPG (validate → execute or reject)
```

AI는 이야기를 만들고 행동을 **제안**한다. 아이템 지급, 퀘스트 등록, 보상 지급, 관계 수치 저장, 월드 변경의 최종 권한은 항상 MythicTRPG에 있다.

### 현재 JAR 상태 주의

현재 테스트 서버의 `mythictrpg-1.0.0.jar`에는 MythicTRPG와 테스트용 AI 어댑터가 함께 들어 있다. 따라서 다른 작업자가 만든 MythicTRPG JAR과 이 파일을 `mods` 폴더에 함께 넣으면 `mythictrpg` 모드 ID와 Java 클래스가 중복되어 충돌한다.

다른 MythicTRPG 빌드에 연결할 때는 아래 둘 중 하나만 사용한다.

1. **권장:** AI 대화 코드를 고유한 모드 ID의 별도 JAR(예: `mythai_ai_response`)로 분리하고, MythicTRPG가 그 API에 의존한다.
2. **임시:** MythicTRPG 소스에 AI 대화 엔진의 공개 API와 얇은 어댑터만 병합한다. 테스트용 `AiTest*` 클래스와 테스트 명령은 병합 대상이 아니다.

기존 `mythictrpg-1.0.0.jar`를 다른 MythicTRPG JAR과 함께 배포하지 않는다.

## 1. 역할과 소유권

| 데이터/기능 | 소유자 | AI 대화 엔진의 권한 |
|---|---|---|
| God ID (`ResourceLocation`) | MythicTRPG | 동일 ID를 읽기만 함 |
| God unlock, appearance, interaction/encounter | MythicTRPG | 승인된 상호작용을 받아 세션 생성 |
| 플레이어 프로필, affinity, 진행도 | MythicTRPG | Snapshot으로 읽기만 함 |
| trust/respect/caution 등 추가 관계 축 | MythicTRPG 또는 협의된 저장소 | Snapshot으로 읽기만 함 |
| 현재 감정 | MythicTRPG | Snapshot으로 읽기만 함 |
| 체력, 장비, 위치, 날씨, 전투, 퀘스트 상태 | MythicTRPG | 필요한 값만 불변 Snapshot으로 읽기 |
| 실제 퀘스트/보상/가호/월드 변경 | MythicTRPG | Proposal만 반환 |
| 신의 정적 프로필, 단계형 로어, 예시 대화 | Content Registry | 읽기만 함 |
| 대화 세션, 최근 대화, AI 메모리, LLM 호출 | AI 대화 엔진 | 소유 |
| 대사, Quest/Reward/Relationship Proposal | AI 대화 엔진 | 생성만 함 |

### 절대 금지

- AI 코드가 별도의 affinity 원본·플레이어 진행도 원본·퀘스트 원본을 저장하는 것
- AI 코드가 Minecraft 이벤트를 다시 감지해 별도의 게임 이벤트 시스템을 만드는 것
- LLM의 문장을 신뢰하고 아이템·보상·관계·월드 상태를 즉시 변경하는 것
- Content Registry의 정적 프로필을 MythicTRPG의 실제 신 상태 대신 사용하는 것
- 다른 세션의 Snapshot, Quest/Reward 제약, 검증 피드백을 재사용하는 것

## 2. 모드 의존성과 식별자

### 필수 런타임 구성

```text
mods/
  mythictrpg-<version>.jar
  mythaiaicontent-<version>.jar
  mythai-ai-response-<version>.jar       # 분리 배포 시
```

Ollama와 모델 파일은 Minecraft `mods` 폴더에 넣지 않는다. Ollama는 서버 PC에서 별도 프로세스로 실행한다.

```text
ollama serve
```

서버 설정의 모델 이름 예시는 다음과 같다.

```json
{
  "ollamaChatUrl": "http://127.0.0.1:11434/api/chat",
  "ollamaModel": "gemma4:12b"
}
```

### ID 규칙

- God ID는 MythicTRPG가 소유하는 `ResourceLocation`을 그대로 사용한다.
  - 예: `mythictrpg:fortuna`
  - 예: `mythictrpg:greek/olympian_zeus`
- 콘텐츠 파일 ID와 God ID는 다를 수 있다.
- AI가 별도 문자열 ID를 새로 만들면 안 된다.
- 대화 세션 ID와 interaction ID는 UUID를 사용하며 서로 다른 개념이다.
  - `sessionId`: AI 대화 세션 식별자
  - `interactionId`: MythicTRPG가 이미 승인한 Encounter/Interaction 참조. 없을 수 있음.

## 3. AI 대화 엔진이 요구하는 공개 진입점

현재 엔진의 중심 진입점은 다음 형태다.

```java
GodAiDialogueService.INSTANCE.startConversation(
    Collection<ServerPlayer> players,
    Collection<ResourceLocation> godIds,
    UUID interactionId // 없으면 null
);
```

### 호출 시점

MythicTRPG가 이미 다음을 판정한 뒤 호출한다.

1. 신이 해금/식별/등장 가능한가
2. 실제 상호작용 또는 Encounter가 승인됐는가
3. 참가 플레이어와 참가 신은 누구인가
4. interaction ID가 존재한다면 무엇인가

AI 엔진은 거리 탐색, 신 등장 판정, 플레이어 자동 탐색을 하지 않는다.

### 호출 예시

```java
// MythicTRPG의 InteractionPlan/Encounter commit 이후
var result = GodAiDialogueService.INSTANCE.startConversation(
    List.of(triggeringPlayer, invitedPlayer),
    List.of(godId, secondaryGodId),
    approvedInteractionId
);

if (result.status() != GodAiDialogueService.StartConversationStatus.STARTED) {
    // MythicTRPG UI/로그에서 실패 사유를 처리한다.
}
```

### 참가자 상태

MythicTRPG가 판정한 상태만 전달한다.

| 상태 | 의미 |
|---|---|
| `ACTIVE` | 직접 대화 중이며 발화 가능 |
| `LISTENER` | 청취만 가능. 발화하면 `ACTIVE`로 승격 가능 |
| `OUTSIDE` | 대화를 듣지 못함 |

연동에 사용할 API:

```java
GodAiDialogueService.INSTANCE.setParticipantState(server, sessionId, playerUuid, state);
GodAiDialogueService.INSTANCE.addListener(sessionId, nearbyPlayer);
GodAiDialogueService.INSTANCE.addDivineParticipant(sessionId, godId);
GodAiDialogueService.INSTANCE.leave(player);
```

`LISTENER`/`OUTSIDE` 판정은 AI가 하지 않는다. 비밀 지식 공개 여부를 AI가 판단할 때도 MythicTRPG가 전달한 세션 참가자 상태를 사용한다.

## 4. 채팅과 대화 세션 연결

활성 세션의 일반 채팅은 AI 엔진으로 보낸다.

```java
if (GodAiDialogueService.INSTANCE.isActive(player)) {
    GodAiDialogueService.INSTANCE.handlePlayerText(player, rawChat);
}
```

- `!`로 시작하는 채팅은 공개 채팅으로 유지할 수 있다.
- 세션 안의 일반 채팅은 서버 채팅 이벤트에서 취소한 뒤 AI로 보낸다.
- 한 세션의 턴은 순차 처리한다. 다른 세션의 요청은 별도 큐에서 처리될 수 있다.
- LLM worker에는 `ServerPlayer`, `Level`, `Entity` 등 Minecraft 런타임 객체를 넘기지 않는다.

## 5. MythicTRPG → AI: Game Snapshot 계약

AI 요청 직전에 MythicTRPG가 **불변 DTO**를 만들어 전달한다. 세션 A와 B의 Snapshot은 절대 공유하지 않는다.

현재 엔진의 DTO 핵심은 다음과 같다.

```text
ConversationTurnContextSnapshot
  sessionId, turnId, generation, requestId
  triggeringParticipantId, triggeringPlayerId
  SessionSnapshot
  GameConversationSnapshot
  pendingVouchInteraction

GameConversationSnapshot
  relationshipsByPlayerParticipantId
  gameState
  questRewardContext
  socialAuthorityByDivineParticipantId
```

### 5.1 관계와 현재 감정

각 플레이어-신 쌍에 독립된 관계 Snapshot을 제공한다.

```text
RelationshipContext
  metrics: affinity, trust, respect, caution
  emotion: anger, happiness, annoyance, curiosity, sadness,
           gratitude, disappointment, fear 등 현재 강도
  derivedTags: R_EXTREME_HOSTILE ... R_DEEP_BOND
  interpretation: 프롬프트에 넣어도 되는 짧은 해석
```

관계 수치와 감정의 영구 저장·변경은 MythicTRPG만 한다. AI는 제안만 반환한다.

### 5.2 게임 상태

필요한 값만 넣는다. 모든 Minecraft API를 AI Domain Layer에서 직접 조회하지 않는다.

```json
{
  "dimension": "minecraft:overworld",
  "x": 123,
  "y": 64,
  "z": -20,
  "dayTime": 6000,
  "raining": false,
  "health": 16.0,
  "maxHealth": 20.0,
  "mainHandItem": "minecraft:iron_sword",
  "recentCombat": false,
  "questSummary": ["..."],
  "authority": "Minecraft server state is authoritative. The LLM cannot mutate it."
}
```

민감하거나 불필요한 내부 데이터는 넣지 않는다. AI가 행동을 실제로 실행할 수 있다고 오해할 수 있는 객체/콜백도 넣지 않는다.

### 5.3 Quest/Reward 제약과 검증 피드백

게임 기능 담당자는 세션·턴별 Provider를 설치한다.

```java
GodAiDialogueService.INSTANCE.installQuestRewardContextProvider(
    (session, triggeringParticipantId) -> {
        // 이 session과 발화자에게만 적용되는 불변 제약/피드백을 반환
        return questRewardContext;
    }
);
```

Provider는 다음을 보장해야 한다.

- Session A의 `maxPower`, 허용 아이템, Quest 제약, 거절 사유가 Session B에 들어가지 않는다.
- 검증 피드백은 이전 Proposal과 같은 세션/대상에만 반환한다.
- 이 Provider는 퀘스트나 보상을 직접 생성/지급하지 않는다.

### 5.4 사회적 위계/권한

신과 플레이어의 실제 위계, 계약, 직위, Encounter 역할이 존재한다면 MythicTRPG가 제공한다.

```java
GodAiDialogueService.INSTANCE.installSocialAuthorityContextProvider(
    (session, triggeringParticipantId) -> Map.of(
        divineParticipantId,
        new NpcSocialAuthorityContext(RelativeAuthority.NPC_LOWER,
            List.of("player is the appointed commander"))
    )
);
```

AI는 채팅 문장, OP 권한, 임의 추측으로 위계를 정하면 안 된다. 정보가 없으면 `UNKNOWN`이다.

### 5.5 대화적 보류 상태

실제 게임 행동이 아닌 농담, 질문, 기대, 오해 같은 **대화적 상태**는 AI 세션이 보유할 수 있다. 이것은 Quest/Reward와 다르다.

향후 확장 시 권장 형태:

```json
{
  "socialFrame": "LIGHT_TEASING",
  "pendingInteraction": "NONE",
  "hasActualGameAction": false,
  "expectedPlayerReaction": "BANTER_OR_CURIOSITY"
}
```

이 상태가 있다면 플레이어의 “뭔데 뭐할려고?” 같은 확인 요청을 실제 위협·퀘스트·가호로 과장하지 않고, 직전 농담의 뜻을 자연스럽게 설명할 수 있다. **이 대화적 보류 상태는 현재 프로덕션 API에 아직 정식 추가되지 않았으므로, 구현 전 AI 엔진 담당자와 DTO를 합의해야 한다.**

## 6. Content Registry 사용

정적 콘텐츠는 `mythaiaicontent`에서 읽는다. MythicTRPG가 별도의 신 프로필 DB나 로어 원본을 다시 만들지 않는다.

```java
ResourceLocation godId = ResourceLocation.parse("mythictrpg:fortuna");

AiContentRegistry.INSTANCE.findGod(godId);
AiContentRegistry.INSTANCE.staticContentFor(godId);
AiContentRegistry.INSTANCE.loreAvailableTo(godId);
AiContentRegistry.INSTANCE.dialogueExamplesAvailableTo(godId);
```

중요 규칙:

- 프로필 `godId`는 MythicTRPG의 God ID와 정확히 같아야 한다.
- AI에는 신별 단계로 제한된 `ResolvedLoreKnowledge`만 전달한다.
- 원본 로어 전체 단계를 LLM에 넣으면 안 된다.
- 관계 단계는 MythicTRPG가 판정하고, Content Registry는 그 태그의 정적 말투 지침만 반환한다.
- 예시 대화는 AI가 선택해 프롬프트 길이 제한 안에서 사용한다. 모든 예시를 매 턴 넣지 않는다.

세부 API와 파일 양식은 다음 문서를 따른다.

- [AI_CONTENT_REGISTRY_IO_CONTRACT.md](AI_CONTENT_REGISTRY_IO_CONTRACT.md)
- [AI_CONTENT_REGISTRY_CONTENT_AUTHORING_GUIDE.md](AI_CONTENT_REGISTRY_CONTENT_AUTHORING_GUIDE.md)

## 7. 1단계 대화 판단 계약

AI 엔진은 대사 생성 전 발화의 상황·말투·대화 흐름을 읽을 수 있다. 이 결과는 게임 실행 명령이 아니다.

현재 최소 형식:

```json
{
  "primarySituation": "S_QUEST_INQUIRY",
  "secondarySituations": [],
  "knowledgeKeywords": [],
  "playerToneTags": ["T_INFORMAL"],
  "conversationAct": "REQUESTING_ACTIVITY",
  "confidence": 86
}
```

상황 태그, 말투 태그, `conversationAct`의 허용값은 다음 계약을 따른다.

- [MYTHICTRPG_AI_1단계_판단태그_IO_규격.md](MYTHICTRPG_AI_1단계_판단태그_IO_규격.md)

연동 담당자는 이 결과를 관계 수치 변경이나 처벌의 직접 근거로 사용하면 안 된다. 예를 들어 `T_INFORMAL`은 반말이라는 관찰일 뿐이고, 해당 신이 불쾌해할지는 관계·감정·위계·성격을 포함한 다음 단계에서 판단한다.

## 8. AI → MythicTRPG: 대사와 Proposal

AI의 구조화 응답은 다음 형태다.

```json
{
  "speech": [
    {
      "speakerId": "divine:mythictrpg:fortuna",
      "text": "뭘 하긴. 네 반응이 궁금했지.",
      "audienceParticipantIds": ["player:<uuid>"]
    }
  ],
  "currentTopic": "가벼운 놀림",
  "proposals": []
}
```

### 대사

- `speakerId`는 현재 세션의 DIVINE 참가자만 허용한다.
- `audienceParticipantIds`는 현재 보이는 `ACTIVE`/`LISTENER` 플레이어만 허용한다.
- MythicTRPG의 Dialogue HUD/Network가 실제 표시를 담당한다.
- AI 응답이 비어 있거나 실패하면 게임 상태를 변경하지 않고 오류만 표시한다.

### Proposal

Proposal은 데이터일 뿐이다.

```json
{
  "type": "quest_proposal",
  "title": "깊은 바다의 심장",
  "summary": "바다의 심장 1개를 찾아오라는 의뢰를 제안한다.",
  "targetParticipantIds": ["player:<uuid>"],
  "parameters": {
    "rewardConcept": "minecraft:totem_of_undying",
    "quantity": "1"
  }
}
```

MythicTRPG는 반드시 검증 Gateway를 설치한다.

```java
AiProposalGateway.INSTANCE.installValidator(envelope -> {
    // 1. sessionId / participant / 진행도 / 밸런스 / 허용 타입 검증
    // 2. typedProposal을 MythicTRPG의 Quest/Reward API로 변환
    // 3. 실제 실행 성공 시에만 ACCEPTED 반환
    // 4. 실패 시 REJECTED 또는 MODIFIED 반환
    return AiProposalGateway.ProposalDecision.rejected("Not implemented yet");
});
```

기본 Validator는 모든 Proposal을 거절한다. Validator를 설치하지 않았다면 AI가 퀘스트·보상·관계를 실제로 바꾸지 않는 것이 정상이다.

`ACCEPTED`는 AI가 권한을 얻었다는 뜻이 아니다. MythicTRPG가 자체 검증을 통과시키고 자신의 API를 실행했다는 기록이어야 한다.

## 9. MythicTRPG 담당자의 최소 구현 순서

1. 기존 God ID와 Content Registry 프로필의 `godId`가 정확히 일치하는지 검증한다.
2. InteractionPlan/Encounter commit 이후에만 `startConversation`을 호출한다.
3. 참가자와 `ACTIVE`/`LISTENER`/`OUTSIDE` 상태를 기존 시스템에서 전달한다.
4. 기존 affinity를 읽는 관계 Snapshot Provider를 연결한다. AI용 affinity 저장소를 만들지 않는다.
5. 현재 감정, 추가 관계 축, 게임 상태, 위계가 존재하면 각각 불변 Provider로 연결한다.
6. Quest/Reward 기능이 준비되기 전에는 `QuestRewardContextProvider.none()`과 기본 거절 Validator를 유지한다.
7. 기능이 준비된 Proposal 타입만 allow-list에 추가하고, 나머지는 거절한다.
8. 정상 대사는 기존 Dialogue HUD로 표시하고, 일반 채팅/세션 종료/로그아웃을 연결한다.
9. 세션 A/B 동시 대화에서 관계·청중·Quest/Reward 제약·검증 피드백이 섞이지 않는지 확인한다.

## 10. 테스트용 코드와 실제 연동의 구분

다음은 개발 테스트용이며 실제 MythicTRPG 기능 연동의 기반으로 사용하지 않는다.

- `AiTestDialogueAdapter`
- `AiTestDialogueCommands` (`/ai_test`)
- `AiTestDialogueEvents`
- `AiTestContentRegistryBridge`

테스트 어댑터는 로컬 Ollama, Content Registry, 간단한 관계 태그를 빠르게 확인하기 위한 임시 경로다. 실제 게임의 interaction, quest, reward, relationship, network 권한을 대체하지 않는다.

실제 연동은 `GodAiDialogueService`, `ConversationSessionManager`, `ConversationTurnContextSnapshot`, `AiProposalGateway` 같은 프로덕션 경계를 사용한다. 단, AI 대화 엔진을 별도 JAR로 분리할 경우 이 공개 API를 별도 API 패키지로 유지하고, MythicTRPG 내부 구현 클래스를 직접 참조하지 않도록 정리한다.

## 11. 완료 조건

다음이 모두 만족되면 기본 연동이 완료된 것이다.

- [ ] MythicTRPG의 기존 God `ResourceLocation`으로 AI 대화 세션을 시작한다.
- [ ] 승인된 interaction ID가 있을 때 세션에 그대로 연결된다.
- [ ] 다중 플레이어/다중 신 세션이 가능한 참가자 Snapshot을 사용한다.
- [ ] LISTENER/OUTSIDE 상태가 대사 청중과 비밀 판단에 반영된다.
- [ ] 각 플레이어-신 관계와 현재 감정이 해당 턴의 Snapshot으로 전달된다.
- [ ] AI 응답은 기존 Dialogue HUD로만 표시되고, AI가 HUD/Network를 재구현하지 않는다.
- [ ] AI Proposal은 Validator 없이는 실제 게임 상태를 바꾸지 않는다.
- [ ] 서로 다른 두 세션의 Quest/Reward 제약과 validator feedback이 섞이지 않는다.
- [ ] Content Registry의 단계 제한 로어만 LLM에 전달된다.
- [ ] 대화 로그가 플레이어/세션별로 저장된다.

## 12. 다른 작업 에이전트에게 전달할 작업 지시문

아래 문장을 그대로 전달해도 된다.

```text
이 저장소의 MythicTRPG 게임 기능과 로컬 AI 대화 엔진을 통합해라.

반드시 `MYTHICTRPG_AI_대화엔진_JAR_통합_가이드.md`를 먼저 읽고 따른다.

핵심 원칙:
- MythicTRPG가 모든 게임 상태와 실제 게임 기능의 authoritative source다.
- AI는 Snapshot을 받아 대사와 Proposal만 만들며, 아이템/퀘스트/보상/관계/월드를 직접 변경하지 않는다.
- 기존 God ResourceLocation, InteractionPlan/Encounter, PlayerMythProfile, affinity, HUD, Network를 재구현하거나 복제하지 않는다.
- AI 테스트용 `AiTest*` 클래스는 실제 기능 연동에 사용하지 않는다.
- 기존 코드에 맞는 얇은 Adapter/Provider/Validator를 추가하고, 대규모 리팩터링은 하지 않는다.
- 세션별 데이터 격리를 보장한다. 특히 Quest/Reward 제약과 validator feedback이 다른 세션에 섞이면 안 된다.

먼저 현재 MythicTRPG의 interaction, participant, 관계, quest/reward, HUD/network API를 조사하고,
가이드의 9번 순서대로 최소 변경으로 연결해라.
```
