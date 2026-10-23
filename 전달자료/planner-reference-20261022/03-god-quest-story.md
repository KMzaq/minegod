# 인물 ID와 퀘스트·Story 연결 보충자료

2026-10-22 · `behaivior`의 현재 개발 소스와 디스크에 있는 서버 데이터팩을 확인했다. 이 자료는 기존 가이드의 변경분과 빠진 ID 연결을 보충한다. 게임 기능·신 등급·정식 퀘스트·운영 서버 설정은 추가하거나 변경하지 않았다.

## 1. 인물 이름과 실제 God ID

전체 대응표는 [god-id-map.csv](god-id-map.csv)다. **139개 정의**를 수록했다.

| 정의 범위 | 개수 | 의미 |
|---|---:|---|
| `mythictrpg-main/src/main/resources/data/*/mythictrpg/gods` | 4 | 현재 게임 소스의 기본 정의 |
| `server/world/datapacks/mythictrpg-greek-ai-test/data/*/mythictrpg/gods` | 135 | 서버 폴더에 설치된 그리스 신 시험 데이터팩 정의 |

CSV의 `기획관리번호`는 비워 두었다. 기획자의 관리번호 대응 자료가 없으므로 이름이나 순서로 추측하지 않았다. 기획자는 자신의 관리번호를 해당 행에 채우고, 개발 전달에는 **God ID를 함께** 적으면 된다. `god_rank`가 비어 있는 행은 실제 파일에 등급이 작성되지 않은 것이다.

대표 연결은 다음과 같다.

| 표시 이름 | 게임 소스의 ID | 서버 시험 데이터팩의 별도 ID |
|---|---|---|
| 포르투나 | `mythictrpg:fortuna` | 없음 |
| 데메테르 | `mythictrpg:demeter` | `mythictrpg:greek_olympian_demeter` |
| 아프로디테 | `mythictrpg:aphrodite` | `mythictrpg:greek_olympian_aphrodite` |
| 멸룡루브라스 | `mythictrpg:lubras` | 없음 |
| 제우스 | 없음 | `mythictrpg:greek_olympian_zeus` |

**같은 이름의 두 ID는 자동 별칭이 아니다.** 현재 포르투나·데메테르의 퀘스트와 AI 프로필, 아프로디테의 Story Actor는 기존 짧은 ID를 사용한다. 그리스 시험 ID로 변경하면 해당 참조와 저장 데이터의 이관을 따로 검토해야 한다. 예를 들어 데메테르 퀘스트를 만들면서 시험 ID와 기존 ID를 섞으면 같은 인물로 연결되지 않는다.

### 서로 구분해야 하는 ID

| 종류 | 실제 예 | 사용 위치 |
|---|---|---|
| God ID | `mythictrpg:fortuna` | 게임 신 정의, 보상 지급 신, 완료 NPC, 프로필의 `godId` |
| AI 프로필 콘텐츠 ID | `mythaiaicontent:fortuna` | `data/mythaiaicontent/mythai_ai/god_profiles/fortuna.json`의 파일 ID |
| 퀘스트 목록 ID | `mythictrpg:quest_list_fortuna` | 프로필의 `questListIds`, 퀘스트 목록의 `questListId` |
| 논리 퀘스트 ID | `mythictrpg:fortuna_deep_sea_heart` | 목록의 `questId`, 게임 바인딩의 파일 ID와 `questId` |
| FTB 퀘스트 ID | `21F48990D282287B` | 바인딩의 `ftbQuestId`, FTB SNBT |
| Story Actor ID | `mythictrpg:aphrodite` | Story Actor 파일 ID. 현재 예시는 God ID와 같지만 같은 개념은 아님 |

God ID는 표시명이 아니라 `data/<namespace>/mythictrpg/gods/<path>.json`의 `<namespace>:<path>`다. GOD 유형의 Story Actor는 별도 `godId`로 게임 신을 가리킨다. AI 프로필도 내부 `godId`로 같은 게임 ID에 연결한다. 프로필 파일명을 바꾸는 것만으로 게임 God ID가 바뀌지 않는다.

