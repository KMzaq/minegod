# 신의 자율성·관계·사회적 맥락 — HanesTest

작성일: 2026-09-29. 실험 브랜치 전용이며 공용 완료 상태나 서버 배포 기록을 대체하지 않는다.

## 목표와 구현 범위

신을 자동 도움 제공자가 아니라 자기 가치와 의지로 판단하는 존재로 묘사한다. 기본 신격/사회적 위상과 실제 전투 우위, 친밀함, 두려움, 의무를 분리한다. 친한 신도 거절하거나 화낼 수 있고, 강한 인간을 경계하는 신도 자존심과 불쾌함을 유지할 수 있다. 모두에게 위협·훈계·복종을 강제하지 않는다.

- Primary 대사, Secondary 반응, 무리 분리 판단에 공통 `DivineSocialPrompt`를 공급한다. 기존 2단계 분류/생성은 유지하며 분류를 최종 사회적 판결로 취급하지 않는다.
- 실제 게임 호감도에서 작성된 9단계 관계 지침을 선택한다. 기존 대화방에서 `R_NEUTRAL`로 고정되던 부분을 교체했다.
- 현재 감정을 별도 공급하지 않는 경로는 `E_UNASSESSED`다. 차분함을 확정하는 `E_NEUTRAL`이 아니다. 페르소나·대화 이력으로 현재 반응을 해석하되 AI가 감정 수치를 영구 저장하지 않는다.
- 데메테르의 자애로움과 포르투나의 장난기를 유지하면서 독립적인 동기·거절·조건부 도움을 보강했다. 프로필 스키마는 추가하지 않았다.
- 새로운 전투력/후원 계약 시스템이나 별도 관계 원본 DB는 만들지 않았다. 아직 연결된 근거 공급자가 없으면 힘·후원·의무·평판은 `UNKNOWN`이다. 자동으로 강약이나 후원자를 알아내는 기능이 완성된 것은 아니다.

## 게임 → AI 입력

구현 위치: [게임 social 패키지](../../../mythictrpg-main/src/main/java/com/sande/mythictrpg/ai/social/RoomSocialContext.java), [대화방 연결](../../../mythictrpg-main/src/main/java/com/sande/mythictrpg/ai/server/ConversationRooms.java), [공통 해석 지침](../../../mythai-ai-response/src/main/java/com/sande/mythictrpg/ai/DivineSocialPrompt.java).

`RoomConversationEngine.Request/GodState` 생성자 규격은 유지한다. 선택된 화자의 `GodState`에 다음을 전달한다.

- `relationshipTier`: 현재 발화 플레이어와 해당 신의 게임 호감도로 선택한 `R_*`.
- `emotionTag`: 현재 경로에서는 `E_UNASSESSED`.
- `gameContext`: 기존 문맥에 `[GAME_SOCIAL_CONTEXT]` JSON 블록을 추가한다. 참가자별 `playerId/affinity/tier/source`, 정책 출처/경계/revision, 화자, `TURN` 또는 `SYSTEM_SPLIT_CONTEXT`, 공개 가능한 `confirmedFacts`가 포함된다.
- 분리 판단에는 특정 발화자가 없으므로 `currentPlayerId`를 넣지 않는다. 참가자별 관계를 모두 유지하고 후보 목록의 첫 사람을 우선하지 않는다. AI 내부 정적 콘텐츠 조회에 쓰는 중립 carrier는 특정인 관계 지침으로 프롬프트에 제공되지 않는다.

조회는 게임 스레드에서 수행하고 불변 스냅샷을 비동기 AI에 넘긴다. 응답/분리 판단을 적용하기 전에 방·청중·호감도·정책·공급자 revision/허용 근거를 다시 확인한다. 변경되거나 조회에 실패하면 예전 응답을 적용하지 않는다.

호감도 저장소가 준비돼 있지만 해당 항목이 없는 경우는 기존 게임 기본값 `0`이며 출처를 `GAME_DEFAULT_ZERO`로 표시한다. 저장소가 준비되지 않은 경우 임의의 중립으로 계속하지 않고 요청 준비에 실패한다.

## 관계 경계 JSON

서버 루트 기준 `config/mythictrpg/ai-affinity-tiers.json`으로 경계를 바꿀 수 있다. 이번 작업은 운영 설정 파일을 생성/교체하지 않는다. 파일이 없으면 아래 **테스트용 임시 해석값**을 쓰며 `TEST_DEFAULTS`라고 표시한다. 영구 게임 밸런스 확정이 아니다.

```json
{
  "schemaVersion": 1,
  "revision": 1,
  "thresholds": [-900, -600, -300, -100, 100, 300, 600, 900]
}
```

| 기존 게임 호감도 | 선택하는 태그 |
|---|---|
| -1000 ~ -900 | `R_EXTREME_HOSTILE` |
| -899 ~ -600 | `R_HOSTILE` |
| -599 ~ -300 | `R_DISLIKE` |
| -299 ~ -100 | `R_WARY` |
| -99 ~ 99 | `R_NEUTRAL` |
| 100 ~ 299 | `R_FAVORABLE` |
| 300 ~ 599 | `R_FRIENDLY` |
| 600 ~ 899 | `R_TRUSTED` |
| 900 ~ 1000 | `R_DEEP_BOND` |

