# 대화방과 기록·기억 연결 보완

2026-09-21 / 소스·개발 JAR·오프라인 검증 완료 / 미배포.

후속 배포: 같은 날 사용자 요청으로 게임1.0.13/AI0.1.14를 서버 및 클라이언트 배포용 폴더에 적용했다. 서버는 실행하지 않았다. 아래는 구현 시점 기록이며 현재 배포 상태·백업은 [배포 결과](ROOM_RECORDING_RELEASE_20260921.md)를 우선한다.

## 범위와 변경

새 공개/비밀 병행방을 실제 소스와 인수인계 49~51절에서 다시 확인했다. 행동 원장/주시/소문의 원본은 기존 게임 저장소를 그대로 재사용한다. 이번에는 신규 장기기억 전체를 구현하지 않고, 바뀐 채팅과 기록·기억 사이의 연결 공백을 보완했다.

| 확인한 공백 | 보완 |
|---|---|
| AI 요청 기반 TXT라 초기 조우·LLM 없는 동의 입력 누락 | 게임이 확정한 `RoomDialogueEvent`와 공통 publisher를 통해 초기/일반/동의 발화 기록 |
| 수신자/HUD 복제와 한 발화의 구별 부족 | message UUID 하나와 immutable 수신 결과. 채팅 여부/HUD 성공 페이지 수, off/미전송 분리 |
| 방 병합·분리 후 기억 provenance 조회가 새 방 기준 | history sourceRoomId 유지, 원래 방 근거 조회·철회 필터, 비동기 분리 판단 최종 재검증 |
| 선택되지 않은 신이 섞인 bundle의 일부 전달 가능 | 게임에서 현재 room/revision/turn·선택 화자·본문 검증 후 전체 결과 승인 |
| 이전 M0가 단일 세션·초기 1인2신만을 기준으로 설명 | 기존 병행방/64플레이어·16신 참가 보존, off 예외와 단계별 연결점 문서 반영 |

통합 스킬의 게임 authoritative 경계를 유지했다. AI는 대사/제안을 만들고 실제 전달 snapshot은 게임이 발급한다. 기록 실패를 게임 성공/보상 롤백으로 바꾸지 않는다. 릴리스 스킬에 따라 개발 빌드와 운영 배포를 분리했다.

## 파일과 호환

- 게임: `RoomDialogueEvent`, `RoomDialoguePublisher`, `RoomConversationEngine`, `ConversationRooms`, publisher 단위 테스트 및 Gradle task.
- AI: `RoomHistorySources`, `MythAiRoomConversationEngine`, `DialogueMemoryBridge`, `RoomDialogueLog`, 실제 방 엔진/기록 통합 테스트.
- AI의 respond/delivered에서 읽기용 참가자 TXT를 중복 저장하지 않는다. 모델 단계 진단과 기존 개인 journal은 별도 역할로 유지한다. 콘텐츠 로드 전에 허용된 입력의 기존 기억 처리부터 진행한다.
- `HistoryLine`의 기존 4인자 생성자는 유지하고 `sourceRoomId`를 추가했다. 새 publication callback은 default no-op이다. 새로운 API를 사용하는 AI 0.1.14는 최소 게임 1.0.13을 요구한다.
- legacy `mine/mine` 소스/JAR이나 생성 오버레이 결과만을 수정하지 않았다. 기존 오버레이 구조를 재사용한다. 게임 네트워크 protocol6은 유지했다.

개발 산출물 SHA-256:

| 파일 | SHA-256 |
|---|---|
| `mythictrpg-main/build/libs/mythictrpg-1.0.13.jar` | `DADDEB4FAF1C7788B7FE78ADBDD502DB2973F40C346BD5926A4607EA58FB6881` |
| `mythai-ai-response/build/libs/mythai_ai_response-0.1.14.jar` | `BF978F516C0497AFC68A60297A5CD248A9735B75AD8864E93A5549F10CE8D158` |

AI 빌드 API는 위 게임 JAR을 `mythai-ai-response/build/room-recording-api`에 복사해 고정했다. 기존 개발 1.0.12/0.1.13을 덮어쓰지 않았다.

## 실제 실행한 검사

