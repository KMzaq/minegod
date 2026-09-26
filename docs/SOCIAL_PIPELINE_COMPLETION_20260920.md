# 소문 생성·신별 인식·대화 설득 회복의 실제 연결

2026-09-20 / 개발 게임 **1.0.11 / AI 0.1.12** / 소스·오프라인 범위 / 운영 OFF·미배포

## 1. 승인과 이번 완료 범위

사용자가 기반 API만 있는 상태를 확인한 뒤 핵심 연결 구현을 승인했다. 추가 결정은 **일반 신 대화는 전서구가 관찰할 수 있고, 비밀·차폐 대화는 제외**다. 실제 전서구 EntityType과 별도 스폰 이벤트는 여전히 후속 제작이며 자동 스폰·재생성은 만들거나 켜지 않았다.

이전 [6단계](RUMOR_STAGE06_IMPLEMENTATION_20260920.md)·[7단계](REPUTATION_STAGE07_20260920.md)의 ‘생산자/검토기/대화 소비자 미연결’을 다음 범위에서 해소했다. 기존 장기기억·검색 모델을 교체하거나 OPELA 전체 파이프라인을 재도입한 작업은 아니다.

| 실제 입력과 처리 | 이번 연결 |
|---|---|
| 수락된 플레이어 발언 → 실제 신 답변 출력 | 기존 대화 adapter → `DialogueMemoryBridge` → 게임 `SocialRuntime` |
| 전서구의 실제 목격 → 소문 후보 | 기존 개체 귀속과 양 시점 거리·시야·차폐 확인 → `CourierEngine` |
| 중요한 게임 결과 → 공개 가능한 후보 | 기존 `ImportantEvents`의 실제 결과 + 정확히 일치하는 작성된 공개 매핑 |
| 후보 의미 판단 → 발행·전달 | `SocialReviewProvider`/`OllamaSocialReview` 비동기 제안 → 게임의 출처·epoch·정책 재검사 |
| 받은 소문 → 그 신의 인식 | 실제 대화에 선택된 소문만 성격·관계로 후처리 → ACCEPTED/DOUBTFUL/IGNORED |
| 실제 해명 대화 → 회복 | 제한된 최근 대화 검토 → 게임 소유 Reviewer → 버전 비교 적용 → RECOVERED |
| 회복 이후 다음 대화 | 현재 평가/버전 전달, 과거 소문을 현재 믿음과 구분하고 오래된 인용의 재사용 차단 |

이는 **실행 가능한 소스 경로를 연결한 것**이지 실제 모델이 항상 올바르게 판단한다거나 지금 서버에서 동작한다는 뜻은 아니다. 기존 RUMOR_TEST 시험 모드 제한은 해제하지 않았다.

## 2. 관측·공개 경계

