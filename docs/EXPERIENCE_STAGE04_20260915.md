# 로드맵 4단계 — 관찰한 행동 한 종류를 기억·대화에 연결

2026-09-15 / 승인된 소스·컴파일·오프라인 범위 완료 / **미배포·실제 서버/모델 미검증, 5단계 미착수**

[로드맵](ACTION_OBSERVATION_MEMORY_ROADMAP_20260915.md) 4단계의 구현 기록이다. [공통 계약](ACTION_OBSERVATION_CONTRACT_20260915.md), [3단계](GOD_WATCH_STAGE03_20260915.md), [개인 회상](RECALL_STAGE01_20260915.md)을 잇는다. 오프라인 종단 자료 연결과 실제 Minecraft/LLM 대사 검증을 구분한다.

## 1. 선행 조건과 백업

- 3단계 변경 목록 16개를 실제 작업 파일의 After 해시와 대조했다. 원장 604/주시 98/소문 39, 합계 741개 선행 검사를 재실행해 통과했다. 당시 운영 hook·생명주기·관찰 저장소 90% 알림·현재 게임 대화에서 발급하는 읽기 계약이 미연결인 사실을 확인했다. 이 단계의 최소 시험 연결에 필요한 부분만 구현했다.
- LP JAR 3개, LP 소스 298개, 기억 기반 이전 전체 개발 소스 906개, 3단계 직전 백업 2,203개 무결성을 확인했다.
- 새 기준은 [before-experience-stage04-20260915-213244-306](../server/backups/before-experience-stage04-20260915-213244-306/file-manifest.csv)의 **2,213개 파일**이다. 사용자 미커밋 소스·문서·필수 legacy 빌드 입력·기존 후보/배포 JAR·설정·개인 기억 등을 포함한다. LP/기존 백업을 덮어쓰지 않았다. 전체 월드 청크·플레이어 진행도 백업은 아니다.
- 백업 helper에 stage4 이름 선택만 먼저 추가해 snapshot을 만들었다. 그 helper의 이전 해시는 3단계 snapshot에서도 복구 가능하며 [이번 변경 목록](experience-stage04-changes-20260915.csv)에 기록했다.

## 2. 구현한 작은 종단 경로

`게임에서 성숙 작물 블록 제거 확정 → 기존 원장에 한 번 제출 → 저장 완료 receipt → 허용 주시 구간의 관찰 증명 → 현재 신·플레이어·청중에 허용된 ExperienceLease → 기존 분류/생성의 같은 참고 문맥 → 적용 직전 재검사`

| 경계 | 구현/제한 |
|---|---|
| 원본 사건 | 기존 ActionLedgerCapture의 단일 submit 뒤 분기한다. 두 번 기록하거나 기존 퀘스트/조우/스토리 sink를 재생하지 않는다. 기존 Mixin 위치·카운터·보상 실행은 변경하지 않았다. |
| 관찰 | 원장 durable receipt와 전체 Draft가 일치해야 처리한다. FIFO 시작 경계·대상 접속 객체·실제 신 ID·boot/발생 순서·정책/가림·필드 공개를 검증한다. 시작 전 사건은 소급 목격하지 않는다. |
| 지원 행동 | 바닐라 밀/당근/감자/비트/네더 와트/코코아의 **성숙 작물 블록 제거 완료**만 대화용으로 투영한다. 아이템 회수·수량·인벤토리 증가·퀘스트/보상 완료를 뜻하지 않는다. 자동화 수확·모드 작물·이동/전투의 대화 기억 전체를 지원한다는 뜻도 아니다. |
| 정보 구분 | 기존 PLAYER_STATEMENT/NPC_UTTERANCE/RUMOR_RECEIVED와 새 DIRECT_OBSERVATION을 구분한다. 플레이어의 계획·주장으로 완료 사실을 만들지 않는다. 관찰로 성격·취향·의도도 확정하지 않는다. |
| 관계 | 기존 PlayerMythDataService의 해당 플레이어→신 affinity 원본을 읽는다. 새 관계 DB·수치 변경 없음. 미준비는 UNKNOWN, tier 매핑은 UNDEFINED다. R_NEUTRAL 콘텐츠 기본 템플릿을 실제 관계 0이라고 단정하지 않도록 안내한다. |
| 검색 범위 | 현재 허용된 최신 관찰 최대 16개에서 관련 작물/행동 질문과 최근 플레이어 화제를 이용해 1개 선택한다. 과거 원장 전체를 의미 검색하는 완성형 RAG는 아니다. 사소한 잡담에 수확 기록을 매번 넣지 않는다. |
| 문맥 예산 | 직접 관찰 1개를 넣으면 기존 개인 발언/소문은 합계 최대 2개로 줄인다. 새 관찰·관계 블록 상한 1,000자, 개인 회상 지침은 별도다. 전체 생성 문맥은 기존 12,000자 한도를 조용히 잘라내지 않고 초과 실패로 다룬다. |
| 지연/실패 | 추가 LLM/임베딩 호출 없음. 원장·증명 IO는 worker, 게임 스레드는 불변 범위 발급과 메모리 검사만 한다. 선택적 경험 조회는 500ms 후 UNAVAILABLE로 끝내 기존 개인 대화를 진행하며 늦은 결과로 바꾸지 않는다. 이 수치는 전체 대화 지연 상한이나 실측 개선 수치가 아니다. |

