# 현재 모델 유지 — 목적 표현·발화 출처 후속

후속 개발 AI 0.1.39의 대사 주체·희망/결정 검토는 [10 — 현재 구현과 검증 한계](10-player-claim-boundaries.md)를 우선한다. 이 문서는 0.1.38 당시의 결과이며 자동 목적 추출 비활성 상태는 계속 유지한다.

2026-10-18, HanesTest. 개발 게임 **1.0.31 / AI 0.1.38**, 미배포. 사용자 최신 선택은 **현재 운영 모델 `gemma4:12b`만 사용하여 개선 계속**이다. [08의 다른 설치 모델 비교](08-model-comparison.md)는 과거 이력이며 추가 비교 승인이 아니다.

## 결론과 적용 범위

희망·결정·미수락 부탁·미결정·철회를 구분하는 추출 계약과 인용 검증을 보강했다. 그러나 추출 결과가 맞아도 최종 대사에서 타인의 목적을 가져오거나 희망을 결정으로 과장하는 문제가 남는다. **`PlayerIntentPlan.ENABLED=false` 유지. 자동 목적 추출·저장·게임 반영을 활성화하지 않았다.** 전체 플레이어 이해 시스템 완성이 아니다.

변경이 전부 비활성인 것은 아니다. `RoomSpeakerAttribution`의 화자 ID 구분과 프롬프트 전용 `OTHER_PLAYER`/`OTHER_GOD` 역할 표기는 개발 소스의 주/보조 생성 입력에 적용된다. 게임 원본 history, 참가자, 기록 저장소와 공개 권한은 바꾸지 않는다. 이 표기만으로 모델의 귀속 오류가 해결되었다는 뜻은 아니다. 운영 JAR은 교체하지 않았다.

## 구현

### 1. 발언의 상태를 먼저 표현

`PlayerIntentPlan` 내부 LLM 출력은 `frame → basis → stance → meaning` 순서다. 모델이 서로 모순된 mode와 상태를 따로 선택하지 않도록 mode는 코드에서 파생한다.

| stance | 내부 mode | 의미/한계 |
|---|---|---|
| NONE, UNDECIDED, REQUESTED_BY_OTHER | NONE | 목적 후보를 만들지 않음. 받은 부탁은 수락이 아님 |
| INTEREST | INTEREST | 약한 관심 |
| DESIRED, DECIDED | STATED_GOAL | 희망과 결정은 stance로 구분. 어느 쪽도 서버 수주·실행 증거가 아님 |
| INFERRED | INFERRED_GOAL | 관련 자기 발언에서 얻은 오류 가능한 추측 |
| WITHDRAWN | CORRECTED_GOAL | 철회/정정. 최신 본인 발화의 인용 필수 |

비본인·가정·인용 frame과 NONE 결과는 내용/근거를 비운 정규 NONE으로 만든다. 채택하는 후보는 source ID와 연속 원문 인용을 검증한다. WITHDRAWN이 옛 긍정 계획만 인용하면 거절한다. **인용이 실제로 부정/철회를 뜻하는지까지 코드가 증명하는 것은 아니다.** 부정 대상의 식별과 지속 목적의 상태 전이는 아직 없다.

입력은 이미 청중에게 허용되고 예산에 맞게 축소된 장면이다. 현재 플레이어의 ID가 일치하는 발화와 기존 허용 기억만 사용하며, 전역 프로필·원장·타인의 비공개 목적을 새로 읽지 않는다. 기억 텍스트 안의 주체와 소문/관찰 출처를 완전히 typed 처리한 단계는 아니다.

### 2. 추출 실패와 NONE을 구분

- 기능 OFF, 입력 없음, 오류/거절: `withoutUnderstanding()`으로 기존 생성 입력 유지.
- 정상 추출 결과 NONE: 이번 근거에서 구체적 목적을 못 찾았다는 제한적 안내를 전달. 목적 자체가 없거나 영원히 모른다는 사실로 저장하지 않음.
- 정상 후보: 고정된 해석 정책만 system에 추가하고 모델이 만든 meaning/인용은 user 자료로 분리. 검토 근거는 원래 장면으로 동결하며 모델의 후보가 자기 정당화 근거로 들어가지 않음.
- 해석 안내가 들어간 실제 발화는 기존 검토를 요구. **보조 신의 정상 침묵은 추론 결과가 있더라도 빈 대사 검토를 강요하지 않음.**

