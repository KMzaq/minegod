# 기록 v2 M3 — 소문 획득과 관찰 지식 철회 연결

2026-09-30 / `HanesTest` 개발 작업본의 M3 검증 시점 기록이다. 이 시점 소스 버전은 게임 `1.0.21`, AI `0.1.24`, 콘텐츠 `0.1.3`이며, 뒤이은 버전 변경·M4 구현 상태는 브랜치 인수인계의 후속 기록을 따른다. 운영 배포·실제 LLM 검증 완료 문서가 아니다.

앞선 [권한 조회·관찰 연결·SHADOW 기록](RECORDING_V2_AUTHORIZED_READ_20260930.md)의 후속 구현이다. 기존 게임의 소문·평판·관찰 시스템을 교체한 것이 아니라 **이미 게임이 저장한 획득과 철회를 새 기록 저장소에 연결**했다.

## 1. 권한과 책임

- 소문의 관찰·발행·실제 전달·철회는 기존 `RumorLedger`/`CourierRumorService`가 소유한다. 새 adapter가 관찰자나 신을 생성하거나 전달·보상을 재실행하지 않는다.
- 소문 수신과 평판 판단은 다르다. `ReputationSavedData`는 별도 저장 파일이며, 소문 파일과 동시에 commit되었다고 가정하지 않는다.
- Watch의 실제 proof와 현재 공개 가능성은 기존 `GameWatchGateway`/`AsyncGodWatch`를 사용한다. 새 저장소가 게임의 관찰 권한을 발급하지 않는다.
- 새 읽기는 계속 SHADOW다. 이 작업으로 새 결과를 생성 프롬프트나 실제 행동의 근거로 전환하지 않았다. 기존 game-owned 현재성 검증도 유지한다.

## 2. SQLite schema 5와 새 저장 계약

관련 구현: [RecordingRecords](../../../mythictrpg-main/src/main/java/com/sande/mythictrpg/recording/api/RecordingRecords.java), [WorldRecordingService](../../../mythictrpg-main/src/main/java/com/sande/mythictrpg/recording/server/WorldRecordingService.java), [RecordingSchema](../../../mythictrpg-main/src/main/java/com/sande/mythictrpg/recording/server/RecordingSchema.java).

다음 네 테이블을 추가한다.

| 테이블 | 역할 |
|---|---|
| `source_cutovers` | owner별 lineage, 최초 수입 경계, 마지막 확인한 원본 durable cursor |
| `source_origins` | source별 실제 최초 발생 cursor와 해당 source 상태 cursor |
| `knowledge_origins` | 신별 실제 receipt의 획득 cursor |
| `knowledge_invalidations` | 특정 신의 특정 knowledge receipt만 철회하는 tombstone |

- `registerSource`: 실제 producer 권한·world·dataset을 확인하고 원본 lineage/cursor를 등록한다. 최초 확인 cursor를 cutoff로 고정한다. 재등록 때 lineage 변경이나 cursor 회귀는 거절한다.
- 기존 자료가 있는데 등록 정보가 사라졌다면 이를 정상 신규 source로 조용히 초기화하지 않는다. manifest에 이미 등록된 source와 동적 등록 경로도 섞지 않는다.
- `appendKnowledge`: source의 최초 발생이 cutoff 이후이고 원본 상태/획득 cursor가 실제 확인 범위 이내일 때만 저장한다. 하나의 source에 다른 신의 늦은 receipt를 별도로 추가할 수 있다.
- source/receipt별 안정적인 키와 해시로 중복은 재사용하고 충돌은 거절한다. 신 한 명의 늦은 수신 때문에 동일 source를 다시 만들지 않는다.
- source 전체 철회와 `invalidateKnowledge`의 신별 receipt 철회는 서로 다른 범위다. 선행 tombstone이 있으면 늦게 도착한 획득으로 되살리지 않는다.
- 한 배치의 마지막 성공 cursor만 보고 앞의 실패까지 성공으로 처리하는 `MAX consumer cursor` 방식을 이 경로에 사용하지 않는다. 실제 source·receipt의 멱등성 키와 전체 시도 결과로 재시도한다.

