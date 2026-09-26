# M0-03. SQLite 패키징과 M1 착수 기준

2026-09-20 / 의존성 **선정·검증 계획만 확정**. Gradle/서버 파일 변경, 다운로드, 설치, 빌드, native 실행은 하지 않았다.

## 1. 고정한 선택

| 항목 | M0 결정 |
|---|---|
| 라이브러리 | `org.xerial:sqlite-jdbc:3.53.4.0`, classifier 없는 기본 JAR. 동적 `latest`/`+` 금지 |
| 소유 모듈 | `mythictrpg-main` 한 곳. AI/content에 JDBC·native 복제 패키징 금지 |
| 도구 체인 | 현재 Java21 / Minecraft1.21.1 / NeoForge21.1.248 / ModDevGradle2.0.144 유지 |
| 포함 방법 | Maven Central 의존성에 `implementation` + NeoForge `jarJar`, exact strict version3.53.4.0. 다운로드한 JAR을 `server/mods`에 직접 넣지 않음 |
| DB | 단일 world/dataset SQLite, writer1/read2. WAL + synchronous=FULL, foreign_keys=ON, FTS5 실제 실행 확인 필수 |
| 설정/manifest | 미래 `config/mythictrpg/recording-v2.json`, `<world>/mythictrpg-recording-v2/manifest.json`. 설정 schema2, missing=OFF, OFF에서 DB/identity 신규 쓰기 없음 |
| native 초기화 | 서버의 archive RECORD_ONLY 초기화 worker에서만 driver/connection 접근. 클라이언트 공통 클래스 static 초기화에서 호출하지 않음 |
| 오류 정책 | native/JAR/schema/FTS5/identity 불일치는 새 기록 UNAVAILABLE. SQLite 대체 무제한 JSON 파일이나 다른 dataset으로 자동 우회하지 않음 |