기존 게임 스레드 복귀, 방/턴 토큰, 기억 revision, 근거 갱신 검사를 유지한다. 이 비활성 비동기 경로의 실제 활성 GameTest는 이번에 수행하지 않았다.

### 3. 발화 주체의 명시적 구분

`RoomSpeakerAttribution`은 이름 대신 role+ID를 사용한다. 현재 플레이어/다른 플레이어/응답 신/다른 신/미분류를 구분하고, 프롬프트의 다른 참여자 발화에는 원본 역할을 `sourceRole`로 보존한다. 원래 필드·내용·순서와 게임 DTO는 유지한다.

예산 때문에 history가 줄어들면 화자 목록도 같은 목록으로 다시 생성한다. 보조 발언에는 이미 전달된 history만 사용하고 아직 전달되지 않은 currentText는 추가하지 않는다. 검토 장면에도 동일한 출처 메타데이터를 보존한다. 추가 참여자나 지식/공개/실행 권한은 만들지 않는다.

### 4. 평가 도구

`PlayerIntentModelComparison`에 기존 26개 회귀, 새 12개 사례, 온화한 신뢰 관계/경계하는 낯선 관계의 합성 캐릭터 8개 집중 평가를 추가했다. 실제 운영 프로필은 수정하지 않았다. 새 12개는 stance/생성 정책을 정한 뒤 결과를 보기 전에 작성했으며 결과를 본 뒤 기준을 낮추지 않았다. 앞으로 재사용할 때는 더 이상 미지 사례라고 부르지 않는다.

모든 이번 실행의 추출·생성·검토는 `gemma4:12b`다. 후보/원문 인용/원래 생성 장면/실제 요청·응답/검토/수정/최종 후보를 별도 디렉터리에 저장하며 과거 결과를 덮어쓰지 않는다.

## 실제 모델 결과

mode 일치는 사전 허용 mode 집합과의 비교일 뿐, stance·의미·인용의 충분성·최종 대사 품질 합격률이 아니다. `publishable`도 기존 구조 검사와 모델 검토를 통과한 **개발 평가 후보**이지 실제 게임 발행이나 사람의 품질 승인 결과가 아니다.

| 실험 디렉터리 | 용도 | mode 일치 | publishable |
|---|---|---|---|
| `gemma-stance-v1` | 추출 전용 26개 | 26/26 | 생성 미실행 |
| `gemma-stance-v2-integrated` | 출처 목록·최신 철회 인용 | 26/26 | 25/26 |
| `gemma-stance-v3-integrated` | 역할 별칭·후보 해석 정책 | 26/26 | 25/26 |
| `gemma-stance-v4-integrated` | 정상 NONE와 추출 실패 분리 | 26/26 | 25/26 |
| `gemma-stance-v4-new` | 새 12개 | 10/12 | 12/12 |
| `gemma-stance-v4-warm` | 온화한 신뢰 관계 8개 | 8/8 | 8/8 |
| `gemma-stance-v4-guarded` | 경계하는 낯선 관계 8개 | 7/8 | 8/8 |

이번 기록은 추출 전용 26회와 통합 106회, 총 **132사례 실행**이다. 독립적인 132개 상황이나 LLM 요청 132회를 뜻하지 않는다. 최종 v4 묶음은 54사례 실행이며 각 mode/발행 후보 수치를 의미 합격률로 합산하지 않는다.

증거 위치: [이번 평가 디렉터리](../../검토결과/player-understanding-20261018/). 서로 다른 전체 시스템 세션이 아니라 합성 장면을 고정한 추출→생성→검토→최대 1회 수정 평가다. 반복 실행과 프롬프트 버전 차이가 섞여 있으므로 숫자만으로 일반 개선율을 계산하지 않는다.

### 확인된 개선과 남은 문제