- 실제로 등록·연결된 살아 있는 전서구가 없으면 목격/소문을 만들지 않는다. 대상은 게임이 지정한 플레이어이며 AI가 관측자나 다른 참가자를 추가할 수 없다.
- 일반 신 대화는 발언 시점과 답변 출력 시점에 **같은 개체/epoch/규칙**이 거리·시야 조건을 통과해야 한다. 중간 사망·차원 이동·유효 시간 만료·규칙 변경 등은 제외한다. 듣지 못한 첫 발언을 답변 시점에 소급 목격하지 않는다.
- 비밀 대화는 게임의 `SocialRuntime.privateConversation(player, true)`, `privateGods`, `blockedAreas`로 차단한다. 현재 상호작용 전체에 적용하며 관리자 시험 명령은 `/mythadmin social private <player> <true|false>`다. 권한 2가 필요하고 이번에 실행하지 않았다. `/mythadmin social status`도 준비했다.
- `reward-watch.json`에 작성된 차폐 공간도 재사용한다. 전서구 권능 허용 규칙이 따로 없으므로 **현재는 해당 영역 전부를 보수적으로 차단**한다. 신별 허용 목록을 전서구 허가로 전용하지 않는다.
- 비밀 표시는 비밀을 말하기 전에 게임/콘텐츠가 설정해야 한다. 현재 교환의 미발행 후보는 취소하지만 이미 공개된 과거 소문을 소급 삭제하지 않는다. 자연어의 “비밀로 해 줘”는 의미 검토에서도 SKIP하도록 지시했으나, 자연어 판단만으로 완전한 비밀 보호를 보장하지 않는다.
- 소문 생성 입력은 실제 관찰한 **현재 1회 왕복 대화**다. 앞선 사적 히스토리·숨은 로어·시스템 프롬프트를 소문 판단에 붙이지 않는다. 플레이어 입력 500자, 양쪽 발언과 구분자 합계 600자를 넘으면 해당 소문 후보를 생략한다. 대사/HUD 자체를 잘라내지는 않는다. 긴 문맥이 필요한 인상은 현재 누락될 수 있다.
- 중요한 결과는 BATTLE_RESULT/QUEST_TRANSITION/ADVANCEMENT_EARNED만 지원한다. 기존 중요 사건 수집이 활성화돼 있어야 하며 `type + targetId + result`가 명시적 공개 매핑과 일치할 때만 `publicDescription`을 투영한다. 원장의 실제 참가자·좌표·숨은 퀘스트 payload 전체를 다른 신에게 넘기지 않는다. 일반 좀비/일상 행동을 상세 소문으로 바꾸지 않는다.
- 하나의 후보는 한 관찰 대상에 한정한다. 다른 인물의 평가·비밀이나 실제 업적 검증이 필요하다고 판단한 출력은 거절한다. 전서구 사냥은 기존대로 **사냥자가 아닌 관찰 대상**의 미전달 소문을 차단한다.

## 3. 의미 판단·회복과 게임 권한

`SocialReview`는 게임 소유의 불변 요청/응답 계약이다. 모델이 하는 일은 비권위 해석 제안이며, 발행·수신·평판 저장은 기존 게임 서비스가 수행한다. 임의 affinity·보상·퀘스트 실행 필드는 JSON에서 허용하지 않는다.

- RUMOR: 평범한 잡담은 SKIP, 강렬한 인상/특별한 사건만 PUBLISH 후보. 실제 근거의 정확한 인용, 짧은 한국어 전언, 선택적인 수식어를 반환한다. 신의 대사도 게임 사건의 증명으로 취급하지 않는다. 고정 금기나 성별·신화권 편견을 새 콘텐츠 규칙으로 넣지 않았다.
- RECEPTION: 기존 콘텐츠 레지스트리의 해당 신 personality/values/restrictions와 **기존 게임의 실제 affinity**를 사용한다. 숨은 로어/다른 신의 지식은 보내지 않는다. 수신됐다는 이유만으로 모든 신에게 모델 호출을 하지 않고, 실제 대화에 선택된 소문을 답변 후 평가한다. 첫 답변은 기존 수신 태도/프로필을 사용하고 후속 평가가 다음 턴부터 반영될 수 있다.
- RECOVERY: 현재 신·플레이어·세션의 최근 최대 6개 발언과 실제 프롬프트에 선택된 소문 최대 3개만 검토한다. 한 번에 최대 2개를 검토하며 이후 tick에서 나머지를 처리할 수 있다. 다른 사람의 사적 히스토리나 전체 소문 DB를 탐색하지 않는다.
- 구체적인 의도 설명·맥락 정정으로 납득하면 **별도 업적 없이 대화만으로 회복 가능**하다. 특정 사과 문자열이나 NPC가 동의한 한마디를 자동 성공 조건으로 쓰지 않는다. 업적 달성/증거 아이템 등 게임 사실의 진위가 필요한 주장만으로는 회복시키지 않는다.
- 게임 소유 Reviewer는 준비된 단회 승인만 소비한다. 실제 플레이어 발언 인용, 현재 세션·세대·신·청중·턴·관계 원본·수신 근거·평가 버전을 재확인한다. 다음 턴, 다인 대화의 다른 화자 개입, 종료·재접속·교체·오류 후 지연 결과는 적용하지 않는다.
- 의미 수신 평가는 `DirectImpact.UNKNOWN`으로 저장한다. **수치 보정 규칙이 있어도 이 경로는 호감도/관계 판단 수치를 자동 차감하지 않는다.** 원본 affinity·퀘스트·보상·FTB 소유권은 그대로다. modifier/cap 0인 서술용 평가 규칙도 사용할 수 있게 했다.
- 회복은 해당 신의 그 평가만 RECOVERED로 바꾼다. 다른 신의 인식이나 원본 소문·대화는 삭제하지 않는다. 프롬프트에서 회복/철회/무시된 주장은 `historical_claim_not_current_belief`로 보내고 현재 수식어를 제거한다. 평가 버전이 바뀌면 이전 비난을 인용한 NPC 히스토리의 근거도 무효화한다.

