# 그리스 8명 개인 MAIN · 완결 기획

현재 위임으로 채택한 퀘스트·보상·지식 작업기준이다. 원문의 정체·개인 독점·세계 공통 해금·무보상 경계를 유지한다. 서버 구현/등록/실플레이 검증 완료를 뜻하지 않는다.

8개 MAIN, 별도 무기 획득 SIDE 2개, 선택 서비스와 디오니소스 술 의뢰를 서로 다른 권리로 다룬다. 각 인물 JSON의 source_preserved는 원문이고 new_execution_decisions가 현재 실행 기획이다. 공통 계약은 00_공통실행계약.json을 따른다.

## 빠른 색인

- RPG-S01-004 하데스: 돌려준 이름의 빈자리, 예상 20~35분. MAIN APPLICABLE, 원문 보상 NOT_APPLICABLE.
- RPG-S01-005 데메테르: 마른 천에 남길 몫, 예상 12~22분. MAIN APPLICABLE, 원문 보상 APPLICABLE.
- RPG-S01-007 아테나: 자기가 깨뜨린 전술, 예상 20~35분. MAIN APPLICABLE, 원문 보상 APPLICABLE.
- RPG-S01-008 아폴론: 마지막에 남은 다른 박자, 예상 10~18분. MAIN APPLICABLE, 원문 보상 APPLICABLE.
- RPG-S01-009 아르테미스: 새끼 길을 비운 사냥선, 예상 18~30분. MAIN APPLICABLE, 원문 보상 APPLICABLE.
- RPG-S01-012 헤파이스토스: 흠집보다 손에 남는 것, 예상 15~25분. MAIN APPLICABLE, 원문 보상 APPLICABLE.
- RPG-S01-013 헤르메스: 받을 손이 비어 있는 동안, 예상 12~24분. MAIN APPLICABLE, 원문 보상 APPLICABLE.
- RPG-S01-014 디오니소스: 떠날 자리까지 남기는 흥, 예상 18~30분. MAIN APPLICABLE, 원문 보상 APPLICABLE.

## RPG-S01-004 하데스 · 돌려준 이름의 빈자리

생산 제공자 ID: mythictrpg:greek_underworld_hades / MAIN ID: mythictrpg:p0m1_hades_personal_main

### 원문을 유지한 부분

그리스·로마 전승의 신 하데스. 명계의 경계와 그 안에 남은 이들의 몫을 지키는 지배자다.
절박하게 돌아가려는 한 사람을 보면서도 말없이 남은 사람을 놓치지 않는다. 차갑다는 평판을 피하려고 값싼 위로를 주지는 않는다. 자기 판단의 오류를 알면서 붙드는 것이야말로 자기가 지키는 질서를 허문다고 여긴다.

원문 카드: PE-MG-S01-004-01
하데스는 실제 확인된 살아 있는 당사자의 오기 한 항목을 취소한다. 빠른 차단은 당장 잘못 들어가는 길을 막지만 다른 길 복구가 남고, 두 표식 보존은 더 긴 답사와 위험을 치른 결과다. 죽은 사람은 부활하지 않는다.

원문 보상 근거: 이 이야기의 대가는 돌려준 이름의 빈자리의 실제 관계·처우·작업 결과다. 장비·가호 지급을 목표로 삼지 않는 역할 선택이며 본편의 기존 정산은 별도로 보존한다.

### 수락과 기존 세계 조건

세계의 유효 C08 경계 통행 결과와 안전한 네더 경계가 확보된 뒤, 허락된 두 심사 사본과 같은 이름의 서로 다른 식별 항목이 실제 존재할 때 제시한다. 새로운 C06/C15·전투력 컷·개인 반복 통행세 없음.

### 실제 장소·현물·배우

- selection: 기존 C08 네더 심사 자리 A, 허락된 원본 보관점 R, 실제 갈림길 J, 기존 귀환점 E를 선택. 모두 같은 기존 통행권 범위. 새 차원/하데스 대역/신전 없음.
- layout: A–R 도보 24~64블록, A–J 12~32블록, J의 두 가지 끝은 각각 8~20블록. 용암·허공 위를 새 필수 길로 삼지 않고 왕복 가능한 마른 기존 지면을 고른다. 범위에 맞는 검증된 후보 없으면 제시만 보류하고 현장 준비를 먼저 한다.
- actors: 살아 있는 청원자는 현재 동의한 MAIN 수락 플레이어 본인을 기본으로 한다. 다른 살아 있는 기존 플레이어가 자발적으로 청원하면 그 UUID로 결속할 수 있다. 죽은 쪽은 C08에 이미 존재하는 기록 항목만 사용하고 실체/새 핵심 인물을 만들지 않는다.
- objects: 두 사본·원본은 C08 자료의 read-only 항목과 각각의 identity_ref/time_ref로 결속. 같은 표시 이름이어도 별개 ID 유지. 이미 있는 표식 2개는 block position+revision으로 잠근다.

### 실제 목표와 완료 증거

- - objective_key: identify_two_records
  - action: A의 두 사본을 각각 실제 열람하고 살아 있는 청원자와 같은 자리에서 신원/현재 의사를 확인한다.
  - quantity: 2
  - scope: 개인 MAIN 소유자의 실제 수행
  - time: 시간 제한 없음
  - cost: 별도 제출 비용 없음
  - requires: 
  - completion_evidence: 사본 read_event 2개, 다른 identity_ref 2개, 청원자 alive=true 및 자기 응답 event. 이름 문자열 일치만으로 통합 불가.
- - objective_key: compare_originals
  - action: R까지 실제 이동해 원본 두 항목을 대조하고 A로 돌아와 원본은 제자리에 둔 채 바뀐 전사 순서를 보고한다.
  - quantity: 2
  - scope: 개인 MAIN 소유자의 실제 수행
  - time: 시간 제한 없음
  - cost: 별도 제출 비용 없음
  - requires: - identify_two_records
  - completion_evidence: R 3블록 이내 열람 2건+왕복 위치 기록+하데스의 실제 보고 수신. 원본 반출/찢기 불허.
- - objective_key: choose_and_repair
  - action: J에서 FAST_BLOCK 또는 KEEP_BOTH를 고르고 아래 실제 표식/길 작업을 완료한다.
  - quantity: 1
  - scope: 개인 MAIN 소유자의 실제 수행
  - time: 시간 제한 없음
  - cost: 별도 제출 비용 없음
  - requires: - compare_originals
  - completion_evidence: 아래 분기 블록/표식 revision과 실제 왕복 통과 증거. 말한 계획으로 통과 불가.
- - objective_key: record_will_and_judgment
  - action: 살아 있는 청원자의 귀환 또는 잔류 의사 1건을 실제 확인하고 하데스에게 오기 한 항목만 정정하도록 결과를 전달한다.
  - quantity: 1
  - scope: 개인 MAIN 소유자의 실제 수행
  - time: 시간 제한 없음
  - cost: 별도 제출 비용 없음
  - requires: - choose_and_repair
  - completion_evidence: 청원자 응답+해당 분기 현장 증거+하데스의 correction event. 다른 망자 항목 unchanged.

### 선택과 결말

- - branch_key: FAST_BLOCK
  - available_when: J의 잘못 향한 진입구 폭2·높이2와 기존 우회에 사용 허락이 있음
  - actual_action: 자기 조약돌4개로 지정 2×2 진입구를 막고 생존자 표식1개를 기존 안전 귀환쪽으로 바로잡는다. 소유자가 우회 끝 E까지 걸어 돌아와 안전함을 확인한다.
  - result: 오기1건 취소. 다른 가지 복원은 미완 표기. 원문대로 정정 완료와 길 전체 복구를 분리한다.
  - main_complete: true
- - branch_key: KEEP_BOTH
  - available_when: 원본 두 항목 확인 및 두 가지 접근 허락
  - actual_action: 기존 표식2개를 각 원본 방향과 일치시킨 뒤 소유자가 J→왼끝→J→오른끝→J를 실제 왕복. 현재 적은 우회/엄폐/합법 전투 중 선택하며 처치 수 목표0.
  - result: 두 실제 통행 증거가 모두 있을 때 양쪽 보존, 한쪽만이면 완료 대기.
  - main_complete: true
- - branch_key: REQUEST_ERASE_OTHER
  - available_when: 현재 대면
  - actual_action: 다른 망자 항목 삭제 요청은 거절. 생존 근거만 접수하고 두 항목 대조부터 재개할 수 있다.
  - result: 명의 오기와 사망 자체는 다르며 부활0, 보상0.
  - main_complete: false
- - branch_key: REMAIN
  - available_when: 청원자가 현재 잔류를 명시
  - actual_action: 귀환을 강제하지 않고 허락된 안전 자리로 실제 이동한 위치를 남긴다.
  - result: 오기 정정은 가능하나 귀환 성취는 기록하지 않는다.
  - main_complete: true

### 다른 인물·본편과의 경계

페르세포네의 별도 출입권이 필요한 이동을 고르면 실제 본체·접근·요청 수신·답변이 필요하다. 기본 MAIN 완료는 기존 C08 허용구역의 오기 정정까지다. 추가 출입권은 OPTIONAL_PENDING/GRANTED/REFUSED로 별도 저장하며 미수신을 승인으로 만들지 않는다. 라다만티스 현재 면담을 새 선행으로 만들지 않는다.

### 실패·사망·중단·재개

자료 원본 누락은 그 항목만 대기. 블록이 훼손되면 현재 revision 재검사 후 미복구 부분만 수행한다. 원본/망자 기록을 임의 복제하지 않는다. 사망/끊김에 본인 선택·열람·정정된1항목과 실제 세계 상태를 보존한다. 차단 재료는 실제 설치된 만큼만 사용하고 MAIN 포기로 재생하지 않는다.

### MAIN 완료 판정

공통 소유자 확인 AND 원본 두 식별 대조 AND 선택한 실제 길 작업 AND 당사자 의사 AND 하데스의 실제 오기1건 정정. 선택한 길 결과를 소유자가 확인·수신해야 한다. 물품/가호/장비/주시 보상0.

### 보상과 수령



### 별도 선택 서비스



### Story Fact와 개인 지식

- - fact_definition_id: mythictrpg:p0m1_hades_fact_transcription
  - statement: 전사 순서의 오류가 원본에서 확인되었다.
  - activation: 해당 실제 수행/수신 event_uuid가 확정된 뒤만. 작성시 기본locked.
  - speaker_condition: R에서 실제 읽은 소유자/동행, 보고를 받은 하데스만.
  - disclosure: 신원 원문은 당사자 허락 수신자 한정.
  - fact_and_knowledge_separate: true
  - evidence_fields: - world_id
    - source_event_uuid
    - actor_uuid
    - observed_at
    - source_mode
    - actual_audience_uuids
    - permission_scope
  - memory_rule: 현재본체가 직접관측/실제보고를 수신한 부분만 자기앎. 같은 player_uuid의 실제기억으로만 다음만남을변형하며 후발자는 허락된새열람으로 학습.
- - fact_definition_id: mythictrpg:p0m1_hades_fact_paths
  - statement: 빠른 차단 또는 양쪽 보전의 실제 길 상태.
  - activation: 해당 실제 수행/수신 event_uuid가 확정된 뒤만. 작성시 기본locked.
  - speaker_condition: 현재 길을 보는 사람은 공개 외형만.
  - disclosure: 살아 있는 청원자의 이름·사정은 자동 공개하지 않는다.
  - fact_and_knowledge_separate: true
  - evidence_fields: - world_id
    - source_event_uuid
    - actor_uuid
    - observed_at
    - source_mode
    - actual_audience_uuids
    - permission_scope
  - memory_rule: 현재본체가 직접관측/실제보고를 수신한 부분만 자기앎. 같은 player_uuid의 실제기억으로만 다음만남을변형하며 후발자는 허락된새열람으로 학습.
- - fact_definition_id: mythictrpg:p0m1_hades_fact_correction
  - statement: 하데스가 한 살아 있는 이의 오기를 정정했다.
  - activation: 해당 실제 수행/수신 event_uuid가 확정된 뒤만. 작성시 기본locked.
  - speaker_condition: 판정 청중과 허락받은 후발 열람자.
  - disclosure: 죽은 사람이 살아났다는 사실을 생성하지 않는다.
  - fact_and_knowledge_separate: true
  - evidence_fields: - world_id
    - source_event_uuid
    - actor_uuid
    - observed_at
    - source_mode
    - actual_audience_uuids
    - permission_scope
  - memory_rule: 현재본체가 직접관측/실제보고를 수신한 부분만 자기앎. 같은 player_uuid의 실제기억으로만 다음만남을변형하며 후발자는 허락된새열람으로 학습.
- - fact_definition_id: mythictrpg:p0m1_hades_fact_passage
  - statement: 페르세포네가 별도 요청에 실제로 답한 범위.
  - activation: 해당 실제 수행/수신 event_uuid가 확정된 뒤만. 작성시 기본locked.
  - speaker_condition: 해당 요청/답변 수신자만.
  - disclosure: 응답 전 locked; MAIN 완료와 별개.
  - fact_and_knowledge_separate: true
  - evidence_fields: - world_id
    - source_event_uuid
    - actor_uuid
    - observed_at
    - source_mode
    - actual_audience_uuids
    - permission_scope
  - memory_rule: 현재본체가 직접관측/실제보고를 수신한 부분만 자기앎. 같은 player_uuid의 실제기억으로만 다음만남을변형하며 후발자는 허락된새열람으로 학습.

### 조건부 대사 예시

- “네가 살아 있다는 것과 저 이름을 지우는 일은 같지 않다.”
- “내가 맞춘 줄이 틀렸다. 그 줄만 고친다.”
- “이 이름의 정정과 저 길의 미완을 따로 남긴다.”

### 시간·보상 적정성

전투 강제0·처치/경제 보상0. 2개 기록을 분리하고 각 길을 몸으로 확인하는 탐색/판단 경험이 핵심이다. 빠른 차단은 조약돌4와 짧은 우회, 양쪽 보전은 더 긴 답사 대신 기존 출입을 덜 제한한다. 고등급 NPC 라는 이유만으로 높은 전투컷을 만들지 않는다.

### 새 구현 요구

- 심사 항목 identity/time 대조 UI
- 표식 revision·허락된 설치·왕복 경로 관측
- 당사자 의사/페르세포네의 별도 응답 상태

### 구현 인수 사례

- - 동명이인
  - 동일 표시 이름·다른 ID의 원본 2건을 끝까지 유지.
- - 차단 결말
  - 오기 정정 완료 가능, 양쪽 길복구 사실은 false.
- - 죽은 기록 선택
  - 살아 있는 actor로 바뀌지 않고 부활0.
- - 페르세포네 미수신
  - 출입권0; 기본 기존 구역 정정은 별도.

출처: 기존페르소나_인물별/RPG-S01-004/profile.json, 기존페르소나_인물별/RPG-S01-004/authoring_contract.json, 772명_스토리와기본페르소나_통합.json#/RPG-S01-004/outline, 원문제작범위_보존색인.json#/RPG-S01-004


## RPG-S01-005 데메테르 · 마른 천에 남길 몫

생산 제공자 ID: mythictrpg:greek_olympus_demeter / MAIN ID: mythictrpg:p0m1_demeter_personal_main

### 원문을 유지한 부분

데메테르. 오늘의 곡식과 다음 파종을 함께 생각하는 농경의 여신.
마음이 상하면 안부보다 곡식이 젖었는지를 먼저 묻는다. 무뚝뚝한 걱정에는 손으로 할 수 있는 작은 보살핌이 따른다. 기다림에 마음이 붙들려도 먹고 살아야 하는 사람의 오늘을 지워 버리지 않으려 한다.

원문 카드: EP005A-RPG-S01-005
남은 종자 천이 묶이고 다음 작업 자리가 정해진다. 오늘 먹을 사람의 몫이 딸 소식과 분리된다.

원문 보상 근거: 새 개인 MAIN 보상안: 실제 파종·보충을 마친 소유자는 경작자가 사전 허락한 수확/씨앗 몫과 데메테르의 다음 파종 지도를 받는다. 남겨야 할 종자를 보상으로 소진하지 않는다. 일반 식량·종자이며 자동 풍요 가호가 아니다.

### 수락과 기존 세계 조건

데메테르의 현행 초기 생활권 조우 뒤, 사용 허락된 기존 밭과 실제 재고를 확인해 제시한다. 수확 이력은 관심 근거이지 새 세계 해금/전원 입장료가 아니다. 경작자는 기존 밭의 소유 플레이어 또는 이미 존재하는 해당 밭 일반 주민으로 결속한다.

### 실제 장소·현물·배우

- selection: 현재 본체와 적법하게 왕래 가능한 기존 마을 밭, 빈 경작 가능16칸과 보관처. 새 농장이나 마을 대표를 생성하지 않는다.
- layout: 보관처 A에서 밭 4×4칸까지 8~32블록. 이미 심긴 칸을 뽑아 목표를 만들지 않는다. 밭 총16빈칸은 연속하지 않아도 한 소유 허락 구역 안이면 허용.
- inventory_lock: 수락 전 실제 당근24개를 경작자 소유 현물로 확인: 식량8, 파종16. 별도 보상 재고 빵2+당근4를 미리 예약한다. 보상은 파종16에서 떼지 않는다. 재고 부족이면 수락만 대기하고 일반 식사/대화는 유지.

### 실제 목표와 완료 증거

- - objective_key: allocate
  - action: 실제 보관처에서 당근24개를 식량8과 파종16으로 나누어 경작자에게 두 수량을 확인받고 A/B를 선택한다.
  - quantity: - food: 8
    - seed: 16
  - scope: 개인 MAIN 소유자의 실제 수행
  - time: 시간 제한 없음
  - cost: 별도 제출 비용 없음
  - requires: 
  - completion_evidence: 각 슬롯 실제 stack 수량·경작자 동의·선택 revision.
- - objective_key: supply
  - action: A는 자기 구운감자8개를 식량 보관처에 실제 인계. B는 기존 파종당근8개를 오늘 먹을 곳에 실제 인계한 뒤 자기 당근8개를 부족종자 보관처에 실제 보충.
  - quantity: 8
  - scope: 개인 MAIN 소유자의 실제 수행
  - time: 시간 제한 없음
  - cost: A 구운감자8 또는 B 당근8만 의무 제공. 타인 재고 무단 차감0.
  - requires: - allocate
  - completion_evidence: A: baked_potato 인수8. B: 기존 carrot 이동8 및 신규 허락 carrot 인수8. 공급자의 소유·기증 동의 기록.
