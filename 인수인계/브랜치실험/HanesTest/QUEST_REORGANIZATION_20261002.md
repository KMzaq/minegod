# 2026-10-02 — NPC 퀘스트 참가자 재편성

`HanesTest` 개발 검증·미배포. 사용자 요청: 처음 선택된 사람만 해당 신의 메인을 진행하는 의도는 유지하고, 고정 파티의 포기·장기 부재로 막힌 퀘스트를 NPC에게 요청해 재편성한다.

## 구현 범위

- 게임 **1.0.24**, AI **0.1.27**, 콘텐츠 **0.1.3**(이번 변경 없음), protocol **10** 유지. AI 최소 게임 의존성/개발 API 입력/세 모듈 통합 검사 입력을 함께 맞췄다. 기존 다른 작업의 변경은 되돌리지 않았다.
- 선택적 `participation.reorganization`: 본인 포기 허용, 부재 서버 가동 틱, 빈자리 충원 허용, 최소 인원, 제출품 반환 여부. GROUP 및 RANKING/ALL_SUBMITTED만 지원한다. 상세 스키마·예시는 [단일 작성 가이드](../../../mythictrpg-main/docs/QUEST_REORGANIZATION_GUIDE.md)에 둔다.
- 게임 `QuestReorganizationService`가 NPC 관리 메뉴와 대상별 확인 토큰을 발급한다. 기존 참가자가 준 NPC/지정 상대의 일반 대화방에서 요청하고, 충원은 실제 같은 방 참가자 중 수주 조건을 만족하는 후보 본인이 동의해야 한다. AI가 임의 동의·대상·아이템·반환량을 정하지 않는다.
- 최종 제출자는 제외/포기 불가. 남은 실적·완료/보상 자격은 유지한다. 미제출 이탈자는 완료/보상 대상에서 빼고 신규 참가자는 0부터 수행한다. 최소 인원/전원 제출을 다시 검사해 기존 정산을 실행하고, 전원 포기는 보상·진행도 없는 취소 종료다.
- MAIN/MAIN_ENTRY 충원에 기존 신 선택 자격을 검사한다. 탈퇴자의 기존 메인 권한은 지우지 않고 새 후보에게 자격을 주지 않는다. 높은 최소 인원과 제한된 메인 후보를 함께 설정하면 충원이 불가능할 수 있으므로 작성 가이드의 주의를 따른다.
- 정확히 소비한 ItemStack components와 수량을 `QuestRoster`에 기록하고, `QuestRosterRefunds`가 기존 `RewardClaimService`의 영속 claim으로 원래 제출자에게 반환한다. source별 중복 방지·반환 cursor·오프라인 수령을 사용하며 별도 보상 엔진은 없다. `[제출품 반환]` 및 `QUEST_SUBMISSION_REFUNDED`로 완료 보상과 구분한다.
- FTB는 개인 미러/마커/고정 표시만 갱신한다. 로그아웃/종료된 방/실적 변경/정의 reload/명단 revision 변경 후 예전 확인을 적용하지 않는다. 확인 대기와 적용 결과는 같은 방/신/플레이어의 다음 AI 턴에 전달한다.
- AI `quest_roster_request` 정규화·현재 화자 목록·메뉴 전용 intent 예외를 연결했다. 정보 질문에서도 메뉴만 열 수 있으며 다른 gameplay 행동 권한, read-only/Secondary 차단은 유지한다.

## 영속성과 적용 주의

`mythictrpg_quests` 저장 버전 **2**는 v1을 읽는다. 기존 roster 없는 실행은 고정 명단 그대로이며 기존 제출품 영수증을 추측해 만들지 않는다. 설정 생략도 기존 동작이다. 실행 중 정책 변경은 보류되므로 원래 정책을 복원해야 한다. 구 버전 롤백에는 업데이트 전 월드 백업이 필요하다.

아이템 기록/지급권/cursor의 정상 저장·복원 및 재시도 검증을 완료했지만, 강제 종료나 일부 파일만 복원한 상황에서 인벤토리와 여러 SavedData 간 완전 원자성을 추가한 것은 아니다. 운영 백업은 관련 월드를 함께 보존한다. 서버 종료 시간은 부재 기간에 세지 않는다.

활성 퀘스트 JSON과 기존 실행은 변경하지 않았다. 실제 사용하려면 대상의 새 실행용 바인딩에 opt-in 정책을 작성하고, 별도 배포/인게임 확인이 필요하다. SOLO·경쟁·시간제 랭킹·즉석 생성 개인형 퀘스트 재편성으로 범위를 넓히지 않았다.

## 검증

