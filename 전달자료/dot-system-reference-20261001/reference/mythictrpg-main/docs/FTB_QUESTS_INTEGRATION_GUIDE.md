# MythicTRPG × FTB Quests 연동 가이드

> 2026-09-30 소스 변경(미배포): 일반 고정 퀘스트의 authoritative 완료 기록이 있으나 FTB 완료 표시가 누락된 경우, 완료자 로그인에서 표시를 재동기화한다. `QuestCompletionMirrorRecovery` → `restoreCompletedMirror`는 완료/시작 표시와 pin만 복원하며 퀘스트 commit·스토리·공용 진행도·Mythic 보상·FTB 자동 보상을 실행하지 않는다. 기존 task 수치와 reward claim 기록도 변경하지 않는다. 외부 FTB가 잠겼거나 정의가 없으면 다음 로그인에 다시 시도한다. 참여형 퀘스트는 기존 개별 mirror 경로를 유지한다.
>
> 기존 FTB 팀 공유 표시의 한계는 유지된다. 같은 FTB 팀의 다른 최초완료 경쟁자가 무효화 처리되면 공유 표시가 다시 숨겨질 수 있으며, 이 변경은 팀별/개인별 표시 정책을 새로 정의하지 않는다. 다른 팀원까지 모두 정상 복구된다는 의미가 아니다.

검증(2026-09-30): 부모 작업의 직렬 컴파일과 `QuestPlayerAccessGameTests` 개발 GameTest
3/3이 통과했다(368.7ms, Gradle 12초). 외부 adapter 예외/실패 후 재시도, 타인 완료의
무효화, 실제 FTB 잠금 해제 후 로그인 2회, 보상 자동 청구 없음과 퀘스트·월드·보상 원본
불변을 확인했다. 초기 fixture의 final 메서드 override 컴파일 오류는 실제 NBT 설정 API로
고친 뒤 검증했다. 운영 JAR 배포와 실제 클라이언트 화면 확인은 하지 않았다.

> 2026-09-20 개발본(미배포): 선택적 `participation`으로 SOLO/GROUP/COMPETITIVE/RANKING과 개인별 서버 목표·공동 정산을 추가했다. 이 필드가 있는 퀘스트는 FTB를 표시 미러로만 사용한다. [참여 유형·대화 합류·설정 양식](QUEST_PARTICIPATION_GUIDE.md)이 최신 계약이며, 아래 기존 전역 최초 완료/FTB 직접 목표 설명은 이 필드가 없는 바인딩에 적용된다.

## 1. 책임 경계

이 연동은 FTB Quests를 화면 및 목표 추적 도구로 사용하고, MythicTRPG를 게임 진행의 원본으로 사용한다.

| 영역 | 소유 시스템 |
|---|---|
| 퀘스트북 UI, 아이템·처치 등의 목표 표시, 보상 버튼, FTB 완료 내역 | FTB Quests |
| 수주 가능 여부, 수주자, 완료 방식, NPC 상호작용 확인, 전역 진행도, 서버 최초 완료 | MythicTRPG |
| 퀘스트 후보의 설명·조건·보상 의미, 신별 사용 가능 목록 | AI 콘텐츠 레지스트리 |
| 대사와 `quest_offer` 제안 | AI 응답 모드 |
| 즉석 SIDE 퀘스트 상태·판정·보상 | MythicTRPG |
| 즉석 SIDE 퀘스트 화면·진행 막대 | FTB Quests 표시 미러 |
| 퀘스트 직접 보상·선택 claim·비아이템 지급 | MythicTRPG |

AI는 퀘스트를 직접 완료하거나 보상을 지급하지 않는다. AI 응답 모드가 반환한 `quest_offer`는 `QuestProposalGateway`로 전달되고, MythicTRPG가 등록된 ID와 현재 전역 상태를 검증한 뒤에만 수주된다.

## 2. 설치 버전

Minecraft/NeoForge 1.21.1 서버와 클라이언트 양쪽에 다음 파일이 필요하다.

