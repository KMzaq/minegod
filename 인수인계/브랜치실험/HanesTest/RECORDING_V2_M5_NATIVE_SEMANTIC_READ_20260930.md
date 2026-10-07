# 기록 v2 M5 — native 의미 후보 조회

2026-09-30 / `HanesTest` 전용. [원문 어휘 검색](RECORDING_V2_M5_LEXICAL_SEARCH_20260930.md)과 [근거 있는 파생 해석 조회](RECORDING_V2_M5_NATIVE_INTERPRETATION_READ_20260930.md)의 후속 **bounded 의미 후보 경로**다. 새 의미 검색의 컴파일·핵심 focused 및 게임/AI 전체 offline build를 통과했으며, 세부 결과와 후속 보강·개발 GameTest 상태를 §7에 구분한다. 실제 모델 품질·전체 M5·운영 배포 완료 기록이 아니다.

## 1. 이번 경로가 하는 일

이미 기록된 native 원문의 허용된 prefix를 가리키는 벡터 후보를 키워드/FTS와 **독립적으로** 찾는다. 질문과 원문에 같은 단어가 없어도 저장된 벡터 유사도를 비교할 수 있다. 검색 결과는 원문과 실제 화자·시각·coverage이며, AI가 확정한 세계 사실이나 행동 결과가 아니다.

전역 벡터를 모델에 건네거나 전역 top-K를 뽑은 뒤 비밀을 가리는 방식이 아니다. 현재 dataset·observer·모델 공간의 작은 후보 창을 만들고 SQL 공개 범위 필터와 기존 원문/청취/계보 검증을 통과한 자료만 벡터 BLOB을 읽어 점수를 계산한다.

이것은 native room 자료의 첫 의미 검색 경로다. Watch·Rumor 일반 검색, 외부 vector DB, ANN 확장, 모든 기간의 완전한 최상위 검색, 생성 프롬프트 전환을 한 번에 완료하는 작업이 아니다.

## 2. 선택적 읽기 계약

[MemoryReadSession](../../../mythictrpg-main/src/main/java/com/sande/mythictrpg/recording/api/MemoryReadSession.java)에 안전한 default `UNAVAILABLE`/`false`를 갖는 다음 기능을 추가한다.

```java
semantic(Query query, EmbeddingRecords.QueryVector vector,
         Optional<SemanticReadRecords.Cursor> cursor, Budget budget)
current(SemanticReadRecords.Page page)
```

기존 `Query(text, fromInclusive, untilExclusive)`를 재사용하지만 의미 검색은 공백뿐인 입력 또는1,600 UTF-16 단위를 넘는 입력을 거절한다. `QueryVector.inputHash`는 **공백·대소문자를 임의로 바꾸지 않은 실제 query text**의 SHA-256이어야 한다.

[EmbeddingRecords](../../../mythictrpg-main/src/main/java/com/sande/mythictrpg/recording/api/EmbeddingRecords.java)의 `ModelSpace`는 모델 이름·고정 digest·차원·encoder version을 함께 식별한다. 다른 공간의 벡터를 섞지 않는다. 벡터는 차원과 유한값·0이 아닌 norm을 검사하며, 생성자와 getter에서 배열을 복사한다.

[SemanticReadRecords.Entry](../../../mythictrpg-main/src/main/java/com/sande/mythictrpg/recording/api/SemanticReadRecords.java)는 다음을 반환한다.

| 값 | 의미 |
|---|---|
| `messageId`, `speaker`, `occurredAt` | 실제 원문의 식별·화자·시각 |
| `text` | 실제 벡터로 만든 원문의 정확한 prefix |
| `similarity` | 해당 query/model space와의 유한 cosine, -1~1 |
| `coveredCharacters` | 벡터가 읽은 원문의 Java UTF-16 길이 |
| `totalCharacters` | 원문 전체의 Java UTF-16 길이 |
| `excerpt()` | prefix가 원문 전체보다 짧은지 여부 |

