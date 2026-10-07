# 기록 v2 — M1 저장·합산 용량·복구

2026-09-29 · `HanesTest`. [05 공통 계약](../../../추가개발/05_AI_장기기억_다중신_대화_시스템/02_공통계약과_데이터모델.md), [저장 정책](../../../추가개발/05_AI_장기기억_다중신_대화_시스템/03_전체대화수집과_저장용량.md), [M0 관리 목록](../../../추가개발/05_AI_장기기억_다중신_대화_시스템/M0_기준확정/02_관리저장소와_기존제한.md)에 따른 소스 구현 기록이다. 운영 전환이나 M2~M7 완료 기록이 아니다.

후속 [M2 중앙 방 capture](RECORDING_V2_M2_ROOM_CAPTURE.md)는 DB schema2→3 publication context migration을 추가했다. 아래 M1 검증 시점과 전체 채널/M3~M7 구현 완료를 구분한다.

## 소유와 실행 경로

게임 모듈 `com.sande.mythictrpg.recording.api`의 불변 DTO와 `GameRecordingPort`는 게임이 등록한 생산자만 받는다. 등록되지 않은 capability 객체, 다른 월드/dataset, 허용되지 않은 channel/source kind/owner는 큐 입장 전에 거절한다. 기존 `ai.memorycontract`와 기억 파일/API는 교체하지 않았다. JDBC·SQL·경로를 AI 공개 포트로 노출하지 않는다. 현재 administrative inspection은 게임 서버 구현에만 있으며 NPC 검색 권한 포트가 아니다.

`RecordingRuntime`은 실제 서버 이벤트에 등록된다. config 읽기는 worker에서 수행하고 **missing/OFF이면 worldId 조회, 새 폴더·identity·manifest·DB 생성, native 초기화를 모두 생략**한다. 명시적 RECORD_ONLY에서만 게임 스레드의 `RumorSavedData.worldId`와 실제 durable 행동 원장 커서를 동결해 `WorldRecordingService`를 연다. 없는/확인할 수 없는 source cursor를 시간이나 임의 숫자로 채우지 않는다. 이 source는 후속 adapter가 정당한 시작점을 제공하기 전 새 source 수집에서 거절된다.

`WorldRecordingService`는 writer 1개/read worker 2개, 쓰기 큐 2,048개 또는 논리 16MiB, 읽기 큐 64개/행 조회 시간 제한과 응답 byte 제한을 사용한다. 현재 batch는 transaction 1건으로 처리하므로 M0의 최대 128개보다 작다. 원문은 길이를 자르지 않고 Unicode 경계를 보존하는 parts로 나눠 전체 hash와 함께 한 transaction에 저장한다. 큐보다 큰 발화는 저장 성공으로 꾸미지 않고 QUEUE_FULL/gap으로 표시한다. 원문 총건수·보관 일수·신별 기억 건수 상한을 새 RAW에 추가하지 않았다.

## 실제 저장과 복구