- Architectury API `13.0.11`
- FTB Library `2101.1.35`
- FTB Teams `2101.1.11`
- FTB Quests `2101.1.34`
- 같은 빌드의 MythicTRPG

`mythai_ai_response`는 서버 전용이며 클라이언트에 설치하지 않는다.

현재 준비된 클라이언트 파일은 `server/client-required-mods`에 있다.

## 3. 데이터 위치

한 논리 퀘스트는 서로 다른 세 종류의 데이터가 같은 ID로 연결된다.

1. AI 콘텐츠: `mythai-ai-content-registry/src/main/resources/data/<namespace>/mythai_ai/quest_lists/*.json`
2. Mythic↔FTB 바인딩: `mythictrpg-main/src/main/resources/data/<namespace>/mythictrpg/ftb_quests/*.json`
3. FTB 퀘스트북: 서버의 `config/ftbquests/quests/` 아래 SNBT 파일

재배포 가능한 FTB 원본 팩은 `mythictrpg-main/ftbquests-pack/config/ftbquests/quests/`에 보관한다. 새 서버에는 이 폴더의 내용을 서버 `config/ftbquests/quests/`로 복사한다. 따라서 맵을 새로 만들어도 퀘스트 정의를 다시 월드 폴더에 넣을 필요가 없다. 플레이 결과만 월드 저장 데이터에 남는다.

## 4. 바인딩 JSON 양식

파일 경로에서 계산되는 ID와 `questId`가 같아야 한다. 예를 들어 `data/mythictrpg/mythictrpg/ftb_quests/fortuna_deep_sea_heart.json`은 `mythictrpg:fortuna_deep_sea_heart`를 선언한다.

```json
{
  "schemaVersion": 1,
  "questId": "mythictrpg:fortuna_deep_sea_heart",
  "ftbQuestId": "21F48990D282287B",
  "assignmentQuestId": "5DD74E73298791E8",
  "completionMode": "PLAYER_RETURN_TO_NPC",
  "completionNpcIds": ["mythictrpg:fortuna"],
  "progressTrackId": "mythictrpg:progress_fortuna",
  "progressOnClear": 10,
  "narrativeRole": "MAIN_ENTRY",
  "minimumAffinity": -250,
  "reminder": {
    "unrelatedActivityTicks": 12000,
    "cooldownTicks": 12000,
    "signalId": "mythictrpg:fortuna_deep_sea_heart_reminder",
    "relevantActions": [
      {
        "observation": "mythictrpg:item_first_obtained",
        "subject": "minecraft:heart_of_the_sea"
      }
    ]
  }
}
```

| 필드 | 의미 |
|---|---|
| `questId` | AI 콘텐츠와 게임 로직이 공통으로 사용하는 논리 ID |
| `ftbQuestId` | 플레이어에게 보이는 실제 FTB 퀘스트의 16자리 ID |
| `assignmentQuestId` | 수주한 플레이어에게만 퀘스트를 공개하는 숨김 마커 ID |
| `completionMode` | 완료 확인 방식 |
| `completionNpcIds` | 완료를 확인할 수 있는 신/NPC ID 목록 |
| `progressTrackId` | 완료 시 올릴 전역 진행도 ID |
| `progressOnClear` | 전역 진행도 증가량, `0~100` |
| `narrativeRole` | 선택 사항. `SIDE`(기본), `MAIN_ENTRY`, `MAIN` 중 하나 |
| `minimumAffinity` | 선택 사항. 수주에 필요한 해당 신 호감도 `-1000~1000`, 기본 `-1000` |
| `reminder` | 선택 사항. 생략하면 이 퀘스트는 재촉하지 않는다. |

### 신별 주목 대상과 메인 퀘스트

> **2026-09-20 주시 보상 설계와 구분:** 아래 `GodAttentionState`는 현재 **메인 수주 접근 권한**이다. [후속 기록·주시 정책](../../docs/ACTION_RECORDING_POLICY_20260917.md)의 지속 주시는 메인 완료 자동 효과가 아니라 작성된 퀘스트 보상 ‘OOO의 주시’의 실제 개인 지급으로 획득한다. 메인/사이드·완료자 전체만으로 부여하지 않으며 기존 수주 권한은 보존한다. [보상 가이드](REWARD_SYSTEM_GUIDE.md)의 현재 타입에는 아직 주시가 없고 지급→획득/관측 연결은 미구현이다. 이번 갱신은 문서만이며 바인딩·보상·서버는 변경하지 않았다.