schema 2→3→4→5, 3→4→5, 4→5는 기존 예약된 시작 트랜잭션에서 추가 이관한다. 기존 원문·수신 행을 삭제하거나 옛 자료에 없던 provenance를 만들어 넣지 않는다. 설정/manifest schema는 계속2다. schema5 DB를 연 뒤 구 schema 전용 JAR로 단순 다운그레이드할 수 없으므로 운영 전환은 별도 백업·rollback 계획이 필요하다. 이번에는 운영 DB를 이관하지 않았다.

## 3. Rumor의 실제 영속 checkpoint

관련 구현: [RumorRecordingState](../../../mythictrpg-main/src/main/java/com/sande/mythictrpg/rumor/RumorRecordingState.java), [RumorSavedData](../../../mythictrpg-main/src/main/java/com/sande/mythictrpg/rumor/RumorSavedData.java).

게임의 기존 `state` JSON v1/v2는 유지한다. 같은 NBT 파일에 선택적 `recordingMetadata` 필드를 추가해 다음을 보관한다.

- metadata schema/world/lineage와 영속 증가 cursor
- root의 최초 발생 cursor
- claim revision과 변경/철회 cursor
- `(root, god, claimRevision)`별 실제 획득 cursor
- metadata와 원본 snapshot을 연결하는 canonical hash

게임의 재시작 때0으로 돌아가는 `RumorLedger.revision()`을 영속 cursor로 사용하지 않는다. metadata가 없던 시점의 기존 root와 receipt는 origin0 취급이며, 나중에 다른 신에게 전달돼도 신규 root로 승격하지 않는다. 실제 저장에는 양의 origin을 가진 항목만 넣고, 없는 항목은 기존/미확인으로 남긴다.

archive OFF에서 새로 생긴 root·수신도 origin0으로 유지한다. 이미 추적하던 source가 OFF 동안 철회되면 그 철회는 계속 추적한다. metadata를 지운 구버전으로 저장한 뒤 재시작하면 새 lineage가 생기므로 기존 DB 등록과 불일치하여 안전하게 연결을 중단한다. 과거 원본을 새 자료로 수입하지 않는다.

`recordingSnapshot()`은 다음 두 경우에만 새 immutable checkpoint를 제공한다.

1. 디스크에서 읽은 원본과 metadata의 바인딩을 검증한 경우
2. `ManagedSavedDataIo`가 atomic write/force/move 및 quota 정산을 끝내고 `COMMITTED`를 반환한 경우

큐 접수, `save(CompoundTag)` 호출, 메모리상의 최신 값은 commit으로 인정하지 않는다. callback은 캡처한 동일 snapshot만 게시하며 게임 API나 mutable ledger를 worker에서 읽지 않는다. callback이 마지막 서버 tick 뒤에 실행되어도 checkpoint는 보존된다.

주기적인 `flushRecordingSnapshot`은 이미 저장 중이면 추가 작업을 합친다. 반면 vanilla의 종료 저장은 새 dirty snapshot을 이전 저장 뒤에 큐에 넣는다. 이 구분으로 마지막 변경이 다음 tick을 기다리다가 유실되는 것을 방지한다. 종료 시 기존 I/O fence 뒤의 checkpoint를 새 저장소와 reconcile한 후 저장소를 닫는다.

초기 baseline 저장을 archive의 공용 quota 설치 전에 시작하면 `QUOTA_LEGACY_WRITER_BUSY`와 경합할 수 있어, 실제 주기 flush는 저장소 `READY`/`FULL` 이후로 이동했다.

## 4. Metadata의 용량·손상 처리

- 기존 게임 state는 NBT StringTag modified UTF-8 65,535바이트 제약을 유지한다.
- 생성 metadata는 49,152바이트 이하, 두 JSON 합산은 120,000바이트 이하로 사전 검사한다. NBT/gzip 여유를 두고 기존 128KiB 저장 예약/출력 상한 안에서 처리한다.
- 게임 draft와 metadata draft는 quota admission 성공 후 함께 채택한다. 게임 변경이 거절됐는데 metadata cursor만 전진하지 않는다.
- metadata 용량 초과·cursor overflow·provenance 불일치는 기록 연결을 영속 `UNAVAILABLE`로 만들며 조용히 다시 활성화하지 않는다. 이를 이유로 유효한 게임 동작을 새로 실행하거나 과거 데이터를 지우지 않는다.
- 손상된 원본 metadata는 보존하고 게임 state와 별도로 격리한다. 외부에서 이미 지나치게 큰 손상 NBT를 넣었다면 보존된 그 값 때문에 실제 bounded save가 실패할 수 있다. 성공이라고 보고하지 않으며, 이런 경우는 백업 후 운영자 복구가 필요하다.

