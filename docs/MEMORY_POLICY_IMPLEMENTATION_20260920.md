# 최신 정책 기준 1~5단계 소스 구현·오프라인 검증

2026-09-20 / 개발 게임 1.0.8 · AI 0.1.9 / 미배포 / 서버·GameTest·실제 모델 미실행 / 6단계 미착수

정책 원본은 [행동 기록·실적·주시 정책](ACTION_RECORDING_POLICY_20260917.md)이다. 이번 기록은 그 정책의 **구현된 범위와 남은 경계**를 구분한다. 과거 단계 문서의 테스트 수나 문서만 반영했다는 문구는 당시 이력이다.

## 1. 보존과 변경 범위

- 작업 전 LP 소스 298개, 기억 기반 이전 소스 906개, LP 원본 JAR 3개의 SHA-256을 검증했다. LP를 덮어쓰지 않았다.
- 이번 변경 직전 상태 2,284개 파일을 `server/backups/before-memory-stage05-20260920-143804-077/`에 복사하고 manifest와 대조했다. 소스·문서·기존 개발 JAR·서버 설정/배포물·개인 기억/시험 로그/데이터팩을 포함한다. **월드 지형 전체 백업은 아니다.**
- 기존 미커밋 퀘스트 참여 유형/다인 대화 작업을 보존했다. 비교 기준은 Git HEAD만이 아니라 위 작업 직전 snapshot이다.
- `mine/mine`의 레거시 AI 원본/JAR, FTB Quests·콘텐츠 레지스트리 소스, 운영 설정·월드·배포 JAR은 수정하지 않았다.
- 개발용 게임 1.0.8 JAR은 AI 컴파일 입력이다. AI 0.1.9는 게임 1.0.8 이상을 요구한다. 레거시 오버레이 빌드 구조를 해체하지 않았다. 서버의 게임 1.0.2/AI 0.1.3/콘텐츠 0.1.0은 그대로다.

## 2. 구현된 연결

| 영역 | 소스에서 연결한 내용 | 운영/정보 경계 |
|---|---|---|
| 기록 선별 | 일반 이동·공격·채굴·일반 처치의 개별 영구 기록을 기본 경로에서 제외. 기존 통계·조우·스토리 이벤트 유지 | 진단용 상세 수집은 `routineDiagnostics` 명시 설정으로만 사용 |
| 인정 실적 | 기존 건축 설치 원장을 재사용. 본인/타인 직접 설치 제외, 정상 성숙 작물/성장 나무 인정, 확인된 생성기 제외, 미기록 자연 간주 | 바닐라 통계를 고치지 않음. 원장 손상은 미기록과 달리 실적 차단 |
| 보상 소비 | 성공한 제거에서 한 번 집계하고 등록형 참여 퀘스트·즉석 SIDE의 블록 목표에 전달 | 기존 묶음 `BLOCK_BROKEN` 알림으로 보상 목표를 다시 증가시키지 않음 |
| 중요 사건 | 네임드/제작 전투 결과, 실제 퀘스트 상태 전환/보상 지급, 바닐라·모드 발전 과제 달성 | 위치 불명은 null+사유. 비공개 사건을 자동으로 신에게 공개하지 않음 |
| 보상형 주시 | 기존 claim에 `watch` 보상과 개인 획득 상태 통합, 선택 보상은 선택 후 획득 | 메인 완료/관계/공동 완료만으로 지급하지 않음. AI_ACTION 경로 거부 |
| 관측 | 실제 획득+작성된 신별 정책으로 재개. 신별 허용 목록/절대 차폐, 청중 검사 | 로그아웃·차폐는 획득 삭제가 아님. 늦은 콜백/과거 재전달로 소급 관측하지 않음 |
| 일상 경험 | 실제 관측 허용된 성공 행동만 메모리에서 집계해 제한된 요약으로 저장/투영 | 전체 통계 차이를 가져오지 않음. 집계 창 미설정(0)은 비활성 |
| 개인 회상 | 약속·정정·이행 진술 우선, 반복 원문 검색 슬롯 중복 제거, 잡담 연상 감쇠 | 진술은 사실 확정이 아니며 완료/관계/보상을 실행하지 않음 |
| 시간 | `REAL_KST` 선택 시 ‘내일’은 발언 기준 현실 날짜, 일출/일몰 표현은 게임 일광 조건 | 미설정 시간대에 임의 날짜를 넣지 않음. 일광을 현실 시각으로 환산하지 않음 |

