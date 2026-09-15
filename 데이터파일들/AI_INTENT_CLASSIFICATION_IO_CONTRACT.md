# AI 대화 1단계(Intent Classification) 입·출력 규격

문서 버전: 1.0  
대상: AI 응답 모드 제작자, AI 콘텐츠 작성자, MythicTRPG 연동 제작자

## 1. 목적과 경계

1단계는 **플레이어의 이번 한마디가 무엇을 요구하는지**만 읽는 경량 분류 단계다. 결과는 2단계 대사 생성과 지식/예시 검색을 돕는 힌트이며, 게임 상태를 변경하는 명령이 아니다.

1단계는 다음을 하지 않는다.

- 아이템·가호·퀘스트·보상을 실제로 지급/등록하지 않는다.
- NPC가 그 지식을 알고 있는지, 비밀을 공개할 수 있는지 최종 판정하지 않는다.
- 관계도·감정·세계관 사실을 저장하거나 확정하지 않는다.
- NPC의 최종 대사를 만들지 않는다.

이 결과를 받은 2단계와 게임 시스템이 각각 **지식 접근**, **관계/청중**, **제안 검증**을 수행한다.

## 2. 논리적 입력

전송 형식은 연동 모드가 정하되, 아래 정보가 1단계에 제공되어야 한다. 현재 테스트 어댑터는 최근 대화 최대 6줄과 이번 플레이어 문장을 사용한다.

```json
{
  "sessionId": "conv_0123",
  "playerId": "uuid-or-game-player-id",
  "currentPlayerMessage": "필요한 거 있어?",
  "recentConversation": [
    {
      "speakerType": "NPC",
      "speakerId": "greek_olympian_zeus",
      "text": "무엇을 찾으러 왔지?"
    },
    {
      "speakerType": "PLAYER",
      "speakerId": "uuid-or-game-player-id",
      "text": "필요한 거 있어?"
    }
  ]
}
```

필수 필드:

- `currentPlayerMessage`: 이번에 분류할 플레이어 원문. 빈 문자열이면 분류하지 않는다.

권장 필드:

- `sessionId`: 결과가 다른 세션으로 섞이지 않게 하는 세션 식별자.
- `playerId`: 로그 및 이후 관계 판단에 쓰는 플레이어 식별자.
- `recentConversation`: 최근 대화. 플레이어가 NPC의 직전 질문에 답하는지, 오해를 정정하는지 읽는 데 사용한다.

1단계의 기본 의도 분류에는 NPC 프로필, 관계도, 감정, 게임 스냅샷, 전체 로어를 넣지 않는다. 이 데이터는 2단계의 응답 판단에 사용한다.

## 3. 출력 형식

LLM 또는 규칙 기반 분류기는 **JSON 객체 하나만** 반환한다. Markdown, 설명문, 코드 블록을 덧붙이지 않는다.

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

| 필드 | 형식 | 규칙 |
| --- | --- | --- |
| `primarySituation` | 문자열 | 아래 10개 `S_` 태그 중 **정확히 1개**. 애매하면 `S_CHAT`. |
| `secondarySituations` | 문자열 배열 | 보조 상황 0~2개 권장. `primarySituation`과 중복하지 않는다. |
| `knowledgeKeywords` | 문자열 배열 | 로어 검색용 핵심어 0~5개. 각 2~64자, 사실을 만들어 내지 않는다. |
| `playerToneTags` | 문자열 배열 | 아래 `T_` 태그 0~3개 권장. 이번 발화의 표현 방식일 뿐 영구 관계도가 아니다. |
| `conversationAct` | 문자열 | 아래 대화 행위 중 1개. 판단 불가 시 `UNSPECIFIED`. |
| `confidence` | 정수 | 0~100. 구현은 0 미만/100 초과를 범위 안으로 보정한다. |

알 수 없는 태그, 형식이 틀린 값은 폐기한다. 모든 분류가 실패하면 호출 측은 안전한 기본값 `S_CHAT` 또는 빈 Intent를 사용하고, 이를 게임 행동으로 해석해서는 안 된다.

## 4. 상황 태그 (`primarySituation`, `secondarySituations`)