- 기존 미수락 부탁은 이번 회귀 실행에서 NONE으로 분류됐다. 명시적으로 우물 수리를 맡겠다는 새 사례는 DECIDED, 허가받지 못했지만 탑에 오르고 싶다는 사례는 DESIRED로 구분됐다.
- 철회 근거를 과거 긍정 발언만으로 만들던 v1 사례는 최신 철회 인용 요구로 차단한다. 이후 해당 사례에서는 현재 철회 발언을 인용했다.
- 일부 추측 요청에서 구체적인 만남 목적을 답했지만, 과장된 간절함·증명 요구·선택권 훈계가 여전히 섞인다. 자연스러움의 안정적인 개선으로 판정하지 않는다.
- **타인 목적 오귀속:** v4 `foreign`은 추출 NONE인데 최종 대사는 다른 플레이어가 한 루브라스 만남 계획을 질문자에게 돌렸다. 온화한 캐릭터에서도 반복되며 기존 검토가 PASS했다. 출처 표기나 같은 모델의 자기 검토만으로 해결되지 않았다.
- **희망→결정 과장:** v4 `explicit`은 DESIRED인데 대사는 직접 찾아가기로 결심했다고 말했다. 게임 실행 권한은 생기지 않지만 플레이어 경험상 잘못된 이해다.
- **의미 덧붙임:** `paraphrase`는 원문에 없는 ‘정체 확인’을 추출 의미에 추가하고, 대사는 떠나지 못함/과정의 어려움 등을 덧붙인다. 정확한 인용이 있다고 의미까지 정확한 것은 아니다.
- **미결정→철회 혼동:** 새 `ambivalent`는 NONE/INTEREST가 기준인데 WITHDRAWN이 됐다. 대사는 미결정을 보존했지만, 영속 목적 상태에 적용하면 부당한 철회가 될 수 있다.
- 새 `reask-after-denial`은 사전 기준 NONE/CORRECTED 대신 약 전달의 INFERRED가 나왔다. 최종 대사는 전쟁 참가를 부정하고 약 전달을 말했으므로 이를 곧바로 ‘전쟁 목적 생성 오류’로 부르지는 않는다. 그러나 현재 정정 대상과 계속 유효한 다른 목적을 단일 후보로 처리하는 계약의 한계가 드러난다.
- 새 `different-new-goal`은 망치 반환의 수신자를 제공하지 않았는데 NPC가 자신에게 가져오라고 말했다. 새 사례가 모두 `publishable`이어도 의미 품질은 합격이 아니다.
- v4 회귀 `helping`은 1회 수정 뒤에도 잘못된 발화 청중으로 구조 검사에서 차단됐다. 권한 검사를 완화해 통과시키지 않았다.
- 경계 캐릭터 묶음 `helping`의 추출은 인용의 `만날`을 `만낼`로 바꿔 원문 일치 검증에서 거절됐다. NONE 판정 성공으로 세지 않고 추출 실패로 기록한다. 기존 생성 경로로 복귀했으며 인용을 임의로 고쳐 통과시키지 않았다. 경계 캐릭터의 `foreign`은 타인의 목적을 가져오지 않았지만, 성격별 일반화 성공이나 안정적인 개선을 입증한 것은 아니다.

## 코드 검증·산출물

최종 개발 검사 **1,055 checks 통과**:

| 검사 | checks |
|---|---:|
| PlayerIntentPlan | 114 |
| RoomSpeakerAttribution | 39 |
| PlayerUnderstandingPrompt | 19 |
| 주/보조/관점 프롬프트 | 184 + 43 + 98 |
| RoomDialogueGrounding | 288 |
| RoomConversationEngine | 114 |
| RecordedNativeSpeechPrompt | 121 |
| RoomEvidenceRefresh | 35 |

별도로 **38사례 preflight**(각각 3개 합성 프로필의 입력 구성, 모델 호출 없음), `verifyEngineOwnership`, `verifyEnginePackage` **514 classes / 게임 클래스 중복 없음**, 최종 Gradle 실행 성공을 확인했다. 침묵 예외 수정은 주 발화 실험 정책을 바꾸지 않았으며 단위 검사로 확인했다. 이번에는 GameTest·실제 인게임 UI·활성 목적 추론 경로를 실행하지 않았고, 이전 게임/화면 GameTest 결과를 재실행으로 합산하지 않았다.

검증 산출물 SHA-256:

- [AI 0.1.38](../../mythai-ai-response/build/libs/mythai_ai_response-0.1.38.jar): `8968A795161C42C31C0A1E8AA028AB21B83827D885D77EDE502CC2823D030189`
- 기존 [게임 1.0.31 API](../../mythictrpg-main/build/libs/mythictrpg-1.0.31.jar), 이번 미변경: `69B4EB601B8889618A27AC6DE739E475382C3A8A6D7596F4AB22809EDE451600`
- 운영 `server/config/mythictrpg/ai-dialogue.json`, 전후 동일: `65B4CA8333D477C917D878C10BA912516AE1494CB32E3EE0E0F8CDC64138A585`

프로젝트 루트에서 재현한다. 출력 경로는 매번 새 경로를 사용한다. 과거 결과가 있으면 덮어쓰지 않고 실패한다. 아래 실모델 명령은 명시적으로 현재 모델만 지정하며 설치 모델 다운로드는 하지 않는다.

```powershell
.\dev-tools\run-mythictrpg-gradle.cmd -p ..\mythai-ai-response '-PmythicTrpgApiJar=../mythictrpg-main/build/libs/mythictrpg-1.0.31.jar' playerIntentPlanTest roomSpeakerAttributionTest playerUnderstandingPromptTest roomPersonaPromptTest roomReactionPromptTest roomPerspectivePromptTest roomDialogueGroundingTest roomConversationEngineTest recordedNativeSpeechPromptTest roomEvidenceRefreshTest playerIntentComparisonPreflight verifyEnginePackage --offline --no-daemon
.\dev-tools\run-mythictrpg-gradle.cmd -p ..\mythai-ai-response '-PmythicTrpgApiJar=../mythictrpg-main/build/libs/mythictrpg-1.0.31.jar' playerIntentModelComparison '-PunderstandingEvaluationOutput=C:/Users/ADMIN/Desktop/markmar/검토결과/player-understanding-20261018/gemma-current-followup' '-PunderstandingModels=gemma4:12b' '-PunderstandingGenerationModel=gemma4:12b' '-PunderstandingStage=integrated-regression' --offline --no-daemon
```

추가 묶음은 새 출력 경로와 `integrated-stance-holdout`, 또는 `integrated-focused` + `-PunderstandingPersona=warm/guarded`로 선택한다. `warm/guarded`는 둘 중 하나를 지정한다. 이 도구는 실제 세션·기억 검색·게임 실행을 대체하지 않는다.

## 호환과 다음 순서

내부 추출 응답의 `mode` 필드는 `stance`로 바뀌었다. 옛 평가 JSON을 현재 parser의 응답으로 그대로 재생할 수 없다. 게임 API·네트워크 protocol·기록 스키마·신 ID·운영 프로필은 이번에 변경하지 않았다. 소스 자체는 전체 dirty 작업공간 위에 있으므로 이 JAR을 독립 배포 승인으로 해석하지 않는다.

1. 현재 모델에서 화자와 행위자/대상/수신자, 희망·결정, 정정 대상과 유지할 다른 목적을 분리하는 의미 평가·검증을 우선한다. 특정 문장용 고정 답변이나 키워드 치환으로 통과시키지 않는다.
2. 같은 모델의 검토 PASS가 오류를 놓친 사례를 재현하고 보완한다. 반복·다중 참여자·실제 선택 프로필·주/보조 및 늦은 응답의 활성 통합 검증을 통과한 뒤 켠다.
3. 그 뒤에 자동 목적 영속화/철회 상태 전이, 관찰자별 허용 근거 연결, 적격 콘텐츠 제안, 장기 누적과 정식 레이드 입력을 순차적으로 연결한다. 추론만으로 퀘스트 수락·보상·관계를 변경하지 않는다.
4. 실제 Minecraft 화면 조작/대화 확인과 별도 승인된 배포가 남는다. 기존 자기 화면과 명시적 비공개 목적 메모를 자동 목적 이해 완료로 부르지 않는다.

프로젝트 integration/dialogue-review 절차에 따라 출처·추론·실행 권한과 모델 평가를 분리했다. server-release 절차는 개발 빌드·패키지 확인에만 적용했고 서버 실행/중지/교체로 확장하지 않았다.
