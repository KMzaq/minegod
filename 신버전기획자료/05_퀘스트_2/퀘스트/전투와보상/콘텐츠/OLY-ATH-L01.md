# 후미 교대

현행 저술 기획. 실제 서버 등록·구현·플레이 검증 자료가 아닙니다.

- 콘텐츠 관리 ID: OLY-ATH-L01
- 제공자 관리 ID: RPG-S01-007
- 정의 ID: mythictrpg:greek_olympus_athena_rearguard_step
- 이름: 후미 교대
- 실행 검증 완료: 아니오
- 공통 설계 기준: ../../공통설계/전역기준.json

## 상세 설계
- management key: OLY-ATH-L01
- 이름: 후미 교대
- type: learned_skill
- content status: 현행 획득 경로가 완결된 저술 기획; 실제 서버 등록·지급 전
- proposed definition id: mythictrpg:greek_olympus_athena_rearguard_step
- registered definition id: 없음
- 획득: 개별 완결기획의 실제 MAIN 수행과 개인 지도·연습 조건을 만족하여 같은 습득 기술 최초권리를 얻는다. 대사·친구 완료·공용 완료만으로 자동 습득하지 않는다.
- 효과: 시선 반대쪽으로 짧게 물러나 빈 후퇴 공간으로 자리를 바꾼다. 무적·도발·아군 자동 이동 없음.

### 수치
- mana: 20
- cooldown ticks: 160
- distance: 2.4
- move ticks: 4
- invulnerability ticks: 0
- 선후딜: 선딜[0,2), 이동[2,6) tick당 최대0.6, 후딜[6,10). 수평 뒤 방향은 승인 때 고정; 이동 중 수동 이동 합산 안 함, 회전 가능.
- 대상 판정: 본인만. 승인 때 지상·비탑승·유효한 수평 방향 필요.
- 실패 처리: 승인 전 공중/탑승이면 무소모. 승인 뒤 충돌·낭떠러지 앞 정지·기절은 부분 이동만 남고 환불 없음.
- 아군: 아군 밀침·끌기 없음. 엔티티 통과 가능한 공간만, 겹침으로 밀어내지 않음.
- 지형: 플레이어 충돌체 sweep. 지지면 없는 다음 구간·닫힌 문·벽에서 정지. 계단은 기존 보행 step-height 이내만. 낙하/물 위 비행/위험 지형 무시 없음.
- 보스: 위치 검사 계속, 허용 이동을 막는 기믹 구속 중 시전 불가. 장벽 통과 안 함.
- 중첩: 1회 이동. 대시/패링과 공통 잠금으로 동시 시전 불가. 이동속도 버프로 거리 늘지 않음.
- 교체: 습득권이므로 무기 교체 영향 없음, 남은 후딜 유지.
- 연출: 발밑에 짧은 뒤쪽 두 선과 낮은 발소리; 안전 경로 자동 안내로 보이지 않게 함.
- existing support vs extension: 기존: learn_skill·K/J·자원. 신규: E3 충돌/지지면 이동(무적 없음), E4 공통 잠금.

### 전투력 평가
- intrinsicPower: 15
- 실행 검증 완료: 아니오
- acquisition contract ref: ../../인물별완결기획/P0_MAIN-001/03_RPG-S01-007_아테나_완결기획.json#/new_execution_decisions/services/0
- common execution ref: ../공통실행.json

## authoring presentation contract
- relative file: ../../스킬연출/효과별/OLY-ATH-L01.json
- 상태: 채택된 연출·판정 상세 저술. 런타임 필드가 아님.
- precedence: 외형·시각 피드백과 명시된 기하 상세는 이 참조를 적용한다. 원시 피해·마나·쿨·장비값·권리·획득 조건은 기존 카드 유지. prototype 검기 개정은 별도 시험 시편이며 이 콘텐츠에 자동 적용하지 않는다.
- actual game validation: NOT_RUN
