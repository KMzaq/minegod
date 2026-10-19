# 스토리 집필용 게임 연결 지원 범위·작성 예제

확인일: 2026-10-15 / `HanesTest`. 현재 소스·가이드·설정·서버 JAR을 읽어 확인한 자료다. 아래 예제는 작성 방법이며, 정식 스토리나 서버에 적용한 데이터가 아니다. `example:*`와 번역 키는 모두 교체·등록할 자리다.

## 1. 지원 범위

| 요구 | 확인된 지원 | 아직 연결·확인할 부분 |
|---|---|---|
| 현재 청중 모두에게 비밀을 공개해도 되는가 | **Story 사실**은 플레이어별 공개 조건을 검사해 전원에게 허용되는 내용만 제공한다. 정식 발언이 실제 전달되면 수신 플레이어·청취 신에게 지식을 저장한다. | 일반 정적 로어의 PRIVATE_ROOM에는 개인 비밀·퀘스트 조건이 없다. 퀘스트 진행을 공개 조건에서 직접 읽는 일반 연결도 없다. |
| NPC가 새로 안 사실을 이후 대화에 반영 | 실제 발언의 청취 기억과 등록된 Story 사실의 사건/정식 전달에 따른 지식 습득을 지원한다. | 자유 발언에서 새 정사·새 로어를 자동 생성/검증하는 기능은 없다. 운영 서버의 관찰·최신 활동 경험·지속 회상은 활성화/배포 상태를 별도로 확인해야 한다. |
| 봉인 단계·개별 인정·독점 MAIN 연결 | Story 상태를 **자동 조우** 조건으로 사용 가능. 새 SOLO MAIN_ENTRY로 최초 선택자에게 후속 MAIN 자격을 제한 가능. | 같은 Story 조건을 권위 퀘스트 수주 gate에 직접 넣는 포트와 MAIN 자격을 조우 조건에서 읽는 노드는 없다. |

이것은 기존 콘텐츠 JSON 스키마를 바꾼 결과가 아니다. 프로필·정적 로어와 별도로 이미 있는 **게임 Story 정의**를 사용해야 하는 항목을 구분한 것이다.

## 2. 청중별 비밀 공개

### 서로 다른 두 종류의 정보

- 정적 로어: `data/<namespace>/mythai_ai/lore/...json`, `schemaVersion: 2`, `knowledgeLevels`.
- 플레이 중 실제 발생/습득과 개인 자격을 관리하는 Story 사실: `data/<namespace>/mythictrpg/story_facts/...json`, `schemaVersion: 1`, `levels`.

정적 로어의 `disclosure`는 `PUBLIC / PRIVATE_ROOM / NEVER`와 다른 참가 신의 허용 목록만 검사한다. **PRIVATE_ROOM은 비밀방의 플레이어 전원에게 허용한다는 뜻이다.** 여기에는 `condition`, `requiredQuest`, `allowedPlayerIds` 같은 새 필드를 임의로 추가할 수 없다.

개인 자격·비밀 습득 정도에 따라 공개를 제어하는 중요한 정보는 Story Fact와 CONDITIONAL 정책으로 작성한다. 동일 비밀을 공개 프로필/정적 로어 본문에도 복사하면 Story 공개 검사와 무관한 공급 경로가 생기므로 피한다.

### 예제 A — 인정된 사람들에게만 공개

파일: `data/example/mythictrpg/story_disclosure_policies/secret_access.json`

```json
{
  "schemaVersion": 1,
  "mode": "CONDITIONAL",
  "maximumDisclosedLevel": 2,
  "condition": {
    "type": "mythictrpg:story_fact",
    "fact": "example:recognized_player",
    "scope": "TRIGGER_PLAYER",
    "expected": true
  },
  "lockedPolicyId": "mythictrpg:withhold"
}
```

전제: `example:recognized_player` Fact를 `allowedScopes: ["PLAYER"]`로 등록하고, 실제 인정 사건이 해당 플레이어의 값을 설정해야 한다. 정책 ID는 파일 경로에서 `example:secret_access`로 정해진다. `mythictrpg:withhold`는 현재 기본 정의에 있는 공개 거절 정책이다.

