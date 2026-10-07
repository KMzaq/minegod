# 기록 v2 — 권한 조회·관찰 연결·SHADOW

2026-09-30 / `HanesTest`, 게임 `1.0.21`, AI `0.1.24`, 콘텐츠 `0.1.3`. 개발 작업본이며 운영 미배포다.

## 이번에 연결한 실행 경로

1. 실제 게임 방 요청 → `RecordedMemoryAccess.open` → 불투명한 `MemoryReadSession`을 발급한다.
2. SQLite의 고정된 committed watermark 안에서 실제 신 청취·현재 청중·원문 계보를 검사한다.
3. 외부 관찰/소문 근거는 기존 엔진의 현재 증거 검증기로 재검사한다. 비동기 완료 때 방·턴·revision·참가자·엔진·저장소가 달라졌으면 폐기한다.
4. AI는 기존 기억 검색 결과와 새 결과를 **SHADOW로 비교**한다. 새 결과는 아직 프롬프트·행동·관계 변경에 사용하지 않는다.
5. 실제 행동 원장과 Watch 양쪽이 저장 완료된 경우에만 `WatchRecordingCapture`가 허용 관찰 projection을 기록한다. 관찰/보상을 새로 실행하지 않는다.

`기록됨`, `신이 획득함`, `지금 청중에게 공개 가능함`은 서로 다른 판정이다. 관리자 원장 검색을 AI에게 제공한 것이 아니다.

## 새 읽기 계약과 제한

- 게임의 실제 요청과 동일한 room/revision/turn/lease, 요청자·화자·전체 신·플레이어 청중이 필요하다. UI 선택, 다른 방 ID, 임의로 만든 DTO만으로 권한이 생기지 않는다.
- 현재 화자 및 다른 신 청중 모두 해당 원문의 실제 `GAME_HEARD`와 native KnowledgeReceipt가 있어야 한다. 사적 발화는 플레이어 청중의 과거 실제 전체 수신도 필요하다. 공개 발화의 새 플레이어 청중 허용과 신의 실제 획득 조건을 구분한다.
- `STANDARD`/`TEST_RECORDING`, `PERSONAL`/`RUMOR_TEST`를 분리한다. mode/context 없는 과거 행은 권한을 추측하여 수입하지 않는다. 기록 off는 계속 저장하지 않는다.
- 부모 발언의 source-message 계보를 재귀 검사한다. 부모가 비밀·누락·미수신이면 자식 대사로 세탁하지 않는다. Watch/소문 등의 외부 근거도 기존 game-owned 검증을 통과해야 한다.
- 한 세션 최대 8회 조회, 동시에 1회, 60초 lease, 비동기 반환 2초 timeout. 페이지는 1~8개/최대 64KiB이며 내부 후보 128개, 전체 원문 검증 2MiB, 단일 원문 1MiB, 계보 256개/깊이32/외부 근거64개 상한이다.
- SQL VM instruction hook으로 약 250ms read deadline을 적용한다. JDBC lock timeout만으로 전수 스캔 제한을 대신하지 않는다. 하나의 read transaction에서 행·수신·계보를 일관되게 본다.
- 긴 원문의 4,096 UTF-16 단위 저장 조각 경계에 걸친 키워드도 찾는다. 출력 예산 초과는 `excerpt=true`로 표시한다. 원문 저장을 잘라내지는 않는다.
- 페이지 존재 여부가 배제된 비밀 자료 수를 드러내지 않도록 1~7번째 요청에는 고정 형식의 opaque next cursor를 준다. 8번째에서 종료한다. 숨긴 자료 개수/이유는 반환하지 않는다.
- source 종류/과거 context/색인 범위가 아직 완전하지 않아 정상 결과도 `PARTIAL`이다. 현재 검색은 lexical/time-range RAW 검색이며 FTS·embedding·새 파생 기억 전체 검색 완료가 아니다.

## 저장 변경: schema 4

- native 방 수신 시 `SourceRef` 하나와 신별 KnowledgeReceipt, work item, cursor를 **같은 트랜잭션**에 저장한다. KnowledgeReceipt에는 message/delivery/view 참조만 두며 신마다 대화 본문을 복사하지 않는다.
- 새 `delivery_part_refs`는 수신자별 실제 전송된 조각 번호만 저장한다. 공통 `delivery_views`를 참조하며 구 `delivery_parts`의 수신자별 본문은 더 만들지 않는다.
- `delivery_parts_resolved` 읽기 view는 구 본문 행과 새 참조 행을 함께 지원한다. 구 본문은 삭제하거나 재작성하지 않는다.
- DB schema 2→3→4, 3→4를 시작 시 예약된 트랜잭션에서 진행한다. schema2 원문에 없던 청중/context를 만들어 넣지 않는다. 설정/manifest 버전은 계속2다.
- schema4를 연 뒤 schema2/3만 지원하는 구 JAR로 단순 다운그레이드할 수는 없다. 운영 전환 전 기존 기록 전체 백업과 대응 버전의 rollback 경로가 필요하다. 이번에는 운영 DB를 열거나 이관하지 않았다.

