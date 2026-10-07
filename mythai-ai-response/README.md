# MythAI Local AI Response

> **대화 의미·실행 근거 개발 조합(2026-10-07, HanesTest·미배포):** AI **0.1.30** / 게임 **1.0.27 이상**. 발화 주체·대상 해석, typed 게임 결과, 선택적 초안 검토·최대 1회 수정이 추가됐다. [계약·검증·남은 한계](../인수인계/브랜치실험/HanesTest/DIALOGUE_GROUNDING_20261007.md)를 따른다. 실 Gemma 합성 평가와 모의 LLM 개발 GameTest를 수행했으며 운영 인게임 검증은 하지 않았다.

> **퀘스트 재편성 개발 조합(2026-10-02, 미배포):** AI **0.1.27** / 게임 **1.0.24 이상** / 콘텐츠 **0.1.3**, protocol 10. `quest_roster_request`는 현재 화자의 게임 제공 목록에 있는 `quest_id`로 관리 메뉴만 연다. 동의·참가자 변경·제출품 반환은 게임 소유다. [게임/AI 계약과 설정](../mythictrpg-main/docs/QUEST_REORGANIZATION_GUIDE.md)을 따른다. 실제 모델 대화·운영 배포는 미확인이다.

> **생활활동 후속 개발 조합(2026-09-30, 미배포):** AI **0.1.26**는 게임 **1.0.23 이상**의 새 활동 API를 사용한다. 콘텐츠 **0.1.3**, 게임 protocol **10**. [활동 AI 계약](NPC_ACTIVITY_AI_GUIDE.md)과 [게임 실행 계약](../mythictrpg-main/docs/NPC_ACTIVITY_SYSTEM.md)을 함께 읽는다. 아래 이전 버전 배너는 당시 이력이며 현재 조합이 아니다.

> **시점 주의:** 아래 `최신` 표시는 2026-09-14 당시의 배포 이력이다. 현재 개발·설치·검증 범위는 [작업 시작 안내](../인수인계/START_HERE.md), 해당 브랜치 기록, Gradle 입력과 `server/mods`를 대조한다.

> **HanesTest 개발 조합(2026-09-30, 미배포):** AI `0.1.24` / 게임 `1.0.21` / 콘텐츠 `0.1.3`. AI의 실제 최소 게임 의존성은 `1.0.21`이다. 아래 LP 시점의 의존성·파일명은 현재 조합의 설치 안내가 아니다. [최신 작업표](../인수인계/브랜치실험/HanesTest/SYSTEM_COMPLETION_TRACKER.md), [기록 v2 권한 조회·SHADOW](../인수인계/브랜치실험/HanesTest/RECORDING_V2_AUTHORIZED_READ_20260930.md), [기존 기억·평판 재사용](../인수인계/브랜치실험/HanesTest/MEMORY_REPUTATION_REUSE_20260929.md), [건축물 방문 계약](../mythictrpg-main/docs/GOD_HOME_VISITS.md)을 따른다.

> 2026-09-14 최신: 회상 개선본 **AI 0.1.3**을 배치했다. 게임/클라이언트 **1.0.2**, 콘텐츠 0.1.0, PERSONAL은 유지한다. LP 원본과 수정 직전 상태를 보존했고 빌드/오프라인 710개 검사를 통과했다. 서버는 실행하지 않았으며 실제 모델 비교는 Ollama 미실행으로 연결 실패했다. [회상 개선 기록](../docs/MEMORY_RECALL_TUNING_20260914.md)과 [서버 시험 안내](../docs/LP_PERSONAL_MEMORY_TEST_SETUP.md)를 따른다.

새 MythicTRPG와 정적 콘텐츠 레지스트리 사이에서 로컬 LLM 응답을 생성하는 별도 NeoForge 모드다.

```text
MythicTRPG interaction/chat
  -> GodAiDialogueService compatibility API
  -> MythAI Content Registry profile/lore/examples
  -> stage 1 intent/tone classification
  -> stage 2 contextual dialogue generation
  -> MythicTRPG AiActionGateway validation/execution
  -> MythicTRPG Dialogue HUD
```

