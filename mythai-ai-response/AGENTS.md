# MythAI 응답 모드 진입 지침

작업 전 [상위 AGENTS.md](../AGENTS.md)를 읽고 해당 작업의 프로젝트 스킬을 선택한다. 인수인계는 [PROJECT_HANDOFF.md](../인수인계/PROJECT_HANDOFF.md)다.

이 모드는 대사·Proposal을 생성하며 실제 게임 상태의 최종 권한을 소유하지 않는다. `build.gradle`에 legacy AI JAR/소스와 생성 오버레이 의존성이 있으므로 입력을 추적하고 `build/generated`만 수정하지 않는다. 상위 작업공간이 없는 복사본이면 누락된 의존성과 계약부터 확인한다.
