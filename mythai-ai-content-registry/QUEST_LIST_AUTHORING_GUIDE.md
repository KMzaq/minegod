# 퀘스트 목록 작성 가이드

이 문서는 `MythAI Content Registry`에서 신 전용·세력 공용 퀘스트 목록 JSON을 작성하는 방법을 설명한다.

퀘스트 목록 JSON은 퀘스트의 고정 정의를 제공한다. 실제 수주 가능 판정, 수주자 기록, 목표 추적, 완료 판정, 보상 지급과 서버 전역 진행도 변경은 MythicTRPG가 담당한다.

## 1. 파일 위치

```text
src/main/resources/data/<namespace>/mythai_ai/quest_lists/<소속_이름>.json
```

MythicTRPG namespace를 사용한다면 다음 위치에 작성한다.

```text
src/main/resources/data/mythictrpg/mythai_ai/quest_lists/
```

## 2. 파일명과 ID 규칙

파일명과 `questListId`는 소문자 영어 `소속_이름` 형식으로 통일한다.

```text
<affiliation>_<name>
```

예시:

| 용도 | 권장 파일명 | `questListId` |
|---|---|---|
| 데메테르 전용 | `olympus_demeter.json` | `mythictrpg:olympus_demeter` |
| 올림포스 공용 | `olympus_common.json` | `mythictrpg:olympus_common` |
| 티탄 공용 | `titan_common.json` | `mythictrpg:titan_common` |
| 명계 공용 | `underworld_common.json` | `mythictrpg:underworld_common` |
| 포르투나 전용 | `roman_fortuna.json` | `mythictrpg:roman_fortuna` |

파일 경로에서 생성되는 ID와 JSON 내부 `questListId`는 반드시 같아야 한다.

```text
파일:
data/mythictrpg/mythai_ai/quest_lists/olympus_demeter.json

올바른 ID:
mythictrpg:olympus_demeter
```

대문자, 한글, 공백은 파일명과 ID에 사용하지 않는다.

## 3. 전체 양식

```json
{
  "schemaVersion": 2,
  "questListId": "mythictrpg:olympus_demeter",
  "progressTrackId": "mythictrpg:progress_olympus_demeter",
  "displayName": "데메테르 퀘스트",
  "quests": [
    {
      "questId": "mythictrpg:olympus_first_harvest",
      "title": "첫 수확의 봉헌",
      "content": "잘 익은 곡물을 모아 수확의 여신에게 성의를 보인다.",
      "objectives": [
        {
          "type": "collect_item",
          "description": "밀 32개를 모은다.",
          "parameters": {
            "itemId": "minecraft:wheat",
            "count": 32
          }
        }
      ],
      "rewards": [
        {
          "type": "item",
          "description": "에메랄드 4개를 받는다.",
          "parameters": {
            "itemId": "minecraft:emerald",
            "count": 4
          }
        }
      ],
      "acceptanceConditions": [
        {
          "type": "world_quest_progress_range",
          "description": "서버 전역 데메테르 진행도가 0일 때 수주할 수 있다.",
          "parameters": {
            "progressTrackId": "mythictrpg:progress_olympus_demeter",
            "minimum": 0,
            "maximum": 0
          }
        }
      ],
      "progressOnClear": 10
    }
  ]
}
```

전체 복사용 빈 양식은 [QUEST_LIST_TEMPLATE.json](QUEST_LIST_TEMPLATE.json)에 있다.

## 4. 목록 필드

| 필드 | 필수 | 설명 |
|---|---:|---|
| `schemaVersion` | 예 | 현재 `2` |
| `questListId` | 예 | 파일 경로에서 생성되는 ResourceLocation과 동일한 ID |
| `progressTrackId` | 예 | 이 목록이 사용하는 서버 전역 진행도 트랙 ID |
| `displayName` | 예 | 관리 화면과 로그에서 구분할 목록 이름 |
| `quests` | 예 | 퀘스트 배열. 준비 중이라면 `[]` 가능 |

## 5. 퀘스트 필드

| 필드 | 필수 | 설명 |
|---|---:|---|
| `questId` | 예 | 전체 퀘스트 목록에서 중복되지 않는 ID |
| `title` | 예 | 플레이어에게 표시할 퀘스트 제목 |
| `content` | 예 | 퀘스트 배경과 해야 할 일을 자연어로 설명 |
| `objectives` | 예 | 최소 하나의 목표 |
| `rewards` | 예 | 최소 하나의 보상 |
| `acceptanceConditions` | 아니오 | 모두 만족해야 하는 수주 조건. 없으면 `[]` 또는 생략 가능 |
| `progressOnClear` | 예 | 완료 확정 시 전역 진행도에 더할 값. `0`부터 `100` |

