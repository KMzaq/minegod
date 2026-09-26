# 대화방 기록·기억 연결 빌드 및 서버 적용

2026-09-21 / JAR 배포 완료 / 서버 정지 유지·부팅/인게임/실제 LLM 미검증.

사용자 요청에 따라 [연결 보완](ROOM_RECORDING_INTEGRATION_20260921.md)의 게임·AI 응답 두 모듈을 다시 빌드하고 `server`에 적용했다. `mythai-server-release` 절차로 실행 상태·빌드 입력·백업·해시·모드 조합을 확인했다. 콘텐츠 프로필, 모델, 서버 설정, 월드 정책을 새로 변경하지 않았다.

## 적용 파일

| 위치 | 현재 파일 | 이전 파일 |
|---|---|---|
| `server/mods` | `mythictrpg-1.0.13.jar` | `mythictrpg-1.0.2.jar` |
| `server/mods` | `mythai_ai_response-0.1.14.jar` | `mythai_ai_response-0.1.3.jar` |
| `server/client-required-mods` | `mythictrpg-1.0.13.jar` | `mythictrpg-1.0.2.jar` |

- 게임 SHA-256: `DADDEB4FAF1C7788B7FE78ADBDD502DB2973F40C346BD5926A4607EA58FB6881`. 서버·클라이언트 배포용·개발 JAR이 동일하다.
- AI SHA-256: `BF978F516C0497AFC68A60297A5CD248A9735B75AD8864E93A5549F10CE8D158`. 서버·개발 JAR이 동일하다.
- AI의 최소 게임 버전은 1.0.13, 게임 네트워크 protocol6이다. Minecraft1.21.1 / NeoForge21.1.248 / Java21 환경은 유지했다.
- Architectury13.0.11 / FTB Library2101.1.35 / FTB Teams2101.1.11 / 수정 FTB Quests2101.1.34 / 콘텐츠0.1.0은 기존 배포물을 유지했다. 별도 작업의 새 데메테르 프로필은 이번 배포에 포함하지 않았다.
- AI/콘텐츠 모드는 클라이언트 필수 폴더에 추가하지 않았다. 실제 런처의 mods 폴더는 수정하지 않았으므로 사용자가 게임 JAR을 교체해야 한다.

## 이번에 수행한 검증

- 게임 `compileJava jar` 및 publication19/방147/Gateway45/동의32/HUD34 = 277 checks PASS.
- 동일 해시의 고정 게임 API 복사본으로 AI `compileJava jar`, 방90/청중46/기억기록20/기존 생성87+로그37/경험37/journal656 = 973 checks PASS.
- 합계 **1,250 checks를 이번 배포 직전에 재실행**했다. 변경 없는 compile/jar 입력은 Gradle UP-TO-DATE였고 출력 해시는 직전 검증본과 일치했다. 실제 서버·모델 시험 결과로 표시하지 않는다.
- 배포 후보 7JAR/246패키지에서 중복 ZIP 엔트리·중복 mod ID·split-package가 없었다. 배포 후 서버 JAR7개/클라이언트 JAR5개와 필수 JAR5개의 양쪽 해시 일치, 구버전 중복 부재를 확인했다.
- 프로세스와 서버 포트를 읽어 서버가 꺼져 있음을 배포 전 두 차례 확인했다. 서버를 실행/강제 종료하지 않았고 모델 API도 호출하지 않았다.

## 백업과 복구 범위

백업: [room-recording-release-20260921-223406](../server/backups/room-recording-release-20260921-223406/README.md).

- 배포 전 `mods`, `client-required-mods`, **world 전체**, `config`, `server.properties`의 541개 파일(51,274,663 bytes)을 복사하고 전부 SHA-256으로 원본과 대조했다. 월드는 478파일/23,110,605 bytes다.
- 기존 JAR 3개는 삭제하지 않고 백업 아래 `retired`에 추가 보관했다. 원래 위치 구조의 백업도 별도로 있다. 구 JAR이 활성 mods에 남아 중복 로딩되지 않도록 했다.
- 백업 기준은 `before-sha256.csv`다. 서버 라이브러리/운영체제/전체 작업공간까지 포함한 백업은 아니다.
- 배포 후 원본 537개(월드478/설정27 포함)가 그대로임을 재검증했다. 의도된 차이는 JAR3개 교체와 클라이언트 README뿐이며, world/config 파일 추가·삭제도 없었다. 백업541개와 retired 구 JAR3개의 해시도 재확인했다.
- 지금은 부팅 전이라 월드 마이그레이션이 발생하지 않았다. 향후 새 버전으로 플레이한 뒤에는 JAR만 내리거나 옛 world를 무조건 덮지 않는다. 새 진행도를 먼저 보존하고 저장 호환성을 확인해야 한다.

## 다음 사용 시 주의

클라이언트는 [필수 모드 안내](../server/client-required-mods/README.md)를 따라 구 MythicTRPG를 모드 폴더 밖에 보관하고 1.0.13으로 맞춘다. 서버 시작은 기존 [실행기](../server/start-neoforge-ai-server.bat)를 사용한다. 이번 요청은 파일 적용까지만 수행했으며 서버는 꺼진 상태다.

NeoForge 실제 부팅, 데이터 읽기/저장 마이그레이션, 여러 플레이어의 공개·비밀 노출, H/G/HUD, 실제 Ollama 대화/지연·부하 확인은 남아 있다. 신규 SQLite·100GB와 소문 후처리/자동 퀘스트 연출의 방별 이전도 이번 배포로 완성된 것이 아니다.
