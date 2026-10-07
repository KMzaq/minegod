# Native 해석 후보의 영속 근거 — 계약·발급·재검증

2026-09-30 / `HanesTest`. **schema11 영속 발급, 실제 game-issued Page/Seal, portable prepare/current, typed 근거를 인용한 발화의 RAW 재조회 및 history 준비 경로를 구현했다.** 별도 typed collection/renderer와 개발용 foreground façade도 추가됐지만 운영 `respond`/NEW는 연결하지 않았다. 실제 모델 호출·운영 배포는 없으며 이번 조합의 실제 GameTest는 아래 검증 상태를 따른다. JSON이나 올바른 해시만 만들었다는 이유로 사용 권한을 얻지 않는다.

관련 기준: [원문 seal](RECORDING_V2_NATIVE_EVIDENCE_SEAL_DESIGN_20260930.md), [foreground 준비](RECORDING_V2_NATIVE_FOREGROUND_PREPARATION_20260930.md), [콘텐츠 근거](RECORDING_V2_CONTENT_AWARE_RAW_SEAL_NEXT_20260930.md).

## 별도 proof kind를 사용하는 이유

기존 `RECORDED_NATIVE_MEMORY_V1`은 실제 발언·수신·출처 DAG를 증명한다. 해석 후보는 여기에 **어떤 extractor가 어떤 전체 입력으로 만든 어떤 분류/정정 관계인가**라는 별도 상태가 붙는다. 원문이 그대로여도 해석 job이 재처리되거나 candidate가 교체될 수 있다.

따라서 RAW v1에 candidate를 발언처럼 끼워 넣거나 v1의 의미를 바꾸지 않고 `RECORDED_NATIVE_INTERPRETATION_V1`을 별도로 정의했다. 기존 RAW descriptor/manifest/메서드는 그대로다. 두 kind는 상호 decode·seal allocation을 거절한다.

`CANCELS`는 취소를 말한 것으로 해석한 후보일 뿐 실제 퀘스트·계약 취소가 아니다. `RELATIONSHIP`은 인용에 근거한 서술적 후보이고 affinity 저장소가 아니다. `SUMMARY` 역시 원문 인용의 선택이지 세계 사실을 확정하는 자유 요약문이 아니다.

## 현재 추가한 코드

게임 `recording/api`:

- `NativeInterpretationEvidence`: immutable Reference/CandidateBinding/Manifest, 엄격한 canonical JSON과 전체 바이트 제한.
- `NativeInterpretationSeal`: reference 문법만 확인하는 unregistered opaque 객체. 실제 game Session에 등록된 **그 객체 identity**만 현재 발급 권한을 가진다.
- `MemoryReadSession.sealInterpretations(rawPages, semanticPages, interpretationPages)`: 기본값 `Optional.empty()`.
- `MemoryReadSession.current(NativeInterpretationSeal)`: 기본값 `false`.
- `NativeInterpretationEvidenceTest`: 순수 계약 fixture. SQL·서버·모델을 사용하지 않는다.

기본 구현의 `Optional.empty()`는 **미지원/미발급**이지 후보가 없거나 조회 권한이 있다는 뜻이 아니다. 실제 구현은 다음에 있다.

- `RecordedMemoryAccess.Session`: 실제 RAW/semantic/interpretation Page identity·seed 권한 검증, 전체 Page 선택, 중복 Entry의 동일성 확인, 원문/typed가 공유하는 **세션당 한 번의 발급 시도**, generation/취소/timeout/owner guard.
- `RecordedNativeInterpretationStore`와 `WorldRecordingService`: bounded strict 후보 재조회, 전체 원문 의존성 결합, schema11 `native_interpretation_evidence` 원자 저장·quota 처리. 발급 자체로 projectionGeneration을 바꾸지 않는다.
- `NativeRoomEvidence`: 실제 opaque seal의 publication 참조 등록, 새 턴/restart에서 영속 manifest prepare/current. 원문·typed의 혼합 batch도 최대64개이며 각 owner의 현재 권한을 다시 검사한다.
- `RecordedRoomSearch` / `RecordedNativeEvidenceStore`: typed ref가 있는 실제 출력의 source DAG 재검증과 RAW seal 재발급. `requiresProjection`이 붙은 Page/Seal만 projection fence를 상속한다. 독립 원문은 무관한 projection 갱신으로 폐기하지 않는다.
- `RoomHistoryEvidencePreparation`, `RoomMemoryBridge`, 관련 엔진 참조 분기: 실제 가시 receipt DAG에서 typed 참조 발견→게임 준비→기존 현재성 확인. 문자열에서 임의 ID를 수집하지 않는다.
- `RecordedNativeInterpretationPrompt` / `collectNativeInterpretations` / foreground façade: 실제 issued Page를 보관하는 명시적 준비 경로. 세부 공통 경계는 [foreground 준비](RECORDING_V2_NATIVE_FOREGROUND_PREPARATION_20260930.md)를 따른다. 기본 SHADOW 수집은 seal을 자동 저장하지 않으며 운영 대화는 아직 이 경로를 사용하지 않는다.

