# 로드맵 2단계 — 상세 행동 원장 최소 기반

2026-09-15 / **소스·오프라인 작업 완료, 미배포·인게임 미검증 / 3단계 미착수**

## 결과와 선행 조건

- [최신 로드맵](ACTION_OBSERVATION_MEMORY_ROADMAP_20260915.md)의 2단계만 진행했다. [공통 계약](ACTION_OBSERVATION_CONTRACT_20260915.md)과 [0~1단계 기록](RECALL_STAGE01_20260915.md)의 코드/산출물/변경 목록을 실제 파일과 대조했다. 직전 인수인계 문서 보완 외 단계 0~1의 기록된 파일 해시가 일치했고, 선행 AI 검사 **831개**를 다시 실행해 통과했다. 실제 대화 품질 검증은 원장 구현의 선행 조건으로 몰래 대체하지 않았으며 여전히 별도 검증이다.
- 게임 개발 소스/JAR은 **MythicTRPG 1.0.3**, AI 개발 JAR은 기존 **0.1.4**다. **실제 서버/클라이언트 배포 폴더는 게임 1.0.2 / AI 0.1.3 / 콘텐츠 0.1.0** 그대로다. PERSONAL/gemma4:12b와 기존 기억·월드·FTB·콘텐츠·레거시 소스를 변경하지 않았다.
- 후보 산출물: `mythictrpg-main/build/libs/mythictrpg-1.0.3.jar`, SHA-256 `E69DAC079EADE39F018091A8BFB4B1C78E48F157C0F0063A78C84CC2AB872889`. 기존 AI 개발 0.1.4 JAR 해시 `6BDC543B0FD8138584FAB1C3DD9D137BDB9BB75FBEDCE91409B680E9A5BE00C7`도 유지했다.
- 서버/GameTest 서버 실행, 실제 LLM 호출, 모델 설치/교체, JAR 배포는 하지 않았다. 아래에서 실행한 별도 JVM은 원장 파일 복구만 시험하는 Java 프로그램이며 Minecraft를 시작하지 않는다.

## 백업·변경 범위

- LP 원본 JAR **3개**, `LP-source-20260912`의 **298개**, `before-memory-rumor-foundation-20260913-223016-887`의 **906개** 해시를 재검증했다. 단계 0~1 직전 보존본 **2,177개**의 무결성도 확인했다. 기존 LP/보존본을 덮거나 삭제하지 않았다.
- 이번 직전 상태 **2,186개**는 [before-action-ledger-stage02-20260915-195314-636의 manifest](../server/backups/before-action-ledger-stage02-20260915-195314-636/file-manifest.csv)에 보존했다. 사용자 소스·필수 빌드 입력·기존 JAR·설정·개인 기억/로그·문서를 포함한다. `.git`·캐시·전체 월드 청크/플레이어 진행 데이터의 완전 백업은 아니다.
- 게임에는 원장/관리자 조회/읽기 전용 성공 hook과 생명주기 등록을 추가했다. 기존 관찰 어댑터의 처치 책임자 조회와 작물 판별 helper를 공유하기 위해 접근 범위만 public으로 열었다. 기존 이벤트 처리/카운터/묶음 알림/퀘스트·조우·스토리 실행 로직은 그대로다.
- AI 소스 변경은 `build.gradle`의 **선택적 후보 게임 API JAR 경로**뿐이다. 기본 입력은 기존 `mythictrpg-1.0.2.jar`, 기존 legacy JAR/소스·오버레이·포함 규칙은 유지한다. 모델/프롬프트/기억 경로는 바꾸지 않았다.
- 파일별 복원 대상과 해시는 [2단계 변경 목록](action-ledger-stage02-changes-20260915.csv)을 따른다. 사전 백업 도구, 생성된 감사 CSV, `build/`의 후보 JAR·시험 산출물은 기능 소스 목록과 별도로 관리한다.
- 최종 감사에서 직전 baseline **2,186개 중 2,174개**가 그대로였고, 승인 범위의 기존 파일 수정 **12개 + 신규 15개 = 27개**를 차이 목록에 기록했다. 관련 문서의 로컬 링크 **63개**, 코드 블록/JSON 예제와 `git diff --check`도 확인했다. 기존 사용자 변경을 되돌리거나 커밋하지 않았다.