- - objective_key: sow_or_handover_seed
  - action: A는 합의된 당근16개를 지정 빈16칸에 실제 파종. B는 보충 뒤 종자16개 전량을 경작자에게 실제 인계하거나, 경작자와 별도 합의한 경우에만16칸 파종한다.
  - quantity: 16
  - scope: 개인 MAIN 소유자의 실제 수행
  - time: 시간 제한 없음
  - cost: 파종시 종자16 실제소비. 단순인계시 소유권/보관권 이전만, 소비0.
  - requires: - supply
  - completion_evidence: A/선택파종: 소유자 plant_event16+현재 crop block. B인계: 원종자8+보충8의 source/quantity 및 경작자 실제 인수16. 두 갈래를 AND하지 않는다.
- - objective_key: settle
  - action: 현재 식량·손실과 선택한 파종16 또는 종자인수16의 실제 결과를 경작자와 대조하고 데메테르의 완료 확인을 받는다.
  - quantity: 1
  - scope: 개인 MAIN 소유자의 실제 수행
  - time: 시간 제한 없음
  - cost: 별도 제출 비용 없음
  - requires: - sow_or_handover_seed
  - completion_evidence: 경작자 확인+선택 branch의 plant16 OR seed_receipt16+데메테르 실제 수신. 성숙/수확대기 불필요.

### 선택과 결말

- - branch_key: KEEP_SEEDS
  - available_when: 원재고24와 보상 별도예약
  - actual_action: 파종16을 그대로 보전하고 구운감자8을 식량8과 별도로 보완한다.
  - result: 오늘 식량은 원당근8+제공감자8, 파종16.
  - main_complete: true
- - branch_key: EAT_AND_REPLACE
  - available_when: 경작자가 오늘 당근8 추가 사용에 동의
  - actual_action: 파종분 중8을 식량으로 옮기고 자신의 당근8을 실제 보충한다. 남은원종자8+보충8=종자16을 경작자에게 실제 인계하면 완료경로 성립. 당사자합의로 직접파종16을 대신 선택할 수도 있으나 인계와파종 둘다 요구하지 않는다.
  - result: 오늘 당근16, 다음파종용종자16의 실제보관/인수 또는파종. 약속만은미이행.
  - main_complete: true

### 실패·사망·중단·재개

작물 파괴·짐 손실은 실제손실로 기록. A/선택파종은 약속16칸의 최종상태를, B인계는 종자16 인수receipt를 확인한다. B인계완료 뒤 경작자가 언제심는지는 MAIN 기한/추가목표가 아니다. 이미심은행위와 인수는개인경험으로보존. 본인고의훼손/반복공로추가0. 배고파먹었다는이유로저주·자동관계박탈없음. 로그아웃/사망뒤미완료물량만재개.

### MAIN 완료 판정

선택공급8 인수 AND (A 파종16 OR B 종자16인계 OR B가선택한파종16) AND 손실공개/경작자확인/데메테르수신. C02/C06/C28/R05의같은실제근거만재사용하며장전체선행/재연없음. 페르세포네귀환일은목표아님.

### 보상과 수령

- - reward_definition_id: mythictrpg:p0m1_demeter_harvest_share
  - kind: ordinary_items
  - recipient: 실제 완료한 MAIN 소유자
  - lines: - - item_id: minecraft:bread
      - count: 2
    - - item_id: minecraft:carrot
      - count: 4
  - earned_condition: 필수 결과 완료 및 별도 예약 재고 인도
  - limit: 개인 최초 1회
  - delivery: 공통 수령 원장 규약. 실제 수행/권리 생성/실수령은 별도. 가방 부족은 바닥 드롭 없이 미수령 보존. 이미 지급된 줄은 다시 지급하지 않는다.
- - reward_definition_id: mythictrpg:p0m1_demeter_sowing_lesson_right
  - kind: instruction
  - recipient: MAIN 소유자
  - lines: - - service_ref: mythictrpg:p0m1_demeter_sowing_lesson
      - count: 1
  - earned_condition: MAIN 완료
  - limit: 개인 최초 1회
  - delivery: 공통 수령 원장 규약. 실제 수행/권리 생성/실수령은 별도. 가방 부족은 바닥 드롭 없이 미수령 보존. 이미 지급된 줄은 다시 지급하지 않는다.

### 별도 선택 서비스

- - definition_id: mythictrpg:p0m1_demeter_sowing_lesson
  - title: 다음 고랑을 읽는 법
  - kind: optional_instruction
  - entry: 해당 지도권1·데메테르 대면·밭 사용허락
  - steps: 같은 허락 밭의 실제4곳을 돌아보며 물·지면·심을종류를 확인한다. 파종형은 이미심긴자리, 인계형은 경작자가 다음에쓸빈고랑을 볼 수 있다. 데메테르의 현재관측설명을듣고 한곳을 대조한다. 직접파종·새제출은 이지도에도 필수아님.
  - quantity: 4곳 방문+설명 수신1회
  - time: 4~6분, 제한시간 없음
  - completion: 실제4곳 관측·설명수신 event; 원문 다음 파종 지도 1회 소진. 기술/수확량 버프/가호/주시0.
  - failure: 중단 전 관측 유지, 같은 지도 미완료만 재개.
  - cost: 0; 기존 도구를 빌렸을 때만 원도구 반환.

### 제공 재고·예약·보충

- provider: 현재 밭의 경작자
- initial: MAIN 유일 소유자용 빵2+당근4 한 묶음, 별도 종자/식량24와 분리
- reservation: 최초 MAIN 수락 전에 현재 제공권/현물을 확인하고 한 묶음 예약
- deduct: 실수령 journal의 각 줄에서만 차감
- refill: MAIN 최초1회이므로 자동 보충/반복 지급 없음. 파손/손실 전에 아직 예약이 안 되었으면 실제 경작자 재고에서 동일수량을 준비한 뒤 제시
- budget: B2 생활 의뢰의 소량 복합 보상; 지도는 전투/영구 성장권0.

### Story Fact와 개인 지식

- - fact_definition_id: mythictrpg:p0m1_demeter_fact_allocation
  - statement: 실제로 합의한 오늘 먹을 몫과 파종량.
  - activation: 해당 실제 수행/수신 event_uuid가 확정된 뒤만. 작성시 기본locked.
  - speaker_condition: 경작자·소유자·실제 들은 데메테르.
  - disclosure: 다른 경작자 재산/당사자 허락 자동 유추 금지.
  - fact_and_knowledge_separate: true
  - evidence_fields: - world_id
    - source_event_uuid
    - actor_uuid
    - observed_at
    - source_mode
    - actual_audience_uuids
    - permission_scope
  - memory_rule: 현재본체가 직접관측/실제보고를 수신한 부분만 자기앎. 같은 player_uuid의 실제기억으로만 다음만남을변형하며 후발자는 허락된새열람으로 학습.
- - fact_definition_id: mythictrpg:p0m1_demeter_fact_sowing_or_handover
  - statement: 선택한16칸 파종 또는 종자16 실제인수.
  - activation: 해당 실제 수행/수신 event_uuid가 확정된 뒤만. 작성시 기본locked.
  - speaker_condition: 현재밭상태는현장관측자, 종자인수는실제당사자/허락된수신자.
  - disclosure: 인계분기를이미자란밭으로쓰지않는다.
  - fact_and_knowledge_separate: true
  - evidence_fields: - world_id
    - source_event_uuid
    - actor_uuid
    - observed_at
    - source_mode
    - actual_audience_uuids
    - permission_scope
  - memory_rule: 현재본체가 직접관측/실제보고를 수신한 부분만 자기앎. 같은 player_uuid의 실제기억으로만 다음만남을변형하며 후발자는 허락된새열람으로 학습.
- - fact_definition_id: mythictrpg:p0m1_demeter_fact_settlement
  - statement: 선택한 부족분이 실제 해소되고 손실이 공개되었다.
  - activation: 해당 실제 수행/수신 event_uuid가 확정된 뒤만. 작성시 기본locked.
  - speaker_condition: 경작자와 유효 보고 수신자.
  - disclosure: 딸의 일정/귀환과 결부하지 않는다.
  - fact_and_knowledge_separate: true
  - evidence_fields: - world_id
    - source_event_uuid
    - actor_uuid
    - observed_at
    - source_mode
    - actual_audience_uuids
    - permission_scope
  - memory_rule: 현재본체가 직접관측/실제보고를 수신한 부분만 자기앎. 같은 player_uuid의 실제기억으로만 다음만남을변형하며 후발자는 허락된새열람으로 학습.

### 조건부 대사 예시

- “그 천은 내일 밭으로 가야 해. 오늘 배고픈 사람도 여기 있고.”
- “싹은 아직이야. 심은 것은 보이네.”
- “내일 어느 고랑부터 할까.”

### 시간·보상 적정성

파종형은16칸작업+식량8제공, 보충형은당근8보충+종자16실제 인계에 빵2+당근4와지도. 분기노력차이는식량사정과공급부담의선택이며직접파종을전원정답으로강제하지않는다. 버프/반복 경제0, 예상12~22분. 성숙대기없음.

### 새 구현 요구

- 재고 분리/예약과 인수 journal
- player별 plant_event와 실제 밭 허락/손실 대조

### 구현 인수 사례

- - 종자에서 보상 떼기
  - 예약 보상과 파종16 별도여서 불가.
- - B 당근을 옮기고 보충하지 않음
  - 실제 부족8, 완료0.
- - 이미 심긴 칸 뽑기
  - 새 파종 공로를 늘리지 않음.
- - 지도 사양
  - MAIN 완료와 식량권 유지.
- - 보충8+종자인계16만 수행
  - 파종0이어도 B 정상완결·동일보상권.

출처: 기존페르소나_인물별/RPG-S01-005/profile.json, 기존페르소나_인물별/RPG-S01-005/authoring_contract.json, 772명_스토리와기본페르소나_통합.json#/RPG-S01-005/outline, 원문제작범위_보존색인.json#/RPG-S01-005


## RPG-S01-007 아테나 · 자기가 깨뜨린 전술

생산 제공자 ID: mythictrpg:greek_olympus_athena / MAIN ID: mythictrpg:p0m1_athena_personal_main

### 원문을 유지한 부분

그리스·로마 전승의 올림포스 여신 아테나. 실제로 쓸 수 있는 계획과 기술을 중히 여긴다.
번뜩이는 수를 좋아하지만 실패했을 때 돌아올 사람이 빠진 계획은 마음에 들지 않는다. 지혜롭다는 칭찬을 얻고 사람을 잃는 것보다 자기 초안의 허점을 보여 주는 불편을 택한다. 누구나 가르쳐 줄 다정한 선생이기보다 함께 일할 만한 판단을 고르는 설계자다.

원문 카드: PE-MG-S01-007-01
추격으로 실제 위협을 제거했는지와 마지막 사람·짐을 지켰는지는 각각 판정한다. 둘 다 잃으면 실패다. 아테나는 자기 공격안의 빈틈을 현장 참가자 앞에서 인정하고 성공한 동작만 지도에 쓴다.

원문 보상 근거: 신규 저술안: 실제 철수 행동을 마친 참가자에게 자기 장비로 엄폐·후퇴 교대 기술을 배우는 개별 지도 한 차례를 제안한다. 기술의 실행 성공은 실제 연습으로 확인하며 수치 버프는 미정이다.

### 수락과 기존 세계 조건

현행 생활권에서 만난 아테나가 허락된 길과 실제 인접 약탈 위협을 확인했을 때 제시한다. 안전 답사·반박은 전투 최소컷 없음. 실제 전투는 귀환로·장비·식량·참가 동의 재검사 뒤 시작한다.

### 실제 장소·현물·배우

- selection: 기존 작업장 옆 A와 앞담 P1, 뒤턱 P2, 안전선 E. A~E 기존 길40~64블록, 엄폐 P1/P2 간8~16블록, 뒤턱 통로 폭1~2블록. 무너진 길의 기존 통행 가능한 바깥 우회도 남긴다.
- threat: 실제로 존재하고 적대 상태인 약탈자 무리에서 원거리 위협2체 이하와 원래 우두머리로 구별되는1체를 instance_uuid로 결속한다. 기존 minecraft:pillager 계열의 현행 수치/장비/공격 규칙을 유지한다. 고유 보스/인물/강제 무적을 새로 만들지 않는다. 무리 없으면 안전 답사만 가능하며 없는 전투 승리를 만들지 않는다.
- cast: 소유자1+자발적 동행자0~4. 마지막 이동자는 실제 동의한 참가자1, 혼자라면 아테나 자신이 자기 돌판을 들고 마지막 이동 역할을 맡겠다고 실제 답한 경우 가능. 해당 동의 전 실전 시작 없음. NPC 본체를 복제하거나 자동 공격지원으로 쓰지 않는다.
- cargo: 마지막 이동자 소유의 실제 일반 물품1 stack을 봉인 위탁 묶음 상태로 결속. 새 경제 아이템/전리품 아님. 원소유·현보유·반환만 관리.

### 실제 목표와 완료 증거

- - objective_key: walk_with_load
  - action: 자기 실제 짐을 들고 A→P1→P2→E→A를 왕복하여 마지막 이동자의 통과 공간을 확인한다.
  - quantity: 5
  - scope: 개인 MAIN 소유자의 실제 수행
  - time: 시간 제한 없음
  - cost: 별도 제출 비용 없음
  - requires: 
  - completion_evidence: 소유자의 순서 위치기록5, 실제 cargo serial, P2에서 마지막 이동자의 실제 통과1. 텔레포트/친구 답사 대체0.
- - objective_key: choose_duty
  - action: 후미 엄호 또는 우두머리 추격 중 하나와 손실 위험을 고지받고 실제 담당·철수 명부를 확정한다.
  - quantity: 1
  - scope: 개인 MAIN 소유자의 실제 수행
  - time: 시간 제한 없음
  - cost: 별도 제출 비용 없음
  - requires: - walk_with_load
  - completion_evidence: 소유자 선택 revision, 동행자 역할동의, 실제 적 UUID/귀환선 확인.
- - objective_key: field_action
  - action: 명부의 실제 현재 위협 앞에서 선택한 철수 또는 추격 행동을 수행한다.
  - quantity: 1
  - scope: 개인 MAIN 소유자의 실제 수행
  - time: 실전 시도180초 서버가동tick; 전체 MAIN 기한 없음
  - cost: 별도 제출 비용 없음
  - requires: - choose_duty
  - completion_evidence: 아래 분기별 실제 위치·보존·피해/제압·남은 위협을 별도 기록.
- - objective_key: own_the_result
  - action: 소유자가 아테나에게 사람/짐 보존과 우두머리 제압을 각각 보고하고 실제 근거로 판정받는다.
  - quantity: 1
  - scope: 개인 MAIN 소유자의 실제 수행
  - time: 시간 제한 없음
  - cost: 별도 제출 비용 없음
  - requires: - field_action
  - completion_evidence: own_result = escort_success OR leader_defeated. 둘다 false면 실패. 아테나 실제 수신과 자기 계획 오류 인정은 해당 결과를 본 청중에 한정.

### 선택과 결말

- - branch_key: REARGUARD
  - available_when: 마지막 이동자·위탁물·우회로 동의 확인
  - actual_action: 소유자가 P1/P2에서 각각 살아 있는 위협의 사선을 엄폐로 끊은 상태1초를 실제 유지하고, 마지막 이동자12블록 안에서 두 엄폐 구간을 함께 지나 E에 도착한다. 마지막 이동자가 E에 도착·위탁물을 인수한 뒤 소유자도 E로 귀환한다.
  - result: escort_success=true는 마지막 사람 생존+짐 인수+소유자 실제 귀환 때. 남은 적은 alive 상태 그대로이고 제거 사실0.
  - main_complete: true
- - branch_key: PURSUIT
  - available_when: 실제 우두머리 UUID와 접근 가능한 경로 확인
  - actual_action: 소유자가 해당 우두머리에 유효 공격 또는 추격 경로의 사선 차단/견제 역할을 실제 수행하며 제압에 참여한다. 표적 사망/항복은 기존 서버 지원 결과만 인정하고 동료의 처치 단독복사0.
  - result: leader_defeated와 사람/짐 결과를 별도로 저장. 우두머리 제압에 실제 기여하고 소유자가 안전선 귀환하면 MAIN 결과 성립. 철수 기여 없는 사람에게 지도권은 발생하지 않는다.
  - main_complete: true
- - branch_key: BOTH_LOST
  - available_when: 시도180초 종료 또는 소유자가 위험 철수 요청
  - actual_action: 현재 생존자/짐 위치와 적 상태를 확인하고 가능한 안전 철수.
  - result: 실제 제압0이고 사람/짐 보존0이면 MAIN 완료0. 실패 책임을 성공 대사로 포장하지 않는다.
  - main_complete: false

### 다른 인물·본편과의 경계

헤라클레스의 기존 인연은 선택 협력. 현재 동일 본체와 실제 요청/수락이 있을 때 지정 장애물1개 제거만 맡는다. 부재/거절이면 기존 안전 우회를 사용한다. 다이달로스의 현재 설계·부활·승인을 요구하지 않는다.

### 실패·사망·중단·재개

시도 종료는 남은 실제 적을 소멸시키거나 죽은 적을 부활시키지 않는다. 같은 남은 위협·현물로 미완료를 이어 가며 완료한 제압/인수는 되감지 않는다. 위협이 모두 사라졌는데 양쪽 결과가 실패라면 그 손실을 보존하고, 재도전은 이후 실제 다른 약탈 위협을 확인한 새 attempt로만 연다. 로그아웃은 시도시계가 흐르지만 전체 소유 유지. 유효기여 뒤 사망한 참가자는 기여 기록을 보존하되 소유자 최종 결과 확인/귀환은 생존 복귀 후 직접 한다. 실제 사망한 일반 배우를 부활시키지 않는다.

### MAIN 완료 판정

선택 분기의 실질 성과 하나 이상과 소유자의 실제 기여/귀환/보고 수신. 안전 답사만으로 완료 불가. 비소유 도움자는 MAIN은0이나 자신의 실제 후미 철수 성공 기여에 따른 지도권은 별도 가능.

### 보상과 수령

- - reward_definition_id: mythictrpg:p0m1_athena_rearguard_instruction
  - kind: learn_skill_after_lesson
  - recipient: 실제 성공 철수에 기여한 각 UUID, 소유자와 독립
  - lines: - - content_ref: mythictrpg:greek_olympus_athena_rearguard_step
      - count: 1
  - earned_condition: 그 참가자가 P1/P2 중1곳 이상에서 실제 엄폐를 유지하며 후미와 함께 E 귀환+사람/짐 인수 성공. 이후 자기 선택 지도 실연 완료
  - limit: 개인 최초 1회
  - delivery: 공통 수령 원장 규약. 실제 수행/권리 생성/실수령은 별도. 가방 부족은 바닥 드롭 없이 미수령 보존. 이미 지급된 줄은 다시 지급하지 않는다.

