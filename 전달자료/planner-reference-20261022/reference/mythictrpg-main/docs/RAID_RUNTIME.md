# HanesTest 작성형 레이드 런타임 계약

> 2026-10-21 `behaivior` 개발 후속: 작성형 피해 대응 도구를 기존 실제 레이드 피해 경로에 연결했다. 도구의 component와 시도·참가자·출처를 확인하며 정책을 모집 생성 시 고정·저장한다. [작성 형식과 지원 범위](CONTENT_SYSTEMS_GUIDE.md)를 따른다. 개별 보스 기믹·운영 밸런스·배포를 완료한 것은 아니다.

일반 권한 `/mythraid create <namespace:path>`는 Minecraft 리소스 ID 파서로 전체 ID(`:` 및 `/` 포함)를 받는다. 기존 단어 인수 파서 때문에 ID 중간에서 거절되던 연결 오류를 수정했다. 등록되지 않은 레이드는 생성하지 않으며 참가·리더·최소 인원 검사도 유지한다. [일반 플레이 검증 기록](../../인수인계/브랜치실험/behaivior/CONTENT_GAMEPLAY_20261021.md)을 참고한다.

멀리 떨어진 전장은 블록 청크 로드만으로 시작하지 않는다. 전장·신 lease와 임시 청크 ticket을 확보한 `STARTING`에서 Minecraft의 엔티티 로드·tick 준비를 기다린 뒤, 참가 조건을 다시 검사하고 이동·보스 생성을 한 번 실행한다. 준비 대기도 작성된 queue timeout을 공유하며 리더 취소와 중단 후 자원 해제를 유지한다. 이 구간에도 피스톤을 포함한 전장 블록 보호를 적용하고, 진행 화면에 입장 준비·남은 대기 시간·명단 고정을 표시한다.

2026-09-30 개발 소스 기준. 레이드·전투 구역의 실제 콘텐츠, 밸런스, 신의 정사상 사망 여부를 이 문서나 엔진이 결정하지 않는다. 기존 `GodDefinition` ID를 보스로 사용할 때도 세계관상 신의 죽음 사실을 자동 기록하지 않는다.

2026-10-13 후속: 게임 1.0.28에 사용자가 기획·제작을 위임한 [첫 시험 레이드](raid-trial-guide.md)를 추가했다. 전용 차원·고정 보스 제어기·직접 제작한 외형을 기존 모집/시작/승패/정리/복귀에 연결한다. 일반 `phases` JSON 형식은 유지하며 시련의 세부 패턴은 서버 코드에 있다. 해당 시련은 무보상·비정사로 Story/중요사건 신호를 발행하지 않는다. 미배포이며 검증은 [브랜치 기록](../../인수인계/브랜치실험/HanesTest/RAID_TRIAL_20261013.md)을 따른다.

## 데이터 작성 위치와 필수 결정

서버 데이터팩 `data/<namespace>/mythictrpg/raids/<path>.json` 및 `data/<namespace>/mythictrpg/raid_arenas/<path>.json`에 작성한다. 파일 ID가 정의 ID다. Reload는 두 목록을 함께 검증하고 성공 때만 원자적으로 교체한다. 이미 시작된 시도는 원문 정의·arena JSON을 SavedData에 고정해, 운영 중 데이터팩을 바꿔도 해당 시도의 규칙이 바뀌지 않는다. 스키마는 `schemaVersion: 1`이고 알 수 없는 필드는 거절한다.

