# Native RAW seal — 콘텐츠·정적 퀘스트 근거와 남은 확장

2026-09-30 / `HanesTest`. **당초 검토안의 CONTENT 지원과 후속 정적 Quest 지원을 구현했고 집중 검증 및 실제 CONTENT+foreground GameTest를 통과했다.** 파일명은 기존 링크 호환을 위해 유지한다. NEW 차단·production façade 미연결·운영 미배포 상태다. 영속 계약은 [native seal 상세 기록](RECORDING_V2_NATIVE_EVIDENCE_SEAL_DESIGN_20260930.md), 실제 Page 선택부터 생성 준비까지는 [foreground 상세 기록](RECORDING_V2_NATIVE_FOREGROUND_PREPARATION_20260930.md)을 따른다.

## 해결한 공통 문제

일반 `MythAiRoomConversationEngine.respond`는 출력 근거에 항상 `CONTENT_DISCLOSURE_V1`을 추가한다. 따라서 실제 프로필을 사용한 NPC 답변은 외부 ref가 없는 합성 native 발언과 다르다.

기존 RAW query는 외부 ref를 반환하고 실제 owner에게 준비/현재성을 검사시켰지만, native seal은 non-native ref를 모두 거절했다. 따라서 프로필을 사용한 정상 NPC 답변을 다시 native 근거로 발급할 수 없었다. 이를 foreground 전용 `contentAwareClosure`와 실제 owner 검증으로 해결했다. background `nativeClosure/nativeSource/nativeSources`를 전역적으로 완화한 것은 아니다.

## 현재 구현 범위

현재는 **RAW native 발언 + `CONTENT_DISCLOSURE_V1`/`QUEST_CONTENT_DISCLOSURE_V1` leaf**를 지원한다. 게임 identity·실제 수신·원래 W·계보·폐기 검사에 더해, 원래 콘텐츠 작성자/화자와 현재 청중에서 해당 콘텐츠를 계속 공개할 수 있는지 기존 owner가 검사한다.

`kind == CONTENT_DISCLOSURE_V1`은 지원 가능한 형식이라는 뜻일 뿐 허가가 아니다. 실제 판단은 AI의 기존 `RoomKnowledgeContext.validEvidence` → 콘텐츠 레지스트리 `audienceContentFor`에 남긴다. 프로필·로어·관계의 별도 원본 DB를 게임 SQLite에 만들지 않는다.

Quest leaf는 `RoomQuestKnowledge.validEvidence`로 원래 신의 목록 소유권과 현재 전체 청중에게 공개 가능한 **정적 퀘스트 설명**을 검사한다. 현재 수주 가능성·수주·완료·보상 실행 권한이 아니며 게임 진행도를 이 ref에 저장하거나 덮어쓰지 않는다.

| 근거 | 현재 상태 | 이유/다음 순서 |
|---|---|---|
| CONTENT_DISCLOSURE_V1 | 구현·검증 | 일반 NPC 발언의 필수 근거. 현재 owner가 원래 화자의 지식과 새 청중으로 fingerprint를 재검증 |
| QUEST_CONTENT_DISCLOSURE_V1 | 구현·집중 검증 | `RoomQuestKnowledge.validEvidence` 재사용. 정적 설명과 실제 퀘스트 진행/실행은 분리 |
| STORY_DISCLOSURE_V1 | 후속 독립 검증 | 게임 `StoryRoomConversationService`의 현재 runtime/state·공개 권한을 유지. 콘텐츠 checksum만으로 스토리 상태를 허가하지 않음 |
| WATCH_OBSERVATION_V1 | 이번에는 제외 | 비동기 실제 관찰 proof와 private/단일 신 계약을 보존하는 후속 adapter 필요. 새 typed Watch card sealing과 과거 NPC 발언의 Watch dependency는 다른 작업 |
| LEGACY_* 및 구 개인 journal 근거 | 제외 | NEW에서 제외한 구 시험 기억을 우회 재도입하지 않음. RAW에 저장되어 있다는 이유만으로 허가하지 않음 |
| 미지원 kind / 해석 candidate / Rumor | 제외 | 각각의 실제 소유자·현재 평가/버전·영속 provenance 계약을 연결하기 전 fail-closed |

