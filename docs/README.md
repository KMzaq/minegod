# 문서 길잡이

새 채팅은 [작업 시작 안내](../인수인계/START_HERE.md)와 [루트 작업 원칙](../AGENTS.md)을 먼저 읽는다. 이 폴더는 계약, 설계, 날짜별 구현·검증 기록이 섞여 있다. 제목에 `완료`나 `최신`이 있어도 **그 문서가 작성된 시점의 범위**이며 현재 설치·실행의 증거가 아니다. 실제 상태는 소스, Gradle 입력, [서버 파일·로그](../server/README.md)를 대조한다.

## 대화와 AI 연동

밸런스 수치를 조정하는 에이전트는 먼저 [조정 원본·단위·연동 필드 안내](../mythictrpg-main/docs/BALANCE_TUNING.md)를 읽는다. 현재 소스 값은 `dev-tools/Get-CombatBalance.ps1`로 조회한다.

야생에서의 스킬·PvP·동물/파티원 보호와 `/mythparty`는 [야생 전투·파티 안내](../mythictrpg-main/docs/WILDERNESS_COMBAT_AND_PARTY.md)를 따른다.

장신구 장착 조작은 [E 인벤토리의 장신구 두 칸](../mythictrpg-main/docs/ACCESSORY_INVENTORY.md)을 따른다.

새 장신구 이름·아이템 ID·텍스처와 보상 연결은 [장신구 아이템 분류와 외형](../mythictrpg-main/docs/ACCESSORY_ITEMS.md)을 따른다.

같은 검·장신구 외형을 여러 장비 정의에서 재사용하는 방법과 시험 지급은 [무기·장신구 외형과 개별 장비 정의](../mythictrpg-main/docs/EQUIPMENT_APPEARANCES.md)를 따른다.

Simply Swords의 외형·Better Combat 평타를 사용하는 우리 보상 무기는 [연동·시험·작성 안내](../mythictrpg-main/docs/SIMPLY_SWORDS_INTEGRATION.md)를 따른다. 원본 고유 능력은 별도 외형 아이템에 붙이지 않으며, 개발 의존성 준비와 실제 서버/런처 설치를 구분한다.

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
| 기존 시험 인물·지식·스토리 보관 | [구버전 보관소](../구버전/20261022-테스트콘텐츠/README.md). 기존 리소스와 서버 시험 팩·인물별 설정은 사용 경로에서 분리. 이전 ID 대응표는 이관 전 스냅샷이며 새 기획을 현재 등록 목록으로 연결해야 함. |
| 기획자가 요청한 실제 계산·작성 규격·인물 ID | [2026-10-22 추가 연결 자료](../전달자료/planner-reference-20261022/README.md). 현재 전투력 설정·계산 코드, 장비/스킬/장신구 예시, God ID 대응표, 기존 퀘스트·Story 가이드 변경점과 레이드 문서. 장비 7등급 저장 규격과 일반 수주 전투력 제한은 미구현으로 구분. |
| 가호·행운 보상·전투력 v2 | [기술 계약과 초기 계수](../mythictrpg-main/docs/BLESSING_REWARD_POWER_V2.md), [기획 전달자료](../전달자료/blessing-reward-planning-20261021.md), [검증 기록](../인수인계/브랜치실험/behaivior/REWARD_PROGRESSION_20261021.md). 인물별 등급·가호 배정은 기획에서 추후 확정. |
| 스킬·장신구·특수지원 | [기획자 전달서·신별 작성 양식](../전달자료/skills-accessories-special-planning-20261022.md), [시스템 작성 가이드](../mythictrpg-main/docs/SKILLS_ACCESSORIES_SPECIAL.md). 마나200+장신구·초당1%, 습득 스킬, 자유 슬롯2개, 조합형 특수 사용 제한. 개별 인물 배정은 기획 후속. |
| 전투 1차 무기·방어구·패링·검기·대쉬 | [직접 시험·수치 조정 안내](../mythictrpg-main/docs/FIRST_COMBAT_PROTOTYPE.md). OP 시제품 지급·재지급·시험장, 실제 레이드 연결과 수치 파일 위치. 인물 배정 없음. |
| FTB Quests, 퀘스트 참여·생성·보상 | [FTB 연동](../mythictrpg-main/docs/FTB_QUESTS_INTEGRATION_GUIDE.md), [참여 유형](../mythictrpg-main/docs/QUEST_PARTICIPATION_GUIDE.md), [생성 퀘스트](../mythictrpg-main/docs/GENERATED_QUESTS_GUIDE.md), [보상](../mythictrpg-main/docs/REWARD_SYSTEM_GUIDE.md) |
| 사건·Story 연출·신 간 관계 | [사건 엔진](../mythictrpg-main/docs/STORY_EVENT_ENGINE_GUIDE.md), [AI 연출](../mythictrpg-main/docs/STORY_AI_PRESENTATION_GUIDE.md), [신 간 관계](../mythictrpg-main/docs/GOD_RELATION_SYSTEM_GUIDE.md) |
| 탐험·단서·재료·방문·동시 사건 | [콘텐츠 시스템 작성/일반 플레이 진입점](../mythictrpg-main/docs/CONTENT_SYSTEMS_GUIDE.md), [기획 분담](../전달자료/content-planning-system-split-20261020.md), [일반 플레이 검증 기록](../인수인계/브랜치실험/behaivior/CONTENT_GAMEPLAY_20261021.md). 정식 정의·운영 배포와 개발 검증을 구분. |
| 상점·건축 평가 | [상점·경제](../mythictrpg-main/docs/SHOP_ECONOMY_GUIDE.md), [건축 평가](../mythictrpg-main/docs/STRUCTURE_EVALUATION_GUIDE.md) |
| 레이드 실행·첫 시험 보스(HanesTest 개발) | [런타임 계약](../mythictrpg-main/docs/RAID_RUNTIME.md), [봉인된 석상 수호자 시련](../mythictrpg-main/docs/raid-trial-guide.md). 서버 배포 상태는 브랜치 기록을 확인. |
| NPC 공간 인식·생활활동(HanesTest 개발) | [활동 실행·권한](../mythictrpg-main/docs/NPC_ACTIVITY_SYSTEM.md), [활동 JSON](../mythictrpg-main/docs/NPC_ACTIVITY_AUTHORING_GUIDE.md), [AI 연동](../mythai-ai-response/NPC_ACTIVITY_AI_GUIDE.md). 운영 적용 상태는 브랜치 기록을 확인. |
| 신 프로필·로어·예시·퀘스트 목록 JSON | [콘텐츠 구조](../mythai-ai-content-registry/AI_CONTENT_REGISTRY_CONTENT_AUTHORING_GUIDE.md), [프로필](../mythai-ai-content-registry/NPC_PROFILE_AUTHORING_GUIDE.md), [퀘스트 목록](../mythai-ai-content-registry/QUEST_LIST_AUTHORING_GUIDE.md) |

