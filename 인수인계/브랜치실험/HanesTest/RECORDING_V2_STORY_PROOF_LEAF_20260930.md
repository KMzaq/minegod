# Story 근거의 엄격 파싱과 native 발언 근거 연결

2026-09-30 / `HanesTest`. **소스 구현·집중 테스트·실제 Story owner 검증 통과, 미배포.**
이 문서는 과거 Story 대사의 근거를 다시 검증하는 경계를 설명한다. Story 콘텐츠 제작, 게임 상태 변경, 전체 기억 전환 또는 운영 배포 완료 보고가 아니다.

## 1. 변경 범위

- 게임 `StoryRoomConversationService.evidenceCurrent`가 저장되거나 외부에서 전달된 proof를 먼저 엄격하게 파싱한다.
- 파싱 이후 FACT/COVER/PRESENTATION/HOOK의 기존 정의·현재 상태·공개 정책·fingerprint 판정은 그대로 사용한다.
- native 발언 seal의 `RecordedRoomSearch.contentLeaves`에 `STORY_DISCLOSURE_V1`을 추가한다. SQL reader 자체는 Story 공개 권한을 판단하지 않고 원본 근거를 기존 게임 소유자의 `prepare/current`로 전달한다.
- 별도 Story 사실 DB나 스토리 실행 경로를 만들지 않는다. 이 변경 자체는 DB schema를 추가하지 않는다. 다른 작업에서 진행하는 해석 seal/schema 확장과 구분한다.

## 2. 기존 V1 wire 형식과 입력 제한

버전은 `RoomEvidenceReference.kind = STORY_DISCLOSURE_V1`에 있다. 정상 V1 payload에는 별도의 `version` 필드가 없으며, 기존 writer의 다음 12개 필드를 유지한다.

```text
type, actor, god, id, level, cover, policy, source,
scopeType, scopeKey, factValue, fingerprint
```

파서는 깊이 없는 JSON object만 읽는다. 대형 중첩 JSON을 재귀적으로 만들거나 Gson의 누락 필드 기본값·문자열/숫자 자동 변환을 사용하지 않는다.

- payload 최대 **UTF-8 16KiB**. 문자열 길이를 먼저 확인하고 실제 바이트 크기도 검사한다.
- 12개 필드가 정확히 한 번씩 존재해야 한다. 누락·중복·알 수 없는 필드와 payload의 임의 `version`/`schemaVersion` 추가는 거절한다.
- 문자열 필드는 JSON string, `level`은 정상 writer가 쓰는 비음수 정수, `factValue`는 소문자 JSON boolean이어야 한다. `null`, 배열/object, quoted 숫자, 소수·지수·음수·선행 0을 거절한다.
- 잘못된 escape, unpaired surrogate, 문자열 안의 raw control character, 주석·후행 object·trailing comma 등 정상 JSON 밖의 입력을 거절한다. 정상 escape·필드 순서·JSON 공백은 허용한다.
- actor/god/id 및 해당 타입의 policy/cover는 실제 `ResourceLocation.toString()` 형식, fingerprint는 소문자 SHA-256 64자리여야 한다.

타입별 shape 제한:

| 타입 | 유지하는 producer 범위 |
|---|---|
| FACT | level 1–32, policy 필수, cover는 빈 문자열 |
| COVER | canonical cover 대사 index 1–8, policy와 cover ID 필수 |
| PRESENTATION | level 0, factValue false, source 필수; cover/policy/scope는 빈 문자열 |
| HOOK | level 0, factValue false; source/cover/policy/scope는 빈 문자열 |

FACT/COVER/PRESENTATION의 source는 UUID 전용이 아니다. 현재 runtime의 일반 instance 문자열을 보존하며 최대 256 UTF-16 문자다. FACT/COVER의 scope는 없으면 type/key가 함께 비어 있어야 하고, 있으면 기존 SERVER/PLAYER/TEAM enum 및 최대 128자의 key를 사용한다.

**파싱 성공은 권한 승인이 아니다.** 문법상 정상인 다른 fingerprint나 factValue도 현재 게임 정의·지식·사건 상태와 다시 비교한다. 본문을 새 사실로 확정하거나 청취하지 않은 신에게 원문을 공개하지 않는다.

## 3. native leaf가 허용하는 것과 허용하지 않는 것

허용하는 것은 해당 Story 근거를 가진 **실제 과거 대사**를 현재 권한으로 읽고, 그 출처를 다음 발언까지 보존하는 경로다.

1. 실제 발행된 원문·publication context hash·원래 watermark·실제 청취/발송 receipt·전체 ancestry를 확인한다.
2. 원문이 가진 모든 Story leaf를 보존한다. 개별 16KiB 및 기존 전체 leaf 64개/64KiB 제한을 넘으면 일부를 버리고 허가하지 않는다.
3. 게임 스레드에서 실제 Story owner를 통해 현재 공개 가능성과 원래 근거를 재확인한다.
4. 새 요청·재시작·종료된 이전 방과는 별도로 원래 portable ref를 새 범위에서 준비한다. 현재 정책/사실 변경으로 한 필수 leaf가 철회되면 해당 seal과 그 대사를 사용한 후속 발언도 거절한다.

