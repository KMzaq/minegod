# NPC 일상 활동 → 기억·감정 연결

2026-10-02 착수·2026-10-03 재개 · `HanesTest` · 개발 게임 **1.0.26 / protocol 10**, AI **0.1.29**, 콘텐츠 **0.1.3**. 운영 미배포.

사용자 요청은 기존 NPC 일상 활동을 기억·감정에 연결하는 것이다. 새 전투/퀘스트/관계 원본을 만들거나 기억 엔진 전체를 교체하는 요청이 아니다. 이전 [활동 구현](NPC_ACTIVITIES_20260930.md), [방 감정](CURRENT_EMOTION.md), [퀘스트·가호](QUEST_CONTACT_BLESSINGS_20261002.md)는 유지한다.

## 연결한 경로

`실제 활동 시작·완료·실패·중단 / 실제 주변 발화 → 기존 활동 SavedData의 God별 경험 → 청중별 검색 → 자율 선택 또는 방 대화 → 근거·현재성 재검증`

- 게임 `NpcActivityRuntime`에서 실제 실행 지점에 안정적인 run/event ID를 발급한다. 기존 대련은 동의 후 실제 시작·종료 및 서버 stopping을 연결한다. 후보/초대/선택은 완료 경험이 아니다.
- `NpcActivityMemory`는 기존 `NpcActivityWorldState` 안의 제한된 신별 경험 보관함이다. 가짜 플레이어/대화방 ID로 플레이어 관찰 또는 방 청취 기록을 만들지 않는다. 새 원본 월드 사실 DB·affinity DB가 아니다.
- 신별 최대 64개, 전체 최대 2,048신. 조회는 최근 3개와 관련된 과거 최대 3개다. 단순 키워드/활동 종류 검색이며 새 embedding 호출은 없다. 수집된 모든 과거를 매번 프롬프트에 넣지 않는다.
- 일상 행동의 공개 기억은 종류/연출 여부/단계 등 축약된 사실만 제공한다. 상세 사적 지시·장소·책 본문·획득품은 공개 요약으로 복제하지 않는다. 실패는 부분적인 자원 변경 가능성을 배제하지 않는다.
- NPC 주변 발화는 실제 화자 기준 거리/시야, 실제 예약 동석 신과 성공한 플레이어 전달을 확인한다. 현재 전체 청중이 당시 허용 청중의 부분집합인 경우에만 원문을 공급한다. 발언 내용은 사실 확정이 아니다.
- 기존 자율 Ollama 호출의 선택적 `activityAffect`로 경험에 대한 질적 해석을 받는다. 새 감정 전용 호출·고정 감정표는 없다. 실행 권한과 별개이며, NPC가 다음에 무엇을 하기로 했다는 이유로 이미 성공한 감정을 저장하지 않는다.
- 감정은 이전 경험에 대한 비권위 해석이다. 새 경험과 현재 대화에 따라 달라질 수 있다. 모델이 선택한 출처뿐 아니라 전체 입력 경험과 이전 감정의 출처를 모두 추적하여 비공개 정보를 감정 문장으로 세탁하지 않는다.
- `ActivityRoomExperience`가 primary/secondary 화자 각각의 기억을 따로 조회한다. 별도 `[NPC_ACTIVITY_EXPERIENCE]`와 포터블 근거 참조를 기존 RoomEngine에 연결한다. 방의 `RoomEmotionState`는 독립된 기존 범위를 유지한다.
- 원본 경험이 남아 있고 projection/청중이 같으면 무관한 새 사건이나 재시작만으로 후속 방 회상을 차단하지 않는다. 원본 삭제/내용 변경/공개 범위 위반이면 근거와 후속 회상을 거절한다.
- 방용 경험 블록은 최대 2,500자로 제한하고 발화 발췌·생략 여부를 표시한다. 전체 대화 문맥 예산이 부족하면 이 선택적 블록만 생략하며 게임 권한·최신 발화는 자르지 않는다. 전체 출처 ID는 검증 계보에 유지한다.

## 호환·실행 조건

- `GodActivityPlanner.Request.experience` 및 `Decision.activityAffect` 추가. 기존 Java 생성자와 JSON affect 누락/null을 호환한다. 새 AI JAR은 게임 1.0.26 이상을 요구한다. 클라이언트 패킷은 변경하지 않아 protocol 10을 유지한다.
- SavedData v2. v1의 시설·금지·기존 짧은 운영 이력·재개 계획은 유지하지만 증거 없는 문자열을 장기 기억으로 승격하지 않는다. 알 수 없거나 손상된 형식은 원본 보존 후 fail-closed다.
- `MemoryFoundationSettings.OFF`는 새 지속 경험 쓰기/조회 금지, 기존 저장값은 보존한다. 기억 OFF가 실제 NPC 활동을 금지하지는 않는다. `TEST_EPHEMERAL` 방에는 경험을 공급하지 않는다. `recording-v2`의 새 아카이브 모드나 NEW 기억 사용을 켜지 않았다.
- 운영 `server/config/mythictrpg/ai-memory-foundation.json`은 확인 당시 `PERSONAL`이다. 수정하지 않았다. 자율 활동은 기존 아바타 정의 및 신별 `npc_activities` 허용 정책이 있어야 한다. 기존 신을 일괄 활성화하지 않았다.
- 운영 서버 실행/중지, 서버·클라이언트 JAR 교체, 월드/프로필/모델 변경은 하지 않는다.

## 검증