### 별도 선택 서비스

- - definition_id: mythictrpg:p0m1_athena_rearguard_lesson
  - title: 후미 교대 실연
  - kind: optional_skill_instruction
  - entry: 개인 철수 기여권+아테나 실제 대면+평탄한 뒤공간3블록+자기 기존 장비
  - steps: 첫 설명을 실제 듣고 K/J의 임시 연습권으로 후미 교대를2회 수행한다. 각 시도는 뒤2.4블록 이동 끝에 지정 엄폐의 가려진 영역에 서야 성공. 시선 고정 뒤 방향과 충돌/지지면 정지를 그대로 적용한다. 연습공간의 피해0 표시선은 몬스터 공격이 아니며 무적 보상이 아니다.
  - quantity: 정상2회; 실패 교정은 같은 권리로 반복
  - time: 4~7분, 전체 제한 없음; 스킬쿨160tick 유지
  - cost: 시도마다 마나20, 기본200과 초당2 회복 사용; 추가 아이템0
  - completion: 실제 설명수신+성공2회 뒤 기존 OLY-ATH-L01 동일 skill_id learn_skill1. 실패/지도 사양은 MAIN을 되돌리지 않는다.
  - failure: 벽에 걸리면 그 시도 실패, 이미 성공한1회 보존. 마나/쿨 환불0. 준비 부족·끊김이면 잔여 연습만 재개.
  - content_ref: mythictrpg:greek_olympus_athena_rearguard_step
  - skill_key_parameters: - mana: 20
    - cooldown_ticks: 160
    - distance: 2.4
    - move_ticks: 4
    - invulnerability_ticks: 0
    - timing: [0,2)준비,[2,6)이동,[6,10)후딜
    - intrinsicPower: 15
  - adoption: 기존 실제 참가자 지도에서 성능이 미정이던 부분을 최신 전투보상 OLY-ATH-L01에 연결. 기존 원문 비수치 연습은 출처 보존이며, 현재는 실연 뒤 독립 습득 기술을 주는 작업기준. 무기·가호·주시 추가0.

### 기존 MAIN과 분리한 추가 SIDE

- - definition_id: mythictrpg:p0m1_athena_return_guard_commission
  - title: 별도 SIDE · 마지막 한 수를 남긴 검
  - kind: SIDE
  - separate_from_main: true
  - predecessors: - mythictrpg:greek_olympus_athena_rearguard_step learned
  - entry: 후미 교대 실제수료자 개인에게만 별도설명/수락. MAIN 소유자일필요없으며 성공철수도움자도 자기수료권으로가능. 아테나가자기 소유훈련용퇴로검1자루를조건부 증여하기로실제제시하고실재고를예약한다. 상징원본검탈취/복제0.
  - duration_minutes: - 8
    - 12
  - time_limit: 전체 기한 없음; 훈련1패턴20초, 미성공패턴만무료재시도
  - cost: 별도재료0, 훈련각시전마나28/기존쿨200tick. 자체준비장비/식량의정상소모외강제비용0.
  - objectives: - - objective_key: receive_loan
      - action: 예약된 same content_id 퇴로검1자루를 훈련대여 상태로 실제 인수한다.
      - quantity: 1
      - scope: 이 별도 SIDE를 명시 수락한 해당 개인의 실제 수행. 수행자를 전역 최초 MAIN 소유자로 자동 대체하지 않는다. 명시된 선행훈련 자격과 그 획득 조건은 별도로 검사한다.
      - time: 시간 제한 없음
      - cost: 별도 제출 비용 없음
      - requires: 
      - completion_evidence: 아테나소유재고1→대여serial. 영구획득권/전투 점수는아직0. 훈련장밖기술/판매/양도불가.
    - - objective_key: front_parry
      - action: 안전시험장의명시허용 정면훈련투사체2회에실제우클릭패링을성공하고 t=6 응수대상에맞힌다.
      - quantity: 2
      - scope: 이 별도 SIDE를 명시 수락한 해당 개인의 실제 수행. 수행자를 전역 최초 MAIN 소유자로 자동 대체하지 않는다. 명시된 선행훈련 자격과 그 획득 조건은 별도로 검사한다.
      - time: 시간 제한 없음
      - cost: 별도 제출 비용 없음
      - requires: - receive_loan
      - completion_evidence: 시도별실제 방향/거리/입력창[2,6)/첫사건1개/응수타격콜라이더. 훈련피해0, 전투 처치/공로로집계0.
    - - objective_key: leave_the_line
      - action: 패링불가로명시된 바닥띠예고1.5초를보고 후미교대로범위밖 엄폐에실제로이동한다.
      - quantity: 1
      - scope: 이 별도 SIDE를 명시 수락한 해당 개인의 실제 수행. 수행자를 전역 최초 MAIN 소유자로 자동 대체하지 않는다. 명시된 선행훈련 자격과 그 획득 조건은 별도로 검사한다.
      - time: 시간 제한 없음
      - cost: 별도 제출 비용 없음
      - requires: - front_parry
      - completion_evidence: 실제몸충돌체가 폭2×길6블록 띠 밖. 패링으로기믹을지우면실패. 새무적/타인강제이동0.
    - - objective_key: accept_gift
      - action: 실연결과를아테나에게실제확인받고 원대여serial의증여를명시수락한다.
      - quantity: 1
      - scope: 이 별도 SIDE를 명시 수락한 해당 개인의 실제 수행. 수행자를 전역 최초 MAIN 소유자로 자동 대체하지 않는다. 명시된 선행훈련 자격과 그 획득 조건은 별도로 검사한다.
      - time: 시간 제한 없음
      - cost: 별도 제출 비용 없음
      - requires: - leave_the_line
      - completion_evidence: 같은원물의소유이전+최초획득entitlement1+journal. 가방에이미있는대여품을새검으로중복 지급0.
  - completion: 자기실연2+위치이동1+현재제공자확인. 본 MAIN 완료를조건부소급변경하지않는다.
  - reward: - reward_definition_id: mythictrpg:p0m1_athena_return_guard_first
    - content_ref: mythictrpg:greek_olympus_athena_return_guard
    - management_key: OLY-ATH-W01
    - count: 1
    - limit: 개인 최초1회·기존 콘텐츠동일 권리
    - acquisition_right: 이미같은콘텐츠를적법취득한기록이있으면동일 권리에연결하고새독립획득/두번째검0.
    - adopted_values: 희귀·신제무한내구·기본공격총7/속도1.6, 우클릭마나28/쿨200tick/정면100도1건패링·응수피해6/거리2.5. 현재효력은기존 동일ID전투명세가권위이며이문서는획득경로만닫는다.
  - failure: 패링실패/충돌은해당패턴만재시도. 마나·쿨초기화0. 대여분실/서버중단은원장/serial대조후같은위탁만복구. 중도반납이면 SIDE 보류, 기존 MAIN/학습권유지. 완료후반복훈련은추가 물품0.
  - knowledge: 실제훈련자와아테나만수료/증여를안다. 관전자에게 MAIN/기술/무기권복제0.
  - implementation: 비생물훈련콜라이더·태그 훈련투사체의0피해시뮬레이션과실패위치판정은신규요구. 실제전투의허용피해표/보스기믹예외를확장하지않는다.
  - stock_table: - provider: 아테나가 제공하기로 한 자기 소유 퇴로검 공급재고
    - initial_units: 10
    - allocation: 첫 수료자5+후발 수료자5 계획량. 유일 신화 원본이 아닌 같은 설계의 보급품이며 각각 개별 실물serial
    - reservation: 현재 존재하는 미예약 실물1을 해당 신규 개인 SIDE 수락에 예약. 실제 재고준비 전 수락0
    - deduct: 실제 대여 뒤 증여완료 때 예약→개인소유. 같은 실물 전환이라 새검 spawn0
    - refill: 초기10 소진 후 자동 재생 없음. 아테나가 적법 취득해 실제 자기재고에 추가한 동일품을 최대5개 배치 단위로 등록할 때만 새 수락. 새로운 공급자/제작자를 발명하지 않음
    - budget: B3 누적: 실제 철수20~35분+후미지도4~7분+별도 실습8~12분, 기존 교육 기술1+희귀 주보상1. MAIN 비소유자의 자기 기여 이력을 그대로 사용.

### Story Fact와 개인 지식

- - fact_definition_id: mythictrpg:p0m1_athena_fact_route_flaw
  - statement: 공격안에서 빠진 뒤턱의 실제 폭/귀환 문제가 드러났다.
  - activation: 해당 실제 수행/수신 event_uuid가 확정된 뒤만. 작성시 기본locked.
  - speaker_condition: 실제 답사자와 보고를 받은 아테나.
  - disclosure: 자기 반례를 하지 않은 친구에게 복사 금지.
  - fact_and_knowledge_separate: true
  - evidence_fields: - world_id
    - source_event_uuid
    - actor_uuid
    - observed_at
    - source_mode
    - actual_audience_uuids
    - permission_scope
  - memory_rule: 현재본체가 직접관측/실제보고를 수신한 부분만 자기앎. 같은 player_uuid의 실제기억으로만 다음만남을변형하며 후발자는 허락된새열람으로 학습.
- - fact_definition_id: mythictrpg:p0m1_athena_fact_field_outcome
  - statement: 추격제압과 사람/짐보존의 실제 결과는 다르다.
  - activation: 해당 실제 수행/수신 event_uuid가 확정된 뒤만. 작성시 기본locked.
  - speaker_condition: 실제 현장관측 및 출처 있는 결과 수신자.
  - disclosure: 사적 피해내역은 소유/피해 당사자 허락 범위.
  - fact_and_knowledge_separate: true
  - evidence_fields: - world_id
    - source_event_uuid
    - actor_uuid
    - observed_at
    - source_mode
    - actual_audience_uuids
    - permission_scope
  - memory_rule: 현재본체가 직접관측/실제보고를 수신한 부분만 자기앎. 같은 player_uuid의 실제기억으로만 다음만남을변형하며 후발자는 허락된새열람으로 학습.
- - fact_definition_id: mythictrpg:p0m1_athena_fact_accountability
  - statement: 아테나가 자신의 공격안의 빈틈을 현재 참가자 앞에서 인정했다.
  - activation: 해당 실제 수행/수신 event_uuid가 확정된 뒤만. 작성시 기본locked.
  - speaker_condition: 그 발화를 실제 들은 청중.
  - disclosure: 전세계 자동 소문/전술승리 선언 금지.
  - fact_and_knowledge_separate: true
  - evidence_fields: - world_id
    - source_event_uuid
    - actor_uuid
    - observed_at
    - source_mode
    - actual_audience_uuids
    - permission_scope
  - memory_rule: 현재본체가 직접관측/실제보고를 수신한 부분만 자기앎. 같은 player_uuid의 실제기억으로만 다음만남을변형하며 후발자는 허락된새열람으로 학습.
- - fact_definition_id: mythictrpg:p0m1_athena_fact_lesson
  - statement: 특정 참가자가 후미 교대 실연을 끝냈다.
  - activation: 해당 실제 수행/수신 event_uuid가 확정된 뒤만. 작성시 기본locked.
  - speaker_condition: 본인과 실제 지도한 아테나.
  - disclosure: 개인 learn_skill receipt, 다른 참가자 자동학습0.
  - fact_and_knowledge_separate: true
  - evidence_fields: - world_id
    - source_event_uuid
    - actor_uuid
    - observed_at
    - source_mode
    - actual_audience_uuids
    - permission_scope
  - memory_rule: 현재본체가 직접관측/실제보고를 수신한 부분만 자기앎. 같은 player_uuid의 실제기억으로만 다음만남을변형하며 후발자는 허락된새열람으로 학습.

### 조건부 대사 예시

- “돌아오는 돌은 어디 있지? 이번에는 내 판에도 빠졌군.”
- “쫓아간 적과 돌아온 사람을 한 줄에 쓰지 마.”
- “그 어깨가 보인다. 숨긴 뒤에 바꿔.”

### 시간·보상 적정성

후미 교대는 마나20/쿨8초의2.4블록 후퇴이며 무적·도발·아군 이동0. 정확한 위치교대 가치만 준다. 추격 성과는 인정하되 실제 후미 경험 없는 사람에게 기술권을 복제하지 않는다. 수치와 성공률은 정적 기획이며 실전검증 필요.

### 새 구현 요구

- 실제 위협/짐/동행자 결속 및 시도명부
- 피해순위 아닌 엄폐·동행·추격 기여 증거
- OLY-ATH-L01의 충돌 sweep·지지면 검사·공통 입력잠금
- 임시 연습권과 영구 learn_skill 구분

### 구현 인수 사례

- - 추격만 성공
  - MAIN 결과 가능, 철수기여 없는 사람의 후미교대권0.
- - 비소유 후미기여자
  - 지도 가능, MAIN 소유/완료 복제0.
- - 위협 없음
  - 안전 답사만, 실전성공0.
- - 벽 뒤 대시
  - 충돌정지, 무적/벽통과0.
- - 지도 거절
  - 완료 유지 및 미수강권 보존.

출처: 기존페르소나_인물별/RPG-S01-007/profile.json, 기존페르소나_인물별/RPG-S01-007/authoring_contract.json, 772명_스토리와기본페르소나_통합.json#/RPG-S01-007/outline, 원문제작범위_보존색인.json#/RPG-S01-007


## RPG-S01-008 아폴론 · 마지막에 남은 다른 박자

생산 제공자 ID: mythictrpg:greek_olympus_apollo / MAIN ID: mythictrpg:p0m1_apollo_personal_main

### 원문을 유지한 부분

아폴론. 아름다운 소리와 정확한 답을 아끼는 올림포스 신.
칭찬에는 박자를 두 번 두드리며 한 소절을 더 내고 싶어 한다. 정정을 받으면 그 박자부터 멎는다. 자기 확신을 고치는 일이 매끄럽지만은 않지만 틀린 것을 명성으로 덮으려 하지는 않는다. 엉성한 흥얼거림에서도 귀에 남는 구절을 찾는다.

원문 카드: EP005B-RPG-S01-008
실제 들은 구절과 출처를 다음 실제 공연 소개에 유지한다. 다음 공연이 아직 없으면 소개 완료도 없다.

원문 보상 근거: 새 개인 MAIN 보상안: 실제 변주 공연과 수정 결과를 확인한 소유자에게 아폴론이 자신이 고른 선율의 표현 지도를 한다. 실제 합주·낭독 경험만 남고 예언권·치료 가호·권능 수용을 선율 정답 보상으로 주지 않는다.

### 수락과 기존 세계 조건

현행 아폴론 본체의 안전한 기존 돌계단/공연 자리에서 제시. 망원경·노트블록 제작 세계해금 후보를 새 필수조건으로 채택하지 않는다. 현재 일반 접촉/봉인 조건은 그대로다. 청중 모집 의무0.

### 실제 장소·현물·배우

- selection: 기존 북방 폐허/감시탑의 안전 돌계단 또는 같은 허용 자연부지. 연주자리 A와 듣는자리 B는4~8블록, 뒤에 낙하 없는2블록 공간. 새 무대건물 불필요.
- interaction: 실제 노트블록이 있으면 사용하고, 없으면 같은 자리에서 플레이어의 손박자 입력을 서버의 짧은 소리/자막으로 내는 보조 연주 기능을 구현한다. 마이크·실제 가창·외부 음원·음악 실력 평가 요구0.
- accessibility: 기본120BPM(박10tick), 느린모드60BPM(박20tick) 선택. 음소거 플레이어는 동일 발음/쉼 자막과 박자 표시 수신으로 관측 가능. 다른 사람에게 들리지 않은 소리를 들었다고 강제하지 않는다.

### 실제 목표와 완료 증거

- - objective_key: hear_two
  - action: 아폴론이 현재 실제로 재생한 A/B 8박 변주를 각각 한 번 듣거나 접근성 자막으로 확인한다.
  - quantity: 2
  - scope: 개인 MAIN 소유자의 실제 수행
  - time: 시간 제한 없음
  - cost: 별도 제출 비용 없음
  - requires: 
  - completion_evidence: 같은 자리의 실제 render_session2, 대상 player의 소리/자막 수신. A=[도,레,미,솔,쉼,미,레,도], B=[도,레,미,쉼,솔,미,레,도]. 양쪽 쉼 위치는4/5박이 다름.
- - objective_key: show_the_difference
  - action: 수락자가 A/B의 다른 쉼 부분을 손박자/음표 입력으로 실제 재연하고 아폴론의 재연주를 확인한다.
  - quantity: 1
  - scope: 개인 MAIN 소유자의 실제 수행
  - time: 시간 제한 없음
  - cost: 별도 제출 비용 없음
  - requires: - hear_two
  - completion_evidence: 원하는 모드에서4박과5박을 실제 입력/쉼으로 재현, 아폴론 playback_receipt. 말로 다르다고만 말한 것0. 정확 음정 채점 없음, 입력창±박길이의40%.
- - objective_key: compose_and_perform
  - action: 자기8박 변주를 편집한 뒤 아폴론의 수정8박과 자신의8박을 원하는 순서로 실제 이어 공연한다.
  - quantity: 16
  - scope: 개인 MAIN 소유자의 실제 수행
  - time: 시간 제한 없음
  - cost: 별도 제출 비용 없음
  - requires: - show_the_difference
  - completion_evidence: 자기변주 최소 소리1+쉼1, 연속16박 입력과 실제 출력. 구성은 자유, 순서선택만으로 재생 완료불가. 중단한 소절은 미완료 소절부터 다시.
- - objective_key: listen_and_credit
  - action: 실제 청중이 기억난 구절을 답하면 그 말 그대로 남기고, 없으면 아폴론과 서로 답한다. 자신/아폴론 구절 출처를 분리하여 현재 공연 결과 확인을 받는다.
  - quantity: 1
  - scope: 개인 MAIN 소유자의 실제 수행
  - time: 시간 제한 없음
  - cost: 별도 제출 비용 없음
  - requires: - compose_and_perform
  - completion_evidence: 실제 응답자 UUID와 구절/없음, 창작 출처2개, 아폴론의 현재 결과 확인. 박수/인기도/정답 응답 강제0.

### 선택과 결말

