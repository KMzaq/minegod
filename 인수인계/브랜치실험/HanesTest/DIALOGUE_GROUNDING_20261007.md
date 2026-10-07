# 대화 의미·게임 근거 연결 개선 — 2026-10-07

범위: `HanesTest`, 게임 개발 JAR **1.0.27**, AI 개발 JAR **0.1.30**. 페르소나·스토리·콘텐츠 JSON을 수정하지 않았다. 운영 `server/`에 배포하거나 플레이어 인게임 시험을 한 기록이 아니다.

## 무엇을 바꿨는가

기존 분류 → 청중에 허용된 콘텐츠/기억 → 생성 → 게임 검증·실행 구조를 유지했다. 별도 신 DB나 퀘스트 실행 엔진을 추가하지 않았다.

1. **분류에 발화 의미 귀속 추가.** 상황/말투 태그 외에 누가 어떤 행동의 주체·대상인지, 질문/정정/농담/가정인지 짧은 해석을 함께 전달한다. 단순 인사 등의 기존 빠른 경로는 유지한다.
2. **생성의 대화 판단 보강.** 지금 말의 의미와 직전 교환을 우선한다. 정정에 반응하고, 거절된 활동을 계속 권하지 않으며, 잡담을 매번 업무·퀘스트로 돌리지 않게 한다. 무조건 정중함·친절함·사과·한 문장을 강제하지 않는다. 기존 순차 HUD 출력은 유지한다.
3. **게임 처리 결과를 구조화해 전달.** 기존 Gateway 결과를 `Request.actionOutcomes`로 전달한다. 제안/확인 대기/실패/성공을 구별하고, 접수·메뉴 열림을 도착·수주·완료라고 확대하지 않게 한다.
4. **일부 응답의 의미·근거 검토.** 아이템/퀘스트/정보 요청, 실행 결과, 과거 사실·장소·시간·안전 주장, 역할이 중요한 정정/농담 등의 신호가 있으면 같은 로컬 모델에 별도 검토를 요청한다. 초안을 아직 채팅에 공개하거나 실행하지 않는다.
5. **최대 1회 수정.** 구체적인 문제와 초안의 정확한 인용을 받아 동일 문맥에서 다시 생성한다. 구조 수정과 의미 수정은 같은 1회 예산을 공유한다. 수정도 검토가 필요하면 다시 확인한다. 실패·오래된 요청·잘못된 인용·재거절은 공개/Proposal 없이 실패한다. 모델 파싱의 기존 전송 재시도는 이 생성 수정 횟수와 별개다.

이는 Astra의 비공개 프롬프트나 내부 사고를 복제한 것이 아니다. 관찰 가능한 맥락 이해·역할 귀속·근거 대조를 로컬 모델에 적용한 설계다. 내부 추론 전문을 요구하거나 저장하지 않는다.

## 1단계 선택적 확장

```json
{
  "turnInterpretation": {
    "subject": "CURRENT_PLAYER",
    "target": "CURRENT_NPC",
    "mode": "CORRECTION",
    "meaning": "플레이어가 신을 돕겠다는 뜻으로 정정함",
    "evidence": "내가 널 돕겠다는 거야"
  }
}
```

- `subject/target`: `UNSPECIFIED`, `CURRENT_PLAYER`, `CURRENT_NPC`, `OTHER`. **meaning 안의 중심 행동/상태** 기준이다. 발화자나 질문자를 무조건 subject로 넣지 않는다. OTHER로 참가자를 만들지 않는다.
- `mode`: `UNSPECIFIED`, `STATEMENT`, `QUESTION`, `REQUEST`, `WISH`, `HYPOTHETICAL`, `QUOTED`, `CORRECTION`, `REFUSAL`, `BANTER`.
- `meaning/evidence`: 각각 최대 160자, 권장 60자 이내. evidence가 현재 입력의 연속된 실제 부분문자열이 아니면 이 해석만 버린다. 잘못된 enum/타입/과대 값도 기존 상황 태그를 폐기하지 않는다.
- 올바른 인용은 **해석의 진실을 증명하지 않는다**. 생성에서도 잠정 가설이며 NPC 감정·동의·게임 사실·실행 권한으로 승격하지 않는다.
- `TURN_INTERPRETATION_V1` 지침을 사용하는 분류 요청만 출력 예산 최소 384 tokens를 보장한다. 기존 서버 JSON의 96 설정을 덮어쓰지 않으며 구형 분류 요청의 예산도 바꾸지 않는다.

