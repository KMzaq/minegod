# 필요할 때만 참고하는 지원 — 검증 기록

2026-10-20 · `behaivior` · 게임1.0.35 / AI0.1.48 / 콘텐츠0.1.3 · gemma4:12b 유지.

계약은 [19](../../추가개발/플레이어이해/19-need-only-dialogue-support.md)를 따른다. **실제 대사 품질 gate 실패, 운영 서버 미배포.** 조회 정책의 동작과 모델의 의미 정확성을 구분한다.

## 소스 변경

행동 저장·성향 집계·목적 상태·본인 화면은 유지했다. 실제 방 엔진은 관찰 없이 먼저 대화 기억을 준비하고, 기존 분류의 현재 요청 힌트가 허용된 경우에만 관찰 자료를 추가한다. 일반 발화/말했던 내용의 회상은 지원 조회 없이 진행한다. 선택된 자료에도 반복 횟수 기반 관심 꼬리표를 더는 생성 프롬프트에 넣지 않는다. WATCH 출처와 기존 Native 원문 권한·턴 검증은 유지한다.

개인 프로필·목적의 NPC 자동 공개나 비활성 목적 자동 추출을 새로 켠 것은 아니다. 추천이 곧 보상/관계/퀘스트 변경이라는 의미도 아니다.

## 완료한 검사

- AI 전체 `build/check` PASS, AI 소유544클래스/게임 중복 없음.
- 집중 검사: SupportConsultation21, ObservedSupport15, RoomPersonaPrompt184, TurnInterpretation104 PASS.
- `build/need-only-offline-20261020-v2`: 실제 엔진·SQLite·발행, LLM 전송만 모의.15턴/1 GameTest PASS(exit0).
- 첫 `need-only-offline-20261020`는 공개방 검사에서 지침에 등장한 항목 이름을 실제 자료로 오인했다. 실제 구분자 `[OBSERVED_PLAYER_SUPPORT]`를 검사하도록 고쳤고 재실행 통과. 실패 월드는 보존한다.

## 실제 모델 및 추가 회귀

### 실제 실행1 — 11번째 턴에서 거절

`build/need-only-live-20261020`, 모델/운영 설정 유지.11턴 시도 중10턴 발행,11번째 자기 평가 요청은 `GROUNDING_REJECTED`. GameTest exit1. 분류11회·생성12회·검토12회(수정1회). [요청·최종 결과·입력](evidence/live-v1.json), [초안/수정/검토 단계](evidence/live-v1-stages), [실제 전달](evidence/live-v1-delivery).

- 앞10턴은 모두 `use=NONE;observations=0`. 이름·희망·타인 계획 회상에는 Native 원문을 사용했다. 디오니소스에 관한 답은 ‘만나보고 싶다, 아직 결정하지 않았다’를 보존했다. 아폴론 계획은 민수에게 귀속했지만 과거 NPC 답변을 그대로 반복하며 불필요한 조언을 덧붙였다.
- 실제 수확 후 날씨 잡담에는 농사 평가가 없었다. 타인의 농사 취향 언급과 일반 도구 질문도 지원 조회를 열지 않았다.
- 자기 평가 요청에서만 `SELF_ASSESSMENT;observations=3;status=READY`. 실제3건은 성숙한 밀 작물 제거다. 초안은 ‘정성껏 땅을 일구고 밀을 수확’, ‘정성을 다해 가꾸는 과정 자체를 소중히 여기는 사람’, ‘성실함’으로 확대했다.
- 수정은 ‘땅을 일구고’만 없앴고 나머지 과장을 남겼다. 두 번째 검토도 거절하여 이 대사는 발행되지 않았다. 검토기는 적절한 과장 지적 외에, 허용된 주시 근거를 받고도 수확/주시 자체가 확인되지 않았다는 부정확한 이유를 섞었다. 정상 행동 설명 과잉거절과 과장 모두 해소했다고 할 수 없다.
- 행동 자료가 없는 이름 소개에서도 ‘네가 정성껏 가꾸는 그곳’을 덧붙였다. 일반 도구 질문도 ‘농사를 시작하려니’로 읽었다. 이는 행동 DB가 목적을 확정한 결과가 아니라, 입력이 없는 상태에서도 모델이 생성하는 추정이다. 조회 차단만으로 일반 한국어 생성 문제까지 해결된 것은 아니다.

첫 실패를 지우지 않고 검증기만 보완했다. LIVE에서는 거절을 기록한 후 다음 턴도 실행하되 마지막에 거절 건수가1개라도 있으면 GameTest가 실패한다. 거절된 초안을 발행으로 취급하거나 근거를 조작하지 않는다. 오프라인 성공 요건도 그대로다. 보완 후 compile/JAR/package 검사 PASS(544클래스).

### 실제 실행2 및 관련 회귀

`build/need-only-live-20261020-v2`에서15턴 전체를 수행했다.13턴 발행,12·13번째 턴은 `GENERATION_FAILED`, 최종 GameTest exit1. 분류15회·생성15회·검토13회. [입력·결과](evidence/live-v2.json), [분류/생성/검토 로그](evidence/live-v2-stages), [발행 대사](evidence/live-v2-delivery).