게임은 정책을 현재 청중 A, B 각각의 UUID로 평가한다. A만 인정되고 B가 인정되지 않았다면 이 사실을 공동 방 프롬프트에서 제외한다. 둘 다 자격이 있으면 화자가 실제로 아는 범위와 정책 최대 단계 안에서 공개할 수 있다. `FULL` 정책은 이 개인 자격 검사를 하지 않으므로, 모든 비밀이 자동으로 조건부가 되는 것은 아니다.

### 예제 B — 이미 아는 사람들에게만 다시 말하기

위 정책의 `condition`만 다음으로 바꾸면 된다.

```json
{
  "type": "mythictrpg:story_knowledge",
  "holder": "PLAYER",
  "fact": "example:secret_record",
  "minimumLevel": 2
}
```

이 정책은 **새로 가르칠 자격**과 다르다. 아직 모르는 친구에게도 지금 가르쳐도 되는 상황이라면, 이미 아는지 여부 대신 예제 A처럼 공개 자격 조건을 사용한다. 여러 조건은 `mythictrpg:all`/`mythictrpg:any`로 조합할 수 있다.

### 방 공개 범위는 별도로 작성

Story Fact의 `levels` 안에 넣는 조각:

```json
{
  "level": 1,
  "canonicalTranslationKey": "story.fact.example.secret_record.level1",
  "disclosure": {
    "mode": "PRIVATE_ROOM",
    "allowedGodIds": []
  }
}
```

Fact 전체에는 `schemaVersion`, `allowedScopes`, `defaultValue`, `adminSummary`, 연속된 `levels`, `keywords`도 필요하다. 실제 번역 문구를 등록해야 한다. 공개 가능한 상위 단계를 쓰려면 그 이전 단계도 허용되어야 한다. level의 `disclosure`를 생략한 기존 Fact는 공공 지식과 다른 참가 신의 기존 지식 단계에 따른 보수적 제한도 적용된다.

- PRIVATE_ROOM: 개인별 공개 정책을 통과한 비공개 방 전체 청중.
- PUBLIC: 공개·비공개 방 모두 허용한다. 공개방에서는 온라인 플레이어 전원이 청중이며 각자의 공개 정책도 통과해야 한다. 대화 참가자만 검사하는 것이 아니다.
- OWNER_ONLY: 플레이어 1명·신 1명의 비공개 방. 특정 소유자 UUID를 지정하는 필드는 아니다. 한 명에게만 말하려면 그 사람과의 별도 방을 사용한다.
- NEVER: 방 프롬프트 공개 금지.
- `allowedGodIds`: 화자 외에 함께 듣는 신을 제한한다. 플레이어 자격 조건을 대신하지 않는다.

해당 신에게 이 Fact를 부여할 때 다음 절의 `disclosurePolicy`가 조건형 정책을 참조해야 한다. level에 PRIVATE_ROOM만 넣고 화자 지식에 FULL 정책을 부여하면 개인 자격 문턱이 생기지 않는다.

### 함께 들은 친구도 알게 되는 경로

AI가 허용된 `story_disclose` 별칭을 선택하면 서버가 정식 canonical 문구를 발행한다. 조회만으로 지식을 올리지 않으며, 실제 원문·근거·방 revision·수신 기록을 확인한 뒤 **그 문구를 완전히 받은 플레이어와 실제 청취 신**에게 해당 Knowledge 단계를 저장한다.

일반 AI의 자유로운 바꿔 말하기, Cover Story, 정적 로어 언급은 자동으로 Story 지식 단계를 올리지 않는다. 서버의 실제 전송 증거를 사용하는 것이며 사람의 독해나 화면 ACK를 측정한 것은 아니다. TEST 방과 RUMOR_TEST에서는 정식 지식 습득을 하지 않는다.

## 3. NPC의 새 사실 습득

정식으로 배울 사실은 `story_facts`에 미리 정의한다. 사건의 outcome `effects`에 넣는 조각:

```json
{
  "effectId": "example:teacher_learns_secret",
  "type": "GRANT_KNOWLEDGE",
  "holder": "ACTOR",
  "actor": "example:teacher",
  "fact": "example:secret_record",
  "level": 2,
  "disclosurePolicy": "example:secret_access"
}
```

`example:teacher`는 등록된 Story Actor ID다. 신이라면 Actor 정의의 `godId`를 실제 게임 God ID와 연결한다. 이 조각은 독립 파일이 아니라 등록된 Story Event의 outcome에 들어간다. Actor/Fact/공개 정책의 참조가 모두 존재해야 한다. 전역 사실의 참/거짓을 바꾸는 `SET_FACT`와 신이 그 사실을 아는 `GRANT_KNOWLEDGE`는 별개 효과다.

