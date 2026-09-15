# MythAI Local AI Response

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

현재 구현은 이전 결합형 JAR에서 실제 Ollama 테스트에 사용한 AI 패키지만 빌드 시 추출한다. 최종 JAR에는 이전 `MythicTrpg` 게임 클래스나 `mythictrpg` 모드 정의가 포함되지 않는다.

## 빌드

```powershell
cd C:\Users\ADMIN\Desktop\markmar
.\dev-tools\run-mythictrpg-gradle.cmd jar memoryFoundationTest
.\dev-tools\run-mythictrpg-gradle.cmd -p C:\Users\ADMIN\Desktop\markmar\mythai-ai-response build --no-daemon
.\dev-tools\run-mythictrpg-gradle.cmd -p C:\Users\ADMIN\Desktop\markmar\mythai-ai-response memoryFoundationTest
```

결과물: `build/libs/mythai_ai_response-0.1.3.jar`

`dev-tools/Test-MemoryFoundationRelease.ps1 -ServerProfile PERSONAL`로 LP 백업·현재 배포/개발 JAR 및 패키지 분리를 검사한다. 기본 LP profile은 LP 상태 검사용이다. `Test-LPBuild.ps1` 기본 실행은 새 응답 모듈에 `NOT LP`가 정상이다. 기존 `mine/mine` AI 추출은 유지하며 게임 계약 입력은 `mythictrpg-main/build/libs/mythictrpg-1.0.2.jar`다. 기억 연결은 Java helper와 `memory-foundation-overlay.gradle`에서 관리한다. 게임의 `ai.memorycontract`와 기존 AI의 `ai.memory` 패키지를 합치지 않는다.

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
