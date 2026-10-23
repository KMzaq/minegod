# 2·3·4·5·8번 일반 플레이 연결 점검

2026-10-21 · `behaivior` · 개발 게임 1.0.36 / AI 0.1.50 / 콘텐츠 0.1.3.

사용자 요청은 기존 다섯 항목을 일반 플레이 경로로 점검하고 시험 전용 제한·연결 누락을 구현하는 것이다. 1번·6번은 제외하고 7번은 [선행 일반 평판 작업](REPUTATION_GAMEPLAY_20261021.md)을 유지했다. 기존 다수 미커밋 변경을 보존했으며 정식 인물·사건·밸런스를 새로 정하지 않았다.

## 확인과 수정

| 항목 | 일반 경로 확인 | 이번 수정 |
|---|---|---|
| 2 단서·조우 | 데이터팩 reload, 일반 `/mythinvestigate`·`/mythclues`, 실제 `ServerPlayerGameMode.useItemOn` → Story 지식·보상 | 성공한 웅크리기 조사는 클릭을 소비해 블록 설치/아이템 사용의 이중 실행 차단. 선행 취소 존중 |
| 3 전투 준비 | 조사 보상 도구 → 일반 `/mythraid create/join/start` → 전장·보스 → 실제 피해 보정/1개 소비 | `create`의 `:`·`/` 인수 파서 수정. 먼 전장의 엔티티 로드·tick 준비를 STARTING에서 기다린 뒤 이동·생성. 모집 때 고정한 대응 정책·영속성·참가자 권한 유지 |
| 4 재료 경로 | 서로 다른 조사 경로와 실제 성숙 작물 수확 관찰 → Story → 기존 RewardClaimService | 별도 시험 제한 없음. 기존 원장/영수증 재사용. 관찰은 구체적인 신호 ID로 작성 |
| 5 집·생활 | 일반 `/mythstructure` 등록·`/mythvisits`, 대화/자율 방문 공급자, 실제 이동·도착 Story | 판단·적용·이동 중 같은 소유자 자격 검사. 이동 중 시험방·관전·허용 철회 후 도착 발행 차단 |
| 8 동시 사건 | STANDARD 일반 방의 응답 대기와 Story 선택 중에도 멀리 있는 다른 플레이어 조사·개인 지식 진행 | LATCHED 재평가 재귀 중복 방지, 만료 선검사, 반복 대기 저장, 오프라인 보존, 저장 범위·청중 사용. PLAYER/TEAM 예약의 로그아웃 유실 방지 및 로그인 재등록 |

게임은 계속 authoritative source다. AI 공급자·모델·Snapshot/Proposal 공개 계약은 변경하지 않았다. 시험 세계 데이터를 일반 콘텐츠로 배포하지 않았다.

## 대기 사건 규칙

- LATCHED는 전제조건·공유 자원뿐 아니라 열린 동일 사건/쿨다운에도 대기한다. 최대 횟수 소진은 종결한다.
- 개인/팀 대기 신호는 시작 플레이어 접속 복귀까지 보존하되 원래 만료를 연장하지 않는다. 저장 순서와 key 순으로 한 번씩 평가해 반복 가능 보상도 재귀적으로 중복 실행하지 않는다.
- 예약·대기 때 저장한 scope/청중을 재개에도 사용한다. 팀 변경에 따른 다른 팀 재귀속은 하지 않는다. 정의 scope 타입 변경은 audit와 함께 거절한다.
- 오프라인 예약은 SavedData에 남기고 실행 큐에서 뺀다. 실제 로그인 이벤트가 재등록하므로 64개를 넘는 오프라인 항목도 뒤의 온라인 예약을 영구 차단하지 않는다. 서버 시작 시 큐를 다시 구성한다.
- IMMEDIATE의 온라인 조건/자원 충돌은 자동 대기로 바꾸지 않는다. LATCHED·만료·재시도 계기는 기획 정의다. 복구 필요 instance의 자원도 자동 해제하지 않는다.

## 검증

최종 관련 GameTest 결과(서로 다른 실행의 각 그룹 최신 결과):

| 묶음 | 결과 | 결과 파일 |
|---|---|---|
| 기존 Minecraft/Story 회귀 | 198/198 PASS | `mythictrpg-main/build/content-gameplay-recheck-20261021/results.json` |
| 콘텐츠 일반 경로·격리·보상·대기·방문 | 16/16 PASS | `mythictrpg-main/build/content-gameplay-verified-20261021/results.json` |
| 기존 레이드·시련 | 4/4 PASS | 같은 `content-gameplay-verified-20261021/results.json` |
| 기존 집 방문 | 2/2 PASS | `mythictrpg-main/build/content-gameplay-20261021/results.json` |
| component 보상 | 3/3 PASS | 같은 `content-gameplay-20261021/results.json` |
| Story 선택 | 1/1 PASS | 같은 `content-gameplay-20261021/results.json` |
| Story 공개 근거 | 1/1 PASS | `mythictrpg-main/build/content-gameplay-recheck-20261021/results.json` |
| 레이드 화면/준비 중 안내·명단 제한 | 1/1 PASS | `mythictrpg-main/build/content-gameplay-ui-20261021/results.json` |

