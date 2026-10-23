# 경계의 사냥칼 · 멈춤선

현행 저술 기획. 실제 서버 등록·구현·플레이 검증 자료가 아닙니다.

- 콘텐츠 관리 ID: OLY-ART-W01
- 제공자 관리 ID: RPG-S01-009
- 정의 ID: mythictrpg:greek_olympus_artemis_boundary_cast
- 이름: 경계의 사냥칼 · 멈춤선
- 실행 검증 완료: 아니오
- 공통 설계 기준: ../../공통설계/전역기준.json

## 상세 설계
- management key: OLY-ART-W01
- 이름: 경계의 사냥칼 · 멈춤선
- type: weapon_package
- content status: 현행 획득 경로가 완결된 저술 기획; 실제 서버 등록·지급 전
- proposed definition id: mythictrpg:greek_olympus_artemis_boundary_cast
- registered definition id: 없음
- 획득: 개별 완결기획의 별도 SIDE 제작 의뢰를 실제 수행한 개인에게 동일 무기 최초권리를 지급한다. MAIN 자동 보상이 아니며 기존 완료 소급지급·동일ID 추가권리0.
- 효과: 작은 칼날 형상의 직선 투사체 1개로 단일 적을 견제. 무기 자체는 손에 남고 실제 아이템 투척/회수 복제 없음.

### 수치
- mana: 22
- cooldown ticks: 140
- damage: 8
- range: 14
- speed blocks per tick: 1.4
- radius: 0.15
- pierce: 0
- 선후딜: 선딜[0,5), t=5 발사, 후딜[5,11). 최대 비행10tick. 승인 시 조준 고정, 이동/회전 자유.
- 대상 판정: 첫 적대 대상 1개, 유도·벽너머 추적·표식 공유 없음. 투사체 최대 거리에서 소멸.
- 실패 처리: 빗맞음/무적/벽이면 소모. 첫 고체 내부 발사도 소멸. 충돌한 무적 표적을 지나 다음 적을 때리지 않음.
- 아군: 아군·중립 동물은 피해/충돌 소비 없이 통과. 시각 연출이 생존 사냥 성공을 보장하지 않음.
- 지형: 매tick 연속 충돌, 가장 이른 충돌; 동거리 블록 우선. 풀/물은 통과하되 지형 변화 없음. 유리/담장/닫힌 문에서 소멸.
- 보스: 기존 방어·무적·장벽 존중. 보스 관통·약점 자동 명중·스턴 없음.
- 중첩: 시전1회/표적1회. 피해1회 연계만. 힘/날카로움/충전·활 인챈트 미적용.
- 교체: 발사 전 주 손 교체 취소, 발사 뒤 최초 기록으로 진행. 복사 무기로 쿨타임 회피 불가.
- 연출: 가느다란 은색 날과 낮은 바람 소리, 벽/적중 소멸 구분. 영구 자국 생성 없음.
- existing support vs extension: 기존: 무기 등록/우클릭. 신규: E1 실이동 투사체·물리 스킬 피해·E4. AIMED_DAMAGE로 투사체 구현 완료를 주장하지 않음.

### 전투력 평가
- weapon skill K: 18
- equipment E: 10
- weapon W: 5
- 실행 검증 완료: 아니오

### 무기 실물
- grade: 희귀
- base item candidate: minecraft:iron_sword
- actual attack contribution: 5
- total attack with player base 1: 6
- target attack speed: 2.0
- durability candidate: 무한내구: 실물 손상·내구 소모 0. 유한 내구 후보값은 적용하지 않음.
- sharpness: 0
- normal attack: 짧은 칼 외형이지만 기존 검 사거리 유지. 좌클릭은 실제 아이템 속성으로 구현.
- variant components: 공격속도 목표2.0의 modifier는 기본4 기준 -2.0; 모델/속성 일치 검증
- reward binding: item+rightClickSkill, 활 우클릭 입력을 빼앗지 않는 별도 칼 후보
- acquisition contract ref: ../../인물별완결기획/P0_MAIN-001/05_RPG-S01-009_아르테미스_완결기획.json#/new_execution_decisions/additional_side_quests/0

## durability policy
- unbreakable: 예
- physical damage consumption: 0
- new rights from revision: 0
- common execution ref: ../공통실행.json

## authoring presentation contract
- relative file: ../../스킬연출/효과별/OLY-ART-W01.json
- 상태: 채택된 연출·판정 상세 저술. 런타임 필드가 아님.
- precedence: 외형·시각 피드백과 명시된 기하 상세는 이 참조를 적용한다. 원시 피해·마나·쿨·장비값·권리·획득 조건은 기존 카드 유지. prototype 검기 개정은 별도 시험 시편이며 이 콘텐츠에 자동 적용하지 않는다.
- actual game validation: NOT_RUN
