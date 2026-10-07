# HanesTest 물리 신 아바타 계약

후속 생활활동은 [공간 인식·활동 실행 계약](NPC_ACTIVITY_SYSTEM.md)과 [활동 JSON 작성](NPC_ACTIVITY_AUTHORING_GUIDE.md)을 따른다. 게임 1.0.23/protocol 10의 개발 추가이며 운영 미배포다.

2026-09-30 `HanesTest` 개발 소스 기준. 이 문서는 기존 `GodDefinition`의 신 ID를 실제 Minecraft entity에 연결하는 코드 계약이다. 플레이어형 모델과 신별 스킨 선택을 지원하며 [스킨 추가 안내](NPC_SKINS.md)를 따른다. 신별 스킨 이미지, 능력치, 전투 성향, 방문 가능 여부, 실제 등장 장소의 콘텐츠 값은 임의로 작성하지 않았다. 클라이언트와 서버 JAR 동기화 및 실제 리소스팩/인게임 체감은 별도 검증 단계다. 레이드 연동은 [RAID_RUNTIME.md](RAID_RUNTIME.md)의 구현·검증 경계를 따른다.

## 데이터와 정체성

서버 데이터팩 `data/<namespace>/mythictrpg/god_avatars/<path>.json`의 파일 ID가 기존 `GodDefinition` ID다. 아바타 파일 안에 별도 신 ID를 쓰지 않는다. `GodAvatarDefinitionManager`는 모든 파일을 strict/atomic reload하고, 출현 전 게임 God Definition 존재를 다시 확인한다. 데이터가 없으면 해당 신은 기존 HUD/방 상호작용만 사용한다. reload로 정의가 사라진 기존 entity는 저장하되 대화·전투·이동을 멈추며, OP가 조사·제거할 수 있다.

필수 JSON 필드는 아래와 같다. `formatVersion`은 `1`이다. 각 값의 범위는 파서 안전 경계일 뿐 운영 밸런스 추천값이 아니다.

| 객체 | 필수 필드 | 허용 범위/의미 |
|---|---|---|
| `appearance` | `textureVariant`, `scale` | variant `0..255`, scale `0.5..2`. 선택 필드 `model`은 `classic`(기본, 4px 팔) 또는 `slim`(3px 팔). variant `0`은 체형에 맞는 Steve/Alex 기본 스킨. `1..255`는 리소스팩의 `assets/mythictrpg/textures/entity/god_avatar/variant_<번호>.png`를 사용하고 누락 시 기본 스킨으로 표시한다. scale은 vanilla `SCALE` 속성으로 표시와 충돌 크기에 함께 적용된다. |
| `stats` | `maxHealth`, `movementSpeed`, `attackDamage`, `armor`, `followRange` | 각각 `1..2048`, `0..1`, `0..512`, `0..30`, `1..128` |
| `movement` | `enabled`, `wander`, `visit`, `navigationSpeed`, `maxCommandDistance`, `maxVisitDistance` | 속도 `0..2`, 거리 각각 `0..128`. 이동이 꺼지면 방황·방문도 불가. 방문은 같은 차원·온라인 플레이어만 대상으로 경로 탐색한다. 이동은 텔레포트하지 않으며 도착을 보장하지 않는다. |
| `combat` | `enabled`, `damageable`, `retaliate`, `raidControl` | 모두 boolean. `retaliate`와 `raidControl`은 전투 enabled일 때만 true 가능. 자동 근처 플레이어 공격은 없다. |
| `placement` | `onEncounter`, `spawnRadius` | boolean과 `0..16`. `onEncounter`가 true인 경우에만 실제 Interaction/Encounter commit 뒤 플레이어 주변의 블록·충돌이 허용되는 위치를 찾는다. 실패해도 기존 대화 전달은 유지한다. |
| root | `interactionRange` | 우클릭 시작·비동기 완료 재검증 거리 `1..16` |

표의 필수 필드는 명시적으로 필요하다. `appearance.model`만 생략할 수 있으며 기존 JSON은 `classic`으로 읽는다. 이 문서는 실제 신별 수치나 정사, 기술을 정하지 않는다. `textureVariant`는 순수 외형 번호이며 클라이언트 entity metadata에는 외형 번호와 slim 여부만 추가한다. God ID, 신 이름, 숨은 로어를 보내지 않는다. 외형 자체로 정체를 추측할 수 있다는 연출은 콘텐츠 작성자의 책임이다. 일반 이름표를 렌더하지 않으며 플레이어별 식별명은 기존 `GodIdentityService`/대화 UI 계약을 따른다. 플레이어 모델 전환으로 기존 zombie UV 기반 커스텀 이미지는 64×64 플레이어 스킨 규격으로 교체해야 한다.

