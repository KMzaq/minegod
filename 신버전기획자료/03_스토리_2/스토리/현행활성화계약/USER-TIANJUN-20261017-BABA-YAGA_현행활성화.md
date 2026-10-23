# 바바 야가 — 현행 세계해금·첫 조우
관리키: USER-TIANJUN-20261017-BABA-YAGA

이번 기획으로 채택 완료. 실제 서버 활성화·구현·플레이 시험은 미실행이다.

## 세계 접근
플레이어 본인이 minecraft:mushroom_stew 1개 이상 직접 제작1회. 서버 recipe 결과를 직접 꺼내 소유한 crafting receipt에서 maker UUID/output/itemCount 확인. 직접 채집 OR 합법 거래 재료 모두 허용; 적법 선물 재료도 사용권을 확인하며 절도는 인정0. 완성 스튜를 받기·상자인출·먹기만으로 불가. 제작 뒤 소비·분실해도 세계해금 유지, 첫 조우 현재 스튜소지0.

## 현재 첫 조우
기존 숲 가장자리 실제 오두막 문밖의 허용 안전점 도달. 시간/현재 소지품 제한 없음. 초대없는 실내 진입0. 실내 거래는 실제 초대 AND (합의 거래품 소지 OR 빈손으로 조건만 듣기로 동의) 별도.

부지는 정확 원문의 차원·선호/대체/바닐라 fallback·구조물 OR 자연부지를 보존한다. 상세 판정·초기화·단일본체 상태는 공통계약을 따른다.

## 보존 경계
원문 버섯스튜 직접1회 후보의 효력을 이번 기획으로 채택. 거래/초대/재고/작업점유 원안 보존.

해금자·조우주체·MAIN 소유자·보상 수혜자는 서로 다르다. 현재 같은 본체에 친구가 합류할 때 개인 세계해금을 반복하지 않으며 개인 지식은 실제 습득 출처로만 남긴다.

## 정확 참조
- 인물입력/P0_SCOPE_DECISION-102/USER-TIANJUN-20261017-BABA-YAGA.json — /current_record/outline/unlock_and_encounter/historical_unlock_design, /current_record/outline/unlock_and_encounter/appearance_trigger
- 퀘스트/인물별완결기획/P0_SCOPE_DECISION-102/USER-TIANJUN-20261017-BABA-YAGA.json — /실행_보강

새 판정은 신규 구현 요구다. 최초 미배치만 최초배치 대상으로 보고, 이미 생성된 본체의 미로드·부재·사망·봉인을 새 본체로 복구하지 않는다.
