# 기록 v2 M5 — 권한을 유지하는 원문 어휘 검색

2026-09-30 / `HanesTest` 개발 작업본. [M4 native 파생 기억](RECORDING_V2_M4_NATIVE_PROJECTIONS_20260930.md) 이후의 **원문 검색 경로**다. 전체 기억/RAG 전환, 운영 배포, 실제 Ollama·인게임 검증 완료 문서가 아니다. 새 SQLite schema는 **7**이다. 색인 timeout·검색 cursor·M4 보완과 [native 파생 후보 조회](RECORDING_V2_M5_NATIVE_INTERPRETATION_READ_20260930.md)를 포함해 게임 전체 offline build 50 tasks, AI 전체 build 40 tasks를 통과했다. 개발 GameTest 결과와 운영 수용은 별도다.

## 1. 이번 구현의 범위

기존 기록 v2 원문에 실제 SQLite FTS5 trigram 색인을 추가하고, 이미 색인된 자료와 아직 색인되지 않은 자료를 별도 경로로 검색한다. 검색 결과가 존재한다는 것과 해당 신이 그 내용을 알고 현재 청중에게 말해도 된다는 것은 계속 별개다.

- 원문·수신 증명·신별 지식·게임 현재성 검증을 대체하지 않는다.
- 기존 게임·레거시 저널을 새로 수입하지 않는다. 기록 v2의 `messages`/`message_parts`에 이미 저장된 원문만 처리한다.
- 같은 원문을 신별로 복제해 색인하지 않는다. 한 원문의 색인과 여러 신의 실제 수신 증명은 서로 다른 데이터다.
- 새 검색은 SHADOW이며, 검색 결과를 실제 대화 생성 프롬프트나 게임 액션의 근거로 자동 전환하지 않는다.
- vector/embedding, 의미 유사도 reranker, 파생 `memories` 검색, Watch·Rumor의 일반 검색 공개는 이번 범위가 아니다.

## 2. 구현 위치와 계약

| 위치 | 역할 |
|---|---|
| [RecordingLexicalIndex](../../../mythictrpg-main/src/main/java/com/sande/mythictrpg/recording/server/RecordingLexicalIndex.java) | 공유 정규화, bounded background 색인, 진행/부분 실패 상태 |
| [RecordingSchema](../../../mythictrpg-main/src/main/java/com/sande/mythictrpg/recording/server/RecordingSchema.java) | schema7의 contentless FTS·manifest·진행·skip 테이블 |
| [WorldRecordingService](../../../mythictrpg-main/src/main/java/com/sande/mythictrpg/recording/server/WorldRecordingService.java) | 기존 예약된 단일 writer, 색인 배치/읽기 snapshot |
| [RecordedRoomSearch](../../../mythictrpg-main/src/main/java/com/sande/mythictrpg/recording/server/RecordedRoomSearch.java) | indexed/raw 후보 생성, 실제 원문·시각·권한 검증 |
| [RecordedMemoryAccess](../../../mythictrpg-main/src/main/java/com/sande/mythictrpg/recording/server/RecordedMemoryAccess.java) | 불투명 cursor, session watermark, 현재 게임 증명, page 권한 |
| [RecordingRuntime](../../../mythictrpg-main/src/main/java/com/sande/mythictrpg/recording/server/RecordingRuntime.java) | idle SHADOW tick의 비동기 색인 요청 |

외부 [MemoryReadSession](../../../mythictrpg-main/src/main/java/com/sande/mythictrpg/recording/api/MemoryReadSession.java)의 Query/Budget/불투명 Cursor 계약은 유지한다. 내부 cursor만 indexed/raw 두 위치를 보관한다. 모델이 SQL·원본 sequence·임의 cursor 권한을 받지 않는다.

## 3. schema7와 원문 보존

