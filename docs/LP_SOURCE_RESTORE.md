# LP 소스 복원·재빌드 검증 — 2026-09-12

> 2026-09-14 현재: 이 문서는 **9월 12일 복원 당시의 기록**이다. 이후 승인된 기억·소문 기반을 추가하여 개발 소스는 패키지 수정본인 게임 1.0.2 / AI 응답 0.1.2이고 현재 재빌드는 LP와 다르다. 후속 요청으로 실제 서버에도 새 버전/PERSONAL 설정을 배치했으며 LP 백업은 그대로다. 최신 배포/복원은 [서버 테스트 안내](LP_PERSONAL_MEMORY_TEST_SETUP.md), 소스 백업은 [기반 구현 안내](LP_MEMORY_FOUNDATION_GUIDE.md)를 따른다.

9월 12일 복원 당시 `mythai-ai-response`와 `mythai-ai-content-registry` 소스를 빌드하여 **OPELA 개선 전 LP와 완전히 동일한 JAR**을 확인했다. 서버 JAR만 되돌리고 소스에 실험본이 남아 있던 문제를 해소했다.

## 복원 범위

- `dialogue-upgrade.gradle`과 RAG·기억 추출·감정/품질 평가·출력 스키마/프롬프트 튜닝 코드를 활성 소스에서 제거했다. 해당 실험 테스트도 백업으로 보존했다.
- 기존 Java 호출부와 기본 오버레이를 LP JAR의 동작과 대조해 복원했다. LP에 없던 AI 측 동적 관계·스토리 연결·운영 다중 신 확장도 백업했다. 게임 본체의 해당 기능 구현이나 사용자 미커밋 변경은 되돌리지 않았다.
- 데메테르·포르투나 프로필은 LP JAR의 원래 JSON으로 복원했다. 콘텐츠 ID나 참조·게임 상태는 새로 만들지 않았다.
- 기존 생성 소스·클래스가 섞이지 않도록 이전 `build` 폴더 전체를 복원 직전 백업으로 이동했다. `build/generated` 수정이나 LP 바이트코드를 새 출력에 복사하는 방법으로 복원하지 않았다. 기존 legacy AI JAR 추출 구조는 LP 당시와 동일하게 유지했다.

완전한 과거 원본 소스 스냅샷은 없었으므로 남아 있던 소스와 LP 바이트코드를 대조해 필요한 변경을 되돌렸다. 아래 결과는 단순한 동작 유사성이 아니라 **새로 컴파일한 전체 JAR의 동일성**을 확인한 것이다.

## 검증 결과

| 모듈 | LP와 동일한 파일 엔트리 | LP 및 재빌드 JAR SHA-256 |
|---|---:|---|
| AI 응답 | 265 / 265 | `DBC7C095943817248E05CD86B58966DE8DF74075A54BB8474134AE7C68D8147F` |
| 콘텐츠 | 69 / 69 | `38B8461C13619569E477BB24AB85F9BFAD317998402FEA3E5ACF0019C6CC8CBA` |

두 모듈 `build` 성공. `Test-LPBuild.ps1`은 엔트리 누락·추가·내용 변경·중복을 검사하고, LP 원본 해시도 먼저 검증한다. 실험 JAR을 후보로 전달하면 실패하는 역검사도 통과했다. 이번에 RAG 회귀 테스트나 실제 LLM 호출을 실행한 것은 아니며 Gradle 기본 test는 NO-SOURCE다.

## 당시 LP 소스의 재빌드

아래는 LP 소스 복원 후 markmar 루트에서 사용하는 명령이다. **현재 개발 소스에 그대로 실행하여 LP가 만들어진다고 생각하지 않는다.** 두 AI 모듈만 대상으로 하며 게임 본체를 재빌드하거나 서버를 실행하지 않는다.

```powershell
.\dev-tools\run-mythictrpg-gradle.cmd -p C:\Users\ADMIN\Desktop\markmar\mythai-ai-response build --no-daemon
.\dev-tools\run-mythictrpg-gradle.cmd -p C:\Users\ADMIN\Desktop\markmar\mythai-ai-content-registry build --no-daemon
.\dev-tools\Test-LPBuild.ps1
```

기존 빌드 의존성인 `mine/mine`의 AI 소스·결합형 JAR과 `mythictrpg-main/build/libs`의 게임 JAR을 임의 교체하지 않는다. 이후 의도적으로 기능을 수정하면 LP 동일성 검사가 실패하는 것이 정상이다.

## 백업과 서버 상태

- 원본 LP 바이너리: `server/backups/LP/` — 기존 해시 그대로 보존.
- 복원 직전 실험 소스·문서·기존 빌드 출력: `server/backups/before-LP-source-restore-20260912/` — 삭제한 활성 소스는 여기서 회수 가능.
- 검증한 LP 소스·빌드 스크립트·필수 외부 입력: `server/backups/LP-source-20260912/` — 구성은 해당 README 참조.

서버 `mods`에는 이미 동일 LP JAR이 있으므로 이번에 추가 배포/교체하거나 서버를 켜지 않았다. 서버·클라이언트 필수 게임 JAR, 모델·설정·월드·플레이어·기억 데이터는 유지한다. 앞선 LP 부팅 기록은 유효한 과거 기록이지만, 이번 소스 복원 뒤 인게임 응답 속도·자연스러움을 새로 측정했다는 뜻은 아니다.
