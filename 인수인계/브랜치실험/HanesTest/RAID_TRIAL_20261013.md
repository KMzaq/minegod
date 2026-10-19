# 첫 시험 레이드 제작 — 봉인된 석상 수호자

2026-10-13 / HanesTest / 게임 **1.0.28**, 개발 검증 후 같은 날 서버 파일 배포 완료. 실제 서버 부팅·클라이언트 설치/접속은 미확인.

## 22:40 후속 — 서버 파일 적용

사용자가 서버 적용과 테스트 방법 안내를 요청했다. 서버·Java 프로세스 없음과 포트25565 미청취를 확인한 뒤 [전용 배포 스크립트](../../../dev-tools/Deploy-RaidTrial-20261013.ps1)로 게임1.0.28을 `server/mods` 및 `server/client-required-mods`에 설치했다. 구1.0.16은 활성 mods 밖으로 옮겨 보존했다. 기존 AI0.1.19, 콘텐츠0.1.2, FTB/Architectury4종은 그대로다.

백업은 [server/backups/raid-trial-20261013-224022-732](../../../server/backups/raid-trial-20261013-224022-732)에557개파일/53,032,772bytes이며 월드·설정·모드·클라이언트 패키지·서버 설정을 포함한다. `backup-manifest.csv`에 파일별SHA256, `deployment.json`에 교체 결과를 기록했다. 교체 대상2개 외 보존 파일의 해시를 대조했다. 서버/클라이언트용 새게임해시는 아래빌드해시와 같다.

구AI404개class의 게임타입115개·멤버참조332개를 새게임JAR과 대조해 누락0/중복class0, 구현interface6개의 필수추상메서드 호환을 확인했다. 메타데이터 의존성도 충족한다. 이 정적 검사는 실제 부팅·AI 대화 성공을 뜻하지 않는다. 서버는 원래의 정지 상태로 유지했고 운영 부팅·레이드전장준비 명령·실제런처설치는 하지 않았다. 시작은 `server/start-neoforge-ai-server.bat`, 접속은 `localhost:25565`, 이후 [시련준비/장비/모집/시작](../../../mythictrpg-main/docs/raid-trial-guide.md)을 따른다. 기본 `.minecraft/mods`는 현재사용NeoForge프로필로 식별되지 않아 자동수정하지 않았다.

아래 제작 당시의 미배포 기록은 위 후속 배포 이전 상태다.

사용자가 첫 시험 레이드의 기획·설계·모델·리소스 제작을 위임했다. 기존 레이드 런타임에 비정사 괴수 시련 하나를 구현했다. 모든 보스 종류의 레이드 시스템 완성이나 정식 콘텐츠·밸런스 확정은 아니다. 다른 채팅의 AI 0.1.34·콘텐츠 0.1.3 작업과 미커밋 변경은 수정하지 않았다.

## 결과와 진입점

- [실행 안내·패턴·아트·제약](../../../mythictrpg-main/docs/raid-trial-guide.md)
- [정적 모델 미리보기](../../../docs/assets/raid-trial/sealed_guardian_preview.png)
- 시련 `mythictrpg:trial/sealed_guardian`, 전장 `mythictrpg:trial/sealed_court`, 차원 `mythictrpg:raid_trial`.
- OP `/mythraid trial prepare` → 필요시 본인에게 `/mythraid trial kit` → 리더 `/mythraid trial create` → 참가자 join → 리더 start. 기존 `/mythraid` UI도 사용한다.
- 1~4명, 3목숨, 10분, 인원별 HP 180/250/320/390. 서버 주도 전방 부채꼴·환형 충격파·고정 위치 낙하, 65% 봉인 세 곳, 30% 과부하.
- 네이티브 보스 28파트·87큐브와 기본/발광 256×256 PNG를 직접 제작. Java 모델과 `dev-tools/raid-trial-artwork.py`를 편집 원본으로 보존. 외부 모델·리깅 라이브러리 의존성 없음.

## 구현 경계

`TrialRaidEntities`를 게임 진입점에 등록했다. `RaidRuntime`은 시련 전장 준비 검사, guardian spawn 이후 제어기 시작, ACTIVE tick 위임, 종료/서버 정지 시 패턴·보스바 제거를 수행한다. 일반 레이드 JSON 스키마와 기존 퀘스트·보상·FTB 권한은 유지한다. 게임 network protocol 10도 유지하지만 새 엔티티와 리소스가 있으므로 서버·클라이언트 게임 JAR은 1.0.28로 맞춰야 한다.

`SealedGuardianEncounter`는 이번 고정 보스 제어기다. 재사용 가능한 순수 기하 `RaidAttackGeometry`와 단일 공격 상태 `RaidPatternTimeline`을 분리했다. 예고와 판정은 같은 고정 중심/방향/크기 상수를 쓰며 피해는 태그된 보스가 발생시켜 기존 `RaidProtection`을 따른다. 참가자·생존·리스폰 대기·탈락·전장 위치를 매 tick 확인한다. 봉인은 40tick 연속 웅크림으로 해제하며 외부인은 기여하지 못한다. 재시작한 ACTIVE 시도는 기존 정책대로 실패·정리하며 패턴을 복원하지 않는다.

전장은 전용 void 차원의 제한 구역에만 명시적으로 생성한다. 기존 블록/유체/엔티티가 있으면 최초 생성을 거절한다. 준비 마커 이후 재실행은 편집을 덮지 않는다. 보스 2.0×3.4 및 참가자 0.6×1.8의 실제 준비 공간을 블록 충돌/유체로 확인한다. 진행 중인 보스 엔티티 자체는 readiness를 막지 않는다.