CSV의 `소스AI프로필파일`은 **현재 콘텐츠 레지스트리 소스에 있는 명시적 연결**만 기록한다. 빈칸이 게임 신 정의의 부재를 뜻하지 않는다. 소스의 `mythictrpg:test5` AI 프로필은 현재 조사한 기본·서버 시험 God Definition 139개에는 대응 정의가 없어, 게임 ID 대응표에 등록된 인물처럼 추가하지 않았다.

### 설치 파일과 현재 실행 상태

135개 시험 정의는 소스 기본 4개와 ID가 겹치지 않으므로 기존 정의를 덮는 대신 추가한다. 표시 이름만 데메테르·아프로디테와 겹친다. 같은 리소스 경로를 쓰는 상위 데이터팩은 그 ID의 내용을 덮을 수 있으므로 실제 활성 팩 순서는 적용 시 확인한다.

현재 `server/logs/latest.log`에는 **2026-10-14 당시 139개 God Definition 로드** 기록이 있다. 이는 당시 조합의 증거이며 현재 개발 소스나 실행 중 레지스트리 전체를 다시 조회한 결과는 아니다. 이번 작업에서는 서버를 켜거나 reload하지 않았다. 실제 적용 시 `/mythadmin gods`와 로드 로그로 등록 결과를 확인한다.

## 2. 기존 퀘스트·Story 가이드의 유지 범위

기존 가이드 전체를 다시 보내지 않고 아래 원본을 계속 사용한다. 이 문서의 변경분이 오래된 상태 설명보다 우선한다.

| 필요한 내용 | 계속 사용할 기존 문서 | 이번 보충 |
|---|---|---|
| AI 퀘스트 목록 양식 | [퀘스트 목록 작성 가이드](../../mythai-ai-content-registry/QUEST_LIST_AUTHORING_GUIDE.md), [빈 양식](../../mythai-ai-content-registry/QUEST_LIST_TEMPLATE.json) | 목록 ID와 God ID 구분, 아래 실제 연결 예 |
| 고정 퀘스트·FTB 연결 | [FTB Quests 연동](../../mythictrpg-main/docs/FTB_QUESTS_INTEGRATION_GUIDE.md) | 보상 4종·주시 미지원이라는 오래된 문구는 아래 정정 적용 |
| SOLO/GROUP/경쟁/랭킹 | [참여 유형 가이드](../../mythictrpg-main/docs/QUEST_PARTICIPATION_GUIDE.md) | 개인·팀·월드 범위를 서로 바꾸지 않음 |
| 즉석 SIDE 템플릿 | [즉석 퀘스트 가이드](../../mythictrpg-main/docs/GENERATED_QUESTS_GUIDE.md) | 전투력 기본 상태와 수주 기준에 관한 아래 제한 적용 |
| 직접·선택 보상 | [통합 보상 가이드](../../mythictrpg-main/docs/REWARD_SYSTEM_GUIDE.md) | 새 보상 3종과 저장 v7은 새 스킬 가이드에 설명 |
| Story 조건·행동·지식 | [Story 사건 엔진](../../mythictrpg-main/docs/STORY_EVENT_ENGINE_GUIDE.md) | 10월 15일 전달본 이후 추가된 내용은 아래 목록 |
| 레이드 작성 | [RAID_RUNTIME.md](reference/mythictrpg-main/docs/RAID_RUNTIME.md) | 사용자 요청에 따라 [이번 묶음의 레이드 문서 사본](reference/mythictrpg-main/docs/RAID_RUNTIME.md) 포함 |

### 10월 15일 Story 전달본과 실제 비교

`전달자료/story-json-reference-20261015.zip`의 `reference/` 파일 36개를 현재 파일과 SHA-256으로 비교했다. **31개가 동일**하고 5개가 달라졌다.

변경 파일은 `STORY_EVENT_ENGINE_GUIDE.md`, `BuiltinConditionTypes.java`, `StoryDefinitionManager.java`, `StoryDefinitions.java`, `story-game-connection-support-20261015.md`다. 기존 Story JSON 예제와 `STORY_AI_PRESENTATION_GUIDE.md`, `STORY_CHOICE_UI_GUIDE.md`는 동일하므로 재복사하지 않는다.