점수가 높아도 발언 내용이 사실이거나 게임 기능이 실행됐다는 뜻이 아니다. 낮은 점수 자체를 storage reader에서 임의의 정답/오답 임계치로 확정하지 않는다. 소비자의 추가 필터와 실제 모델 품질 평가는 별도다.

## 3. 독립 후보 창과 continuation

[RecordedSemanticSearch](../../../mythictrpg-main/src/main/java/com/sande/mythictrpg/recording/server/RecordedSemanticSearch.java)는 schema8의 `embedding_rows`와 `embedding_scope(dataset_id, observer_god, model_fingerprint, created_sequence, id)` index를 사용한다.

- 원문 FTS 일치를 후보의 전제 조건으로 사용하지 않는다.
- 한 창은 최대128개 metadata 후보와 다음 창 확인용1개다. 벡터 내용을 이 단계에서 가져오지 않는다.
- archive 생성 sequence와 ID를 내림차순으로 한 복합 keyset을 사용한다. cursor는 sequence 한 값만 저장해서 같은 transaction의 형제 row를 빠뜨리지 않는다.
- session 생성 시 archive watermark `W`를 고정하며 `created_sequence <= W`인 벡터 row만 읽는다. 조회 도중 늦게 생성된 색인은 기존 session으로 들어오지 않는다.
- 원문 발생 시각의 optional from/until 조건은 `Instant`로 비교한다. 과거 전체에 대한 고정30일 만료 조건은 없다.
- 한 창에서 검증된 후보의 cosine을 정렬하고 row/byte 예산 안에서 상위 후보를 반환한다. 동점은 message ID로 결정한다.
- cursor는 검토한 창 뒤로 이동한다. **그 창의 모든 후보를 다음 페이지에서 반드시 다시 반환하는 목록 API가 아니며, 전역 top-K 보장도 아니다.**

현재 고정8회 호출 예산에서는 임의로 먼 과거의 모든 벡터를 한 번에 검토하지 않는다. 여러 창을 따라 오래된 자료에 도달할 수 있지만 자료량·권한·시간 예산 때문에 끝까지 가지 못할 수 있다. 결과가 비어 있다고 “그런 기억이 전혀 없다”로 단정하면 안 된다. 모든 페이지는 `PARTIAL`이다.

## 4. 벡터를 읽기 전의 권한 검증

SQL의 현재 observer/receipt·공개 범위 prefilter 뒤에도 [RecordedRoomSearch](../../../mythictrpg-main/src/main/java/com/sande/mythictrpg/recording/server/RecordedRoomSearch.java)와 native binding 검사를 재사용한다.

1. dataset, God, recording policy/memory mode와 public/private 청중 일치
2. 실제 `GAME_HEARD` 및 필요한 플레이어 수신 완료
3. 원문 part 순서·길이·hash, native source revision/hash, 현재 knowledge receipt/hash, 철회 부재
4. 필수 native 부모의 지식/공개 권한·순환·시간 순서; 외부 evidence가 있으면 이 경로에서 거절
5. 저장된 embedding metadata와 실제 source/receipt/actor/disclosure의 일치
6. 정확한 encoder prefix 길이·내용 hash와 원문 전체 길이 일치
7. 여기까지 통과한 후에만 벡터 BLOB의 길이·SHA-256·float32 little-endian·차원·유한값/norm 검사와 cosine 계산

이 검사는 하나의 SQLite read transaction에서 수행한다. source나 receipt가 철회되면 원문 authority generation과 진행 중 철회 guard가 기존에 발급한 semantic 페이지도 즉시 무효화한다. immutable 벡터 row의 추가 자체는 원문/semantic 권한 generation을 바꾸지 않는다. 늦은 row는 고정 watermark로 격리한다.

검색 cursor는 session과 query의 전체 값(시각 조건 포함), 모델 공간, input hash, 벡터 값 전체의 fingerprint에 묶인다. 같은 input hash를 유지하면서 벡터만 바꾸거나 다른 session의 cursor를 가져와 이어 읽을 수 없다. public DTO를 복제한 page도 발급 객체 identity가 아니므로 `current`에서 거절한다.