- 위치: `<world>/mythictrpg-recording-v2/manifest.json`, `<datasetId>/recording.sqlite`. 새 namespace이며 구 시험 로그·journal·memories.json을 읽거나 이관하지 않는다.
- SQLite 3.53.4, WAL, `synchronous=FULL`, `foreign_keys=ON`, 실제 FTS5 쿼리, `temp_store=MEMORY`, 제한 page cache를 적용한다. 알려진 네트워크 FS/UNC는 거절한다. 전원 장애의 완전 무손실이나 모든 파일시스템의 안전을 보장하지 않는다.
- schema2의 conversation/turn/message/parts/delivery views/성공 페이지/receipt/source/knowledge/work/cursor/invalidation/gap 테이블을 갖는다. 자식 행은 FK로 dataset 부모에 결속된다. RAW+초기 receipt+work, 추가 delivery batch+work, source+knowledge+work+consumer cursor를 각각 원자적으로 커밋한다. COMMIT 후만 STORED를 반환한다.
- 같은 occurrence 재시도는 기존 durable 식별자/sequence를 반환한다. 다른 본문·수신 내용·이미 다른 메시지에 속한 receipt는 CONFLICT이며 transaction 전체를 되돌린다. 같은 문장의 다른 occurrence는 별도 발화다. 화면 열람/인지 ACK는 만들지 않는다.
- body hash는 수락 원문의 UTF-8 SHA-256이다. display view hash는 정확한 표시 문자열과 순서 있는 페이지 계획에 결속된다. PARTIAL에는 실제 성공한 페이지 원문만 연결하고 완전 전달로 승격하지 않는다.
- source invalidation은 원본 revision과 별도의 단조 증가 state version을 유지한다. 원문을 지우지 않고 관련 work를 무효화하며 늦은 낮은 version으로 되돌리지 않는다. 재시작 뒤에도 revoked source 재수집을 거절한다.
- manifest/world/dataset/schema/4096-byte page size, `quick_check`, 원장 cutover 및 **이미 소비한 durable cursor**를 재검증한다. 부분 복원·손상·알 수 없는 schema·manifest만/DB만 남은 상태는 보존 후 fail-closed이며 새 dataset을 자동 생성하지 않는다.
- 시작 시 이전 clean marker가 없으면 durable high watermark 이후 POSSIBLE_GAP을 남긴다. 종료는 기존 action/watch drain 후 NeoForge SavedData I/O chain을 fence하고, legacy 예약이 모두 정리된 경우에만 clean marker를 쓴다. 실제 live tick에는 disk 대기가 없고 서버 정지 이벤트에서만 완료를 최대 10초 기다린다. 완료 미확인/legacy retry가 남으면 오류를 숨기지 않는다.

원문 수집용 API와 실제 서버 lifecycle은 연결했지만 **채팅·귓속말·팀·FTB Teams·스토리 전체 채널 capture adapter는 M2**다. 기본 OFF를 운영 ON으로 바꾸지 않았고, M1의 저장 API를 만들었다는 이유로 실제 플레이 대화를 모두 기록한다고 보고하지 않는다. NPC 권한 조회/파생 기억/검색 포트의 실제 소비는 M3~M5다. FTS5 availability를 확인하는 것과 전체 자료가 이미 검색 인덱싱됐다는 것은 다르다.

## 합산 quota와 기존 writer 연결

`ManagedStoreRegistry`는 recording-v2 전체(비활성 이름의 사본·WAL·SHM·staging 포함), action-ledger-v1 전체, god-watch-v1 전체, rumor/reputation 전용 SavedData 및 같은 접두사의 교체·복구 파일을 합산한다. 정규화/실경로가 월드 밖으로 나가는 symlink/junction, 중복·불명 파일은 fail-closed다. 파일 길이 합계이며 파일시스템 할당 블록/전체 디스크 사용량과 같다고 하지 않는다.

`WorldRecordingBudget`은 `used physical + outstanding reservations + maintenance headroom`을 checked 64-bit로 계산한다. 기본은 십진 100,000,000,000 bytes, 내부 유지보수 1,000,000,000 bytes, 경고 .90/백그라운드 보류 .95이며 JSON에서 검증한다. malformed 설정을 작은 기본값으로 대체하지 않는다. 90% 이상 관리자 알림은 30분 억제하며 quota·기존 안전 한도·I/O 실패를 별도 표시한다.

SQLite admission은 큐 입장 전에 DB 성장과 모든 dirty page가 WAL에 기록되는 보수적인 최대 증가량, SHM, checkpoint 중 공존 크기를 예약한다. `max_page_count`는 보조 할당 경계이며 단독 합산 제한으로 사용하지 않는다. cache spill을 끄고 매 짧은 transaction 전에 checkpoint 및 실제 파일 합계를 재확인한다. 시작 복구는 기존 WAL 전체를 DB에 반영하는 추가 증가량도 예약한다. 실제 종료한 I/O 뒤에만 실측 정산한다. OS 여유 공간 부족/외부 파일 증가도 admission을 닫는다.

이 보수적 전체-page WAL 예약은 큰 DB에서 hard cap보다 먼저 admission을 거절할 수 있다. 이는 100GB를 반드시 끝까지 채운다는 보장이 아니며, 100GB 실제 부하/최악 성능을 측정한 것도 아니다. 더 정밀한 reservation으로 바꾸려면 별도의 증명·실측이 필요하다. 자동 원문 삭제/FIFO/무제한 대체 파일은 없다.

