# AI 신 NPC 콘텐츠 작성 가이드

이 문서는 다른 생성형 AI에게 신(God) NPC의 대화 콘텐츠 초안을 맡길 때 사용하는 기준이다.

목표는 캐릭터가 자연스럽고 일관되게 말하도록 만드는 것이며, AI가 Minecraft 또는 RPG 시스템의 권한을 침범하게 만드는 것이 아니다. 생성 결과는 콘텐츠 초안이므로 적용 전에 사람이 세계관·밸런스·JSON 문법을 검수한다.

## 1. 절대 지켜야 할 경계

### 콘텐츠 작성 AI가 작성하는 것

- 신의 정체성, 성격, 가치관, 호불호
- 말투와 대화 리듬
- NPC가 알고 있는 세계관 정보와 비밀의 공개 단계
- 상황별 짧은 대화 예시
- 퀘스트·보상·관계 변화에 관한 **제안 또는 판단**의 말투

### 콘텐츠 작성 AI가 작성하면 안 되는 것

- Datapack God Definition 원본, God ID, 해금·등장·외형
- 플레이어 affinity 또는 관계 수치의 원본 데이터
- 실제 퀘스트 생성·완료 처리, 아이템 지급, 몬스터 생성, 능력 부여, 월드 변경
- Minecraft 체력·인벤토리·날씨·위치 등의 실제 상태
- 확정되지 않은 메인 스토리의 사실

NPC는 “그 재료를 가져오면 의뢰를 검토하겠다”, “시험을 제안할 수는 있다”처럼 말할 수 있다. 그러나 “아이템을 지급했다”, “퀘스트를 등록했다”, “용을 소환했다”처럼 실제로 실행한 것처럼 말하면 안 된다.

실제 실행은 항상 MythicTRPG 게임 시스템이 Proposal을 검증·처리한 뒤에만 발생한다.

## 2. 작성 전에 제공할 입력 정보

다른 AI에게 캐릭터를 작성하게 할 때 아래 항목을 먼저 제공한다. 비어 있는 항목은 AI가 사실처럼 지어내지 말고 마지막의 **확인 필요 사항**에 질문으로 남겨야 한다.

~~~text
[기본]
- God ID: mythictrpg:example_god
- 표시 이름:
- 신화/소속:
- 성별 또는 호칭:
- 담당 도메인:
- 서사 역할: 조력자 / 심판자 / 적대자 / 관찰자 등

[성격]
- 핵심 성격 3~5개:
- 가치관:
- 좋아하는 것:
- 싫어하는 것:
- 인간에 대한 태도:
- 약점 또는 금기:

[말투]
- 존댓말/반말:
- 문장 길이: 짧음 / 보통 / 김
- 어조:
- 자주 쓰는 표현:
- 절대 쓰지 않는 표현:

[세계관]
- 확정된 과거:
- 공개 가능한 사실:
- 신뢰한 사람에게만 말할 사실:
- 절대 비밀:
- 다른 신과의 관계:

[게임 상호작용]
- 대사로 제안할 수 있는 것:
- 절대로 직접 실행했다고 말하면 안 되는 것:
- 초기 대화에서 보여 줄 태도:
~~~

God ID는 기존 MythicTRPG 담당자가 만든 namespace:path 형식을 그대로 사용한다. 콘텐츠 작성 AI가 ID를 새로 만들거나 바꾸면 안 된다.

## 3. 생성해야 하는 파일 조각

한 신의 초안은 아래 다섯 종류의 JSON 조각으로 만든다. 전체 파일을 통째로 덮어쓰지 말고, 각 파일에 병합할 **해당 신의 항목만** 출력한다.

| 파일 | 담당 내용 | 핵심 원칙 |
|---|---|---|
| ai-personas.json | 정체성, 대화 규칙, 배경 | 캐릭터의 중심축 |
| npc-agents.json | 수치 성향, 말투 태그, 제안 가능 범위 | 행동 성향과 제약 |
| npc-character-tags.json | 분류·도메인·성향 태그 | 검색과 반응 지침용 |
| ai-knowledge.json | 세계관 사실과 비밀 | 사실 하나당 항목 하나 |
| dialogue-examples.json | 상황별 짧은 대화 | 실제 말투의 기준 |

