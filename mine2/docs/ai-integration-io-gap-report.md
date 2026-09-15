# MythicTRPG 로컬 AI 대화 엔진 IO Gap Report

## 1. 조사 기준

이 보고서는 다음 구현을 비교한 결과다.

- MythicTRPG 기준 커밋: `e65e1a7816dae3a9b74624ed7ead94f888f2ec62`
- AI 엔진 계약: `MYTHICTRPG_AI_대화엔진_JAR_통합_가이드.md`
- 콘텐츠 계약: `AI_CONTENT_REGISTRY_IO_CONTRACT.md`
- 1단계 판단 계약: `MYTHICTRPG_AI_1단계_판단태그_IO_규격.md`
- 참고 바이너리: 테스트 서버의 결합형 `mythictrpg-1.0.0.jar`

문서가 충돌하는 경우 이번 작업 지시와 위 3개 AI IO 계약을 우선한다. 특히 기존 `docs/ai-dialogue-integration-bridge.md`의 RisuAI 전제와 단계 계획은 과거 참고사항이며 이번 구현 범위가 아니다. 이 연동에는 RisuAI 전용 코드, 설정, transport를 추가하지 않는다.

참고 바이너리는 게임 클래스와 AI 클래스를 같은 `mythictrpg` 모드 ID 및 같은 Java 패키지에 포함한다. 새 프로젝트와 동시에 로드할 수 없으므로 배포 의존성으로 추가하지 않았다. 이 프로젝트의 연동 코드는 가이드가 요구하는 고유 모드 ID의 분리 AI JAR이 제공될 때 공개 API를 선택적으로 발견하는 방식이다.

## 2. 이미 일치하는 항목

- God 식별자는 `ResourceLocation`이며 파일 경로 기반 namespaced ID를 그대로 사용한다.
- `InteractionPlan`은 시작 승인이 아니라 불변 계획이고, 최종 legality 재검증과 encounter commit은 서버 스레드의 `InteractionStartService`가 담당한다.
- interaction ID는 commit 경계에서 `InteractionIdGenerator`가 만드는 UUID다. AI session ID와 별개로 전달할 수 있다.
- 플레이어 식별자는 UUID이며 `PlayerMythProfile`의 canonical 원본은 `PlayerMythDataRepository`/`PlayerMythDataService`다.
- affinity는 `PlayerMythProfile.affinities(): Map<ResourceLocation, Integer>`로 읽을 수 있다. 누락 값의 현재 의미는 0이다.
- God unlock/appearance/선택/encounter는 기존 interaction pipeline이 판정하며 AI에 위임하지 않는다.
- `DialoguePresentationService`와 `ClientDialoguePayload`가 서버 검증, sanitization, 크기 제한, Network 전송, 중앙 HUD 표시를 담당한다.
- `InteractionOrchestrator`는 비동기 content preparation 완료 후 `server.execute(...)`로 서버 스레드에 복귀한 다음 commit한다.
- AI 계약의 1단계 태그는 실행 명령이 아니며 현재 MythicTRPG에도 태그를 게임 상태 변경으로 해석하는 경로가 없다.

## 3. MythicTRPG 측 Adapter만 추가하면 되는 항목

- 승인된 interaction의 audience UUID를 온라인 `ServerPlayer`로 변환하고 primary/secondary God ID와 interaction UUID를 AI `startConversation(...)`에 전달하는 post-commit Adapter.
- 기존 profile affinity와 현재 서버 상태를 AI 엔진의 턴별 Snapshot으로 읽는 경계. 결합형 참고 엔진에는 `MythicTrpgConversationSnapshotProvider`가 있으므로 별도 관계 저장소는 필요 없다.
- 엔진의 정상 speech를 기존 `DialoguePresentationService`로 보내는 경계. 분리 AI JAR도 MythicTRPG 공개 HUD API를 호출해야 하며 별도 HUD/Network를 만들면 안 된다.
- 활성 AI 세션의 일반 채팅을 취소해 `handlePlayerText`로 전달하고, `!` 접두 채팅은 공개 채팅으로 유지하는 event Adapter.
- logout/server stop을 AI session lifecycle에 전달하는 event Adapter.
- Quest/Reward context를 안전한 빈 값으로 반환하고 Proposal을 기본 거절하는 Provider/Validator 설치.

## 4. MythicTRPG 데이터 구조 또는 공개 API를 수정해야 하는 항목

- `InteractionAudience`는 현재 `recipientPlayerIds` 집합만 가지며 모두 직접 수신자로 취급한다. 계약의 `ACTIVE`와 `LISTENER`를 구분하려면 audience에 역할별 상태를 추가하거나 별도 authoritative participant-state view가 필요하다.
- `OUTSIDE`는 현재 audience에 포함하지 않는 것으로만 표현된다. 세션 중 ACTIVE/LISTENER/OUTSIDE 전환을 게임 규칙으로 결정하는 공개 API가 없다.
- Dialogue HUD API는 단일 대상 전송만 제공한다. AI의 `audienceParticipantIds`를 검증한 뒤 각 대상에게 전달할 수는 있지만, speech envelope 전체를 한 번에 검증/전달하는 공개 Gateway는 아직 없다.
- affinity의 유효 범위와 `-4..+4` 관계 단계 매핑 정책이 명문화된 공개 API로 존재하지 않는다. 임의 threshold를 새로 정하지 않고 현재 원시 affinity만 제공한다.
- AI 엔진을 고유 모드 ID의 별도 JAR로 분리하고 안정적인 API artifact 또는 service interface를 제공해야 한다. 현재 결합형 JAR은 새 MythicTRPG와 클래스/모드 ID가 충돌한다.
- AI 엔진이 MythicTRPG HUD를 직접 구현 클래스에 결합하지 않도록 speech callback/public bridge 계약을 고정할 필요가 있다.

