# 선택적 AI 사건 연출 보조 시스템

## 현재 범위 — 2026-09-23

게임 1.0.16 / AI 응답 0.1.17 개발 소스 기준이다. 소스 구현·컴파일·테스트·서버 배포·실제 모델 품질은
구분한다. 이번 검증 결과와 배포 여부는 [원래 목표 재연결 기록](../../docs/FULL_ROOM_DIALOGUE_INTEGRATION_20260923.md)을 따른다.

| 경로 | 입력 → 출력 | 권한 |
|---|---|---|
| 사건 연출 | 확정 PresentationOpportunity → AI/작성 대사 → 지정 플레이어 또는 명시적으로 허용된 방 하나 | 이미 확정한 결과의 표현 |
| 일반 대화방 | 전체 청중에게 허용된 사실·위장 이야기 → 성격에 맞는 대화, 정식 사실 공개 선택, 허용 Hook 제안 | 사실·사건 결정은 게임, Hook은 별도 확인 |

장시간 신들끼리 자율 대화를 돌려 사건을 결정하는 시스템이 아니다. 플레이어 행동과 게임 조건이
사건을 확정하고, 필요할 때만 선택적 AI 연출을 사용한다.

## 책임과 모델 입력

`StoryEventService`가 사실·행위자 상태·사건 결과·관계 효과를 소유한다. 모든 외부 관계 효과가
끝나 `RESOLVED`가 되어야 해당 Presentation을 발행한다. AI 미설치·실패 시 작성 fallback을 사용한다.

새 `StoryOllamaPresentationProvider`는 기존 모델 설정과 비동기 `LocalOllamaClient`/요청 스케줄러를
사용한다. 과거 실험 소스를 통째로 복원한 것이 아니다. 모델에는 다음만 제공한다.

- 허용된 자연어 문구 최대 8개와 비밀이 아닌 연기 지시
- 현재 청중에게 공개 가능한 신 프로필의 성격·가치·말투·대화 지침
- 방 화자에서 다른 참가 신을 향하는 현재 동적 관계 태도(원인·과거 비밀 제외)
- 청중 공개 검사를 통과한 정적 관계 태그(화자→각 상대의 방향을 유지)
- 허용된 `hook_1` 별칭 최대 3개, 실제 발화 God ID

Fact/Event/Hook ID, outcome 후보, weight, roll, 다른 화자의 비밀 지식, 원본 token,
내부 request/player UUID와 실제 청중 메타데이터는 모델에 직렬화하지 않는다.
프로필의 identity/background/로어 전체도 제공하지 않는다. 성격·말투 필드는 raw profile 조회가 아니라
콘텐츠 레지스트리의 청중 필터를 거친다. `Provider.current`가 완료 직전 공개 권한을 재검증하고,
방 생성 발화에는 `CONTENT_DISCLOSURE_V1` 근거를 붙인다.

## Presentation JSON

```json
{
  "schemaVersion": 1,
  "kind": "DIRECT_DIALOGUE",
  "generationPolicy": "AI_PARAPHRASE",
  "canonicalDeliveryPolicy": "FACT_BEARING",
  "speakerActorId": "mythictrpg:lubras",
  "factRequests": [
    {"factId": "mythictrpg:wanderer_occupies_lubras_base", "maximumLevel": 2, "required": true}
  ],
  "fallbackTranslationKeys": ["story.presentation.mythictrpg.lubras_full_story"],
  "performanceTags": ["mythictrpg:amused", "mythictrpg:candid"],
  "offeredHookIds": [],
  "offlineDeliveryPolicy": "ON_NEXT_LOGIN",
  "maximumAiTurns": 3
}
```

`CRITICAL`은 `SCRIPTED_ONLY`만 허용한다. `FACT_BEARING`은 사실 요청이 필요하고
`AI_FLAVOR`는 사실 요청을 가질 수 없다. 개인 전달에서 required 사실이 거절되면
`BLOCKED_DISCLOSURE`다. 허용 문구가 8개를 넘으면 조용히 잘라 전체 지식을 올리지 않고 차단한다.

