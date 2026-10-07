# Recording V2 M5 — 개인 관찰 기록의 타입 지정 읽기

2026-09-30, `HanesTest` 실험 브랜치.

**현재 상태: Watch 읽기 API·reader·발급 세션과 SHADOW 소비자 구현, 게임 컴파일/JAR 및 AI 소비자 focused 검증 통과. 실제 ActionLedger/Watch journal→SQLite→발급 세션 reader 회귀 370개 검사를 통과했다.** [native 의미 검색](RECORDING_V2_M5_NATIVE_SEMANTIC_READ_20260930.md)의 검증 범위와 구분한다. 기존 [M3 source reconciliation](RECORDING_V2_M3_SOURCE_RECONCILIATION_20260930.md)의 capture·취소 정리는 이미 존재하며, 이번 작업은 이를 다시 만들지 않는다.

## 1. 목표와 제외 범위

이미 게임이 직접 관찰로 승인하고 Recording V2에 저장한 `DIRECT_WATCH` 개인 경험을, 현재 방의 신·플레이어·청중·권한으로 다시 검증해서 읽는다. 저장소의 존재 자체는 지식 권한이 아니다.

- `PERSONAL`, 비공개, 화자 신 한 명에 한정한다. 기존 저장된 허용 청중은 해당 신과 관찰 대상 플레이어의 쌍이며, 현재 청중이 이를 넘을 수 없다.
- 반환물은 기존 `ExperienceView.Event`의 관찰 사실이다. 원본 게임 ledger payload, 비공개 좌표, 숨겨진 필드, 관계 수치, 추정 총 통계는 반환하지 않는다.
- `MATURE_CROP_REMOVED`는 블록 제거다. 아이템을 얻었다는 사실로 바꾸지 않는다. 활동 요약도 실제로 보인 표본이지 전체 행동 횟수가 아니다.
- AI 소비자는 SHADOW 집계 진단만 수행한다. 이 단계에서 프롬프트/대사/행동에 새 기록을 사용하거나 운영 모드 ON을 활성화하지 않는다.
- 새로운 관찰 시작, 과거 ledger 자동 import, 공개 평판, 소문 확산, 기존 기억 writer 전환은 범위 밖이다.

## 2. 선택적 API

[MemoryReadSession](../../../mythictrpg-main/src/main/java/com/sande/mythictrpg/recording/api/MemoryReadSession.java)에 기존 구현이 `UNAVAILABLE`로 안전하게 거절하는 optional method를 추가한다.

```java
CompletableFuture<ObservationReadRecords.Page> observations(
    MemoryReadSession.Query query,
    Optional<ObservationReadRecords.Cursor> cursor,
    MemoryReadSession.Budget budget);

boolean current(ObservationReadRecords.Page page);
```

새 [ObservationReadRecords](../../../mythictrpg-main/src/main/java/com/sande/mythictrpg/recording/api/ObservationReadRecords.java) DTO:

```java
record Entry(
    RecordingRecords.SourceRef source,
    UUID knowledgeReceiptId,
    String observerGodId,
    UUID subjectPlayerId,
    ExperienceView.Event experience) {}

record Page(MemoryReadSession.Status status, List<Entry> entries,
            Optional<Cursor> next) {}
```

Cursor는 세션이 발급·등록한 opaque 객체다. 다른 세션의 cursor/page, 새로 조립한 page, query가 바뀐 continuation은 거절한다. 기존 raw/derived/semantic 읽기와 8회 호출·60초 세션 수명·동시 요청 제한을 공유한다.

검색 문자열은 반환 가능한 typed action/subject/outcome/gameTime만 대상으로 하는 제한된 리터럴 검색이다. 비공개 evidence payload나 source hash는 검색 문서로 쓰지 않는다. 빈 검색은 제한된 최근 기록 조회다. 첫 SHADOW 소비자는 이미 계산된 기존 관찰 ID 목록과 bounded 최근 typed 관찰을 비교하며, 한국어 채팅을 action enum에 직접 대조한 결과를 의미 검색이라고 부르지 않는다. 원본 projection에는 UTC가 없고 게임 시간 또는 `NOT_DISCLOSED`가 있으므로 UTC 기간 조건은 무시하거나 다른 시각으로 바꾸지 않고 지원 불가로 반환한다.

