# Native RAW/semantic 근거 발급·재검증 — 승인된 계약과 구현 현황

2026-09-30, `HanesTest`. **아래 계약과 schema 9 영속 발급·재검증은 구현되었고 집중 검증 및 실제 CONTENT+foreground GameTest를 통과했다. 전체 기억 기능·운영 검증이나 배포 완료 문서는 아니다.** NEW foreground는 계속 차단하며 준비 façade도 production `respond`에 연결하지 않았다. SHADOW 비교는 seal을 자동 발급·저장하지 않는다.

## 목적과 범위

현재 게임이 발급한 RAW/semantic 페이지의 발언을 실제 답변에서 사용하게 될 때, 답변을 저장하고 다음 대화에서 다시 읽어도 최초 근거의 권한·철회·출처 계보를 잃지 않는 경로를 만든다.

대상 원본은 `room-publication-v2`의 `DIALOGUE_DIRECT`/`DERIVED_SPEECH`이며 기존 `sourceMessages`와 native descriptor의 전체 계보를 검증한다. 이후 [콘텐츠 근거 확장](RECORDING_V2_CONTENT_AWARE_RAW_SEAL_NEXT_20260930.md)으로 `CONTENT_DISCLOSURE_V1` 및 `QUEST_CONTENT_DISCLOSURE_V1`의 실제 owner 검증을 추가했다. 이는 그 근거를 사용한 **과거 발언**을 읽는 권한이지 세계관 사실이나 현재 퀘스트 상태를 확정하는 권한이 아니다. Story·Watch·Legacy 등 미지원 leaf, 해석 candidate·관찰·소문 카드 자체의 sealing은 아직 지원하지 않는다.

## 현재 연결 지점

- 게임 `RecordedMemoryAccess.Session`: 페이지 identity, 원래 read watermark, 현재 room/turn/audience, authority/projection generation을 가지고 있다. 공개 Entry만으로는 source/receipt hash 등 영속 근거를 복원할 수 없다.
- 게임 `RecordedRoomSearch`: RAW hash, 실제 GAME_HEARD와 플레이어 dispatch, knowledge receipt, native parent DAG와 현재 tombstone/supersession을 검증한다.
- AI `MythAiRoomConversationEngine.preparePublication`: 실제 출력 전에 현재 근거를 확인하고 `RoomDialogueEvent.evidenceRefs`/`sourceMessageIds`를 결합한다.
- 게임 `RoomRecordingCapture`: 위 이벤트의 refs를 `PublicationContext.evidence`에 그대로 저장하며 source hash는 이 context를 포함한다.
- AI `RoomMemoryBridge.validReference` 및 `prepareRecordedEvidence`: 기존 owner 검증을 보존하고 게임 `NativeRoomEvidence.prepare/current`의 native ref 처리기를 연결했다. descriptor 문자열만으로 허가하지 않는다.
- 실제 Page를 선택부터 seal까지 보관하는 foreground 준비와 긴 모델 요청 뒤 동일 근거 재준비는 [별도 상세 문서](RECORDING_V2_NATIVE_FOREGROUND_PREPARATION_20260930.md)를 따른다. 기존 SHADOW 비교 결과에서 임의 Page를 복원하지 않는다.

## 승인된 최소 계약

`MemoryReadSession.seal(List<실제 RAW Page>, List<실제 Semantic Page>)`는 그 Session이 발급한 페이지 identity만 받는다. 임의 ID 배열이나 AI가 만든 Entry/JSON으로 발급하지 않는다. 페이지 전체 발언과 필수 ancestry closure를 대상으로 비동기 재조회한 뒤 게임 스레드에서 다시 검증한다.

발급 결과는 런타임 identity가 등록된 opaque `NativeMemorySeal`이다. `Session.current(seal)`은 복제·가짜 token을 거절한다. 공개 portable ref 자체는 새 읽기나 출력 권한을 주지 않는다.

DTO:

```text
Reference:
  version, worldId, datasetId, sealId, manifestHash

Manifest:
  version, worldId, datasetId, originalWatermark
  roomId, revision, turnId, playerId, recipientGodId
  publicRoom, recordingPolicy, memoryMode, audience
  roots, dependencies

SpeechDependency:
  messageId, SourceRef
  knowledgeReceiptId, receiptHash
  deliveryReceiptId, deliveryHash
  bodyHash, disclosureHash, parentMessageIds
```

