# 문서 길잡이

새 채팅은 [작업 시작 안내](../인수인계/START_HERE.md)와 [루트 작업 원칙](../AGENTS.md)을 먼저 읽는다. 이 폴더는 계약, 설계, 날짜별 구현·검증 기록이 섞여 있다. 제목에 `완료`나 `최신`이 있어도 **그 문서가 작성된 시점의 범위**이며 현재 설치·실행의 증거가 아니다. 실제 상태는 소스, Gradle 입력, [서버 파일·로그](../server/README.md)를 대조한다.

## 대화와 AI 연동

| 필요 정보 | 읽을 문서 | 해석 |
|---|---|---|
| 공개/비밀·고정/이동 대화방과 참가자 | [CONVERSATION_ROOMS.md](CONVERSATION_ROOMS.md) | 방 동작의 시작점. 세부 구현과 변경은 다음 통합 기록 및 현재 코드로 확인. |
| 다중 신, Story, 지식·대화 공개 경계 | [FULL_ROOM_DIALOGUE_INTEGRATION_20260923.md](FULL_ROOM_DIALOGUE_INTEGRATION_20260923.md), [ORIGINAL_GOALS_REINTEGRATION_20260923.md](ORIGINAL_GOALS_REINTEGRATION_20260923.md) | 2026-09-23 개발·검증·배포의 시점별 기록. |
| 신별 청취 기억 | [ROOM_HEARD_MEMORY_20260923.md](ROOM_HEARD_MEMORY_20260923.md) | 방 통합의 기억 세부. |
| 채팅 기록·시험 명령 | [ROOM_RECORDING_INTEGRATION_20260921.md](ROOM_RECORDING_INTEGRATION_20260921.md), [AI_DIALOGUE_TEST_COMMAND.md](AI_DIALOGUE_TEST_COMMAND.md) | 명령 가이드 첫머리의 구버전·미배포 문구는 과거 상태. 현재 명령은 게임 소스와 서버 파일을 대조. |
| AI가 제안하는 행동과 게임의 실행 권한 | [AI_ACTION_INTEGRATION_GUIDE.md](../mythictrpg-main/docs/AI_ACTION_INTEGRATION_GUIDE.md) | 게임 모듈의 계약 가이드. |
| AI 기억·검색 | [AI_MEMORY_RAG_GUIDE.md](../mythai-ai-response/AI_MEMORY_RAG_GUIDE.md) | AI 모듈 가이드. 실제 모드 버전의 구현 범위를 확인. |

## 게임 기능과 콘텐츠 작성

| 작업 | 읽을 문서 |
|---|---|
| FTB Quests, 퀘스트 참여·생성·보상 | [FTB 연동](../mythictrpg-main/docs/FTB_QUESTS_INTEGRATION_GUIDE.md), [참여 유형](../mythictrpg-main/docs/QUEST_PARTICIPATION_GUIDE.md), [생성 퀘스트](../mythictrpg-main/docs/GENERATED_QUESTS_GUIDE.md), [보상](../mythictrpg-main/docs/REWARD_SYSTEM_GUIDE.md) |
| 사건·Story 연출·신 간 관계 | [사건 엔진](../mythictrpg-main/docs/STORY_EVENT_ENGINE_GUIDE.md), [AI 연출](../mythictrpg-main/docs/STORY_AI_PRESENTATION_GUIDE.md), [신 간 관계](../mythictrpg-main/docs/GOD_RELATION_SYSTEM_GUIDE.md) |
| 상점·건축 평가 | [상점·경제](../mythictrpg-main/docs/SHOP_ECONOMY_GUIDE.md), [건축 평가](../mythictrpg-main/docs/STRUCTURE_EVALUATION_GUIDE.md) |
| NPC 공간 인식·생활활동(HanesTest 개발) | [활동 실행·권한](../mythictrpg-main/docs/NPC_ACTIVITY_SYSTEM.md), [활동 JSON](../mythictrpg-main/docs/NPC_ACTIVITY_AUTHORING_GUIDE.md), [AI 연동](../mythai-ai-response/NPC_ACTIVITY_AI_GUIDE.md). 운영 적용 상태는 브랜치 기록을 확인. |
| 신 프로필·로어·예시·퀘스트 목록 JSON | [콘텐츠 구조](../mythai-ai-content-registry/AI_CONTENT_REGISTRY_CONTENT_AUTHORING_GUIDE.md), [프로필](../mythai-ai-content-registry/NPC_PROFILE_AUTHORING_GUIDE.md), [퀘스트 목록](../mythai-ai-content-registry/QUEST_LIST_AUTHORING_GUIDE.md) |

