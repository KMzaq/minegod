# 일반 대화방 사회·평판 경로 포트 — M3 부분 구현

2026-09-30 갱신 · `HanesTest` · 개발 소스. [05 M3 계획](../../../추가개발/05_AI_장기기억_다중신_대화_시스템/08_단계별_구현계획.md)의 일반 방 포트에 한정한다. M3 전체, 운영 소문·평판 활성화 또는 서버 배포 완료 기록이 아니다.

## 실제 연결 범위

- `ConversationRooms`가 수락한 플레이어 발언의 게임 발행 receipt와 같은 `roomId/revision/turnId/turnSequence`의 **전체 신 대사 묶음이 실제 전달된 뒤에만** 기존 `SocialRuntime`에 알린다. 첫 대사·일부 HUD 페이지만으로 완료시키지 않는다. 메시지 ID와 최신 방 턴 순서로 중복·늦은 결과를 거절한다. 방 선택 UI는 사회 판단 권한이 아니다.
- `SocialRuntime`은 기존 `CourierRumorService`의 같은 실제 전서구·epoch·작성된 관측 규칙·양 시점 거리/차폐를 그대로 사용한다. `PRIVATE` 방은 일반 대화 소문을 생성하지 않는다. 공개 방에서도 플레이어 발언과 답변의 서버 측 전체 발송 증거, 최대 16명인 원문 공개 청중, 신별 비공개 설정, 차폐 조건을 만족하지 못하면 후보를 만들지 않는다. 전서구를 스폰하거나 소문을 추측하지 않는다.
- AI가 해당 방의 기존 기억 검색에서 선택하여 최종 prompt에 실제 포함한 소문 root만 `roomRecoveryTopics`로 전달한다. `RoomRumorAccess`로 `PRIVATE` 다신 방에서도 같은 소문을 현재 모든 신이 수신했고 전체 플레이어 청중에 허용된 경우 선택한다. 신별 평가/믿음은 동일할 필요가 없다. 게임은 같은 턴·관계 원본·소문과 평판 항목 버전을 회복 확정 전 다시 확인한다. 종전 비동기 `SocialReview`와 게임 소유 `ReputationService`·단회 Reviewer를 재사용한다. Primary 중심 연결이며 모든 Secondary의 독립 회복 심사를 자동 실행하는 것은 아니다. 새로운 AI 평판 원본이나 Reviewer는 없다.
- `RoomSocialContext`의 `REPUTATION` 공급자는 기존 게임 평판 저장소와 현재 소문 수신을 읽기 전용으로 투영한다. **사적 방에서** 실제 수신 신과 전체 참가 신·플레이어에게 허용된 현재 평가만 한정적으로 제공한다. `PUBLIC` 방은 기존 소문 자료에 명시적 공개 허가가 없으므로 `UNKNOWN`으로 남긴다. 다른 신만 아는 소문, 16명을 넘는 청중, 긴 전언, 소스/평가 불일치는 전체 생략한다. 원본 affinity를 대체하거나 확정 월드 사실·권한으로 승격하지 않는다.

## 유지한 경계와 아직 아닌 것

- 사회 파이프라인은 기존처럼 `ai-memory-foundation=RUMOR_TEST`에 `social-rumor`, `rumor-courier`, `reputation-judgement`, AI review의 각 명시적 설정·콘텐츠가 맞아야 동작한다. `PERSONAL`과 `OFF`에 자동으로 켜지지 않으며, `RUMOR_TEST`의 AI 게임 액션 읽기 전용 제한도 풀지 않는다. `TEST_EPHEMERAL` 방은 새 사회 게임 상태를 만들지 않는다. 소스 연결과 운영 활성화는 별도다.
- 공개 방의 사적 소문 평판 공개, 새 공개 권한 정책, 모든 채널의 M2 수집, M3 행동·주시 durable cursor/KnowledgeReceipt, M4~M7, 수치 효과·콘텐츠 매핑은 이 포트의 완료 범위가 아니다. 공개할 근거가 없으면 숨긴다. 서버 발송 receipt는 클라이언트 수신 확인이 아니다.
- 최초 포트 검사는 `socialPipelineTest` 68 / `roomTurnSequenceTest` 103이었다. 후속 재사용 보완에서 사회87·평판4,221·사회맥락2,074 및 AI 관련 검사가 통과했고, 세 모듈 방 GameTest1/1을 재실행했다. [최신 상세 결과](MEMORY_REPUTATION_REUSE_20260929.md)를 따른다. 실제 사회 Reviewer·물리 전서구·다인 방을 결합한 전용 종단 검사, 실제 LLM·클라이언트·운영 배포는 하지 않았다. 일반적인 방 경로에 코드를 연결한 것과 운영 모드에서 활성화한 것은 다르다.