## 이번 단계의 수집 지원표

| 원장 타입 | 성공 판정/공유 입력 | 기록하는 사실 | 포함하지 않는 범위 |
|---|---|---|---|
| MATURE_CROP_REMOVED | NeoForge 21.1.248의 `ServerPlayerGameMode.removeBlock` RETURN이 true일 때, 기존 MatureCropHarvestClassifier 재사용 | 밀·당근·감자·비트·네더와트·코코아의 성숙 블록 1개 제거, 행위자·좌표·모드·시간 | 실제 아이템 획득/드롭 수량, 우클릭 수확, 미성숙 작물, 다른 모드의 별도 수확 경로 |
| ENTITY_KILLED | `LivingEntity.die`의 취소/이미 사망/제거 검사 뒤 `dead=true` 확정 지점, 기존 playerResponsibleForKill 재사용 | 플레이어에게 직접/기존 투사체 규칙으로 귀속된 비플레이어 생명체의 사망, 대상 UUID/종류·위치·시간 | 공격 시도·피해량·플레이어 사망, 보상/루트 지급, 개별 모드가 부모 die를 호출하지 않는 사망 경로 |

기존 `BreakEvent`는 파괴 **시도**이며 `destroyBlock`도 제거 실패 뒤 true를 반환할 수 있어 원장 성공 근거로 쓰지 않는다. `LivingDeathEvent`는 취소 가능하다. 따라서 기존 알림을 수정하거나 재발행하지 않고 같은 게임 동작의 **실제 결과 지점**을 비취소형 Mixin 2개로 읽는다. 공격/드롭 취소를 사망 취소와 혼동하지 않는다. 성공 hook은 게임 반환값/피해/드롭/보상을 변경하지 않는다.

NeoForge의 해당 메서드/필드와 hook descriptor는 제공된 실제 바이트코드로 오프라인 검사했다. **실제 Mixin 적용·다른 모드의 변환과 충돌 여부는 서버 부팅 없이 확정할 수 없다.** `require=1`이므로 필요한 hook이 사라지면 정상 기록인 척하지 않고 로딩 오류가 날 수 있다. 기능 OFF라도 Mixin 구성은 로드된다. 배포 승인 후 이 검증을 먼저 해야 하며 임의로 다른 모드를 수정하지 않는다.

## 사건·저장·읽기 계약

구현은 게임 소유 `com.sande.mythictrpg.gameplay.ledger`와 `.server` 패키지다. 신의 identity/관계 DB나 AI 월드 사실 DB를 추가하지 않았다.