기획자가 반영할 변경은 다음과 같다.

1. **Story 직접 보상:** `GRANT_REWARDS`로 기존 보상 claim을 사용한다. 사건을 시작한 온라인 플레이어 한 명에게 지급하며 SERVER 범위라고 모두에게 지급하지 않는다. 성공에 종속된 완료 사실은 `event_resolved`를 받는 후속 사건에 둔다.
2. **평판 회복:** `RECOVER_REPUTATION`에 대상 신·기존 정책·이유를 작성한다. 배상·중재 등의 실제 게임 결과에 연결하며 일반 플레이에서도 동작한다. AI의 사과 수락 대사만으로 회복하지 않는다.
3. **조사·생활과 동시 사건:** 현장 조사, 방문 도착 신호, `story_admission`의 공유 자원·개인 점유를 사용한다. LATCHED 대기는 로그아웃 후에도 원래 범위·대상을 유지한다. 작성법은 [콘텐츠 시스템 가이드](../../mythictrpg-main/docs/CONTENT_SYSTEMS_GUIDE.md)다.
4. **행운 사건:** PLAYER 범위 `WEIGHTED_ONCE`의 outcome에 `luckQuality`를 작성할 수 있다. 효과 버프와 장신구를 합친 유효 행운을 0–17로 제한해 사용한다. TEAM·SERVER 사건과 수동 선택은 자동 보정하지 않는다.
5. **인간 루트의 가호 조건:** `has_non_raid_blessing`으로 직접 레이드 보상을 제외한 영구 가호 기록을 검사한다. 레이드 성공으로 완료한 **퀘스트 보상은 제외되지 않는다**. 우유로 지워도 보유 기록은 남는다.

공통 조건과 Story 조건은 같은 Condition Tree를 사용하지만, **AI 퀘스트 목록의 `acceptanceConditions`는 별도 형식**이다. 두 형식을 그대로 서로 붙여 넣지 않는다.

### 보상·주시·저장에 관한 정정

- 현재 실제 보상 codec에는 기존 타입 외에 `learn_skill`, `accessory`, `special_skill`이 있다. 각 항목은 `definitionId`를 참조한다. [장비·스킬 보충자료](02-equipment-skills-accessories.md)와 [스킬 시스템 가이드](../../mythictrpg-main/docs/SKILLS_ACCESSORIES_SPECIAL.md)를 따른다.
- 보상 획득 원장은 현재 **v7**이다. 통합 보상 가이드의 v6 설명은 앞선 가호·행운 구현 당시 기록이다. 작성 JSON에 저장 버전이나 내부 `resolved_*` 결과, 지급 출처를 임의로 넣지 않는다.
- `watch` 보상은 구현돼 있다. FTB 가이드 중 ‘주시 지급 미구현’이라는 과거 문구를 현재 상태로 읽지 않는다. MAIN 접근 권한, 실제 주시 보유, 현재 원격 관찰·응답 가능 여부는 각각 다르다.
- 현재 기본 전투력 설정과 계산은 [전투력 보충자료](01-combat-power.md)를 기준으로 한다. 즉석 퀘스트 가이드의 과거 ‘전체 기본 동작은 UNAVAILABLE’ 문구만으로 현재 상태를 단정하지 않는다. 저장 이력과 입력 검증 실패 시에는 여전히 사용할 수 없는 판정이 된다.

## 3. 전투력 수주 제한의 현재 지원 여부

**‘전투력 N 이상이어야 수주’라는 작성형 조건은 현재 미구현이다.** 이번 자료 작업에서 새로 만들지 않았다.

