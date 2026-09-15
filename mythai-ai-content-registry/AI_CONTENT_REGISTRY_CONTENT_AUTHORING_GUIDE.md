# AI 콘텐츠 레지스트리 데이터 작성 가이드

**대상:** 신 페르소나, 단계형 세계 지식, 예시 대화, 정적 퀘스트 목록을 작성하는 콘텐츠 제작자  
**모드:** `MythAI Content Registry` (`mythaiaicontent`)  
**현재 데이터 형식:** Datapack JSON, `schemaVersion: 2`

이 데이터는 변하지 않는 기본 콘텐츠다. 퀘스트 정의와 진행도 트랙 ID는 넣을 수 있지만, 플레이어 관계·현재 감정·수락/진행/완료 상태·실제 지급 결과처럼 플레이 중 변하는 정보는 넣지 않는다.

## 1. 공통 규칙

1. 파일 인코딩은 UTF-8을 사용한다.
2. 신규 파일은 모두 최상단에 `"schemaVersion": 2`를 넣는다.
3. 파일명과 폴더명은 소문자 영문·숫자·`_`·`-`·`/`만 쓴다.
4. `godId`에는 MythicTRPG 담당자가 제공한 기존 `ResourceLocation`을 쓴다. 표시명은 ID가 아니다.
5. 배열 필드는 비어 있어도 생략하지 말고 `[]`로 작성한다.
6. JSON 수정 후 서버에서 `/reload`를 실행한다. 오류가 있으면 이전 정상 콘텐츠가 유지된다.

## 2. 파일 경로와 콘텐츠 ID

| 종류 | 경로 | ResourceLocation ID 예시 |
|---|---|---|
| 신 프로필 | `data/<namespace>/mythai_ai/god_profiles/<path>.json` | `mythaiaicontent:greek/athena` |
| 로어 | `data/<namespace>/mythai_ai/lore/<path>.json` | `mythaiaicontent:greek/titan_war` |
| 예시 대화 | `data/<namespace>/mythai_ai/dialogue_examples/<path>.json` | `mythaiaicontent:greek/athena_strategy` |
| 정적 NPC 관계 | `data/<namespace>/mythai_ai/social_relations/<path>.json` | `mythaiaicontent:greek/zeus_athena` |
| 퀘스트 목록 | `data/<namespace>/mythai_ai/quest_lists/<path>.json` | `mythictrpg:quest_list_demeter` |

예를 들어 `data/mythaiaicontent/mythai_ai/lore/greek/titan_war.json`의 로어 ID는 `mythaiaicontent:greek/titan_war`다. JSON 안에 별도 `id` 필드는 쓰지 않는다.

## 3. 신 기본 프로필

신 프로필은 신의 변하지 않는 성격·화법과 **그 신이 각 로어를 어디까지 아는지**를 정의한다. 한 `godId`에는 프로필이 정확히 하나만 존재해야 한다.

```json
{
  "schemaVersion": 2,
  "godId": "mythictrpg:greek_olympian_athena",
  "displayName": "아테나",
  "identity": "전략과 지혜를 상징하는 올림포스의 신격이다.",
  "description": "성급한 용기보다 준비된 판단을 높게 평가한다.",
  "personality": ["신중함", "논리성", "절제된 자부심"],
  "values": ["전략", "책임", "정당한 승리"],
  "speechStyles": ["P_FORMAL", "P_WISE", "P_CALM"],
  "restrictions": [
    "승인되지 않은 게임 기능을 실행했다고 말하지 않는다.",
    "허가되지 않은 세계관 사실을 단정하지 않는다."
  ],
  "characterTags": [
    "그리스 신화",
    "올림포스의 신",
    "신",
    "여성",
    "지혜",
    "전쟁",
    "책략가",
    "인간간섭"
  ],
  "loreKnowledge": [
    {
      "loreId": "mythaiaicontent:greek/titan_war",
      "level": 3
    }
  ],
  "questListIds": [
    "mythictrpg:quest_list_athena",
    "mythictrpg:quest_list_olympus"
  ],
  "signatureExampleIds": [
    "mythaiaicontent:greek/athena_strategy"
  ],
  "relationshipGuidelines": {
    "R_WARY": [
      "경계심을 유지하고, 신뢰를 얻을 행동을 먼저 요구한다."
    ],
    "R_FRIENDLY": [
      "이전의 노력을 인정할 수 있지만, 지나친 친밀함을 먼저 보이지는 않는다."
    ],
    "R_TRUSTED": [
      "필요할 때는 직접적인 조언을 주며, 위험에는 단호하게 경고한다."
    ]
  }
}
```