조우 자체는 기존 `interaction_rules`의 플레이어 행동 조건과 확률로 발생한다. 서버는 플레이어들의 점수를 비교해 한 명을 뽑지 않는다. 조우 뒤 실제 퀘스트 수주가 다음 권한을 확정한다.

- `MAIN_ENTRY`: 해당 신의 첫 고정 메인 퀘스트다. 최초 수주자가 신의 주목 대상이 되고, 같은 퀘스트가 전역 완료되기 전에 수주한 다른 플레이어도 함께 주목 대상에 들어간다.
- `MAIN`: 이미 그 신의 주목 대상인 플레이어에게만 수주가 허용된다.
- `SIDE`: 주목 여부와 관계없이 기존 조건에 따라 누구에게나 제안할 수 있다.

주목 대상은 `GodAttentionState`의 `mythictrpg_god_attention` SavedData에 신별로 영구 저장된다. 한 신은 하나의 `MAIN_ENTRY` 퀘스트로만 최초 대상을 정할 수 있으며, 다른 입문 퀘스트로 덮어쓸 수 없다. `minimumAffinity`보다 호감도가 낮으면 서버가 수주를 거부하므로, AI는 해당 행적과 관계 단계에 맞춰 퀘스트 없이 적대적으로 반응할 수 있다.

AI 응답 모드는 플레이어별 서버 검증을 통과한 퀘스트만 후보로 받는다. 따라서 비주목 플레이어에게 `MAIN` 퀘스트가 대사 후보로 노출되지 않는다. 이 제한은 메인 퀘스트에만 적용되며, 데이터팩에 등록된 가호·보상·일반 상호작용·공격은 비주목 플레이어에게도 기존 검증 아래 실행할 수 있다.

`reminder.unrelatedActivityTicks`는 관련 행동 없이 무관한 관찰 행동이 이어져야 하는 최초 재촉 간격이고, `cooldownTicks`는 재촉 이후 다음 재촉까지의 최소 간격이다. Minecraft는 정상 상태에서 초당 20틱이므로 `12000`틱은 약 10분이다. 두 값은 퀘스트마다 다르게 설정할 수 있다.

`relevantActions`에는 이 퀘스트를 수행하려는 행동으로 인정할 관찰 타입과 선택적 `subject`를 선언한다. 관련 행동이 감지되면 무관 활동 타이머와 횟수가 초기화된다. 그 외 관찰 행동만 설정 시간 이상 이어지고, 플레이어가 온라인·활동 중이며 목표가 아직 완료되지 않은 경우에만 `signalId`의 자발적 AI 상호작용이 제출된다. 해당 신을 선택할 수 있도록 동일한 `signalId`의 `interaction_rules` JSON도 필요하다.

현재 관련성 판정에 사용할 수 있는 주요 관찰은 블록 파괴, 성숙 작물 수확, 엔티티 처치, 아이템 최초 획득, 동물 먹이주기·번식, 플레이어 사망, 등록된 바닐라 통계 임계치다. 이동·블록 설치·상자 이동 등 아직 관찰되지 않는 행동은 이 버전에서 관련/무관 판정에 포함되지 않는다.

## 평가형 퀘스트와 NPC 보상 등급표

평가형 퀘스트는 바인딩에 다음 선택 설정을 사용한다.

```json
"evaluation": {
  "rewardTableId": "mythictrpg:fortuna",
  "minimumRewardTier": 2,
  "maximumRewardTier": 8,
  "passingScore": 60
}
```

보상표는 `data/<namespace>/mythictrpg/reward_tables/*.json`에 신별로 작성한다. 한 신의 기본 표는 하나만 허용하며, 표마다 최대 등급이 달라도 된다. 등급은 반드시 1부터 빠짐없이 연속되어야 한다.

