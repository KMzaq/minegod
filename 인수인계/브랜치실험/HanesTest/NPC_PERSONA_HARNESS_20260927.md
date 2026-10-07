# 페르소나 중심 NPC 대화 하네스 개선

**`HanesTest` 브랜치 전용 실험 기록.** 공용 인수인계나 운영 배포 완료 기록이 아니다. 이 실험의 후속 기록은 같은 폴더의 [브랜치 인수인계](PROJECT_HANDOFF.md)에 남긴다. 아래 명령은 프로젝트 루트에서 실행하는 기준이다.

대상: AI 응답 **0.1.18** 개발본. 게임 **1.0.16**, 콘텐츠 레지스트리 **0.1.1** 계약 유지. 운영 서버 배포와 실제 모델 품질 검증은 별도다.

## 목표와 변경 범위

NPC가 미리 정해진 반응을 출력하는 대신, 자신의 페르소나와 실제 대화·관계·기억·게임 상황을 해석하여 말하도록 한다. 이는 프롬프트가 게임 상태의 최종 권한을 갖는다는 뜻이 아니다.

변경은 현재 대화방의 **Primary 응답 생성·출력 검증**에 집중한다. 새 하네스 프레임워크, 게임 상태 저장소, 관계 점수 계산기, 퀘스트 실행기는 만들지 않았다. 기본 1단계 분류/빠른 경로와 기존 행동 제안 허용 게이트도 유지한다. Secondary의 실제 전달된 발화 기반 선택적 반응/침묵은 그대로다.

| 구분 | 변경 |
| --- | --- |
| NPC의 기본 역할 | 잡담에도 정체성·성격·가치관·말투·제약과 작성된 조건부 지침을 유지 |
| 사전 해석 | 분류의 출처·신뢰도·태그를 잠정 검색 힌트로 전달. 감정·무례함·의도를 확정하는 명령으로 쓰지 않음 |
| 현재 대화 | 실제 발화자와 시간 순서가 있는 최근 대화, 현재 발화를 별도 JSON 데이터로 전달 |
| 기억과 게임 사실 | 과거 발언·계획·소문·퀘스트 후보·게임이 확인한 실행 결과를 구분. 실제 성공은 인정할 수 있지만 제안을 성공으로 말하면 안 됨 |
| 놀이 상태 | 과거 발화에서 얻은 활동명은 잠정 힌트. 수락·거절·종료·화제 전환은 실제 대화로 판단 |
| 응답 후처리 | 인사를 무조건 `안녕.`으로 바꾸거나 반복 인사를 고정 문장으로 치환하지 않음 |
| 재생성 | 특정 사회적 표현/단어 때문에 재생성하지 않음. 화자·청중·개수·길이 등 구조 위반에만 한 번 교정 요청 |
| 문장 길이 | Primary 1~4개 speech, 각 4,000자/전체 8,000자 안전 상한. 의미 있는 여러 문장 보존, 기존 게임 HUD 순차 출력 사용 |

LLM 출력 토큰 한도는 변경하지 않았다. 현재 운영 설정은 `gemma4:12b`, `maxOutputTokens=260`이다. 안전 상한이 길어졌다고 그 길이의 답변 생성을 보장하지는 않는다. 현재 Primary는 과거 `maxResponseCharacters`로 문자열을 잘라 전달하지 않으며, 예산 초과/잘못된 형식은 명시적으로 거절한다. Secondary와 다른 레거시 경로의 기존 정책은 그대로다.

## 소유권과 유지된 경계

`게임 Snapshot/허용 청중 → 콘텐츠·기억의 권한 투영 → 참고 분류 → 페르소나 기반 생성 → 기존 Proposal 검증 → 게임의 실제 전달/실행`

- 신·플레이어 ID, 참가자, 관계 수치, 게임 사건, 행동의 성공/실패, 정체 공개와 퀘스트/보상은 계속 MythicTRPG가 소유한다.
- AI의 발언은 새로운 월드 사실이나 실행 완료 증거가 아니다. 단순히 과거에 NPC가 말했다는 이유로 월드 사실로 승격하지 않는다.
- `RoomKnowledgeContext`, `pruneHistory`, 실제 청취 기억과 근거 계보, 콘텐츠 generation, room/revision/turn token, 비동기 응답 재검증을 유지한다.
- 게임 액션의 `readOnly`, 기존 gameplay 허용 여부, capability/후보 quest ID 검증, Story alias 검증을 우회하지 않는다. 허용된 방 제어는 일반 gameplay와 독립이다.
- 메모리의 화자·질문자·방이 현재 요청과 일치하는지 분류 전부터 검사한다. 명시적 기대 memory context가 있다면 generation·청중 등 전체 context도 일치해야 한다.
- 서로 다른 방의 history, 관계, 감정, Quest/Reward 제약과 validator feedback을 합치지 않는다.
- 입력은 출처별 JSON 데이터다. 플레이어가 `[ROOM_SCOPE]`나 가짜 역할 구문을 입력해도 프롬프트의 실제 구조를 바꾸지 않는다. 이것만으로 LLM의 의미적 프롬프트 주입 내성을 완전히 보장하는 것은 아니며, 실행 권한은 계속 코드가 검증한다.
- 데이터 context의 기존 **12,000자** 한도를 유지한다. 오래된 history부터 제외하되 마지막 history·현재 발화·권한·필수 페르소나는 잘라내지 않는다. 필수 부분만으로 넘치면 `PROMPT_REJECTED`로 실패한다.

