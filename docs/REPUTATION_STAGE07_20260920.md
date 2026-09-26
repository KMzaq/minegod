# 7단계 — 평판 판단 보정·대화 회복 연결 기반

2026-09-20 / 개발 게임 **1.0.10**, 기존 AI **0.1.11 유지** / 소스·오프라인 검증 완료 / 운영 OFF·미배포

## 1. 승인 범위와 선행 조건

사용자 선택은 **관계 판단용 보정 기반만 구현하고 운영값은 OFF 유지**, **대화로 납득시킨 경우도 회복 가능하게 설계**다. 후자를 미응답이나 금지로 취급하지 않는다. 소문 효과를 실제 퀘스트 제한·보상·영구 호감도 차감으로 연결하지 않는다.

| 선행 항목 | 이번에 확인한 상태 |
|---|---|
| LP·직전 작업 보존 | LP 소스 298개, 기반 이전 906개, 원본 JAR 3개 일치. 현재 2,347개 별도 백업·검증 |
| 6단계 소스 | [변경 manifest](rumor-stage06-changes-20260920.csv)의 41개 경로가 작업 전 After 해시와 일치 |
| 선행 컴파일·오프라인 | 게임 컴파일, Courier 4,189 + Rumor 39 + Quest 61 = 4,289 PASS |
| 실제 소문 생산 | 연결 API만 존재. 실제 엔티티·스폰·승인 관측 생산자·자동 후보 판단은 미연결 |
| 운영 수용 | 4~5단계 Minecraft 종단·1/4/6인 품질/부하, 6단계 물리 콘텐츠 검증은 미완료 |

따라서 전체 운영 선행 조건 충족을 선언하지 않고, 독립적으로 검증 가능한 승인 범위의 7단계 기반만 구현했다. [6단계 연결 계약](RUMOR_STAGE06_IMPLEMENTATION_20260920.md)과 [최신 로드맵](ACTION_OBSERVATION_MEMORY_ROADMAP_20260915.md)을 함께 따른다.

## 2. 게임 권한과 보정 계산

기존 `PlayerMythDataService`의 affinity가 관계 원본이다. 새 `ReputationLedger`는 **승인된 신별 평가 영수증**이지 두 번째 affinity DB가 아니다. `ReputationService.judgement`는 원본과 현재 유효한 소문 보정으로 읽기 전용 판단값을 계산한다. 기존 프로필 getter·퀘스트 조건·FTB 보상 경로를 대체하지 않았다.

- 게임이 현재 수신을 확인한 새 `CourierProof` 근거만 사용한다. 원장 전체·사적 대화를 프롬프트나 다른 신에게 공개하지 않는다.
- 월드/대상 플레이어/신/원본 source ID/소문 root와 revision/작성된 규칙 지문을 검증한다. 청중별 수신 조회가 허용돼야 한다.
- `(플레이어, 신, sourceId)`별 평가 하나로 같은 원본의 여러 소문 root 중첩을 막는다. 이미 평가한 root/revision은 다른 것으로 갈아 끼울 수 없다. 같은 ID의 동일 최종 결정은 중복 처리, 버전이 지난 결정은 거절한다.
- ACCEPTED이며 직접 관계 효과가 `NOT_APPLIED`인 경우만 보정한다. `ALREADY_APPLIED`와 `UNKNOWN`은 제외해 이중 불이익을 방지한다. 이 표시는 미래 게임 생산자가 실제 실행 결과를 확인해 제공해야 하며 자동 추정하지 않는다.
- 작성된 기존 관계 구간을 만족하는 규칙만 계산한다. 긍정/부정 상한을 각각 적용하고 최종 판단값은 기존 관계 범위 -1000~1000 안으로 제한한다. 신별 실제 강도·상한·금기·관계 구간은 아직 작성하지 않았다.
- 조회마다 재차 차감하지 않는다. 출처 철회·정책 변경·공개 불가로 근거가 무효가 되면 보정에서 제외되며 원본 호감도를 환급/수정하지 않는다.
- 수신 기록에 평가가 없으면 보정도 없다. AI가 소문을 언급했다는 이유로 평가를 생성하지 않는다.