## 5. 아직 존재하지 않아 기본값/Proposal-only로 유지하는 항목

- trust, respect, caution의 authoritative 원본이 없다. 영구 저장하지 않으며 엔진에는 UNKNOWN/미제공 의미로 유지해야 한다. 엔진 DTO가 숫자를 강제하면 엔진 측 계약에 nullable/availability 표기를 추가해야 한다.
- 현재 감정의 authoritative 원본이 없다. UNKNOWN/neutral fallback은 읽기 전용 표현일 뿐 게임 상태로 저장하지 않는다.
- 플레이어-신 사회적 위계, 직위, 계약의 authoritative 원본이 없다. `UNKNOWN` 또는 빈 context를 제공한다.
- Quest 시스템, Reward 정책/allow-list, Quest/Reward Validator가 없다. context는 safe defaults이며 모든 관련 Proposal은 기본 거절한다.
- 가호, 관계 변경, 아이템 지급, 월드 상태 변경 Proposal을 실행할 기존 validator/API가 없다. 모두 Proposal-only/거절 상태다.
- 현재 production interaction rule과 자동 shared audience가 없다.
- Content Registry 프로필과 MythicTRPG God ID 전체 일치 여부는 분리 런타임 JAR/datapack이 제공되기 전 정적 빌드에서 완전 검증할 수 없다. 엔진은 프로필이 없는 God의 세션 시작을 실패시켜야 한다.
- 대화적 보류 상태는 AI 엔진 정식 DTO가 아니므로 이번 연동에서 추가하지 않는다.

## 6. 세션 격리를 위해 새로 필요한 식별자/데이터

- `sessionId`: AI 엔진이 매 대화마다 만드는 UUID. interaction ID와 동일시하지 않는다.
- `interactionId`: MythicTRPG가 승인/commit한 UUID를 AI 세션 참조로 그대로 전달한다.
- `turnId`, `generation`, `requestId`: AI worker의 턴 순서와 stale completion 방지를 위해 세션별로 유지한다. MythicTRPG 전역 상태로 저장하지 않는다.
- participant ID: 플레이어는 `player:<uuid>`, 신은 `divine:<ResourceLocation>` 규칙을 사용하되 변환 소유자는 AI 공개 계약으로 고정해야 한다.
- participant state: 각 session/player별 ACTIVE/LISTENER/OUTSIDE. 현재는 승인 audience의 온라인 플레이어를 ACTIVE로만 시작한다.
- Quest/Reward constraints와 validator feedback은 반드시 `(sessionId, targetParticipantId, proposal/requestId)` 범위로 보관해야 한다. 현재 기능이 없으므로 공유 mutable cache 없이 매 capture마다 빈 context를 반환한다.

## 7. 계약 변경과 호환 방식

MythicTRPG의 기존 저장 형식이나 interaction ID 형식은 변경하지 않았다.

현재 필요한 배포 계약 변경은 AI 엔진 쪽 패키징이다.

- 이전 형식: 게임 구현과 AI 구현이 `mythictrpg-1.0.0.jar` 하나에 함께 존재.
- 새 형식: AI 구현을 고유 모드 ID(권장 `mythai_ai_response`)의 별도 JAR로 분리하고 `GodAiDialogueService`, `AiProposalGateway`, Provider interface를 공개 API로 유지.
- 이유: 새 MythicTRPG JAR과 동시 로드할 때 mod ID와 Java 클래스 중복을 방지하기 위해서다.
- 호환: MythicTRPG Adapter는 클래스 존재 여부와 필요한 공개 메서드를 런타임에 검사한다. 엔진이 없거나 API가 맞지 않으면 기존 interaction commit/HUD를 유지하고 AI 세션만 시작하지 않는다. Proposal 실행 권한은 생기지 않는다.

## 8. 이번 구현 범위

- 기존 interaction 검증과 encounter commit이 성공한 뒤에만 AI session start hook을 호출한다.
- 현재 audience의 온라인 recipient는 ACTIVE 참가자로, plan의 primary/secondary God은 divine 참가자로 전달한다.
- 결합형 테스트 JAR은 의존성으로 추가하거나 복사하지 않는다.
- 엔진이 존재하면 safe Quest/Reward provider, 빈 social authority provider, 모든 Proposal 거절 validator를 설치한다.
- 채팅과 lifecycle을 세션 서비스에 연결한다.
- AI hook 실패는 이미 commit된 encounter/cooldown/HUD 결과를 rollback하거나 변경하지 않는다.

## 9. 현재 구현에 맞춘 최소 연동 계획과 결과

1. 기존 `InteractionStartService`의 final validation, runtime reserve, encounter commit, cooldown commit은 그대로 유지한다. **완료**
2. commit된 interaction ID와 기존 plan audience/participants만 post-commit Adapter에 전달한다. **완료**
3. 분리 AI 엔진 발견 시 safe Quest/Reward context, 빈 authority context, 기본 거절 Proposal validator를 먼저 설치한다. **완료**
4. 승인 audience의 온라인 플레이어와 기존 God `ResourceLocation`으로 AI session을 시작한다. **완료**
5. ACTIVE session의 채팅을 세션 큐로 보내고 logout/server stop lifecycle을 연결한다. **완료**
6. AI 엔진 speech는 기존 MythicTRPG Dialogue HUD API로만 전달한다. **분리 AI JAR이 기존 공개 API를 사용하도록 패키징 필요**
7. LISTENER/OUTSIDE와 Quest/Reward/관계 변경은 authoritative 게임 API가 생긴 타입부터 별도 검증 후 단계적으로 활성화한다. **현재 Proposal-only/기본 거절**
