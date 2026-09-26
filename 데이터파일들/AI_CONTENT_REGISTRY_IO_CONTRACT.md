# AI 콘텐츠 레지스트리 모드 입출력 계약

**모드 ID:** `mythaiaicontent`  
**대상:** AI 응답 모드, MythicTRPG 연동 담당자, AI 콘텐츠 제작자  
**Minecraft / NeoForge:** 1.21.1 / NeoForge 21.1.x

`MythAI Content Registry`는 같은 Minecraft 서버 JVM에서 Datapack JSON을 읽고, 고정 콘텐츠를 Java API로 제공하는 모드다. HTTP API·LLM 서버·관계도 저장소가 아니다.

## 1. 책임 경계

```text
MythicTRPG
  ├─ God ID, 관계, 플레이어, 사건, 퀘스트, 보상, 실제 게임 기능
  └─ AI 응답 모드에 현재 Conversation Snapshot 전달

AI 콘텐츠 레지스트리 모드
  ├─ 신 기본 프로필
  ├─ 단계형 정적 세계 지식
  ├─ 예시 대화
  └─ 정적 퀘스트 목록과 진행도 트랙 ID

AI 응답 모드
  ├─ 콘텐츠 레지스트리 조회
  ├─ 기억·로그 검색
  ├─ 1단계 / 2단계 LLM 호출
  └─ 대사 + Proposal을 MythicTRPG로 반환
```

레지스트리 모드는 플레이어 관계·감정·기억·현재 퀘스트·보상·게임 상태·LLM 호출·Proposal 검증·실제 게임 기능을 소유하지 않는다. 또한 MythicTRPG의 Java 클래스나 JAR에 의존하지 않는다.

## 2. 입력: Datapack JSON

| 데이터 | 리소스 경로 | 파일 ID 예시 |
|---|---|---|
| 신 프로필 | `data/<namespace>/mythai_ai/god_profiles/*.json` | `mythaiaicontent:lubras` |
| 단계형 로어 | `data/<namespace>/mythai_ai/lore/*.json` | `mythaiaicontent:dragon_ruin` |
| 예시 대화 | `data/<namespace>/mythai_ai/dialogue_examples/*.json` | `mythaiaicontent:lubras_information` |
| 정적 NPC 관계 | `data/<namespace>/mythai_ai/social_relations/*.json` | `mythaiaicontent:greek/zeus_athena` |
| 퀘스트 목록 | `data/<namespace>/mythai_ai/quest_lists/*.json` | `mythictrpg:quest_list_demeter` |

파일 경로가 ResourceLocation ID가 된다. 예를 들어 `data/mythaiaicontent/mythai_ai/lore/greek/titan_war.json`은 `mythaiaicontent:greek/titan_war`다.

신 프로필의 `godId`는 콘텐츠 파일 ID가 아니라 MythicTRPG가 소유하는 기존 신 ID다.

### 단계형 지식 원본

각 프로필의 `loreKnowledge`가 “그 신이 이 지식을 어디까지 아는가”의 유일한 원본이다.

```json
"loreKnowledge": [
  { "loreId": "mythaiaicontent:greek/titan_war", "level": 3 }
]
```

레지스트리는 이를 역색인해 로어별 보유자와 각 보유자의 단계 목록을 만든다. 따라서 로어 JSON에 수동 `known_by`를 작성하지 않는다.

## 3. 출력: Java 조회 API

AI 응답 모드는 `AiContentRegistry.INSTANCE`를 통해 콘텐츠를 읽는다.

```java
ResourceLocation godId = ResourceLocation.parse("mythictrpg:lubras");
ResourceLocation loreId = ResourceLocation.parse("mythaiaicontent:dragon_ruin");

AiContentRegistry.INSTANCE.findGod(godId);
AiContentRegistry.INSTANCE.staticContentFor(godId);
AiContentRegistry.INSTANCE.loreFor(godId, loreId);
AiContentRegistry.INSTANCE.loreAvailableTo(godId);
AiContentRegistry.INSTANCE.dialogueExamplesAvailableTo(godId);
AiContentRegistry.INSTANCE.relationshipGuidanceFor(godId, RelationshipTier.FRIENDLY);
AiContentRegistry.INSTANCE.socialRelationTagsFor(sourceGodId, targetGodId);
AiContentRegistry.INSTANCE.questListsFor(godId);
AiContentRegistry.INSTANCE.questCandidatesFor(godId);
```

### 3.1 `findGod`