평가 상태는 ACCEPTED/DOUBTFUL/IGNORED/DISPUTED/RECOVERED/RETRACTED다. 회복/철회는 해당 평가의 종결 상태이며 지연된 요청으로 되살릴 수 없다. 최초 승인과 최신 결정을 보존하되 모든 중간 전환의 별도 영구 감사 원장을 구현한 것은 아니다. 부분 회복·시간 감쇠·같은 원본 평가의 재개방 규칙은 미확정이다.

## 3. 대화 설득에 의한 회복

지원 경로는 **설득 후보 → 게임 측 검토 → 현재 문맥·근거 재확인 → 평가 RECOVERED**다. 설득을 허용하되 특정 사과 문장이나 AI의 “알겠다”를 성공 조건으로 고정하지 않았다.

1. `DialogueRecovery.Proposal`은 실제 게임 `ConversationMemoryContext`, 턴, 소문 root, 예상 평가 버전과 설명을 담는다. 설명은 최대 600자이며 후보일 뿐 게임 사실이 아니다.
2. 게임 소유 `Reviewer`가 실제 대화/턴과 신의 성격·관계·설득 근거를 확인하는 포트다. 이미 준비된 검토 결과를 읽어야 하며 서버 스레드에서 모델 추론을 기다리는 구현을 넣으면 안 된다.
3. `reviewDialogueRecovery`는 실제 접속 플레이어·월드·신·세션/세대·청중·턴·평가 버전·현재 수신 근거를 확인한다. 검토 뒤에도 문맥/턴/출처를 다시 확인하고 버전 비교로 적용한다.
4. 승인되면 DIALOGUE_REVIEW 종류와 승인 참조/사유 ID를 기록하고 보정을 제거한다. 후보 설명을 영구 월드 사실로 저장하거나 원본 소문·과거 대화를 삭제하지 않는다.

**Reviewer 구현·등록, 실제 대화 후보 생산, 자동 납득 기준은 이번에 연결하지 않았다.** 검토기가 없으면 거절한다. AI 응답 모듈이 이 승인 포트를 직접 소유하지 않는다. 일반 `applyApproved` 호출로 DIALOGUE_REVIEW 종류를 우회 적용할 수도 없다.

게임 결과·관리자 검토를 통한 해명/반증/큰 업의 회복도 승인 포트로 표현할 수 있다. `Approval`의 UUID/종류/사유는 승인 참조 형식이지 스스로 사건의 진위를 증명하는 인증서가 아니다. 미래의 신뢰된 게임 호출자가 실제 사건·검토 결과를 검증해야 한다. 어떤 업이 어떤 평판을 상쇄하는지는 작성하지 않았다.

**회복 후 AI 대사의 인식 변화까지 자동 연결된 상태는 아니다.** 기존 AI의 수신 소문 소비는 유지했고, 새 평가 상태/보정의 대화 소비자는 아직 없다. 향후 대화 연결 시 회복 사실을 현재 신에게만 전달하고 게임 승인 전 성공 대사를 확정하지 않도록 검사해야 한다. 원본 소문은 남기되 과거 인상을 계속 현재 사실처럼 강요하지 않는 회귀 시험도 필요하다.

## 4. 설정·저장·후속 API

설정 경로는 `config/mythictrpg/reputation-judgement.json`이다. [예제](examples/reputation-judgement.example.json)는 `enabled:false`, 양쪽 cap 0, 빈 rules다. 실제 서버에 복사하지 않았다. 누락·손상·64KiB 초과 설정은 OFF이며 **RUMOR_TEST + 명시적 enabled**에서만 서비스가 생긴다. 기존 RUMOR_TEST 게임 액션 차단을 해제하지 않는다.

