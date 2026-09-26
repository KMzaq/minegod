# 로드맵 3단계 — 신별 주시·관찰 증명 기반

2026-09-15 / **소스·오프라인 기반 완료, 운영 연결/배포 없음, 4단계 미착수**

## 승인과 선행 조건

사용자는 최신 로드맵 3단계만 승인했고, 관찰 범위는 **신별 권능·영역·장소와 가림 조건에 따라 제한**을 선택했다. 시작·유지·중지와 관계 기준은 아직 기획되지 않았다고 답했다. 따라서 임의 관계 수치/신별 자동 시작/전지적 관찰/사적 대화 공개를 추가하지 않았다.

작업 전에 [2단계 변경 목록](action-ledger-stage02-changes-20260915.csv)의 27개 현재 해시를 대조했다. 실제 기존 GodAttentionState는 첫 MAIN_ENTRY 수주에 따른 신별 관심 대상이며 관찰자가 아니다. 게임 GodDefinitionManager와 PlayerMythDataService가 기존 신 ID/관계 원본을 소유하는 것도 확인했다. 해당 저장소·관계값·퀘스트 의미는 변경하지 않았다.

원장 604개 + 기존 소문 39개를 소스 변경 전 다시 실행해 선행 조건을 확인했다. 2단계의 실제 Mixin 적용/서버 부팅은 미검증 상태로 승계하며, 오프라인 통과를 운영 검증으로 간주하지 않는다.

## 구현과 비활성 범위

| 구분 | 이번 결과 |
|---|---|
| 게임 개발 소스·새 후보 JAR | MythicTRPG **1.0.4**, 아래 기반 포함 |
| AI 개발 | **0.1.4** 유지, 소스/프롬프트/JAR 변경 없음 |
| 실제 서버·클라이언트 | 게임 **1.0.2**, 서버 AI **0.1.3**, 콘텐츠 **0.1.0** 그대로 |
| 기존 행동 원장 | 기본 OFF 유지, 기존 hook/관리자 명령/90% 용량 안내 변경 없음 |
| 새 주시 | 기본적으로 **인스턴스가 설치되지 않음**. 운영 JSON/시작 명령/자동 이벤트 등록 없음 |
| 행동→관찰 | 실제 원장 파일/receipt와 새 coordinator를 연결한 **오프라인 통합 시험** 완료. 운영 capture hook은 아직 기존 raw.submit 그대로 |
| 관찰→AI 기억·대화 | 미연결, 4단계 범위. 이번에 ExperienceView Provider/프롬프트를 만들지 않음 |
| 전서구/소문 | 변경 없음. 직접 주시는 전서구 전파와 별개이며 해당 플레이어만의 소문 차단 유지 |

코드는 `gameplay.watch`에만 추가했다. 레거시 `ai.memory`, Snapshot/Proposal/공개 Provider, FTB 보상/실행 소유권은 변경하지 않았다. 원장 쪽 추가는 `AsyncActionLedger.fence()` 하나다. 서버 스레드에서 기존 pending 쓰기 뒤에 순서 표식을 넣어 worker가 확정한 sequence를 받는 비동기 API이며, 현재 기존 생산자는 호출하지 않는다.

### 주시 생명주기·순서

- `WatchContract.Approval`: 게임 월드/기존 God ID/대상 플레이어/자격·원인 ref와 revision/정책 snapshot. 자격이 있다는 사실만으로 ACTIVE가 되지 않는다.
- `Watch`: 고유 watchId, ACTIVE/PAUSED/ENDED, revision, 원장 기준 반개구간 `[from, until)`. 시작/중지는 같은 raw writer의 FIFO fence를 기다리는 전용 관찰 worker에서 확정한다.
- 예: 같은 tick의 이전 행동이 디스크 대기 중이어도 `이전 행동 → 시작 fence → 다음 행동` 순서를 유지한다. 마지막 저장 head를 서버 스레드에서 읽어 즉시 시작하는 방식은 사용하지 않는다.
- 재개는 **새 watchId/새 구간**이다. 종료된 ID 재시도는 재활성화하지 않는다. 대상별 활성 인덱스에서 해당 플레이어의 신만 검사한다.
- 재시작 때 저장된 ACTIVE는 PAUSED로 닫고 게임의 명시적 재검증을 기다린다. 저장돼 있던 증명은 보존하지만 관찰하지 못한 과거 raw 이력을 다시 관찰하지 않는다.
- `suspendTarget`은 로그아웃/플레이어 generation 변경 연결용이다. 다른 플레이어의 주시는 중지하지 않는다. 재접속은 자동 재개가 아니다. 이것은 미정 관계 임계값을 정한 것이 아니라 늦은 콜백의 권한 확장을 막는 기술 경계다.