| 필드 | 필수 | 설명 |
|---|---:|---|
| `godId` | 예 | MythicTRPG의 기존 신 ResourceLocation ID |
| `displayName` | 예 | 표시용 신 이름 |
| `identity` | 예 | 신의 정체성 한두 문장 |
| `description` | 아니오 | 기본 배경·행동 기준 |
| `personality`, `values`, `speechStyles`, `restrictions` | 예 | 신의 정적 페르소나 데이터 |
| `characterTags` | 아니오 | 신화권·종족·성별·권능·소속·성격 등 복수의 정적 분류 태그 배열. 없으면 빈 배열로 처리 |
| `loreKnowledge` | 예 | 이 신의 로어 보유 수준 목록 |
| `loreKnowledge[].loreId` | 예 | 로어 파일 경로에서 생성된 ResourceLocation ID |
| `loreKnowledge[].level` | 예 | 이 신이 알고 있는 최고 지식 단계. 3이면 1·2·3단계를 모두 안다. |
| `questListIds` | 아니오 | 이 신이 제안할 수 있는 신 전용·세력 공용 퀘스트 목록 ID 배열 |
| `signatureExampleIds` | 예 | 이 신의 고유 예시 대화 ID 배열 |
| `relationshipGuidelines` | 아니오 | 관계 단계 태그별로 이 신이 보일 정적 말투·태도 지침 배열. 없으면 빈 객체로 처리 |

한 프로필 안에서 동일한 `loreId`를 두 번 쓰면 오류다. 존재하지 않는 로어 또는 로어의 최대 단계보다 높은 `level`을 지정해도 `/reload`가 거부된다.

### 캐릭터 태그 작성 규칙

`characterTags`에는 제공된 **신화·캐릭터 태그 분류 체계**의 태그를 대괄호 없이 문자열로 넣는다. 태그는 여러 개를 자유롭게 넣을 수 있고, 중복은 자동으로 제거된다.

```json
"characterTags": [
  "그리스 신화",
  "올림포스의 신",
  "신",
  "여성",
  "지혜",
  "전쟁",
  "책략가",
  "인간간섭"
]
```

권장 핵심 축은 신화권, 존재종, 위계, 성별, 성향, 권능, 속성, 세계/소속이다. 그 밖에 성격·무기·전투 방식·외형·상징·관계·기원·현재 상태·인간에 대한 태도·위험도·서사적 역할을 필요에 따라 더한다.

태그는 고정된 분류 자료일 뿐 게임 기능을 직접 부여하지 않는다. 예를 들어 `"번개"` 태그가 있다고 해서 아이템 지급이나 번개 소환 권한이 생기지 않는다. 현재 감정, 플레이어와의 관계, 진행 중인 퀘스트처럼 변하는 정보도 태그로 쓰지 않는다.

현재 레지스트리는 태그 목록을 문자열 그대로 보존하며, 허용 태그를 코드로 강제하지 않는다. 오탈자와 표현 불일치를 막기 위해 콘텐츠 작성자는 제공된 태그 목록의 표기를 통일해야 한다.

### 관계 단계별 태도 지침

