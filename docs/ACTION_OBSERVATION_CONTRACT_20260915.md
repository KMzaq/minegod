# 행동 사건·주시·관찰 증명 공통 계약 — 0단계

2026-09-15 / 기술 계약 정리. **아래 게임 DTO·원장·주시 기능은 아직 구현하지 않았다.**

[최신 로드맵](ACTION_OBSERVATION_MEMORY_ROADMAP_20260915.md)의 0단계 산출물이다. 1단계 AI 회상은 기존 ConversationMemoryContext와 v1 journal을 사용한다. 이 문서가 2~7단계 구현·운영 정책 승인을 대신하지 않는다.

## 1. 현재 코드와 연결점

| 현재 생산자/소비자 | 유지하는 계약 | 다음 단계의 추가 연결 |
|---|---|---|
| GameplayObservationAdapters → GameplayIngressService | 기존 type/player/gameTime/payload, 같은 키 알림 묶기 | 게임이 인정한 개별 사건 지점에서 상세 원장으로 별도 분기 |
| 기존 관찰 sink → 조우/퀘스트/스토리 | 기존 묶음 알림 및 실행 횟수 | 원장 재생은 이 sink를 재호출하지 않음 |
| GodAttentionState/Record | 메인 퀘스트 기반 주목 대상 | 주시 자격의 입력일 뿐 실제 목격으로 변환하지 않음 |
| AiConversationRuntimeService.memoryContext | world/interaction/generation/player/god/audience/readOnly | 변경 없음. AI가 식별자·참가자·공개 권한을 발급하지 않음 |
| AI 개인 journal | PLAYER_STATEMENT/NPC_UTTERANCE 등 출처별 원문 | 게임 원장 전체 복사 금지. 미래 승인된 관찰 투영만 별도 입력 |
| RumorLedger/RumorSavedData | 목격 근거·주장·실제 수신과 대상별 차단 | 전서구 관찰과 전달 연결은 6단계 |

기존 Snapshot/Proposal/GameplayObservation 생성자, public Provider, FTB 팀·개인 수주·보상 소유권은 변경하지 않는다. 이름이 같아도 AI 레거시 ai.memory와 게임 memorycontract 패키지를 혼용하지 않는다.

## 2. 논리 레코드 v1

아래는 다음 단계의 **추가 계약**이다. Java 이름/저장 파일명은 생산자 구현 때 확정하되 의미와 권한을 유지한다. 누락 필드를 AI가 채우지 않는다. 미지원 버전은 capability 불가용으로 처리하고 원문은 보존한다.

### 행동 사건

| 필드 | 의미/검증 |
|---|---|
| schemaVersion | 최초 1, 지원하지 않는 버전은 실행/투영하지 않음 |
| worldId, eventId | 게임이 발급하는 월드 ID와 영구 발생 ID. 모델 입력 아님 |
| sourceRef, sourceRevision, dedupKey | 원천 기록/어댑터와 revision, 동일 **발생**의 중복 키. 같은 행동 종류/같은 tick이라는 이유만으로 별개 사건을 합치지 않음 |
| sequence | 원장 내 단조 순서. 재시작 후 유지. tick 초기화/차원 이동과 별개 |
| actorId, subjectIds | 행동자와 영향을 받은 실제 대상. 플레이어/엔티티 종류 구분 |
| occurredAtUtc, gameTick, dimensionId, gameDayTime | 실제 발생 시점과 게임 시간. nullable인 값은 이유 기록, 임의 추정 금지 |
| type, outcome, payload | 등록된 타입별 제한 payload. 시도/취소/실패/피해/처치/완료를 구분 |
| visibilityRef | 게임이 정의한 민감 필드/공개 조건 참조. 원장의 존재만으로 신에게 공개하지 않음 |

이동 샘플은 이동 횟수/거리의 완전 기록이 아니다. 동일 적에게 여러 공격은 각각 발생 키가 필요하다. 실제 피해 적용 전 취소 가능한 알림을 성공 결과로 기록하지 않는다. 타입별 완료 판정 지점은 2·5단계 지원표와 테스트로 확정한다.

### 주시 상태

| 필드 | 의미/검증 |
|---|---|
| worldId, godId, targetPlayerId, watchId | 기존 게임 God ID 사용, 신/대상별 독립 상태 |
| eligibilityRef | 조건·관계 등 게임 원본의 자격 근거와 revision |
| policyId, policyVersion | 신별 관측 범위/가림/비밀 정책. 미확정이면 운영 비활성 |
| state | ACTIVE / PAUSED / ENDED. 자격 있음과 ACTIVE는 별개 |
| effectiveFromSequence, effectiveUntilSequence | 게임이 확정한 반개구간 [시작, 종료). 같은 tick 내부 경계도 순서로 판단 |
| causeRef, revision | 시작·중지·종료 이유와 변경 버전. 모델의 감정/호감도 추정으로 생성하지 않음 |

주시 시작 이전의 과거 원장을 열지 않는다. 주시 종료는 이미 본 기억의 일괄 삭제가 아니다. 반면 관측 오류로 근거가 취소되면 파생 자료를 무효화한다. 신 수×플레이어 수×전체 원장을 매 tick 순회하지 않고 대상별 활성 구독 인덱스를 사용한다.

### 관찰 증명