### 관측·공개·취소

- `Policy`: 신별 버전, 권능/영역 참조, 명시한 차원/좌표 영역, 관측 필드. 빈 범위는 허용 아님. 실제 권능/장소 목록은 테스트용 값 외에 작성하지 않았다.
- `Scene`: 게임 스레드에서 발생 ID/boot session/order와 당시 context 근거, 권능/영역 판정, 가림/사적 장면, 필드별 관측 가능 여부·관련 플레이어별 공개 근거를 불변 snapshot으로 만든다. worker가 현재 Minecraft 월드를 재조회하지 않는다.
- 가림/사적 장면은 보수적으로 차단한다. 명시적으로 사적 장면을 예외 허용하는 콘텐츠 정책은 이번에 구현하지 않았다. 다른 신과의 사적 대화 수집기도 없다.
- raw durable receipt 뒤에만 `Proof`를 커밋한다. event/sourceRevision/sourceRef/watchId/policy/context/sequence와 DIRECT_WATCH 출처를 유지한다. 실제 전서구 PHYSICAL_WITNESS와 전언은 이 producer의 지원 경로가 아니다.
- 투영은 ACTOR/ACTION/SUBJECT_TYPE/SUBJECT_ID/LOCATION/TIME/OUTCOME 중 허용된 값만 원본에서 생성한다. raw payload 전체나 마음·의도는 복사하지 않는다. 작물 제거는 아이템 획득으로, 사망은 전리품 지급으로 바꾸지 않는다.
- **알고 있음과 공개 허용은 별개**다. 현재 게임 청중이 필드에 관련된 **모든 플레이어의 공개 규칙**을 충족해야 해당 필드를 반환한다. 빈 청중은 공개 권한이 아니다. 허용 밖 사건 수/존재를 반환하지 않는다.
- 공개 규칙 revision 변경은 옛 필드의 공개를 자동 확대하지 않는다. 새 근거/투영 승인이 필요하다. 비공개 필드를 프롬프트에 넣고 말하지 말라고 지시하는 구조가 아니다.
- 원본 사건/관찰 ID/정책·자격·관측 context ref 취소는 append-only tombstone으로 영속화한다. 논리적 REVOKED 상태를 tombstone으로 표현하고 읽기에서 배제한다. 주시의 정상 종료 자체는 과거 지식을 취소하지 않는다.
- `ReadSnapshot + revalidate`는 같은 게임 세션/청중과 관련 증명·현재 공개 필드만 다시 확인한다. 다른 플레이어의 정상 기록은 일괄 무효화 사유가 아니다. 실제 AI 결과 적용 시 이 검사를 호출하는 연결은 4단계에서 해야 한다.
- 조회 시 raw 저장소에서도 world/actor/sequence/event/sourceRevision/sourceRef를 재확인한다. raw 불가용/손상은 `available=false`이며 허용된 0건과 구분한다.

### 저장·실패 경계