```json
{
  "schemaVersion": 2,
  "godId": "mythictrpg:fortuna",
  "defaultForGod": true,
  "tiers": [
    {
      "tier": 1,
      "rewards": [
        { "type": "item", "itemId": "minecraft:gold_ingot", "count": 1 }
      ]
    }
  ]
}
```

분석기는 `QuestEvaluationGateway.submit(player, questId, evaluatorNpcId, score, evidenceSummary)`에 `0~100` 점수와 근거 요약만 제출한다. 서버가 다음을 검증한다.

- 활성 수주 여부와 평가 NPC 소유권
- 평가형 퀘스트 여부와 합격 점수
- 퀘스트의 등급 범위가 NPC 보상표 안에 있는지
- 보상 아이템·호감도·가호·칭호 정의의 유효성
- 중복 완료 여부

점수가 `passingScore`보다 낮으면 퀘스트를 유지하고 AI가 평가 근거를 이용해 재도전 대사를 생성한다. 합격점은 허용 범위의 최저 등급, 100점은 최고 등급이며 그 사이는 선형으로 변환한다. 예를 들어 범위가 2–8이면 이 퀘스트에서는 포르투나 표의 1·9·10등급을 지급할 수 없다. 합격하면 서버가 완료를 커밋하고 해당 등급 및 퀘스트 직접 보상을 지급한 뒤 AI 완료·평가 대사를 생성한다.

실행 가능한 보상 타입은 `item`, `affinity`, `blessing`, `title`이다. 바인딩의 `rewards`는 신 기본 보상에 더하는 `ADD`와 직접 보상으로 대체하는 `REPLACE`, 2~6개 선택지를 지원한다. 평가형 퀘스트의 FTB 원본에는 동일한 수령형 보상을 중복 배치하지 않는다. 전체 스키마는 `REWARD_SYSTEM_GUIDE.md`를 따른다. 개발 검증 명령은 `/mythadmin quest evaluate <player> <questId> <godId> <0..100> <summary>`와 `/mythadmin reward test-choice <player> <godId>`다.

FTB ID는 정확히 16자리 16진수이며 첫 글자가 `0~7`이어야 한다. `8~F`로 시작하면 Java signed long에서 음수가 되어 FTB가 ID를 다시 만들 수 있으므로 로더가 거부한다. 가장 안전한 방법은 FTB 편집기에서 생성된 ID를 복사하는 것이다.

## 5. 완료 방식

### `AUTO`

FTB의 일반 목표가 모두 끝나면 즉시 완료한다. 이 방식의 퀘스트에는 Mythic 확인용 `custom` 태스크를 넣지 않는다.

### `PLAYER_RETURN_TO_NPC`

아이템·처치 등 일반 FTB 목표가 모두 끝난 뒤 플레이어가 `completionNpcIds` 중 하나에게 직접 상호작용해야 완료한다. FTB 퀘스트의 마지막에 버튼이 없는 `custom` 태스크를 둔다. 그 태스크는 MythicTRPG만 완료할 수 있다.

완료 조건이 충족된 경우 MythicTRPG가 먼저 authoritative 완료·FTB `custom` 태스크 완료·전역 진행도 갱신을 수행한다. 그 뒤 AI 응답 모드가 검증된 완료 정보만 받아 NPC HUD 완료 대사를 생성한다. AI가 없거나 호출에 실패하면 같은 NPC HUD 영역에 `[퀘스트가 완료되었습니다]`를 표시하고, 대화 세션은 계속 유지한다. 조건이 아직 부족하면 완료하지 않고 NPC가 미완료 안내 대사를 한다.

### `NPC_VISIT_PLAYER`

일반 목표가 끝나면 `QuestRuntimeService.readyForNpcVisit(player)`에 노출된다. 기존 사건/등장 시스템이 해당 NPC의 자발적 상호작용을 실제로 생성했을 때만 완료한다. 단순히 목표가 끝났다는 이유로 NPC를 강제 소환하지 않는다.

## 6. 포르투나 샘플 흐름

