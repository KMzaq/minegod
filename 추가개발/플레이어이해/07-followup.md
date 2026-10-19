# 재점검·선택 기록 확장·출처 기반 목적 후보

2026-10-18, HanesTest. 개발 게임 **1.0.31 / AI 0.1.36**, 미배포. 사용자의 ‘이전 작업 성공 확인 후 전체 완성’ 요청에 대한 후속 기록이다. **이전 작업은 자기 화면 단계의 부분 완료였으며 전체 완료 조건을 충족하지 않았다.** 이번에도 자동 목적 추론의 품질 게이트를 통과하지 못했으므로 전체 완성으로 보고하지 않는다.

이후 사용자가 설치 모델 비교를 승인했다. 최신 AI 0.1.37의 비교 결과·보완·남은 조건은 [08](08-model-comparison.md)을 따른다. 아래의 모델 비교 허용 질문과 검증 수치는 이 문서를 작성한 당시 기록이다.

## 구현한 확장

### 레이드 선택 원장과 자기 화면

게임의 `RAID_CHOICE`는 기존 원장에 기록한다. 별도 신별 행동 DB나 새로운 용량 정책을 만들지 않는다. 기존 월드 저장 관리·누락 진단·분석 시작 경계·시험 제외를 유지한다.

- 생산자: `RaidChoiceCapture`, 게임 스레드의 ACTIVE 시도·고정 참가 명단만 허용. AI나 C2S 입력으로 선택 사건을 만들지 않는다.
- 원본: 실제 playerId, raidId, runId, decisionId, 기믹 버전, phase, 종류(MECHANIC/SUPPORT/RISK_RESPONSE), 결과(CHOSEN/NOT_CHOSEN/UNAVAILABLE/CANCELLED), test. 위치는 기록 시점의 실제 차원/좌표이며 오프라인이면 위치 미상이다.
- run/player/decision/kind의 동일 최종 결과 재시도는 첫 기록을 보존한다. 같은 ID로 다른 결과를 덮어쓰지 않는다. 저장 실패로 레이드 진행을 막지 않으며 기존 원장 진단을 사용한다.
- 자기 화면: 같은 raid/version/kind를 구분하고 가장 최근 파티션을 표시한다. run당 최신 4기회까지만 반영하며, 3run·8기회 전에는 제한적 관측이다. 선택 수/유효 기회 수와 최근 최대 10run을 표시한다. **여전히 원본 최대 400건 창이지 영구 누적 집계가 아니다.**
- 현재 실제 생산자는 시험 수호자의 봉인 기믹뿐이다. 기존에 안내를 받은 참가자가 접근 가능한 반경에 연속 40tick 있었던 경우에만 비선택을 기록한다. 안내 수신 이력이 없거나 접근이 짧으면 UNAVAILABLE이다. 웅크림은 참여 **시도**이며 개인의 해결 성공/기여도 증명이 아니다. 안내를 실제 이해했다는 심리적 사실도 주장하지 않는다.
- 봉인 완료 또는 기존 보호막 시간 종료에서 기회를 닫는다. 그 전에 시도가 중단되면 부정 선택을 만들지 않는다. 보스 규칙·보상·Story 신호는 변경하지 않았다.
- 모든 시험 기믹 자료는 `test=true`로 기록하고 정식 프로필에서 제외한다. **지원/위험 대응의 실제 생산자, 정식 레이드 기회 규칙은 미연결**이다. 새 카드가 생겼다고 모든 레이드 성향을 측정하는 것은 아니다.

`ActionRecord` v1 형식의 enum 확장이다. 구 JAR이 새 enum을 읽을 수 있다고 가정하지 않는다. 배포 전 월드/원장 백업과 서버·클라이언트 버전 일치가 필요하다.

### 목적 후보 실험 — 기본 비활성

`PlayerIntentPlan`을 대사 생성 앞의 별도 단계로 작성했다. `ENABLED=false`이므로 현재 기본 대화는 이 추가 모델 호출을 하지 않는다. 기존 실험 프롬프트의 `productionPolicy()`도 계속 빈 문자열이다.

후보 연결의 범위:

1. 이미 권한·청중별로 투영되고 예산에 맞춰진 **최종 장면**만 사용한다. 전역 자기 프로필/원장을 읽지 않는다.
2. 최근 최대 12발화 중 현재 플레이어의 발화, 현재 입력, 이미 허용된 출처 표기 기억만 선택한다. 보조 신은 아직 전달되지 않은 현재 입력을 받지 않는다. 최대 15출처/총 6000자, 현재 발화 최대 1000자다.
3. 모델은 발화 프레임과 목적 종류, 짧은 해석, 1~3개의 원문 인용을 낸다. 가정/타인/인용/목적 없음 프레임은 후보를 버린다. 출처 ID·원문 일치·타입·예산을 코드로 검증한다. **인용이 일치한다고 의미 해석까지 맞는 것은 아니다.**
4. 후보는 별도의 생성 참고로만 붙인다. 원본 근거와 분리해 최종 검토의 사실 근거로 스스로 재사용하지 못한다. 후보가 있으면 기존 근거 검토를 요구한다. 후보는 새 수주·보상·게임 실행 동의가 아니다.
5. 비동기 복귀 시 방 token과 기억 revision을 다시 확인하는 연결 코드를 작성했다. 추론 오류는 NONE으로 후퇴하도록 했다. 단, **활성 상태의 실제 방/지식 철회 통합 GameTest는 아직 실행하지 않았다.** 현재 비활성 경로와 순수 계약 회귀 통과를 그 증거로 대체하지 않는다.

후보는 턴 단위 해석이다. 자동 목적의 영속 상태/철회 이력, 장기 자기 화면 반영, 인물별 가설 저장소, 적격 콘텐츠 추천기는 아직 아니다. 특히 현재 기억 공급자의 그룹 관찰 지원 제한을 이 단계가 해결하지 않는다. 일반 NPC에게 신의 지식을 공유시키지도 않는다.

## 실제 모델 결과

운영 기록을 쓰지 않고 동일한 합성 8사례(추측 요청, 정정, 근거 없음, 대리 질문, 가정, 무관한 화제, 레이드 사망, 철회)를 현재 `gemma4:12b`로 검사했다. 추출 → 생성 → 원본 기반 검토와 최대 1회 수정의 시험이다. 이전 156회 프롬프트 비교와는 별도 **40개 사례 실행**이며 모델 호출 횟수가 40회라는 뜻은 아니다.

| 실험 | 사례 실행 | 시험의 발행 가능 초안 | 의미 품질 |
|---|---:|---:|---|
| [intent-v1](../../검토결과/player-understanding-20261018/intent-v1) | 8 | 7 | 가정/자기 평가 질문을 목적처럼 해석; 1개 Ollama 요청 실패 |
| [intent-v2](../../검토결과/player-understanding-20261018/intent-v2) | 24 (각 3회) | 24 | 가정 질문을 실제 욕구처럼 답함, 새 농장 목적 누락, 추측 요청 회피 |
| [intent-v3](../../검토결과/player-understanding-20261018/intent-v3) | 8 | 5 | 프레임 분리 후에도 단순 질문을 목표로 분류; 3개 후보 구조/범위 거절 |

‘발행 가능’은 시험의 기존 구조·근거 검토가 통과했다는 뜻이다. 플레이어 이해의 합격률이 아니다. 특히 [v2 가정 질문](../../검토결과/player-understanding-20261018/intent-v2/hypothetical-1.json)은 실제 욕구처럼 표현했는데도 기존 검토가 통과했다. [v3 추측 요청](../../검토결과/player-understanding-20261018/intent-v3/inferred-1.json)은 원문을 뽑고도 의중을 짐작할 영역이 아니라고 회피했다. 고정 문장 답변을 추가하거나 이의를 제기한 플레이어를 거짓말쟁이로 기록하지 않았다.

v1~v3 평가 도구는 후보 추출 실패 시 그 사례를 실패로 저장하고 중단했다. 따라서 v3의 3개 실패는 프로덕션 채팅이 반드시 중단된다는 뜻이 아니다. 비활성 연결 코드는 NONE 후퇴를 갖지만, 그 활성 통합 검증과 의미 품질 확인은 남아 있다. 원본 JSON을 사후 성공 결과로 수정하지 않았다.

대화/모델 설정은 바꾸지 않았다. 설치된 로컬 생성 모델 목록에 `qwen3-14b:latest`, `gemma3:12b`, `qwen2.5:7b`가 있음을 읽기 전용으로 확인했다. 다른 설치 모델의 비교 평가 허용 여부를 사용자에게 질문했다. 모델 다운로드·유료 API·운영 모델 교체는 수행하지 않았다.

## 완료를 막는 남은 단계

1. 목적 있음/가정/인용/대리 질문/철회와 추론 회피를 안정적으로 구분하고, 캐릭터별·연속 대화·청중 변화에서도 의미 품질을 검증한다. 다른 모델 비교는 사용자 선택 후 진행한다.
2. 통과한 후보에 원본 참조·revision 기반 영속 목적 상태/정정 억제와 최근·누적 자기 프로필을 연결한다. 재접속/재시작 후 낡은 목적이 부활하지 않는 검사도 필요하다.
3. NPC별 허용 관찰·청취·소문 입력의 실제 통합과 늦은 응답 격리 검사를 활성 경로로 통과시킨다.
4. 확정된 정식 레이드 규칙에서만 지원/위험 등의 기회 생산자를 연결한다. 없는 기회를 추론으로 만들지 않는다.
5. 기존 적격 콘텐츠의 제안에 연결하되 수주/소비는 기존 본인 확인과 게임 검증을 거친다. 보상·호감도·가격·난이도·정사는 자동 변경하지 않는다.
6. 그래픽 클라이언트에서 근거 확인·정정·스크롤·재접속과 대화 체감을 확인한다. 운영 배포는 별도 요청 후 진행한다.

