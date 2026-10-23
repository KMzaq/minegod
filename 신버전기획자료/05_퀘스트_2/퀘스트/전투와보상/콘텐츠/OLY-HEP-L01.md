# 충격 흘리기

현행 저술 기획. 실제 서버 등록·구현·플레이 검증 자료가 아닙니다.

- 콘텐츠 관리 ID: OLY-HEP-L01
- 제공자 관리 ID: RPG-S01-012
- 정의 ID: mythictrpg:greek_olympus_hephaestus_yielding_grip
- 이름: 충격 흘리기
- 실행 검증 완료: 아니오
- 공통 설계 기준: ../../공통설계/전역기준.json

## 상세 설계
- management key: OLY-HEP-L01
- 이름: 충격 흘리기
- type: learned_skill
- content status: 별도 비독점 SIDE 획득 연결 채택 완료; 기존 원문 개인 MAIN 보존; 실제 등록·구현 전
- proposed definition id: mythictrpg:greek_olympus_hephaestus_yielding_grip
- registered definition id: 없음
- 획득: 별도 비독점 SIDE 「버티는 대신 흘리는 손」(mythictrpg:hephaestus_yielding_grip_lesson). 공방 Q3 개인 완료 뒤 안전 훈련에서 일반 타격 기준1회, 좌우 일반 넉백감쇠2회, 강제이동은 남고 후속 일반타격만감쇠하는 연속수행1회 및 실제 출구 이동, 본체 지도 확인 후 충격 흘리기 개인 최초 습득1회. 골드/재료0, 연습도 마나18/쿨180tick 유지. 임시 지도허가는 영구권/점수0이고 정식습득과 같은쿨 기록. 원래 손질·신체상태·가호 변경0. 상세: ../../공방/후속제작과훈련.json#/quests/1. 같은 canonical 최초권리이며 재학습 추가0.
- 효과: 자기 자세를 안정시켜 다음 허용 일반 적 공격의 수평 넉백 하나를60% 줄인다. 체력 피해·상태이상은 그대로.

### 수치
- mana: 18
- cooldown ticks: 180
- ready duration ticks: 40
- horizontal knockback reduction: 0.6
- max events: 1
- 선후딜: 선딜[0,4), t=4 준비, 후딜[4,8), 준비[4,44). 이동/회전 자유, 무적 없음.
- 대상 판정: 본인에게 들어온 명시 일반 공격 넉백 사건1개. 감소 전 벡터가0이면 소비 안 함. 수직 벡터는 그대로.
- 실패 처리: 지속 만료/적 공격 없음도 비용 유지. 넉백 없는 피해는 정상 받고 준비 유지. 기절/사망/로그아웃 시 준비 제거, 비용·쿨타임 유지.
- 아군: 아군에게 전달/공유하지 않으며 아군 밀기 사건을 성공으로 소비하지 않음.
- 지형: 벽 통과/지면 고정/낙하 취소 없음. 원래 충돌과 수직 이동 유지.
- 보스: 보스 강제 위치 이동·밀어내기 기믹·낙사 판정용 힘은 제외. 분류 미확인 넉백은 감소 불가.
- 중첩: 같은 준비는 갱신/중첩 불가. 기존 일반 넉백 저항 처리 후 남은 수평벡터에0.4배. 전체 차단으로 가호를 대체하지 않으며 최종벡터가0이면 새 성공 안 셈.
- 교체: 습득 기술, 무기 교체해도 준비·만료시각 그대로. 장신구 교체가 사용 횟수를 재충전하지 않음.
- 연출: 팔 주변 작은 금속 매듭 빛, 소비 때 짧은 낮은 울림. 몸 외형 변경 없음.
- existing support vs extension: 기존: 습득권·K/J·자원. 신규: 일반 넉백 원인 분류와 1회 수평벡터 감쇠 버프. 무적/저항V/상시 속성으로 대체하지 않음.

### 전투력 평가
- intrinsicPower: 12
- 실행 검증 완료: 아니오
- common execution ref: ../공통실행.json

## acquisition ref
- relative file: ../../공방/후속제작과훈련.json
- json pointer: /quests/1

## authoring presentation contract
- relative file: ../../스킬연출/효과별/OLY-HEP-L01.json
- 상태: 채택된 연출·판정 상세 저술. 런타임 필드가 아님.
- precedence: 외형·시각 피드백과 명시된 기하 상세는 이 참조를 적용한다. 원시 피해·마나·쿨·장비값·권리·획득 조건은 기존 카드 유지. prototype 검기 개정은 별도 시험 시편이며 이 콘텐츠에 자동 적용하지 않는다.
- actual game validation: NOT_RUN