- 게임 `questParticipationTest`: 새 재편성 **41 assertions**, 기존 참여 **61 assertions** 통과. GROUP/전원제출 랭킹, 부재 경계·재접속, 제출 보호, 최소 인원·충원/재가입 차단, 취소, 저장 round-trip, 구형 호환·엄격 파싱 검사.
- 게임 `roomActionGatewayTest` **71**, `roomTurnSequenceTest` **103 assertions** 통과. 실제 Gateway 소스를 컴파일하는 fixture에도 새 액션 상수를 반영했다.
- AI `questRosterPromptTest` **24**, `roomPersonaPromptTest` **164**, `roomConversationEngineTest` **98**, `godActivityAiTest` **128 checks** 통과. 실제 Primary 프롬프트/intent 예외·정규화·방 간 목록 격리, read-only/Secondary/임의 동의/대상/반환 필드 차단을 검사했다. 최종 AI 묶음 12 tasks/19초.
- `verifyEngineOwnership`, `verifyEnginePackage`, 두 개발 JAR 빌드 성공. AI **489 classes**, 게임 클래스 중복 없음.
- 세 모듈 실제 로드 GameTest **10/10**, 테스트 실행 **873.6ms**, Gradle 10 tasks/14초 성공. 새 재편성 4개 + 기존 퀘스트 접근/미러 3개 + component 보상 3개. 실제 인벤토리 차감/동일 components 반환·오프라인 대기/재접속·중복 지급 방지·NBT 복원/훼손 보존·MAIN/MAIN_ENTRY 신규 자격 차단·실제 방/Gateway 메뉴·후보 본인 동의·원 제출자의 보상·재접속/종료 방/reload/실적 변경 후 확인 차단·전원 포기 취소를 포함한다.
- 최종 로그: `mythictrpg-main/build/quest-roster-integrated-20261002/logs/latest.log`. 게임/AI/콘텐츠 **1.0.24/0.1.27/0.1.3** 로드를 확인했다. 합성 테스트 플레이어/방이며 모델 호출·실제 HUD 조작은 아니다.
- 초기 검사의 namespace 미등록, offline tick fixture, mock ProfileCache 부재, 기존 component 보상 fixture의 NeoForge 채널 미등록, 반복 월드의 기존 신 선택 충돌을 보완한 뒤 재실행했다. component 테스트의 두 플레이어를 등록된 mock connection/종료 정리로 교체했으며 테스트의 보상 단언을 약화하지 않았다. 최종 로그의 receipt 누락/미지원 저장 버전 거절 오류는 의도적으로 훼손한 데이터 보호 테스트다.
- 변경 대상 `git diff --check` 통과. 새 가이드/인수인계 링크 확인. 전체 GameTest·전체 AI 테스트·실제 Ollama 대화·실클라이언트는 이번 범위에서 실행하지 않았다.

재실행 명령(프로젝트 루트 PowerShell):

```powershell
.\dev-tools\run-mythictrpg-gradle.cmd questParticipationTest roomActionGatewayTest roomTurnSequenceTest jar --offline --no-daemon --max-workers=1
.\dev-tools\run-mythictrpg-gradle.cmd -p ..\mythai-ai-response questRosterPromptTest roomPersonaPromptTest roomConversationEngineTest godActivityAiTest verifyEngineOwnership verifyEnginePackage jar --offline --no-daemon --max-workers=1
.\dev-tools\run-mythictrpg-gradle.cmd jar runRoomIntegrationGameTestServer -ProomAiIntegration=true '-ProomTestNamespaces=mythictrpg_quest_roster,mythictrpg_quest_access,mythictrpg_component_rewards' -ProomTestDirectory=build/quest-roster-integrated-20261002 --offline --no-daemon --max-workers=1
```

검증한 개발 JAR SHA-256:

| 파일 | SHA-256 |
|---|---|
| `mythictrpg-main/build/libs/mythictrpg-1.0.24.jar` | `B984DA55681BA90FC0A27BBCA279AC0F997A28E4E1DA52DEAA4109985E5CA602` |
| `mythai-ai-response/build/libs/mythai_ai_response-0.1.27.jar` | `4000858A58F4CC388AC44018932BE07931096637E42C489666A416A1DEECAF51` |

## 운영 상태와 남은 일

`server/mods` 파일은 게임 **1.0.16** / AI **0.1.19** / 콘텐츠 **0.1.2** 그대로다. 운영 서버 시작/중지·JAR 배포·클라이언트 파일 교체·월드/프로필 초기화는 하지 않았다. 개발 테스트 서버만 별도 build 월드에서 실행하고 자동 종료했다.

다음은 사용할 퀘스트의 부재 시간·최소 인원·반환 정책 확정/콘텐츠 설정, 요청된 별도 배포 및 실제 다인 NPC 대화·클릭/자연어 동의·LLM 후속 대사 확인이다. 기존 실행을 바꾸려면 현재 데이터/정책/제출품 내역을 확인한 별도 이관 설계가 필요하다. 이 구현 완료를 공용 브랜치 반영이나 운영 완료로 승격하지 않는다.