정확한 인용/출처 검사는 모델의 **해석 자체가 옳다는 증명은 아니다**. 오해의 적정성·회복 난이도·캐릭터 자연스러움은 별도의 실제 모델/콘텐츠 평가 대상이다. 게임 승인 전에 출력된 “알겠다”가 이미 회복을 실행했다는 뜻도 아니며, 승인 상태는 후속 턴에 반영된다.

## 4. 실행 설정과 후처리 부하

새 설정 예제는 [social-rumor](examples/social-rumor.example.json), [ai-social-review](examples/ai-social-review.example.json)이며 **둘 다 OFF, 서버 미복사**다. 모듈 버전만 올리거나 예제를 켜는 것만으로 실제 전서구가 생기지 않는다.

필수 조합:

1. 기존 `ai-memory-foundation.json`의 RUMOR_TEST. PERSONAL에는 몰래 켜지지 않는다. RUMOR_TEST의 기존 AI 게임 액션 차단도 유지한다.
2. 기존 `rumor-courier.json`의 enabled, 실제 EntityType/개체 귀속, 작성된 관측·수신 규칙. 일반 대화 eventType은 `mythictrpg:ordinary_god_dialogue`, source는 DISCLOSED_DIALOGUE이며 의미 판단을 거치는 publication은 CANDIDATE다. 물리 개체 제작/스폰 후 게임에서 `bindExisting`을 호출해야 한다.
3. `social-rumor.json`: schemaVersion 1, enabled, ordinaryDialogueObservable, privateGods, blockedAreas, eventRoutes, publicationGuidance, recoveryGuidance. eventRoutes는 type/targetId/result/eventType/publicDescription, 지침 map의 키는 각각 courier rule ID/reputation policy ID다. 정확한 필드·검증은 [SocialSettings](../mythictrpg-main/src/main/java/com/sande/mythictrpg/rumor/SocialSettings.java)를 따른다. 이벤트 매핑·신별 강도·금기는 임의 작성하지 않았다.
4. `ai-social-review.json`: enabled, timeoutMs(100~30000), maxTokens(128~1200). 예제는 8000ms/700이다. 기존 대화 Ollama endpoint/model을 재사용하고 새 모델을 설치·전환하지 않는다. 숫자 loopback의 `/api/chat`만 허용, 리다이렉트/원격 주소/설치 경로는 거절한다.
5. 신별 평가/회복에는 기존 `reputation-judgement.json` enabled와 정확한 신/전서구 규칙 매핑이 필요하다. 한 조합에 여러 규칙이 걸리면 자동 선택하지 않는다. 서술용 modifier=0 및 양쪽 cap=0이 허용되며 수치 페널티를 먼저 정할 필요는 없다.

설정 누락/오류·알 수 없는 근거는 닫힌 상태로 처리한다. 설정 변경 후 서버 생명주기에 맞춘 적용이 필요하며 이번에는 변경·재시작하지 않았다. 실제 배포본은 **게임1.0.2 / AI0.1.3 / 콘텐츠0.1.0** 그대로다.

