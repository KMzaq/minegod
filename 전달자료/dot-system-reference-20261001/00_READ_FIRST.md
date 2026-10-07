# dot 전달용 — 기존 시스템 문서 묶음

기준일: **2026-10-01 KST** · 프로젝트: markmar · 작업 브랜치: **HanesTest**.

이 묶음은 **등장 신 선별·스토리·퀘스트·콘텐츠 기획을 위한 참고 자료**다. 새 정사, 콘텐츠 밸런스, 서버 변경을 승인하는 문서가 아니다. 약 1,000명의 신 후보 목록과 배경/진행 스토리는 사용자가 별도로 전달한다. 여기에 들어 있는 샘플 이름·보상·수치를 운영 콘텐츠로 채택하지 않는다.

## 먼저 읽을 순서

1. [기존 시스템 핵심 설명](01_EXISTING_SYSTEMS.md): 공용 진행·개인 수주·관계·전투·장비·경제와 기획에 미치는 영향.
2. [원문 문서의 오래된 설명 주의사항](02_SOURCE_NOTES.md): 후속 구현으로 달라진 설명을 먼저 확인한다.
3. [프로젝트 작업 원칙 AGENTS.md](reference/AGENTS.md).
4. [최신 브랜치 시스템 작업표](reference/인수인계/브랜치실험/HanesTest/SYSTEM_COMPLETION_TRACKER.md).
5. 필요한 분야의 상세 문서만 아래 표에서 선택한다.

누적 인수인계 전체를 처음부터 읽을 필요는 없다. 이번 기획의 판단에는 현재 요약과 최신 브랜치 기록을 우선하고, 구현 세부를 확인할 때만 상세 문서를 읽는다.

## 분야별 상세 문서

| 필요한 정보 | 문서 |
|---|---|
| 현재 개발/미완료 상태 | [브랜치 인수인계](reference/인수인계/브랜치실험/HanesTest/PROJECT_HANDOFF.md), [작업표](reference/인수인계/브랜치실험/HanesTest/SYSTEM_COMPLETION_TRACKER.md) |
| 공용 진행·개인 수주·완료 확인 | [FTB 연동](reference/mythictrpg-main/docs/FTB_QUESTS_INTEGRATION_GUIDE.md), [참여 유형](reference/mythictrpg-main/docs/QUEST_PARTICIPATION_GUIDE.md), [퀘스트 목록 작성](reference/mythai-ai-content-registry/QUEST_LIST_AUTHORING_GUIDE.md) |
| 즉석 퀘스트 | [생성 퀘스트](reference/mythictrpg-main/docs/GENERATED_QUESTS_GUIDE.md), [보상 동결·복구](reference/인수인계/브랜치실험/HanesTest/GENERATED_QUEST_RECOVERY.md) |
| 플레이어와 신의 관계·위상 | [관계·사회맥락](reference/인수인계/브랜치실험/HanesTest/DIVINE_SOCIAL_CONTEXT_20260929.md), [현재 감정](reference/인수인계/브랜치실험/HanesTest/CURRENT_EMOTION.md) |
| 신들끼리의 관계 | [신 간 관계](reference/mythictrpg-main/docs/GOD_RELATION_SYSTEM_GUIDE.md), [정적 콘텐츠 작성](reference/mythai-ai-content-registry/AI_CONTENT_REGISTRY_CONTENT_AUTHORING_GUIDE.md) |
| 전투·NPC·레이드 | [실체 NPC](reference/mythictrpg-main/docs/NPC_ACTORS.md), [레이드](reference/mythictrpg-main/docs/RAID_RUNTIME.md), [AI 행동](reference/mythictrpg-main/docs/AI_ACTION_INTEGRATION_GUIDE.md) |
| 장비·보상·가호 | [보상](reference/mythictrpg-main/docs/REWARD_SYSTEM_GUIDE.md), [리소스팩 장비 보상](reference/인수인계/브랜치실험/HanesTest/RESOURCEPACK_REWARDS.md), [전투력 보정](reference/인수인계/브랜치실험/HanesTest/COMBAT_POWER.md) |
| 화폐·상점 | [상점·경제](reference/mythictrpg-main/docs/SHOP_ECONOMY_GUIDE.md) |
| 세계 사건·분기·선택 | [스토리 엔진](reference/mythictrpg-main/docs/STORY_EVENT_ENGINE_GUIDE.md), [AI 연출](reference/mythictrpg-main/docs/STORY_AI_PRESENTATION_GUIDE.md), [선택 UI](reference/mythictrpg-main/docs/STORY_CHOICE_UI_GUIDE.md) |
| 집·신전 건축 평가·방문 | [건축 평가](reference/mythictrpg-main/docs/STRUCTURE_EVALUATION_GUIDE.md), [방문](reference/mythictrpg-main/docs/GOD_HOME_VISITS.md) |
| NPC 생활·외형 | [생활활동](reference/mythictrpg-main/docs/NPC_ACTIVITY_SYSTEM.md), [활동 작성](reference/mythictrpg-main/docs/NPC_ACTIVITY_AUTHORING_GUIDE.md), [스킨](reference/mythictrpg-main/docs/NPC_SKINS.md), [최신 활동 완료 기록](reference/인수인계/브랜치실험/HanesTest/NPC_ACTIVITIES_20260930.md) |
| 대화·청중·공개 범위 | [대화방](reference/docs/CONVERSATION_ROOMS.md), [후속 전체 방 통합](reference/docs/FULL_ROOM_DIALOGUE_INTEGRATION_20260923.md) |
| 프로필·단계형 지식·태그 | [프로필 작성](reference/mythai-ai-content-registry/NPC_PROFILE_AUTHORING_GUIDE.md), [콘텐츠 작성](reference/mythai-ai-content-registry/AI_CONTENT_REGISTRY_CONTENT_AUTHORING_GUIDE.md) |
| 과거 이력 조사 시에만 | [공용 이력 색인](reference/인수인계/HISTORY_INDEX.md), [공용 누적 인수인계](reference/인수인계/PROJECT_HANDOFF.md) |