- `ActionRecord(schemaVersion=1, worldId, sequence, event)`의 worldId는 기존 게임 `RumorSavedData.worldId()`를 사용한다. 다른 월드 ID로 같은 원장 폴더를 열면 거절한다.
- `event`는 불변 Draft다. 게임이 발생마다 만든 `occurrenceId`, `captureSession/captureOrder`, `sourceRef/sourceRevision=1`, 실제 actor/typed subject, UTC 밀리초·gameTick·gameDayTime·차원·좌표·종류·COMPLETED·제한 payload를 담는다. eventId는 occurrenceId이며 dedupKey는 sourceRef/occurrenceId/revision의 조합이다. 같은 tick·행동 종류·좌표를 같은 발생으로 합치지 않는다.
- 게임 스레드에서 제한된 원본 snapshot과 발생 순서를 만들고, 전역 원장 sequence는 단일 작성자가 FIFO 순서로 커밋할 때 부여한다. PENDING에는 아직 커밋 sequence가 없다. 같은 불변 Draft를 재전달하면 같은 영구 기록/receipt를 반환하며 같은 ID의 다른 내용은 거절한다.
- 모든 원본은 `visibilityRef=mythictrpg:admin_only_unprojected`다. 관찰 증명·신별 공개 capability·AI 프롬프트 전달은 아직 없다. 원장에 있다는 이유로 신이 알지 않는다.
- 파일은 월드의 `mythictrpg-action-ledger-v1/` 아래 metadata.json, checkpoint.json, writer.lock 및 분할된 `segment-<첫 sequence>.alog`로 저장한다. segment는 길이+CRC32+UTF-8 JSON 프레임이며 읽기는 프레임 16KiB로 제한한다. 과거 전체 파일을 매 행동마다 다시 쓰지 않는다.
- 제한 큐/단일 IO 작성자, 파일 잠금, data force 후 원자적 checkpoint 교체로 커밋한다. Windows의 일시적인 교체 거부만 IO worker에서 최대 150ms의 제한 재시도를 한다. 게임 스레드는 디스크를 기다리지 않는다. 메타데이터/임시 파일을 위한 4KiB 여유와 인덱스 개수 상한도 둔다.
- 읽기 인덱스는 시작 시 검증된 segment에서 복원하며 eventId/sequence/actor로 조회한다. 페이지는 최대 100건, 관리자 명령은 10건이다. `Cursor(worldId, sequence)`는 커밋된 자료의 재시도 위치이며 다른 월드/미래 커서는 거절한다. 후속 관찰 소비자는 **자기 저장 성공 후에만** cursor를 전진시켜야 한다. 원장 재생 자체는 퀘스트·보상·스토리/기존 sink를 호출하지 않는다.

## 실패·공백·종료

- `PENDING_NOT_DURABLE`, 큐 포화, 불가용, ID 충돌, 내구성 확보된 결과를 구분한다. 디스크/체크포인트 오류와 한도 초과는 원장 실패로 노출하고 기존 게임 진행은 유지한다. 자동 삭제나 조용한 샘플링으로 한도를 맞추지 않는다.
- 체크포인트에 거절/실패 진단의 누적 횟수·최초/최근 UTC·최근 사유를 남긴다. 이는 **거절된 요청/오류 진단 수**이며 고유한 누락 사건 수를 완벽하게 센 값은 아니다. 여러 공백의 바깥 시간 범위일 수 있다. 누락 진단 증가는 관리자/로그에 최대 5분 간격으로 알린다.
- 정상 종료는 최대 5초 동안 drain한다. 시간 초과를 정상 저장 완료로 표시하거나 살아 있는 작성자의 lock을 해제하지 않는다. 이전 실행이 비정상 종료였는지, 이전 checkpoint 시각을 제공한다. OFF/서버 미실행 구간은 수집했다고 간주하지 않는다. OFF 중에는 원장을 열지 않으며 재활성화 때 이전 checkpoint 이후의 관측 공백을 추정으로 메우지 않는다.
- 완전한 프레임이 checkpoint보다 앞서면 재시작 시 검증 후 복구할 수 있다. 미완성 프레임/CRC·스키마·순서 손상은 원본을 수정하지 않고 불가용 상태로 둔다. 자동 절단/복구 도구는 제공하지 않는다. 디스크가 완전히 실패하면 공백 진단 자체도 저장하지 못할 수 있고, 미커밋 queue는 크래시로 유실될 수 있다.
- 게임 SavedData와 원장 파일은 하나의 트랜잭션이 아니다. 특히 신규 월드의 기존 worldId가 최초 저장되기 전 크래시 등으로 ID가 달라지면 원장을 자동 재귀속하지 않고 불일치로 거절한다. 실제 게임 저장/종료 경로와 신규 월드 통합 시험은 남았다.

## 설정·90% 알림·관리자 조회

