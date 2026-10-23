# Simply Swords · Better Combat 연동 검증 — 2026-10-23

브랜치 `behaivior`, 게임 **1.0.41**, Minecraft 1.21.1 / NeoForge 21.1.248. 사용자가 선택한 범위는 원본 무기의 외형·평타 동작을 쓰고 실제 보상 능력은 우리 기획으로 지정하는 것이다. [작성·사용 안내](../../mythictrpg-main/docs/SIMPLY_SWORDS_INTEGRATION.md), [브랜치 기록](../../인수인계/브랜치실험/behaivior/SIMPLY_SWORDS_INTEGRATION_20261023.md).

## 구현 및 확인

- 별도 일반 `SwordItem` 외형 두 개가 원본 철 장검/Stormbringer 모델과 Better Combat 평타 데이터를 참조한다. 원본 고유 아이템 클래스를 상속하지 않으며 원본 모델·텍스처를 우리 JAR에 복사하지 않는다. 기존 논리 장비 JSON/보상 영수증/획득 이력으로 네 가지 이름·수치·인챈트·선택형 우클릭 예시를 지급한다.
- Simply Swords 1.70.2가 다른 모드의 검에도 경로 이름으로 고유 패시브를 붙이는 것을 실제 실행에서 발견했다. 공개 API의 명시적 아이템 유형 등록으로 우리 외형을 고유 능력 없는 예약 유형에 연결했다. 지급 후 사용·틱·실제 타격에서 원본 능력 구성요소가 생기지 않고 획득 정체성/스킬 권한이 유지되는 것을 검사했다. 원본 Simply Swords 아이템의 동작은 별도 양성 대조군으로 확인했다.
- Better Combat의 실제 서버 평타 요청을 기존 시전/기절 잠금과 대상 보호에 연결했다. 파티원·소유 동물은 원본 공격 코드가 피격 무적·넉백·광역 피해 배율을 바꾸기 전에 제외한다. 실제 주 손/보조 손 타격, PvP 설정, 주민, 패링, 회피, 환경 피해를 검사했다.
- 자기 플레이어용 스킬 스냅샷에 공격 잠금 상태를 추가하고 프로토콜을 `weapon-skills-3`으로 변경했다. 클라이언트 입력 차단/준비 공격 취소 코드는 컴파일했지만 실제 GUI에서 실행하지 않았다. 서버와 클라이언트 모두 새 MythicTRPG 버전을 사용해야 한다.
- 모드가 없으면 새 외형/예시만 비활성화한다. 잘못된 정의는 누락 모드로 숨기지 않는다. 원본 `simplyswords:*`를 보상 장비 정의의 `itemId`에 직접 지정하는 것은 거절한다.

## 검사 결과

[집계 JSON](results.json)은 아래 최종 성공 결과와 원본 결과 로그 경로를 보존한다. 실행 로그도 이 폴더에 `with-mods-*.log`, `without-mods-*.log`로 복사했다. 두 환경 합계는 검사 실행 151건이며 서로 다른 고유 테스트 151개라는 뜻은 아니다.

| GameTest 묶음 | 실제 SS+BC 포함 | 추가 전투 모드 미포함 |
|---|---:|---:|
| combat_compat | 5/5 | 대상 아님 |
| combat_optional | 6/6 | 6/6 |
| party | 4/4 | 이번 환경 미실행 |
| prototype_catalog | 22/22 | 22/22 |
| prototype_combat | 15/15 | 15/15 |
| weapon_skills | 20/20 | 20/20 |
| wild_combat | 8/8 | 8/8 |
| 합계 | **80/80** | **71/71** |

