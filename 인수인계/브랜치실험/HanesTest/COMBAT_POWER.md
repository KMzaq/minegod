# 전투력 공급자 — 획득 보상 증거 기반 캐치업

> 2026-10-02 후속: 영구 가호는 실제 활성 효과와 별개로 보유 기록을 읽으며 우유로 제거해도 점수가 유지된다. 같은 효과는 보유/활성 최고 단계 한 번만 반영한다. 사용자 승인 효과별 점수 초안과 기존 정책 override 우선순위는 [가호 전투력 가이드](../../../mythictrpg-main/docs/BLESSING_POWER_GUIDE.md)를 따른다. 아래의 ‘출처 DB 없음·제거 시 효과 점수 제외’는 임시 효과에만 계속 적용된다. 전체 장비/권장치 정책 부재와 미배포 상태는 그대로다.

2026-09-29, `HanesTest` 소스 구현. 공용 인수인계의 「전투력 보정 상태와 반드시 지켜야 할 후속 기준」 후속이다. **운영 점수표·계수·콘텐츠는 작성하지 않았다. 정책이 없는 현재 기본값은 `UNAVAILABLE`, 보정 0이다.**

## 소유권과 입력

`power/CombatPowerRuntime` 별도 EventSubscriber가 기존 `CombatPowerService`에 실제 공급자를 연결한다. AI, 대화 응답, 인벤토리 임의 아이템, 현재 착용 장비는 전투력 증거를 만들 수 없다. 실제 게임 상태 읽기와 보상 원장 갱신은 서버 스레드에서만 한다. 기존 GeneratedQuest의 0~1 clamp와 템플릿 maximumTier는 그대로 유지한다. 플레이어 간 순위, 신의 관심 대상, 메인 퀘스트 수주자 선정에는 쓰지 않는다.

| 입력 | 실제 원본과 범위 |
|---|---|
| 받은 보상 장비 | `RewardExecutionService.grantItem`에서 실제 인벤토리 증가 또는 생성된 drop entity 확인 후, 지급 전 복사한 ItemStack의 변형을 저장한다. 반복 claim은 기존 claim 소유권 그대로이며 중복 변형은 한 번만 저장한다. |
| 현재 유효한 가호/효과 | `ServerPlayer.getActiveEffects()`의 실제 effect ID와 amplifier. 종료·제거된 효과는 빠진다. 현재 게임에 가호의 별도 출처 DB가 없어 **포션/신 가호를 구별하거나 sourceGod를 추측하지 않는다.** |
| 실제 영구 보정 | `AttributeMap.save()` → `AttributeInstance.save()`의 permanent modifier에서 실제 현재 MobEffect 템플릿과 정확히 일치하는 임시 효과 modifier를 뺀다. attribute ID + modifier ID + operation + 실제 amount. `getValue()`/`getModifiers()`는 착용 장비 transient까지 섞으므로 쓰지 않는다. |
| 세계 권장치 | `MythicWorldState.questProgress()`와 작성된 진행 구간 표. 개인 진행/FTB 팀 진행으로 대체하지 않는다. 없는 게임 트랙 값은 게임 원본 계약의 시작값 0이다. |

확인한 게임 코드에서 실제 아이템 보상의 `getInventory().add`/`drop` 경로는 공용 RewardExecutionService다(테스트 fixture 제외). 외부 모드 아이템, 임의 명령 지급, 채집·제작·거래 전달을 이 프로젝트의 보상 증거로 소급하지 않는다. 향후 별도 보상 지급 경로가 생기면 같은 **실제 지급 후** 어댑터 계약을 먼저 연결해야 한다.

인벤토리 전후 비교는 이 공용 지급 함수 안에서 지급 성공을 확인하는 데만 사용한다. 인벤토리 내용 자체를 보상 원장으로 가져오지 않는다. 크리에이티브 overflow가 입력 stack만 소모해도 실제 수신으로 오인하지 않는다. 원장 실패/용량 한도는 전투력만 불가로 만들고 실제 보상·호감도·퀘스트·claim을 차단하거나 롤백하지 않는다.

## 정책 JSON 계약

데이터팩의 `data/<namespace>/mythictrpg/combat_power/<path>.json`에서 최종 적용 리소스가 **정확히 1개**여야 한다. 없음·여러 개·오류이면 기존 정책을 계속 쓰지 않고 즉시 불가로 전환한다. 기본 정책 파일은 생성하지 않는다. 모든 필드는 필수이며 알 수 없는 필드도 거절한다.

