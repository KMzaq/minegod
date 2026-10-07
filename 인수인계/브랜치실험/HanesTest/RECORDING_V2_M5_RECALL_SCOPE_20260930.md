# Recording V2 — 회상 질문·실제 발화자 범위

2026-09-30 / HanesTest / SHADOW 비교 경로, 운영 미배포.

## 변경 이유와 흐름

기존 `RoomMemoryBridge`는 회상 질문과 짧은 후속 질문의 문맥을 이미 계산한다. 새 RAW/semantic 비교가 현재 채팅만 다시 사용하면 “내가 내일 어디 간다고 했지?” 뒤의 “다시 알려줘”는 주제와 실제 발화자를 잃는다. 두 번째 분류기나 focus 저장소를 만들지 않고 기존 계산 결과를 재사용한다.

`RoomMemoryBridge.recall → Recall.query → RecordedRecallQuery → RAW/semantic SHADOW`

- `Recall.query`는 성공한 기존 조회의 불변 `Optional<RecallQuery>`다. 정상 조회의 결과가 비어 있어도 계획은 전달한다. OFF·사용 불가·stale 결과는 계획을 만들지 않는다.
- 기존 2/3인자 `Recall` 생성자는 유지하며 계획 없이 작동한다. 기존 prompt·선택된 출처·짧은 prompt 변형은 바꾸지 않는다.
- 새 소비자는 요청의 방/revision·발화 신·질문자·청중과 계획을 대조한다. 게임 memory context가 있으면 world도 확인한다. 잘못 연결된 계획은 더 넓은 조회로 fallback하지 않는다.
- 기존 최대3턴/5분·같은 scope의 focus 규칙을 그대로 쓴다. 짧은 후속 질문은 최초 플레이어 질문과 그 질문의 시간 기준을 유지한다. NPC가 생성한 답변을 다음 검색 질문의 사실로 저장하지 않는다.
- 계획이 없으면 현재 문장에 한정한 RAW 비교만 가능하다. 별도의 semantic 판단/모델 호출을 시작하지 않는다. 기존에 계산된 명시 회상 또는 그 후속 질문만 opt-in 의미 검색을 시도한다.

## Query의 추가 필드

[MemoryReadSession](../../../mythictrpg-main/src/main/java/com/sande/mythictrpg/recording/api/MemoryReadSession.java)의 Query는 기존 text·선택적 발생 UTC 범위에 `ActorSelection actorSelection`을 추가한다. 기존 3인자 생성자는 `ANY`로 유지한다. 새 AI/게임 JAR는 함께 맞춰야 하며 네트워크 protocol이나 SQLite schema를 변경하지 않는다.

`ActorSelection(Optional<ActorKind> kind, Set<ActorRef> include, Set<ActorRef> exclude)`

- kind/include/exclude는 AND 조건이다. 빈 include는 추가 포함 제한이 없음을 뜻한다.
- include+exclude 합계 최대16개, 교집합 금지, 불변 복사한다. UUID/게임 ID만 사용하고 표시 이름으로 identity를 만들지 않는다.
- 실제 말한 사람을 선택하는 조건이다. 그 말을 들은 신·소문 대상·관찰자는 발화자가 아니다.
- 기존 `RecallSourceScope.resolve`를 재사용한다. `PLAYER`는 요청자 UUID, `THIS_GOD`는 현재 화자 신 ID, `OTHER_GOD`는 현재 신을 제외한 GOD, `ANY_GOD`는 GOD, `IDENTIFIED_GOD`는 명시된 정확한 ID, `UNSPECIFIED`는 ANY다. 모순된 표현을 임의의 인물로 확정하지 않는다.
- 정확한 신 ID는 검색 주제 문자열에서 제외하고 selector로 전달한다. LLM이 참가자나 청취 권한을 추가하는 방식이 아니다.

## 저장소·권한·예산

RAW/FTS와 semantic 모두 기존 최대128개 metadata window 안에서 actor를 먼저 거른다. 다른 발화자의 큰 본문/벡터가 선택된 발화자의 payload/result 예산을 소진하지 않도록 한다. 검증된 실제 source의 actor도 다시 대조한다. actor 필터로 모든 기간을 무제한 SQL 순회하지 않으며 오래된 후보를 놓칠 수 있는 PARTIAL/페이지 한계는 그대로다.

원문 hash·실제 수신 receipt·현재 청중·증거 계보·철회·고정 watermark 검사는 유지한다. Query 전체를 opaque cursor에 묶으므로 같은 검색어/벡터여도 actor를 바꾼 continuation은 거절한다. 알 수 없는 신 ID를 지정해도 새 지식 접근권은 생기지 않는다.

필터는 **검색 seed만** 제한한다. 그 발언의 정정·취소·인용을 검증하는 전체 input/parent 의존성에서 다른 화자를 지우지 않는다. 그렇게 지우면 잘못된 약속을 되살릴 수 있다.

Watch projection에는 실제 대화 화자가 없으므로 non-ANY actor selector는 UNAVAILABLE로 거절한다. 요청 조건을 무시하거나 관찰 대상 플레이어를 화자로 위장하지 않는다.

## 시간 표현

“내일 바다에 가겠다”의 내일은 발언 내용에 언급된 계획 시각이지 원문이 작성된 시각이 아니다. 기존 `RecallSearch`의 날짜 해석을 Query의 `fromInclusive/untilExclusive`에 임의 대입하지 않는다. 이번 adapter는 해당 두 필드를 비워 두며, 기존 질문의 `askedAt`도 바꾸지 않는다.

## 검증과 남은 일

- 게임 actor selector 전용 SQLite 회귀66 checks 통과: 불변/상한·RAW/FTS/semantic·실화자·cursor·ACL·Watch 거절·고정 W·손상된 actor metadata·다른 화자의 정정 의존성·큰 제외 본문의 예산을 확인했다.
- AI focused build15 tasks 성공: 계획 전달38·query/소비자48·관찰 SHADOW52·기억 청중374·semantic SHADOW55/runtime67·SQLite+모의 embedding backend103·방 엔진98 checks 통과. JAR440 classes/게임 중복0 확인. 새로운 actor long-tail fixture는 후속 검사로 추가 중이며 이 최초66 검사에 합산하지 않는다.
- 새 결과는 여전히 내용 없는 SHADOW 집계다. 실제 답변 prompt/Proposal, 운영 ON, legacy writer 종료, 실제 모델의 검색·답변 품질 검증은 이번 연결의 완료 범위가 아니다.
