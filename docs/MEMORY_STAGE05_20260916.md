# 로드맵 5단계 — 상세 수집 확장·파생 기억·혼합 검색 오프라인 기반

2026-09-16 / **미배포, 기본 OFF. 5단계 전체 완료·운영 품질 수용은 아직 아님. 6단계 미착수.**

## 1. 승인과 선행 조건

이번 승인은 최신 로드맵 5단계 소스·컴파일·오프라인 검사다. 서버/GameTest 서버, 실제 LLM/임베딩 호출, 모델 설치, JAR 배포는 하지 않았다. 통합·대화 검토·릴리스 스킬에 따라 게임 권한과 원문 출처를 분리하고 실제 대사 품질을 합성 시험으로 대체하지 않았다.

- 직전 4단계 변경 목록 37개 해시와 실제 소스/빌드 입력을 확인했다. 착수 전 기존 오프라인 1,630개 검사를 다시 통과했다.
- **4단계의 실제 Minecraft 종단·모델 대사·HUD·지연 수용 조건은 미충족**이다. 따라서 이번에는 로드맵이 독립 착수를 허용하는 5B 검색-only와 기본 OFF인 5A 수집 경로를 구현했다. 새 상세 정보를 신에게 공개하는 운영 확대는 하지 않는다.
- 사용자 확정: 이동은 **일정 간격 좌표 + 차원 이동·텔레포트 별도 기록**, 채집·전투는 **시도·취소·실패 포함**이다.
- 미확정: 좌표 간격의 구체적인 tick 수, 실장비 측정 후 원문 보관 용량/초과 정책, 현실/게임 시간 기준, 신별 영역/가림 상세와 자동 주시 관계 임계값. 미확정 설정을 임의 활성화하지 않았다.

## 2. 보존과 개발/서버 구분

- LP JAR 3개, LP 소스 298개, 기억 개발 직전 전체 906개, 4단계 직전 2,213개를 확인했다. LP를 덮어쓰지 않았다.
- 이번 작업 전 별도 백업: `server/backups/before-memory-stage05-20260916-223454-266/`, manifest **2,231개**. 소스·설정·배포본·기존 개발 JAR·대화 로그를 포함한다. 전체 월드 지형 백업은 아니다.
- 게임 개발 **1.0.6**, AI 개발 **0.1.6**. AI 빌드 입력/최소 게임 버전은 1.0.6이다. 이전 개발 1.0.5/0.1.5 JAR도 보존한다.
- 실제 서버/클라이언트 게임 **1.0.2**, AI **0.1.3**, 콘텐츠 **0.1.0**은 변경하지 않았다. FTB·콘텐츠·legacy 빌드 원본·서버 설정·개인 기억/로그도 작업 전 manifest와 대조한다.
- 변경 목록: [5단계 delta](memory-stage05-changes-20260916.csv). 재검증: `dev-tools/Test-MemoryStage05.ps1`. 이 스크립트는 백업 검증/변경 범위/패키지 격리/바이너리 차이만 검사하며 배포·서버 실행을 하지 않는다.

## 3. 5A 지원표

새 타입은 모두 **관리자 raw 원장 전용**이다. 기존 `MATURE_CROP_REMOVED`/`ENTITY_KILLED` 수집과 기존 퀘스트 관찰 알림은 보존한다. 기존 4단계 신의 경험 공개는 허용된 성숙 작물 6종만이다. 새 타입은 GodWatch/Experience/소문으로 자동 전달하지 않는다.

