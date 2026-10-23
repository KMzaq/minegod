# 2026-10-21 가호·행운 보상과 전투력 v2

브랜치 `behaivior`, 게임 작업본 `mythictrpg-main`. 기존 다수 미커밋 변경을 보존했다. 이번 작업으로 운영 서버를 중지·시작하거나 JAR·월드·플레이어 데이터를 교체하지 않았다. 실제 GUI/운영 플레이 확인과 격리 GameTest를 구분한다.

## 사용자 결정

- 저항 I~IV, 다른 보상 효과 I~XVII. 힘은 고정/자유 필드 레이드에서 추가 보정.
- 행운은 보상 희귀도/수량과 확률 사건에 적용. 수치 초기안 제안 승인. 치명타 적용 없음.
- 신 등급 6종 확정. 실제 인물별 등급·가호 목록과 등급별 상세 수치는 기획이 추후 결정. 실제 캐릭터 등급을 자동 배정하지 않음.
- 특징적 효과는 해당 신의 명시 허용 목록과 우선순위 사용.
- 가호 고유 보유 점수와 실제 활성 효과 분리, 우유 후 본인 명령 재적용, 강한 임시 효과 종료 후 약한 장기 효과 복귀.
- 기존 고정 보상 유지, adaptive 선택은 명시 opt-in. 귀속 보상 장비는 획득 원장으로 평가, 대응 도구 제외. 실제 귀속장비 재발급 제작은 이번 범위 아님.
- 추가 사용자 결정: 레이드 자체 지급 가호만 ‘가호를 얻지 않는 루트’의 예외다. 레이드 성공 퀘스트가 지급한 가호는 예외가 아니다. 행운은 레이드 직접 보상에도 적용하며 획득 난도는 기획에서 높게 설정할 예정.
- 루트는 고정 직업/독립 캠페인이 아니라 실제 인연·관계·성장·접근 경로의 분류다. 겹치거나 이동해도 관계를 초기화하지 않는다. 인간 경로는 신 가호 보유 중 제한하며 직접 레이드 가호는 제외한다. 포기/복귀 기능은 미확정. 이번에는 범용 가호 보유 조건만 제공하고 실제 루트 선택/적대/관계 규칙을 자동 배정하지 않았다.

설명 원본: 사용자 첨부 `붙여넣은 텍스트.txt`. 그 안의 32000/HP4000/검7000/97% 사례는 비교용 참고이고 원본 공식을 확정하는 근거로 쓰지 않았다.

## 구현

- `BlessingRewardEntry`·AI blessing offer의 상한 통일. `BlessingEffectApplication`은 vanilla의 숨은 효과 갱신 결과를 확인하고 만료된 중간 노드의 장기 효과 유실을 막는다. 보유 가호에 연결된 활성 체인은 일반 물약 경로도 정규화하며 우유 suppression과 흡수 소모량을 유지한다.
- GodDefinition의 optional `god_rank`, `quest/reward/blessing`의 strict 정의/등급별 cap/신별 allowlist·priority/후보 선택. 임시 포션을 빼고 보유 영구 단계 기준으로 결정하며 같은 보상 묶음의 장비/고정 효과를 예상 평가에 포함한다.
- `RewardResolutionService`와 기존 `RewardClaimService`에 adaptive/lucky 확정 연결. 실제 NPC 표/퀘스트/Story/오프라인 로그인 지급 경로 포함. 정의 재로딩·로그인·선택 재시도에서 결과 재선택 금지.
- `RewardClaimState` v6에 named 가호 원장과 동적 보상 source·실제 지급 origin 보존. 가호 대신 고정 선택지를 골라도 완료 receipt 정리 후 source를 다시 열 수 없게 한다. 미확정 선택지를 가진 자동 지급 완료 데이터, 출처/보유권 불일치, 용량 초과를 거절한다. v4 저항 V는 IV로 이관한다. 과거 v1~v5 origin은 LEGACY_UNSPECIFIED로 보수적으로 취급한다.
- 직접 레이드 보상 `RAID_DIRECT`와 레이드 완료 조건 퀘스트 `QUEST`를 분리한다. NPC_DIRECT/STORY_DIRECT는 비레이드로 취급. `hasNonRaidBlessing`과 일반 조건 엔진 `has_non_raid_blessing`/NOT을 연결하여 루트 작성에 사용할 수 있다. 원장이 불가하면 UNKNOWN으로 닫히고 우유 해제는 루트 획득 이력을 지우지 않는다.
- 새 v2 전투력은 공통 순수 `CombatPowerEvaluator`로 평가한다. 현재 효과와 named 가호 intrinsic 분리, 획득 장비 슬롯별 최고와 고유 스킬 중복 방지. 임시 효과 attribute를 영구 체력/공격력과 중복 계산하지 않는다. v1 사용자 정책은 보존한다.
- `RaidCombatScaling`은 실제 참가자의 동일 레이드 대상 근접 공격만 힘 추가분을 적용한다. 실제 등록 지급 방어구의 고보호 곡선은 방어구 파괴 후 상태로 계산한다. invalid item policy가 일반 피격을 예외로 중단하지 않게 한다.
- 행운 풀은 희귀도 가중치/허용된 수량 보정을 사용한다. 개인 Story WEIGHTED_ONCE의 명시 luckQuality만 적용하며 roll/outcome을 저장한다. 정적 reload 검증과 live God/rank 참조 검증을 분리해 첫 부팅 순서 문제를 해소한다.

