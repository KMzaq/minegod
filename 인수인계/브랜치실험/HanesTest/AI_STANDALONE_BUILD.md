# AI 엔진 소스 독립 빌드

2026-09-29 / HanesTest. 운영 배포와 별개인 개발 변경이다.

## 변경

`mythai-ai-response/src/engine/java`에 기존 JAR에 포함하던 AI 패키지의 최상위 Java 소스 156개를 이관했다.
`AiTestDialogueAdapter`, `DialogueTurnDirective`, `LocalLlmRequestScheduler` 3개는 직전 빌드의 모든 오버레이를 적용한 최종 소스를 이관했다.
따라서 새로운 수정은 `src/main/java` 또는 `src/engine/java`에서 수행한다. 생성된 파일을 편집하거나 오버레이 문자열 패치를 추가하지 않는다.

- `mine/mine` 원본/결합 JAR은 수정·삭제하지 않았다. `mine2`도 보존한다.
- 결합 JAR에서 GameTests·구 명령/이벤트·구 GodAiDialogueService는 가져오지 않았다.
- 현재 AI 모듈의 호환 facade/명령/이벤트/방 엔진은 기존 `src/main/java` 구현을 유지한다.
- `build.gradle`의 결합 JAR compileOnly/ZIP 추출과 오버레이 생성 작업을 제거했다.
- 이전 `*-overlay.gradle`은 더 이상 빌드에 적용하지 않는 과거 구현 자료다. 활성 소스로 오인하지 않는다.
- 현재 게임 API JAR 의존성은 의도적으로 유지한다. 이는 구 AI 구현 JAR 의존성과 다르다.

## 자동 방어

- `verifyEngineOwnership`: 실제 소스/compileClasspath에 `/mine/mine/`·`/mine2/` 입력이 있으면 실패한다.
- `verifyEnginePackage`: JAR의 클래스가 현재 컴파일 산출물과 정확히 같은지, 게임 API JAR과 클래스가 중복되지 않는지 검사한다.
- 두 검사를 `compileJava`/`check`에 연결했다.

## 이관 직후 비교

- 선택 소스 156개를 복사한 직후 각 입력/출력의 SHA-256이 일치함을 확인했다.
- 최종 생성 소스 3개의 직접 컴파일을 포함하여 독립 `compileJava --offline --no-daemon` 성공.
- 오버레이로 교체하던 클래스와 제외 대상을 빼고, 기존 결합 JAR의 **232개 클래스가 새 직접 컴파일 결과와 바이트 단위로 일치**했다. 누락/차이는 0개였다.
- 이 비교는 이관 직후 결과다. 이후 추가되는 감정 등 신규 기능 변경까지 기존 바이트와 같아야 한다는 뜻은 아니다.
- 후속 감정/레이드 정규화 포함 AI `build --offline --no-daemon --max-workers=1` **36 tasks 성공**, 최종 소유 클래스410개·게임 중복0을 확인했다. 이후 진행 중인 사회/기록 연결 패치는 작업표에서 검증 상태를 별도로 확인한다. 위 검사를 운영 대화 확인으로 해석하지 않는다.
- 건축물 방문·사회 연결을 포함한 AI `0.1.22`의 후속 전체 build는 **37 tasks 성공**, 소유 클래스 **413개·게임 중복0**이다. 게임 API 입력/최소 런타임 의존성은 `1.0.19`로 맞췄다. 현재 감정58·방문24 검사를 포함하며, 세 모듈 실제 로드 대화 GameTest **1/1**도 `build/home-social-integration-20260929`에서 통과했다. LLM transport는 모의했다.

## 재현

루트의 기존 Gradle wrapper를 사용한다. `build.gradle`에서 가리키는 현재 게임 API JAR을 먼저 만든다.

```powershell
.\dev-tools\run-mythictrpg-gradle.cmd assemble --offline --no-daemon
.\dev-tools\run-mythictrpg-gradle.cmd -p ..\mythai-ai-response build --offline --no-daemon
```

서버/클라이언트 JAR·월드·운영 설정은 이 작업으로 교체하지 않는다. 서버에는 구 결합형 JAR을 넣지 않는다.
