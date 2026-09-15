# 신 NPC 프로필 작성 가이드

이 문서는 `MythAI Content Registry`에서 신 NPC의 고정 프로필 JSON을 작성하는 방법을 설명한다.

프로필에는 성격·가치관·말투·세계 지식 범위·관계 단계별 태도·사용 가능한 퀘스트 목록처럼 제작자가 정한 고정 콘텐츠만 작성한다. 현재 감정, 실제 호감도, 퀘스트 진행 상태, 플레이어 기억처럼 플레이 중 변하는 값은 넣지 않는다.

## 1. 파일 위치

```text
src/main/resources/data/<namespace>/mythai_ai/god_profiles/<소속_이름>.json
```

기본 namespace를 사용한다면 다음 위치에 작성한다.

```text
src/main/resources/data/mythaiaicontent/mythai_ai/god_profiles/
```

## 2. 파일명과 ID 규칙

파일명과 ID의 path는 반드시 소문자 영어 `소속_이름` 형식으로 작성한다.

```text
<affiliation>_<name>
```

예시:

| 신 | 소속 | 권장 파일명 |
|---|---|---|
| 데메테르 | Olympus | `olympus_demeter.json` |
| 크로노스 | Titan | `titan_cronus.json` |
| 하데스 | Underworld | `underworld_hades.json` |
| 포세이돈 | Sea | `sea_poseidon.json` |
| 포르투나 | Roman | `roman_fortuna.json` |

허용 문자는 다음과 같다.

```text
a-z, 0-9, _, -, /
```

대문자, 한글, 공백은 파일명과 ID에 사용하지 않는다.

```text
올바름: olympus_demeter
잘못됨: Olympus_Demeter
잘못됨: 올림포스_데메테르
잘못됨: olympus demeter
```

### 프로필 콘텐츠 ID

파일 경로가 프로필 콘텐츠 ID가 된다.

```text
파일:
data/mythaiaicontent/mythai_ai/god_profiles/olympus_demeter.json

프로필 콘텐츠 ID:
mythaiaicontent:olympus_demeter
```

프로필 JSON 안에는 `contentId`를 직접 작성하지 않는다.

### `godId`

`godId`는 MythicTRPG가 소유하는 실제 신 ID다. 신규 신은 MythicTRPG 측 God Definition도 같은 `소속_이름` 규칙으로 만드는 것을 권장한다.

```json
"godId": "mythictrpg:olympus_demeter"
```

단, 이미 존재하는 신의 `godId`가 `mythictrpg:demeter`라면 프로필만 임의로 `mythictrpg:olympus_demeter`로 바꾸면 안 된다. MythicTRPG의 God Definition ID와 정확히 일치해야 한다.

## 3. 전체 양식

```json
{
  "schemaVersion": 2,
  "godId": "mythictrpg:olympus_demeter",
  "displayName": "데메테르",
  "identity": "곡물과 농경, 수확을 관장하는 올림포스의 여신이다.",
  "description": "성실한 노동과 생명의 결실을 중요하게 생각한다.",
  "personality": [
    "차분하고 위엄 있다",
    "인내심이 강하다",
    "보호할 대상에게 현실적인 온기를 보인다"
  ],
  "values": [
    "생명과 결실",
    "성실한 노동",
    "가족과 보호"
  ],
  "speechStyles": [
    "P_CALM",
    "P_FORMAL",
    "P_SHORT",
    "P_INDIRECT_CARE"
  ],
  "dialogueGuidelines": [
    "플레이어의 현재 말에 먼저 직접 반응한다.",
    "평범한 대화를 매번 신탁이나 교훈으로 바꾸지 않는다."
  ],
  "situationGuidelines": {
    "S_CHAT": [
      "평범한 잡담에는 한두 문장으로 자연스럽게 답한다."
    ],
    "S_QUEST_INQUIRY": [
      "게임 시스템이 현재 수주 가능하다고 제공한 퀘스트만 설명한다.",
      "후보가 여러 개면 차이를 설명하고 플레이어에게 선택하게 한다."
    ]
  },
  "repetitionGuidelines": {
    "CASUAL_COMPLAINT": [
      "같은 넋두리가 반복되면 이미 들었다는 사실을 자연스럽게 드러낸다."
    ]
  },
  "restrictions": [
    "승인되지 않은 게임 기능을 실행했다고 말하지 않는다.",
    "허용되지 않은 퀘스트, 보상, 가호를 즉석에서 만들지 않는다."
  ],
  "characterTags": [
    "그리스 신화",
    "올림포스",
    "여신",
    "농경",
    "수확"
  ],
  "loreKnowledge": [
    {
      "loreId": "mythaiaicontent:greek/titan_war",
      "level": 2
    }
  ],
  "questListIds": [
    "mythictrpg:olympus_demeter",
    "mythictrpg:olympus_common"
  ],
  "signatureExampleIds": [],
  "relationshipGuidelines": {
    "R_NEUTRAL": [
      "특별한 친밀감 없이 차분하게 말한다."
    ],
    "R_FRIENDLY": [
      "격식을 조금 내려놓고 현실적인 걱정을 표현한다."
    ],
    "R_TRUSTED": [
      "플레이어를 믿을 만한 사람으로 대한다."
    ]
  }
}
```

