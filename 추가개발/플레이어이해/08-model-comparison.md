# 설치 모델 비교와 목적 추출 보완

2026-10-18, HanesTest. 개발 게임 **1.0.31 / AI 0.1.37**, 미배포. [07 후속 기록](07-followup.md) 이후 사용자가 설치된 로컬 모델 비교를 승인하여 수행했다. **목적 추출 후보는 여전히 `ENABLED=false`이며 전체 시스템 완료가 아니다.** 운영 모델 교체·다운로드·유료 API 호출·서버 시작/중지·월드 수정은 하지 않았다.

**이후 사용자 선택:** 현재 운영 모델만 사용하여 개선을 계속한다. 최신은 [09 — 현재 Gemma 유지 후속](09-current-model.md)이다. 아래의 다중 모델 비교와 재현 예시는 과거 실험 기록이며, 추가 다중 모델 실행 지시로 사용하지 않는다.

## 판단

현재 시험만으로 운영 모델을 교체하거나 자동 목적 기록을 활성화할 근거는 부족하다. Gemma4의 목적 분류는 개선됐지만 미수락 부탁을 본인 목표로 바꾸는 문제가 남았고, 목적 추출이 맞아도 대사 생성·검토가 그 의미와 발화 주인을 보존하지 못했다. Gemma3는 신규 분류 사례에서 유리했지만 기존 사례의 추론 누락/출력 조작 오인이 남는다. 모델 전체의 일반 성능 순위가 아니다.

이번 비교는 추출 전용 **216사례 실행**과 통합 **48사례 실행**, 총 **264사례 실행**이다. 서로 다른 264개 상황이나 LLM 호출 264회를 뜻하지 않는다. 26개 상황을 정책/모델별로 재사용했으며, 통합 사례는 추출·생성·검토·필요시 수정을 포함한다.

분류 → 대사 생성 → 검토를 별도로 평가해야 한다. 모델이 정상 JSON을 내거나 기존 검토가 PASS했다고 플레이어 경험/출처 정확성이 합격한 것으로 처리하지 않는다. 기존 자기 화면, 명시적 목적 메모, 행동·레이드 선택 원장은 유지한다. 이번 변경은 아직 비활성인 추출 후보와 개발 평가 도구에 한정한다.

## 코드 변경

- `PlayerIntentPlan`의 추출 지침을 한국어로 정리하고, 대화 행위(답/평가를 얻고 싶음)와 세계 안의 구체적 목적을 구분했다. 현재 질문만 요약하지 않고 자신의 앞선 근거를 연결하며 가정·인용·타인의 목적·철회를 구분하도록 했다. 고정 NPC 답변이나 특정 문장용 분기를 추가하지 않았다.
- 스키마 속성 순서를 `frame → basis → mode → meaning`으로 고정했다. 기존 `Map.of` 순서의 재현성 문제를 없앤 것이며, 순서 변경 자체가 의미 품질 향상을 입증하지는 않았다.
- 제외 프레임 또는 NONE의 부가 설명/인용은 구조·타입·크기 확인 후 버린다. 채택하는 목적의 원문 인용·sourceId 검증은 그대로 유지했다. NONE을 양성 후보로 승격시키지 않는다. 이 정규화 효과를 의미 이해 향상과 혼동하지 않는다.
- `PlayerIntentModelComparison`과 명시 실행용 Gradle task를 추가했다. 설치 모델 확인, 준비 로드, 합성 입력, 원문 요청/응답, 오류, 시간, 기대 분류·수동 판정 기준을 보존한다. 기존 결과는 CREATE_NEW로 덮어쓰지 않는다. 추출 실패는 실패로 기록하고 통합 시험에서는 NONE으로 후퇴한다.
- 기본 16사례와 정책을 고정한 뒤 만든 신규 10사례를 분리했다. 신규 사례의 출력을 보고 이번 정책을 다시 튜닝하지 않았다. 다음 작업에서 이 사례를 튜닝에 쓰면 더 이상 미사용 검증 집합으로 부르지 않는다.

원본 장면과 추정 참고의 분리, 자기 화면 비공개, 게임 실행 권한, 기억·방 revision 경계는 변경하지 않았다. 자동 목적 영속화/장기 누적/관찰자 저장소/추천기 구현을 이번 비교로 대체하지 않는다.

## 추출 결과