## 최소 immutable 계약

```text
Reference:
  version = 1
  worldId, datasetId, sealId, manifestHash

Manifest:
  version = 1
  sources: NativeMemoryEvidence.Manifest
  speechRoots: Set<messageId>
  candidates: List<CandidateBinding>

CandidateBinding:
  memoryId, jobId, observerGodId, extractorVersion
  authority = CANDIDATE
  layer, kind
  payloadHash, inputManifestHash
  createdSequence
  inputMessageIds: complete Set<messageId>
```

`sources`는 RAW v1의 **불변 데이터 구조**만 재사용한다. 이것이 별도 RAW seal이 발급되었다는 뜻은 아니다. 실제 issuer가 이 구조 전체와 typed manifest를 하나의 게임 소유 발급 record로 검증·저장한다.

`sources.roots`는 `speechRoots`와 **모든 candidate의 전체 inputMessageIds**의 정확한 합집합이다. 나머지 `sources.dependencies`는 기존 규칙대로 모든 필수 ancestor를 포함하고, 누락·여분·순환을 거절한다. 인용하지 않은 context input도 빠지면 안 된다. candidate-only 선택은 speechRoots가 비어도 되지만 원문 input/ancestor 근거는 반드시 존재한다.

최초 발급 candidate의 observer는 sources의 원래 recipient와 같아야 한다. candidate 생성 sequence는 1 이상이며 originalWatermark 이하이다. 같은 job의 여러 layer는 같은 extractor/full-input manifest/commit sequence/input 집합을 공유해야 하며 같은 job/layer 중복은 허용하지 않는다.

상한은 합계8개 실제 선택 Page, candidate64개, 원문 roots/dependencies 각64개, candidate당 입력1~6개, 전체 manifest UTF-8 64KiB, compact reference UTF-8 1KiB다. 개별 집합이 각각 상한 이내여도 합친 JSON이64KiB를 넘으면 전체 발급을 거절한다. 일부 입력·정정 링크를 잘라 크기를 맞추지 않는다. Page 개수와 identity는 Session이 검사하며 codec만으로 이를 증명할 수 없다.

## schema10과의 연결 요구사항

schema10 담당자의 commit-time `projection_input_manifests`는 `(dataset_id, job_id, extractor_version)`에 원래 입력·출력 fingerprint를 보관한다. 이 계약의 `inputManifestHash`는 그 **실제 원자 커밋된 manifest**를 가리켜야 한다. 현재 남은 `memory_sources` 행을 모아서 새 manifest를 추정·복원하면 안 된다.

현재 issuer/validator는 다음 경계를 검사한다.

1. 실제 현재 Session이 발급한 InterpretationPage identity와 그 seed Page identity. Entry/UUID/JSON을 받아 가짜 Page를 만들지 않는다.
2. candidate의 memoryId/jobId/dataset/observer/layer/kind/createdSequence/payloadHash, work의 현재 `DONE` 및 extractor 일치.
3. schema10 canonical full-input manifest/hash와 전체 input/output metadata 일치. 원문/수신 receipt/actor/시각/청중/disclosure/기록 정책/모드/coverage/prefix hash도 원래 manifest와 같아야 한다.
4. 모든 quote가 해당 alias의 실제 허용된 prefix 안에 존재함. alias→message/actualActor/time 매핑과 명시적인 newer→older links의 의미·방향·참가자 제한을 기존 strict reader로 재검증한다. alias `e0`는 candidate별 namespace이며 다른 candidate의 `e0`와 합치지 않는다.
5. 전체 원문 DAG 및 현재 모든 필요한 source/receipt 철회·supersession·청중 권한. 원래 read watermark 이후 새로 생긴 receipt나 projection을 소급해 넣지 않는다.
6. 같은 SQLite snapshot에서 확인한 expanded Entry가 실제 발급 Page의 Entry와 정확히 같음. checksum 값만 같다고 모델이 공급한 quote/summary를 허가하지 않는다.

