# MythicTRPG + Local AI RPG 개발 진행도 및 로드맵

작성 기준일: 2026-08-25  
MythicTRPG 기준 커밋: `a6ffb9051d6a57ae87981a25c08b91f0d111832f`  
개발 브랜치: `integration/ai`

## 1. 최종 목표

Minecraft RPG 서버에서 플레이어가 신 NPC와 자연스럽게 다회차 대화를 나누고, 신이 게임 상태와 관계·감정·기억·세계 지식을 바탕으로 반응하게 한다.

AI는 대사와 행동을 제안하며 실제 게임 상태 변경은 MythicTRPG가 검증하고 실행한다.

```text
MythicTRPG
  게임 상태·참가자·관계·이벤트·허용 범위 제공
        ↓
AI 응답 모드
  문맥 분석·검색·LLM 호출·대사/Proposal 생성
        ↓
MythicTRPG
  결과 검증·플레이어 확인·실제 실행·HUD 출력
```

## 2. 모듈별 책임

### MythicTRPG

- 게임 상태의 authoritative source
- 플레이어, 신, 관계 수치, unlock, appearance, encounter, knowledge 관리
- Minecraft 행동 감지와 InteractionPlan 생성
- 퀘스트·보상·아이템·가호의 허용 범위와 실제 실행
- AI Proposal 검증 및 실행 결과 반환
- Dialogue HUD와 Network

### AI 응답 모드 (`mythai-ai-response`)

- ConversationSession과 대화 문맥
- 1단계 입력 분석
- 콘텐츠 검색 요청과 Context 구성
- Ollama 비동기 호출
- NPC 대사 및 Proposal 생성
- 대화 로그와 AI 기억
- 게임 상태를 직접 변경하지 않음

### AI 콘텐츠 레지스트리 (`mythai-ai-content-registry`)

- 신 기본 프로필과 성격·가치관·말투
- 세계 지식과 단계별 공개 내용
- 말투·상황·관계 예시 데이터
- ID 기반 정적 콘텐츠 조회
- 플레이어 관계 수치나 실제 게임 상태를 저장하지 않음

## 3. 현재 완료된 기능

### MythicTRPG 기반

- [x] Minecraft 1.21.1 + NeoForge 21.1.248 + Java 21 프로젝트
- [x] Datapack JSON 기반 God Definition
- [x] `ResourceLocation` 기반 God ID
- [x] World SharedData와 플레이어별 영구 데이터
- [x] 플레이어별 God affinity 저장 구조
- [x] encounter와 identification 분리
- [x] God unlock, appearance, identity/knowledge 판정
- [x] Condition Engine과 dependency index
- [x] 아이템 획득 이력 일부 감지
- [x] custom gameplay counter
- [x] Minecraft Statistics sampling과 threshold observation
- [x] 블록 파괴, 번식, 처치, 사망, 아이템 최초 획득, 작물 수확, 동물 먹이 observation
- [x] 플레이어 ACTIVE/IDLE runtime 판정
- [x] Datapack 기반 Gameplay Promotion
- [x] Datapack 기반 Interaction Rule
- [x] Interaction candidate, scoring, selection과 InteractionPlan
- [x] Interaction validation, encounter/cooldown commit
- [x] 중앙 Dialogue HUD와 Network
- [x] Spontaneous resolver/preparer 확장 경계
- [x] 비동기 in-flight 제한, timeout, stale response 차단
- [x] commit 없는 관리자 dry-run
- [x] Demeter 성숙한 밀 수확 production binding
- [x] 관리자 진단 및 대화 테스트 명령
- [x] 기준 `clean build` 성공
- [x] 필수 GameTest 158/158 통과

### 기존 AI 작업 자산

- [x] Ollama 로컬 모델 호출 경험 및 테스트 자산
- [x] 1단계 상황 분석 + 2단계 응답 생성 구조
- [x] 규칙 기반 빠른 분류와 LLM 분류의 하이브리드 설계
- [x] 플레이어 말투·관계·상황을 반응 판단에 사용하는 설계
- [x] 대화 반복과 최근 문맥을 반영하는 설계
- [x] 신 프로필·세계 지식·예시 콘텐츠 레지스트리
- [x] 단계형 지식 공개 구조
- [x] 9단계 관계 태그 구조
- [x] 플레이어별 대화 로그 구조

