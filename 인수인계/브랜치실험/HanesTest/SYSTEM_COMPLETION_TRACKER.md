# 시스템 완성 작업표

기준: 2026-10-03 / `HanesTest`. 콘텐츠 제작·밸런스 확정과 시스템 구현을 분리한다.
이 문서의 `완료`는 해당 항목의 범위만 뜻하며 서버 전체 완성이나 운영 배포를 뜻하지 않는다.

**현재 추가 범위:** 별도 사용자 요청으로 [NPC 일상 활동 기억·감정 연결](NPC_ACTIVITY_MEMORY_20261002.md)을 추가했다. 최신 개발 게임 1.0.26/protocol 10 · AI 0.1.29 · 콘텐츠 0.1.3, 운영 미배포다. 앞선 [퀘스트 접촉 확인·영구가호·가호 점수](QUEST_CONTACT_BLESSINGS_20261002.md), [NPC 퀘스트 재편성](QUEST_REORGANIZATION_20261002.md)·스킨 및 [공간 인식·생활활동](NPC_ACTIVITIES_20260930.md)은 유지한다. 무관한 미완료 시스템을 이번 요청으로 자동 재개하지 않았다.

## 2026-10-03 — NPC 활동 기억·감정 연결

- 실제 활동/청취 사건 → 신별 제한된 기억 → 청중별 검색 → 기존 자율 선택 및 primary/secondary 대화로 연결했다. 감정은 전체 입력 출처에 묶인 과거의 질적 해석이며 관계 수치/방 감정 원본을 대체하지 않는다.
- SavedData v1 호환 v2, OFF 보존/차단, 원본 철회·청중 격리, 실제 완료와 제안 구분, 지연 응답 및 문맥 예산 검증을 포함한다. 상세 결과와 제약은 [이번 기록](NPC_ACTIVITY_MEMORY_20261002.md)을 따른다.
- 신별 활동 정책 활성화·운영 배포·실제 LLM/클라이언트 검증은 별개다. 기존 9월 30일의 기억 미연결 기록은 당시 이력이며, 소문 자동 전파와 전체 아카이브 이관은 여전히 미완료다.

## 2026-10-02 후속 — 접촉 확인·영구가호

- 기본 퀘스트 완료는 실제 접촉 확인이 필요하다. 주시 소유와 현재 관찰을 구분하며 독서·산책 중 원격 확인은 차단한다. 비주시 플레이어는 수주/지정 장소에서 실제 대면하거나 실제 재조우해야 한다. 즉석 퀘스트도 준비/승인/지급 대기를 분리했다.
- 영구가호 보유권·우유 제거 상태를 기존 reward SavedData에 저장하고 `/mythblessing reapply` 또는 사망 부활로 복구한다. 효과별 최고 보유 단계로 점수를 계산하므로 우유로 낮아지지 않는다. 초기 효과별 점수는 사용자 승인으로 작성한 조정 가능 초안이다.
- 상세 계약과 검증은 [이번 기록](QUEST_CONTACT_BLESSINGS_20261002.md)을 따른다. 아래 9월 30일의 ‘영구가호 규칙 미착수’는 당시 이력이며 이 후속 요청으로 해당 부분을 구현했다. 자동 방문 개시·신별 콘텐츠·완성된 전체 전투력 밸런스까지 구현했다는 뜻은 아니다.

## 2026-10-02 퀘스트 포기·장기 부재 재편성

- 구현: GROUP/ALL_SUBMITTED 랭킹의 퀘스트별 재편성 정책, 지정 NPC의 메뉴·명시 확인, 동석 적격자의 본인 동의 충원, 제출 완료자 보호, 남은 인원 정산/최소 인원 대기, 정확한 제출품 반환과 기존 보상 claim 재사용. 처음 선택된 메인 자격은 유지하며 새 권한을 만들지 않는다.
- 게임 저장 v2는 v1을 읽지만 기존 실행을 자동 이관하지 않는다. AI 메뉴 Proposal과 같은 방 최종 결과 환류를 연결했다. [단일 상세 가이드](../../../mythictrpg-main/docs/QUEST_REORGANIZATION_GUIDE.md)를 따른다.
- 컴파일·핵심 오프라인/격리 게임 검증과 한계는 [이번 기록](QUEST_REORGANIZATION_20261002.md)에 기록한다. 활성 콘텐츠 설정, 운영/클라이언트 배포, 실제 HUD·LLM 대화 검사는 남았다.