## 3. 두 종류의 시계

기존 source-owner cursor와 Recording archive transaction sequence는 서로 다른 시계다.

| 저장 경로 | 읽기 세션의 archive watermark `W` 확인 |
|---|---|
| 기존 Watch `captureSource`의 초기 receipt | source와 초기 receipt들이 같은 txn에 생성된다. 정확한 source binding의 `SOURCE_CAPTURED` work와 `created_sequence == source.ingest_sequence <= W`를 요구한다. origin metadata가 없는 초기 capture 경로만 여기에 해당한다. |
| origin metadata가 있는 incremental receipt | source/knowledge origin과 정확한 `receiptId:receiptHash`에 연결된 `KNOWLEDGE_ACQUIRED` work를 요구한다. 이 work의 `created_sequence <= W`가 필수다. |

`knowledge_origins.acquired_cursor`를 archive `W`와 비교하지 않는다. 늦게 취득한 receipt는 원본 source가 오래되었다는 이유로 이미 열린 읽기 세션에 들어오면 안 된다. 반대로 source cursor의 숫자가 archive `W`보다 크다는 이유만으로 이미 확인된 취득을 거절해서도 안 된다.

현재 취소/철회는 `W` 이후의 것이어도 적용한다. 고정 watermark는 과거의 허용 권한을 영구 보존하는 수단이 아니다.

## 4. 검증 순서

1. 기존 게임 발급 세션의 신·플레이어·방 revision·runtime·memory mode·공개 범위를 확인한다.
2. SQL에서 같은 dataset/source/God의 실제 `DIRECT_WATCH` receipt를 제한된 window로 찾는다. current source/receipt tombstone와 정확한 acquisition watermark를 확인한다.
3. 허용 projection의 크기·정확한 JSON 필드·receipt hash·actor audience를 확인한다. descriptor의 world/God/subject/observation과 Event의 observation/event/revision을 source에 연결한다.
4. 기존 `prepareRecordedEvidence`를 통해 `ExperienceRoomEvidence.prepare`와 Watch의 `readExact`를 비동기로 호출한다. 별도의 새로운 owner authority나 게임 상태 DB를 만들지 않는다.
5. 반환 직전, 후속 요청 전, 최종 진단 직전에 기존 `recordedEvidenceCurrent`를 다시 확인한다. UNKNOWN, runtime 부재, 요청 실패, 현재 proof 불일치는 거절한다.

자료 복구/reconciliation이 진행 중이라는 사실을 허용 권한으로 쓰지 않는다. SQL scan·자료 크기·전체 카드 UTF-8 크기를 제한하고, aggregate 예산 때문에 현재 후보를 평가하지 못했다면 다음 페이지에서 다시 시도할 cursor를 보존한다. intrinsically oversized/unsupported 카드만 명시적으로 건너뛸 수 있다. 반환 상태는 계속 `PARTIAL`이며 미공개 자료 수는 노출하지 않는다.

[RecordedObservationSearch](../../../mythictrpg-main/src/main/java/com/sande/mythictrpg/recording/server/RecordedObservationSearch.java)는 source-owner sequence index의 최대128개 source/receipt 평가 창과 다음 창 확인용 자료를 사용한다. source sequence·source key·source 내부 receipt ID로 이어 읽어 한 source의 여러 영수증을 건너뛰지 않는다. 요청 SQL 시간은250ms, payload 누계는256KiB, 한 projection은32KiB와 audience4KiB로 제한한다. payload 길이 metadata를 먼저 읽고 누계 예산에 들어올 때만 같은 read transaction에서 PK로 내용을 읽는다. 전체 카드가 호출 Budget보다 크면 카드 내용을 자르거나 새로운 문장으로 요약하지 않는다.

현재 source보다 새 revision이 존재하면 구 revision을 다시 허용하지 않는다. source/receipt 취소가 진행 중이거나 적용되면 기존 shared authority generation으로 발급 페이지를 무효화한다. 실제 Watch proof의 현재성도 별도로 확인하므로 reconciliation 저장 지연이 과거 권한을 계속 허용하는 이유가 되지 않는다.