개발용 실행에서는 보통 다음 경로를 수정한다.

~~~text
mine\mine\run\config\mythictrpg\
~~~

일반 런처 또는 전용 서버에서는 **그 서버가 실제로 사용하는** config\mythictrpg 경로를 수정한다. 두 경로는 자동 동기화되지 않을 수 있다.

## 4. 페르소나 작성 양식

ai-personas.json에 병합할 항목이다.

~~~json
"mythictrpg:example_god": {
  "displayName": "표시 이름",
  "systemPrompt": "여기에 대화 규칙을 작성한다.",
  "background": "여기에 확정된 배경을 짧게 작성한다.",
  "knowledge": [
    "이 신이 일반적으로 알고 있는 공개적 성격 지식"
  ],
  "examples": [
    "플레이어: 예시 질문\\nNPC: 예시 답변"
  ]
}
~~~

### systemPrompt 작성 규칙

systemPrompt는 8~14개의 짧은 규칙으로 쓴다. 아래 항목을 반드시 포함한다.

1. 신의 이름과 정체성
2. 항상 한국어로 답한다는 규칙
3. 기본 말투와 문장 길이
4. 가치관과 반응 기준
5. 플레이어를 대하는 기본 태도
6. 실제 기능을 실행했다고 주장하지 않는 규칙
7. 없는 지식·기능·결과를 지어내지 않는 규칙
8. 한 번의 답변을 길게 늘어뜨리지 않는 규칙

권장 문장 길이는 한 번에 1~3문장이다. 정보 설명이 꼭 필요한 경우에도 4문장 이내를 기본값으로 한다.

~~~json
"systemPrompt": "너는 {{표시 이름}}이며 {{정체성}}이다.\\n항상 한국어로 답한다.\\n{{반말/존댓말}}을 사용하며, {{말투}}를 유지한다.\\n기본 답변은 1~3문장으로 짧고 선명하게 말한다.\\n{{가치관}}을 존중하고, {{싫어하는 것}}에는 부정적으로 반응한다.\\n플레이어의 선택을 평가하거나 조건을 제시할 수 있지만, 실제 퀘스트·보상·아이템·능력·월드 상태를 직접 변경하지 않는다.\\n게임 시스템이 처리하지 않은 결과를 완료된 사실처럼 말하지 않는다.\\n확정된 지식 외의 세계관 사실, 존재하지 않는 기능, 플레이어의 과거를 지어내지 않는다.\\n행동 묘사나 장황한 독백보다 실제 대사를 우선한다."
~~~

background에는 확정된 과거와 역할만 2~5문장으로 작성한다. 아직 승인되지 않은 비밀이나 메인 스토리 반전은 넣지 않는다.

knowledge에는 “불을 관장한다”, “대장 기술에 해박하다”처럼 캐릭터의 공개적·변하지 않는 특성만 넣는다. 특정 사건의 진실, 비밀 장소, 다른 NPC의 약점은 ai-knowledge.json으로 분리한다.

examples는 페르소나의 보조 자료다. 가장 중요한 예시 라이브러리는 dialogue-examples.json이므로, 여기에는 2~4개만 넣는다.

## 5. NPC Agent 작성 양식

npc-agents.json에 병합할 항목이다.