## Game → AI 결과 계약

`RoomConversationEngine.Request` 마지막 필드가 추가됐다. 이전 생성자들은 빈 결과 목록으로 호환된다.

```json
{
  "actionOutcomes": [{
    "proposalId": "00000000-0000-0000-0000-000000000001",
    "actionType": "mythictrpg:raid_offer",
    "status": "EXECUTED",
    "reason": "모집 접수",
    "details": {"status": "FORMING", "combat_started": "false"}
  }]
}
```

- 상태: `EXECUTED`, `PENDING_CONFIRMATION`, `CANCELLED`, `EXPIRED`, `REJECTED`, `FAILED`.
- 최대 16건. reason 최대 320자. details 최대 12개, 값 최대 160자. 게임이 허용한 상세 키만 투영하며 미성공 결과의 details는 빈 값이다. 모델 제목/설명은 결과 사실로 되돌려 넣지 않는다.
- 기존 휘발성 Gateway 결과를 조회한다. 새로운 영구 원장이나 현재 월드 상태 원본이 아니다. 오래된 지급 성공이 현재도 아이템을 보유함을 증명하지 않는다.
- 방·revision·신·플레이어 범위를 확인한다. Secondary/read-only 요청의 결과 목록은 빈 목록이다. 다른 신/방의 사적 실행 결과를 자동 전달하지 않는다.
- 생성용 scene에서는 `gameConfirmedActionOutcomes`로 들어가며 선택적 기억/로어를 예산 때문에 줄여도 이 근거는 자르지 않는다. 필수 문맥 전체가 너무 크면 명시적으로 실패한다.
- 확인이 생성 도중 완료되는 등 결과가 변하면 검토 뒤와 공개 직전에 재검증해 이전 응답을 버린다. 다른 방의 결과 변경은 현재 방을 오염시키지 않는다.
- Proposal 출력 양식·게임 네트워크·영속 저장 스키마는 이번 변경으로 바뀌지 않았다. 대사 공개 → 게임 Proposal 검증·실행 순서를 유지한다.

## 검토 계약과 비용

`LocalLlmClient.submitReview`가 추가됐다. Ollama 구현은 같은 scheduler/admission을 사용하고 temperature 0, 출력 최대 512 tokens, 요청 timeout 최대 60초다. 다른 backend가 이를 구현하지 않으면 검토가 필요한 턴은 실패한다.

검토에는 **이미 생성에 사용한 청중 허용 scene/history만** 재사용한다. 추가 로어·다른 방 기억을 검색하지 않는다. 캐릭터 연기용 system은 제외하고 독립된 검토자 system을 사용한다. 수정 지적은 세계 사실이 아닌 별도 데이터다.

```json
{
  "verdict": "REVISE",
  "issues": [{
    "code": "UNEXECUTED_ACTION",
    "excerpt": "초안 대사의 정확한 부분문자열",
    "correction": "아직 확인을 기다리는 결과를 완료로 표현하지 말 것"
  }]
}
```

PASS는 빈 issues. 최대 3개, 인용 최대 240자·수정 지침 최대 200자. code는 `UNSUPPORTED_FACT`, `WRONG_SOURCE`, `CONTEXT_CONTRADICTION`, `UNEXECUTED_ACTION`, `ROLE_CONFUSION`, `NARRATION`, `NONRESPONSIVE`다. 다른 턴의 문장이나 초안에 없는 인용은 거절한다. 비동기 응답은 기존 방/turn token·기억/콘텐츠 근거·실행 결과 검증도 모두 통과해야 한다.

기본은 기존 분류/생성 흐름이며, 신호가 있는 턴에 검토 호출 1회가 추가된다. 수정 시 생성 1회와 필요하면 검토 1회가 더 추가된다. 게임의 기존 전체 턴 timeout은 그대로다. 키워드는 검토 여부를 고르는 신호이지 금지어/고정 답변 표가 아니다.

## 검증과 한계