방 전달은 작성자가 선택 필드 `"roomDisclosure":{"mode":"PRIVATE_ROOM","allowedGodIds":[]}`를
추가할 때만 시도한다. 이는 fallback 문구도 그 청중에게 공개할 수 있다는 명시적 허가다.
각 사실의 요청 단계와 위장 문구가 모두 안전한 후보 중 PRIVATE 우선·UUID 순으로 한 방만 고른다.
설정이 없거나 안전한 방이 없으면 기존 개인 전달을 유지하며 여러 방에 무차별 방송하지 않는다.
한 사건의 동일 연출이 여러 플레이어별 opportunity를 가진 경우 실제 전체 문구 수신자만 완료로 표시한다.

## 공개 규칙

선택 필드 위치는 fact의 `levels[].disclosure`, cover/Hook의 `disclosure`,
presentation의 `roomDisclosure`다. 형식 예:

```json
{"mode":"PUBLIC","allowedGodIds":["mythictrpg:fortuna"]}
```

- `OWNER_ONLY`: 플레이어 1명·신 1명의 PRIVATE. 기존 Hook/cover의 기본값.
- `PRIVATE_ROOM`: 현재 비밀방 플레이어 전원에게 공개 허용.
- `PUBLIC`: 현재 공개/비밀방 전체 청중에게 공개 허용.
- `NEVER`: 방 공개 금지.
- `allowedGodIds`: 화자 외 신 제한. 생략/빈 목록은 추가 신 제한 없음.

명시 규칙이 있어도 화자가 모르는 지식이나 플레이어별 WITHHOLD/조건 미충족은 공개되지 않는다.
모든 현재 플레이어의 공개 정책을 검사하며 공개방은 전역 온라인 수신자까지 포함한다.
fact 단계의 disclosure가 없으면 기존 publicPlayerKnowledge/다른 신의 기존 지식 교집합을 보존한다.
이미 알고 있음과 새로 공개해도 됨은 다르다. 기존 JSON을 자동으로 PUBLIC으로 바꾸지 않는다.

Cover Story는 모든 플레이어에게 동일한 작성 위장 문구가 허용되고 cover 공개 규칙도 맞을 때만
Context에 넣는다. 숨겨진 진실을 프롬프트에 함께 넣지 않으며, 위장 대사가 원본 Fact 지식을 올리지 않는다.

## 일반 대화와 지식 습득

`StoryRoomConversationService`는 명시된 방 UUID/revision·입력 플레이어·화자·실제 청중을 사용한다.
다른 선택 비밀방이나 legacy 세션으로 대신 조회하지 않는다.

프롬프트 조회 자체는 Knowledge를 올리지 않는다. 일반 방 모델이 실제 설명할 문구를 선택할 때는
`story_disclose`의 `statement_aliases`에 `statement_1,statement_2`처럼 서버 별칭만 제안한다.
게임은 이를 액션이 아닌 응답 메타데이터로 받아 하위 단계까지 canonical 문구로 별도 발행하고,
완전 수신 receipt가 있는 플레이어·실제 청취 신에게만 Knowledge를 준다. 상위 단계는 하위 문구
수신 또는 기존 지식이 확인되어야 한다. 임의 Fact ID·모델 주장·선택되지 않은 문구는 grant하지 않는다.
TEST_* 방과 RUMOR_TEST는 대사·허용 기록만 처리하고 플레이어/신의 게임 지식은 변경하지 않는다.

`STORY_DISCLOSURE_V1` 근거는 원 화자·fact 단계/상태·source·정책·정의/번역 fingerprint를 보존한다.
기억을 조회하는 신이 원 화자와 달라도 원 주장에 대한 새 실제 청중의 공개 권한을 재검증한다.
단순 지식 grant/전역 revision 증가/요청 캐시 초기화로 과거 기억을 무효화하지 않으며,
사실 정정·정의 변경·공개 철회는 차단한다. 살아 있는 응답은 별도로 정확한 방/revision/청중,
actor 상태와 방향별 동적 관계를 재검증한다.

## 화자 상태와 사건 연출 전달

일반 발화·늦은 응답·Secondary 후보는 등록된 Story actor의 ACTIVE + AVAILABLE을 요구한다.
등록 actor가 없는 신까지 차단하지는 않는다. 이것이 실제 NPC 경로 탐색·이동을 실행한다는 뜻은 아니다.

