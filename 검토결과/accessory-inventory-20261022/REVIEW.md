# E 인벤토리 장신구 검증

2026-10-22 `behaivior`, 게임 **1.0.38**. [사용법](../../mythictrpg-main/docs/ACCESSORY_INVENTORY.md), [구현 인수인계](../../인수인계/브랜치실험/behaivior/ACCESSORY_INVENTORY_20261022.md).

## 자동 검증

관련 **7개 namespace, 서로 다른 GameTest 91/91 PASS**. [최종 결과](final-results.json)는 [첫 회귀90개](initial-results.json)와 [크리에이티브 패킷 검증을 포함한 신규9개 재실행](creative-results.json)을 namespace별로 합친 결과다. 같은 테스트를 중복 합산하지 않는다.

| namespace | 최종 통과 |
|---|---:|
| mythictrpg_accessories | 16/16 |
| mythictrpg_accessory_inventory | 9/9 |
| mythictrpg_combat_prototype | 13/13 |
| mythictrpg_progression_rewards | 8/8 |
| mythictrpg_prototype_catalog | 10/10 |
| mythictrpg_prototype_combat | 15/15 |
| mythictrpg_weapon_skills | 20/20 |

신규 검사는 실제 `InventoryMenu.clicked()`로 두 칸 장착·해제, 같은 UUID 중복 거부/별도 권리 중첩, 포화 Shift 이동 보존, 핫바·보조 손 교환, 원시 슬롯 변이 차단, 드롭 취소, 위조/타인/다른 정의/과수량 거부, 관리 화면과 재지급 연결, 저장 상태와 새 Player 메뉴, 기존 방어구 조작, 옛 기념 토큰 정리를 확인한다. 기존 시제품 반복 지급 검사에도 착용 후 인벤토리 포화 상태를 추가했다.

크리에이티브 검사는 **실제 서버 패킷 핸들러**를 거쳐 일반 인벤토리 토큰→커서→장착 두 칸→해제→일반 인벤토리 반환을 실행한다. 잘못된 클라이언트 커서 예상값이 서버 원장을 변경하지 못하고, Creative SetSlot 46/47 위조가 거부되는지 확인한다. 실제 송신 `ClientboundContainerSetContentPacket`에서 48칸·두 착용 UUID·커서 UUID를 검사했다.

세부 로그와 격리 월드는 `mythictrpg-main/build/accessory_inventory_first_20261022`, `build/accessory_inventory_creative_20261022`에 보존한다. 운영 월드를 사용하지 않았다.

## 보존한 규칙과 클라이언트 확인 범위

저장 원본은 기존 보상 권리/착용 UUID이며, 사망·재접속·장신구 교체·재지급이 쿨타임·마나·새 보상 권리를 만들지 않는다. 장신구 수치와 일반 스킬·야생 전투 수치는 변경하지 않았다. 네트워크 버전은48칸과 구46칸 클라이언트가 섞이지 않도록 `accessories-2`로 변경했다.

생존 슬롯 위치는 실제 Minecraft 소스의 캐릭터 오른쪽/제작 왼쪽 빈 공간을 사용한다. 크리에이티브는 생존 인벤토리 탭의 SlotWrapper 위임을 유지하면서 두 좌표만 바꾼다. 이 탭의 휴지통은 사용하지 않고 빈 칸/드롭을 안내하며, 서버 커서와 카탈로그 커서가 어긋나지 않도록 물건을 내려놓은 뒤 탭을 바꾸게 한다.

**실제 클라이언트 창을 열어 E 화면을 조작하거나 렌더 배치를 확인하지 않았다.** 서버 패킷/메뉴 검증을 GUI 확인으로 취급하지 않는다. 운영 서버·개인 런처 미배포, 운영 부팅/종료·새 LLM 호출 없음.

## 빌드와 산출물

[게임 컴파일·순수 검사·JAR](game-build.log): 무기 정의46·전투력 계산36·정책38검사 PASS. [AI API 호환](ai-compatibility.log): 컴파일·소유권·패키지551클래스 검사 PASS, 게임 클래스 중복0. 소스 수치 일관성3스킬·2장신구·11장비 PASS.

[mythictrpg-1.0.38.jar](../../mythictrpg-main/build/libs/mythictrpg-1.0.38.jar), 17,321,580바이트. SHA-256 `54E403BCFCC7D0F45D82C04114B24269CFC30157C2350F04B3619C6FB1FE48D8`.

[패키지 확인](artifact.json): 버전1.0.38, 서버/클라이언트 슬롯 mixin과 클래스, 네트워크 `accessories-2` 포함 확인. 사용자 후속 텍스처 요청 이전에 완성한 UI 산출물이며, 이후 JAR은 별도 버전과 후속 기록으로 구분한다.
