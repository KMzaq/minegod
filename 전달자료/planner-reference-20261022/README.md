# 기획자 추가 연결 자료 — 전투력·작성 규격·실제 ID

> 2026-10-22 후속: 사용자가 기존 시험 콘텐츠의 사용 중단을 요청하여, 이 자료의 기존 God ID 139개·샘플 인물/Story는 [구버전 보관소](../../구버전/20261022-테스트콘텐츠/README.md)로 분리했다. 아래 대응표와 코드 사본은 이관 이전 스냅샷이다. 현재 기본 배포의 등록 인물 목록으로 사용하지 않으며 새 기획의 실제 ID 연결은 다시 작성한다.

2026-10-22 · `behaivior`의 현재 개발 소스 기준. 기존 네 기획 전달서는 방향과 규칙을 설명하지만, 이번에 요청한 실제 설정·계산 코드·ID와 정상 작성 예시를 한곳에 제공하지는 않았다. 이 묶음은 그 빠진 자료와 이전 가이드의 변경 사항을 보완한다.

## 읽는 순서

| 요청 | 자료 | 포함 범위 |
|---|---|---|
| 1. 실제 전투력 계산 | [01-combat-power.md](01-combat-power.md) | 입력 수집, 장비 선택, 체력·회복·방어와 공격 효과의 결합 순서, 수치 예시, 수주 기준과의 연결 여부 |
| 실제 기본 설정 | [combat_power/default.json](reference/mythictrpg-main/src/main/resources/data/mythictrpg/mythictrpg/combat_power/default.json) | 현재 소스의 원본 사본. 아이템 표와 권장 전투력 표는 비어 있음 |
| 실제 계산 코드 | [CombatPowerEvaluator.java](reference/mythictrpg-main/src/main/java/com/sande/mythictrpg/power/CombatPowerEvaluator.java), [CombatPowerRuntime.java](reference/mythictrpg-main/src/main/java/com/sande/mythictrpg/power/CombatPowerRuntime.java) | 평가 수식과 게임 상태에서 가져오는 입력. 관련 정책·상태·실제 레이드 보정 코드도 `reference/`에 포함 |
| 2. 장비·스킬·장신구 작성 | [02-equipment-skills-accessories.md](02-equipment-skills-accessories.md) | 장비 등급의 현황, 방어구·무기, 스킬 연결·지급, 장신구 슬롯·효과의 작성 예시 |
| 3. 실제 인물 ID·퀘스트·Story | [03-god-quest-story.md](03-god-quest-story.md), [god-id-map.csv](god-id-map.csv) | 기본 소스와 서버 시험 데이터팩 구분, 이름↔ID, 이전 가이드의 유효 부분과 정정 사항, 퀘스트·Story 정상 예시 |
| 요청한 레이드 문서 | [RAID_RUNTIME.md](reference/mythictrpg-main/docs/RAID_RUNTIME.md) | 현재 문서 사본. 제목의 HanesTest와 앞부분의 behaivior 후속 기록을 구분해 읽음 |

## 확인된 빈칸

- **장비 7등급의 이름·저장값:** 현재 전용 7등급 정의·검증 체계가 없으며, 임의의 이름·enum·JSON 필드를 추가하지 않았다. 아이템 종류, 인챈트, 전투력의 `intrinsicPower`는 이 7등급을 대신하지 않는다.
- **전투력으로 퀘스트 수주를 제한하는 일반 기능:** 미구현. 현재 자동 연결은 생성 퀘스트의 뒤처짐 보상 단계 보정이며, 최저 수주 전투력 조건은 별도로 연결해야 한다. 설정의 `recommendations`를 수주 제한표로 해석하지 않는다.
- **기획자 관리번호와 God ID의 대응:** 관리번호 원본이 제공되지 않아 빈칸으로 남겼다. 이름이 같아도 서로 다른 ID를 자동 합치지 않는다.
- **개별 정식 콘텐츠:** 실제 인물별 등급·가호·장비·스킬·장신구의 최종 배정과 밸런스는 아직 기획 입력이다. 정의를 작성할 수 있는 공통 기능과 정식 정의가 존재하는지는 별개다.

## 기존 자료와의 관계

기존 가이드 중 유효한 내용은 다시 묶지 않았다. 변경·정정 사항은 01~03에서 설명하며, 원 작업공간의 기존 파일로 연결한다. 이전 `content-authoring-kit-20261004-v2.zip`과 `story-json-reference-20261015.zip`을 전부 재전송하거나 대체하는 묶음이 아니다. 문서의 `../../mythictrpg-main/` 등 링크는 원 작업공간의 기존 자료이며, 외부에 ZIP만 전달할 때에는 이미 전달한 가이드를 함께 참고한다. 이번에 요청한 기본 설정·계산 소스·레이드 문서의 사본은 ZIP 안 `reference/`에 있다.

`examples/`의 JSON은 형식과 연결 방법을 보여 주는 예시다. 정식 인물의 등급·능력·보상을 임의 확정하지 않았고, 이 폴더 자체는 설치용 데이터팩이 아니다. 코드 사본도 읽기용이며 개발 시에는 원 작업본을 수정한다. 배포 전에는 실제 사용할 God ID·등급·조건·보상·표시 문구를 채우고 게임의 파서와 참조 검증을 거쳐야 한다.

## 검증 범위

이번 작업은 문서·예시·기존 파일 사본만 작성한다. 현재 소스의 파서와 필드를 대조하고, JSON 문법·파일 링크·원본 사본 일치 여부를 확인한다. 신규 게임 기능 구현, 컴파일, 서버 실행·배포 또는 이 예시의 실제 인게임 검증을 수행한 것은 아니다. 파일별 해시는 `manifest.json`, 문서 검증 결과는 `validation.json`을 참고한다.
