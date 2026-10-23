# 기억 재조회 기능 수정 및 전체 오프라인 검증

2026-10-20 / `behaivior` / 게임1.0.34, AI0.1.46, 콘텐츠0.1.3. `gemma4:12b` 유지. **운영 서버 미배포**.

## 원인과 수정

이전 [오래된 테스트 갱신 결과](../test-refresh-20261020/REVIEW.md)의 `NativeContentEvidenceGameTests` 실패를 기능 코드까지 추적했다. 발행된 대화가 SQLite에 비동기로 저장되는 동안 읽기 Session이 먼저 과거 highWatermark를 고정했다. 원문 유실·콘텐츠 소유자의 허가 실패가 아니라 **저장 완료보다 앞선 읽기 발급**이었다.

- 수정 전 진단 `build/native-recall-trace2-20261020`: 첫 NPC 발화 조회부터 phase4 실패.8회 재시도 모두 highWatermark1, queuedEntries4~5로 저장이 끝나기 전에 완료되었다. 이전 phase82 실패 역시 그 자체로 저장 데이터 손상을 뜻하지 않았다.
- 이전 실패 월드의 SQLite를 읽기 전용으로 재조회: 후보1개, 원 Native proof·source closure·bindings 및 후속 발화 closure 모두 정상. 이 검사는 live 소유자 허가를 대신하지 않는다.
- `RecordingRuntime`에서 전체 room capture 완료(후속 delivery batch 포함)를 추적하고 `RecordedMemoryAccess.openAfterCapture`로 최대5초 비동기 대기 후 게임 스레드에서 요청/모드/engine/runtime을 재검증해 Session을 발급한다.
- RAW/typed foreground 준비 소비자가 새 API를 사용한다. 기존 고정 watermark·lease·source/청취/청중/콘텐츠 소유자 검증은 유지한다. 종료·취소·지연·실패 시 권한을 내주지 않으며 저장 작업은 취소하지 않는다.
- 재조회 fixture의 임의8회 재시도는 제거했다. 허용 기대값을 낮추거나 authority 검사를 생략하지 않았다. NEW 자동 활성화·모델·목적 자동 통합 정책은 바꾸지 않았다.
- 새 게임 API의 최소 의존 버전을1.0.34로 올려 구 게임 JAR과 새 AI0.1.46의 혼합을 막았다. 저장 스키마/데이터 마이그레이션은 없다.

상세 계약: [저장 직후 재조회](../../인수인계/브랜치실험/behaivior/RECORDING_READ_AFTER_CAPTURE_20261020.md).

## 검증

| 범위 | 결과 |
|---|---|
| 신규 CaptureReadFenceTest | 27 checks PASS: RAW 이후 delivery까지 대기, 고정 대기 스냅샷, lifecycle, overflow, 실패, game dispatch 후 현재성, 취소/timeout |
| 집중 기존 저장/조회 | RoomRecordingCapture40·RecordedRoomRead719 PASS |
| 게임 전체 build/check | PASS,77 tasks;6분14초 |
| AI 전체 build/check | PASS,70 tasks;1분31초; ownership/package537 classes, 게임 중복0 |
| 콘텐츠 전체 build/check | PASS,11 tasks;7초 |
| 실제 방 권한 GameTest | 1/1 PASS. 위조6종 거절과 저장 대기 중 실제 턴 교체 후 권한 미발급 포함 |
| 실제 모듈 Native/콘텐츠 GameTest | 1/1 PASS. 원 발화 및 후속 NPC 발화를 각1회 API 호출로 재조회, 실제 소유자/seal/공개 경계 유지 |
| 첫 전체 GameTest 행렬 | 37묶음/264회 실행 완료.36묶음 통과, 저장 fixture2개 timeout으로262/264 PASS·exit1. Native 재조회는 재통과 |
| 테스트 시간 보완 후 저장 묶음 | 3/3 PASS. 핵심27·719·40 재통과, 게임 재컴파일/JAR 및 AI ownership/package 재검증 성공 |
| 테스트 시간 보완 후 전체 행렬 | **37/37묶음·264/264회 PASS, exit0**. 게임259/259 + AI5/5. 실패0 |

신규/집중/전체 검사 수를 중복 합산하지 않는다. GameTest의 NPC 발화는 고정된 모의 발화이며 실제 LLM 생성 문장이 아니다.

### 추가 발견: 실제 디스크 I/O를 기다리는 테스트의 tick 시간

첫 전체 실행의 `mythictrpg_recording` 묶음에서 `namedSavedDataReservationsSurviveActualAsyncIo`와 `rumorMetadataFollowsAtomicCommitAndFinalSave`가600tick 제한으로 실패했다.3개 검사의 전체 실시간은639.6ms였다. GameTest의 tick은 정상 서버처럼50ms마다 실행되지 않는다. 일부러 존재하지 않는 폴더에 저장하여 실패·복구를 확인하는 fixture이므로 해당 `NoSuchFileException` 자체는 의도된 시험이다.

`RecordingSavedDataGameTests`의2개 fixture와 `RumorRecordingGameTests`의1개 fixture에만1ms scheduler yield와30초의 실제 시간 제한을 적용하고, framework tick 상한은40000으로 두었다. 실패 시 대기 latch도 해제한다. 실제 NBT 저장, quota 예약·정산, 미저장 상태 미승인, 실패 후 재시도·정확한 committed snapshot을 요구하는 모든 assertion은 유지했다. 생산 저장 코드·quota 정책은 바꾸지 않았다.