`SourceRef`는 기존 world/dataset/kind/owner/sourceId/revision/hash 계약을 재사용한다. 대사 본문을 manifest에 복사하지 않는다. 이 계약은 **원본 단위의 출처·권한 근거**이며 모델 입력 전문을 증명하는 계약은 아니다. 실제 표시 발췌·prefix 제한은 발급 페이지와 renderer가 보존한다.

각 `SpeechDependency`의 knowledge/delivery receipt 쌍은 `recipientGodId` 한 명의 실제 수신 근거다. 전체 청중의 허가를 대신하지 않는다. SQL 발급·재검증은 현재 청중의 모든 신이 각 원본을 적법하게 수신했는지 원래 watermark와 현재 철회 상태 양쪽으로 확인해야 한다. 과거 발급 청중 안의 다른 신이 다음 턴 화자가 될 수 있는지는 그 신의 실제 receipt와 새 요청 범위로 판단하며, 전역 scope 비교를 느슨하게 만들어 허가하지 않는다.

## 영속 발급 기록

새 서명키의 저장·백업·회전 경로를 만드는 대신, schema 9의 제한된 issued-manifest 테이블을 구현했다. 게임 writer만 생성한 `sealId + canonical manifest + hash`를 quota 내에 원자적으로 저장한다. portable ref는 compact pointer만 가진다. 저장소·Session·owner 재검증은 연결되어 있지만, 운영 생성 경로 활성화와 실제 모델 품질 검증은 별도 단계다.

단순 unkeyed hash가 권한은 아니다. 실제 발급 row가 존재하고 일치하는지 확인한 뒤, manifest의 **모든** 원본·수신 receipt·공개 조건을 다시 검증해야 한다. FULL/저장 실패 시 미발급을 성공으로 꾸미지 않는다. 원문이나 기억 사실 DB를 새로 만들지 않는다.

승인된 상한: 페이지 합계 8, roots/dependencies 각 64, audience 256 및 신 16, reference UTF-8 1 KiB, manifest UTF-8 64 KiB. 초과 시 일부 ancestry를 잘라 발급하지 않고 전체 발급을 거절한다. 실제 source reader의 별도 깊이·본문·시간 예산도 함께 지켜야 하므로 이 최대치가 항상 발급된다는 뜻은 아니다.

## 현재 구현·검증 상태