| 테이블 | 내용 |
|---|---|
| `recording_lexical_fts` | `body` 한 열의 contentless FTS5 trigram. `rowid`는 원문 `ingest_sequence`. `case_sensitive 1`, `contentless_delete=1`. 읽어낼 두 번째 원문 body를 저장하는 테이블이 아니다. |
| `recording_lexical_manifest` | 원문 sequence/dataset/message ID/hash, normalization version, **색인 commit의 indexed_sequence** |
| `recording_lexical_progress` | dataset별 확정 색인 cursor, 처리·skip 수와 상태 |
| `recording_lexical_skips` | 현재 크기 상한 또는 반복된 시간 예산을 넘는 원문의 ID/hash/사유. 원문을 삭제하거나 성공 색인으로 표시하지 않는다. |

공유 정규화 버전은 `java-root-lower-trigram-v1`이다. writer와 reader가 같은 Java `Locale.ROOT` 소문자 변환을 사용한다. SQLite `lower()`가 모든 Unicode 문자를 같은 방식으로 변환한다고 가정하지 않는다. 원래 대소문자·공백·개행이 있는 `message_parts`는 바꾸지 않는다.

schema6→7 등 지원되는 이전 기록 v2 이관은 기존 시작 시 예약된 트랜잭션에서 부가 테이블을 만든다. 이관만으로 기존 원문 전부를 한 번에 읽거나 색인하지 않는다. 운영 DB에 적용한 검증은 아직 없다. schema7을 연 DB를 구 schema 전용 JAR로 단순 다운그레이드할 수 있다고 가정하지 않는다.

## 4. 선택적인 background 색인

원문 capture는 색인 완료를 기다리지 않는다. `pumpLexicalIndex()`가 이미 확정된 원문을 제한된 배치로 읽고 기존 단일 writer에 별도 트랜잭션을 요청한다. 그 트랜잭션에서 FTS posting·manifest·진행 cursor를 함께 commit한다. 실패하면 부분 manifest나 거짓 진행 완료를 남기지 않는다.

- 1배치 최대16개 원문, 원문 합계1MiB, 원문 하나 최대1MiB다.
- SQL/Java 처리에 시간 예산을 적용하며, 계속 남은 자료는 다음 배치로 넘긴다.
- 이미 존재하는 manifest는 원문 ID/hash/version 일치를 확인하고 재사용한다. `indexed_sequence`를 나중에 이전 시점으로 덮어쓰지 않는다.
- `indexed_sequence`는 원문 생성 sequence가 아니라 **실제로 색인이 commit된 새 archive sequence**다.
- 큰 원문·quota·일시 admission 거절·색인 오류를 원문 소실이나 성공 완료로 숨기지 않는다. 원문은 남기고 선택적 색인 상태로 진단한다.
- 색인 작업이 시간 예산으로 rollback되면 별도 작은 트랜잭션에 재시도 상태를 남기고 다음 배치를 원문1개로 줄인다. 최초 배치 timeout을 포함해 같은 cursor에서 총3회의 제한된 시도이며, 첫 실패 후의 재시도는 단일 원문 단위다. 마지막 단일 원문 처리도 시간 예산을 넘으면 `INDEX_TIME_BUDGET_EXCEEDED`로 skip하여 뒤의 원문을 계속 처리한다. 이 원문은 manifest를 얻지 않으며 raw 검색 경로에 남는다.
- 일시적인 SQLite lock/checkpoint busy는 원문 자체의 시간 초과와 구분한다. `LEXICAL_DATABASE_BUSY`로 지연하고 원문 timeout 횟수나 영구 skip을 추가하지 않는다. optional 오류만으로 정상 RAW 저장소를 FULL/UNAVAILABLE로 바꾸지 않되, 실제 손상·회계 불확실성은 안전하게 중단한다.
- Runtime은 SHADOW이면서 온라인 플레이어가0명일 때 주기적으로 요청한다. 실제 SQLite 대기나 원문 읽기는 게임 tick에서 하지 않는다. 서비스의 background admission과 동시 실행 제한도 통과해야 한다.
- 별도 LLM·embedding 요청이 필요하지 않다. 이 작업 때문에 모델을 실행하거나 다운로드하지 않았다.

`caughtUp=true`는 **그 시점까지 원문 cursor를 따라잡았다는 뜻**이다. skip이 있거나 지원하지 않는 source가 있으므로 전체 게임 기억/검색 coverage가 완전하다는 뜻이 아니다. 이후 새 원문이 추가되면 다시 처리할 자료가 생긴다.

