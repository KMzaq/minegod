# 기록 v2 — M2 실제 채널 전달 수집

2026-09-29 · `HanesTest`. [중앙 방 수집](RECORDING_V2_M2_ROOM_CAPTURE.md)의 후속이며, 05 [M0-01 채널 계약](../../../추가개발/05_AI_장기기억_다중신_대화_시스템/M0_기준확정/01_수집채널과_기존계약_대응.md)에 따른 게임 소유 adapter다. 운영 ON·legacy import·전체 장기기억 완성 기록이 아니다.

## 실제 연결

`명시된 채널 producer → ChannelCaptureHooks.Scope → 실제 Connection.send의 PacketSendListener 성공 → 불변 occurrence/recipient view → 게임 스레드 epoch 재검증 → ChannelRecordingCapture → 기존 WorldRecordingService`.

| 채널 | 발생 지점 | 제외 |
|---|---|---|
| 일반 공개, `!` | 최종 `PlayerList.broadcastChatMessage`의 실제 player overload | 취소된 ServerChatEvent 자체, 알 수 없는 chat type |
| `msg`/`tell`/`w` | 해석 완료 `MsgCommand.sendMessage` | 대상 없음·명령 실패, 전체 명령 문자열 |
| `teammsg`/`tm` | `TeamMsgCommand.sendMessage` | 다른 scoreboard 팀, FTB 팀을 같은 팀으로 추정 |
| 플레이어 `say`/`me` | player-origin CommandSourceStack + 최종 broadcast chat type | 콘솔·명령 블록·`execute as`로 바꾼 가짜 플레이어 발신자 |
| FTB GUI | `SendMessageMessage`의 **큐 실행 후** 실제 team send | GUI handler가 큐에 넣었다는 사실만으로 receipt 발급 |
| FTB 채팅 전환 | 등록된 `chatReceived`의 accepted redirect send | 일반 팀 자동 공지 |
| FTB 명령 | `FTBTeamsCommands`의 accepted `ftbteams msg` send + 실제 inbound command identity | API/자동 호출로 만든 source, 팀 관리 알림 |

명령 origin hook는 서버가 처리 중인 플레이어 객체만 짧게 보관한다. 원시 명령, 비밀번호, 임의 인수·관리 명령 이력을 수집하지 않는다. 비동기 signed-message 해석은 기존 decoder가 발급한 sender와 실제 player source identity를 확인한다.

FTB는 현재 팀 객체, manager 서버, 실제 온라인 작성자 객체와 sender UUID가 모두 맞을 때만 scope를 연다. 설치/개발 JAR은 `2101.1.11`, SHA-256 `E6BCD670493624E6906DF872A9C6AF4A33FE6405CAC57A32C59CD4CD5838C655`로 같다. 자동 승급·강등·초대 알림도 플레이어 UUID를 사용하므로 `AbstractTeam.sendMessage` 전체를 수집하지 않는다.

`mythictrpg.recording-channels.mixins.json`은 기존 필수 액션 Mixin과 분리된 optional 설정이다. plugin이 실제 대상 signature와 FTB 정확 버전을 검사하며 미지원이면 해당 adapter를 끄고 내용 없는 coverage 경고를 남긴다. 원래 채팅/팀 기능은 그대로 실행한다. 적용 로그와 실제 실행 검증 없이 coverage를 성공으로 보지 않는다.

## 원문·receipt·중복·비공개

- 발화 ID는 등록 producer의 runtime epoch + 단조 acceptance counter에서 발급한다. 텍스트 hash로 같은 말을 합치지 않는다. 같은 occurrence의 retry만 idempotent다.
- 한 발화의 귓속말 발신자 echo와 여러 실제 대상, FTB 채팅/UI 두 표면을 **RAW 한 건**에 연결한다. 동일 수신자·surface의 여러 echo는 하나의 receipt에 실제 순서 있는 parts로 보존한다.
- signed original과 `!` 제거/장식/부분 필터 view를 구분한다. 숨김·완전 필터는 해당 패킷이 생성되지 않아 receipt가 없다. 전송 예외·실패·취소 future도 성공 receipt가 아니다.
- 정확한 서버측 plain view이며 클라이언트 로케일·차단 설정·화면 열람 ACK를 주장하지 않는다. 클라이언트가 서버 성공 패킷을 나중에 숨기는 것은 서버에서 확인할 수 없다.
- 모든 채널의 저장 청중은 실제 전달 수신자로 한정한다. PUBLIC이라는 이유로 모든 신에게 GAME_HEARD/KnowledgeReceipt를 만들지 않는다. 필터된 view만 받은 플레이어는 full-original audience에 추가하지 않는다.
- 중앙 방의 chat/HUD는 이 producer scope를 열지 않으므로 하위 전송에서 재수집되지 않는다. 기존 AI memory/social 후처리와 보상/퀘스트 실행을 다시 호출하지 않는다.
- 과거 FTB `SyncMessageHistoryMessage`는 새 RAW가 아니다. 구 history를 새 dataset으로 수입하거나 현재 팀 전체에게 과거 비밀의 청취 권한을 새로 만들지 않는다. 이미 매핑된 새 발화의 **나중 history-sync receipt 추가**는 아직 미지원이다.