이후 대화에서 그 신이 습득한 사실 중 현재 청중에게 허용되는 단계만 문맥에 제공한다. 정식 문구를 실제로 듣고 배우는 경로도 같은 신별 Knowledge를 사용한다.

자유 발언의 청취 기억은 "그 플레이어가 그렇게 말했다"는 출처를 유지한다. 플레이어의 거짓말이나 NPC의 추측을 확인된 세계 사실로 저장하는 기능은 아니다. 프로필에 `learnedFacts` 같은 임의 필드를 넣을 필요도 없다. `loreKnowledge`는 계속 정적으로 선언한 지식 범위다.

## 4. 봉인·인정·MAIN 조건

### 자동 조우 — 서버 단계와 개인 인정 조합

기존 God Definition의 `appearance_conditions` 조각:

```json
{
  "type": "mythictrpg:all",
  "conditions": [
    {
      "type": "mythictrpg:story_fact",
      "fact": "example:seal_stage_2_reached",
      "scope": "SERVER",
      "expected": true
    },
    {
      "type": "mythictrpg:story_fact",
      "fact": "example:recognized_player",
      "scope": "TRIGGER_PLAYER",
      "expected": true
    }
  ]
}
```

등록한 Fact의 `allowedScopes`는 각각 SERVER, PLAYER로 정하고 권위 사건에서 상태를 변경한다. **조건의 개인 scope는 PLAYER가 아니라 TRIGGER_PLAYER**다. 봉인 진행 단계는 boolean Fact 여러 개나 사건 outcome으로 표현할 수 있다. Fact의 `levels`는 알고 있는 정보의 상세 수준이지 봉인 진행 숫자가 아니다.

이 조건은 자동 SPONTANEOUS 조우 경로에 적용된다. 명시적 EXPLICIT 호출은 등장 조건을 우회하므로 모든 대화 접근을 차단하는 권한으로 사용하면 안 된다. 개인 인정 조건을 `unlock_conditions`에만 넣으면 신 해금은 월드 전역에 적용된다. 실제 조우 발생에는 기존 신호·선택·cooldown 등의 조건도 맞아야 한다.

### 최초 한 명만 MAIN 자격 획득

**새 퀘스트의 게임 FTB 바인딩**에 넣는 예시 조각:

```json
{
  "narrativeRole": "MAIN_ENTRY",
  "participation": {
    "type": "SOLO",
    "objectives": [
      {
        "kind": "ITEM_SUBMISSION",
        "subject": "minecraft:wheat",
        "count": 1
      }
    ]
  }
}
```

밀 1개는 형식 설명용 예시다. 이 조각 외에 `schemaVersion`, `questId`, `ftbQuestId`, `assignmentQuestId`, `progressTrackId`, `progressOnClear` 등 바인딩 필드와 **MythicTRPG 소유 `rewards` 설정**이 필요하다. God ID는 바인딩 루트 필드가 아니며, 별도 신 프로필·퀘스트 목록과 실제 수주 제공 신의 연결을 확인한다. SOLO 수주 동의 후 확정 run이 생기면 다른 사람의 동일 입문 수주를 막는다. 후속 퀘스트의 `narrativeRole`은 MAIN으로 작성하면 해당 신의 주목 자격을 검사한다.

참여 설정이 없는 기존 MAIN_ENTRY는 전역 완료 전 다른 수주자도 자격을 얻는다. GROUP/COMPETITIVE/RANKING 입문은 동의한 전원이 자격을 얻으며, 경쟁의 최종 승자만 MAIN 자격을 받는 방식은 아니다. 이미 복수 자격이 저장된 월드에서 JSON을 SOLO로 바꾼다고 기존 자격이 자동 제거되지는 않는다.

### 현재 퀘스트 수주에 직접 연결할 수 없는 조건

God 조우에 사용하는 Story Condition Tree를 퀘스트 `acceptanceConditions`에 그대로 넣는 기능은 없다. 현행 AI 후보 조건 소비자는 `world_quest_progress_range`만 사용하며, 게임 바인딩·권위 수주 검증에도 별도 Story 전제조건 필드가 없다. **AI에게 후보를 보여 주지 않는 것만으로 실행 권한을 막았다고 볼 수 없다.**

