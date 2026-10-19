# Story PLAYER_CHOICE 화면·네트워크 계약

2026-09-29 `HanesTest` 개발 소스 기준. 기존 조건 기반 Story 엔진의 `PLAYER_CHOICE`에 플레이어 화면을 연결한다. 소스 구현과 컴파일, GameTest, 서버 JAR 설치, 실제 클라이언트 확인은 별도 단계다. 이 문서는 정식 Story 사건이나 선택 문구를 작성하지 않는다.

## 권한과 흐름

1. Story 엔진이 작성된 사건을 `WAITING_FOR_CHOICE`로 저장하면, 서버는 그 사건의 고정 청중 중 온라인 플레이어에게만 현재 선택 화면을 보낸다. 여러 사건이 대기 중이면 발생 시각과 instance ID 순서로 한 사건씩 페이지로 보여준다.
2. 서버의 `StoryChoiceUiService`는 `WAITING_FOR_CHOICE`, 고정 청중, 현재 definition fingerprint, 현재 조건을 모두 확인한다. 허용된 결과의 ID와 작성된 표시 문구만 보낸다. 효과, 비밀 조건, 전체 Story 정의, 다른 청중의 자료는 보내지 않는다. 선택 가능 결과가 없으면 그 사건은 화면에 보이지 않으며 엔진의 기존 timeout/recovery 처리는 유지된다.
3. 클라이언트는 화면의 `instanceId`, `revision`, `outcomeId`만 서버에 제출한다. `StoryEventService.choose`가 서버 스레드에서 현재 대기 상태·revision·고정 청중·정의 fingerprint·결과 조건을 다시 검사한다. 첫 유효 선택만 커밋되고 중복/옛 클릭은 거절된다. `SERVER`/`TEAM` 사건도 기존 고정 청중 내 첫 유효 선택이 확정되는 엔진 정책을 그대로 사용한다.
4. 닫기/ESC는 보류일 뿐 Story 상태를 변경하지 않는다. `/mythstory choices`로 현재 서버 조회 결과를 다시 연다. 접속 시에도 대기 선택을 다시 보낸다. 새 대기 선택은 화면을 제안하고, 다른 화면이 열려 있으면 닫힌 뒤 보여준다. 선택·timeout·복구 상태로 바뀌면 기존 화면을 갱신하거나 닫는다. 다른 플레이어가 먼저 선택한 뒤의 클릭도 서버가 거절한다.
5. 선택 결과가 `EXTERNAL_EFFECT_PENDING`이면 “기록됐으나 외부 효과 대기”로만 보고한다. effect 검증 실패로 `RECOVERY_REQUIRED`가 된 경우 성공으로 보고하지 않는다. 기존 Story 결과/보상/관계 실행 규칙을 클라이언트가 재구현하지 않는다.

제목과 선택지의 전체 문구 및 설명은 각 화면 요소의 툴팁으로 확인할 수 있다. 긴 설명은 화면에서 첫 줄만 미리 보이지만 툴팁에는 전체 작성 문구를 넣는다. `/mythstory choices`와 페이지 이동의 명시적 조회는 플레이어별 4 서버 틱 간격으로 제한한다. 선택 제출, 자동 갱신, 실패·timeout 복구 알림에는 이 제한을 적용하지 않는다.

스토리 선택 패킷은 protocol `7`에서 추가됐다. 현재 개발본은 공물·가호 확인 내용과 레이드 UI 패킷을 추가한 protocol `8`이다. 새 게임 JAR을 배포한다면 서버와 클라이언트 필수 게임 JAR을 함께 맞춰야 한다. 이 변경은 운영 배포를 뜻하지 않는다.

## 콘텐츠 작성 필드

기존 `data/<namespace>/mythictrpg/story_events/*.json` 정의는 변경 없이 유효하다. `PLAYER_CHOICE`의 `choicePolicy`와 각 `outcomes[]`에 아래 문자열을 선택적으로 작성할 수 있다. Story 작성자가 문구를 결정하며, 미작성 시 화면은 해당 사건/결과의 정확한 namespaced ID를 표시한다. 서버는 빈 문자열·초과 길이를 거부한다. 기존 정의에 임의의 줄거리나 효과를 추가하지 않는다.

| 위치 | 필드 | 최대 길이 | 미작성 시 |
|---|---|---:|---|
| `choicePolicy` | `displayName` | 120자 | 사건 ID |
| `choicePolicy` | `description` | 600자 | 설명 없음 |
| `outcomes[]` | `displayName` | 120자 | 결과 ID |
| `outcomes[]` | `description` | 600자 | 설명 없음 |

필드는 기존 strict/atomic Story reload, definition fingerprint에 포함된다. 대기 중 정의를 바꾸면 기존 정책대로 해당 선택을 다시 해석하지 않고 recovery로 보낸다. 표시 문구에 현재 청중에게 공개할 수 없는 사실을 쓰지 않아야 한다. 화면은 비공개 조건과 effect 본문을 자동으로 이야기로 바꾸지 않는다.

## 구현 위치와 검증

- 권위: `StoryEventService`, `StoryChoiceUiService`, `StoryDefinitionManager`, `StoryDefinitions`.
- 패킷: `StoryChoicePagePayload`(S2C 한 사건/페이지), `StoryChoiceBrowsePayload`와 `StoryChoiceSelectionPayload`(C2S). `DialogueNetwork`에서 등록하고 서버 발신자만 권위 서비스에 전달한다.
- 클라이언트: `ClientStoryChoiceBridge`, `StoryChoiceClientController`, `StoryChoiceScreen`. 클라이언트 전용 화면은 전용 서버 클래스 로딩 경계 밖에 둔다.
- 재개 명령: `/mythstory choices`.
- 검증 소스: `StoryChoiceContractTest`(DTO 한도/구 생성자 호환), 독립 namespace `mythictrpg_story_choice`의 `StoryChoiceGameTests`(대기·고정 청중·revision·중복·저장·timeout). GameTest는 합성 메모리 정의를 설치했다가 복원하며 운영 Story JSON을 만들지 않는다.

소스 작성만으로 컴파일·GameTest·실제 화면 사용을 확인한 것으로 보지 않는다. 특히 실제 클라이언트의 작은 해상도, ESC/재개, 동시 청중 두 명의 선착순, 서버 재접속·정의 reload 경계는 인게임 확인 대상이다.
