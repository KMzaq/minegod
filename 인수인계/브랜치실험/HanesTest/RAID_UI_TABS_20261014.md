# 레이드 목록 탭 분리 — 2026-10-14

사용자가 첫 시련의 전용 차원과 UI에 만족하며 모집 중과 완료된 레이드를 구분해 달라고 요청했다. 개발 게임 **1.0.29 / protocol 11**, 이번 변경은 UI·목록 조회 계약에 한정한다.

## 구현

- `RaidScreen`: 모집 중 / 진행 중 / 완료 기록 탭. 목록과 상세에 한국어 상태 표시, 완료 결과는 클리어·실패·취소 구분. 작은 화면은 목록·상세를 각각 스크롤한다.
- `RaidUiService`: FORMING+생성 정의 / 나머지 비종료 / terminal로 먼저 분류하고 기존 조회 권한을 적용한 뒤 페이지를 나눈다. 새로고침·페이지 이동은 View를 유지한다. 시작 성공은 진행 중, 취소 성공 후 실제 종료는 완료 기록으로 이동한다. CLEANUP_PENDING은 진행 중에 남는다.
- View는 서버 grant와 응답에 포함한다. 탭 변경도 1회 토큰을 소비하므로 이전 화면 토큰을 재사용할 수 없다. 완료 기록은 실행 액션이 없다. 본인 참가 이력·OP 관리 조회 범위를 유지하며, 타인의 비공개 이력을 공개하지 않는다.
- 전투·모델·밸런스·보상·SavedData 스키마·이력 보존 정책은 변경하지 않았다. 이미 저장된 시도를 사용하며 영구 클리어 도감은 아니다.

## 검증

- `compileJava --offline --no-daemon --max-workers=1`: 성공.
- `runRoomIntegrationGameTestServer -ProomTestDirectory=build/raid-ui-tabs-20261014 -ProomTestNamespaces=mythictrpg_raid_ui --offline --no-daemon --max-workers=1`: **1/1 통과, 377.7ms**, Gradle 22초. 기존 참가·탈퇴·명단 고정·중복 토큰 검증에 View codec 왕복, 탭/페이지/갱신 보존, 오래된 탭 토큰 거절, 완료 세 결과 읽기 전용, 6개 초과 이력의 모집 목록 분리, 비공개 모집·진행·이력 비노출 검증을 추가했다.
- 최종 클라이언트 코드 포함 `jar --offline --no-daemon --max-workers=1`: 성공, Gradle 10초.
- 관련 diff 형식과 화면의 작은 크기 배치 계산을 검토했다. 실제 Minecraft 화면·마우스 조작은 미확인이다.
- 테스트는 `mythictrpg-main/build/raid-ui-tabs-20261014` 개발 월드에서만 실행했다. 첫 생성의 server.properties 없음 로그는 이후 테스트 통과와 구분한다.

## 적용 상태

빌드 출력은 `mythictrpg-main/build/libs/mythictrpg-1.0.29.jar` (16,148,120바이트, SHA-256 `97C43510EBE1D0A6A1BF284D2B0E19C82E6F7C9F771CEE7E527BB42F576B5F1B`). 이번 요청에서는 실제 `server/mods`, `server/client-required-mods`, 런처 설치 폴더를 교체하거나 실제 서버를 실행/중지하지 않았다. 확인한 서버 게임 파일은 기존 1.0.28이다. 적용 시 서버 정상 종료 뒤 구 게임 JAR을 백업하고 **서버·클라이언트 모두 1.0.29로 맞춰야 한다**. protocol 10과 11은 혼용할 수 없다. AI·콘텐츠·FTB 및 사용자가 변경한 server.properties는 유지한다.

사용법과 계약은 [레이드 런타임](../../../mythictrpg-main/docs/RAID_RUNTIME.md)과 [시련 안내](../../../mythictrpg-main/docs/raid-trial-guide.md)를 따른다.
