# HanesTest 작성형 레이드 런타임 계약

2026-09-30 개발 소스 기준. 레이드·전투 구역의 실제 콘텐츠, 밸런스, 신의 정사상 사망 여부를 이 문서나 엔진이 결정하지 않는다. 기존 `GodDefinition` ID를 보스로 사용할 때도 세계관상 신의 죽음 사실을 자동 기록하지 않는다.

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

Arena 필수 필드는 `schemaVersion`, `dimension`, `minimum`, `maximum`, `entry`, `bossSpawn`, `exit`이다. 세 점·두 경계는 `[x,y,z]` 배열이며 각 변 길이는 4..128블록이다. entry와 bossSpawn은 경계 안쪽, exit은 2블록 확장 경계 밖이어야 한다. 실제 발판·안전한 복귀 좌표·보스 충돌 공간과 지역 연출은 작성자가 월드에서 확인해야 한다. 값의 허용 범위는 기술적 안전 경계이지 추천 전투 밸런스가 아니다.

## 실행과 권한

`/mythraid create <raidId>`로 모집을 만들고 참가자가 `/mythraid join <attemptUUID>`를 직접 실행한다. `PARTY_ISOLATED`는 모집 시 FTB Teams의 초대 가능 멤버를 고정하고 join 시 현재 같은 팀인지 재확인한다. `/mythraid start <attemptUUID>`는 리더만 실행하며 roster를 고정해 큐에 넣는다. `/mythraid mine`, `/mythraid status <attemptUUID>`, 리더의 `/mythraid cancel <attemptUUID>`를 제공한다. 공용 레이드도 누가 실제로 참가할지는 작성형 최소·최대 인원과 명시적 join으로 결정한다.

`/mythraid leave <attemptUUID>`는 **FORMING의 비리더 본인만** 탈퇴시킨다. membership 제거를 기존 `mythictrpg_raids` SavedData에 저장하며 다시 참가하거나 다른 모집을 만들 수 있다. 리더에게는 기존 cancel 명령을 안내하고, 리더 자동 이전은 하지 않는다. QUEUED/STARTING/ACTIVE의 참가 명단, 사망·퇴장 처리와 보상 자격 규칙은 변경하지 않는다.

### 참가·상태 화면

`/mythraid` 또는 `/mythraid ui`로 열린다. 서버가 현재 생성 가능한 작성형 레이드, 본인이 참가한 시도, 실제 join 조건을 통과한 FORMING 모집을 제공한다. OP의 시도 조회 권한은 기존 status와 같지만 화면의 일반 취소 버튼은 여전히 해당 리더만 쓸 수 있다. 타인의 비공개 진행 시도나 초대·현재 팀·Condition 검증에 실패한 모집은 일반 플레이어에게 노출하지 않는다.

화면에서 모집 생성/참가/대기 시작/모집 탈퇴/전체 취소를 요청할 수 있다. 모집·대기·전투·정산/종료 상태, 인원, 본인 남은 목숨, 대기/전투 남은 시간, 현재 보스 체력이 있으면 표시한다. 2초마다 서버에 새 snapshot을 요청하며 수동 새로고침도 가능하다. 서버 확인 전에는 상태를 미리 바꾸지 않는다. 작은 화면에서 상세 영역은 휠로 스크롤할 수 있다. 별도 BossBar는 추가하지 않았다.

네트워크는 공통 protocol 8의 `raid_page`/`raid_request`를 사용한다. 페이지는 최대 6행, 저장소·카탈로그 한도에 맞춰 최대 768행/128페이지다. 클라이언트는 서버가 발급한 token/action/행 번호만 돌려보내고, player UUID·roster·상태·보스·보상 등은 보내지 않는다. 토큰은 플레이어별 단일 30초 lease이며 한 번의 요청 전에 소비한다. 상태·참가 명단·정의·현재 권한을 재확인하고 기존 `RaidRuntime`에서 다시 실행 검증한다. 토큰 재전송, 다른 플레이어 토큰, 오래된 roster·상태, 임의 버튼은 상태를 변경하지 않고 새 조회 결과로 돌려준다. 닫기/로그아웃/서버 종료 시 grant를 폐기하며 늦은 응답은 닫힌 화면을 다시 열지 않는다. 이 UI 토큰은 게임 저장 상태나 보상 영수증을 대체하지 않는다.
OP는 `/mythraid admin cancel <attemptUUID>`로 리더가 떠난 모집/진행 시도를 취소할 수 있다. 이 명령도 `CLEANUP_PENDING`을 강제로 풀거나 승리 영수증을 취소하지 않는다.

`RaidRuntime.canCreate(player, raidId)`와 `validateOffer(player, godId, raidId)`는 실제 시도를 만들지 않는 조회 검증이다. 확인 후 `create`가 같은 조건을 다시 평가한다. AI가 대사에서 레이드를 언급하는 것과 실제 모집 생성은 구분한다.

큐는 빈 authored arena와, God 보스라면 전역 단일 실체 lease를 함께 확보해야 시작한다. 다른 God 실체를 복제·강제 이동하지 않는다. 입장 전 모든 참가자의 위치·차원·회전값을 저장한다. arena 청크는 임시 region ticket으로 유지하며 기존 강제 청크 상태를 수정하지 않는다. 보스와 추가 몹은 시도 UUID로 표시하고, 실제 보스 사망이 확인된 뒤에만 승리한다. 보스 소실·전투 제한·전원 탈락은 실패다. 레이드 몹의 일반 사망 드롭·XP는 막고, 보상은 작성형 receipt만 따른다. 승리·패배는 기존 ImportantEvents와 StorySignal에 보고하지만 Story 콘텐츠 매칭·세계 사실 변경은 별도 작성형 정의가 있어야 한다.

