# 기록 v2 — 기록 수집과 검색 선택의 분리

2026-09-30 / `HanesTest`. 게임 `1.0.22`, AI `0.1.25` 개발 소스. **설정/연결 게임 집중126개 검사 및 실제 runtime NEW 거절 GameTest1/1 통과. 운영 미배포이며 NEW 검색 활성화가 아니다.**

## 1. 바뀐 경계

기존 `recording-v2.json` schema2는 `archiveMode: OFF / RECORD_ONLY / SHADOW`를 사용했고, `SHADOW`가 기록 수집과 새 읽기 비교 허용을 함께 뜻했다. 이 파일의 schema/필드나 기존 manifest를 변경하지 않고 선택적인 별도 파일을 추가했다.

```json
{
  "schemaVersion": 1,
  "retrievalMode": "LEGACY"
}
```

경로는 `config/mythictrpg/recording-retrieval.json`이다. 파일을 자동 생성하지 않으며 이번 작업에서 운영 설정 파일을 작성하지 않았다. 서버 시작 시 기존 비동기 설정 loader에서 읽는다. 핫 리로드나 명령으로 런타임 도중 전환하는 기능은 추가하지 않았다.

[RecordingRetrievalSettings](../../../mythictrpg-main/src/main/java/com/sande/mythictrpg/recording/server/RecordingRetrievalSettings.java)는 정확한 두 필드와 자료형, schema1, 최대4KiB를 검사한다. `NEW`도 파싱하지만 그것을 실행 가능한 경로로 해석하지 않는다.

## 2. 호환·해결 규칙

| 기존 archive | 새 파일 | 결과 |
|---|---|---|
| OFF / RECORD_ONLY | 없음 | 기본 LEGACY, 새 읽기 미허용 |
| SHADOW | 없음 | 기존 명시적 opt-in을 `ARCHIVE_SCHEMA2_COMPAT` 출처로 유지, 비교 읽기 허용 |
| RECORD_ONLY / SHADOW | 명시 LEGACY | 새 읽기 차단, 기존 기록 수집은 유지 |
| RECORD_ONLY / SHADOW | 명시 SHADOW | 기록 수집과 별도로 새 비교 읽기 허용 |
| OFF | 명시 SHADOW | `ARCHIVE_OFF`, 새 저장소·dataset·설정파일 생성 없음 |
| 어느 archive 값이든 | 명시 NEW | 요청값 NEW 보존, `BLOCKED_CONTRACT_NOT_READY`, 새 읽기/foreground 사용 불가 |
| 어느 archive 값이든 | 잘못된 새 파일 | `INVALID_RETRIEVAL_CONFIG`, 새 읽기/foreground 사용 불가. LEGACY나 호환 SHADOW로 바꾸지 않음 |

파일이 **없는 것**과 파일에 **명시적으로 LEGACY를 적은 것**은 다르다. 전자는 이전 schema2 SHADOW 선택을 유지하지만, 후자는 그 호환 동작을 덮어쓴다. 요청한 값, 출처, 유효 상태를 immutable `Policy`로 보관한다. 새 자료가 없거나 오류가 났다고 다른 dataset을 생성하거나 구 시험 자료를 수입하지 않는다.

## 3. 실제 연결 위치

- [RecordingRuntime](../../../mythictrpg-main/src/main/java/com/sande/mythictrpg/recording/server/RecordingRuntime.java): bootstrap에서 설정을 읽어 현재 월드의 store에 전달한다. `retrievalState(server)`는 내용 없는 고정 상태 코드, `retrievalForegroundBlocked(server)`는 미지원/잘못된 전환의 명시적 거절 여부를 반환한다.
- [WorldRecordingService](../../../mythictrpg-main/src/main/java/com/sande/mythictrpg/recording/server/WorldRecordingService.java): 기존4인자 `open`은 schema2 호환 정책을 유지하고, 새5인자 `open(..., Policy)`로 명시 정책을 받는다. 원문/해석/semantic/Watch/Rumor의 공통 native read gate만 새 정책을 확인한다. source capture·실제 수신·철회 쓰기는 retrieval과 독립적이다.
- 실제 `RecordedMemoryAccess.open`은 이 store gate를 계속 재사용한다. 새 read 권한 발급 규칙이나 ACL·watermark·계보를 완화하지 않는다.
- AI [MythAiRoomConversationEngine](../../../mythai-ai-response/src/main/java/com/sande/mythictrpg/ai/MythAiRoomConversationEngine.java)는 foreground가 차단되면 legacy 기억 검색이나 모델 요청 전에 고정 코드의 `Result.failed`를 반환하도록 연결했다. 이는 NEW를 사용한 실패 fallback이 아니라 **아직 지원하지 않는 전환의 명시적 거절**이다.
- OP 진단 `/ai_memory archive_status`에는 archive health와 별도로 retrieval 상태가 나온다. claim·대화문·신/플레이어 ID를 표시하지 않는다.

설정 bootstrap 중에는 `RETRIEVAL_CONFIG_LOADING`으로 foreground를 잠시 차단하여 아직 읽지 않은 NEW 선택을 임시 LEGACY로 취급하지 않는다. 기존 archive 설정 자체가 잘못되어 새 정책을 읽을 수 없다면 `BLOCKED_ARCHIVE_CONFIG_INVALID`로 구분한다. 서버 runtime이 종료된 뒤에는 `RETRIEVAL_RUNTIME_STOPPED`다.

