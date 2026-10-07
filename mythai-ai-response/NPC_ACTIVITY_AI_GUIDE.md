# NPC 생활활동 AI 연동

이 문서는 생활활동의 **AI 판단·대화 부분**만 설명한다. 실제 활동 정의, 장소, 이동, 아이템 소비/생산, 공물·가호, 참여자 및 접근 권한의 원본은 MythicTRPG다. 소스 구현·검증·운영 배포는 별개다.

## 호출 경계

- 자율 활동: 게임 `GodActivityPlanner.Request` → `OllamaActivityProvider` → 현재 설정의 로컬 Ollama → `Decision` → 게임 재검증·실행.
- 플레이어 대화: 기존 room 생성 요청의 `GodState.gameContext` 안에 있는 `[NPC_ACTIVITY_CONTEXT]`와 별도의 `[NPC_ACTIVITY_EXPERIENCE]`를 사용한다. 후자는 게임이 현재 신·청중에 허용한 과거 경험이며 새 액션 권한을 부여하지 않는다. 생활활동을 위해 별도 분류/생성 호출을 추가하지 않는다.
- NPC끼리의 말: `SOCIAL` 후보를 선택했을 때만 게임이 실제 동석한다고 제공한 신들을 함께 화자로 선택할 수 있다. 다른 활동/제어 응답은 요청 주체인 신만 발화한다. 가짜 플레이어나 대화방을 만들지 않는다. 실제 청중과 발행/표시는 게임이 결정한다.

`NONE`은 선택하지 않음, `CONTINUE`는 현상 유지, `STOP`은 중단 **요청**이다. 자율 선택은 실제 제공된 UUID 후보도 사용할 수 있다. 이 응답 자체는 이동·시작·중단·완료 증명이 아니다.

채팅의 활동 제안은 다음 모양이다. 현재 게임 capability가 제공되고 primary/live 요청일 때만 정규화한다.

```json
{
  "type": "npc_activity_request",
  "title": "",
  "summary": "",
  "targetParticipantIds": [],
  "parameters": { "choice_id": "STOP" }
}
```

`choice_id`는 `STOP`, `CONTINUE` 또는 해당 요청의 `available_choices[].choiceId`에서 그대로 복사한 UUID다. snapshot 후보 필드는 `choiceId`, 제안 parameters의 키는 `choice_id`이다. `NONE`을 제안하는 대신 `proposals: []`를 사용한다. 좌표, 대상 신, 아이템 ID, 강제 실행 옵션 등은 받지 않는다. AI 정규화는 형식을 확인할 뿐이며, UUID의 소유 신·방·revision·만료·금지 정책 검증은 게임이 수행한다.

정보 질문/회상 분류가 일반 gameplay 제안을 제한해도, 유효한 현재 `[NPC_ACTIVITY_CONTEXT]`가 있고 primary/live인 경우 `npcActivityProposalsAllowed`만 별도로 제공한다. 실제 출력 필터도 STOP/CONTINUE 또는 그 snapshot의 정확한 UUID인 `npc_activity_request`만 예외 처리한다. 기존 퀘스트·보상·타격·아이템·관계 변경의 허용 범위는 넓히지 않는다. readOnly/secondary, 보이지 않음/등록 활동 없음, 잘못되거나 중복된 snapshot은 이 예외를 얻지 못한다. 후보 목록이 비어도 현재 활동 중단은 가능하며 실제 실행 허용 여부는 끝까지 게임이 검증한다.

## 페르소나와 사실의 구분

- 실제 신/동석 신의 정적 프로필과 방향별 작성 태그를 읽는다. 로어 본문, 다른 방의 기억·감정, 플레이어 관계 원본을 자율 요청에 덧붙이지 않는다.
- 현재 자율 Request에는 플레이어별 관계/힘/room 감정 snapshot이 없다. 따라서 이들은 `UNASSESSED`이며 임의로 친밀·적대·중립·우위를 추측하지 않는다. 일반 채팅은 기존 게임 관계·힘 문맥과 해당 방 감정을 그대로 사용한다.
- 자율 Request의 `experience`는 해당 God ID의 게임 필터를 통과한 실제 활동 경험과 그 근거를 인용한 과거 감정 해석이다. 이전 해석은 현재 확정 감정이나 플레이어에 대한 태도가 아니다. 현재 상황과 성격에 맞춰 재평가하며, 신마다 따로 유지한다. 비밀방의 `[NPC_SESSION_EMOTION]`을 가져오거나 여기에 다시 저장하지 않는다.
- 정적 신 관계 태그는 현재 게임의 동적 관계 수치를 대체하지 않는다. 프로필은 역할 지침이지 동석 신들이 공유하는 세계 지식이 아니다.
- 최근 경험은 반복 선택을 줄이거나 계속할지 판단하는 자료다. 반복했다는 이유만으로 강제로 짜증 내거나 다른 행동을 선택하지 않는다. `recentActivities`는 구 API 생성자 호환용으로 남지만 실제 게임 경로는 권한 정보 없는 옛 문자열 이력 대신 `experience`를 전달한다.
- “그거 쓰지 마” 같은 일반 대사는 부탁이다. 성격/관계/상황에 따라 따르거나 거절할 수 있다. 관리자가 설정한 금지나 게임이 제외한 후보는 말로 우회할 수 없다.
- 활동 소품·애니메이션은 실제 아이템 보유/소비 증거가 아니다. 책 본문이 제공되지 않으면 책 내용을 인용하거나 정사를 만들어내지 않도록 지시한다.
- 제안·진행 중·중단·완료를 구분한다. 완료를 서술하려면 게임 확인 결과가 필요하다. 생성 대사의 의미적 무오류를 코드가 보장하는 것은 아니다.

## 활동 경험과 감정 해석

