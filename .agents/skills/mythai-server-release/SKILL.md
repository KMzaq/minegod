---
name: mythai-server-release
description: "markmar NeoForge 모드를 빌드하거나 테스트 서버를 세팅·배포하고 서버/클라이언트 JAR을 맞춘다. 빌드 입력, 런타임 의존성, 백업, 서버 로드 확인에 사용한다. 단순 상태 질문은 읽기 전용으로 처리하며 스킬 선택 자체는 서버 실행·중지·교체 권한을 주지 않는다."
---

# MythAI 테스트 서버 릴리스

[루트 지침](../../../AGENTS.md)과 [인수인계](../../../인수인계/PROJECT_HANDOFF.md)의 관련 최신 기록을 읽는다. 과거 성공한 테스트 수나 JAR 해시를 현재 결과로 재사용하지 않는다.

## 준비와 범위

- Minecraft/NeoForge/Java는 `gradle.properties`, 빌드 파일, 서버 설치 파일에서 확인한다. 현재 문서 기준선은 1.21.1 / 21.1.248 / Java 21이지만 실제 입력을 우선한다.
- 상태 확인 요청이면 프로세스·포트·로그·파일을 읽기만 한다. 빌드 요청은 실행 서버 교체를 자동으로 허용하지 않는다. 배포 전 대상 폴더와 서버 실행 여부를 확인한다.
- 게임 모드는 `mythictrpg-main`, AI 응답은 `mythai-ai-response`, 콘텐츠는 `mythai-ai-content-registry`, 수정 FTB는 `ftb-quests`다. 기존 사용자 변경을 보존한다.

## 빌드

루트에서 사용하는 기존 실행기는 `dev-tools/run-mythictrpg-gradle.cmd`다. Java 경로를 먼저 확인하고 PowerShell에서는 `.cmd`/`.bat`를 사용한다. 시스템 실행 정책을 바꾸지 않는다.

```powershell
.\dev-tools\run-mythictrpg-gradle.cmd compileJava --no-daemon
.\dev-tools\run-mythictrpg-gradle.cmd build --no-daemon
.\dev-tools\run-mythictrpg-gradle.cmd runGameTestServer --no-daemon
```

위 세 개를 매번 모두 실행하라는 뜻은 아니다. 필요한 범위만 선택한다. 다른 모듈은 같은 wrapper에 `-p`로 명시적 프로젝트 경로를 전달할 수 있다. FTB는 자체 wrapper의 `:neoforge:build`를 사용한다. 생성 파일을 보존할 필요가 없는지 확인하지 않고 `clean`을 습관적으로 붙이지 않는다.

AI 응답의 실제 `build.gradle` 입력을 먼저 확인한다. 현재 브랜치는 현재 게임 API JAR과 모듈 소유 `src/main/java`/`src/engine/java`로 빌드하며 이전 overlay Gradle을 실행하지 않는다. 게임 API JAR을 먼저 맞추고 `verifyEngineOwnership`/`verifyEnginePackage`로 구 체크아웃 의존과 클래스 중복을 검사한다. 다른 브랜치의 legacy 입력 유무는 재확인한다. 기존 결합형 JAR에는 게임도 들어 있으므로 최종 서버에 넣지 않는다.

## 배포

1. 실행 서버라면 사용자 작업 영향을 확인하고 정상 `stop`/종료를 기다린다. 권한이나 종료 수단이 없으면 중단하고 요청한다. 모든 Java 프로세스를 일괄 종료하지 않는다.
2. 교체 대상 기존 파일을 서버 `backups`의 구분되는 폴더에 보존한다. 월드·SavedData·플레이어 로그를 초기화하지 않는다. 파일명만 같다고 같은 빌드라고 가정하지 않는다.
3. 서버에는 `server/mods`를 사용한다. MythicTRPG·수정 FTB 등 양쪽 필수 JAR은 `server/client-required-mods`에도 맞추고 SHA-256으로 확인한다. 이 폴더에 복사한 것과 플레이어의 실제 런처에 설치한 것은 다르다.
4. AI 응답/콘텐츠 레지스트리는 현재 서버 전용이다. 모드 metadata로 다시 확인하며, 서버 전용 파일을 클라이언트에 무조건 복사하지 않는다. 중복 mod ID나 구버전 중복 JAR이 없어야 한다.
5. FTB 팩/Datapack/설정 배포도 변경분만 대조한다. 사용자 수정본 위에 전체 폴더를 무조건 덮어쓰지 않는다. `world/datapacks`의 리소스가 JAR을 덮을 수 있다.

## 실행과 검증

일반 테스트 서버 실행기는 `server/start-neoforge-ai-server.bat`다. Gradle `runServer`를 평범한 배포 서버 콘솔 대신 안내하지 않는다. Ollama 모델/URL은 `server/config/mythictrpg/ai-dialogue.json`에서 확인하고 사용자 요청 없이 모델을 교체하지 않는다.

실행까지 요청된 경우에만 부팅하고 최신 실행 로그의 모드·리소스 오류와 `Done`을 확인한다. 부팅 성공은 대화·UI 성공이 아니다. 요청된 검증에 따라 1회 실제 대화/Proposal과 HUD, 보상 중복 방지 등을 별도로 확인한다. 접속/GUI 검증이 불가능하면 그 부분을 사용자 수동 확인으로 남긴다.

최종 보고에는 빌드/배포한 파일, 서버·클라이언트 일치 여부, 실제 수행한 테스트, 서버 실행/종료 상태, 미확인 항목, 백업 위치를 포함한다. 관련 인수인계와 변경한 계약 문서를 갱신한다.