```java
Optional<GodContentProfile> findGod(ResourceLocation mythicGodId)
```

```text
GodContentProfile
  contentId: ResourceLocation
  godId: ResourceLocation
  displayName, identity, description
  personality, values, speechStyles, dialogueGuidelines, situationGuidelines, repetitionGuidelines, restrictions
  characterTags: List<String>  // 신화권·종족·권능·성격 등의 정적 복수 태그
  loreKnowledge: List<LoreKnowledge>
    loreId: ResourceLocation
    level: int
  questListIds: List<ResourceLocation> // 신 전용·세력 공용 목록을 복수 참조
  signatureExampleIds: List<ResourceLocation>
  situationGuidelines: Map<String, List<String>> // key: S_ situation tag
  repetitionGuidelines: Map<String, List<String>> // key: CASUAL_COMPLAINT etc.; static persona guidance only
  relationshipGuidelines: Map<RelationshipTier, List<String>>
```

### 3.2 안전한 단계형 로어 조회

```java
Optional<ResolvedLoreKnowledge> loreFor(ResourceLocation godId, ResourceLocation loreId)
List<ResolvedLoreKnowledge> loreAvailableTo(ResourceLocation godId)
```

`ResolvedLoreKnowledge`는 해당 God ID의 지식 단계까지만 포함한다. **이 조회만으로 청중 공개 허가가 증명되지는 않는다.** 대화방 프롬프트는 3.8의 `audienceContentFor` 결과만 사용한다.

```text
ResolvedLoreKnowledge
  id, title, secrecy, keywords
  knowledgeLevel: int
  accessibleLevels: List<LoreKnowledgeLevel>  // 1~knowledgeLevel만 존재
  knowledgeHolders: List<LoreKnowledgeHolder> // 해당 단계에서 공개된 경우만 존재

LoreKnowledgeLevel
  level: int
  content: String
  revealKnowledgeHolders: boolean

LoreKnowledgeHolder
  godId: ResourceLocation
  knowledgeLevel: int
```

`revealKnowledgeHolders: true`인 단계를 보유한 God만 `knowledgeHolders` 목록을 받는다. 목록은 프로필 `loreKnowledge`에서 자동 생성된다.

`findLoreDefinition(loreId)`는 원본의 모든 단계를 반환하는 콘텐츠 작성·관리용 API다. 이 값을 LLM 프롬프트에 그대로 넣으면 상위 단계 지식이 유출되므로 AI 응답 모드는 사용하면 안 된다.

### 3.3 `staticContentFor`

```java
Optional<StaticGodContent> staticContentFor(ResourceLocation mythicGodId)
```

```text
StaticGodContent
  profile: GodContentProfile
  explicitlyReferencedLore: List<ResolvedLoreKnowledge>
  signatureExamples: List<DialogueExample>
```

프로필이 참조한 로어도 이미 신별 단계로 제한되어 있다. 이 메서드는 키워드 검색·관계·청중에 따른 공개 결정을 하지 않는다.

### 3.4 예시 대화 조회

```java
List<DialogueExample> dialogueExamplesAvailableTo(ResourceLocation mythicGodId)
```

예시 대화의 `known_by`가 비어 있으면 공용 예시, 값이 있으면 해당 신만 사용할 수 있는 예시다. 점수 계산과 프롬프트 길이 제한은 AI 응답 모드 책임이다.

### 3.5 관계 단계별 태도 조회

```java
List<String> relationshipGuidanceFor(ResourceLocation godId, RelationshipTier tier)
```

`RelationshipTier`는 다음 9단계의 고정 대화 태그를 제공한다.

| 단계 | 태그 | Enum 값 |
|---:|---|---|
| -4 | `R_EXTREME_HOSTILE` | `EXTREME_HOSTILE` |
| -3 | `R_HOSTILE` | `HOSTILE` |
| -2 | `R_DISLIKE` | `DISLIKE` |
| -1 | `R_WARY` | `WARY` |
| 0 | `R_NEUTRAL` | `NEUTRAL` |
| +1 | `R_FAVORABLE` | `FAVORABLE` |
| +2 | `R_FRIENDLY` | `FRIENDLY` |
| +3 | `R_TRUSTED` | `TRUSTED` |
| +4 | `R_DEEP_BOND` | `DEEP_BOND` |