- - branch_key: PLAYER_LAST
  - available_when: 두 변주 준비
  - actual_action: 아폴론 수정구절→자기변주16박 실제 공연.
  - result: 자기구절이 더 기억된다고 보장하지 않음. 실제 반응만.
  - main_complete: true
- - branch_key: APOLLO_LAST
  - available_when: 두 변주 준비
  - actual_action: 자기변주→아폴론 수정구절16박 실제 공연.
  - result: 자기구절을 감추거나 아폴론 저작으로 변경하지 않음.
  - main_complete: true
- - branch_key: EMPTY_PRAISE
  - available_when: 칭찬만 함
  - actual_action: 어느 구절인지 질문을 받되 실연을 강제하지 않고 중단할 수 있다.
  - result: 대화만 기록, 수정/공연 완료0.
  - main_complete: false

### 실패·사망·중단·재개

자리 이탈·로그아웃·피해로 소절이 끊기면 해당 미완료8박만 다시. 이미 들은 변주/실연/수정과 출처는 보존한다. 박자 입력 실패는 정정할 구간만 반복하며 주화/아이템 벌금0. 관객0은 실패가 아니다. 악기를 빌렸으면 같은 serial 반환, 애초 대여0이면 반환목표0.

### MAIN 완료 판정

차이의 실제 재연+두 변주의 실제 이어 공연+현재 응답 및 출처를 아폴론이 실제 확인. 다음 공연에서 소개한다는 것은 이번 완료 조건이 아니라 미래 의향. 다음 공연이 실제 열려 소개될 때만 별도 사실 생성.

### 보상과 수령

- - reward_definition_id: mythictrpg:p0m1_apollo_phrasing_instruction_right
  - kind: instruction
  - recipient: MAIN 소유자
  - lines: - - service_ref: mythictrpg:p0m1_apollo_phrasing_lesson
      - count: 1
  - earned_condition: 실제 MAIN 결과 완료
  - limit: 개인 최초 1회
  - delivery: 공통 수령 원장 규약. 실제 수행/권리 생성/실수령은 별도. 가방 부족은 바닥 드롭 없이 미수령 보존. 이미 지급된 줄은 다시 지급하지 않는다.

### 별도 선택 서비스

- - definition_id: mythictrpg:p0m1_apollo_phrasing_lesson
  - title: 한 쉼을 남기는 표현 지도
  - kind: optional_instruction
  - entry: MAIN 완료와 미수신 지도권·아폴론 현재 동의
  - steps: 자기가 고른8박 변주를 한 번 내고, 아폴론이 실제로 표시한 숨/쉼1곳을 반영해8박을 한 번 더 낸다. 시작자리/실제16박 출력/지도수신을 검증한다.
  - quantity: 8박 전후 각1회, 지도1회
  - time: 3~5분, 시간제한 없음
  - cost: 0; 실제 악기 대여가 있었을 때만 반환
  - completion: 개인 표현 지도 수신+선택된 수정 변주 기억. 일반 공연 도구의 저장곡 슬롯1에 이 변주를 저장 가능하며 공격·치유·신탁·마나·가호·주시·전투력 상승0.
  - failure: 중단 전 완주 소절은 유지, 미완 소절만 재개. 저장곡은 본인 편집/삭제 가능하지만 지도권 재발급0.
  - adoption: 원문이 명시한 실제 합주/낭독 경험과 표현 지도를 구현한다. 성능 미정을 영구 불가로 일반화한 것이 아니라, 이 원문의 예언/치료권능 금지와 서사형 보상을 유지한다. 신규 전투기술은 별도 사건 없이 소급 제공하지 않는다.

### Story Fact와 개인 지식

- - fact_definition_id: mythictrpg:p0m1_apollo_fact_difference
  - statement: 현재 두 변주의 다른 쉼이 실제 재현되었다.
  - activation: 해당 실제 수행/수신 event_uuid가 확정된 뒤만. 작성시 기본locked.
  - speaker_condition: 출력/접근성 자막을 실제 받은 사람.
  - disclosure: 이전에 알고 있던 곡/예언 지식을 만들지 않는다.
  - fact_and_knowledge_separate: true
  - evidence_fields: - world_id
    - source_event_uuid
    - actor_uuid
    - observed_at
    - source_mode
    - actual_audience_uuids
    - permission_scope
  - memory_rule: 현재본체가 직접관측/실제보고를 수신한 부분만 자기앎. 같은 player_uuid의 실제기억으로만 다음만남을변형하며 후발자는 허락된새열람으로 학습.
- - fact_definition_id: mythictrpg:p0m1_apollo_fact_authorship
  - statement: 소유자의 변주와 아폴론의 구절을 각각 창작자에게 귀속했다.
  - activation: 해당 실제 수행/수신 event_uuid가 확정된 뒤만. 작성시 기본locked.
  - speaker_condition: 실제 공연과 소개를 들은 청중.
  - disclosure: 저작 출처와 인기도 응답은 다른 값.
  - fact_and_knowledge_separate: true
  - evidence_fields: - world_id
    - source_event_uuid
    - actor_uuid
    - observed_at
    - source_mode
    - actual_audience_uuids
    - permission_scope
  - memory_rule: 현재본체가 직접관측/실제보고를 수신한 부분만 자기앎. 같은 player_uuid의 실제기억으로만 다음만남을변형하며 후발자는 허락된새열람으로 학습.
- - fact_definition_id: mythictrpg:p0m1_apollo_fact_audience_response
  - statement: 이번 실제 응답자의 기억난 구절 또는 기억나지 않음.
  - activation: 해당 실제 수행/수신 event_uuid가 확정된 뒤만. 작성시 기본locked.
  - speaker_condition: 직접 듣거나 허락된 후기를 받은 사람.
  - disclosure: 허구 관객/환호0.
  - fact_and_knowledge_separate: true
  - evidence_fields: - world_id
    - source_event_uuid
    - actor_uuid
    - observed_at
    - source_mode
    - actual_audience_uuids
    - permission_scope
  - memory_rule: 현재본체가 직접관측/실제보고를 수신한 부분만 자기앎. 같은 player_uuid의 실제기억으로만 다음만남을변형하며 후발자는 허락된새열람으로 학습.
- - fact_definition_id: mythictrpg:p0m1_apollo_fact_future_credit
  - statement: 다음 실제 공연 소개에 출처를 유지했다.
  - activation: 해당 실제 수행/수신 event_uuid가 확정된 뒤만. 작성시 기본locked.
  - speaker_condition: 후속 공연이 실제 발생한 뒤 그 청중만.
  - disclosure: 이번 완료 때 자동 생성하지 않는 후속 사건.
  - fact_and_knowledge_separate: true
  - evidence_fields: - world_id
    - source_event_uuid
    - actor_uuid
    - observed_at
    - source_mode
    - actual_audience_uuids
    - permission_scope
  - memory_rule: 현재본체가 직접관측/실제보고를 수신한 부분만 자기앎. 같은 player_uuid의 실제기억으로만 다음만남을변형하며 후발자는 허락된새열람으로 학습.

### 조건부 대사 예시

- “같다고 했나. 잠깐, 네가 멈춘 곳을 다시 들려줘.”
- “네 구절은 네 이름으로 남겨. 마지막에 내 것을 놓아도.”
- “그 쉼은 남기자. 빈 곳이라고 없는 것은 아니니까.”

### 시간·보상 적정성

전투 없는10~18분 음악/표현 MAIN. 물품/가호를 억지 추가하지 않고 직접 출력하는 작은 상호작용과 자기 변주 저장으로 가치를 준다. 박자모드/자막으로 실제 행동은 남기되 가창 능력이나 청중 확보를 장벽으로 두지 않는다.

### 새 구현 요구

- 실제 박자입력·음표/손박자/자막 출력·수신 session
- 변주 창작자 메타데이터와 개인 저장곡
- 구간별 재개·선택 순서 공연

### 구현 인수 사례

- - 관객0
  - 아폴론과 소유자의 실제 응답으로 완료 가능.
- - 칭찬만
  - 실연/공연0.
- - 음소거
  - 접근성 박자수신과 실입력으로 동일 수행 가능.
- - 다음 공연 아직 없음
  - 미래 소개 사실 locked.
- - 치유 요구
  - 이 지도에 치유/예언/가호 효과0.

출처: 기존페르소나_인물별/RPG-S01-008/profile.json, 기존페르소나_인물별/RPG-S01-008/authoring_contract.json, 772명_스토리와기본페르소나_통합.json#/RPG-S01-008/outline, 원문제작범위_보존색인.json#/RPG-S01-008


## RPG-S01-009 아르테미스 · 새끼 길을 비운 사냥선

생산 제공자 ID: mythictrpg:greek_olympus_artemis / MAIN ID: mythictrpg:p0m1_artemis_personal_main

### 원문을 유지한 부분

숲의 흔적을 세밀하게 살피는 올림포스 여신 아르테미스. 생존을 위한 사냥과 기량을 뽐내려는 살육을 구별한다.
쉼터에서 얼굴보다 신발에 묻은 흙을 먼저 본다. 부러진 가지의 방향을 살피며 바람과 짐승의 흔적을 가리는 데 몰두한다.

원문 카드: EP005B-RPG-S01-009
다음 실제 추적에서 소유자에게 멈출 지점을 먼저 가리킨다. 미참여 동료에게 같은 공적을 부르지 않는다.

원문 보상 근거: 새 개인 MAIN 보상안: 실제 추적·먹을 몫·보전 행동을 마친 소유자에게 아르테미스가 기존 활을 이용한 추적·사격 훈련을 제공한다. 동의한 훈련 자리·실제 자기 탄약 소비·표적 안전 확인 뒤 수행한다. 신급 활·가호를 초반 접촉만으로 주지 않는다.

### 수락과 기존 세계 조건

현행 숲 본체와 만나 실제 새끼 개체와 어미가 지나는 낮은 틈을 안전 가장자리에서 확인한다. 늑대 길들이기/번식 세계해금 후보를 새 선행으로 채택하지 않는다. 새끼를 일부러 위험에 넣어 사건을 만들지 않는다.

### 실제 장소·현물·배우

- selection: 허용 숲/자작나무 숲의 기존 낮은 통로 G(폭2,높이2 이상), 가장자리 A에서12~24블록. 현재 성체/새끼 UUID를 직접 관측 가능한 곳만. 먹을거리 탐색구역은 A로부터32~96블록의 허락된 자연지형.
- route: G 중심으로 반경3블록을 보전구역으로 지정. A→왼/오른 외곽노드→반대쪽 귀환점→A 3노드 우회. 새끼가 실제 다른 곳으로 이동하면 현재 길을 다시 확인하되 과거 보호사실을 조작하지 않는다.
- food: 수락 전 먹을 몫을 식량점수8로 고정한다. 정확 허용 item ID와 점수는 food_item_points만 사용한다. minecraft:beef/porkchop/mutton/chicken은 해당 바닐라 생고기 ID이며 raw_ 접두사 ID를 만들지 않는다. sweet_berries와 glow_berries를 각각 명시 허용한다. 실제 현재 수급 가능한 사냥/채집 경로만 설명한다. 식량점수는 퀘스트 분량 단위이지 영양/전투력 수치가 아니다. 기존 허락된 성체만 사냥하며 새끼/길들인 동물/주민 소유 동물은 제외한다.
- recipient: 실제 식량요청자가 있으면 그 기존 인수자 UUID. 없으면 소유자 자기 먹을 몫. 새 마을대표/인질/억지 요청 없음.
- food_item_points: - minecraft:sweet_berries: 1
  - minecraft:glow_berries: 1
  - minecraft:apple: 2
  - minecraft:beef: 2
  - minecraft:porkchop: 2
  - minecraft:mutton: 2
  - minecraft:chicken: 1

### 실제 목표와 완료 증거

- - objective_key: observe_and_leave_gap
  - action: A에서 새끼/어미 실제 움직임을 각각 관측하고 G를 밟지 않는 외곽3노드를 실제 걷는다.
  - quantity: - animals: 2
    - nodes: 3
  - scope: 개인 MAIN 소유자의 실제 수행
  - time: 시간 제한 없음
  - cost: 별도 제출 비용 없음
  - requires: 
  - completion_evidence: 각 개체 시야 관측1과 소유자 우회3노드. 대사로 발자국추적 완료0. G 보전구역 블록변경·새끼피해0.
- - objective_key: secure_a_meal
  - action: 수락 이후 허용 성체 사냥 또는 허락된 현재 채집으로 먹을 몫8점을 확보한다.
  - quantity: 8
  - scope: 개인 MAIN 소유자의 실제 수행
  - time: 시간 제한 없음
  - cost: 자기 탄약·도구·실제 채집시간, 의무 희귀재료0.
  - requires: - observe_and_leave_gap
  - completion_evidence: 각 적법 hunt/drop 또는 harvest event의 원천/소유/수량. 기존 가방 식량만 보여 주기는 이 실제 탐색 수행을 대신하지 않는다. 도움자 제공분은 그 기여로 기록하되 소유자도 최소4점 직접 확보.
- - objective_key: stop_and_return
  - action: 8점에 도달하면 요청 범위의 사냥을 멈추고 실제 A로 돌아와 식량을 자기 보관하거나 실제 요청자에게 인계한다.
  - quantity: 1
  - scope: 개인 MAIN 소유자의 실제 수행
  - time: 시간 제한 없음
  - cost: 별도 제출 비용 없음
  - requires: - secure_a_meal
  - completion_evidence: 상한도달시각 이후 추가 의도적 성체 사냥0, 귀환 위치와8점 현물/인수. 마지막 한 번의 정상 드롭 초과분은 개인 일반 획득으로 기록하고 추가사냥 목표로 쓰지 않는다.
- - objective_key: confirm_two_results
  - action: 아르테미스에게 보전된 길과 식량 처리의 두 결과를 각각 실제 확인받는다.
  - quantity: 2
  - scope: 개인 MAIN 소유자의 실제 수행
  - time: 시간 제한 없음
  - cost: 별도 제출 비용 없음
  - requires: - stop_and_return
  - completion_evidence: 통로 훼손0 및 현재 통행 가능, 식량8점 확보/인계, 실제 소유자 보고수신. 전투승리만으로 먹을몫 성공을 대신하지 않는다.

### 선택과 결말

- - branch_key: HUNT
  - available_when: 실제 허용 성체가 있고 도구·안전·돌아올 길 확인
  - actual_action: 허용 성체를 추적하고 필요한8점까지 사냥. 놓치거나 불필요한 추가 사냥이 될 때는 채집으로 전환 가능.
  - result: 사냥 실제 성과만 집계, 새끼 처치공로0.
  - main_complete: true
- - branch_key: GATHER
  - available_when: 현재 허락된 먹을 채집물이 존재
  - actual_action: 사과/열매를 실제 채집해8점 확보하고 길을 보전한다.
  - result: 처치0으로도 동일 MAIN 완료와 선택 훈련 가능.
  - main_complete: true
- - branch_key: WITHDRAW_PARTIAL
  - available_when: 현재 위험 또는 분량부족
  - actual_action: 확보한 분량과 보전된 틈을 유지하고 A로 철수.
  - result: 부분분량 유지·완료0·훈련권0. 남은 분량만 재개.
  - main_complete: false

### 실패·사망·중단·재개

새끼/보전통로에 본인이 위해를 가하면 현재 사냥 진행을 멈추고 안전확보·실제 훼손복구부터 한다. 죽은 새끼를 부활/대체복제하지 않는다. 새끼를 죽인 시도는 보호 성공으로 영원히 바꾸지 않고 실제 다른 자연적 무리의 안전한 관측에서 재시도한다. 다른 사람의 위해를 소유자 행위로 붙이지 않되 현재 길 안전은 다시 확인한다. 로그아웃/사망은 완료된 관측과 확보증거 보존, 분실 음식은 실제 회수/잔여 확보 필요.

### MAIN 완료 판정

실제 통로 보전+식량8점+귀환/현재 인수+아르테미스 결과 확인. 현재 범위 밖 아탈란테·이피게네이아 방문/신급활/가호는 선행/보상으로 만들지 않는다.

### 보상과 수령

- - reward_definition_id: mythictrpg:p0m1_artemis_measured_shot_instruction
  - kind: learn_skill_after_lesson
  - recipient: MAIN 소유자만
  - lines: - - content_ref: mythictrpg:greek_olympus_artemis_measured_shot
      - count: 1
  - earned_condition: MAIN 완료 뒤 선택한 기존 활 지도 실연 완료
  - limit: 개인 최초 1회
  - delivery: 공통 수령 원장 규약. 실제 수행/권리 생성/실수령은 별도. 가방 부족은 바닥 드롭 없이 미수령 보존. 이미 지급된 줄은 다시 지급하지 않는다.

### 별도 선택 서비스

- - definition_id: mythictrpg:p0m1_artemis_measured_shot_lesson
  - title: 멈춰 쏘는 숨 실연
  - kind: optional_skill_instruction
  - entry: 소유자의 MAIN 완료·현재 훈련동의·자기 minecraft:bow·실제 일반 화살·안전 표적 뒤 막힌 공간
  - steps: 표적8블록/뒤 고체벽을 실제 확인한다. 임시 J연습으로 멈춰 쏘는 숨을 시전한 뒤 준비시간 안에 일반 활을 당겨 표적에 실제 화살을2회 맞힌다. 실화살 발사·준비1회 소비·표적적중을 대조. 대상은 비생물 훈련표적이며 훈련 성공을 사냥으로 집계하지 않는다.
  - quantity: 실제 준비화살 적중2회; 실패 교정 가능
  - time: 5~8분, 스킬쿨240tick, 전체 기한 없음
  - cost: 시도마다 마나24+실제화살1. 일반회수 가능한 화살만 서버 기존 규칙으로 회수; 무상 화살생성0.
  - completion: 설명수신+적중2회 뒤 OLY-ART-L01 동일 ID learn_skill1. 채집분기 완료자도 동일.
  - failure: 탄약부족은 준비시간만 흐름, 헛침/벽/만료는 비용유지. 무한 인챈트의 실제 탄약처리는 기존 규칙으로 하되 발사 event 없이 성공0.
  - content_ref: mythictrpg:greek_olympus_artemis_measured_shot
  - skill_key_parameters: - mana: 24
    - cooldown_ticks: 240
    - ready_duration_ticks: 60
    - bonus_damage: 3
    - movement_multiplier_while_ready: 0.75
    - max_shots: 1
    - timing: [0,6)준비,[6,10)후딜,준비상태[6,66)
    - intrinsicPower: 18
  - adoption: 원문 기존 활 훈련의 미정 기술 수치를 OLY-ART-L01에 연결한다. 신급 활/가호/주시 지급0. 실제 화살 한 발에만 고정 추가3을 방어계산 전 합산하며 보스무적/벽/아군 보호를 우회하지 않는다.