> 위 AI 자산은 기존 테스트 프로젝트에 존재한다. 새 MythicTRPG upstream과의 정식 계약 이식 및 재검증은 아직 완료되지 않았다.

## 4. 현재 부분 구현 상태

- [~] production God Definition: Demeter, Aphrodite, Lubras 3명만 존재
- [~] spontaneous interaction: 서버 파이프라인은 있으나 실제 AI provider가 미등록
- [~] affinity: 저장 구조는 있으나 실제 증감 규칙과 Proposal 적용기는 없음
- [~] item history: pickup/crafting/smelting은 감지하지만 모든 inventory 유입 경로를 다루지 않음
- [~] 다중 신: Primary 1명과 Secondary 최대 2명 구조는 있으나 자동 선택 없음
- [~] gameplay promotion: 범용 엔진은 있으나 production 콘텐츠는 Demeter 밀 수확 1개
- [~] AI 콘텐츠: 테스트 콘텐츠는 있으나 production God ID와 정합성 검증 필요
- [~] 자유 대화: 기존 테스트 통합에는 있었지만 새 upstream 계약으로 이식되지 않음

## 5. 개발 순서

각 단계는 이전 단계의 완료 조건을 만족한 뒤 진행한다. 기능 구현 중에는 기존 158개 GameTest를 유지한다.

### 단계 1 — 새 MythicTRPG ↔ AI 응답 모드 기본 연결

- [x] 기존 reflection/live-object 브리지를 사용하지 않는 정식 provider 구현
- [x] `InteractionContentPreparerResolverRouter`에 production provider configure-once 등록
- [x] MythicTRPG의 bounded `ContentPreparationRequest` 계약 사용
- [x] 선택된 primary/secondary God, 플레이어, signal, audience 전달
- [x] AI 응답을 `PreparationResult`와 `PreparedInteractionContent`로 변환
- [x] Minecraft server thread를 막지 않는 비동기 Ollama 호출
- [x] timeout, provider unavailable, malformed response 처리
- [x] deterministic fake provider 및 기존 회귀 테스트
- [ ] Ollama dry-run 테스트
- [ ] Demeter 밀 수확 → AI 대사 → HUD 출력 통합 테스트

추가 완료:

- [x] 서버 권위 AI 대화 ON/OFF runtime state
- [x] 우측 AI 대화 패널과 기본 `G` 키 전환
- [x] 현재 primary God 이름 표시
- [x] 미식별 God을 `????`로 마스킹
- [x] 서버 → 클라이언트 상태 동기화와 클라이언트 → 서버 전환 요청

완료 조건:

- 기존 158개 GameTest 통과
- AI가 꺼져 있어도 서버가 정상 동작
- 성숙한 밀 수확 시 데메테르가 선택되고 AI 대사가 HUD에 출력됨
- AI가 다른 God이나 게임 상태를 임의로 변경할 수 없음

### 단계 2 — 자유로운 다회차 채팅 연결

- [ ] 플레이어 채팅을 기존 Interaction/participant 흐름으로 연결
- [ ] 명령어로 테스트할 신을 안전하게 선택·호출
- [ ] `ConversationSession` 생성·종료·재접속 정책
- [ ] 세션별 history와 current topic 관리
- [ ] ACTIVE/LISTENER/OUTSIDE participant snapshot 계약
- [ ] 세션별 요청 직렬화와 서로 다른 세션의 병렬 처리
- [ ] 같은 플레이어의 오래된 응답이 새 대화에 출력되지 않도록 turn/request ID 검증
- [ ] 긴 답변을 의미 단위 여러 HUD 문장으로 순차 출력
- [ ] 플레이어별·세션별 대화 로그 기록

완료 조건:

- 같은 신과 이전 대화를 기억하며 여러 차례 자연스럽게 대화 가능
- Session A의 기록·참가자·응답이 Session B에 섞이지 않음
- 종료된 세션의 늦은 AI 응답이 출력되지 않음

### 단계 3 — 콘텐츠 레지스트리 정식 연동

- [ ] production God ID와 콘텐츠 프로필 ID 정합성 검사
- [ ] 신 프로필 조회 계약
- [ ] 단계형 세계 지식 조회 계약
- [ ] 말투·관계·상황 예시 조회 계약
- [ ] 콘텐츠 schema version과 capability negotiation
- [ ] 누락 프로필·누락 지식·잘못된 태그의 fail-safe 처리
- [ ] Datapack reload 또는 명시적 reload 정책
- [ ] Demeter, Aphrodite, Lubras production 프로필 준비