MythicTRPG는 실제 affinity 등 관계 원본 수치를 저장하고, 대화 요청에 위 태그 또는 대응 Enum 값을 전달한다. AI 응답 모드는 해당 단계로 이 메서드를 조회해 신별 정적 지침을 프롬프트에 포함한다. 레지스트리는 점수를 저장하거나 단계를 판정하지 않는다.

### 3.6 정적 NPC 간 보조 관계 태그 조회

```java
List<SocialRelationTag> socialRelationTagsFor(ResourceLocation sourceGodId, ResourceLocation targetGodId)
```

이 메서드는 **발화자 관점**의 정적 관계 유형을 반환한다. 예를 들어 제우스에서 아테나 방향으로 조회하면 `RT_FATHER`, 아테나에서 제우스 방향으로 조회하면 `RT_CHILD`가 반환된다.

```text
SocialRelation
  contentId: ResourceLocation
  participantA, participantB: ResourceLocation
  aToBTags: List<SocialRelationTag>
  bToATags: List<SocialRelationTag>

SocialRelationTag
  RT_FATHER, RT_MOTHER, RT_CHILD, RT_BROTHER, RT_SISTER, RT_TWIN
  RT_SPOUSE, RT_LOVER, RT_COMRADE
  RT_NEMESIS, RT_RIVAL, RT_ENEMY
  RT_MASTER, RT_SERVANT
  RT_BLOOD_RELATION, RT_CREATOR, RT_CREATION
```

보조 관계 태그는 호감도 단계 `R_*`와 별개이며 복수로 동시에 존재할 수 있다. 콘텐츠 레지스트리는 실제 세션 참가자를 탐색하지 않는다. AI 응답 모드는 MythicTRPG가 전달한 참가자 중 NPC 쌍에 대해서만 이 메서드를 호출해야 한다.

### 3.7 퀘스트 목록 조회와 진행도 계약

```java
List<QuestListDefinition> questListsFor(ResourceLocation godId)
List<QuestCandidateDefinition> questCandidatesFor(ResourceLocation godId)
```

```text
QuestListDefinition
  questListId: ResourceLocation
  progressTrackId: ResourceLocation
  displayName: String
  quests: List<QuestDefinition>

QuestDefinition
  questId, title, content
  objectives, rewards, acceptanceConditions
  progressOnClear: 0..100
```

반환값은 정적 후보이며 현재 수주 가능하다는 뜻이 아니다. MythicTRPG가 플레이어 상태와 `acceptanceConditions`를 검증해 가능한 후보만 AI 응답 모드에 제공한다. 가능한 후보가 여러 개면 AI는 선택지를 제시하고, 선택 전에는 하나를 임의 확정하지 않는다.

서버 전역 진행도는 MythicTRPG의 `MythicWorldState.questProgress`가 authoritative source다. 키는 신 ID가 아니라 `progressTrackId`이므로 신 전용·세력 공용 진행도를 모두 지원한다. 누락된 트랙은 0이고 최대값은 100이다. 누군가 진행도를 올리면 모든 플레이어의 수주 후보가 같은 값으로 바뀌며, 지난 구간 퀘스트는 다른 플레이어도 새로 받을 수 없다. 퀘스트 완료가 authoritative하게 한 번 커밋된 뒤에만 `applyQuestClearProgress(progressTrackId, progressOnClear)`를 호출한다. 콘텐츠 레지스트리와 LLM은 이 값을 직접 변경할 수 없다. 누가 어떤 퀘스트를 수주했는지는 별도의 수주 상태이며 전역 진행도와 혼동하지 않는다.

### 3.8 대화방 공개 계약 보충 (2026-09-23)

```java
Optional<AudienceGodContent> audienceContentFor(
    ResourceLocation mythicGodId, String relationshipTier,
    boolean publicRoom, List<ResourceLocation> participantGodIds, Set<UUID> audiencePlayerIds);
```

`AudienceGodContent`는 `profile`, `lore`, `examples`, `relationshipGuidance`, `socialRelationTags`, `generation`을 반환한다. `profile`은 원본 정의가 아닌 안전한 문자열/목록/맵 투영이며 숨겨진 `loreKnowledge` 참조 목록을 포함하지 않는다. 원본 지식 보유는 프로필 `loreKnowledge`로 제한하고, 공개 여부는 각 로어 단계의 선택 `disclosure`, 예시의 선택 `disclosure`, 프로필 선택 `fieldDisclosure`로 사전에 제한한다.