| 레이드 필드 | 의미 |
|---|---|
| `displayName`, `mode` | 이름과 `PUBLIC_WORLD` 또는 `PARTY_ISOLATED`. 공용도 참가 버튼 없이 자동 참가시키지 않는다. 격리는 원본 맵 복제가 아닌 미리 만든 구역 배정이다. |
| `arenas` | 사용할 arena ID 목록 `1..16`. 존재·상호간 최소 16블록 buffer를 검사한다. |
| `minimumPlayers`, `maximumPlayers` | 명시적 참가자 범위 `1..32`. 모집 중에만 join 가능하며 start 때 roster가 고정된다. |
| `queueTimeoutTicks`, `combatTimeoutTicks` | 각각 대기·전투의 제한 `20..1,728,000` tick. 시간 초과는 실패. |
| `livesPerPlayer`, `disconnectGraceTicks` | 사망 허용 횟수 `1..100`, 재접속 유예 `0..72,000` tick. 퇴장·초과 사망 시 참가 종료. |
| `boss` | `entityType` 또는 `godId` 정확히 하나. God ID 보스는 기존 God Definition과 아바타 JSON이 필요하고, 아바타의 `combat.enabled`, `damageable`, `raidControl`이 true여야 한다. 같은 신의 실체가 다른 곳에 있거나 다른 시도 예약 중이면 대기한다. |
| `phases` | 선택적 체력 비율 내림차순 `0..8`단계. 각 단계의 `atHealthRatio`와 `spawns`는 작성자가 정한다. 몹의 실제 AI 공격 행동은 entity type의 구현에 좌우된다. |
| `entryCondition` | 기존 Condition Engine 노드. create/join 및 시작 직전에 모든 참가자를 fail-closed 재평가한다. `UNKNOWN`은 통과하지 않는다. |
| `offerGodIds` | 선택적 신 ID 목록(생략 시 빈 집합). AI 신의 레이드 제안 권한은 이 목록에만 있으며 `rewardGodId`가 같아도 암묵 제안권은 없다. 제안 확인 뒤에도 모집 생성만 가능하고 join/start는 별도 자발 명령이다. |
| `rewardEligibility` | 필수 선택: `ALL_FROZEN_ROSTER` 또는 `SURVIVING_PRESENT`. 보상 자격은 승리 시 고정된다. 운영 정책 기본값은 없다. |
| `rewardGodId`, `rewards` | 기존 RewardEntryCodec 스키마. 보상은 전원 고정 영수증 batch로 등록하고 기존 `RewardClaimService`가 전달한다. 레이드 엔진이 직접 아이템·화폐를 재지급하지 않는다. |

### 직접 승리 보상과 퀘스트 보상의 출처

2026-10-21 후속 소스에서는 레이드 정의의 `rewards`를 실제 승리로 지급할 때 `RewardOrigin.RAID_DIRECT`를 보상 영수증과 영구 가호 획득 기록에 저장한다. 퀘스트가 “레이드 보스 처치”를 완료 조건으로 삼아 지급하는 보상은 기존 `QUEST` 출처를 유지한다. 같은 신·효과·보상 저장소를 사용해도 두 획득 경로를 합치지 않는다. `RewardClaimState.hasNonRaidBlessing(playerId)`는 직접 레이드 가호만 보유한 경우 false이며, 별도의 퀘스트 등 레이드 외 경로에서 영구 가호를 얻으면 true다. 지급 출처는 실행 서비스가 지정하며 레이드 JSON·플레이어 요청으로 덮어쓰지 못한다.

직접 레이드 보상도 `adaptive_blessing`과 `lucky_item`을 공용 codec으로 읽고 기존 claim 경로에서 실행한다. 예를 들어 레이드 JSON 안의 다음 `rewards`는 가상 가호 정의와 행운 풀을 참조한다. 실제 정의·정책·신 등급은 별도로 작성해야 하며, 이 예시가 운영 보상표를 등록하지는 않는다.

```json
{
  "rewardGodId": "example:guide_deity",
  "rewards": [
    {"type": "adaptive_blessing", "definitionId": "example:guide_blessing"},
    {"type": "lucky_item", "poolId": "example:raid_victory_items"}
  ]
}
```

`lucky_item`은 수령자 본인의 실제 행운 효과와 작성된 풀의 품질·가중치·수량 허용값으로 아이템 종류·component·수량을 정한다. 행운 XVII, 기본 수량 10, 수량 보너스를 허용한 기본 모델이면 13개가 된다. 고정 `item` 보상은 행운에 의해 자동 증량하지 않는다. 오프라인 승리자는 기존 대기 영수증을 통해 접속 후 수령하며, 처음 확정한 결과와 `RAID_DIRECT` 출처를 저장해 재시도·선택·재접속 때 다시 추첨하거나 퀘스트 출처로 바꾸지 않는다.

정적 `RaidDefinition.parse`는 공용 보상 스키마를 검사한다. 다른 리소스 레지스트리가 동시에 reload되는 준비 단계에 이전 snapshot을 교차 조회하지 않으며, 실제 `canCreate`, `start`, 입장 직전에 `RaidRewardValidation`이 등록된 보상·아이템 component·적응형 가호의 신 일치·신 등급·허용 정책을 검증한다. 플레이어별 후보 선택은 실제 지급 경로가 담당한다. 전투 중 정의가 사라지거나 수령 상태를 확인할 수 없으면 기존 보상 대기·재시도 규칙을 사용하며, 승리 상태와 개인의 실제 수령 완료는 구분한다. 레이드 시도에 고정한 보상 참조와 해당 보상 결과를 처음 확정하는 시점도 구분한다.

