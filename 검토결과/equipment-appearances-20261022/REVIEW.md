# 무기·장신구 외형 재사용 검증

2026-10-22 `behaivior`, 게임 **1.0.40**. [사용·작성 안내](../../mythictrpg-main/docs/EQUIPMENT_APPEARANCES.md), [브랜치 인수인계](../../인수인계/브랜치실험/behaivior/EQUIPMENT_APPEARANCES_20261022.md).

## 결과

관련 **10개 namespace, GameTest149/149 PASS**. [실행 결과](gametest-results.json), 상세 로그·격리 월드 `mythictrpg-main/build/equipment_appearances_final_20261022`.

| namespace | 통과 |
|---|---:|
| mythictrpg_accessories | 20/20 |
| mythictrpg_accessory_inventory | 10/10 |
| mythictrpg_blessings | 27/27 |
| mythictrpg_combat_prototype | 13/13 |
| mythictrpg_component_rewards | 3/3 |
| mythictrpg_power | 11/11 |
| mythictrpg_progression_rewards | 8/8 |
| mythictrpg_prototype_catalog | 22/22 |
| mythictrpg_prototype_combat | 15/15 |
| mythictrpg_weapon_skills | 20/20 |

신규 검사는 같은 외형의 서로 다른 정의·이름·속성·실제 인챈트·우클릭 연결, 실제 지급과 개인 획득 이력, 내구도 사용, 단순 외형·다른 사람 장비·정의 태그만 바꾼 위조 거부를 포함한다. 등록 실패·잘못된 희귀도·없는 인챈트는 레지스트리 기반 reload 준비 중 거절하며 전역 스냅샷을 부분 수정하지 않는다.

공통 보상 codec·직접 미확정 지급 거부·정의와 다른 아이템으로 고정하기 거부·자동/선택 보상·오프라인 대기 저장·재접속·직렬화와 영수증 정리 이후 같은 출처의 재수령 방지, 실제 namespaced 명령 파싱과 OP 검사·빈 칸 부족 시 무지급·커서 보유 중 중복 방지·분실품 복구도 확인했다. 장신구 표시 구성요소와 실제 지급/착용/재지급 일치, 같은 외형의 다른 효과, 기존 UUID·쿨타임 유지와 구형 토큰 호환을 검증했다.

획득 이력은 개인4096종/서버65536종으로 늘렸다. 1024종 기록·중복 제거·4096경계·초과 시 기존 기록 보존·저장 왕복·기존 불완전 이력 자동 복구 금지를 검사했다. 저장 schema1과 기존 개인 원장을 유지한다.

[게임 빌드](game-build.log): 컴파일·JAR 및 무기 정의46·전투력 계산36·정책38검사 PASS. [수치 일관성](balance-check.log)은 기존3스킬·2장신구·11장비 PASS다. 신규 예시는 별도 `reuse_demo` 정의이며 기존 시제품 행을 변경하지 않는다.

[AI API 호환 검사](ai-compatibility.log): 새 게임1.0.40 JAR을 입력으로 응답 모듈 main/test 컴파일·소유권·패키지551클래스 PASS, 게임 클래스 중복0을 확인했다.

## 리소스·산출물

[패키지 확인](artifact.json): 사용자 검 PNG2장을 변경 없이 복사했고 기존 장신구4장도 그대로 포함한다. 새 외형2개는 `item/handheld` 모델을 사용한다. 원본과 JAR 내부 PNG 해시가 모두 같으며, 장비4·장신구2 예시 JSON과 새 등록·정의·보상 클래스, 모드 버전1.0.40을 확인했다.

[mythictrpg-1.0.40.jar](../../mythictrpg-main/build/libs/mythictrpg-1.0.40.jar), **19,863,496바이트**.

SHA-256: `0391C3E2483E772391F5F4B61981A43FE814E8D5CEB7966CF4B212D2B833FAFC`

**운영 서버·개인 런처에 설치하지 않았다.** 운영 서버 부팅/중지·실제 Minecraft 클라이언트 아이콘/손 모델 렌더·사람의 플레이 조작·새 LLM 호출은 수행하지 않았다. 사용자는 게임을 정상 종료한 뒤 기존 게임 모드 JAR을 새 파일 하나로 교체한다. 서버/클라이언트는 같은 게임 JAR을 사용한다.
