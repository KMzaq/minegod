# 기록 v2 — native 해석 후보의 실제 영속 발급 (schema11)

2026-09-30 / `HanesTest`. [typed API 계약](RECORDING_V2_INTERPRETATION_SEAL_CONTRACT_20260930.md)과 [schema10 전체 입력 무결성](RECORDING_V2_PROJECTION_INPUT_INTEGRITY_20260930.md)의 실제 SQL/Session 연결 후속이다. **게임 focused18개 task/65초 성공, typed SQL178·Session133 checks, 실제 typed GameTest1/1 통과. NEW 활성화·운영 배포·실제 모델 호출은 하지 않았다.**

## 구현된 경계

실제 발급된 RAW/semantic/interpretation 페이지의 전체 선택을 게임 Session이 확인한 뒤, 별도 `RECORDED_NATIVE_INTERPRETATION_V1` 근거를 발급한다. SQL helper는 공개 AI API가 아니며 `Entry`나 UUID만 보내 권한을 만드는 입구가 아니다. Session은 객체 identity, 원래 seed/current 상태, 요청·청중·턴, source/projection generation, owner prepare/current를 확인한다.

이번 slice의 해석은 `EVENT`, `RELATIONSHIP`, `SUMMARY` 모두 **`CANDIDATE`**다. 실제 발언·세계 사실·관계 수치·퀘스트 실행 결과로 바꾸지 않는다. `CANCELS`는 취소 발언에 대한 후보 해석이고, `REPORTS_FULFILLMENT`는 완료했다고 한 주장이지 게임 완료 판정이 아니다.

## SQL 계약

[RecordedNativeInterpretationStore](../../../mythictrpg-main/src/main/java/com/sande/mythictrpg/recording/server/RecordedNativeInterpretationStore.java)의 package-private 계약은 다음과 같다.

```text
issue(scope, request, originalW, speechRoots, actualEntries)
  -> IssuedInterpretation(seal, ownerReferences)

validate(scope, portableReference)
  -> ValidatedInterpretation(reference, manifest, ownerReferences)

load(... currentW, childSequence ...)
  -> Stored(reference, manifest, issuedSequence)

matches(scope, manifest, fullSourceClosure)
  -> boolean
```

`load`는 실제 발급 row·형식·scope·sequence만 확인한다. 호출자는 같은 SQLite snapshot에서 **반드시 `matches`까지 성공**해야 사용할 수 있다. JSON과 hash만 맞는 caller-created reference는 발급된 것으로 취급하지 않는다.

[WorldRecordingService](../../../mythictrpg-main/src/main/java/com/sande/mythictrpg/recording/server/WorldRecordingService.java)의 `issueNativeInterpretationEvidence`는 기존 공용 quota writer를 사용한다. **`projectionTransaction`이 아니므로 발급 자체로 projection generation을 증가시키지 않는다.** 발급 row와 archive high watermark는 같은 transaction에서 commit된다. read/SQL250ms 및 cooperative200ms, 기존 queue/할당량 한도를 유지하며 optional 실패가 정상 RAW archive를 중단시키지 않는다.

## exact candidate 검증

[RecordedInterpretationSearch.exact](../../../mythictrpg-main/src/main/java/com/sande/mythictrpg/recording/server/RecordedInterpretationSearch.java)는 기존 strict reader를 재사용한다.

- 현재 dataset/observer, `CANDIDATE` 상태, 현재 `DONE` job과 정확한 extractor version
- candidate ID/job/layer/kind/payload hash/created sequence
- 실제 schema10 immutable input manifest/hash 및 전체 input/output 집합
- 모든 입력의 source/실제 receipt/화자/발생 시각/청중·공개 범위/prefix coverage와 prefix hash
- 정확한 quote와 alias→원문 바인딩, 실제 newer/older 관계, link 의미·시각·화자 제한
- **동일 job의 최대3개 sibling 전체 quote/link 검증**: 선택하지 않은 EVENT의 취소 link가 손상됐어도 SUMMARY로 우회하지 않음
- 원래 read watermark 이후 생성된 후보·receipt를 소급 포함하지 않음

같은 job의 sibling 검증은 권한·무결성 검사다. 선택되지 않은 sibling을 프롬프트나 발급 manifest에 강제로 추가하지 않는다. 요청 내부의 manifest/확장 결과 캐시로 중복 검증을 줄인다.

선택된 실제 Entry와 DB가 엄격히 확장한 Entry가 **정확히 같아야** 발급한다. 인용문·입력·시각·분류를 바꾼 Entry나 같은 ID의 서로 다른 Entry는 거절한다.

## 발급 manifest와 원문 계보

별도 `native_interpretation_evidence` 테이블에 `seal_id`, world/dataset, canonical manifest/hash, original watermark, issued sequence를 저장한다. 기존 RAW `native_memory_evidence` row나 v1 의미는 바꾸지 않는다.

