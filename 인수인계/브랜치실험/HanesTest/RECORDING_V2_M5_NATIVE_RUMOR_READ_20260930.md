# 기록 v2 M5 — 실제 수신 소문과 현재 판단의 분리 조회

2026-09-30 / `HanesTest`. 게임 `1.0.22`, AI `0.1.25` 개발 작업본. **소스 연결 및 게임 집중 검사 통과. 전체 실행/AI 후속 상태는 아래 §6을 따른다. 운영 미배포·SHADOW 전용이며 전체 M5 완료가 아니다.**

## 1. 목적과 소유권

기존 게임의 소문·전령·평판 기능을 새로 만들지 않는다. 새 기록 검색이 발견한 소문 후보를 현재 게임 저장소의 **정확한 root 1건**과 대조하는 읽기 전용 경로다.

`소문을 전달받음`과 `그 신이 현재 그 소문을 어떻게 판단하는가`는 다르다. 소문 자체는 여전히 검증되지 않은 주장이다. `ACCEPTED`도 해당 신의 판단이며 세계의 정사, 원장의 직접 관찰, 플레이어의 실제 행동 확정이나 호감도 변경을 뜻하지 않는다.

- 원본: 기존 `RumorSavedData` / `RumorLedger` / `CourierRumorService`.
- 평판 판단: 기존 `ReputationSavedData` / `ReputationLedger` / 현재 제작자 정책.
- 기록 DB: 확인된 영속 소문 snapshot에서 획득 이력과 허용 주장의 투영만 보존한다. 평판 평가가 같은 순간 저장됐다고 가정하지 않는다.
- AI: 이번 경로의 결과는 SHADOW 비교 대상일 뿐이며 생성 프롬프트·Proposal·보상·관계에 새로 적용하지 않는다.

소문의 실제 저장 완료와 cutover 배경은 [M3 source reconciliation](RECORDING_V2_M3_SOURCE_RECONCILIATION_20260930.md)을 따른다.

## 2. 정확한 게임 조회 계약

[NativeRumorReadAccess](../../../mythictrpg-main/src/main/java/com/sande/mythictrpg/rumor/NativeRumorReadAccess.java)의 공개 API:

```java
Optional<Snapshot> read(MinecraftServer server, RoomConversationEngine.Request request, UUID rootId)
boolean current(MinecraftServer server, RoomConversationEngine.Request request, Snapshot snapshot)
```

게임 스레드에서 현재 게임이 발급한 방/턴/revision/청중 요청만 사용한다. `RUMOR_TEST`, 비공개 방, 실제 참여 신 정확히 1명, 플레이어 청중 최대 16명으로 제한한다. 소문의 대상은 요청자가 아니라 **입증된 root의 subject**에서 읽으며 그 subject가 현재 청중에 있어야 한다. 현재 청중 전원이 제작자가 허용한 disclosure audience 안에 있어야 한다.

원문 증거 없는 구형 root, 미전달/pending, 현재 전령 정책 불일치, 수신 신 불가, 취소된 claim, 없는 durable lineage는 반환하지 않는다. `heard()`의 최근 64건 제한을 재사용하지 않고 `heardOne()`으로 지정 root만 확인하므로 오래됐다는 이유로 실제 수신 이력이 사라지지 않는다. 전령의 죽음은 이후 전달을 막지만, 이미 들은 소문을 취소하지 않는다.

Snapshot은 다음만 노출한다.

- world/lineage/root, claim revision, 실제 subject, 현재 수신 신.
- 원본 증거 source ID/revision 및 excerpt hash — 원문 payload 자체는 아님.
- 허용된 claim/epithet/disclosure audience.
- 현재 제작자 reception (`CAUTIOUS` 또는 `INTERESTED`).
- 아래의 현재 평판 Assessment.

전령 entity ID, 좌표, 증거 원문, 다른 신의 평가, 실제 affinity는 반환하지 않는다. `sourceHash()`는 기존 capture의 canonical hash와 같은 입력 순서를 사용한다. lineage/root/subject/증거 source·revision·hash/claim revision·text·epithet/정렬한 disclosure가 모두 결합된다. 현재 reception/assessment는 원본 claim hash를 바꾸지 않는다.