### 기존 MAIN과 분리한 추가 SIDE

- - definition_id: mythictrpg:p0m1_artemis_boundary_knife_commission
  - title: 별도 SIDE · 경계를 넘지 않는 칼날
  - kind: SIDE
  - separate_from_main: true
  - predecessors: - mythictrpg:greek_olympus_artemis_measured_shot learned
  - entry: 멈춰쏘는숨의실제수료자가아르테미스에게새무기제공의뢰를별도수락. 그녀소유일반공급재고의경계사냥칼1을미리예약. 기존활훈련만으로자동칼지급0. 신화상징무기탈취/새동물피해과제0.
  - duration_minutes: - 8
    - 12
  - time_limit: 전체 기한 없음, 각투사시험10초·미완만재시도
  - cost: 추가 재료0. 기술실습1회마나22·쿨140tick. 실제아이템을던져소모하지않음.
  - objectives: - - objective_key: receive_loan
      - action: 예약칼1을훈련대여상태로인수하고안전한되돌아올3노드길을걸어확인한다.
      - quantity: - knife: 1
        - nodes: 3
      - scope: 이 별도 SIDE를 명시 수락한 해당 개인의 실제 수행. 수행자를 전역 최초 MAIN 소유자로 자동 대체하지 않는다. 명시된 선행훈련 자격과 그 획득 조건은 별도로 검사한다.
      - time: 시간 제한 없음
      - cost: 별도 제출 비용 없음
      - requires: 
      - completion_evidence: 원serial위탁+실제3노드이동. 영구 권리/점수아직0.
    - - objective_key: open_target
      - action: 8블록과12블록의비생물훈련표적각1개에 실제 칼날투사체를쏘아맞힌다.
      - quantity: 2
      - scope: 이 별도 SIDE를 명시 수락한 해당 개인의 실제 수행. 수행자를 전역 최초 MAIN 소유자로 자동 대체하지 않는다. 명시된 선행훈련 자격과 그 획득 조건은 별도로 검사한다.
      - time: 시간 제한 없음
      - cost: 별도 제출 비용 없음
      - requires: - receive_loan
      - completion_evidence: 실제 투사체연속충돌/첫충돌/거리·속도·발사기록. 훈련표적은훈련중콜라이더일뿐생명체/경험치/처치수아님.
    - - objective_key: respect_wall
      - action: 같은표적앞의허락된기존벽이막은경로에1회쏘아 벽에서소멸하는실제 결과를확인한다.
      - quantity: 1
      - scope: 이 별도 SIDE를 명시 수락한 해당 개인의 실제 수행. 수행자를 전역 최초 MAIN 소유자로 자동 대체하지 않는다. 명시된 선행훈련 자격과 그 획득 조건은 별도로 검사한다.
      - time: 시간 제한 없음
      - cost: 별도 제출 비용 없음
      - requires: - open_target
      - completion_evidence: block_collision이target_hit보다먼저발생,표적피해0. 관통성공을요구하지않음.
    - - objective_key: come_back_and_receive
      - action: 처음확인한3노드의안전길을따라돌아와칼을보여주고별도증여확인을받는다.
      - quantity: 1
      - scope: 이 별도 SIDE를 명시 수락한 해당 개인의 실제 수행. 수행자를 전역 최초 MAIN 소유자로 자동 대체하지 않는다. 명시된 선행훈련 자격과 그 획득 조건은 별도로 검사한다.
      - time: 시간 제한 없음
      - cost: 별도 제출 비용 없음
      - requires: - respect_wall
      - completion_evidence: 실제귀환·같은대여serial→소유이전·수령journal.
  - completion: 실제표적명중2+벽차단1+안전귀환+아르테미스실제확인. 새끼/중립동물·주민·친구를시험표적으로허용하지않는다.
  - reward: - reward_definition_id: mythictrpg:p0m1_artemis_boundary_knife_first
    - content_ref: mythictrpg:greek_olympus_artemis_boundary_cast
    - management_key: OLY-ART-W01
    - count: 1
    - limit: 개인 최초1회·기존 동일콘텐츠권리
    - acquisition_right: 같은ID의기존적법획득권은신규 SIDE 라는이유로증식하지않고동일 권리연결.
    - adopted_values: 희귀·신제무한내구·기본공격총6/속도2.0, 우클릭마나22/쿨140tick/피해8/사거리14/속도1.4블록tick/단일표적·비관통. 기존 동일ID전투명세를재정의하지않고획득만닫는다.
  - failure: 헛침/타당한벽차단은해당목표결과에맞게판정, 첫두명중전부초기화0. 중단/죽음/재접속에대여/부분목표/마나쿨보존. 원물분실은같은위탁대조,새 보상/현금화용복제0. 반복수료물품0.
  - knowledge: 실제시험결과를아르테미스가직접보거나받은범위만알고,벽차단을기믹무시능력으로이야기하지않는다.
  - implementation: 권리제한훈련모드의비생물콜라이더·실투사체관측연결필요. 정식전투대상의허용표에주민/중립을추가하지않는다.
  - stock_table: - provider: 아르테미스 자기 소유 경계의 사냥칼 공급재고
    - initial_units: 1
    - allocation: 현재 원문 학습권이 유일 개인 MAIN 소유자에게만 발생하므로 적격1인용. 명부 모든 사람용10개를 만들지 않음
    - reservation: 현재 존재하는 미예약 실물1을 SIDE 수락에 예약
    - deduct: 원 대여serial의 실제 증여완료 때 소유이전
    - refill: 이 획득경로 최초 권리1개이므로 반복/시간보충0. 실제 구현준비 시 제공할 원물1 확보, 미수령/오류는 같은serial 복구만
    - budget: B3 누적: 실제 탐색/보전18~30분+활지도5~8분+별도 실습8~12분. 교육 기술1+희귀 주보상1, 기존 MAIN 자동보상 교체0.

### Story Fact와 개인 지식

- - fact_definition_id: mythictrpg:p0m1_artemis_fact_gap
  - statement: 실제 새끼 퇴로를 비운 우회.
  - activation: 해당 실제 수행/수신 event_uuid가 확정된 뒤만. 작성시 기본locked.
  - speaker_condition: 실제 동물/위치 관측자와 수신한 아르테미스.
  - disclosure: 마음속 선함을 자동 판정하지 않는다.
  - fact_and_knowledge_separate: true
  - evidence_fields: - world_id
    - source_event_uuid
    - actor_uuid
    - observed_at
    - source_mode
    - actual_audience_uuids
    - permission_scope
  - memory_rule: 현재본체가 직접관측/실제보고를 수신한 부분만 자기앎. 같은 player_uuid의 실제기억으로만 다음만남을변형하며 후발자는 허락된새열람으로 학습.
- - fact_definition_id: mythictrpg:p0m1_artemis_fact_food
  - statement: 먹을 몫8점의 실제 사냥/채집·인수.
  - activation: 해당 실제 수행/수신 event_uuid가 확정된 뒤만. 작성시 기본locked.
  - speaker_condition: 수행자와 실제 인수/보고 수신자.
  - disclosure: 채집을 사냥처치로 쓰지 않는다.
  - fact_and_knowledge_separate: true
  - evidence_fields: - world_id
    - source_event_uuid
    - actor_uuid
    - observed_at
    - source_mode
    - actual_audience_uuids
    - permission_scope
  - memory_rule: 현재본체가 직접관측/실제보고를 수신한 부분만 자기앎. 같은 player_uuid의 실제기억으로만 다음만남을변형하며 후발자는 허락된새열람으로 학습.
- - fact_definition_id: mythictrpg:p0m1_artemis_fact_stop
  - statement: 필요량에서 멈춰 귀환한 실제 행동.
  - activation: 해당 실제 수행/수신 event_uuid가 확정된 뒤만. 작성시 기본locked.
  - speaker_condition: 아르테미스의 직접 관측 또는 실제 근거보고.
  - disclosure: 다음 만남은 해당 본인에게만 기억.
  - fact_and_knowledge_separate: true
  - evidence_fields: - world_id
    - source_event_uuid
    - actor_uuid
    - observed_at
    - source_mode
    - actual_audience_uuids
    - permission_scope
  - memory_rule: 현재본체가 직접관측/실제보고를 수신한 부분만 자기앎. 같은 player_uuid의 실제기억으로만 다음만남을변형하며 후발자는 허락된새열람으로 학습.
- - fact_definition_id: mythictrpg:p0m1_artemis_fact_instruction
  - statement: 기존 활의 멈춰 쏘는 숨 실연수료.
  - activation: 해당 실제 수행/수신 event_uuid가 확정된 뒤만. 작성시 기본locked.
  - speaker_condition: 수강자와 지도자.
  - disclosure: 후발 친구/가호권 복제0.
  - fact_and_knowledge_separate: true
  - evidence_fields: - world_id
    - source_event_uuid
    - actor_uuid
    - observed_at
    - source_mode
    - actual_audience_uuids
    - permission_scope
  - memory_rule: 현재본체가 직접관측/실제보고를 수신한 부분만 자기앎. 같은 player_uuid의 실제기억으로만 다음만남을변형하며 후발자는 허락된새열람으로 학습.

### 조건부 대사 예시

- “네 발이 막는 곳부터 봐.”
- “놓쳤으면 다른 먹을 것을 보자. 저 틈까지 쫓을 이유는 없어.”
- “빠르게 놓는 것보다 놓지 않을 때를 알아.”

### 시간·보상 적정성

사냥/채집 동등 보상으로 처치만의 정답을 피한다. 추가3 피해는 마나24/쿨12초/준비3초/이동0.75배의 다음 일반 화살1발에 한정. 기본200마나에서 연속8회 비용192, 기본회복만으로12초마다24회복하므로 최대 빈도는 쿨과 실화살 준비/실제 명중이 제한한다. 이 정적 계산은 전투 효율 실측이 아니다.

### 새 구현 요구

- 동물 나이/소유·보전구역/채집원천/실제식량점수 추적
- 일반 활 발사1회 준비소비·투사체 귀속·추가피해 스냅샷
- 해제 시 전용0.75이동modifier만 제거

### 구현 인수 사례

- - 사냥 실패 후 채집
  - 같은8점 잔량으로 완주, 사냥처치 강제0.
- - 새끼 위해
  - 보호 성공0, 부활/대체복제0.
- - 지도 사양
  - MAIN 완료 유지.
- - 발사후 무기교체
  - 화살의 소비/보너스 스냅샷 유지, 준비재생0.
- - 봉인대상 레이드
  - 기존 아르테미스 가호 보유자 제한만 적용. 이번 학습스킬을 가호로 오인해 제한하지 않는다.

출처: 기존페르소나_인물별/RPG-S01-009/profile.json, 기존페르소나_인물별/RPG-S01-009/authoring_contract.json, 772명_스토리와기본페르소나_통합.json#/RPG-S01-009/outline, 원문제작범위_보존색인.json#/RPG-S01-009


## RPG-S01-012 헤파이스토스 · 흠집보다 손에 남는 것

생산 제공자 ID: mythictrpg:greek_olympus_hephaestus / MAIN ID: mythictrpg:p0m1_hephaestus_personal_main

### 원문을 유지한 부분

헤파이스토스. 번쩍임보다 손에 맞는 쓸모를 따지는 올림포스의 장인 신.
금 간 물건의 쓸모를 쉽게 버리지 않지만 허영 섞인 장식 주문에는 코웃음을 친다. 자기가 만든 물건이라는 자부심과 실제 사용자의 불편을 듣는 일이 충돌할 때, 실패 흔적을 감추기보다 공정을 다시 살피려 한다.

원문 카드: EP005A-RPG-S01-012
헤파이스토스의 다음 공정 순서에서 장식보다 사용 시험이 앞에 놓인다.

원문 보상 근거: 새 개인 MAIN 보상안: 실제 수리와 사용 시험을 마친 소유자에게 헤파이스토스가 그 사람 소유의 기존 일반 도구 손질을 제공한다. 재료 제공자 동의·실제 공정·원도구 보존·반환 확인을 거친다. 새 수치 강화나 C24 조각 무기 지급은 없다.

### 수락과 기존 세계 조건

현재 헤파이스토스 단일 본체가 실제로 사용하는 기존 작업장 또는 원문 선박 갑판/해안 야영지의 안전 작업공간. 기존 일반 도구1자루의 사용 허락과 테스트 목재가 실제 준비되었을 때 제시. C24 합일·공방 SIDE 완료·새 신전공방은 선행0.

### 실제 장소·현물·배우

- selection: 식은 작업대 A, 안전 시험점 B(6~10블록), 기존 화로/안전 보관점 C를 구분. 같은 현행 허용부지 안에 배치.
- tool: 기본 시험물은 헤파이스토스 또는 자발적 기존 사용자가 소유한 일반 minecraft:iron_axe 1자루. item_instance_uuid를 유지한다. C24 원본·합일검·희귀 신제장비는 제외. 소유자가 플레이어 본인이어도 가능.
- materials: 시험용 참나무 원목6개는 소유자 허락된 기존 재고에서 준비하며 수락자 소유재고를 기본으로 한다. 앞3/뒤3을 실제 설치해 실제 도끼로 파괴하고 정상 드롭은 원재료 소유자에게 반환. 채광 공로로 세지 않는다. 손잡이 조정용 막대기2+실1은 수락 전 고지하여 자기/기증 허락 재고에서만 소비.
- loan: 대여 도구는 양도/판매/제작재료 사용 불가. 잃으면 기존 serial/드롭/보관 이력을 먼저 찾고 확인된 실제 위탁 반환만 완료조건. 고유 대여권의 누락복구는 판매용 새 보상이 아니다.

### 실제 목표와 완료 증거

- - objective_key: inspect
  - action: 식은 작업대에서 같은 일반 도구1자루를 실제 인수하여 손잡이 흔들림/무게 표시를 허락 범위로 확인한다.
  - quantity: 1
  - scope: 개인 MAIN 소유자의 실제 수행
  - time: 시간 제한 없음
  - cost: 별도 제출 비용 없음
  - requires: 
  - completion_evidence: 소유자 동의·item_instance_uuid·인수와 현재 손잡이 점검 event. 아직 수리/소비0.
- - objective_key: before_trial
  - action: 허락된 B에서 원목3개를 같은 도끼로 실제 작업하여 사용 전 상태를 보고한다.
  - quantity: 3
  - scope: 개인 MAIN 소유자의 실제 수행
  - time: 시간 제한 없음
  - cost: 일반 도구 내구/사용시간. 원목3 드롭은 원소유자 유지.
  - requires: - inspect
  - completion_evidence: 도끼 serial 일치+block_break3+현재 사용자 실제 응답. 장식 칭찬만으로 시험0.
- - objective_key: adjust
  - action: 막대기2+실1을 고지한 공정함에 제출하고 헤파이스토스가 현재 같은 도구를 조정하는100tick 공정을 실제 진행한다.
  - quantity: 1
  - scope: 개인 MAIN 소유자의 실제 수행
  - time: 현재 작업100tick=5초, 전체 기한 없음
  - cost: minecraft:stick2 + minecraft:string1
  - requires: - before_trial
  - completion_evidence: 입력2+1 소비journal·same serial·공정시작/종료·식은 작업상태. 새 도구 생성/공격속도 변경0.
- - objective_key: after_trial
  - action: 동일 도끼로 남은 원목3개를 같은 B에서 작업하고 안정됨 또는 재조정 필요를 현재 사용자에게 실제 묻는다.
  - quantity: 3
  - scope: 개인 MAIN 소유자의 실제 수행
  - time: 시간 제한 없음
  - cost: 별도 제출 비용 없음
  - requires: - adjust
  - completion_evidence: 동일 도끼 block_break3, 현재 사용자 응답. 답만으로 block_break3 대체0.
- - objective_key: return_and_confirm
  - action: 대여했다면 원소유자에게 같은 도구를 실제 반환하고, 자기 도구였다면 위탁 해제를 확인한다. 헤파이스토스에게 전후/손실/반환을 보고한다.
  - quantity: 1
  - scope: 개인 MAIN 소유자의 실제 수행
  - time: 시간 제한 없음
  - cost: 별도 제출 비용 없음
  - requires: - after_trial
  - completion_evidence: 도구 인수/위탁해제 receipt+before3/after3+현재 사용자 응답+헤파이스토스 유효 완료확인.

### 선택과 결말

- - branch_key: FIT_THE_HAND
  - available_when: 사용허락/공정/실제시험 모두 성립
  - actual_action: 기존 손잡이 조정과 같은 작업 비교를 수행.
  - result: 평범한 도구의 사용결과만 확인. 장식보다 시험이 먼저인 공정 기억.
  - main_complete: true
- - branch_key: STILL_UNCOMFORTABLE
  - available_when: 실제 후시험 뒤 사용자가 불편을 말함
  - actual_action: 같은 도구를 내려놓고 불편 부위를 선택/설명한다. 첫 투입재료 안에서 재배치100tick 후 뒤시험3개만 다시 한다. 새 추가 재료 자동청구0.
  - result: 사용자 안정확인 전 미검증 유지. 반복 횟수만으로 성공 강제0.
  - main_complete: false
- - branch_key: HIDE_A_FLAW
  - available_when: 겉만 덮어 완료품으로 요구
  - actual_action: 헤파이스토스가 납품판정을 보류하고 사실확인을 요구한다.
  - result: 기존 관측/소유 보존, 완료/보상0.
  - main_complete: false

### 실패·사망·중단·재개

위험 균열/도구파손을 발견하면 즉시 시험 중단. 같은 serial이 파손된 경우 현재 남은 실물/손실을 보존하고 원소유자 동의 없이 새 물건으로 대체하지 않는다. 다른 일반 도구로 다시 하기로 당사자가 명시 동의하면 신규 시험대상 revision으로 전후시험을 새로 하되 이미 소비한 공정재료는 다시 청구하지 않는다. 로그아웃/사망은 공정journal을 보존하고 현재 도구 위치/미반환만 재개. 손에 맞았다는 말을 시스템이 고통/의학 판정으로 만들지 않는다.

### MAIN 완료 판정

같은 도구 전후 실제작업3+3, 실제 조정공정, 현재 사용자 응답, 필요한 원도구 반환, 헤파이스토스 완료확인. 공방 SIDE3 승리·새 곡괭이 수령은 이 다섯 목표를 대신하지 않는다.

### 보상과 수령