관련 8개 그룹의 최신 결과 합계 **226/226 PASS**. 전체 프로젝트의 모든 테스트를 다시 실행한 합계는 아니다. 소스 테스트는 실제 non-OP 명령, 실제 조사 우클릭/생존 수확, 실제 일반 방 권한/비동기 요청, 실제 이동/도착, 실제 레이드 보스 공격·아이템 소비와 저장 복원을 사용한다. 예약은 65개 오프라인 원본 뒤 온라인 사건이 진행하고 NBT 복원·실제 같은 UUID 로그인으로 1회 보상이 전달되는 것까지 검사했다. NBT 로드/큐 재구성 검증을 실제 별도 서버 프로세스 재시작 검증으로 해석하지 않는다.

실패·보완 이력도 각 실행 폴더에 보존한다. 최초 `content-gameplay`는 일반 raid 명령의 ID 파싱 오류 및 새 fixture 세팅 오류(빈 effect, 방 엔진 미설치)로 콘텐츠 4개 실패. `recheck`는 원거리 arena 보스 조회/시작 문제 1개 실패. `final`은 콘텐츠 16개 통과했으나 기존 시련의 즉시 ACTIVE 기대 1개 실패. 엔티티 준비 대기 구현과 기존 시련의 실제 tick 준비를 반영한 `verified`에서 해당 묶음 전체가 통과했다. assertion을 삭제하거나 엔티티 준비 상태를 강제로 주입하지 않았다.

- 게임 `compileJava`·`jar` 성공. `storyExternalEffectReceiptsTest` 10검사, `storyChoiceContractTest` 17검사 PASS.
- 현재 게임 API JAR을 사용하는 AI `compileJava`·`verifyEngineOwnership`·`verifyEnginePackage`·`jar` 성공. AI 551클래스, 게임 클래스 중복 없음. `homeVisitTest` 24검사 및 `storyPresentationProviderTest` 665검사 PASS. 외부 LLM 호출 없음.
- `git diff --check` 및 변경 가이드의 상대 링크 검사 PASS.

개발 산출물:

| 파일 | SHA-256 |
|---|---|
| `mythictrpg-main/build/libs/mythictrpg-1.0.36.jar` | `FD1E4FDE2E476F7A0A0260B11A7B3CA1D7F3471C88875B701C6344C56ABC78F8` |
| `mythai-ai-response/build/libs/mythai_ai_response-0.1.50.jar` | `9BA722021164888214081229A07DC0AD6D60995AE62235B81E4021E9091423A5` |

같은 버전의 이전 JAR과 해시로 구분한다. 작업공간의 선행 변경을 포함하는 개발 산출물이며 이번 범위만 독립 커밋/배포한 파일이 아니다.

## 적용 상태와 남은 일

소스·격리 개발 검증 작업이다. 운영 `server/mods`, `server/client-required-mods`, 설정·월드를 수정하거나 운영 서버를 시작/중지하지 않았다. 실제 LLM 방문 판단과 그래픽 클라이언트 수동 플레이는 이번 점검에 포함하지 않았다. 일반 방 테스트는 실제 게임 방 권한과 비동기 요청 경로를 사용하되 응답 엔진만 외부 호출 없는 stub으로 보류한다.

운영 게임 1.0.28의 서버/클라이언트 배포용 JAR은 모두 `F7F75C034FA152D996407BCAF0653B0E5823CA831F7B17F4FDE2DAE929AFBAD2`, 운영 AI 0.1.19는 `E3BA2CF5E2CF1B53F0C75861F8FAF48D2391C214E5A22E0C4BE3A1FB7502BB7A`로 선행 기록과 같음을 읽기 전용 확인했다.

기획에서 정식 조사점·단서 단계·지급 경로·대응 도구·방문 정책·도착 사건·공유 자원을 작성해야 한다. 작성 형식과 일반 플레이 진입점은 [시스템 가이드](../../../mythictrpg-main/docs/CONTENT_SYSTEMS_GUIDE.md), 분담은 [기획 전달 문서](../../../전달자료/content-planning-system-split-20261020.md)를 따른다. 새 정의를 포함한 운영 배포와 실제 사용자 플레이 확인은 별도 단계다.