각 가이드는 해당 모듈의 작업 참고 자료다. 입력 형식이나 기능이 바뀌면 소스·생산자/소비자 계약과 함께 갱신한다. 새 게임 기능의 작성 기준은 `mythictrpg-main/`이며, 과거 `mine/`, `mine2/`는 새 개발 기준이 아니다. 다만 AI 빌드 의존성이 남아 있어 임의로 삭제하지 않는다.

## 기억·관찰·사회 시스템의 정책과 이력

- 기록 범위의 정책 출발점: [ACTION_RECORDING_POLICY_20260917.md](ACTION_RECORDING_POLICY_20260917.md). 이전 [ACTION_OBSERVATION_MEMORY_ROADMAP_20260915.md](ACTION_OBSERVATION_MEMORY_ROADMAP_20260915.md)의 일부 조건은 나중에 조정되었다.
- 행동·주시 단계별 당시 구현: [ACTION_LEDGER_STAGE02_20260915.md](ACTION_LEDGER_STAGE02_20260915.md), [GOD_WATCH_STAGE03_20260915.md](GOD_WATCH_STAGE03_20260915.md), [EXPERIENCE_STAGE04_20260915.md](EXPERIENCE_STAGE04_20260915.md), [MEMORY_STAGE05_20260916.md](MEMORY_STAGE05_20260916.md), [MEMORY_STAGE05_COMPLETION_20260917.md](MEMORY_STAGE05_COMPLETION_20260917.md), [MEMORY_POLICY_IMPLEMENTATION_20260920.md](MEMORY_POLICY_IMPLEMENTATION_20260920.md).
- 기억 설계·측정·복구 이력: [LP_LONG_TERM_MEMORY_DESIGN.md](LP_LONG_TERM_MEMORY_DESIGN.md), [EPISODIC_MEMORY_UPGRADE_DESIGN_20260915.md](EPISODIC_MEMORY_UPGRADE_DESIGN_20260915.md), [MEMORY_MODEL_CONNECTION_20260920.md](MEMORY_MODEL_CONNECTION_20260920.md), [LP_MEMORY_FOUNDATION_GUIDE.md](LP_MEMORY_FOUNDATION_GUIDE.md). 모델 측정 JSON은 `evidence/`에 있다.
- 소문·평판 단계별 당시 상태: [RUMOR_STAGE06_PREFLIGHT_20260920.md](RUMOR_STAGE06_PREFLIGHT_20260920.md), [RUMOR_STAGE06_IMPLEMENTATION_20260920.md](RUMOR_STAGE06_IMPLEMENTATION_20260920.md), [REPUTATION_STAGE07_20260920.md](REPUTATION_STAGE07_20260920.md), [SOCIAL_PIPELINE_COMPLETION_20260920.md](SOCIAL_PIPELINE_COMPLETION_20260920.md). 계획·소스 연결·운영 OFF/검증 범위를 각각 구분한다.
- 기타 과거 분석·복구·배포 보고서는 [공용 인수인계 색인](../인수인계/HISTORY_INDEX.md)에서 날짜와 절을 찾아 읽는다. 이 폴더의 `*-changes-*.csv`, `examples/`, `evidence/`는 해당 시점 보고서의 증거·예제다.

## 현황으로 쓰면 안 되는 초기 문서

[development-workspace-guide.md](development-workspace-guide.md), [integration-status.md](integration-status.md), [implementation-roadmap.md](implementation-roadmap.md), [mod-list.md](mod-list.md)는 초기 구성·계획·목록의 이력이다. [version-matrix.md](version-matrix.md)의 Minecraft/NeoForge/Java 기준과 **실제 모드 JAR 버전**도 별개다. 이 문서들을 최신 기능·설치 목록의 단일 출처로 사용하지 않는다.

현재 작업 기록은 [공용 인수인계](../인수인계/PROJECT_HANDOFF.md)와 **해당 브랜치 인수인계**, 설치 상태는 `server/mods` 및 `server/logs`에서 확인한다. 실험 브랜치 문서를 공용 계약으로 자동 복사하지 않는다.