**2026-09-30 우선순위 변경:** 사용자 요청으로 현재 typed 기억/해석 근거 묶음의 구현·핵심 검증을 마감하고 LLM 추가 강화는 후순위로 둔다. 신의 타격·공물·가호, 퀘스트 수주/달성, 신전·집 건축, 레이드, 상점의 **실제 실행 경로가 없는 부분**을 우선한다. 기존 서비스와 데이터를 재사용하며 운영 수치·콘텐츠를 임의로 확정하지 않는다.

## 2026-09-30 플레이어형 신 NPC·신별 스킨

- 별도 사용자 요청으로 `GodAvatarRenderer`를 zombie humanoid에서 vanilla `PlayerModel`로 전환했다. classic/slim 체형, 64×64 플레이어 스킨의 독립 좌우/바깥 레이어, 손 아이템과 장비 표시를 지원한다. 스킨 누락 시 체형에 맞는 Steve/Alex fallback, 리소스팩 재로드 시 존재 캐시 초기화를 넣었다.
- 기존 신별 아바타 정의에 `appearance.model`(`classic`/`slim`, 생략 classic)만 추가했다. 기존 `textureVariant` 0..255/scale/formatVersion 1과 Java 2인자 생성자는 유지한다. 클라이언트에는 신 ID 대신 기존 외형 번호+slim 여부만 동기화하며 서버 God identity/게임 권한을 바꾸지 않았다. 일반 이름표 숨김도 유지한다.
- 작성법: [NPC_SKINS.md](../../../mythictrpg-main/docs/NPC_SKINS.md), 전체 계약: [NPC_ACTORS.md](../../../mythictrpg-main/docs/NPC_ACTORS.md). 새로운 활성 신별 능력치·출현 정책·스킨 이미지는 임의로 만들지 않았다. 스킨 PNG만 서버에 넣으면 클라이언트에 자동 전송되는 구조가 아니다.
- 검증: `runRoomIntegrationGameTestServer jar -ProomTestDirectory=build/god-avatar-skins-20260930 -ProomTestNamespaces=mythictrpg_god_avatar --offline --no-daemon --max-workers=1` 성공, **3/3 GameTest**(335.1ms), 10 tasks/11초. 기존 단일 실체/raid lease/조우 회귀 + strict model 파싱/구형 호환 + 실제 metadata 복사/정체 비전송/신별 분리/정의 reload/NBT 재적용을 검사했다. 최초 실패는 fixture의 직접 tick 호출이 tickCount 증가를 생략한 것으로, 실제 `ServerLevel.tickNonPassenger` 경로를 사용해 수정했다. 기존 mock 로그인의 Story 화면 전송 경고는 가짜 클라이언트의 채널 미등록이며 실제 화면 전달 성공을 뜻하지 않는다.
- 개발 게임 **1.0.22 / protocol 9**. 컴파일·JAR 생성 완료, JAR SHA-256 `77B562B70BEA048823B2BB834CD594191F160D258DAEC414F7BD31797E0E399F`. 운영 서버/클라이언트 미배포. 실제 클라이언트 PNG/장비/애니메이션 화면과 `F3+T` 변경 확인은 남았다. 전체 후속 RPG 작업은 자동 재개하지 않는다.

## 2026-09-30 게임 연결 묶음 마감