표의 숫자는 **미리 허용한 mode 열거값과의 일치 수**다. `meaning`·근거·대사의 의미 정확도나 실제 유저 만족도 점수가 아니다. 구조 실패는 일치 실패로 센다. 보수적인 NONE과 INTEREST를 모두 허용한 경우도 있다.

| 설치 모델 | v1 기존 지침 /16 | v2 순서 고정 /16 | v3 지침·NONE 정규화 /16 | v3 신규 /10 |
|---|---:|---:|---:|---:|
| gemma4:12b | 12 | 9 | 15 | 9 |
| qwen3-14b:latest | 6 | 6 | 10 | 6 |
| qwen2.5:7b | 10 | 5 | 9 | 7 |
| gemma3:12b | 미실행 | 11 | 12 | 10 |

v1은 3모델, v2/v3/신규는 4모델이다. 각 모델·사례 1회이며 반복 신뢰구간이나 일반화 정확도는 산출하지 않았다. v3는 지침과 파서가 함께 바뀌었으므로 단일 원인 실험이 아니다. 추출 전 준비 로드를 별도 기록하고 요청에는 동일한 45초 상한을 사용했다. 정확한 요청 옵션은 각 `extractionWire`에 있다.

자료: [v1](../../검토결과/player-understanding-20261018/model-compare-v1), [v2](../../검토결과/player-understanding-20261018/model-compare-v2), [v3](../../검토결과/player-understanding-20261018/model-compare-v3), [신규 사례](../../검토결과/player-understanding-20261018/model-v3-holdout). 이 파일들의 manifest에 당시 지침·스키마·사전 판정 기준이 있다.

### 직접 확인한 의미 오류

| 사례 | 관찰 | 해석 |
|---|---|---|
| [Gemma4 / paraphrase](../../검토결과/player-understanding-20261018/model-compare-v3/gemma4_12b-paraphrase.json) | 직접 만나 자기 말이 맞는지 확인하겠다는 말을 ‘정체 확인’으로 확대 | mode가 맞아도 동기는 틀릴 수 있음 |
| [Gemma4 / 미수락 부탁](../../검토결과/player-understanding-20261018/model-v3-holdout/gemma4_12b-unaccepted-request.json) | ‘아직 하겠다고 답하지 않았다’는 근거로 STATED_GOAL ‘유적 조사’ 출력 | 목표 기록에 사용하기에 중요한 오인 |
| [Qwen2.5 / 신규 기록관](../../검토결과/player-understanding-20261018/model-v3-holdout/qwen2.5_7b-archivist.json) | 빈 페이지를 질문하려는 목적을 일지를 찾는 목적으로 바꿈 | 분류 일치와 내용 일치가 다름 |
| [Qwen3 / 미수락 부탁](../../검토결과/player-understanding-20261018/model-v3-holdout/qwen3-14b_latest-unaccepted-request.json) | 설명에는 미수락을 남겼으나 mode는 STATED_GOAL | 문장 의미와 구조화 상태가 충돌 |
| [Gemma3 / injection](../../검토결과/player-understanding-20261018/model-compare-v3/gemma3_12b-injection.json) | 출력 조작 지시를 STATED_GOAL로 취급 | 신규 10/10만으로 활성화할 수 없음 |

기존 `proxy` 사례는 본인의 만남 의도뿐 아니라 명시적 수락 없는 대리 조사를 확정하지 않도록 NONE을 기대했다. Gemma4의 ‘대신 소식 확인’은 친구를 만나려는 본인 욕구로 바꾸는 오류와는 다르며, 수락 여부가 모호한 보수적 판정이다. 이 모호한 사례보다 명백히 미수락을 말한 신규 사례를 중요한 활성화 실패로 본다. 결과를 본 뒤 기대 mode를 바꿔 점수를 높이지 않았다.

## 대사 생성·검토 비교

추출은 Gemma4 v3로 고정하고, 생성과 검토 모델을 함께 바꿨다. 합성 페르소나는 기존의 ‘신중하고 직설적이며 선택의 자유를 중시하는 시험 신’으로 동일하다. 실제 데메테르 등 운영 프로필·관계별 대화 전체를 시험한 것이 아니다. 원본 근거 검토와 최대 1회 수정을 적용했다. 수정에는 원래 근거만 쓰며 후보가 자신의 사실 근거가 되지 않는다.