시련의 `rewards=[]`, `offerGodIds=[]`이다. 필수 `rewardGodId`에는 기존 Demeter ID를 두었지만 빈 보상이므로 사용하지 않는다. 이 시련을 신에게 귀속시키거나 새 신 ID를 만들지 않는다. 시련 승패는 RaidState에만 남으며 `ImportantEvents`/Story 신호를 발행하지 않는다. 무보상 레이드가 기존 다른 퀘스트 보상을 수령시키지 않도록 `settleVictory`의 `deliverQueued`는 작성 보상이 있을 때만 호출하며 승리 문구도 구분했다.

## 검증

- `compileJava`, `raidAttackGeometryTest` 147검사, `raidPatternTimelineTest` 28검사 통과.
- 모델 geometry/UV 경계 및 정적 미리보기 검수 완료. Minecraft 화면 캡처나 실제 애니메이션 검증은 아니다.
- 개발 GameTest **5/5 통과**(최종 파일 재검사 22:11:23): 시련 lifecycle/기믹 1개, 장비·명령 권한 1개, 기존 레이드 lifecycle/신 lease 2개, UI 1개. 새 시련 시험은 실제 피격/예고/중복피격 방지, 보호막 피해 거절, 외부인 기여 거절, 연속 웅크림과 solo 세 봉인, 경직·3단계, 봉인 시간초과 pulse와 해제, 취소/승리/재시작 정리·귀환을 포함한다.
- 준비 검사: 반복 준비의 장식 보존, 필수 봉인/보스의 천장·옆 장애물 거절, 자기 보스가 있어도 준비 상태 유지. 장비는 OP 권한과 빈칸 전량 사전검사, 기존 스택/component 보존, 자동 착용 없음, 재실행 시 빈칸 부족 거절 확인.
- 무보상 승리 전 기존 다른 source의 pending 보상을 등록하여 승리 후에도 동일 receipt/미지급 상태·인벤 무변화를 확인했다. 기존 일반 레이드의 승리 영수증 재사용 회귀도 통과했다.
- 원본 개발 로그: `mythictrpg-main/build/raid-trial-20261013/logs/latest.log`. `Raid state is read-only` 오류 2건은 기존 손상 저장 데이터 거절을 검증하는 의도된 로그이며 전체 실행은 성공 종료했다.
- `jar` 패키징 성공: [게임 1.0.28 JAR](../../../mythictrpg-main/build/libs/mythictrpg-1.0.28.jar), 16,137,892 bytes. 엔티티/제어기/클라이언트 renderer·텍스처·차원·raid/arena 필수 9개 항목과 버전 메타데이터를 검사했고 테스트 전용 FLAT override가 JAR에 없음을 확인했다. 문서 링크와 `git diff --check`도 통과했다.
- JAR SHA256: `F7F75C034FA152D996407BCAF0653B0E5823CA831F7B17F4FDE2DAE929AFBAD2`.

재현 명령은 프로젝트 루트 PowerShell에서 실행한다.

```powershell
.\dev-tools\run-mythictrpg-gradle.cmd compileJava raidAttackGeometryTest raidPatternTimelineTest --offline --no-daemon --max-workers=1
.\dev-tools\run-mythictrpg-gradle.cmd runRoomIntegrationGameTestServer -ProomTestDirectory=build/raid-trial-20261013 '-ProomTestNamespaces=mythictrpg_raid_trial,mythictrpg_raid,mythictrpg_raid_ui' --offline --no-daemon --max-workers=1
```

NeoForge 1.21.1의 GameTestServer는 기본 FLAT preset만 사용해 일반 서버의 모드 차원 주입을 거치지 않는다. 해당 namespace를 명시했을 때만 `prepareRaidTrialFixture`가 **build 내부 테스트 월드 데이터팩**에 운영용 차원 정의를 포함한 FLAT preset을 만든다. 실제 모드 JAR에 `minecraft:flat` 덮어쓰기를 싣지 않는다. 순수 검사 외 통합 시험은 실제 등록된 시련/엔티티/차원 리소스를 사용하며 카탈로그를 테스트용 boss로 교체하지 않는다.

첫 통합 시험에서 차원이 없는 fixture를 발견했고 테스트용 preset으로 수정했다. 다음 시험의 모의 플레이어는 생성/차원전환 면역이 남아 공격 검증에 실패했다. fixture가 실제 outbound teleport ID를 ACK하고 신규 플레이어 60tick 보호 시간을 정상 tick으로 경과시키도록 수정했다. 제품의 피해 면역을 해제하거나 약화한 변경은 없다.

## 운영 상태와 남은 확인

- `server/mods`, `server/client-required-mods`, 운영 월드/설정/런처 파일을 수정하지 않았고 운영 서버를 실행·중지·배포하지 않았다. GameTest는 `mythictrpg-main/build/raid-trial-20261013` 전용이다.
- 실제 클라이언트의 조명·발광·공격 애니메이션·패킷 지연 체감, 1인/4인 생존 모드 밸런스, 사망 부활 조작은 후속 인게임 확인이 필요하다. 테스트의 저장 roundtrip/재시작 복구 함수 검증은 두 실제 프로세스 재시작과 다르다.
- 보스는 몸통 중심 hitbox이며 팔 끝/장식의 부위별 판정은 없다. 큰 단일 피해로 HP 임계값 기믹을 건너뛸 수 있다. 강제 HP 잠금, 별도 무력화 게이지, 일반 JSON 패턴 편집기, 다른 모드 보스의 AI 어댑터는 이번 범위에 포함하지 않았다.
- 외부 고해상도 모델과의 혼용은 후속 보스별로 가능성을 검토한다. 자산 라이선스, 사용 버전, 형식, skeleton/animation, renderer, hitbox 연결을 각각 확정해야 한다. 이번 모델은 직접 제작한 자산이다.