원본 결과는 게임 모듈 `build/swords_combined_final_20261023`, `build/swords_without_mods_20261023`, `build/swords_wild_combined_20261023`, `build/swords_wild_vanilla_20261023`의 `results.json`이다. 최초 combined 실행의 wild_combat는 바닐라 휩쓸기 경로만 호출하던 시험 때문에 실패했다. 모드 설치 시 실제 Better Combat 요청, 미설치 시 기존 바닐라 공격을 실행하도록 시험을 보완한 뒤 양쪽 8/8로 재검증했다. 적 피해와 파티원/동물 무피해·무넉백 검사는 유지했다. 이 재검증이 최초 wild 실패를 대체한다.

초기 검사에서 발견한 패링/회피 시험의 시전 시점 오류도 실제 windup 1tick·대기 후 판정으로 수정했다. 원본 패시브 자동 부여 결함은 실행 코드를 수정했다. 테스트 통과를 위해 보상 정체성 검사를 완화하거나 Better Combat 설정/기존 밸런스를 바꾸지 않았다.

- [최초 컴파일·순수 검사](initial-compile-pure-tests.log): 무기 스킬 정의 46, 전투력 평가 36, 정책 38 assertions 통과.
- [최종 게임 빌드](game-build.log): `compileJava compileTestJava jar` 성공. 최종 소스와 리소스를 포함한다.
- [AI API 검사](ai-api-check.log): 새 게임 1.0.41 API로 main/test 컴파일 및 `verifyEngineOwnership`/`verifyEnginePackage` 성공. AI 소유 클래스 551개, 게임 클래스 중복 0.
- 최종 문서·명령·정의·프로토콜·API 참조를 별도 읽기 전용 검토했다. 실제 클라이언트 렌더/애니메이션은 이 검사 범위에 포함하지 않는다.

## 의존성과 로그 제한

실제 추가 모드 7개의 버전/공식 Modrinth 주소/SHA-512는 [manifest](../../external-mods/combat-compat/manifest.json)에 기록했다. 기존 Architectury 13.0.11과 함께 검사했다. [다운로드 도구](../../dev-tools/Get-CombatCompatibilityMods.ps1)는 버전을 고정하고 SHA-512를 확인하며 개발 폴더에만 저장한다. 게임 JAR에 해당 모드를 합치지 않는다.

실제 조합의 로그에는 Simply Swords의 미설치 선택 연동인 Mythic Metals/Eldritch End 레시피 참조 오류 두 개와 Lootr 클래스 탐색 경고가 있다. 검사 서버는 계속 부팅했고 모든 대상 검사가 통과했다. 따라서 부팅 로그 전체가 오류 없이 깨끗하다고 보고하지 않는다. 새 격리 시험 디렉터리의 최초 `server.properties` 부재, 기존 NeoForge/Gradle deprecated 경고도 기록되어 있다. 이번 요청과 무관한 원본 모드의 선택 연동 콘텐츠는 수정하지 않았다.

## 산출물과 배포 상태

- [게임 JAR](../../mythictrpg-main/build/libs/mythictrpg-1.0.41.jar): **19,911,065 bytes**.
- SHA-256: `9C2553D5AEE0CD81B1D1E57EDFCD31E5C1A3219A8E18C410D2637670FE37AD81`.
- [파일 검사](artifact.json): 버전, 브리지 클래스, 모델2개, 평타 연결2개, 정의4개 포함 확인. Simply Swords/Better Combat의 클래스·외형 리소스·JAR 중복 포함 0. [실제 JAR 메타데이터](jar-neoforge.mods.toml).
- **소스 구현·컴파일·자동 검사·JAR 생성 완료. 격리 GameTest 서버 부팅 완료. 운영 `server/mods`와 개인 Minecraft 미배포. 실제 클라이언트 GUI/모델/공격 애니메이션 및 사람끼리 밸런스 시험 미실행.**
- 다른 버전의 외부 모드, 모든 Simply Swords 무기 외형, 원본 제작/전리품 제한은 이번 검증에 포함하지 않는다. 추가 외형은 공통 등록 목록·모델·평타 연결을 추가하고 동일 검사를 적용한다. 기존 인물/스토리 아카이브나 게임/AI 권한 경계는 변경하지 않았다.