## 최종 검증 결과

- 게임 순수 검사: 자기 분석 41 + 원장 604 + 기존 레이드 기하 147 + 타임라인 28 = **820 checks 통과**.
- AI 순수 검사: 목적 후보 22 + 기존 실험 프롬프트 19 + 관점 98 + 주 발화 184 + 보조 발화 43 + 근거 검사 288 + 엔진 114 = **768 checks 통과**. 합계 **1,588 checks**이며 실제 모델 의미 검증과 별개다.
- [선택 기록 GameTest](../../mythictrpg-main/build/raid-choice-20261018-v2/logs/latest.log) **1/1 통과**. 실제 생산자와 봉인 종료 hook, ACTIVE/명단 제한, 중복과 허위 누락 진단 없음, 4가지 관측 조건, 닫기/다시 열기 후 원장 복원을 검사했다. 봉인 tick 전체를 실제 플레이어로 진행한 시험은 아니다.
- [본인 화면 GameTest](../../mythictrpg-main/build/understanding-ui-20261018-v2/logs/latest.log) **1/1 통과**. 최종 버전에서 다른 사용자 토큰/재전송/늦은 화면 요청과 저장 후 정정 표시를 재검증했다. 그래픽 UI 확인은 아니다.
- 두 모듈 컴파일·JAR 성공, AI **512 classes / game overlap 없음**. 기본 경로에서 추론 비활성을 검사했다. 운영 서버 부팅·접속·배포는 하지 않았다.

새 빈 GameTest 디렉터리의 `server.properties` 없음 로그 후 기본값으로 진행해 정상 종료했다. 앞선 선택 fixture에서는 중복 최종 사건을 일반 submit으로 보내 허위 누락 진단이 발생했으며, `submitTransition`으로 수정하고 마지막 fixture에서 rejected=0을 검증했다. 초기 실행을 소급 성공으로 정리하지 않는다. 1차 광범위 GameTest의 기존 1개 실패도 [04 이력](04-implementation.md)에 남아 있으며 전체 게임 회귀 성공 주장은 하지 않는다.

최종 개발 JAR SHA-256:

- `mythictrpg-1.0.31.jar`: `69B4EB601B8889618A27AC6DE739E475382C3A8A6D7596F4AB22809EDE451600`
- `mythai_ai_response-0.1.36.jar`: `13965D6B92C35B58FC324C92C234B6E9170BB9661BE32B8589F010FCA65B345C`

다른 채팅의 미커밋 변경을 보존한 공유 개발 작업본이다. 코드 변경·컴파일·격리 서버 시험·실모델 품질 실패를 구분하며, 이 JAR을 운영 배포 완료로 취급하지 않는다.

## 재현

프로젝트 루트 PowerShell. 각 GameTest는 별도 실행한다. 두 fixture가 동시에 같은 게임 원장 runtime을 교체하지 않도록 namespace를 합치지 않는다.

```powershell
.\dev-tools\run-mythictrpg-gradle.cmd playerUnderstandingTest actionLedgerTest raidAttackGeometryTest raidPatternTimelineTest jar --offline --no-daemon
.\dev-tools\run-mythictrpg-gradle.cmd runRoomIntegrationGameTestServer '-ProomTestNamespaces=mythictrpg_raid_choice' '-ProomTestDirectory=build/raid-choice-20261018-v2' --offline --no-daemon
.\dev-tools\run-mythictrpg-gradle.cmd runRoomIntegrationGameTestServer '-ProomTestNamespaces=mythictrpg_understanding' '-ProomTestDirectory=build/understanding-ui-20261018-v2' --offline --no-daemon
.\dev-tools\run-mythictrpg-gradle.cmd -p ..\mythai-ai-response '-PmythicTrpgApiJar=../mythictrpg-main/build/libs/mythictrpg-1.0.31.jar' playerIntentPlanTest playerUnderstandingPromptTest roomPerspectivePromptTest roomPersonaPromptTest roomReactionPromptTest roomDialogueGroundingTest roomConversationEngineTest verifyEnginePackage --offline --no-daemon
```

모델 평가는 `playerIntentPlanEvaluation`에 새 `-PunderstandingEvaluationOutput=절대경로`와 `-PunderstandingRepeats=횟수`를 명시할 때만 실행된다. CREATE_NEW로 기존 결과를 덮어쓰지 않는다. 최신 프레임 분리 이후 반복 실험의 정확한 입력은 해당 JSON에 있으며 원래 v1/v2 소스와 동일하다고 가정하지 않는다.