확정 사건으로 떠난 화자의 작성된 알림은 별개다. `publishStoryPresentation`은 게임 발급 context가
실제 전달 구간에 활성화되어 있고 authored 정책·방·청중·만료 검사를 통과할 때만 부재 상태 발화를
예외 허용한다. 일반 모델 발화에는 이 예외가 없고, 떠난 신을 청취자로 기록하지 않는다.

AI 응답은 지정 화자 한 명, 1~4개 대사와 선택 Hook 하나다. 다른 화자·임의 청중·허용하지 않은
액션·잘못된 별칭·빈 응답·지연 실패는 작성 대사로 전환한다. 완료 시 문구·정책·청중·프로필 공개·
동적 관계를 다시 검사한다. fallback도 현재 정의를 다시 읽고 예전 FACT_BEARING 전송 목록을
새 FLAVOR_ONLY 정의에 적용하지 않는다.

개인 연출은 기존 canonical 시스템 문구 뒤 지식을 반영한다. 방 연출은 방 발화/실제 수신 기록으로
canonical을 전달하고 그 수신자만 갱신한다. JOURNAL_IMMEDIATELY는 별도 저널 UI가 없어 온라인
canonical 전달/오프라인 pending을 사용한다. 영구 저널 UI는 별도 미구현이다.

## Story Hook

별칭/token은 현재 입력 플레이어, 화자 Actor/God, 방 UUID/revision 및 실제 청중, 세션,
대상 scope/instance/revision, 정의 세대·TEAM ID·1,200 게임 틱 TTL에 묶인다.
확인 UI에서 현재 조건·횟수·쿨다운·소유권·화자 상태를 재검증한 등록 Hook만 실행한다.
단 한 번 소비되며 방 변경·새 공개 관찰자·공개 철회·정의 reload·팀 변경·로그아웃·종료 후에는 거절한다.

명시 disclosure가 허용하는 다인/공개방에서도 제안할 수 있고 Secondary도 자신의 Hook을 제안할 수 있다.
수락 대상은 현재 입력 플레이어다. 다른 청중에게 소유권을 옮기거나 기존 TEAM 동의 규칙을 넓히지 않는다.
읽기 전용 시험은 제안 실행 금지다. 개인 연출 Hook은 활성 legacy 세션, 명시 방 연출은 선택된 같은 방의
`submitRoom` 경로만 사용한다. 임의의 다른 방으로 제안을 붙이지 않는다.

## 검증과 한계

과거 04 구현의 전체 GameTest 186/186 기록은 역사이며 이번 확장 검증 숫자가 아니다.
이번 실행 결과·합계는 [작업 기록](../../docs/FULL_ROOM_DIALOGUE_INTEGRATION_20260923.md)에 남긴다.
2026-09-23 새로 분리한 GameTest 실행 환경에서 기존 검증과 방·Story 종단 검사를 포함한 **204/204가
통과**했다. 부재 일반 발화 차단과 활성 작성 연출만 허용하는 검사도 포함한다. 실제 LLM 대사는 생성하지
않았고, 이 숫자는 서버 배포나 다인 클라이언트 화면 확인을 뜻하지 않는다. AI 모듈의 최종 빌드·오프라인
검증 집계는 위 작업 기록의 후속 결과를 따른다.

- StoryHookTokenRoomTest: 실제 token 서비스 + 주변 stub. 방·청중·소유권·TTL·정의·조건·팀 변경·일회성.
- StoryPresentationProviderTest: 실제 prompt/parser/별칭·공개 규칙. 서버/LLM 호출 없음.
- StoryExternalEffectReceiptsTest: 부분 관계 효과 실패/재시도/예외 뒤 성공 receipt 보존.
- StoryRoomGameTests: 실제 방 발행·수신과 하위 canonical→플레이어/신 지식, Cover/test 미grant, portable 증거.
  작성 fixture와 대체 전송을 사용하는 권한 검증이며 자율적 스토리 창발 시험은 아니다.

구조 검증으로 모델 문장의 모든 의미나 자연스러움이 보장되지는 않는다. 실제 다인 HUD·모델의 근거 없는
덧붙임·캐릭터 말투는 별도 인게임/모델 검증 대상이다. 모델 문장만으로 정식 사건·보상·관계가 바뀌지는 않는다.