`relationshipGuidelines`는 관계 수치가 아니라 **관계 단계에 따라 이 NPC가 어떻게 말하고 행동을 제안하는가**를 적는 정적 데이터다. 실제 수치 보관과 단계 판정은 MythicTRPG가 담당한다.

| 수치 단계 | 관계 태그 | 의미 |
|---:|---|---|
| -4 | `R_EXTREME_HOSTILE` | 극도의 적대 — 적극적으로 해치거나 제거하려 함 |
| -3 | `R_HOSTILE` | 적대적 — 강한 반감·적의를 가짐 |
| -2 | `R_DISLIKE` | 불쾌/반감 — 싫어하고 부정적으로 평가함 |
| -1 | `R_WARY` | 비호감/경계 — 꺼리거나 경계하지만 적극적으로 적대하지는 않음 |
| 0 | `R_NEUTRAL` | 중립 — 특별한 감정이나 관심이 없음 |
| +1 | `R_FAVORABLE` | 관심/호의 — 약간 긍정적으로 봄 |
| +2 | `R_FRIENDLY` | 호감 — 좋아하거나 편하게 여김 |
| +3 | `R_TRUSTED` | 강한 호감 — 상당히 좋아하고 신뢰함 |
| +4 | `R_DEEP_BOND` | 극도의 호감/애정 — 매우 깊이 좋아하거나 특별하게 여김 |

```json
"relationshipGuidelines": {
  "R_WARY": [
    "상대의 의도를 확인하기 전에는 개인적인 도움을 약속하지 않는다.",
    "대답은 짧고 조심스럽게 유지한다."
  ],
  "R_FRIENDLY": [
    "이전의 노력을 인정할 수 있다.",
    "위험한 부탁에는 경고와 대안을 함께 제시한다."
  ],
  "R_TRUSTED": [
    "직접적인 칭찬은 드물지만, 플레이어를 신뢰하는 표현을 사용한다.",
    "위험에 처하면 평소보다 짧고 단호하게 걱정한다."
  ]
}
```

모든 단계를 반드시 작성할 필요는 없지만, 작성하지 않은 단계에는 해당 NPC 고유 지침이 전달되지 않는다. 관계 변화가 크게 드러나는 신이라면 9단계를 모두 작성하는 것이 좋다.

`R_EXTREME_HOSTILE`이라도 실제 공격·제거·아이템 회수 같은 게임 기능을 실행했다고 말하면 안 된다. AI는 대사 또는 Proposal만 만들고, 실제 실행 여부는 MythicTRPG가 검증한다.

### NPC 간 보조 관계 태그

`R_FRIENDLY` 같은 `R_*` 태그는 **현재 호감도 단계**다. 반면 부신·모신·연인·숙적·창조자 같은 관계 유형은 여러 개를 함께 붙일 수 있는 **보조 관계 태그**이며, 항상 `RT_*` 접두사를 사용한다.

정적 신화 관계는 신 프로필에 넣지 않고 `social_relations`에 참가자 쌍마다 하나의 파일로 작성한다. 관계는 방향이 있으므로 `participantA`가 `participantB`를 보는 태그와 그 반대 태그를 각각 적는다.

```json
{
  "schemaVersion": 2,
  "participantA": "mythictrpg:greek_olympian_zeus",
  "participantB": "mythictrpg:greek_olympian_athena",
  "aToBTags": [
    "RT_FATHER",
    "RT_BLOOD_RELATION"
  ],
  "bToATags": [
    "RT_CHILD",
    "RT_BLOOD_RELATION"
  ]
}
```

위 예시에서 제우스가 발화자이고 아테나가 상대면 `RT_FATHER`, `RT_BLOOD_RELATION`이 사용된다. 아테나가 발화자이면 `RT_CHILD`, `RT_BLOOD_RELATION`이 사용된다.