| 마무리한 범위 | 구현·검증 |
|---|---|
| 공물·가호 확인 및 결과 환류 | [액션 계약](../../../mythictrpg-main/docs/AI_ACTION_INTEGRATION_GUIDE.md): 실제 소비 ID/수량·효과/시간 표시, 템플릿 변경 시 재확인 필요, 원래 방/신/플레이어에 pending→최종 결과 교체. 취소·서버 만료 구분. 별도 LLM 호출 없음 |
| 일반 플레이어 건축 퀘스트 | [건축 가이드](../../../mythictrpg-main/docs/STRUCTURE_EVALUATION_GUIDE.md): `/mythstructure quest confirm <questId>`로 본인 영역 확정. 수주·정책·제출 잠금·기여자 동결 검증은 관리자와 공용 서비스 사용 |
| FTB 완료 내역 표시 복구 | [FTB 가이드](../../../mythictrpg-main/docs/FTB_QUESTS_INTEGRATION_GUIDE.md): 로그인 시 서버 완료 이력으로 완료자의 표시만 복구. 아이템/보상·세계 진행도·스토리 재실행 없음. 기존 FTB 팀 공유 표시와 task 수치 복구 한계 유지 |
| 레이드 참가/상태 화면과 모집 탈퇴 | [레이드 가이드](../../../mythictrpg-main/docs/RAID_RUNTIME.md): `/mythraid` 또는 `ui`, 생성/참가/시작/취소/모집 탈퇴 버튼과 상태·시간·목숨·체력 표시. 서버 1회용 요청 권한과 현 상태 재검증. `/mythraid leave <UUID>`는 FORMING 비리더 본인만; 대기·전투 중 명단은 불변 |

검증: `roomActionGatewayTest` **71**, `roomTurnSequenceTest` **103** 통과(9 tasks/15초). 별도 개발 월드의 실제 GameTest는 공물/가호·확인 데이터 **2/2**(359.1ms, `build/action-feedback-20260930`), 건축/FTB 복구 **3/3**(368.7ms, `build/quest-access-20260930`), 레이드 UI/탈퇴 **1/1**(351.9ms, `build/raid-ui-20260930`), 기존 전투·보상·복귀 회귀 **2/2**(371.0ms, `build/raid-ui-regression-20260930`) 통과했다. 마지막 GameTest 후 최신 소스 JAR 재생성 완료. 최초 컴파일의 테스트 fixture final 메서드 override 오류는 실제 FTB NBT API 사용으로 수정해 통과했다. 격리 월드 최초 `server.properties` 부재 및 손상 NBT 거절 fixture 로그는 실행 실패가 아니다.

개발 게임 **1.0.22 / protocol 8**, AI **0.1.25**, 콘텐츠 **0.1.3**. 운영 `server/mods`, `client-required-mods`, `world`, `config`는 변경하지 않았다. 실제 클라이언트 화면/마우스 조작·운영 부팅·실제 LLM 자연스러움은 미확인이다. 전체 시스템 완료 선언이 아니다.

**착수하지 않고 남긴 것:** 별도 신전 봉헌/의식, 공물 영구 이력·후속 관계/진행도 정책, 임시 효과 외 영구 가호 규칙, 퀘스트 완료 준비→NPC 방문 개시 연결, 다인 즉석 퀘스트 등의 확장. 콘텐츠/밸런스 선택이 필요한 것은 사용자와 협의해야 한다. 상점 거래·저장·화폐/UI, 타격·임시 가호·아이템 전달, 퀘스트/레이드 기본 런타임은 기존 구현이 있어 새로 중복 제작하지 않았다. 이 목록을 지금 자동 착수하라는 지시로 해석하지 않는다.

## 사용자 확정 사항

