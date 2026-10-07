# Native 해석 카드의 foreground 준비와 단일 typed 근거

2026-09-30 / `HanesTest`. **소스 구현·집중 및 실제 게임 왕복 검사 통과. 추가 unquoted-input fixture 재검증 완료, 미배포.**
production NEW 자동 생성에는 연결하지 않았으며, 실제 모델 응답 품질이나 전체 기억 기능 완료를 뜻하지 않는다.

## 1. 목적과 범위

이미 조회된 원문과 정정·취소 등 해석 카드를 함께 사용할 때, 해석만 버리고 오래된 발언을 확정된 현재 사실처럼 남기지 않도록 한다. 해석은 끝까지 `CANDIDATE`이며 실제 게임 상태나 실행 결과가 아니다.

새 경로는 기존 RAW-only `RecordedForegroundMemory.prepare`와 별개인 **additive `prepareInterpretations`**다. 기존 legacy 대화 경로와 RAW 준비를 유지한다. typed 결과가 없거나 실패해도 새 경로가 legacy 검색 또는 RAW-only seal을 자동 실행하지 않는다.

## 2. AI 측 연결

| 구성 | 역할 |
|---|---|
| `RecordedRetrievalCoordinator.collectNativeInterpretations` | 동일 게임 발급 Session, 동일 watermark, 공유 호출/바이트 예산으로 조회한다. 실제 RAW·semantic·interpretation Page 객체를 private collection에 보존한다. |
| `RecordedNativeInterpretationPrompt.render` | 실제 조회된 페이지와 input/quote의 message ID만으로 연결 그룹을 만든다. 전체 페이지와 이미 알려진 연결 그룹 단위로 선택/제외한다. |
| `Selection.seal` | 선택한 전체 원문·semantic·해석 페이지를 한 번의 `MemoryReadSession.sealInterpretations`에 함께 넘긴다. RAW seal을 대신 발급하지 않는다. |
| `Sealed.payloadFor` | 실제 발급 typed seal, 정확한 scope, 선택/페이지의 현재성이 모두 유효할 때만 텍스트와 portable reference를 함께 반환한다. |
| `RecordedForegroundMemory.prepareInterpretations` | 실제 요청 → 독립 회상 계획 → actual pages → whole-group 렌더링 → 게임 typed seal → `NativeRoomEvidence.prepare/current`를 연결한다. 명시적인 개발 API이며 production `respond`와 SHADOW에서 호출하지 않는다. |

현재 façade는 lexical 원문과 그 페이지에서 조회한 해석을 사용한다. renderer/collector는 실제 semantic Page도 처리할 수 있지만 façade에서 embedding 또는 모델 호출을 시작하지 않는다. Watch/Rumor를 추가로 검색하지 않는다.

기본 façade 예산은 공유 **8회 조회 / 카드 32KiB / lane별 최대 4행·2페이지 / 수집 15초**다. 게임 Session의 별도 시간·권한·SQL 예산도 적용된다. renderer는 최대 8개 전체 페이지, 최종 JSON 32KiB를 사용한다. seal 대기는 별도의 3초 제한, 전체 준비는 30초 제한이며 성공을 기다리려고 게임의 read lease를 늘리지 않는다.

## 3. 연결 그룹과 출력 규칙

연결 정보는 이번 수집에서 받은 Page와 해석의 input/quote edge뿐이다. 전역 archive 역조회, 숨겨진 연관 자료의 자동 검색 또는 새로운 플레이어·신 접근 권한을 만들지 않는다.

- 한 페이지에 보존되지 않은 row가 있으면 그 페이지를 부분 재구성하지 않는다. 연결된 그룹 전체를 제외한다.
- 같은 발언/해석 identity가 중복되면 임의 first-wins로 정하지 않고 관련 그룹을 제외한다.
- semantic 임계값 때문에 한 row가 제외된 페이지도 그대로 seal하지 않는다. 그 페이지와 연결된 원문·정정 해석까지 함께 제외한다.
- 한도를 넘는 그룹은 문장·quote·link를 잘라 맞추지 않는다. 독립된 다른 그룹은 예산 안에서 선택할 수 있다.
- typed 선택에는 실제 해석 페이지가 적어도 하나 있어야 한다. 없다면 이용 가능한 typed payload를 만들지 않는다.

