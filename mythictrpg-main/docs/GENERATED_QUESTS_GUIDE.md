# AI 즉석 SIDE 퀘스트 가이드

> 2026-10-02 접촉 확인: 신규 즉석 의뢰의 기본 완료 방식은 `PLAYER_RETURN_TO_NPC`다. 목표 달성은 **확인 대기**이며 실제 신 접촉 확인 뒤에만 보상을 처리한다. 아래의 저장 v3·수주 위치·FTB 표시 계약을 따른다. 소스 구현과 운영 배포/실제 대화 검증은 구분한다.

> HanesTest 개발본 1.0.18: 신규 수주 보상 스냅샷과 목표 달성 후 지급 재시도를 추가했다. [보상 동결·복구 및 저장 호환](../../인수인계/브랜치실험/HanesTest/GENERATED_QUEST_RECOVERY.md)을 참고한다. 다인 즉석 생성까지 추가한 것은 아니다.

> 2026-09-20 범위 구분: [참여 유형](QUEST_PARTICIPATION_GUIDE.md)의 4종 설정은 등록된 FTB 바인딩/`quest_offer` 경로에 추가됐다. 이 문서의 `GeneratedQuestTemplate`/`generated_quest_offer` 즉석 생성 경로는 기존 개인형 그대로이며 다인 생성 지원으로 해석하지 않는다.

## 책임과 안전 경계

AI는 현재 대화에 맞는 등록 템플릿과 제목·요약만 제안한다. MythicTRPG 서버가 월드 진행도,
플레이어의 기존 활성 의뢰, 템플릿 쿨다운, 목표 레지스트리, 신의 보상표 소유권과 보상 단계를
검사한 뒤 생성한다. 생성된 퀘스트는 항상 `SIDE`이며 메인 퀘스트 수주권이나 신의 주목 대상을
변경하지 않는다.

FTB Quests에는 고유 퀘스트와 `custom` 진행 태스크를 런타임에 만들어 표시하지만, FTB 완료
이벤트나 FTB 보상은 실제 완료의 원본이 아니다. `GeneratedQuestState`의 관찰 진행도가 목표에
도달하면 확인 대기로 저장한다. 게임이 발급한 현재 신 접촉으로 승인이 난 뒤 `RewardClaimService`가 고유 퀘스트 instance ID를 근거로 대상 플레이어에게 지급권을 만들고 기존 단계의 자동 보상을 처리한다. 게임 1.0.8부터 등록형 퀘스트와 동일한 주시 보상 경로를 사용하지만 즉석 템플릿에 선택 보상 정의를 새로 추가한 것은 아니다. 실제 보상/콘텐츠를 자동 추가하지 않는다.

> 1.0.8의 `block_broken` 보상 목표는 성공 제거에서 출처를 검사한 `ELIGIBLE_BLOCK_MINED`를 사용한다. 직접 설치 재파괴·확인된 생성기는 제외하며 기존 비보상 관찰 소비자는 유지한다. 수주/목표 완료/만료/완료는 원장 opt-in 시 중요 전환으로 기록한다. [지원 범위와 미검증](../../docs/MEMORY_POLICY_IMPLEMENTATION_20260920.md)을 함께 확인한다.

## 템플릿

경로는 `data/<namespace>/mythictrpg/generated_quest_templates/*.json`이다.

```json
{
  "schemaVersion": 1,
  "godId": "mythictrpg:fortuna",
  "completionMode": "PLAYER_RETURN_TO_NPC",
  "objective": {
    "observation": "mythictrpg:entity_killed",
    "subject": "minecraft:zombie",
    "count": 8
  },
  "worldProgress": {
    "trackId": "mythictrpg:progress_fortuna",
    "minimum": 0,
    "maximum": 35
  },
  "reward": {
    "tableId": "mythictrpg:fortuna",
    "baseTier": 2,
    "maximumTier": 3,
    "catchUpMaximumBonus": 1
  },
  "cooldownTicks": 24000,
  "expiresAfterTicks": 72000
}
```

허용 목표 관찰은 다음 다섯 가지다.

- `mythictrpg:entity_killed`
- `mythictrpg:block_broken`
- `mythictrpg:mature_crop_harvested`
- `mythictrpg:animal_fed`
- `mythictrpg:animal_bred`

횟수는 1~256, 월드 진행도는 0~100, 보정 보상은 0~1단계다. 쿨다운은 최대 2,592,000틱,
만료 시간은 1,200~2,592,000틱이다. 대상은 해당 관찰에 맞는 실제 등록 블록 또는 엔티티여야
한다. 템플릿에 메인 역할, 자유 명령, 블록 변경, 임의 아이템 보상을 넣는 필드는 없다.

