# Story JSON 작성·검증용 최신 원본 묶음

2026-10-15 / `HanesTest` 작업본에서 복사한 자료다. 현재 개발 버전은 게임 **1.0.29 / AI 응답 0.1.34 / 콘텐츠 0.1.3**이다. 실제 서버 설치·활성 상태와는 구분한다. 파일별 원본 경로·SHA-256은 `manifest.json`에 있다.

## 요청한 네 파일

| 자료 | 묶음 내부 경로 |
|---|---|
| 사건 엔진 가이드 전체 | [STORY_EVENT_ENGINE_GUIDE.md](reference/mythictrpg-main/docs/STORY_EVENT_ENGINE_GUIDE.md) |
| AI 연출·공개 가이드 전체 | [STORY_AI_PRESENTATION_GUIDE.md](reference/mythictrpg-main/docs/STORY_AI_PRESENTATION_GUIDE.md) |
| 전체 정의 파서·참조 검사 | [StoryDefinitionManager.java](reference/mythictrpg-main/src/main/java/com/sande/mythictrpg/story/definition/StoryDefinitionManager.java) |
| Story 조건의 필드·평가 | [StoryConditionTypes.java](reference/mythictrpg-main/src/main/java/com/sande/mythictrpg/story/condition/StoryConditionTypes.java) |

원본의 내용을 수정하거나 일부만 잘라 보내지 않았다. 가이드에 적힌 작성일·버전은 과거 기록일 수 있으므로 아래 주의와 현재 파서·정의 코드를 함께 읽는다. 가이드 안의 일부 링크는 원래 전체 프로젝트의 다른 자료를 가리키며, 그 자료까지 전부 들어 있는 묶음은 아니다.

## 전체 정의 확인에 필요한 보조 자료

- [StoryDefinitions.java](reference/mythictrpg-main/src/main/java/com/sande/mythictrpg/story/definition/StoryDefinitions.java): 모든 enum, record 필수값, 값의 범위와 필드 조합 검사. 파서만 읽으면 놓칠 수 있는 제약이 있다.
- [ConditionTreeParser.java](reference/mythictrpg-main/src/main/java/com/sande/mythictrpg/condition/engine/ConditionTreeParser.java): 조건 트리 공통 형식과 깊이/노드 제한.
- [BuiltinConditionTypes.java](reference/mythictrpg-main/src/main/java/com/sande/mythictrpg/condition/builtin/BuiltinConditionTypes.java): `all`, `any`, `not` 등의 실제 필드 형식.
- [ConditionScope.java](reference/mythictrpg-main/src/main/java/com/sande/mythictrpg/condition/api/ConditionScope.java) 및 [StoryStateView.java](reference/mythictrpg-main/src/main/java/com/sande/mythictrpg/story/api/StoryStateView.java): 공통 조건 scope와 Story 지식 holder의 enum·조회 계약.
- [Story 선택 화면 후속 가이드](reference/mythictrpg-main/docs/STORY_CHOICE_UI_GUIDE.md): PLAYER_CHOICE 화면과 표시 문구 작성 필드.
- [개인 공개·습득·조우·MAIN 연결 지원 범위](reference/전달자료/story-game-connection-support-20261015.md): 지원 범위와 아직 연결이 필요한 항목.
- `reference/mythictrpg-main/src/main/resources/data/mythictrpg/mythictrpg/story_*/`: **현재 저장소의 완성된 엔진 예제 JSON 23개**. Actor/Location/Fact/Cover/Disclosure/Event/Hook/Presentation을 함께 참고한다.
- `reference/mythictrpg-main/src/main/resources/assets/mythictrpg/lang/ko_kr.json` 및 `en_us.json`: 예제의 canonicalTranslationKey·fallbackTranslationKeys에 대응하는 현재 언어 파일 전체 원본.

예제는 엔진 검증용 비정사 자료다. 본편 줄거리나 반드시 등장해야 하는 인물을 확정한 자료가 아니며, 복사해서 서버에 일괄 설치하는 팩도 아니다. `pack.mcmeta`는 제공하지 않는다. Java 파일은 필드·검증 규칙을 읽기 위한 원본이며 이 묶음만으로 실행되는 별도 검증 프로그램은 아니다.

## 읽는 순서

