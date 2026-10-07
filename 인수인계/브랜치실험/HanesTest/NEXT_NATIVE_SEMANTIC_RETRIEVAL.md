# 다음 작업안 — native V2 의미 검색

작성일: 2026-09-30 / `HanesTest` 전용 **설계 메모**.

이 문서는 전체 구현·검증·배포 완료 기록이 아닌 상위 설계 메모다. 이후 native prefix의 embedding worker/저장, 독립적인 bounded chronological 의미 후보, game-issued semantic read와 SHADOW 소비자 구현을 진행했다. 현재 코드 범위와 검증 상태는 [native 의미 후보 조회 기록](RECORDING_V2_M5_NATIVE_SEMANTIC_READ_20260930.md)을 우선한다. 아래의 전체 후보 분기·공유 벡터 최적화·전체 기간 효율·프롬프트 전환까지 완료한 것은 아니다. 운영 설정 변경이나 실제 모델 호출도 수행하지 않았다.

## 1. 목표와 재사용 범위

현재 native 기록의 정확한 원문/FTS 검색과 근거가 있는 해석 조회에, 표현이 달라진 회상을 보조하는 bounded 의미 검색을 추가한다. 외부 vector DB, 새 native ANN 확장, 새 모델은 도입하지 않는다.

- [기존 모델 설정](../../../server/config/mythictrpg/ai-memory-index.json)은 `bge-m3:latest`, 고정 digest, 1024차원, CPU 4 threads, 질의 350ms/백그라운드 30초를 지정한다. 파일 설정을 읽었을 뿐 현재 설치 모델·성능을 실시간 확인한 것은 아니다.
- [OllamaMemoryBackend](../../../mythai-ai-response/src/main/java/com/sande/mythai/response/memory/OllamaMemoryBackend.java)의 `embed`를 재사용할 수 있다. numeric loopback, 호출 전후 digest, 응답 크기·차원·유한 숫자 검사를 유지한다.
- [SemanticIndex](../../../mythai-ai-response/src/main/java/com/sande/mythai/response/memory/SemanticIndex.java)의 중립 `Vector`/cosine 계산은 재사용 가능하다.
- `SemanticIndex.allowed`, `MemoryIndexRuntime`, `MemoryIndexStore`, `HybridRetrieval`은 그대로 이식하지 않는다. 기존 `MemoryJournal.Key`·플레이어별 파일 자료와 메모리 내 순회에 결합되어 있어 native receipt/청중/DAG 검증을 대체하지 못한다. 임시 `MemoryJournal.Entry`를 만들어 우회하지 않는다.

## 2. 최소 모듈·계약안

아래 표는 최초 책임 분담안이다. 일부 이름은 구현에서 조정됐으며 현재 API는 후속 기록과 실제 코드가 기준이다. 표 전체를 구현 완료 목록으로 읽지 않는다.

| 소유 모듈 | 추가할 책임 |
|---|---|
| 게임 `recording.api` | `MemoryEmbeddingPort`: game-issued worker와 `claim/commit/finish`, opaque token, 제한된 벡터 결과 DTO |
| 게임 `recording.server` | `RecordingEmbeddingStore`: 동일 SQLite writer/quota의 작업·벡터·receipt 연계 저장과 권한 재검증 |
| 게임 `MemoryReadSession` | 별도 scoped 의미 후보 요청/opaque 페이지와 `current` 검사. AI에 JDBC·전체 벡터 목록을 주지 않음 |
| AI 응답 `memory` | native embedding consumer: 기존 backend/admission만 사용하며 모델 입력은 game-issued bounded 문장 |
| AI 응답 검색 조합 | exact/derived 결과를 유지한 SHADOW 비교. 명시적 전환 전에는 실제 대사 Context에 새 결과를 추가하지 않음 |

작업에는 source identity/hash, 실제 knowledge receipt/hash, observer God, disclosure 범위, input kind, 정확한 원문 범위/text hash, model name/digest/dimensions, encoder/chunk version, epoch/lease가 필요하다. 게임 commit이 모두 재검증한다. 임베딩 유사도는 세계 사실·관계 변화·퀘스트 완료 권한이 아니다.

## 3. 저장 구조안

