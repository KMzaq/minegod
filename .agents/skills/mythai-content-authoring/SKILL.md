---
name: mythai-content-authoring
description: "markmar의 신 프로필, 단계형 로어, 태그 예시대화, 정적 신 관계, 퀘스트 목록 JSON을 작성·검사·수정한다. God ID와 파일 기반 콘텐츠 ID, 참조 무결성, 서버 적용 위치를 확인하는 작업에 사용한다. 게임 실행 기능 자체는 구현하지 않는다."
---

# MythAI 정적 콘텐츠 작성

[루트 지침](../../../AGENTS.md)을 따른다. 사용자가 수정한 프로필과 창작 설정을 먼저 보존하고, 기존 스키마를 추측해서 새 필드를 만들지 않는다.

## 작성 기준 찾기

`mythai-ai-content-registry/`에서 작업별 가이드를 읽는다.

- 신 프로필: `NPC_PROFILE_AUTHORING_GUIDE.md`
- 로어·예시·정적 관계: `AI_CONTENT_REGISTRY_CONTENT_AUTHORING_GUIDE.md`
- 퀘스트 목록: `QUEST_LIST_AUTHORING_GUIDE.md`, `QUEST_LIST_TEMPLATE.json`

현재 파서는 `src/main/java/com/sande/mythaiaicontent/content/AiContentRegistry.java`다. 가이드, 실제 parser/record, 정상 로드되는 기존 파일을 대조한다. 게임 바인딩/보상은 `mythictrpg-main/docs/`의 해당 가이드를 따르며 콘텐츠 목록만으로 실제 퀘스트 기능이 생긴다고 설명하지 않는다.

## ID와 저장 위치

- 원본은 `src/main/resources/data/<namespace>/mythai_ai/` 아래 `god_profiles`, `lore`, `dialogue_examples`, `social_relations`, `quest_lists`다.
- 파일 경로 기반 콘텐츠 ID와 내부 `godId`는 별개다. `godId`는 실제 MythicTRPG God Definition과 일치해야 한다. 표시명으로 매칭하지 않는다.
- 신규 신 프로필은 영어 소문자 `소속_이름.json`을 기준으로 하되 기존 ID를 파일명 정리만을 이유로 바꾸지 않는다. 테스트 예외 `mythictrpg:demeter`, `mythictrpg:fortuna`를 옮길 때는 게임 정의·참조·저장 데이터까지 포함한 명시적 이관이 필요하다.
- 하위 폴더를 넣으면 경로 기반 ID도 바뀐다. 모든 참조를 확인한다. `questListId`처럼 파일 ID와 일치해야 하는 선언 필드도 parser에서 확인한다.

## 내용과 공개 범위

- 신규 로어는 schemaVersion 2의 `knowledgeLevels`를 사용한다. 프로필 `loreKnowledge`의 level N은 1..N의 누적 지식이다. 프로필이 지식 보유 수준의 원본이며 로어에 별도 `known_by` 원본을 다시 만들지 않는다.
- `revealKnowledgeHolders`는 지식 보유자 정보 자체의 공개다. 단계를 높였다고 청중에게 모든 비밀을 공개할 권한까지 주지 않는다.
- 예시의 `known_by`는 로어와 다른 필드다. 필드 의미를 섞지 않는다. 파일이 존재하거나 `signatureExampleIds`에 연결되어 있다는 것과 실제 생성 프롬프트에 주입된다는 것도 구분한다.
- 관계 9단계 태그와 NPC 간 보조 관계 태그를 혼합해 저장하지 않는다. 정확한 enum/지원 태그는 현행 코드로 확인한다. `social_relations`의 A→B와 B→A를 각각 작성하고 동적 관계 상태를 정적 파일에 복제하지 않는다.
- 퀘스트 후보, 진행 트랙 참조는 콘텐츠지만 실제 수주·완료·진행도·보상은 게임 소유다. 서버 전역 트랙을 개인 진행도로 바꾸지 않는다.

## 적용과 검증

JSON 구문뿐 아니라 중복 God ID, 존재하지 않는 참조, 로어 최대 단계, 지원 enum, 퀘스트 바인딩/보상 조건을 검사한다. 데이터 의미를 검증하지 못했으면 구문 확인만 했다고 보고한다.

`example_dialogue_dictionary/`, JAR 내 리소스, `server/world/datapacks`, 기존 `server/config`는 서로 다른 자료/적용 경로다. 같은 ID의 상위 팩이 원본을 덮는지 loader와 활성 팩을 확인한다. 요청이 파일 작성까지만이면 서버 사본을 덮어쓰지 않는다. 적용까지 요청받으면 원본과 실제 유효 리소스를 맞추고, 필요한 rebuild/reload/restart를 구분한다. JSON 수정이 언제나 JAR 재빌드를 요구하는 것도, 언제나 즉시 반영되는 것도 아니다.
