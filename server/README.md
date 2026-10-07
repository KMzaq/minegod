# MythicTRPG + Local AI 통합 테스트 서버

> 2026-09-27 **HanesTest 실험본 배포:** 게임 `mythictrpg-1.0.16.jar`는 유지하고 콘텐츠 `mythaiaicontent-0.1.2.jar`, 서버 전용 AI `mythai_ai_response-0.1.19.jar`를 설치했다. 페르소나 하네스 개선과 Minecraft 공용 기본 지식 17개를 포함한다. 이번 배포 후 서버를 부팅하거나 실제 LLM 대화를 확인하지는 않았다. 해시·백업·검증 범위는 [HanesTest 전용 인수인계](../인수인계/브랜치실험/HanesTest/PROJECT_HANDOFF.md)의 배포 기록을 확인한다. 공용 인수인계의 이전 설치 목록과 구분하며, 클라이언트 필수 파일은 변경하지 않았다.

Minecraft 1.21.1 / NeoForge 21.1.248 전용 서버다. RisuAI를 사용하지 않고 같은 PC의 Ollama `gemma4:12b`를 호출한다.

## 설치된 서버 모드

- `mods/mythictrpg-1.0.16.jar`: 게임 상태, 신 관계/사건 시스템, 대화방·Dialogue HUD/Network와 게임 권한 검증
- `mods/mythaiaicontent-0.1.2.jar`: 신 프로필, 지식, 예시·퀘스트와 청중별 공개 정책, 공개 기본 지식
- `mods/mythai_ai_response-0.1.19.jar`: 콘텐츠·청취 기억, Ollama 호출, 다중 신 반응/Story 응답 연결, 페르소나 하네스·공용 지식 조회

이전 결합형 `mythictrpg` JAR을 동시에 넣으면 안 된다.

## 클라이언트 설치

클라이언트도 Minecraft 1.21.1 / NeoForge 21.1.248을 사용한다. 우측 AI UI와 Network 호환을 위해 다음 파일을 클라이언트 `mods` 폴더에 넣는다.

```text
client-required-mods/mythictrpg-1.0.16.jar
```

클라이언트에 이전 `mythictrpg-*.jar`가 있다면 새 파일 하나만 남긴다. AI 응답 모드와 콘텐츠 모드는 서버 전용이며 클라이언트에 설치하지 않는다.

## 실행

1. Ollama를 실행한다.
2. `ollama list`에서 `gemma4:12b`를 확인한다.
3. `start-neoforge-ai-server.bat`을 실행한다.
4. 콘솔에 `Done`이 나타나면 `localhost:25565`로 접속한다.

## AI UI

화면 우측에 다음 정보가 표시된다.

- AI 대화 `ON` / `OFF`
- 현재 대화의 primary God 표시 이름
- 플레이어가 신의 정체를 모르면 `????`

기본 `G` 키로 ON/OFF를 전환한다. 키는 Minecraft 키 설정에서 변경할 수 있다. 서버가 최종 상태를 판정하고 클라이언트 UI에 다시 전송한다.

OFF에서는 일반 채팅이 AI 세션으로 전달되지 않는다. ON으로 다시 전환할 때 현재 신이 남아 있으면 해당 신과 새 AI 세션을 시작한다.

## 첫 production AI 호출 테스트

현재 production gameplay binding은 데메테르의 성숙한 밀 수확이다.

1. 성숙한 밀을 수확한다.
2. MythicTRPG가 `mythictrpg:demeter_harvest`를 생성한다.
3. 서버가 데메테르를 선택하고 AI 응답 모드를 비동기로 호출한다.
4. 응답을 검증한 뒤 Dialogue HUD에 출력한다.
5. 이후 ON 상태에서 일반 채팅으로 대화를 이어간다.

처음 만났고 정체를 식별하지 않았다면 우측 이름은 `????`가 정상이다. 테스트 중 이름을 공개하려면 OP 권한으로 다음 명령을 사용한다.

```mcfunction
/mythadmin identify @s mythictrpg:demeter
```

## 명시적 대화 테스트

관리자가 직접 Interaction을 시작해 자유 채팅 연결과 UI만 먼저 확인할 수도 있다.

```mcfunction
/mythadmin interaction god @s mythictrpg:fortuna 포르투나가 모습을 드러냈다.
```

이 명령의 첫 문장은 관리자가 입력한 scripted content다. 그 뒤 일반 채팅은 AI 응답 모드와 Ollama로 전달된다.

`!`로 시작한 메시지는 AI가 소비하지 않고 일반 공개 채팅으로 남는다.

## 현재 안전 경계

- AI는 대사와 Proposal 데이터만 생성한다.
- AI는 대사와 등록된 Proposal을 제안한다. 신 선택, encounter, identification, 관계·퀘스트·보상·아이템·가호의 실제 변경은 MythicTRPG가 검증하고 수행한다.
- Hook/관계 제안도 플레이어 확인 및 서버 재검증 전에는 게임 상태에 적용되지 않는다. 상세 대화방·관계·사건 권한은 [재통합 구현 기록](../docs/ORIGINAL_GOALS_REINTEGRATION_20260923.md)을 따른다.
- AI 장애나 timeout은 Minecraft server thread를 막지 않으며 interaction을 안전하게 실패시킨다.

## 로그와 복구

- 서버 로그: `logs/latest.log`
- 대화 로그: `world/mythictrpg-ai-test-logs/<플레이어 UUID>/`
- 교체 전 JAR 백업: `backups/mods-before-ai-provider-2026-08-25/`