## 구현한 DAO·Session 계약

공개 Entry/모델 JSON을 권한으로 쓰지 않는다. 아래 새 타입은 game `recording/server` package-private 결과로 제한한다.

```text
SpeechClosure(nodes: Map<messageId, Node>, ownerReferences: List<RoomEvidenceReference>)
ValidatedNative(reference, manifest, ownerReferences)
IssuedNative(seal, ownerReferences)
```

`ownerReferences`는 실제 hash-검증된 각 원본 `PublicationContext.evidence`에서 얻은 **전체 leaf union**이다. Native descriptor 자체는 DAO에서 다시 풀어 parent edge로 검증하므로 owner 목록에 포함하지 않는다. 내용이 같은 ref는 중복 제거하되 임의 조합·요약·일부 잘라내기는 하지 않는다.

1. `RecordedRoomSearch`의 별도 `contentAwareClosure` 경로를 사용한다. 기존 `nativeSource/nativeSources`의 전역 조건은 바꾸지 않았다. 해석·embedding background가 새 형식 권한을 자동으로 얻지 않는다.
2. `RecordedNativeEvidenceStore.issue`는 기존 실제 source/knowledge/delivery/hash/원래 W 검증과 content-aware closure를 수행하고 `IssuedNative`를 반환한다. `validate`는 boolean 대신 `Optional<ValidatedNative>`를 반환한다. sourceHash는 원본 본문 hash와 **전체 context JSON**을 이미 묶으므로 외부 ref의 종류/payload도 기존 dependency hash로 고정된다.
3. `WorldRecordingService`는 단일 SQLite snapshot에서 위 결과를 얻고 기존 writer quota/rollback/읽기 예산을 유지한다. SQL worker에서 콘텐츠 레지스트리나 Minecraft API를 호출하지 않는다.
4. `RecordedMemoryAccess.Session`은 game dispatcher로 돌아온 뒤 `prepare.apply(ownerReferences)`를 호출한다. 원래 요청/engine/store/authority generation·선택 페이지가 계속 유효한지 준비 전후 다시 확인한다. 지원 whitelist 밖 ref가 하나라도 있으면 전체 해당 seal을 거절한다.
5. 모든 owner 준비가 성공한 뒤에만 seal/ref의 current guard를 등록한다. guard는 기존 Session 유효성, 원래 실제 발급 페이지, `evidenceCurrent(ownerReferences)`, 해당 future의 성공을 모두 요구한다. timeout/cancel/dispatch 실패/늦은 callback은 등록된 문자열만으로 허가를 남기지 않는다.
6. `NativeRoomEvidence.prepare`는 이 Session 경로만 사용한다. native pointer를 owner 준비 목록에 다시 넣지 않아 재귀 준비·자기 참조를 만들지 않는다. 이후 일반 RAW 재조회에서는 전체 ancestry의 content leaf를 Candidate의 evidence 목록에 전달하여 기존 page prepare/current도 다시 검사한다.

게임 closure의 상한은 기존 페이지8·root/dependency64·manifest64KiB와 별도로 owner leaf 최대64개/전체 UTF-8 payload64KiB다. 개별 payload는 CONTENT UTF-8 16KiB, Quest UTF-8 4KiB를 초과하면 seal 전체를 거절한다. owner 파서 자체의 기존 문자 수 제한도 별도로 유지한다. 더 작은 시간/본문/깊이 budget이 먼저 닿을 수 있다.

## manifest/version 변경 여부

**이번 확장은 schema9 및 v1 manifest 형식을 유지했다.** dependency의 sourceHash가 원문 hash와 실제 저장된 `PublicationContext` JSON을 이미 묶고 있으며, 재검증 때 반드시 원본 context를 다시 읽고 hash를 대조한 뒤 정확한 외부 ref union을 복구하기 때문이다. DAO가 반환한 임시 목록을 그 자체로 durable grant로 저장하지 않는다.

