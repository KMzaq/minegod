# 기억 모드 시작 종료 오류 수정 — 2026-09-14

## 원인

20:35의 실제 서버 로그는 월드 로드 전 Java 모듈 구성에서 다음 오류로 종료됐다.

```text
java.lang.module.ResolutionException:
Modules mythai_ai_response and mythictrpg export package
com.sande.mythictrpg.ai.memory to module mixinextras.neoforge
```

새 게임 기억 계약과 AI JAR의 기존 레거시 기억 클래스가 같은 패키지에 들어간 split-package 오류다. 클래스 이름은 서로 달라 기존 중복 클래스 검사는 통과했지만 NeoForge의 Java 모듈 로더에서는 허용하지 않는다. 오류에 등장하는 mixinextras나 FTB 모드 자체가 원인이라는 뜻은 아니다. 앞선 배포 검증이 패키지 충돌을 놓친 문제를 수정했다.

## 수정·배포

- 게임 소유 새 계약을 `com.sande.mythictrpg.ai.memorycontract`로 옮기고 게임/AI의 모든 참조를 함께 변경했다. 기존 AI 기억 패키지·레거시 소스/JAR은 제거하거나 수정하지 않았다.
- 게임 **1.0.2**, AI 응답 **0.1.2**로 패치 버전을 구분했다. AI의 게임 의존성도 `[1.0.2,)`로 맞췄다. 구 1.0.1/0.1.1 조합과 섞지 않는다.
- 서버 및 클라이언트 배포용 게임 JAR을 1.0.2로 교체했다. 실제 Minecraft 런처 폴더는 수정하지 않았다.
- 개인 기억 `PERSONAL`, 기존 모델/출력 한도, JSON/SavedData 구조와 저장 경로는 유지한다. 데이터 이관·월드 초기화·퀘스트/관계/보상 변경은 없다. FTB·콘텐츠 레지스트리·데이터팩도 수정하지 않았다.
- `Test-ModPackageIsolation.ps1`을 추가하고 배포 검사에 연결했다. 각 JAR의 모든 클래스 패키지를 검사하며 클래스 이름이 달라도 패키지가 겹치면 실패한다. 보존한 오류 JAR 조합을 거절하는 회귀 검사도 포함했다.

현재 배포 해시:

| 파일 | SHA-256 |
|---|---|
| `mythictrpg-1.0.2.jar` (서버/클라이언트 동일) | `32C70B972640EE534AE5FF09FDBBA5578C863197EE9B3F9EFD6CA62253226124` |
| `mythai_ai_response-0.1.2.jar` (서버 전용) | `DDB3CD2EFA525ED6613946AB397572A2B162758B1F19A99686FF7F9A6D996E88` |

## 검증과 한계

게임/AI 빌드, 소문 상태 39개 검사, AI 기억 656개 검사 통과. 수정 JAR 조합은 Java 21의 `--validate-modules` 검사도 통과했다. 이것은 Minecraft 서버를 시작하지 않는 Java 모듈 검사다. 실제 배포 모드 전체의 패키지 분리 및 서버/클라이언트 해시를 확인했다. 과거 192개 GameTest를 이번에 재실행한 결과로 인용하지 않는다.

```powershell
.\dev-tools\Test-MemoryFoundationRelease.ps1 -ServerProfile PERSONAL
.\dev-tools\Test-ModPackageIsolation.ps1
```

사용자가 **서버 실행 없이 수정만**을 선택했으므로 실제 부팅·LLM 호출·접속은 하지 않았다. 확인된 시작 오류는 수정했지만 추가 실행 단계까지 검증한 것은 아니다. 실제 테스트 절차는 [개인 기억 서버 안내](LP_PERSONAL_MEMORY_TEST_SETUP.md)를 따른다.

## 백업

수정 전에 LP 원본, LP 소스 298개, 구현 직전 전체 소스 906개, 개인 기억 배포 직전 29개 파일의 해시를 재검증했다. 별도로 `server/backups/before-memory-package-fix-20260914-203705-251/`에 수정 직전 소스·빌드 파일·JAR·설정·문서·오류 로그 **673개**를 저장하고 모든 해시를 확인했다. 기존 활성 JAR 3개는 이 백업의 `retired-active/`로 옮겼다. 삭제하지 않았으며 `workspace/`에도 원본 사본이 있다.

LP 원본은 계속 `server/backups/LP/`, 완전한 구현 전 게임 소스는 906개 스냅샷에 있다. [최신 합산 소스 차이 목록](memory-foundation-current-changes-20260914.csv)은 그 906개 기준과 현재 소스/문서의 차이다. 최초 36개 목록은 이력으로 보존한다. 패키지 경로가 바뀌었으므로 최초 목록만 보고 새 `memorycontract` 파일을 남겨 둔 채 LP로 복원하지 않는다. 실제 복원은 후속 변경 보존·선택 파일 복원·새 빌드 검증을 거치는 별도 작업이다.