후처리는 답변 경로에서 기다리지 않는다. 서버가 최대 4개 진행 요청을 추적하고 AI는 worker 1개/대기 4개, 모델별 요청은 한 번의 구조화 생성으로 제한한다. 실제 모델 실패는 최대 3회/100tick 이후 재시도, 혼잡·foreground 선점은 실패 횟수에서 제외하고 유효한 턴에서만 다시 시도한다. 완료된 의미 결정을 반복 추첨하지 않는다. 후보 32개/수신 16개 순환 조회로 앞의 실패 항목에 영구 막히는 것을 피한다.

대화 후처리는 플레이어가 접속 중이어도 foreground 작업이 없을 때만 모델을 빌린다. 기존 유휴 기억 정리는 여전히 접속자 0명 조건을 유지한다. 새 대화는 후처리 HTTP에 중단을 요청하지만 **HTTP가 실제 종료될 때까지 모델 사용권을 점유**하므로 잔여 경합이 완전히 0이라고 보장하지 않는다. 받아들인 소문마다 모든 신에게 분석을 돌리지 않으며, 이 제한이 없는 전역 팬아웃 설계는 채택하지 않았다.

추적 bookkeeping 상한은 런타임 8192개, 종료된 회복 턴은 정리한다. 이는 기존 영속 소문/평판의 항목별 4096개 상한·90% 경고와 다르다. 자동 원본 삭제·새 바이트 보관 정책은 추가하지 않았다. 재시작 시 소문 대기는 기존 저장소에서 다시 조회하고 미완료 설득 턴은 버린다. 이미 저장된 신별 평가/회복은 유지한다.

## 5. 보존·빌드·오프라인 검증

- LP 소스 298개, 기반 이전 906개, LP 원본 JAR 3개 해시를 검증했다. 기존 백업을 덮어쓰지 않았다.
- 직전 상태 별도 백업은 `server/backups/before-memory-stage05-20260920-192438-700/`, **2365개 파일**이다. 도구 라벨은 기존 memory-stage05이지만 이번 연결 작업 전 상태다. 소스/문서/JAR/설정/기억/시험 로그/데이터팩 등 manifest 범위이며 전체 월드 지형 백업은 아니다.
- 게임 1.0.11, AI 0.1.12를 개발 build/libs에만 만들었다. AI 최소 게임 의존성도 1.0.11로 명시했다. legacy mine/mine 입력과 과거 JAR은 보존하고 생성 소스는 원본 Gradle 오버레이로만 패치했다.
- 게임 명령: `compileJava socialPipelineTest reputationStageTest courierStageTest memoryFoundationTest questParticipationTest watchRewardTest jar`.
- AI 명령: `compileJava socialReviewTest rumorDialogueTest experienceDialogueTest memoryFoundationTest questParticipationDialogueTest memoryIndexRuntimeTest derivedMemoryTest naturalMemoryTest jar`.
- 두 명령 모두 `--offline --no-daemon --max-workers=1`, 로컬 Java 21로 통과했다. 실제 모델 벤치마크/서버/GameTest 작업은 실행하지 않았다.

| 최종 오프라인 검사 | assertion |
|---|---:|
| 게임 SocialPipeline / Reputation / Courier | 56 / 4221 / 4189 |
| 게임 Rumor / QuestParticipation / WatchReward | 39 / 61 / 14 |
| 게임 합계 | **8580** |
| AI SocialReview / RumorDialogue / Experience / MemoryJournal / QuestParticipation | 41 / 16 / 37 / 656 / 22 |
| AI MemoryIndex / Scheduler fixture / ModelConnection | 1874 / 4 / 50 |
| AI DerivedMemory / Summary fixture / NaturalMemory | 435 / 4 / 63 |
| AI 합계 | **3202** |
| 최종 합계 | **11782 PASS** |

개발 JAR SHA-256:

