# 기록 v2 M4 — native 대화의 비권위 파생 기억

2026-09-30 / `HanesTest` 개발 작업본. 목표 소스 버전은 게임 `1.0.22`, AI `0.1.25`, 콘텐츠 `0.1.3`이다. M4 파생 저장은 SQLite schema **6**에서 추가됐고, 현재 작업본은 후속 lexical index의 schema **7**을 사용한다. 이 문서의 M4 검증과 후속 검색 검증은 구분한다. [M3 획득·철회 연결](RECORDING_V2_M3_SOURCE_RECONCILIATION_20260930.md)의 후속이다. **전체 M4 완료, 운영 배포, 실제 모델 품질 검증을 뜻하지 않는다.**

이 문서는 새 원장에 이미 기록된 **직접 청취 native 방 대화**를 제한된 background 작업으로 읽어, 기존 Ollama backend를 통해 EVENT/RELATIONSHIP/SUMMARY 후보로 저장하는 실제 연결 범위를 설명한다. 기존 기억 writer/reader, 청취 기록, 게임 관계 저장소를 대체하지 않는다.

## 1. 이번 범위와 제외 범위

지원하는 대상은 `room-publication-v2`가 기록한 `ROOM_KNOWLEDGE_CAPTURED` work item이다. 원문, publication context, 실제 `GAME_HEARD` 전체 수신, 신별 knowledge receipt가 맞아야 한다.

- 플레이어 발언은 `DIALOGUE_DIRECT`, 신 발언은 `DERIVED_SPEECH`로 구분한다. 신의 대사를 플레이어의 `MemoryJournal.Entry`로 위조하여 기존 기능에 넣지 않는다.
- 동일 world/dataset, 대화방, 관찰 신, 공개 청중, 공개 정책, recording policy, memory mode 안에서만 앞선 자료를 묶는다. `STANDARD`/`TEST_RECORDING`, `PERSONAL`/`RUMOR_TEST`를 섞지 않는다.
- 원문의 `context.evidence`는 비어 있어야 한다. 외부 Watch/Rumor 등의 증거가 포함된 발언은 여전히 `EXTERNAL_EVIDENCE_UNSUPPORTED`이며, 외부 권한을 추측하지 않는다.
- `sourceMessages`가 참조하는 **native 부모 대사 계보**는 지원한다. target과 재귀적으로 필요한 모든 부모를 합쳐 최대 6개이며, 같은 방·관찰 신·정확히 같은 공개 범위/정책이어야 한다. 앞서 저장된 source와 실제 신의 청취 receipt만 허용하고, 자기 참조·순환·미래 source·다른 방·공개 범위 차이·예산 초과는 거절한다.
- 부모의 신 청취 receipt가 아직 commit되지 않았으면 `AWAITING_NATIVE_PARENT_RECEIPT`로 지연한다. 지연을 청취 성공이나 영구 미청취로 바꾸지 않는다. 선택적 과거 문맥은 필수 부모를 확보한 뒤 남은 예산에서만 추가하며, 자체 부모가 있는 선택적 문맥은 이번 범위에서 제외한다.
- Watch/행동/활동/소문 등 외부 source, provenance 없는 구 행, 현재 구현이 지원하지 않는 work kind는 `SKIPPED_UNSUPPORTED` 등 명시 상태로 남긴다. 성공적인 기억 추출로 보고하지 않는다.
- 이 작업으로 NPC에 새 지식을 부여하거나 게임 사건을 확정하지 않는다. 후속 [native 파생 조회](RECORDING_V2_M5_NATIVE_INTERPRETATION_READ_20260930.md)는 이미 허용된 RAW 검색 결과에 연결된 후보만 SHADOW로 읽는다. 전체 source 범위의 M4 전환, vector 연결, 생성 프롬프트의 새 저장소 전환은 별도 후속이다.

## 2. 실제 호출 경로와 소유권

```text
게임의 native 방 원문 + 실제 신 청취 receipt
  → WorldRecordingService: source/knowledge/work 동시 commit
  → RecordingRuntime: 별도 설정·게임 정책 확인, worker 권한 발급
  → RecordedProjectionService / Runtime: idle 상태에서 bounded claim
  → RecordedProjectionExtractor → 기존 OllamaMemoryBackend
  → MemoryProjectionPort.commitProjection
  → 게임 저장소가 lease·source·receipt·인용·공개 범위를 재검증
  → 후보 기억 + source 계보 + work DONE 동시 commit
```

