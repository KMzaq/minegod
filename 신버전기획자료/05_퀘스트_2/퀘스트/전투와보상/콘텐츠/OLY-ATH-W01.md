# 퇴로검 · 한 수 남기기

현행 저술 기획. 실제 서버 등록·구현·플레이 검증 자료가 아닙니다.

- 콘텐츠 관리 ID: OLY-ATH-W01
- 제공자 관리 ID: RPG-S01-007
- 정의 ID: mythictrpg:greek_olympus_athena_return_guard
- 이름: 퇴로검 · 한 수 남기기
- 실행 검증 완료: 아니오
- 공통 설계 기준: ../../공통설계/전역기준.json

## 상세 설계
- management key: OLY-ATH-W01
- 이름: 퇴로검 · 한 수 남기기
- type: weapon_package
- content status: 현행 획득 경로가 완결된 저술 기획; 실제 서버 등록·지급 전
- proposed definition id: mythictrpg:greek_olympus_athena_return_guard
- registered definition id: 없음
- 획득: 개별 완결기획의 별도 SIDE 제작 의뢰를 실제 수행한 개인에게 동일 무기 최초권리를 지급한다. MAIN 자동 보상이 아니며 기존 완료 소급지급·동일ID 추가권리0.
- 효과: 짧게 정면 공격 하나를 패링한 뒤 가까운 적 하나에만 자동 응수. 보호자를 무적 탱커로 만들지 않는다.

### 수치
- mana: 28
- cooldown ticks: 200
- parry half angle degrees: 50
- counter damage: 6
- counter range: 2.5
- max blocked events: 1
- 선후딜: 선딜[0,2), 패링[2,6), t=6 성공 때만 응수, 후딜[6,14). 시전 방향 고정, 이동은 보행 허용, 시점 회전은 가능하나 판정 방향 안 바뀜.
- 대상 판정: 승인 방향 정면100도. 피해원 위치/투사체 역진행 방향이 범위 안인 명시 허용 공격 1건. t=6 원 공격자의 현재 히트박스가 거리/시야/각도 안이면 1회. 다른 적으로 자동 재조준 안 함.
- 실패 처리: 패링 실패는 원 피해 그대로. 성공 후 원 공격자가 죽음/멀어짐/무적/벽 뒤면 응수 헛침, 비용 유지. 이미 무적 등으로 취소된 피해는 성공으로 세지 않음.
- 아군: 시전자만 방어. 아군을 가로막거나 아군에게 반격 안 함.
- 지형: 벽 뒤 응수 불가. 발판·문·블록 변경 없음.
- 보스: 보스 허용표의 직접 근접/투사체만 차단; 기믹 광역·즉사·미분류 불가. 응수는 피해만, 스턴/강제 경직 없음.
- 중첩: 첫 1건으로 창 소비. 연속 타격 둘째부터 정상 피격. 패링 자체는 무기 적중 아님; 응수 체력피해>0일 때만 연계.
- 교체: 공통 무기 교체 취소. 패링 성공 뒤 t=6 전에 교체해도 응수 취소, 비용·후딜 유지.
- 연출: 올리브색 짧은 방어선, 성공 1회 금속음, 응수는 작은 직선 베기. 화면 번쩍임/전장 전체 음성 없음.
- existing support vs extension: 기존: 무기 지급·우클릭·자원. 신규: E2 방향/시간창/단일 사건 패링, 조건 응수, E4 입력잠금·피해 사건 순서. 수치는 이번 저술 작업기준이며 시제품·실서버 검증 뒤 보정한다. 실제 구현·게임 검증 전이다.

### 전투력 평가
- weapon skill K: 20
- equipment E: 10
- weapon W: 6
- 실행 검증 완료: 아니오

### 무기 실물
- grade: 희귀
- base item candidate: minecraft:diamond_sword
- actual attack contribution: 6
- total attack with player base 1: 7
- target attack speed: 1.6
- durability candidate: 무한내구: 실물 손상·내구 소모 0. 유한 내구 후보값은 적용하지 않음.
- sharpness: 0
- normal attack: 기존 검 좌클릭, 별도 패시브 없음
- variant components: 모델/속성 component와 지급 변형 일치 필요; 모델 번호 미배정
- reward binding: item 보상+items.skills/rightClickSkill, learn_skill 아님
- acquisition contract ref: ../../인물별완결기획/P0_MAIN-001/03_RPG-S01-007_아테나_완결기획.json#/new_execution_decisions/additional_side_quests/0

## durability policy
- unbreakable: 예
- physical damage consumption: 0
- new rights from revision: 0
- common execution ref: ../공통실행.json

## authoring presentation contract
- relative file: ../../스킬연출/효과별/OLY-ATH-W01.json
- 상태: 채택된 연출·판정 상세 저술. 런타임 필드가 아님.
- precedence: 외형·시각 피드백과 명시된 기하 상세는 이 참조를 적용한다. 원시 피해·마나·쿨·장비값·권리·획득 조건은 기존 카드 유지. prototype 검기 개정은 별도 시험 시편이며 이 콘텐츠에 자동 적용하지 않는다.
- actual game validation: NOT_RUN