격리 구역은 비참가자를 exit으로 이동시키고, 참가자와 레이드 몹 사이의 피해만 허용한다. 참가자는 외부 플레이어·몹을 공격할 수 없고 외부 공격도 참가자를 해치지 못한다. 레이드 중 두 모드의 arena에서는 블록 파괴·설치·일부 상호작용·유체·피스톤·폭발 블록 피해를 차단한다. 이 보호는 모든 모드의 직접 월드 쓰기, 화재 확산·엔티티 변환, 모드가 자체 생성하는 전리품까지 완전하게 가로채지는 못한다. 구역을 자동으로 초기 상태로 되돌리는 기능도 없다. 제작자는 전용 슬롯의 재사용·정리 정책을 별도로 마련해야 하며, 현재 엔진을 완전한 월드 격리/롤백으로 간주하면 안 된다.

정상 종료 때 온라인 참가자는 원위치로 복귀한다. 오프라인 또는 사망 상태는 복귀 지점을 SavedData에 유지하고 재접속/리스폰 때 재시도한다. 서버 재시작 중이던 전투는 임의 재개하지 않고 실패 정리하며, 승리 후 receipt 대기는 같은 source ID로 재시도해 중복 지급을 막는다. 청크 또는 생성 entity의 제거를 확인하지 못하면 `CLEANUP_PENDING`으로 arena·God lease를 계속 점유하고 운영자 조사가 필요하다. 손상된 저장 데이터는 원문을 보존하고 신규 레이드 쓰기를 멈춘다. 성공/실패가 기록됐어도 실제 월드 슬롯 상태·복귀 차원·리소스팩·클라이언트 UI는 인게임 확인 전까지 검증 완료로 표시하지 않는다.

## 검증 경계

후속 검토에서 **아직 arena/God lease를 얻지 못한 대기 시도의 취소**가 다른 시도의 점유 해제를 기다리던 오류를 수정했다. 자신의 entity·lease가 없는 큐 항목은 다른 시도의 자원을 건드리지 않고 취소한다. 손상 NBT 목록의 잘못된 타입은 읽기 전용으로 보존하며, 구 정상 저장본에서 선택적 `cleanedEntities`가 없는 경우는 호환한다. `build/recording-channels-20260929` 결합 실행 중 레이드 namespace **2/2는 통과**했지만, 같은 실행의 채널 시험은 당시 실패했으므로 결합 전체 성공은 아니다. 채널의 후속 통과는 별도 기록을 따른다.

`RaidGameTests`는 `mythictrpg_raid` namespace의 합성 arena/보스 fixture이며 개발용 `build/raid-lifecycle-20260929` 결합 실행에서 레이드 소속 **2/2 통과**했다. 같은 첫 실행의 별도 AI raid offer GameTest 1개는 fixture 오류로 실패했으나 수정 후 `build/power-offer-recovery-recheck-20260929`의 focused 검사에서 offer 1개 포함 **5/5 통과**했다. 처음 결합 실행을 소급해 3/3 통과로 바꾸지 않는다. 명시적 큐와 arena 점유 대기, 외부인 양방향 공격 차단, 실제 보스 사망→복귀·보상 receipt 1회, SavedData 및 손상 원문 보존을 확인했다. 별도 합성 God 시도는 전역 God lease 대기·해제 후 단일 실체 시작, ACTIVE 저장 상태의 round trip과 동일 복구 함수의 재시작 실패·복귀·lease 정리를 검사했다. God lease 자체의 단일 실체 계약은 `GodAvatarGameTests`에서도 확인했다. 손상 원문 fixture가 출력하는 `Raid state is read-only` 오류 로그는 의도적 거절 경로다.

이번 두 시험은 단계 추가몹의 실제 AI 행동, 사망/재접속/차원이동의 모든 타이밍, 유예·시간초과, 오프라인 복귀, 모든 보상 종류·불가용 저장소 재시도, 실제 데이터팩 arena와 여러 FTB 팀, 모든 모드의 블록·드롭 이벤트를 포괄하지 않는다. 실제 서버를 중지·재시작한 2프로세스 디스크 복원, 클라이언트 화면/리소스팩, 운영 서버 JAR 배포와 인게임 체감 검증도 실시하지 않았다. 원본 전투 구역을 자동으로 초기화하거나 복제·삭제하는 시스템 역시 없다.

2026-09-30 추가한 `RaidUiGameTests` (`mythictrpg_raid_ui`)는 실제 서버/접속 플레이어 fixture에서 UI grant→기존 Runtime 실행을 검사한다. 생성·재전송·다른 플레이어 토큰, stale roster join, 잘못된 리더 조작, FORMING 탈퇴의 dirty/NBT round trip·재참가, 대기 시작·고정 roster 거절·취소, 비공개 모집 비노출·닫힌 토큰을 포함한다. **집중 실행 1/1 통과, 351.9ms / Gradle 12초 성공**이다. 이는 실제 클라이언트 렌더링/마우스 조작·운영 배포를 수행한 것으로 보지 않는다.