규칙 필드는 `id/courierRuleId/godId/modifier/minimumBaseAffinity/maximumBaseAffinity`다. namespaced ID, 중복 없는 최대 128개 규칙과 수치 경계를 검증한다. 예제에 특정 신·수식어·운영 강도를 임의로 넣지 않았다.

| API | 계약과 현재 연결 상태 |
|---|---|
| `applyApproved(server, decision)` | 이미 검증된 게임/관리자 결정만 수신. 자동 평가 생산자·명령·네트워크 입력 없음 |
| `judgement(server, subject, god, audience)` | 기존 원본 관계 + 허용 근거 보정 조회. 실제 게임 문맥의 청중을 호출자가 공급 |
| `stillCurrent(...)` | 비동기 결과 후 같은 대상/신/청중/월드/현재 근거·관계인지 재조회. READY만 유효 |
| `installDialogueReviewer(server, reviewer)` | 후속 게임 통합 등록점. 기본 등록 없음, 서버 시작/종료 시 제거 |
| `reviewDialogueRecovery(player, proposal)` | 준비된 검토를 현재 대화에 검증해 적용. 후보만으로 성공하지 않음 |

`heardOne`은 기존 64개 프롬프트 목록 제한과 독립적인 단일 수신 조회다. AI의 기존 목록/예약 슬롯 계약은 바꾸지 않는다. IGNORE·공개 범위·철회와 새 물리 증명 확인을 동일하게 적용한다.

새 SavedData 키는 `mythictrpg_reputation_judgement_v1`이며 소문 저장소의 월드 UUID에 귀속된다. OFF에서는 새 저장소를 만들지 않는다. 기존 LP/소문 v1·v2/프로필 형식을 이관하지 않으며 손상·미지원 형식은 읽기 전용 격리와 원본 NBT 보존으로 처리한다. 새 데이터의 옛 LP JAR 하향 호환까지 보장하지 않는다.

보관 상한은 **평가 4,096개라는 코드상 항목 수**다. 설정 가능한 바이트 용량이 아니다. 90%부터 서버 알림과 관리자 증설 검토 안내를 표시하며 1,200 게임 tick 간격으로 억제한다. 가득 차면 새 평가는 거절하지만 기존 평가의 회복/철회는 가능하다. 자동 삭제·아카이브는 없다. 실제 용량/부하 측정 후 설정화 여부를 결정해야 한다.

## 5. 백업·빌드·오프라인 검증

- 직전 기준: `server/backups/before-memory-stage05-20260920-182320-030/`, **2,347개**. 기존 도구 라벨은 memory-stage05이지만 이번 7단계 수정 전 기준이다. 소스·문서·JAR·설정·기억·시험 로그·데이터팩 등 manifest 범위이며 전체 지형 백업은 아니다.
- LP 소스 298개, 기반 이전 906개, LP 원본 JAR 3개의 해시 일치. 기존 사용자 미커밋 변경·AI/콘텐츠/FTB/legacy 소스·이전 개발 JAR을 보존했다.
- 게임: `compileJava reputationStageTest courierStageTest memoryFoundationTest questParticipationTest watchRewardTest jar`를 offline/no-daemon/max-workers=1로 통과.
- AI: 새 게임 1.0.10 API JAR을 명령행 속성으로 지정하여 `compileJava rumorDialogueTest experienceDialogueTest memoryFoundationTest questParticipationDialogueTest` 통과. 기존 AI 빌드의 기본 게임 1.0.9/legacy 오버레이 입력·버전·기존 JAR은 변경하지 않았다.

| 오프라인 묶음 | assertion |
|---|---:|
| 새 ReputationStageTest | 4,221 |
| 기존 Courier / Rumor / QuestParticipation / WatchReward | 4,189 / 39 / 61 / 14 |
| 게임 합계 | **8,524** |
| AI RumorDialogue / Experience / Memory / QuestParticipation | 16 / 37 / 656 / 21 |
| AI 합계 | **730** |
| 최종 합계 | **9,254 PASS** |

