# 클라이언트 필수 모드

이 폴더의 다음 JAR을 모두 Minecraft 1.21.1 / NeoForge 21.1.248 클라이언트의 `mods` 폴더에 넣는다.

- `mythictrpg-1.0.2.jar`
- `architectury-13.0.11-neoforge.jar`
- `ftb-library-neoforge-2101.1.35.jar`
- `ftb-teams-neoforge-2101.1.11.jar`
- `ftb-quests-neoforge-2101.1.34.jar`

MythicTRPG에는 Dialogue HUD, 우측 AI UI 표시 전환, `G` 키 설정과 Network payload가 포함되어 있다. FTB 모드들은 퀘스트북 UI와 동기화에 필요하다. 서버 전용인 `mythai_ai_response`와 `mythaiaicontent`는 클라이언트에 설치할 필요가 없다.

2026-09-14 시작 시 패키지 충돌을 수정한 서버 게임 JAR과 동일한 1.0.2를 배치했다.
실제 런처의 기존 `mythictrpg-1.0.0.jar` 또는 `mythictrpg-1.0.1.jar`는 모드 폴더 밖에 보관한 뒤 1.0.2로 교체한다.
신구 MythicTRPG JAR을 동시에 넣지 않는다. 이 폴더 준비와 실제 런처 설치는 별개다.
시험 절차는 [개인 기억 서버 테스트 안내](../../docs/LP_PERSONAL_MEMORY_TEST_SETUP.md)를 따른다.
