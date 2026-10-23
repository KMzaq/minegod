# 야생 전투·파티 — 검증 기록

2026-10-22, `behaivior`, 게임 **1.0.37**. [작업 인수인계](../../인수인계/브랜치실험/behaivior/WILDERNESS_PARTY_20261022.md), [플레이·명령 안내](../../mythictrpg-main/docs/WILDERNESS_COMBAT_AND_PARTY.md).

## 결과

관련 **12개 namespace, 서로 다른 GameTest 115/115 PASS**, 무기 정의46·전투력 계산36·정책38검사 PASS. 게임 컴파일·JAR 생성, AI 모듈 컴파일/소유권·패키지 검사 PASS(소유551클래스, 게임 중복0). 수치 조회 도구의 3스킬·2장신구·11장비 일관성 검사 PASS.

| namespace | 최종 통과 |
|---|---:|
| mythictrpg_accessories | 16/16 |
| mythictrpg_combat_prototype | 13/13 |
| mythictrpg_content_isolation | 1/1 |
| mythictrpg_party | 4/4 |
| mythictrpg_power | 8/8 |
| mythictrpg_progression_rewards | 8/8 |
| mythictrpg_prototype_catalog | 10/10 |
| mythictrpg_prototype_combat | 15/15 |
| mythictrpg_raid | 4/4 |
| mythictrpg_special_skills | 8/8 |
| mythictrpg_weapon_skills | 20/20 |
| mythictrpg_wild_combat | 8/8 |

[최종 결과](final-results.json)는 초기 matrix와 보완 후 해당 범위의 재실행 결과를 namespace별로 합친 것이다. 동일 검사를 중복 합산하지 않았다. 전체 프로젝트의 모든 GameTest/LLM 대사 품질 통과를 의미하지 않는다.

- [초기 관련 matrix](initial-matrix-results.json): 113/113 PASS, 상세 로그·월드 `mythictrpg-main/build/wilderness_final_20261022`.
- [잔류형 포션 보완](lingering-potion-results.json): 야생8/8 PASS, `build/wilderness_cloud_20261022`. 실제 구름 tick으로 온라인/로그아웃 플레이어의 파티원·자기 동물 보호, 비파티원 공격·이로운 효과·몹 출처 효과 유지를 확인했다.
- [정산 대기 후 복귀 보완](settlement-results.json): 검기13·방어15·시험장10·레이드4 = 42/42 PASS, `build/wilderness_settlement_20261022`. ACTIVE/미완료 정리는 차단하고 정리 완료 후 VICTORY_PENDING은 야생·시험장 사용을 허용하며 이전 전투 scope를 무효화한다.
- [게임 빌드·순수 검사](game-build.log), [최종 API 호환 검사](ai-compatibility.log).

## 초기 실패와 보정

`build/wilderness_first_20261022`, `build/wilderness_second_20261022`의 실패 로그는 보존했다. GameTest 서버의 빈 프로필 캐시, 16자를 넘는 가상 이름, 여러 시험장 fixture의 겹친 영역, GameTest의 기본 PvP 비활성, 같은 틱의 중복 시전으로 인한 fixture 오류를 보정했다. 정상 이름 조회 캐시는 네트워크 없는 전용 fixture에만 설치하고 기존 서비스를 복원한다. PvP도 해당 fixture 동안만 켜고 복원하며 공격 가능 여부를 먼저 검사한다. 운영 권한·중복 시전·전장 격리를 완화하지 않았다.

최종 코드 검토에서 발견한 잔류 포션 소유자 누락과 보상 정산 대기 상태의 과도한 야생 제한은 생산 코드를 수정하고 각각 회귀 검증을 추가했다.

## 최종 JAR

[mythictrpg-1.0.37.jar](../../mythictrpg-main/build/libs/mythictrpg-1.0.37.jar), 17,283,560바이트.

SHA-256: `E1AB8EBBB70F5233C50590B5B93FF463B829226C83A7AF29A0CA60FDD9659659`

[패키지 확인](artifact.json): metadata 1.0.37, 새 전투/파티 클래스와 휩쓸기 보호 mixin 등록 확인, 구버전 인물·Story 정의 재유입0. 이전 1.0.36 JAR과 파일명을 구분한다. Minecraft를 정상 종료한 뒤 기존 게임 JAR을 새 파일 하나로 교체한다. 멀티플레이 서버도 같은 새 파일을 사용한다.

## 확인하지 않은 단계

운영 서버와 실제 런처에는 배포하지 않았다. 운영 부팅/종료, 실제 클라이언트 UI·사람끼리 PvP/파티·밸런스 검증, 새 LLM 요청은 수행하지 않았다. 운영 설정·월드·플레이어 획득 이력은 변경하지 않았다. 서버 PvP 비활성이나 타 모드의 피해 보호는 계속 적용한다.