새 평판 4,221개에는 상한 삽입 4,096개가 포함된다. 독립된 게임 플레이 시나리오 4천 개를 실행했다는 의미가 아니다. OFF·중복·범위 격리·원본 관계 보존·상한·직접 효과 중복 제외·출처 철회·정책 변경·회복/종결·오래된 대화/턴·재시작/손상 NBT·가득 찬 상태의 회복·64개 밖 수신 조회를 검사했다. 실제 서버 통합이 아닌 순수/fixture/바이트코드 검사 범위를 구분한다.

손상 NBT 음성 검사의 ERROR는 예상 거부 로그다. 중간 병렬 JavaExec의 Log4j 파일 잠금 경고는 새 평판 시험 작업 경로를 `build/reputation-stage07-test-artifacts`로 격리하고 최종 직렬 재실행으로 해결했다. 개발 테스트 로그 자동 회전으로 `mythictrpg-main/logs/2026-09-20-4.log.gz`가 정리됐지만 원본은 위 백업에 해시 검증돼 있어 복구할 수 있다. 실제 서버 로그/데이터를 삭제한 것이 아니다.

개발 산출물 SHA-256:

- 새 게임 `mythictrpg-1.0.10.jar`: `F0CE9630F08A5E3AFD9BB17FE17219ABFAACB06D41DB4E9249FB7FBE2360C685`
- 보존 AI `mythai_ai_response-0.1.11.jar`: `DD83D2105390FEE3757BC8C550AC47442544A5DF4B33BDD7F25B412A4CD8586F`

[보존·호환 감사 스크립트](../dev-tools/Test-ReputationStage07.ps1)와 [이번 변경 manifest](reputation-stage07-changes-20260920.csv)를 기준으로 기존 dirty tree와 이번 변경을 구분한다. 게임/AI+나머지 서버 모드 7개 JAR, 244개 패키지의 split-package 0, 중복 엔트리/오프라인 시험 class 포함 없음. 이는 실제 Mixin·모드 이벤트 호환성 검증을 대신하지 않는다.

최종 보존 감사 PASS: 직전 2,347개 중 **2,325개 불변**, 수정21개·개발 로그 자동 회전 정리1개, 신규17개와 개발 JAR1개로 변경 manifest 총40행이다. 로컬 문서 링크127개·코드 펜스·저장소 기본 줄바꿈 기준 `git diff --check`를 통과했다. manifest 자체는 자기 해시 재귀를 피하려 목록에서 제외한다.

## 6. 미검증·미완료와 종료 지점

- 실제 전서구 EntityType/별도 스폰 이벤트, 관측·공개 승인 생산자, 소문 의미 후보 판단/발행·전달 종단은 여전히 후속이다. 자동 스폰/재생성 OFF를 유지했다.
- 평판의 실제 신별 정책·강도, 승인 생산자, 대화 검토기, 회복 후 대사/인식 소비자는 미연결이다. 기존 호감도·퀘스트·상점·보상 경로는 바꾸지 않았다.
- 실제 Minecraft/GameTest·다른 모드 취소 이벤트 조합·서버 저장 중단·재접속·HUD/말투·1/4/6인 지연/tick/용량은 미검증이다. 오프라인 영속성 검사는 실제 장애 복구 검증을 대체하지 않는다.
- 이번에는 실제 LLM 호출/모델 설치, 서버 실행/중지, 서버·클라이언트 JAR 배포, 서버 설정·월드·기억 데이터 변경을 하지 않았다. 배포본은 게임 **1.0.2 / AI 0.1.3 / 콘텐츠 0.1.0** 그대로다.
- **승인된 7단계 기반 구현 범위에서 멈춘다.** 전체 운영 완료나 지금 서버에서 새 평판 효과를 체험할 수 있다는 보고가 아니다. 추가 콘텐츠 연결/배포/실행 시험은 별도 요청과 승인 후 진행한다.
