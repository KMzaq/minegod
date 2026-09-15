# 다른 PC용 Mythic TRPG AI 개발 인수인계

## A. 프로젝트 한 줄 요약

현재 Minecraft NeoForge 모드는 gameplay 관찰, progression, God 선택, interaction planning과 서버 권위 검증을 담당한다. 다른 PC에서 개발하는 RisuAI/local LLM 시스템은 그 결과에 맞는 대화 내용만 생성하는 외부 provider를 담당한다.

AI는 Minecraft gameplay 규칙의 판단자나 저장소가 아니다.

## B. 정상 기능 기준점

| 항목 | 기준 |
| --- | --- |
| 기능 기준 commit | `4e20737fea42160c52c52cf8b95588f0fbf1971b` |
| 기능 기준 commit message | `Phase 4B1 Demeter wheat harvest binding` |
| 이전에 검증된 전체 GameTest | `158/158` 통과 |
| Minecraft | Java Edition `1.21.1` |
| NeoForge | `21.1.248` |
| ModDevGradle | `2.0.144` |
| Gradle Wrapper | `9.2.1` |
| 검증된 JDK | `21.0.11` |
| Mod ID | `mythictrpg` |
| Java package | `com.sande.mythictrpg` |
| 정상 시작 조건 | `git status --short` 출력 없음 |

문서 정리 이후 별도의 문서 commit을 만들면 저장소의 최신 HEAD는 위 기능 기준 commit과 달라질 수 있다. 최신 문서 HEAD와 실제 기능 검증 기준 commit은 구분해서 기록한다.

현재 production God은 `mythictrpg:demeter`, `mythictrpg:aphrodite`, `mythictrpg:lubras` 세 명이다. 실제 gameplay promotion과 interaction rule은 Demeter의 성숙한 밀 수확 경로 한 세트만 존재한다.

## C. 저장소 전달 후 첫 실행 절차

전달받은 저장소에서 먼저 현재 상태와 Git 이력을 확인한다.

```powershell
git status
git log -1
git merge-base --is-ancestor 4e20737fea42160c52c52cf8b95588f0fbf1971b HEAD
```

세 번째 명령의 종료 코드가 `0`이면 현재 HEAD가 기능 기준 commit을 포함한다. Working tree가 clean인지, 예상하지 못한 코드나 production JSON 변경이 없는지도 확인한다.

다음으로 Java 21과 Gradle 환경을 확인한다. 아래 기본 Gradle 명령은 저장소에 기록된 JDK 경로가 실제로 존재할 때 사용한다. 다른 PC의 설치 경로가 원래 PC와 다르면 아래 D 절의 세션 전용 JDK 설정을 먼저 적용하고 모든 Gradle 명령에 `-Dorg.gradle.java.home=...` override를 붙인다. 기본 `java -version`이 Java 8 등 다른 버전을 가리키는 경우에도 D 절처럼 현재 PowerShell 세션에서 Java 21을 선택한다.

```powershell
java -version
javac -version
.\gradlew.bat --version
.\gradlew.bat build --no-daemon
.\gradlew.bat runGameTestServer --no-daemon
```

클라이언트 또는 실제 서버 확인이 필요할 때만 다음을 실행한다.

```powershell
.\gradlew.bat runClient --no-daemon
.\gradlew.bat runServer --no-daemon
```

AI 개발은 기능 기준 branch를 직접 오염시키지 않고 별도 branch에서 진행한다.

```powershell
git switch -c codex/ai-integration
```

`158/158`은 원래 PC의 기능 기준 commit에서 이전에 검증된 결과다. 다른 PC에서는 직접 다시 실행한 결과를 별도로 기록해야 한다.

## D. 다른 PC의 JDK 경로 주의

현재 저장소의 `gradle.properties`에는 원래 PC의 설치 위치가 기록돼 있다.

```properties
org.gradle.java.home=C:/Program Files/Java/jdk-21.0.11
```