## 6. 목표와 보상 노드

목표·보상·조건은 공통적으로 다음 구조를 사용한다.

```json
{
  "type": "collect_item",
  "description": "밀 32개를 모은다.",
  "parameters": {
    "itemId": "minecraft:wheat",
    "count": 32
  }
}
```

- `type`: MythicTRPG가 해석할 기능 유형
- `description`: NPC와 플레이어에게 설명할 자연어 문장
- `parameters`: 기능에 필요한 문자열 또는 숫자 값

현재 예시로 사용하는 유형:

```text
목표: collect_item
보상: item
조건: world_quest_progress_range
```

새로운 유형을 JSON에 적는 것만으로 게임 기능이 자동 생성되지는 않는다. MythicTRPG가 해당 `type`을 구현하고 검증할 수 있어야 한다. 알 수 없는 수주 조건은 안전하게 수주 불가로 처리한다.

## 7. 서버 전역 진행도

진행도는 플레이어별이 아니라 서버 전체가 공유한다.

```json
"progressTrackId": "mythictrpg:progress_olympus"
```

- 저장값이 없으면 시작값은 `0`
- 최대값은 `100`
- 같은 `progressTrackId`를 사용하는 목록은 진행도를 공유
- 다른 `progressTrackId`를 사용하면 독립 진행도
- JSON에는 현재 진행도 값을 직접 쓰지 않음
- 현재 값은 월드 저장 데이터에 보존

예시:

```text
olympus_demeter -> progress_olympus_demeter
olympus_common  -> progress_olympus
```

누군가 `progress_olympus`를 20까지 올리면 다른 플레이어에게도 20이 적용된다. 수주 조건의 최대값이 19인 이전 퀘스트는 다른 플레이어도 새로 받을 수 없다.

## 8. 수주 구간 작성

진행도 0에서만 가능한 첫 퀘스트:

```json
"acceptanceConditions": [
  {
    "type": "world_quest_progress_range",
    "description": "전역 진행도가 0일 때 수주할 수 있다.",
    "parameters": {
      "progressTrackId": "mythictrpg:progress_olympus",
      "minimum": 0,
      "maximum": 0
    }
  }
]
```

진행도 20~39에서 가능한 퀘스트:

```json
"parameters": {
  "progressTrackId": "mythictrpg:progress_olympus",
  "minimum": 20,
  "maximum": 39
}
```

구간이 겹치는 퀘스트가 여러 개면 AI는 현재 가능한 퀘스트들을 선택지로 보여 주고 플레이어가 하나를 고르게 한다.

## 9. 새 퀘스트 추가 방법

기존 목록의 `quests` 배열에 객체를 하나 더 추가한다.

```json
"quests": [
  { "questId": "mythictrpg:olympus_first_harvest", "...": "..." },
  { "questId": "mythictrpg:olympus_second_harvest", "...": "..." }
]
```

`questId`는 권장상 `소속_퀘스트이름` 형식의 소문자 영어를 사용한다.

```text
mythictrpg:olympus_first_harvest
mythictrpg:underworld_lost_soul
mythictrpg:titan_broken_chain
```

## 10. 신 프로필에서 참조하기

NPC 프로필의 `questListIds`에 목록 ID를 추가한다.

```json
"questListIds": [
  "mythictrpg:olympus_demeter",
  "mythictrpg:olympus_common"
]
```

여러 신이 `mythictrpg:olympus_common`을 참조하면 공용 퀘스트를 제안할 수 있다.

## 11. 점검 목록

- 파일명이 소문자 영어 `소속_이름.json`인가?
- `questListId`가 파일 경로에서 생성되는 ID와 정확히 같은가?
- `progressTrackId`가 의도한 신 전용 또는 세력 공용 트랙인가?
- `questId`가 다른 목록의 퀘스트와 중복되지 않는가?
- 목표와 보상이 각각 최소 하나인가?
- 수주 조건의 `progressTrackId`가 목록의 트랙과 일치하는가?
- 진행도 구간에 빠진 부분이나 의도하지 않은 중복이 없는가?
- `progressOnClear`가 0~100 범위인가?
- 존재하지 않는 Minecraft 아이템 ID를 사용하지 않았는가?
- JSON 문법상 마지막 항목 뒤에 불필요한 쉼표가 없는가?

