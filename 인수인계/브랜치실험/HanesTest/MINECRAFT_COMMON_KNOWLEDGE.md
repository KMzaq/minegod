# Minecraft 공용 기본 지식 — HanesTest

2026-09-27. 실험 브랜치 개발본: AI 응답 **0.1.19**, 콘텐츠 레지스트리 **0.1.2**. 게임 모드 실행 코드는 변경하지 않는다. 서버 배포 및 실제 LLM 답변 품질 확인은 별도다.

## 목적

NPC가 `바다의 심장`을 플레이어가 탐험으로 구할 수 있는 Minecraft 아이템으로 이해하도록 한다. 이름이 거창하다는 이유만으로 유일한 신물·추상적 정신·새로운 초능력으로 바꾸지 않는다. 다만 사용자가 명시적으로 비유하고 있거나 현재 허용된 서버 설정이 그 물건을 재정의했다면 그 문맥을 존중한다.

기본 사실은 신의 감정이나 대사를 고정하지 않는다. 친근한 신은 가볍게 이야기할 수 있고 엄격한 신은 다른 태도로 반응할 수 있다. 요청하지 않은 제작법 설명이나 아이템 백과사전식 답변을 매번 출력하게 하는 기능이 아니다.

## 콘텐츠 위치와 작성

원본: [common_knowledge 폴더](../../../mythai-ai-content-registry/src/main/resources/data/mythaiaicontent/mythai_ai/common_knowledge).

한 파일에 한 개념을 작성한다. 모든 NPC에게 공개 가능한 기본 정보 전용이며, 신 프로필 `loreKnowledge`에 개별 등록할 필요가 없다. 신의 비밀·줄거리·미해금 지식은 기존 단계형 `lore`를 계속 사용한다.

```json
{
  "schemaVersion": 2,
  "title": "바다의 심장",
  "keywords": ["바다의 심장", "바다의심장", "heart of the sea", "minecraft:heart_of_the_sea"],
  "content": "바다의 심장은 묻힌 보물 상자에서 얻을 수 있는 아이템이다. 바다의 심장 1개와 앵무조개 껍데기 8개로 전달체를 제작한다."
}
```

- 경로: `data/<namespace>/mythai_ai/common_knowledge/<name>.json`. 하위 폴더도 가능하다.
- 콘텐츠 ID는 파일 경로에서 나온다. 예를 들어 이 폴더의 `heart_of_the_sea.json`은 `mythaiaicontent:heart_of_the_sea`다. 실제 아이템 ID `minecraft:heart_of_the_sea`와는 다르다. 실제 아이템 ID는 검색용 `keywords`와 필요하면 본문에 적는다.
- 허용 필드는 위 네 개뿐이다. `secrecy`, `known_by`, `knowledgeLevels` 등을 넣으면 로드를 거부한다. 내용도 반드시 공개 가능한 것인지 제작자가 확인해야 한다. 시스템이 본문의 비밀 여부를 자동 판독하는 것은 아니다.
- 상한: 전체 128항목, 제목 100자, 본문 1,200자, 키워드 1~24개·각 80자. 짧은 본문을 권장한다.
- 플레이어 소유 여부, 주변 보물 좌표, 이미 지급한 보상, 퀘스트 완료 여부, 고유 레이드·장비 밸런스는 쓰지 않는다.
- 같은 리소스 ID의 데이터팩으로 덮어쓸 수 있다. 데이터팩 수정은 `/reload`, JAR에 묶인 원본 수정은 콘텐츠 JAR 재빌드·교체·재시작이 필요하다. 잘못된 입력의 reload는 이전 정상 스냅샷을 유지한다.

초기 17항목은 바다의 심장·전달체·앵무조개 껍데기·불사의 토템·엔더 진주·엔더의 눈·네더의 별, 석탄·철·다이아몬드·네더라이트, 화로·횃불·침대·농사·밀·빵이다. Minecraft 전체 사전이나 서버에 추가된 모든 모드의 지식을 자동 수집하는 것은 아니다.

내용은 프로젝트의 Minecraft **1.21.1** 제작법·전리품 데이터와 관련 게임 코드에 대조했다. 공개 설명도 [바다의 심장](https://www.minecraft.net/en-us/article/heart-sea), [위더](https://www.minecraft.net/en-us/article/meet-wither), [경작지](https://www.minecraft.net/en-us/article/block-week-farmland)의 공식 소개를 참고했다. 최신 버전의 새 규칙을 1.21.1에 자동 적용하지 않는다.

## 조회와 프롬프트 연결

콘텐츠 레지스트리의 추가 조회 계약:

```java
AiContentRegistry.INSTANCE.publicCommonKnowledge()
// List<CommonKnowledgeEntry(id, title, keywords, content)>, immutable snapshot
```

AI 응답은 별도 [조회 공급자](../../../mythai-ai-response/src/main/java/com/sande/mythictrpg/ai/MinecraftCommonKnowledge.java)를 통해 이 공개 목록만 읽는다. 기존 신별 로어 보유 수준·청중 공개 필터를 완화하거나 우회하지 않는다. Minecraft/RPG 요청·Proposal DTO는 그대로다. 새 응답 JAR은 콘텐츠 레지스트리 0.1.2 이상을 요구한다.

1. 현재 발화를 가장 우선하고, 해당 요청의 최근 허용 대화 최대 4개와 해당 화자의 게임 컨텍스트를 보조로 검색한다. 다른 방의 대화를 조회하지 않는다.
2. 한국어 공백 변형, 영문 이름, 명시적 ID를 인식한다. 한 글자 명사 `밀`·`빵`·`철`은 독립된 단어나 조사와 함께 쓰인 경우만 찾으며 `비밀`·`빵긋`·`철학` 등을 같은 아이템으로 찾지 않는다. ID의 네임스페이스를 무시해 다른 모드의 물건을 바닐라 물건으로 치환하지 않는다.
3. 관련 항목 최대 4개, 참조 JSON 최대 1,800자만 넣는다. 매번 전체 사전을 넣지 않는다.
4. 1단계 분류, 2단계 Primary 생성, Secondary 후속 반응에 연결한다. Secondary는 이미 전달된 대화를 참조하며 검증되지 않은 원시 현재 입력을 사용하지 않는다.
5. 생성 프롬프트의 `publicMinecraftReference`에 게임 상태와 분리하여 넣는다. 12,000자 데이터 문맥 예산이 부족하면 이 선택적 참고 정보를 먼저 줄이고 기존 필수 권한·페르소나·마지막 대화를 보존한다.

이는 공개된 일반 규칙 참고다. 현재 서버의 명시적 재정의가 우선하며, 아이템이 존재한다는 지식만으로 지급 권한·NPC 보유·플레이어 소유·제작 또는 퀘스트 성공을 주장할 수 없다. 실제 게임 처리는 기존 MythicTRPG 검증·실행 경로를 따른다.

## 검증과 적용 상태

검증 결과와 다음 단계는 [브랜치 인수인계](PROJECT_HANDOFF.md)의 공용 기본 지식 기록을 따른다. 공용 인수인계는 이 실험 때문에 갱신하지 않는다.