| 필드 | 명시적으로 작성할 값 |
|---|---|
| `schemaVersion` | 현재 1 |
| `aggregation` | `MAX_ALL` 또는 `SUM_SLOT_BEST` |
| `basePower`, `equipmentCoefficient`, `effectCoefficient`, `permanentCoefficient` | 유한한 0 이상의 수. 0도 의도적으로 작성해야 한다. |
| `permanentMode` | `ACTUAL_PERMANENT_MODIFIERS_ONLY` |
| `lowRatioExclusive` | 0 초과 1 미만. `actual < recommended × 값`일 때만 보정 1, 등호는 보정 0. |
| `items` | `itemId`, **필수** `components` 객체(기본형도 `{}`), `classification`, `slot`, `score`의 배열 |
| `effects` | `effectId`, `amplifier`(0..255), `score`의 배열 |
| `permanentModifiers` | `attributeId`, `modifierId`, `operation`, `coefficient`의 배열 |
| `recommendations` | `progress` 객체와 양수 `recommendedPower`의 배열 |

아이템 classification은 `GEAR` 또는 `IGNORE`. GEAR는 작성된 slot별 최고 점수를 먼저 뽑고, 정책이 선택한 MAX_ALL(전체 최고 하나) 또는 SUM_SLOT_BEST(슬롯별 최고 합)로 집계한다. slot은 `[a-z0-9_]{1,32}`이며 슬롯 체계 자체도 작성자가 선택한다. IGNORE는 반드시 `slot: "none"`, `score: 0`이다. 음식·화폐성 아이템처럼 전투와 무관한 보상도 명시적으로 분류한다. 수량은 전투력에 중복 가산하지 않는다.

components는 실제 보상 `NpcRewardEntry`와 같은 Minecraft ItemStack component codec으로 정규화한다. 리소스팩 모델 번호, 이름, attribute modifier 등이 다르면 별도 변형이다. 정규화 후 같은 변형을 두 번 정의하면 불가다. 와일드카드나 item ID만으로 모든 변형에 점수를 부여하지 않는다.

effect score와 modifier coefficient는 유한한 0 이상의 값이다. permanent amount는 실제 게임값이므로 음수일 수도 있다. modifier operation은 `ADD_VALUE`, `ADD_MULTIPLIED_BASE`, `ADD_MULTIPLIED_TOTAL`. 이 정책은 실제 attribute 연산을 다시 실행하지 않고 작성된 전투력 계수와 실제 amount를 곱한다. 무관한 효과/영구 modifier를 배제하려면 정확한 키에 계수·점수 0을 명시해야 한다. 실제 활성 입력 중 미정의 키는 불가이며, 전체 계수가 0이어도 누락을 숨기지 않는다.

주의: Minecraft의 strength 등은 만료되는 MobEffect인데도 내부적으로 `addPermanentModifier`를 사용한다. 공급자는 현재 효과의 `createModifiers(amplifier)`가 제공하는 정확한 키·amount와 일치하는 항을 영구 보정에서 제외해 효과 점수에만 한 번 반영한다. 동일 키 중복 또는 실제 amount 불일치는 출처를 추측하지 않고 불가로 처리한다. 신의 identity나 가호 출처를 추정하는 기능은 아니다.

`progress`는 `"namespace:track": {"minimum": 정수, "maximum": 정수}` 형태이며 양끝 포함, 0 ≤ minimum ≤ maximum. 각 행은 작성한 모든 트랙의 범위를 만족해야 한다. 정확히 한 행만 일치해야 하며 빈 표·미일치·중복 일치는 불가다. 숫자 문자열은 허용하지 않는다.

계산식은 다음과 같다. 각 항의 계수와 모든 점수는 위 정책이 반드시 제공한다.

`actual = basePower + gearAggregate × equipmentCoefficient + Σ(activeEffectScore) × effectCoefficient + Σ(actualPermanentAmount × ruleCoefficient) × permanentCoefficient`

계산 결과가 유한하지 않거나 음수이면 불가다. `UNAVAILABLE`은 실제 힘이 0이라는 뜻이 아니며 API의 숫자 0은 사용할 수 없는 값의 placeholder다. 신과의 사회적 힘 비교·인지 권한은 이 모듈에서 만들지 않으며 여전히 unknown이다.

## 영속성과 불완전 이력

월드 `data/mythictrpg_reward_power_v1.dat`에 플레이어 UUID별 수신 변형 전체와 coverage를 저장한다. 현재 최고 하나만 남기지 않으므로 정책의 점수/슬롯이 바뀌어도 **모든 과거 변형**을 다시 평가한다. 착용 해제·버림·교체·내구도 변경은 지급 당시 원본을 낮추지 않는다. 미정의 과거 변형을 삭제해 평가를 통과시키지 않는다.

첫 로그인 이벤트에서 기존 Mythic profile이 없고 원장과 세션 fence가 준비된 경우에만 새 플레이어의 COMPLETE coverage를 시작한다. 기존 profile이 있거나 일반 `obtainedItems`만 있는 경우, 기존 claim이 일부 남아 있어도 전체 보상 변형을 복원할 수 없으므로 `LEGACY_HISTORY_UNKNOWN`이다. 재로그인이나 새 보상을 받았다는 이유로 이를 COMPLETE로 승격하지 않는다. 아직 과거 이력을 검증해 이관하는 import/reset 명령은 없다.