external 목록은 manifest에 중복 복사하지 않고 source dependency에 유지한다. 구 reader는 지원하지 않는 외부 ref를 계속 거절하므로 구버전에서 권한이 넓어지지도 않는다. 단, v1 wire가 같더라도 **지원 범위가 달라졌으므로 모듈 간 지원 범위와 producer/consumer 검증을 함께 확인**해야 한다.

향후 source 원문 없이 외부 근거를 독립 복원하거나, candidate의 payload/version, Rumor assessment revision 등 native sourceHash에 없는 새 의미를 seal하려면 그때 별도 manifest version/kind를 정의한다. 지금 v1 hash에 존재하지 않는 의미를 끼워 넣지 않는다.

## 구현 파일과 검증

- 게임: `RecordedRoomSearch`, `RecordedNativeEvidenceStore`, `WorldRecordingService`, `RecordedMemoryAccess`. 이번 leaf 확장으로 API codec을 변경하지 않았다.
- AI/콘텐츠: 기존 `MythAiRoomConversationEngine.prepareRecordedEvidence`, `RoomMemoryBridge`, `RoomKnowledgeContext`/`RoomQuestKnowledge`/`AiContentRegistry`의 owner 검증을 재사용. 새 권위 저장소 없음.
- 실제 SQLite: 프로필 ref가 있는 NPC 발언 → RAW page → seal → 새 답변 저장 → 다른 턴/restart RAW 재조회; 원래 화자 A와 실제 수신 신 B, 새 미수신 신/새 private listener/공개 전환 거절.
- owner false, profile/lore 변경, malformed/unknown kind, nested native+content, 참조/byte 초과, 원래 W 이후 수신, source/receipt 철회 및 callback 사이 reload/timeout/cancel을 검사한다.
- background 기존 테스트: content ref 입력은 계속 unsupported이며 모델 호출·projection/embedding 생성이 증가하지 않아야 한다.

실제 검증 결과:

- CONTENT-only SQLite **156 checks** 및 RAW719·projection327·embedding119: **9 tasks/53초 성공**.
- 후속 CONTENT+Quest SQLite **172 checks**, 게임 JAR 포함 **8 tasks/13초 성공**.
- AI content owner **106 checks**: malformed 사전 거절, 같은 generation의 프로필·로어·예시·관계 변경, 원래 source와 현재 전체 청중 분리, 반복 current 검사와 실패 후 실제 owner 재조회. Quest owner **14 checks**, 레지스트리 실제 공개 검사 **50 checks**도 통과했다.
- foreground 준비 관련 최신 AI 집중 검증은 **17 tasks/17초 성공**, package475개 클래스/게임 중복0이다. envelope121·planner39·refresh35를 포함한 세부 결과와 실패 수정 이력은 [foreground 상세 기록](RECORDING_V2_NATIVE_FOREGROUND_PREPARATION_20260930.md)을 따른다.
- 실제 세 모듈 CONTENT+foreground GameTest **1/1 통과**, 768.0ms/9 tasks/12초 (`build/native-content-foreground-wire-20260930`). 실제 CONTENT owner → 기록/조회 → whole-page envelope → seal → 새 요청 재검증을 확인했다. 첫 실행은 fixture 닉네임이 Minecraft 16자 제한을 넘어 실패했고 `ContentReader`로 수정한 뒤 통과했다. 테스트 데이터만 수정했으며 실패 실행은 성공으로 합산하지 않는다.
- primary/secondary full-wire GameTest **1/1 통과**, 6.705초/9 tasks/18초 (`build/native-refresh-shadow-wire-20260930`). 모델 완료 뒤 같은 요청·근거를 재준비하는 기존 경로 회귀다. 실제 Ollama·플레이어 UI 수용·운영 배포 검증은 아니다.

실제 CONTENT GameTest의 공개 전환 거절은 private 원본 ACL에서 먼저 차단될 수 있다. 이것을 실제 Registry의 모든 공개 정책 조합을 검사한 결과로 확대하지 않으며, owner106/레지스트리50의 별도 범위와 구분한다. 정적 Quest의 실제 수주·보상 실행을 이 검증에 포함했다고 주장하지 않는다.

