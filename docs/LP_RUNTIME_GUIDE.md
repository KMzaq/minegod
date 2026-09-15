# 현재 AI 서버 운영 기준 — 개인 기억 시험

> 2026-09-14 최신: 회상 개선본 **AI 0.1.3**을 배치했다. 게임/클라이언트 **1.0.2**, 콘텐츠 0.1.0, PERSONAL은 유지한다. LP 원본과 수정 직전 상태를 보존했고 빌드/오프라인 710개 검사를 통과했다. 서버는 실행하지 않았으며 실제 모델 비교는 Ollama 미실행으로 연결 실패했다. [회상 개선 기록](MEMORY_RECALL_TUNING_20260914.md)과 [서버 시험 안내](LP_PERSONAL_MEMORY_TEST_SETUP.md)를 따른다.

9월 12일 LP 복원 당시 실부팅 기록은 과거 검증이다. 이번 배포 직전 서버가 실행 중이 아님을 확인했고 서버를 새로 실행·중지하지 않았다. 이번 새 JAR 조합의 실제 부팅/인게임 검증은 아직 하지 않았다.

## 적용 파일과 설정

| 항목 | 현재 기준 |
|---|---|
| 서버 AI 응답 | `mythai_ai_response-0.1.3.jar` / SHA-256 `CC5547CB643D5A1354828850ECDA6F3425D6245B24B2CD766F5CE2E86DC10F80` |
| 서버 콘텐츠 | LP `mythaiaicontent-0.1.0.jar` / SHA-256 `38B8461C13619569E477BB24AB85F9BFAD317998402FEA3E5ACF0019C6CC8CBA` |
| 게임 모드 | MythicTRPG 1.0.2 / SHA-256 `32C70B972640EE534AE5FF09FDBBA5578C863197EE9B3F9EFD6CA62253226124`, 서버/클라이언트 배포용 동일 |
| 모델 | `gemma4:12b` / 기존 로컬 Ollama |
| 출력 토큰 한도 | 260 |
| 개인 장기기억 | 새 `ai-memory-foundation.json`, mode=`PERSONAL` |
| 과거 OPELA/RAG 확장 | v2 임베딩·일시 감정·품질 평가·튜닝 스키마는 미적용. 현재는 별도 경량 로컬 검색 기반 |

LP 복원 기준은 [보존 JAR과 해시](../server/backups/LP/README.md)다. 현재 새 AI는 게임 1.0.2 이상을 요구한다. 클라이언트용 게임 JAR도 1.0.2로 바꾸고 구버전을 함께 넣지 않는다. 서버 전용 AI 두 JAR은 클라이언트에 넣지 않는다.

## 실행과 보존

Ollama를 준비한 뒤 기존 `server/start-neoforge-ai-server.bat`으로 실행한다. 종료는 콘솔 `stop`으로 한다. 복원 과정에서 월드·플레이어·기존 기억을 삭제하거나 되감지 않았다.

과거 `ai-memory.json`과 `mythictrpg-ai-memory/`는 보존하되 활성화/이관하지 않는다. 이번 설정은 별도 `ai-memory-foundation.json`이고 새 저장 경로는 월드의 `mythictrpg-ai-memory-lp-v1/`다. 임베딩 모델을 설치/제거/호출하지 않았다.

튜닝본 복원 직전 백업은 `server/backups/before-LP-restore-20260912-170306-827/`이며, 이전 튜닝 소스·결과 묶음은 `server/backups/dialogue-tuned-20260911/`에 있다.

## 개발·문서 기준

9월 12일 소스를 LP로 복원한 뒤 승인된 기반 기능을 추가했다. 현재 소스/배포 모두 게임 1.0.2 / AI 응답 0.1.3이며 LP와 다르다. 콘텐츠는 LP 0.1.0이다. 코드 기본값은 OFF지만 현재 테스트 서버에는 PERSONAL을 명시했다. 소문 시험/자동 전파는 활성화하지 않았다.

`dev-tools/Test-LPBuild.ps1` 기본 실행은 새 AI에 대해 `NOT LP`가 정상이다. 현재 배포는 `dev-tools/Test-MemoryFoundationRelease.ps1 -ServerProfile PERSONAL`로 읽기 전용 검사한다. 이 검사는 LP 보존본·새 배포/설정·변경하지 않은 의존성을 구분한다. `Restore-LP.ps1`을 현재 새 게임/버전 변경의 전체 복원 도구로 사용하지 않는다.

최신 배포 기준은 [인수인계](../인수인계/PROJECT_HANDOFF.md) 31절과 [개인 기억 테스트 안내](LP_PERSONAL_MEMORY_TEST_SETUP.md)다. 구현 상세는 [기반 가이드](LP_MEMORY_FOUNDATION_GUIDE.md), LP 재빌드의 과거 검증은 [소스 복원 기록](LP_SOURCE_RESTORE.md)으로 구분한다. LP 원본·298개 소스·906개 전체 소스·배포 직전 29개 및 오류 수정 직전 673개 파일 백업을 보존한다. 과거 [튜닝 보고서](LP_DIALOGUE_TUNING_REPORT.md)와 [RAG 가이드](../mythai-ai-response/AI_MEMORY_RAG_GUIDE.md)의 사양을 이번 구현에 적용하지 않는다.