- 지형·구조물 추가 모드를 사용할 예정. 다른 추가 모드는 미선정이다. 필요하면 호환성·라이선스·기존 기능 중복을 확인하여 도입할 수 있다.
- 장비 외형은 리소스팩으로 제작한다. 리소스팩 자체가 능력치·보상·강화를 실행하지는 않는다.
- 장비 성장은 더 좋은 장비를 얻어 교체하는 방식만 사용한다. 사용자 답변에 따라 강화·개조 시스템은 이번 범위에서 제외한다.
- 레이드는 정의별로 공용 월드형 또는 파티별 격리형을 선택할 수 있어야 한다.
- 신은 실제 NPC 개체로 존재할 수 있고 레이드 보스도 될 수 있다.
- 같은 신은 세계 전체에서 동시에 한 곳에만 존재한다. 해당 신이 다른 레이드/장소에서 사용 중이면 다음 파티는 대기한다.
- 파티별 격리 공간은 미리 만든 전용 전투 구역을 배정한다. 빈 구역이 없으면 대기하며 월드 복제·인스턴스 삭제는 하지 않는다.
- 후원·서약은 현재 대화에서 기억하는 약속이다. 보호 효과·권속 계약·자동 처벌을 생성하지 않는다.
- 신 방문은 JSON 허용·서버 공용 진행도 조건을 만족할 때 기존 등록 건축물 중 선택한다. 건물이 여러 개면 신의 취향·허용된 평가·상황을 이용해 고르거나 거절할 수 있다. **대화 중 요청과 대화 밖 자율 판단 모두 지원**한다. 기존 실체의 보행만 사용하며 자동 소환/순간이동·레이드/기존 명령 탈취는 하지 않는다.
- 실제 게임 상태와 실행 권한은 MythicTRPG에 남는다. 게임 규칙을 AI 대사만으로 확정하지 않는다.

## 완료 기준

작성 가능한 데이터 → 검증·로드 → 실제 호출 → 게임 실행/거절 → 저장/재시작 → UI/AI 결과 전달까지 해당 범위를 연결한다.
인터페이스, 더미 제공자, 예시 JSON만 존재하면 `기반만 있음`이다. 소스·오프라인 검증·패키징·배포·인게임 검증은 별도로 기록한다.
각 작업의 테스트는 권한·중복 지급·지속성·세션/청중 격리 등 영향을 받는 부분부터 수행한다.

## 작업 순서 및 상태