| 분류 | 허용 태그 |
|---|---|
| 가족 | `RT_FATHER`, `RT_MOTHER`, `RT_CHILD`, `RT_BROTHER`, `RT_SISTER`, `RT_TWIN`, `RT_BLOOD_RELATION` |
| 친밀 관계 | `RT_SPOUSE`, `RT_LOVER`, `RT_COMRADE` |
| 대립 관계 | `RT_NEMESIS`, `RT_RIVAL`, `RT_ENEMY` |
| 위계 관계 | `RT_MASTER`, `RT_SERVANT` |
| 기원 관계 | `RT_CREATOR`, `RT_CREATION` |

한 방향에 여러 태그를 붙일 수 있다. 예를 들어 아버지이면서 혈연이고 숙적인 존재도 표현할 수 있다. 단, 동일한 두 NPC 쌍은 하나의 `social_relations` 파일에서만 정의한다.

`RT_MASTER`와 `RT_SERVANT`는 반드시 방향을 나눠 쓴다. 모호한 `RT_MASTER_SERVANT`는 사용하지 않는다.

이 파일은 God ID끼리의 변하지 않는 신화 관계 전용이다. 플레이어와 NPC 사이의 연인·사제·입양·계약 등 게임 중 생기거나 바뀔 수 있는 관계는 MythicTRPG가 현재 Snapshot으로 AI 응답 모드에 전달해야 하며, 콘텐츠 레지스트리에 저장하지 않는다.

## 4. 단계형 세계 지식 로어

로어는 세계관의 고정 사실 또는 전승이다. 한 로어는 1부터 연속된 지식 단계로 작성한다. 신이 3단계를 보유하면 LLM에는 1·2·3단계만 전달된다. 4단계 이상의 원문은 전달되지 않는다.

```json
{
  "schemaVersion": 2,
  "title": "티탄 전쟁",
  "knowledgeLevels": [
    {
      "level": 1,
      "content": "먼 옛날 신들과 티탄 사이에 거대한 전쟁이 있었다."
    },
    {
      "level": 2,
      "content": "올림포스 신격과 주요 티탄들이 이 전쟁에 참여했다."
    },
    {
      "level": 3,
      "content": "전쟁은 올림포스 측의 승리로 끝났고, 패배한 티탄 다수는 타르타로스에 봉인되었다."
    },
    {
      "level": 4,
      "content": "전쟁의 전말을 누가 어디까지 알고 있는지는 그 자체로 중요한 비밀이다.",
      "revealKnowledgeHolders": true
    }
  ],
  "secrecy": "DIVINE",
  "keywords": ["티탄 전쟁", "크로노스", "타르타로스"]
}
```

| 필드 | 필수 | 설명 |
|---|---:|---|
| `title` | 예 | 로어 제목 |
| `knowledgeLevels` | 예 | 최소 1개인 단계 배열 |
| `knowledgeLevels[].level` | 예 | 반드시 `1, 2, 3 ...` 순서로 연속 작성 |
| `knowledgeLevels[].content` | 예 | 해당 단계에서 새롭게 알게 되는 고정 사실 |
| `knowledgeLevels[].revealKnowledgeHolders` | 아니오 | `true`면 이 단계 이상을 아는 신에게 “누가 몇 단계까지 아는가” 목록도 제공. 기본값 `false` |
| `secrecy` | 아니오 | `PUBLIC`, `RESTRICTED`, `DIVINE`, `SECRET`. 기본값 `PUBLIC` |
| `keywords` | 예 | 검색용 핵심어 배열 |

### 누가 이 지식을 아는가

로어 파일에 `known_by`를 수동으로 쓰지 않는다. 각 신 프로필의 `loreKnowledge`가 유일한 원본이며, 레지스트리가 다음과 같은 역색인을 자동으로 만든다.

```text
루브라스 프로필: titan_war 3단계
아테나 프로필:   titan_war 4단계
        ↓
레지스트리의 titan_war 보유자 목록
루브라스: 3단계 / 아테나: 4단계
```

