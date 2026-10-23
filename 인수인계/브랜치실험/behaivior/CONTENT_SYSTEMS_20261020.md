# 탐험과 상호작용 공통 시스템 작업 기록

2026-10-20 시작 · 2026-10-21 후속 · `behaivior`.

후속 변경: [일반 플레이 평판 회복](REPUTATION_GAMEPLAY_20261021.md). 아래의 평판 시험 모드 제한은 선행 구현 당시 상태다. 사용자 추가 요청으로 일반 PERSONAL 경로와 AI 기억 모드에 독립적인 게임 결과 회복을 연결했다.

## 요청 범위

이전 플레이 기록에 대한 제안 중 2 3 4 5 7 8번을 기획과 시스템으로 나눠 문서화하고, 공통 실행 기능을 설계·제작한다. 1번의 초기화 없는 세계 진행은 별도 기획이며, 6번의 귀속 보상장비·분실 재지급은 이번 구현에서 제외했다. 특정 상대용 대응 도구를 전투력에 넣지 않는 기획 원칙은 유지하며 전투력 코드는 수정하지 않았다.

이 작업은 기존 지원 통계·목적 추론·일반 대사 품질 작업과 별도다. 그 작업의 보류·실패 이력과 모델 설정을 바꾸지 않는다. 다른 채팅의 미커밋 변경을 보존했으며 커밋하지 않았다.

## 전달 문서

- [기획과 시스템 작업 분담](../../../전달자료/content-planning-system-split-20261020.md): 범위와 기획 산출물, 실제 공통 기능의 첫 실행 범위.
- [2번 탐험 단서 전용 전달](../../../전달자료/minecraft-clue-encounter-planning-20261020.md): 이전의 다른 항목 요약을 분리하고 2번에 집중하도록 정리.
- [작성 형식과 실행 계약](../../../mythictrpg-main/docs/CONTENT_SYSTEMS_GUIDE.md): 실제 JSON과 명령, 저장·권한·실패 처리 및 남은 기능.

## 구현한 공통 기능

| 항목 | 구현 |
|---|---|
| 2 | `investigations` 고정 조사점. 실제 차원·좌표·블록·거리·시야·주 손 아이템 변형·조건을 확인하고 기존 Story 사건 호출. 웅크리고 우클릭 또는 명령. `/mythclues`는 기존 본인 Story 지식만 재열람 |
| 3 | `raid_countermeasures`. 실제 ACTIVE 시도의 참가자와 보스·추가 몹, 피해 종류, 전장, 준비한 도구의 component를 검증하고 피해 배율·선택적 1개 소모 적용. 정책은 모집 생성 시 고정·저장 |
| 4 | Story `GRANT_REWARDS`. 기존 보상 codec과 claim 지급을 사용하며, 사건과 effect로 고정 source ID 생성. 보상 항목은 사건 fingerprint에 포함. 불명확한 지급 영수증은 자동 성공·재지급하지 않고 복구 확인 상태로 보존 |
| 5 | `/mythvisits on/off`의 개인 설정 저장. 새 판단·늦은 AI 판단·이동 중 재검증. 실제 도착에서만 `home_visit_arrived` 신호를 기존 Story로 전달 |
| 7 | Story `RECOVER_REPUTATION`과 승인 게임 결과 API. 현재 시작 플레이어·지정 신·기존 정책의 평가만 기존 원장에서 회복. 원본 사건·최초 승인·타인 평가·호감도 원본 보존 |
| 8 | `story_admission`. 작성형 논리 자원과 시작 플레이어의 배타 점유를 기존 event instance에 저장. 선택·외부 효과·복구 대기에서 유지하고 독립 사건은 병행. LATCHED 재평가에서 중복 원인 재실행 방지 |

신규 게임 소스는 `mythictrpg-main/.../content/`와 전용 GameTest에 있으며 기존 Story, RaidState, GodHomeVisitService/GodAvatarEntity, ReputationService에 연결했다. `build.gradle`에는 테스트 namespace 한 개를 기존 템플릿 목록에 추가했다. AI 소스·프롬프트·Proposal·모델·운영 플래그는 이번 작업에서 수정하지 않았다.

## 검증

- 초기 세 모듈 `build` 성공. 영향 범위 GameTest 10묶음·224개 통과: `mythictrpg-main/build/content-systems-regression-20261021/results.json`. 이는 뒤의 최종 보완 전 회귀다.
- 정책 동결·불명확한 claim 방어를 보완한 `build/content-systems-20261021-v4`: 신규 GameTest 6/6, `reputationStageTest` 4,221 assertion, `storyExternalEffectReceiptsTest` 10검사, JAR 생성 exit0.
- 아이템 component 변형 검증을 추가한 `build/content-systems-20261021-v5`: 신규 GameTest 6/6와 위 단위 검사·JAR exit0. 이후 긍정 JSON 파싱 검사와 조사 시야의 경유 청크 확인을 추가했다.
- 최종 게임 `compileJava jar` 성공. AI가 실제 사용하는 게임 API JAR 경로는 현재 `mythictrpg-1.0.36.jar`. AI 컴파일·`verifyEngineOwnership`·`verifyEnginePackage` 성공: 소유 AI 클래스 551개, 게임 중복 없음.
- 최종 `mythictrpg-main/build/content-systems-final-20261021/results.json`: **10/10묶음·224/224 GameTest PASS, exit0**. 긍정 JSON 파싱·component 변형·경유 청크 확인을 포함한 최종 소스로 실행했다. 이 행렬은 위에서 생성한 최종 JAR을 사용한 `-SkipBuild` 실행이며 모듈 전체 build를 다시 했다는 뜻이 아니다.