관련 계약은 [ProjectionRecords](../../../mythictrpg-main/src/main/java/com/sande/mythictrpg/recording/api/ProjectionRecords.java), [MemoryProjectionPort](../../../mythictrpg-main/src/main/java/com/sande/mythictrpg/recording/api/MemoryProjectionPort.java)다. 실제 저장 검증은 [RecordingProjectionStore](../../../mythictrpg-main/src/main/java/com/sande/mythictrpg/recording/server/RecordingProjectionStore.java), AI 해석은 [RecordedProjectionExtractor](../../../mythai-ai-response/src/main/java/com/sande/mythai/response/memory/RecordedProjectionExtractor.java)에 있다.

- AI는 generic SQL writer, 원문 producer, 무제한 관리자 조회 포트를 받지 않는다. 게임이 등록한 `ProjectionWorkerCapability`와 해당 claim의 불투명 `ProjectionWorkToken`만 사용한다.
- `unregistered()`로 만든 DTO/token 자체는 권한이 아니다. 게임 저장소의 현재 instance와 registry가 확인한 handle이어야 한다.
- worker/token은 runtime epoch, job, extractor version, nonce, 만료에 연결된다. 종료·교체·재시작 후 이전 token을 새 작업에 재사용할 수 없다.
- 권위 게임 상태, affinity/trust 수치, 보상, 퀘스트 등록, 아이템, 실제 사건은 이 포트로 변경할 수 없다.

## 3. 별도 명시적 활성화

새 background 추출은 archive 또는 SHADOW를 켰다는 이유만으로 시작하지 않는다. 다음 조건을 모두 충족해야 한다.

1. 게임 `config/mythictrpg/recording-v2.json`의 archive가 OFF가 아니고 저장소가 준비돼 있어야 한다.
2. 게임 `config/mythictrpg/recording-projection.json`에서 명시적으로 ON이어야 한다.
3. 기존 `config/mythictrpg/ai-memory-index.json`이 유효하고 `enabled=true`, `consolidate=true`여야 한다. 기존 모델 이름·digest·timeout·CPU/thread/context 설정을 그대로 사용한다.
4. 게임 memory mode가 OFF가 아니고, 온라인 플레이어가 0명이며, quota가 background 작업을 허용해야 한다. 전경 모델 작업과의 admission도 별도로 통과해야 한다.

새 설정의 전체 형식은 다음과 같다.

```json
{
  "schemaVersion": 1,
  "projectionMode": "OFF"
}
```

`projectionMode`는 `OFF`/`ON`이다. 파일 누락은 OFF, 여분 필드·잘못된 타입·schema·enum은 비활성으로 처리한다. projection 설정 오류로 정상 archive 정책까지 임의 변경하지 않는다. 구성은 시작 시 읽으므로 현재 구현을 실시간 `/reload` 전환으로 가정하지 않는다. 위 예시는 형식 설명이며 **이 작업에서 운영 설정을 ON으로 바꾼 것이 아니다.**

`semanticMode=OFF`여도 유효한 extraction 설정이면 추출은 가능하다. 이 기능을 켜기 위해 embedding 모델을 새로 설치하거나 vector 검색을 켤 필요가 없다. 실제 모델 자동 설치·시작은 하지 않는다.

## 4. 입력 예산·모델 요청·중단

현재 실제 worker는 한 번에 최대 **6개 source / 16,384 UTF-8 바이트 / 45초 lease**를 claim한다. 게임 저장소가 확인할 단일 원문은 최대 1MiB이고, 검증 read window의 원문 합계는 2MiB다. 원문은 DB에서 삭제하거나 잘라 저장하지 않는다. 모델에 제공한 범위가 일부면 `excerpt=true`, 전체 문자 수와 제공한 문자 수를 따로 유지한다.

필수 부모 계보는 target보다 우선순위가 낮은 참고 예시가 아니다. target과 모든 필수 부모의 허용된 발췌를 함께 확보할 수 없으면 추출하지 않는다. 부모는 target보다 늦은 발생 시각일 수 없고, 직접 부모-자식의 ingest 순서도 검증한다. 모델은 이렇게 확인된 source만 받으며, 포함된 모든 source가 이후 철회 가능한 파생 의존성으로 저장된다.