계약/수식/설정 예시: [가호·보상·전투력 v2](../../../mythictrpg-main/docs/BLESSING_REWARD_POWER_V2.md). 기획 전달: [버프 목록·작성 항목](../../../전달자료/blessing-reward-planning-20261021.md).

## 검증

검증 결과는 이 절에 마지막 실행 결과를 기록한다. 소스에 테스트가 있다는 사실만으로 통과로 취급하지 않는다.

- Java 컴파일 및 핵심 순수 검사: `combatPowerPolicyTest` 38, 내부 `CombatPowerEvaluatorTest` 33, `blessingSelectorTest` 39, `luckSelectorTest` 24개 검사 통과(합134).
- 첫 GameTest 실행 `build/reward-progression-core-20261021`: 가호20/21 통과. 새 손상 저장 테스트의 선택지가 1개여서 기존 2~6개 사전검사에 막혔으며 fixture를 수정했다. power는 새 피해 테스트의 기대값에 바닐라 방어력 단계를 누락하여 실패했다. 후속 검증 결과는 아래에 추가한다.

### 최종 실행 결과

- 게임 `jar`, `compileTestJava`, 위 순수 검사134개 통과.
- AI 응답은 새 게임 JAR 기준 `compileJava`, `verifyEngineOwnership`, `verifyEnginePackage` 통과. AI 소유551클래스, 게임 클래스 중복 없음. 실제 LLM 호출/대화 품질 검증은 하지 않았다.
- `build/reward-origin-final-20261021/results.json`: minecraft198, 가호27, 생성보상6, 참여퀘스트5, Story choice1/evidence1 통과. power와 기존 raid의 실패는 아래 재검증으로 해소했다.
- `build/reward-combat-verified-20261021/results.json`: 가호27/27, power8/8, raid4/4 모두 통과. 해당 최종 8묶음의 고유 GameTest는 **250/250 PASS**다. 전체 프로젝트의 모든 namespace를 새로 실행했다는 뜻은 아니다.
- 실제 힘 XVII/날카로움200 근접 공격, 충전/치명타/다른 대상/투사체 경계, 고보호/특수 보호/방어구 파괴/흡수, 일반 물약 재투여, 우유/재접속/재적용, named 원장/저장 상한/receipt 정리, 행운 직접 지급 및 실제 레이드 승리 경로, 퀘스트와 레이드 origin 분리, 실제 Story 조건의 인간 경로 판정 포함.
- 수정한 테스트 기대값은 실제 바닐라 가죽 방어구의 기본 방어력을 포함한다. `attackStrengthTicker`의 실제 선언 클래스는 LivingEntity다. 기존 동기 raid fixture는 실제 ticket/entity 준비를 기다린 뒤 검증하도록 보완했으며 운영 준비 검사를 완화하지 않았다.
- 추가 선행 회귀 `build/reward-progression-regression-20261021/results.json`은9묶음256/256 통과(출처 추가 전). 최종250과 중복되므로 합산하지 않는다.
- 관련 문서 로컬 링크40개, JSON예시25개(기존 rewards 속성 조각2개는 객체로 감싸 파싱), `git diff --check` 통과.

개발 산출물 SHA-256:

| 파일 | SHA-256 |
|---|---|
| `mythictrpg-main/build/libs/mythictrpg-1.0.36.jar` | `BF63AD16224C47D3727B8787D03E85597D68C4978B75D6B48CEB41D3B78FE9BA` |
| `mythai-ai-response/build/libs/mythai_ai_response-0.1.50.jar` | `9BA722021164888214081229A07DC0AD6D60995AE62235B81E4021E9091423A5` |

기존 개발 버전 번호를 유지한 로컬 산출물이다. 운영/클라이언트 JAR 배포는 하지 않았다.

## 남은 기획·운영 작업

- 실제 NPC 등급/허용 가호/등급별 cap/고유 점수, 실제 지급 장비 정책, 행운 보상 풀/사건 품질은 기획 산출물이다. 예시는 자동 배포되는 정식 콘텐츠가 아니다.
- 세계 기여의 개인/전역 정의, 실제 HP4000 상한 확장, 영구 포화 동작은 질문 후 미확정. 현재 기여 계수0, 순간 포화의 영구 가호 사용 거절, Minecraft 기본 HP 제한 유지.
- 전체 전투력 전후 비교 UI는 이번에 추가하지 않았다. 현재 선택창은 확정 보상 설명, `/mythpower`는 현재 합계/근거를 제공한다.
- 서버/클라이언트 배포, 데이터팩 연결, 실제 플레이에서 초기 밸런스 조정이 필요하다. 기존 다른 대화 품질 작업의 미통과 상태를 이번 자동 테스트 통과로 해소했다고 보고하지 않는다.