`revealKnowledgeHolders: true`인 단계를 보유한 신만 이 보유자 목록을 LLM Context에서 볼 수 있다. 따라서 아테나는 “누가 이 전쟁을 아는가”를 논할 수 있지만, 3단계의 루브라스는 그 목록을 보지 못한다.

`secrecy`와 지식 단계는 다르다.

- 지식 단계: NPC가 **무엇을 알고 있는가**
- `secrecy`: NPC가 알고 있는 내용을 **이 자리에서 말해도 되는가**를 AI 응답 모드가 관계·청중·상황과 함께 판단할 때 쓰는 분류

## 5. 퀘스트 목록

퀘스트 목록은 여러 신이 재사용할 수 있는 정적 정의다. 신 프로필은 `questListIds`에 여러 목록을 참조할 수 있다. `ResourceLocation`은 대문자를 허용하지 않으므로 `mythictrpg:QuestList_Demeter`가 아니라 `mythictrpg:quest_list_demeter`처럼 작성한다.

```json
{
  "schemaVersion": 2,
  "questListId": "mythictrpg:quest_list_demeter",
  "progressTrackId": "mythictrpg:progress_demeter",
  "displayName": "데메테르 퀘스트",
  "quests": [
    {
      "questId": "mythictrpg:demeter_first_harvest",
      "title": "첫 수확의 봉헌",
      "content": "잘 익은 곡물을 수확하여 데메테르에게 성의를 보인다.",
      "objectives": [
        {
          "type": "collect_item",
          "description": "밀 32개를 모은다.",
          "parameters": { "itemId": "minecraft:wheat", "count": 32 }
        }
      ],
      "rewards": [
        {
          "type": "item",
          "description": "에메랄드 4개를 받는다.",
          "parameters": { "itemId": "minecraft:emerald", "count": 4 }
        }
      ],
      "acceptanceConditions": [
        {
          "type": "world_quest_progress_range",
          "description": "서버 전역 데메테르 진행도 0~20에서 수주할 수 있다.",
          "parameters": { "progressTrackId": "mythictrpg:progress_demeter", "minimum": 0, "maximum": 20 }
        }
      ],
      "progressOnClear": 10
    }
  ]
}
```

| 필드 | 필수 | 설명 |
|---|---:|---|
| `questListId` | 예 | 파일 경로에서 생성되는 ID와 정확히 같아야 함 |
| `progressTrackId` | 예 | 완료 시 증가하는 서버 전역 0~100 진행도 트랙. 신 전용 또는 세력 공용 가능 |
| `displayName` | 예 | 제작·관리용 목록 이름 |
| `quests` | 예 | 비어 있을 수 있는 퀘스트 배열 |
| `questId` | 예 | 전체 목록에서 중복되지 않는 퀘스트 ID |
| `title`, `content` | 예 | 플레이어에게 설명할 제목과 내용 |
| `objectives`, `rewards` | 예 | 각각 최소 1개. `type`, 설명, 단순 문자열/숫자 `parameters` 사용 |
| `acceptanceConditions` | 아니오 | MythicTRPG가 판정할 수주 조건 배열. 콘텐츠 레지스트리는 실행하지 않음 |
| `progressOnClear` | 예 | 권한 있는 퀘스트 엔진이 완료를 확정한 뒤 해당 트랙에 더할 0~100 값 |

`progressTrackId`를 통해 전용과 공용 진행도를 함께 표현한다.

```text
quest_list_demeter  -> progress_demeter
quest_list_olympus  -> progress_olympus
```

