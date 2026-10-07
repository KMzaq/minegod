# 신의 건축물 방문 — HanesTest 개발 계약

2026-09-29. 운영 콘텐츠를 임의로 켜지 않았다. 방문은 **이미 존재하는 실체의 보행**이며 소환·순간이동·다른 명령 취소가 아니다.

## 설정과 대상

`data/<namespace>/mythictrpg/god_visits/<god_path>.json`의 파일 ID가 기존 God ID다. 예: `data/mythictrpg/mythictrpg/god_visits/demeter.json` → `mythictrpg:demeter`.
파일이 없으면 그 신의 AI 방문은 꺼져 있다. [아바타 정의](NPC_ACTORS.md)의 `movement.enabled`와 `movement.visit`도 true여야 한다.

아래 수치는 **스키마 설명용**이지 운영 밸런스가 아니다. 실제로 어떤 신을 허용할지와 빈도·진행도는 제작자가 정한다.

```json
{
  "formatVersion": 1,
  "dialogue": true,
  "autonomous": true,
  "minimumProgress": {"mythictrpg:example_progress": 30},
  "evaluationPolicies": [],
  "checkIntervalTicks": 6000,
  "cooldownTicks": 12000,
  "requestTimeoutTicks": 400,
  "travelTimeoutTicks": 2400,
  "maximumEvaluationAgeTicks": 24000
}
```

- 모든 필드 필수. 알 수 없는 필드·소수 정수·비정규 namespaced ID는 원자적 reload에서 거절한다.
- `minimumProgress`: 1~16개의 **서버 공용** 진행도 ID → 최소값 `0..100`. 모두 만족해야 한다. AI 별도 진행도 저장소를 만들지 않는다.
- `evaluationPolicies`: 최대 16개의 기존 건축 평가 정책 ID. 선택 신과 정책/저장 평가의 God ID가 모두 같고 유효기간 이내인 이력만 전달한다. 다른 신의 사적인 평가는 섞지 않는다. 비어 있으면 이름과 등록 사실만 제공하며 외형·점수를 만들지 않는다.
- tick 필드: `20..12096000`, 단 `requestTimeoutTicks`는 `20..2400`. 20 TPS 기준 20 tick은 약 1초이며 서버 정지 중에는 흐르지 않는다.
- `checkIntervalTicks`: 해당 실체에서 후보 조회를 시도한 뒤 재판단까지 최소 간격. `cooldownTicks`: 유효 판단(방문/거절)을 받은 뒤 간격. 실체 NBT에 남는다.

집 전용 DB를 만들지 않는다. `/mythstructure register` 등의 기존 `FreeStructureRecord` 중 대상 플레이어가 **소유자로 등록한 건축물**을 사용한다. 팀 기여자라는 이유만으로 타인 소유 건축물을 자신의 집으로 바꾸지 않는다. 신별 전용 건물이라는 배타적 제약도 없다.

## 처리 흐름

1. 게임이 진행도·온라인/생존·실체·raid lease·기존 이동/전투·쿨다운을 검사한다.
2. 같은 차원, 아바타의 `maxVisitDistance` 안, 로드된 청크에서 안전한 보행 목표가 있는 건물만 후보로 만든다. 최대 64개 등록 항목을 ID 순으로 확인하고 최대 8개를 제시한다. 대형 맵 전체를 탐색하거나 최적 건물을 보장하는 검색은 아니다.
3. AI 응답 모듈이 기존 프로필·관계·시간/날씨·해당 신의 과거 건축 평가와 현재 대화 맥락을 읽는다. 취향과 상황에 따라 후보 ID 하나 또는 `NONE`을 선택한다. 최고 점수를 무조건 고르는 규칙은 없다.
4. 게임이 후보·소유·진행도·God/아바타/정책 reload·플레이어·기존 명령 revision을 재검증한다. 대화에서 비롯된 요청이면 동일 방/revision/최신 입력 순서도 확인한다.
5. 실제 도달 가능한 경로가 있을 때만 이동한다. 로드되지 않은 경로 영역을 강제 로드하지 않는다. 건축 삭제·진행도 취소·오프라인·전투·시간 초과·경로 실패는 중단된다.