### 채굴 출처와 호환

`PlayerConstructionState` 버전 3이 기존 버전 1/2를 읽는다. 직접 설치/DERIVED 외 `GENERATOR`를 추가했다. 생성기에 설치자가 없더라도 제외하며, 합성 UUID를 건축 기여자로 인정하지 않는다. 도구 변환으로 알려진 직접 설치/생성기 출처를 지우지 않는다.

일반 아이템 설치는 NeoForge의 snapshot 취소/복구가 끝난 성공 결과에서 반영한다. 피스톤은 이동 전 출처를 캡처해 성공한 도착 위치에 옮기며, 낙하 블록은 엔티티 NBT에 출처를 싣고 실제 착지 성공 때 복원한다. 공기/액체로 제거된 위치를 정리하고, 묘목 성장 원목을 직접 원목 설치와 구분한다. 유체 생성 돌·조약돌·흑요석·현무암 및 `mythictrpg:generator_outputs` 블록 태그를 지원한다.

별도 실적은 `mythictrpg:eligible_block_mined`의 기존 custom-counter 합계/블록별 키다. `ELIGIBLE_BLOCK_MINED`는 성공 제거의 보상 소비 경로이며, 기존 BLOCK_BROKEN을 사용하는 조우/스토리 등 비보상 소비자를 없애지 않는다. 새로 만드는 업적·FTB 자체 통계 목표까지 자동으로 보정되지는 않으므로 콘텐츠 보상에는 이 인정 실적을 사용해야 한다. 바닐라 발전 과제의 판정/보상은 변경하지 않는다.

검증 범위는 순수 인정 정책·NBT 재로드·손상 보존·설치된 Minecraft/NeoForge bytecode의 주입 대상 확인이다. **실제 설치 취소, 성장, 피스톤 연쇄, 낙하/폭발, 모드 변환의 월드 동작 검증은 남았다.** 네이티브 설치 scope 밖에서 외부 모드가 직접 보내는 설치 이벤트는 그 모드의 commit 계약 확인이 필요하다. 이벤트/기본 `Level` 변경 경로를 우회하는 생성기도 개별 어댑터가 필요하다.

### 중요 사건 계약

`ActionRecord.Draft`에 선택적 `Details(biomeId, locationStatus, runId, participants)`를 추가했다. 기존 생성자/기존 v1 행은 유지하며 누락 정보는 LEGACY_UNKNOWN이다. 위치 미확인 오프라인 전환은 OFFLINE_UNKNOWN, 위치 null, 차원 `mythictrpg:unknown`으로 구분한다. 가짜 0,0,0을 실제 장소로 쓰지 않는다.

- 타입: `BATTLE_RESULT`, `QUEST_TRANSITION`, `ADVANCEMENT_EARNED`, `OBSERVED_ACTIVITY_SUMMARY`.
- 전투: `mythictrpg:detailed_battle_targets` 엔티티 타입 태그로 선별한다. 이름표를 네임드 판정으로 쓰지 않는다. 단일 대상에서는 확인된 피해 기여자+처치자를 최대 64명까지 기록하며 근거 종류를 명시한다. 주변/팀/지원자를 추측하지 않는다. 제작 전투 엔진은 `ImportantEvents.battle`에 실제 run/참가자/결과를 제공한다. 레이드 엔진이나 전서구 콘텐츠를 새로 만들지는 않았다.
- 퀘스트: 기존 실제 생산 지점의 수주, 제출, 목표 완료, 완료, 경쟁 탈락/미제출 종료, 전역 완료에 따른 무효화, 즉석 퀘스트 만료, 평가 및 보상 지급을 기록한다. 존재하지 않는 포기/실패 기능을 만들어내지 않는다. 완료자와 보상 수령자는 분리한다. 오프라인 공동 정산도 기록하되 위치는 미확인이다.
- 발전 과제: 기본/외부 모드의 `AdvancementEarnEvent`와 완료된 criterion 근거로 달성을 식별한다. 숨겨진 발전 과제/레시피도 필터로 누락시키지 않지만 비공개를 유지한다. 다른 모드의 독자 업적 DB가 이 이벤트를 쓰지 않으면 추가 생산자 연결이 필요하다.
- `submitTransition`은 동일 확정 전환의 재알림에서 최초 시각/위치를 유지한다. 결과·참가자 등 의미가 달라진 재사용은 거부한다. 이전 사건 재시도에 새 관측 증명을 붙이지 않는다. 일반 행동의 엄격한 중복 검사는 유지한다.
- 공동 전투 원장은 실제 참가자별 조회 인덱스에 같은 사건을 연결한다. **원장 참가자 등재는 그 참가자의 신이 봤다는 증거가 아니다.** 기본 관찰 생산자는 사건 actor 기준이며 공동 전투의 별도 관측자는 전투 엔진이 정식 관측 근거를 제공해야 한다.