- - reward_definition_id: mythictrpg:p0m1_hephaestus_ordinary_tool_care_right
  - kind: service
  - recipient: MAIN 소유자
  - lines: - - service_ref: mythictrpg:p0m1_hephaestus_ordinary_tool_care
      - count: 1
  - earned_condition: 기존 MAIN 실완료
  - limit: 개인 최초 1회
  - delivery: 공통 수령 원장 규약. 실제 수행/권리 생성/실수령은 별도. 가방 부족은 바닥 드롭 없이 미수령 보존. 이미 지급된 줄은 다시 지급하지 않는다.

### 별도 선택 서비스

- - definition_id: mythictrpg:p0m1_hephaestus_ordinary_tool_care
  - title: 원래 도구를 돌려주는 손질
  - kind: optional_existing_tool_service
  - entry: MAIN 완료권·본인 소유의 일반 목재/석재/철 도구1개·현재 헤파이스토스 공정동의
  - steps: 원도구 serial, 기존 attack/speed/enchants/max_damage를 스냅샷한다. 목재도구는 해당 판자1, 석재는 조약돌1, 철은 철주괴1을 사전 고지하고 명시 제출받는다. 현재100tick 손질 뒤 같은 serial의 Damage를 min(현재Damage, floor(max_damage×0.25))만큼 줄여 반환한다.
  - quantity: 기존 일반 도구1자루·손질1회
  - time: 100tick 공정, 수령 기한 없음
  - cost: 해당 재질1개. 재료가 없으면 권리/원도구 보존하고 미공정 대기. 무손상 도구는 소비/권리사용 전에 다른 대상 선택 가능.
  - completion: 기존 원도구의 손실내구 일부 복구와 실제 반환. 새 도구/공격력/공격속도/인챈트/최대내구/무한내구/새 스킬/가호0.
  - failure: 공정 후 반환 실패는 같은serial 미인수 상태, 새 도구복제0·재료재차감0. 이미손질한 도구를 다시 넣어 보상1회를 늘리지 않는다.
  - adoption: 원문이 금지한 새 수치 강화와 C24 무기는 유지한다. 손질의 정확 범위는 기본 최대내구를 높이지 않는25% 손실복구로 결정했다. 인간/일반 도구를 신제 무한내구로 전환하지 않는다.

### 공방 SIDE와의 분리

- relationship: 현재 공방3 SIDE는 별도 진행/보상/소유원장. MAIN과 상호 필수선행 없음. 같은 실제 사용 경험은 이미 관측했다는 배경으로만 사용 가능.
- quest_refs: - mythictrpg:hephaestus_workshop_materials
  - mythictrpg:hephaestus_workshop_team_practice
  - mythictrpg:hephaestus_workshop_trial
- source: 퀘스트/공방_퀘스트와보상
- guard: 공방의 시험작·수작·무기·레이드 보상을 MAIN 손질권으로 다시 지급하지 않는다. 대장장이 직접제작 예외는 그대로이나 본 MAIN에 새로운 장비를 끼워 넣지 않는다.

### Story Fact와 개인 지식

- - fact_definition_id: mythictrpg:p0m1_hephaestus_fact_user_problem
  - statement: 특정 일반 도구의 현재 사용자 불편과 허락된 시험범위.
  - activation: 해당 실제 수행/수신 event_uuid가 확정된 뒤만. 작성시 기본locked.
  - speaker_condition: 사용자·헤파이스토스와 실제점검자.
  - disclosure: 다른 이용자의 불편을 추정하지 않는다.
  - fact_and_knowledge_separate: true
  - evidence_fields: - world_id
    - source_event_uuid
    - actor_uuid
    - observed_at
    - source_mode
    - actual_audience_uuids
    - permission_scope
  - memory_rule: 현재본체가 직접관측/실제보고를 수신한 부분만 자기앎. 같은 player_uuid의 실제기억으로만 다음만남을변형하며 후발자는 허락된새열람으로 학습.
- - fact_definition_id: mythictrpg:p0m1_hephaestus_fact_comparison
  - statement: 같은 도구의 전후작업3+3 및 실제 응답.
  - activation: 해당 실제 수행/수신 event_uuid가 확정된 뒤만. 작성시 기본locked.
  - speaker_condition: 현장관측 또는 근거보고 수신자.
  - disclosure: 명장 이름 자체를 실제 성능증거로 쓰지 않는다.
  - fact_and_knowledge_separate: true
  - evidence_fields: - world_id
    - source_event_uuid
    - actor_uuid
    - observed_at
    - source_mode
    - actual_audience_uuids
    - permission_scope
  - memory_rule: 현재본체가 직접관측/실제보고를 수신한 부분만 자기앎. 같은 player_uuid의 실제기억으로만 다음만남을변형하며 후발자는 허락된새열람으로 학습.
- - fact_definition_id: mythictrpg:p0m1_hephaestus_fact_return
  - statement: 원도구가 실제 반환되었다.
  - activation: 해당 실제 수행/수신 event_uuid가 확정된 뒤만. 작성시 기본locked.
  - speaker_condition: 원소유자와 인수당사자.
  - disclosure: 미인수를 반환완료로 만들지 않는다.
  - fact_and_knowledge_separate: true
  - evidence_fields: - world_id
    - source_event_uuid
    - actor_uuid
    - observed_at
    - source_mode
    - actual_audience_uuids
    - permission_scope
  - memory_rule: 현재본체가 직접관측/실제보고를 수신한 부분만 자기앎. 같은 player_uuid의 실제기억으로만 다음만남을변형하며 후발자는 허락된새열람으로 학습.
- - fact_definition_id: mythictrpg:p0m1_hephaestus_fact_care
  - statement: 본인의 별도 일반 도구 손질이 수행되었다.
  - activation: 해당 실제 수행/수신 event_uuid가 확정된 뒤만. 작성시 기본locked.
  - speaker_condition: 서비스당사자/제작자.
  - disclosure: 공방 SIDE 또는 C24 합일 완료 기억0.
  - fact_and_knowledge_separate: true
  - evidence_fields: - world_id
    - source_event_uuid
    - actor_uuid
    - observed_at
    - source_mode
    - actual_audience_uuids
    - permission_scope
  - memory_rule: 현재본체가 직접관측/실제보고를 수신한 부분만 자기앎. 같은 player_uuid의 실제기억으로만 다음만남을변형하며 후발자는 허락된새열람으로 학습.

### 조건부 대사 예시

- “멋있다는 말 말고, 어느 손가락이 미끄러졌는지 말해.”
- “내가 만들었어도 네 손이 편한지는 다시 봐야지.”
- “받아. 새 물건이 아니라 네 물건이다.”

### 시간·보상 적정성

15~25분 실제 도구시험과 저가 공정재료2막대기+실1. 보상은 본인 일반도구1개의25% 손실복구이며 추가 재질1 소모. 전투 스탯을 강화하지 않고 별도 공방의 성장보상을 침범하지 않는다. 원목6은 반복 생성물이 아닌 실제 준비/반환 자산이다.

### 새 구현 요구

- 동일 item serial의 위탁·공정·반환 journal
- 허락된 시험블록과 실제 사용도구 판별
- 원도구 불변 속성 스냅샷 및 손실내구만 복구

### 구현 인수 사례

- - 공방 SIDE3완료
  - 개인 MAIN 미수행이면 자동완료0.
- - C24원본 제출
  - 시험/보상손질 대상거절, 소비0.
- - 손질 속성대조
  - Damage외 attack/speed/max_damage/enchants/serial 불변.
- - 공정뒤 끊김
  - 재료재차감/새도구 생성0.

출처: 기존페르소나_인물별/RPG-S01-012/profile.json, 기존페르소나_인물별/RPG-S01-012/authoring_contract.json, 772명_스토리와기본페르소나_통합.json#/RPG-S01-012/outline, 원문제작범위_보존색인.json#/RPG-S01-012


## RPG-S01-013 헤르메스 · 받을 손이 비어 있는 동안

생산 제공자 ID: mythictrpg:greek_olympus_hermes / MAIN ID: mythictrpg:p0m1_hermes_personal_main

### 원문을 유지한 부분

헤르메스. 말을 정확히 맡아 옮기는 올림포스의 전령 신.
도착을 서두르지만 받을 사람의 대답까지 앞질러 대신하지 않으려 한다. 용도가 다른 문서는 손부터 나눠 든다. 할 말을 끝내면 자리를 뜨려다가도 짧은 선율의 변주에는 발걸음을 늦춘다.

원문 카드: EP005A-RPG-S01-013
헤르메스가 다음 봉투를 쥐고 먼저 수신 가능 시간을 묻는다. 실패한 지름길 자랑을 지우고 실제 왕복 계획을 설명한다.

원문 보상 근거: 새 개인 MAIN 보상안: 실제 전달·반송을 마친 소유자에게 헤르메스가 기존 길의 지형을 읽어 왕복 계획을 세우는 이동 지도를 하고, 현재 자신이 제공할 권리가 있는 일반 이동 보급을 사전 고지한 범위에서 지급한다. 순간이동·신격 속도·포탈 권능은 없다.

### 수락과 기존 세계 조건

현행 초기 생활권의 같은 헤르메스와 실제 전달허락 받은 봉투1건. 기존 대기 의뢰가 있으면 그 겉표를 사용한다. 새 장면은 헤르메스가 지금 실제로 맡기는 짧은 연락만 가능하고 이미 존재했던 편지를 꾸미지 않는다.

### 실제 장소·현물·배우

- selection: 기존 갈림길 쉼터 A와 길 건너 공개 작업터 B를 결속. 안전 마른 우회80~128블록 또는 얕은 물길40~64블록 중 현재 허락 길을 선택한다. 실제 지형이 범위 밖이면 가장 가까운 같은 생활권 왕복길로 고정하며 대본에서 이동거리만 안내한다.
- recipient: 수락 전 실제 authorizing_sender_uuid, named_recipient_uuid, return_actor_uuid, B 주소를 확정한다. 기존 전달요청 우선. 신규 단기 장면은 현재 인근 작업터에 실제 있는 헤파이스토스가 정상 연락을 받을 의사가 확인된 경우 그에게 보내는 헤르메스의 연락으로 한다. 적격 기존 인물이 없으면 해당 자리에 자발적으로 참가한 기존 플레이어 수신자1명만 가능. 미확정 수신자/이름없는 신규 핵심 NPC 로 대체0.
- envelope: minecraft:paper 외형의 봉함 위탁품1, item_instance_uuid. 화면에는 발신/수신/반송처만, 본문은 server-only sealed_payload. 플레이어/NBT/도움자에게 본문자동공개0. 소유는 발신자, 운반권만 위탁.
- contact: 다른 신의 현행 접근/봉인에 막히면 접근권을 위조하지 않고 지정 B의 합법 관측 가능 위치까지만. 그 위치에서 부재/접수불가를 확인한 뒤 반송 가능.

### 실제 목표와 완료 증거

- - objective_key: take_sealed
  - action: 겉표의 실제 지정 수신자·주소·반송 허락을 확인하고 봉함을 유지한 채 실물1건을 인수한다.
  - quantity: 1
  - scope: 개인 MAIN 소유자의 실제 수행
  - time: 시간 제한 없음
  - cost: 별도 제출 비용 없음
  - requires: 
  - completion_evidence: 현재 발신자 허락, accepted_offer, same envelope serial 인수. 열람권/운반권 별도.
- - objective_key: check_and_walk
  - action: 선택한 마른 우회 또는 얕은 물길의 시작/중간/도착3점을 실제 지나 B에 도달한다.
  - quantity: 3
  - scope: 개인 MAIN 소유자의 실제 수행
  - time: 시간 제한 없음
  - cost: 별도 제출 비용 없음
  - requires: - take_sealed
  - completion_evidence: 실제 이동3노드·current envelope holder=owner. 텔레포트/다른친구 이동 대체0.
- - objective_key: handover_or_return
  - action: B에서 실제 수신자 이름/역할과 현재 수락 의사를 확인한다. 받으면 직접인계, 없거나 거절하면 허락된 원반송처A로 돌아와 봉함 그대로 실제 반환한다.
  - quantity: 1
  - scope: 개인 MAIN 소유자의 실제 수행
  - time: 시간 제한 없음
  - cost: 별도 제출 비용 없음
  - requires: - check_and_walk
  - completion_evidence: DELIVERED recipient receipt 또는 RETURNED sender receipt. 부재/거절/미수신을 별개 저장. 상자에 놓기만 한 상태는 인수 아님.
- - objective_key: return_report
  - action: A로 실제 귀환하여 전달 또는 반송 결과와 지연/미확인 부분을 헤르메스에게 그대로 보고한다.
  - quantity: 1
  - scope: 개인 MAIN 소유자의 실제 수행
  - time: 시간 제한 없음
  - cost: 별도 제출 비용 없음
  - requires: - handover_or_return
  - completion_evidence: 왕복위치+해당 receipt+current Hermes received report. 물리 인수근거 없이 성공 주장만은 거절.

### 선택과 결말

- - branch_key: DELIVER
  - available_when: 지정수신자가 현재 유효접촉에서 받겠다고 실제 응답
  - actual_action: 봉함1을 지정수신자에게 인계하고 A로 돌아온다.
  - result: 내용을 읽었다는 지식/답변을 받았다는 지식은0. 수신자 인수만.
  - main_complete: true
- - branch_key: RETURN
  - available_when: B에서 부재·접수불가·거절을 실제 확인, 원발신자가 반송을 사전 허락
  - actual_action: 누가 없었는지/어떤 거절을 직접 들었는지 구분하여 A에 실제 반송.
  - result: 전달안됨과 반송성공을 함께 기록. 정직한 반송도 MAIN 완료.
  - main_complete: true
- - branch_key: WAIT
  - available_when: 수신이 아직 없고 본인이 기다리기를 선택
  - actual_action: 안전한 B 자리에서 위탁봉투를 실제 보관.
  - result: 대기기한 없음. 5분마다 상태만 갱신하되 타인응답을 생성하지 않으며 언제든 허락반송 가능.
  - main_complete: false
- - branch_key: FALSE_REPORT
  - available_when: 인수근거 없는 성공주장
  - actual_action: 헤르메스가 실제 receipt를 대조하고 현재 정정 기회를 준다.
  - result: 소유/실제 반송 유지, 완료/보급권0. 사실정정 후 남은 보고만 재개.
  - main_complete: false

### 실패·사망·중단·재개

로그아웃/사망은 위탁 현재보유/드롭 위치·행위·원소유를 보존한다. 봉투영구손실은 발신자에게 손실을 실제 보고하고 그가 같은 message_uuid의 재작성본을 승인한 때만 envelope_version 증가·이전본 무효화 후 재운반한다. 새 독립편지/보상권으로 만들지 않는다. 이미 인계했으면 재운반 요구0. 무단개봉은 내용노출 사건만 실제청중에 기록하고 완료부정으로 자동 덮지 않되 봉함준수 목표는 실패; 발신자 재승인 전 위탁재개0.

### MAIN 완료 판정

원래허락받은 봉투의 실제 인계 또는 정당한 반송+왕복+사실대로 보고수신. 기다림/입으로 전달했다고함/도움자의 다른편지 전달은 대체0. C03/C18/C22/R06-01 완료를 새 필수선행으로 요구하지 않는다.

### 보상과 수령

- - reward_definition_id: mythictrpg:p0m1_hermes_travel_supply
  - kind: ordinary_items
  - recipient: MAIN 소유자
  - lines: - - item_id: minecraft:bread
      - count: 2
    - - item_id: minecraft:torch
      - count: 8
  - earned_condition: 실제 인계 또는 정당반송 완료. 수락 전 헤르메스 소유 별도재고 예약
  - limit: 개인 최초 1회
  - delivery: 공통 수령 원장 규약. 실제 수행/권리 생성/실수령은 별도. 가방 부족은 바닥 드롭 없이 미수령 보존. 이미 지급된 줄은 다시 지급하지 않는다.
- - reward_definition_id: mythictrpg:p0m1_hermes_route_instruction_right
  - kind: instruction
  - recipient: MAIN 소유자
  - lines: - - service_ref: mythictrpg:p0m1_hermes_route_lesson
      - count: 1
  - earned_condition: MAIN 실완료
  - limit: 개인 최초 1회
  - delivery: 공통 수령 원장 규약. 실제 수행/권리 생성/실수령은 별도. 가방 부족은 바닥 드롭 없이 미수령 보존. 이미 지급된 줄은 다시 지급하지 않는다.

### 별도 선택 서비스

- - definition_id: mythictrpg:p0m1_hermes_route_lesson
  - title: 빨리 가는 길과 돌아오는 길
  - kind: optional_instruction
  - entry: 개인지도권·현재 헤르메스·허락 기존길
  - steps: 실제 왕복했던 길의 갈림점2곳을 지도에서 찍고 각 지점에 다시 직접 서서 보이는 건조 우회와 현재 물길 상태를 확인한다. 헤르메스는 플레이어가 실제 받은 관측만 바탕으로 왕복순서를 수정한다.
  - quantity: 갈림점2곳 관측+실제 지도수신1회
  - time: 4~6분, 전체 기한 없음
  - cost: 0; 지도가 없어도 현재 좌표표시/현장설명으로 가능
  - completion: 이번왕복계획과 실제 지도수신 기억. 전역지도/미발견지형/순간이동/신격속도/포탈/가호0.
  - failure: 중단 전 확인한 지점은 유지하고 미확인 지점만 재개. 같은 지도권1, 추가 보급0.

### 제공 재고·예약·보충

- provider: 헤르메스의 자기 일반 여행 보급
- initial: MAIN 유일 소유자용 빵2+횃불8 한 묶음
- reservation: 제시 전 실재고/제공권 확인, 수락 시 해당 UUID 예약
- deduct: 실지급 줄별 journal
- refill: 최초1회 뒤 자동 보충/반복 없음. 미수령분은 예약 보존
- budget: B2 이동 의뢰의 두 소액 줄로 된 한 묶음. 지도는 영구 성장권0.

### Story Fact와 개인 지식

- - fact_definition_id: mythictrpg:p0m1_hermes_fact_entrustment
  - statement: 한 봉투의 겉표/전달/반송허락과 실제 보유자.
  - activation: 해당 실제 수행/수신 event_uuid가 확정된 뒤만. 작성시 기본locked.
  - speaker_condition: 해당 운반권자와 발신자.
  - disclosure: 봉함본문은 열람허락 없이 비공개.
  - fact_and_knowledge_separate: true
  - evidence_fields: - world_id
    - source_event_uuid
    - actor_uuid
    - observed_at
    - source_mode
    - actual_audience_uuids
    - permission_scope
  - memory_rule: 현재본체가 직접관측/실제보고를 수신한 부분만 자기앎. 같은 player_uuid의 실제기억으로만 다음만남을변형하며 후발자는 허락된새열람으로 학습.