완료 조건:

- 신 프로필을 코드 수정 없이 데이터로 추가 가능
- 신이 알지 못하는 지식은 LLM Context에 포함되지 않음
- 콘텐츠 오류가 서버 전체나 다른 신의 대화를 중단시키지 않음

### 단계 4 — 대화 분석과 자연스러움

- [ ] 빠른 규칙 분류: 인사, 종료, 명확한 키워드 요청
- [ ] 모호한 부탁·비밀·협상·복수 참가 대화의 LLM 1단계 분류
- [ ] 상황 태그 정식 지원
  - [ ] `S_CHAT`
  - [ ] `S_ITEM_REQUEST`
  - [ ] `S_POWER_REQUEST`
  - [ ] `S_HELP_REQUEST`
  - [ ] `S_INFORMATION_REQUEST`
  - [ ] `S_QUEST_INQUIRY`
  - [ ] `S_REWARD_NEGOTIATION`
  - [ ] `S_GIFT_OFFER`
  - [ ] `S_APOLOGY`
  - [ ] `S_CONFLICT`
- [ ] 플레이어 말투, 예의, 공격성, 감정, 의도 분석
- [ ] 관계 단계·힘의 우위·대화 상황을 결합한 반응 판단
- [ ] 반복 문장의 의미 정규화와 반복 의도 해석
- [ ] 최근 발화와 미해결 질문·약속·놀이 상태 반영
- [ ] 과장된 신격 연기와 없는 기능 약속 방지
- [ ] 길이와 문장 분할을 대화 상황에 따라 결정

완료 조건:

- 같은 문장도 관계와 상황에 따라 다른 반응을 보임
- 표현만 다른 의미상 반복을 인지함
- 사과, 무례, 농담, 질문, 놀이가 앞 대화와 자연스럽게 이어짐
- 실행할 수 없는 능력을 이미 실행한 것처럼 말하지 않음

### 단계 5 — 관계와 현재 감정

- [ ] 게임 affinity를 authoritative source로 사용
- [ ] 9단계 관계 태그 매핑 계약
- [ ] trust, respect, caution 저장 위치와 authoritative owner 확정
- [ ] NPC 현재 감정 snapshot과 갱신 정책
- [ ] 장기 관계와 단기 감정 분리
- [ ] `RelationshipChangeProposal` schema
- [ ] 관계 변화 허용 범위, cooldown, 누적 제한 validator
- [ ] 검증 결과와 실제 반영값을 다음 AI turn에 feedback
- [ ] 세션 간 관계/감정 context 격리 테스트

완료 조건:

- AI가 관계 수치를 직접 저장하거나 변경하지 않음
- 친한 플레이어에게 화난 상태 등 관계와 감정의 조합이 가능
- 동일 행동도 관계·감정·힘의 차이에 따라 다른 반응을 생성

### 단계 6 — Memory Engine

- [ ] Recent Memory와 Long-term Important Memory 분리
- [ ] 교체 가능한 `MemoryRepository` 인터페이스
- [ ] 대화와 게임 이벤트에서 memory candidate 생성
- [ ] 중요도·태그·NPC·플레이어·세션 기반 저장
- [ ] 현재 문맥과 관련된 기억만 검색
- [ ] 기억 요약, 병합, 만료, 삭제 정책
- [ ] 플레이어 데이터 삭제 및 운영자 관리 기능
- [ ] Memory가 world authoritative fact를 임의로 만들지 않도록 검증

완료 조건:

- 모든 과거 대화를 프롬프트에 넣지 않고 관련 기억만 사용
- 플레이어별 기억이 다른 플레이어에게 유출되지 않음
- 기억 저장소 구현을 파일/DB 등으로 교체 가능

### 단계 7 — Knowledge Engine

- [ ] 지식 ID와 단계별 내용 조회
- [ ] God별 허용 knowledge ID/level 적용
- [ ] secrecy, 관계, 감정, audience 기반 공개 판단
- [ ] 신뢰하지 않는 LISTENER가 있을 때 비밀 정보 차단
- [ ] 키워드·별칭·태그·계층·가중치 기반 검색
- [ ] 검색 결과 점수와 선택 이유 진단 기능
- [ ] 이후 embedding 검색으로 교체 가능한 Retriever 인터페이스
- [ ] 지식 보유자 공개 여부(`revealKnowledgeHolders`) 적용