### 실제 semantic 페이지에서 파생 해석으로 연결하는 후속 추가

`interpretations(SemanticReadRecords.Page, cursor, budget)` 선택적 overload와 `compareSemantic` 소비자 경로를 추가했다. **후속 게임 전용114개 검사와 새 소비자 검사를 포함한 AI 그룹374개가 통과**했으며 §7의 원래 schema8 checkpoint와 구분한다. 발급 semantic 페이지를 가짜 raw 페이지로 포장하지 않고 원래 identity/current 조건을 유지한다. semantic gate OFF, source 철회, 세션/턴 변경이면 이를 시작점으로 받은 해석도 무효다. 같은 bounded receipt lookup과 전체 input·source·quote·link 검증을 재사용하며 임의 message ID 조회 권한을 열지 않는다.

새 소비자는 정확한 원본 페이지들을 독립 재검증하고 인용·정정 link의 내용 없는 집계만 남긴다. 정정이 연결됐다고 “세계 사실이 수정됐다”고 처리하지 않는다. 특히1,600자 prefix 밖의 허용된 extraction 인용이 별도로 검증됐더라도 이를 semantic 점수의 근거로 표시하거나 벡터 coverage를 늘리지 않는다. 상세 경계는 [native 파생 해석 조회](RECORDING_V2_M5_NATIVE_INTERPRETATION_READ_20260930.md)를 따른다.

## 5. coverage와 예산

encoder는 `native-prefix-1600-v1`이다. 최초 최대1,600 UTF-16 단위만 사용하며 끝에서 surrogate pair를 나누지 않는다. query와 prefix를 임의로 번역·소문자화·요약하지 않는다.

벡터가 prefix까지만 읽었는데 전체 원문이나 뒤쪽 꼬리 내용을 그 점수의 근거처럼 반환하지 않는다. 장문 뒤에만 있는 정정·비밀·사건은 이 벡터 하나로 검색됐다고 주장할 수 없다. 더 넓은 coverage/chunk 전략은 별도 과제다.

- 요청당 SQL/Java 예산250ms
- 기존 shared RAW 검증의 원문 하나1MiB·요청 합계2MiB, node/계보 상한 유지
- 벡터는 최대4,096차원, float32 BLOB 최대16KiB
- 반환 최대8개, Budget 총256~65,536 UTF-8바이트
- 원문/해석/semantic 조회가 session 최대8회·60초·동시에1요청 한도를 공유

byte 한도는 인용문 길이만이 아니라 actor·시각·점수·coverage 등을 포함한 명시적 wire 구조로 계산한다. `Instant`를 plain Gson reflection으로 직렬화하지 않는다. prefix card를 예산에 맞추려고 자르거나 다른 부분으로 바꾸지 않는다. card 하나가 해당 예산에 안 들어오면 건너뛰며, 충분한 예산으로 새 조회를 시작하면 다시 찾을 수 있다.

앞선 원문들이 공용 byte/node 예산을 소진했거나 SQL/deadline이 끝나 현재 source를 검증하지 못했다면 그 source의 cursor를 전진시키지 않는다. 반대로 원문 자체가 상한을 넘는 등 새 요청에서도 지원되지 않는 source는 건너뛰며 `PARTIAL`을 유지한다.

## 6. 활성화·소비자 경계

실제 게임의 session은 `RecordingRuntime.embeddingEnabled(server)`를 별도로 확인한다. `config/mythictrpg/recording-embedding.json`의 명시적인 `SHADOW` 및 archive `SHADOW`가 필요하며 기본은 `OFF`다. 기존 legacy semantic `ON` 설정만으로 native 경로가 자동 활성화되지 않는다. 허용하는 native 설정 형태는 다음과 같으며, 파일이 없으면 OFF로 취급한다. 이 작업에서 운영 설정 파일을 생성·활성화하지 않았다.