- - fact_definition_id: mythictrpg:p0m1_hermes_fact_arrival
  - statement: 지정수신자 부재·거절·인수 중 실제 결과.
  - activation: 해당 실제 수행/수신 event_uuid가 확정된 뒤만. 작성시 기본locked.
  - speaker_condition: 현장과 실제보고 수신자.
  - disclosure: 부재를 거절로 또는 그 반대로 바꾸지 않는다.
  - fact_and_knowledge_separate: true
  - evidence_fields: - world_id
    - source_event_uuid
    - actor_uuid
    - observed_at
    - source_mode
    - actual_audience_uuids
    - permission_scope
  - memory_rule: 현재본체가 직접관측/실제보고를 수신한 부분만 자기앎. 같은 player_uuid의 실제기억으로만 다음만남을변형하며 후발자는 허락된새열람으로 학습.
- - fact_definition_id: mythictrpg:p0m1_hermes_fact_return
  - statement: 사실대로 전달/반송 지연을 보고했다.
  - activation: 해당 실제 수행/수신 event_uuid가 확정된 뒤만. 작성시 기본locked.
  - speaker_condition: 헤르메스 및 실제 해당발화 청중.
  - disclosure: 친구에게 사적본문/상대응답 자동공유0.
  - fact_and_knowledge_separate: true
  - evidence_fields: - world_id
    - source_event_uuid
    - actor_uuid
    - observed_at
    - source_mode
    - actual_audience_uuids
    - permission_scope
  - memory_rule: 현재본체가 직접관측/실제보고를 수신한 부분만 자기앎. 같은 player_uuid의 실제기억으로만 다음만남을변형하며 후발자는 허락된새열람으로 학습.

### 조건부 대사 예시

- “겉표는 네가 알아야 하지. 안쪽까지 읽으라는 뜻은 아니고.”
- “받을 손이 없으면, 돌아올 곳은 여기야.”
- “전달하지 못했다는 말도 정확히 전달했군.”

### 시간·보상 적정성

왕복·위탁책임12~24분에 빵2/횃불8과 실지형 지도1회. 반송을 실패로만 만들지 않아 타인의 부재를 억지 해결하지 않는다. 반복 최초보상0, 기존공용카드 정산과 중복0.

### 새 구현 요구

- 봉함payload 비공개와 위탁serial/재작성version
- 지정수신자 실제 응답/인수와 원반송journal
- 부재/거절/대기 분리

### 구현 인수 사례

- - 정당반송
  - 실제 반송+정직보고로 MAIN 완료.
- - 빈상자투입
  - 지정인수receipt 없음이면 전달완료0.
- - 재작성뒤옛봉투발견
  - 구version 무효, 새메시지/중복보상0.
- - 봉함본문
  - 운반자화면/도움자/기본지식에 유출0.

출처: 기존페르소나_인물별/RPG-S01-013/profile.json, 기존페르소나_인물별/RPG-S01-013/authoring_contract.json, 772명_스토리와기본페르소나_통합.json#/RPG-S01-013/outline, 원문제작범위_보존색인.json#/RPG-S01-013


## RPG-S01-014 디오니소스 · 떠날 자리까지 남기는 흥

생산 제공자 ID: mythictrpg:greek_olympus_dionysus / MAIN ID: mythictrpg:p0m1_dionysus_personal_main

### 원문을 유지한 부분

잔치와 즐거움을 좋아하는 올림포스 신 디오니소스.
처음 듣는 결말과 서로 다른 맛에 자기부터 신이 난다. 한 대목 더 듣고 싶어 붙들려다가 상대가 지치면 아쉬운 채 잔을 내려놓는다. 모두 같은 표정으로 억지로 남은 자리에는 흥미를 잃으며, 술을 거절한 이에게도 다른 음료와 이야기 자리를 권할 수 있다.

원문 카드: EP005A-RPG-S01-014
디오니소스가 공연의 끝을 늘이지 않고 잔을 치운다. 그 개인 소유자에게만 완료와 후속 주시 권리가 성립한다.

원문 보상 근거: 기존 확정 지급권: 기한 전 전체 유효 접수 성공자의 a/b 하나. a는 실제 적격 상대와 양방향 대화 개시 때만 소진하며 연결 전 실패는 미사용권 보존. b는 개인1회 실물 지급 확인 후 소진·수령 기록, 가득 참이면 미수령 유지. 분실/판매는 1회 기록 초기화 없음. b→MAIN 제시→첫 유효 수락자→채택된 MAIN 실제 완료→그 소유자 주시 순서다. 장신구 이름·외형·효력·등록은 기존 제안 상태. 포션 고정 요구량만 정산 소비하고 오종/초과/미정산 반환분은 원소유와 접수 기록을 따른다.

### 수락과 기존 세계 조건

기존 술 의뢰 성공자의 b 장신구 최초 실수령 receipt가 있어야 별도 MAIN을 제시한다. 현재 장신구를 착용/소지할 필요는 없으며 잃어버렸어도 과거 실수령 근거는 남는다. MAIN 미소유일 때 첫 유효 명시 수락자1명. b 복구는 신규 제시/수락 사건을 만들지 않는다.

### 실제 장소·현물·배우

- selection: 현재 같은 본체가 실제 머무르는 기존 마을 공터/여관 앞. 새 공연장/신전/취객폭력 의뢰 없음.
- layout: 소유자가 사용허락 받은6×6블록 연기공간과 폭2블록 출구, 출구 밖 안전대기점6블록을 직접 걸어 확인한다. 길/문/통행권은 기존 생활권 허락 범위. 혼자 낭독이면 소유자자리와 폭2 퇴로만 필요.
- props: 혼자 기본형은 자기 빈그릇1개와 양털1개를 소품으로 선택할 수 있으나 필수0. 공동형은 당사자가 실제 고른0~6개 기존 소품을 목록에 고정한다. 의자 모델 없음이면 기존 계단블록을 사용하며 새 아이템기능을 주장하지 않는다. 소품목록을 비워도 실제 퇴로/공연은 수행해야 한다.
- script: 기존 대본 짧은 도입과 결말 각1대목. 기본 도입 “잔은 서로 다르고, 오늘의 이야기도 다르다.” 원래 마지막 “모두 끝까지 남아 마지막 잔을 들어야 한다.” 소유자가 이 강제잔류 문장을 철회가능 문장으로 실제 고친다. 예시 “떠나는 사람의 자리를 비워 두고, 남은 우리는 여기서 끝낸다.” 예시 정답 강요0, 동일 의미의 자유수정 허용.

### 실제 목표와 완료 증거

- - objective_key: script_and_choice
  - action: 대본 도입/결말2곳을 실제 열람하고 공터출구를 걸어 확인한 뒤 혼자 낭독 또는 역할교체 공연을 선택하여 수정 결말을 저장한다.
  - quantity: - passages: 2
    - exit_walks: 1
  - scope: 개인 MAIN 소유자의 실제 수행
  - time: 시간 제한 없음
  - cost: 별도 제출 비용 없음
  - requires: 
  - completion_evidence: 소유자 read_event2+exit actual position+script_revision1. 수정텍스트는 동의 의미 판단 근거일 뿐 공연완료 아님.
- - objective_key: prepare
  - action: 실제로 빌리거나 자기 소유인 소품목록0~6개를 배치하고 출구 폭2·귀환점을 다시 걷는다. 공동형은 실제 각 참여자에게 자기 역할/철회가능성을 확인한다.
  - quantity: - props: 선택목록의 실제 전부(0~6)
    - exit_walks: 1
  - scope: 개인 MAIN 소유자의 실제 수행
  - time: 시간 제한 없음
  - cost: 별도 제출 비용 없음
  - requires: - script_and_choice
  - completion_evidence: item/placement serial 목록, actual clear_exit collision검사, 각 role_consent_event. 친구없는 혼자형에 가상동의/필수인원 추가0.
- - objective_key: perform_and_release
  - action: 실제 도입부터 수정 결말까지1편을 공연한다. 철회자가 있으면 즉시 그 역할을 멈추고 자발교대 또는 소유자 혼자남은결말로 전환한다.
  - quantity: 1
  - scope: 개인 MAIN 소유자의 실제 수행
  - time: 예상2~4분; 전체/MAIN 마감 없음, 서두르라는 타이머 없음
  - cost: 별도 제출 비용 없음
  - requires: - prepare
  - completion_evidence: 현장 actual_performance_session: 소유자 위치·발화/자막 송신 및 디오니소스 수신. 도입/중간/결말3구간 각각 실제 출력, 구간간 소유자의 동작입력1회(잔내림/자리옮김/출구비키기).
- - objective_key: settle_and_close
  - action: 실제 대여목록 전부를 원주인에게 인수확인까지 반환하고 자기소품만 썼으면 잔여정리로 마친다. 디오니소스가 실제 형태/철회/결말/정산을 확인한다.
  - quantity: 1
  - scope: 개인 MAIN 소유자의 실제 수행
  - time: 시간 제한 없음
  - cost: 별도 제출 비용 없음
  - requires: - perform_and_release
  - completion_evidence: 대여 serial별 returned receipt 또는 loan_count0, actual script_end, 같은 소유자의 actual result_received. 이후 주시1권을 별도 연결.

### 선택과 결말

- - branch_key: SOLO
  - available_when: 소유자 선택, 다른 출연자 동의 없어도 가능
  - actual_action: 혼자 도입/중간/수정 결말 실제 낭독과 자리/출구 동작을 한다.
  - result: 혼자 공연으로 기록. 허구 출연자·박수·철회자 생성0.
  - main_complete: true
- - branch_key: RELAY
  - available_when: 현재 동의한 실제 역할자들만
  - actual_action: 자기 역할을 실제 시작하고, 철회가 있으면 해당 배우를 강요없이 빼며 실제 자발교대자 또는 소유자 단독마무리. 철회없으면 수정 결말만 실제 공연.
  - result: 실제 철회 유무를 그대로 저장. 철회사건을 통과목적으로 만들지 않는다.
  - main_complete: true
- - branch_key: HOLD_A_GUEST
  - available_when: 실제 철회자를 붙잡으려 함
  - actual_action: 디오니소스가 현재 공연을 중단하고 퇴로를 비우게 한다. 소유자가 실제 방해를 해제한 뒤 새 남은구간을 다시 시작할 수 있다.
  - result: 현재결말 미완, b/소유 유지, MAIN 완료/주시0.
  - main_complete: false

### 실패·사망·중단·재개

전체 MAIN 기한 없음. 현실48h·오프라인 실패·서비스 다음회차와 연동하지 않는다. 공연 중 로그아웃/타인철회는 실제 출력된 구간/대여/소비 보존, 남은구간만 재개하되 새 청중은 지나간 부분을 들었다고 하지 않는다. 소품파손은 원주인에게 손실을 알리고 실제 보상/면책 동의로 정산하기 전 대기. 본인소품0이면 반환0. 본체부재면 완료확인만 대기한다.

### MAIN 완료 판정

b 최초 실수령 근거+유일소유자+실제4장면의 선택형태 수행/정산+디오니소스 유효확인. 완료 후 소유자에게만 주시. 후기/축하를 거절해도 이미완료 유지. 음악토글·장신구복구·다른사람포션납품은 MAIN 진행0.

### 보상과 수령

- - reward_definition_id: mythictrpg:p0m1_dionysus_personal_main_gaze
  - kind: existing_gaze_right
  - recipient: 유일 MAIN 소유자
  - lines: - - kind: 주시
      - provider_ref: mythictrpg:greek_olympus_dionysus
      - count: 1
  - earned_condition: MAIN 실제완료 뒤 현재 디오니소스의 주시 연결확인. 주시 기능/연락은 기존 공통시스템 범위이며 새 공격/가호/지원효과0.
  - limit: 개인 최초 1회
  - delivery: 공통 수령 원장 규약. 실제 수행/권리 생성/실수령은 별도. 가방 부족은 바닥 드롭 없이 미수령 보존. 이미 지급된 줄은 다시 지급하지 않는다.

### 별도 선택 서비스

- - definition_id: mythictrpg:p0m1_dionysus_potion_service
  - title: 서로 다른 잔, 현실 마흔여덟 시간
  - kind: repeatable_service_not_personal_main
  - source_card_id: SERVICE-DIONYSUS
  - encounter_design: - fixed: 미사용 기존 마을, 서버 활성 술 의뢰 0건, 단일 본체, 현행 접근·봉인 조건을 유지한다. C06을 만남 제한으로 추가하지 않는다. 실제 등장했을 때만 해당 village_uuid를 사용 이력에 저장한다.
    - adopted_values: 마을 진입 및 서버 활동 600초마다 해당 마을에서 후보를 1회 판정한다. 같은 마을의 같은 600초 구간에서는 재진입하거나 방문자가 바뀌어도 추가로 굴리지 않는다. 첫 용의 공용 격파 전에는 6%, 이후에는 5%로 판정한다. 적격 후보 중 먼저 도착한 server sequence를 사용하며, 동시이면 UUID 사전순으로 등장 주체를 정한다. 세계 재해금 조건이 아니라 조우 실행값이다.
    - body: 본체가 다른 곳에서 실제 활동 중이면 새 출현 추첨은 보류한다. 기존 본체와의 대화에는 재추첨이 없다. 단일 본체가 실제로 이동해 다른 미사용 마을에 머무를 수는 있지만, 이 서비스가 순간이동이나 분신을 허용하지는 않는다.
    - clock: 조우 활동시계는 서버가 가동 중이고 적격 온라인 방문자가 있는 시간만 센다. 수락 뒤 48시간은 UTC 절대시계이며 오프라인과 서버 중단 중에도 흐른다.
  - offer: 현재 합법적으로 수급 가능한 포션 목록과 안전한 인수함, a 또는 b 중 실제 이행 가능한 보상 하나 이상을 확인한다. 요구표·비용·48시간 기한을 모두 보여 준 뒤에만 수락을 연다. b 최초 획득권이 이미 있고 현재 가능한 a 대상도 없으면 새 의뢰 수락만 대기한다. 일반 만남과 대화는 유지한다.
  - requirements: - candidate_order: - minecraft:water
      - minecraft:awkward
      - minecraft:swiftness
      - minecraft:healing
      - minecraft:night_vision
      - minecraft:fire_resistance
    - selection: 현재 접근 가능한 양조법/실재 합법 거래재고/동의 제공재고로 공급 가능성이 확인된 n종 중 min(4,n)종을 무복원 선택하여 각2병 요구한다. n<2면 수락을 열지 않는다. 2종4병/3종6병/4종8병 중1패키지. 최초 유효 제안 때 seed=SHA256(world_generation_seed+village_uuid+"dionysus-v1")를 고정하고 각 potion_id를 SHA256(seed+potion_id) 오름차순으로 정렬하여 앞 min(4,n)종을 선택한다. 동일 후보 목록에서 질문/닫기/재접속으로 재추첨0. 수락 전 실제 가용목록이 바뀌면 같은 고정 순위에서 새 offer_revision을 설명하며, 수락 뒤에는 목록/수량/수급근거 snapshot을 절대 바꾸지 않는다. 여섯 후보 모두 해당 마을의 순위와 실제 수급성에 따라 선택될 수 있다.
    - exact_item: 아이템은 minecraft:potion만 허용한다. potion_contents의 potion ID가 요구 목록과 정확히 같아야 한다. long/strong/splash/lingering, 추가 효과가 있는 커스텀 물약, 이름만 바꾼 물병은 다른 요구 물약으로 세지 않는다. 순정 water/awkward도 실제 보유품을 접수해야 하며 빈 병은 불가하다.
    - source: 양조, 구매, 허락받은 기존 재고, 친구가 동의해 제공한 물품을 모두 허용한다. 새로 양조하도록 강제하지 않는다. 접수할 때 원소유자·기증량·목적 동의를 기록하며, 명시적으로 기증한 수량만 소비한다.
    - deadline: accepted_at UTC + 172800초. 모든 유효 접수시각은 deadline보다 엄격히 빨라야 한다. 정확히 deadline인 접수는 만료로 처리한다. 질문이나 거절만으로는 시계가 시작되지 않는다.
  - objectives: - - objective_key: accept_request
      - action: 내용, 고정 요구표, 48시간 기한, a/b 선택을 확인한 첫 유효 수락자와 서버 활성 의뢰 1건을 함께 확정한다.
      - quantity: 1
      - scope: 해당 술 서비스의 최초 유효 명시 수락자. 개인 MAIN 소유/수락은 필요하지 않다.
      - time: 시간 제한 없음
      - cost: 별도 제출 비용 없음
      - requires: 
      - completion_evidence: service_attempt_uuid+owner_uuid+offer_revision+accepted_at+deadline의 영속commit.
    - - objective_key: deposit_all
      - action: 원래 마을의 안전 접수함에서 3블록 안으로 접근해 실제 포션을 넣고 명시적으로 접수한다.
      - quantity: - types: 수락시2~4종
        - bottles_per_type: 2
      - scope: 해당 서비스 수락자 또는 그가 승인하고 자기 물품 제공에 동의한 실제 기증·접수자. 기여와 소유자를 분리하고 성공 보상권은 서비스 수락자에게만 귀속한다.
      - time: 현실48h
      - cost: 전체 유효 분량이 기한 안에 접수되었을 때 고정 요구량만 1회 소비한다.
      - requires: - accept_request
      - completion_evidence: 개별 potion ID/수량/허락/valid_received_at와 stack serial. 상자넣음만이 아니라 권위접수확인.
    - - objective_key: select_one_reward
      - action: 기한 전에 얻은 성공권을 가지고 현재 디오니소스와 대면해 a 연결 또는 b 장신구 중 하나를 선택한다.
      - quantity: 1
      - scope: 해당 술 서비스 성공 선택권의 소유자만 a/b를 선택한다. b 최초 수령 전이므로 개인 MAIN 소유를 조건으로 요구하지 않는다.
      - time: 시간 제한 없음
      - cost: 별도 제출 비용 없음
      - requires: - deposit_all
      - completion_evidence: success_right+현재 본체확인+choice state. 보상대화지연으로기존 성공만료0.
  - escrow: 부분 납품은 에스크로에 보관만 하고 소비하지 않는다. 잘못된 종류와 초과분은 따로 돌려준다. 마지막 필요 분량까지 기한 전에 유효 접수되면 요구량 소비와 성공권 생성을 journal로 한 번만 정산하고 공용 활성 잠금을 해제한다. 소비 결과가 불명확하면 CLAIM_PENDING으로 남기며 성공·실패 대사로 덮지 않는다.
  - outcomes: - success: 수락자에게만 선택권 1개가 생긴다. 친구의 도움은 해당 기여로만 남는다. 다른 미사용 마을에서 새 회차를 진행할 수 있다. 성공권 하나마다 a 또는 b 중 하나만 선택한다.
    - expired_or_abandoned: 활성 의뢰 잠금만 해제한다. 부분 납품, 잘못된 종류, 초과분 중 소비하지 않은 물품은 원래 접수 장소와 원소유 원장에 남는다. 원소유자가 직접 인수해야 반환 완료다. 마감 연장, 자동 우편, 벌금, MAIN 박탈, 마을 사용 이력 초기화는 없다.
    - repeat: 완료·만료·포기 뒤에는 다음 미사용 마을에서만 새 의뢰를 받을 수 있다. 같은 마을의 이름 변경이나 별칭 UUID로 사용 이력을 우회할 수 없다. 새 미사용 마을이 없으면 대기한다. b는 개인 최초 1회, a는 정상 성공권마다 1회 연결 가능하나 동일 연결을 여러 권리에 중복 정산하지 않는다.
    - choice_switch: a의 실제 양방향 연결 전 또는 b의 실지급 전에는 원장을 확인하고 재선택할 수 있다. b가 DELIVERED가 된 뒤에는 a로 교환할 수 없다. a 연결 뒤 상대가 부탁이나 비밀 공개를 거절하는 것은 정상 대화 결과이며 선택권을 되돌리지 않는다.
  - reward_binding: - a_service_ref: mythictrpg:p0m1_dionysus_conversation_connection
    - b_content_ref: mythictrpg:greek_olympus_dionysus_empty_cup_music
    - b_count: 1
    - b_limit: 개인 신규 획득 최초1회
    - b_first_delivery: b를 선택하고 실제 실물 지급을 확인할 때 영구 entitlement_uuid 하나를 확정한다. 가방이 부족한 경우는 최초 미수령이며 분실 복구가 아니다. 다른 사람이 개인 MAIN을 소유해도 자신의 b 획득은 가능하다.
    - success_right_definition_id: mythictrpg:p0m1_dionysus_potion_choice_right
  - tests: - - deadline-1ms 전체접수
      - 성공권1,늦은보상대화가능.
    - - deadline 정확접수
      - 만료,미소비분반환대기.
    - - 부분접수후중단
      - 소비0,원소유보관.
    - - 같은마을재진입100회
      - 같은600초창추첨1,사용 마을재사용0.
    - - 동시수락2명
      - 활성1건/수락자1/각마감1.