## 4. 필드 설명

| 필드 | 필수 | 설명 |
|---|---:|---|
| `schemaVersion` | 예 | 현재 `2` |
| `godId` | 예 | MythicTRPG에 실제로 등록된 God ID |
| `displayName` | 예 | 게임과 AI 대화에서 사용할 표시 이름 |
| `identity` | 예 | 정체성과 신격을 설명하는 핵심 문장 |
| `description` | 아니오 | 배경, 사고방식, 기본 행동 기준 |
| `personality` | 예 | 성격 특징 배열 |
| `values` | 예 | 중요하게 여기는 가치 배열 |
| `speechStyles` | 예 | 기본 말투 태그 배열 |
| `dialogueGuidelines` | 아니오 | 모든 대화에 적용할 작성 지침 |
| `situationGuidelines` | 아니오 | `S_*` 상황별 반응 지침 |
| `repetitionGuidelines` | 아니오 | 반복 대화에 대한 NPC 고유 지침 |
| `restrictions` | 예 | AI가 만들거나 실행하면 안 되는 내용 |
| `characterTags` | 아니오 | 신화권·소속·권능·성격 등 검색용 태그 |
| `loreKnowledge` | 예 | 해당 NPC가 알고 있는 로어와 최고 단계 |
| `questListIds` | 아니오 | 이 NPC가 제안할 수 있는 퀘스트 목록 ID 배열 |
| `signatureExampleIds` | 예 | 이 NPC가 특별히 참조할 예시대화 ID 배열 |
| `relationshipGuidelines` | 아니오 | 관계 단계별 말투와 태도 지침 |

## 5. 퀘스트 목록 연결

신 전용 퀘스트와 세력 공용 퀘스트를 동시에 연결할 수 있다.

```json
"questListIds": [
  "mythictrpg:olympus_demeter",
  "mythictrpg:olympus_common"
]
```

목록 ID만 프로필에 적고 퀘스트 제목·목표·보상은 퀘스트 목록 JSON에 작성한다. 프로필 안에 `quest_id=...` 같은 문장을 넣지 않는다.

## 6. 관계 단계

지원하는 관계 단계는 다음과 같다.

```text
R_EXTREME_HOSTILE
R_HOSTILE
R_DISLIKE
R_WARY
R_NEUTRAL
R_FAVORABLE
R_FRIENDLY
R_TRUSTED
R_DEEP_BOND
```

관계 지침은 수치를 저장하는 곳이 아니다. 해당 관계 단계에서 NPC가 어떻게 말하는지만 작성한다.

## 7. 작성 금지 항목

프로필에는 다음 데이터를 넣지 않는다.

- 현재 플레이어별 호감도와 감정
- 서버의 현재 퀘스트 진행도
- 현재 수주자와 진행 중 목표
- 실제 지급된 아이템과 보상
- 플레이어별 기억과 대화 로그
- 현재 날씨, 시간, 체력, 장비

이 값들은 MythicTRPG가 소유하고 AI에게 현재 Context로 전달한다.

## 8. 점검 목록

- 파일명이 소문자 영어 `소속_이름.json`인가?
- `godId`가 실제 MythicTRPG God ID와 일치하는가?
- 퀘스트 본문을 프로필에 직접 넣지 않았는가?
- 참조한 `loreId`, `questListId`, `signatureExampleId`가 실제로 존재하는가?
- 대화 지침이 게임 기능을 실행했다고 주장하게 만들지 않는가?
- JSON 문법상 마지막 항목 뒤에 불필요한 쉼표가 없는가?