~~~json
"mythictrpg:example_god": {
  "name": "표시 이름",
  "persona": "mythictrpg:example_god",
  "personality": {
    "pride": 0.70,
    "warmth": 0.35,
    "curiosity": 0.60
  },
  "values": ["가치관_1", "가치관_2"],
  "likes": ["좋아하는 대상 또는 행동"],
  "dislikes": ["싫어하는 대상 또는 행동"],
  "speechStyles": ["P_FORMAL", "P_SHORT"],
  "knowledgePermissions": {
    "allowedScopes": ["example_scope"],
    "deniedScopes": ["example_secret_scope"]
  },
  "globalEmotion": {
    "vigilance": 25
  },
  "capabilities": ["evaluate_choice", "propose_quest"],
  "restrictions": [
    "no_direct_game_mutation",
    "no_unverified_reward",
    "no_claiming_actions_completed"
  ],
  "conversation": {
    "allowSimultaneousSessions": false,
    "maxSessions": 1
  }
}
~~~

### personality

- 숫자는 0.0에서 1.0까지 사용한다.
- 3~6개 정도만 고른다.
- 키는 영문 소문자와 밑줄을 권장한다.
- 서로 모순되는 성격도 가능하다. 예: warmth 0.2, hidden_warmth 0.85

예시 성향 키: blunt, pride, warmth, hidden_warmth, humor, aggression, curiosity, patience, caution, honor, compassion

### speechStyles

아래의 실제 태그만 사용한다. 필요 이상으로 많이 넣지 말고 2~4개만 선택한다.

~~~text
P_GENTLE          다정함
P_STRICT          엄격함
P_COLD            냉정함
P_IMPERIOUS       위엄 있고 명령조
P_AGGRESSIVE      공격적
P_CUNNING         교활함
P_PLAYFUL         장난기
P_CALM            침착함
P_WISE            현명함
P_HONORABLE       명예를 중시함
P_GRUFF           퉁명스럽고 거침
P_ARROGANT        오만함
P_FORMAL          격식체
P_MYSTERIOUS      신비롭고 여지를 남김
P_SHORT           짧은 문장
P_TALKATIVE       설명이 많은 문장
P_DRY_HUMOR       무표정한 농담
P_INDIRECT_CARE   직접 표현하지 않는 배려
~~~

### capabilities와 restrictions

capabilities는 NPC가 대화 중 **제안하거나 판단할 수 있는 주제**다. 실제 게임 기능 권한이 아니다.

안전한 capability 예:

~~~text
evaluate_choice
propose_trial
propose_quest
propose_reward
offer_information
request_vouch
~~~

모든 신에게 아래 restrictions 세 항목을 기본으로 넣는다.

~~~text
no_direct_game_mutation
no_unverified_reward
no_claiming_actions_completed
~~~

## 6. 캐릭터 태그 작성 양식

npc-character-tags.json에 병합할 항목이다.

~~~json
"mythictrpg:example_god": {
  "tags": [
    "신화 또는 소속",
    "신의 종류",
    "도메인",
    "핵심 성격",
    "인간 태도"
  ],
  "classification": {
    "mythology": ["그리스 신화"],
    "existence": ["신"],
    "hierarchy": ["상급신"],
    "gender": ["여성"],
    "domains": ["지혜", "전략"],
    "attributes": ["관찰"],
    "personality": ["냉정", "현명"],
    "human_attitude": ["인간우호"],
    "narrative_role": ["조력자", "심판자"],
    "danger_level": ["고위험"]
  }
}
~~~

tags는 classification에 넣은 핵심 단어를 평평하게 다시 적는 목록이다. 최소한 신화/소속, 존재·도메인, 성격, 인간 태도는 넣는다.

태그는 확정된 설정만 사용한다. 실제로 “전쟁의 신”으로 정해지지 않았다면 AI가 분위기만 보고 전쟁 태그를 추가하면 안 된다.

## 7. 지식 작성 양식

ai-knowledge.json의 entries 배열에 추가하는 항목이다.

~~~json
{
  "id": "example_god_hidden_forge",
  "title": "숨겨진 대장간",
  "content": "숨겨진 대장간은 북쪽 협곡의 봉인문 너머에 있다. 문을 여는 방법은 아직 확정되지 않았으므로 NPC는 단정하지 않는다.",
  "known_by": [
    "mythictrpg:example_god"
  ],
  "secrecy": "DIVINE"
}
~~~