| 생성 + 검토 모델 | 실행 | 시험 내 발행 후보 | 미발행 원인 | 전체 처리 중앙값 |
|---|---:|---:|---:|---:|
| Gemma4 | 16 | 16 | 0 | 약 4.71초 |
| Qwen3 | 16 | 13 | 검토 응답 오류 3 | 약 12.07초 |
| Gemma3 | 16 | 1 | 청중 규약 거절 11 + 검토 응답 오류 4 | 약 12.25초 |

Qwen3의 세 실패는 `INCONSISTENT_AUDIT_CHECK`다. 지적 없음과 부가 필드의 일관성 검사를 통과하지 못한 응답을 발행하지 않았다. 검사를 느슨하게 바꿔 통과시키지 않았다. 서로 다른 모델을 번갈아 호출하는 비용도 포함하므로 이 시간은 순수 모델 추론 속도의 공정 비교가 아니다. 발행 후보 수 역시 품질 합격 수가 아니다.

[Gemma3 통합](../../검토결과/player-understanding-20261018/model-v3-integrated-gemma3)은 요청한 빈 `audienceParticipantIds` 대신 플레이어 UUID를 넣어 1회 수정 후에도 11개가 구조 검사에서 거절됐다. 나머지 4개 검토 응답 오류는 Qwen3와 같은 일관성 오류였다. 이 사례를 ‘내용이 모두 틀린 15개’라고 해석하지 않는다. 반대로 유일한 발행 후보 [inferred](../../검토결과/player-understanding-20261018/model-v3-integrated-gemma3/gemma4_12b-inferred.json)도 NPC가 ‘내가 왜 이 동네까지 왔는지’라고 주어를 혼동해 의미 품질 합격은 아니다. 게임의 청중 라우팅 규약은 유지했다.

- [Gemma4 / inferred](../../검토결과/player-understanding-20261018/model-v3-integrated-gemma/gemma4_12b-inferred.json): 추출은 만남·직접 대화 목적을 찾았지만 대사는 다시 이유를 말해 달라고 요청했다.
- [Gemma4 / foreign](../../검토결과/player-understanding-20261018/model-v3-integrated-gemma/gemma4_12b-foreign.json): 추출은 다른 플레이어의 발화를 제외해 NONE이었다. 하지만 생성은 원본 장면에 남아 있는 다른 플레이어의 말을 현재 플레이어에게 귀속시켰고 검토도 PASS했다. **추출 입력 격리는 통과했지만 대사 주체 구분은 실패**했다. 금지된 원장을 읽었다는 증거는 아니며 합성 장면의 발화 귀속 오류다.
- [Qwen3 / interest](../../검토결과/player-understanding-20261018/model-v3-integrated-qwen/gemma4_12b-interest.json): 제공되지 않은 루브라스의 신격·성격을 만들어냈는데 같은 모델의 검토가 통과했다.
- [Qwen3 / helping](../../검토결과/player-understanding-20261018/model-v3-integrated-qwen/gemma4_12b-helping.json): 주민에게 물어 친구에게 전하려는 계획을 주민에게 소식을 전달하는 계획으로 바꿨다.

따라서 현 검토기는 실행/출처 오류를 모두 막는 안전 보증이 아니며, 일반적인 질문 회피·추상적 훈계까지 평가하는 만족도 심판도 아니다. 동일 모델의 자기 검토만으로 의미 오류를 해결했다고 간주하지 않는다.

## 다음 구현의 활성화 조건

1. 사람·대상·희망·받은 부탁·본인 수락·가정·철회 상태를 구분하고, 원문 인용 검증과 별도로 **의미/부정/귀속 일치**를 검증한다. 특히 미수락 부탁을 현재 목표로 저장하지 않는다.
2. 후보뿐 아니라 최종 대사의 화자/대상 귀속을 검사한다. 다른 참가자의 말, 출처 있는 소문, 그룹 청중 변화, 정정 후 재질문을 포함한 연속 사례를 추가한다. 모두 친절하게 만들거나 질문별 고정 문장을 늘리는 방식은 쓰지 않는다.
3. 실제 캐릭터·관계·청중별로 답변의 구체성과 불확실성, 정정 반영을 확인한 뒤 비활성 후보의 방 token/기억 revision/늦은 응답을 활성 통합 시험한다.
4. 그 다음 원본 참조를 가진 목적 상태·철회 억제·장기 집계·본인 화면을 연결하고, 게임이 허용한 기존 콘텐츠만 제안한다. 추론으로 수주/소비/보상/관계 수치/정사를 바꾸지 않는다.

