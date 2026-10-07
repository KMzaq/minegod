# 기록 v2 — projection 전체 입력 무결성 (schema10)

2026-09-30 / `HanesTest` 개발 작업본. [native 파생 해석 조회](RECORDING_V2_M5_NATIVE_INTERPRETATION_READ_20260930.md)의 저장 무결성 후속 변경이다. **소스 구현과 게임 모듈 focused16개 task/59초 검증 통과. 운영 이관·배포·실제 모델 검증은 하지 않았다.**

## 해결하려는 문제

기존 `projection_hash`는 후보의 `payload_json`만 묶었다. writer는 인용되지 않은 문맥까지 `memory_sources`에 원자 저장했지만, 인용도 필수 부모도 아닌 입력 행이 외부 손상으로 삭제되면 reader는 사라진 입력이 원래 존재했는지 알 수 없었다.

schema10은 **실제 extraction lease에 있었던 전체 입력**을 commit 순간에 별도 manifest로 묶는다. 남은 DB 행으로 나중에 그 목록을 재구성하지 않는다. 원문이나 해석을 게임의 확정 사실로 승격하는 기능이 아니다.

## 저장과 검증

새 `projection_input_manifests`의 키는 `(dataset_id, job_id, extractor_version)`이다. canonical `manifest_json`, SHA-256 `manifest_hash`, `created_sequence`를 저장한다. 같은 writer/할당량/transaction에서 다음이 함께 commit된다.

1. 모든 후보 layer의 `memories`, `memory_sources`, `memory_links`
2. 실제 lease로 만든 immutable input manifest
3. 해당 작업의 `DONE`

한 manifest에는 다음을 넣는다.

| 범위 | 정확히 묶는 값 |
|---|---|
| 작업 | 형식 버전1, dataset, job, extractor version, observer God, disclosure hash, target alias, 생성 archive sequence |
| 전체 입력1–6개 | alias, `SourceRef` 전체, 실제 knowledge receipt ID/hash, message/conversation ID, 실제 화자, observer, 정렬된 전체 청중, disclosure hash, recording policy/memory mode, 발생 시각 |
| 각 입력의 모델 노출 범위 | UTF-16 `coveredCharacters`/`totalCharacters`, excerpt 여부, 모델에 제공한 **정확한 prefix 문자열**의 hash |
| 출력1–3개 | deterministic memory ID, layer, kind, payload hash |

manifest는 최대64KiB다. 입력 원문을 별도 복사해 저장하지 않는다. 실제 원문은 기존 archive에 남고 prefix hash는 그 원문과 다시 대조한다. manifest 저장 공간은 projection commit 예약량에 포함한다. 모델이 입력을 실제로 이해했거나 올바르게 해석했다는 증명은 아니다.

`RecordedInterpretationSearch`는 기존 native source/청취/공개 범위/철회/필수 부모 검증에 다음을 더한다.

- manifest의 canonical 형식, 전체 hash, 실제 DB world/dataset, original read watermark
- 같은 commit의 **모든 sibling layer** ID·metadata·payload와 생성 sequence 일치
- 각 sibling의 `memory_sources` **전체 집합**이 manifest의 alias/출처/receipt/화자/시각/coverage와 정확히 일치
- 현재 검증된 원문의 conversation·청중·policy/mode와 일치
- 현재 원문의 `substring(0, coveredCharacters)` hash가 당시 입력 prefix hash와 일치

한 입력이나 sibling이 누락되면 나머지 행만으로 성공 처리하지 않는다. malformed 후보는 거절하되 archive의 정상 RAW 조회까지 중단하지 않는다. 같은 read transaction에서 검증한 manifest는 내부 캐시로 재사용하며 기존 조회·SQL 시간 한도를 유지한다.

이는 부분 손상을 검출하는 구조다. 공격자가 DB의 원문·영수증·manifest와 모든 hash를 함께 임의로 재작성하는 경우를 외부 서명으로 인증하는 설계는 아니며, 실행 중 외부 DB 변조를 정상 서비스 입력으로 지원하지 않는다.

