# 신 NPC 프로필 작성 가이드

이 문서는 `MythAI Content Registry`에서 신 NPC의 고정 프로필 JSON을 작성하는 방법을 설명한다.

프로필에는 성격·가치관·말투·세계 지식 범위·관계 단계별 태도·사용 가능한 퀘스트 목록처럼 제작자가 정한 고정 콘텐츠만 작성한다. 현재 감정, 실제 호감도, 퀘스트 진행 상태, 플레이어 기억처럼 플레이 중 변하는 값은 넣지 않는다.

## 1. 파일 위치

```text
src/main/resources/data/<namespace>/mythai_ai/god_profiles/<소속신화_소속_신이름>.json
```

기본 namespace를 사용한다면 다음 위치에 작성한다.

```text
src/main/resources/data/mythaiaicontent/mythai_ai/god_profiles/
```

## 2. 파일명과 ID 규칙

2026-10-04 사용자 확정 기준: 신규 신의 파일명과 God ID의 path는 소문자 영어 `소속신화_소속_신이름` 형식으로 작성한다. 이전의 두 부분 `소속_이름` 기준을 대체한다.

```text
<mythology>_<affiliation>_<name>
```

예시:

| 신 | 신화 | 소속 | 작성 파일명 |
|---|---|---|---|
| 데메테르 | Greek | Olympian | `greek_olympian_demeter.json` |
| 크로노스 | Greek | Titan | `greek_titan_cronus.json` |
| 하데스 | Greek | Underworld | `greek_underworld_hades.json` |
| 포세이돈 | Greek | Olympian | `greek_olympian_poseidon.json` |
| 포르투나 | Roman | 미정 | `roman_affiliation_fortuna.json` (`affiliation`은 작성 시 결정) |

그리스 예시는 기존 135신 테스트 데이터팩의 ID를 따른다. `olympian`과 `olympus`를 같은 ID로 취급하지 않는다. 포르투나의 소속은 이름만으로 임의 확정하지 않는다. 이 문서 갱신은 기존 게임 God ID·참조·월드 저장 자료를 변경하거나 이관하지 않는다.

허용 문자는 다음과 같다.

```text
a-z, 0-9, _, -, /
```

대문자, 한글, 공백은 파일명과 ID에 사용하지 않는다.

```text
올바름: greek_olympian_demeter
잘못됨: Greek_Olympian_Demeter
잘못됨: 그리스_올림포스_데메테르
잘못됨: greek olympian demeter
```

### 프로필 콘텐츠 ID

파일 경로가 프로필 콘텐츠 ID가 된다.

```text
파일:
data/mythaiaicontent/mythai_ai/god_profiles/greek_olympian_demeter.json

프로필 콘텐츠 ID:
mythaiaicontent:greek_olympian_demeter
```

프로필 JSON 안에는 `contentId`를 직접 작성하지 않는다.

### `godId`

`godId`는 MythicTRPG가 소유하는 실제 신 ID다. 신규 신은 MythicTRPG 측 God Definition도 같은 `소속신화_소속_신이름` 규칙으로 작성한다.

```json
"godId": "mythictrpg:greek_olympian_demeter"
```

단, 기존 테스트 신 `mythictrpg:demeter`를 사용 중인 프로필만 `mythictrpg:greek_olympian_demeter`로 바꾸면 안 된다. 두 ID는 서로 다른 게임 identity이며 자동 별칭이 아니다. 기존 테스트 신을 이전하려면 게임 정의·모든 참조·저장 데이터의 이관을 별도로 맞춘다.

## 3. 전체 양식

