# 클라이언트 필수 모드

이 폴더의 다음 JAR을 모두 Minecraft 1.21.1 / NeoForge 21.1.248 클라이언트의 `mods` 폴더에 넣는다.

- `mythictrpg-1.0.36.jar`
- `architectury-13.0.11-neoforge.jar`
- `ftb-library-neoforge-2101.1.35.jar`
- `ftb-teams-neoforge-2101.1.11.jar`
- `ftb-quests-neoforge-2101.1.34.jar`

MythicTRPG에는 Dialogue HUD, 공개/비밀 대화방, 방 선택 화면, UI 표시 전환과 Network payload, 레이드·이해·스킬 등 현재 개발 UI가 포함되어 있다. Dialogue protocol은 11이며 이해 화면 채널은 `understanding-2`다. FTB 모드들은 퀘스트북 UI와 동기화에 필요하다. 서버 전용인 `mythai_ai_response`와 `mythaiaicontent`는 클라이언트에 설치하지 않는다.

2026-10-22 시험 콘텐츠 분리 후 서버 게임 JAR과 동일한 1.0.36을 배치하고 SHA-256 `2393A6E27070247AF99C92EA402B3B98889C59B30C3DEE8B647F434CDECFAFBF` 일치를 확인했다. [현재 배포 기록](../../인수인계/브랜치실험/behaivior/CONTENT_ARCHIVE_20261022.md)을 따른다. 최초 시험 배포와 버전명은 같고 해시는 다르다. 나머지 필수 4종은 기존 파일을 유지하며 서버와 같아야 한다. 알려진 대사 품질 문제는 해결된 것으로 취급하지 않는다.
실제 런처의 기존 `mythictrpg-*.jar`는 정확한 파일을 확인해 모드 폴더 밖에 보관한 뒤 1.0.36으로 교체한다.
신구 MythicTRPG JAR을 동시에 넣지 않는다. 이 폴더 준비와 실제 런처 설치는 별개다.
배포 상태와 백업은 [최신본 테스트 배포 기록](../../인수인계/브랜치실험/behaivior/TEST_DEPLOYMENT_20261022.md), 방 사용법은 [대화방 가이드](../../docs/CONVERSATION_ROOMS.md)를 따른다. 실제 런처에는 이 폴더의 필수 모드 전부를 복사하고 실제 HUD·대화를 확인해야 한다. 이번 배포는 실제 런처 설치·서버 부팅·인게임 확인을 포함하지 않는다.