- 게임 `recording/api/NativeMemoryEvidence.java`: 위 immutable DTO, explicit canonical JSON codec, exact UUID/정수/해시/필드 검사, dependency closure·cycle·중복 검사 및 전체 UTF-8 예산을 구현했다. 정렬은 UUID 문자열의 사전순이다.
- 게임 `recording/api/NativeMemorySeal.java`: syntax-only `unregistered` 생성과 opaque identity를 제공한다. 같은 portable ref를 복사해 token을 새로 만든다고 Session이 발급한 객체가 되지 않는다.
- `recording/api/NativeMemoryEvidenceTest.java`: 잘못된 schema/field/type/hash/scope, 누락·여분·순환 계보, immutable 복사, 최대 chain/과대 DAG, byte budget, token identity의 **92개 순수 검사 통과**.
- AI `memory/RoomHistoryEvidencePreparation.java`: 새 턴의 proof cache가 비어 있다는 이유만으로 유효한 history가 먼저 삭제되지 않도록, 실제 durable/volatile receipt DAG에서 비동기 prepare 후보만 찾는다. root 128·고유 receipt 512·reference 64로 제한하고, 각 root의 전체 가시성·누락·순환 검사를 통과한 뒤 native/Watch ref만 반환한다. 누락/잘못된 root는 버리고 가능한 독립 root를 보존한다. 이 결과는 허가가 아니며 준비가 끝난 뒤 기존 `currentSources`를 다시 실행해야 한다. Watch descriptor의 파싱·권한 판단은 기존 Watch prepare가 소유한다. 잘못된 Watch ref가 한 prepare 묶음을 거절하게 할 수 있지만, 다른 ref의 허가와 합쳐서 통과시키지 않는다. **순수 `RoomHistoryEvidencePreparationTest` 38개 검사 통과**.
- 게임 집중 검증 15개 task 성공: codec 92, native cache 114, RAW 719, embedding store 119, knowledge 65, lexical 110, projection 327, capture 40. AI 집중 검증 14개 task 성공: history 준비 38, room memory 98, audience 374, retrieval bundle 185, renderer 65, retrieval shadow 13. AI package 검사 461개 클래스, 게임 클래스 중복 없음.
- 첫 AI 명령은 존재하지 않는 `roomMemoryStoreTest` task를 지정해 **task 선택 단계에서 실패했고 테스트를 실행하지 않았다**. 올바른 task명으로 수정한 후 위 집중 검증이 통과했다. 실패한 실행을 성공으로 합산하지 않는다.
- native runtime GameTest **1/1 통과**, Gradle 9개 task 성공 (`build/native-evidence-runtime-20260930`). mock으로 고정한 game engine을 사용하되 실제 요청 발급·대화 capture·seal·새 대화방 권한 경로를 확인했다. 실제 LLM·인게임 수용 테스트는 아니다. source 철회는 이 GameTest에서 권한을 임의로 넓혀 실행하지 않았으며 별도 SQLite fixture 대상이다.
- 실제 SQLite native seal fixture **109개 검사 통과**: 여덟 read 뒤의 별도 bounded seal, 실제 issued 페이지/가짜·타 Session 거절, A→B 및 중첩 seal, quota·malformed, 원래 W 이후 늦은 private PLAYER receipt 거절, world tamper, migration을 포함한다. 최신 private PLAYER watermark gate 뒤 RAW 719개 검사도 재통과했다.
- 첫 SQLite fixture 컴파일은 `EvidencePointer`/`RoomEvidenceReference` 타입 불일치로 실패했다. 수정 후 첫 실행은 fixture의 source cutover 누락으로 실패했고, fixture를 정확한 기존 기록에 맞춘 뒤 위109개 검사가 통과했다. production의 source 회귀 보호를 완화하지 않았으며 실패한 실행을 성공으로 소급하지 않는다.
- 위 최초 runtime GameTest1/1은 마지막 private PLAYER receipt watermark gate 추가 **이전**의 결과다. 그 gate 추가 후 runtime 재실행은 phase3에서 **실패**했다. 실제 CHAT view는 방·이름 접두사와 원문을 함께 저장하지만 gate가 view 전체와 원문이 정확히 같아야 한다고 검사한 불일치였다. 기존 게임의 완전 수신 계약(CHAT는 원문으로 끝나는 정확한 발송 view, HUD는 원문과 같은 view), 실제 발송 hash·parts·receipt 및 원래 watermark를 보존하도록 수정했다. 수정 후 native GameTest **1/1 통과** (`build/native-evidence-chat-prefix-20260930`, 509.1ms), 기존 세 모듈 SHADOW 대화 **1/1 통과** (`build/native-history-chat-prefix-20260930`, 6.636초). 앞선 실패를 성공으로 소급하지 않는다.
- CONTENT-only SQLite 확장 **156개 검사**, RAW719·projection327·embedding119를 포함해 **9개 task/53초 성공**. 이어 CONTENT+정적 Quest SQLite fixture는 **172개 검사**, 게임 JAR 포함 **8개 task/13초 성공**. 실제 owner 회귀는 AI content106·Quest14, 콘텐츠 레지스트리 공개 검사50을 통과했다.
- 최신 AI 집중 검증 **17개 task/17초 성공**: planner39, 요청 후 근거 재준비35, foreground envelope121, coordinator185, SHADOW renderer65·shadow13, content owner106·Quest owner14·room98. JAR package 검사 **475개 클래스/게임 클래스 중복0**. 새 envelope fixture의 첫 컴파일은 `Status` wildcard import 충돌로 실패했고 명시 import 수정 후 통과했다. foreground 선택 계약과 추가 검증 이력은 [해당 상세 기록](RECORDING_V2_NATIVE_FOREGROUND_PREPARATION_20260930.md)을 따른다.
- 실제 CONTENT+foreground GameTest **1/1 통과**, 768.0ms/9개 task/12초 (`build/native-content-foreground-wire-20260930`). primary/secondary full-wire 회귀도 **1/1 통과**, 6.705초/9개 task/18초 (`build/native-refresh-shadow-wire-20260930`). 실제 owner/게임·AI·콘텐츠 모듈을 사용하되 모델 응답은 offline fixture다. 첫 CONTENT 실행의 닉네임 길이 오류 및 세부 범위는 [콘텐츠 확장 기록](RECORDING_V2_CONTENT_AWARE_RAW_SEAL_NEXT_20260930.md)을 따른다. NEW는 계속 차단하고 production façade 미연결이다. 실제 Ollama·인게임 수용·운영 서버 배포는 미수행이며, 위 수치가 전체 기억 시스템 완료를 뜻하지 않는다.

