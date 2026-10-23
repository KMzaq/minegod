# 일반 플레이 평판 회복 후속

2026-10-21 · `behaivior`. 사용자가 7번 평판 회복의 시험 모드 제한 이유를 물으며 실제 플레이에서도 사용할 수 있도록 구현을 요청했다. [선행 콘텐츠 시스템](CONTENT_SYSTEMS_20261020.md)의 **평판 시험 모드 제한을 이 기록으로 갱신**한다. 1·6번과 기존 일반 대사 품질 작업은 이번 요청 범위가 아니다.

## 원인과 구현

기존 `ReputationService.started/current`가 `RUMOR_TEST`에서만 작동했다. 선행 Story 효과가 이 서비스에 연결되어 일반 `PERSONAL`에서는 실행되지 않았다. Courier/Social/기록 조회/AI 기억 연결도 같은 시험 제한을 갖고 있어 함께 연결했다.

- 평판 서비스는 기존 명시적 `reputation-judgement.json`으로 시작한다. 승인 게임 결과의 회복은 AI 기억 OFF/PERSONAL에서도 가능하다.
- `MemoryFoundationSettings.supportsRumors()`와 기존 개별 설정을 사용해 Courier/Social을 일반 PERSONAL에 연결했다. 목격·시야·현존 엔티티·수신·청중·비공개 대화·세션/턴/버전 검사와 게임 승인 소유권을 유지한다.
- 새 소문 Evidence에 생성 `memoryMode`를 보존한다. 예전 누락 필드는 RUMOR_TEST. 생성 ID·쿨다운·후보·발행·전달·조회에서 모드를 분리하고 다른 모드의 미처리 자료를 삭제하지 않는다.
- Native 기록은 Evidence의 생성 모드를 사용한다. 기존 시험 metadata fingerprint를 유지하고 일반 자료는 PERSONAL을 hash에 포함한다. SQLite 조회와 현재 게임 검증의 모드가 일치해야 한다.
- AI의 NEW 경로에는 현재 게임의 수신/평가 snapshot을 연결했다. 기존 저널 전체·관찰 검색을 켜지 않는다. 실제 발화에 들어간 소문은 기존 출처 reference로 발행 시 재검증하며, 회복된 평판은 역사적 평가로 표현한다.
- 기존 `ReputationLedger`에 회복 batch 완료 영수증을 추가했다. 평가 집합과 영수증은 하나의 draft/용량 검사/commit으로 반영한다. 빈 batch도 기록하며 동일 결과 ID는 이후 평가에 재사용되지 않는다. 대상·신·정책·사유가 달라진 재사용은 거절한다.
- 일반 회복은 알려진 시험 소문 출처의 평가를 제외한다. 출처가 유효하지 않게 된 기존 평가의 정리는 허용한다. 원본 호감도·출처·최초 승인·타인 평가를 유지한다.
- Story preflight에서 신·정책·서비스 사용 가능 여부를 검사한다. 평판 완료 영수증만 저장된 재시작은 완료 확인을 재개하며, 영수증 없는 불명확한 재시작은 `RECOVERY_REQUIRED`로 보존한다.
- 평가와 회복 영수증 각각 4,096개 한도 및 90% 알림. 알림 실패가 이미 commit된 회복 성공을 실패로 뒤집지 않는다.

작성·설정 계약은 [일반 플레이 평판 가이드](../../../mythictrpg-main/docs/REPUTATION_GAMEPLAY_GUIDE.md), 기존 효과 예시는 [콘텐츠 시스템 가이드](../../../mythictrpg-main/docs/CONTENT_SYSTEMS_GUIDE.md)에 둔다. [기획/시스템 분담](../../../전달자료/content-planning-system-split-20261020.md)의 평판 제한 문구도 갱신했다.

## 검증

