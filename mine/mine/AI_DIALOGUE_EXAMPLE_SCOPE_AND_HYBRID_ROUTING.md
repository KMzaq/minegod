# NPC 예시 대화 범위와 하이브리드 라우팅

## 1. NPC별 예시 대화 범위

`dialogue-examples.json`은 기존 공용/NPC 전용 완성 대화 라이브러리다. 특정 NPC의 고유 표현 예시를 다른 NPC가 그대로 사용하지 않도록, NPC 전용 예시에는 `known_by`에 기존 God `ResourceLocation` ID를 넣는다.

```json
{
  "exampleId": "EX_HEPHAESTUS_ITEM_01",
  "known_by": ["mythictrpg:greek_olympian_hephaestus"],
  "tags": ["P_GRUFF", "P_INDIRECT_CARE", "S_ITEM_REQUEST"],
  "dialogue": [
    {"role": "player", "text": "검을 만들어줄래?"},
    {"role": "npc", "text": "재료부터 가져와."}
  ]
}
```

- `known_by`가 비어 있거나 생략된 예시는 모든 NPC가 사용할 수 있는 공용 예시다.
- 값은 NPC 이름이 아니라 God Definition과 동일한 `ResourceLocation`이어야 한다.
- 한 예시를 여러 신이 공유할 수 있으면 여러 ID를 배열에 넣는다.
- 검색 시 현재 대화의 NPC ID와 정확히 일치하는 전용 예시와 공용 예시만 후보가 된다. 다른 NPC 전용 예시는 제외된다.

### 1.1 기본 경로: 태그별 조합 가이드

일반 대화의 기본 자료는 `config/mythictrpg/dialogue-tag-guidance.json`이다. 이 파일은 조합마다 완성 대화를 복제하지 않고, **태그 하나당** 기본 규칙과 짧은 예시 하나를 둔다.

```json
{
  "tag": "S_ITEM_REQUEST",
  "priority": 90,
  "rules": [
    "재료, 조건, 제한을 먼저 다룬다.",
    "게임 승인 전에는 제작이나 지급이 완료되었다고 말하지 않는다."
  ],
  "dialogue": [
    {"role": "player", "text": "무기를 만들어 줄 수 있어?"},
    {"role": "npc", "text": "용도와 재료부터 말해라."}
  ]
}
```

- `P_*`는 NPC의 기본 말투 규칙과 예시다.
- `R_*`, `E_*`는 기본 말투를 지우지 않고 친밀도와 현재 감정에 맞게 조절하는 규칙이다.
- `S_*`는 요청 유형에 맞는 반응 규칙이다.
- `C_*`는 서버가 전달한 참가자·청중 상태로만 만든다. 1단계 LLM이 이 태그를 결정하지 않는다.
- 규칙은 우선순위가 높은 순서로 전달된다. 기본 설정에서 상황(`S`) → 관계(`R`) → 감정(`E`) → 말투(`P`) 순으로 제약이 강하다. `C_UNTRUSTED_LISTENER`와 `C_PRIVATE_TOPIC`처럼 비밀 공개에 관련된 청중 규칙은 최상위 수준이다.
- 예시는 기본적으로 상황·관계·말투에서 하나씩 우선 선택하고, `exampleRetrievalLimit`(3~5)까지만 전달한다. 나머지 활성 태그는 규칙만 전달한다.

새 태그 가이드가 선택되면 이전 `dialogue-examples.json`의 복합 예시는 프롬프트에 섞지 않는다. 기존 파일은 새 가이드가 없는 콘텐츠 팩을 위한 호환용 fallback이며, NPC만의 서명 표현이 꼭 필요할 때만 유지한다.

## 2. 두 단계 하이브리드 처리

대화 한 턴은 다음 우선순위로 처리된다.

1. 짧고 명확한 인사·감사·작별·단순 잡담은 기존 규칙/검색 경로를 사용한다.
2. `아이템`, `퀘스트`, `의뢰`, `사과`처럼 의미가 명확한 단일 요청은 코드가 즉시 `S_*` 태그를 부여한다. 이 경우 1단계 LLM 호출을 생략한다.
3. 모호한 부탁, 비밀·관계 요청, 보상 협상, 보증·갈등, 여러 신이 참여한 대화는 1단계 LLM 분류를 거친다. 키워드가 있어도 사회적 판단이 필요한 경우에는 빠른 경로를 사용하지 않는다.
4. 1단계 결과는 다음 생성 단계에서만 사용하는 자문용 `ConversationIntent`다. 월드 사실, 관계 수치, 퀘스트 상태가 아니다.
5. 2단계 생성은 1단계의 `S_*` 태그와 지식 검색어, 서버가 만든 `P_*`·`R_*`·`E_*`·`C_*` 태그, 태그별 규칙·예시, 페르소나, 허가된 지식, 게임 Snapshot을 함께 받아 최종 대사와 비구속 Proposal을 만든다.

1단계 출력은 다음처럼 제한된다.

```json
{
  "primarySituation": "S_INFORMATION_REQUEST",
  "secondarySituations": ["S_SECRET_REQUEST"],
  "knowledgeKeywords": ["티탄 전쟁"],
  "confidence": 0.86
}
```

알 수 없는 태그는 무시되며, 분류 실패 시 기존 휴리스틱 검색으로 안전하게 대체된다. 1단계가 반환하는 것은 `S_*` 태그뿐이다. 그룹, 신뢰하지 않는 청자, 여러 신의 참여 같은 `C_*` 태그는 게임 세션 Snapshot에서만 계산된다. 분류 결과는 세션 전역이나 NPC 전역에 저장하지 않고 해당 턴에만 붙이므로 다른 세션으로 섞이지 않는다.

## 3. 운영 설정

서버의 `config/mythictrpg/ai-dialogue.json`에서 조정할 수 있다.

```json
{
  "hybridIntentRoutingEnabled": true,
  "intentRoutingMaxOutputTokens": 96,
  "exampleRetrievalLimit": 3
}
```

`hybridIntentRoutingEnabled`를 `false`로 바꾸면 1단계 LLM 분류 없이 서버 휴리스틱으로 `S_*` 태그를 만든다. `exampleRetrievalLimit`은 최종 프롬프트에 들어가는 태그별 대화 예시 수(3~5)다. 규칙 자체는 활성 태그별로 계속 전달된다.

## 4. 책임 경계

예시 검색과 1단계 분류는 AI 모듈의 자문 기능이다. 실제 아이템 지급, 퀘스트 등록, 보상 검증, 관계 수치 변경, 참가자·청중 판정은 기존 Minecraft/Mythic TRPG 시스템이 authoritative source로 계속 소유한다.