- 게임: 컴파일·JAR, 새 결과 계약 32검사, Gateway 71검사, 방 순서 103검사 통과.
- 개발 GameTest `mythictrpg_action_confirmation` **2/2**: 실제 공물 소비/가호, pending→최종 결과 갱신, 다른 방 격리·종료방 차단. 첫 잘못된 namespace 실행은 0건이었고 성공으로 세지 않았다.
- AI: 프롬프트 183, Secondary 프롬프트 43, 방 엔진 114, 발화 해석 104, 근거 검토 99, 감정 상태 58검사 통과. 최종 14 tasks 성공, JAR 501 classes·게임 클래스 중복 0. 검토 테스트는 의미 정확도가 아니라 형식/근거 보존/격리 계약을 검사한다.
- 실제 엔진 + 모의 LLM 개발 GameTest **1/1 안의 5개 시나리오** 통과: 수정 후 실제 채팅 전달, 재거절, 전송 실패, 잘못된 인용, 검토 중 실제 퇴장. 거절된 방 퇴장 Proposal 미실행도 확인했다. 실제 아이템 지급 선실행 검사의 대체는 아니다.
- 기존 2플레이어·2신 full-path GameTest **1/1**, 6응답·기억/감정/활동/사회 문맥 격리 회귀 통과.
- 실제 Gemma 테스트 자료는 [스포일러 없는 검토 결과](../../../검토결과/dialogue-system-20261007/RESULTS.md)에 있다. `live`는 첫 구현, `audit-v2/v3`는 **동일한 기존 초안** 검토 재시험, `live-final`은 최종 프롬프트를 사용한 새 생성이다. 9개 합성 상황, 같은 시험 페르소나이며 실제 스토리/인게임 결과가 아니다. `BaselineRoomPersonaPrompt20261007`은 작업 시작 시 프롬프트의 테스트 전용 동결본이다.
- 최종 live 9건 중 **8건 후보 반환, 1건 검토 재거절로 출력 차단**. 이것은 자연스러움 8/9 합격률이 아니다. 최종 경로 안내 사례는 근거 없는 위험 단정이 PASS하는 한계가 남았다. 서술/실행 오인 억제와 대화 품질을 구분한다.

중요: 첫 실모델 검토는 미실행 아이템 전달·근거 없는 보유/능력을 PASS해 실패했다. 이를 숨기지 않고 검토 역할 분리·조건문 속 능력/보유 전제 점검을 보강했다. 이 단계는 **모든 환각을 차단하는 증명이나 자연스러움 완성 판정이 아니다.** 검토 신호 밖 오류, 모델의 잘못된 PASS/REVISE, 우회적인 지식 암시, 어색한 말투는 남을 수 있다. 정확한 인용도 지적의 진실을 보장하지 않는다. 신의 불친절함/농담/거절 자체를 오류로 판정하지 않는다.

## 실행·재현

운영 서버와 분리된 build/검토결과 경로만 사용한다. `.\dev-tools\run-mythictrpg-gradle.cmd`는 게임 폴더로 이동하므로 AI `-p`는 절대경로를 사용한다.

```powershell
.\dev-tools\run-mythictrpg-gradle.cmd -p C:\Users\ADMIN\Desktop\markmar\mythai-ai-response roomPersonaPromptTest roomReactionPromptTest roomConversationEngineTest turnInterpretationTest roomDialogueGroundingTest verifyEnginePackage --console=plain
```

실 Ollama 시험은 `dialogueGroundingLiveEvaluation -PdialogueLive=true`로 명시적으로만 실행되며 `check`에는 포함하지 않았다. 모델은 시험 fixture에서 `gemma4:12b`·loopback으로 고정한다. 저장된 case 파일은 재실행 시 건너뛰므로 새 측정은 `-PdialogueEvaluationOutput=<새 폴더>`를 지정한다. `dialogueGroundingAuditReplay`는 `live`의 동일 초안을 다시 검토한다. 이 재현 도구는 실제 서버/권한/게임 행동 실행 시험을 대신하지 않는다.

운영 반영 시 게임·AI JAR과 클라이언트 게임 JAR의 호환을 맞춰 별도 배포해야 한다. 이 작업에서는 배포하지 않았다.