| 작업 | 시작 시 상태 | 현재 작업/완료 조건 |
|---|---|---|
| AI 독립 빌드 | 구 `mine` 소스/JAR 추출에 의존 | [소스 이관](AI_STANDALONE_BUILD.md)·전체 오프라인 build 성공. 156개 엔진 소스, 이관 직후 기존 232개 비오버레이 class byte 동일성, 최신 413 class 게임 중복 없음 검사. 운영 미배포 |
| 스토리 선택 UI | 서버 선택 API는 있고 표시/입력 경로 누락 | 실제 서버/네트워크/클라이언트 Screen 연결. 허용 선택·재열기·재접속·만료·중복/권한 검증, 오프라인17 및 실제 GameTest1 통과. 실제 클라이언트 GUI 조작은 미검증 |
| 현재 감정 연속성 | 전달된 감정 상태 공급이 없는 경로 존재 | [비권위 감정 상태](CURRENT_EMOTION.md) 연결. 실제 발행된 대화에만 근거, 방/신/플레이어/청중 격리. 최신 오프라인58 및 모의 LLM 연속3턴 통합 검사 통과. 실제 자연스러움 품질은 미검증. 자율 방문용 공용 감정 원본은 없음 |
| 장기기억 05 | 기존 기록/검색 구현 + 새 05 명세 M0 | 현재 묶음은 schema10 전체 입력 무결성·schema11 [해석 근거 발급](RECORDING_V2_NATIVE_INTERPRETATION_SEAL_20260930.md)·[foreground 준비](RECORDING_V2_NATIVE_INTERPRETATION_FOREGROUND_20260930.md)와 실제 발행/다음 턴/재추출 차단까지 검증. 같은 observer만 허용. 기존 RAW·CONTENT/Quest/Story 경로 유지. NEW 자동 사용·다른 신의 해석 공유·Watch/Rumor 출력 근거·외부 source 모델 파생·전체 coverage/랭크 결합·실모델 품질·legacy writer 전환은 미완료이며 사용자 지시로 후순위. 기존 LLM 대화 기능을 중단한 것은 아님 |
| 평판의 일반 대화 연결 | 테스트 모드 제한, 일부 경계/소비자 있음 | [명시적 방 scope 포트](ROOM_SOCIAL_REPUTATION_M3_PORT.md) 연결. 후속 사회87·평판4,221·사회맥락2,074 검사 통과. 다신 비밀방의 공통 수신 소문 검색·실제 prompt에 들어간 회복 주제만 공급·신별 평가 소유 분리. RUMOR_TEST 제한 유지·PUBLIC 평판 미공개. 운영 일반 활성화/M3 전체 완료 아님 |
| 장비/전투력 | CombatPowerProvider 기본 UNAVAILABLE | [보상 이력·정책·공급자](COMBAT_POWER.md) 연결. 오프라인38·GameTest2 통과. 정책/이력 불완전 시 UNAVAILABLE 유지. 착용 장비로 약함을 위장할 수 없음. 강화·개조는 사용자 결정으로 제외 |
| 리소스팩 장비 보상 | 상점만 component 객체 지원 | [component 보상](RESOURCEPACK_REWARDS.md) 실제 지급/claim 영속/사전검증·선택 시 동결 지원, GameTest3 통과. 클라이언트 리소스팩 렌더는 별도 |
| 물리 NPC | 신 identity/방은 있으나 실제 신 개체 시스템 없음 | [God 아바타](../../../mythictrpg-main/docs/NPC_ACTORS.md) 정의·단일 실체·이동·우클릭 대화·작성형 전투 연결, GameTest1 통과. [건축물 방문](../../../mythictrpg-main/docs/GOD_HOME_VISITS.md) 대화 Proposal와 자율 판단→서버 재검증→실제 보행/도착·NBT 지속성 연결, 방문 GameTest2·AI24 통과. 로드된 같은 차원/도달 가능한 근거리 범위이며 장거리 여행 완성 아님 |
| 레이드 | 전투 기록용 source 구분만 있음 | 작성형 공용/격리 구역·명시적 모집/참가/시작·전역 God lease 대기·실보스 사망/단계 추가몹·탈락/제한시간·영속 receipt/복귀/재시작 실패정리와 일부 arena 보호를 소스에 연결. 전용 합성 GameTest 2/2 통과. AI 제안 확인 통합·운영 콘텐츠/실제 월드/클라이언트/2프로세스 재시작 검증은 별도. 전투 구역 자동 원상복구와 모든 모드의 직접 월드 쓰기 차단은 미구현. [상세 계약](../../../mythictrpg-main/docs/RAID_RUNTIME.md) |
| 즉석 퀘스트 확장 | 개인 SIDE 템플릿 실행됨 | [신규 수주 보상 동결·목표 달성 후 지급 복구](GENERATED_QUEST_RECOVERY.md) 연결, GameTest2 통과. 개인형·기존 5목표 범위 유지. 다인 즉석 생성/새 목표 추가를 완료한 것이 아님 |
| 통합/릴리스 | 이전 설치 JAR 조합 | 게임1.0.22·AI0.1.25·콘텐츠0.1.3 미배포. schema11 게임 focused18 tasks/65초, AI focused13 tasks/13초·483 classes/게임 중복0 및 마지막typed fixture121 단독 통과. 실제 typed lifecycle1/1(1.025초), CONTENT 후손 회귀1/1(878.5ms) 통과. 이전 전체 build와 구분하며 운영 서버·실제 LLM/클라이언트/대규모 검증은 하지 않음 |

## 이미 있는 시스템을 대체하지 않을 것

퀘스트 수주·참여·정산, FTB 표시, 보상 claim, 화폐·상점, 관찰/조건/조우, Story 런타임,
신/플레이어 관계 원본, 대화방·채팅·HUD·이름 식별, 콘텐츠 레지스트리는 기존 구현부터 확장한다.
`기존 구현 있음`은 추가 콘텐츠나 모든 위험 조건의 실게임 검증까지 완료됐다는 뜻이 아니다.

## 콘텐츠가 비어 있어도 시스템에서 준비할 수 있는 것

작성자가 넣을 가격·능력치·기술·보상·스토리 문구·조건은 데이터 필드로 받는다.
임의의 운영용 기본 밸런스나 신별 기술을 채워 넣지 않는다. 합성 개발 fixture는 운영 콘텐츠와 분리한다.
새로운 게임 규칙 결정이 필요한 경우 이 표에 미결정을 기록하고 사용자에게 질문한다.

## 현재 검증/배포

