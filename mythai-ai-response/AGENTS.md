# MythAI 응답 모드 진입 지침

작업 전 [상위 AGENTS.md](../AGENTS.md)와 [작업 시작 안내](../인수인계/START_HERE.md)를 읽고 해당 작업의 프로젝트 스킬을 선택한다. 과거 기록은 [공용 인수인계](../인수인계/PROJECT_HANDOFF.md)에서 관련 절만 확인한다.

이 모드는 대사·Proposal을 생성하며 실제 게임 상태의 최종 권한을 소유하지 않는다. 현재 브랜치는 `src/main/java`와 `src/engine/java`를 직접 컴파일한다. 예전 생성 오버레이가 아닌 이 소스를 수정한다. `build.gradle`의 현재 게임 API JAR과 패키지 검증 태스크를 확인한다. 상위 작업공간이 없는 복사본이면 누락된 게임 API/도구 의존성부터 확인한다.