읽기 검토 중, 새 revision을 SQL에서 거절하는 것만으로는 이미 발급된 페이지의 `current`가 바뀌지 않는 빈틈을 발견했다. `captureSource`/`appendKnowledge`가 실제로 더 높은 source revision을 삽입할 때만 writer 내부에서 pending/generation fence를 시작하고, commit/실패의 내부 완료에서 정리하도록 보강했다. caller future 취소가 이 정리를 생략하게 하지 않는다. 새로운 독립 source와 늦게 추가된 receipt는 이 교체 fence를 켜지 않는다. 기존 raw reader의 fresh 조회에도 같은 최신 revision/명시적 source tombstone 조건을 맞췄다. 실제 Watch proof는 여전히 유효하지만 source revision만 교체된 경우, 이미 발급된 관찰 페이지의 `current=false`와 fresh 조회 거절을 실제 API fixture에서 확인했다.

## 5. 구현 소유권과 검증 계획

- reader 담당: 새 DTO/search, `MemoryReadSession`, `RecordedMemoryAccess`, 이 문서.
- storage 담당: read-only transaction wrapper. 현재 계획에는 schema migration이 없다.
- 별도 검토 담당: 실제 SQLite + 발급 세션 회귀 fixture.
- 주 작업 담당: counts-only SHADOW 소비자, 진단, 통합 빌드와 현재 인수인계.

필수 회귀는 초기/늦은 취득의 두 시계, 실제 receipt 없는 source, 대상/God/청중 불일치, 공개·다중 신·다른 memory mode 차단, source/receipt/proof 철회, async prepare 실패/timeout/late callback, 정확한 DTO와 JSON shape, UTF-8 whole-card 예산, long-tail continuation, 재시작·무중복·무자동 import를 포함한다.

현재 확인된 새 경로 검증은 다음과 같다.

- 게임 `compileJava` + JAR: 6개 task 성공. 전체 게임 build를 다시 실행한 것이 아니다.
- AI의 [RecordedObservationShadow](../../../mythai-ai-response/src/main/java/com/sande/mythai/response/memory/RecordedObservationShadow.java) 소비자: 가짜 발급 포트 기반52개 focused 검사 통과. 최대3페이지·4개 사건·8KiB로 기존에 계산된 관찰 ID와 비교하며, 내용 없는 `completed/unavailable/legacySelected/legacyMatches`만 진단한다. 기존 기억 조회를 다시 호출하거나 prompt를 바꾸지 않는다.
- [RecordedObservationReadTest](../../../mythictrpg-main/src/test/java/com/sande/mythictrpg/recording/server/RecordedObservationReadTest.java): 실제 ActionLedger/Watch journal과 Recording SQLite, owning-thread exact-proof 준비/현재성 검사를 연결하여 **370개 검사 통과**. 신별 허용 필드·비공개/다중 신/추가 청중 차단, source/receipt/live proof 철회, source supersession, fresh/continuation의 고정 watermark, owner cursor가 archive `W`보다 큰 정상 취득, 128개 창 이후 long-tail, whole-card 예산, canonical/hash 손상 거절, SQLite 재개방 및 전체 raw/Watch/SQLite clean restart의 무중복·지속 철회를 포함한다.
- 앞선 fixture 실행은 Java `Ref` import 모호성으로 컴파일 실패했고, 수정 후에는 `action-ledger-v1` 중복 producer 등록과 재개방 시 source cursor를 0으로 보고한 문제로 각각 실패했다. 실제 adapter가 이미 가진 capability를 테스트 안에서 재사용하고, 재개방 시 실제 durable source head를 보고하도록 수정한 뒤 위 370개 검사가 통과했다. 이전 실패 실행을 성공으로 소급하지 않으며, 기존 manifest의 최초 cutover나 운영 데이터를 초기화하지 않았다.

native 의미 검색 및 이전 raw/derived/capture 테스트의 성공을 이번 작업의 검증으로 대체하지 않는다. 운영 서버 실행·배포·실제 LLM 호출은 별도이며 현재 범위에서 수행하지 않는다.