- source alias는 요청 안의 `e0`…`e5`, 실제 화자는 동일인 여부가 유지되는 임시 actor alias로 바꾼다. 관찰 신은 `observer`다. source kind, 실제 actor kind, 발생 시각, 허용 텍스트, excerpt 범위만 전달한다.
- 내부 world/dataset/message/receipt UUID, receipt hash, token, NPC identity DB를 프롬프트에 복사하지 않는다. 별도 신 identity 체계를 만든 것도 아니다.
- 입력 텍스트는 명령이 아닌 인용 자료로 취급한다. structured JSON만 받으며 임의 필드, 새 보상·수치·사실, 제공하지 않은 source, 원문에 없는 인용을 거절한다.
- 기존 numeric-loopback-only Ollama transport, 요청 전후 model digest 검사, 전체 timeout을 재사용한다. background timeout은 설정 상한 30초다. non-streaming/Thinking Off와 기존 CPU/thread/context/token/keep-alive 설정을 유지한다.
- 기존 `ModelAdmission`을 사용한다. 전경 대화가 오면 background 추출을 중단하되 실제 transport가 종료되기 전에는 hardware permit을 먼저 풀지 않는다. 중단을 일반 모델 실패와 구분한다.
- 단일 worker와 제한된 queue를 사용한다. 서버 tick에서 LLM이나 SQLite 완료를 기다리지 않는다. runtime당 동시에 하나의 background 추출만 수행한다.

모델 오류·잘못된 구조 출력·실제 모델 timeout은 제한된 실패 횟수에 포함된다. 동일 extractor version에서 최대 3회 실패 후 FAILED이며, 재시도는 durable 지연을 둔다. 플레이어 접속·전경 선점·정책 변경·저장소 일시 거절은 DEFERRED로 처리해 모델 실패 횟수를 소모하지 않는다. 종료 중 finish를 확인하지 못한 작업은 lease 만료/재시작 복구 대상이며 성공으로 위장하지 않는다.

## 5. 저장되는 세 계층

| 계층 | 내용 | 하지 않는 것 |
|---|---|---|
| `EVENT` | 대상 발언의 비권위 분류, 원문 인용, 선택적 발언 간 관계 후보 | 실제 이벤트 성공·퀘스트 이행·월드 사실 확정 |
| `RELATIONSHIP` | 관계에 의미 있는 대화 이력의 원문 인용 | affinity 변경, 영구 성격·호감 방향 추측 |
| `SUMMARY` | 동일 공개 범위의 중요한 원문들을 source별로 선택한 extractive 요약 | 새로운 요약 사실 생성, 여러 출처를 하나의 사실로 합성 |

분류는 `DIALOGUE_EPISODE`, `SPEAKER_CLAIM`, `INTENTION_OR_PROMISE`, `REPORTED_CLAIM`, `CONDITIONAL`, `JOKE`, `CORRECTION_OR_EXPLANATION`이다. 이 값도 해석 후보이며 관측 사실의 승격이 아니다.

링크는 `CORRECTS`, `CONTRADICTS`, `CANCELS`, `ALSO_PLANNED`, `REPORTS_FULFILLMENT`를 지원한다. AI extractor의 첫 구현은 동일 실제 화자의 앞선 제공 source만 연결한다. 정정/취소는 `CORRECTION_OR_EXPLANATION`, 이행 주장은 `SPEAKER_CLAIM`, 추가 계획은 `INTENTION_OR_PROMISE`와 맞아야 한다. 앞선 약속을 반복하거나 시간이 지났다는 이유로 이행했다고 판단하지 않는다. 농담·전언·조건은 이행 링크로 승격하지 않는다.

선택된 인용은 source의 연속 원문이어야 하고, target 인용은 반드시 포함한다. 링크는 앞선 source 인용도 EVENT에 보존한다. 서버가 인용을 다시 검사하고 실제 actor/source/receipt 방향과 연결한다. 모든 파생 행·링크의 상태는 `CANDIDATE`이며 게임 성공 상태가 아니다.

## 6. 파생 저장 schema·멱등성·철회

`work_items`에 extractor version, 시도 횟수, 다음 시도 시각, 실패 코드, lease nonce를 추가했다. `memories`, `memory_sources`, `memory_subjects`, `memory_links`를 추가한다. 원문과 수신 행은 그대로 보존한다.