`audiencePlayerIds`는 기록 ON/OFF와 무관한 **실제 전체 전달 대상**이다. 비공개 방은 최대 64플레이어/16신이며 공개 방의 실제 전달 대상은 64명보다 많을 수 있다. 호출자는 게임이 만든 Snapshot을 사용해야 한다. 레지스트리는 참가자를 감지하거나 추가하지 않는다. 반환된 `SECRET` 분류 로어가 있을 수 있으므로 소비자가 다시 `secrecy == PUBLIC`만 허용하면 안 된다. 허가는 분류명이 아니라 이미 적용한 작성자 정책의 결과다.

AI `RoomKnowledgeContext`가 이 계약을 읽고 분류·생성·보조 반응용 기존 DTO로 변환한다. 새 조회 메서드가 없는 구 registry에서는 raw 프로필/로어로 돌아가지 않고 실패 처리한다. 현재 세대와 다른 지연 응답은 기존 엔진의 generation 검증으로 취소한다.

기억 재검증용 동일 메서드의 여섯 번째 인수 `List<ResourceLocation> relationReferenceGodIds`는 원래 사용한 정적 관계 조회 대상을 재현한다. 이 인수는 청중이 아니며 공개 정책은 항상 현재 `participantGodIds`/`audiencePlayerIds`로 평가한다. 회상 원화자가 현재 방을 떠났어도 원화자의 지식과 현재 청중의 공개 권한을 별개로 검사할 수 있다.

`RoomEvidenceReference(kind="CONTENT_DISCLOSURE_V1", payload=...)`에는 원화자 ID, 조회에 사용한 관계 태그, 원래 정적 관계 참조 대상과 안전한 콘텐츠 전체 SHA-256만 기록한다. 비밀 원문은 들어가지 않는다. 현재 정책으로 새 청중을 필터한 결과 지문이 같아야 다시 사용한다. 영속 증거는 재시작에 따라 달라지는 reload generation을 비교하지 않는다. 지금은 전체 콘텐츠 지문이므로 관련 없는 콘텐츠 추가도 보수적으로 옛 대화를 사용 불가로 만들 수 있다. 실제 사용 항목 단위 증거로 정교화하는 것은 별도 개선이다.

이 절은 기존 raw API 호환을 제거하지 않는다. `findGod`/`staticContentFor`/`loreAvailableTo`는 관리·지식소유 조회용으로 계속 존재하지만 대화방 공개의 근거로 사용할 수 없다.

### 3.9 정적 퀘스트 후보 공개와 기억 증거 (2026-09-23)

```java
List<QuestCandidateDefinition> audienceQuestCandidatesFor(
    ResourceLocation sourceGodId, boolean publicRoom,
    List<ResourceLocation> participantGodIds, Set<UUID> audiencePlayerIds);
List<QuestCandidateDefinition> publicQuestCandidatesFor(ResourceLocation sourceGodId);
```

`QuestDefinition.disclosure`는 선택 공용 `ContentDisclosure`이며 미지정은 기존 호환 `PUBLIC`이다. `audienceQuestCandidatesFor`는 원 신의 프로필 `questListIds` 소유권과 현재 실제 전체 청중의 공개권을 검사한 후보만 반환한다. 제목·내용·목표·보상 설명은 하나의 후보 단위로 필터한다. 수주 가능 여부나 실제 완료·보상 지급은 이 API가 판정하지 않는다. 기존 raw `questCandidatesFor`와 `questDefinitionsFor`는 게임/관리 조회용으로 보존한다.

AI `AiQuestContentBridge.candidatesFor(Request, ServerPlayer)`는 전체 실제 청중의 공개 필터와 기존 게임 수주 후보 검사를 적용한다. 퀘스트 참여자 판정은 방 참가 플레이어를 계속 사용하므로 공개 방송 수신자를 자동 수주자로 확장하지 않는다. 전체 청중을 명시할 수 없는 legacy 후보/완료/리마인더는 `publicQuestCandidatesFor`로 무제한 PUBLIC만 읽으며 새 계약이 없으면 raw 비밀 본문으로 fallback하지 않는다.

각 후보의 `fingerprint()`는 해당 정적 후보의 목록/진행트랙 ID, 본문·목표/보상·조건·진행 증가량과 작성자 공개정책을 포함한다. reload generation과 현재 게임 진행도는 포함하지 않는다. AI는 한 번 조회한 후보 객체를 프롬프트와 `QuestCandidate.evidenceReferences()` 양쪽에 사용한다. `QUEST_CONTENT_DISCLOSURE_V1` payload에는 원화자 ID, quest ID, 항목별 SHA-256만 기록하고 원문은 넣지 않는다.