| 범위 | 실제 수집 지점·의미 | 취소/실패·한계 |
|---|---|---|
| 위치 샘플 | PlayerTick.Post, double 좌표/차원/경과 tick. `MovementSamples`는 플레이어별 상태 | 간격 0=미정/OFF. 같은 두 좌표는 연속 정지의 증거 아님. 첫 연결·차원 경계·시계 역행·샘플 공백을 구분. 이동 거리/완전 경로/순간이동을 좌표 차이로 추정하지 않음 |
| 차원 이동 | PlayerChangedDimensionEvent의 실제 from/to | 차원 변경 완료만 기록. 텔레포트와 함께 생기는 두 자료는 다른 측면이지 독립 업적 2회가 아님 |
| 텔레포트 | ServerPlayer.teleportTo 3개 overload + teleportRelative의 시작/반환 실제 위치 | 중첩된 같은 플레이어 호출은 한 묶음. 위치 변화 없음은 취소 확정이 아님. 임의 모드의 직접 위치 쓰기/모든 connection teleport를 망라하지 않음 |
| 채굴 요청/중단 | CommonHooks.onLeftClickBlock의 이벤트 버스 처리 후 결과 | START/STOP/ABORT, canceled와 useBlock/useItem DENY를 별도로 보존. REQUEST_ACCEPTED는 미취소 알림이라는 뜻일 뿐 실제 블록 처리/아이템 획득 성공이 아님 |
| 파괴 시도/결과 | ServerPlayerGameMode.destroyBlock HEAD/RETURN + 실제 removeBlock RETURN | ATTEMPTED, CANCELLED, NOT_REMOVED, REMOVED. 함수가 true라도 실제 제거가 없으면 성공으로 기록하지 않음. `attempt_id`로 연결 |
| 실제 블록 제거 | 실제 removeBlock true의 등록 블록 ID, 수량 1 | 모드 등록 블록도 ID 그대로. 작물 전용 제거 기록과 겹칠 수 있는 별도 측면이며 합산해 업적 2회로 세면 안 됨. 드롭/인벤토리 획득 수량 아님 |
| 공격 시도/결과 | Player.attack HEAD/RETURN, CommonHooks.onPlayerAttackTarget 최종 취소/아이템 허용 결과 | 근접 엔티티 공격. CANCELLED/DENIED_BY_ITEM/반환 시 확인된 체력 감소/감소 확인 못함. 공중 헛스윙·모든 원거리 발사 시도·모드 고유 전투 API는 미지원 |
| 실제 피해 | LivingDamageEvent.Post의 최종 양수 health damage | 플레이어 책임 피해 또는 플레이어가 받는 비플레이어 피해. 실제 대상의 차원/좌표 사용. PvP는 공격자 actor·피격자 entity로 한 원본을 남기므로 피격자 actor 조회의 별도 행은 없음. 흡수/갑옷 소모·0 피해와 처치/전리품은 별개 |
| 아이템 획득 | ItemEntityPickupEvent.Post, original-current 실제 수량 | 지상 아이템 줍기만. 스택이 다른 아이템으로 바뀐 경우 차감을 추정하지 않음. 제작/상자 이동/명령 지급/소비 전체 기록은 아님 |
| 플레이어 사망 | 기존 LivingEntity 죽음 확정 훅 | 사망 확정과 드롭/인벤토리 변화 구분. 기존 처치 소유권 계산은 수정하지 않음 |
| 퀘스트 완료 | 기존 tryComplete 성공 receipt 직후, 실제 완료자만 | 서버 최초 완료를 수주자 전원/FTB 팀 완료로 복제하지 않음. 완료 시각·확인 NPC·수주 인원수. 보상 지급 사실로 승격하지 않음 |
| 퀘스트 평가 | 기존 점수 미달 또는 완료 후 실제 reward issue 결과 | 평가 통과/미달, issue 실패/수락/선택 대기를 분리. 거절된 잘못된 평가 요청 전부를 수집하는 것은 아님. 보상 실행 엔진은 그대로 |

미완결 RETURN 추적은 tick 끝에 누락 진단으로 남기며 성공을 만들어내지 않는다. 원장 queue/full/failed는 기존 거절·공백 진단으로 노출한다. submit은 영속 저장 완료와 다르다. 운영 활성화 시 원래 기능을 바꾸지 않는다는 실제 모드 조합 검증은 남았다.

### 설정/관리