## 목표 달성과 신의 확인

`completionMode` 생략은 `PLAYER_RETURN_TO_NPC`다. 작성자가 명시한 `AUTO`만 목표 달성 시
자동 승인한다. `NPC_VISIT_PLAYER`도 지정할 수 있지만 실제 조우 접촉을 기다리며, 목표를
채웠다는 이유로 NPC를 소환·이동시키거나 대화방을 자동 생성하지 않는다.

신규 수주 때 서버가 플레이어의 실제 차원·좌표를 `origin`으로 저장한다. 선택적
`returnLocation`은 콘텐츠 작성자가 지정하는 확인 장소이며 형식은 다음과 같다.

```json
"returnLocation": {
  "dimension": "minecraft:overworld",
  "x": 100,
  "y": 64,
  "z": 200,
  "radius": 8
}
```

이 좌표는 형식 예시이며 운영 장소가 아니다. 반경은 1~64블록이고 `AUTO`에는 지정할 수 없다.
위치와 완료 정책은 수주 인스턴스에 보존한다. 미승인 중 템플릿의 완료 정책/지정 장소를
변경하거나 템플릿을 제거하면 새 승인을 보류한다. 원래 정책을 복원하거나 별도 이관을 설계해야 한다.

실제 NPC를 찾아가 접촉하거나, 주시가 없는 경우 수주 위치/지정 장소에서 해당 신을 만나거나
정상 재조우가 발생했을 때 공통 `QuestContactService`가 확인 가능 여부를 검사한다. 좌표에
서 있거나 명령으로 대화방만 연 사실은 접촉 증명이 아니다. 원격 확인은 `/mythquest call <godId>`의
실제 호출 순간에 개인 주시 권한·현재 관찰 중·차폐 통과·다른 대화/전투/생활활동으로 바쁘지 않음을
모두 재검증한다. AI가 신 이름이나 성공 대사를 출력한 사실로 승인하지 않는다.

진행 상태는 **목표 수행 → 목표 달성/확인 대기 → 신 확인/지급 대기 → 지급 처리 완료**다.
목표를 기한 안에 채우면 기존처럼 달성 상태를 보존하고, 이후 기한이 지나도 확인 대기를
취소하지 않는다. 미달성 의뢰는 기한에 만료된다. 이미 확인받은 뒤 지급이 실패하면 승인 상태를
저장하고 tick/재접속에서 기존 claim으로 재시도한다. 새 접촉이나 바뀐 보상표를 요구하지 않으며
같은 instance ID의 지급 영수증이 중복 보상을 막는다.

기존 플레이어 퀘스트 Context에도 `OBJECTIVES_IN_PROGRESS`, `AWAITING_NPC_CONFIRMATION`,
`CONFIRMED_REWARD_PENDING`을 전달한다. 실제 대면/조우에서 확인한 최종 결과는 해당 방·신·플레이어에만
최대 1,200틱 보관하며 다음 AI 턴에 `CONFIRMED_REWARD_PENDING` 또는 `COMPLETED`로 구분한다.
다른 방/다른 신에 결과를 옮기거나 이 결과만으로 LLM을 새로 호출하지 않는다. 방을 만들지 않는 원격
확인의 결과는 플레이어에게 직접 전달하며, 임의 방을 골라 결과 이력을 넣지 않는다.

`mythictrpg_generated_quests` 저장 버전은 **3**이며 v1/v2도 읽는다. 예전 저장에 없는 수주 위치나
지정 장소는 추정하지 않는다. 예전 미달성 실행은 기본 NPC 확인으로 이어지고, 예전 목표 달성
실행은 당시 자동 지급 계약상 이미 지급 재시도 단계였으므로 명시적 legacy `AUTO` 승인으로
보존한다. 이를 과거 NPC를 만났다는 이력으로 만들지 않는다. v3의 승인 필드 누락/미달성 승인 등
손상 데이터는 원문 보존·읽기 전용으로 거절한다. 구 JAR로 롤백하려면 업데이트 전 월드 백업이 필요하다.

개발 회귀는 `mythictrpg_generated_rewards` GameTest namespace에서 보상 동결/재시도, 상태·위치
저장/이관, 실제 아바타 접촉, 목표 이벤트→FTB 확인 대기, 명시 AUTO와 만료를 검사한다.
실제 두 프로세스 재시작·사용자 클라이언트 조작·LLM 대화 및 운영 배포는 별도 검증이다.