## 주요 파일

- [RoomPersonaPrompt.java](../../../mythai-ai-response/src/main/java/com/sande/mythictrpg/ai/RoomPersonaPrompt.java): 새 생성 문맥/구조 검증.
- [room-conversation-overlay.gradle](../../../mythai-ai-response/room-conversation-overlay.gradle): 생성 소스에 실제 연결. `build/generated`를 직접 수정하지 않는다.
- [MythAiRoomConversationEngine.java](../../../mythai-ai-response/src/main/java/com/sande/mythictrpg/ai/MythAiRoomConversationEngine.java): 구조 교정과 `GENERATION_POLICY` 진단 기록. 기존 recording ON/OFF 정책 유지.
- [RoomPersonaPromptTest.java](../../../mythai-ai-response/src/test/java/com/sande/mythictrpg/ai/RoomPersonaPromptTest.java), [RoomConversationEngineTest.java](../../../mythai-ai-response/src/test/java/com/sande/mythictrpg/ai/RoomConversationEngineTest.java): helper와 실제 overlay 경로의 회귀 검사.

게임/AI 모듈 간 Request/Result/Proposal JSON 스키마는 변경하지 않았다. 임의 다른 화자나 임의 수신자 응답은 새 Primary에서 묵살 후 일부 채택하지 않고 구조 교정 또는 실패로 처리한다. 정확한 화자와 `audienceParticipantIds: []`를 사용한다. 기존 `["player"]`도 호환되지만 실제 수신 대상은 게임이 결정한다.

## 실제 모델 비교 도구

개발용 **고정 문맥 생성 비교**다. 운영 플레이어 대화나 기억을 읽고 덮어쓰지 않는다. 합성 프로필과 여러 턴의 합성 과거 대화를 사용하여 기존/새 생산 프롬프트 builder에 같은 분류 결과를 넣는다. 프로필 예시는 정식 포르투나/데메테르 정사나 운영 프로필 변경이 아니다.

10가지 사례: 인사, 다른 표현으로 반복되는 요구, 친밀한 반말, 낯선 사이의 반말, 상처 이후 사과, 거절된 놀이/화제 전환, 보상 거절, 확정된 보상 성공, 힘의 역전, 기억 속 계획 정정.

1. 프로젝트 루트에서 합성 입력을 생성한다. LLM을 호출하지 않는다. Wrapper가 게임 폴더로 이동하므로 `-p`에는 **절대 경로**를 사용한다.

```powershell
.\dev-tools\run-mythictrpg-gradle.cmd -p C:\Users\ADMIN\Desktop\markmar\mythai-ai-response exportNpcHarnessComparison --offline --no-daemon
```

출력: `mythai-ai-response/build/npc-harness-comparison/cases.json`. 현재 서버 설정의 출력 토큰 값을 읽기만 하며, 생산 `LocalOllamaClient`의 구조화 JSON schema를 사용한다.

2. 실행 계획만 확인한다. 네트워크 호출/결과 파일 생성 없음.

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\dev-tools\Compare-NpcHarness.ps1 -MaxCases 10
```

3. 모델 사용을 원할 때에만 `-Execute`를 붙인다. 예를 들어 두 번 생성하는 작은 한 사례 비교:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\dev-tools\Compare-NpcHarness.ps1 -CaseId repeated_need -Execute
```

기본은 6사례 × 2변형 × 1회이며 최대 사례 수/반복을 제한한다. 현재 서버 설정의 Ollama 모델을 사용하고 루프백 `/api/chat`만 허용한다. 같은 쌍은 동일 seed·토큰 옵션을 사용한다. 모델 설치·서버 기동·게임 액션 실행·운영 기억 저장은 하지 않지만 GPU/메모리를 사용한다.

결과는 `build/npc-harness-comparison/runs/<새 시각-ID>/`의 요청, 원응답, 지연/토큰/종료 사유, 파싱 결과, `REVIEW.md` 수동 평가표에 저장한다. 품질 점수를 자동으로 만들지 않는다. 구조화 JSON 성공은 자연스러움이나 게임 실행 검증 성공이 아니다.