- [action-detail 예시](examples/action-detail.example.json): `enabled:false`, `movementIntervalTicks:0`. 실제 서버에 복사하지 않았다.
- 상세 수집은 기존 action-ledger도 활성/사용 가능해야 한다. 별도 원장·월드 ID·별도 게임 상태 DB를 만들지 않는다.
- 기존 raw 용량 경고는 그대로: 90% 진입 시 서버 채팅/로그에 알림, 관리자에게 `action-ledger.json`의 `maxStorageBytes` 증가와 정상 재시작 안내. 30분 재알림 제한. 용량/부하 때문에 몰래 수집 간격을 낮추거나 자동 삭제하지 않는다.
- `/mythadmin ledger status`에 상세 ON/OFF와 좌표 간격을 추가했다. 명령을 실제 서버에서 실행하지 않았다.

## 4. 5B 구현과 미구현 경계

### 구현·오프라인 검증한 기반

- `DerivedMemory`/`DerivedStore`: 원문 ID·전체 원문 SHA-256·월드/신/플레이어·청중·추출 버전·기록 시각·원문 발췌·중요 pin을 보존하는 append-only sidecar. 원본 개인 기억 v1을 마이그레이션하거나 덮어쓰지 않는다.
- 계획/약속 후보와 인용/조건/취소 후보는 **보수적인 문자열 힌트**이지 정확한 의미 분류나 확정 약속이 아니다. 날짜는 원 발언 시각에서 계산하고 기본 UNSPECIFIED를 유지한다. 추출 시점의 ‘내일’로 이동하지 않는다.
- `ReviewedLink`는 정정/상충/취소/다른 계획을 **명시적으로 검토해 연결할 계약만**이다. 자동 정정 판단, 정정 링크 영속 저장/운영 소비, 자동 중요 사건 선정은 아직 없다. 두 문장 유사하다는 이유로 예전 원문을 삭제하지 않는다.
- sidecar는 단일 writer/queue 16, 프레임 최대 32KB, 명시적 디스크 용량·건수 제한, 파일 lock, force 후 게시, checksum/순번 검증. 손상·잘린 tail은 기존 파일을 덮지 않고 FAILED로 차단하며 raw 검색은 유지한다. 삭제/교정/pin 변경된 원문의 이전 파생 자료는 해시·현재 원문 재검증으로 제외한다. 파일에 남은 오래된 파생 bytes 자체를 자동 삭제하지는 않는다.
- `DerivedService`는 회상 V2 worker에서 선택적으로 원문 최대 8개씩 정리한다. 별도 모델 작업 없음. 설정 OFF는 파일을 생성하지 않는다. 닫힌 journal의 늦은 worker가 sidecar를 다시 열지 못하도록 종료 경계를 둔다.
- `SemanticIndex`/`HybridRetrieval`: 준비된 벡터를 받는 순수 검색 시험 API. 모델 fingerprint/차원/유한값 검사, **검색 전에** 월드·신·플레이어·청중·현재 원문/시간/망각 범위 제한, 단어 후보+의미 후보 rank fusion, 최대 3개 원문, 지연/실패 시 기존 검색 반환. 의미 유사도만으로 사실/부정/정정/성공을 확정하지 않아 AMBIGUOUS 유지.
- `ObservedExperienceSummary`: **게임 발급 ExperienceLease의 허용 창(최대 16개)**만 코드로 묶고 동일 event ID를 중복 세지 않는다. 모든 관찰 참조를 유지해 철회 검사 가능. 창 일부를 하루 전체 행적/획득 수량/성격으로 확장하지 않는다. 이것은 검색-only 도구이며 대화 프롬프트에 자동 투입하지 않았다.
- `/ai_memory status`에 파생 상태/용량/거절과 ‘의미 검색 미연결’을 명시한다. sidecar 용량도 90%에 서버·로그 알림 및 `ai-derived-memory.json` 용량 증가 안내를 제공한다.

### 아직 구현/연결하지 않은 것

