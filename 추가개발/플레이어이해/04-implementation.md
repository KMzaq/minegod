# 구현 상태

기준: 2026-10-18, HanesTest. 개발 게임 **1.0.31**, AI **0.1.39**. 전체 시스템이 아니라 자기 화면과 레이드 선택 기록·대사 검토 확장 단계의 상태다. 레이드/원장 검증은 [07 후속 기록](07-followup.md), 최신 대사 가드는 [10 발화/의사 경계](10-player-claim-boundaries.md), 자동 추론 보류 배경은 [09 현재 모델 후속](09-current-model.md)을 따른다. 08은 이전 비교 이력이다.

## 구현된 범위

- `/mythprofile`: 본인만 조회하는 별도 Minecraft 화면. 활동 관측·근거·반복 레이드의 약한 목적 후보·직접 적은 목적을 구분한다.
- 기존 행동 원장 위의 비동기 bounded 조회. 별도 원본 DB나 NPC별 행동 원장을 만들지 않았다. 원본 최대 400건/카드별 근거 최대 3건이며 일부 기록이라는 설명을 표시한다.
- 분석 시작 이후만 사용하고 이전 자료를 자동 소급하지 않는다. 시험 플래그 및 현재 시험 레이드 ID를 제외한다. 원장 OFF일 때 켜거나 용량을 바꾸지 않는다.
- 농사, 블록 제거, 처치, 퀘스트 진행, 실제 참가 명단의 레이드 결과를 구분한다. 반복 관측은 인격/실력/선호 확정이 아니다. 레이드 대표 기록자의 행위를 다른 참가자 개인 행동으로 복제하지 않는다.
- 성향 해석 이의 제기/취소, 목적 진술/철회, 분석 중단/새 시작을 원장에 영속화한다. 정정은 원본 삭제가 아니고 NPC에 자동 공유되지 않는다. 저장 확인 전 성공 표시를 하지 않는다.
- 서버가 현재 연결·본인 UUID·발급 토큰·요청 순번·만료·busy·요청 간격을 확인한다. 화면 재개방·종료 뒤 늦은 결과를 적용하지 않는다. 클라이언트 입력에 조회 대상 UUID가 없다.
- 새 AI 대화 정책과 대조 평가 도구를 작성했지만, 품질 미달로 `productionPolicy()`는 빈 문자열을 반환한다. 기존 다른 작업의 대화/기억/근거 검사 코드는 유지한다.
- `RAID_CHOICE` 원장 계약과 기믹/지원/위험 선택 집계: 같은 레이드·버전 분리, run별 최신 4기회 상한, 취소/불가용/시험 제외. 현재 실제 생산자는 비정사 시험 레이드의 봉인 기믹뿐이며 `test=true`라 정식 프로필에 포함하지 않는다. 정식 입력이 없는 항목은 근거 부족이다.
- 별도 `PlayerIntentPlan` 후보 추출·원문 인용 검증·주/보조 비동기 연결 코드를 작성했으나 `ENABLED=false`다. 프로덕션에서 추가 추론 호출이나 NPC 기억 갱신을 하지 않는다. 현재 자기 목적 메모와 자동 의미 추론을 혼동하지 않는다.
- 현재 모델 후속에서 희망/결정/미수락/미결정/철회 stance, 최신 철회 인용, 정상 NONE와 실패의 분리, 보조 침묵 보존을 보완했다. 개발 주/보조 프롬프트에는 ID 기반 화자 구분과 발화 주인 표기를 적용했다.
- 10 후속에서는 기존 일반 검토 PASS 뒤에 같은 모델의 출처·의사 분류 검사를 연결하고, 원문보다 결정/수락을 강화한 경우 코드에서 수정 판정한다. 일반 희망 대사도 검토 대상으로 삼으며 실패 시 발행하지 않는다. 이 대사 가드는 활성 개발 경로지만 비활성 목적 추출·자동 영속화를 켜지는 않는다. 작은 합성 검증을 모든 대사의 정확도 보장으로 해석하지 않는다.

현재 반복 구간은 runId가 있으면 run, 그 외에는 수집 세션/10분 게임 tick 구간이다. 세 구간 이상을 ‘반복 관측’으로 표시하는 초기 휴리스틱이며 장기 심리 모델이 아니다. 원문 보관 일수 제한을 추가하지 않았고 기존 월드 용량 관리 정책과 기술적 색인 한도를 따른다.

## 사용 조건과 UX

추후 동일 게임 JAR을 서버·클라이언트에 설치한 뒤 `/mythprofile`을 연다. **이번 작업에서는 설치하지 않았다.** 기존 원장이 사용 가능해야 분석을 시작할 수 있다. 화면의 ‘분석 시작’은 원장 전체 기록 설정을 활성화하는 버튼이 아니다.

원장이 선택적으로 수집하는 사건만 보이므로 일상 행동 전체가 분석된다고 약속하지 않는다. 분석 중단 후 다시 시작하면 새 경계 이후로 다시 분석하며 과거 기록/정정은 삭제하지 않는다. 현재 목적 입력은 직접 작성한 비공개 진술이다. 자연어 대화에서 자동 추출해 저장한 목적이 아니다.

## 1차 검증 이력 — 최신 결과는 07 참고