기존 생산자도 빈 adapter가 아니라 실제 경로에 연결했다.

- `AsyncActionLedger`/`ActionLedgerStore`: 큐 수락 시 frame/control 최대치 예약, 실제 append·교체·flush 이후 정산. 기존 원장 형식과 index 제한 유지.
- `AsyncGodWatch`/`WatchJournal`: 관측/철회/복구 쓰기별 큐 예약과 worker scope. 읽기 자체는 계속 가능하며 안전상 철회 쓰기는 유지보수 경계 사용.
- `RumorLedger`/`ReputationLedger`: prospective 기록 변경만 검증·예약한 뒤 기존 인스턴스에 적용한다. 관계/퀘스트/보상 원본의 writer를 가로채지 않는다.
- `RumorSavedData`/`ReputationSavedData` + `ManagedSavedDataIo`: 실제 NeoForge 비동기 저장 완료까지 ticket 유지, setDirty(false)/serialize 시점에 조기 반환하지 않는다. 실패 retry와 교체용 temp의 동시 용량도 포함한다. 단일 NBT StringTag의 기존 modified-UTF 한도는 `LEGACY_NBT_STRING_LIMIT`으로 별도 거절하며 저장 포맷을 몰래 바꾸지 않는다.

archive OFF에는 bridge binding이 없어서 기존 운영 의미를 유지한다. 기존 quota 없는 writer가 진행 중인 순간 archive를 열려 하면 시작을 거절하며, 살아 있는 예약을 지워서 시작하지 않는다. 종료 후 실패한 legacy ticket이 남은 integrated-server 재시작도 명시적으로 차단될 수 있으므로 정상 I/O 완료와 관리자 복구가 필요하다.

## 의존성·검증 상태

루트 작업이 Maven Central에서 확보하고 게임 한 곳의 jarJar 패키징을 확인한 artifact:

- `org.xerial:sqlite-jdbc:3.53.4.0` 기본 JAR, 12,002,370 bytes.
- SHA-256 `BCB1F51E36F940867E83342F9EFBF5968AC44A6BEF4D397BB4AF7B17B45CD2FB`.
- strict range `[3.53.4.0]`, preferred version `3.53.4.0`, transitive false. 산출물 nested metadata도 정확 range를 확인했다. AI에 JDBC/native를 중복 패키징하지 않는다. native/별도 라이선스 리소스는 원본 JAR 안에 보존한다.

검증 작업:

- `recordingStoreTest`: 실제 JDBC/FTS5, 긴 원문/부분 receipt/transaction rollback/중복/cutover/철회, 정상 재시작, child JVM 강제 종료 후 WAL·POSSIBLE_GAP 복구, 잘못된 world/schema/corrupt 파일 보존, OFF 무쓰기, 작은 실제 quota.
- `managedStoreQuotaTest`: 실제 관리 파일 합산, 89/90/95/유지보수/FULL, overflow/동시 예약, OFF writer와 activation 경합, SavedData 여러 snapshot의 비동기 성공/실패 ticket.
- GameTest namespace `mythictrpg_recording`: 실제 Rumor/Reputation SavedData → NeoForge I/O worker → NBT 파일·worldId roundtrip·예약 해제, FULL에서도 일반 게임 상태 파일은 저장되고 quota에서 제외됨.

루트 순차 검증 결과: 게임 컴파일 성공, `recordingStoreTest` 실제 SQLite 62 assertions 통과, `managedStoreQuotaTest` 71 checks 통과. Windows symlink 생성 권한이 없는 환경의 해당 생성 검사 1개는 생략됐다. 실제 NeoForge `RecordingSavedDataGameTests`도 game-only 통합 6개 중 하나로 통과해 비동기 파일 저장·roundtrip·예약 해제와 FULL에서도 일반 상태 저장을 검증했다.

서버 설정 변경·운영 데이터 ON·legacy 이관·실제 모델·운영 배포는 하지 않았다. M1 저장·quota·복구/lifecycle 및 위 핵심 경로의 검증이지 M2 채널 수집이나 전체 장기기억 완성 주장이 아니다. 100GB 규모·장기 reader/대규모 성능·일반 클라이언트와 실제 서버 조합의 최종 수용은 별도다.