```json
{"schemaVersion": 1, "embeddingMode": "OFF"}
```

현재 이 파일은4KiB 이내의 정확한 두 필드와 `OFF`/`SHADOW`만 허용한다. 실제 native gate에는 저장소 READY·서버 runtime 유효성도 필요하다. 백그라운드 모델 작업은 여기에 온라인 플레이어0명·기억 모드 OFF 아님·quota admission 조건을 더한다.

session은 생성 당시 허용 여부를 보관하고 사용 때도 다시 확인한다. OFF에서 생성한 session이 뒤늦은 설정 변경만으로 새 읽기 능력을 얻지 않으며, 현재 gate가 꺼지면 기존 semantic page를 사용할 수 없다. package-private 테스트 생성자의 기본 허용값을 실제 게임 진입 경로로 오해하지 않는다.

AI 응답 쪽은 다음 경계로 연결한다.

- [RecordedEmbeddingModel](../../../mythai-ai-response/src/main/java/com/sande/mythai/response/memory/RecordedEmbeddingModel.java): 기존 `ai-memory-index.json` 모델 설정과 neutral `OllamaMemoryBackend.embed`를 재사용한다. 기존 model digest·차원·응답 검사 후 값만 native `ModelSpace`로 반환하며 legacy 기억 entry나 vector ID로 위장하지 않는다. 모델 설정 자체가 OFF이면 호출하지 않는다.
- [RecordedEmbeddingRuntime](../../../mythai-ai-response/src/main/java/com/sande/mythai/response/memory/RecordedEmbeddingRuntime.java): game-issued lease가 제공한 exact prefix만 한 worker에서 인코딩하고 벡터 값만 commit한다. source/receipt 바인딩은 게임 포트가 소유한다. foreground가 필요하면 협력적 interrupt로 물러나며 실제 transport가 반환되기 전 permit을 임의로 반납하지 않는다.
- [RecordedEmbeddingService](../../../mythai-ai-response/src/main/java/com/sande/mythai/response/memory/RecordedEmbeddingService.java): tick은 자격만 확인하고 설정/HTTP/저장은 비동기로 처리한다. projection과 embedding은 같은 process-wide admission을 쓰며, clock의 짝수/홀수 대신 실제 허용된 시도 차례를 번갈아 배정한다. 새로운 병렬 모델 pool을 만들어 기존 대화 요청을 우회하지 않는다.
- 명시적 회상 발언만 별도 game-issued session과 query embedding을 시도한다. 같은 서버에서 한 query worker만 허용하며 foreground가 있으면 대기열 없이 포기/중단할 수 있다. 이는 best-effort SHADOW 비교이지 모든 대화에 추가 모델 호출을 강제하거나 답변을 기다리게 하는 생성 단계가 아니다.
- [RecordedSemanticShadow](../../../mythai-ai-response/src/main/java/com/sande/mythai/response/memory/RecordedSemanticShadow.java): 최대3페이지·4개 선택 자료·16KiB 예산 안에서 발급 페이지의 현재성을 다음 조회와 최종 집계 전에 재검사하고, 내용 없는 집계만 기록한다. 최초4KiB 예산은 한글1,600자(본문만4,800 UTF-8바이트)에 metadata를 더한 card를 받지 못하므로16KiB로 보강했다. 기존 어휘 RAW 비교의4KiB 예산을 바꾼 것은 아니다. similarity 임계치는 기존 실행 설정을 사용하며 조회를 허용하는 ACL 대신 쓰지 않는다. 새 자료를 생성 프롬프트·게임 기능에 반영하지 않는다.

별도 모델 호출이 가능한 코드를 추가했다는 사실과 실제로 활성화/호출했다는 사실은 다르다. 이 작업에서 운영 gate 변경이나 실제 Ollama embedding을 실행하지 않았다. SHADOW가 foreground 선점으로 건너뛰어진 일을 실제 품질 향상이나 실패한 게임 행동으로 해석하지 않는다.

## 7. 검증 상태와 후속 과제