- **실제 임베딩 backend/벡터 생성·영속 인덱스·운영 의미 검색 연결**. 설정의 `semanticRetrieval`은 예약 필드이며 true라도 backend를 만들거나 호출하지 않는다. 실제 `DerivedService.search`는 지금도 기존 RecallSearch 결과를 반환한다. 합성 벡터 성공을 ‘바다→수영을 실제 모델이 이해한다’는 품질 결과로 설명하면 안 된다.
- 자동 LLM 정리/정정/요약 및 기존 대화·자발 대사·시각 평가와 공통 모델 입장 관리. 이 선행 관리와 모델 선택/별도 실행 승인이 마련되기 전에 자동 생성 일을 시작하지 않는다.
- 새 행동 타입의 신별 공개 규칙/주시 투영·AI 소비. 자동 관계 주시 조건도 여전히 미정이다. 기존 Fortuna 시험 경로는 활성화하지 않았다.
- 개인 원문 v1의 기존 12,000개/버킷 2,000개·사소한 기억 30일/수동 pin 정책은 유지한다. 이번 sidecar는 원문 한도를 없애거나 중요한 사건을 자동 영구 보존하는 완성본이 아니다.

설정 예시는 [ai-derived-memory](examples/ai-derived-memory.example.json)이며 OFF/용량 0이다. 활성 시 `maxStorageBytes`를 명시해야 한다. 운영 설정은 바꾸지 않았다.

## 5. 검증

오프라인 명령(프로젝트 루트, 기존 Java 21 wrapper):

```powershell
.\dev-tools\run-mythictrpg-gradle.cmd compileJava actionDetailTest actionLedgerTest godWatchTest experienceStageTest memoryFoundationTest jar --offline --no-daemon
.\dev-tools\run-mythictrpg-gradle.cmd -p ../mythai-ai-response compileJava derivedMemoryTest memoryFoundationTest memoryRecallTest recallStageTest experienceDialogueTest jar --offline --no-daemon
.\dev-tools\Test-MemoryStage05.ps1
```

최종 테스트 수/해시/변경 대조 결과는 아래 최종 검사 절에 기록한다. 첫 실행에서 새 테스트의 `Submission`과 future 반환형 사용 오류를 수정했다. 제품 소스 컴파일은 통과했고 해당 테스트를 수정 후 재실행했다. Gradle 캐시 `.lck` 쓰기에는 권한 승인을 사용했으며 다운로드 옵션 없이 `--offline`만 사용했다.

합성 기록 측정은 실제 1/4/6인 인게임 시험이 아니다. raw fixture는 각각 120/480/720개 행, 약 82,491/330,351/495,591 bytes였다. sidecar는 30/120/180개, 약 18,071/72,332/108,552 bytes다. final artifacts에 enqueue p95, 경과 시간, JVM heap delta(음수는 GC 영향), 거절/배출 상태를 기록한다. raw fixture는 단일 서버 생산자 버스트이고, sidecar fixture는 1/4/6개의 동시 생산 작업이다. 이 값으로 보관 기간/운영 quota를 확정하지 않는다.

### 최종 검사 기록

