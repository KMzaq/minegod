# 기존 기억·평판 재사용 점검과 보완

2026-09-29 · `HanesTest` · 게임 **1.0.20** / AI **0.1.23** / 콘텐츠 **0.1.3** · 미배포

이미 구현된 기억·평판을 확인하고 재사용했다. 기존 기능을 없는 것으로 간주하여 새 저장소나 평판 원본을 중복 작성하지 않았다. 새 recording-v2 전체 전환 완료 기록은 아니다.

## 확인한 기존 구현

| 영역 | 실제 구현과 사용 범위 |
|---|---|
| 현재 대화방 기억 | `MythAiRoomConversationEngine`이 `DialogueMemoryBridge.beginRoomAsync`와 `RoomMemoryBridge.recall`을 함께 호출한다. `RoomMemoryStore`에는 원문·화자·실제 발행/청취 근거·공개/비밀 청중과 의존 근거 철회가 있다. |
| 기존 개인 기억 검색 | `MemoryJournal`, `RecallSearch`, `MemoryRecallPolicy`, `DerivedService`, `MemoryIndexRuntime`에 검색·시간 조건·선택적 파생/의미 검색 경로가 있다. 새 방 원문은 RoomMemoryStore에 저장되므로 이 기능들이 새 방 원문 전체에 자동 적용되는 것은 아니다. |
| 행동·주시 기억 | 게임의 observation/watch/ExperienceMemory 경로가 존재한다. 구 근거의 신 청중 정보가 부족하므로 이번에도 경험 기억은 단일 신 비밀방 제한을 유지했다. |
| 소문·평판 | `CourierEngine/CourierRumorService`의 실제 전달, `RumorSavedData`, `ReputationLedger/Service`, `SocialReview/SocialRuntime`의 수신·개별 평가·해명/회복을 재사용한다. 게임이 관계 수치와 실제 변경을 소유한다. |
| recording-v2 | SQLite 원문/receipt/철회·작업 큐와 게임 소유 쓰기 포트가 있다. `GameRecordingPort`는 쓰기 인터페이스이며 전체 AI 검색 포트는 아니다. `WorldRecordingService.inspect`는 진단용이므로 AI 권한 필터를 우회하는 검색 API로 사용하지 않았다. |

## 이번 최소 변경

### 다신 비밀방의 공통 수신 소문

게임에 읽기 전용 `RoomRumorAccess`를 추가했다. 별도 소문 DB가 아니라 기존 저장소와 현재 전서구 수신 증거의 투영이다.

- 새 턴 조회 `heard`: 원래 평가를 제공하는 신이 현재 방에 있어야 한다.
- 과거 발언 검증 `referenced`: 원래 발언한 신이 방을 나갔어도 남은 청취자가 가진 기억을 검증할 수 있다. 원래 신의 현재 근거와 현재 모든 신의 동일 소문 수신 증거는 여전히 필요하다.
- 다신 조회는 root/revision/text/epithet가 일치하는 수신만 허용한다. 각 신의 믿음·반감·회복 상태까지 같아야 하는 것은 아니다. 한 신의 해명을 다른 신의 평가로 복사하지 않는다.
- 현재 전체 플레이어 청중도 허용돼야 한다. 미수신 신, 새로 공개 불허된 청중, 철회/변경된 근거는 제외한다. 참가자를 추가하거나 새 수신 권한을 만들지 않는다.
- `LegacyRoomEvidence`는 원래 신의 평가를 포함한 fingerprint를 다시 검사한다. 방 원문의 청취/부모 의존 근거 검사도 유지한다. 과거 발언이 현재 화자의 독자적 믿음으로 승격되지 않는다.
- 기존 단일 신 읽기 호환성을 유지한다. `PUBLIC` 평판 공개와 `RUMOR_TEST` 외 모드 자동 활성화는 하지 않는다.

`ReputationService`의 개인 평가 메타데이터도 수정했다. `knownByGodIds`는 평가한 신만 포함하고, 해당 평가를 들을 수 있는 청중은 별도 publication scope로 유지한다. 같은 소문을 아는 것과 동일한 평가를 소유하는 것은 다르다.

### 실제 프롬프트와 선택 근거 일치

구 fallback 포맷의 항목 수/문자 예산으로 잘린 소문이 후속 회복 판단의 대상 목록에 남을 수 있었다. `DialogueMemoryBridge.packLegacy`가 **실제로 들어간 항목과 프롬프트를 함께 반환**하도록 했다. 현대 방 호출의 fallback과 경험 기억 결합 후 재포장을 이 결과로 맞췄다. 이미 같은 방식으로 동작하던 `MemoryRecallPolicy.pack` 경로는 유지했다.