- `mythictrpg-1.0.11.jar`: `BA520704D419304A67581C93486EC1DBAB7E53FC97AB9ED6544EAE998A3C12E9`
- `mythai_ai_response-0.1.12.jar`: `7281FFBFD2E43A3F35264AA3DB80BE4F43791D9D5F3A6412381B86E4C771E447`

새 검사는 실제 엔진+모의 의미 판단, 가짜 HTTP transport, 생성된 adapter 메서드/바이트코드 연결 검사다. 가짜 응답으로 통과한 횟수를 실제 LLM 정확도나 Minecraft 플레이 횟수로 표기하지 않는다. 반복 용량 삽입 검사를 포함한다. 중간에 대화 오버레이 순서, 모의 검사 문구 및 격리된 기존 다인 테스트의 새 연결 stub을 수정했으며 최종 재실행으로 확인했다. 손상/미지원 NBT를 넣는 음성 검사의 ERROR 로그는 예상된 거절이다.

[보존·패키지 감사](../dev-tools/Test-SocialPipeline.ps1)와 [이번 변경 manifest](social-pipeline-changes-20260920.csv)를 사용한다. 기존 6·7단계 보고서/CSV는 당시 이력으로 남겨 두고 재생성하지 않는다. 개발 테스트 로그 자동 회전으로 정리된 파일은 baseline에 보존돼 복구 가능하며 실제 서버 로그/데이터를 삭제한 것이 아니다. 새 DTO 필드/승인 enum은 개발 JAR 양쪽을 맞춰야 하며 기존 저장 형식 이관이나 LP JAR로 새 데이터를 읽는 하향 호환을 약속하지 않는다.

최종 보존/호환 감사 PASS: 직전2365개 중 **2323개 불변**, 수정41개·개발 회전 로그 정리1개, 신규21개·개발JAR2개로 manifest **65행**이다(자기 해시 제외). 새 게임/AI와 나머지 실제 서버 모드 조합 **7개JAR/244개 패키지의 split-package 0**, 중복 엔트리/시험 fixture 패키징 없음. 이는 실제 Mixin·게임 이벤트 호환성 검사와는 다르다. 변경 Markdown의 로컬 링크138개/코드 펜스, `git diff --check` 및 신규 소스·문서의 공백/충돌 마커 검사를 통과했다. 서버/클라이언트 배포본·설정·기억·콘텐츠·FTB·legacy와 기존 사용자 변경은 baseline 기준으로 보존했다.

## 6. 남은 검증과 실제 콘텐츠

- 후속 전서구 EntityType·스폰 이벤트/개체 귀속 및 신별 전파·공개 사건 매핑은 아직 필요하다. 장비명/드래곤 친분 등 개별 콘텐츠 생산자를 모두 자동 구현한 것은 아니다. 일반 신 대화와 매핑된 기존 중요 사건 생산자는 이번에 실제 연결했다.
- 수치 평판 효과·퀘스트/상점 제한·큰 업의 자동 상쇄·부분 회복/시간 감쇠·재전파망은 승인된 규칙/콘텐츠가 없는 상태다. 이번에 임의 확정하지 않았다.
- 실제 LLM의 한국어 후보/비밀 판단/신별 설득 품질, 과도한 소문 빈도, 1·4·6인 대기와 GPU 메모리, 전체 Minecraft/HUD/모드 이벤트·실제 서버 복구는 미검증이다. 예제 설정은 성능 수용값이 아니다.
- **서버/GameTest 실행, 실제 모델 호출·설치, 서버·클라이언트 JAR 배포, 서버 설정·월드·기억 변경은 하지 않았다.** 별도 승인 없이 운영 활성화/다음 단계로 넘어가지 않는다.

다음 작업은 추상 검토기 재설계가 아니라, 실제 콘텐츠 ID/전서구 연결과 별도 승인된 모델·인게임 수용 시험이다. 현재는 그 시험에 사용할 핵심 연결 소스와 오프라인 경계 검사가 준비된 상태다.