## 3. 게임→AI 읽기 계약

생산자는 게임 `ai.experiencecontract.ExperienceAccess/ExperienceProjection`, 소비자는 AI `ExperienceMemory/DialogueMemoryBridge`다. 기존 Snapshot/Proposal/ConversationMemoryContext 생성자·Provider 계약은 유지한다. 새 게임 패키지를 AI JAR에 복제하지 않는다.

- `ExperienceAccess.request(player, expectedContext)`는 게임 스레드에서 현재 서버 플레이어 객체와 게임 발급 world/interaction/generation/god/player/audience를 확인한다. AI가 임의 ID로 공개 권한을 얻지 못한다. 비결합 `/ai_test start`와 readOnly RUMOR_TEST에는 발급하지 않는다.
- `ExperienceLease`의 정상 생성자는 게임 패키지 내부이며, AI 생산 코드는 사용할 수 없다. 공개 unavailable 생성은 빈 자료만 만든다. AI가 선택한 observation ID는 해당 lease가 발급한 목록의 부분집합이어야 한다.
- `ExperienceView(schemaVersion=1, available, reason, events, relationship)`는 불변이다. 각 event에는 observationId/eventId/sourceRevision/acquisitionKind/actionType/subjectType/outcome/gameTime이 있다. 필수 공개 필드 ACTOR/ACTION/SUBJECT_TYPE/OUTCOME이 없으면 제외하고 TIME 비공개는 NOT_DISCLOSED로 둔다. raw payload/좌표/차원/숨은 인벤토리를 역참조하지 않는다.
- 정상 읽기 결과를 조립한 뒤에도 게임 스레드에서 재검증한다. 마지막 대사/Proposal 적용은 현재 세션·턴·generation·청중·관계 snapshot과 포함 근거의 취소/정책/공개 revision을 검사한다. 검증 실패는 늦은 결과를 버리고 pending을 해제한다.
- 취소·공개 변경을 worker에 큐잉하기 **전** 메모리 차단 표식을 세운다. 비동기 재조회 완료와 실제 적용 사이의 틈으로 취소된 근거가 통과하지 않는다. 무관한 다른 대상/사건 변경으로 일괄 무효화하지 않는다.
- 관찰을 넣은 분류와 생성은 같은 referenceContext를 사용한다. 다른 신을 화자로 선택해 주 신의 개인 관찰을 대신 말하는 것을 거절한다. 회상은 정보 응답이며 행동을 재실행하지 않는다. 그 외 턴의 Proposal도 기존 게임 Validator/Executor만 실행한다.

프롬프트 자료 예시(자동 생성 NPC 대사나 실제 테스트 로그가 아님):

```json
{
  "source": "DIRECT_OBSERVATION",
  "event": "성숙한 작물 블록 제거",
  "crop": "밀",
  "time": "NOT_DISCLOSED",
  "outcome": "BLOCK_REMOVED_NOT_ITEM_ACQUISITION"
}
```

정확한 실제 JSON 키/라벨은 `ExperienceMemory.prompt`가 생성한다. 내부 UUID/revision은 프로그램 검증용이며 자연어 대사 참고 블록에 노출하지 않는다.

