# AI 즉석 SIDE 퀘스트 가이드

> HanesTest 개발본 1.0.18: 신규 수주 보상 스냅샷과 목표 달성 후 지급 재시도를 추가했다. [보상 동결·복구 및 저장 호환](../../인수인계/브랜치실험/HanesTest/GENERATED_QUEST_RECOVERY.md)을 참고한다. 다인 즉석 생성까지 추가한 것은 아니다.

> 2026-09-20 범위 구분: [참여 유형](QUEST_PARTICIPATION_GUIDE.md)의 4종 설정은 등록된 FTB 바인딩/`quest_offer` 경로에 추가됐다. 이 문서의 `GeneratedQuestTemplate`/`generated_quest_offer` 즉석 생성 경로는 기존 개인형 그대로이며 다인 생성 지원으로 해석하지 않는다.

## 책임과 안전 경계

AI는 현재 대화에 맞는 등록 템플릿과 제목·요약만 제안한다. MythicTRPG 서버가 월드 진행도,
플레이어의 기존 활성 의뢰, 템플릿 쿨다운, 목표 레지스트리, 신의 보상표 소유권과 보상 단계를
검사한 뒤 생성한다. 생성된 퀘스트는 항상 `SIDE`이며 메인 퀘스트 수주권이나 신의 주목 대상을
변경하지 않는다.

FTB Quests에는 고유 퀘스트와 `custom` 진행 태스크를 런타임에 만들어 표시하지만, FTB 완료
이벤트나 FTB 보상은 실제 완료의 원본이 아니다. `GeneratedQuestState`의 관찰 진행도가 목표에
도달해야 `RewardClaimService`가 고유 퀘스트 instance ID를 근거로 대상 플레이어에게 지급권을 만들고 기존 단계의 자동 보상을 처리한다. 게임 1.0.8부터 등록형 퀘스트와 동일한 주시 보상 경로를 사용하지만 즉석 템플릿에 선택 보상 정의를 새로 추가한 것은 아니다. 실제 보상/콘텐츠를 자동 추가하지 않는다.

> 1.0.8의 `block_broken` 보상 목표는 성공 제거에서 출처를 검사한 `ELIGIBLE_BLOCK_MINED`를 사용한다. 직접 설치 재파괴·확인된 생성기는 제외하며 기존 비보상 관찰 소비자는 유지한다. 수주/목표 완료/만료/완료는 원장 opt-in 시 중요 전환으로 기록한다. [지원 범위와 미검증](../../docs/MEMORY_POLICY_IMPLEMENTATION_20260920.md)을 함께 확인한다.

## 템플릿

경로는 `data/<namespace>/mythictrpg/generated_quest_templates/*.json`이다.

```json
{
  "schemaVersion": 1,
  "godId": "mythictrpg:fortuna",
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

## 전투력 보정 공급자와 활성화 조건

HanesTest 개발본은 `power/CombatPowerRuntime`이 서버 시작 시 게임 소유 `CombatPowerProvider`를
기존 `CombatPowerService`에 연결한다. 다만 획득 이력 coverage와 저장 세션 fence가 유효하고,
명시적으로 작성된 정책 JSON과 모든 실제 입력이 검증될 때만 `AVAILABLE`이다.
**운영 점수표·계수·권장 구간은 작성하지 않았으므로 현재 기본 동작은 `UNAVAILABLE`, 보정 0이다.**
실제 지급 단계는 기존 0~1 보정 제한과 템플릿의 `maximumTier`를 넘지 않는다.

실제 공급자는 현재 착용 장비 대신 다음 게임 원본을 사용한다.

- 공용 보상 실행기가 실제 지급한 장비 변형 전체의 영속 원장. 정책에서 전체 최고 또는 슬롯별 최고 합을 명시한다.
- 현재 실제 효과와 영구 attribute modifier. 일시 효과의 내부 modifier는 영구 항에서 제외해 중복 계산하지 않는다.
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