경계 8개는 -1000..1000의 엄격히 증가하는 정수이며 앞 4개는 음수, 뒤 4개는 양수다. 음수는 해당 단계의 상한 포함, 양수는 해당 단계의 하한 포함이다. 파일 설정 출처는 `CONFIGURED`다. 수치를 변경할 때 `revision`도 증가시킨다. 다음 요청과 응답 재검증에서 다시 읽으므로 JAR 재빌드가 필요 없다. 문법 오류/과대 파일은 무시하고 계속하지 않고 실패한다.

이 정책은 AI용 관계 해석만 바꾼다. 호감도 원본, 기존 보상/퀘스트 조건, 전투력은 수정하지 않는다. 개인 관계, 신↔신 관계, 현재 감정과 서버 전역 진행도를 합치지 않는다.

## 힘·후원·의무·평판 공급 계약

[RoomSocialContextProvider](../../../mythictrpg-main/src/main/java/com/sande/mythictrpg/ai/social/RoomSocialContextProvider.java)는 기존 게임 서비스에 붙일 읽기 전용 Adapter다. 서버 스레드에서 `RoomSocialContext.register(server, providerId, provider)`로 등록한다. LLM이나 플레이어의 주장을 그대로 등록하는 쓰기 API가 아니다.

공급자는 전달된 정확한 `Scope`를 되돌리는 `ProviderSnapshot`을 반환한다.

- `providerId`, `revision`: 공급자 식별 및 버전. 관련 상태 변경/되돌림/공개 권한 변경 때 버전을 증가시킨다.
- 각 `Fact`: `id`, `subjectPlayerId`, `kind`, `statement`, `sourceId`, `evidenceId`, `evidenceRevision`, `knownByGodIds`, `publication`, 선택적 `referencedGodId`.
- `kind`: `POWER`, `PATRONAGE`, `OBLIGATION`, `REPUTATION`. 근거는 정확한 범위를 서술한다. 예를 들어 특정 대결의 승리와 지금의 전투 우위, 보호 계약의 존재와 즉시 개입 가능성은 별개다.
- `sourceId`는 해당 공급자의 ID와 같아야 한다. 다른 방/신/플레이어용 Snapshot을 재활용할 수 없다.
- `knownByGodIds`에 현재 화자가 있어야 한다. 비밀 대화에서는 전체 플레이어 청중과 참가 신 모두에게 공개가 허용돼야 한다. 공개 대화에는 `publicAllowed=true` 근거만 들어간다.
- 원본 근거 전체를 프롬프트에 넣고 숨기라고 하지 않는다. 허용된 사실의 진술과 출처/근거 참조만 추려 넣고 내부 허용 목록은 노출하지 않는다.
- 참조된 후원 신은 자동 참가자가 아니며, 그 신의 대사나 행동 권한을 부여하지 않는다. 높은 호감도·주시권·가호도 자동 보호 계약으로 간주하지 않는다.

등록 공급자는 최대 8개, 공급자 원본 사실은 최대 64개, 최종 허용 사실은 최대 12개다. 공급자 실패/범위 불일치/한도 초과는 조용히 누락해 확신하는 대신 요청을 실패시킨다. 단순 근거 부재는 실패가 아닌 `UNKNOWN`이다.

후속 게임 콘텐츠에 실제 전투 우위 판정이나 후원 계약이 생기면 이 인터페이스에 연결해야 한다. 지금은 이를 추정하는 가짜 원본 데이터를 만들지 않는다.

## AI → 게임과 권한

대사/Proposal 출력 스키마, 게임 검증/실행, 읽기 전용 테스트 제약은 유지한다. 신의 위상이 높거나 플레이어의 후원자가 있다고 아이템 지급·피해·퀘스트·방 제어 권한이 늘어나지 않는다. 친절한 답변과 실제 행동 성공을 구분한다.

실험 산출물 버전은 게임 `1.0.17`, AI `0.1.20`, 콘텐츠 `0.1.3`이다. AI 최소 게임 의존성은 `1.0.17`로 올렸다. 콘텐츠 조회 API는 그대로라 AI 최소 콘텐츠 의존성은 기존 `0.1.2`이나 이번 프로필 변경을 쓰려면 콘텐츠 `0.1.3`이 필요하다. 게임 개발용 통합 테스트 입력도 새 조합을 사용한다. `mine/mine` 레거시 빌드 의존성은 그대로다.

## 검증과 배포

최종 검증/설치 상태는 [브랜치 인수인계](PROJECT_HANDOFF.md)의 2026-09-29 기록을 따른다. 고정 문맥 비교용으로 16개 합성 사례를 준비했으며 이는 실제 모델의 자연스러움이 개선됐다는 증거가 아니다. 같은 부탁에 대한 관계/분노 차이, 확인되지 않은 후원 주장, 확인된 후원과 미확인 개입, 힘의 우위/미확인을 비교한다. 고정 NPC 답변은 만들지 않았다.