| 태그 | 의미 | 대표 발화 | 포함하지 않는 것 |
| --- | --- | --- | --- |
| `S_CHAT` | 별도 요구가 없는 기본 대화/잡담 | `안녕`, `오늘 날씨가 좋네` | 구체적인 부탁·질문·사과·갈등 |
| `S_ITEM_REQUEST` | 물건 제작, 획득, 수리, 교환 등의 부탁 | `검 하나 만들어 줄 수 있어?` | 가호/능력 그 자체의 부탁 |
| `S_POWER_REQUEST` | 축복, 가호, 신력, 능력 부여 요청 | `내게 번개의 가호를 내려 줘` | 일반적인 도움 요청 |
| `S_HELP_REQUEST` | 문제 해결을 위한 넓은 도움 요청 | `길을 잃었어. 도와줘` | NPC에게 맡길 일이 있는지 묻는 퀘스트 문의 |
| `S_INFORMATION_REQUEST` | 사실, 인물, 장소, 사건, 방법에 대한 질문 | `티탄 전쟁에서 무슨 일이 있었어?` | 비밀 여부 자체를 태그로 만들지 않는다. 비밀도 이 태그로 분류한다. |
| `S_QUEST_INQUIRY` | NPC에게 맡길 일/필요한 일/할 일이 있는지 묻거나 퀘스트를 문의 | `필요한 거 있어?`, `내게 맡길 일이 있어?` | 이미 정해진 보상의 흥정 |
| `S_REWARD_NEGOTIATION` | 보상 조건·수량·대가를 협상 | `보상을 조금 더 받을 수 있을까?` | 단순 선물 전달 |
| `S_GIFT_OFFER` | 선물, 공물, 헌납을 건네거나 제안 | `이 사과를 받아 줘` | 아이템을 달라고 요청하는 것 |
| `S_APOLOGY` | 사과, 용서 요청, 잘못 인정 | `아까 무례했어. 미안해` | 단순히 공손한 말투 (`T_POLITE`) |
| `S_CONFLICT` | 비난, 반박, 다툼, 도발, 명시적 대립 | `네 판단은 틀렸어` | 가벼운 반말만으로는 사용하지 않는다. |

### 우선순위 원칙

- 하나의 발화에 요청과 사과가 함께 있으면, 핵심 목적을 `primarySituation`으로 두고 나머지를 `secondarySituations`에 둔다.  
  예: `미안하지만 이번만 가호를 받을 수 있을까?` → `S_POWER_REQUEST` + `S_APOLOGY`
- `S_CONFLICT`는 상대를 공격/반박하는 대화 목적일 때만 사용한다. 말투가 거칠다는 이유만으로 붙이지 않는다.
- `S_APOLOGY`는 말의 **의도**, `T_APOLOGETIC`은 말의 **표현 방식**이다. 둘은 함께 나올 수 있다.
- `S_VOUCH`, `S_SECRET_REQUEST`, `S_QUEST_OFFER`, `S_SMALLTALK`는 새 출력에 사용하지 않는다. 기존 데이터 호환용으로만 남아 있다.
- 다른 플레이어 보증은 별도 상황 태그가 아니다. 필요하면 이후 사회적 상호작용/Proposal 판단에서 처리한다.

## 5. 플레이어 말투 태그 (`playerToneTags`)

| 태그 | 의미 | 예 |
| --- | --- | --- |
| `T_POLITE` | 존댓말·공손한 부탁 | `도와주실 수 있습니까?` |
| `T_INFORMAL` | 반말·친근한 구어체 | `이거 해 줄래?` |
| `T_IMPOLITE` | 무례하거나 깎아내리는 표현 | `쓸모도 없네` |
| `T_AGGRESSIVE` | 강한 공격성·적대적 압박 | `당장 내놔` |
| `T_MOCKING` | 조롱·빈정거림 | `신이라더니 이 정도야?` |
| `T_THREATENING` | 해를 끼치겠다는 위협 | `안 주면 후회하게 해 주지` |
| `T_APOLOGETIC` | 미안함·후회의 표현 | `정말 미안해` |

말투 태그는 관계 수치나 플레이어의 인격을 판정하지 않는다. 예를 들어 `T_INFORMAL`에 대한 NPC의 반응은 2단계에서 친밀도, 현재 위계, 감정, 페르소나를 함께 보고 결정한다.

## 6. 현재 대화 행위 (`conversationAct`)

`conversationAct`는 상황 태그보다 더 작은 현재 대화 흐름 신호다. 해당하지 않으면 `UNSPECIFIED`를 쓴다.

| 값 | 의미 |
| --- | --- |
| `GREETING` | 인사 |
| `CASUAL_FEELING` | 심심함, 피곤함, 외로움 등 현재 기분 표현 |
| `SEEKING_COMPANY` | 특정 NPC와 이야기하고 싶다는 의사 |
| `ANSWERING_NPC_QUESTION` | 직전 NPC 질문에 대한 답변 |
| `CORRECTING_NPC` | NPC의 오해를 정정 |
| `REQUESTING_ACTIVITY` | 함께 할 가벼운 활동/할 거리 요청 |
| `CASUAL_BANTER` | 가벼운 농담·말장난·잡담 주고받기 |
| `UNSPECIFIED` | 위 어느 것에도 확실히 맞지 않음 |