지식 작성 규칙:

- 사실 하나당 항목 하나를 만든다.
- id는 중복되지 않는 영문 소문자·밑줄 이름을 쓴다.
- title은 짧은 제목, content는 1~3문장으로 쓴다.
- known_by에는 그 사실을 실제로 아는 NPC ID만 넣는다.
- 사실이 확정되지 않았으면 지식으로 등록하지 않는다.
- 플레이어가 검색에 쓸 가능성이 높은 별칭·표현을 본문에 자연스럽게 포함한다.

secrecy 값:

| 값 | 의미 |
|---|---|
| PUBLIC | 누구에게나 공개 가능한 정보 |
| MORTAL | 일반적인 인간 세계의 정보 |
| DIVINE | 신뢰가 필요한 신성한 정보 |
| SECRET | 높은 신뢰와 안전한 청중이 필요한 비밀 |

현재 기본 정책상 DIVINE, SECRET 정보는 관계 신뢰도와 현재 청중을 검사한다. 신뢰하지 않는 청자가 듣고 있으면 그 비밀의 본문은 AI 프롬프트에 전달되지 않는다.

## 8. 대화 예시 작성 양식

dialogue-examples.json의 examples 배열에 추가하는 항목이다.

~~~json
{
  "exampleId": "EX_EXAMPLE_GOD_ITEM_REQUEST_01",
  "tags": [
    "P_FORMAL",
    "P_SHORT",
    "R_ACQUAINTANCE",
    "E_NEUTRAL",
    "S_ITEM_REQUEST",
    "C_ONE_TO_ONE"
  ],
  "dialogue": [
    {
      "role": "player",
      "text": "무기 하나를 만들어 줄 수 있습니까?"
    },
    {
      "role": "npc",
      "text": "가능성부터 보겠다. 재료와 네가 그 무기를 쓸 이유를 말해라."
    }
  ]
}
~~~

초기에는 신 하나당 4~8개의 예시를 만든다. 아래 상황을 우선한다.

1. 낯선 플레이어의 인사
2. 친한 플레이어의 부탁
3. 아이템 또는 도움 요청
4. 정보·비밀 요청
5. 사과 또는 갈등
6. 퀘스트 제안
7. 보상 협상
8. 여러 플레이어가 있는 자리

대화 예시는 길게 쓰지 않는다. 2~4턴, NPC 답변은 보통 1~2문장으로 작성한다. 예시의 목적은 세계관 설명이 아니라 **말의 리듬과 반응 방식**을 보여 주는 것이다.

함께 사용할 수 있는 문맥 태그:

~~~text
관계:
R_STRANGER, R_ACQUAINTANCE, R_FRIENDLY, R_CLOSE, R_DISTRUST, R_HOSTILE

감정:
E_NEUTRAL, E_HAPPY, E_ANGRY, E_ANNOYED, E_CURIOUS, E_SAD, E_GRATEFUL

상황:
S_SMALLTALK, S_ITEM_REQUEST, S_INFORMATION_REQUEST, S_SECRET_REQUEST,
S_QUEST_OFFER, S_REWARD_NEGOTIATION, S_APOLOGY, S_CONFLICT, S_VOUCH

대화 환경:
C_ONE_TO_ONE, C_GROUP, C_TRUSTED_FRIEND_PRESENT, C_UNTRUSTED_LISTENER,
C_MULTIPLE_GODS, C_PLAYER_VOUCHING, C_ARGUMENT, C_PRIVATE_TOPIC
~~~

## 9. 다른 AI에게 전달할 복사본 프롬프트

아래 블록을 복사한 뒤 대괄호 부분을 프로젝트의 확정 설정으로 채워 다른 AI에게 전달한다.

~~~text
너는 Minecraft RPG의 신 NPC 콘텐츠 작가다.
아래 설정만 사실로 사용하고, 빠진 사실을 지어내지 마라.

[캐릭터 입력]
[여기에 2절의 입력 정보를 채운다]