현재 형식 이전의 보상 기록은 출처를 추측하지 않고 `LEGACY_UNSPECIFIED`로 보존한다. 새 직접 레이드 보상의 구분은 신규 지급부터 명시적으로 기록한다. 전체 출처·영구 가호 정책과 기획 입력은 [가호·보상 기획 전달자료](../../전달자료/blessing-reward-planning-20261021.md)를 따른다.

Arena 필수 필드는 `schemaVersion`, `dimension`, `minimum`, `maximum`, `entry`, `bossSpawn`, `exit`이다. 세 점·두 경계는 `[x,y,z]` 배열이며 각 변 길이는 4..128블록이다. entry와 bossSpawn은 경계 안쪽, exit은 2블록 확장 경계 밖이어야 한다. 실제 발판·안전한 복귀 좌표·보스 충돌 공간과 지역 연출은 작성자가 월드에서 확인해야 한다. 값의 허용 범위는 기술적 안전 경계이지 추천 전투 밸런스가 아니다.

## 실행과 권한

`/mythraid create <raidId>`로 모집을 만들고 참가자가 `/mythraid join <attemptUUID>`를 직접 실행한다. `PARTY_ISOLATED`는 모집 시 FTB Teams의 초대 가능 멤버를 고정하고 join 시 현재 같은 팀인지 재확인한다. `/mythraid start <attemptUUID>`는 리더만 실행하며 roster를 고정해 큐에 넣는다. `/mythraid mine`, `/mythraid status <attemptUUID>`, 리더의 `/mythraid cancel <attemptUUID>`를 제공한다. 공용 레이드도 누가 실제로 참가할지는 작성형 최소·최대 인원과 명시적 join으로 결정한다.

`/mythraid leave <attemptUUID>`는 **FORMING의 비리더 본인만** 탈퇴시킨다. membership 제거를 기존 `mythictrpg_raids` SavedData에 저장하며 다시 참가하거나 다른 모집을 만들 수 있다. 리더에게는 기존 cancel 명령을 안내하고, 리더 자동 이전은 하지 않는다. QUEUED/STARTING/ACTIVE의 참가 명단, 사망·퇴장 처리와 보상 자격 규칙은 변경하지 않는다.

### 참가·상태 화면

`/mythraid` 또는 `/mythraid ui`로 열린다. 서버가 현재 생성 가능한 작성형 레이드, 본인이 참가한 시도, 실제 join 조건을 통과한 FORMING 모집을 제공한다. OP의 시도 조회 권한은 기존 status와 같지만 화면의 일반 취소 버튼은 여전히 해당 리더만 쓸 수 있다. 타인의 비공개 진행 시도나 초대·현재 팀·Condition 검증에 실패한 모집은 일반 플레이어에게 노출하지 않는다.

화면에서 모집 생성/참가/대기 시작/모집 탈퇴/전체 취소를 요청할 수 있다. 모집·대기·전투·정산/종료 상태, 인원, 본인 남은 목숨, 대기/전투 남은 시간, 현재 보스 체력이 있으면 표시한다. 2초마다 서버에 새 snapshot을 요청하며 수동 새로고침도 가능하다. 서버 확인 전에는 상태를 미리 바꾸지 않는다. 작은 화면에서 상세 영역은 휠로 스크롤할 수 있다. 별도 BossBar는 추가하지 않았다.

게임 1.0.29부터 상단 탭으로 목록을 나눈다. **모집 중**은 FORMING 시도와 생성 가능한 레이드, **진행 중**은 QUEUED/STARTING/ACTIVE/VICTORY_PENDING/CLEANUP_PENDING, **완료 기록**은 SUCCEEDED/FAILED/CANCELLED다. 완료 기록은 클리어·실패·취소를 구분하고 조작 버튼을 허용하지 않는다. 기록 공개 범위는 기존과 같이 본인 참가 이력 또는 OP 조회이며, 타인의 종료 기록을 전역 공개하지 않는다. 기존 SavedData의 보존된 시도만 조회하며 별도 영구 업적/클리어 도감을 만들지 않는다.