### 보상·획득·관측

기존 보상표/직접 보상/선택 보상에 넣을 수 있는 예시(실제 콘텐츠에는 삽입하지 않음):

```json
{"type":"watch","godId":"mythictrpg:fortuna","displayName":"포르투나"}
```

등록된 God Definition을 검증한다. 개인 보상 claim의 자동 지급/실제 선택 표시와 주시 획득을 같은 SavedData 변경에서 처리한다. 중복 획득은 최초 근거/시각을 유지하며, 오래된 지급 영수증이 정리되어도 획득은 남는다. v2 지급 저장은 v1을 읽고 미래 버전/손상은 원본 보존 후 읽기·지급 불가로 둔다. 기존 아이템·화폐 등까지 디스크 트랜잭션으로 묶은 것은 아니다. 기존 at-most-once 지급 표시 후 부수 효과를 실행하는 크래시 한계는 유지된다.

`/mythquest watches`는 본인의 `OOO의 주시` 획득 목록만 보여준다. 획득=현재 모든 행동을 관측 중이라는 뜻은 아니다. 랭킹 제출 완료자 중 보상 구간 밖인 사람은 주시를 받지 않는다. 즉석 SIDE의 보상도 고유 instance ID를 지급 근거로 기존 claim 경로에 연결한다. 현재 즉석 템플릿은 기존 보상 단계의 자동 보상 목록을 사용하며 선택 보상 정의 자체를 새로 추가하지 않았다. 주시가 작성된 단계라면 같은 개인 획득 규칙을 따른다.

`reward-watch.json`은 서버 로컬 명시 정책이다. 없으면 OFF다. 신·권능·영역·장소·이벤트 타입·하늘 요구 조건과 차폐 영역을 작성해야 한다. 차폐는 영역별 `blockAll` 또는 `allowedGods`로 정의하며 신화 위계를 추측하지 않는다. 잘못된 차폐 설정이면 개발 시험 경로까지 관측을 비활성화해 우회를 막는다. 보상 관측과 개발 시험은 같은 차폐/청중 검사를 사용한다. 재시작/재접속 후 정책을 다시 확인하고, 중지 저장이 늦었으면 제한된 기술 재시도로 재개한다.

일상 요약은 현재 관측 가능한 성공 행동의 카운트이며 총 통계나 연속 전 구간의 목격 증명이 아니다. 관측 시작 전/차폐/오프라인 구간은 채우지 않고, 미완성 집계는 종료·가림 시 버린다. 원문 개인 대화나 퀘스트/숨겨진 업적은 원격 주시 정책만으로 공개하지 않는다. 현재 Experience의 확장 타입은 전투 결과와 허용 활동 요약이다. **퀘스트·업적의 원격 AI 경험 투영은 별도 명시 공개 정책이 없어 차단 상태**이며 기존 게임 퀘스트 Context의 권한을 유지한다.

### 개인 기억·용량

새 회상 정책은 `recallV2` 경로에 적용한다. 보호 후보 판정은 검색/저장 우선순위 힌트다. 문장 패턴만으로 실제 이행·영구 성격·관계 수치를 확정하지 않는다. 유휴 추출에 `REPORTS_FULFILLMENT` 후보를 추가했지만 원문 인용/범위 검증을 거친 자기 진술일 뿐이다. 추출 fingerprint는 extractive-v2로 분리하여 이전 sidecar를 새 정책 결과로 오인하지 않는다. 실제 의미 검색/추출은 기존 OFF/SHADOW/ON 및 모델 검증/부하 제어를 유지한다.