```json
{
  "schemaVersion": 2,
  "godId": "mythictrpg:greek_olympian_demeter",
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
| `fieldDisclosure` | 아니오 | 필드별 전체 청중 공개 규칙. 미지정 필드는 기존 공개 persona로 유지 |

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

### 신의 독립적 동기 작성

- `description`·`personality`·`values`에는 신이 무엇을 원하고 지키며 어떤 부탁을 거절할 수 있는지 고정된 성격을 쓴다. `dialogueGuidelines`에는 그 성격을 대화 전반에서 적용하는 원칙을, `situationGuidelines`에는 도움·사과·갈등처럼 실제 상황이 맞을 때만 쓰는 판단을 둔다. `relationshipGuidelines`는 게임이 전달한 관계 단계에 따른 태도 변화이며, 친밀함이 자동 승낙이나 복종을 뜻하지 않도록 작성한다.
- 자애로운 신은 계속 따뜻할 수 있고 장난스러운 신은 계속 호기심을 보일 수 있다. 거절·이견·서운함도 자기 가치와 실제 문맥에서 나오게 하며, 모든 신이 거절하거나 사소한 반말을 모욕으로 처벌하게 만들지 않는다. 가까운 관계에서는 믿음과 애정을 실제로 드러내되 매번 권위나 교훈을 강조하지 않는다.
- 상대의 물리적 힘, 신격·사회적 지위, 실제 후원·보호 약속, 호감과 현재 감정은 서로 다르다. 확인된 게임 정보나 허용된 기억이 없으면 힘의 서열·후원 관계·과거 사건을 프로필에서 만들어 내지 않는다. NPC가 믿거나 추측하는 말은 그 신의 관점으로 표현하며 확정된 월드 사실이나 실행 완료로 쓰지 않는다.
- 도움·가호·퀘스트·보상의 의향과 실제 실행을 구분한다. 작성 지침은 허용된 제안을 어떻게 말할지 정하지만 게임의 지급·관계 변경·퀘스트 판정을 대신하지 않는다.

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

- 신규 신 파일명이 소문자 영어 `소속신화_소속_신이름.json`인가?
- `godId`가 실제 MythicTRPG God ID와 일치하는가?
- 퀘스트 본문을 프로필에 직접 넣지 않았는가?
- 참조한 `loreId`, `questListId`, `signatureExampleId`가 실제로 존재하는가?
- 대화 지침이 게임 기능을 실행했다고 주장하게 만들지 않는가?
- JSON 문법상 마지막 항목 뒤에 불필요한 쉼표가 없는가?

## 9. 프로필 지식의 선택적 공개 규칙

프로필 문장에 비밀 정체나 과거사가 포함되면 해당 필드에 선택 `fieldDisclosure`를 작성할 수 있다. 기존 자료에는 필수 변경이 없으며 자동으로 비밀 여부를 추측하거나 정식 설정을 다시 쓰지 않는다.

```json
"fieldDisclosure": {
  "description": { "mode": "PRIVATE_ROOM", "allowedGodIds": ["mythictrpg:demeter"] },
  "identity": { "mode": "NEVER" },
  "examples": { "mode": "PUBLIC" }
}
```

지원 키는 `displayName`, `identity`, `description`, `personality`, `values`, `speechStyles`, `dialogueGuidelines`, `situationGuidelines`, `repetitionGuidelines`, `restrictions`, `characterTags`, `relationshipGuidelines`, `examples`, `socialRelationTags`다. 정의되지 않은 키나 공개규칙 내부의 오타 필드는 reload를 거부한다. `mode`와 `allowedGodIds`의 정확한 뜻은 [콘텐츠 작성 가이드](AI_CONTENT_REGISTRY_CONTENT_AUTHORING_GUIDE.md#대화방-공개-규칙--선택-필드-기존-자료-호환)를 따른다.

금지된 문자열은 빈 문자열, 목록/맵은 빈 값으로 사전 투영하며 다른 성격·가치·말투는 그대로 보존한다. 예를 들어 비밀 과거사인 `description`을 차단해도 공개 `personality`를 친절한 성격으로 바꾸지 않는다. `identity`를 숨기는 필터는 AI 프롬프트용이고 실제 게임의 신 ID/표시명/등장 판정 원본을 바꾸지 않는다. 정체 공개 자체는 게임의 식별 정책도 함께 설정해야 한다.

한 필드에 공개 성격과 비밀 사건을 섞으면 필드 전체를 숨겨야 한다. 구체적인 비밀 사실은 단계형 로어로 분리하고 `loreKnowledge`로 보유수준을 지정하는 편이 좋다. `PRIVATE_ROOM`은 **현재 비공개 방의 플레이어 전원**에게 허용한다는 작성자의 명시 권한이므로, “친밀한 플레이어 한 명만 알 수 있음”과 혼동하지 않는다. 그런 조건은 별도 게임 권한/공개 정책이 필요하며 현재 자동 부여하지 않는다.
