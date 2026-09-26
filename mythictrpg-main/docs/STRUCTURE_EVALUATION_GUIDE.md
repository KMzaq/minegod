# MythicTRPG 범용 건축물 평가 시스템

> 2026-09-20 / 게임 1.0.8: 기존 건축 설치 원장을 채굴 인정 실적에도 재사용하며 `GENERATOR` 출처와 피스톤/낙하 이동 보존 경로를 추가했다. 기존 평가·기여자·보상 소유권은 유지한다. 원장 버전 3은 1/2를 읽고 손상 시 원본 보존/쓰기 차단을 적용한다. 실제 모드 이동/변환 조합 검증은 남았다. [상세 변경과 한계](../../docs/MEMORY_POLICY_IMPLEMENTATION_20260920.md)를 참조한다.

## 권한 경계

건축 점수, 합격 여부, 퀘스트 완료와 보상은 서버가 확정한다. AI가 실행할 수 있는
`structure_evaluation_request`에는 `quest_id` 하나만 허용되며 점수·근거·합격·보상 필드는
받지 않는다. 서버 분석 결과만 기존 `QuestEvaluationGateway`에 전달된다.

첫 기준 신은 `mythictrpg:fortuna`다. 포르투나의 모던풍 선호는 Java 분기가 아니라
`data/mythictrpg/mythictrpg/structure_evaluation_policies/fortuna_modern.json`과 블록 태그에
정의되어 있다. 다른 신은 같은 스키마의 정책 JSON을 추가하면 된다.

## 퀘스트 연결

FTB 퀘스트 바인딩에 `evaluation`과 `structureEvaluation`을 함께 둔다.

```json
{
  "evaluation": {
    "rewardTableId": "mythictrpg:fortuna",
    "minimumRewardTier": 1,
    "maximumRewardTier": 5,
    "passingScore": 60
  },
  "structureEvaluation": {
    "policyId": "mythictrpg:fortuna_modern"
  }
}
```

신 기본 보상표가 없는 평가 퀘스트는 `rewardTableId`를 생략하고 직접 보상을
`rewards.mode = REPLACE`로 작성할 수 있다. `ADD`는 반드시 신 기본 보상표가 있어야 한다.

## 영역과 협동

- 전용 아이템 `mythictrpg:structure_selector`를 일반 사용하면 지점 1, 웅크리고 사용하면
  지점 2가 설정된다.
- X/Z와 Y 모두 사용자에게 보이는 건축 크기 제한이 없다. `region.maxWidth/maxDepth`는 이전
  데이터팩 호환과 운영 권장 크기를 위한 값이며 등록·평가 거부 조건으로 사용하지 않는다.
- `confirm` 시 FTB Teams의 현재 팀원 UUID와 소유자 UUID를 복사해 고정한다. 이후 팀 이동은
  해당 건축의 참가 자격을 바꾸지 않는다.
- 영역을 다시 확정하면 그 퀘스트의 기존 원장을 비우고 새 시작 시점부터 기록한다.

운영 흐름:

```text
/mythadmin structure tool <player>
선택기로 지점 1/2 지정
/mythadmin structure confirm <player> <questId>
건축
/mythadmin structure inspect <player> <questId>
/mythadmin structure evaluate <player> <questId> <godId>
```

추가 명령은 `pos1`, `pos2`, `status`, `clear`, `policies`다. `inspect`는 점수만 미리 계산하고
퀘스트를 완료하지 않는다. `evaluate`는 실제 Gateway까지 호출한다.

## 퀘스트 없는 자유 건축

`mythictrpg_player_constructions` SavedData는 모드 적용 이후의 플레이어 설치·파괴 기록을
퀘스트 수락 여부와 관계없이 계속 보관한다. 따라서 먼저 집을 짓고 나중에 등록해도 본인과
등록 시점에 고정된 FTB 팀원의 설치 기록을 가져올 수 있다.

```text
/mythstructure pos1
/mythstructure pos2
/mythstructure register "바닷가 현대식 집"

# 현재 위치 32블록 안에서 가장 가까운 설치 블록의 연결 성분을 자동 탐색
/mythstructure discover "자동 탐색한 집"

/mythstructure list
/mythstructure status
/mythstructure evaluate "바닷가 현대식 집" mythictrpg:fortuna_modern
/mythstructure result "바닷가 현대식 집" mythictrpg:fortuna_modern
/mythstructure delete "바닷가 현대식 집"
```

선택 등록은 떨어진 별채·정원까지 한 건축물로 묶을 때 사용하고, `discover`는 서로 맞닿은
블록으로 이루어진 집을 빠르게 등록할 때 사용한다. 등록하면 로드된 모든 신별 건축 정책의
평가를 자동 예약한다. 원장 준비는 틱당 1,500항목으로 분할되며 정책 표본 상한을 넘는
대형 건축은 전체를 거부하지 않고 결정적인 대표 표본으로 평가한다. 평가 중 청크를 생성하거나
강제 로드하지 않는다.