## Watch 연결 범위

- `GodWatchRuntime.observed`가 기존 raw durable future와 Watch commit을 모두 확인한 후 호출한다. 등록 전 source cursor 이하의 자료를 재수입하지 않는다.
- 기존 `readExact`/`current` 및 `ExperienceProjection`을 재사용한다. 관찰 신·대상·발생 ID·source revision·sequence가 일치해야 하며, 현재 공개 가능한 필드만 기록한다.
- `ACTION_OBSERVED`/`ACTIVITY_OBSERVED` source는 원장 발생 1건을 참조한다. 여러 신의 허용 투영과 지식 receipt는 각각 다르다. 좌표·비허용 원장 payload·전체 통계를 복사하지 않는다.
- 취소된 proof, 차폐, 대상 차단, runtime 변경, 비허용 경험은 새 획득으로 만들지 않는다. replay가 원장/보상/관찰을 재실행하지 않는다.
- **아직 없는 것:** 새 저장소의 Watch 검색 후보 노출, receipt 단위 영속 철회/reconciliation. 한 신의 proof 철회를 공유 source 전체 취소로 잘못 바꾸지 않았다. 현재 생성 경로는 기존 검증기를 계속 사용한다.

## 기존 회상 경로 개선

기존 `RecallSearch`의 시간 해석을 방 청취 기억에도 재사용한다. 발화일(`REAL_KST`)과 약속/계획일을 구분하여 오늘·어제의 발언 및 당시 오늘/내일/모레 언급을 조회한다. 질문의 시각은 요청 때 고정한다. 현재 회상 질문 자체를 과거 사실로 사용하지 않는다. 임의 기간·지난주 전체 자연어 해석기를 새로 완성한 것은 아니다.

## 운영 진단

OP2 전용 `/ai_memory archive_status`는 현재 저장소 상태·commit watermark·queue·누락·quota 및 SHADOW 비교 집계만 표시한다. 원문·신/플레이어 ID·비밀 존재를 출력하지 않는다. 새 읽기를 켜거나 설정을 바꾸는 명령은 아니다.

SHADOW는 이미 계산한 기존 청취 기억의 message ID 집합과 겹치는 정도를 비교한다. 진단을 위해 기존 검색/LLM을 또 호출하거나 생성 완료를 기다리게 하지 않는다. 단순 최근 히스토리 일치와 실제 기존 선택 일치는 별도 집계다. 이 숫자만으로 검색 품질이나 자연스러움 개선을 확정하지 않는다.

## 이번 검증

- 새 권한/RAW 검색 `RecordedRoomReadTest` **363 checks**, 중앙 수집/2·3→4 보존 이관 **36 checks**, SQLite/quota/강제 종료 복구 **62 assertions** 통과. 마지막 검토에서 timeout/dispatch 거절 후 조회가 잠긴 채 남는 문제를 발견해 active future identity로 수정했다. 늦은 이전 callback이 새 조회에 영향을 주지 않는 검사도 포함한다.
- 실제 원장·Watch journal→SQLite **47 checks**, 기존 경험 **24**, exact proof **16** 통과. 관찰 테스트의 SQL `Ref` import 충돌 및 JSON escape 문자열 비교를 수정한 후 최종 재실행했다.
- AI 청중/기억 **290 checks**, 회상 **126**, 방 엔진 **98** 및 JAR 소유권 **415 classes / 게임 클래스 중복0** 통과.
- 전체 오프라인 build: 게임 **42 tasks**, AI **37 tasks** 성공. 마지막 async 재조회 수정 뒤에는 해당 게임 컴파일/363 checks/JAR를 다시 실행했고 실제 아래 GameTest도 최종 소스로 실행했다. 작은 수정 뒤 전체 build를 다시 실행한 것으로 소급하지 않는다.
- schema4 최종 `build/recording-integration-final-20260930`: 실제 게임 발급 요청·새 입력/퇴장에 의한 권한 취소1 + 실제 SavedData quota1 + 저장 완료 callback/실패/재시도1, **3/3 통과**. 콜백이 queue 접수 때 실행되지 않음, 정확한 NBT snapshot/순서/방어적 사본, 이미 commit된 뒤 callback 오류가 저장 재시도를 일으키지 않음, 실패/용량 FULL/새 dirty 상태 보존을 확인했다. 같은 개발 월드에서 별도 실행한 세 모듈 실제 로드·모의 LLM 대화 **1/1 통과**. 앞선 schema3 단독 권한 검사1/1도 통과했으나 최종 확인은 이 schema4 결과다.
- 실제 모델 호출, 운영 배포/설정 전환, 클라이언트 GUI, 대규모 데이터 성능은 실행하지 않았다. 일부 tiny fixture 시간은 100GB 성능 측정이 아니다.

