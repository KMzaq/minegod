# Story 연결 추가 확인 — 전원 합의와 현실 48시간

2026-10-15 / `HanesTest` 현재 소스 확인. 기존 [Story 연결 지원 자료](story-game-connection-support-20261015.md)와 [원본 묶음 안내](story-json-reference-20261015/README.md)의 추가 확인 자료다. **요구사항과 현재 구현의 차이를 기록한 문서이며, 새 JSON 필드나 실행 기능을 구현한 결과가 아니다.**

## 1. 최종 결말의 사전 지정 참여자 전원 동의

### 현재 구현

- `StoryEventService.choose`는 사건 instance/revision·고정 청중·선택 가능 결과·기한을 검사한 뒤 유효한 선택 하나를 바로 확정한다. SERVER/TEAM 사건도 첫 유효 선택이 확정되는 방식이다.
- `EventInstance`에는 고정 청중과 선택 결과는 있지만, 참여자별 투표·동의안·전원 찬성 기록은 없다. 화면에서 모두에게 선택지를 보여 준다고 전원 동의가 되는 것은 아니다.
- 기존 사건 청중은 PLAYER의 해당 플레이어, TEAM의 시작 당시 FTB 팀원, SERVER의 시작 플레이어 또는 공집합으로 계산된다. SERVER 범위라고 서버 전체가 합의 대상이 되는 것은 아니며, 사전 지정 명단을 받는 별도 입력 경로가 필요하다.
- 기존 `QuestEnrollment`는 모든 청중의 응답을 받은 뒤 YES인 사람만 수주에 포함한다. NO가 있어도 YES 집합을 만들 수 있으므로 "전원 응답"과 "전원 찬성"을 구분해야 한다. 이 서비스는 퀘스트 수주용이며 최종 결말 합의에 연결되어 있지 않다.
- 일반 Story 조건만으로 정해진 참여자 UUID 집합의 동일안 동의를 자동으로 증명하는 경로는 확인되지 않았다.

### 추가 연결에 필요한 항목

1. **사전 지정 명단:** 합의 대상 플레이어 UUID 집합을 게임이 저장·고정한다. 현재 방 청중, 온라인 플레이어 전원, 현재 FTB 팀을 그대로 대체 명단으로 쓰지 않는다.
2. **합의안 식별:** 같은 사건 instance/revision·같은 결말/결정안과 정의 지문을 가리키는 각자의 명시적 응답을 기록한다. 다른 안이나 오래된 안에 대한 동의를 재사용하지 않는다.
3. **확정 조건:** 지정 명단 전원이 같은 안에 찬성한 뒤에만 결말 effect를 한 번 적용한다. 대표자의 클릭, 다수결, 무응답, LLM의 "모두 동의했다"는 대사로 대체하지 않는다.
4. **권위 실행 검사:** 최종 commit 직전에도 명단·동의안·각 동의·사건 조건을 재검증한다. 첫 선택이 바로 `resolve`로 들어가는 현행 경로에 이 합의 검증을 연결해야 한다.
5. **영속성과 변경:** 서버 재시작 후에도 합의 대상·안·응답을 보존하고, 중복 클릭과 늦은 응답을 차단한다. 안이 변경될 때 기존 동의를 유지/철회할 기준을 정한다.

현재 선택 timeout은 기본 outcome을 자동 적용할 수 있다. 해당 결말의 일반 선택/timeout 확정 경로가 전원 동의 확인을 우회하지 않도록 해야 한다. 무응답을 찬성으로 처리하는 기본 결말은 전원 명시 동의 요구를 충족하지 않는다.

집필·기획에서 아직 정할 사항:

- 반대자가 있을 때 논의 대기/새 안 제안/결말 취소 중 어떤 결과인가?
- 미응답·접속 종료자는 계속 필수 참여자인가? 참여자 변경을 누가 어떤 조건으로 허용하는가?
- 동의 철회·안 변경·합의 기한이 있는가? 있다면 자동 기본 결말은 전원 동의 규칙을 어떻게 만족하는가?

이 선택들은 이번 확인에서 임의로 확정하지 않았다. **"지정 참여자 전원 합의"를 원고의 필수 연결 요구로 유지**하고, 현재 `PLAYER_CHOICE`만으로 구현 완료라고 표시하지 않는다.

## 2. 디오니소스 퀘스트의 현실시간 48시간 제한

### 현재 구현

- Story `SCHEDULE_EVENT.delayTicks`, `choicePolicy.timeoutTicks`, LATCHED의 `expiresAfterTicks`는 월드 `getGameTime()` 기준이다. 예약도 `dueGameTime`으로 저장된다.
- 퀘스트 RANKING의 `durationTicks`도 게임 틱 기준이다. 이 값을 48시간×20틱으로 계산해 넣어도 현실 48시간과 같지 않다. 서버 정지·일시정지·TPS 지연에 따라 실제 경과 시간과 달라진다.
- 일반 `QuestAssignment.assignedAt`는 `Instant`이고 기존 저장소가 실제 시각을 보존한다. 이 값은 기산점 재사용 후보이지만 현재 48시간 deadline/만료 판정에 연결되어 있지 않다.
- 현재 Story/고정 퀘스트/참여 정책 JSON에 현실시간 deadline을 지정하는 지원 필드는 확인되지 않았다. `delayTicks` 등의 뜻을 현실시간으로 임의 변경하거나 미지원 필드를 원고의 실행 JSON에 넣지 않는다.
- 즉석 SIDE 퀘스트의 `expiresAfterTicks`도 틱 기준이고 현재 상한은 2,592,000틱이다. 48시간×20으로 계산한 3,456,000틱 자체도 이 예제 경로의 허용 범위를 넘는다.