### 후속 대사와 영속성

관찰을 사용한 NPC 발언을 독립적인 NPC_UTTERANCE로 개인 journal에 다시 저장하지 않는다. 그렇지 않으면 관찰 취소 후에도 그 발언을 검색해 사실이 되살아날 수 있다. 세션 안의 `ExperienceHistory`가 대사와 원래 lease 참조를 연결하며, 그 대사를 다시 참고한 후속 발언에도 근거를 전달한다. 취소·청중/세션 변경 시 파생 대사를 문맥에서 제외한다. 관계 snapshot 변경도 보수적으로 이전 파생 대사를 제외할 수 있다.

이 임시 계보는 최대 64개 대사/근거로 제한한다. 오래되거나 예산을 넘는 파생 대사는 프롬프트에서 제외하며 원장/증명은 삭제하지 않는다. 종료 후에는 말의 재진술 대신 게임의 보존된 관찰을 다시 조회한다. 사용자 자신이 다시 말한 문장은 여전히 PLAYER_STATEMENT이지 목격 사실이 아니다.

퀘스트 완료/평가 안내도 새 턴의 game context를 사용하고 직전 사용자 턴의 검색 결과를 상속하지 않는다. 최근 대화의 관찰 근거는 별도로 상속/재검사한다. 실제 퀘스트 완료·평가·보상/FTB 실행 및 기존 권위 사실 입력은 바꾸지 않았다.

## 4. 서버 한정 시험 주시와 사용자 답변

사용자는 향후 **일정 이상의 관계를 달성한 신이 플레이어를 주시**하기를 원한다. 정확한 임계값·유지/종료·신별 권능/가림 정책은 아직 미정이다. 자동 관계 주시는 구현/활성화하지 않았다.

현재 서버에서만 포르투나가 `zaqGlGlT`를 주시하도록 해달라는 요청은 **시험 경로만 준비**하는 대안으로 처리했다. 실제 배포 게임 1.0.2에는 기능이 없고 새 JAR 배포는 금지되어 있으므로, 현재 서버에서 이미 주시 중이라고 안내하지 않는다. 공통 Fortuna JSON/다른 서버의 기본 설정/플레이어 관계 수치를 변경하지 않았다.

후속 별도 허가로 배포할 때의 순서(이번에는 실행하지 않음):

1. 서버 상태·전체 월드 및 현재 파일을 보존한 뒤 게임 **1.0.5**를 서버와 클라이언트 필수 JAR에 맞추고 AI **0.1.5**를 서버에 배포한다. 구버전 동시 배치는 하지 않는다. 새 AI의 최소 게임 의존성은 `[1.0.5,)`이다.
2. `server/config/mythictrpg/action-ledger.json` 및 `watch-trial.json`을 명시적으로 설정한다. [원장 예제](examples/action-ledger.example.json)와 [주시 시험 예제](examples/watch-trial.example.json)는 OFF/용량 0인 **문서 예제**이며 서버에 복사하지 않았다. 용량은 측정 후 결정한다는 사용자 선택을 유지했다. 설정만 ON으로 하고 용량 0을 유지하면 불가용 처리된다.
3. 정상 부팅 후 관리자 권한 2 이상으로 `/mythadmin ledger status`, `/mythadmin watch status`에서 준비를 확인한다. 현재 PERSONAL 대화 경로를 유지하고 일반 게임이 승인한 신 대화를 사용한다. [기존 개인 기억 시험 안내](LP_PERSONAL_MEMORY_TEST_SETUP.md) 참조.
4. `zaqGlGlT`가 온라인이고 시험 농장에 있을 때 `/mythadmin watch trial_start mythictrpg:fortuna zaqGlGlT <반경>`을 실행한다. 반경은 관리자가 1~128 블록 중 명시한다. **해당 순간 위치를 중심으로 고정된 상자 영역**이며 따라다니는 원형 반경이 아니다. 명령의 대기/저장 완료 응답은 구분된다.
5. 저장 완료 이후 영역 안에서 하늘이 보이는 성숙 작물을 제거하고, 포르투나와 종료·재개한 게임 승인 대화에서 관련 행동을 질문한다. 시작 전 과거 행동·가려진 농장·다른 신·다른 청중에 대한 대조 시험도 한다. 수확 아이템 획득까지 알고 있는 척하면 실패다.
6. `/mythadmin watch suspend zaqGlGlT`는 해당 플레이어에 대한 시험 주시들을 일시중지한다. 이미 본 사실의 삭제와는 다르다. 로그아웃/respawn/정상 재시작도 기존 구간을 멈추며 시험 시작 명령을 다시 요구한다. 신 정의 reload는 보수적으로 관찰 runtime을 닫고 재시작을 요구한다.