샘플 퀘스트는 바다의 심장 1개를 소비하고 포르투나에게 돌아가 확인받은 뒤, FTB UI에서 불사의 토템 1개를 수령하는 구성이다.

1. 서버를 `server/start-neoforge-ai-server.bat`로 실행한다.
2. 운영자 명령으로 바인딩을 확인한다: `/mythadmin quest list`
3. 테스트 수주: `/mythadmin quest assign ADMIN mythictrpg:fortuna_deep_sea_heart mythictrpg:fortuna`
4. FTB Quests 키 설정에서 퀘스트북을 연다.
5. 테스트 아이템 지급: `/give ADMIN minecraft:heart_of_the_sea 1`
6. 아이템 목표가 완료된 후 포르투나와 명시적으로 대화한다. 개발용 직접 확인은 `/mythadmin quest check-return ADMIN mythictrpg:fortuna`이다.
7. FTB 퀘스트북에서 불사의 토템 보상을 누른다.
8. 상태 확인: `/mythadmin quest status ADMIN`

서버 전역 퀘스트이므로 여러 플레이어가 따로 수주할 수는 있지만 최초 한 명이 완료하면 해당 논리 퀘스트는 전역 완료된다. 그 순간 다른 수주자의 동일 퀘스트는 무효화된다. 같은 FTB Teams 팀에 속한 플레이어는 FTB 목표 진행 자체도 공유될 수 있으므로 개인 목표 추적이 필요하면 각 플레이어를 별도 팀으로 유지한다.

## 7. 새 퀘스트 추가 순서

1. AI 콘텐츠 레지스트리의 퀘스트 목록 JSON에 논리 `questId`를 추가한다.
2. FTB 퀘스트 편집기로 화면, 일반 태스크, 보상을 만든다.
3. NPC 확인형이면 마지막에 버튼 없는 `custom` 태스크를 추가한다.
4. 숨김 내부 챕터에 빈 수주 마커 퀘스트를 하나 만들고, 실제 퀘스트가 그 마커에 의존하게 한다.
5. 두 FTB ID를 바인딩 JSON에 기록한다.
6. 퀘스트의 `narrativeRole`과 필요하면 `minimumAffinity`를 기록한다.
7. 신 프로필의 `questListIds`에 해당 퀘스트 목록 ID를 연결한다.
8. 서버에서 `/reload` 후 `/mythadmin quest list`로 바인딩을 확인한다. FTB SNBT를 바꿨다면 안전하게 서버를 재시작한다.

## 8. 구현 API

- AI → 게임 수주: `QuestProposalGateway.acceptOffer(player, giverGodId, questId)`
- 명시적/자발적 NPC 상호작용 완료: 기존 `AiConversationRuntimeService`의 커밋 지점에서 자동 전달
- 방문형 후보 조회: `QuestRuntimeService.readyForNpcVisit(player)`
- 전역 저장: `MythicQuestState` (`mythictrpg_quests` SavedData)
- 신별 주목 대상 저장: `GodAttentionState` (`mythictrpg_god_attention` SavedData)
- 전역 진행도 반영: 기존 `MythicWorldState.applyQuestClearProgress(...)`

FTB 클래스는 `FtbQuestAdapter` 밖으로 노출하지 않는다. 추후 FTB 버전을 올릴 때 이 어댑터만 우선 점검한다.

## 9. DonationItemTask 배포 상태

수정된 FTB Quests NeoForge JAR에는 `DonationItemTask`, `DonationItemsScreen`,
`CompleteDonationTaskMessage`가 포함되어 있다. 빌드 명령과 결과는 다음과 같다.

```powershell
cd C:\Users\ADMIN\Desktop\markmar\ftb-quests
.\gradlew.bat :neoforge:build --no-daemon
```

결과 JAR은 `ftb-quests/neoforge/build/libs/ftb-quests-neoforge-2101.1.34.jar`이며 서버의
`mods`와 `client-required-mods`에 배포되어 있다. 전용 서버 부팅과 FTB 퀘스트팩 로드는
완료했다. 누적 기부·숨김 최대치·수동 완료 버튼은 실제 그래픽 클라이언트에서 마지막으로 확인한다.
