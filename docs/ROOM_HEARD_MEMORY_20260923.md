# 대화방 청취 기억 연결 (2026-09-23)

## 범위와 저장 위치

`RoomMemoryBridge → RoomMemoryStore`는 게임이 실제 발행한 `RoomDialogueEvent`를 한 번 저장하고 `worldId + 실제 청취 God ID`로 조회한다. 월드 사실·신 지식·관계 원본 DB를 새로 만드는 기능이 아니다. 검색 결과는 과거 **발언**이며, 발언 내용이 실제 사건이라는 확인은 아니다.

- PERSONAL: `<world>/mythictrpg-ai-room-heard-v1/heard-dialogue.jsonl`
- RUMOR_TEST: `<world>/mythictrpg-ai-room-heard-rumor-test-v1/heard-dialogue.jsonl`
- 전역 OFF: 지속 저장·지속 기억 검색 없음. 현재 발행의 원문 없는 메타데이터만 모드별 RAM에 보관한다.
- `/ai_call` 등의 `TEST_RECORDING`은 기존 의도대로 해당 전역 모드의 기억 기록을 허용한다. `STANDARD`와 별도의 가상 기억으로 자동 분리하지 않는다. `TEST_EPHEMERAL`은 지속 저장하지 않는다.

기존 PERSONAL/RUMOR_TEST 개인 `MemoryJournal`은 읽기 호환으로 남는다. 새 대화방 발언을 양쪽에 중복 기록하지 않는다. 기존 형식의 16명/1200자 제한을 새 방 기록에 적용하지 않는다. 새 원장은 게임 이벤트 전체 텍스트(계약 상 최대 131,072자), 방·revision·발화자·실제 전체 수신 플레이어·실제 청취 신·발언 ID·근거·선행 발언 ID를 보관한다. 신 수는 기존 게임 방 계약의 최대 16신을 유지한다.

## 발행·조회·공개 조건

1. 게임이 발화자, 방, 전체 HUD/채팅 전달과 신의 실제 청취를 판정한다. 멤버 목록만으로 청취를 추정하지 않는다. 신이 떠나며 한 말은 실제 남아서 들은 신만 기억하며 화자에게 가짜 청취 기록을 추가하지 않는다.
2. `preparePublication`은 생성에 사용한 콘텐츠/Story/기억/관찰 근거와 선행 발언을 붙인다. `published`가 기록 ON일 때 지속 쓰기, `observed`는 OFF에도 현재 히스토리 검증용 메타데이터를 받는다.
3. 최초 신 발언, LLM을 호출하지 않은 플레이어 입력, 일반/다중/공개/비밀방, 정식 Story 연출도 같은 발행 경로로 처리한다. AI Request 또는 `memoryContext` 존재 여부로 저장을 누락하지 않는다.
4. 질문자가 바뀌어도 같은 신이 들은 발언을 검색한다. `내가`, `네가`, `다른 신이`, 명시 God ID는 발언 출처 필터이지 고정 대사 선택 규칙이 아니다. 플레이어 이름은 기록 당시 실제 이름/UUID를 이용하며 신의 별명 매핑을 임의 추정하지 않는다.
5. PUBLIC 발언은 새로운 청중에게도 재공개할 수 있지만 현재 근거 권한을 통과해야 한다. PRIVATE 발언은 현재 플레이어·신 청중이 원래 전체 청취 청중의 부분집합이어야 하며 공개방으로 전환해 재공개할 수 없다. 공개 발언의 선행 근거가 비밀 발언이면 그 비밀 조건도 유지한다.
6. raw 원문 전체를 검색한다. 모델 입력에는 검색 주제 주변 최대 600자씩, 최대 3개 인용을 넣고 원문 길이·인용 위치·출처·발화자 ID를 표시한다. 이 프롬프트 예산은 원문 저장 잘림을 뜻하지 않는다.

## 재시작과 근거 철회

Story·콘텐츠는 저장한 정의 ID/출처/문구 해시와 현재 공개 정책을 재검증한다. 관찰은 기존 게임 watch 저장소에 정확한 관찰 ID를 비동기 조회한 후 게임 스레드의 최종 guard를 사용한다. 예전 개인 기억은 원 Entry ID/fingerprint, 소문은 원 수신 신/대상/claim fingerprint를 현재 원본과 비교한다.