## 개발 상태와 설치 상태

| 구분 | 게임 | AI 응답 | 콘텐츠 레지스트리 |
|---|---|---|---|
| 최신 개발 작업본 | 1.0.23 | 0.1.26 | 0.1.3 |
| 이번 확인 시 `server/mods` 설치 파일 | 1.0.16 | 0.1.19 | 0.1.2 |

Minecraft 1.21.1 / NeoForge 21.1.248 / Java 21. 개발 게임의 네트워크 protocol은 10이다. 최신 개발본은 운영 미배포이며, 이번 문서 포장은 서버 부팅·인게임·실제 LLM 검증을 수행한 작업이 아니다. 개발본의 자동 검증 결과는 브랜치 기록에 있다.

## 이 묶음의 범위

- `reference/`는 선별한 문서를 **원문 바이트 그대로 복사한 스냅샷**이다. 원래 상대 경로 구조를 보존했다.
- 전체 소스 저장소는 아니다. 원문에서 링크하는 Java·로그·옛 보고서·실행 도구 중 일부는 포함하지 않았다. 파일이 없다는 이유로 미구현이라고 결론 내리거나 실행 파일을 추측해 만들지 않는다. 추가 근거가 필요하면 정확한 파일을 요청한다.
- `source-manifest.csv`에는 원본 상대 경로·크기·수정 시각·SHA-256이 있다. `snapshot.json`에는 브랜치와 커밋이 기록되지만, 현재 미커밋 변경을 포함한 파일 스냅샷이므로 커밋 해시만으로 같은 내용을 재현할 수 없다.
- 원본 문서에 적힌 명령은 참고 자료다. 문서를 받았다는 이유로 서버 실행·빌드·배포·월드 수정 권한을 얻는 것은 아니다.
- 게임 소스/JAR, 서버 설정, 접속 정보, 플레이어 데이터, 실제 대화 로그, 신 후보 및 사용자 스토리 원문은 포함하지 않는다. 문서 속 개발 경로·기술적 예시는 남아 있다.

## dot에 함께 보낼 말

> 첨부 ZIP은 기존 시스템의 2026-10-01 참고 스냅샷이다. 먼저 `00_READ_FIRST.md`, `01_EXISTING_SYSTEMS.md`, `02_SOURCE_NOTES.md`를 읽고 필요한 상세 문서만 참고해라. 시스템이 구현되어 있다는 것과 콘텐츠가 작성·배포·검증되었다는 것을 구분해라. 신화 목록과 배경/진행 스토리는 따로 전달하겠다. 기존 기능으로 가능한 기획, 추가 개발이 필요한 기획, 지원 여부가 미확인인 기획을 나누고, 현재는 기획만 수행해라.