## background projection/embedding과 혼동하지 말 것

idle worker에는 현재 대화 `Request`가 없다. 현재 foreground owner 검증을 가짜 Request로 흉내 내거나 저장된 과거 roomId를 live permission으로 사용하지 않는다.

콘텐츠 기반 발언까지 EVENT/RELATIONSHIP/SUMMARY 또는 embedding을 확장하려면 후속으로 게임 thread에서 실제 work의 world/dataset/observer God/전체 저장 audience를 확인하고, 콘텐츠 owner가 그 범위의 명시적 extraction lease를 발급해야 한다. commit에서도 같은 lease와 source/receipt/content fingerprint를 재검증한다. 모델 호출과 SQL 처리는 계속 background이며 원래 원본과 observer를 바꾸지 않는다. 이 기능은 RAW-only 초기 NEW 시험의 필수 조건은 아니지만, 일반 NPC 답변의 의미 검색/모델 파생을 지원하려면 필요하다.

## 실제 NEW 전환까지의 권장 순서

1. **기존 native 회귀 완료:** CHAT/HUD/W 수정 뒤 native GameTest1/1과 기존 세 모듈 SHADOW1/1 통과. 세부 이력은 native seal 문서 참조.
2. **CONTENT+정적 Quest 소스·집중 검증 및 CONTENT 실제 GameTest 완료:** Story 등 미지원 source는 계속 제외한다.
3. **준비 구현·검증 완료:** 실제 issued Page identity를 유지하는 foreground handle, legacy 검색 없는 focus planner, 같은 근거 재준비를 추가했다. [foreground 상세 기록](RECORDING_V2_NATIVE_FOREGROUND_PREPARATION_20260930.md)으로 계약을 공유하며 Bundle Entry에서 Page를 재구성하지 않는다. façade는 production에 미연결이다.
4. **아직 미연결:** 원문-only 제한 NEW 생성 경로. 동일 권한 자료를 분류/생성에 전달하고 seal/current를 호출 직전과 발행 직전에 검사하며 실제 발행에만 ref를 기록해야 한다. 구 journal/legacy recall을 자동 fallback으로 사용하지 않고 실패 시 허용된 현재 대화만 사용함을 상태로 구분해야 한다.
5. 정정 candidate를 프롬프트에 포함하려면 그 **이전에** actual interpretation page + 최신 extractor/job/payload hash + 전체 입력의 영속 seal을 추가. 이를 RAW source seal로 위장하지 않음. Watch/Rumor card도 각 owner의 실제 관찰/수신/현재 평가 계약을 추가한 뒤에만 사용.
6. 실제 모델/격리 서버/사용자 승인한 규모 검증 후 작은 NEW 시험을 허용. spec09의 정확성·복구·비밀 경계가 필수이며 fixture만으로 자연스러움 완료를 선언하지 않음.

game-issued split read, 외부 source 의미/모델 파생, 장문 전체 semantic coverage·대규모 rank 결합, 다신 범위 확대는 제한 RAW-only 첫 시험의 선행 조건은 아니다. 다만 생략했다고 M4/M5/M6 전체 완료로 표시하거나 전체 NEW 기능인 것처럼 설명해서는 안 된다.

실행 lifetime은 계속 구분한다. read Session은60초, `AiDialogueConfig`의 모델 요청 기본 timeout은180초(허용15~600초)이며 분류/생성이 순차 진행될 수 있다. SHADOW lease나 만료 검사를 완화하지 않았다. `RoomEvidenceRefresh`와 엔진의 `afterEvidenceRefresh`는 모델 완료 후 **같은 아직-current Request·같은 근거**만 제한적으로 다시 prepare하도록 연결되었으며35개 검사와 위 full-wire 회귀를 통과했다. 이것이 새 retrieval façade의 production 사용을 뜻하지 않는다. 실제 NEW 연결에서도 재준비·현재 owner/source 검사를 유지해야 하며 최초 프롬프트 직전 허가만으로 만료·철회된 결과를 발행하지 않는다.
