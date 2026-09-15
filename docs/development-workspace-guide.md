# MythicTRPG 개발 작업공간 안내

## 폴더 역할

| 경로 | 역할 | 수정 여부 |
|---|---|---|
| `incoming/mythictrpg-upstream` | 다른 개발자에게 받은 원본과 인수인계 문서 보존 | 수정 금지 |
| `mythictrpg-main` | 앞으로 MythicTRPG를 개발하고 AI 계약을 통합할 작업본 | 여기서 작업 |
| `mythai-ai-response` | Ollama 호출, 1·2단계 처리, 대화 상태, AI Proposal 생성 | AI 응답 기능만 수정 |
| `mythai-ai-content-registry` | 신 프로필, 지식, 대화 스타일 등 정적 AI 콘텐츠 | 콘텐츠와 조회 계약만 수정 |
| `server` | 실제 통합 테스트 서버 | 검증된 JAR만 배포 |
| `external-mods` | 앞으로 추가할 외부 모드의 설치 범주별 보관 위치 | JAR 분류용 |
| `docs` | 통합 상태, 버전표, 모드 목록과 계약 문서 | 계속 갱신 |

## 개발 기준선

- Minecraft: 1.21.1
- NeoForge: 21.1.248
- Java: 21
- MythicTRPG 개발 브랜치: `integration/ai`
- 인수 시점 태그: `upstream-handoff-2026-08-25`
- 인수 시점 검증: `clean build` 성공, 필수 GameTest 158/158 통과

## 빌드 명령

PowerShell 실행 정책과 프로젝트에 기록된 이전 PC의 Java 경로에 영향받지 않도록 CMD 실행기를 사용한다.

```bat
cd /d C:\Users\ADMIN\Desktop\markmar
dev-tools\run-mythictrpg-gradle.cmd clean build --no-daemon
```

GameTest 기준 검증:

```bat
cd /d C:\Users\ADMIN\Desktop\markmar
dev-tools\run-mythictrpg-gradle.cmd runGameTestServer --no-daemon
```

빌드 결과는 `mythictrpg-main/build/libs/mythictrpg-1.0.0.jar`에 생성된다.

## 서버 배포 원칙

현재 `server`는 기존 `mine2` 기반 자유 대화 테스트 통합을 사용하고 있다. 새 upstream 개발본에는 정식 spontaneous provider 경계가 있지만 아직 `mythai-ai-response` 연결과 자유 채팅 계약 이식이 끝나지 않았다. 따라서 지금 생성된 새 MythicTRPG JAR을 `server/mods`에 덮어쓰지 않는다.

다음 조건을 모두 만족한 뒤 교체한다.

1. upstream resolver에 AI provider를 정식 등록한다.
2. 자유 채팅용 bounded DTO 계약을 구현한다.
3. 콘텐츠 레지스트리 God ID를 production God과 맞춘다.
4. fake provider, Ollama dry-run, HUD 출력 순서로 확인한다.
5. 전체 GameTest와 실제 서버 단일 대화 스모크 테스트를 통과한다.

## 외부 모드 추가 원칙

외부 모드 JAR은 바로 `server/mods`에 섞지 말고 먼저 다음 중 하나로 분류한다.

- `external-mods/server-only`: 클라이언트 설치가 필요 없는 서버 모드
- `external-mods/client-required`: 서버와 모든 플레이어에게 같은 파일이 필요한 모드
- `external-mods/optional-client`: 선택적인 클라이언트 편의 모드

추가할 때마다 `docs/mod-list.md`에 파일명, 버전, 의존성, 서버·클라이언트 설치 범위를 기록한다. 한 번에 여러 모드를 넣지 말고 소규모 묶음 단위로 서버 부팅을 확인해야 충돌 원인을 분리할 수 있다.

## 다음 작업 진입점

다음 개발 단계는 `mine2` 코드를 새 프로젝트에 통째로 복사하는 작업이 아니다. upstream의 새 resolver/preparer 경계를 유지한 상태에서 `mythai-ai-response`를 provider로 연결하고, 자유 대화용 Game → AI snapshot과 AI → Game result/proposal 계약을 이식하는 작업이다. 세부 상태는 `docs/integration-status.md`를 기준으로 한다.