1. 가이드에서 파일 범주·책임·저장 경로를 확인한다.
2. `StoryDefinitionManager`의 해당 `parse*` 메서드에서 실제 허용 필드, 필수/선택 필드, 기본값을 확인한다. `fields(...)`의 허용 목록이 곧 필수 목록은 아니다. `string`, `array`, `integer` 호출과 `optional*` 호출을 구분한다.
3. 해당 `StoryDefinitions` record 생성자에서 필수 조합, enum, 배열/숫자/문구 범위를 확인한다.
4. 조건은 `StoryConditionTypes`와 공통 조건 파서를 대조한다.
5. 예제의 전체 파일과 참조 관계·번역 키를 확인한다. 임시 ID를 실제 콘텐츠 ID로 바꾸고 참조를 모두 맞춘다.
6. 최종 완성본은 실제 프로젝트의 Story reload/검증 경로에서 전체 정의와 함께 확인한다. JSON 구문만 통과한 것을 의미·참조·실행 검증 완료로 표시하지 않는다.

## 최신 코드 기준으로 주의할 차이

- Story 정의는 **schemaVersion 1**이다. AI 콘텐츠 프로필/로어의 schemaVersion 2와 섞지 않는다.
- 사건의 `scope`는 `SERVER / PLAYER / TEAM`이다. Story **조건과 SET_FACT 효과의** `scope`는 `EVENT_SCOPE / SERVER / TRIGGER_PLAYER / TRIGGER_TEAM`이다. 개인 조건에 `scope: "PLAYER"`를 복사하면 파서가 거절한다.
- `story_knowledge` **조건**의 `holder`는 `PLAYER / ACTOR`다. `GRANT_KNOWLEDGE` **효과**의 `holder`는 `TRIGGER_PLAYER / ACTOR / TRIGGER_TEAM / ALL_PLAYERS_CURRENT_AND_FUTURE`다. 모든 PLAYER 문자열을 일괄 치환하지 않는다.
- 조건 트리는 최대 깊이 32, 최대 노드 256이다. Fact 지식 레벨은 1부터 연속되어야 하며 실제 범위/조합은 `StoryDefinitions`를 따른다.
- `STORY_EVENT_ENGINE_GUIDE`의 과거 "PLAYER_CHOICE GUI가 없다"는 문구는 최신 상태가 아니다. 후속 선택 화면이 구현돼 있으므로 동봉한 `STORY_CHOICE_UI_GUIDE`와 현재 표시 필드를 따른다. 이 보조 가이드의 과거 protocol 번호도 현재 설치 버전의 확인 자료로 사용하지 않는다.
- 기본 ID는 파일 경로에서 만들어진다. 정의 간 참조, effectId 중복, 공개 정책 순환, 잘못된 Hook/event scope·trigger 등은 전체 reload 검사의 대상이다. 빠진 실행 연결을 임의의 새 JSON 필드로 해결한 것처럼 작성하지 않는다.
- 가이드의 "알 수 없는 ID 거절"을 모든 조건 내부 참조까지 검사한다는 뜻으로 확대하지 않는다. 현재 `validateReferences`는 조건 트리 안의 Fact/Event/Actor/Location ID를 전부 순회하여 존재 검사하지 않는다. namespaced 형식 검사와 실제 등록 여부 검사는 다르므로, 최종 작성본에서는 이 조건 내부 참조도 별도로 대조해야 한다.

## EXPLICIT 호출에 남길 실행 검증 요구

사용자가 지적한 **디오니소스의 호출로 완전 봉인된 신을 만나지 못하게 하는 조건**은 자동 `appearance_conditions`만으로 보장할 수 없다. 호출 기능의 구현 담당이 다음을 실행 전 검증해야 한다.

- 호출 대상의 실제 봉인/존재 상태와 현재 대화·등장 가능 상태.
- 호출 플레이어의 접근 자격과 해당 호출 경로에 적용할 예외 규칙.
- 대기·비동기 처리 후 실제 실행하는 시점에도 같은 대상/조건이 유효한지 재검증.
- 조건 미충족 시 소환·등장·대화 참가를 확정하지 않고 거절 결과를 전달.

이 요구를 현재 구현 완료로 선언하거나 새 Story JSON 필드가 이미 지원된다고 설명하지 않는다. 본 묶음은 자료 전달이며 호출 코드·봉인 조건·정식 스토리·운영 서버를 변경하지 않았다.

## 묶음 검사 범위

`manifest.json`의 각 사본은 원본과 SHA-256을 대조한다. ZIP 내부 파일도 같은 바이트인지 확인한다. 예제 JSON은 구문을 확인하되, 새로운 최종 Story 콘텐츠를 서버에 로드하거나 실행하지 않는다. 이번 전달 작업에서 Java 빌드/서버 실행/LLM 호출은 하지 않는다.
