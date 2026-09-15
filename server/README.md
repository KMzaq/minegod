# MythicTRPG + Local AI 통합 테스트 서버

Minecraft 1.21.1 / NeoForge 21.1.248 전용 서버다. RisuAI를 사용하지 않고 같은 PC의 Ollama `gemma4:12b`를 호출한다.

## 설치된 서버 모드

- `mods/mythictrpg-1.0.0.jar`: 게임 상태, Interaction, Dialogue HUD/Network, AI 계약과 우측 상태 UI
- `mods/mythaiaicontent-0.1.0.jar`: 신 프로필, 지식, 분류 예시 등 정적 콘텐츠
- `mods/mythai_ai_response-0.1.0.jar`: 콘텐츠 검색, Ollama 호출, 응답 생성과 대화 세션

이전 결합형 `mythictrpg` JAR을 동시에 넣으면 안 된다.

## 클라이언트 설치

클라이언트도 Minecraft 1.21.1 / NeoForge 21.1.248을 사용한다. 우측 AI UI와 Network 호환을 위해 다음 파일을 클라이언트 `mods` 폴더에 넣는다.

```text
client-required-mods/mythictrpg-1.0.0.jar
```

클라이언트에 이전 `mythictrpg-1.0.0.jar`가 있다면 새 파일로 교체한다. AI 응답 모드는 서버 전용이며 클라이언트에 설치하지 않는다.

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
- 신 선택, encounter, identification, HUD, 실제 관계·퀘스트·보상·아이템·가호 변경은 MythicTRPG가 소유한다.
- 퀘스트·아이템·가호 validator/executor는 아직 없으므로 실제 게임 상태에는 적용되지 않는다.
- AI 장애나 timeout은 Minecraft server thread를 막지 않으며 interaction을 안전하게 실패시킨다.

## 로그와 복구

- 서버 로그: `logs/latest.log`
- 대화 로그: `world/mythictrpg-ai-test-logs/<플레이어 UUID>/`
- 교체 전 JAR 백업: `backups/mods-before-ai-provider-2026-08-25/`