신규 GameTest는 실제 현장 조사와 보상 수령/중복 차단, 플레이어별 지식·평판 격리, Story 예약 및 레이드 정책의 NBT 복원과 손상 격리, 실제 `.hurt` 피해 경로의 도구 소모, 방문 허용 철회 뒤 늦은 AI 차단, 실제 보행 도착→소유자 Story 사건을 확인한다. 작성 예의 양수 파싱과 잘못된 정의 거절도 검사한다. 손상 NBT를 의도적으로 넣은 검사의 오류 로그는 실패 판정과 구분한다.

기존 4,221 평판 assertion에는 4,096개 용량 삽입이 포함된다. 실제 게임 장면 수로 해석하지 않는다. AI 방 통합은 모의 LLM transport이며 이번 작업에서 실제 모델 요청은 하지 않았다.

### 보존한 실패 이력

1. `build/content-systems-20261020-v1`: 6개 중 실제 피해 검사 1개 실패. 실제 접속 fixture의 바닐라 초기 60틱 보호로 `.hurt`가 거절됐다. 테스트가 전투 상태를 재현하도록 수정한 v2에서 6/6 통과했다. 게임의 접속 보호를 제거하지 않았다.
2. v3 실행 명령의 컴파일에서 fixture가 존재하지 않는 `Arena.parse`를 호출했다. 실제 `RaidDefinition.parseArena`로 수정했으며, GameTest를 실행하기 전 컴파일 실패였다. v4와 v5의 통과로 이전 실패를 지우지 않는다.

## 적용 상태와 남은 일

- 개발 버전 표기는 기존 게임1.0.36 / AI0.1.50 / 콘텐츠0.1.3을 유지했다. 현재 JAR에는 기존 미커밋 변경도 포함되므로 이번 기능만 분리된 릴리스 산출물로 취급하지 않는다. 운영 배포는 하지 않았다.
- 게임 JAR SHA256: `949000754765E8DBBE3E3C0D8414E7C2CE6994F68FD2C80E7DC04E52ACAF94B5`.
- 운영 게임1.0.28 해시 `F7F75C034FA152D996407BCAF0653B0E5823CA831F7B17F4FDE2DAE929AFBAD2`, 운영 AI0.1.19 해시 `E3BA2CF5E2CF1B53F0C75861F8FAF48D2391C214E5A22E0C4BE3A1FB7502BB7A`는 보존됐다. 운영 서버·클라이언트 필수 JAR·설정·월드·모델을 변경하거나 운영 서버를 실행·중지하지 않았다.
- 정식 조사점·등장인물·새 사건·대응 도구·확률·보상·예약 정책은 미설치다. 기획 문서에 맞춘 실제 정의를 작성해야 한다.
- 평판 회복은 기존 **RUMOR_TEST와 명시적 평판 설정**의 제한을 유지한다. 일반 운영 평판 활성화·Native 소문 전체 연결을 완료했다고 하지 않는다. 실패·재시작 시 평가 집합이 바뀐 회복을 자동 재시도하지 않는다.
- 이번 전투 대응은 지정 피해 배율·소모 도구다. 새 패링, 숨은 상성 판정, 개별 보스 패턴·전용 도구 효과는 실제 기획에 따라 추가한다. 대응 도구 보상을 전투력 정책에서 IGNORE로 분류하는 콘텐츠 작업도 필요하다.
- 조사는 고정 블록 좌표다. 자연 유적 자동 배치, 발굴 출처 인증, 공통 실패 누적 보장, 지도·그래픽 단서장, 대화 자동 중단·복원은 구현 범위에 넣지 않았다.
- Story의 canonical effect와 외부 지급은 서로 다른 단계다. 실제 지급·회복 성공에 종속된 완료 사실은 `event_resolved` 후속 사건에 둬야 한다. 여러 저장소를 하나의 롤백 트랜잭션으로 만든 것은 아니다.
- 기존 Story·Raid NBT는 새 선택 필드를 누락한 상태로 읽을 수 있다. 새 예약·외부 효과·도구 정책을 사용한 월드의 구 JAR 롤백은 안전하다고 가정하지 않는다. 실제 같은 월드의 2프로세스 재시작·클라이언트 그래픽·사람의 인게임 플레이는 미확인이다.

다음 단계는 기획자가 각 사건의 카드와 실제 자원을 작성하고, 이를 격리 데이터팩으로 연결해 플레이 흐름을 확인하는 것이다. 이 기록은 기존 일반 대사 품질의 미통과 결과를 합격으로 바꾸지 않는다.