`RoomQuestKnowledge.validEvidence`는 원화자 소유권과 새 전체 청중에 대한 필터를 다시 적용해 같은 항목 지문인지 확인한다. 원화자가 현재 방에 없어도 검증 가능하다. 정의/정책 변경·삭제·원화자 참조 제거는 이전 발언의 재사용을 차단하며, 무관한 후보 추가·서버 재시작·퀘스트 완료 때문에 과거의 허용된 발언 자체가 무효화되지는 않는다. 이 증거는 실행 성공이나 현재 수주 가능성의 근거가 아니다. FTB 등 게임 UI의 별도 퀘스트 공개권은 이 정적 AI 프롬프트 계약의 범위가 아니다.

## 4. 모드 간 관계 단계 입출력 규격

관계 수치는 MythicTRPG가 소유한다. 콘텐츠 레지스트리는 수치를 받거나 저장하지 않고, AI 응답 모드가 판정 결과 태그로 정적 지침을 조회할 때만 사용된다.

```text
MythicTRPG ── RelationshipContext ──▶ AI 응답 모드
AI 응답 모드 ── godId + relationshipTag ──▶ 콘텐츠 레지스트리
콘텐츠 레지스트리 ── 신별 정적 지침 ──▶ AI 응답 모드
AI 응답 모드 ── DialogueResponse + Proposal ──▶ MythicTRPG
```

### 4.1 MythicTRPG → AI 응답 모드

대화 요청에서 각 플레이어-신 쌍에 다음 관계 Context를 포함한다. 아래는 전송 형식의 의미를 설명하는 JSON 예시이며, 실제 Java DTO나 네트워크 패킷은 MythicTRPG와 AI 응답 모드의 별도 계약에 맞춘다.

```json
{
  "sessionId": "conv_001",
  "playerId": "player-uuid-or-game-id",
  "godId": "mythictrpg:lubras",
  "relationship": {
    "affinityStage": 2,
    "relationshipTag": "R_FRIENDLY",
    "auxiliaryRelationshipTags": [
      "RT_LOVER"
    ],
    "axes": {
      "affinity": 78,
      "trust": 32,
      "respect": 81,
      "caution": 18
    }
  }
}
```

| 필드 | 필수 | 규칙 |
|---|---:|---|
| `playerId` | 예 | 관계 대상 플레이어의 안정적인 ID |
| `godId` | 예 | MythicTRPG가 소유하는 God `ResourceLocation` |
| `affinityStage` | 예 | `-4`부터 `+4`까지의 9단계 정수 |
| `relationshipTag` | 예 | 해당 단계에 대응하는 고정 `R_*` 태그 |
| `auxiliaryRelationshipTags` | 아니오 | 플레이어가 포함된 쌍처럼 게임 중 변할 수 있는 보조 관계 유형 `RT_*` 배열 |
| `axes` | 아니오 | 향후 trust/respect/caution 등 추가 축. 원본 수치는 MythicTRPG가 소유 |

`affinityStage`와 `relationshipTag`는 반드시 같은 단계를 의미해야 한다. 예를 들어 `2`는 항상 `R_FRIENDLY`다. AI 응답 모드는 이 둘이 불일치하거나 지원하지 않는 태그를 받으면 로그를 남기고, 해당 턴에서는 안전하게 `R_NEUTRAL` 지침만 사용해야 한다. 이 오류가 관계 수치를 변경해서는 안 된다.

`auxiliaryRelationshipTags`는 호감도 단계를 대체하지 않는다. `RT_LOVER`는 `R_DEEP_BOND`가 되었다고 자동 부여하면 안 되며, MythicTRPG의 명시적인 게임 상태로만 전달한다.

### 4.2 AI 응답 모드 → 콘텐츠 레지스트리

AI 응답 모드는 `relationshipTag`를 `RelationshipTier`로 변환해 **현재 발화의 대상 God ID**로만 조회한다.

```java
RelationshipTier tier = RelationshipTier.fromTag(request.relationship().relationshipTag());
List<String> guidance = AiContentRegistry.INSTANCE.relationshipGuidanceFor(request.godId(), tier);
```

조회 결과는 해당 신 프로필의 `relationshipGuidelines[relationshipTag]` 배열이다. 지침이 비어 있으면 빈 목록을 반환하며, AI 응답 모드는 기본 페르소나·말투·현재 관계 태그만으로 답변을 생성한다. 콘텐츠 레지스트리가 자동으로 인접 단계를 추정하거나 다른 NPC의 지침을 섞지 않는다.