## 5. 구현 담당에게 남길 확인·개발 항목

1. 봉인/개인 인정/기존 지식 같은 공통 Condition Tree를 퀘스트 후보 필터와 실제 수주 시 재검증에 연결할 포트. JSON 필드·범위·재로드/동시 수주 검증을 함께 정해야 한다.
2. 개인 수주·중간 진행·완료를 PLAYER Story Fact/Knowledge에 동기화할 규칙. 현재 QUEST_COMPLETED는 전역 완료자 또는 다인 정산의 대표 1명 신호라, 단체 완료자의 개인 자격 전원이 자동 기록되지 않는다.
3. 기존 MAIN 자격을 자동 조우와 공개 조건에서 읽을 Condition Type. 또는 별도의 개인 Story fact와 자격 저장소를 일관되게 갱신하는 연결. 현재 focus 자체를 읽는 공통 조건 노드는 없다.
4. 중요한 자유 대사로 공개한 내용을 정식 Fact 습득으로 인정해야 한다면 canonical/alias 전달 방식과 집필 방식의 연결을 명시. 현재 모든 자유 발언을 자동으로 정사 지식으로 승격하지 않는다.
5. 운영 배포와 실제 다인 검증. 현재 서버는 게임1.0.28 / AI0.1.19 / 콘텐츠0.1.2, 개발은 1.0.29 / 0.1.34 / 0.1.3이다. 최신 활동 경험의 AI 연결은 아직 운영 적용이 아니다. 관찰 원장 OFF_NO_CONFIG, 새 회상 설정 경로 불일치도 별도 확인 대상이다.

이 항목 때문에 집필을 멈출 필요는 없다. 원고에는 "누가 아는가", "누구에게 지금 공개 가능한가", "함께 공개하면 누가 새로 아는가", "서버 공용 단계", "개인 인정", "MAIN 소유자"를 나누어 적고, 미연결 조건은 구현 요청으로 표시한다. 임의의 새 JSON 필드를 실제 지원 형식처럼 작성하지 않는다.

## 6. 기존 가이드와 코드 근거

- [Story 작성·지식·공개 가이드](../mythictrpg-main/docs/STORY_EVENT_ENGINE_GUIDE.md)
- [Story 방 공개·실제 전달·지식 이전](../mythictrpg-main/docs/STORY_AI_PRESENTATION_GUIDE.md)
- [고정 퀘스트와 MAIN 설정](../mythictrpg-main/docs/FTB_QUESTS_INTEGRATION_GUIDE.md)
- [참여 유형](../mythictrpg-main/docs/QUEST_PARTICIPATION_GUIDE.md)
- [정적 로어의 공개 제한](../mythai-ai-content-registry/AI_CONTENT_REGISTRY_CONTENT_AUTHORING_GUIDE.md)
- [전체 청중 Story 검사](../mythictrpg-main/src/main/java/com/sande/mythictrpg/story/presentation/StoryRoomConversationService.java:175)
- [정식 실제 수신 후 Knowledge 저장](../mythictrpg-main/src/main/java/com/sande/mythictrpg/story/presentation/StoryRoomConversationService.java:429)
- [청중별 조건 평가](../mythictrpg-main/src/main/java/com/sande/mythictrpg/story/runtime/StoryDisclosureService.java:63)
- [Story 조건의 실제 필드](../mythictrpg-main/src/main/java/com/sande/mythictrpg/story/condition/StoryConditionTypes.java:38)
- [Story JSON 실제 파서](../mythictrpg-main/src/main/java/com/sande/mythictrpg/story/definition/StoryDefinitionManager.java:175)
- [정적 로어의 제한된 공개 규칙](../mythai-ai-content-registry/src/main/java/com/sande/mythaiaicontent/content/ContentDisclosure.java:18)
- [실제 MAIN 수주 검사](../mythictrpg-main/src/main/java/com/sande/mythictrpg/quest/QuestRuntimeService.java:104)
- [참여 설정 실제 파서](../mythictrpg-main/src/main/java/com/sande/mythictrpg/quest/QuestParticipationPolicy.java:47)

이번 확인에서는 코드를 수정하거나 서버/LLM을 실행하지 않았다. 작성 예제는 현행 파서의 필드와 대조한 조각이며, 등록된 전체 데이터팩을 새로 로드해 검증한 것은 아니다.