| 필드 | 의미/검증 |
|---|---|
| observationId, worldId, eventId, sourceRevision | 실제 원본 사건과 revision. 원본 없는 증명만 커밋하지 않음 |
| observerGodId 또는 observerRef | 직접 주시 신 또는 실제 전서구 등의 게임 관찰자. 경로를 혼합하지 않음 |
| acquisitionKind | DIRECT_WATCH / PHYSICAL_WITNESS 등 등록된 경로. 수신 소문은 직접 목격으로 재분류하지 않음 |
| watchRef / witnessRef, policyVersion, observedAtSequence | 당시 관측권한·물리 목격과 순서 증명 |
| visibleProjection | 그 관찰자가 실제로 알 수 있었던 필드만 포함. 의도·마음·숨은 행동을 덧붙이지 않음 |
| disclosureRef, revision, state | 현재 청중에게 공개 가능한지 재검증하는 참조와 VALID/REVOKED 상태 |

직접 관측, 플레이어가 말한 주장, 전언, 신의 평가를 서로 다른 출처로 보관한다. 누가 누구의 이야기를 했는지도 유지한다. 복수 플레이어 사건은 필드별 공개 조건을 통과한 부분만 투영한다.

## 3. 기억용 읽기 capability

게임이 발급한 현재 대화 범위로만 `ExperienceView`를 요청한다. 요청에 God ID 등이 있어도 허용 근거는 서버 세션이다. 반환은 허용된 관찰 참조/원본 revision/출처/시각/제한된 설명/공개 조건 토큰을 포함한다.

- `available=false`와 ‘허용된 결과 0건’을 구분한다. 허용 밖 사건의 개수·존재·내용을 프롬프트나 플레이어 화면에 노출하지 않는다.
- 읽은 snapshot과 근거별 revision을 보관하고 대사·Proposal 적용 전 현재 세션/turn/generation, 관찰 유효성, 공개 조건을 재검사한다.
- 다른 플레이어의 정상 쓰기로 모든 응답을 무효화하지 않는다. 같은 범위의 관련 정정/삭제/관측 취소는 늦은 응답 적용을 막는다.
- 기록/증명 저장은 제한 큐와 단일 작성자로 비동기 처리한다. PENDING은 저장 성공이 아니다. 실패·용량·관측 공백은 상태/진단에 남긴다.
- 재시도는 sourceRef/eventId/revision으로 멱등 처리한다. receipt cursor는 내구성 확보 후 전진한다. 재생이 퀘스트 완료나 보상을 다시 실행하지 않는다.
- AI 미설치·기억 불가용·신규 capability 미지원이어도 기존 게임 진행은 유지한다. AI는 관측 증명 발급 API를 갖지 않는다.

## 4. 전서구·평판으로 확장할 때 유지할 경계

전서구 사냥은 게임이 지정한 **해당 대상 플레이어의 소문**을 차단한다. 다른 플레이어의 소문까지 전역 차단하지 않는다. 직접 주시와 소문 전달은 별개 경로다. 다른 플레이어가 섞인 자료나 신 간 임의 재전파로 대상별 차단을 우회하지 못한다.

소문을 여러 번 읽거나 NPC가 재진술해도 독립 증거/별도 업적으로 중복 산정하지 않는다. 대표 수식어·호감도 효과·퀘스트 제한/보상은 별도 승인된 게임 정책에서만 적용한다. 해명·반증·취소·회복은 효과 적용 전에 함께 설계한다.

## 5. 미확정 정책과 진행 가능한 범위

- 주시 범위/시작·유지·종료 임계값, 관계별 매핑: 사용자 선택 또는 작성된 게임 규칙 필요.
- 다른 신과의 대화·비밀 공간: 명시적 관측 허용 근거 없이 공개하지 않는 권한 경계를 유지한다. 구체 콘텐츠 규칙은 미확정.
- 상세 기록의 샘플링 간격·피해/시도 범위·원문 보관 기간·용량: 2단계 최소 기반과 5단계 범위/부하 측정에 맞춰 제안·승인한다.
- 상대 시간: 현실 한국 날짜와 게임 날짜 중 미응답. 이번 회상 코드는 UNSPECIFIED(단정 금지)와 REAL_KST를 지원하지만 서버 설정은 변경하지 않는다. 게임 날짜 해석은 필요한 게임 시간 snapshot 계약이 마련될 때 추가한다.
- 전서구 개체 귀속/재생성·기존 수신/미전달 처리·평판 강도: 6~7단계 전에 별도 선택한다.

미확정 정책은 OFF로 두고 명시적인 가짜 사건/시험 정책으로 기반을 검증할 수 있다. 기술 계약 초안 작성이 신별 수치/정밀도/운영 활성화의 승인은 아니다.

## 6. 단계별 필수 검증

2단계: 발생 수·순서·월드 분리·중복·취소/실패·재시작/손상/용량·기존 sink 중복 실행 없음.

3단계: A 주시/B 미주시, 시작 전/직후, 종료/중단/재시작, 비밀·현재 청중, 복합 대상, 근거 취소.

4단계: 허용된 작은 사건 한 종류만 원장→관찰→기억→대화까지 연결. 완료와 보상 지급 실패를 구분. 실제 LLM/인게임 검증은 별도 승인.

이번 0~1단계는 이 게임 경로를 구현/실행하지 않는다. 기술 구현·오프라인 결과는 [작업 기록](RECALL_STAGE01_20260915.md)에 구분해서 남긴다.