기술적 저장 한도는 플레이어 4096명, 플레이어당 변형 512개, 월드 총 변형 8192개이며 변형 snapshot 길이도 제한한다. 초과 시 과거 최고 증거를 내보내거나 삭제하지 않고 해당 coverage를 불가로 유지한다. 이 파일은 실제 게임 증거이며 기록 아카이브 quota의 rumor/reputation 대상이 아니다.

아이템 저장, claim 저장, 획득 증거 SavedData는 하나의 원자적 트랜잭션이 아니다. 이를 숨기지 않도록 `data/mythictrpg_reward_power_session.json`에 세션 fence를 둔다.

- 시작: 이전 정상 종료 marker와 SavedData의 cleanSession UUID가 일치하는지 확인하고 새 세션의 unclean marker를 먼저 저장한다. 이후에만 공급자를 활성화한다.
- 종료: 정상 ServerStopping을 거쳤을 때만 마지막 SavedData를 큐에 넣고, NeoForge 실제 IO worker 완료 뒤 저장 파일의 세션 UUID를 확인해 clean marker를 쓴다. tick 중 디스크 대기는 하지 않으며 정상 종료 시 최대 10초만 확인한다.
- 종료 확인 실패, crash 경로, 누락·손상·서로 다른 UUID의 marker, fence IO 불가: 이전 COMPLETE 이력을 추정 복구하지 않고 coverage를 불가로 바꾼다. 기존 변형 증거와 실제 보상은 보존한다.

이는 정상 종료/불완전 종료를 구분하는 보수적 보호이며 **분산 트랜잭션이나 모든 파일의 일관된 백업 증명은 아니다.** marker와 원장만 같은 과거 시점으로 부분 복구하고 player inventory/claim을 다른 시점으로 복구하면 두 파일의 UUID 일치만으로 실제 과거 지급 전체를 증명할 수 없다. 월드 백업을 부분 섞어 복원한 뒤 이력을 완전하다고 취급하면 안 된다. 그런 복구를 자동 판정·교정하는 운영 도구는 미지원이다. 하드웨어/파일시스템 차원의 전원 손실 내구성까지 보장하지 않는다.

## 실제 지원하지 않는 게임 상태

현재 프로젝트에서 플레이어용 영구 능력치 성장 지급 시스템 자체는 발견되지 않았다. 따라서 이 공급자는 실제 존재하는 Minecraft permanent modifier만 읽고, 제목·퀘스트 이력·문장으로 가상 영구 능력치를 더하지 않는다. attribute base value 변경의 기원/영구성을 증명하는 별도 시스템도 없어 이를 전투력 성장으로 추정하지 않는다. 향후 실제 성장 원본이 추가되면 입력 계약과 tests를 함께 확장해야 한다.

## 진단과 검증

`/mythpower`는 본인의 실제 공급자 status, actual/recommended, catchUp, evidence를 표시한다. `/mythpower <player>`는 권한 2의 온라인 플레이어 진단이다. 수치 수정·coverage 초기화·AI 숫자 입력 명령은 없다.

- `CombatPowerPolicyTest` / Gradle `combatPowerPolicyTest`: 필수 정책 값, 집계 두 방식, strict threshold, 구간 누락/중복, unknown 입력, overflow, NBT round trip, 플레이어 분리, immutable snapshot, 불완전 이력 승격 금지, 한도 초과 시 과거 증거 보존.
- `CombatPowerGameTests`, namespace `mythictrpg_power`: 실제 RewardClaim 지급/중복→공급자, 로그인 coverage, offhand 합산 수신, 착용/제거와 transient modifier 제외, 활성 효과/실제 permanent modifier 분리, 동일 item ID의 미정의 component 변형, 정책 변경 재점수/누락 거절, native SavedData 완료 및 세션 marker 불일치.
- 2026-09-29 루트 통합 검증에서 `combatPowerPolicyTest` **38개 assertion 통과**, `mythictrpg_power` **2/2 GameTest 통과**. 다른 관련 3개와 합계 5/5 통과 로그는 `mythictrpg-main/build/power-offer-recovery-recheck-20260929/logs/latest.log`다. 최초 실행에서 드러난 vanilla 효과의 permanent modifier 중복 입력을 수정한 뒤 재검증한 결과다.
- 소스 `diff --check` 확인. **실제 프로세스 종료→재시작 end-to-end 검증은 미실시**다. 현재 검증은 실제 NeoForge SavedData IO와 세션 marker 일치/불일치 검사이며, 프로세스 재시작 검증을 대신하지 않는다. 운영 서버 배포·실제 플레이어/리소스팩 화면·운영 밸런스 검증도 수행하지 않았다.