- AI 독립 `build` 통과. 기존 원본 232개 비오버레이 class가 새 컴파일 결과와 byte 단위 동일하다.
- 스토리 선택 오프라인17, 감정 오프라인53 통과. 필수 문맥을 보존하고 선택 기억을 항목 단위로 줄이는 예산 처리를 수정하여 모의 LLM 연속 대화/스토리 wire 통합 GameTest **2/2 통과** (`build/room-budget-story-wire-20260929`).
- 기록 저장소 62 assertions, 합산 용량 71 checks 통과. Windows symlink 생성 권한이 없어 해당 symlink 검증은 생략됐다.
- 전투력2/즉석 보상 복구2/레이드 제안1의 집중 GameTest **5/5 통과** (`build/power-offer-recovery-recheck-20260929`). 바닐라 효과 modifier 이중 계산과 offer fixture의 누락 arena를 수정했다. 손상 데이터 거절 로그는 해당 fixture의 의도된 검사다.
- 게임 `build --offline --no-daemon --max-workers=1` **39 tasks 성공**. 최초 전체 검사에서 Windows 자식 프로세스 로그를 UTF-8로 읽는 진단 코드가 실패하여 UTF-8 출력/실패 때만 안전 디코딩하도록 수정했다. 실제 강제 종료 후 SQLite WAL 복구 검사62는 재통과했다.
- AI `build` **36 tasks 성공**, raidCapabilityBridge22 checks, 410 class 소유권·중복 검사를 포함한다. 실제 Ollama는 호출하지 않았다.
- 레이드 전용 namespace `mythictrpg_raid` 합성 GameTest **2/2 통과**(`build/raid-lifecycle-20260929`). 일반 몹 실제 사망→보상 영수증 1회·복귀, arena 점유 대기/외부 공격 차단, God 전역 lease 대기→실체화·재시작 중단 경로를 검사했다. 첫 결합 실행의 AI raid offer fixture 1건 실패는 후속 `build/power-offer-recovery-recheck-20260929` focused **5/5 통과**(offer1/power2/generated2)로 해결했다. 처음 실행 자체를 전체 성공으로 소급하지 않는다. 실제 운영 월드와 클라이언트 화면, 물리적 서버 재기동은 시험하지 않았다.
- 운영 `server`의 JAR·월드·설정·클라이언트 파일은 변경하지 않았다.

### 2026-09-29 후속 검증 — 위 초기 결과 이후

- 방문/채널 후속 시점 게임 **1.0.19 / AI 0.1.22** 전체 build는 각각 **40 / 37 tasks 성공**이다. AI 클래스413·게임 클래스 중복0, 감정58, 방문24 검사를 포함한다. 이전39/36 결과는 당시 이력이다.
- 방문 전용 `build/home-visit-forced-fixture-20260929` **2/2 통과**: 실제 걷기/도착, 자율 tick, 건축물 삭제·진행도 취소·미제시 ID·다른 명령, NBT 재읽기, 실제 사적 방 Proposal→Gateway→이동, 같은 방의 후속 요청, 닫힌 방의 늦은 결과 폐기를 검사했다. fixture만 청크를 로드/강제 ticking하며 원래 상태를 복원한다. 운영 방문은 청크를 강제 로드하지 않는다.
- 채널 `build/recording-accepted-empty-20260929` **1/1 통과**: 실제 NeoForge/FTB/Netty/SQLite, 13 RAW, 성공 전송 없는 입력도 원문 보존·receipt 0. 실패/cancelled transport 오류 로그는 의도된 fixture다.
- 방문/사회 세 모듈 대화 `build/home-social-integration-20260929` **1/1 통과**. 프로필/기억/Primary→Secondary/3턴 연속 문맥/실제 게임 발행을 확인했으나 LLM transport는 모의했다. 별도의 SocialRuntime 의미 심사 종단 검사는 아니다.
- 기존 기억/평판 재사용 후 **게임1.0.20 / AI0.1.23** 컴파일·관련 검사·JAR·413 class 패키지 검사를 통과했다. 최신 세 모듈 `build/memory-reputation-reuse-20260929` **1/1 통과**(모의 LLM). [세부 검사/제약](MEMORY_REPUTATION_REUSE_20260929.md). 전체 build를 다시 실행하거나 물리 전서구+의미 심사 새 종단 검사를 완료한 것은 아니다.