같은 자율 선택 호출의 응답에 다음 선택적 필드를 추가할 수 있다. 아래 UUID는 형식 예시이며 실제로는 **그 요청의 `experience.experiences[].eventId`**를 그대로 인용해야 한다.

```json
{
  "requestId": "게임이 발급한 현재 요청 UUID",
  "choiceId": "NONE",
  "speech": [],
  "activityAffect": {
    "hint": "조용히 쉬고 난 뒤의 차분한 호기심",
    "sourceEventIds": ["11111111-1111-1111-1111-111111111111"]
  }
}
```

- `activityAffect`는 생략 또는 `null` 가능하다. 근거가 없거나 감정을 판정할 수 없으면 `null`이다. 실제 경험이 없는 입력의 출력 스키마는 `null`만 허용한다.
- 객체를 반환하면 `hint`와 `sourceEventIds`만 허용한다. `hint`는 비어 있지 않은 최대 120자의 한 줄 질적 해석이며 점수·다른 신의 내면·플레이어 태도·숨은 원인·새 세계 사실을 만들지 않는다. 이 해석은 게임 사실이나 관계 변경 Proposal이 아니다.
- 출처는 현재 공급된 과거 경험 UUID 중 중복 없는 1~4개다. 후보 `choiceId`, 요청 `requestId`, 다른 신/청중의 경험, 삭제·필터된 경험을 근거로 쓸 수 없다. 출력 형식·출처 일치는 코드로 검사하지만 자연어 의미의 완전한 무오류를 보장하지는 않는다.
- `NONE`과 침묵도 과거 경험 해석을 반환할 수 있다. 감정 때문에 별도 모델 호출을 추가하지 않는다. 새 후보를 선택했다고 그 활동이 성공하거나 감정이 생겼다고 확정하지 않는다.
- 게임이 현재 실체·요청·활동/경험 revision·출처·청중을 다시 확인한 뒤 적용한다. 새 활동이 시작되어도 허용된 이전 근거의 해석은 이어질 수 있지만 이후 경험이 반박하면 재평가 대상이다. 유효 근거가 없는 해석은 공용 상태로 승격하지 않는다.
- 방 대화의 경험 표시는 원본 DTO 전체를 반복하지 않는 최대 2,500자 축약 블록이다. 긴 발화는 `excerpt: true`, 항목 생략은 `omittedMemoryCount`로 구분한다. 전체 12,000자 문맥 예산이 부족하면 선택적 경험 블록만 생략하고 권한·최신 대화를 보존한다. 출처 ID와 해시는 모델 입력에서 줄여도 기존 publication evidence에 유지한다.

## 제한과 비동기 처리

- 설정된 Ollama 모델/숫자 loopback HTTP `/api/chat`만 사용한다. 모델을 바꾸거나 실제 LLM 테스트를 자동 실행하지 않는다.
- `stream=false`, `think=false`; HTTP 20초, 입력 scene 32,000문자, 출력 JSON 8,192문자. 자율 대사는 0~4줄, 각 300문자까지다.
- 후보 최대 32개, 실제 동석 신 최대 3명, 최근 활동 최대 8개인 게임 계약을 사용한다. 후보 UUID 중복, 미제공 화자/후보, 잘린 완료, 다른 requestId, 중복 JSON 필드, 여분 제어 필드를 거절한다.
- 전용 worker 1개/대기 1개와 기존 `ModelAdmission.followup()`을 사용한다. 일반 대화가 도착하면 자율 모델 호출을 중단하고 양보한다. 실패/혼잡은 활동 결정으로 위조하지 않고 실패 future로 반환한다.
- 응답 적용 직전 현재 서버, 프로필 generation/내용, 모델 설정을 다시 확인한다. 게임은 실제 actor·활동 revision·후보·청중을 재확인해야 한다. 종료 시 실행/대기 요청을 취소한다.

## 구현 위치와 검증

- `src/main/java/com/sande/mythai/response/memory/OllamaActivitySelection.java`: local wire, 입력/출력 schema, 엄격 parser.
- `src/main/java/com/sande/mythictrpg/ai/OllamaActivityProvider.java`: 게임 스레드 프로필 snapshot, 선택 worker, 완료 전 현재성 검사.
- `NpcActivityPrompt`, `AiActionCapabilityBridge`, `RoomPersonaPrompt`, `RoomReactionPrompt`: 실제 room context와 활동 제안 경계.
- `src/test/java/com/sande/mythictrpg/ai/GodActivityAiTest.java`: 가짜 transport, 잘못된 입력/출력, NPC-only, 실제 prompt 격리, secondary 권한 보존, foreground 우선권 검증.

2026-10-02~03 개발 후속: `GodActivityAiTest`에 과거 경험 입력·감정 선택 응답의 strict parser/schema·다른 신/누락 출처/중복/잘못된 타입/길이·단일 호출 회귀를 추가했다. 문맥 예산과 실제 두 신의 대화 경로 검증도 포함하며 최신 결과는 [활동 기억 연결 기록](../인수인계/브랜치실험/HanesTest/NPC_ACTIVITY_MEMORY_20261002.md)을 따른다.

이전 2026-09-30 기록: AI 0.1.26/게임 1.0.23 컴파일·JAR 성공. 활동 128 + primary 164 + secondary 40 + room engine 98 = 오프라인 430 checks 통과, 488 AI classes에 게임 클래스 중복 없음. 당시 세 모듈 격리 로드/스키마 검사도 통과했다. 실제 Ollama 응답 품질, 클라이언트 연출, 운영 서버 배포·인게임 확인은 별도 확인 대상이다. [이전 마감 기록](../인수인계/브랜치실험/HanesTest/NPC_ACTIVITIES_20260930.md)을 따른다.