## 5. 검색 중 바뀌지 않는 두 경로

대화 read session이 시작할 때 archive watermark `W`를 고정한다. 원문은 `message_sequence <= W`인 것만 대상이다.

**indexed 경로 소속 조건:** manifest의 dataset/message ID/raw hash/normalization version이 현재 원문과 맞고 `indexed_sequence <= W`여야 한다. 이 조건이 아니면 raw 경로 소속이다.

예를 들어 session이 시작할 때 미색인이던 원문을 다음 페이지를 읽기 전에 색인해도, 새 `indexed_sequence`는 `W`보다 크므로 그 session에서는 계속 raw 경로로 읽는다. 새로 시작한 session만 새 색인을 사용한다. 이 원칙 때문에 두 경로 사이에서 원문이 사라지거나 두 번 반환되지 않는다.

내부 `SearchPosition`은 다음을 분리한다.

- indexed 후보의 이전 sequence와 소진 여부
- raw 후보의 이전 sequence와 소진 여부
- 다음에 먼저 검사할 경로
- 해당 query에 indexed 경로를 사용할 수 있는지

두 경로를 번갈아 검사하고, page의 row/byte 예산 때문에 끝나도 다음 경로를 기억한다. 오래된 indexed 희귀어가 최근 raw 자료에 계속 밀리지 않으며, raw 자료도 다음 순서를 얻는다. 이전 테스트용 `long before` overload는 명시적인 bounded raw-only 경로로 남았고, 실제 `MemoryReadSession`은 두 경로를 사용한다.

## 6. 단어·기호·긴 검색어·시각

- 기본은 최대8개 Unicode 단어의 OR 부분 문자열 검색이다. 형태소 분석·동의어 검색·의미 검색은 아니다.
- 한 글자도 버리지 않는다. `신` 같은 입력이 빈 키워드가 되어 무관한 최근 대화로 바뀌지 않는다.
- 기호만 있는 입력은 입력 전체를 literal로 다룬다. `%`, `_`, 따옴표, 이모지를 SQL wildcard/FTS 명령으로 실행하지 않는다. FTS MATCH 값은 parameter로 전달하고 각 term의 따옴표를 escape한다.
- 의도적으로 빈 query만 최근의 허용된 기록을 찾는다.
- OR term 중 하나라도3 codepoint 미만이거나 FTS에 그대로 사용할 수 없는 NUL이 있으면 raw-only로 검색한다. 이때 이미 색인된 원문도 raw에서 검사하므로 짧은 term의 일치를 놓치지 않는다.
- term이256 codepoint 이하면 literal FTS phrase를 사용한다. 그보다 길면 시작·중간·끝의 최대3개 trigram을 AND로 결합해 후보만 좁힌다. 최종적으로 **완전한 원래 term**이 실제 원문에 있는지 다시 검사한다. trigram만 따로 있는 가짜 일치를 답변 자료로 반환하지 않는다.
- Query의 기존8192 UTF-16 단위 상한을 유지한다. 원문 part 경계를 넘는 term도 재조립·hash 검증된 제한된 원문으로 확인한다.
- 발생 시각은 `Instant`로 직접 비교해 `fromInclusive`/`untilExclusive`를 적용한다. `julianday`의 반올림 때문에 나노초 경계의 이웃 기록이 섞이지 않는다.
- byte 예산 때문에 excerpt가 필요하면 실제 match 근처에서 UTF-8 경계를 지킨다. `İ`처럼 소문자 변환 후 길이가 늘어나는 문자도 folded 위치를 원문 위치로 다시 대응시킨다.

## 7. 후보가 지식 권한을 주지 않는 이유

검색 후보는 아직 신의 지식이 아니다. 결과 반환 전 다음을 유지한다.

1. dataset, native room producer, recording policy와 memory mode 일치
2. 현재 청중과 원문의 public/private 공개 범위 일치
3. 실제 완전한 `GAME_HEARD`·플레이어 delivery receipt 확인
4. source hash, native knowledge pointer, delivery view hash, 철회 여부, knowledge 생성 watermark 확인
5. 원문 part 순서·UTF-8 길이·hash 확인과 완전한 lexical match
6. 부모 `sourceMessages` 계보의 권한·시간 순서·순환·깊이 확인
7. 외부 evidence의 기존 game-owned 준비·현재성 검증
8. callback 시 session/현재 turn/runtime/authority generation 재검증