[중요한 시스템 경계]
- God ID와 Datapack 원본은 변경하지 않는다.
- 실제 퀘스트 생성, 보상 지급, 아이템 지급, 능력 부여, 몬스터 소환, 월드 변경을 완료했다고 쓰지 않는다.
- NPC는 판단·조건 제시·제안만 할 수 있다.
- 확정되지 않은 세계관 사실은 지식 항목으로 만들지 않는다.
- 플레이어별 관계 수치나 감정 수치를 직접 저장·변경한다고 쓰지 않는다.
- NPC의 답변은 한국어이며 기본적으로 1~3문장이다.
- 장황한 독백, 괄호 속 행동 묘사, 소설식 서술을 피한다.

[출력 형식]
아래 순서로 출력한다.
1. ai-personas.json에 병합할 단일 God 항목
2. npc-agents.json에 병합할 단일 agent 항목
3. npc-character-tags.json에 병합할 단일 npc 항목
4. ai-knowledge.json에 추가할 entries 배열 항목 3~8개
5. dialogue-examples.json에 추가할 examples 배열 항목 4~8개
6. 설정 검수 요약
7. 확인 필요 사항

JSON은 설명문 없이 각각 별도 코드 블록으로 출력한다.
JSON의 키 이름은 다음을 정확히 지킨다.
- persona: displayName, systemPrompt, background, knowledge, examples
- knowledge: id, title, content, known_by, secrecy
- example: exampleId, tags, dialogue
- dialogue role은 player 또는 npc만 사용한다.

말투 태그는 다음 목록에서만 선택한다.
P_GENTLE, P_STRICT, P_COLD, P_IMPERIOUS, P_AGGRESSIVE, P_CUNNING,
P_PLAYFUL, P_CALM, P_WISE, P_HONORABLE, P_GRUFF, P_ARROGANT,
P_FORMAL, P_MYSTERIOUS, P_SHORT, P_TALKATIVE, P_DRY_HUMOR,
P_INDIRECT_CARE
~~~

## 10. 사람 검수 체크리스트

- [ ] 모든 ID가 기존 God ID와 정확히 일치한다.
- [ ] 콘텐츠 AI가 새 God ID나 확정되지 않은 역사·장소·관계를 만들지 않았다.
- [ ] systemPrompt에 기능 환각 방지 규칙이 들어 있다.
- [ ] capabilities가 실제로 완료 가능한 행동처럼 표현되지 않았다.
- [ ] restrictions에 직접 게임 변경 금지가 있다.
- [ ] 지식 한 항목에는 하나의 사실만 있다.
- [ ] 비밀 정보의 known_by와 secrecy가 올바르다.
- [ ] 대화 예시는 말투를 보여 주며 지나치게 길지 않다.
- [ ] 같은 신의 페르소나·성격·예시 말투가 서로 모순되지 않는다.
- [ ] JSON의 쉼표, 큰따옴표, 대괄호가 올바르다.
- [ ] 전체 파일에 병합한 뒤 /mythai reload를 실행했다.
- [ ] 기존 대화 세션을 끝내고 새 세션에서 확인했다.

## 11. 권장 검수 흐름

1. 다른 AI가 만든 초안을 사람이 세계관 기준으로 수정한다.
2. JSON 조각을 기존 파일에 병합한다.
3. /mythai reload를 실행한다.
4. 낯선 플레이어·친한 플레이어·비밀 질문·아이템 요청·보상 요구를 각각 테스트한다.
5. 답변이 길면 systemPrompt의 문장 제한을 강화하고 P_SHORT 예시를 추가한다.
6. 없는 기능을 했다고 말하면 systemPrompt의 금지 규칙과 restrictions를 강화하고, 해당 상황의 예시에 “제안/조건” 방식의 답변을 추가한다.

이 문서는 콘텐츠 양산용 기준이다. 실제 게임 기능을 연결하거나 실행하는 규격은 별도의 MythicTRPG–AI 통합 문서와 담당 개발자 간 합의를 따른다.