최종 접수된 occurrence는 실제 성공 dispatch가 없어도 RAW를 보존한다. 전송이 모두 실패했거나 모두 숨김인 입력의 receipt/허용 청중은 비어 있으며, 입력 접수 자체를 청취 근거로 승격하지 않는다. 명시된 producer 밖 작성형 Story/NPC/퀘스트 안내 및 미등록 외부 모드는 미지원이며 blanket system-message 수집으로 채우지 않는다.

## bounded 비동기와 OFF

채널 한 발생은 최대 256개 전송 계획, 본문/view 각각 131,072 UTF-16 단위, pending 전체 64 scope/16MiB 보수적 문자 비용으로 제한된다. 한도 초과는 명시 gap이고 임의 절단/무제한 fallback 파일을 만들지 않는다. 이 채널 adapter는 중앙 방의 4,096-receipt batch와 별도 예산이다.

각 실제 write의 성공 callback만 receipt 후보를 확정한다. 최대 5초 동안 확인하고 나머지는 `CHANNEL_DISPATCH_CONFIRMATION_TIMEOUT`으로 남긴다. 성공한 일부 recipient는 보존할 수 있지만 미확인·실패 recipient를 성공으로 채우지 않는다. snapshot에는 받아 둔 원문/수신 UUID/view만 있고 worker가 새 Minecraft 청중을 읽지 않는다. 적용은 게임 스레드에서 같은 server/runtime adapter인지 다시 확인한다. old runtime이 새 world/session으로 넘어가지 않는다.

OFF에는 archive adapter·pending body·DB/manifest/새 world identity를 만들지 않는다. 모드의 기존 채팅 표시와 FTB history writer 의미는 바꾸지 않았다. RECORD_ONLY의 새 archive만 M1 합산 quota를 사용하며 legacy 참가자 TXT/AI journal/FTB 자체 history의 전체 mode별 writer·quota 전환은 M3~M5 후속이다.

## 검증 상태

- `channelRecordingCaptureTest`: 실제 SQLite 원문/echo/필터 view/FTB 두 surface, occurrence retry/동일 문장 새 발화, 세계/epoch 격리, bounded 실패, OFF/실제 reopen fixture.
- `ChannelCaptureGameTests`, namespace `mythictrpg_recording_channels`: `build/recording-channels-20260929`의 명시 RECORD_ONLY fixture에서 실제 NeoForge mock-network, vanilla 명령/필터/숨김, failed/cancelled Netty writes, 설치된 FTB GUI·redirect·명령·자동 알림/history 제외와 durable SQLite receipt를 검증하도록 작성했다.
- 초기 `CONVERSATION_CLOSED`, 개발 runtime SQLite 누락, Architectury payload wrapper 인식 문제를 수정했다. 오프라인 **23 checks**, 최신 `build/recording-accepted-empty-20260929` 실제 채널 GameTest **1/1 통과**(13 RAW, 모두 숨김일 때 receipt 0 포함). [수정 이유와 검증 기록](RECORDING_V2_M2_CHANNEL_CAPTURE.md)을 따른다. 실제 FTB `2101.1.11`과 Architectury `13.0.11` 및 시그니처를 확인하여 미지원 조합은 해당 수집만 중단한다.

운영 서버/월드/설정/배포·실제 모델 호출은 하지 않았다. M2 채널 코드나 저장 시험을 M3 지식 권한·M4 파생·M5 검색·M6 다중 신·M7 운영 수용 완료로 보고하지 않는다.