선행 발언 계보는 재귀 깊이 제한 대신 방문 상태가 있는 DAG 순회로 검증한다. 중복 근거는 한 순회에서 한 번 검증한다. 원본 삭제·철회, 알 수 없는 근거, 다른 월드, 누락된 선행 기록, 순환 계보는 재사용을 거부한다. 실제 들은 원문을 지우거나 권위 있는 새 사실로 바꾸지는 않는다. 과거 전송 경로에서 가져온 불투명한 session closure만 호환상 `LIVE_LEASE_V1`로 남으며 재시작 후 허용하지 않는다. 새 Story/관찰 발화는 그 임시 closure 대신 지속 검증 가능한 근거를 사용한다.

OFF 메타데이터는 모드별 32,768개 RAM 한도로 오래된 것부터 해제하며 원문은 없다. 이를 유일한 선행 근거로 사용한 발언은 메타데이터 해제/재시작 후 원 근거가 없으면 재사용하지 않는다. 이는 OFF 기록을 나중에 지속 기억으로 세탁하지 않기 위한 경계다. ON의 정상 지속 원본/조상은 자동 만료·삭제하지 않는다.

## 용량·실패·검증

`config/mythictrpg/ai-room-memory-retention.json`이 없으면 기본 12,000메시지/64MiB다. 현재 공용 `MemoryRetentionSettings` 스키마를 사용한다.

```json
{
  "schemaVersion": 1,
  "maxEntries": 12000,
  "maxPerScope": 2000,
  "maxStorageBytes": 67108864,
  "protectedReserveEntries": 0,
  "protectedReservePerScope": 0
}
```

새 방 원장은 메시지 하나를 여러 신이 함께 참조하므로 `maxEntries`, `maxStorageBytes`만 용량 판정에 사용한다. 나머지 필드는 공용 스키마 호환용이며 방/플레이어별 삭제나 예약 슬롯을 뜻하지 않는다. 설정 변경은 정상 재시작 후 적용한다. 최대 설정 범위는 공용 스키마(100,000건/1GiB)이며 대형 SQLite 아카이브 구현을 의미하지 않는다.

90% 용량 경고, 한도 도달·쓰기 실패 로그, 중복 ID 충돌 거절, 파일 잠금과 `force(true)`를 사용한다. 초과 시 오래된 원문 자동 삭제/축약을 하지 않고 새 쓰기를 명시적으로 거절한다. 손상 파일은 덮어쓰거나 자동 복구하지 않고 읽기/쓰기를 중단한다. 종료 시 대기 쓰기를 drain하며 미완료는 오류 로그로 구분한다. 늦은 검색 완료는 종료 generation·방 revision·전체 청중을 다시 확인한다.

소스 회귀는 `RoomMemoryStoreTest`(기존 `memoryAudienceTest`에 포함)에 있다. 실제 JSONL close/reopen, 원문 꼬리 검색, 교차 질문자/발화 역할, 41명 수신, PUBLIC/PRIVATE 병합·분리, OFF, 전역 모드 분리, Story 근거와 삭제 계보, 10,000단계 DAG, 파일 잠금·손상·용량 거절을 검사한다. 실행 결과와 배포/인게임 확인 여부는 최신 통합 작업 기록을 따른다. 이 문서 자체는 실제 LLM·서버 검증 성공을 주장하지 않는다.

추가로 AI 모듈의 `RoomFullPathGameTests`(`mythai_room_full_path`, batch `ai_room_live_modules`)는 세 모듈을 로드하는 opt-in GameTest fixture다. `LocalLlmClient`만 오프라인 대체하며 실제 `respond`·콘텐츠 `audienceContentFor` reflection·게임 PUBLIC_MOBILE 발행·전체 수신/청취 receipt·기억 writer commit·다른 질문자가 요청한 다른 신 발언 회상을 검사한다. 주 응답 뒤 보조 반응, 양방향 정적 관계의 화자/상대 ID, DIVINE 로어의 공개 프롬프트 제외도 확인한다. 모델 문장의 창작 품질이나 실제 클라이언트 수신 확인은 검증하지 않는다. 실패·기한 초과·정상 종료에서 플레이어/LLM/router/기억 모드를 복원하며, 실행 결과는 전체 방 통합 기록에만 기록한다.