각 가이드는 해당 모듈의 작업 참고 자료다. 입력 형식이나 기능이 바뀌면 소스·생산자/소비자 계약과 함께 갱신한다. 새 게임 기능의 작성 기준은 `mythictrpg-main/`이며, 과거 `mine/`, `mine2/`는 새 개발 기준이 아니다. 다만 AI 빌드 의존성이 남아 있어 임의로 삭제하지 않는다.

## 기억·관찰·사회 시스템의 정책과 이력

- 2026-10-20 게임1.0.36 / AI0.1.50: [누적 플레이 통계·진행 지원](../추가개발/플레이어이해/20-support-statistics.md), [현재 검증](../검토결과/support-statistics-20261020/REVIEW.md). 평소 플레이/레이드/대화 경향과 선택적 인물 목표 기록, 본인 화면·동의 후 요청형 도움. AFK 중립·모델 유지·운영 미배포. 아래 버전은 선행 이력이다.
- 2026-10-20 후속 AI0.1.49: [필요할 때만 참고하는 행동·성향 지원](../추가개발/플레이어이해/19-need-only-dialogue-support.md). 기록·대화 기억을 유지하고 평상시 관찰/파생 지원 조회는 생략한다. [과장·생성 잘림 후속 검증](../검토결과/support-grounding-20261020/REVIEW.md): 행동 범위 표현과 재작성·잘림 재시도를 보완했으며 전체 의미 gate 및 운영 배포 상태는 보고서를 따른다.
- 2026-10-20 개발 게임1.0.35 / AI0.1.47: [실제 대화의 Native 기억·관찰 기반 지원 계약](../추가개발/플레이어이해/18-native-dialogue-support.md), [실제 LLM·그래픽·전체 회귀 검증](../검토결과/native-dialogue-support-20261020/REVIEW.md). 명시적 NEW 개발 경로이며 개인 프로필/목적 자동 공개나 운영 배포가 아니다. 아래 이전 버전의 미연결·검증 기록은 당시 이력으로 구분한다.
- 현재 담당/검증 범위: [행동 기록·성향/목적 추론과 일반 대화 품질 분리](../추가개발/플레이어이해/16-behavior-system-scope.md), [Minecraft 활동·AFK 중립 구현](../추가개발/플레이어이해/17-minecraft-support-activities.md). 잠정적 가설·작은 제안 보정을 목표로 실제 입력/저장/권한/게임 연동을 우선한다. 이전 대화 실패 결과를 합격으로 바꾸거나 서버 배포한 것은 아니다.
- 목적 대사 후속 AI0.1.45 실험: [의미 계약](../추가개발/플레이어이해/15-purpose-reply-contract.md), [비교 검증](../검토결과/purpose-reply-20261019/REVIEW.md). 핵심 구절/후속 표현 제한과 검토 후보를 비교하며 기본 경로 비활성·미배포다. 자동 PASS를 전체 의미·자연스러움 합격으로 보지 않는다.
- 플레이어 성향·목적 이해: [설계·구현 입구](../추가개발/플레이어이해/README.md). 현재 `behaivior` 게임1.0.32 / AI0.1.44의 [근거 누적형 목적 상태](../추가개발/플레이어이해/14-evidence-driven-purpose.md)는 의향·행동·강도·생명주기를 분리하고 원장 영속 주석·본인 화면·철회를 구현했다. 실제 Gemma 대사·수정은 격리 평가했으나 전체 의미 gate는 실패했고, 자동 방 대화/Native 기억·관찰자 연결은 미완료다. [검증 기록](../검토결과/purpose-state-20261019/REVIEW.md)을 우선한다. 자동 추출 비활성·전체 gate 미완료·미배포. 이전0.1.43 실험은 [13](../추가개발/플레이어이해/13-independent-semantic-frames.md)의 당시 결과다.
- 기록 범위의 정책 출발점: [ACTION_RECORDING_POLICY_20260917.md](ACTION_RECORDING_POLICY_20260917.md). 이전 [ACTION_OBSERVATION_MEMORY_ROADMAP_20260915.md](ACTION_OBSERVATION_MEMORY_ROADMAP_20260915.md)의 일부 조건은 나중에 조정되었다.
- 행동·주시 단계별 당시 구현: [ACTION_LEDGER_STAGE02_20260915.md](ACTION_LEDGER_STAGE02_20260915.md), [GOD_WATCH_STAGE03_20260915.md](GOD_WATCH_STAGE03_20260915.md), [EXPERIENCE_STAGE04_20260915.md](EXPERIENCE_STAGE04_20260915.md), [MEMORY_STAGE05_20260916.md](MEMORY_STAGE05_20260916.md), [MEMORY_STAGE05_COMPLETION_20260917.md](MEMORY_STAGE05_COMPLETION_20260917.md), [MEMORY_POLICY_IMPLEMENTATION_20260920.md](MEMORY_POLICY_IMPLEMENTATION_20260920.md).
- 기억 설계·측정·복구 이력: [LP_LONG_TERM_MEMORY_DESIGN.md](LP_LONG_TERM_MEMORY_DESIGN.md), [EPISODIC_MEMORY_UPGRADE_DESIGN_20260915.md](EPISODIC_MEMORY_UPGRADE_DESIGN_20260915.md), [MEMORY_MODEL_CONNECTION_20260920.md](MEMORY_MODEL_CONNECTION_20260920.md), [LP_MEMORY_FOUNDATION_GUIDE.md](LP_MEMORY_FOUNDATION_GUIDE.md). 모델 측정 JSON은 `evidence/`에 있다.
- 소문·평판 단계별 당시 상태: [RUMOR_STAGE06_PREFLIGHT_20260920.md](RUMOR_STAGE06_PREFLIGHT_20260920.md), [RUMOR_STAGE06_IMPLEMENTATION_20260920.md](RUMOR_STAGE06_IMPLEMENTATION_20260920.md), [REPUTATION_STAGE07_20260920.md](REPUTATION_STAGE07_20260920.md), [SOCIAL_PIPELINE_COMPLETION_20260920.md](SOCIAL_PIPELINE_COMPLETION_20260920.md). 계획·소스 연결·운영 OFF/검증 범위를 각각 구분한다.
- 기타 과거 분석·복구·배포 보고서는 [공용 인수인계 색인](../인수인계/HISTORY_INDEX.md)에서 날짜와 절을 찾아 읽는다. 이 폴더의 `*-changes-*.csv`, `examples/`, `evidence/`는 해당 시점 보고서의 증거·예제다.

## 현황으로 쓰면 안 되는 초기 문서

[development-workspace-guide.md](development-workspace-guide.md), [integration-status.md](integration-status.md), [implementation-roadmap.md](implementation-roadmap.md), [mod-list.md](mod-list.md)는 초기 구성·계획·목록의 이력이다. [version-matrix.md](version-matrix.md)의 Minecraft/NeoForge/Java 기준과 **실제 모드 JAR 버전**도 별개다. 이 문서들을 최신 기능·설치 목록의 단일 출처로 사용하지 않는다.

현재 작업 기록은 [공용 인수인계](../인수인계/PROJECT_HANDOFF.md)와 **해당 브랜치 인수인계**, 설치 상태는 `server/mods` 및 `server/logs`에서 확인한다. 실험 브랜치 문서를 공용 계약으로 자동 복사하지 않는다.