정식 레이드의 지원/위험 기회 생산자와 실제 그래픽 UI 검증 등 [07의 남은 단계](07-followup.md#완료를-막는-남은-단계)는 여전히 남는다. 이번 모델 비교 승인이 운영 배포나 새 외부 모델 도입 승인인 것은 아니다.

## 코드 검증·빌드

- AI 집중 검사 **773 checks**: 목적 후보 27 + 기존 실험 프롬프트 19 + 관점 98 + 주 발화 184 + 보조 발화 43 + 근거 검사 288 + 엔진 114. 모두 통과.
- 비교 도구 **26사례 preflight 통과**, 모델 호출 없는 별도 검사다.
- 컴파일/JAR, **AI 512 classes / game overlap 없음** 통과. 이 단계의 Java 코드 변경은 비활성 추출 후보이며 자동 추가 LLM 호출을 켜지 않았다.
- 게임 코드는 이번 비교에서 수정/재빌드하지 않았다. [07의 GameTest 2개](07-followup.md#최종-검증-결과)를 이번에 재실행했다고 보고하지 않는다. 활성 목적 추론 통합 GameTest·그래픽 클라이언트·운영 부팅은 미실행.
- 서버 설정 `ai-dialogue.json` SHA-256은 전후 `65B4CA8333D477C917D878C10BA912516AE1494CB32E3EE0E0F8CDC64138A585`로 동일하다. 운영 JAR·정적 프로필·월드는 교체하지 않았다.

개발 JAR SHA-256:

- AI `mythai_ai_response-0.1.37.jar`: `6D2E2F6774EBE886B27540FE0F54F59D136CF68952642F9A90F2C0A3C2D90862`
- 기존 게임 `mythictrpg-1.0.31.jar`: `69B4EB601B8889618A27AC6DE739E475382C3A8A6D7596F4AB22809EDE451600`

재현: 프로젝트 루트 PowerShell, 새 결과 경로를 지정한다. 모델 호출 task는 일반 check에 포함하지 않는다.

```powershell
.\dev-tools\run-mythictrpg-gradle.cmd -p ..\mythai-ai-response '-PmythicTrpgApiJar=../mythictrpg-main/build/libs/mythictrpg-1.0.31.jar' playerIntentComparisonPreflight playerIntentPlanTest playerUnderstandingPromptTest roomPerspectivePromptTest roomPersonaPromptTest roomReactionPromptTest roomDialogueGroundingTest roomConversationEngineTest verifyEnginePackage --offline --no-daemon
.\dev-tools\run-mythictrpg-gradle.cmd -p ..\mythai-ai-response '-PmythicTrpgApiJar=../mythictrpg-main/build/libs/mythictrpg-1.0.31.jar' playerIntentModelComparison '-PunderstandingEvaluationOutput=C:/Users/ADMIN/Desktop/markmar/검토결과/player-understanding-20261018/new-comparison' '-PunderstandingModels=gemma4:12b,qwen3-14b:latest,qwen2.5:7b,gemma3:12b' '-PunderstandingStage=extract-holdout' --offline --no-daemon
```

`understandingStage`: `extract`, `integrated`, `extract-holdout`, `integrated-holdout`. 통합 단계의 `understandingGenerationModel` 기본값은 `gemma4:12b`이며 생성과 검토 양쪽에 적용된다. `understandingModels`는 추출 모델 목록이다. 모델 선택은 이 개발 프로세스에만 적용된다.

시험일 `/api/tags`에서 확인한 모델 digest(모두 Q4_K_M):

| 태그 | digest |
|---|---|
| gemma4:12b | `4eb23ef187e2c5462566d6a1d3bbbc2f1346d0b4327cbb66d58fffbcc9b2b05c` |
| qwen3-14b:latest | `a5af2573e0ac9accb8945475b80db64726835cd15261756c3dc5d50dca7338f9` |
| qwen2.5:7b | `845dbda0ea48ed749caafd9e6037047aa19acfcfd82e704d7ca97d631a0b697e` |
| gemma3:12b | `f4031aab637d1ffa37b42570452ae0e4fad0314754d17ded67322e4b95836f8a` |

프로젝트 dialogue-review 절차로 추출·생성·검토의 실패를 나누고 의미를 직접 확인했다. integration 절차에 따라 원본/추정/실행 권한을 유지했고, server-release 절차는 개발 빌드에만 적용했다.
