# 기록 v2 M5 — native 파생 해석의 제한된 조회

2026-09-30 / `HanesTest` 개발 작업본. [M4 파생 기억 저장](RECORDING_V2_M4_NATIVE_PROJECTIONS_20260930.md)과 [M5 원문 어휘 검색](RECORDING_V2_M5_LEXICAL_SEARCH_20260930.md)의 후속 범위다. **전체 기억 검색·운영 배포·실제 모델 응답 전환 완료 문서가 아니다.** 아래 구현의 컴파일·테스트 결과는 §7에 구분한다.

## 1. 목적과 한계

검색된 원문과 관련된 AI 해석을 읽을 수 있게 하되, 해석을 게임의 확정 사실로 바꾸지 않는다. 예를 들어 “내일 돌아올게”와 “그 약속은 취소할게”를 연결하는 `CANCELS`는 **약속 취소 발언에 대한 후보 해석**이다. 실제 퀘스트 취소나 게임 상태 변경을 실행했다는 뜻이 아니다.

- 기존 원문은 수정·삭제하지 않는다. 이전 발언과 정정 발언의 실제 화자·시각·정확한 인용을 함께 보존한다.
- `EVENT`, `RELATIONSHIP`, `SUMMARY` 모두 `CANDIDATE`다. 실제 관계 수치·평판·진행도 변경 권한이 없다.
- 임의의 message ID나 archive 탐색 권한을 AI에 제공하지 않는다. 현재 session이 발급하고 아직 유효한 원문 페이지를 시작점으로 삼는다.
- 이번 경로는 native room 자료만 지원한다. 외부 Watch·Rumor 증명을 포함한 자료는 지원 완료로 간주하지 않으며 거부한다.
- 이 문서의 최초 checkpoint는 새 DB schema 없이 schema7의 기존 projection 테이블·receipt index를 이용했다. 후속 전체 입력 무결성 변경은 [schema10 기록](RECORDING_V2_PROJECTION_INPUT_INTEGRITY_20260930.md)을 따른다.
- 결과는 SHADOW 진단용이다. 생성 프롬프트에 해석을 주입하거나 `ON`으로 전환하는 작업이 아니다.

## 2. 조회 계약

[MemoryReadSession](../../../mythictrpg-main/src/main/java/com/sande/mythictrpg/recording/api/MemoryReadSession.java)에 다음 선택적 기능을 추가했다. 기존 구현은 default `UNAVAILABLE`/`false`로 안전하게 남는다.

```java
interpretations(issuedRawPage, Optional<InterpretationReadRecords.Cursor>, Budget)
interpretations(issuedSemanticPage, Optional<InterpretationReadRecords.Cursor>, Budget)
current(InterpretationReadRecords.Page)
```

**후속 추가 상태: semantic-page overload는 소스 구현 및 게임 전용 회귀114개 검사를 통과했다.** 기존 raw-page 검증 성공을 이 overload의 검증으로 소급하지 않는다. 의미 검색으로만 찾은 원문도 취소/정정 후보를 조회할 수 있도록 실제로 발급된 `SemanticReadRecords.Page`를 받는다. raw Page를 새로 조립해 권한을 우회하지 않고, 내부 seed handle이 원래 페이지 identity·원문 ID 목록·해당 페이지의 `current` 조건을 보존한다. projection generation과 모든 input 검증은 그대로 적용하며, semantic gate가 OFF가 되면 그 페이지를 기반으로 받은 해석도 사용할 수 없다.

[InterpretationReadRecords](../../../mythictrpg-main/src/main/java/com/sande/mythictrpg/recording/api/InterpretationReadRecords.java)의 Entry는 다음을 담는다.

| 값 | 의미 |
|---|---|
| `memoryId`, `layer`, `kind`, `extractorVersion` | 저장된 해석 후보의 식별과 분류 |
| `authority()` | 항상 `CANDIDATE`; caller가 authoritative로 바꿀 필드가 없음 |
| `quotes` | source alias, 원문 ID, 실제 화자, 발생 시각, 원문에 존재하는 정확한 인용 |
| `links` | newer/older source alias와 `CORRECTS`, `CONTRADICTS`, `CANCELS`, `ALSO_PLANNED`, `REPORTS_FULFILLMENT` |
| `inputs` | 인용되지 않은 입력도 포함한 전체 의존 원문 ID·alias와 prefix coverage |