`run-gradle.ps1`도 같은 원래 PC 경로를 사용한다. 다른 PC에 동일한 경로가 없으면 이 스크립트를 그대로 사용하지 않는다. 저장소의 Java 소스, `gradle.properties`, `gradlew.bat` 또는 전역 Java 설정을 임의로 수정하지 않는다.

먼저 실제 JDK 21 설치 후보와 현재 명령 해석 상태를 확인한다.

```powershell
Get-Command java -ErrorAction SilentlyContinue
Get-Command javac -ErrorAction SilentlyContinue
Get-ChildItem -LiteralPath 'C:\Program Files\Java' -Directory -ErrorAction SilentlyContinue
Get-ChildItem -LiteralPath 'C:\Program Files\Eclipse Adoptium' -Directory -ErrorAction SilentlyContinue
```

확인한 실제 설치 경로를 현재 PowerShell 세션에서만 지정한다. 아래 경로는 설명용 예시이며, 반드시 해당 PC의 실제 JDK 21 설치 경로로 바꾼다.

```powershell
$projectJavaHome = 'D:\Java\jdk-21'
& "$projectJavaHome\bin\java.exe" -version
& "$projectJavaHome\bin\javac.exe" -version

$env:JAVA_HOME = $projectJavaHome
$env:Path = "$projectJavaHome\bin;$env:Path"

java -version
javac -version
.\gradlew.bat "-Dorg.gradle.java.home=$projectJavaHome" --version
.\gradlew.bat "-Dorg.gradle.java.home=$projectJavaHome" build --no-daemon
.\gradlew.bat "-Dorg.gradle.java.home=$projectJavaHome" runGameTestServer --no-daemon
```

`runClient`나 `runServer`를 실행할 때도 동일하게 task 이름 앞에 `"-Dorg.gradle.java.home=$projectJavaHome"`을 전달한다.

`JAVA_HOME`과 `Path` 변경은 현재 PowerShell 프로세스에만 적용된다. `-Dorg.gradle.java.home=...`은 저장소에 기록된 기존 경로를 해당 실행에서 override한다. 원래 PC와 동일한 JDK 경로가 존재하면 Gradle 경로 override는 생략할 수 있지만, 기본 `java`나 `JAVA_HOME`이 Java 8이면 launcher를 위해 세션 전용 `JAVA_HOME`과 `Path` 설정은 유지해야 한다.

최종적으로 `.\gradlew.bat "-Dorg.gradle.java.home=$projectJavaHome" --version`의 `Launcher JVM`과 `Daemon JVM`이 모두 Java 21을 사용하는지 확인한다. 시스템 전역 Java 8 또는 다른 프로젝트의 JDK 설정을 삭제하거나 변경하지 않는다.

## E. 현재 사용 가능한 관리자 명령

모든 `/mythadmin` 명령은 OP permission level 2 이상이 필요하다.

```text
/mythadmin gods
/mythadmin god <id>
/mythadmin appearance <player> <god>
/mythadmin interaction dry-run god <player> <god>
/mythadmin interaction god <player> <god> <text>
/mythadmin dialogue god <player> <god> <text>
```

각 명령의 역할은 다음과 같다.

- `gods`: 로드된 God ID 목록을 조회한다.
- `god <id>`: definition, category, unlock policy와 effective unlock 상태를 조회한다.
- `appearance <player> <god>`: 자동 등장 가능 여부를 read-only로 평가한다.
- `interaction dry-run god ...`: explicit God candidate planning과 provider availability만 read-only로 조회한다.
- `interaction god ... <text>`: 고정 scripted text로 실제 interaction을 시작할 수 있다. Encounter commit과 Dialogue HUD 출력이 발생할 수 있으므로 dry-run이 아니다.
- `dialogue god ... <text>`: HUD presentation을 직접 시험한다. 실제 플레이어에게 대화가 출력되므로 dry-run이 아니다.

특히 두 signal은 다르다.

