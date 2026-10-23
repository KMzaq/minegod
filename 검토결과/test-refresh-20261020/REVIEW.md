# 오래된 검사 갱신과 전체 오프라인 재검증

2026-10-19 시작, 2026-10-20 후속. 브랜치 `behaivior`, 게임1.0.33 / AI0.1.45 / 콘텐츠0.1.3. 기존 미커밋 구현은 보존했다. 이 문서의 검증은 **현재 세 모듈의 Gradle build/check 및 발견된 전체 GameTest**를 뜻하며, 실제 LLM·그래픽 클라이언트·운영 서버 검증과 구분한다.

## 변경 범위

- `AiActionGameTests.registryAllowsOnlyDefinitionsOwnedByTheGame`: 방문 요청 미등록 및 총12개라는 오래된 기대값을 현재 게임 소유15개 액션의 명시적 집합과 비교하도록 수정했다. 단순 개수만 맞아 다른 액션이 섞이는 경우도 거절한다. 방문/활동/명단 요청의 확인 정책도 검사한다. 게임의 액션 등록·검증·실행 정책은 수정하지 않았다.
- `registeredVisitStillRequiresExactRoomAndGameEligibility`: 방 범위 누락, 세션/신 불일치, 게임 자격 부족, 모델의 방문 대상 주입을 검증기와 실행기가 거절하는 검사 추가. 모의 접속자는 `finally`에서 제거한다.
- `lifecycleCleanupIsRuntimeOnlyWithoutObservation`: 무접속 서버 및 서버 전역 상태 초기화가 필요한 기존 검사를 `mythictrpg_activity_lifecycle`이라는 별도 테스트 공간/프로세스로 옮겼다. 빈 서버 tick, 로그인/교체/부활/차원 이동/로그아웃, 관측 미발행 및 프로필 불변 **검사 조건은 그대로 유지**한다. 빈 구조 리소스를 개발 빌드에서 생성한다.
- `NativeContentEvidenceGameTests`: 전체 실행에서 발견한 추가 실패의 오류 메시지에 조회 상태·결과 수·시도 수만 추가했다. 성공 조건, 재시도 수 및 기능 구현은 변경하지 않았다.
- [전체 검사 실행기](../../dev-tools/Test-OfflineGameMatrix.ps1): 실제 `@GameTest.templateNamespace`와 검사 수를 소스에서 발견하고, 묶음마다 새 `build/` 월드와 프로세스를 사용한다. 종료 코드·완료 수·필수 검사 성공 문구를 모두 대조한다. 실패 이후에도 다른 묶음을 실행하고 전체 결과는 실패로 반환한다. 기존 결과 덮어쓰기, 운영 폴더 사용, 배포 및 LLM 호출은 하지 않는다.

## 재현 방법

프로젝트 루트 PowerShell에서 새 `RunId`로 실행한다. 기존 wrapper의 Java21과 Gradle 캐시가 필요하다.

```powershell
./dev-tools/Test-OfflineGameMatrix.ps1 -RunId offline-matrix-new-run
```

전체 실행은 게임→AI→콘텐츠 `build` 후 게임 namespace32개 및 AI 통합5개 묶음을 별도 프로세스에서 실행한다. 기본 공간만 실행하는 기존 GameTest 명령으로 이 전체 범위를 대체하지 않는다. `-ListOnly`는 실행 계획 확인, `-Only <묶음명>`은 부분 재검증이며 전체 통과를 뜻하지 않는다. `-SkipBuild`는 입력 JAR이 최신임을 별도 확인한 경우에만 사용한다.

- 레이드 공간에는 trial 차원용 개발 datapack 준비 조건을 함께 전달한다.
- 기록 채널은 `RECORD_ONLY/PERSONAL`, 소문은 `RECORD_ONLY/RUMOR_TEST`, Native 비교는 `SHADOW/PERSONAL`의 격리 설정을 준비한다.
- AI 통합은 현재 버전의 개별 AI/콘텐츠 JAR을 명시하며, 실제 엔진+모의 LLM 응답을 사용한다. 전체 방 경로는 legacy/shadow 두 조건을 검사한다. NEW 정책 검사는 계약 미완성 시 차단을 확인하는 것이지 운영 NEW 활성화가 아니다.

## 보존한 실패 이력

1. 이전 활동 구현의 `build/activity-support-20261019-v1`:199개 중 오래된 액션 미등록 기대값1개 실패. 해당 결과는 당시 실패로 유지한다.
2. 이번 최초 `build/registry-refresh-20261019/base`:200개 중 활동 수명주기1개 실패. 새 방문 검사에서 접속자를 정리하지 않은 문제를 수정했다.
3. `build/registry-refresh-20261019/base-v2`:정리 후에도 동일한1개 실패. 기존 지연 실행 검사들이 접속자를 유지하는 동안 같은 서버에서 무접속 조건을 단정한 것이 추가 원인이다. 운영 활동 감지의 정상 동작을 끄지 않고, 해당 전역 상태 검사를 별도 프로세스에서 실행하도록 수정했다.

## 최종 실행 결과

전체 실행 위치: `mythictrpg-main/build/registry-refresh-matrix-20261019/`. `inventory.json`, `results.json`, 각 `*.gradle.log` 및 묶음별 `logs/latest.log`를 함께 확인한다. **전체 실행 exit1, 전체 gate 미통과**다. 실패 항목을 제외하고 재집계하지 않았다.