coverage의 길이는 Java UTF-16 단위다. 인용이 전체 원문 어딘가에 있다는 것만으로는 충분하지 않다. 당시 모델이 제공받은 `raw.substring(0, coveredCharacters)` 안에 있어야 한다. `memory_subjects`는 입력 화자 목록이지 해석의 의미상 대상이나 관계 방향이 아니며, 그 뜻으로 공개하지 않는다.

원문과 해석은 각각 다른 불투명 cursor다. cursor는 session과 시작 원문 페이지의 **객체 identity**에 묶인다. 다른 session·다른 페이지·조작한 페이지·임의 cursor를 전달하면 거절한다.

## 3. bounded 후보 생성

[RecordedInterpretationSearch](../../../mythictrpg-main/src/main/java/com/sande/mythictrpg/recording/server/RecordedInterpretationSearch.java)는 원문 seed의 현재 native knowledge receipt를 검증한 뒤 `memory_receipt_withdrawal(knowledge_receipt_id, memory_id)` index를 사용한다.

1. receipt별 UUID keyset으로32개 후보와 다음 창 확인용1개만 가져온다. metadata·공개 범위 필터 전에 제한한다.
2. 요청당 최대64개 해석 후보를 검사한다. 원문 seed는 최대8개다.
3. 한 해석이 여러 seed에 연결돼 있으면 가장 앞선 seed에만 귀속시켜 페이지 간 중복을 막는다.
4. 전체 archive를 JVM으로 읽거나 전역 의미 검색을 수행하지 않는다.
5. 반환은 최대8개, `Budget`의 UTF-8 byte 한도 내 **완전한 card** 단위다. 정정의 한쪽 인용만 잘라내지 않는다. 새 페이지 예산으로 들어갈 card는 cursor를 전진시키지 않고 다음 요청으로 넘긴다. card 자체가 요청 예산보다 크면 그 card를 건너뛴다.
6. SQL/Java 요청 예산250ms와 shared RAW 검증의 원문 하나1MiB·요청 합계2MiB를 적용한다. 요청 전체 예산이 먼저 소진되면 해당 후보를 다음 페이지에서 재검사한다.

원문 조회와 해석 조회는 같은 session의 최대8회 호출,60초 유효 기간, 동시에1개 요청 제한을 공유한다. 응답은 `PARTIAL`이며 고정된 호출 한도 안에서만 cursor를 제공한다. 빈 결과나 cursor 종료는 “관련 해석이 전혀 없다”거나 “최신 정정까지 전부 확인했다”는 증거가 아니다.

## 4. 모든 입력의 권한 재검증

[RecordedRoomSearch](../../../mythictrpg-main/src/main/java/com/sande/mythictrpg/recording/server/RecordedRoomSearch.java)의 기존 원문/청취/공개 범위/부모 계보 검사를 package 내부 helper로 재사용한다. 별도의 느슨한 AI용 ACL을 만들지 않는다.

- dataset, 현재 speaker God, 원문 생성 watermark와 native producer 일치
- 현재 모든 신과 private 플레이어 청중의 실제 수신 및 공개 범위
- native source owner/revision/hash, receipt hash, actual `GAME_HEARD`, view pointer·내용·완료 part, 철회/tombstone 부재
- payload hash, 정해진 layer/kind/quote/link 형태 및 저장 행과의 일치
- `memory_sources`에 저장된 **전체 입력**을 확인; 인용에 등장하지 않은 입력도 빠뜨리지 않음
- 원문의 실제 화자·발생 시각·prefix coverage와 저장된 dependency 일치
- 모든 입력의 동일 conversation/disclosure 범위; 입력의 필수 native 부모도 저장된 dependency 집합에 이미 포함돼 있어야 함
- source hash는 단순 body hash가 아니라 원문 body hash와 canonical context를 결합한 hash
- 외부 evidence가 있으면 이 native-only 경로에서 거절
- 신규 source revision이나 source/receipt 철회가 있으면 이전 해석을 되살리지 않음

link는 payload의 alias를 actual receipt로 해석한 결과와 `memory_links` 행이 정확히 일치해야 한다. newer는 그 작업의 target이고 older는 더 나중 시각일 수 없다. `CONTRADICTS` 외에는 실제 화자가 같은 경우만 허용한다. `CORRECTS`/`CANCELS`는 정정 분류, `REPORTS_FULFILLMENT`는 발언 주장 분류, `ALSO_PLANNED`는 의도 분류를 유지한다. “완료했다고 말했다”는 실제 완료 판정으로 변환하지 않는다.