이 시험 정책의 `trial:explicit_sight`/작물 영역/하늘 가림/본인만의 공개는 개발용 명시 규칙이다. 포르투나의 정식 권능, 모든 비밀 장소에 대한 운영 판정, 관계 해금 규칙으로 확정하지 않았다. 다른 신과의 사적 대화·숨은 정보는 수집하지 않는다.

### 용량·실패와 종료

원장과 관찰 저장소는 각각 명시 quota를 갖는다. 둘 다 90% 이상이면 서버 전체 안내/관리자 용량 증설·정상 재시작 안내를 하고 30분 재알림 제한을 적용한다. 관찰 알림 로그에는 raw+watch 총량도 표시한다. 한도/큐/IO 실패 때 자동 삭제나 오래된 내용의 자동 요약으로 덮지 않는다. 저장 중단은 관리자 진단에 남기고 게임 진행은 별도로 유지한다.

증명 저장소에 BOOT/CLEAN_CLOSE 제어 레코드를 추가했다. 정상 종료는 watch drain 후 raw를 닫는다. clean marker 없는 비정상 종료에서는 다음 시작이 `UNCLEAN_WATCH_REQUIRES_REVIEW`로 실패하고 자료를 보존한다. 이유는 취소 요청이 메모리에는 반영됐지만 디스크에 남기기 전에 죽은 경우 이전 증명을 다시 공개하지 않기 위해서다. 자동 복구/봉인 해제 명령은 이번에 만들지 않았다. 관리자 검토가 필요하다.

3단계 형식만으로 생성된 비어 있지 않은 시험 journal도 clean marker가 없으므로 자동 운영 복구 대상으로 취급하지 않는다. 제어 레코드는 v1 저장소 확장이며 구 코드로의 역호환을 보장하지 않는다. 현재 실제 서버에는 새 watch 시험 저장소를 생성하지 않았으므로 실제 데이터 마이그레이션은 수행하지 않았다. 관찰 거절 건수는 runtime 진단으로, 영구 누락 집계/OS 강제 종료 복구 도구는 남아 있다.

## 5. 수행한 검증과 산출물

아래는 **이번 최종 소스**로 실행한 assertion 수다. 실제 모델 대화/인게임 시나리오 수가 아니다.

| 검사 | 결과 |
|---|---:|
| 원장 회귀 / 소문 회귀 | 604 / 39 PASS |
| 주시·공개·큐·취소·재시작/비정상 종료 | 99 PASS |
| 게임 Experience 계약·필드 제거·lease·단일 기록 fan-out | 24 PASS |
| 기존 AI journal / 개인 회상 / 1단계 회귀 | 656 / 54 / 121 PASS |
| 저장 종료·재개→관찰 투영→실제 프롬프트 helper, 다른 신/청중·재진술 근거 취소·생성 소스 연결 | 33 PASS |
| 합계 | **1,630 PASS** |

```powershell
.\dev-tools\run-mythictrpg-gradle.cmd jar godWatchTest experienceStageTest actionLedgerTest memoryFoundationTest --offline --no-daemon
.\dev-tools\run-mythictrpg-gradle.cmd -p ../mythai-ai-response jar check --offline --no-daemon
.\dev-tools\Test-ExperienceStage.ps1
```

Java 21/NeoForge 21.1.248/Minecraft 1.21.1의 기존 오프라인 Gradle 캐시를 사용했다. Gradle 캐시 잠금 접근 승인 외 서버·네트워크 모델 호출은 없다. 일반 `test/check` 및 위 JavaExec들은 파일/순수 함수/ASM 검사이며 GameTest 서버를 시작하지 않는다.