| 범위 | 결과 |
|---|---|
| 게임 전체 build/check | PASS,76 tasks 실행 그래프. 원장604·상세445·활동89·성향41·목적57 등 포함 |
| AI 전체 build/check | PASS. 소유권 및 패키지537클래스, 게임 클래스 중복 없음 |
| 콘텐츠 전체 build/check | PASS |
| 게임 GameTest32묶음 | **259/259 PASS**. 기본198개, 별도 수명주기·활동·성향·레이드 선택 검사 포함 |
| 실제 세 모듈을 함께 로드한 AI GameTest5묶음 | **4/5 PASS**, Native 콘텐츠 근거의 다음 턴 재조회1개 실패 |
| 전체 GameTest 합계 | **263/264회 PASS**,36/37묶음 PASS. 고유263개에 전체 방 경로의 shadow 조건1회 추가 |
| 환경상 미실행 | Windows 심볼릭 링크 생성 불가로 관련 assertion1개 미실행. 전체 build 성공과 별도로 명시 |

전체 방 legacy/shadow, 대화 근거 검증, NEW 계약 미완성 차단 검사는 통과했다. 실행기의 기대264회와 실제264회가 일치한다. 이후 게임 테스트 들여쓰기만 정리한 컴파일 산출물은 전체 빌드 JAR 내 해당 클래스와 바이트 해시가 같음을 확인했다.

### 추가 실패 — Native 콘텐츠 근거의 다음 턴 재조회

검사: `NativeContentEvidenceGameTests.actualContentOwnerGatesPublicationAndNativeSeal`, phase82.

- 실제 콘텐츠 정의의 근거로 NPC 발화→근거 봉인→그 근거를 참조한 NPC 후속 발화까지는 진행한다. 다음 턴에서 후속 발화를 재조회할 때 기대한1개 대신0개가 반환된다.
- 최초 전체 실행과 새 월드 단독 재실행 `build/registry-refresh-native-retry-20261020`, 진단 메시지 추가 후 `build/registry-refresh-native-diagnostic-20261020`의 **3회 모두 재현**했다. 마지막 결과는 `status=PARTIAL, entries=0, attempts=8`이다.
- 최초 실패 월드의 SQLite를 읽기 전용으로 확인했다. `native_content_reply_marker` 원문, Native 근거 참조, GOD/PLAYER의 `SERVER_DISPATCHED` 전달 기록과 `ROOM_KNOWLEDGE_CAPTURED` 작업은 존재한다. 따라서 관찰된 현상은 단순 원문 미저장이 아니라 **저장 이후 재조회 경로의 제외**다. 조회/권한·근거 검증 중 정확히 어느 조건이 배제하는지는 추가 진단이 필요하며 확정 원인으로 단정하지 않는다.
- 이 검사는 모의 발화를 사용하므로 실제 LLM의 한국어 해석·생성 실패를 뜻하지 않는다. 반대로 결과0개를 정상으로 인정하거나 검사를 제외하면 요구한 기억 재조회 보장이 사라지므로 성공 기대값을 낮추지 않았다.
- 진단 후 AI JAR 재빌드 및 ownership/package 검사는 다시 PASS. 이후 전체5개 AI 통합을 재실행했다고 주장하지 않는다. 이번 요청은 오래된 검사 수정·전체 재검증이므로 런타임 기억 권한/조회 구현을 임의로 변경하지 않았다.

### 개발 산출물과 운영 서버

| 개발 JAR | 최종 SHA-256 |
|---|---|
| `mythictrpg-main/build/libs/mythictrpg-1.0.33.jar` | `966336DA5F9F808A4314D6DAA62D6A64E57D66261C16965CAC0FDA46DE96FA1C` |
| `mythai-ai-response/build/libs/mythai_ai_response-0.1.45.jar` (진단 메시지 추가 후) | `1D294D738525AF15F2BDD208A477B5B187D2BFEEFF17AB1A9D8C8542EB7C6A72` |
| `mythai-ai-content-registry/build/libs/mythaiaicontent-0.1.3.jar` | `FE759A1D98DDC230BFD9A952EE23D0F7ABD609B12D43E755CFC017E3C5429FF2` |

전체 matrix에서 사용한 진단 추가 전 AI JAR 해시는 `E8B97DE442B958432AD9053B50539BB0C040C34884835426FA475545A83F637F`이다. 테스트 오류 설명만 변경한 후 최종 해시와 구분한다.

서버 배포·부팅·종료·설정/월드 변경 없음. 운영 `server/mods` 및 `server/client-required-mods`의 게임1.0.28은 양쪽 모두 `F7F75C034FA152D996407BCAF0653B0E5823CA831F7B17F4FDE2DAE929AFBAD2`, 운영 AI0.1.19는 `E3BA2CF5E2CF1B53F0C75861F8FAF48D2391C214E5A22E0C4BE3A1FB7502BB7A`로 작업 전과 일치한다. 실패가 남아 있으므로 배포 승인 상태가 아니다.

## 검증 경계

실제 Gemma 생성/수정 대사, 플레이어의 그래픽 UI·수동 플레이 및 운영 서버는 이번 실행 대상이 아니다. 과거 실제 대사의 의미·자연스러움 실패와 목적 자동 연결 미완료는 기존 [브랜치 기록](../../인수인계/브랜치실험/behaivior/PROJECT_HANDOFF.md)에 남아 있다. 테스트 수정이나 자동 검사 통과로 이를 합격 처리하지 않는다. `gemma4:12b` 및 목적 추출/대사 실험의 `PRODUCTION_ENABLED=false`를 유지한다.
