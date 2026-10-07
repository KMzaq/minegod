# markmar 작업 시작 안내

이 문서는 여러 채팅이 **같은 프로젝트 목표와 서로 다른 검증 단계**를 혼동하지 않도록 하는 진입점이다. 상세 기능 명세를 복제하지 않는다. 프로젝트 원칙은 [루트 AGENTS.md](../AGENTS.md), 과거 작업 기록은 [공용 인수인계](PROJECT_HANDOFF.md), 주제별 문서는 [문서 길잡이](../docs/README.md)를 따른다.

## 1. 시작할 때 확인할 것

1. `git branch --show-current`와 `git status --short`로 브랜치 및 다른 채팅의 미커밋 변경을 확인한다. 같은 파일의 동시 수정은 작업 범위를 먼저 나눈다.
2. 아래의 **해당 브랜치 기록**을 읽고, [공용 인수인계 색인](HISTORY_INDEX.md)에서 필요한 절만 읽는다. 공용 문서의 §1–12는 구조 설명이지만 버전·설치 목록은 작성 당시의 기록이다.
3. 기능을 말할 때 `계획 → 소스 구현 → 컴파일/테스트 → JAR 배포 → 서버 부팅 → 실제 인게임/LLM 확인`을 분리한다. 한 단계를 통과했다고 다음 단계가 끝난 것은 아니다.
4. 실제 상태는 해당 소스·빌드 입력·`server/mods`·`server/logs`에서 다시 확인한다. 날짜가 붙은 문서의 “최신”이나 다른 채팅의 답변만으로 현재 상태를 확정하지 않는다.

## 2. 기록과 실제 상태의 관계

| 대상 | 우선 읽을 기록 | 주의 |
|---|---|---|
| 공용 기준과 과거 작업 | [공용 인수인계](PROJECT_HANDOFF.md) §58 및 [절별 색인](HISTORY_INDEX.md) | §58은 2026-09-23 당시 게임 1.0.16 / AI 0.1.17 / 콘텐츠 0.1.1의 **파일 배포·미부팅** 기록이다. 현재 설치 상태를 대체하지 않는다. |
| `HanesTest` 실험 브랜치 | [실험 전용 인수인계](브랜치실험/HanesTest/PROJECT_HANDOFF.md) | 공용 완료 상태로 자동 승격하지 않는다. 실험 변경·검증·배포 기록은 이 폴더에만 남긴다. |
| 실제 설치·실행 | [서버 안내](../server/README.md), `server/mods`, `server/client-required-mods`, `server/logs/latest.log` | README도 작성 시점 기록이다. 파일 설치와 새 조합의 성공적인 서버 부팅·게임 대화는 다르다. |

**2026-09-29 읽기 전용 점검 스냅샷:** 작업 브랜치는 `HanesTest`였다. `server/mods`에는 게임 `1.0.16`, AI `0.1.19`, 콘텐츠 `0.1.2`가 설치돼 있고, 클라이언트 필수 게임 JAR은 `1.0.16`이다. AI와 콘텐츠는 서버 전용이다. 확인된 마지막 `latest.log`는 2026-09-24의 이전 조합(게임 `1.0.16` / AI `0.1.17` / 콘텐츠 `0.1.1`) 부팅 로그다. 따라서 이 점검만으로 **현재 설치 조합의 서버 부팅·Ollama 대화·인게임 동작을 확인했다고 말할 수 없다.** 이후 다른 채팅이 서버를 실행·배포했을 수 있으므로 작업 직전에 다시 확인한다.

## 3. 작업별 바로가기

| 작업 | 시작 문서 |
|---|---|
| 대화방·다중 신·채팅 공개 범위 | [대화방 계약](../docs/CONVERSATION_ROOMS.md) → [전체 방 통합 기록](../docs/FULL_ROOM_DIALOGUE_INTEGRATION_20260923.md) |
| 기억·지식 공개·청취 이력 | [신별 청취 기억](../docs/ROOM_HEARD_MEMORY_20260923.md), [AI 기억/RAG 가이드](../mythai-ai-response/AI_MEMORY_RAG_GUIDE.md) |
| 게임 ↔ AI 제안·실행 경계 | [AI 행동 연동](../mythictrpg-main/docs/AI_ACTION_INTEGRATION_GUIDE.md)와 [루트 권한 경계](../AGENTS.md#모듈-간-권한-경계) |
| 신 프로필·정적 로어·퀘스트 목록 | [콘텐츠 작성 가이드](../mythai-ai-content-registry/AI_CONTENT_REGISTRY_CONTENT_AUTHORING_GUIDE.md), [프로필](../mythai-ai-content-registry/NPC_PROFILE_AUTHORING_GUIDE.md), [퀘스트 목록](../mythai-ai-content-registry/QUEST_LIST_AUTHORING_GUIDE.md) |
| 퀘스트·보상·FTB 연결 | [FTB 연동](../mythictrpg-main/docs/FTB_QUESTS_INTEGRATION_GUIDE.md), [보상](../mythictrpg-main/docs/REWARD_SYSTEM_GUIDE.md) |
| 테스트 명령·서버 파일 | [명령 가이드](../docs/AI_DIALOGUE_TEST_COMMAND.md), [서버 안내](../server/README.md) — 먼저 현재 버전/명령을 소스로 대조 |
| 다른 상세 가이드·과거 보고서 | [문서 길잡이](../docs/README.md), [공용 이력 색인](HISTORY_INDEX.md) |

## 4. 여러 채팅에서 작업할 때

- 각 채팅은 자기 수정 범위와 현재 브랜치를 확인한다. 기존 미커밋 파일은 다른 작업자의 것으로 간주하고, 겹치는 변경을 덮어쓰지 않는다.
- 모듈 계약을 바꾸면 생산자·소비자·예제·가이드를 함께 확인한다. 다른 채팅에 자동 전파된다고 가정하지 않는다.
- 구현 완료 보고에는 **수정 위치, 수행한 검증, 서버에 설치했는지, 실제 부팅/LLM/인게임 확인 여부, 남은 일**을 별도로 적는다. 검증하지 않은 항목은 미확인으로 남긴다.
- 실험 브랜치 작업은 해당 브랜치 기록에 적고, 사용자 승인 없는 공용 기준 전환이나 운영 완료 선언을 하지 않는다. 공용 문서는 공용 결정·병합 시에만 갱신한다.
- 이 문서는 길찾기용이다. 동시 작업자가 자주 덮어쓰는 실시간 진행표로 사용하지 않는다. 새 채팅은 자신의 작업 종료 시 관련 상세 가이드/브랜치 기록을 갱신한다.