완료 조건:

- NPC가 모르는 정보는 프롬프트에 포함되지 않음
- knowledge level 3이면 1~3단계만 제공
- Session A의 지식·청중 정보가 Session B로 유출되지 않음

### 단계 8 — Example/Style Retriever

- [ ] 단일 태그별 기본 말투 규칙과 예시 데이터 사용
- [ ] 상황·관계·감정·대화 문맥 태그 조합
- [ ] 태그 계층별 우선순위와 충돌 규칙
- [ ] weighted tag matching
- [ ] 상위 예시 개수 3~5개 설정
- [ ] 반복·중복 예시 제거
- [ ] 예시가 오히려 답변을 고정하거나 부자연스럽게 만드는지 평가
- [ ] 필요성이 낮은 예시 범주는 제거

완료 조건:

- 모든 태그 조합별 예시를 따로 작성할 필요가 없음
- 관계·상황 규칙을 우선하고 말투 예시를 자연스럽게 조합
- 선택된 예시와 점수를 로그에서 확인 가능

### 단계 9 — 퀘스트 시스템과 AI 제안

- [ ] MythicTRPG Quest Definition 및 Quest Instance 설계
- [ ] 퀘스트 상태: 제안, 수락, 진행, 완료, 실패, 취소
- [ ] 목표 타입과 진행 추적
- [ ] 플레이어별 활성 퀘스트 저장
- [ ] `QuestConstraintSnapshot`
- [ ] `QuestProposal` schema
- [ ] Quest Proposal validator
- [ ] 플레이어 수락/거절 UI 또는 채팅 흐름
- [ ] 검증된 퀘스트만 실제 등록
- [ ] validator feedback을 다음 AI 대화에 전달
- [ ] 세션별 constraint/feedback 격리

완료 조건:

- AI가 퀘스트 내용을 제안할 수 있으나 직접 등록하지 못함
- 존재하지 않는 목표나 허용되지 않는 퀘스트를 서버가 거절
- 퀘스트 진행과 완료는 게임 이벤트가 판정

### 단계 10 — 아이템·공물·보상 교환

- [ ] 아이템 ID, 수량, NBT/component 정책
- [ ] `ItemRequestProposal`, `GiftOfferContext`, `RewardProposal` schema
- [ ] 플레이어가 실제로 가진 아이템 snapshot
- [ ] 공물 제출 시 원자적 제거와 실패 복구
- [ ] 보상 allowlist, power/cost limit, 중복 제한
- [ ] 인벤토리 여유 공간 확인과 안전 지급
- [ ] 교환 확인·취소·timeout
- [ ] 지급/제거 결과 feedback
- [ ] 서버 재시작과 중복 요청에 대한 idempotency

완료 조건:

- AI 대사만으로 아이템이 지급되거나 제거되지 않음
- 검증·확인·실행이 모두 성공했을 때만 거래 확정
- 실패나 재시도 시 아이템 복제·유실이 발생하지 않음

### 단계 11 — 가호 시스템

- [ ] 가호 Definition과 ResourceLocation ID
- [ ] 가호 효과, 지속시간, 중첩, 갱신, 해제 정책
- [ ] 플레이어별 활성 가호 저장
- [ ] `BlessingConstraintSnapshot`
- [ ] `BlessingProposal` schema
- [ ] 관계, 진행도, cooldown, 상호 배제 validator
- [ ] 플레이어 수락과 실제 적용 executor
- [ ] 효과 종료·사망·로그아웃·서버 재시작 처리
- [ ] 적용·거절 결과 feedback

완료 조건:

- AI는 등록된 가호 ID만 제안 가능
- 서버 validator를 통과한 가호만 적용
- 가호 효과가 재접속과 서버 재시작 정책에 맞게 유지 또는 종료

### 단계 12 — 다중 참가자와 사회적 상호작용

- [ ] Secondary God 자동 선택 정책
- [ ] 복수 플레이어 ACTIVE/LISTENER audience 전달
- [ ] 플레이어가 LISTENER에서 ACTIVE로 전환하는 규칙
- [ ] 여러 신 중 다음 화자 선택
- [ ] NPC와 NPC의 관계 유형 보조 태그
- [ ] 보증, 논쟁, 중재, 비밀 대화 판단
- [ ] 참가자별 지식·관계 공개 범위 검증
- [ ] 다중 참가자 세션의 turn ordering과 동시성 제어