따라서 데메테르가 두 목록을 참조하면 데메테르 전용 퀘스트와 올림포스 공용 퀘스트를 모두 제안할 수 있다. 진행도는 플레이어별이 아니라 서버 전체가 공유한다. 예를 들어 누군가 `progress_demeter`를 20까지 올리면 다른 플레이어도 0~19 구간 퀘스트를 새로 받을 수 없다. 여러 퀘스트가 현재 전역 진행도에서 실제로 수주 가능하면 AI는 간단한 선택지를 제시하고 플레이어의 선택 전에는 임의로 하나를 수락시키지 않는다. 실제 수주 가능 후보 필터링·수주자 기록·클리어·보상 지급·전역 진행도 갱신은 MythicTRPG 책임이다. 전체 빈 양식은 `QUEST_LIST_TEMPLATE.json`을 복사해 사용한다.

## 6. 예시 대화

예시 대화는 LLM이 말투·반응 방식·문장 길이를 참고하도록 돕는 자료다. 그대로 복사할 고정 대사가 아니며, 실제 게임 기능을 실행했다고 주장하면 안 된다.

```json
{
  "schemaVersion": 2,
  "tags": ["P_WISE", "P_FORMAL", "S_INFORMATION_REQUEST"],
  "known_by": ["mythictrpg:greek_olympian_athena"],
  "dialogue": [
    { "role": "player", "text": "어느 길을 택해야 할까?" },
    { "role": "npc", "text": "더 빠른 길보다, 실패했을 때 다시 설 수 있는 길을 먼저 살펴보아라." }
  ]
}
```

예시 대화의 `known_by`는 유지한다. 이것은 **예시를 어떤 신의 말투 참고 자료로 사용할 수 있는가**를 뜻하며, 로어의 단계형 지식과는 별개다. `[]`이면 공용 예시다.

권장 태그 분류:

```text
P_*  NPC 성격·말투      예: P_GRUFF, P_FORMAL, P_WISE
R_*  관계 상태           예: R_STRANGER, R_CLOSE
E_*  현재 감정           예: E_NEUTRAL, E_ANGRY
S_*  대화 상황           예: S_SMALLTALK, S_INFORMATION_REQUEST
C_*  청중·대화 구조      예: C_ONE_TO_ONE, C_GROUP
```

모든 조합별 예시를 만들 필요는 없다. 공용 말투 예시 + 공용 상황 예시 + 신별 서명 예시 1~5개가 권장된다.

## 7. 작성 금지 데이터

```text
현재 affinity / trust / respect / caution
현재 anger / gratitude 등 감정 수치
플레이어별 기억과 채팅 로그
플레이어 인벤토리·체력·위치
진행 중인 퀘스트와 실제 보상
오늘 발생한 사건과 월드 변경 사항
아이템 지급, 몹 소환, 관계 수치 변경 명령
```

이 데이터는 MythicTRPG 또는 AI 응답 모드가 Snapshot·로그·검증 절차를 통해 관리한다.

## 8. 이전 양식

`schemaVersion: 1`의 기존 `loreIds`와 단일 로어 `content`는 읽을 수 있도록 호환 처리되어 있으며, 각각 1단계 지식으로 해석된다. 신규 콘텐츠는 반드시 `schemaVersion: 2`, `loreKnowledge`, `knowledgeLevels` 양식을 사용한다.

## 9. `/reload` 전 점검표

- [ ] 모든 파일이 유효한 JSON이며 신규 파일은 `schemaVersion: 2`인가?
- [ ] 한 God ID에 프로필이 하나만 있는가?
- [ ] `loreKnowledge[].loreId`가 실제 로어 파일 ID를 가리키는가?
- [ ] 지정한 `loreKnowledge[].level`이 로어 최대 단계를 넘지 않는가?
- [ ] 로어 단계가 1부터 빠짐없이 연속되는가?
- [ ] `signatureExampleIds`가 실제 파일 ID를 가리키는가?
- [ ] 전용 예시의 `known_by`에 해당 프로필의 `godId`가 포함되는가?
- [ ] 변하는 게임 상태를 넣지 않았는가?

오류가 있으면 `/reload`가 실패하고 서버는 이전 정상 Snapshot을 계속 사용한다.