예: `아니, 그게 아니라 티탄 전쟁의 결과를 묻는 거야.`는 `primarySituation: S_INFORMATION_REQUEST`, `conversationAct: CORRECTING_NPC`가 될 수 있다.

## 7. 지식 검색어 (`knowledgeKeywords`)

이 배열은 월드 로어를 고르는 검색어일 뿐, 지식 접근 허가가 아니다.

```json
{
  "primarySituation": "S_INFORMATION_REQUEST",
  "secondarySituations": [],
  "knowledgeKeywords": ["티탄 전쟁", "승패"],
  "playerToneTags": ["T_POLITE"],
  "conversationAct": "UNSPECIFIED",
  "confidence": 91
}
```

- 고유명사, 사건명, 장소명, 묻는 핵심 주제를 짧게 넣는다.
- NPC가 실제로 아는지, 지금 공개해도 되는지는 2단계의 로어/청중/관계 검증이 결정한다.
- `제우스가 이겼다`처럼 답을 단정하거나, 입력에 없는 설정을 검색어로 만들지 않는다.

## 8. 1단계 분류 예시 콘텐츠 작성 규격

분류용 예시는 다음 폴더에 둔다.

`data/mythaiaicontent/mythai_ai/dialogue_examples/intent_classifier/`

이 폴더 안의 JSON은 **플레이어 한 줄만** 가질 수 있다. NPC의 답변은 넣지 않는다. 이 예시는 1단계 분류기에만 전달되며, 2단계 대사 생성 프롬프트에는 전달되지 않는다.

```json
{
  "schemaVersion": 2,
  "tags": ["S_QUEST_INQUIRY"],
  "known_by": [],
  "dialogue": [
    {
      "role": "player",
      "text": "필요한 거 있어?"
    }
  ]
}
```

작성 규칙:

- 파일 하나는 대표 플레이어 발화 한 개를 담는다.
- `tags`에는 위 10개 중 상황 태그 하나만 넣는다.
- `known_by`는 반드시 빈 배열 `[]`이다. 모든 NPC가 공통으로 쓰는 분류 사전이기 때문이다.
- 파일명은 자유지만 소문자/숫자/밑줄을 권장한다. 실제 리소스 ID는 `mythaiaicontent:intent_classifier/<파일명>`으로 만들어진다.
- 인사체, 반말체, 존댓말체, 짧은 표현 등 다양한 표현을 각 태그마다 3개 이상 준비한다.
- 일반 예시대화와 달리, 이 폴더에서만 한 턴짜리 `dialogue`가 허용된다.

현재 기본 예시는 31개이며, 실제 경로는 다음과 같다.

`C:\Users\ADMIN\Desktop\markmar\mythai-ai-content-registry\src\main\resources\data\mythaiaicontent\mythai_ai\dialogue_examples\intent_classifier\`

## 9. 2단계로 넘기는 범위

1단계 결과는 해당 `sessionId`의 이번 턴에만 붙인다. 2단계는 이를 NPC 프로필, 관계/감정 스냅샷, 접근 가능한 로어, 최근 대화와 결합해 대사를 만든다.

```text
플레이어 메시지 + 최근 대화
        ↓
1단계: Situation / Tone / Act / Keywords
        ↓
세션 전용 Context Builder
        ↓
2단계: NPC 대사 + Quest/Reward/Relationship Proposal
        ↓
MythicTRPG 검증 및 실제 게임 처리
```

어떤 결과도 다른 `ConversationSession`의 분류, 제약, 검증 피드백과 섞이면 안 된다.

## 10. 검수 체크리스트

- `primarySituation`이 10개 허용 태그 중 하나인가?
- `secondarySituations`가 주 태그와 중복하지 않는가?
- `S_VOUCH`나 제거된 구형 상황 태그를 새 출력에 쓰지 않았는가?
- `knowledgeKeywords`가 질문에서 나온 검색어이며, 답을 지어내지 않았는가?
- 말투(`T_`)와 의도(`S_`)를 혼동하지 않았는가?
- JSON 외의 문장을 반환하지 않았는가?
- 결과를 실제 보상/퀘스트/관계 변경 명령으로 사용하지 않았는가?
