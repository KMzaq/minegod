# Simply Swords · Better Combat 연동 — 2026-10-23

브랜치 `behaivior`, 게임 **1.0.41**. 사용자는 Simply Swords 외형·Better Combat 공격 동작을 활용하고 능력은 우리 기획으로만 지정하기로 선택했다. [작성·시험 안내](../../../mythictrpg-main/docs/SIMPLY_SWORDS_INTEGRATION.md), [전체 검증·JAR](../../../검토결과/simply-swords-integration-20261023/REVIEW.md).

## 구현

- `equipment/SimplySwordAppearances.APPEARANCES`에 철 장검·Stormbringer 두 외형을 등록한다. 두 모드가 있을 때만 일반 `SwordItem`을 등록하고 원본 모델/평타 데이터 존재를 검사한다. 모델과 `weapon_attributes`는 원본 ID를 참조하며 리소스나 원본 실행 클래스를 복사하지 않는다.
- 실제 Simply Swords 1.70.2가 타 모드 검 경로에도 고유 패시브를 자동 생성하는 것을 확인했다. 공개 API `WeaponImplicitRegistry.registerWeaponType`로 우리 외형을 고유 능력 없는 예약 유형 `mythictrpg:authored_reward_no_native_implicit`에 명시적으로 연결한다. 새 외형도 공통 초기화 등록에 포함한다. 원본 Simply Swords 무기 자체의 능력은 유지한다.
- 기존 `equipment` 정의·보상 지급·재지급·획득 정체성을 재사용한다. 선택 `requiredMods`는 전체 형식 검증 후 누락 모드만 제외한다. 원본 `simplyswords:*` 직접 지급 정의는 거절한다. `swords_demo` 네 JSON과 OP2 `/mythequipment grant swords`를 추가했다. 기존 `grant examples` 여섯 예시와 장신구는 유지한다.
- `BetterCombatServerMixin`/`BetterCombatBridge`에서 시전·기절 중 요청을 거절하고 보호 대상을 원본 부수효과 실행 전에 제거한다. 패링·회피·PvP 등 기존 판정도 유지한다. `CombatActionInputMixin`/선택형 클라이언트 API 브리지는 입력/준비 공격을 취소한다.
- 무기 스냅샷 `actionLocked` 필드를 추가하고 `weapon-skills-3`으로 변경했다. 게임 JAR은 서버/클라이언트 동시 교체가 필요하다. AI API 기본 빌드 입력도 1.0.41로 맞추고 호환 검사를 수행했다.
- 고정 의존성 다운로드 도구와 `Test-OfflineGameMatrix.ps1 -CombatMods`를 추가했다. 선택 모드 API는 compileOnly, 실제 시험 런타임은 `-PcombatMods=true`로 구분한다. 기본 모드 미설치 실행은 계속 지원한다.

## 검증과 남은 일

실제 모드 포함 GameTest **80/80**, 미포함 **71/71** 통과. 순수 무기/전투력 assertions46+36+38, 최종 게임 main/test 컴파일/JAR, AI main/test API·소유551클래스/게임 중복0 검사 통과. 실제 모드 패시브 자동 부여 수정과 원본 평타 실행 경로에 맞춘 야생 시험 보완을 포함한다. 상세 결과·외부 모드 선택 연동 로그 오류·JAR 해시는 위 보고서를 따른다.

**운영 서버·개인 Minecraft 미배포, 실제 클라이언트 모델/애니메이션/키 조작 및 사람끼리 밸런스 시험 미확인.** 추가 외형과 정식 인물 보상 배정은 후속이다. 기존 전투력 공식은 평타 연속 공격 배율·사거리·쌍수를 자동 평가하지 않으며 이번 작업으로 밸런스를 임의 조정하지 않았다. 이미 지급한 실물 정의를 수정할 때는 기존 안내대로 새 정의 ID 또는 명시적 이관이 필요하다.