모드 적용 전에 이미 완성된 건물은 설치 provenance가 없으므로 자동 소유권 판정 대상이 아니다.
필요하면 관리자가 적용 후 일부를 다시 설치하게 하거나 별도의 향후 import 도구로 승인해야 한다.

## 건축을 신 등장 조건으로 사용

신 정의의 `appearance_conditions`에 `mythictrpg:structure_evaluated`를 사용할 수 있다. 이는
신을 즉시 강제 소환하지 않고 기존 등장 후보·확률 흐름에 들어갈 자격만 만든다.

```json
{
  "type": "mythictrpg:structure_evaluated",
  "scope": "player",
  "policy": "mythictrpg:fortuna_modern",
  "minimum_score": 72,
  "minimum_build_score": 68,
  "maximum_age_ticks": 24000,
  "minimum_features": {
    "interior_quality": 0.55,
    "palette_cohesion": 0.65
  }
}
```

`maximum_age_ticks`는 생략하면 무기한이며, `minimum_features`도 생략할 수 있다. 자유 건축과
퀘스트 건축의 서버 확정 평가 이력이 모두 조건 대상이다. 현재 번들 포르투나의 실제 등장 조건은
기존 콘텐츠 동작을 바꾸지 않기 위해 `always`로 유지했다.

## Player Placed Ledger

SavedData 파일명은 `mythictrpg_structure_evaluations`다. 각 레코드는 다음을 저장한다.

```text
owner UUID + quest ID
dimension + min/max X/Z
확정 시점
고정된 참가자 UUID 집합
BlockPos(long) -> placer UUID, game tick, PLAYER_PLACED/DERIVED
장식 entity UUID -> placer UUID, game tick
마지막 평가 시점
성공한 구조 fingerprint -> 사용 퀘스트 ID
```

블록 파괴 시 현재 원장에서 제거하고 같은 위치를 다시 설치하면 새 기록으로 교체한다.
직접 설치, 괭이로 만든 농지 같은 상태 변환, 물 양동이 source, 심은 작물의 현재 성장 상태를
지원한다. 그림·아이템 액자·갑옷 거치대는 영역 안 생성 시 가까운 고정 참가자를 설치자로
귀속해 기록한다. 기존 자연 지형과 퀘스트 전 건물은 BUILD 블록에 들어가지 않는다.

같은 퀘스트의 불합격 후 수정·재평가는 허용한다. 성공한 구조의 fingerprint를 다른 퀘스트에서
다시 쓰는 것은 기본 차단하며 정책의 `allowReuse`로만 해제할 수 있다.

## Snapshot과 성능 안전장치

평가 시 선택 영역 전체를 스캔하지 않는다. 원장 좌표의 현재 BlockState만 확인하고
남아 있는 블록의 min/max Y로 실제 Bounding Box를 만든다.

- 기본 포르투나 분석 표본: 최대 30,000블록
- 원장 크기와 영역 치수에는 하드 거부 상한 없음
- 내부 분석 후보 셀: 300,000개
- flood-fill: 180,000개
- 환경 대표점: 최대 80개
- 환경 반경: 수평 5, 수직 4, 2블록 간격
- 바이옴: 실제 X/Z 경계의 5×5 표본
- 평가 때문에 청크를 생성하거나 강제로 로드하지 않음
- live `ServerLevel` 읽기는 서버 스레드에서만 수행하고 결과에는 복사된 수치만 유지

실제 경계 부피가 내부 상한을 넘으면 건축 전체를 거절하지 않고 내부 공간 지표만 0으로 두며
근거에 생략 사유를 표시한다. 추적 블록이 표본 상한을 넘으면 대표 좌표를 사용하고, 미로드
청크 표본은 제외한 사실을 근거에 표시한다. 사용 가능한 표본이 하나도 없을 때만 거절한다.

## Gemma4 시각 분석

`visualProfile`이 있는 정책은 블록 분석 직후 서버가 추적 원장의 대표 표본을 512×512 PNG
5장(4방향 아이소메트릭 + 상단)으로 소프트웨어 렌더링한다. 전용 서버에서 동작하도록 Minecraft
클라이언트 렌더러나 GPU·리소스팩 텍스처를 사용하지 않는다. 최대 12,000개 시각 표본만 복사하고
PNG 인코딩과 Ollama 호출은 서버 틱 밖의 단일 작업 큐에서 처리한다. 이미지는 기본적으로 파일로
저장하지 않고 메모리에서 요청 후 폐기한다.

일반 대화와 건축 평가는 같은 Ollama 프로세스와 같은 `ai-dialogue.json`의 모델을 사용하지만
메시지·JSON 스키마·작업 큐는 분리되어 있다. 따라서 Gemma4 모델을 두 개 실행하는 구조가 아니다.
현재 서버 설정은 `gemma4:12b`이며 `/api/chat`의 `images`와 구조화 출력 스키마를 사용한다.
웹 검색 결과는 게임 점수 근거로 사용하지 않는다.