## 출현과 대화

등록 타입은 `mythictrpg:god_avatar` 한 가지 `PathfinderMob`이다. `GodAvatarRegistryState`가 God ID마다 entity UUID 하나를 세계 SavedData에 기록해, 청크가 언로드되어도 중복 spawn을 막는다. 엔티티 NBT는 canonical God ID와 이동·방문 명령을 저장한다. `/summon mythictrpg:god_avatar`만으로 생성한 미등록 entity는 대화·전투 권한이 없다. 운영 출현은 `placement.onEncounter`를 명시한 신이 실제 `InteractionStartService`의 Encounter commit을 마쳤을 때만 시도한다. 기존 조건·잠금·참가자 권한은 원래 director/validator가 판정하며 아바타가 우회하지 않는다.

우클릭은 근접·생존·등록 상태·방 엔진·provider를 확인하고 기존 `EXPLICIT_GOD_CALL`/`PLAYER_EXPLICIT_POLICY`를 `InteractionOrchestrator`로 보낸다. AI 응답이 늦으면 같은 플레이어와 entity 인스턴스, 거리·차원·생존, God/아바타 정의 generation을 다시 확인한 뒤에만 기존 `InteractionStartService`가 대화와 방을 확정한다. 현재 방에 이미 같은 신이 있으면 중복 방 대신 기존 방의 입력 방법을 알리며, 비밀 방이면 선택한다. Provider/방 엔진이 없으면 실제 대화를 시작한 척하지 않고 실패를 알린다. 기본 provider는 `mythictrpg.ai_test.enabled=false`이면 unavailable이므로 실제 환경 설정을 확인해야 한다. HUD, 이름 식별, 방 기록 및 공개/비밀 청중은 기존 시스템이 담당한다.

## OP 명령과 레이드 경계

- `/mythavatar summon <전체 신 ID>`: OP 위치에 실체 생성 시도. 현재 God/아바타 정의, loaded chunk, 충돌, God당 단일 실체 규칙이 모두 통과해야 한다.
- `/mythavatar id <entity>`: OP에게만 등록 God ID·entity UUID 표시.
- `/mythavatar despawn <entity>`: 실체만 제거. God Definition, 관계·기억·퀘스트 기록은 삭제하지 않는다.
- `/mythavatar move <entity> <position>` 및 `/mythavatar visit <entity> <player>`: 작성형 정책·범위·경로를 확인해 이동 명령을 시작한다. 성공 응답은 도착 확정이 아니다.

`GodAvatarService.spawn`, `acquireRaidLease`, `spawnForRaid`, `targetForRaid(..., attemptId)`, `clearRaidTarget`, `despawn`, `releaseRaidLease`는 서버 측 연동 API다. raid lease는 attempt UUID가 소유하고 SavedData에 남는다. 기존 월드 실체가 있으면 lease 획득이 실패하며, 다른 attempt가 같은 신을 동시에 복제하거나 이동시킬 수 없다. `RaidRuntime`은 완료·중단·재시작 복구 때 lease를 정리하며, 미확인 실체가 있으면 강제로 해제하지 않고 `CLEANUP_PENDING`을 유지한다. 아바타 단독으로 플레이어를 자동 공격하거나 엔티티 사망을 신의 세계관상 죽음/Story fact로 확정하지 않는다. 레이드 결과·보상은 작성형 레이드 정의와 게임 권한 경계가 담당한다.

## 검증 상태와 남은 확인

AI 방문은 별도 [등록 건축물 방문 계약](GOD_HOME_VISITS.md)으로 연결했다. 대화/자율 판단을 모두 지원하되 작성형 정책이 없으면 OFF이며, 기존 실체만 보행한다. OP `visit`의 플레이어 추적과 혼동하지 않는다.

소스에는 단일 실체·NBT·정의 파서·OP 명령·조우 연결·우클릭 비동기 재검증·전투 타깃 권한을 구현했다. `GodAvatarGameTests`의 독립 namespace는 `mythictrpg_god_avatar`이며 합성 아바타 정의로 중복 방지, NBT, raid lease, 조우 출현을 검사한다. 실제 클라이언트 리소스팩 표시, 서버 재시작 뒤 청크 로드/이동, 실제 LLM·다인 방 상호작용과 보스 전투는 인게임 검증 대상이다. 테스트/JAR 배포가 수행되지 않았다면 구현과 별개로 미검증이다.