## 4. 유지한 background 축

projection/embedding 설정·모델 admission·실행 조건은 이번에 바꾸지 않았다. retrieval SHADOW라고 자동으로 추출/임베딩 모델을 켜지 않는다. 기존 legacy semantic 설정을 native opt-in으로 바꾸지도 않는다.

어휘 색인도 원래 archive SHADOW에 묶인 background 유지 정책을 그대로 둔다. 따라서:

- archive SHADOW + 명시 retrieval LEGACY: native 조회는 꺼져도 기존 어휘 색인 background는 유지한다.
- archive RECORD_ONLY + 명시 retrieval SHADOW: 비교 조회는 가능하나 background 어휘 색인은 자동 시작하지 않는다. 기존 인덱스와 bounded RAW fallback으로 읽으며 coverage는 계속 PARTIAL이다.

이는 구현된 최소 분리 범위다. 독립 색인 maintenance 축이나 새 ON 전략까지 완성했다고 부르지 않는다.

## 5. NEW가 아직 차단되는 이유

game-owned portable proof/seal과 foreground prompt·발행 히스토리의 연결이 아직 완료되지 않았다. 임시 bundle을 만들거나 renderer로 출력 형식을 확인했다고 그 내용을 실제 모델·출력·후속 히스토리에 넣을 권한이 생기지 않는다. 이번 구현은 LEGACY/SHADOW를 분리하고 NEW 요청을 정확히 보존하여 차단하는 단계다.

정상 LEGACY/SHADOW에서는 기존 답변 생성 방식이 유지되고 새 검색은 집계 비교에만 쓴다. NEW에서 기존 시험 기억으로 자동 복귀하거나, 불완전한 “현재 대화만 사용” 경로를 정상 기능처럼 제공하지 않는다. 정식 NEW 전환은 proof lifetime·전송/미전송·후속 인용 히스토리·철회/재시작 경계를 연결한 뒤 별도 검증해야 한다.

## 6. 검증 상태

`RecordingRetrievalSettingsTest`는 **126개 검사, focused8개 task 성공**했다. strict JSON,3×3 명시 설정 조합·기존 누락 호환, 실제 SQLite의5개 typed read gate, 같은 dataset으로 재시작, 차단된 retrieval에서도 archive write 보존, OFF 무파일, 어휘 색인 background 독립성을 검사했다. 앞선 `--continue` 실행에서 기존 RAW719개·어휘 색인110개도 통과했고 JAR가 생성됐다. 실패가 있었던 첫 전체 명령을 성공으로 부르는 것은 아니다.

추가 [RecordingPolicyGameTests](../../../mythai-ai-response/src/main/java/com/sande/mythictrpg/ai/RecordingPolicyGameTests.java)는 실제 설정 bootstrap과 production 엔진의 NEW 조기 거절을 검사했다. 명시 NEW 테스트 설정에서 runtime의 `BLOCKED_CONTRACT_NOT_READY`를 확인한 다음, 플레이어를 만들지 않은 요청을 보내도 player lookup 전에 즉시 거절되는지 확인한다. no-network fake client의 분류/생성 호출은 모두0이며 테스트 종료·실패·timeout 때 원래 client를 복구한다. 격리 개발 경로 `mythai-ai-response/build/recording-new-policy-block-20260930`에서 **required1/1 성공,118.8ms**, Gradle **9개 task 성공,12초**였다.

최초에는 이 fixture가 fullwire의 template namespace를 공유하여 SHADOW 실행에 함께 들어가 실패했다. 별도 `mythai_recording_policy` template namespace/resource로 분리한 뒤 위 격리 NEW 결과를 얻었다. 공유 검색의 SHADOW fullwire 역시 v3에서1/1 통과했으며, 중간의 namespace·SQLite 대기·fixture quota/즉시 mock 응답 실패와 수정은 [공유 검색 검증 이력](RECORDING_V2_M5_SHARED_RETRIEVAL_20260930.md#8-검증-이력)에 기록했다. NEW 거절 검증은 지원되지 않는 전환을 명시적으로 차단한다는 검증이지 NEW foreground 구현 완료가 아니다.

운영 설정 변경·실제 LLM 호출·서버/JAR 배포는 수행하지 않았다. native Rumor의230/131 성공이나 이전 schema8 전체 build를 이 설정 분리의 검증으로 소급하지 않는다.

최초 집중 실행에서는 fixture가 source cursor1을 실제 저장한 후 재시작에도0을 현재 cursor로 전달하여 `SOURCE_CURSOR_REGRESSED_AFTER_CAPTURE`로 거절됐다. fixture의 재시작 입력만 실제 확인된1/2로 바로잡았으며, dataset 최초 cutover0 및 production의 회귀 차단은 변경하지 않았다. 이 최초 실패는 성공으로 소급하지 않는다. 별도의 첫 실행 task명 `recordedMemoryAccessTest` 오타도 올바른 기존 task명으로 고쳤으며, 존재하지 않는 task 시도를 검증 통과로 세지 않는다.
