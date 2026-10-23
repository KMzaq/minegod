# 대화 저장 직후 재조회 순서 보장

2026-10-20 / `behaivior` / 게임 개발1.0.34, AI 개발0.1.46. 기능 수정 및 검증 상태는 [브랜치 인수인계](PROJECT_HANDOFF.md)와 [검증 기록](../../../검토결과/native-recall-fix-20261020/REVIEW.md)을 따른다. 운영 활성화·배포·모델 품질 완료 선언이 아니다.

## 원인

게임의 대화 발행/화면 전달과 SQLite 비동기 저장 완료는 서로 다른 시점이다. 기존 `RecordedMemoryAccess.open`은 호출 순간의 committed highWatermark를 고정한다. 발행 직후 저장이 밀려 있으면 해당 NPC 발화가 그 조회에 포함되지 않는다. 빠른 재시도 역시 커밋 전에 끝날 수 있다.

실패 fixture의 원문·영수증·Native 근거를 보존된 SQLite에서 다시 읽으면 조회와 근거 연결은 정상이다. 따라서 source/청중/콘텐츠 소유자 검증을 완화할 문제가 아니라 읽기 발급 순서를 바로잡을 문제다.

## 추가 계약

- `RecordingRuntime`은 게임 발행 이벤트의 **전체 capture 완료**를 추적한다. 최초 RAW 커밋만 아니라 뒤이어 제출되는 모든 delivery batch 완료를 포함한다. 저장 포맷·원문·해석 정책·보관 용량은 바꾸지 않는다.
- `RecordedMemoryAccess.openAfterCapture(server, request)`는 게임 스레드에서 호출한다. 현재 모드·게임 발급 요청·runtime·engine을 확인하고, 이미 접수되어 진행 중인 월드 대화 capture의 스냅샷을 최대5초 동안 비동기로 기다린다. 게임 tick을 막지 않는다.
- 이후 게임 스레드로 돌아와 같은 모드/engine/runtime 및 현재 요청의 방·revision·턴·화자·청중을 다시 검증한 뒤 읽기 Session을 발급한다. **highWatermark는 이 시점에 고정**한다. 발급된 Session의 기존 고정 스냅샷,60초 lease와 조회/근거 예산을 늘리거나 재사용하지 않는다.
- 기다리는 동안 추가된 대화는 해당 대기를 연장하지 않는다. 방 이동 후 이전 방의 허용된 대화도 찾을 수 있도록 완료 순서는 월드 단위로 맞추지만, 데이터/권한은 공유하지 않는다. 실제 검색의 청취·공개 범위와 source/콘텐츠 소유자 검증은 그대로다.
- 진행 중 capture 실패/불완전 기록, 시간 초과, 취소, 종료, 권한 변경에는 결과를 내주지 않는다. 부분 전달 자체가 정확히 기록된 경우에는 기존 청취/공개 검사가 열람 여부를 판정한다. 최대4096개의 진행 중 capture를 추적하며 초과분이 미완료인 동안 새 대기를 실패 처리한다. 이미 완료된 실패는 기존 누락 진단에 남고 영구적인 조회 장애를 만들지 않는다.
- 읽기의 취소/시간 초과는 원문 저장을 취소하지 않는다. 늦은 완료가 만료된 조회 권한을 되살리지 않는다. 빈 결과는 과거 사건의 부재나 전체 기록의 완전성을 의미하지 않는다.

## 소비자와 호환

`RecordedForegroundMemory.prepare`와 `prepareInterpretations`의 명시적 foreground 준비 경로가 새 비동기 발급 API를 사용한다. 전체 준비30초 제한과 동일 요청의 seal/payload/publication 검증은 유지한다. typed 실패를 RAW-only 또는 legacy로 자동 대체하지 않는다.

기존 동기 `open`은 즉시 committed snapshot을 원하는 진단 및 기존 proof 검증용으로 유지한다. 이미 발급된 proof의 재검증이 새 대화를 기다리거나 새 자료를 검색하도록 바꾸지 않는다. SHADOW 진단·Watch/Rumor·projection/embedding 백그라운드 작업을 새 대기에 포함하지 않는다.

새 API가 있는 게임1.0.34를 AI0.1.46의 기본 컴파일 입력과 최소 런타임 의존 버전으로 지정했다. 이전 게임 JAR과 새 AI JAR을 섞지 않는다. 콘텐츠 모듈 계약·버전은 변경하지 않았다.

`NEW` 자동 전환 차단, 기존 production 대화 경로, `gemma4:12b`, 목적 추출/대사 자동 활성화 설정은 유지한다. 이 수정은 아직 연결하지 않은 전체 기억 전환이나 일반 한국어 이해 문제를 해결했다고 주장하지 않는다.

## 회귀 검증

- `CaptureReadFenceTest`: 지연 RAW/후속 delivery, 대기 스냅샷, 월드 격리, 실패/과부하/종료, game dispatch 후 현재성, 취소/timeout 검사. 기본 `check`에 포함한다.
- `RecordedRoomAuthorityGameTests`: 실제 발행 뒤 새 API로 읽고, 위조 요청 거절 및 대기 중 실제 턴 교체 후 권한 미발급을 검사한다.
- `NativeContentEvidenceGameTests`: 임의8회 재시도를 제거하고 새 기능 API를 한 번 호출한다. 실제 콘텐츠 소유자·발행·저장·Native seal·후속 NPC 발화 재조회와 공개 범위 거절 기대값은 유지한다. 고정된 모의 발화이며 실제 모델 생성 시험이 아니다.
- 선택적 `nativeRecallReplay -PnativeRecallDatabase=<build 내부 보존 DB>`는 SQLite를 읽기 전용으로 검사한다. live 소유자 허가나 실제 모델 시험을 대신하지 않는다.

정확한 실행별 성공/실패·전체 범위·미검증 항목은 검증 기록에 남긴다. 과거 실패 로그는 보존한다.