새 schema8/의미 조회의 게임/AI 소스는 컴파일됐고 다음 focused 검증을 통과했다. 게임 focused 실행은8개 task이며, 이어 게임 전체 offline build도 **53개 task로 통과**했다. 해당 전체 build에서 embedding 저장소 검사는113개, semantic reader 검사는555개를 통과했다. AI 전체 offline build는 **42개 task로 통과**했고433개 engine class의 패키징·게임 클래스 중복 없음도 확인했다. 실제 SQLite/game port에서 가짜 embedding backend를 거쳐 읽는 end-to-end pipeline은103개 검사를 통과했다.

그 뒤 live-lease 복구 guard를 포함한 저장소 후속 focused 검증도 **119개 검사, 8개 task + JAR 생성으로 통과**했다. 이는 전체 build 이후의 추가 focused 검사이며 전체 build를 한 번 더 실행했다고 합쳐 쓰지 않는다.

- `build/recording-semantic-authority-20260930`: 새 개발 fixture에서 authority GameTest **1/1 required 통과**, Gradle 9개 task. semantic OFF의 typed 거절도 포함하며, fixture 전용 설정을 준비했다.
- `build/recording-semantic-final-wire-20260930`: 세 모듈 연결 개발 GameTest **1/1 required 통과**, Gradle 8개 task. 가짜 LLM 응답을 쓰는 연결 검증이다.

개발 fixture 부팅을 운영 `server/`의 검증으로 부르지 않는다. 앞선 schema7 게임50 task·AI40 task 성공을 새 경로의 전체 검증으로 대신하지 않는다.

| focused 검사 | 통과 check 수 |
|---|---:|
| [RecordedSemanticReadTest](../../../mythictrpg-main/src/test/java/com/sande/mythictrpg/recording/server/RecordedSemanticReadTest.java) — 실제 SQLite와 발급 session | 555 |
| native embedding 저장소 | 최초89 → 전체 build113 → live-lease focused119 |
| 명시적 native embedding 설정 | 9 |
| AI SemanticShadow | 55 |
| AI embedding 모델/worker runtime | 67 |
| [RecordedEmbeddingPipelineTest](../../../mythai-ai-response/src/test/java/com/sande/mythai/response/memory/RecordedEmbeddingPipelineTest.java) — 실제 SQLite/game port + 가짜 embedding backend | 103 |

검증 범위는 비키워드 독립 후보,128개보다 오래된 후보의 continuation, 모델 공간/시각 경계, private 청중, 원문·receipt 철회, 늦은 색인 watermark, query/vector/cursor 조작, prefix/surrogate·byte 한도·손상 벡터, gate OFF다. 원문과 query 단어가 겹치지 않아도 정해진 가짜 벡터로 검색되는 경로를 확인했다. 이는 embedding 모델의 실제 의미 이해를 평가한 것이 아니다. 실제 Ollama·네트워크 모델 호출·운영 설정 활성화·JAR 배포는 수행하지 않았다.

schema8 checkpoint 이후의 semantic-seed 해석 overload는 `RecordedSemanticInterpretationReadTest`114개 검사를 통과했다. AI 소비자 추가 검사를 포함한 `MemoryAudienceTest` 그룹374개도 통과했다.374개 전체가 semantic-seed 전용 검사는 아니다. 위119개 저장소·103개 fake backend pipeline·개발 GameTest 결과를 이 새 추가의 통과 결과로 합치거나 새 전체 build를 완료한 것으로 소급하지 않는다.

다음은 여전히 미완료 범위다.

- 장문 전체/chunk coverage와 임의로 오래된 자료에 대한 효율적인 독립 후보 생성
- 실제 모델의 paraphrase recall·성능·적정 임계치·랭크 결합 평가
- Watch·Rumor 외부 원본 검색과 각 owner의 현재성 계약
- 명시적으로 검토한 생성 프롬프트 전환 및 게임 응답 수용 검증
- 운영 JAR 배포·운영 DB 이관·실제 LLM/클라이언트 인게임 확인