- 새 게임+기존 배포 AI, 새 게임+새 AI 각각 **7 JAR/243 패키지** 분리 검사 통과. 기존 게임 1.0.4 ZIP 엔트리 **992/1,006**, AI 0.1.4 엔트리 **303/318**가 바이트 단위 동일하다. 나머지는 허용된 연결 클래스/버전 metadata만 달라졌다. 퀘스트/보상/다른 게임 클래스 변경·누락이나 새 패키지 충돌이 없음을 정적 검사했다. 실제 타 모드 런타임 호환을 보장하는 검사는 아니다.
- `Test-ExperienceStage.ps1`은 LP/직전 snapshot 무결성, 허용된 파일 차이 외 모든 baseline 보호, 이전 후보/배포 JAR·서버 설정·개인 기억·다른 모드/legacy 소스 불변을 검사하고 이번 CSV만 갱신한다. 이전 단계 CSV를 덮어쓰는 검증 스크립트를 현재 상태에 실행하지 않는다.
- 최종 baseline **2,192/2,213개 불변**, 승인된 기존 파일 21개 수정 + snapshot 전 helper 1개 + 신규 파일 15개 = 이번 CSV **37개**다. CSV 자체와 새 후보 JAR은 생성 산출물로 별도 취급한다.
- 관련 문서 로컬 링크 **85개** 존재 확인, Git diff 공백 검사 통과. LF/CRLF 안내는 기존 저장소 설정의 경고이며 코드 오류가 아니다.
- 게임 후보: `mythictrpg-main/build/libs/mythictrpg-1.0.5.jar`, SHA-256 `DE092EBB2FF45A2710B66E00F13D03C9FFE2C4F018295EAA59F821D7CF612F1D`.
- AI 후보: `mythai-ai-response/build/libs/mythai_ai_response-0.1.5.jar`, SHA-256 `36E3AE651959ECE99994308C978ECA8DE142C1E7729FEE87F1E259FAEE2F5670`.
- 실제 서버/클라이언트 필수 게임 **1.0.2**, 서버 AI **0.1.3**, 콘텐츠 **0.1.0**, PERSONAL 및 gemma4:12b 설정 유지. **소스 재빌드 결과는 새 개발본이지 LP/현재 배포본이 아니다.**

## 6. 남은 검증과 다음 작업

- 실제 서버 부팅, Mixin 적용/다른 모드 간 충돌, 관리자 명령·온라인 객체·reload/재접속/respawn·90% 알림의 런타임 확인.
- 승인된 게임 대화→실제 LLM→HUD/종료·재접속 종단 시험. 오프라인 시험은 같은 helper/생성 소스·자료 연결을 검사했으며 네트워크 모델/전체 adapter를 실제 Minecraft 안에서 구동하지 않았다.
- 포르투나/관찰하지 않은 다른 신, 관계 차이, 관련/무관 질문, 관찰 취소·관객 변경·LLM 지연 중 종료, 자연스러움/첫 토큰/전체 응답시간 비교.
- 1/4/6인 서버 tick·메모리·저장 증가·큐와 실제 latency 측정 후 운영 quota·보존 정책 결정. 오프라인 표본 약 739~744 bytes/행동과 작은 회상 표본 p95 약 0.146ms는 서버 전체 비용이 아니다.
- 정식 신별 관측 정책·관계 임계값/시작·유지·중지, 운영 누락 진단 영속화/비정상 종료 복구 절차. 의미 검색/집계·자동 중요 기억·전서구/소문 콘텐츠는 후속 범위다.

**4단계의 허용된 소스·오프라인 작업은 끝났지만 로드맵의 실제 모델/인게임 통과 조건은 미충족이다.** 다음은 별도 허가를 받아 작은 서버 시험을 하는 것을 권한다. 5단계 확대의 기술적 출발점은 마련됐으나 자동 진행/운영 완료를 선언하지 않는다.

복원은 [LP 가이드](LP_MEMORY_FOUNDATION_GUIDE.md)와 이번 CSV/직전 snapshot을 대조해 단계별 선택 복원한다. LP JAR을 새 데이터가 기록된 월드에 덮는 것만으로 완전 롤백이라고 하지 않는다. 기능 OFF는 데이터 삭제나 월드 되감기가 아니다.
