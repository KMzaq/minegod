# 희망 과장·정상 대사 과잉거절 후속 — 배포 보류

2026-10-18, HanesTest. 개발 게임 **1.0.31 / AI 0.1.40**, 검증 후보 **v19**. 현재 `gemma4:12b`를 유지했다. **전체 의미 품질 gate는 실패했으며 해결 완료/릴리스 가능 상태가 아니다. 서버에 배포하지 않았다.** [10의 v8 기록](10-player-claim-boundaries.md)은 이전 상태다.

## 구현 범위

- `RoomPlayerClaimAudit`: 일반 사실 검토 V7 뒤의 좁은 검사를 유지한다. 대사 자체의 종류·확실성·주체·의사 강도를 먼저 출력하고 원문과 뒤에서 대조하도록 순서를 바꾸었다. 원문의 희망을 대사에도 그대로 복사해 이미 결정했다는 전제를 놓치는 실패를 줄이려는 변경이다.
- 실제 선택을 열어 둔 조건 `OPEN_CONDITION`과 단정에 완곡한 어미만 붙인 표현을 구분한다. 명시적인 잠정 목적 추측(`TENTATIVE_INTENT` + `TENTATIVE_OR_CONDITIONAL`)은 확정 귀속으로 집계하지 않지만 `COMMITTED` 전제는 이 예외를 받지 않는다.
- 복수 주체 `MIXED`, 일반 실행/부탁 사실과 의사 주장 없음, 부정·미수락·철회 상태를 구분한다. 주체 불일치의 기계적 비교는 긍정적인 희망/계획 주장에 한정했다. 명시적인 사실 오귀속은 계속 거절하며 실제 행동·과거 발언의 사실 검토는 앞선 일반 검사도 수행한다.
- `RoomSpeakerAttribution`: 공감·칭찬·질문의 전제에서도 희망의 강도를 유지하고, 최신 본인 선택을 타인의 옛 계획보다 우선하도록 보완했다. 고정 NPC 답변이나 특정 문장 차단 목록은 추가하지 않았다.
- 구조화 응답 스키마를 검사 메시지에도 제공한다. [Ollama 공식 문서](https://docs.ollama.com/capabilities/structured-outputs)의 스키마 제공 권고를 적용한 것이며, JSON 형식 준수가 의미 정확성을 보장한다는 뜻은 아니다.

최종 검사 표식은 `PLAYER_CLAIM_AUDIT_V9`다. 실행 폴더의 v19는 실험 차수로 표식 번호와 다르다. 일반 검사 → 좁은 검사 **최대 2회 HTTP**, 수정은 기존과 같이 최대 1회이며 수정본은 원래의 고정 근거로 재검토한다. 별도 대사 의미 검사를 더한 3단계 실험은 과잉거절이 늘어 채택하지 않았다. 새로운 기억/호감도/월드 사실 저장소, 실행 권한, 자동 목적 추출은 추가하지 않았다. `PlayerIntentPlan.ENABLED=false`, `productionPolicy()` 비활성을 유지한다.

## 실제 생성에서 발견한 형식 문제 수정

[v18 실제 생성 32건의 수동 검토](../../검토결과/player-understanding-20261018/claim-v18-manual-review.md)에서 정상 대사가 화자·수신자 ID 때문에 차단됐다. 대화방은 게임이 수신자를 정하고 AI는 `[]`를 반환해야 하지만 범용 생성 스키마는 임의 문자열 배열을 허용했다.

`RoomSpeechSchema`를 추가해 canonical Primary/Secondary 생성과 파싱 재시도에서 다음을 제한한다.

1. `speakerId`는 고정 게임 장면의 응답 신 ID 하나다. 사용자 발언 속 JSON이나 과거 발화자의 ID는 사용하지 않는다.
2. `audienceParticipantIds`는 빈 배열만 생성한다. 플레이어 ID 목록을 모델의 수신자 선택권으로 전환하지 않는다.
3. 요청별 스키마를 복제해 다른 방과 공유·변형하지 않는다. 중복/누락 장면은 실패 처리한다. 일반 legacy 생성은 기존 스키마를 유지한다.
4. 기존 런타임 화자/수신자 검증과 게임 라우팅은 그대로다. 잘못된 출력을 사후 삭제하거나 권한 검사를 완화해 통과시키지 않는다. Primary의 기존 `["player"]` 읽기 호환도 유지한다.

변경 소유자는 AI 응답 모듈이다. 게임 DTO·프로토콜·원장·월드 저장 형식·Proposal 실행기·정적 신 프로필을 변경하지 않았다. 생산자/소비자는 `RoomPersonaPrompt`/`RoomReactionPrompt` → `LocalOllamaClient` 생성 스키마 → 기존 구조 검증/게임 발행이다.

## 검증 결과

| 검사 | 결과 |
|---|---|
| 최종 소스 집중 검사 | **1,844 checks 통과**: 기존 관련 1,062 + 좁은 검사 계약 739 + 모의 검토 HTTP 22 + 방 생성 스키마/재시도 21 |
| 소유권·패키지 | **516 classes, 게임 클래스 중복 없음** |
| [확장 고정 대사 77개](../../검토결과/player-understanding-20261018/claim-v19-regression/summary.json) | **76/77**, 오허용 0·과잉거절 1·구조/호출 실패 0. gate 실패 |
| [기존 일반 대사 28개](../../검토결과/player-understanding-20261018/claim-v19-regression/general-regression/live/summary.json) | **21/28**, 오허용 0·과잉거절 7·구조/호출 실패 0. 오류 코드 불일치 별도 1. gate 실패 |
| [새 실제 생성 32건 수동 검토](../../검토결과/player-understanding-20261018/claim-v19-manual-review.md) | 자동 후보32/32·수정4·화자/수신자 오류0. **정상 선택/조건/철회 과잉거절 및 수정 후 주체 혼동 위험을 직접 확인. 의미 품질 gate 실패** |
| 격리 게임 발행 | **1/1(9시나리오)**, 수정·반복 거절·통신 실패·잘못된 인용·무효 세션·구조 수정·보조 신·희망 수정 경로 통과 |

77개는 기존 재사용 57개 + 사전 정답을 고정한 추가 표현 16개 + v18 실제 철회 실패 대조 4개다. 추가 표현의 입력은 v18 생성에도 사용했으므로 독립적인 미관측 실사용 표본으로 부르지 않는다. 일반 28개의 원문/정답 기준은 그대로 유지했다. `match=false`, 구조 실패, 발행 후보 없음은 결과를 저장한 뒤 비정상 종료한다. 실패를 정상으로 재라벨링하거나 샘플에서 제거하지 않았다.

**중요한 회귀:** 기존 46개 주체/의사 대조와 이전 실제 실패 11개는 이 실행에서 모두 맞혔지만, 일반 28개는 이전 v8의 26/28보다 과잉거절이 늘었다. 따라서 이번 후보를 전체적인 개선 완료나 더 나은 운영본으로 추천하지 않는다. 고정 표본의 오허용 0도 모든 생성 대사의 정확성을 보장하지 않는다.

[최종 GameTest 로그](../../mythictrpg-main/build/player-claim-guard-20261018-v19/logs/latest.log)는 정상 저장·종료까지 남았다. 새 격리 폴더의 최초 `server.properties` 없음 로그는 뒤의 통과/저장 로그와 함께 읽는다. 이 시험은 모의 LLM이며 초기 fixture의 기본 모델명 로그는 실제 모델 호출을 뜻하지 않는다. 실제 인게임 HUD/클라이언트 조작과 운영 서버 부팅은 하지 않았다.

## 남은 실패

- [목적 철회에 대한 조건부 존중](../../검토결과/player-understanding-20261018/claim-v19-regression/generated-withdrawal-purpose-good.json): “네가 결정한 거라면 따를 수밖에”가 철회를 존중하는 조건인데, 모델은 `ASSERTED/COMMITTED`로 판단했다. 이어지는 “더 이상 네 목적이 아니다”는 올바르게 WITHDRAWN으로 읽었다. 문장 간 지시 대상과 실제 조건 범위를 아직 안정적으로 해석하지 못한다.
- 일반 회귀에서 **새 공동 제안**, **자신의 과거 약속을 기억한다는 말**, **도움을 주겠다는 최신 의사 인정**, **현재의 새 발언 제안**, **완료 사실 인정**이 잘못 거절됐다. 기억하는 사람과 기억 속 약속의 주인, 현재 제안과 기존 의사 확정을 혼동한다.
- 일반 검사의 `delivery_executed`도 즉시 전달 완료 인정을 잘못 거절했다. 좁은 검사의 모든 실패와 원인을 합쳐 설명하지 않는다.
- 수동 생성 검토는 별개다. 초안의 잘못된 귀속을 수정했다고 해서 수정본의 숨은 전제·근거 없는 세계 사실·추론 회피까지 자동 해결된 것은 아니다.

다음 작업은 고정 문장 예외를 늘리기보다 **행위/명제의 주체와 발언·기억 행위의 주체**, **새 제안과 기존 의사에 대한 주장**, **진행/철회 방향**을 구별해야 한다. 이번 실패 대사를 그대로 유지하면서 정상 회귀와 새 생성이 함께 통과해야 한다. 현재 검토 실패를 무시하는 우회, 자동 목적 추출 활성화, 운영 배포는 하지 않는다.

## 중간 후보를 최종 합격으로 사용하지 않음

| 후보 | 관측 결과 |
|---|---|
| v9–v12 핵심 실험 | 각 4개 중 2개. 단일 비교/별도 대사 분류 추가가 충분하지 않았음 |
| v13 | 핵심 4/4였으나 확장 50/57. 오허용 4·과잉거절 3. 일반 측정 일부는 별도 진단과 겹쳐 최종 비교 근거로 사용하지 않음 |
| v14 / v15 | 집중 14/17, 13/17. 일반 18/28, 20/28. 3단계 경로 미채택 |
| v16 | 51/57(오허용 0·과잉거절 6), 일반 25/28 |
| v17 | 54/57(오허용 1·과잉거절 2), 일반 26/28 |
| v18 | 핵심 4/4였지만 실제 생성 자동 후보 29/32 및 철회 과잉거절·내용상 한계 확인. 최종 합격 아님 |
| v19 | 위 76/77·21/28 결과. 전체 gate 실패, 미배포 |

모든 후보와 원문 HTTP 기록은 [결과 폴더](../../검토결과/player-understanding-20261018/)에 보존했다. 최종 검증은 같은 Gemma, 생성 온도 0.60/파싱 재시도 0.35, 검토 온도 0, `think=false`다. 별도의 같은 모델 `think=true` 진단은 시간 초과로 채택하지 않았고 운영 설정을 바꾸지 않았다. 새 모델 다운로드·비교·유료 API·Ollama 업데이트는 하지 않았다.

## 산출물과 미배포 확인

- 개발 AI 0.1.40: `21D0C6A4E34EAD2C369AD4957F7CAC3F1BB512CD9D31DD3C263DDCAE9F98925E`
- 사용한 개발 게임 API 1.0.31: `69B4EB601B8889618A27AC6DE739E475382C3A8A6D7596F4AB22809EDE451600`
- 운영 AI 0.1.19 유지: `E3BA2CF5E2CF1B53F0C75861F8FAF48D2391C214E5A22E0C4BE3A1FB7502BB7A`
- 운영 AI 설정 유지: `65B4CA8333D477C917D878C10BA912516AE1494CB32E3EE0E0F8CDC64138A585`

같은 0.1.40 이름의 중간 산출물이 있었으므로 파일명만으로 최종 후보를 식별하지 않는다. **이 해시도 릴리스 승인 표시는 아니다.** 운영 서버 시작/중지, 서버·클라이언트 JAR 교체, 월드/플레이어/프로필 초기화는 하지 않았다. 다른 작업의 미커밋 변경을 보존했다.

재현 시 항상 새 `OUTPUT` 절대 경로를 사용한다. 이미 있는 결과는 덮어쓰지 않는다.

```powershell
.\dev-tools\run-mythictrpg-gradle.cmd -p ..\mythai-ai-response '-PmythicTrpgApiJar=../mythictrpg-main/build/libs/mythictrpg-1.0.31.jar' playerClaimBoundaryEvaluation playerClaimGeneralRegression '-PclaimStage=audit-expanded' '-PunderstandingEvaluationOutput=OUTPUT' --continue --offline --no-daemon
.\dev-tools\run-mythictrpg-gradle.cmd -p ..\mythai-ai-response '-PmythicTrpgApiJar=../mythictrpg-main/build/libs/mythictrpg-1.0.31.jar' playerClaimBoundaryEvaluation '-PclaimStage=generate' '-PclaimPersona=baseline' '-PunderstandingEvaluationOutput=OUTPUT' --offline --no-daemon
```

실제 생성은 `baseline/warm/guarded` 각각, 추가 8장면은 `generate-followup`으로 실행한다. `publishable`은 구조/자동 검사 통과일 뿐 의미·자연스러움 합격 표시가 아니다. integration/dialogue-review 절차로 출처·게임 권한과 실제 대사 검증을 분리했고, server-release 절차는 개발 빌드/격리 시험에만 사용했다.