서버 실행 폴더의 `config/mythictrpg/action-ledger.json`을 시작 시 한 번 비동기로 읽는다. [예제](examples/action-ledger.example.json)는 **enabled=false, maxStorageBytes=0**이며 서버에 복사하지 않았다. 누락/손상/미지원 스키마는 OFF다. 운영에 켜려면 향후 배포 승인과 함께 명시적인 용량을 정해야 한다. hot reload/자동 용량 변경/삭제 명령은 없다.

| 설정 | 이번 상태/의미 |
|---|---|
| schemaVersion | 1 |
| enabled | 기본 false |
| maxStorageBytes | 사용자 운영값 미확정, 예제 0. true로 켤 때 64KiB~1TiB 범위의 명시값 필요 |
| segmentBytes | 기술적 분할 크기, 예제 4MiB |
| maxIndexedEvents | 메모리 인덱스 안전 상한, 예제 100,000. 초과 시 삭제 없이 INDEX_LIMIT 상태 |
| queueCapacity | 쓰기/조회 공유 큐, 예제 256, 허용 1~4096 |

사용자는 용량/정리 정책은 **부하·용량 측정 뒤 결정**하되 **사용량 90%에서 서버 알림과 관리자 용량 증설 안내**를 요청했다. 이를 다음과 같이 구현했다.

- 실제 저장 사용량이 설정 한도의 90% 이상이면 서버 전체 채팅과 서버 로그에 안내한다. 플레이어 행동 내용이나 개인 정보는 알림에 넣지 않는다.
- 최초 진입 후 계속 90% 이상이면 최대 30분 간격으로 재알림한다. 용량을 늘려 사용률이 내려가면 다음 90% 진입을 다시 알린다. 관리자는 maxStorageBytes를 늘리고 정상 재시작하라는 안내를 받는다.
- 기록은 자동 삭제하지 않는다. 안전 여유/프레임 크기/인덱스/설정 한도를 넘을 쓰기는 명시적으로 실패한다. 이것은 테스트 가능한 기술적 상한 처리이며 **100% 도달 때의 운영 정리 정책을 승인받은 뜻이 아니다.** 보관 기간과 실제 용량, 오래된 기록 정리는 여전히 미확정이다.

관리자 권한 레벨 2 이상에서만 사용할 수 있는 읽기 명령:

```text
/mythadmin ledger status
/mythadmin ledger recent <player_uuid> [after_sequence]
```

상태/사용량/내구성 확보된 커서/pending/공백 진단을 보여주며, 조회는 단일 IO worker에서 실행한다. 결과 표시 전에 권한과 현재 서버/접속자 identity를 다시 확인한다. OFF/오류/잘못된 커서는 ‘기록 없음’과 구분한다. 일반 플레이어/신/LLM용 전역 조회 명령은 없다.

## 수행한 검증과 측정

```powershell
.\dev-tools\run-mythictrpg-gradle.cmd jar actionLedgerTest memoryFoundationTest --offline --no-daemon
.\dev-tools\run-mythictrpg-gradle.cmd -p ../mythai-ai-response "-PmythicTrpgApiJar=../mythictrpg-main/build/libs/mythictrpg-1.0.3.jar" compileJava check --offline --no-daemon
.\dev-tools\Test-ActionLedgerRelease.ps1
```

