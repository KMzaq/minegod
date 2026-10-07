# M2 일반 채널 수집 — 2026-09-29

운영 기본 OFF 유지. 새 SQLite로 기록을 수집하는 경로이지 M3~M7의 지식 권한/검색/파생/전체 전환 완료가 아니다.

## 실제 연결

`recording/channel`이 최종 허용된 바닐라 채팅, `/msg` 계열, scoreboard `/teammsg`, 플레이어 `/say`·`/me`, FTB 팀 UI·채팅 redirect·팀 message 명령을 수집한다. 단순 명령 문자열이나 콘솔의 `execute as`를 플레이어 입력으로 인정하지 않는다. 시스템 공지·FTB 과거 기록 동기화도 제외한다.

원문 1회와 실제 수신자별 표시 view를 분리한다. `/msg` 다중 대상의 작성자 echo 여러 개도 같은 RAW 아래에 보존한다. FTB 채팅창과 전용 UI는 별도 실제 surface receipt이며 같은 RAW를 공유한다. 숨김 설정·부분 필터·완전 필터의 최종 view를 사용한다.

receipt는 실제 `Connection.send`의 성공 callback을 확인한 **서버 전송 근거**다. 클라이언트가 화면을 읽었다는 ACK가 아니다. 실패·취소·5초 만료는 receipt를 만들지 않는다. 최종 접수된 입력은 수신자나 성공 전송이 없어도 RAW를 보존하고, receipt와 허용 청중은 비워 둔다. scope 최대64/16MiB, 세션 epoch/currentness 재검사를 사용한다. 일반 채널 기록이 신의 지식 receipt나 새 대화 참가자를 자동 생성하지 않는다.

FTB adapter는 실제 설치 버전 Teams `2101.1.11`, Architectury `13.0.11`과 대상 시그니처를 확인한다. 일치하지 않으면 선택적 수집을 중단하고 게임 채팅 자체는 유지한다. Architectury의 `BufCustomPacketPayload`는 정확한 FTB `SendMessageResponseMessage` 타입만, 최대262144 byte의 독립 읽기 view로 해석한다. 다른 패킷/기록 동기화를 추측해서 수집하지 않는다.

## 검증 및 수정 이유

- 오프라인 `channelRecordingCaptureTest`: 23 checks 통과.
- 실제 NeoForge/FTB/Netty callback/SQLite 결합 GameTest: 최신 `build/recording-accepted-empty-20260929`, **1/1 통과**. 13개 RAW, 바닐라 수신 격리, FTB 세 입력 경로 각각 두 플레이어×두 surface, 전송 실패/취소, 모두 채팅 숨김인 accepted 입력의 RAW 보존·receipt 0, 콘솔·시스템 제외, God receipt 0을 검사했다. 앞선 `build/recording-channels-complete-20260929`의 12개 RAW 검사도 통과했다.
- 이전 실행에서 개발 runtime에 SQLite JDBC가 없어 초기화가 실패했다. `additionalRuntimeClasspath`를 추가했고, 원래 배포 JAR의 jarJar 구성은 유지했다. 초기화 실패 stack은 서버 진단 로그에만 남긴다.
- FTB는 typed payload를 Architectury byte wrapper로 바꾸므로 처음 matcher에서 UI 전송을 놓쳤다. 실제 wrapper 해석 후 위 1/1 통과를 확인했다. 실패한 build fixture는 보존했다.

## 아직 안 한 것

- 기존 legacy writer/읽기 소비자를 제거하지 않았다. 중복 원문 보유·쿼터 원본 전환·마이그레이션 정책은 M2 cutover 후속이다.
- 패키징된 production JAR의 RECORD_ONLY 별도 부팅, 실제 클라이언트 화면/네트워크 단절, 모든 추가 채팅 모드 호환은 별도다.
- 운영 `server` 설정/월드/JAR은 바꾸지 않았다.