FACT 회상은 새 지식 부여가 아니며, COVER 회상은 숨겨진 진실 공개가 아니다. PRESENTATION 회상은 사건을 다시 해결하지 않는다. HOOK 설명을 회상해도 실행 가능한 Hook token이나 퀘스트 수주 권한은 생기지 않는다. 실제 Story 실행과 canonical 전달에 따른 지식 부여는 기존 시스템이 계속 소유한다.

백그라운드 해석/embedding의 `nativeSource/nativeSources` 범위는 넓히지 않는다. 해당 경로는 외부 소유 근거가 남으면 계속 거절한다. 이 Story leaf 추가가 Watch·Rumor·Legacy 또는 다른 버전의 자동 허가를 의미하지 않는다.

## 4. 구현한 검사와 결과 구분

부모 작업에서 직렬로 실행한 이번 추가분의 결과다. 앞선 CONTENT/Quest 성공을 Story 성공으로 세지 않는다.

| 검사 | 구현된 검증 범위 | 현재 상태 |
|---|---|---|
| `StoryEvidenceProofTest` | 정상 V1 네 타입 round-trip, 각 필드 누락/null/type/중복, unknown/version, malformed JSON, 타입별 범위, UTF-8 경계 | **219 checks 통과** |
| `RecordedNativeEvidenceStoreTest.storyRoundtrip` | 실제 SQLite와 주입한 owner seam으로 네 leaf 보존, 새 청취 God, 재시작, 중첩 후속 대사, 개별 leaf 철회, oversize, 미청취 God 거절 | 포함한 native SQL 전체 **202 checks 통과**; 202개 전부가 Story 전용 검사는 아님 |
| `StoryRoomGameTests.canonicalReceiptsCoverStoriesAndPortableAuthority` | 실제 Story 정의·상태·방 발행, FACT/COVER/PRESENTATION 정상 proof, extra field/새 버전 거절, 정책 및 fact 변경, canonical 수신과 cover의 진실 지식 비부여 | 실제 GameTest **1/1 통과**, 352.5ms; 실제 HOOK 수락/실행의 신규 검증 아님 |

게임 집중 실행은 **16 tasks / 59초 성공**이며, Story219·native SQL202 외에도 해당 실행의 API codec86·projection331·interpretation236 등을 통과했다. 실제 Story owner 실행은 `mythictrpg-main/build/story-proof-owner-wire-20260930`, namespace `mythictrpg_story_evidence`, **8 tasks / 12초 성공**이다.

SQLite owner-seam 검사와 실제 Story owner GameTest는 **아직 하나의 결합된 Story→native seal→후속 발언 테스트가 아니다.** 두 검증의 연결 범위를 과장하지 않는다. 소스 파일의 `git diff --check`도 통과했다. 실제 LLM이나 운영 서버에서는 실행하지 않았다.

별도의 `NativeContentEvidenceGameTests`에는 기존 실제 콘텐츠 owner + foreground 검사 뒤 다음 단계가 추가되었다: **같은 실제 Request의 준비 결과 → 모의 응답 → 실제 게임 publication/capture → 새 실제 요청의 descendant RAW 조회와 발급 Session의 `current(descendant)` 검사**. phase81/82는 저장소 직접 삽입이나 권한 우회 없이 원래 CONTENT leaf와 native 계보를 확인한다. `mythai-ai-response/build/native-content-descendant-wire-20260930`에서 **실제 GameTest 1/1 통과**, 800.2ms, **9 tasks / 13초 성공**했다. 이 결과를 Story owner+native 전체 왕복 검사로 혼동하지 않는다.

## 5. 아직 하지 않은 것

- production NEW 자동 생성 활성화, legacy 검색 자동 전환 또는 미지원 근거의 fallback 허가.
- Story 레이드/퀘스트/사건 콘텐츠나 공개 정책의 변경.
- 실제 Ollama 응답 품질, 운영 서버/클라이언트 배포, 사용자 인게임 수용 테스트.

관련 소스: [Story owner/decoder](../../../mythictrpg-main/src/main/java/com/sande/mythictrpg/story/presentation/StoryRoomConversationService.java), [순수 proof 테스트](../../../mythictrpg-main/src/test/java/com/sande/mythictrpg/story/presentation/StoryEvidenceProofTest.java), [native RAW reader](../../../mythictrpg-main/src/main/java/com/sande/mythictrpg/recording/server/RecordedRoomSearch.java), [SQLite seal 검사](../../../mythictrpg-main/src/test/java/com/sande/mythictrpg/recording/server/RecordedNativeEvidenceStoreTest.java), [실제 Story owner 검사](../../../mythictrpg-main/src/main/java/com/sande/mythictrpg/story/presentation/StoryRoomGameTests.java).

관련 계약: [native seal](RECORDING_V2_NATIVE_EVIDENCE_SEAL_DESIGN_20260930.md), [foreground 준비 경로](RECORDING_V2_NATIVE_FOREGROUND_PREPARATION_20260930.md), [Story 이벤트 가이드](../../../mythictrpg-main/docs/STORY_EVENT_ENGINE_GUIDE.md), [Story AI presentation 가이드](../../../mythictrpg-main/docs/STORY_AI_PRESENTATION_GUIDE.md). 기존 문서의 Story 미지원 표기는 이 추가 구현/검증 단계와 함께 읽는다.