`current()`는 동일한 root를 다시 읽어 Snapshot 전체를 비교한다. 별도 파일인 Reputation의 변경도 재조회하며 기록 DB의 generation만 믿지 않는다. Snapshot DTO를 직접 만들었다고 권한이 생기지는 않는다. 기록 읽기 세션은 별도로 게임이 발급한 실제 페이지 객체를 등록해야 한다.

## 3. UNKNOWN과 UNASSESSED를 구분

```text
Assessment(availability, Optional<Outcome> outcome, version)
```

| availability | 의미 |
|---|---|
| `UNKNOWN` | 평판 runtime/저장소/현재 제작자 정책이 없거나 사용할 수 없음. 혹은 저장된 판단의 world·subject·God·원본 source·root·claim revision·정책 ID/hash가 현재와 일치하지 않음. |
| `UNASSESSED` | 현재 정책과 저장소가 유효하고 해당 source에 실제 평가 entry가 아직 없음. |
| `ASSESSED` | 현재 정책·동일한 증거/주장에 대해 실제 저장된 outcome과 양의 version을 확인함. |

UNKNOWN/UNASSESSED는 outcome이 없고 version 0이다. ASSESSED는 `ACCEPTED`, `DOUBTFUL`, `IGNORED`, `DISPUTED`, `RECOVERED`, `RETRACTED` 중 실제 값을 보존한다. 미확인을 임의로 `UNASSESSED`나 중립 평가로 바꾸지 않는다.

새 `ReputationService.nativeAssessment`는 기존 `ReputationLedger.find(subject, god, evidenceSourceId)` point lookup을 사용한다. 목록 전체 검색, affinity 조회, 평판 검토 실행, 승인 생성, 관계 변경은 하지 않는다. 같은 소문이라도 신 A와 신 B의 reception/assessment는 독립적이다.

## 4. 기록 후보와 현재 게임 판단의 결합

[RecordedRumorSearch](../../../mythictrpg-main/src/main/java/com/sande/mythictrpg/recording/server/RecordedRumorSearch.java)는 해당 dataset·수신 신의 허용 후보만 반환한다. 저장 projection에는 현재 평가 대신 `CURRENT_GAME_LOOKUP_REQUIRED`가 들어 있다. 게임 point snapshot과 source hash·lineage·root·revision·subject·recipient·claim·epithet·disclosure를 모두 대조한 뒤 reception/assessment를 붙인다.

획득 시각에는 서로 다른 두 시계가 있다.

1. 원본 Rumor metadata cursor: root 발생·claim 변화·실제 신별 전달 순서. cutoff 이하, OFF/legacy 발생은 신규 지식으로 승격하지 않는다.
2. 기록 DB committed watermark: 해당 receipt의 `KNOWLEDGE_ACQUIRED` work item `created_sequence <= W`여야 발급 당시 세션이 볼 수 있다.

원본 `acquired_cursor`를 DB watermark와 직접 비교하지 않는다. 늦게 전달되거나 늦게 reconcile된 다른 신의 receipt가 이미 열린 세션으로 역류해서는 안 된다. source origin/lineage/cutoff/confirmed 및 receipt의 정확한 ID/hash/work binding을 모두 확인한다.

현재 source/receipt 철회와 더 최신 source revision은 별도로 거절한다. root가 취소되거나 평가가 바뀌면 현재 게임 재검증이 즉시 걸러야 하며, archive 반영을 기다렸다가 허용을 취소하지 않는다.

## 5. 검색 범위와 제한