- 파생 identity는 dataset/job/extractor version/layer에 묶인다. 동일 token·동일 결과의 재전달은 멱등적으로 처리하고 다른 결과는 충돌로 거절한다. 완료 job은 같은 version의 정상 재시작에서 다시 모델에 보내지 않는다.
- 생성 전 lease와 생성 후 commit 사이에 source hash, receipt hash, 실제 화자, 공개 범위가 바뀌면 늦은 결과를 적용하지 않는다. 원문 전체 hash와 모델에 제공한 excerpt coverage는 별개로 보존한다.
- schema 5→6은 예약된 시작 트랜잭션에서 파생 테이블을 추가 이관한다. 현재 후속 검색 작업은 schema 6→7에서 contentless lexical index 테이블을 추가한다. M4 파생 데이터 형식을 다시 작성하는 변경은 아니다. 이전 schema의 이관 경로를 유지하며, 과거 행에 없는 권한·청취 이력을 추측해 만들지 않는다. 새 schema DB를 구 schema 전용 JAR로 단순 되돌릴 수 없다. 운영 전환에는 별도 백업/rollback이 필요하며 이번에는 운영 DB를 열지 않았다.
- native 부모 지원으로 extractor 기본 version은 `recorded-extractive-v2-native-ancestry`가 됐다. 실제 worker version에는 기존 모델 설정 fingerprint도 결합한다. 이전 version에서 `EXTERNAL_OR_ANCESTRY_EVIDENCE_UNSUPPORTED`로 보류했던 행은 version 변경 후 재검사할 수 있다. 외부 증거가 남아 있으면 새 지원 범위에서도 거절하며, 무관한 영구 거절 행 전체를 다시 실행하지 않는다.

**철회 후속 보강 완료:** 검토에서 발견한 다음 두 조건을 storage 구현·전용 테스트에 반영했고, SQL 실행 예산과 조회 인덱스 보강 후에도 storage 95 checks 및 실제 SQLite pipeline 66 checks를 다시 통과했다.

1. 모델이 참고한 모든 입력 source를 derivation dependency로 보존한다. 최종 인용에서 선택되지 않은 source도 분류에 영향을 주었을 수 있으므로, 해당 receipt 철회가 그 파생물을 무효화한다. 출력 payload의 인용은 여전히 선택된 원문만 유지한다.
2. target receipt 자체가 철회된 경우와 이전 context만 철회된 경우를 구분한다. 후자는 현재 draft를 폐기하되 유효한 target job을 영구 INVALIDATED로 만들지 않는다. 모델 실패 횟수를 늘리지 않고 PENDING/backoff로 돌려, 철회된 context를 뺀 새 claim으로 재처리한다.

기존 source 전체 철회와 신별 receipt 철회, 현재 game-owned 권한 검증을 제거하지 않는다. 새 파생 저장이 현재 청중에 대한 조회 허가를 발급하는 것도 아니다.

부모 계보 확장 후에는 모든 필수 부모와 선택적 문맥을 동일한 all-input dependency 규칙으로 저장한다. 부모 하나의 receipt가 철회되면 그 부모를 인용하지 않은 파생 결과도 무효화된다. 계속 증거가 도착하지 않는 과거 작업들이 새 유효 작업을 막지 않도록, 새 PENDING 작업을 우선하고 예약 재시도끼리는 durable 다음 시도 시각 순서로 선택한다. 이 공정성 및 늦은 부모 receipt의 추가 fixture는 아래 검증 상태를 따른다.

## 7. 확인된 검증과 아직 확인하지 않은 것

아래는 root가 직렬 실행해 확인한 결과다. 부모 계보 지원 후 실제 parent graph pipeline 66 checks를 확인했고, 공정성·늦은 부모 receipt fixture까지 포함한 storage 327 checks를 통과했다. 이후 schema 7 및 typed 파생 조회를 포함한 **게임 전체 offline build 50 tasks / AI 전체 build 40 tasks**가 성공했다. 후속 native 권한 GameTest 및 게임·AI·콘텐츠 3모듈 wire GameTest도 각각1/1, exit0으로 통과했다. 운영 배포·실제 모델 수용은 별개다.