- 게임 컴파일·개발 JAR 생성 성공.
- `reputationStageTest`: 4,234 assertion; `courierStageTest`: 4,204; `socialPipelineTest`: 87; `storyExternalEffectReceiptsTest`: 10. 대량 용량 삽입을 포함한 코드 검사이며 그 숫자만큼 실제 플레이 장면을 실행했다는 의미가 아니다.
- `rumorRecordingStateTest`: 62; `rumorRecordingCaptureTest`: 30; `nativeRumorReadAccessTest`: 230; `recordedRumorReadTest`: 158. 마지막 검사는 실제 게임 원장과 SQLite를 사용한다.
- AI 컴파일·JAR, `verifyEngineOwnership/verifyEnginePackage` 성공: 소유 AI 클래스551개, 게임 클래스 중복 없음. `rumorDialogueTest`27, `dialogueMemoryRecordingTest`20검사 통과.
- 초기 통합 `mythictrpg-main/build/reputation-gameplay-first-20261021/results.json`: 콘텐츠8·기록3·AI 전체 방 경로1, **12/12 PASS**. 실제 접속 플레이어/앵무새/서비스 lifecycle/Story 실행과 NBT 저장 복원, 다른 모드 및 다른 대상 격리, 늦게 생긴 평가의 재회복 차단을 검사한다.
- 확장 회귀 `build/reputation-gameplay-final-20261021/results.json`: 콘텐츠8·Native 증거1·기록3·Story 선택1·Story 증거1·AI 전체 방 경로1, **6묶음15/15 PASS**. `minecraft`198개 중 구 소문 저장 검사1개는 아래의 이전 가정 때문에 실패했다.
- 저장 검사 수정 후 `build/reputation-gameplay-storage-recheck-20261021/results.json`: **198/198 PASS**. 앞의6묶음과 합쳐 관련 **213개 GameTest의 최신 결과가 모두 통과**이며, 실패한 첫 행렬을 덮어쓰지 않았다. 최종 변경은 테스트의 저장/노출 검사 분리였고 게임 실행 코드를 바꾸지 않았다.
- 최종 JAR 생성 성공, `git diff --check` 통과. 변경 전달 문서4개·로컬 링크12개·JSON 예시 형식 검사 통과.

새 GameTest는 서비스 `started/stopped`와 격리 build 폴더의 설정을 사용한다. 이전의 평판 런타임 reflection 주입을 제거했다. 시험 설정은 같은 테스트 안에서 복원한다. 실제 모델 호출 대신 기존 모의 LLM을 사용하는 방 통합이며 사람의 클라이언트 플레이와 구분한다.

보존한 중간 실패: 새 구 저장 형식 테스트가 dataVersion1 wrapper에 VERSION2 snapshot을 넣어 `rumorRecordingStateTest`1개가 실패했다. 실제 저장 문자열에서 모드 필드만 누락하도록 fixture를 수정한 뒤 62검사 통과했다. runtime의 schema 검사를 느슨하게 바꾸지 않았다. 의도적 손상 입력 검사의 ERROR/WARN은 예상 격리 동작이다.

확장 회귀의 `rumorSavedDataRoundTripAndUnknownSchemaPreserveOriginal`은 시험 출처로 생성한 소문을 현재 PERSONAL에서도 1개 조회할 수 있다고 가정했다. 새 모드 격리 규칙과 충돌하므로 저장 원장의 수신 기록1개 보존과 모드별 노출 허용 수를 각각 검사하도록 수정했다. 출처 격리를 해제하거나 저장 검사를 삭제하지 않았다.

## 적용 상태

개발 표기는 기존 게임1.0.36 / AI0.1.50 / 콘텐츠0.1.3을 유지했다. 두 개발 JAR에는 이 작업 이전의 공유 미커밋 변경도 포함된다. 새 게임 API의 `supportsRumors`를 AI가 사용하므로 배포 시 대응 게임·AI 조합이 필요하다.

최종 SHA256:

- 게임 개발 JAR: `9BC5A39B96517C4B5F3C882EBAD35CCE6E98D07C992C822B1AB036382252FBCD`
- AI 개발 JAR: `9BA722021164888214081229A07DC0AD6D60995AE62235B81E4021E9091423A5`
- 보존 운영 게임1.0.28: `F7F75C034FA152D996407BCAF0653B0E5823CA831F7B17F4FDE2DAE929AFBAD2`
- 보존 운영 AI0.1.19: `E3BA2CF5E2CF1B53F0C75861F8FAF48D2391C214E5A22E0C4BE3A1FB7502BB7A`

운영 JAR·클라이언트 필수 JAR·운영 설정·월드·모델을 수정하거나 운영 서버를 시작/중지하지 않았다. 실제 소문 정책·회복 사건의 정사/수치·목격자 배치는 기획 정의가 필요하다. 소스 연결이 운영 배포나 실제 모델의 설득 품질 합격을 의미하지 않는다.

구 평판 저장본의 새 `recoveries` 필드 누락과 구 소문 생성 모드 누락은 읽을 수 있다. 신규 자료 기록 후 구 JAR로의 무조건적 롤백은 지원하지 않는다. 서로 다른 Story/평판 SavedData 파일의 디스크 쓰기를 하나의 트랜잭션으로 만들지 않았다. 같은 월드의 두 프로세스 강제 종료/재시작과 실제 그래픽 클라이언트 플레이는 미검증이다.