색인 posting이 남아 있어도 receipt가 철회되면 읽을 수 없다. 신이 실제로 듣지 않은 자료, private 청중에게 없는 플레이어, 비공개 자료의 공개 전환, 조상 대사를 통한 정보 세탁을 허용하지 않는다. 원문 시각 검색 조건은 검색 대상에 적용하며, 그 자료를 정당화하는 부모 증명까지 임의로 지우는 조건으로 사용하지 않는다.

## 8. 조회 예산과 `PARTIAL`의 의미

각 경로는 manifest·ACL 필터 **이전**에 최대129개 sequence 후보만 만든다. 필터 뒤에 LIMIT를 붙여 드문 일치를 찾느라 전체 DB를 훑는 방식이 아니다. 한 번의 요청에서 실제 처리하는 후보는 두 경로 합계 최대128개다.

- SQL/Java 요청 예산250ms, 읽는 원문 하나1MiB·합계2MiB
- page 반환1~8행, 총256~65,536 UTF-8바이트
- Query 길이8192 UTF-16 단위, term 최대8개
- session 최대8회 호출과 기존60초 lease
- 조상 깊이32·node cache256·외부 evidence64의 기존 상한

시간·원문·후보 예산으로 중단된 자료를 모두 검색했다고 표시하지 않는다. 앞선 후보들이 요청 전체의 byte/node 예산을 썼거나 SQL/deadline이 끝나 현재 후보를 검증하지 못했다면, 그 경로의 cursor를 후보 검사 전으로 복구한다. 다음 페이지는 새 예산으로 같은 후보부터 다시 검사한다. 반면 원문 자체가1MiB를 넘거나 후보 하나의 필수 조상 합계가2MiB를 넘는 등 새 요청으로도 허용되지 않는 자료는 명시적으로 건너뛰어 오래된 정상 자료를 계속 찾는다. 시간·호출 횟수와 고유 source 한도 때문에 전체 coverage는 여전히 부분적이며, 무제한 재시도는 하지 않는다.

사용자/모델에게 반환하는 페이지는 계속 `PARTIAL`이다. next 유무는 private 일치 개수 대신 기존 고정8회 호출 한도를 따른다. 빈 결과를 “과거에 그런 일이 전혀 없었다”는 증명으로 사용하면 안 된다. FTS가 있더라도 부분 색인, 짧은 term, 너무 큰 원문, 지원하지 않는 source, 시간·횟수 예산 때문에 전체 coverage를 보장하지 않는다.

## 9. 검증 상태

schema7 핵심 통합과 후속 검증 결과는 다음과 같다. SQLite interrupt 복구·lock 구분·재시도, query byte/node cursor, M4 fairness/late-parent 보완, typed 파생 조회까지 포함한 게임 전체 offline build 50 tasks와 AI 전체 build 40 tasks를 통과했다. 개발 native 권한 GameTest와 게임·AI·콘텐츠 3모듈 wire GameTest도 각각1/1, exit0으로 확인했다. **운영 배포·실제 모델/인게임 수용을 뜻하지 않는다.**

| 검사 및 실행 시점 | 통과 check 수 |
|---|---:|
| `RecordingLexicalIndexTest` — 후속 전체 build | 110 |
| `RecordedRoomReadTest` — 후속 전체 build | 719 |
| M4 projection 저장 검증 — 후속 전체 build | 327 |
| `RecordedInterpretationReadTest` — 후속 typed 조회 | 97 |
| `RecordingProjectionAuthorityTest` — 후속 revision/취소 | 30 |
| 기존 knowledge 저장 검증 — 초기 통합 | 65 |
| 기존 room capture — 후속 focused | 40 |
| AI의 실제 SQLite + fake backend parent pipeline — 초기 통합 | 66 |
| AI memory audience / SHADOW page — 초기 통합 | 332 |
| AI projection extractor — 초기 통합 | 183 |