고정 주 유형은 다음과 같다. 세부 유형과 스타일 문자열은 새 건축 양식을 표현할 수 있도록
제한된 길이의 자유 라벨이다.

```text
HOUSE, TEMPLE, ARENA_AMPHITHEATER, FORTRESS_CASTLE, TOWER, FARM,
WORKSHOP, BRIDGE, MONUMENT, PUBLIC_BUILDING, SHIP, OTHER, UNKNOWN
```

반환 결과는 주 유형, 세부 유형, 스타일, 신뢰도, 시각 품질, 완성도, 해당 신의 선호도,
관찰 근거와 우려 사항이다. 서버는 필드·범위·목록 길이를 다시 검증한다. 신뢰도가 정책의
`minimumConfidence`보다 낮거나 유형이 `UNKNOWN`이면 시각 결과를 기록만 하고 점수에는 반영하지
않는다. 타임아웃, 잘못된 JSON, 모델 비가동도 기존 블록 점수를 유지한다.

신뢰도가 충분할 때 저장되는 범용 평가 점수는 다음과 같다.

```text
STORED FINAL = OBJECTIVE × (100 - visualWeight)% + GOD_VISUAL_PREFERENCE × visualWeight%
visualWeight 범위 = 0..30 (포르투나 30)
```

즉 AI가 관여할 수 있는 최대 비중은 30%다. 건축 퀘스트의 즉시 완료·보상 판정은 서버 블록 점수를
계속 사용하고, 비동기로 도착한 시각 결과는 재사용 가능한 평가 이력과 등장 조건용 최종 점수에
반영된다. 자유 건축 등록·자동 발견·수동 재평가는 모두 시각 분석을 예약한다.

포르투나의 시각 취향도 Java 하드코딩이 아니라 정책 JSON의 `visualProfile`에 있다.

```json
"visualProfile": {
  "preferredStyles": ["modern", "contemporary", "minimalist", "clean geometric"],
  "favoredTypes": ["HOUSE", "TEMPLE", "TOWER", "PUBLIC_BUILDING"],
  "guidance": "형태·비례·입면·공간 구성을 함께 보고 현대성을 평가한다...",
  "visualWeight": 30,
  "minimumConfidence": 0.68
}
```

## 점수 계산

각 scope의 criterion weight를 먼저 100으로 정규화한다.

```text
BUILD scope score      0..100
ENVIRONMENT scope score 0..100
FINAL = BUILD × buildWeight + ENVIRONMENT × environmentWeight
```

포르투나는 BUILD 90%, ENVIRONMENT 10%다. 현재 정책은 모던 재료, 유리 입면, 절제된 재료
팔레트, 대칭·기하 균형, 개방형 공간, 완성된 실내, 조명, 생활 기능을 중심으로 보고 자연광과
지면 결합을 환경 보조점수로 사용한다. 수치가 `target`에 도달하면 해당 criterion은 만점이며
그 이상의 반복 배치는 점수를 늘리지 않는다. `linear`, `sqrt`, `square` 곡선을 지원한다.

등록된 범용 criterion type:

```text
block_count, block_ratio, tag_count, tag_ratio, material_diversity
flower_count, crop_count, crop_diversity
water_presence, water_ratio, water_adjacency, underwater_ratio
biome_match, biome_ratio
interior_volume, interior_quality, lighting, open_space
defensive_structure, decoration_density, displayed_item_count, functional_block_count
height, symmetry, connectivity, palette_cohesion
sky_visibility, ground_contact, underground_ratio
environment_match, environment_water_ratio, environment_vegetation_ratio
environment_lava_ratio, environment_stone_ratio, environment_sand_ratio
environment_snow_ice_ratio, composite
```

정책은 반드시 신 ID, 권장 영역, 작업 상한, BUILD/ENVIRONMENT 합계 100, criterion 목록을
정의한다. 알 수 없는 JSON 필드는 reload 전체를 거절하고 이전 snapshot을 유지한다.

## 현재 분석 결과

결과에는 0~100 최종 점수, 블록 원점수, BUILD/ENVIRONMENT 점수, criterion별 raw/정규화/획득점수,
직접·파생 블록 수, 실제 크기, 주요 재료, 바이옴 분포, 강점·보완점과 제한 경고가 포함된다.
시각 분석이 완료되면 유형·세부 유형·스타일·신뢰도·시각 품질·선호도·완성도·근거도 SavedData에
저장된다. `/mythstructure result <name> <policy>`로 확인할 수 있다.

## 후속 고도화 항목

MVP에서는 묘목에서 자란 나무 전체, 피스톤 이동, 폭발 후 provenance를 정밀 추적하지 않는다.
시각 분석은 블록 색상 기반 소프트웨어 투영이므로 실제 텍스처, 셰이더, 미세한 계단·반블록 형상,
가구 엔티티, 완전한 실내 동선을 보지 못한다. 추후 클라이언트 실렌더 캡처를 선택 기능으로
추가할 수 있지만, 서버 권위 점수와 provenance 검증은 현재 구조를 유지해야 한다.
