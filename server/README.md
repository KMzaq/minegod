# MythicTRPG + Local AI 통합 테스트 서버

> **2026-10-22 구버전 시험 콘텐츠 분리:** 기존 페르소나·지식·인물·Story·샘플 퀘스트와 시험 데이터팩을 [구버전](../구버전/20261022-테스트콘텐츠/README.md)으로 보관하고 빈 카탈로그 상태의 새 JAR을 배포했다. 버전명은 같지만 아래 최초 배포 해시와 다르다. [현재 검증·해시](../인수인계/브랜치실험/behaivior/CONTENT_ARCHIVE_20261022.md)를 따른다. 현재 기본 등록 인물이 없으므로 아래 데메테르/포르투나 대화 예시는 과거 시험 절차다. 새 기획을 등록한 뒤 해당 God ID로 사용한다. 운영 서버 부팅과 실제 런처 변경은 하지 않았다.

> **2026-10-22 사용자 승인 최신본 테스트 배포:** 게임 **1.0.36**, AI 응답 **0.1.50**, 콘텐츠 **0.1.3**을 설치하고 `client-required-mods`도 게임 **1.0.36**으로 맞췄다. 세 모듈 build/check와 JAR 소유권/중복 검사는 통과했지만, 알려진 대사 품질 문제를 감수하는 사용자 시험 배포이며 전체 품질 합격이 아니다. 기존 월드·설정·모드 575개 파일을 `backups/test-latest-20261022-015346-541/`에 보존했다. **서버는 부팅하지 않았고 실제 런처도 변경하지 않았다.** [검증·백업·배포 상태](../인수인계/브랜치실험/behaivior/TEST_DEPLOYMENT_20261022.md)를 참고한다. 아래 9~10월의 이전 배포 배너는 이력이다.

> **2026-10-13 레이드 파일 배포 완료:** 서버·`client-required-mods`의 게임을 `mythictrpg-1.0.28.jar`로 교체했다. 기존 서버 전용 AI `0.1.19`·콘텐츠 `0.1.2`와 FTB/Architectury는 유지했다. 게임 protocol 10이므로 실제 클라이언트도 반드시 새 게임 JAR로 교체한다. 월드·설정·모드 557개 파일을 `backups/raid-trial-20261013-224022-732/`에 백업하고 보존 파일 해시를 확인했다. **이번 배포에서 서버는 부팅하지 않았고 실제 런처 파일도 변경하지 않았다.** [레이드 테스트 방법](../mythictrpg-main/docs/raid-trial-guide.md)과 [검증·배포 기록](../인수인계/브랜치실험/HanesTest/RAID_TRIAL_20261013.md)을 따른다. 아래 9월 배너는 당시 기록이다.

> 2026-09-27 **HanesTest 실험본 배포:** 게임 `mythictrpg-1.0.16.jar`는 유지하고 콘텐츠 `mythaiaicontent-0.1.2.jar`, 서버 전용 AI `mythai_ai_response-0.1.19.jar`를 설치했다. 페르소나 하네스 개선과 Minecraft 공용 기본 지식 17개를 포함한다. 이번 배포 후 서버를 부팅하거나 실제 LLM 대화를 확인하지는 않았다. 해시·백업·검증 범위는 [HanesTest 전용 인수인계](../인수인계/브랜치실험/HanesTest/PROJECT_HANDOFF.md)의 배포 기록을 확인한다. 공용 인수인계의 이전 설치 목록과 구분하며, 클라이언트 필수 파일은 변경하지 않았다.

Minecraft 1.21.1 / NeoForge 21.1.248 전용 서버다. RisuAI를 사용하지 않고 같은 PC의 Ollama `gemma4:12b`를 호출한다.

## 설치된 서버 모드

- `mods/mythictrpg-1.0.36.jar`: 게임 상태·대화방·권한 검증, 퀘스트·레이드·가호·스킬 등 현재 개발 기능
- `mods/mythaiaicontent-0.1.3.jar`: 신 프로필, 지식, 예시·퀘스트와 청중별 공개 정책, 공개 기본 지식
- `mods/mythai_ai_response-0.1.50.jar`: 콘텐츠·허용 기억 조회, Ollama 호출, 대화방/Story 및 현재 개발 대화 파이프라인

기존 설정·데이터팩을 유지했다. 콘텐츠 정의 또는 명시적 opt-in이 필요한 기능은 JAR 교체만으로 활성화되지 않는다.

이전 결합형 `mythictrpg` JAR을 동시에 넣으면 안 된다.

## 클라이언트 설치

클라이언트도 Minecraft 1.21.1 / NeoForge 21.1.248을 사용한다. 우측 AI UI와 Network 호환을 위해 다음 파일을 클라이언트 `mods` 폴더에 넣는다.

```text
client-required-mods/mythictrpg-1.0.36.jar
```

클라이언트에 이전 `mythictrpg-*.jar`가 있다면 새 파일 하나만 남긴다. AI 응답 모드와 콘텐츠 모드는 서버 전용이며 클라이언트에 설치하지 않는다.

FTB/Architectury 4종도 [클라이언트 필수 폴더](client-required-mods/README.md)의 서버와 같은 파일을 사용한다. 레이드만 시험하는 데에는 AI 모델 호출이 필요하지 않다. 서버 부팅 후 OP가 `/mythraid trial prepare`, 필요시 빈 인벤토리 9칸을 확보하고 `/mythraid trial kit`, 리더가 `/mythraid trial create`를 실행한다. 모집 UUID로 `/mythraid start <UUID>`를 실행하면 입장한다. 장비는 직접 착용하고 `/gamemode survival`에서 시험한다. 여러 명이면 start 전에 `/mythraid join <UUID>`로 참가한다.

## 실행

1. Ollama를 실행한다.
2. `ollama list`에서 `gemma4:12b`를 확인한다.
3. `start-neoforge-ai-server.bat`을 실행한다.
4. 콘솔에 `Done`이 나타나면 `localhost:25565`로 접속한다.

## AI UI

화면 우측에는 참여 중인 대화방과 신 정보가 표시된다. 플레이어가 신의 정체를 모르면 `????`로 표시된다.

기본 `G` 키는 **우측 UI 표시만** 전환한다. 대화 자체를 ON/OFF하는 키가 아니다. 방 선택과 공개/비밀 채팅 사용법은 [대화방 가이드](../docs/CONVERSATION_ROOMS.md)를 참고한다.

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

대화·기록 시험은 OP가 다음 명령을 사용한다. `on/off`는 영속 기록 여부이며 채팅 표시나 현재 방의 문맥 사용 여부가 아니다.

```mcfunction
/ai_call private on mythictrpg:demeter
/s 안녕하세요
```

여러 신은 전체 ID를 공백으로 나열한다. 공개 고정방은 `public`, 공개 이동방은 `mobile`로 시작하고 일반 채팅을 사용한다. 비밀방은 `/s`를 사용한다. `/mythroom list`로 방 코드를 확인하고 `/mythroom leave <방코드>`로 퇴장한다. [전체 명령 안내](../docs/AI_DIALOGUE_TEST_COMMAND.md)를 참고한다.

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