```text
관리자 interaction dry-run:
mythictrpg:explicit_god_call

성숙한 밀 수확 gameplay signal:
mythictrpg:demeter_harvest
```

Production resolver가 `mythictrpg:demeter_harvest`만 처리하면 관리자 explicit dry-run은 계속 `PROVIDER_UNAVAILABLE`일 수 있다. Dry-run 성공과 gameplay provider 등록 성공을 동일하게 해석하지 않는다.

## F. 첫 gameplay 통합 시나리오

현재 production 경로는 다음과 같다.

```text
성숙한 minecraft:wheat 수확
-> mythictrpg:mature_crop_harvested
-> mythictrpg:demeter_wheat_harvest
-> GameplayActionPayload
-> mythictrpg:demeter_harvest
-> mythictrpg:demeter
-> 외부 provider
-> 서버 검증된 대화 출력
```

Production 데이터는 다음 세 파일이다.

```text
src/main/resources/data/mythictrpg/mythictrpg/gods/demeter.json
src/main/resources/data/mythictrpg/mythictrpg/gameplay_promotions/demeter_wheat_harvest.json
src/main/resources/data/mythictrpg/mythictrpg/interaction_rules/demeter_harvest.json
```

Demeter는 `mythictrpg:always` appearance condition을 가진다. Promotion은 성숙한 `minecraft:wheat`만 처리하며 당근, 감자, 비트, 네더 와트와 코코아는 일치하지 않는다.

현재 외부 provider는 없으므로 실제 실행은 다음 단계에서 멈춘다.

```text
SpontaneousInteractionSubmissionService
-> resolver.resolve(signal)
-> UNAVAILABLE
```

Demeter의 promotion attempt cooldown은 `1,200 ticks`이며 resolver unavailable이어도 소비된다. 그러나 encounter, knowledge, 실제 interaction cooldown과 HUD delivery는 발생하지 않는다. 기존 mature crop custom counter는 정상적인 작물 수확 이벤트 자체에 따라 기록될 수 있으며, unavailable provider 때문에 추가 progression mutation이 생기는 것은 아니다.

## G. 다른 PC가 구현해야 하는 것

구현은 별도 승인된 AI 단계에서 진행하며, 이 문서는 실제 구현을 포함하지 않는다.

```text
provider availability 판단
production resolver 등록
signal별 InteractionContentPreparer 선택
bounded request/response DTO
RisuAI/local LLM transport
timeout/failure/stale response 처리
deterministic fake provider 검증
```

실제 production 등록 경계는 다음 API다.

```java
InteractionContentPreparerResolverRouter.INSTANCE
        .configureProductionResolver(resolver);
```

연결에 필요한 기존 타입은 다음과 같다.

```text
com.sande.mythictrpg.interaction.spontaneous.InteractionContentPreparerResolverRouter
com.sande.mythictrpg.interaction.spontaneous.InteractionContentPreparerResolver
com.sande.mythictrpg.interaction.spontaneous.ContentPreparerResolution
com.sande.mythictrpg.interaction.spontaneous.SpontaneousInteractionSubmissionService
com.sande.mythictrpg.interaction.content.InteractionContentPreparer
com.sande.mythictrpg.interaction.content.ContentPreparationRequest
com.sande.mythictrpg.interaction.content.PreparationResult
com.sande.mythictrpg.interaction.orchestration.InteractionOrchestrator
```

Resolver는 configure-once다. `setResolverForTesting(...)` 같은 test override를 production provider 등록 수단으로 사용하면 안 된다. Resolver 단계에서는 signal만 전달되며 아직 최종 interaction plan이나 선택된 God이 확정되지 않는다. 선택된 God은 `ContentPreparationRequest.plan().participants().primaryGodId()`에서 확인한다.

`InteractionContentPreparer.prepare(...)`는 `CompletionStage<PreparationResult>`를 반환한다. 네트워크 또는 local LLM 호출은 비동기로 실행하고 Minecraft server thread를 block하지 않는다.

