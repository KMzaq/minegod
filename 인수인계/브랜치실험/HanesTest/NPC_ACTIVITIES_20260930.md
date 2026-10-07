# NPC 생활활동 묶음 — 2026-09-30

개발 **게임 1.0.23 / protocol 10 · AI 0.1.26 · 콘텐츠 0.1.3**. 운영 미배포. 사용자가 앞선 작업 마감 이후 별도로 요청한 인테리어 인식·생활활동 범위이며 무관한 후속 RPG 개발을 재개한 것이 아니다.

## 사용자 확정 사항

- 관찰/휴식/독서/산책/대화/듣기/동행/안내/물러남/시설 점검, 식음/훈련/제작/수리/조리/농사/의식/공물/놀이/공연/신끼리 교류를 함께 설계·구현한다.
- 연출 활동과 실제 자원 소비 작업을 모두 지원한다. 플레이어 인벤토리는 제외한다.
- 플레이어의 “쓰지 마”는 페르소나·관계·상황에 따라 거절/계속 사용 가능하다. 관리자 강제 금지는 별도로 항상 실행을 막는다.
- 대련은 NPC 초대 결정 + 플레이어 수락이며 실제 체력·장비 손상/죽음/보상을 만들지 않는다.

## 구현과 안내

계약·권한·종류별 실제 동작·제한은 [게임 활동 시스템](../../../mythictrpg-main/docs/NPC_ACTIVITY_SYSTEM.md), 작성과 명령은 [활동 JSON 가이드](../../../mythictrpg-main/docs/NPC_ACTIVITY_AUTHORING_GUIDE.md), AI 부분은 [AI 계약](../../../mythai-ai-response/NPC_ACTIVITY_AI_GUIDE.md)을 따른다. 이 파일에 스키마를 중복 정의하지 않는다.

- 게임: 실제 실체/로드된 공간의 후보 생성, OP 시설/하드 금지, 자리/경로/도달 검증, 비동기 응답 현재성, 활동 상태/중단/재개, 보관함 NBT, 자세/소품, 실제 작업, 무피해 근접 대련, 명령어를 연결했다.
- 대화: 기존 방 요청의 GodState에 해당 NPC 활동만 공급하고 `npc_activity_request`를 게임 Gateway로 보낸다. 일반 정보/회상 분류여도 이 타입만 별도 허용하며 다른 퀘스트/보상/타격 권한은 늘리지 않았다. primary/live/current token만 가능하고 secondary/시험방은 읽기 전용이다.
- 자율 활동: 기존 Ollama/ModelAdmission을 사용한다. 실제 동석 신의 공개 주변 발화는 최대 4줄이다. 자율 요청은 다른 비밀 대화의 관계/감정을 가져오지 않는다. 실제 대화방을 위조하지 않는다.
- 공용 활동 JSON 29개(연출 22 + 실제 vanilla 작업 7), 비활성 정책/실제 의식·대련 예제, 엄격한 작성 데이터 검증을 추가했다. **기존 신의 활성 정책과 능력치/스킨/출현 장소는 새로 정하지 않았다.** 운영 허용 목록이 있어야 자율 활동한다.
- 기존 게임 API를 쓰는 AI JAR이 구버전과 조합되지 않도록 새 개발 버전·AI 최소 게임 버전·양쪽 빌드 기본 입력을 함께 올렸다. `mine`, `mine2`, 받은 원본, 서버 설치 파일은 수정하지 않았다.

## 확인한 것

- 게임 컴파일/JAR 성공. `roomActionGatewayTest` 71 + `roomTurnSequenceTest` 103 = **174 checks** 통과. 새 상수가 누락된 기존 독립 fixture를 실제 API에 맞춰 갱신했다.
- AI 최종 0.1.26: `godActivityAiTest` **128**, primary prompt **164**, secondary **40**, room engine **98** = **430 checks** 통과. `verifyEngineOwnership`/`verifyEnginePackage`: **488 classes, 게임 클래스 중복 없음**. 실제 LLM 호출 없는 검사다.
- 핵심 새 GameTest **4/4**(생활 실행/비동기, 몸·대련, 자원 거래, 작성 스키마). 기존 avatar 회귀 3개를 포함한 합동 검사 **7/7** 통과 후 마지막 거래 재진입·NaN 보강에서 해당 4개를 다시 통과했다. 반복 실행 숫자를 더해 고유 테스트 수로 주장하지 않는다.
- 최종 개발 JAR 조합 **게임 1.0.23 + AI 0.1.26 + 콘텐츠 0.1.3**의 격리 부팅·리소스 스키마 **1/1** 통과(152.0ms, `build/npc-activity-modules-20260930/logs/latest.log`). AI 엔진 초기화와 새 API 로드를 확인했지만 플레이어/NPC를 생성하거나 LLM을 호출하지 않은 검사다. 해당 격리 디렉터리의 기본 모델명 로그는 운영 모델을 바꾼 결과가 아니다.
- 실제 아이템 소비/잔여물/용량부족/원상복구, 플레이어 인벤 제외, 작물 재파종, 허용 의식, 관리자 차단, 실제 Player.attack 무피해/무내구, 외부 피해 정상 처리, 사망 시 실물 1회 드롭/소품 제외, 계단·반블록 좌석, NBT 왕복, unload 명시 중단 뒤 재개, 동일 완료의 이중 소비 방지, 취소·사망 후 늦은 응답 거절을 검사했다.
- 초기 실패: 존재하지 않는 mapped 블록 클래스 3개와 CustomModelData fixture 타입을 수정했다. Body reload fixture의 tickCount=19 직접 tick 문제는 20으로 수정했고 생산 reload 회귀가 통과했다. 새 모의 로그인 클라이언트의 Story 채널 미등록 경고와 새 개발 디렉터리의 초기 server.properties 부재는 실제 사용자 UI 검증 증거가 아니다.