## 미완료 이유와 다음 작업

### 2026-09-30 연속 작업 최종 확인

- [새 기록 읽기·관찰 연결](RECORDING_V2_AUTHORIZED_READ_20260930.md): 권한/RAW 검색 **363**, 중앙 수집/호환 migration **36**, Watch source **47** checks 통과. 기존 경험24/exact proof16, SQLite62 검사도 통과했다.
- 전체 게임 **42 tasks**, AI **37 tasks** offline build 성공. 마지막 async read timeout/dispatch 복구 패치 뒤 게임 관련363 검사·JAR를 다시 만들었다. AI는290 청중/기억·126 회상·98 방 엔진, 415 classes/게임 중복0을 포함한다.
- 최종 세 모듈 조합 `build/recording-integration-final-20260930`: 실제 저장 완료/실패 callback·quota·게임발 방 권한 취소 **3/3**, 별도 모의 LLM 방 대화 **1/1** 통과. 실제 모델·운영 서버·클라이언트 검증은 아니다.
- 소문용 저장 완료 callback은 준비됐지만, root/receipt별 영속 cursor와 신규 source cutover·철회/reconciliation까지 연결한 것은 아니다. 이 부분을 전체 Rumor source 완료로 오해하지 않도록 상세 문서에 다음 구현 조건을 기록했다.
- 운영 설치본은 여전히 게임1.0.16/AI0.1.19/콘텐츠0.1.2다. 서버·클라이언트·월드·설정은 교체하지 않았다.

### 아직 남은 범위와 사유

**후속 진행(2026-09-30):** 아래 이전 시점의 잔여 목록 중 개별 receipt 철회·Watch 재시작 reconciliation·Rumor durable cursor는 [M3 후속 기록](RECORDING_V2_M3_SOURCE_RECONCILIATION_20260930.md)에서 구현·집중 검증했다. 새 M4 경로는 native 직접 청취 대화부터 실제 SQLite 저장과 모의 모델을 연결해 검증 중이다. 전체 M4/M5 완료나 운영 전환으로 표시하지 않는다.

- **장기기억 새 저장소 전체 전환:** 개별 receipt 철회·재시작 reconciliation·Rumor durable cursor, native work item→파생 backend와 원문 FTS까지 후속 연결했다. 외부 행동/소문의 파생·새 검색, 파생물/의미 검색, 명시 ON 전환·legacy writer 종료는 여전히 남는다. 기존 읽기 소비자와 TXT writer를 중단하지 않았고 기본 OFF를 유지했다. 운영 기록을 갑자기 끊거나 기존 데이터 권한을 추측해 수입하지 않았다.
- **일반 대화 평판:** 방 scope의 생성/회복·현재 평가 Context와 다신 비밀방 공통 소문 검색은 연결했다. 새 저장소의 지식/공개 권한 전환 전에는 RUMOR_TEST 제한을 풀지 않는다. PUBLIC의 기존 소문 공개 허가는 없어 미제공이며, 모든 Secondary의 독립 회복 심사·실제 의미 심사 종단 검증은 남았다.
- **방문 한계:** 대화/자율 방문의 실행 경로는 연결했다. 다만 자율 판단에 쓸 공용 감정 공급자가 없으므로 `UNASSESSED`를 전달한다. 다른 비밀방의 감정을 무단 재사용하지 않는다. 언로드 장거리·차원 간 이동·자동 소환은 구현 범위가 아니며 도착 후 강제 대화 개시도 하지 않는다. 운영용 허용 신/조건·빈도는 제작자가 JSON으로 작성해야 한다.
- **운영 콘텐츠/검증:** 신별 실제 외형/전투 수치, arena 월드, 레이드 정의·보상, 전투력 정책은 제작자가 작성한다. 엔진용 fixture를 운영 콘텐츠로 설치하지 않았다. 실제 프로세스 재시작·클라이언트·Ollama 품질·확정 모드 조합 검증과 배포는 별도다.