현재 spontaneous 요청 보호 정책:

- player당 in-flight permit 최대 1개.
- server당 in-flight permit 최대 1,024개.
- permit 기본 timeout 1,200 game ticks.
- timeout, logout, server stop 이후 stale 응답으로 interaction을 시작하지 않는다.
- 실제 spontaneous interaction cooldown은 성공적인 commit 이후 별도 적용되며 현재 200 ticks다.

## H. 다른 PC가 수정하면 안 되는 책임

다음 판단과 변경은 반드시 Minecraft 서버에 남긴다.

```text
God unlock
God appearance
God candidate selection
player ACTIVE/IDLE
promotion cooldown
interaction planning
encounter commit
knowledge mutation
HUD validation
```

AI는 서버가 확정한 God과 interaction plan에 맞는 대화 내용만 제안한다. AI 응답에서 God unlock, 보상, 호감도 변경, 명령 실행, 세계 상태 변경을 직접 수행하면 안 된다.

Provider에 `ServerPlayer`, `Entity`, `Level`, `ItemStack`, 자유 형식 mutable Map 또는 다른 Minecraft live 객체를 전달하지 않는다. 외부 요청에는 제한된 문자열, ID, 숫자와 명시적으로 정의된 immutable plain data만 사용한다.

## I. 초기 AI 테스트 순서

1. 기준 저장소의 build와 전체 `158`개 GameTest를 다시 검증한다.
2. 외부 네트워크 없는 deterministic fake provider를 별도로 검증한다.
3. 기존 read-only preview를 안전하게 확장하거나, commit/HUD 없는 별도 AI dry-run을 마련한다.
4. RisuAI/local LLM transport를 provider 경계 뒤에 연결한다.
5. timeout, 실패, malformed response, player logout과 stale response 차단을 검증한다.
6. 성숙한 밀 수확에서 Demeter 선택과 검증된 AI 대화 출력까지 통합 검증한다.

Dry-run은 `InteractionOrchestrator.execute()` 또는 `InteractionStartService.start()`를 호출해서는 안 된다. 해당 production 경로는 content validation 이후 encounter commit과 HUD delivery까지 진행할 수 있다.

## J. 저장소 전달 제외 항목

다음 항목은 Git 저장소 전달이나 commit 대상에 포함하지 않는다.

```text
.gradle/
build/
run/
logs/
테스트 world
API key
machine-local 설정
```

API key, endpoint, bearer token과 비밀 설정은 Git에 기록하지 않고 향후 해당 PC의 승인된 local configuration 경계에서만 관리한다.

현재 선택적 빌드 산출물은 다음과 같다.

```text
build/libs/mythictrpg-1.0.0.jar
```

JAR은 실행 또는 확인용 참고 자료일 뿐이다. 다른 PC에서 Codex로 기능을 이어서 개발하려면 JAR이 아니라 Git 저장소 전체와 commit 이력이 필요하다. JAR을 Git에 추가하지 않는다.

## K. 현재 제한 사항

- production resolver는 현재 `UNAVAILABLE`이다.
- 실제 AI provider, RisuAI transport와 local LLM 연결은 없다.
- player chat observation/listener가 없다.
- 자유로운 다회차 대화 시스템이 없다.
- secondary God 자동 선택이 없다.
- shared audience가 없다.
- gameplay counter 기반 God unlock condition이 없다.
- affinity 실제 증감 규칙이 없다.
- persistent sampling cursor와 repeated interval crossing이 없다.
- chest/container, `/give`, creative, trade, 다른 모드의 direct inventory insert까지 포함하는 item acquisition coverage는 완성되지 않았다.
- production gameplay binding은 현재 Demeter의 성숙한 밀 수확 한 건뿐이다.

상세 개발 구조는 `docs/mythic-trpg-development-guide.md`, 외부 provider 계약과 연동 원칙은 `docs/ai-dialogue-integration-bridge.md`를 참고한다.