중요 후보는 30일 연상 제한을 적용하지 않고, 오래된 잡담은 연상에서 줄이되 명시 회상 요청에는 원문을 찾을 수 있다. 중복 발언은 검색 슬롯을 중복 점유하지 않는다. 정정/취소/이행 발언을 자동 덮어쓰기하지 않고 소수 근거와 함께 전달한다. 완료했다고 말했을 때 계속 같은 약속을 재촉하지 않도록 정책을 보완했다. 매 턴 추가 LLM을 강제하지 않는다.

`ai-memory-retention.json`이 없으면 이전 수치(전체 12,000개, 신·플레이어·월드 범위 2,000개, 원문 파일 총 64 MiB)를 유지한다. `protectedReserveEntries`/`protectedReservePerScope`는 명시 설정 시에만 중요한 후보용 빈자리를 예약한다(기본 0). 총 바이트에는 journal/snapshot/previous/staging을 포함하고 checkpoint의 임시 공간까지 검사한다. 한도에 닿으면 신규 저장을 거부·진단하며 **기존 원문을 자동 삭제하지 않는다**. 보호 후보라도 무한 보존/수용을 보장하지 않는다. 용량 측정 뒤 예약량·장기 아카이브·삭제 정책을 결정해야 한다. 90% 경고와 관리자 증설 안내를 유지/확장했다.

## 3. 설정과 배포 상태

실제 서버 설정을 생성하거나 켜지 않았다. 아래는 계약 안내이며 운영 수치 확정이 아니다.

- `action-ledger.json`: 원장 기존 opt-in과 보관 한도 유지.
- `action-detail.json`: 기존 `enabled`, `movementIntervalTicks`에 `routineDiagnostics` 추가(누락=false). 중요 사건은 enabled를 따르고 일상 상세 원문은 추가 진단 스위치를 요구한다.
- `reward-watch.json`: `schemaVersion=1`, `enabled`, 저장 한도/큐, `rules`, `barriers`, `activityWindowTicks`. 누락 OFF/집계 0. 신별 실제 영역·차폐·이벤트 범위는 미작성.
- 개인 회상/의미 검색 설정은 기존 opt-in 유지. `ai-memory-retention.json`은 schemaVersion 1과 위 5개 용량/예약 필드를 받는다. 설정 변경 후 정상 재시작 검증이 필요하다.
- 기술적 유한 상한(관측 정책 63개, 차폐 128개, 집계 버킷 256개 등)은 메모리/입력 안전장치이며 콘텐츠 밸런스가 아니다. 상한/큐/쓰기 실패의 누락을 완전한 목격으로 말하지 않는다.

새 ActionRecord enum과 claim v2/건축 원장 v3을 **옛 JAR이 읽는 하향 호환은 보장하지 않는다**. 나중에 시험 후 LP로 복원할 때는 JAR만 바꾸지 말고 해당 시험 직전의 호환 데이터까지 함께 복원해야 한다. 지금 운영 데이터는 변경하지 않았다.

## 4. 오프라인 검증

Java 21 기존 로컬 JDK와 Gradle `--offline --no-daemon`을 사용했다. AI는 공용 wrapper를 `../mythictrpg-main/gradlew.bat`로 호출하고 기존 잘못된 JBR 경로는 명령 인자의 `-Dorg.gradle.java.home`로만 덮어썼다. 전역 설정/모델/의존성 설치 없음.

| 게임 검사 | 통과 검사 수 |
|---|---:|
| ActionLedgerTest | 604 |
| ActionDetailTest | 432 |
| GodWatchTest | 99 |
| ExperienceStageTest | 24 |
| QuestParticipationTest | 61 |
| WatchRewardTest | 14 |
| LatestWatchPolicyTest | 14 |
| MiningPolicyTest | 91 |
| **게임 합계** | **1,339** |

| AI 검사 | 통과 검사 수 |
|---|---:|
| MemoryJournalTest | 656 |
| MemoryRecallTest | 54 |
| RecallStageTest | 121 |
| ExperienceDialogueTest | 37 |
| DerivedMemoryTest + ObservedSummaryFixture | 435 + 4 |
| MemoryIndexRuntimeTest + ModelAdmissionSchedulerFixture | 1,874 + 4 |
| QuestParticipationDialogueTest | 21 |
| NaturalMemoryTest | 63 |
| **AI 합계** | **3,269** |