내용을 노출하지 않는 상태값은 `DISABLED`, `WAITING_COMMIT`, `READY`, `UNAVAILABLE_METADATA`, `UNAVAILABLE_LIMIT`, `UNAVAILABLE_CURSOR`다. Runtime은 unavailable 상태 전환을 누락 진단에 반영한다.

## 5. 실제 Rumor → 기록 저장소 경로

관련 구현: [RumorRecordingCapture](../../../mythictrpg-main/src/main/java/com/sande/mythictrpg/recording/server/RumorRecordingCapture.java), [RecordingRuntime](../../../mythictrpg-main/src/main/java/com/sande/mythictrpg/recording/server/RecordingRuntime.java).

1. archive가 활성화되고 memory mode가 `RUMOR_TEST`일 때 게임 metadata 추적을 설정한다.
2. 확인된 immutable checkpoint로 source lineage/cutoff를 등록한다.
3. cutoff 이후 root의 입증된 source와 실제 신별 receipt만 저장한다.
4. 같은 source에 늦게 수신한 신은 자신의 획득 cursor로 추가한다.
5. 게임이 철회한 source는 기존 원본 revision의 tombstone으로 반영한다.

철회는 우선 시도한다. 한 source의 불명확한 proof나 용량 부족이 다른 source의 철회를 굶기지 않도록 실패 후에도 나머지를 검사한다. 일부만 성공했다면 snapshot 전체 완료로 표시하지 않는다. 이후 동일 snapshot도 멱등적으로 재시도한다.

최초 DB 등록 전에 벌어진 자료가 첫 확인 cutoff 안에 들어가면 보수적으로 제외될 수 있다. 이를 과거 자료 자동 수입으로 메우지 않는다. 정상 새 월드에서는 baseline cursor0을 먼저 실제 저장·등록하여 이후 자료를 구분한다.

소문 projection에는 claim, 실제 수신 신, 대상 플레이어, revision과 공개 audience 등을 넣지만 세계 사건의 전체 payload/좌표나 다른 신의 평판을 복사하지 않는다. 평판은 `CURRENT_GAME_LOOKUP_REQUIRED`로 표시한다. 단지 소문 파일에 receipt가 있다는 이유로 `ACCEPTED` 또는 실제 `UNASSESSED` 판단을 만들어 넣지 않는다.

## 6. Watch receipt 재검증과 개별 철회

관련 구현: [WatchKnowledgeReconciler](../../../mythictrpg-main/src/main/java/com/sande/mythictrpg/recording/server/WatchKnowledgeReconciler.java).

- 이미 저장된 `DIRECT_WATCH` receipt를 고정 archive watermark에서 한 번에 최대32개씩 읽는다. 새 관찰을 찾거나 raw action을 다시 실행하는 작업이 아니다.
- 원본의 정확한 observation ID를 기존 Watch gateway에 물어본다. source/world/god/subject와 허용 projection을 다시 확인한다.
- proof가 실제 철회됐거나 허용 projection이 바뀌면 그 신의 해당 knowledge receipt만 영속 철회한다. 같은 source를 본 다른 신의 receipt까지 전체 취소하지 않는다.
- cursor 회귀, runtime 교체, 비동기 지연, 공개 범위 불명확성은 안전하게 중단/재시도한다. callback은 현재 adapter/token을 확인한다.
- `UNKNOWN`은 철회나 유효함으로 추측하지 않는다. 해당 pass를 완료 처리하지 않되 이후 receipt를 계속 검사하므로 뒤의 확실한 철회가 굶지 않는다.
- 모든 항목이 확정된 pass만 checkpoint를 저장한다. checkpoint 자체는 archive watermark를 증가시키지 않아 자기 자신 때문에 끝없이 다시 검사하지 않는다.

현재 검색/생성의 기존 game-owned Watch proof 검증을 삭제하지 않았다. 새 저장소의 Watch 검색 노출과 전체 과거 proof의 완전한 coverage를 완료했다고 볼 수 없다. 불명확한 과거 proof는 원본에서 확인되지 않는 한 미확인으로 남는다.

## 7. 철회 중 비동기 읽기 보호