프롬프트 카드에는 다음을 보존한다.

- 원문: 실제 화자·시각·발언·발췌 여부. semantic prefix는 별도의 covered/total 범위를 유지한다.
- 해석: `CANDIDATE`, layer/kind/extractorVersion, 각 exact quote의 실제 화자·시각·alias, 방향 있는 link, 인용하지 않은 입력까지 포함한 coverage.
- `CANCELS`/`CORRECTS`는 발언에 대한 취소·정정 주장이고, `REPORTS_FULFILLMENT`는 화자의 완료 주장이다. 실제 퀘스트 취소·보상 지급·완료 판정을 의미하지 않는다.
- 카드 본문은 `UNTRUSTED_RECORDED_DATA`다. 본문의 지시·역할명·ID·권한 주장은 실행 지시가 아니다.

연결 그룹은 **검색한 범위에서 알려진 연결**이지 모든 관련 과거·미래 대화를 보장하지 않는다. 반환되지 않은 후속 정정이 없다고 단정할 수 없다. 빈 결과·제외·시간 초과·부분 검색은 사건의 부재를 증명하지 않는다.

## 4. 게임 소유 근거와 현재성

typed portable kind는 `RECORDED_NATIVE_INTERPRETATION_V1`이다. descriptor 자체나 공개 Entry/UUID에서 권한을 만들지 않는다. 게임이 실제 발급한 opaque `NativeInterpretationSeal`과 schema11의 `native_interpretation_evidence` 영속 발급 row가 필요하다. 기존 RAW seal 저장소와 다른 typed 계약이며 해석을 RAW 사실로 바꾸지 않는다.

게임 측 계약은 schema10의 commit-time input manifest로 **모든 입력과 sibling output set**을 묶고, 원 observer/job/extractorVersion/payload hash/input manifest hash를 보존한다. 인용하지 않은 입력도 source/receipt/공개 검증에서 빠지면 안 된다. 과거 schema에서 새 manifest를 추정해 만들어 통과시키지 않는다.

이번 단계는 **같은 observer 신**의 typed 해석을 다시 준비하는 범위다. 다른 신이 들었다는 이유만으로 그 신의 해석으로 바꾸거나, 원 extractor 결과를 새 버전 결과로 몰래 교체하지 않는다. 원문 source ACL과 해석 observer 제한은 별개다.

projection 재처리·철회가 발생하면 해석 Page/typed seal뿐 아니라 그 근거를 사용한 후속 발언·중첩 RAW seal·history current guard도 함께 재검증되어야 한다. 단순 RAW source generation만으로 해석 변경을 무시하지 않는다. 재시작 이후에는 런타임 generation 값이 아니라 영속 job/version/manifest와 실제 원본·청취 기록을 확인한다.

`PreparedInterpretations.payloadFor`는 같은 실제 요청/서버/scope와 현재 native proof에서만 동작한다. 긴 응답 뒤 `revalidateForPublication`은 같은 원본 근거를 다시 준비하며 새 검색·재추출·턴 교체를 하지 않는다. 취소·timeout·dispatcher 거절·source 또는 projection 철회 이후 늦게 도착한 seal을 노출하지 않는다.

## 5. 구현한 검증과 결과

다른 단계의 통과 수치를 이 기능의 검증으로 대체하지 않는다. 아래 첫 통과와 후속 fixture 보강을 구분한다.