새 방의 `roomRecoveryTopics`에는 최종 프롬프트에 포함된 소문만 전달된다. 회복은 여전히 게임의 턴·관계 원본·소문/평가 버전 검증과 Reviewer를 거친다. 현재 Primary 중심 경로이며 모든 Secondary가 자동으로 독립 회복 심사를 수행하도록 확장한 것은 아니다.

방 검색 설정의 기준 경로도 프로세스 상대 경로 대신 `server.getServerDirectory()`로 맞췄다.

### 회상 질문이 자기 자신의 증거가 되는 문제

현재 플레이어 발화가 게임에 먼저 발행된 뒤 기억 검색이 실행되므로, 방 저장소가 현재 회상 질문 자체를 과거 근거로 선택할 수 있었다. 기존 `RecallQuery` 정책을 재사용하여 다음을 제외한다.

- 일반 검색에서 현재 입력과 동일한 원문 및 최근 히스토리에 이미 있는 항목
- 명시적 회상에서 과거의 회상 요청 자체와 내용 없는 후속 질문

일반적인 의미 있는 질문은 기억할 수 있고 원문을 삭제하지 않는다. 현재 저장 레코드에는 해당 턴 ID가 없어 동일 문장/질문 역할 필터를 사용한다. 모든 문맥을 의미 분석하거나 이벤트 ID로 구분하는 검색으로 완성한 것은 아니다.

## 검증

컴파일·JAR 생성과 아래 영향 범위 검사를 실행했다. 전체 오프라인 build를 이번 수정 후 다시 수행한 것은 아니다.

| 검사 | 결과/범위 |
|---|---|
| 게임 `socialPipelineTest` | 87 assertions. 실제 CourierEngine/RumorLedger fixture로 다신 수신·다른 평가·새 청중/철회·복원·원래 신 퇴장 검사 |
| 게임 `reputationStageTest` | 4,221 checks. 기존 평판 단계 회귀 |
| 게임 `roomSocialContextTest` | 2,074 assertions. 평가 소유 신과 공개 청중 분리 포함 |
| AI `memoryAudienceTest` | 250 checks. 회상 질문 자기 참조와 기존 청중/철회 검사 포함 |
| AI `rumorDialogueTest` | 21 assertions. 항목/문자 예산 이후 실제 prompt와 근거 목록 일치 |
| AI `roomConversationEngineTest` / `memoryRecallTest` | 98 / 54 checks |
| AI `verifyEnginePackage` | AI 413 classes, 게임 클래스 중복 없음 |
| 세 모듈 GameTest | 게임1.0.20·AI0.1.23·콘텐츠0.1.3 실제 로드, `mythai_room_full_path` 1/1 통과. LLM transport는 모의 |

통합 결과: `mythictrpg-main/build/memory-reputation-reuse-20260929/logs/latest.log`. 실제 방 파이프라인 회귀이며 **물리 전서구 + SocialReview 의미 판단 + 다신 대화의 새로운 종단 검사나 실제 Ollama 품질 평가가 아니다.** 임시 GameTest 서버는 자동 종료했다.

루트에서 순차 재현한다. 병렬 Gradle을 사용하지 않는다.

```powershell
.\dev-tools\run-mythictrpg-gradle.cmd socialPipelineTest reputationStageTest roomSocialContextTest jar --offline --no-daemon --max-workers=1
.\dev-tools\run-mythictrpg-gradle.cmd -p ..\mythai-ai-response compileJava memoryAudienceTest rumorDialogueTest roomConversationEngineTest memoryRecallTest jar verifyEnginePackage --offline --no-daemon --max-workers=1
.\dev-tools\run-mythictrpg-gradle.cmd runRoomIntegrationGameTestServer -ProomAiIntegration=true -ProomTestNamespaces=mythai_room_full_path -ProomTestDirectory=build/memory-reputation-reuse-20260929 --offline --no-daemon --max-workers=1
```

## 호환·남은 일

- 저장 스키마/기록 모드/게임 network protocol7은 변경하지 않았다. AI 최소 게임 버전은 새 읽기 helper 때문에 **1.0.20**이다. 향후 배포 시 게임과 AI를 함께 맞추고 클라이언트 게임 JAR도 동기화한다.
- 운영 설정·기억·평판·월드·서버/클라이언트 JAR은 교체하지 않았다. 실제 LLM 호출과 운영 서버 실행/중지는 하지 않았다.
- 기존 검색·평판을 폐기하지 않는다. 새 방 기억의 시간/파생/의미 검색 연결, recording-v2의 정식 읽기/소스·공개 권한/철회와 legacy writer 전환, 일반 운영 평판 활성화는 여전히 남았다. 무제한 진단 조회나 이중 원문 기록으로 우회하지 않는다.
- 최신 작업 범위와 남은 시스템은 [작업표](SYSTEM_COMPLETION_TRACKER.md), 사회 모드의 제약은 [사회 방 포트](ROOM_SOCIAL_REPUTATION_M3_PORT.md)를 함께 본다.