## 이전 자료와 재추출

- schema9 → 10은 빈 manifest table을 추가한다. 기존 원문·receipt·작업·후보를 삭제하지 않는다.
- **구 후보 행으로 manifest를 사후 생성하거나 자동 backfill하지 않는다.** manifest가 없는 후보는 보존하되 새 해석 조회에서 제외한다.
- 같은 extractor version의 기존 `DONE`을 자동 재추출 대상으로 바꾸지 않는다.
- 운영자가 기존 projection/model opt-in을 명시적으로 허용한 상황에서 **다른 extractor version**으로 실제 lease → 추출 → 검증 → commit을 수행해야 새 manifest를 얻는다.
- 이 migration 자체는 모델/서버/worker를 켜지 않고 버전을 자동 변경하지 않는다.
- 재추출 중 old `DONE`으로 돌아가거나 오래된 후보를 자동 복구하지 않는다. 현재 job의 version/state와 projection generation fence는 그대로 적용한다.

## 여전히 제외되는 것

`EVENT`, `RELATIONSHIP`, `SUMMARY`는 계속 `CANDIDATE`다. `CANCELS`는 취소 발언에 대한 해석이지 퀘스트 취소 실행이 아니며, `REPORTS_FULFILLMENT`도 실제 완료 판정이 아니다.

이번 변경은 native-only 파생 해석의 무결성 선행 단계다. 외부 CONTENT/QUEST/Story leaf를 background extraction에 허가하지 않는다. RAW용 `RECORDED_NATIVE_MEMORY_V1` seal로 요약/분류의 권한을 대신 증명하지 않는다. 해석을 포함한 영속 seal, 선택된 취소/정정 connected group의 원자 발급, 실제 모델 prompt 사용은 별도 단계다. `NEW`는 여전히 차단 상태이며 이번 변경으로 활성화되지 않는다.

## 검증 파일과 상태

- [ProjectionInputManifest](../../../mythictrpg-main/src/main/java/com/sande/mythictrpg/recording/server/ProjectionInputManifest.java): package-private canonical manifest·정확한 저장 집합 대조
- [RecordingProjectionStoreTest](../../../mythictrpg-main/src/test/java/com/sande/mythictrpg/recording/server/RecordingProjectionStoreTest.java): 실제 commit의 manifest/receipt/prefix/output 바인딩과 duplicate 불변성
- [RecordedInterpretationReadTest](../../../mythictrpg-main/src/test/java/com/sande/mythictrpg/recording/server/RecordedInterpretationReadTest.java): 비인용 optional 입력 삭제, coverage/alias 변조, sibling 삭제, metadata 변조, manifest 누락/hash/prefix 변조, schema9 보존·읽기 제외·명시적 재추출·재시작
- 기존 구버전 migration fixture는 schema10 table을 제거한 뒤 해당 구버전을 재현하도록 수정했다.

루트 에이전트의 직렬 실행에서 게임 모듈 focused16개 task가59초에 성공했다. 이번 변경을 실제로 포함한 결과는 다음과 같다.

- projection 저장331 checks
- native 해석 조회236 checks(이번 입력 손상·schema9 이관 fixture 포함)
- semantic-seed 해석114 checks
- native SQL seal202 checks
- recording 저장62, lexical index110, embedding119 checks(구버전 migration 회귀 포함)
- 함께 실행된 별도 Story decoder219, native interpretation codec86 checks

storage failure-injection fixture의 예상된 ERROR 로그 외에 실패는 없었다. 숫자는 각 suite 전체이며 전부가 schema10 전용 검사는 아니다. 마지막 두 별도 suite의 성공은 실제 interpretation seal issuer나 모델 prompt 경로가 구현·검증됐다는 뜻이 아니다. 실제 서버/GameTest·Ollama·운영 DB 이관·배포를 이번 offline 결과로 대체하지 않는다.