`projectionGeneration`은 **현재 Session의 transient fence**로만 사용한다. 발급 전후와 비동기 callback 시 현재 generation/pending mutation을 다시 검사하되, 그 숫자를 재시작 후 영속 버전처럼 비교하지 않는다. 새 요청/restart 재검증은 job/version/원자 manifest/payload/원문 바인딩을 다시 조회한다. 관련 없는 projection 변경은 기존 Session을 stale하게 만들 수 있지만 정상적인 새 준비에서 동일 후보가 재검증되는 것까지 영구 금지하지 않는다.

typed 발급은 기존 quota-bound 일반 writer 경로를 사용한다. 해석 변경과 proof 발급을 구분하므로 자기 발급이 projectionGeneration을 증가시켜 즉시 stale해지는 문제가 없다.

## 지원 범위와 남은 연결

**same observer만 지원한다.** A가 만든 해석을 B가 자신의 해석으로 가져오거나, RAW의 A→B 청취 규칙을 typed에 자동 적용하지 않는다. A의 typed 근거를 인용한 출력도 B가 들었다는 사실만으로 B의 이 경로에 허용되지 않는다. 향후 지원하려면 원래 해석 주체와 새 청중의 권한을 함께 유지하는 명시적 계약이 필요하다.

원래 native RAW/semantic Page를 seed로 후보를 읽고 seal하는 것은 지원한다. 그러나 **typed 근거를 가진 후속 발화**를 idle projection/embedding 또는 native-only semantic 검색 입력으로 재사용하는 것은 아직 지원하지 않는다. `nativeSource/nativeSources/nativeClosure`의 외부/typed 근거 제한을 풀지 않았고, foreground의 owner 검증 성공이 background 권한으로 전파되지 않는다.

schema10 manifest가 없는 옛 후보는 보존하되 사용하지 않으며, 남은 행에서 입력을 추정해 backfill하지 않는다. schema11은 별도의 발급 table을 추가하고 RAW v1의 저장 의미를 바꾸지 않는다.

남은 단계는 AI typed renderer를 포함한 통합 회귀 결과 확정, 권한 있는 bounded typed context의 운영 생성/출력 연결, 별도 NEW 전환 검증이다. 아래 실제 게임 경로 시험은 통과했지만 NEW 운영 연결을 뜻하지 않는다. Watch/Rumor seal 및 다른 observer로의 해석 공유도 이번 범위가 아니다. 실제 모델 자연스러움/성능 수용 검증과 운영 배포는 별도다.

## 한도의 적용 범위 — 전수 archive closure가 아니다

권한 검증은 **선택한 후보의 전체 commit-time 입력 + 실제 원문 부모 DAG**를 완전히 확인한다. 인용하지 않은 optional input도 철회 검사에서 제외하지 않는다. 하지만 그 input을 공유하는 다른 job을 역방향으로 계속 조회해 candidate를 추가하지 않는다. schema10의 같은 job sibling 출력 무결성 검사도 sibling을 전부 프롬프트에 넣으라는 뜻이 아니다.

검색/렌더 그룹은 이번 bounded 수집에서 실제로 얻은 카드들 안에서만 만든다. 현재 정리 작업은 후보당 최대6개 필수 입력을 먼저 확보하고, optional context에는 자체 부모가 없는 원문만 추가한다. 기본4개 후보는 최대24개 입력이며 RAW4+semantic4와 합쳐 최대32개 roots다. 옵션 최대8개 후보에서는48+8+8=64개다. 별도 RAW의 진짜 ancestry 또는 전체 JSON이 한도를 넘으면 해당 그룹 전체를 제외/거절하고 PARTIAL로 취급한다. candidate64는 계약의 절대 상한이지 검색 기본값이 아니다.

조회/발급은 동일 SQLite snapshot·scope·watermark 안에서 job manifest와 RAW node를 재사용한다. archive 전체 역방향 graph나 요청 간 무기한 권한 cache를 만들지 않는다. 검색되지 않은 후속 정정이 없다고 단정하지 않는다.

## renderer의 atomic group 확장

기존 SHADOW `RecordedMemoryPrompt`는 quote/input의 messageId로 연결된 component를 원자 선택한다. 별도 typed 발급용 렌더러는 여기에 **같은 실제 Page의 모든 행**도 묶는다.