- AI 퀘스트 목록의 수주 조건 실행은 현재 `world_quest_progress_range`를 지원한다. 알 수 없는 타입은 수주 불가로 처리한다.
- 고정 FTB 바인딩은 현재 `minimumAffinity`, MAIN 접근 권한, 전역 진행도 등 기존 검사를 사용한다. `minimumCombatPower` 필드는 없다. 알 수 없는 필드를 넣으면 거절된다.
- Story·레이드의 공통 Condition Tree에도 현재 전투력 숫자 비교 타입은 등록돼 있지 않다. 레이드에 `entryCondition`이 있다는 사실만으로 임의의 전투력 타입을 사용할 수는 없다.
- 전투력의 현재 자동 연동은 **즉석 SIDE 퀘스트의 뒤처짐 보상 보정**이다. 실제 평가가 AVAILABLE일 때 템플릿의 `catchUpMaximumBonus`(0 또는 1)와 평가 결과 중 작은 값을 기본 보상 tier에 더하고 `maximumTier`로 제한한다. 전투력 미달자의 수주를 차단하는 기능은 아니다.

기획은 계산 자료로 권장 전투력·최소 수주 전투력을 구분해 작성할 수 있다. 이후 실제 최소 기준을 적용하려면 그 조건 기능과 검증을 별도 구현해야 한다. 현재 JSON에 임의 필드를 추가하면 실행되지 않는다.

## 4. 기존 정상 연결 예: 포르투나 고정 퀘스트

새로 꾸민 퀘스트 대신 현재 세 위치에 함께 있는 예를 사용한다.

| 연결 단계 | 현재 파일·값 |
|---|---|
| 지급·완료 신 | `mythictrpg:fortuna` |
| 프로필 연결 | [fortuna.json](../../구버전/20261022-테스트콘텐츠/원본/mythai-ai-content-registry/src/main/resources/data/mythaiaicontent/mythai_ai/god_profiles/fortuna.json)의 `godId`, `questListIds` |
| AI 목록 | [quest_list_fortuna.json](../../구버전/20261022-테스트콘텐츠/원본/mythai-ai-content-registry/src/main/resources/data/mythictrpg/mythai_ai/quest_lists/quest_list_fortuna.json) |
| 게임 바인딩 | [fortuna_deep_sea_heart.json](../../구버전/20261022-테스트콘텐츠/원본/mythictrpg-main/src/main/resources/data/mythictrpg/mythictrpg/ftb_quests/fortuna_deep_sea_heart.json) |
| FTB 목표·레거시 보상 | [mythic_story.snbt](../../구버전/20261022-테스트콘텐츠/원본/mythictrpg-main/ftbquests-pack/config/ftbquests/quests/chapters/mythic_story.snbt) |
| FTB 수주 표시 | [mythic_internal.snbt](../../mythictrpg-main/ftbquests-pack/config/ftbquests/quests/chapters/mythic_internal.snbt) |

논리 ID는 `mythictrpg:fortuna_deep_sea_heart`, 진행도는 `mythictrpg:progress_fortuna`, 수주 시 진행도 범위는 0–0이다. 바다의 심장 2개 목표를 달성한 뒤 `PLAYER_RETURN_TO_NPC` 확인을 받는다. 퀘스트 ID `21F48990D282287B`와 수주 마커 `5DD74E73298791E8`를 바인딩과 SNBT에서 함께 확인할 수 있다.

AI 목록의 `rewards[].parameters`는 설명용 콘텐츠 형식이고, Mythic 직접 보상의 평탄한 `RewardEntry` 형식과 다르다. 현재 예제의 FTB 원본에는 불사의 토템 보상이 있다. **이 레거시 예에 같은 토템을 Mythic 직접 보상으로 추가해 중복 지급하지 않는다.** 새 보상·스킬 권한은 Mythic 보상 경로로 작성하고 FTB에 같은 보상을 중복 배치하지 않는다.

새 직접 보상은 바인딩의 `rewards`에 아래 구조를 넣는다. 이것은 완전한 바인딩 파일이 아닌 해당 필드 예시다.

```json
{
  "mode": "REPLACE",
  "automaticRewards": [
    {"type": "item", "itemId": "minecraft:emerald", "count": 3}
  ],
  "choices": []
}
```

