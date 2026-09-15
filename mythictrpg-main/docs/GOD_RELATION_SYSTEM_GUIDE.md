# 신 간 관계 시스템 가이드

## 1. 관계의 두 층

신 간 관계는 서로 다른 의미를 가진 두 층으로 나눈다.

- **정적 관계**: 혈연, 창조자, 과거의 진영·숙적처럼 배경 설정에 속하는 사실. AI Content Registry의
  `social_relations`에 `RT_*` 태그로 작성한다.
- **동적 관계**: 현재 월드에서 느끼는 두려움·원한·무관심과 동맹·휴전·전쟁 상태. MythicTRPG
  `DynamicGodRelationState`에 방향별 점수와 상태 태그로 저장한다.

과거에 싸웠다는 사실만으로 현재도 서로 적대한다고 단정하지 않는다. 예를 들어 닉스 사례는 실제
God ID와 최종 관계도를 받은 뒤 다음처럼 서로 다르게 표현할 수 있다.

```text
아프로디테 → 닉스: FEARFUL, RESPECTFUL
제우스 → 닉스: HOSTILE, RESENTFUL
닉스 → 제우스: INDIFFERENT 또는 HOSTILE (현재 설정에 따라 하나를 선택)
제우스 ↔ 닉스: AT_WAR (실제 전투가 지속되는 동안만 양방향)
```

봉인 여부와 승패는 관계 태그가 아니라 사건·세계 상태로 기록한다. 위 항목은 모델링 예시일 뿐이며,
닉스의 확정 ID와 프로필이 아직 없으므로 운영 데이터에는 추가하지 않았다.

## 2. 동적 관계 규칙

- 점수 범위: `-1000..1000`
- 방향성 태그: `hostile`, `fearful`, `resentful`, `indifferent`, `respectful`, `owes_debt`,
  `protective`, `watchful`
- 대칭 태그: `allied`, `truce`, `at_war`
- 대칭 태그는 한 방향의 전이에 작성해도 반대 방향에 같은 트랜잭션으로 적용된다.
- `allied+hostile`, `allied+at_war`, `truce+at_war`는 허용하지 않는다.
- `indifferent`는 `watchful` 외의 활성 감정 태그와 함께 사용할 수 없다.
- 관계가 없는 방향은 점수 0, 태그 없음인 중립 상태로 조회한다.
- 최근 이력은 방향별 16개까지만 저장한다.

## 3. 관계 전이 데이터

경로:

```text
data/<namespace>/mythictrpg/god_relation_transitions/*.json
```

예시:

```json
{
  "schemaVersion": 1,
  "actingGodId": "example:nyx",
  "aiEnabled": true,
  "maxApplications": 1,
  "summary": "제우스와 닉스의 충돌이 공개적인 전쟁으로 번진다.",
  "changes": [
    {
      "sourceGodId": "example:zeus",
      "targetGodId": "example:nyx",
      "scoreDelta": -300,
      "addTags": ["hostile", "resentful", "at_war"],
      "removeTags": ["truce"]
    },
    {
      "sourceGodId": "example:nyx",
      "targetGodId": "example:zeus",
      "scoreDelta": -100,
      "addTags": ["hostile"],
      "removeTags": ["indifferent"]
    }
  ]
}
```

- `scoreDelta`, `addTags`, `removeTags`는 선택 필드지만 각 change는 실제 변화가 하나 이상 있어야 한다.
- 한 파일에서 같은 방향을 두 번 정의할 수 없다.
- `actingGodId`는 모든 change에 참가해야 한다.
- `maxApplications`는 1~1000, 생략 시 1이다. 적용 횟수는 월드에 저장되므로 재접속·재시작 뒤에도
  같은 일회성 사건이 다시 적용되지 않는다.
- `aiEnabled=false`이면 관리자·서버 로직만 적용할 수 있다.
- 알 수 없는 필드·태그·God ID, 범위 초과, 모순된 최종 태그는 reload 또는 적용 단계에서 거절된다.
- 한 전이에 여러 방향이 있으면 전부 검증된 경우에만 원자적으로 커밋된다.

## 4. 조건에서 사용하기

퀘스트·등장·향후 사건 조건은 `mythictrpg:god_relation`을 사용할 수 있다.

```json
{
  "type": "mythictrpg:god_relation",
  "scope": "world",
  "source_god": "example:aphrodite",
  "target_god": "example:nyx",
  "maximum_score": -100,
  "required_tags": ["fearful"],
  "forbidden_tags": ["allied"]
}
```

`minimum_score`, `maximum_score`, `required_tags`, `forbidden_tags`는 선택 필드다. 방향을 뒤집으면
다른 관계를 조회하므로 의도한 화자와 대상을 정확히 써야 한다.

## 5. AI 대화와 변경 권한

- 현재 서버가 허용한 대화 참가자(현재 최대 두 신) 사이의 관계만 프롬프트에 들어간다.
- 화자별 `화자 → 상대` 정적 태그와 동적 상태를 따로 전달한다.
- AI는 관계를 말투와 반응에 반영할 수 있지만 임의의 신을 참가자로 추가하지 못한다.
- 자동 Secondary God 선택은 이 기능에 포함되지 않는다.
- AI가 관계 변경을 제안할 때는 `aiEnabled=true`인 등록 전이의 `transition_id`만 선택할 수 있다.
- `god_relation_transition`은 항상 플레이어 확인을 거치며 승인 순간 서버가 현재 상태와 적용 횟수를
  다시 검증한다. AI가 점수·태그·대상·결과를 자유롭게 만들 수 없다.

## 6. 운영 명령

```text
/mythadmin relation gods get <sourceGod> <targetGod>
/mythadmin relation gods history <sourceGod> <targetGod>
/mythadmin relation gods preview <transitionId>
/mythadmin relation gods apply <transitionId>
```

운영 중에는 `preview`로 전체 방향의 결과를 먼저 확인한 뒤 `apply`한다. 이미 적용 한도에 도달한
전이는 preview와 apply 모두 거절된다.

## 7. 검증 범위

`GodRelationGameTests`가 방향성 감정, 대칭 전쟁 상태, 모순 태그의 원자적 거절, 일회성 전이 중복
방지, 조건 평가, 저장·로드, 현재 참가자만 포함하는 Context 조회를 검증한다. 실제 신 데이터가
추가된 뒤에는 전용 서버에서 resource reload와 실제 AI 응답 스모크 테스트를 추가로 수행한다.