두 모듈 `compileJava`/`jar` 성공. Gradle은 offline, Java21, worker1로 실행했다. 최초 wrapper cache 잠금 권한 오류 후 승인받은 권한으로 재실행했다. Minecraft나 실제 모델은 실행하지 않았다.

| 검사 | 결과 |
|---|---|
| 게임 publication: 한 발화·중복 대상·partial dispatch·off·비밀 수신자·observer 실패 | 19 PASS |
| 방 원장/실제 action Gateway/실제 퀘스트 동의 메서드/HUD 분할 | 147 + 45 + 32 + 34 PASS |
| AI 방 엔진: 프롬프트 격리·실제 history pruning/split input·기록 sink·근거 철회·긴 원문 | 90 PASS |
| 개인 기억 청중/실제 recording bridge | 46 + 20 PASS |
| 기존 생성 메서드와 로그 가드 | 87 + 37 PASS |
| 경험 대화/기억 journal | 37 + 656 PASS |

합계 **게임 277 + AI 973 = 1,250 checks PASS**. fixture의 월드/FTB 일부는 stub이며 실제 보상·월드 저장·다인 접속 검증을 대신하지 않는다. 테스트 임시 기록은 모듈 build 폴더에만 생성했다.

패키징 검사: 두 개발 JAR의 중복 엔트리 0, 새 시험 fixture 포함 0, 게임↔AI split-package 0을 확인했다. NeoForge 실제 부팅/전체 다른 모드 호환성을 보증하는 검사는 아니다.

문서 검사: 대상 Markdown 18개/로컬 링크 76개, 펜스·공백 및 관련 tracked diff 검사를 통과했다. 인수인계는 기존 51절까지의 내용을 보존하고 52절을 추가했다.

## 미완료와 다음 연결

- 새 SQLite/월드별 100GB/날짜 제한 없는 RAW·검색은 M1 이후다. TXT는 참가자별 관리 사본이며 영속 멱등 DB가 아니다. 기존 개인 기억의 길이·청중·검색 제한은 남아 있다.
- event는 서버 전송 결과이며 클라이언트 열람 확인이나 신의 지식 획득 증명이 아니다. 수신자별 표시 view/정확한 HUD 부분 전송 내용과 모든 채팅 채널 수집은 M2다. 임의의 시스템 알림/동의 안내까지 전부 RAW라고 하지 않는다.
- 구 SocialRuntime의 소문 생성/설득 회복, 게임발 퀘스트 완료/평가 자동 AI 연출은 여전히 새 방 포트로의 이전이 필요하다. 명시적 방 액션/퀘스트 제출과 게임 판정은 기존 연결을 유지한다. 이 후처리를 플레이어 단일 세션으로 우회 호출하지 않았다.
- 실제 서버 다인 공개/비밀 노출, H/G·HUD, 연결구역 병합/분리, Ollama 품질·지연·부하, NeoForge 전체 모드 부팅은 미실행이다. 배포 후 검증했다고 보고하지 않는다.

다음 구현 기준은 [M0 후속 연결](../추가개발/05_AI_장기기억_다중신_대화_시스템/M0_기준확정/04_대화방_변경_연결_20260921.md)과 [M1~M7 계획](../추가개발/05_AI_장기기억_다중신_대화_시스템/08_단계별_구현계획.md)을 따른다.

## 보존·운영 상태

변경 전 대상 파일 29개는 `dev-tools/backups/room-recording-integration-20260921`에 보존했다. 기존 M0 CSV를 갱신하지 않는다. 보호 대상은 서버 mods/client-required-mods/설정/주요 게임 SavedData, 신 프로필, legacy JAR, 이전 개발 JAR 등이며 전체 월드 백업을 뜻하지 않는다.

[보호 기준 CSV](room-recording-protected-20260921.csv)의 78개 파일은 작업 전후 SHA-256이 모두 동일했다. 사용자 미커밋 변경은 유지했다.

서버/클라이언트 배포, 실행/중지, 설정/월드/기억 초기화, 파일 삭제, 콘텐츠 변경, 모델 호출·설치, 커밋을 하지 않았다. `server/mods`는 게임1.0.2/AI0.1.3/콘텐츠0.1.0을 유지한다.