이 예시는 신별 기본 표를 사용하지 않고 직접 아이템만 지급한다. 습득 스킬·장신구·특수스킬로 바꾸려면 해당 정의와 제공 신의 등급·소유권 조건도 유효해야 한다.

## 5. Story 조건·행동의 기존 정상 예와 새 보상 예

기존 전달본의 [demo_investigate_lubras_base.json](../../구버전/20261022-테스트콘텐츠/원본/mythictrpg-main/src/main/resources/data/mythictrpg/mythictrpg/story_events/demo_investigate_lubras_base.json)은 그대로 유효하다. `story_hook_accepted` 신호와 `investigate_lubras_base` 주체를 받고, `wanderer_occupies_lubras_base`라는 SERVER 사실이 참인지 확인한다. 기존 `aphrodite` Actor에 연결해 `GRANT_KNOWLEDGE`와 `EMIT_PRESENTATION`을 실행한다. 관련 Actor·Fact·Hook·Presentation도 이전 전달본에 있으므로 새로 보내지 않는다. 이 예시의 데모 스토리를 정식 정사로 확정한 것은 아니다.

새로 추가된 보상 행동은 [story-harvest-reward.json](examples/story-harvest-reward.json)에 전체 사건 예시를 작성했다. 적용 경로는 `data/example/mythictrpg/story_events/harvest_reward.json`, 파일 ID는 `example:harvest_reward`다. 성숙한 밀 수확 신호를 받아 **개인별 한 번**, 기존 데메테르 ID를 지급 신으로 사용하여 금 조각 하나를 준다. 파일은 전달자료 안에만 있으며 자동 설치되지 않는다. 수치·신·지급 조건은 형식 설명용이다.

인간 루트의 조건은 Story `prerequisites` 또는 outcome의 `additionalConditions`에 다음처럼 작성할 수 있다.

```json
{
  "type": "mythictrpg:not",
  "condition": {
    "type": "mythictrpg:has_non_raid_blessing",
    "scope": "player"
  }
}
```

`scope`의 대소문자에 주의한다. 일반 Condition의 플레이어 범위는 `player`, Story 사건 범위는 `PLAYER`, Story 사실 범위는 `SERVER`처럼 각 스키마를 따른다. 상태를 읽을 수 없으면 UNKNOWN이며, 부정 조건도 UNKNOWN을 통과시키지 않는다.

## 6. 레이드 가이드의 후속 내용

요청한 `RAID_RUNTIME.md`는 이번 묶음에 포함한다. 기존 작성 규격을 재설계하지 않았으며 기획자가 확인할 최근 내용은 다음과 같다.

- 직접 레이드 승리 보상은 `RAID_DIRECT`, 레이드로 완료한 퀘스트 보상은 `QUEST`다. 작성자가 origin을 임의 지정하지 않는다.
- 직접 레이드 보상도 공용 보상 codec과 claim을 사용하므로 가호·행운 보상과 새 스킬·장신구 권한을 같은 지급 경로로 연결한다. `lucky_item`에만 행운에 따른 품질·수량 보정이 있으며 고정 `item`을 자동 증량하지 않는다.
- 작성형 대응 도구는 실제 시도·참가자·피해 출처를 검사한다. 구체적인 보스 패링·새 기믹은 자동 생성되지 않는다.
- 특수스킬의 레이드별 횟수는 **시도 UUID**를 기준으로 하며, 동일 시도에서 재접속·부활해도 초기화되지 않는다. 현실 쿨타임·소모 아이템 조합은 [별도 스킬 기획서](../skills-accessories-special-planning-20261022.md)를 따른다.

## 확인 범위

이번에는 문서·CSV·설명용 JSON만 작성했다. God ID는 파일 경로, 한글 표시명, 프로필 `godId`를 대조했고 기존 Story ZIP과 현재 파일의 동일/변경 여부를 비교했다. 예시의 JSON 구문과 현재 파서 필드·열거값·참조를 정적으로 확인했다. 새 예시 데이터팩 로드, 게임 빌드, 서버 배포·부팅·실제 플레이는 수행하지 않았다.