- `speechRoots`: 선택된 실제 RAW/semantic 페이지의 모든 원문 ID
- `candidates`: 선택된 실제 해석 Entry 각각의 immutable candidate binding
- `sources.roots`: `speechRoots`와 **선택된 모든 candidate의 전체 입력 ID**의 정확한 합집합
- `sources.dependencies`: 위 root의 실제 필수 source 부모 전체
- candidate binding: memory/job/observer/extractor/layer/kind/payload hash/input manifest hash/created sequence/전체 입력 ID

후보 최대64개, 입력 각1–6개, roots/dependencies 각64개, manifest 전체 UTF-8 64KiB다. 넘치면 전체 거절하며 일부 입력·취소 link를 잘라 맞추지 않는다. 선택된 job의 전체 입력과 실제 부모만 확인하고, 같은 optional 입력을 공유하는 **archive 전체 job을 역검색하지 않는다.**

`projectionGeneration`은 진행 중 Session의 fence일 뿐 영속 manifest 버전이 아니다. 새 요청·재시작 후에는 실제 job/version/input manifest/payload와 모든 source 권한을 다시 조회한다. 무관한 projection 작업 때문에 기존 Session이 stale할 수 있지만 그 숫자를 영속 차단 근거로 쓰지는 않는다.

## 최초 제한과 owner 경계

**처음 발급한 observer God만 portable proof를 재사용한다.** RAW v1의 A→B 규칙을 해석 소유권에 자동 적용하지 않는다. 같은 observer라도 private 새 플레이어 청중, 새 God, 공개 확장, policy/mode 변경은 기존 source 공개 규칙에 따라 거절한다.

candidate 입력 자체는 기존 native-only 경로다. 별도로 선택된 speech root에 CONTENT/QUEST/Story leaf가 있으면 그 ownerReferences를 그대로 반환하며 Session이 실제 owner를 game dispatcher에서 준비·재검증해야 한다. leaf가 있다고 background extraction/embedding을 허용하지 않는다. 정적 퀘스트 내용이나 Story Hook 설명을 실행 토큰으로 승격하지 않는다.

실제 출력의 context에 typed ref가 저장되면 foreground RAW reader는 이를 필수 부모 계보로 확인한다. `candidate/source sequence ≤ originalW < issuedSequence < child publication sequence`를 유지한다. unknown/변조된 typed ref를 제거하고 평범한 RAW로 읽는 fallback은 없다. typed 의존 RAW는 projection currentness fence를 전달하며, 아직 그 fence를 지원하지 않는 native semantic/background 경로는 typed 후손을 명시적으로 거절한다.

## schema10 → 11

빈 typed issued table만 추가한다. 원문·기존 RAW seal·schema10 projection manifest/candidate는 보존한다. 자동 발급·백필·모델 실행·기존 candidate 재추출은 하지 않는다. 유지된 유효 후보는 실제 Session의 새 명시적 발급을 통해서만 새 typed seal을 얻는다. 구버전 migration fixture들도 새 table을 제거한 뒤 이전 schema를 재현하도록 갱신했다.

## 검증 상태

이번 코드가 포함된 focused 실행에서 확인된 결과:

- [RecordedNativeInterpretationStoreTest](../../../mythictrpg-main/src/test/java/com/sande/mythictrpg/recording/server/RecordedNativeInterpretationStoreTest.java): **178 checks**. 실제 projection → typed 발급 → 실제 typed-backed 출력 RAW → 재시작 → 비인용 receipt 철회, 선택 job 재추출, 무관 job 변경, 위조 Entry/old W/FULL, sibling 취소 link 손상 등6종 변조, schema10 이관.
- [NativeInterpretationSessionTest](../../../mythictrpg-main/src/test/java/com/sande/mythictrpg/recording/server/NativeInterpretationSessionTest.java): **133 checks**. 실제 페이지 발급 identity·공유 단일 seal 시도·generation·cancel/owner 비동기 경계·후손 fence.
- 관련 회귀: schema10 해석 reader236, semantic-seed 해석114, projection 저장331, 기존 RAW seal SQL202, recording 저장62, lexical110, embedding119 checks 통과.

위 수치는 각 suite 전체이며 모두 새 SQL 발급 전용 검사는 아니다. 해당 게임 focused 묶음은18개 task/65초에 성공했다.

후속 실제 typed GameTest도 `build/native-interpretation-wire-20260930`에서 **필수1/1 PASS(1.025초), Gradle9개 task/15초 성공**을 확인했다. 실제 publish/capture → 새 턴의 typed-backed RAW 조회 → 중첩 RAW seal → v2 재추출에 따른 철회를 검사했다. 저장소/Session mock만으로 이 결과를 대신하지 않았다.

개발용 GameTest는 운영 월드의 DB 이관·운영 JAR 배포·사용자 인게임 확인·실제 Ollama 생성 품질을 검증한 것이 아니다. NEW와 실제 모델 활성화는 이번 작업에 포함하지 않는다.