- `WatchJournal`: `watch-v1.journal`, schema/world/transaction sequence + 길이/CRC32 + JSON의 append-only 프레임. 단일 파일 잠금, 프레임 최대 64 KiB, 용량/인덱스 상한, force 이후 receipt. 매 행동마다 전체 과거 파일을 다시 쓰지 않는다.
- 한 OBSERVE transaction에 해당 사건의 증명들과 처리 cursor를 함께 남긴다. **증명 0건도 처리된 발생**으로 저장한다. 중복 source 재시도는 기존 receipt를 반환하며 현재 정책으로 재투영하지 않는다. 살아 있는 capture API는 새 boot/order만 받으며 재생 API가 아니다.
- 손상/잘린 프레임/다른 월드/지원 밖 schema/순서 오류는 fail-closed다. 자동 잘라내기·초기화·삭제·월드 ID 입양 없음. force 후 receipt 전 실패한 완전 프레임은 재시작에 한 번 복원한다. 내구성이 불명확한 작업을 성공으로 보고하지 않는다.
- `AsyncGodWatch`: 유한 큐/단일 daemon IO worker. raw receipt/fence 대기 상한은 worker에서 5초이고 게임 스레드는 기다리지 않는다. 큐 포화·writer 실패 시 해당 관찰 future 실패 및 Status 진단, 원본 기록/기존 게임 진행과 분리한다. 종료는 제한 시간 내 drain이며 timeout은 성공 아님.
- 현재 Status의 rejected 수는 **프로세스 내 진단**이다. 원본처럼 영구 누락 요약 파일을 별도로 구현하지 않았다. 재시작 때 누락 행동을 소급 목격으로 채우지 않지만 정밀한 관찰 공백 운영 진단은 보완 대상이다.
- 새 저장소 운영 용량은 미정이며 생성 자체가 비활성이다. 현재 구현상 raw quota와 별개인 명시적 watch Limits가 필요하다. **운영 설치 전 두 저장소 총량/상한과 90% 서버 알림을 연결해야 한다.** 기존 raw 90% 안내만으로 새 증명 저장소도 경고된다고 안내하면 안 된다. 자동 정리는 없다.

`GameWatchGateway`는 실제 게임 registry/현재 runtime/서버 스레드/접속 객체와 규칙을 확인하는 **미등록 adapter**다. 관계 원본이나 GodAttentionState를 수정하지 않으며 실제 규칙 구현체는 없다. 설치하려면 승인된 Rules, lifecycle·로그아웃/respawn/reload 처리, 원장 hook의 단일 제출 분기, 관리자 진단/용량 정책이 필요하다. adapter 준비와 운영 게임 이벤트 연결 완료를 혼동하지 않는다.

## 검증 결과

최종 게임 `jar godWatchTest actionLedgerTest memoryFoundationTest --offline --no-daemon` 성공.

- 신규 **GodWatchTest 98개**: A 주시/B 미주시/대상 격리, 같은 tick 앞뒤/원본 pending fence, pause/end/resume, 재시작/재접속, 권능·영역·장소·가림·사적 장면, 숨긴 필드, 복합 플레이어·청중/세션 변화, 원본/정책/자격/context/증명 취소, 무관한 플레이어 쓰기, 재시도/늦은 콜백, 파일잠금/손상/용량, 원본 실패/관찰 실패/큐 포화/종료 timeout/force 후 복구.
- 기존 **ActionLedgerTest 604개**, **RumorLedgerTest 39개** 재통과. 기존 Mixin 위치/취소·성공 경계 및 원래 알림 경로의 바이트코드 검사를 포함한다.
- 후보 게임 1.0.4 API로 AI `compileJava check --offline --no-daemon`: **656 + 54 + 121 = 831개** 회귀 통과. 실제 LLM 호출 없음.
- 합계 **1,572개 오프라인 검사**. 인게임 시나리오나 모델 정확도 수가 아니다. 첫 테스트 컴파일의 EnumMap 제네릭 타입 오류는 수정 후 재검증했다.
- [감사 도구](../dev-tools/Test-GodWatchStage.ps1): 후보 게임+서버 AI, 후보 게임+개발 AI 각각 **7 JAR / 242 패키지** 충돌 없음. 중복 ZIP 엔트리 없음. 기존 게임 1.0.3 JAR의 **969/971 엔트리 완전 동일**; 차이는 AsyncActionLedger 본체의 추가 fence와 버전 metadata뿐이며 새 watch 패키지/내부 클래스만 추가됐다. 기존 게임 실행·보상·스토리·FTB·Mixin 코드가 바뀌지 않았음을 함께 확인했다.