| 검사 | 최종 결과 | 증명하지 않는 것 |
|---|---|---|
| `playerUnderstandingTest` | 35 checks 통과 | 실제 UI·LLM 만족도 |
| 기존 `actionLedgerTest` | 604 checks 통과 | 전체 게임 회귀 |
| `playerUnderstandingPromptTest` | 19 checks 통과 | 의미 품질; 기본 비활성화도 검사 |
| 주/보조/관점 회귀 | 184 + 43 + 98 checks 통과 | 실제 캐릭터 대화 품질 |
| 자기 화면 개발 GameTest | 1/1 통과 | 그래픽 렌더링·실제 사람의 조작 |
| 게임 JAR / AI 패키지 | 성공; AI 505 classes, game overlap 없음 | 운영 설치·호환 접속 |
| 실제 로컬 Gemma 비교 | 156회 생성, 품질 합격 아님 | 실제 검색·최종 검토·게임 실행 전체 경로 |

집중 검사 합계 983 checks와 별도 GameTest 1/1이다. GameTest 로그는 [최종 개발 로그](../../mythictrpg-main/build/understanding-ui-20261018-final/logs/latest.log)에 있다. 두 모의 접속자의 토큰 도용·재전송·새 화면의 이전 토큰·닫힌 화면 권한과 저장 후 조회·패킷 codec을 검사했다. 순수 저장 검사는 재시작 복구, 400건 밖 정정 유지, 용량 거절 시 허위 정정 없음도 포함한다.

첫 GameTest 실행에서는 namespace를 넓게 지정해 기존 198개가 함께 실행됐고 `registryallowsonlydefinitionsownedbythegame` 1개가 실패했다. 새 fixture 선택도 잘못되어 해당 실행은 이번 기능의 성공 증거가 아니다. [초기 실행 로그](../../mythictrpg-main/build/understanding-ui-20261018/logs/latest.log)를 보존했고, 전용 namespace로 고친 후 위 집중 검사를 통과했다. **전체 회귀 통과로 보고하지 않는다.**

## 미완료와 다음 순서

1. 실제 클라이언트에서 작은 해상도·긴 한글·스크롤·저장 실패·재접속을 확인한다. 개발 테스트만으로 외형/조작을 완료 처리하지 않는다.
2. 시험 기믹에서 검증한 선택 입력을 향후 확정된 정식 레이드에 연결한다. 지원/위험 대응의 실제 생산자는 아직 없다. 지금 시험 데이터로 정식 성향을 산출하지 않는다.
3. 출처 참조를 검증하는 목적 후보·변화/철회 상태, 최근·누적 프로필을 구현한다. 현재 400건 자기 화면은 이를 대체하지 않는다.
4. 출처 검증 후보의 실제 분류/대화 품질과 활성 경로의 세션 무효화·관찰자별 통합을 통과시킨다. 작성된 비활성 연결만으로 이를 완료 처리하지 않는다.
5. 검증 후 기존 적격 콘텐츠의 제안에 연결한다. 관계 수치·보상·난이도·정사는 자동 변경하지 않는다. 확장 범위는 사용자 결정 후 별도 설계한다.

## 호환·배포 주의

새 원장 enum `PLAYER_UNDERSTANDING_CONTROL/FEEDBACK`과 `RAID_CHOICE`를 추가했다. **새 레코드가 저장된 월드를 구 게임 JAR로 안전하게 다운그레이드할 수 있다고 가정하면 안 된다.** 배포 전 기존 원장·월드 전체를 함께 백업하고, 롤백은 서로 맞는 데이터/모듈 세트로 검토한다. 기능 중단은 이미 쓴 새 레코드를 구 형식으로 변환하지 않는다. 자동 변환/삭제는 구현하지 않았다.

자기 화면 payload는 별도 `understanding-1` 등록이며 기존 대화 protocol 11을 변경하지 않았다. 그래도 새 필수 payload가 있으므로 서버/클라이언트 게임 JAR을 함께 맞춰야 한다. AI 빌드에는 게임 1.0.31 API JAR을 명시했다. 다른 채팅의 미커밋 변경이 포함된 개발 작업본이므로 이 JAR을 별도 승인 없이 운영 배포하지 않는다.

운영 `server` JAR·설정·월드·프로필 교체, 운영 서버 시작/중지, 실제 런처 설치는 수행하지 않았다. 전용 `build/` 개발 GameTest는 자동 종료했다.

## 재현

프로젝트 루트 PowerShell, JDK 21 wrapper 사용. Gradle 캐시 접근은 환경에 따라 승인이 필요하다. 모델 호출은 기본 검사에 포함하지 않는다.

```powershell
.\dev-tools\run-mythictrpg-gradle.cmd playerUnderstandingTest actionLedgerTest jar --offline --no-daemon
.\dev-tools\run-mythictrpg-gradle.cmd -p ..\mythai-ai-response '-PmythicTrpgApiJar=../mythictrpg-main/build/libs/mythictrpg-1.0.31.jar' playerIntentPlanTest playerUnderstandingPromptTest roomPerspectivePromptTest roomPersonaPromptTest roomReactionPromptTest roomDialogueGroundingTest roomConversationEngineTest verifyEnginePackage --offline --no-daemon
.\dev-tools\run-mythictrpg-gradle.cmd runRoomIntegrationGameTestServer '-ProomTestNamespaces=mythictrpg_understanding' '-ProomTestDirectory=build/understanding-ui-20261018-final' --offline --no-daemon
```