- node: 실제 RAW/semantic/interpretation Page의 각 card.
- edge: 같은 Page, 같은 messageId, candidate의 모든 input 및 quote/link alias가 가리키는 messageId.
- component: 전체 카드·모든 quote·links·input coverage를 함께 넣거나 전체 제외.
- 해석 Page의 일부 카드만 선택한 새 Page를 만들지 않는다. 같은 Page의 무관한 항목도 page identity 계약 때문에 같은 선택 단위가 될 수 있다.
- correction 그룹이 예산을 초과하거나 stale/미지원이면 연결된 오래된 약속 RAW도 제외한다. 해석만 빼고 기존 문장을 남기지 않는다.
- 카드의 `CANDIDATE_INTERPRETATION`과 `RAW_SPEECH` 구분, 인용 화자, 제한된 prefix/부분 검색 표시를 유지한다.
- 하나의 선택을 새 단일 `sealInterpretations(raw,semantic,interpretations)`로 발급한다. 두 별도 seal 중 하나만 성공한 결과를 노출하지 않는다.

## history/출력 호환 영향

`RoomHistoryEvidencePreparation`은 실제 가시 receipt DAG에서 새 kind를 문법 확인 후 warm-up 대상으로 수집한다. 이 발견 결과는 권한이 아니다. `RoomMemoryBridge` 및 게임 prepare/current가 실제 DB/owner 재검증을 완료한 뒤 기존 `currentSources`를 다시 실행한다.

`RoomRecordingCapture`의 context hash는 새 typed ref를 그대로 묶는다. 다음 RAW 조회에서 `RecordedRoomSearch`가 새 proof를 검증하고 그 전체 source roots를 필수 parent edge로 다룬다. unknown typed ref를 제거하고 평범한 RAW 발언처럼 읽는 fallback은 없다. proof issuedSequence < child publication sequence 및 source/projection sequence ≤ originalWatermark < issuedSequence를 유지해 순환·미래 근거 참조를 막는다.

구 reader는 새 kind를 미지원으로 거절하는 것이 정상이다. 실제 출력이 새 kind를 사용하려면 이 새 producer/consumer/history handler 조합이 함께 있어야 하며, 구 운영 JAR에 descriptor만 전달하면 안 된다.

## 검증 상태

부모 에이전트의 최신 게임 집중 실행 **18개 task/65초 성공**: **NativeInterpretationSessionTest133, NativeInterpretationEvidenceTest86, NativeRoomEvidenceCacheTest114, 기존 RAW/native SQL202, 새 typed SQL178**, interpretation read236·semantic→interpretation114·projection331·store62·lexical110·embedding119가 통과했다. 이는 해당 집중 실행의 결과이지 운영 배포·전체 시스템 완료를 뜻하지 않는다.

새 Session133은 실제 SQLite·projection·issued Page로 복제/외부 세션 Page 거절, 동일 후보 Entry dedup, RAW/typed 공유 한 번의 발급, 발급으로 generation 불변, 원래 semantic seed 권한, unquoted 철회, 취소/턴 변경의 늦은 callback, 혼합 content owner의 비동기 거절, typed 후속 RAW의 선택적 projection fence를 확인한다. 순수 codec86은 문법/불변성/범위/기본 deny이고 실제 권한 검증을 대신하지 않는다. 최초 새 fixture 컴파일은 `Candidate` wildcard import 충돌로 실패했으며 명시 import 수정 후 이번133 검사가 통과했다.

실제 개발용 GameTest `build/native-interpretation-wire-20260930`는 **1/1 통과, 시험1.025초 / Gradle9개 task15초 성공**이다. 실제 game-issued 입력→projection lease/commit→typed seal→NPC publication/capture→새 턴 prepare→후속 RAW/RAW seal→extractor v2 교체 후 기존 current와 새 조회 차단을 확인했다. 기존 pending work의 우선순위에 맞게 fixture를 보완한 뒤 첫 실제 실행이 통과했으며 production 권한을 완화하지 않았다.

앞선 별도 게임 집중 실행은16개 task/59초 성공(schema10 projection331·interpretation236·semantic→interpretation114, native SQL202, Story219 포함)이었다. 이것을 이번 새 typed 발급 조합의 검증 결과와 혼동하지 않는다. 실제 GameTest는 개발용 합성 데이터/모델 없는 fixture이며, 실제 LLM·플레이어 UI·운영 배포는 미수행이다.