실체가 없거나 먼 곳/다른 차원에 있으면 소환·텔레포트하지 않으므로 방문하지 못한다. 가까운 목표 지점 탐색은 현재 높이 ±4블록의 제한된 표본이다. 복잡한 산악/수직 건축·언로드 구간을 가로지르는 장거리 여행은 구현 완료 범위가 아니다.

## 두 호출 경로

- 대화: `npc_visit_request`, `parameters={}`, `targetParticipantIds=[]`. 현재 방의 Gateway 권한이 필요하다. 추가 좌표·건축물/타인 ID는 받지 않는다. `EXECUTED`는 **방문 판단 접수**이고 이동/도착 성공이 아니다. 결과 reason에도 이 구분을 명시한다. `TEST_*`/`RUMOR_TEST`의 read-only 정책은 유지한다.
- 자율: `autonomous=true`인 신과 온라인 플레이어 쌍을 순환 검사한다. 서버 전체에서 1초에 한 쌍, 동시에 한 판단만 예약한다. 신별 간격도 적용한다. 바쁜 LLM은 재시도 폭주 없이 다음 기회로 넘기며 일반 대화를 우선한다.

`GodVisitPlanner.Request → Decision`은 immutable 내부 IO다. 요청마다 새 UUID, canonical God ID, 대상 플레이어 ID, trigger, 게임 affinity, 시간/날씨, 후보 목록, 선택적 **해당 방** 대화가 있다. AI wire 출력은 `{ "requestId":"요청 UUID", "structureId":"제시된 UUID 또는 NONE" }`뿐이다. 외부 URL·다른 세션 ID·추가 기능 필드를 거절한다.

감정은 대화 중에 **동일 방·신·플레이어의 실제 전달된 발화 근거**가 남아 있을 때만 기존 비권위 현재 감정을 재사용한다. 자율 방문에 다른 비밀방의 감정을 가져오지 않는다. 현재 공용 감정 공급자가 없으므로 자율 판단의 감정은 `UNASSESSED`이고, 이를 중립으로 확정하지 않는다. 서버 공용 감정 엔진을 완성한 것으로 보지 않는다.

## 결과·영속성·한계

- 시스템 메시지는 소유자에게만 이름 식별 규칙을 적용하여 판단 불가·거절·이동 시작·경로 실패·중단·실제 도착을 구분한다. 모델이 작성한 사유를 공개 대사로 자동 전송하지 않는다.
- 마지막 실제 이동 결과는 해당 신/소유자의 1:1 비밀방에만 게임 Context로 공급한다. 공개·다인방에 집 방문 내역을 자동 노출하지 않는다. 생성 도중 결과가 바뀌면 오래된 응답을 취소한다.
- 진행 중 목적지/소유자/만료 tick과 재판단 시점은 아바타 NBT에 저장한다. 언로드된 실체를 강제로 불러오지 않는다. 다시 로드되면 유효성을 재검사한다. 새 OP 이동은 방문을 중단하며 AI가 그 명령을 되돌리지 않는다.
- 도착은 보상, 건축 점수 변경, 자동 대화방 합류 또는 신 정체 공개를 뜻하지 않는다. 우클릭 등 기존 대화 경로를 재사용한다.
- 원래 일반 OP `visit <player>`는 플레이어 따라가기이며, 이 등록 건축물 방문과 다르다.

## 검증

전용 `mythictrpg_home_visit` GameTest에 실제 보행/도착, 진행도 변경, 미제시 ID, 다른 명령, 건축물 삭제, NBT 재읽기, 자율 tick과 실제 방 Proposal 경로 검사가 있다. AI `homeVisitTest`는 모의 HTTP envelope/출력 스키마/정규화 검사이며 실제 Ollama 호출이 아니다. 최신 통과 결과와 배포 상태는 [시스템 작업표](../../인수인계/브랜치실험/HanesTest/SYSTEM_COMPLETION_TRACKER.md)를 따른다. 실제 프로세스 재시작·클라이언트·실모델 품질은 별도다.