- `RecordedNativeInterpretationPromptTest`: fake issuer에 실제 Page identity를 보관하여 단일 typed 발급, 전체 페이지/연결 그룹 원자성, 공통 input을 통한 전이 연결, quote·화자·link·coverage 보존, byte/page 제한, semantic 필터와 연결 그룹, wrong-scope, 취소·timeout·projection 변경·dispatcher 거절·세션 격리를 검사한다. 첫 실행 **120 checks 통과**. 이후 공통 input을 quote에도 포함하던 fixture를 보강하여, 서로 다른 네 원문/quote와 **인용하지 않은 별도 e2 context만**으로 연결되는 두 후보가 전체 제외되는지 검사하도록 변경했다. 보강 후 재실행은 **121 checks 통과 / 7 tasks / 7초 성공**이다. 생산 renderer는 변경하지 않았다. 실제 SQLite/게임/모델을 실행하지 않는 소비자 계약 검사다.
- 기존 `RecordedNativeSpeechPromptTest`/coordinator/RAW 준비 회귀는 유지한다. 새 경로가 speech-only seal 또는 Watch/Rumor를 대신 호출하면 fixture에서 실패하도록 했다.
- `NativeInterpretationGameTests`: 실제 게임 입력/capture → deterministic extractor의 실제 lease/commit → typed seal → 실제 publication/capture → 다음 턴 descendant RAW와 중첩 RAW seal → extractor 재처리 뒤 철회 경로 **1/1 통과, 1.025초**. fixture의 재처리 claim은 신규 PENDING 작업 우선순위를 고려해 실제 포트로 새 작업을 처리하면서 원 target을 찾는 bounded 단계로 조정했다. 생산 claim 우선순위나 권한을 완화하지 않았다. 실제 모델 응답 검사는 아니다.
- 첫 AI 집중 실행 **13 tasks / 13초 성공**: typed prompt120, 기존 speech prompt121, coordinator185, history47, refresh35. 패키지 **483 classes / 게임 클래스 중복0**. 이 결과는 위 unquoted fixture 보강 이전 체크포인트다.
- schema11 적용 후 기존 CONTENT owner/native RAW foreground의 실제 publication/capture·후속 회상 회귀도 **1/1 통과 / 878.5ms / 11 tasks / 14초 성공**이다. 이는 typed 해석의 실제 모델 품질 검사가 아니라 기존 CONTENT 경계의 회귀 검사다.
- Story decoder219/native SQL202 및 실제 Story owner1/1 결과는 [별도 Story 기록](RECORDING_V2_STORY_PROOF_LEAF_20260930.md)에 있으며, 이 typed 해석 경로의 통과 수치가 아니다.

실제 Ollama·사용자 인게임 수용·운영 서버/클라이언트 배포는 수행하지 않았다. NEW 활성화, 미지원 source fallback, observer 간 해석 전파와 전체 규모/품질 검증은 이 문서가 완료로 선언하는 범위가 아니다.

관련 소스: [typed renderer](../../../mythai-ai-response/src/main/java/com/sande/mythai/response/memory/RecordedNativeInterpretationPrompt.java), [소비자 집중 테스트](../../../mythai-ai-response/src/test/java/com/sande/mythai/response/memory/RecordedNativeInterpretationPromptTest.java), [공유 collector](../../../mythai-ai-response/src/main/java/com/sande/mythai/response/memory/RecordedRetrievalCoordinator.java), [foreground façade](../../../mythai-ai-response/src/main/java/com/sande/mythai/response/memory/RecordedForegroundMemory.java), [typed evidence 계약](../../../mythictrpg-main/src/main/java/com/sande/mythictrpg/recording/api/NativeInterpretationEvidence.java), [실제 typed GameTest](../../../mythictrpg-main/src/main/java/com/sande/mythictrpg/recording/server/NativeInterpretationGameTests.java).

관련 기록: [기존 RAW 준비](RECORDING_V2_NATIVE_FOREGROUND_PREPARATION_20260930.md), [공유 retrieval](RECORDING_V2_M5_SHARED_RETRIEVAL_20260930.md), [native seal](RECORDING_V2_NATIVE_EVIDENCE_SEAL_DESIGN_20260930.md).