AI engine package 검사는 후속 전체 build에서425개 class 및 게임 class 중복 없음으로 통과했다. fake backend 테스트는 실제 Ollama 호출이나 응답 품질 검증이 아니다.

- [RecordedRoomReadTest](../../../mythictrpg-main/src/test/java/com/sande/mythictrpg/recording/server/RecordedRoomReadTest.java): 기존 source/receipt/DAG/session 테스트에 한 글자·기호·Unicode, 정확한 시각, raw continuation, 실제 FTS 희귀어, 조회 중 색인, 뒤늦은 원문 제외, 짧은 term 혼합, literal 따옴표,8192자와 gram-only 오탐, 실제 receipt 철회를 추가했다.
- [RecordingLexicalIndexTest](../../../mythictrpg-main/src/test/java/com/sande/mythictrpg/recording/server/RecordingLexicalIndexTest.java): 실제 contentless FTS, bounded 배치, atomic 실패 격리, 큰 원문 skip, quota, mode, schema 이관을 검증하도록 추가했다.
- 최초 raw 검색 회귀 실행은 테스트의 무관한 문장에 `최신`을 써서 `신` 부분 문자열도 일치하는 fixture 오류로 실패했다. `최근`으로 수정했으며, 이 실패를 통과로 세지 않는다.
- 후속 게임 전체 build에서는 새 timeout 복구 fixture가 실패했다. SQLite interrupt 이후 native transaction이 이미 rollback됐지만 JDBC 상태가 남는 경우를 복구하도록 수정했다. 새로 시작한 빈 transaction임을 확인한 뒤 JDBC 상태를 복구하며, 불확실한 기존 transaction을 commit하지 않는다. 수정 후 lexical 110 checks를 통과했다. 처음 전체 build 실패를 성공으로 소급하지 않는다.
- 요청 전체의 byte/node/deadline 소진 전에 cursor를 먼저 전진시키는 문제도 수정했다. 두 개의1MiB 무관 원문 뒤의 작은 일치가 다음 페이지에서 복구되고, 본질적으로 큰 조상 묶음은 건너뛰어 정상 기록으로 진행하는 검사를 포함해 권한 검색 719 checks를 통과했다.
- 이후 typed 조회에서 Java `Instant`의 암묵적 JSON 직렬화가 실패해 정상 카드를 누락시키는 문제를 실제 SQLite 검사로 발견했다. 시간값을 ISO 문자열로 명시 변환한 전체 카드 wire-byte 계산으로 수정했으며, typed 조회97 checks에 시간/메타데이터 용량과 통째 반환 조건을 포함했다.
- 게임 50 tasks / AI 40 tasks 전체 build와 개발 JAR 생성은 완료했다. native 권한 GameTest는 초기 fixture 설정 누락으로 timeout된 뒤 설정을 보완하여8 tasks,1/1, exit0으로 통과했다. 후속 3모듈 wire GameTest도1/1, exit0으로 통과했다. 첫 timeout을 성공으로 소급하지 않는다. 운영 JAR 배포·기존 운영 DB 이관·운영 서버 부팅·실제 LLM/인게임 검증은 완료하지 않았다.

## 10. 남은 일

1. 이후 의미 검색 수직 경로의 검증은 별도로 기록한다. 위 schema7/typed 조회 결과를 새 의미 검색 코드의 검증으로 소급하지 않는다.
2. native 파생 후보 조회는 [별도 문서](RECORDING_V2_M5_NATIVE_INTERPRETATION_READ_20260930.md)의 제한된 SHADOW 범위다. Watch·Rumor 및 의미 검색 범위와 권한 계약은 계속 별도로 연결한다. 이번 색인만으로 완료하지 않는다.
3. 실규모의 색인 비용·quota 예약·희귀어/짧은 검색어 recall을 측정한다. 모든 기간의 모든 상황에 완전한 검색이 된다고 가정하지 않는다.
4. 새 검색을 생성 프롬프트에 사용할지 별도 SHADOW 비교·권한/품질 검증을 거쳐 결정한다. 현재 기존 운영 응답 경로를 조용히 교체하지 않는다.
