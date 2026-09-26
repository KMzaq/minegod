# 클라이언트 필수 모드

이 폴더의 다음 JAR을 모두 Minecraft 1.21.1 / NeoForge 21.1.248 클라이언트의 `mods` 폴더에 넣는다.

- `mythictrpg-1.0.16.jar`
- `architectury-13.0.11-neoforge.jar`
- `ftb-library-neoforge-2101.1.35.jar`
- `ftb-teams-neoforge-2101.1.11.jar`
- `ftb-quests-neoforge-2101.1.34.jar`

MythicTRPG에는 Dialogue HUD, 공개/비밀 대화방, `H` 방 선택 화면, `G` UI 표시 전환과 Network payload가 포함되어 있다. 게임 네트워크 protocol은 6이다. FTB 모드들은 퀘스트북 UI와 동기화에 필요하다. 서버 전용인 `mythai_ai_response`와 `mythaiaicontent`는 클라이언트에 설치할 필요가 없다.

2026-09-23 서버 게임 JAR과 동일한 1.0.16을 배치하고 SHA-256 일치를 확인했다.
실제 런처의 기존 `mythictrpg-*.jar`는 정확한 파일을 확인해 모드 폴더 밖에 보관한 뒤 1.0.16으로 교체한다.
신구 MythicTRPG JAR을 동시에 넣지 않는다. 이 폴더 준비와 실제 런처 설치는 별개다.
배포 상태와 백업은 [전체 대화방 통합 배포 기록](../../docs/FULL_ROOM_DIALOGUE_INTEGRATION_20260923.md), 새 방 사용법은 [대화방 가이드](../../docs/CONVERSATION_ROOMS.md)를 따른다. 실제 런처에는 이 폴더의 필수 모드 전부를 복사하고 실제 HUD·대화를 확인해야 한다.
