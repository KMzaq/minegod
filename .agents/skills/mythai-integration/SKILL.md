---
name: mythai-integration
description: "markmar의 MythicTRPG·AI 응답·콘텐츠 레지스트리 모드 기능과 IO 연동을 구현하거나 검토한다. Snapshot, Proposal, 비동기 세션, 퀘스트/보상/스토리의 권한 경계 변경에 사용하며 로그 분석만 하거나 정적 JSON만 작성할 때는 해당 전용 스킬을 사용한다."
---

# MythAI 모드 연동

프로젝트 루트는 이 파일에서 `../../..`이다. [AGENTS.md](../../../AGENTS.md)와 [인수인계](../../../인수인계/PROJECT_HANDOFF.md)의 관련 최신 절을 기준으로 작업 범위를 정한다.

## 실제 경로부터 확인

- 게임의 호출부 → DTO/Provider → AI 응답 → 정규화 → Gateway → Validator/Executor → HUD/피드백을 추적한다. 같은 이름의 구 구현과 운영 코드가 공존할 수 있다.
- AI 응답의 `build.gradle`에서 현재 게임 JAR, legacy AI JAR, 원본 Java, 생성 오버레이 및 JAR 제외 목록을 확인한다. 패키지명이 `com.sande.mythictrpg.ai`라는 이유만으로 게임 모드 소유 코드라고 단정하지 않는다.
- `mythictrpg-main`은 현재 작업본, `incoming`은 보존 원본이다. `mine/mine`은 일부 AI 빌드 입력이다. 불필요한 전체 복사·원본 수정·의존성 분리를 하지 않는다.

## 필요한 계약만 읽기

상세 가이드는 모두 `mythictrpg-main/docs/`에 있다. 해당하는 것만 읽고 구현과 대조한다.

- 액션: `AI_ACTION_INTEGRATION_GUIDE.md`
- 고정/즉석 퀘스트: `FTB_QUESTS_INTEGRATION_GUIDE.md`, `GENERATED_QUESTS_GUIDE.md`
- 지급/선택: `REWARD_SYSTEM_GUIDE.md`; 상점/화폐면 `SHOP_ECONOMY_GUIDE.md`
- 신 간 관계: `GOD_RELATION_SYSTEM_GUIDE.md`
- 스토리: `STORY_EVENT_ENGINE_GUIDE.md`, AI 연출이면 `STORY_AI_PRESENTATION_GUIDE.md`
- 건축 평가: `STRUCTURE_EVALUATION_GUIDE.md`

## 변경 시 보존할 것

- 게임 상태는 기존 저장소에 둔다. AI는 서버가 허용한 데이터로 대사·Proposal을 생성하고, 실제 등록된 액션과 템플릿만 Gateway에서 실행한다. 지원하지 않는 기능은 성공으로 꾸미지 않는다.
- 퀘스트/관계/스토리/경제는 SERVER·PLAYER·TEAM 범위를 먼저 확인한다. 정적 관계와 동적 관계, 화자에서 상대를 향하는 방향을 섞지 않는다.
- 게임 스레드에서 bounded snapshot을 만들고 LLM 처리는 비동기로 실행한다. 완료 적용 시 세션·턴·revision·참가자를 다시 검증한다. LLM 지연 때문에 서버 tick을 막지 않는다.
- 참가자 판정과 비밀 공개는 게임 계약을 사용한다. 미참가 신이나 불허된 지식을 관계 데이터로부터 새로 추가하지 않는다.
- IO 변경이 필요하면 송신·수신·정규화·fallback·예제·문서를 함께 수정하고 기존 데이터/저장 형식 영향도 명시한다.

## 검증과 결과

관련 컴파일과 회귀 테스트를 우선한다. 동시성/컨텍스트를 바꿨다면 Session A/B에 서로 다른 QuestConstraint, RewardConstraint, ProposalValidationFeedback을 주고 각각의 턴 snapshot에 자기 세션 것만 존재하는지 검사한다. 게임 실행 경로를 바꿨다면 거절·중복 실행·확인 후 상태 변경을 함께 확인한다.

변경 소유 모듈, IO 변경, 실제 실행 가능 범위, 테스트 결과, 미배포/미확인을 구분해 보고한다. 분석 요청만 받았다면 패치·빌드·배포를 실행하지 않는다.