- `embedding_documents`: native source와 원문 발췌 범위·내용 hash·encoder version. 첫 범위는 `RAW_EXCERPT`로 제한한다.
- `embedding_rows`: document + model space별 유한 float32 BLOB, 차원, 생성 archive sequence. 원문을 벡터의 원본으로 대체하지 않는다.
- receipt 연계: 해당 문장을 실제로 아는 God의 receipt와 hash/공개 범위를 검증한다. 동일 문장이라고 서로 다른 사건·화자의 권한을 합치지 않는다.
- 정확히 동일한 source 발췌의 벡터는 공유할 수 있으나, 늦게 들은 God은 별도의 유효 receipt 연결 없이는 검색할 수 없다.
- 별도 embedding 작업/progress에 durable lease·bounded retry를 둔다. 현 projection consumer에 다른 kind 작업을 섞으면 `SKIPPED_UNSUPPORTED`로 소비될 수 있으므로 작업 소유권을 분리한다.
- 같은 writer/transaction/quota를 사용한다. 부분 처리 후 `MAX(sequence)`만 올려 나머지를 건너뛰지 않는다. 같은 sequence의 여러 receipt를 처리할 수 있는 복합 cursor 또는 개별 멱등 작업 키가 필요하다.
- 원문/projection, 서로 다른 발췌, encoder/모델 digest를 같은 hash로 취급하지 않는다. 기존 `raw-text-v1` fingerprint와 새 chunk 규칙의 관계를 명시한다. 옛 시험 vector 파일은 자동 import하지 않는다.
- 원본·receipt 철회 시 검색과 발급된 페이지를 무효화한다. 모델 재색인/해석 변경의 generation은 RAW read 권한 generation과 분리한다.

## 4. ACL 우선 후보 분기

FTS hit만 vector로 재정렬하면 다른 표현의 옛 기억을 찾을 수 없다. 각 분기는 독립적인 제한과 진행 cursor를 가진다.

1. FTS/정확한 구절 후보.
2. 실제 actor + 요청 시간 범위의 에피소드.
3. 약속·정정·취소 연결이 있는 **해석 후보**.
4. 최근의 허용된 에피소드.

각 분기에서 God receipt, dataset/source revision, tombstone, 공개 범위를 먼저 제한하고 native DAG까지 검증한 후보만 cosine/rank fusion에 사용한다. 전역 top-k를 구한 다음 비공개 항목을 숨기는 방식은 사용하지 않는다.

현재 `MemoryReadSession.Query`는 text/from/until만 갖는다. query vector/model space와 game-bound subject actor, scoped 후보 읽기 계약이 추가로 필요하다. 기존 해석에 authoritative 중요도·미해결 상태가 있는 것은 아니므로 `INTENTION_OR_PROMISE`/링크를 실제 미완료 퀘스트로 해석하지 않는다.

늦은 색인으로 페이지 간 중복·누락이 생기지 않도록 생성 watermark를 고정한다. 후보 상한/미완료 색인/timeout은 `PARTIAL` 또는 fallback이며 “그런 기록이 없다”가 아니다. NPC에게 비공개 후보 수나 탈락 사유를 노출하지 않는다.

## 5. 실행·활성화 한계

- 백그라운드는 기존 `ModelAdmission.optional(true)`, 접속자 0명, foreground 없음, 1개 작업을 사용한다. HTTP가 실제 종료되기 전 하드웨어 permit을 돌려주지 않는다.
- projection과 embedding이 항상 같은 순서로 경쟁해 한쪽을 굶기지 않도록 bounded 순환 배정을 검토한다. 별도 병렬 모델 pool을 만들지 않는다.
- 95% 저장량에서는 새 embedding을 보류하고 원문을 우선한다. 원문 capture가 선택적 색인 실패에 함께 중단되지 않도록 한다.
- 첫 query embedding은 명시 회상에만 기존 제한 안에서 시도한다. 혼잡/timeout/model mismatch면 exact/derived 검색을 유지한다. 일반 발언마다 숨은 모델 호출을 추가하지 않는다.
- legacy `semanticMode=ON`을 새 native V2의 foreground 활성화로 자동 해석하지 않는다. 기본 비활성 또는 명시적 native SHADOW gate를 둔다.
- offline 구현/가짜 transport 검증에는 새로운 콘텐츠 결정이 필요하지 않다. 실제 호출·서버 활성화는 그 작업 범위에서 명시적으로 확인한다. ANN/새 모델/개인정보 경계 완화는 별도 결정이다.

## 6. 착수 전 검증 목록

먼저 기존 exact/derived·철회·재추출 currentness 테스트를 유지한다. 이후 model digest 변경, 차원·NaN 거절, timeout/선점, 재시작·부분 작업 재시도, 늦은 receipt, 청중 분리, 장문 뒤쪽 정정, 다른 actor의 같은 문장, FTS에 안 걸리는 paraphrase를 fake transport와 실제 SQLite로 검증한다.

이 후보군 방식은 100GB 전체의 즉시·완전한 의미 검색을 보장하지 않는다. 오래된 드문 사건의 누락·실측 지연을 별도로 보고하고, 승인된 실제 모델/인게임 수용 검증과 offline 결과를 구분한다.

기준: [검색·모델 운용 설계](../../../추가개발/05_AI_장기기억_다중신_대화_시스템/06_검색과_컨텍스트_모델운용.md).