## 전투력 보정 공급자와 활성화 조건

HanesTest 개발본은 `power/CombatPowerRuntime`이 서버 시작 시 게임 소유 `CombatPowerProvider`를
기존 `CombatPowerService`에 연결한다. 다만 획득 이력 coverage와 저장 세션 fence가 유효하고,
명시적으로 작성된 정책 JSON과 모든 실제 입력이 검증될 때만 `AVAILABLE`이다.
**전체 장비 점수표·계수·권장 구간은 작성하지 않았으므로 현재 전체 기본 동작은 `UNAVAILABLE`, 보정 0이다.**
2026-10-02에는 사용자가 승인한 [가호 효과별 기본 점수 초안](BLESSING_POWER_GUIDE.md)을 별도로 추가했다.
`/mythpower`의 영구 가호 항만으로 전체 전투력이 사용 가능해지는 것은 아니다.
실제 지급 단계는 기존 0~1 보정 제한과 템플릿의 `maximumTier`를 넘지 않는다.

실제 공급자는 현재 착용 장비 대신 다음 게임 원본을 사용한다.

- 공용 보상 실행기가 실제 지급한 장비 변형 전체의 영속 원장. 정책에서 전체 최고 또는 슬롯별 최고 합을 명시한다.
- 보유 영구가호와 현재 실제 효과의 효과별 최고 단계, 영구 attribute modifier. 우유로 영구가호 효과를 지워도 보유 점수는 유지하며 효과의 내부 modifier는 영구 항에서 제외해 중복 계산하지 않는다.
- `MythicWorldState`의 세계 진행도와 작성된 권장 전투력 구간 표.

장비를 벗거나 버려도 과거 지급 증거는 낮아지지 않는다. 모든 계수·점수·집계 방식·구간·뒤처짐
임계치는 데이터팩의 `data/<namespace>/mythictrpg/combat_power/<path>.json`에 필수로 작성한다.
기존 플레이어의 불완전한 `obtainedItems` 이력, 미정의 과거 변형, 정책 오류, 불완전 종료 흔적은
점수를 추정하지 않고 `UNAVAILABLE`로 처리한다. `/mythpower`는 본인, `/mythpower <player>`는
권한 2의 읽기 전용 진단이며 AI 수치 변경 권한은 없다. 플레이어 선발이나 신과의 힘 비교에는 쓰지 않는다.

2026-09-29 정책 테스트 38개와 전투력 GameTest 2개(실제 지급·착용 변경·효과/영구 보정 분리·native 저장)가
통과했다. 실제 프로세스를 종료·재시작하는 end-to-end 검증과 운영 배포는 수행하지 않았다.
정책의 전체 계약, 미지원 영구 성장 원본, 세션 fence 및 부분 백업 복구 한계는
[전투력 공급자 문서](../../인수인계/브랜치실험/HanesTest/COMBAT_POWER.md)를 따른다.

## FTB Quests 설치와 표시

제공된 `ftbquests-pack`에는 고정 ID `1D1A4D1C00000001`의 `즉석 의뢰` 챕터가 있다. 서버
`config/ftbquests/quests/`에 팩을 복사해야 런타임 미러가 생성된다. 팩이 없더라도 MythicTRPG
퀘스트는 채팅 안내와 SavedData로 진행되지만 FTB 표시는 생략되고 오류가 기록된다.

미러 퀘스트에는 FTB 보상을 넣지 않는다. 완료 보상은 MythicTRPG만 지급한다. FTB Teams의
진행 데이터는 팀 단위이므로 같은 팀의 다른 플레이어에게 표시 상태가 공유될 수 있다. 개인별
표시가 필요하면 플레이어별 별도 FTB 팀 정책을 사용한다. 실제 보상 대상과 권위 상태는 팀과
무관하게 생성 당시 플레이어 UUID로 고정된다.

진행 태스크는 목표 횟수 외 마지막 1칸을 서버의 완료 확인에 예약한다. 예를 들어 목표 8회라면
달성 시 8/9와 `목표 달성 · 신의 확인 대기`, 신 확인 후 9/9로 표시한다. 목표 횟수는 제목과
설명에 별도로 표시하며 마지막 칸을 추가 사냥으로 채우지 않는다. 구 미러의 완료 기준은 로그인
복구 시 새 확인 단계로 맞춘다. 이 FTB 표시는 지급 성공 영수증을 대체하지 않는다.