최종 개발 JAR SHA-256(미배포): 게임1.0.21 `D2698553465C117B7F9551D7272D546395317F9E778610913E93359B2193BA80`, AI0.1.24 `3967740F8B3A9DC2361DCB9A7FE6DE814B76F1671945C550D1147514F25D2891`.

## 다음에 이어야 하는 범위

1. 새 저장소 source/receipt 단위 철회·재시작 reconciliation. 기존 game-owned 현재성 검증을 제거하지 않는다.
2. Rumor의 실제 durable save 완료와 root/receipt별 cursor 연결. `RumorLedger.revision()`은 재시작 시0이므로 영속 cursor처럼 쓰면 안 된다. 기존 root가 나중 수신/평가되었다고 신규자료로 수입하지 않는다.
3. 기존 Derived/MemoryIndex backend를 새 work item에 연결하여 EVENT/RELATIONSHIP/SUMMARY를 만들고 권한·계보를 보존한다.
4. 새 검색 비교/coverage를 충분히 검사한 뒤 별도 승인한 ON 전환. 그 전까지 기존 기억 writer/reader와 테스트 TXT 기록을 임의 중단하지 않는다.

즉 M3~M5의 일부 실제 경로를 구현한 상태이며, 장기기억 전체 전환이나 RPG 서버 전체를 완료한 것은 아니다.

### Rumor 연결을 위해 확인한 보존 조건

소문 자체와 신별 평판 평가/회복은 이미 게임 시스템에 있다. 이를 또 구현하지 않는다. 이번에는 두 기록 SavedData의 `ManagedSavedDataIo`에 실제 저장 완료 callback을 추가했다. 기존 3인자 API는 유지하고, 새 callback은 `COMMITTED`/`REJECTED`/`FAILED`를 구분한다. COMMITTED만 atomic force/move와 quota 정산 후 정확히 저장한 NBT의 방어적 사본을 제공한다. callback 오류가 이미 완료된 저장을 실패나 중복 재시도로 바꾸지 않는다. **이 callback 자체가 Rumor source 연결 완료는 아니다.**

후속 연결은 다음 조건을 함께 구현해야 한다.

- Rumor의 현재 in-memory revision은 재시작에 걸쳐 유지되지 않는다. Reputation의 영속 revision과 다르다. 큐 접수 여부·시계·현재 ledger.revision을 Rumor durable cursor로 대체하지 않는다.
- 기존 root의 최초 발생, claim 변경, `(root, god, claimRevision)`별 실제 수신 cursor를 구분한다. enable 당시 기존 root는 baseline/unknown으로 유지한다. 나중 전달·평가되었다고 새 root로 승격하지 않는다.
- 원본 소문 NBT v1/v2와 기록용 metadata를 분리한다면 metadata 자체의 schema/world/lineage도 필요하다. archive OFF 기간과 metadata 없는 구버전 downgrade 후 재시작을 신규자료로 추정하지 않는다. 구 게임 데이터는 보존하고 기록 연결만 불가로 표시한다.
- metadata의 크기도 prospective quota admission과 atomic 저장 상한에 포함해야 한다. 현재 SavedData는 NBT StringTag 및 압축 write bound가 있으므로 거대한 추가 맵을 무제한 붙이지 않는다.
- 기존 dataset manifest에 새 source cutoff가 없으면 자동 수입하지 않는다. 영속 source 등록/cutover와 원본 cursor 회귀 검사를 먼저 마련한다.
- 한 snapshot 안의 여러 receipt 중 일부만 저장된 상태에서 MAX consumer cursor를 전진시키고 전부 완료했다고 간주하지 않는다. batch 전체 ack 또는 receipt별 재시도 키가 필요하다.
- 서버 종료 시 callback을 `server.execute`에만 보내면 마지막 tick 뒤에는 소비되지 않을 수 있다. frozen durable checkpoint를 보관하고 다음 시작에서 디스크와 reconcile한다. 이전 runtime의 callback을 새 runtime에 적용하지 않는다.
- Rumor와 Reputation은 서로 다른 파일이다. 두 저장소가 동시에 commit되었다고 가정하지 말고, 양쪽 정확한 revision을 확인한 projection만 사용한다. 수신했다는 사실을 ACCEPTED/현재 믿음과 동일시하지 않는다.
