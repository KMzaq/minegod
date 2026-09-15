# 선택적 AI 사건 연출 보조 시스템

## 책임 경계

`StoryEventService`가 먼저 사건 결과와 효과를 확정한다. AI는 그 결과를 바꾸지 않고
`PresentationOpportunity`를 표현하거나, 현재 대화에서 서버가 허용한 Story Hook을 제안한다.
`mythai-ai-response`가 없거나 Ollama 요청이 실패해도 작성된 `fallbackTranslationKeys`가 전달된다.

AI 모듈에 공개되는 `StoryAiPresentationContracts.Snapshot`에는 다음만 들어간다.

- 화자 God ID와 플레이어 ID
- 공개 정책 검사를 통과한 자연어 의미(최대 8개)
- 비밀이 아닌 연기 지시
- `hook_1` 형태의 일회성 별칭(최대 3개)

Fact/Event/Hook ID, outcome 후보, weight, roll, 다른 화자의 Knowledge는 Snapshot에 포함되지 않는다.

## Presentation JSON

```json
{
  "schemaVersion": 1,
  "kind": "DIRECT_DIALOGUE",
  "generationPolicy": "AI_PARAPHRASE",
  "canonicalDeliveryPolicy": "FACT_BEARING",
  "speakerActorId": "mythictrpg:lubras",
  "factRequests": [
    {"factId": "mythictrpg:wanderer_occupies_lubras_base", "maximumLevel": 2, "required": true}
  ],
  "fallbackTranslationKeys": ["story.presentation.mythictrpg.lubras_full_story"],
  "performanceTags": ["mythictrpg:amused", "mythictrpg:candid"],
  "offeredHookIds": [],
  "offlineDeliveryPolicy": "ON_NEXT_LOGIN",
  "maximumAiTurns": 3
}
```

`CRITICAL`은 `SCRIPTED_ONLY`만 허용한다. `FACT_BEARING`에는 fact 요청이 필요하며,
`AI_FLAVOR`는 fact 요청을 가질 수 없다. `required=true`인 사실을 화자가 모르거나 공개 정책이
막으면 `BLOCKED_DISCLOSURE`가 되고 AI에 빈 문맥을 보내지 않는다.

## 전달과 실패 처리

AI 응답은 화자 한 명, 1~4개 대사, 선택적인 Hook 제안 하나로 제한된다. 응답이 없거나 잘못된
화자·빈 대사·제한 초과·네트워크 실패가 발생하면 작성 대사로 전환한다. `FACT_BEARING`의 허용
사실은 AI 대사와 별개로 시스템 문구로 남긴 뒤 플레이어 Knowledge를 반영한다.

현재 구현의 `JOURNAL_IMMEDIATELY`는 별도 퀘스트 저널 UI가 아직 없으므로, 플레이어가 온라인일
때는 canonical 시스템 문구로 전달하고 오프라인이면 pending 상태를 유지한다. 영구 저널 UI가
추가되면 이 정책의 저장 대상을 교체해야 한다.

## Story Hook 안전성

AI는 실제 Hook ID를 받지 않는다. 서버는 활성 대화에 한해 별칭과 난수 token을 만들고 다음을
token에 묶는다.

- 플레이어
- 대화 세션
- 화자 Actor/God
- 대상 사건 scope와 현재 instance/revision
- 60초 TTL

AI가 별칭을 제안해도 기존 `AiActionGateway` 확인 UI가 먼저 열린다. 확인 시 token 소유권,
세션, 화자, TTL, 대상 사건 revision, Hook 조건·횟수·쿨다운을 다시 검사하고 통과한 등록 Hook만
`story_hook_accepted` 신호로 실행한다.

## 검증 범위

- MythicTRPG 전체 GameTest: 186/186 통과
- `mythictrpg-main` 컴파일/JAR 빌드 통과
- `mythai-ai-response` 패치 소스 생성 및 컴파일 통과

이는 서버 권한 경계와 기존 사건 회귀가 통과했다는 뜻이다. 실제 Gemma/Ollama가 매번 자연스럽고
좋은 문장을 생성한다는 보장은 아니며, 모델이 실행 중인 통합 환경에서 별도의 대사 품질 스모크
테스트가 필요하다.