**구 checkpoint의 완전한 입력 집합 손상 한계:** 당시 `projection_hash`는 payload만 인증하며 전체 dependency 집합의 개수/hash를 별도로 포함하지 않았다. 정상 writer는 모든 입력 행을 한 transaction에 저장하고 임의 삭제하지 않지만, 외부 변조·손상으로 **인용되지 않고 필수 native 부모도 아닌 입력 행 자체가 사라진 경우** 원래 입력에 포함됐다는 사실을 reader가 항상 복원·검출할 수 없었다. 필수 부모 행 누락이나 남아 있는 입력의 철회는 검출했다. 무감사 DB 수리/수입은 지원하지 않으며, 이 제한을 “모든 종류의 저장소 손상에도 완전한 계보를 보장한다”로 해석하면 안 된다.

**후속 schema10:** [실제 lease의 commit-time 전체 입력 manifest](RECORDING_V2_PROJECTION_INPUT_INTEGRITY_20260930.md)가 새 후보의 전체 입력 및 sibling 출력 집합을 묶는다. 구 후보는 보존하지만 무검증 소급 manifest를 만들지 않고 조회에서 제외한다. 새 source 구현·검증 상태 및 남은 한계는 해당 기록을 따른다.

## 5. 재추출과 현재성

원문 session watermark 이후 만들어진 해석은 포함하지 않는다. 해석의 작업이 현재 `DONE`이고 `work_items.extractor_version == memories.extractor_version`인 경우만 읽는다. 버전 문자열을 숫자나 사전 순서로 비교하지 않으며, 새 버전이 철회됐다고 옛 후보를 자동 복구하지 않는다.

[WorldRecordingService](../../../mythictrpg-main/src/main/java/com/sande/mythictrpg/recording/server/WorldRecordingService.java)의 projection 전용 generation과 진행 중 mutation guard를 사용한다. `DONE → LEASED` 재추출 등 해석 상태 변경이 확정되면 기존 해석 lease는 보수적으로 오래된 것으로 취급한다. 반면 **이 변화만으로 원문 페이지를 stale로 만들지 않는다.** 원문 자체의 철회는 기존 source authority generation으로 둘 다 무효화한다.

callback과 사용 직전 `current(page)`에서 session/turn/runtime/source generation/projection generation/진행 중 변경을 다시 확인한다. source·quote·link는 하나의 SQLite read transaction에서 읽는다. 게임 tick에서 직접 DB 조회하지 않는다.

이 currentness는 정해진 서비스 API를 통한 변경을 전제로 한다. 외부 프로그램으로 실행 중 DB를 직접 변조하는 일을 정상 입력으로 지원하지 않는다.

## 6. SHADOW 소비자

[RecordedMemoryAccess](../../../mythictrpg-main/src/main/java/com/sande/mythictrpg/recording/server/RecordedMemoryAccess.java)가 실제 요청에 묶인 session을 발급한다. AI 응답의 별도 [RecordedInterpretationShadow](../../../mythai-ai-response/src/main/java/com/sande/mythai/response/memory/RecordedInterpretationShadow.java) 소비자는 기존 원문 비교가 끝난 뒤 최대3회 해석 조회, 중복 없는 최대4개 card, 인용문 합계4,096 UTF-8바이트 안에서 진단한다. 각 원문 seed 페이지의 첫 조회를 우선하고 남은 예산으로 continuation을 따라간다. 다음 조회 전과 최종 집계 전 모든 원문/해석 페이지의 현재성을 다시 검사한다. 내용·신 이름·원문·해석을 진단 로그에 복사하지 않고 별도의 집계 counter만 기록한다.

새로운 권한 없는 조회가 생기거나 기존 legacy 기억을 다시 조회하는 방식이 아니다. 원문/해석의 모델 노출, 응답 품질 개선, 외부 source 검색, 관계 수치 갱신, gameplay 기능 실행은 이 slice의 완료 범위 밖이다.

후속 `compareSemantic` 경로도 실제 semantic 페이지들을 시작점으로 사용한다. 독립 검증된 정확한 인용·정정 link를 내용 없는 집계로만 비교하며 prompt/ON 전환은 하지 않는다. 의미 벡터가 읽은 prefix와 해석 모델의 input coverage는 다른 값이다. 더 긴 허용 인용이나 정정 link가 존재한다고 해서 원문 꼬리까지 의미 벡터가 읽었다고 coverage를 확장하지 않는다. 이 소비자 추가를 포함한 AI `MemoryAudienceTest` 그룹374개 검사가 통과했으며,374개 전부가 semantic-seed 전용 검사는 아니다.