- `claim`/`epithet`의 전체 literal 또는 빈 질의의 최근 **source 순서** 조회다. 의미 검색이나 실제 UTC 발생일 검색이 아니다.
- UTC 범위 및 native actor selector는 이번 소문 source에서 지원하지 않으므로 무시하지 않고 사용할 수 없음으로 반환한다. 소문의 subject를 실제 발화자로 바꿔 해석하지 않는다.
- SQL 약 250ms, source/receipt 후보 최대 128, 투영 누적 읽기 256KiB, 페이지 전체 typed card byte 예산을 적용한다. source 내부 receipt까지 keyset cursor로 이어간다.
- 게임이 허용한 현재 snapshot만 카드로 만들며, 전체 archive 검색 완료를 뜻하지 않는 `PARTIAL` 계약을 유지한다. 다른 읽기 유형과 공통인 세션 8회/60초/동시 1회 제한을 늘리지 않는다.
- 별도 DB/schema나 새 소문·평판 authoritative 원본을 만들지 않는다. native embedding은 현재 방 발언 prefix 범위이며 소문 벡터 검색을 구현했다고 해석하지 않는다.

`MemoryReadSession.rumors(Query, cursor, budget)`와 `current(RumorReadRecords.Page)`의 선택적 계약, 실제 Session의 게임 point 비교/발급 페이지 identity 보관도 소스로 연결했다. current 판정은 매번 독립 평판까지 재조회하고 실제 발급 페이지 이외의 DTO는 거절한다. 실패·예산 초과·늦은 callback이 새 조회에 영향을 주지 않도록 기존 active-future와 timeout 경계를 유지한다.

AI [RecordedRumorShadow](../../../mythai-ai-response/src/main/java/com/sande/mythai/response/memory/RecordedRumorShadow.java)는 이미 계산한 기존 선택 root ID와 새 결과를 비교한다. 빈 질의의 최근 후보만 최대3페이지/4개 root/8KiB로 읽고 다음 조회 및 최종 집계 전 모든 페이지를 재검증한다. 별도 legacy 검색이나 모델 요청은 하지 않는다. `completed`, `unavailable`, `legacySelected`, `legacyMatches` 집계만 남기며 claim·subject·God·평가 내용은 진단에 출력하지 않는다. 이 소비자 소스 구현은 ON/프롬프트 전환이나 검색 품질의 검증 완료를 뜻하지 않는다.

## 6. 검증 상태

게임 소스/테스트 컴파일 후 다음 집중 검사를 통과했고, 함께 실행한 **게임 focused11개 task도 성공**했다. 이는 해당 집중 실행의 성공이지 전체 game build를 새로 완료한 것은 아니다.

- `NativeRumorReadAccessTest`: **230 assertions 통과**. 실제 game ledger/CourierEngine의 point lookup 및 현재 평판 분리 경계를 검사한다.
- `RecordedRumorReadTest`: **131 checks 통과**. 실제 RumorSavedData의 확인된 checkpoint → 기존 capture → SQLite → 발급 read session과 게임 point 재검증을 연결한 fixture다. 신규 발생/수신과 cutoff, 늦은 receipt의 archive watermark, 즉시 revoke·현재 평판/회복·정책 변경, disclosure와 페이지 재검증을 포함한다.

`NativeRumorReadAccessTest`는 실제 RumorLedger/CourierEngine/ReputationLedger 기반으로 pending/실제 전달, 비공개·한 신·청중, requester와 다른 subject, UNKNOWN/UNASSESSED/ASSESSED, 정책/모든 평가 binding 변화, 신별 독립성, 64건 밖 point 조회, 전령 사망과 root revoke의 차이, 동일 lineage 재시작, 조회 무변이, canonical hash를 검사했다. 두 fixture 모두 Minecraft 월드 부팅·실제 전령 entity·LLM 호출 없이 수행한다. game-thread/현재 요청 경계는 주입된 테스트 경계와 production wiring 검사를 사용하며 실제 인게임 소문 대화를 검증한 것은 아니다.

기존 [native semantic checkpoint](RECORDING_V2_M5_NATIVE_SEMANTIC_READ_20260930.md)의 성공을 이번 소문 경로의 통과로 소급하지 않는다. 운영 배포, 기록 ON 전환, 실제 모델/클라이언트 테스트는 하지 않았다.