source/receipt 철회가 접수되고 끝나는 경계에서 저장소 `authorityGeneration`을 변경하고, 처리 중인 철회가 있으면 read authority를 안정 상태로 인정하지 않는다. 이전 generation에서 발급한 `MemoryReadSession`과 page는 stale로 처리한다. SQL 읽기는 source tombstone과 receipt tombstone도 검사한다.

현재는 저장소 전역 generation을 쓰는 보수적 방식이다. Session A 관련 철회가 독립적인 Session B의 SHADOW 읽기도 stale로 만들 수 있다. 이는 B에 A의 자료를 섞는 것이 아니라 안전을 위해 재조회하게 하는 처리이며, source별 정밀 lease 최적화는 아직 아니다. 이 방식이 실제 생성의 새 권위 저장소 전환을 승인한 것도 아니다.

## 8. 이번 검증의 증거와 범위

M3 변경 후 관련 게임 소스/테스트 컴파일 및 다음 focused 검증을 실행했다.

| 검사 | 결과 |
|---|---:|
| `RecordingKnowledgeStoreTest` | 65 checks |
| `RumorRecordingStateTest` | 59 checks |
| `RumorRecordingCaptureTest` | 30 checks |
| `RecordedRoomReadTest` | 369 checks |
| `RoomRecordingCaptureTest` | 40 checks |
| `WatchExactEvidenceTest` | 29 checks |
| `WatchKnowledgeReconcilerTest` | 55 checks |
| `WatchRecordingCaptureTest` | 58 checks |
| `CourierStageTest` | 4,189 assertions |

실제 NeoForge GameTest는 운영 서버가 아닌 다음 개발 월드에서 확인했다.

- `mythictrpg-main/build/recording-m3-save-20260930`: 실제 저장 quota/commit callback/권한 읽기 및 Rumor metadata 저장 경로 **4/4 required 통과**. 큐 접수와 commit 구분, 최신 종료 저장, 실패/재시도, FULL에서 game+metadata 동시 거절, 원본/metadata 재시작 일치를 포함한다.
- `mythictrpg-main/build/recording-m3-rumor-20260930`: 실제 `RecordingRuntime` 주기 호출 → `RumorSavedData` 원자 저장 → SQLite 연결 **1/1 required 통과**. baseline 등록, 첫 신 수신, 같은 source의 늦은 두 번째 신 수신, 독립적인 획득 cursor, 실제 게임 철회를 확인했다. 해당 실행은 781ms fixture / Gradle 17초·8 tasks였다. 이는 대용량 성능 측정이 아니다.
- 처음 runtime namespace 시도는 Gradle exit0이었지만 `No test functions were given!`로 **검증이 아니었다**. NeoForge가 `templateNamespace`로 테스트를 필터링한다는 점을 확인해 annotation과 빈 템플릿 생성 namespace를 맞춘 뒤 재실행한 위 1/1이 실제 통과 결과다.

M3 전체 변경 뒤 모든 모듈의 full build를 다시 실행한 것은 아니다. 운영 JAR 교체, 운영 서버 부팅, 실제 Ollama/LLM 대화, 클라이언트 GUI, 장시간/대용량 성능은 실행하지 않았다. 이전 전체 build 결과를 이번 M3 전체 검증으로 소급하지 않는다.

## 9. 다음 M4/M5에서 이어야 하는 작업

이번 변경은 획득·철회·commit 경계를 연결한 M3 작업이다. 다음을 완료한 것으로 선언하지 않는다.

1. 새 `work_items`에서 기존 AI Derived/MemoryIndex 기능으로 이어지는 실제 소비 경로: 제한된 lease, source·receipt 계보, 성공/실패/retry 및 무효화 전달.
2. EVENT/RELATIONSHIP/SUMMARY 파생물의 일관된 저장·업데이트·검색 연결. 권위 게임 관계 수치를 AI 파생물로 대체하지 않는다.
3. 새 FTS/semantic 검색 coverage와 기존 검색의 실제 품질 비교, session/audience별 접근 정책을 유지한 후보 노출.
4. 새 검색 결과를 생성에 사용할 ON 전환의 별도 검증과 승인. 그 전까지 SHADOW와 기존 writer/reader를 유지한다.

옛 자료 자동 수입, metadata/receipt의 추측 복원, 소문 수신을 믿음으로 승격, 운영 미배포 상태를 완료 상태로 표현하는 방식으로 남은 작업을 덮지 않는다.