완료 조건:

- 여러 신과 플레이어가 한 세션에서 자연스럽게 대화
- 참가하지 않은 신이나 플레이어가 발화하지 않음
- 비밀 정보가 권한 없는 청중에게 공개되지 않음

### 단계 13 — NPC 및 월드 상호작용

- [ ] 신의 물리적 표현 방식 결정: entity, manifestation, UI-only
- [ ] NPC 소환·제거·위치·상호작용 권한
- [ ] AI `ActionProposal` 허용 목록
- [ ] 월드 변경, 몹 생성, 이동, 이펙트 등 validator/executor
- [ ] 지역 보호와 다른 모드 권한 시스템 연동
- [ ] 실패·부분 실행·rollback 정책

완료 조건:

- AI가 임의 명령어나 자유 형식 월드 변경을 실행하지 못함
- 등록된 action과 검증된 parameter만 실제 게임에 적용

### 단계 14 — 운영, 보안, 성능, 관측성

- [ ] 모델·endpoint·timeout·context limit 설정 파일
- [ ] AI 요청 rate limit과 backpressure
- [ ] 프롬프트 입력 크기와 생성 토큰 제한
- [ ] 민감 정보와 명령 주입 방어
- [ ] 대화/Proposal/검증 결과 structured logging
- [ ] 플레이어별 로그 보존·삭제·백업 정책
- [ ] 운영자 세션 진단 및 강제 종료 명령
- [ ] 모델 unavailable 시 fallback 대사
- [ ] 장시간 부하와 다중 세션 테스트
- [ ] 다른 모드와의 호환성 매트릭스

완료 조건:

- Ollama 장애나 느린 응답이 Minecraft 서버 tick을 멈추지 않음
- 문제 발생 시 request/session/proposal ID로 전체 흐름을 추적 가능
- 외부 모드를 추가해도 충돌 원인을 단계적으로 판별 가능

### 단계 15 — 콘텐츠 확장과 서버 완성

- [ ] 신 135개 ID와 MythicTRPG God Definition 정합성 확정
- [ ] 신별 프로필 검수
- [ ] 세계 지식과 knowledge level 검수
- [ ] 말투·관계·상황 콘텐츠 품질 검수
- [ ] 신별 gameplay promotion과 interaction rule 추가
- [ ] 퀘스트·보상·가호 콘텐츠 밸런스
- [ ] 외부 모드 분류 및 의존성 확인
- [ ] 클라이언트 필수 모드 배포팩 구성
- [ ] 신규 월드·기존 월드 migration 테스트
- [ ] 실제 다중 플레이어 플레이테스트

완료 조건:

- 신규 신과 콘텐츠를 코드 수정 없이 데이터 중심으로 추가 가능
- 서버와 클라이언트 배포 파일 목록이 확정됨
- 실제 플레이에서 대화·퀘스트·보상·가호가 하나의 흐름으로 동작

## 6. 바로 진행할 다음 단계

현재 최우선 작업은 **단계 1 — 새 MythicTRPG ↔ AI 응답 모드 기본 연결**이다.

첫 구현 단위는 다음으로 제한한다.

```text
성숙한 밀 수확
→ MythicTRPG가 Demeter와 InteractionPlan 확정
→ bounded DTO로 AI 응답 모드 호출
→ AI가 Demeter 대사만 반환
→ MythicTRPG가 검증
→ Dialogue HUD 출력
```

이 단계에서는 퀘스트, 아이템, 가호, 관계 변화 Proposal을 함께 구현하지 않는다. 먼저 대화 provider 경계와 비동기 실패 처리부터 안정화한다.

## 7. 진행 기록

| 날짜 | 단계 | 변경 내용 | 검증 결과 |
|---|---|---|---|
| 2026-08-25 | 기준선 | upstream 보존, `mythictrpg-main` 개발본과 `integration/ai` 브랜치 생성 | `clean build` 성공, GameTest 158/158 통과 |
| 2026-08-25 | 단계 1 | 정식 AI provider, 대화 엔진 계약, 우측 ON/OFF UI, 데메테르 프로필과 서버 배포 | 세 모드 빌드 성공, GameTest 158/158 통과, 실제 서버 정상 부팅 |