- 게임 컴파일/JAR 생성 성공. 원장 **604개**, 기존 소문 **39개** 검사 통과.
- 후보 게임 API를 사용한 AI 컴파일·기억 656개/기존 회상 54개/단계1 회상 121개, **831개** 통과. 합계 **1,474개 오프라인 검사**이며 반복 실행 횟수나 실제 모델 대화 정확도를 더한 수가 아니다.
- 원문 개별 수/순서, 같은 tick의 별개 발생, actor/world 격리, 영구/대기 중 중복, 충돌, 페이지/cursor, segment 회전, 정상/비정상 재시작, 쓰기 경계 실패, 손상/과대 프레임, 파일 잠금, 큐/인덱스/용량 한도, graceful drain/시간 초과, OFF 설정, 90%와 재알림을 확인했다.
- 독립 테스트 JVM을 beforeWrite/afterForce 지점에서 즉시 종료하고, 미커밋 손실/완료 프레임 복구/비정상 종료 표시를 확인했다. 실제 전원 차단이나 물리 디스크 장애를 재현한 시험은 아니다.
- 제공된 Minecraft 바이트코드의 성공 hook 위치/비취소형 callback, 기존 1.0.2 JAR의 `onMatureCropBreak/onLivingDeath/emit` 및 `GameplayIngressService.accept/drain`과 이번 바이트코드 동등성을 검사했다. 원장 저장/재생 코드에 게임 소비자/AI 호출이 없음을 확인했다. 이는 실제 모드 변환·인게임 수확/취소 이벤트·보상 검증을 대신하지 않는다.
- 새 게임 후보와 현재 서버 AI 0.1.3 조합, 개발 AI 0.1.4 조합 각각 **7 JAR / 241 패키지**, split-package 없음. 후보 JAR의 중복 ZIP 엔트리·필수 클래스·Mixin 구성·버전도 검사했다. 실제 모드 로딩 성공을 뜻하지 않는다.

아래는 최종 게임 원장 실행의 **단순 수확 payload를 쓰는 파일 시험**이다. 작성자 수는 병렬 Java 요청 수이지 실제 접속자 수가 아니다. 서버 tick/메모리/월드 저장/LLM/HUD 부하는 포함하지 않았다.

| 작성자 | 기록 | 디스크 bytes | 평균 bytes/건 | 쓰기·종료 포함 시간 |
|---|---:|---:|---:|---:|
| 1 | 40 | 29,763 | 744.1 | 366.5ms |
| 4 | 160 | 118,238 | 739.0 | 594.5ms |
| 6 | 240 | 177,260 | 738.6 | 790.5ms |

특정 표본의 진단이다. 이를 전체 행동 기록의 용량/성능 보장이나 운영 보관 기간 결정으로 사용하지 않는다. 진행 중 테스트의 클래스 경로 누락과 Windows checkpoint 교체 거부를 발견해 보완했고 최종 통과 결과를 기록했다.

## 미검증·복원·다음 단계

- 서버/GameTest/실제 LLM·모델 설치·배포·인게임 동작은 미실시다. 실제 Mixin 적용, 취소/재진입/다른 모드의 수확·사망 override, 신규 월드 저장, 관리자 권한 변경/재접속·서버 알림 표시, 1/4/6인 전체 부하는 남았다. 새 JAR을 서버에 놓으면 바로 정상 동작한다고 보장하지 않는다.
- 행동 기록은 **아직 신에게 전달되지 않는다**. 주시 상태·관찰 증명·Experience 조회·관계 입력 연결·의미 검색·전서구/평판 효과는 이번 단계가 아니다. 이동/상세 채굴/모든 전투 기록으로 확장하지 않았다.
- 이번 단계만 되돌리려면 변경 목록과 2,186개 직전 백업을 대조해 MODIFIED를 선택 복원하고 ADDED를 별도 보존한다. Mixin 구성/metadata/생명주기/관리자 등록/버전/빌드 설정을 함께 처리하고 재컴파일한다. 기존 사용자 변경이나 월드/기억은 통삭제하지 않는다. LP까지 돌아가려면 [기반 복원 가이드](LP_MEMORY_FOUNDATION_GUIDE.md)의 이전 합산 목록도 함께 검토한다. 이번에는 복원/배포하지 않았다.
- **2단계의 소스·허용된 오프라인 검증 범위는 완료했다. 3단계는 시작하지 않았다.** 다음에는 사용자 승인 후 신별 주시와 관찰 증명을 연결하되, 미확정 주시 범위/시작·유지·종료 정책을 확인하고 운영 관찰은 임의로 켜지 않는다.

연동 스킬에 따라 기존 권위/소비자 경계를 보존하고 성공 판정 위치를 분리했으며, 릴리스 스킬에 따라 백업/후보 JAR/오프라인 검사와 미배포·미부팅을 구분했다.