NPC가 여러 명인 세션에서는 AI 응답 모드가 발화 후보 NPC와 다른 NPC 사이마다 `socialRelationTagsFor(speakerGodId, otherGodId)`를 조회해 보조 관계 태그를 합친다. 플레이어가 상대인 경우에는 콘텐츠 레지스트리를 조회하지 않고 MythicTRPG가 전달한 `auxiliaryRelationshipTags`만 사용한다.

### 4.3 AI 응답 모드 → MythicTRPG

AI 응답 모드는 관계 지침을 사용해 생성한 대사와, 필요할 경우 비구속 `RelationshipChangeProposal`을 반환할 수 있다.

```json
{
  "type": "relationship_change_proposal",
  "targetPlayerId": "player-uuid-or-game-id",
  "changes": {
    "affinity": -1
  },
  "reason": "반복적인 모욕과 경고 무시"
}
```

이 Proposal은 제안일 뿐이다. MythicTRPG가 조건·밸런스·현재 상태를 검증한 뒤에만 실제 관계 수치를 변경한다. 콘텐츠 레지스트리와 AI 응답 모드는 관계 수치를 직접 저장하거나 변경하지 않는다.

## 5. 공개 판단

지식 단계는 NPC가 **아는 범위**를 제한한다. 프롬프트에 넣을 수 있는지는 작성자의 공개 정책과 게임의 실제 전체 청중으로 먼저 제한한다. 성격·감정·관계는 허용된 내용 중 무엇을 자연스럽게 말할지 결정할 수 있지만 금지된 사실을 새로 허가하지 않는다.

```text
원본 profile/lore → 신별 보유단계 → 명시 disclosure + 전체 실제 청중 → AudienceGodContent → LLM
```

`PUBLIC`은 공개/비공개 전체 플레이어 청중, `PRIVATE_ROOM`은 비공개 전체 플레이어 청중, `NEVER`는 프롬프트 공급 금지를 뜻한다. 선택 `allowedGodIds`가 비어 있지 않으면 화자 외 현재 신 전원이 포함되어야 한다. 미지정 로어는 `secrecy: PUBLIC`만 허용하고 기존 비PUBLIC은 차단한다. 기존 프로필 필드와 예시는 공개 persona/말투 자료로 호환하므로 **기존 identity/description/예시에 비밀을 써 놓았다면 작성자가 명시 정책을 추가해야 한다.** 자동 비밀 분류나 친밀도 기반 공개권한 부여는 없다.

필드별 양식과 예제는 [콘텐츠 작성 가이드](../mythai-ai-content-registry/AI_CONTENT_REGISTRY_CONTENT_AUTHORING_GUIDE.md) 및 [적용되지 않는 로어 예제](../mythai-ai-content-registry/examples/lore_disclosure.example.json)를 참고한다.

## 6. 오류·재로딩 동작

| 상황 | 동작 |
|---|---|
| JSON 문법 또는 필수 필드 오류 | reload 전체 거부, 이전 정상 Snapshot 유지 |
| 동일 MythicTRPG God ID에 프로필 2개 | reload 전체 거부 |
| 프로필이 존재하지 않는 로어·예시 ID 참조 | reload 전체 거부 |
| 프로필의 로어 단계가 로어 최대 단계를 초과 | reload 전체 거부 |
| 로어 단계가 1부터 연속되지 않음 | reload 전체 거부 |
| 존재하지 않는 God ID 또는 보유하지 않은 Lore ID 조회 | `Optional.empty()` |

## 7. 연동 흐름

```text
1. MythicTRPG → AI 응답 모드
   ConversationRequest + 게임 Snapshot + RelationshipContext

2. AI 응답 모드 → AI 콘텐츠 레지스트리
   God ID + relationshipTag 기준 프로필·단계 제한 로어·예시·관계 지침 조회

3. AI 응답 모드 → Local LLM
   선택된 정적 콘텐츠 + 현재 Snapshot + 기억

4. AI 응답 모드 → MythicTRPG
   DialogueResponse + 비구속 Proposal
```

레지스트리의 출력은 읽기 전용 정적 데이터다. 이 모드는 `DialogueResponse`, 아이템 지급, 퀘스트 등록, 관계 변경 Proposal을 만들지 않는다.
