# MythicTRPG ↔ AI 응답 모드: 1단계 판단 태그 IO 규격

이 문서는 MythicTRPG 모드가 AI 응답 모드에 전달하고, AI 응답 모드가 반환하는 **1단계 대화 의도 판단**만 정의한다.

1단계 결과는 대사 생성·지식 검색을 위한 힌트다. 퀘스트 지급, 보상 지급, 관계 수치 변경 등 게임 기능을 실행하는 명령이 아니다.

## 1. 요청: MythicTRPG → AI 응답 모드

```json
{
  "sessionId": "conv_0123",
  "playerId": "player-uuid",
  "currentPlayerMessage": "필요한 거 있어?",
  "recentConversation": [
    {
      "speakerType": "NPC",
      "speakerId": "greek_olympian_zeus",
      "text": "무엇을 찾으러 왔지?"
    },
    {
      "speakerType": "PLAYER",
      "speakerId": "player-uuid",
      "text": "필요한 거 있어?"
    }
  ]
}
```

| 필드 | 필수 | 설명 |
| --- | --- | --- |
| `sessionId` | 예 | 대화 세션 식별자. 결과가 다른 세션에 섞이지 않게 한다. |
| `playerId` | 예 | 발화 플레이어 식별자. |
| `currentPlayerMessage` | 예 | 이번에 판단할 플레이어 원문. 빈 문자열은 요청하지 않는다. |
| `recentConversation` | 권장 | 최근 대화 배열. 최근 6줄 이하를 권장한다. |
| `recentConversation[].speakerType` | 권장 | `PLAYER` 또는 `NPC`. |
| `recentConversation[].speakerId` | 권장 | 발화자 ID. |
| `recentConversation[].text` | 권장 | 발화 원문. |

## 2. 응답: AI 응답 모드 → MythicTRPG

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
| `primarySituation` | 문자열 | 아래 상황 태그 중 정확히 1개. 애매하면 `S_CHAT`. |
| `secondarySituations` | 문자열 배열 | 보조 상황. 0~2개 권장, 주 태그와 중복 금지. |
| `knowledgeKeywords` | 문자열 배열 | 로어 검색용 핵심어. 0~5개. |
| `playerToneTags` | 문자열 배열 | 이번 발화의 말투. 0~3개 권장. |
| `conversationAct` | 문자열 | 현재 대화 흐름. 아래 값 중 하나. |
| `confidence` | 정수 | 0~100. |

응답은 JSON 객체 하나만 반환한다. JSON 외 설명문이나 Markdown은 포함하지 않는다.

## 3. 상황 태그

`primarySituation`과 `secondarySituations`에만 사용한다.

| 태그 | 의미 | 예시 |
| --- | --- | --- |
| `S_CHAT` | 별도 요구가 없는 일반 대화/잡담 | `안녕`, `오늘은 조용하네` |
| `S_ITEM_REQUEST` | 아이템 제작·획득·수리·교환 요청 | `검 하나 만들어 줄 수 있어?` |
| `S_POWER_REQUEST` | 가호·축복·신력·능력 요청 | `번개의 가호를 내려 줘` |
| `S_HELP_REQUEST` | 넓은 의미의 도움 요청 | `길을 잃었어. 도와줘` |
| `S_INFORMATION_REQUEST` | 인물·사건·장소·방법 등 정보 질문 | `티탄 전쟁에서 무슨 일이 있었어?` |
| `S_QUEST_INQUIRY` | NPC에게 맡길 일/필요한 일/퀘스트가 있는지 문의 | `필요한 거 있어?`, `내게 맡길 일이 있어?` |
| `S_REWARD_NEGOTIATION` | 보상 조건·수량·대가 협상 | `보상을 조금 더 받을 수 있을까?` |
| `S_GIFT_OFFER` | 선물·공물·헌납 제안 | `이 사과를 받아 줘` |
| `S_APOLOGY` | 사과·용서 요청 | `아까 무례했어. 미안해` |
| `S_CONFLICT` | 비난·반박·도발·명시적 대립 | `네 판단은 틀렸어` |

규칙:

- 비밀을 묻는 질문도 `S_INFORMATION_REQUEST`를 사용한다. 공개 가능 여부는 이후 단계에서 지식·관계·청중으로 판단한다.
- 다른 플레이어 보증은 상황 태그가 아니다. 이후 사회적 상호작용 판단에서 처리한다.
- `S_VOUCH`, `S_SECRET_REQUEST`, `S_QUEST_OFFER`, `S_SMALLTALK`는 새 요청/응답에서 사용하지 않는다.

## 4. 플레이어 말투 태그

`playerToneTags`에만 사용한다. 말투 태그는 관계도나 영구 성향이 아니다.

| 태그 | 의미 |
| --- | --- |
| `T_POLITE` | 공손한 말투 |
| `T_INFORMAL` | 반말·친근한 구어체 |
| `T_IMPOLITE` | 무례하거나 깎아내리는 표현 |
| `T_AGGRESSIVE` | 강한 공격성·압박 |
| `T_MOCKING` | 조롱·빈정거림 |
| `T_THREATENING` | 위협 |
| `T_APOLOGETIC` | 미안함·후회 표현 |

예: 친한 플레이어의 반말도 `T_INFORMAL`로 반환할 수 있다. 이를 불쾌하게 받아들일지는 2단계에서 NPC 성격, 관계, 감정, 위계를 함께 보아 판단한다.

## 5. 현재 대화 행위

`conversationAct`에는 아래 중 하나를 넣는다. 확실하지 않으면 `UNSPECIFIED`를 사용한다.

| 값 | 의미 |
| --- | --- |
| `GREETING` | 인사 |
| `CASUAL_FEELING` | 심심함·피곤함·외로움 등 기분 표현 |
| `SEEKING_COMPANY` | 특정 NPC와 이야기하고 싶음 |
| `ANSWERING_NPC_QUESTION` | 직전 NPC 질문에 답함 |
| `CORRECTING_NPC` | NPC의 오해를 정정함 |
| `REQUESTING_ACTIVITY` | 가벼운 활동/할 거리 요청 |
| `CASUAL_BANTER` | 농담·말장난·가벼운 주고받기 |
| `UNSPECIFIED` | 해당 없음 또는 불명확 |

## 6. 세션 격리

- `sessionId`별로 1단계 요청·응답을 완전히 분리한다.
- Session A의 최근 대화, 분류 결과, 검색어를 Session B의 요청에 재사용하지 않는다.
- 이 결과 자체는 authoritative game state가 아니며, MythicTRPG가 최종 게임 상태와 기능 실행 권한을 가진다.