| 검사 | 확인 결과 |
|---|---:|
| `RecordingProjectionSettingsTest` | 11 checks |
| `RecordedProjectionRuntimeTest` | 43 checks |
| `RecordedProjectionExtractorTest` | 183 checks |
| `RecordedProjectionPipelineTest` — 실제 native 부모 그래프 포함 | 66 checks |
| 기존 memory index 회귀 | 1,874 assertions |
| 기존 background scheduler 회귀 | 4 checks |
| 기존 Ollama backend 회귀 | 53 checks |
| `RecordingProjectionStoreTest` — lease/철회/이관·native 부모 계보·공정성·늦은 청취 | 327 checks |
| `RecordedInterpretationReadTest` — native 후보 조회/권한·계보·버전·용량 | 97 checks |
| `RecordingProjectionAuthorityTest` — 파생 revision fence/취소 정리 | 30 checks |
| AI 패키지 소유권/게임 클래스 중복 검사 — typed 조회 후 전체 build | 425 classes / 중복 0 |
| 지연 작업 공정성(161개 미확정 부모 cohort + 새 작업) fixture | 위 storage 327에 포함해 통과 |
| 늦게 도착한 실제 부모 `GAME_HEARD` receipt fixture | 위 storage 327에 포함해 통과 |

Pipeline 검사는 개발 `build/` 아래의 임시 synthetic world에서 실제 SQLite와 공개 `GameRecordingPort`를 사용한다. 플레이어 약속→그 약속을 부모로 둔 신 대사→두 발언을 부모로 둔 플레이어 취소 및 미청취 원문을 저장한 뒤, 실제 background runtime/extractor에 fake transport를 넣어 EVENT/RELATIONSHIP/SUMMARY 9개와 취소 링크를 검증한다. 인용·양끝 receipt·원문 hash·실제 화자·미청취 배제·필수 부모와 다중 source 요약·clean reopen 후 중복 추출 방지를 검사한다. 검사 SQL은 read-only이며 운영 DB를 변경하지 않는다.

게임 `1.0.22` / AI `0.1.25` 개발 작업본에서 schema 7 전체 build 중 발생한 optional index timeout 복구 오류와 typed 조회의 시간값 직렬화 오류를 수정했다. 이후 lexical 110 / projection 327 / 권한 원문 검색 719 / room capture 40 및 새 typed 조회 97 / authority 30을 포함해 게임 50 tasks, AI 40 tasks 전체 build를 통과했다. 이전 실패 자체를 성공으로 소급하지 않는다. 기존 memory index/scheduler/backend 행은 이번 개발 중 수행한 회귀 결과이며, 매 후속 변경마다 전체 검사를 반복했다는 뜻은 아니다. 빌드/검증 결과를 서버 설치 완료로 표현하지 않는다.

개발 native 권한 GameTest는 초기 fixture 설정 누락 timeout 이후 설정을 보완해8 tasks,1/1, exit0으로 통과했다. 3모듈 wire GameTest는 실제 모듈 로드 경로에서1/1, exit0을 확인했다. 이 개발 fixture 검증은 실제 모델의 기억 품질이나 운영 월드 수용 검사가 아니며, 초기 실패를 성공으로 소급하지 않는다.

**이번에 실행하지 않은 것:** 실제 Ollama/LLM 호출, 실제 모델 품질 비교, 운영 설정 ON 전환, 운영 JAR 배포·서버 부팅, 클라이언트/인게임 확인, 대용량 성능. 이전 M3 GameTest를 M4 실행 검증으로 소급하지 않는다.

## 8. 다음 작업

1. 외부 source/evidence를 포함하는 파생 처리 확장. 기존 Watch/Rumor 현재 증거 검증과 철회 연결을 유지한다.
2. native RAW→파생 후보의 제한된 SHADOW 조회를 바탕으로 외부 source 및 semantic 검색, 완전성·coverage·청중 격리 비교를 확장한다. 현재 새 파생 저장을 생성 프롬프트에서 사용한다고 보고하지 않는다.
3. 새 결과의 실사용 ON 전환은 별도 검증/승인 후 진행. 그 전까지 기존 writer/reader와 기록 경로를 유지한다.

기반 저장과 native 수직 경로를 구현한 단계이며, 이 문서의 체크 수가 전체 장기기억/RPG 서버의 완성을 뜻하지 않는다.