| 검사 | 실제 결과 |
|---|---|
| 이름·희망·타인 계획 회상 | Native 원문이 전달되며 이름, 희망/미결정, 민수의 계획이라는 핵심은 유지. 반복·불필요한 조언은 남음 |
| 수확 후 잡담·타인 취향·일반 도구 질문 | 모두 관찰 조회/지원 문맥 없음. 날씨 대사는 농사 평가 없음. 단, 일반 도구 질문을 ‘농사를 시작해보려는구나’로 확대하는 모델 추측은 남음 |
| 직접 자기 평가(11턴) | 관찰3건만 전달. 모델은 ‘밀밭을 돌보며 수확을 준비’, ‘소중히 가꾸고 돌보는 기쁨/진심/성취감’으로 확대. 검토기가 잘못 통과시켜 실제 발행됨: 의미 품질 실패 |
| 화제 전환(12턴) | 지원 문맥 없음. 생성 단계 실패하여 대사 미발행. 자연스러운 화제 전환 대사는 검증 통과가 아님 |
| 개인화된 추천(13턴) | 이 요청에서만 다시 관찰3건 전달. 생성 단계 실패하여 추천 대사 품질 미통과 |
| 정정(14턴) | 지원 문맥 없음. ‘너무 앞서서 해석했다’며 정정을 수용. 다만 장황한 마음 해석은 남음 |
| 공개방(15턴) | 개인 이름과 주시 자료 미전달. 모델도 비밀 이름을 말하지 않음 |

실제15개 생성 입력의 `ownPermittedMemory`를 모두 검사했다.11·13턴에만 관찰 자료가 있고 나머지13턴에는 없다. 분류 입력은 관찰 자료 없이 만들어진다. 새 정책의 조회 분리는 통과했지만, 필요한 상황의 대사와 후속 생성까지 포함한 전체 품질은 실패다. 실패2턴은 `GENERATION_FAILED`까지 확인했고 HTTP/파싱/출력 잘림 중 어느 원인인지는 현재 수집 자료만으로 확정하지 않는다. 권한 실패로 단정하지도 않는다.

15턴 완료/실패 응답까지16.098–34.880초, 평균22.937초(생성 실패2턴 포함). 직전 버전과 입력·출력·실행 조건을 통제한 A/B가 아니므로 속도 향상으로 주장하지 않는다. 조회 선택을 위한 별도 LLM 호출은 추가하지 않았다.

게임 집중 단위 검사도 다시 실행했다: PlayerUnderstanding41, PurposeState57, ActivitySupport89, CaptureReadFence27 PASS. 최종 관련 격리 GameTest는 **11/11묶음·13/13회 PASS(exit0)**다. 실제 활동/본인 화면 저장, Native 근거, 기록 저장·재조회, 대사 가드, Native 콘텐츠 의존,15턴 새 경로, 기록 정책, LEGACY/SHADOW 전체 방 경로를 포함한다. [행렬 결과](evidence/regression-results.json), 원본 `build/need-only-regression-20261020/results.json`. 최종 검증기 보완 후의15턴 오프라인 검사도 여기 포함되어 통과했다. 이번 변경에서 게임 전체265회/그래픽 검사를 다시 수행한 것은 아니다.

## 배포 경계

운영 서버/클라이언트 JAR, 모델 설정과 월드에 변경 없음. 그래픽 클라이언트를 새로 실행하지 않았다. 이전 그래픽 통과나 과거 전체265검사를 이번 변경의 결과로 재사용하지 않는다.

작업 전후 보호 파일 해시 일치:

- 운영 `ai-dialogue.json`: `65B4CA8333D477C917D878C10BA912516AE1494CB32E3EE0E0F8CDC64138A585`
- 운영/클라이언트 게임1.0.28: `F7F75C034FA152D996407BCAF0653B0E5823CA831F7B17F4FDE2DAE929AFBAD2`
- 운영 AI0.1.19: `E3BA2CF5E2CF1B53F0C75861F8FAF48D2391C214E5A22E0C4BE3A1FB7502BB7A`

개발 AI0.1.48 최종 검증 JAR SHA-256: `B7B1124DE42A0AC1BCF343D56F1E50166A3A949F09B68B80EE6A6E56DAA7FEE8`. 첫 실행은 검증기 계속 진행 보완 전 `4F7307E5FA3C4769DF447E37742C509A78DDCDFD17D84C647C8E30F4395A1AFC`였다. 런타임 기능 변경 없이 검증기만 바뀐 두 산출물을 구분한다.

## 재현

```powershell
.\dev-tools\run-mythictrpg-gradle.cmd -p ../mythai-ai-response build --offline --no-daemon --max-workers=1
.\dev-tools\run-mythictrpg-gradle.cmd runRoomIntegrationGameTestServer '-ProomTestDirectory=build/새로운실행ID' '-ProomTestNamespaces=mythai_native_dialogue' '-ProomNativeRetrievalMode=NEW' '-ProomSupportDialogue=true' '-ProomLiveDialogue=true' '-ProomAiIntegration=true' '-ProomAiResponseJar=../mythai-ai-response/build/libs/mythai_ai_response-0.1.48.jar' '-ProomAiContentJar=../mythai-ai-content-registry/build/libs/mythaiaicontent-0.1.3.jar' --offline --no-daemon --max-workers=1
```

새 디렉터리를 사용하고 기존 실패 증거/월드를 덮어쓰지 않는다. `roomLiveDialogue`를 빼면 전송만 모의하는 오프라인 검사가 된다. 전체 gate 통과 전 운영 배포 금지.
