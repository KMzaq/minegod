# E 인벤토리 장신구 — 2026-10-22

브랜치 `behaivior`, 게임 **1.0.38**. 사용자가 장신구를 E 인벤토리에서 방어구처럼 착용하도록 요청했다. [사용 안내](../../../mythictrpg-main/docs/ACCESSORY_INVENTORY.md), [검증·산출물](../../../검토결과/accessory-inventory-20261022/REVIEW.md).

## 구현

- `InventoryMenu` 끝에 실제 장신구 메뉴 슬롯2개를 `addSlot()`으로 추가했다. 기존 제작·방어구·인벤토리·핫바·보조 손 슬롯 번호와 일반 Player Inventory 저장 형식은 유지한다. 생존 화면은 캐릭터 오른쪽/보조 손 위 `(77,8)`, `(77,26)`, 크리에이티브는 생존 인벤토리 탭의 별도 빈 위치를 사용한다.
- `AccessoryInventory`의 서버 슬롯은 **기존 RewardClaimState 권리 UUID와 AccessoryState 착용 UUID의 투영**이다. 추가 물리 장비 DB나 권리 이관은 없다. 소유권·정의·권리·수량1·저장소 준비·다른 칸 중복을 확인한 뒤 실제 클릭 트랜잭션이 커서·인벤토리와 교환한다. 슬롯 직접 set/remove, 복제·모아담기·스택 분배로 투영을 변조하지 못한다.
- 마우스 장착/해제·Shift 이동·핫바/보조 손 교환·Q 버리기를 연결했다. 인벤토리 포화와 ItemTossEvent 취소 시 원상태를 유지한다. 기존 관리 화면도 같은 이동 서비스를 사용한다. 재지급과 시제품 반복 지급은 장착 칸·커서·인벤토리에 이미 있는 장신구를 분실로 계산하지 않는다.
- 이전 별도 화면에서 착용했지만 인벤토리에도 남아 있던 기념 토큰은 착용 칸으로 정리한다. 사망·리스폰·재접속과 장신구 교체가 권리나 현실 쿨타임을 새로 만들지 않는다. 피해량·효과·마나·쿨타임 수치는 그대로다.
- 크리에이티브 생존 인벤토리 탭은 일반 ContainerClick 경로로 서버가 아이템과 커서를 함께 처리한다. 전체 상태 응답으로 커서를 동기화하고 vanilla SlotWrapper 위임을 유지한다. 이 탭에서 휴지통 삭제 대신 빈 칸/드롭을 안내하며, 커서가 차 있으면 다른 아이템 목록 탭으로 이동하기 전에 내려놓도록 한다. 다른 카탈로그 조작은 유지한다.
- 추가48칸과 이전46칸 클라이언트를 섞지 않도록 네트워크 버전을 `accessories-2`로 변경했다. 서버·클라이언트 모두1.0.38로 맞춘다. AI 기본 게임 API JAR 경로도1.0.38로 갱신했다.

## 주요 위치

`accessory/AccessoryInventory.java`, `RewardAccessoryItems`, `AccessoryNetwork`, `prototype/PrototypeGrantService`, `mixin/InventoryAccessorySlotsMixin`, `InventoryAccessoryClickMixin`, `client/accessory/AccessoryInventoryDecoration`, `AccessoryCreativeInventory`, `mixin/client/AccessoryInventoryScreenMixin`, `AccessoryCreativeInventoryMixin`.

## 범위와 남은 확인

검증 결과와 최종 JAR 해시는 위 검증 보고서를 따른다. **운영 서버·개인 런처에 설치하지 않았고 운영 부팅/중지·실제 클라이언트 화면 조작/GUI 배치·새 LLM 검증은 하지 않았다.** 실제 UI는 사용자 클라이언트에서 확인할 단계다. 수치 기획·장신구 이름/아이콘 개별 디자인과 기존 AI 대사 품질 상태는 이번 작업으로 바뀌지 않는다.