Xerial의 해당 릴리스는 SQLite3.53.4 기반이다. 태그 POM은 기본 JAR에 클래스/리소스와 Android 외 native를 넣고, 별도 `without-natives`/OS별 classifier도 제공한다. 이번에는 classifier 조합을 직접 관리하지 않고 기본 JAR 하나로 고정한다. [릴리스](https://github.com/xerial/sqlite-jdbc/releases/tag/3.53.4.0), [해당 버전 POM](https://github.com/xerial/sqlite-jdbc/blob/3.53.4.0/pom.xml).

이는 라이선스·패키징 형식을 조사한 결정이지 현재 Minecraft 조합에서 로딩 성공을 확인한 결과가 아니다. M1에서 실제 artifact digest, embedded native 경로, runtime SQLite version과 FTS5를 확인하기 전에는 배포 가능 판정을 하지 않는다.

## 2. 라이선스·로깅·확보 방식

Xerial POM의 라이선스는 Apache-2.0이다. 배포 JAR의 라이선스/고지 리소스를 보존하고, 포함된 native의 별도 고지도 검사한다. AI에 SQLite 클래스가 들어가지 않았는지 검사한다. 현재 POM의 slf4j-api1.7.36은 optional이다. 서버에는 slf4j-api2.0.9와 Log4j SLF4J2 구현이 있으므로 구 SLF4J binder/별도 logging 구현을 묶지 않는다. 런타임 연결은 M1의 실체 검사 항목이다. [Xerial POM](https://github.com/xerial/sqlite-jdbc/blob/3.53.4.0/pom.xml).

NeoForge는 Jar-in-Jar metadata로 nested dependency와 지원 버전을 기록한다. M1에서는 현재 ModDevGradle의 실제 산출물에서 metadata/내장 JAR을 검사하고 exact version이 협상 중 임의 최신 버전으로 바뀌지 않게 한다. [NeoForge 공식 Jar-in-Jar 설명](https://docs.neoforged.net/toolchain/docs/dependencies/jarinjar/).

현재 조회한 `C:/Users/ADMIN/.gradle/caches/modules-2/files-2.1`에서 sqlite 관련 artifact를 찾지 못했다. M1에서 Maven 확보가 필요할 수 있다. 네트워크 제한으로 실패하면 승인 요청을 사용하며, 별도 설치 도구/전역 패키지/다른 모델을 깔지 않는다. 확보 후 SHA-256과 origin을 기록하고 검증된 의존성 고정을 사용한다. 아직 확보하지 않은 binary의 hash를 문서에 꾸며 넣지 않는다.

## 3. SQLite 운용 제약

WAL은 로컬 동일 호스트 파일 저장을 전제로 한다. 긴 reader는 checkpoint를 늦추므로 read deadline/짧은 transaction/정기 checkpoint를 둔다. FULL 모드에서도 OS/장치 오류까지 포함한 절대 무손실을 보장하지 않는다. WAL만 삭제하거나 실행 중 DB 본체만 복사해서 백업했다고 하지 않는다. 선택 버전은 공식 WAL-reset 수정 버전보다 뒤지만 런타임 실제 version도 다시 확인한다. [SQLite WAL 및 수정 안내](https://sqlite.org/wal.html).

운영자 `java.io.tmpdir`나 전역 `org.sqlite.tmpdir`를 임의로 바꾸지 않는다. SQLite native 추출 경로는 코드 의존성 영역으로, DB의 query/temp/staging 파일은 관리 저장소 영역으로 구분한다. M1에서 임시 저장의 실제 생성 위치를 확인하고 기록 파일이 합산 대상 밖으로 새지 않도록 구성한다. 지원하지 않는 공유/네트워크 파일시스템은 자동 승인하지 않는다.

## 4. 기존 빌드 결합 보존

- 게임/AI 개발 JAR은 각각1.0.11/0.1.12이고 서버는1.0.2/0.1.3이다. 새 게임 포트를 AI에 연결할 때 AI의 실제 compile API 입력도 맞춰 검사한다. 개발 JAR을 만들었다고 서버 배포 파일을 교체하지 않는다.
- AI는 여전히 `mine/mine/build/libs/mythictrpg-1.0.0.jar`의 AI 패키지 일부와 legacy 소스, 현재 overlay를 사용한다. `build/generated` 단독 수정이나 구 결합형 JAR 재배포 금지다.
- 공개 계약은 게임 소유, AI는 소비자다. 생성 overlay의 기존 memory/social/quest 적용 순서와 중복 엔트리/분할 패키지 문제를 함께 검사한다. 현재 dirty Gradle 내용을 기본본으로 되돌리지 않는다.

## 5. M1 착수 범위와 완료 게이트

M0에서 새로 물어야 할 사용자 선택은 없다. SQLite·구조 변경·test 제외·100GB 결정 안에서 구현할 수 있다. 다음은 **M1 요청 이후** 수행한다.

1. 신규 `recording.api` 값 타입·포트, world service/manifest와 schema migration을 구현한다. v1 DTO와 파일 형식은 보존한다. 사전 누락/손상/상위 schema/다른 world는 신규 기능만 닫는다.
2. 메시지·receipt/view·source·작업 queue·cursor·invalidation의 DDL/FK/unique/transaction 검사. 실제 source adapter 연결 전 dummy adapter로 권한/멱등성을 검증한다. 미래 모델 파생 테이블은 versioned migration으로 추가할 수 있으나 예약 schema와 충돌하지 않아야 한다.
3. [관리 저장소 목록](02_관리저장소와_기존제한.md) 전체의 합산 registry/예약·진단을 구현한다. 기존 writer에 안전한 예약 접점을 연결하되 OFF 기존 동작을 보존한다. 새 원문 자동 삭제/기존 파일 이관은 범위 밖이다.
4. worker1/read2, 큐2048건 또는16MiB, batch128건 또는1MiB, hot cache64MiB를 초기 예산으로 사용한다. 상세 성능 목표는 [09](../09_검증_전환_복구.md)의 **목표값**이지 실측값이 아니다. 큐보다 큰 입력은 무절단 성공으로 꾸미지 말고 분할 admission 또는 명시 실패·gap으로 처리한다.
5. 제한 quota 동시 쓰기/WAL/temp/SavedData 비동기 예약 시험, 강제 writer 종료 후 재개, truncation/손상/lock 경합/중복/revision/overflow/OFF 무쓰기 시험을 수행한다. 위험한 기존 보상/상태 저장으로 quota 실패가 전파되지 않는지 검증한다.
6. 의존성 JAR/라이선스/중복/nested metadata 검사, Java21 임시 시험 DB로 driver version·FTS5·WAL·재시작 검증, 관련 게임/AI 컴파일·오프라인 회귀를 실행한다. 시험 위치는 개발 모듈 build 하위이며 `server/world`를 쓰지 않는다.

Minecraft/GameTest·클라이언트 실행·실제 모델·서버 설정 변경·JAR 배포는 이 문서만으로 승인되지 않는다. M1의 패키지·오프라인 smoke와 실제 NeoForge 로딩은 별도 결과로 보고한다. 현재 계획의 M1 완료는 전자를 요구하며, 후자는 승인된 통합 검증에서 수행한다. 실제 로딩을 하지 않았다면 그 항목은 계속 미검증이다.

M1 다음은 M2(전체 수집)다. M1에서 채팅/소문 채널까지 운영 ON하거나 M2~M7을 자동 진행하지 않는다.