서버에서 탭별 상태·조회 권한을 필터링한 뒤 페이지를 나누므로 완료 기록이 많아도 모집 목록을 밀어내지 않는다. 최초 열기는 모집 중, 탭 변경은 첫 페이지로 이동하며 자동/수동 새로고침과 이전/다음은 현재 탭을 유지한다. 시작 성공은 진행 중으로, 취소 후 실제 정리가 끝났으면 완료 기록으로 이동한다. 취소 후 CLEANUP_PENDING이 남으면 진행 중에 표시한다.

네트워크는 공통 **protocol 11**의 `raid_page`/`raid_request`를 사용한다. `raid_page`는 서버가 확정한 View(RECRUITING/IN_PROGRESS/HISTORY)를 포함한다. 서버·클라이언트 게임 JAR을 함께 갱신해야 하며 기존 protocol 10과 혼용할 수 없다. 페이지는 최대 6행, 저장소·카탈로그 한도에 맞춰 최대 768행/128페이지다. 클라이언트는 서버가 발급한 token/action/행 번호만 돌려보내고, player UUID·roster·상태·보스·보상 등은 보내지 않는다. 토큰은 플레이어별 단일 30초 lease이며 탭·페이지·행에 묶이고 한 번의 요청 전에 소비한다. 탭 변경 요청도 이전 grant를 소비하므로 이전 탭 토큰으로 조작할 수 없다. 상태·참가 명단·정의·현재 권한을 재확인하고 기존 `RaidRuntime`에서 다시 실행 검증한다. 토큰 재전송, 다른 플레이어 토큰, 오래된 roster·상태, 임의 버튼은 상태를 변경하지 않고 새 조회 결과로 돌려준다. 닫기/로그아웃/서버 종료 시 grant를 폐기하며 늦은 응답은 닫힌 화면을 다시 열지 않는다. 이 UI 토큰은 게임 저장 상태나 보상 영수증을 대체하지 않는다.
OP는 `/mythraid admin cancel <attemptUUID>`로 리더가 떠난 모집/진행 시도를 취소할 수 있다. 이 명령도 `CLEANUP_PENDING`을 강제로 풀거나 승리 영수증을 취소하지 않는다.

`RaidRuntime.canCreate(player, raidId)`와 `validateOffer(player, godId, raidId)`는 실제 시도를 만들지 않는 조회 검증이다. 확인 후 `create`가 같은 조건을 다시 평가한다. AI가 대사에서 레이드를 언급하는 것과 실제 모집 생성은 구분한다.

큐는 빈 authored arena와, God 보스라면 전역 단일 실체 lease를 함께 확보해야 시작한다. 다른 God 실체를 복제·강제 이동하지 않는다. 입장 전 모든 참가자의 위치·차원·회전값을 저장한다. arena 청크는 임시 region ticket으로 유지하며 기존 강제 청크 상태를 수정하지 않는다. 보스와 추가 몹은 시도 UUID로 표시하고, 실제 보스 사망이 확인된 뒤에만 승리한다. 보스 소실·전투 제한·전원 탈락은 실패다. 레이드 몹의 일반 사망 드롭·XP는 막고, 보상은 작성형 receipt만 따른다. 승리·패배는 기존 ImportantEvents와 StorySignal에 보고하지만 Story 콘텐츠 매칭·세계 사실 변경은 별도 작성형 정의가 있어야 한다.

격리 구역은 비참가자를 exit으로 이동시키고, 참가자와 레이드 몹 사이의 피해만 허용한다. 참가자는 외부 플레이어·몹을 공격할 수 없고 외부 공격도 참가자를 해치지 못한다. 레이드 중 두 모드의 arena에서는 블록 파괴·설치·일부 상호작용·유체·피스톤·폭발 블록 피해를 차단한다. 이 보호는 모든 모드의 직접 월드 쓰기, 화재 확산·엔티티 변환, 모드가 자체 생성하는 전리품까지 완전하게 가로채지는 못한다. 구역을 자동으로 초기 상태로 되돌리는 기능도 없다. 제작자는 전용 슬롯의 재사용·정리 정책을 별도로 마련해야 하며, 현재 엔진을 완전한 월드 격리/롤백으로 간주하면 안 된다.