## 출력과 다음 history

1. 실제 생성·출력 직전에 opaque seal의 현재성을 확인한다.
2. 게임이 발급한 portable ref를 이벤트에 붙인다. archive-only ID를 legacy `sourceMessageIds`에 넣어 기존 RoomMemoryStore가 알아서 검증할 것으로 가정하지 않는다.
3. 다음 요청 또는 restart 후에는 새 audience/speaker 범위에서 ref를 비동기 prepare한다. 원래 read watermark는 당시 포함 가능한 row/receipt의 상한으로만 쓰고, 이후 철회·supersession·현재 정책은 별도로 확인한다.
4. prepare 결과는 store 인스턴스·현재 generation·요청 scope에 묶인 가벼운 current guard로만 보관한다. current 호출에서 디스크를 읽거나 future를 기다리지 않는다.
5. `RecordedRoomSearch`는 실제 저장된 native descriptor를 검증한 뒤 그 roots를 필수 ancestry edge로 확장한다. foreground seal은 별도 `contentAwareClosure`에서 CONTENT/Quest leaf를 owner에게 전달한다. background projection/embedding의 기존 `nativeSource/nativeSources` 허용 범위는 넓히지 않았으며 미지원 외부 ref는 계속 닫는다.

원래 room/turn이 아직 살아 있어야만 과거 대사를 읽도록 만들지는 않는다. 과거 기록을 새 요청에서 적법하게 다시 읽는 것과, 만료된 in-flight 응답을 현재 출력으로 적용하는 것을 구분한다.

별도 미지원 범위: 현재 `chooseSplit`의 그룹 분리 판단은 실제 speech turn이 아닌 scope carrier를 사용한다. 그 경로에서 native history를 cold-cache 상태로 재준비하는 권한을 이번 일반 대화 준비 경로로 우회 발급하지 않는다. 추후 game-issued split 요청용 읽기 권한을 별도로 연결해야 하며, 지금은 필요한 근거를 준비할 수 없으면 해당 history를 제외하는 기존 fail-closed 동작이 유지된다.

## 검증 기준과 남은 범위

다음은 위 집중·GameTest 검증과 향후 통합 검증이 유지해야 하는 기준이다. 이미 수행한 항목의 결과는 위 기록을 따른다. CONTENT+foreground 실제 GameTest, production 연결, 실제 모델·규모·운영 검증을 서로 대신하지 않는다.

- 게임 발급 페이지/Seal만 수락: forged·cloned·타 Session page/token 거절.
- canonical codec, 변경된 world/dataset/manifest/hash/receipt, 누락·여분 dependency, cycle/forward ancestry 거절.
- 늦은 receipt는 원래 W에 소급 포함되지 않음. 원본·receipt 철회는 W 이후라도 즉시 적용.
- 출력 직전 철회, 출력 저장 후 철회, clean restart 뒤 재검증.
- 새 LISTENER/God/공개 방으로 이동해 disclosure가 확대되지 않음.
- native 근거를 쓴 응답을 저장하고 다음 RAW 조회에서 다시 검증하는 실제 SQLite 왕복.
- 외부 ref, 예산/queue/timeout/FULL, malformed/unknown kind는 fail-closed.

상위 원칙은 `추가개발/05_AI_장기기억_다중신_대화_시스템/04_행동참조와_지식공개.md` §6과 `06_검색과_컨텍스트_모델운용.md` §1·§4를 따른다. 오프라인 성공과 실제 모델·인게임 수용은 별도 단계다.