총 **4,608개 검사**, 두 모듈 컴파일·개발 JAR 생성 성공. NBT 미래 버전/손상 테스트에서 예상된 ERROR 로그가 나오지만 원본 보존·거부를 확인하는 정상 음성 검사다. 처음 새 테스트 API 사용 오류와 이전 정책 기대값 실패를 수정한 뒤 전부 재실행했다. 생성된 오버레이를 직접 수정하지 않았다.

검증 명령(각 모듈을 작업 디렉터리로 사용, 기존 Java 21 경로 지정):

```text
게임: gradlew.bat --offline --no-daemon compileJava jar watchRewardTest latestWatchPolicyTest miningPolicyTest actionLedgerTest actionDetailTest godWatchTest questParticipationTest experienceStageTest
AI: ../mythictrpg-main/gradlew.bat --offline --no-daemon -Dorg.gradle.java.home=C:/Users/ADMIN/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2 compileJava memoryFoundationTest memoryRecallTest recallStageTest experienceDialogueTest derivedMemoryTest memoryIndexRuntimeTest questParticipationDialogueTest naturalMemoryTest jar
```

`dev-tools/Test-MemoryPolicyImplementation.ps1`로 LP/직전 snapshot을 재검증했다. 기준 2,284개 중 2,226개 불변, 58개 수정·24개 추가(개발 fixture 로그 4개 포함)이며 [작업 직전 대비 변경 manifest](memory-policy-changes-20260920.csv)에 경로/전후 해시를 기록했다. CSV 자체는 자기 해시 순환을 피하려 목록에서 제외한다. 개발 JAR 2개+서버의 나머지 모드 5개, 총 7개 JAR에서 **244 패키지, split-package 0**을 확인했다. 원본 Word/LP/옛 개발 JAR/FTB·콘텐츠·legacy 소스/배포·설정·기억 파일은 그대로다. 문서 로컬 링크/펜스와 CRLF 고려 diff 공백 검사도 확인한다. `mythictrpg-main/logs/`의 추가 로그는 순수 NBT 음성 fixture의 로거 출력이지 서버를 실행한 로그가 아니다.

개발 JAR SHA-256: 게임 `F2DDE942245069AF17E287B3D8261FA25EF1316D2E141F26F235D45BB60ED5C1`, AI `2D34B931A03B0EFB2E036B756512EAF40117DE0BF0FED0D2E225CDE4A0C86654`. 기존 개발 JAR은 보존했다.

1/4/6인 합성 파일/큐 fixture는 통과했지만 실서버 부하 측정이 아니다. 마지막 회상 검사에서 2,000개 bucket/15ms 예산은 5회 중 1회 초과해 불가용 fallback이 동작했다. 실제 응답시간 개선이나 6인 TPS 보장은 하지 않는다. 검증 산출물은 각 모듈 `build/*-test-artifacts/`에 있다.

## 5. 남은 검증과 다음 단계

1. 승인 후 테스트 서버 배포/부팅 및 실제 설치·취소·성장·생성기·피스톤·낙하·다른 모드 조합 검증. 서버/클라이언트 JAR 조합을 함께 맞춰야 한다.
2. 실제 신별 보상 매핑·네임드/전투 분류·차폐 영역/관측 범위·요약 창 작성. 존재하지 않는 레이드·전서구·독자 업적/비공개 공개 규칙을 임의 생성하지 않았다.
3. 실제 LLM의 약속·정정·이행 회상, 관측하지 않은 신/새 청중, 게임 날짜/일광, HUD와 지연 비교. 모델 설치·호출은 별도 승인 사항이다.
4. 실제 1/4/6인 부하·용량 측정 뒤 한도/예약·보관 정책 확정. 장기 원문 아카이브/자동 삭제는 이번에 활성화하지 않았다.
5. **1~5단계의 최신 공통 기반 소스·오프라인 결과를 다음 작업 입력으로 쓸 수 있다. 운영 수용 완료는 아니다.** 6단계는 새 승인과 전서구 귀속·재생성/차단 종료·전파 조건 확인 후 진행한다. 이번에 자동 착수하지 않았다.
