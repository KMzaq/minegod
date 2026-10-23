# 구버전 콘텐츠 회귀 검증 자료

`resources/`는 2026-10-22에 사용 경로에서 분리한 테스트 인물·퀘스트·루브라스 Story의 회귀 검증용 사본이다. 보존 원문은 프로젝트 루트의 `구버전/20261022-테스트콘텐츠/원본/`에 있다.

- `sourceSets.main.resources`, 일반 서버·클라이언트 실행, 배포 JAR에는 포함하지 않는다.
- `build.gradle`은 `type = gameTestServer`인 개발 실행에만 `build/<실행 폴더>/world/datapacks/mythictrpg-legacy-content-fixture`로 복사한다.
- `-PlegacyContentFixtures=false`는 이 복사를 비활성화한다. 기존 팩이 남은 실행 폴더에서는 실패하므로 비활성 검증에도 새 폴더를 사용한다. GameTest에는 `mythictrpg.legacyContentFixtures` 시스템 속성으로 선택한 모드를 전달한다.
- 실행 폴더는 반드시 모듈의 `build/` 안에 있어야 한다. 운영 `server/`나 기존 일반 실행 월드에는 복사하지 않는다.
- 기존 GameTest가 검사하는 4개 God ID, 포르투나 보상·퀘스트·건축 정책, 루브라스 사건의 기대값은 그대로 유지한다. 이 자료는 신규 콘텐츠의 설정이나 정사가 아니다.
- AI 통합 회귀에 필요한 이전 콘텐츠 레지스트리의 프로필·로어·전용 예시·퀘스트 목록 11개도 이 팩에만 포함한다.
- 인물별 번역은 제거한 19개 키만 언어별로 보존한다. 데이터팩의 `assets/`는 일반 클라이언트 리소스팩으로 자동 적용되지 않는다.

예: `runRoomIntegrationGameTestServer -ProomTestDirectory=build/<새 검증 폴더> -ProomTestNamespaces=minecraft`.