게임 후보 SHA-256: `5EF3FAA0068F34884DFC199B68C58396E422D83DD44E4F59CCE13C0597B17936`.

재실행(서버 없이, Java 21/기존 Gradle 캐시 필요):

```powershell
.\dev-tools\run-mythictrpg-gradle.cmd jar godWatchTest actionLedgerTest memoryFoundationTest --offline --no-daemon
.\dev-tools\run-mythictrpg-gradle.cmd -p ../mythai-ai-response "-PmythicTrpgApiJar=../mythictrpg-main/build/libs/mythictrpg-1.0.4.jar" compileJava check --offline --no-daemon
.\dev-tools\Test-GodWatchStage.ps1
```

## 보존·복원

- LP 원본 JAR 3개, LP AI 필수 소스/입력 298개, 기억 도입 전 전체 개발 소스 포함 906개 해시 일치. 2단계 직전 2,186개와 이번 직전 2,203개 백업도 검증했다. LP는 덮어쓰지 않았다.
- 이번 직전: [before-god-watch-stage03-20260915-210456-433 manifest](../server/backups/before-god-watch-stage03-20260915-210456-433/file-manifest.csv). 기존 미커밋 소스/배포본/설정/개인 기억·로그/필수 빌드 입력을 포함한다. `.git`·캐시·전체 월드 청크/플레이어 진행도의 완전 백업은 아니다.
- 백업 도구에 stage3 이름 선택만 먼저 추가하고 snapshot을 생성했다. helper의 수정 전 해시는 2단계 snapshot에서 가져와 [3단계 변경 목록](god-watch-stage03-changes-20260915.csv)에 별도 포함했다. 다른 원본 소스 수정은 snapshot 이후다.
- 기존 백업 대비 변경 7개 외 **2,196/2,203개 기존 파일 동일**. helper 추가분 및 신규 파일까지 source/docs delta **16개**다. CSV 자체와 새 build 후보/캐시는 별도 산출물이다. 서버 mods/client-required-mods/config/개인 기억/기존 단계 CSV는 변경하지 않았다.
- 단계3만 복원 시 변경 목록의 MODIFIED는 이번 snapshot에서, helper만 기록된 2단계 snapshot에서 개별 복원한다. ADDED는 별도 보존 후 활성 소스에서 제외한다. LP 전체 복원은 [기반 복원 가이드](LP_MEMORY_FOUNDATION_GUIDE.md)의 기존 단계별 차이도 함께 검토한다. Git 전체 reset/월드 초기화/무관한 사용자 변경 덮기는 하지 않는다. 이번에 실제 복원은 하지 않았다.

## 남은 검증과 다음 단계

1. 이번 승인 안에서 가능한 **3단계 기반/오프라인 정보 경계**는 완료했다. 사용자 요청대로 여기서 멈추며 **4단계는 시작하지 않는다**.
2. 4단계의 작은 종단 시험을 설계할 출발점은 준비됐다. 별도 승인 시 명시적 시험 정책으로 수확 1종을 연결할 수 있다. 운영 신의 시작/유지/중지·관계 임계값은 기획 확정이 필요하다.
3. 운영 설치 전 GameWatchGateway의 실제 이벤트/생명주기/관리자 진단·관찰 공백/증명 저장소 90% 경고 연결 및 서버 세션에서만 발급하는 읽기 capability를 완성해야 한다. AI에 이 내부 진단/원장 API를 그대로 공개하면 안 된다.
4. 서버/GameTest 실행, 실제 Mixin/타 모드 런타임 충돌, 파일 lock/force의 OS 강제 종료·전원 차단 조건, 실제 1/4/6명 tick/메모리/디스크 부하와 신규 월드 identity 저장은 미검증이다. 신규 테스트의 예외 주입은 실제 서버 강제 종료 시험이 아니다.
5. 실제 LLM 자연스러움·기억 활용·응답 시간/HUD·종단 대화는 미검증. 모델 설치/전환·LLM 호출·JAR 배포·서버 실행은 하지 않았다. 기존 개인 회상 경로와 서버 테스트 상태는 이번 작업 전과 같다.
