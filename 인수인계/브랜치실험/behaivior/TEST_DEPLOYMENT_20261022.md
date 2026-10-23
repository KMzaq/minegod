# 2026-10-22 — 사용자 승인 최신 개발본 테스트 배포

## 승인과 범위

사용자는 최신 버전 적용을 요청했고, AI 0.1.50의 대사 품질 미통과와 기존 배포 금지 기록을 안내받은 뒤 **알려진 문제를 감수하고 직접 시험하기 위한 배포**를 명시적으로 승인했다. 이 배포는 테스트 예외이며 기존 의미 품질 실패를 PASS로 변경하거나 전체 시스템의 운영 적합성을 승인한 것이 아니다. 후속 정식 배포의 품질 게이트도 자동 면제하지 않는다.

현재 `behaivior` 작업본으로 세 모듈을 빌드하고 `server/mods` 및 `server/client-required-mods`에 적용했다. 다른 채팅의 소스 변경은 되돌리지 않았고, 이번 작업에서 게임/AI 기능 코드는 수정하지 않았다. 모델, 프로필, 데이터팩, 기록/관찰/목적 기능의 opt-in 설정을 임의로 변경하지 않았다.

## 배포 파일

| 모듈 | 변경 | SHA-256 |
|---|---|---|
| 게임 | 1.0.28 → 1.0.36 | `0F26620F338925BA9BD1232ACB5C625E4376B64207916A9CE23B166C5459FB14` |
| AI 응답 | 0.1.19 → 0.1.50 | `9BA722021164888214081229A07DC0AD6D60995AE62235B81E4021E9091423A5` |
| 콘텐츠 레지스트리 | 0.1.2 → 0.1.3 | `FE759A1D98DDC230BFD9A952EE23D0F7ABD609B12D43E755CFC017E3C5429FF2` |

게임 JAR은 서버와 클라이언트 배포 폴더 양쪽을 같은 해시로 맞췄다. AI/콘텐츠 JAR은 서버에만 설치했다. Architectury 및 FTB 4종은 기존 파일을 유지했다. 현재 게임의 Dialogue protocol은 11, 이해 화면 채널은 `understanding-2`이므로 실제 런처에서도 게임 JAR을 교체해야 한다. 같은 `1.0.36`이라는 파일명이라도 이전 산출물과 동일하다고 가정하지 말고 위 해시를 기준으로 한다.

## 이번에 실행한 검증

루트 실행기 `dev-tools/run-mythictrpg-gradle.cmd`로 다음을 수행했다.

- 게임: `build --offline --no-daemon --max-workers=1` — exit 0, BUILD SUCCESSFUL, 81 tasks(73 executed/8 up-to-date), 4분 45초.
- AI: `-p ../mythai-ai-response build --offline --no-daemon --max-workers=1` — exit 0, BUILD SUCCESSFUL, 74 tasks(69 executed/5 up-to-date), 1분 59초.
- 콘텐츠: `-p ../mythai-ai-content-registry build --offline --no-daemon --max-workers=1` — exit 0, BUILD SUCCESSFUL, 11 tasks(3 executed/8 up-to-date), 11초.
- AI `verifyEngineOwnership` 및 `verifyEnginePackage` 통과: AI 소유 551 classes, 게임 클래스 중복 0.
- 명령 라우팅, 대화방/청중, 저장·기억, 세션/턴, Proposal 및 스키마 등의 build/check 연결 오프라인 검사가 통과했다. 일부 오류 로그는 손상 자료를 거절하는 음성 테스트이며 해당 task는 PASS다. Windows 심볼릭 링크 생성이 불가능해 관련 assertion 1개는 미실행이다.
- 배포 스크립트의 PowerShell 구문 확인, 배포 JAR metadata 및 SHA-256, 서버/클라이언트 의존성 파일 일치 검사를 수행했다.

이는 새 실제 LLM 대사 품질 검사나 전체 GameTest 재실행 결과가 아니다. 운영 서버 부팅, 클라이언트/HUD/신규 화면, 저장 자료의 실제 부팅 시 이관은 이번에 검증하지 않았다.

## 백업과 보존

- 백업: `server/backups/test-latest-20261022-015346-541/`
- 원본 파일 575개, 90,579,303 bytes. `backup-manifest.csv`에 상대경로/크기/SHA-256을 저장했다.
- 백업 범위: 월드(플레이어·퀘스트·관계·기억·시험 대화 포함), 설정, 기존 서버/클라이언트 JAR, 존재하는 관련 데이터 폴더와 서버 루트 주요 파일.
- 교체 전 CIM 프로세스, 25565 listen 및 `world/session.lock`의 배타 접근을 확인했다. 서버가 실행 중이지 않은 상태에서만 변경했다.
- 구 JAR 4개는 삭제하지 않고 백업의 `retired/`로 이동했다. 백업 사본도 별도로 있다.
- 의도적으로 교체한 구 JAR 이외의 보존 파일은 교체 직후 해시가 동일했다. README는 그 후 배포 안내를 위해 수정했다. 월드·설정·모델·기억 자료는 변경하지 않았다.
- 안전 점검/복사/해시 검증과 실패 시 원복을 수행하는 실행 기록용 스크립트: [Deploy-TestLatest-20261022.ps1](../../../dev-tools/Deploy-TestLatest-20261022.ps1). 이 스크립트는 특정 구 버전 상태를 요구하므로 재배포 일반 도구가 아니다.

새 버전으로 월드를 연 뒤에는 저장 버전/enum/권리 데이터가 바뀔 수 있다. 구 JAR만 되돌리는 방식의 호환성을 보장하지 않는다. 복원이 필요하면 서버를 정상 종료한 후 이 백업의 대응 월드·설정·JAR 전체를 기준으로 별도 복원 작업을 진행한다. 이후 플레이 내용을 덮을 수 있으므로 자동 복원하지 않는다.

## 직접 시험

1. 실제 런처의 이전 게임 JAR을 모드 폴더 밖에 보관하고 [클라이언트 필수 폴더](../../../server/client-required-mods/README.md)의 게임 1.0.36과 FTB/Architectury 조합을 적용한다. 서버 전용 AI/콘텐츠 모드는 넣지 않는다.
2. Ollama의 기존 `gemma4:12b`를 준비하고 `server/start-neoforge-ai-server.bat`을 실행한다.
3. 새 로그의 모드 버전·리소스 로드 오류 및 `Done`을 확인한 뒤 접속한다.
4. OP가 `/ai_call private on mythictrpg:demeter`로 시작하고 `/s 안녕하세요`로 시험한다. 등록된 신 ID는 Tab 자동완성을 사용한다. 종료는 `/mythroom leave <방코드>`다.

**현재 배포 종료 상태:** 서버 미부팅/정지 상태 유지, 실제 런처 변경 없음, 새 실제 LLM 호출 없음, 실제 인게임 검증 없음. 최근 개발 기능 중 콘텐츠 정의나 명시적 opt-in을 요구하는 기능은 JAR 교체만으로 자동 활성화되지 않는다.