- 게임 `npcActivityMemoryTest`, `activityRoomExperienceTest`, `jar` 통과. 신별 격리·중복·청중·근거·지연 응답·OFF 보존·용량 한계 등 8개 시나리오 묶음과 방 경험 계약 23개 검사를 실행했다.
- 격리 게임의 활동 runtime / memory / body / work / schema GameTest **7/7** 통과. 실제 시작·중단·식사 완료, 지연된 감정 응답 거절, 행동 없는 감정 갱신, 주사위 일시중단/재개 중복 방지, SavedData v2/OFF 왕복·v1 호환·손상 원본 보존을 포함한다. 로그: `mythictrpg-main/build/npc-activity-memory-20261003/logs/latest.log`.
- AI `godActivityAiTest` **183**, `roomConversationEngineTest` **114**, `roomEmotionStateTest` **58**, `roomPersonaPromptTest` **164**, `roomReactionPromptTest` **40** — 합계 **559** 검사 통과. primary/secondary 분리, 근거 있는 선택적 감정, 프롬프트 예산 및 인용문 속 가짜 경계문자 처리를 포함한다.
- `verifyEngineOwnership` / `verifyEnginePackage` 통과. AI 489 classes, 게임 클래스 중복 0. 구 작업본/오버레이를 다시 빌드 입력에 넣지 않았다.
- 실제 세 모듈을 로드한 `mythai_room_full_path` GameTest **1/1** 통과(690.3ms). 게임이 발급한 요청 → 서로 다른 두 신의 실제 생성 프롬프트 → 발행/지속 청취 기록 → 다른 플레이어가 다른 신에게 회상 요청 → 후속 감정 연속성을 기존 **6회 모의 생성** 안에서 검사했다. 각 신의 READ/FARM·감정 분리, 사적 발언/상세 미노출, 원래 출처 ID 및 전체 근거 승인, 임의 턴 ID 거절도 포함한다. 로그: `mythictrpg-main/build/npc-activity-room-wire-verified-20261003/logs/latest.log`.
- 이 통합 검사의 앞선 실패 두 건은 보존했다. 첫째는 종료된 턴에 임의 UUID를 붙여 회상하던 fixture였고, 둘째는 게임 호출 대상용 bare ID가 검색 점수를 낮춘 문제였다. 현재 요청 권한/검색 임계값을 완화하지 않고 실제 발급 턴과 기존 지원 수신자 문법(`mythictrpg:fortuna에게 ...`)을 사용하도록 테스트를 수정했다. 로그는 각각 `build/npc-activity-room-wire-20261003`, `build/npc-activity-room-wire-final-20261003`에 있다.
- 위 검사는 모의 LLM 응답을 사용하며 Ollama 서버에 새 생성 요청을 보내지 않았다. 신규 격리 GameTest 디렉터리의 최초 `server.properties` 부재 로그는 초기 설정 생성 과정이며 운영 서버의 오류/부팅 기록이 아니다.

핵심 재실행 명령(루트에서 실행, 각 GameTest 디렉터리는 기존 결과와 구분한다):

```powershell
.\dev-tools\run-mythictrpg-gradle.cmd npcActivityMemoryTest activityRoomExperienceTest jar --offline --no-daemon --max-workers=1
.\dev-tools\run-mythictrpg-gradle.cmd -p ..\mythai-ai-response godActivityAiTest roomConversationEngineTest roomEmotionStateTest roomPersonaPromptTest roomReactionPromptTest verifyEngineOwnership verifyEnginePackage --offline --no-daemon --max-workers=1
.\dev-tools\run-mythictrpg-gradle.cmd runRoomIntegrationGameTestServer -ProomAiIntegration=true -ProomTestNamespaces=mythai_room_full_path -ProomTestDirectory=build/npc-activity-room-wire-rerun --offline --no-daemon --max-workers=1
```

생성된 개발 JAR SHA-256(서버/클라이언트에 복사하지 않음):

- `mythictrpg-main/build/libs/mythictrpg-1.0.26.jar`: `75DF8B84DB60A49D5D774E32FD6B04B1AFCC123CEEBC70DD9BA18BDC19EFF4AD`
- `mythai-ai-response/build/libs/mythai_ai_response-0.1.29.jar`: `53B163BED5EDD5E37D2D97967079E991C700F732E2EF2F4B0D115FDE905E4F1B`

## 남은 범위·과장 금지

- 실제 Ollama의 선택/감정/대화 품질, 사용자 클라이언트 화면 및 운영 부팅은 이번 코드 검사와 다르다.
- 기억 저장은 유한 보관이다. 무한 보관, recording-v2 전체 아카이브/벡터 검색 전환, 신들 간 자동 소문 전파까지 완성한 것이 아니다.
- 상세 책 본문·정확한 장소 등 사적 경험을 임의의 새 청중에게 공개하는 권한을 새로 만들지 않았다. 제공되지 않은 내용은 모델이 만들어내면 안 된다.
- 활동 감정은 자동 관계 수치 변경·세계관 정사·퀘스트 완료·보상 실행으로 사용하지 않는다.
- 기존 방 기억의 단순 어휘 검색에는 bare God ID가 주제어로 섞이면 일치율이 낮아지는 제약이 있다. `ID에게`처럼 지원되는 명시 수신자 문법은 제외된다. 이번 작업은 활동 기억·감정 연결이며 일반 회상 검색 고도화까지 수정/완료한 것은 아니다.

계약 상세는 [게임 활동 가이드](../../../mythictrpg-main/docs/NPC_ACTIVITY_SYSTEM.md)와 [AI 활동 IO](../../../mythai-ai-response/NPC_ACTIVITY_AI_GUIDE.md)를 따른다. 공용 인수인계의 운영 완료 상태로 승격하지 않는다.