이 후속 변경은 **테스트 실행 시간 처리만** 바꾼 것이다. 세 모듈 전체 build/check는 그 직전 동일한 기능 코드에서 통과했으며, 이후 게임 재컴파일/JAR·핵심 저장 회귀와 GameTest 전체 행렬도 다시 통과했다. 실패한 첫 행렬도 보존했다. Native 콘텐츠/후속 발화 재조회는 집중 실행·첫 행렬·최종 행렬의 서로 다른 세 월드에서 모두 통과했다.

### 실행 증거와 재현

프로젝트 루트의 PowerShell에서:

```powershell
# 최초 실행: 세 모듈 build/check와 두 핵심 GameTest
.\dev-tools\Test-OfflineGameMatrix.ps1 -RunId native-recall-fix-focused-20261020 -Only mythai_native_content_evidence,mythictrpg_recording_read
# 위 빌드 검증 뒤 같은 코드/JAR로 모든 GameTest 실행
.\dev-tools\Test-OfflineGameMatrix.ps1 -RunId native-recall-fix-matrix-20261020 -SkipBuild
# I/O fixture 시간 보완 후 집중 검사와 최종 전체 행렬
.\dev-tools\Test-OfflineGameMatrix.ps1 -RunId native-recall-saveddata-pacing-20261020 -SkipBuild -Only mythictrpg_recording
.\dev-tools\Test-OfflineGameMatrix.ps1 -RunId native-recall-fix-final-20261020 -SkipBuild
```

재현할 때 `RunId`는 새 이름으로 바꾼다. 이전 결과/월드를 덮어쓰지 않는다. 소스가 달라졌다면 `-SkipBuild`를 생략하여 새 전체 빌드를 검증한다.

- [집중 실행 결과](../../mythictrpg-main/build/native-recall-fix-focused-20261020/results.json): 세 모듈 빌드 + 두 GameTest,5항목 성공/exit0.
- 첫 전체 실행: `mythictrpg-main/build/native-recall-fix-matrix-20261020/`의 `inventory.json`, `results.json`, 각 `*.gradle.log` 및 격리 월드. 최종 전체 실행은 `build/native-recall-fix-final-20261020/`에 별도 보존한다.
- [최종 전체 결과](../../mythictrpg-main/build/native-recall-fix-final-20261020/results.json):37행 모두 `Passed=true`, 총264회. 동일 소스/JAR로 실행했으며 행렬 진행 중 게임 클래스의 추가 컴파일은 없었다.
- 수정 전 실패 `native-recall-trace-20261020`, `native-recall-trace2-20261020`와 이전 `registry-refresh-*` 결과도 보존한다. 진단용 Gradle task의 초기 configuration-cache 오류는 task 설정 수정 뒤 재실행 성공했으며 기능 실패로 혼합 집계하지 않는다.

## 미검증과 남은 경계

- Windows 환경 권한 때문에 managed quota의 심볼릭 링크 assertion1개가 미실행되었다. 나머지 해당 검사71개는 통과했다.
- 실제 Ollama 호출·자유 대화 품질·클라이언트/HUD 수용·운영 서버 부팅은 이번에 실행하지 않았다. 이전 한국어 의미 오류를 합격으로 바꾸지 않는다.
- `RecordedForegroundMemory`는 명시적 개발/시험 준비 API다. production NEW 전환과 전체 목적/기억 자동 통합을 활성화한 것은 아니다.
- 빈 조회/시간 초과는 기록 부재를 증명하지 않는다. 기존 capture 누락 진단 및 공개 권한을 유지한다.

## 파일 적용 상태

개발 산출물 SHA-256:

| 파일 | SHA-256 |
|---|---|
| `mythictrpg-main/build/libs/mythictrpg-1.0.34.jar` | `1D2F4B404A449055B8D1E3A2BB99CCAF6846791F7FBEAAFCDB54B9591FBF53AE` |
| `mythai-ai-response/build/libs/mythai_ai_response-0.1.46.jar` | `06F94F99DE1AD73CC501C1D11F08C5FF7F090B6C9C32E67D25BB23F2416087BE` |
| `mythai-ai-content-registry/build/libs/mythaiaicontent-0.1.3.jar` (변경 없음) | `FE759A1D98DDC230BFD9A952EE23D0F7ABD609B12D43E755CFC017E3C5429FF2` |

운영 게임1.0.28 서버/클라이언트 필수 JAR은 모두 `F7F75C034FA152D996407BCAF0653B0E5823CA831F7B17F4FDE2DAE929AFBAD2`, AI0.1.19는 `E3BA2CF5E2CF1B53F0C75861F8FAF48D2391C214E5A22E0C4BE3A1FB7502BB7A`, 모델 설정 파일은 `65B4CA8333D477C917D878C10BA912516AE1494CB32E3EE0E0F8CDC64138A585`로 작업 전과 동일하다. 운영 월드·설정·프로세스·설치 파일은 변경하지 않았다. 테스트 서버는 모두 `build/` 내부 격리 실행이다.
