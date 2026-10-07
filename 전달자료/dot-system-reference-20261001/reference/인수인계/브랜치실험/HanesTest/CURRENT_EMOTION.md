# 현재 감정의 세션 연속성

2026-09-29 · `HanesTest` 실험 브랜치. 기획상의 영구 감정 수치나 게임 사실을 추가하지 않는다.

## 실행 경로

같은 대사 생성 응답의 선택 필드 `currentEmotion`을 `LocalOllamaClient`가 읽고 `StructuredAiResult`로 전달한다. 길이 120자 이내의 한 줄 해석만 허용한다. 누락·잘못된 타입·제어 문자·초과 길이는 빈 평가로 처리하며, 기존 대사/제안 응답과 3인자 생성자 호환은 유지한다. 이 필드 때문에 추가 모델 호출이나 점수 계산을 하지 않는다.

`MythAiRoomConversationEngine`은 정상 응답을 임시 보관한다. 게임이 `dialogueObserved`로 보내는 실제 전송 결과와 `delivered` 완료가 모두 정확히 일치해야 `RoomEmotionState`에 반영한다. 방/revision/턴/화자/참가 신/현재 플레이어/전체 청중과 대사 원문을 검사한다. 일부 청중 누락, HUD 일부 전달, 일부 대사만 전달, 생성만 완료, 오래된 턴, 중복 완료 또는 무응답은 새 감정을 확정하지 않는다. 영구 기록 OFF도 실제 전송 callback을 사용하므로 세션 연속성은 지원한다.

다음 턴에는 기존 권한 필터를 통과한 히스토리에 원본 message ID와 대사 원문이 모두 남아 있는 상태만 `[NPC_SESSION_EMOTION]`으로 넣는다. 출처가 철회·변조·잘리면 파생 상태를 제거한다. 출처는 기존 publication evidence 의존성에 포함되어 비동기 생성 및 공개 직전에도 기존 경로로 재검증된다. 방/revision/화자/현재 플레이어/참가 신/청중/공개 여부가 다르면 재사용하지 않는다. 방 무효화와 서버 종료 시 제거하며 디스크에는 쓰지 않는다. 상태 수는 최대 1,024개이다.

Primary의 `RoomPersonaPrompt`와 Secondary의 `RoomReactionPrompt`는 자기 상태만 받는다. Secondary가 다른 신에게 느낀 반응을 곧바로 현재 플레이어에 대한 감정이라고 해석하지 않도록 범위를 명시했다. 자기 감정의 연속성과 재평가는 허용하되 사과·반복·요청 문장에 특정 반응이나 수치 감쇠를 강제하지 않는다. 힌트는 비권위 대화 해석이지 다른 사람의 내면, 계획, 행동 실행, 영구 성격 또는 게임 수치가 아니다.

게임에서 오는 `E_UNASSESSED`는 계속 ‘게임이 감정을 평가하지 않음’이다. 이 값이 차분함/무관심/중립을 뜻하거나 별도의 NPC 세션 해석을 지우지는 않는다. 이번 구현은 `GodState`, 게임의 affinity 저장소, 힘/후원 계약을 변경하지 않는다. 실제 전달된 새 응답이 빈 힌트를 반환하면 과거 힌트를 계속 현재 평가로 단정하지 않도록 해당 상태를 제거한다.

## 검증 범위

- `roomEmotionStateTest`: 실제 전송 필요, 기록 OFF, 부분 전송, stale/revision, 방 A/B, 플레이어/화자/청중 격리, 철회·변조된 출처, Primary/Secondary의 실제 프롬프트 소비, 침묵, 실제 JSON parser/schema의 선택 필드 호환.
- `RoomFullPathGameTests.productionEnginePublishesAndRecallsHeardSpeech`: 실제 게임 → AI 엔진 → 가짜 LLM transport → 실제 공개/기억 경로. 같은 플레이어의 다음 턴에서 각 신의 자기 감정만 도달하고, 다른 플레이어의 다음 턴에는 이전 플레이어 감정이 전달되지 않는지 검사한다. 기존 affinity tier/content guidance 및 비공개 로어 차단 검증을 유지한다.
- 함께 회귀할 기존 작업: `roomPersonaPromptTest`, `roomReactionPromptTest`, `roomConversationEngineTest`.

루트 순차 검증 결과: AI `build --offline` 전체 check 성공, `roomEmotionStateTest` 53 assertions, 기존 Persona 156 / Reaction 37 / RoomEngine 96 assertions 통과. 독립 엔진 패키징 검사도 통과했다. 확장한 실제 3모듈 full-path GameTest의 첫 실행에서는 두 번째 primary가 prompt 예산에 걸렸다. 첫 primary scene이 이미 11,622/12,000자였고 읽기 전용에도 불필요한 gameplay capability 설명이 들어가 있었으며, 선택적 청취 기억이 필수 gameContext에 합쳐져 있었다. 모델/기본 qwen 설정 오류가 아니다.

이 후속 수정은 read-only의 실행 불가 capability 설명을 생략하되 room/Story 제어를 보존한다. 청취 기억은 별도 불변 후보로 전달해 허가된 whole record를 3→2→1개로 줄인다. 선택적 Minecraft 참조·로어·청취 기억을 먼저 줄이고 필수 게임 권한/청중/페르소나/현재 감정/현재 입력 및 마지막 실제 NPC 발화부터 이어지는 대화 tail은 자르지 않는다. 미포함은 `OMITTED_CONTEXT_BUDGET` 내부 메타데이터이며 기억이나 사건이 존재하지 않는다는 뜻이 아니다. 모델이 예산 설명을 대사로 말하도록 요구하지 않는다. 출처 재검증은 원래 전체 집합을 보수적으로 유지한다. 필수 문맥 자체가 넘으면 `PROMPT_BUDGET_REQUIRED_CONTEXT`와 내용 없는 길이 진단으로 fail-closed한다. 후속 소스/회귀 검사는 루트 재빌드·full-path 재실행 대기다.

서버 배포·새 조합의 실제 운영 서버 부팅·실제 LLM 감정 표현 품질·인게임 체감은 확인하지 않았다. 오프라인/패키징 결과를 운영 검증 완료로 보지 않는다.

후속 검증: 루트가 예산 수정본 AI 전체 build를 통과시켰고, `build/room-budget-story-wire-20260929`의 격리 GameTest 2/2도 통과했다. 확장한 방 시험은 Primary/Secondary 6회 생성과 실제 다음 턴의 각 신 감정/다른 플레이어 격리를 확인했다. 모델 transport는 fixture이며 실제 LLM의 감정 표현 품질 수용으로 승격하지 않는다.