## 7. 검증 상태

게임 전체 offline build는50개 task로 성공했다. 새 [RecordedInterpretationReadTest](../../../mythictrpg-main/src/test/java/com/sande/mythictrpg/recording/server/RecordedInterpretationReadTest.java)는 실제 SQLite와 발급된 session을 사용해97 checks를 통과했다. 원문 검색719, projection 저장327, projection authority30 checks도 각각의 경계를 검증했으며, 원문/저장 검사만으로 새 조회를 검증했다고 대신하지 않는다.

AI 응답의 전체 offline build는40개 task로 성공했다. `memoryAudienceTest`365 checks에는 새 SHADOW 소비자33 checks가 포함된다. native SQLite/fake-backend pipeline66, extractor183, engine package425개 class 및 게임 class 중복 없음도 통과했다. 이 결과는 실제 Ollama 호출·응답 품질·운영 배포 성공 선언이 아니다.

첫 실제 SQLite derived 조회 검사는 유효한6개 card가0개로 반환되어 실패했다. 원문·binding·card 검증은 모두 통과했지만, 크기 계산에 plain Gson으로 `Instant`가 든 DTO를 직렬화하면서 Java module 접근 예외가 발생했다. `wireByteSize`에서 시각/UUID를 문자열로 명시한 wire 구조로 계산하도록 수정한 후97 checks를 통과했다. 전체 wire byte 한도,256바이트로 들어갈 수 없는 card의 부분 인용 금지, 새 충분한 예산의 재조회도 검사한다. 최초 실패를 성공으로 소급하지 않는다.

개발용 `RecordedRoomAuthorityGameTests`는 필수1/1 검사를 통과했고 Gradle8개 task, 종료 코드0을 확인했다. 실제 발급 원문을 시작점으로 한 typed `PARTIAL` 조회, 복제 page 거절, 대화 요청 변경 후 현재성 거절을 확인한다. 이 fixture에서는 새 해석 추출이나 모델을 실행하지 않으므로 빈 해석 결과가 정상이며 실제 모델이 만든 card나 대화 품질을 확인한 검사가 아니다. 최초 실행은 새 테스트 폴더에 `SHADOW`/`PERSONAL` 설정이 없어 시간 초과됐고, fixture 설정을 준비한 후 통과했다. 이를 고치려고 production 코드를 변경하지 않았다. 별도3모듈 개발 wire GameTest도 필수1/1과 종료 코드0을 확인했다.

통과한 focused 검증 범위는 다음과 같다.

- 취소/정정의 옛·새 정확한 인용과 alias/link 보존
- 인용되지 않은 입력의 철회 시 card 전체 거절 및 이미 발급한 page 무효화
- 모델에 제공된 prefix 밖 인용의 거절
- 누락된 필수 native 부모, 다른 신/청중, foreign cursor/page의 거절
- 재추출로 해석만 무효화되고 원문 currentness는 유지됨
- row/byte/keyset continuation과 fixed `PARTIAL`

후속 [native 의미 후보 조회](RECORDING_V2_M5_NATIVE_SEMANTIC_READ_20260930.md)는 별도 schema8 checkpoint까지 진행됐다. 이 문서의 원래97개 reader 검사는 raw-seed 기준이다. 그 이후 새 semantic-seed 해석 overload의 `RecordedSemanticInterpretationReadTest`114개와 새 소비자 검사를 포함한 AI `MemoryAudienceTest` 그룹374개도 focused 검증을 통과했다. 해당 checkpoint의 저장소119개·fake embedding pipeline103개 성공을 새 overload 성공으로 대신한 것이 아니며,374개 전체를 새 소비자 전용 검사 수로 부르지 않는다.

운영 서버/JAR 교체·운영 DB 이관·실제 Ollama·클라이언트 인게임 검증은 하지 않았다. [더 넓은 native 의미 검색 설계](NEXT_NATIVE_SEMANTIC_RETRIEVAL.md)의 장문 coverage·오래된 자료 후보 효율화, 외부 source 검색, 프롬프트 전환과 운영 수용 검증은 여전히 별도 과제다. 이 bounded native 조회만으로 전체 M5나 자연스러운 장기 기억 시스템을 완성했다고 선언하지 않는다.