- 게임/AI 컴파일 및 개발 JAR 생성 PASS. 기존 회귀 1,630 + ActionDetailTest **402** + DerivedMemoryTest **434** + ObservedSummaryFixture **4** = **2,470개 검사 PASS**. 검사 수에는 데이터/파일/정적 bytecode 확인이 포함되며 인게임 시나리오 수가 아니다.
- 새 게임/기존 배포 AI 조합 및 새 게임/새 AI 조합 각각 **7 JAR/244 패키지** 격리 PASS. 이전 게임 ZIP 엔트리 **1,002/1,017**, AI **316/322**가 동일하고 차이는 명시한 연결 클래스/metadata만이다. 기존 퀘스트 소스는 이번 read hook만 제거하면 직전 소스와 동일함을 별도로 검사했다. 이는 다른 모드의 실제 런타임 호환 증명은 아니다.
- 작업 전 manifest **2,231개 중 2,208개 불변**, 기존 파일 23개 수정 + 새 파일 21개 + snapshot 직전 helper 1개 = delta **45개**. 서버 배포·클라이언트 필수 JAR/설정/개인 기억/로그, 다른 모드/legacy, 이전 단계 CSV와 기존 개발 JAR 보호 대조 PASS. LP 원본/백업 hash PASS.
- 최종 raw 합성 fixture: `mythictrpg-main/build/action-detail-test-artifacts/detail-3322861152727370973/`. 1/4/6인 120/480/720행, enqueue p95 **7/4/<1 µs**, 총 경과 **541/1,186/1,593 ms**, pending/rejected=0. 이는 설정을 정할 수 있는 실서버 tick 실측이 아니다.
- 최종 sidecar 동시 fixture: `mythai-ai-response/build/derived-memory-test-artifacts/derived-12050347722104597313/`. 1/4/6인 30/120/180행, 총 경과 **59/134/174 ms**, 거절=0. 저장·검사 합산이며 실제 대화/임베딩 지연이 아니다. 기존 회상 tiny fixture p95 0.359ms도 전체 응답시간으로 해석하지 않는다.
- 게임 후보: `mythictrpg-main/build/libs/mythictrpg-1.0.6.jar`, SHA-256 `B6D2809402F35D112D24DD5CEA81112D72B227CD78155D601518499A4539F53C`.
- AI 후보: `mythai-ai-response/build/libs/mythai_ai_response-0.1.6.jar`, SHA-256 `526725DE811DD11510C65019AFA01072A79861365B5C02C30CA05A6C738D9F80`.
- 실제 미실시: Minecraft/GameTest 서버, LLM/임베딩 호출, HUD, 새 모델 설치, 실제 보호/전투/텔레포트 모드 조합, 1/4/6인 인게임 부하와 전체 대화 품질/지연. Gradle의 기존 폐기 예정 기능 경고는 남아 있으며 이번 빌드 실패를 의미하지 않는다.

## 6. 남은 검증과 다음 작업

1. 별도 승인 후 4단계 최소 종단부터: 서버/클라이언트 후보 동기화, 실제 Mixin 적용, 정상 부팅, 포르투나 관찰→재대화, 신 B/청중 변경/철회 차단, 모델 말투·문맥·지연 비교.
2. 좌표 간격/시험용 quota 선택 후 5A 실제 이벤트 취소·보호 모드·텔레포트·PvP·퀘스트/FTB/보상 결과 검사. 1/4/6인 tick/쓰기 backlog/디스크/메모리/누락/전체 대화 지연 측정. 이때 설정 용량 정책을 결정한다.
3. 5B 운영 의미 검색·벡터 재색인·정정 관계 저장/해석·모델 공통 입장 관리 구현/선택 및 원 대화와 다른 표현을 이용한 검색-only/실제 모델 품질 시험. 기본 단어 검색 fallback을 유지한다.
4. 위 수용 조건이 충족되기 전 **5단계 전체 완료로 표기하거나 6단계 전서구/소문을 자동 시작하지 않는다**.

### 되돌리기

운영 배포를 하지 않아 서버 되돌리기는 필요 없다. 개발 소스는 이번 delta와 직전 snapshot을 대조해 이번 변경만 선택 복원하며, 기존 dirty 변경까지 Git reset 하지 않는다. LP 복원은 [LP 기반 가이드](LP_MEMORY_FOUNDATION_GUIDE.md)의 이전 단계 delta까지 함께 검토한다.

raw schemaVersion은 1이지만 새 action enum/subject kind를 추가했으므로 **새 상세 기록이 있는 저장소를 옛 JAR이 읽는 다운그레이드는 호환 보장하지 않는다**(알 수 없는 타입은 실패/차단). 활성화 전에 새 저장 자료와 직전 버전을 함께 보존해야 한다. sidecar는 별도 하위 디렉터리라 원문 v1을 변경하지 않는다. 이번에는 운영에 새 타입을 기록하지 않았다.
