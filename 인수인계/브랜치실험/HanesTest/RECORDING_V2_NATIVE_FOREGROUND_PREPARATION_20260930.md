# Native 기억의 실제 생성 준비 경로

2026-09-30 / `HanesTest`. **개발 중·미배포. NEW 자동 생성 경로는 아직 차단되어 있다.**
이 문서는 저장/조회 결과를 실제 LLM에 넘길 수 있는 근거로 묶는 후속 구현을 설명한다.
전체 기억 전환, 실제 모델 품질 또는 RPG 전체 완성 보고가 아니다.

## 연결한 범위

| 경로 | 역할과 권한 |
|---|---|
| `RoomRecallPlanner` / `RoomMemoryBridge.planRecall` | 기존 플레이어 회상 focus를 저장소 검색과 분리. 실제 요청을 확인한 진입점으로만 사용하며 계획은 읽기 권한이 아니다. 같은 턴은 중복 계산하지 않는다. |
| `RecordedRetrievalCoordinator.collectNativeSpeech` | 단일 실제 읽기 Session/시점/예산을 재사용하고 실제 발급된 원문 페이지 객체를 유지한다. 비교용 `collect`는 그대로다. |
| `RecordedNativeSpeechPrompt` | 선택한 페이지 전체를 원문 발췌 카드로 렌더링하고 동일 페이지를 게임 seal에 전달한다. 공개 Entry/UUID에서 가짜 Page를 만들지 않는다. |
| `RecordedForegroundMemory.prepare` | 실제 게임 요청 → 독립 회상 계획 → lexical 조회 → 페이지 전체 선택 → 영속 근거 발급 → 현재 콘텐츠 소유자 재검증을 연결한다. 명시적인 개발/시험 API이며 production `respond`나 SHADOW에서 호출하지 않는다. |
| `RoomEvidenceRefresh` / 엔진 `afterEvidenceRefresh` | 오래 걸린 분류·생성·Secondary 응답 뒤 기존 proof가 만료되면 **같은 요청·같은 근거**만 다시 준비한다. 자료를 새로 검색하거나 만료 시간을 무조건 늘리지 않는다. 이 부분은 기존 응답 경로에 연결했다. |

계획은 서버/월드/모드/방/revision/화자/플레이어/신 참가자/공개 여부/청중별로 격리한다.
Secondary 요청의 입력은 다른 NPC의 말일 수 있으므로 플레이어 focus를 읽거나 수정하지 않고 단발 literal 검색만 유지한다.

## 콘텐츠·퀘스트 설명 근거

게임의 영속 manifest는 원본 문장·전체 publication context hash·원래 시점·실제 청취와 발송 영수증을 확인한다.
원문 context에서 복원한 외부 leaf는 게임 스레드에서 기존 소유자의 `prepare/current` 검증을 다시 받는다.

- `CONTENT_DISCLOSURE_V1`: 기존 프로필·로어·예시·관계 공개 소유자, 개별 UTF-8 16KiB.
- `QUEST_CONTENT_DISCLOSURE_V1`: 원래 신의 퀘스트 목록 소유권과 현재 전체 청중에게 공개 가능한 **정적 퀘스트 설명**, 개별 UTF-8 4KiB.
- `STORY_DISCLOSURE_V1`: 기존 Story 소유자의 FACT/COVER/PRESENTATION/HOOK 근거, 개별 UTF-8 16KiB. 실제 사건 해결이나 Hook 실행 권한이 아니다. [strict decoder와 검증 범위](RECORDING_V2_STORY_PROOF_LEAF_20260930.md).
- leaf 합계 최대 64개/UTF-8 64KiB. 일부를 잘라 허가하지 않는다.
- 퀘스트 설명을 회상했다고 수주·완료·현재 수주 가능·보상 지급 권한이 생기지 않는다.
- Watch/Legacy 등의 다른 leaf는 아직 seal 지원 대상이 아니다. 별도 원본 DB를 만들지 않았다.
- 백그라운드 해석·embedding의 기존 `nativeSource/nativeSources` 조건은 넓히지 않았다.

schema9 및 native descriptor/manifest v1 형식은 유지한다. hash 자체가 권한이 아니라 실제 발급 row와 현재 원본/영수증/소유자 검증이 권한의 근거다.

## 선택·출력 계약

`Selection`은 텍스트를 직접 내주지 않는다. 실제 게임 seal 성공 후 `Sealed.payloadFor(scope)`가 텍스트와 portable reference를 함께 반환한다.
`RecordedForegroundMemory.Prepared.payloadFor(server, request)`는 동일 실제 요청과 현재 근거를 한 번 더 확인한다.

현재 façade는 lexical 원문만 검색한다. 독립 renderer는 실제 semantic Page도 지원하지만 façade에서 embedding을 호출하지 않는다.
Watch·Rumor는 수집하지 않으며, 해석 카드도 prompt에 넣지 않는다.

부분 필터링/중복 source ID/알려진 해석의 input·quote가 포함된 페이지는 통째로 제외한다.
이 RAW-only 경로에서는 정정 내용을 빼고 오래된 약속만 남기는 일을 피하기 위한 보수적 제한이다. 별도 typed 해석 경로는 [후속 계약](RECORDING_V2_INTERPRETATION_SEAL_CONTRACT_20260930.md)을 따른다.
따라서 해석이 많은 데이터에서는 회상 결과가 줄 수 있으며, 이를 검색 완성이라고 보지 않는다.
빈 결과·제외·시간 초과는 “그런 일이 없었다”의 증거가 아니다.