현재 HanesTest 브랜치는 AI 엔진을 `src/engine/java`의 소유 소스로 이관하여 `src/main/java`와 함께 직접 빌드한다. `mine/mine` 소스·결합형 JAR을 빌드 때 읽지 않는다. 이전 overlay Gradle 파일은 과거 구현 기록이며 더 이상 실행하지 않는다. 이관/검증 상태는 [작업표](../인수인계/브랜치실험/HanesTest/SYSTEM_COMPLETION_TRACKER.md)를 따른다.

## 빌드

```powershell
cd C:\Users\ADMIN\Desktop\markmar
.\dev-tools\run-mythictrpg-gradle.cmd jar memoryFoundationTest
.\dev-tools\run-mythictrpg-gradle.cmd -p C:\Users\ADMIN\Desktop\markmar\mythai-ai-response build --no-daemon
.\dev-tools\run-mythictrpg-gradle.cmd -p C:\Users\ADMIN\Desktop\markmar\mythai-ai-response memoryFoundationTest
```

결과물: `build/libs/mythai_ai_response-0.1.3.jar`

현재 빌드 입력 버전은 `build.gradle`의 `mythicTrpgApiJar`에서 확인한다. `verifyEngineOwnership`과 `verifyEnginePackage`는 `check`/빌드에 연결되어 구 체크아웃 의존과 게임 클래스 중복을 거부한다. 기억 연결도 이제 Java 소스에서 수정하며 게임의 `ai.memorycontract`와 AI의 `ai.memory` 패키지는 합치지 않는다. 아래 과거 LP 안내와 옛 테스트 스크립트는 당시 버전 기준이므로 새 독립 빌드 판정을 대신하지 않는다.

## 런타임 의존성

- `mythictrpg` 1.0.2 이상
- `mythaiaicontent` 0.1.0 이상
- Ollama `http://127.0.0.1:11434/api/chat`
- 기본 테스트 모델 `gemma4:12b`

현재 LP 호환 facade는 플레이어 1명 + 신 1명 세션을 지원한다. 테스트 Adapter의 참가자 설정 API와 운영 facade의 지원 범위는 다르다. Proposal은 `MythicAiActionDispatcher`를 거쳐 MythicTRPG의 공통 `AiActionGateway`로만 전달한다. 실제 허용 액션은 현재 게임 측 Definition과 Capability를 기준으로 하며, 이 README의 과거 지원 목록을 기준으로 판단하지 않는다.

## 현재 대화 정책

- 복합 문장에서는 퀘스트·도움·아이템·가호·보상 요청을 단순 감정/잡담보다 우선 분류한다.
- 콘텐츠 레지스트리의 퀘스트 목록과 게임의 현재 수주 가능 여부를 바탕으로 후보를 제공한다. 임의 생성된 ID는 승인하지 않는다.
- `PLAY_ACTIVITY`는 끝말잇기 전용 상태가 아니라 놀이 이름을 보관하는 범용 세션 문맥이다.
- 여러 문장 응답은 삭제하지 않고 문장 단위 HUD 메시지로 나눠 기존 FIFO 재생 대기열에 보낸다.
- 예시대화는 1단계 intent classifier에만 사용한다. 2단계 생성은 프로필·관계·상황 지침을 사용한다.

## 중단된 OPELA/RAG 실험 이력

2026-09-11 추가했던 Hybrid RAG, 일시 감정, 품질 평가, 출력 스키마 튜닝은 현재 소스·빌드에 포함되지 않는다. LP 기존 분류·생성, 세션 문맥, 게임 검증 경로에 선택적 로컬 기억 참고만 연결했다.

[AI_MEMORY_RAG_GUIDE.md](AI_MEMORY_RAG_GUIDE.md)는 보관용 실험 설명이다. 실험 소스·설정·기존 빌드 출력은 `server/backups/before-LP-source-restore-20260912/`에 남겼다. 별도 요청 없이 실험 코드를 재적용하지 않는다.
