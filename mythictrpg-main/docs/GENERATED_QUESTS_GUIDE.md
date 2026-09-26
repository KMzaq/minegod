# AI 즉석 SIDE 퀘스트 가이드

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

## 전투력 보정 확장 계약

`CombatPowerService`는 현재 `UNAVAILABLE` 상태를 반환하므로 보상 보정을 적용하지 않는다.
외부 전투력 시스템이 완성되면 `CombatPowerProvider`를 설치한다. 서버는 제공자가 +1보다 큰
값을 반환해도 거절하며, 실제 지급 단계는 항상 템플릿의 `maximumTier` 이하로 제한한다.

향후 제공자의 실제 전투력은 현재 착용 장비가 아니라 다음 자료를 사용해야 한다.

- 플레이어가 지금까지 받은 보상 중 가장 좋은 장비
- 현재 유효한 가호 및 기타 영구 전투 보정
- 월드 진행도별 권장 전투력 데이터
- 필요하면 같은 진행 구간 플레이어 분포. 단, 특정 플레이어 선발에는 사용하지 않는다.

권장 전투력보다 명확히 낮은 플레이어만 +1단계 보정 대상으로 평가한다. 아직 보상 장비 원장과
월드 진행도별 권장 전투력 표가 없으므로 현재 장비만 보고 임시 점수를 만들지 않는다.

## FTB Quests 설치와 표시

제공된 `ftbquests-pack`에는 고정 ID `1D1A4D1C00000001`의 `즉석 의뢰` 챕터가 있다. 서버
`config/ftbquests/quests/`에 팩을 복사해야 런타임 미러가 생성된다. 팩이 없더라도 MythicTRPG
퀘스트는 채팅 안내와 SavedData로 진행되지만 FTB 표시는 생략되고 오류가 기록된다.

미러 퀘스트에는 FTB 보상을 넣지 않는다. 완료 보상은 MythicTRPG만 지급한다. FTB Teams의
진행 데이터는 팀 단위이므로 같은 팀의 다른 플레이어에게 표시 상태가 공유될 수 있다. 개인별
표시가 필요하면 플레이어별 별도 FTB 팀 정책을 사용한다. 실제 보상 대상과 권위 상태는 팀과
무관하게 생성 당시 플레이어 UUID로 고정된다.