정상 종료 때 온라인 참가자는 원위치로 복귀한다. 오프라인 또는 사망 상태는 복귀 지점을 SavedData에 유지하고 재접속/리스폰 때 재시도한다. 서버 재시작 중이던 전투는 임의 재개하지 않고 실패 정리하며, 승리 후 receipt 대기는 같은 source ID로 재시도해 중복 지급을 막는다. 청크 또는 생성 entity의 제거를 확인하지 못하면 `CLEANUP_PENDING`으로 arena·God lease를 계속 점유하고 운영자 조사가 필요하다. 손상된 저장 데이터는 원문을 보존하고 신규 레이드 쓰기를 멈춘다. 성공/실패가 기록됐어도 실제 월드 슬롯 상태·복귀 차원·리소스팩·클라이언트 UI는 인게임 확인 전까지 검증 완료로 표시하지 않는다.

## 검증 경계

2026-10-18 개발 1.0.31: 비정사 시험 수호자에 `RAID_CHOICE` 행동 원장 생산자를 연결했다. 보스 진행·보상·Story 정책은 유지한다. `test=true`라 정식 플레이어 분석에서 제외되며, 정식 레이드의 기믹/지원/위험 성향이 완성된 것은 아니다. 명단·중복·불가용 기회·재시작 검증과 구 원장 읽기 호환 주의는 [플레이어 이해 후속 계약](../../추가개발/플레이어이해/07-followup.md)을 따른다.

후속 검토에서 **아직 arena/God lease를 얻지 못한 대기 시도의 취소**가 다른 시도의 점유 해제를 기다리던 오류를 수정했다. 자신의 entity·lease가 없는 큐 항목은 다른 시도의 자원을 건드리지 않고 취소한다. 손상 NBT 목록의 잘못된 타입은 읽기 전용으로 보존하며, 구 정상 저장본에서 선택적 `cleanedEntities`가 없는 경우는 호환한다. `build/recording-channels-20260929` 결합 실행 중 레이드 namespace **2/2는 통과**했지만, 같은 실행의 채널 시험은 당시 실패했으므로 결합 전체 성공은 아니다. 채널의 후속 통과는 별도 기록을 따른다.

`RaidGameTests`는 `mythictrpg_raid` namespace의 합성 arena/보스 fixture이며 개발용 `build/raid-lifecycle-20260929` 결합 실행에서 레이드 소속 **2/2 통과**했다. 같은 첫 실행의 별도 AI raid offer GameTest 1개는 fixture 오류로 실패했으나 수정 후 `build/power-offer-recovery-recheck-20260929`의 focused 검사에서 offer 1개 포함 **5/5 통과**했다. 처음 결합 실행을 소급해 3/3 통과로 바꾸지 않는다. 명시적 큐와 arena 점유 대기, 외부인 양방향 공격 차단, 실제 보스 사망→복귀·보상 receipt 1회, SavedData 및 손상 원문 보존을 확인했다. 별도 합성 God 시도는 전역 God lease 대기·해제 후 단일 실체 시작, ACTIVE 저장 상태의 round trip과 동일 복구 함수의 재시작 실패·복귀·lease 정리를 검사했다. God lease 자체의 단일 실체 계약은 `GodAvatarGameTests`에서도 확인했다. 손상 원문 fixture가 출력하는 `Raid state is read-only` 오류 로그는 의도적 거절 경로다.

이번 두 시험은 단계 추가몹의 실제 AI 행동, 사망/재접속/차원이동의 모든 타이밍, 유예·시간초과, 오프라인 복귀, 모든 보상 종류·불가용 저장소 재시도, 실제 데이터팩 arena와 여러 FTB 팀, 모든 모드의 블록·드롭 이벤트를 포괄하지 않는다. 실제 서버를 중지·재시작한 2프로세스 디스크 복원, 클라이언트 화면/리소스팩, 운영 서버 JAR 배포와 인게임 체감 검증도 실시하지 않았다. 원본 전투 구역을 자동으로 초기화하거나 복제·삭제하는 시스템 역시 없다.

2026-09-30 추가한 `RaidUiGameTests` (`mythictrpg_raid_ui`)는 실제 서버/접속 플레이어 fixture에서 UI grant→기존 Runtime 실행을 검사한다. 생성·재전송·다른 플레이어 토큰, stale roster join, 잘못된 리더 조작, FORMING 탈퇴의 dirty/NBT round trip·재참가, 대기 시작·고정 roster 거절·취소, 비공개 모집 비노출·닫힌 토큰을 포함한다. **집중 실행 1/1 통과, 351.9ms / Gradle 12초 성공**이다. 이는 실제 클라이언트 렌더링/마우스 조작·운영 배포를 수행한 것으로 보지 않는다.