모델 호출 뒤에는 `revalidateForPublication` 또는 엔진의 기존 전체 evidence 재검증을 거쳐야 한다.
읽기 Session의 60초 lease와 모델의 더 긴 제한 시간을 혼동하지 않는다.
기록 OFF 대화의 정상 응답은 storage 읽기 권한을 요구하지 않는다. 실제 재준비가 필요한 경우에만 게임의 `memoryReadCurrent`를 요구한다.

## 검증 진행 기록

- CONTENT+Quest SQLite fixture **172 checks 통과**, 게임 JAR 포함 **8 tasks / 13초 성공**.
- 앞선 CONTENT-only 단계에서 RAW719·projection327·embedding119를 함께 통과했다(9 tasks / 53초).
- 기존 실제 CHAT 접두사 수정 후 native GameTest **1/1** (`build/native-evidence-chat-prefix-20260930`, 509.1ms), 기존 세 모듈 SHADOW 대화 **1/1** (`build/native-history-chat-prefix-20260930`, 6.636초) 통과.
- AI 소유자106 및 콘텐츠 레지스트리 실제 공개 검사50 통과. 처음 planner fixture는 잘못된 다중 GodState 입력으로 실패했고 실제 단일 화자 계약으로 수정했다.
- 새 envelope의 첫 test compile은 두 `Status` enum wildcard import 충돌로 실패했다. 명시 import로 수정했으며 후속 실행 결과는 아래에 추가한다.
- 새 실제 CONTENT GameTest의 첫 실행은 fixture 닉네임이 Minecraft의 16자 제한을 넘어서 실패했다. `ContentReader`로 수정했다. 실패한 실행을 성공으로 세지 않는다.
- 수정 후 AI focused **17 tasks / 17초** 성공: planner39·refresh35·native speech prompt121·coordinator185·SHADOW renderer65/consumer13·콘텐츠 owner106·Quest owner14·방 엔진98, 패키지 **475 classes / 게임 중복0**.
- 실제 콘텐츠 소유자+foreground 왕복 GameTest **1/1 / 768.0ms** 통과 (`build/native-content-foreground-wire-20260930`, 9 tasks / 12초). 같은 요청의 payload/재검증, 다른 턴·종료 방·새 방의 facade 재사용 거절과 새 private 방의 portable reference 재검증을 구분해 확인했다.
- 엔진 refresh 연결 후 기존 Primary→Secondary SHADOW 실제 모듈 모의 대화 **1/1 / 6.705초** 통과 (`build/native-refresh-shadow-wire-20260930`, 9 tasks / 18초). 실제 Ollama 호출이 아니다.
- 이 체크포인트 SHA-256: 게임1.0.22 `50482DAF230C35F06B4E686F9754540C7CEDEA321CFC7528C2D724AF34BCFF32`, AI0.1.25 `5DE70C99B17352B038C441ABCF56DA113435701A95E6B8ED6ADA4B530618BFA6`. 같은 버전의 후속 소스/빌드와 구분한다.
- schema10/Story 추가 뒤 CONTENT 실제 publication/capture → 새 턴 descendant RAW 조회까지 **1/1 / 800.2ms** 통과 (`build/native-content-descendant-wire-20260930`, 9 tasks / 13초). 원래 요청의 현재 소유자 검증 후 실제 발행 이벤트에 native ref가 남았고 다음 조회도 실제 수신 기록을 사용했다.
- 같은 시점 actual Story owner **1/1 / 352.5ms** (`build/story-proof-owner-wire-20260930`, 8 tasks / 12초), 게임 focused16 tasks / 59초(native SQL202 포함), AI focused11 tasks / 10초(speech121/coordinator185/history38,475 classes/게임 중복0) 성공. Story SQL seam과 actual owner를 한 종단 시험으로 합친 결과는 아니다.
- 이 후속 체크포인트(typed issuer 구현 전) SHA-256: 게임1.0.22 `1F0AC3138A8B1E6405476EE4940BB92E6E038D614B9B14DA11EF922E536F8B68`, AI0.1.25 `00E3A4CD6DA8CB9D62EC4EBFABDDB94460E5AB2A7E68ECA8AB505C4E43A00F5D`. 이후 같은 버전의 재빌드는 다시 대조한다.

## 아직 필요한 다음 연결

1. 실제 콘텐츠 foreground 격리 왕복은 위 검사로 확인했다. 운영 foreground 전환은 아직 하지 않는다.
2. interpretation·Watch·Rumor의 각 소유 근거와 영속 seal 확장. Story는 위 범위로 추가했으며 정적 Quest 설명과 게임 실행 상태는 계속 분리.
3. production 분류/생성 경로에서 새 retrieval 정책에 따른 사용·생략/실패 구분과 legacy 검색 없는 경로 연결.
4. 새 경로의 출력 저장·후속 조회·취소/재시작을 확인한 뒤 별도 승인 범위에서 제한적 NEW 시험. 현재 정책 차단을 미리 풀지 않는다.
5. 실제 Ollama 품질·운영 서버/클라이언트·규모 검증과 배포. 이 작업에서 수행하지 않았다.

관련: [영속 seal 계약](RECORDING_V2_NATIVE_EVIDENCE_SEAL_DESIGN_20260930.md), [콘텐츠 후속 설계](RECORDING_V2_CONTENT_AWARE_RAW_SEAL_NEXT_20260930.md), [공유 검색](RECORDING_V2_M5_SHARED_RETRIEVAL_20260930.md), [작업표](SYSTEM_COMPLETION_TRACKER.md).