- - definition_id: mythictrpg:p0m1_dionysus_conversation_connection
  - title: a · 지금 답할 수 있는 상대
  - kind: reward_service
  - entry: 해당술성공권 AVAILABLE과 수락자의 대상선택/첫전달문장
  - target_policy: 대상은 명부의 현재 실제 생존/현신이허용된 신이며, 디오니소스가 원문에 유지한 자기인연 또는 이후 실제상호작용으로 알고 있는 named actor만. 실제관계가 있다고 자동허용하지 않고 서비스대상허용·정상 연락수단·봉인·개인접근·현재 응답의사·공개권을모두검사한다. 현재 baseline 관계RPG-S01-079도 신/현신/연락 조건을 통과할 때만 후보이며 이름이있다는이유로강제연결0. 새유명인/772전원/괴수 자동호출0.
  - steps: 가능후보의실제이름과허용연락방식만표시→소유자가1명을선택/첫메시지확정→디오니소스가현재정상수단으로실제요청→대상의유효응답이수락자에게실제도달해양방향대화개시→같은choice entitlement 소진.
  - quantity: 한성공권당실제 양방향연결1회
  - time: 연결만료없음. 한시도 응답관측대기120초 뒤자동강요없이PENDING으로두고사용자가다른대상/ b를선택가능. 실제늦은응답은그request_uuid의권리가아직미사용일때만재검사.
  - cost: 추가 아이템/마나0. 성공권1개만연결개시때소진.
  - failure: 연결 전부재/거절/접근불가/오류면권리유지. 다른대상선택후늦게온옛응답은알림수신범위만처리하고두번째연결/권리소비0. 대화중부탁거절/비밀거절은사용된정상결과.
  - completion: 실제 양방향대화receipt1. 본체소환·강제협력·MAIN·가호·호감·주시·비밀전체공개0.
  - privacy: 첫요청은소유자가정한내용만. 실제대화당사자와허락된청중만수신. 물품도움자/팀은자동청중아님.
- - definition_id: mythictrpg:p0m1_dionysus_music_right_recovery
  - title: 같은 매듭의 분실 복구
  - kind: permanent_right_recovery
  - entry: 유효최초b실지급receipt와현재 기존 권리 소유자 UUID 확인. 분실주장만으로최초 권리생성0.
  - steps: 기존entitlement_uuid/소유자/최초receipt·현재유효실물/미완delivery journal대조→기존실물이인벤토리/접근가능보관함에있으면위치안내만→실물분실이확인되면같은entitlement_uuid의복구transaction과새physical_instance_uuid1을기록→현재유효대표실물generation만착용가능→실지급결과확인.
  - quantity: 기존영구 권리1개의실물복구; 신규 획득증분0
  - time: 기한 없음; 동시요청은같은권리잠금,연속요청안내갱신60초;실패후재시도는같은transaction을대조후계속
  - cost: 추가포션/재료/마나0. 기존 성공권재소비0.
  - completion: 같은entitlement_uuid 유지·신규 획득횟수1. 복구 뒤원품이발견되면구generation은연출/착용불가표시하고같은권리로회수/보관만. 두칸중복/추가 점수/MAIN/주시/가호0.
  - failure: 최초 지급이불명확하면먼저최초journal대조. 최초 미수령을분실 복구로분류하지않는다. 가방부족은복구delivery_pending보존. 타인양도/판매품이있다는사실만으로권리를이전하지않으며권리소유확인중인실물은새사용자를활성화하지않는다.
  - transfer_boundary: 기존승인은판매/양도권리이전의승인이아니다. 이복구기능은기존 소유자권리에만작용한다. 제삼자소유권주장/거래분쟁은서버의별도기존거래정책으로처리하고강제회수/추가 권리를발명하지않는다.

### BGM 장신구 운영값

- content_ref: mythictrpg:greek_olympus_dionysus_empty_cup_music
- management_key: OLY-DIO-A01
- definition_ownership: 기존 동일 콘텐츠 정의의 운영값 완결. 새 장신구/새 획득권 아님.
- name: 빈 잔의 매듭
- equipment_grade: 고급
- provider_tier: 중급
- category_exception: 디오니소스에게 허용한 전용 장신구 1종이다. 다른 중급 신에게 장신구 제공 자격을 자동으로 확대하지 않는다.
- base_item: minecraft:rabbit_foot
- model: 작은 빈 잔을 얇은 포도색 끈으로 묶은 장신구. 고유 모델 제작이 필요하다. 기존 장비 속성은 추가하지 않는다.
- slots: 1
- total_accessory_slots: 2
- fixedPower: 0
- max_mana_bonus: 0
- durability: 신이 준 장비의 기본 무한내구를 적용한다. 분실 불가라는 뜻은 아니며, 복구는 같은 영구 권리의 규칙만 따른다.
- combat_effects: 
- activation: - command: /발동
  - toggle: 실제로 착용한 발동 후보가 하나이면 /발동으로 ON/OFF 한다. 후보가 둘 이상이면 /발동 1 또는 /발동 2로 실제 슬롯을 지정해 전환한다. 같은 권리를 두 칸에 착용할 수 없고 가방에 보유한 것만으로는 발동할 수 없다.
  - mana_cost: 0
  - combat_cooldown_ticks: 0
  - spam: ON 요청은 최소 20tick 간격으로 받으며 OFF는 즉시 허용한다. 이는 전투 쿨이 아닌 명령 제한이다. 장착 교체나 재로그인으로 ON 난사 방지 구간이 초기화되지 않는다.
  - state: 착용 해제, 사망, 로그아웃, 차원 변경, 권리 대표 실물 변경 때 즉시 OFF 한다. 재착용·복구·재접속 뒤 자동 재생하지 않는다.
  - failure: 권리·실물·음원이 준비되지 않았으면 재생하지 않고 현재 원인을 안내한다. 이 실패로 보상이나 MAIN 상태를 바꾸지 않는다.
- audio: - asset_key: mythictrpg:music.dionysus_feast
  - resource_file: assets/mythictrpg/sounds/music/dionysus_feast.ogg
  - provided_by: 사용자가 선택하고 준비하는 음원이다. 이 패킷은 음원을 임의로 선택·복제·배포하지 않는다.
  - asset_contract: Ogg Vorbis 파일을 사용한다. 위치 음원 처리를 위해 모노를 권장하며 실제 파일 길이는 로딩할 때 읽는다. 파일이 없으면 NO_AUDIO와 무음 상태를 안내한다. 사용자가 준비한 곡 끝에서 0.5초 페이드아웃한 뒤, 0.5초 페이드인으로 처음부터 반복한다.
  - radius_blocks: 24
  - volume: 거리 0~4블록은 클라이언트 장신구 볼륨 상한의 0.4, 4~24블록은 선형 감쇠, 24블록 초과는 0이다. 벽 차폐를 새로 만들어 적용하지 않으며 같은 차원에서만 들린다. 음원 위치는 착용자의 현재 위치로 5tick마다 갱신한다.
  - listener_control: 각 청취자는 장신구 음악 ON/OFF와 0~100% 볼륨을 개인 설정한다. 기본은 ON·50%이며 Minecraft 음악 및 전체 음소거를 항상 존중한다. /음악 off로 즉시 음소거할 수 있다. 들었다는 사실이 동의·관계·버프를 뜻하지는 않는다.
  - multiple: 청취자마다 한 곡만 재생한다. 현재 유효 음원을 유지하되, 다른 음원이 4블록 이상 더 가까운 상태가 2초 지속되거나 현재 음원이 범위 밖 또는 종료 상태이면 교체한다. 같은 거리이면 owner UUID 사전순으로 고른다. 전환은 0.5초 교차 페이드이며 두 음원의 피크 합산 상한은 0.4다.
  - raid_warning: 기존 레이드 경고 채널이 우선이다. 경고 중과 종료 뒤 2초 동안 음악 볼륨을 원래의 20%로 낮췄다가 복원한다. 음악이 경고음이나 일반 전투음을 중단하거나 변조하지 않는다.
  - safety: 정화·회복·피해 지연·피해 가산·광란·포션 연장·공격 전환·쿨 환급·숨은 힘 탐지·NPC 소환 효과는 없다.
- ledger: b 최초 수령의 UUID를 영구 유지한다. 신규 획득 1회와 복구 횟수는 별개다. 같은 권리에서 나온 추가 실물을 두 칸에 동시에 착용하거나 음악·점수를 중복 적용할 수 없다. 적법한 별도 권리의 같은 종류 장신구 2개를 일반적으로 허용하는 공통 규칙을 이 1회 권리로 우회하지 않는다.
- implementation: 현재 MUSIC enum이 지원된다고 주장하지 않는다. 실제 장신구 권리·착용 event, 이동 음원, 청취자 설정, 경고 시 감쇠, 재연결 정리 기능의 신규 구현과 실기 검증이 필요하다.

### 제공 재고·예약·보충

- provider: 디오니소스의 빈 잔의 매듭 증여재고
- initial_units: 10
- allocation: 개인 신규b 첫5+후발5. 다른 MAIN 소유자 여부와 독립
- reservation: b 실물 미획득 수락자의 서비스에 b 선택이 가능한 경우 아직미사용 성공권별1개 예약. a로 확정하면 그 b 예약해제
- deduct: b 최초 실지급 journal과 영구 권리확정에서만
- refill: NPC 자기 소유의 실제 빈그릇5+실5가 확보된 때 본체가 안전한 자리에서 각100tick 매듭공정으로 동일품5개만 보충. 초기 별도 제공 재료 그릇10+실10으로 최대2배치. 이후 실제 적법재료가 다시 생길 때만 준비하며 시간경과/납품포션에서 자동 변환0. b재고0이면a만가능한의뢰 또는수락대기
- recovery: 분실 복구는 신규 재고소비/새획득이 아니라 같은영구 권리의실물표현 재발급. 잠금·대표generation으로중복 착용방지
- budget: B2 서비스 보상1권. b 전투력0·마나0·전투 효과0, a 정상 대화1회. MAIN 주시는 별도실제 결과 권리.

### Story Fact와 개인 지식

- - fact_definition_id: mythictrpg:p0m1_dionysus_fact_request
  - statement: 술 서비스의 고정 요구표/수락시각/유효접수/선택결과.
  - activation: 해당 실제 수행/수신 event_uuid가 확정된 뒤만. 작성시 기본locked.
  - speaker_condition: 실제 당사자와 접수기록을 실제 받은 디오니소스.
  - disclosure: 도움자가 a 사적청중이 되는 것아님.
  - fact_and_knowledge_separate: true
  - evidence_fields: - world_id
    - source_event_uuid
    - actor_uuid
    - observed_at
    - source_mode
    - actual_audience_uuids
    - permission_scope
  - memory_rule: 현재본체가 직접관측/실제보고를 수신한 부분만 자기앎. 같은 player_uuid의 실제기억으로만 다음만남을변형하며 후발자는 허락된새열람으로 학습.
- - fact_definition_id: mythictrpg:p0m1_dionysus_fact_music_right
  - statement: b 최초실수령과 같은영구권리 복구.
  - activation: 해당 실제 수행/수신 event_uuid가 확정된 뒤만. 작성시 기본locked.
  - speaker_condition: 권리소유자와 실제제공자.
  - disclosure: 복구를 두번째신규획득/주시로 쓰지 않는다.
  - fact_and_knowledge_separate: true
  - evidence_fields: - world_id
    - source_event_uuid
    - actor_uuid
    - observed_at
    - source_mode
    - actual_audience_uuids
    - permission_scope
  - memory_rule: 현재본체가 직접관측/실제보고를 수신한 부분만 자기앎. 같은 player_uuid의 실제기억으로만 다음만남을변형하며 후발자는 허락된새열람으로 학습.
- - fact_definition_id: mythictrpg:p0m1_dionysus_fact_performance
  - statement: 실제 선택형태와 실제 철회/교대 또는 철회없음.
  - activation: 해당 실제 수행/수신 event_uuid가 확정된 뒤만. 작성시 기본locked.
  - speaker_condition: 그 구간을 실제 본/들은 청중만.
  - disclosure: 사적철회이유는 허락없이 전달0.
  - fact_and_knowledge_separate: true
  - evidence_fields: - world_id
    - source_event_uuid
    - actor_uuid
    - observed_at
    - source_mode
    - actual_audience_uuids
    - permission_scope
  - memory_rule: 현재본체가 직접관측/실제보고를 수신한 부분만 자기앎. 같은 player_uuid의 실제기억으로만 다음만남을변형하며 후발자는 허락된새열람으로 학습.
- - fact_definition_id: mythictrpg:p0m1_dionysus_fact_ending
  - statement: 디오니소스가 잔을 내려놓고 바뀐결말을 실제 받아들였다.
  - activation: 해당 실제 수행/수신 event_uuid가 확정된 뒤만. 작성시 기본locked.
  - speaker_condition: 해당마지막구간 실제청중.
  - disclosure: 기획문만으로 이미겪은기억을 넣지 않는다.
  - fact_and_knowledge_separate: true
  - evidence_fields: - world_id
    - source_event_uuid
    - actor_uuid
    - observed_at
    - source_mode
    - actual_audience_uuids
    - permission_scope
  - memory_rule: 현재본체가 직접관측/실제보고를 수신한 부분만 자기앎. 같은 player_uuid의 실제기억으로만 다음만남을변형하며 후발자는 허락된새열람으로 학습.
- - fact_definition_id: mythictrpg:p0m1_dionysus_fact_gaze
  - statement: 그 유일소유자의 완료뒤 주시가 실제 연결되었다.
  - activation: 해당 실제 수행/수신 event_uuid가 확정된 뒤만. 작성시 기본locked.
  - speaker_condition: 소유자와 실제응답한 디오니소스.
  - disclosure: 음악청취자/친구/복구요청자에게 확장0.
  - fact_and_knowledge_separate: true
  - evidence_fields: - world_id
    - source_event_uuid
    - actor_uuid
    - observed_at
    - source_mode
    - actual_audience_uuids
    - permission_scope
  - memory_rule: 현재본체가 직접관측/실제보고를 수신한 부분만 자기앎. 같은 player_uuid의 실제기억으로만 다음만남을변형하며 후발자는 허락된새열람으로 학습.

### 조건부 대사 예시

- “물어본 것만으로 시계는 안 돌아. 맡겠다고 받아들인 뒤부터지.”
- “한 대목쯤 더 듣고 싶지만, 그건 내가 청할 일이지.”
- “없는 목소리는 빈 채로 두자.”
- “오늘 끝은 네가 골랐지. 다음 첫 대목도 네게 맡기고 싶어.”

### 시간·보상 적정성

술 의뢰는 수급상태에 따라 실제 플레이20~60분을 예상하되 현실48h 여유. MAIN은 별도18~30분·무기한. b는 전투 효과0이라 포션 경제를 무한 전투력으로 바꾸지 않고, 후발자는 장신구만 받을 수 있다. 협력자모집이 필수가 아니어서 단독도완주 가능.

### 새 구현 요구

- 절대 UTC 마감과 활동조우시계 분리
- 마을사용이력/고정 요구표/부분보관/전체정산 journal
- a 실제 양방향연락/대상별현재 접근
- BGM 이동음원·수신자제어·경고우선·동일 권리복구
- 실제 공연구간/역할 철회/소품인수/주시연결

### 구현 인수 사례

- - b수령뒤 MAIN 거절
  - b유지·MAIN 소유0·주시0.
- - 다른 MAIN 소유자존재
  - 자격있는b수령가능·추가 MAIN/주시0.
- - 혼자 낭독
  - 배우모집/철회사건없이4장면완주가능.
- - MAIN48h경과
  - 실패/소유이전0.
- - 음악토글/복구
  - MAIN·가호·주시·두번째권리 변화0.

출처: 기존페르소나_인물별/RPG-S01-014/profile.json, 기존페르소나_인물별/RPG-S01-014/authoring_contract.json, 772명_스토리와기본페르소나_통합.json#/RPG-S01-014/outline, 원문제작범위_보존색인.json#/RPG-S01-014