개발 로그 위치:

- `mythictrpg-main/build/npc-activities-20260930-final/logs/latest.log`: 기존 avatar 포함 7/7 (528.2ms).
- `mythictrpg-main/build/npc-activities-20260930-release/logs/latest.log`: 마지막 자원 안전 보강 4/4 (598.9ms).

스키마 fixture의 최종 namespace는 `mythictrpg_npc_activity_schema`로 분리했다. 전체 핵심 재실행 시 `mythictrpg_npc_activity,mythictrpg_npc_activity_body,mythictrpg_npc_work,mythictrpg_npc_activity_schema`를 사용한다. PowerShell에서는 쉼표가 있는 `-ProomTestNamespaces=...` 인수를 따옴표로 감싼다.

최종 개발 산출물 SHA-256:

- `mythictrpg-main/build/libs/mythictrpg-1.0.23.jar`: `E824D6C0A8BC3AF82055A02BD4F32C4DBB642C052F4396A82778FBF753A98966`
- `mythai-ai-response/build/libs/mythai_ai_response-0.1.26.jar`: `378B2477C7CECD3DC7927EA6BDBD88BB1D524006F7903A918586075C27374BF7`

## 미완료/미확인 범위를 숨기지 않을 것

1. **운영 미배포·미활성:** `server/mods`, `client-required-mods`, 설정/월드/기억은 건드리지 않았다. 실제 사용할 신의 아바타 및 활동 허용 JSON과 재료/시설을 준비해야 한다. 배포 시 서버와 클라이언트 게임 JAR을 protocol 10으로 함께 맞춘다.
2. **실모델·클라이언트 미확인:** 자연스러운 선택·항의 거절·다신 대화 품질, 실제 스킨/장비/자세/소품 화면, 원격 클라이언트 조작은 검증하지 않았다. 테스트 성공만으로 자연스러운 세상 전체가 완성됐다고 하지 않는다.
3. **외부 모드 호환 범위:** 임의 기계/IItemHandler/특수 레시피·특수 finishUsingItem 구현은 미지원이다. 기존 확정 API로 원자적 실행을 보장할 수 없어 미소비 거절한다. 일반 vanilla 저장소/레시피/작물과 그 타입을 쓰는 데이터팩을 지원한다. 모든 모드 사용을 지원했다고 하지 않는다.
4. **기억/감정:** NPC-only 주변 대사는 실제 동석자에 대한 짧은 활동 이력까지만 연결된다. 기존 장기 기억·소문 청취 원장 통합은 아직 없다. 자율 활동에 사용할 공용 감정/플레이어별 사회 상태 원본이 없을 때 UNASSESSED를 유지한다. 방의 비밀 감정을 복사하지 않는다.
5. **콘텐츠 규칙 미확정:** 공물의 관계/퀘스트 보상, 신전 봉헌, 영구 가호, 임의 게임의 규칙/보상, 장거리 청크 간 여행은 만들지 않았다. 의식은 기존 허용 소리/입자만, 수리는 vanilla 두 도구 수리이며 새 강화 시스템이 아니다.
6. **저장 검증 범위:** NBT 왕복과 unload 이벤트 상당의 실제 중단→재개를 확인했다. 실제 두 프로세스 재시작·강제 종료 중 경제 트랜잭션 내구성·모든 외부 모드 재진입까지 검증한 것이 아니다.

이 묶음 이후 무관한 다음 시스템을 자동 착수하지 않는다. 공용 인수인계의 완료 상태로 승격하지 않는다.