### 추가 연결에 필요한 항목

1. **기산점과 마감 시각:** 게임이 실제 시작 시각과 절대 마감 시각을 저장한다. 현실 48시간은 172,800초지만 무엇을 시작으로 삼을지는 별도 기획 항목이다. 공동 run의 기한은 고정된 run 기준으로 보존해야 한다. 충원 참가자는 새 수주 시각을 받으므로 그 사람의 `assignedAt`으로 공동 마감을 다시 계산하면 기한이 잘못 연장될 수 있다.
2. **실제 시간 판정:** 서버의 실제 시계로 만료를 확인한다. 게임 틱은 확인을 호출하는 주기로 사용할 수 있으나 경과 시간의 원본으로 삼지 않는다.
3. **재시작·부재 처리:** 서버 시작과 플레이어 접속/퀘스트 조회·제출·완료 검사 때 영속 마감 시각을 재확인한다. 서버가 꺼져 있는 동안에는 효과를 실행할 수 없으므로 복구 시 만료를 한 번 처리하는 경로가 필요하다.
4. **완료와 만료의 충돌:** 목표 달성·제출·NPC 완료 승인·보상 정산 중 어디까지 기한 안에 해야 하는지 정하고, 실행 직전 다시 검사한다. 기한 내 목표를 달성했지만 신이 응답 불가능했던 경우도 같은 정책으로 처리한다.
5. **만료 결과와 재시도:** 실패/취소/대사/진행도/보상/재수주 정책을 명시하고, 같은 만료를 두 번 처리하지 않도록 기존 영속 상태·보상 경로와 연결한다.

집필·기획에서 아직 정할 사항:

- 기산점: 첫 제안, 수주 동의 완료, 특정 사건 시작 중 무엇인가?
- 서버 종료 시간·플레이어 로그아웃 시간도 경과 시간에 포함하는가?
- 기한의 대상: 목표 달성, 실제 제출, 신의 완료 확인 중 무엇인가?
- 개인별 마감인가, 지정 파티/서버 공용 마감인가?
- 마감 순간의 완료/만료 우선순위와 재수주 가능 여부는 무엇인가?

이번 확인에서는 이 정책을 임의로 정하지 않았다. **"현실시간 48시간"을 별도 만료 기능 연결 요구로 기록**하고 현재 Story 틱 예약이 이미 만족한다고 설명하지 않는다.

## 3. 코드 근거와 확인 범위

- [Story 첫 선택 즉시 확정](../mythictrpg-main/src/main/java/com/sande/mythictrpg/story/runtime/StoryEventService.java:76)
- [예약의 gameTime 처리](../mythictrpg-main/src/main/java/com/sande/mythictrpg/story/runtime/StoryEventService.java:515)
- [선택 timeout의 gameTime 검사](../mythictrpg-main/src/main/java/com/sande/mythictrpg/story/runtime/StoryEventService.java:534)
- [선택 UI 계약](../mythictrpg-main/docs/STORY_CHOICE_UI_GUIDE.md)
- [사건 상태·고정 청중과 예약 모델](../mythictrpg-main/src/main/java/com/sande/mythictrpg/story/state/StoryRuntimeModels.java:64)
- [퀘스트 수주 응답·YES 집합](../mythictrpg-main/src/main/java/com/sande/mythictrpg/quest/QuestEnrollment.java:54)
- [실제 수주 시각 모델](../mythictrpg-main/src/main/java/com/sande/mythictrpg/quest/QuestAssignment.java:9)
- [Story 지원 JSON 필드](../mythictrpg-main/src/main/java/com/sande/mythictrpg/story/definition/StoryDefinitionManager.java:258)
- [고정 퀘스트 지원 JSON 필드](../mythictrpg-main/src/main/java/com/sande/mythictrpg/quest/FtbQuestBindingManager.java:101)
- [참여형 제한시간 정책](../mythictrpg-main/src/main/java/com/sande/mythictrpg/quest/QuestRankingPolicy.java:11)
- [즉석 퀘스트의 틱 기한 상한](../mythictrpg-main/src/main/java/com/sande/mythictrpg/quest/dynamic/GeneratedQuestTemplate.java:55)
- [초기 수주·충원 수주 시각](../mythictrpg-main/src/main/java/com/sande/mythictrpg/quest/MythicQuestState.java:130)

소스·문서의 현재 경로를 읽어 확인했다. 이번 작업에서는 게임 코드·저장 형식·서버·LLM을 변경하거나 실행하지 않았다. 기존 전달 ZIP은 원본 스냅샷으로 보존하며 **이 추가 문서를 함께 전달**하면 된다.
