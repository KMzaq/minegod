# 기록 v2 — M2 중앙 대화방 수집

2026-09-29 · `HanesTest`. [05 M2 계획](../../../추가개발/05_AI_장기기억_다중신_대화_시스템/08_단계별_구현계획.md)의 **중앙 방 채널부터 연결한 부분 구현**이다. 모든 채널/검색/M3~M7 완료가 아니다.

## 실제 경로와 호환

`ConversationRooms.publish → RoomDialoguePublisher(actual dispatch) → RecordingRuntime.captureRoom → RoomRecordingCapture → GameRecordingPort.capture/recordDeliveries`.

게임 capture는 선택적 AI publication observer보다 먼저 호출된다. AI 모델 성공·AI callback·개인 memory bind를 저장 조건으로 쓰지 않는다. 실제 수락된 플레이어 입력은 나중에 생성이 실패해도 보존된다. 초기 신 대사·동의 발언·Story의 `publishStoryPresentation`도 중앙 발행을 사용하면 같은 경로에 들어온다. 모델 초안/프롬프트/게임 보상 알림을 추가 수집하지 않는다.

기존 event/Delivery/TurnLease/RawMessage 생성자는 호환 유지한다. 옛 Delivery의 chat flag·HUD count로 정확한 view나 성공 page를 만들지 않는다. 확인 가능한 원문/신 청취는 보존하고 `EXACT_DELIVERY_VIEW_UNAVAILABLE` gap을 남긴다. 옛 TurnLease와 event의 turn sequence 0은 UNKNOWN이며 turn이 있으면 v2 capture에서 거절한다. turn이 없는 초기/동의 발언은 가짜 turn 없이 저장할 수 있다.

새 TurnLease sequence는 게임의 room UUID별 수락 순서다. 같은 room의 새 입력마다 checked increment하며 join/정책 revision 변경으로 초기화하지 않는다. room 파괴·새 UUID의 병합/분리·새 runtime 원장에서만 새 순서를 시작한다. 기존 lease 현재성 검증은 sequence까지 포함한다. 시각이나 UUID에서 순서를 추정하지 않는다.

## 원문·실제 전달·권한

- 논리 message UUID/room UUID/long revision/turn UUID·sequence와 수락 원문을 그대로 저장한다. 참가자 수에 따라 원문을 복제하지 않는다. 원문 whitespace/Unicode를 프롬프트 예산으로 자르지 않는다.
- chat은 실제 넘긴 Component의 서버측 plain-text view(방/이름 장식 포함)를 보존한다. HUD는 lossless literal page plan, 실제 성공 index, 전송용 message UUID를 기록한다. 일부 page 실패 뒤 다른 page가 성공해도 `{0,2}`를 `{0,1}`로 바꾸지 않는다.
- 서버 dispatch이며 클라이언트 열람 ACK가 아니다. 서버 측 번역 Component의 plain string을 실제 클라이언트 로케일 화면과 동일하다고 주장하지 않는다.
- 신은 기존 게임 `heardGodIds`에 든 경우에만 GAME_HEARD receipt를 갖는다. 단순 참가/공개 채팅이 모든 신의 지식이 되지 않는다. receipt 저장만으로 `KnowledgeReceipt`/NPC 검색 권한을 자동 발급하지 않는다.
- 발행 시점 참가자·정확한 완전 수신 청중·기록 정책·portable evidence/sourceMessageIds와 transport 참조를 RAW와 같은 transaction의 `message_contexts`에 보존한다. payload는 근거 pointer이며 현재 유효한 사실 허가가 아니다. 원래 tick/dayTime/차원은 event가 제공하지 않으므로 명시 UNKNOWN이며 현재값으로 채우지 않는다.
- producer는 실제 world/dataset/runtime epoch에 묶이고 capture 전에 게임 스레드에서 room/revision을 검사한다. queued snapshot은 새 방의 mutable 상태를 다시 읽지 않는다. OFF는 callback/본문/새 진단 영속화를 하지 않으며 off→on 과거 RAM history를 수입하지 않는다.

256개를 넘는 receipt는 한 RAW 이후 256개 단위의 stable batch ID로 저장한다. 전체 4,096 receipt/16MiB view staging 안전 예산을 초과하면 명시 gap이며 무제한 임시파일로 우회하지 않는다. 후속 batch 실패는 RAW 성공과 receipt 부분 저장을 구분한다. COMPLETE는 모든 계획된 receipt가 durable 성공한 경우만이다. 같은 occurrence 재시도는 중복, 같은 문장의 새 occurrence는 새 발화다.

FULL/STARTING/adapter 오류는 bounded 내용 없는 누락 집계와 관리자 알림으로 보고하며, 대화·보상·일반 게임 저장을 멈추지 않는다.

## 저장 schema와 검증

config/manifest 계약은 schema2이며 DB schema만 3으로 상승했다. 검증한 schema2 DB는 identity와 integrity 확인 후 예약된 startup transaction에서 `message_contexts`를 추가하고 meta/user_version을 함께 3으로 올린다. 과거 행에 가짜 metadata를 채우거나 시험 자료를 import하지 않는다. 알 수 없는 schema는 기존 fail-closed다.

새 `roomRecordingCaptureTest`의 main은 `com.sande.mythictrpg.recording.server.RoomRecordingCaptureTest`다. 실제 SQLite와 실제 RoomDialoguePublisher를 사용해 AI observer 실패, 긴 원문, 부분 HUD, 실패 수신자/미청취 신 제외, occurrence 중복, OFF/stale/foreign world/unknown sequence, 300관전자 batch, 재시작, schema2→3을 검사한다. 테스트 경로는 build 하위만 허용한다. 루트 순차 검증에서 **30 checks 통과**, `recordingStoreTest` **62 assertions 통과**를 확인했다. 실제 일반 클라이언트/모델 시험과 구분한다.

## 남은 범위 — 완료로 세지 않음

일반 공개/`!`/`msg`/scoreboard team/FTB Teams 후속은 [M2 채널 수집](RECORDING_V2_M2_CHANNELS.md)에서 별도 구현·검증 상태를 관리한다. 중앙 방을 거치지 않는 작성형 출력과 미등록 외부 채널은 여전히 완료로 세지 않는다. 모든 `sendSystemMessage`를 잡는 방법은 쓰지 않는다.

기존 참가자 TXT·room heard-memory·개인 journal의 실제 소비/후처리는 보존돼 있다. 현재 RECORD_ONLY는 새 archive 병행 capture이며 이 legacy 기록을 모두 quota 관리 전환/중복 writer 정리한 상태가 아니다. 운영 활성화 전에 M3~M5에서 mode별 writer/read route와 관리 파일 합산을 완성해야 한다. 기존 archive는 기본 OFF다. 서버 설정·운영 데이터·모델·배포는 변경하지 않았다.