**비교 한계:** 1단계 분류를 실제로 다시 실행하는 종단 비교가 아니다. 각 사례의 과거 대화는 고정되어 있고 모델 응답을 다음 턴에 다시 넣는 자율 rollout이 아니다. 기존 인사 후처리도 raw 모델 비교에는 적용하지 않는다. 실제 게임에서의 차이는 별도 확인해야 한다.

## 남는 한계

- 모델이 가진 한국어·역할 연기 능력과 생성 랜덤성은 그대로다. 새 프롬프트만으로 모든 대화가 자연스러워졌다고 확정하지 않는다.
- 관계 수치→9단계 매핑이나 감정 원본을 새로 만들지 않았다. 현재 게임 caller의 일부 값은 `R_NEUTRAL/E_NEUTRAL` 기본값이며 context에 실제 affinity와 `numerical tier mapping UNDEFINED`가 들어온다. 합성 `R_CLOSE/E_ANGRY` 검사는 실서버의 모든 관계/감정 입력이 완성됐다는 증거가 아니다. undefined 표시는 중립/무관심의 확정 근거로 쓰지 않도록 안내한다.
- 1단계의 부정확한 검색 키워드, 누락된 과거 대화/게임 결과, 잘못 작성된 프로필까지 자동 복원하지 않는다.
- 임의의 모든 상황에 게임 액션을 새로 허용하지 않는다. 제안 생성 범위는 기존 gate와 허용 capability에 따른다.
- 실제 게임 모델 품질, 응답시간과 클라이언트 HUD 수용 테스트는 별도다.

## 검증·배포 기록

- AI `build exportNpcHarnessComparison --offline --no-daemon` 성공. 기존 회귀를 포함한 오프라인 **5,139 checks** 통과. 새 helper137, 실제 RoomPrompt/룸 검사96 포함. 이는 자연스러운 대화 사례5,139개를 실제 모델로 시험했다는 뜻이 아니다.
- 비교 도구의 PowerShell5.1 오프라인 **33 checks** 통과. 첫 실행에서 IPv6 루프백의 확장 표기를 거절하던 문제를 IPAddress 값 비교로 수정한 뒤 재검증했다. 실제 생성 manifest10사례의 dry-run도 성공, HTTP 호출0.
- 세 모듈 동시 로드 전용 **GameTest 1/1** 통과. 실제 콘텐츠 프로필 조회·청중 필터·Primary/Secondary 전달·신별 청취 기억을 검사했다. **LLM transport는 모의**하며 실제 Ollama를 호출하지 않는다. 테스트 런타임은 `mythictrpg-main/build/persona-harness-gametest-20260927`, 근거는 그 아래 `logs/latest.log`다. 새 디렉터리의 최초 `server.properties` 미존재 로그 후 테스트가 정상 진행·종료됐다.
- 개발 GameTest가 다른 AI 버전을 명시적으로 로드할 수 있도록 게임 `build.gradle`에 `-ProomAiResponseJar=<절대 JAR 경로>` 선택지만 추가했다. 게임 실행 Java나 모듈 IO는 수정하지 않았다. 기존 기본값은0.1.17이므로 새 후보 검사는 이 옵션을 명시한다.
- JAR: `mythai-ai-response/build/libs/mythai_ai_response-0.1.18.jar`.
- SHA-256: `FE6F447D90487D2C98EE826E6BB53C2C0AAAA75FC2B4BB9CC69872AA260C0EE2`.
- **미배포**: `server/mods`는 게임1.0.16/AI0.1.17/콘텐츠0.1.1을 유지한다. 운영 서버 시작·중지·파일 교체, 월드/운영 기억/설정 변경, 클라이언트 파일 변경은 하지 않았다. 실제 LLM 비교는 실행 여부 확인을 요청했으나 답변이 없어 실행하지 않았다.
- 개발 Gradle 검사가 AI 프로젝트의 기존 추적된 `logs/debug-1.log.gz`~`debug-5.log.gz`를 회전시켰다. 개발 진단 로그이며 운영 플레이어 로그가 아니다. 소스 변경과 분리해서 취급한다.

전용 통합 검사 재실행(운영 서버가 아닌 별도 개발 런타임):

```powershell
.\dev-tools\run-mythictrpg-gradle.cmd runRoomIntegrationGameTestServer -ProomAiIntegration=true -ProomAiResponseJar=C:/Users/ADMIN/Desktop/markmar/mythai-ai-response/build/libs/mythai_ai_response-0.1.18.jar -ProomTestNamespaces=mythai_room_full_path -ProomTestDirectory=build/persona-harness-gametest-20260927 --offline --no-daemon
```
