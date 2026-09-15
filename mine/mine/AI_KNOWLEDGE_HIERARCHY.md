# AI 세계관 지식 계층화 규격

세계관 지식은 `config/mythictrpg/ai-knowledge.json`의 단일 저장소에서 관리한다. 파일을 NPC별로 복제하지 않고, 각 항목의 분류와 상위 ID를 메타데이터로 표현한다.

```json
{
  "id": "person_athena_domains",
  "category": "PERSON",
  "parent_id": "mythictrpg:greek_olympian_athena",
  "title": "아테나의 권능 영역",
  "content": "주요 관장 영역은 지혜와 전략이다.",
  "keys": ["아테나", "전략", "지혜"],
  "known_by": ["mythictrpg:greek_olympian_athena"],
  "secrecy": "PUBLIC",
  "priority": 4,
  "always_active": false
}
```

필드 의미:

- `category`: `SYSTEM`, `WORLD`, `RACES`, `REGION`, `FACILITY`, `ORGANIZATION`, `PERSON`, `EVENT`, `MYTH` 등. 코드에 고정된 enum이 아니라 대문자 식별자이므로 새 분류를 데이터만으로 추가할 수 있다.
- `parent_id`: 상위 지역·조직·인물·사건의 ID. 없으면 빈 문자열로 둔다.
- `keys`: 플레이어 발화에서 우선 검색할 이름·별칭·주제어다.
- `known_by`: 해당 정보를 알고 있는 기존 God `ResourceLocation` ID다. 지식 권한의 원본이며 계층 분류와 별개다. 세계 공통 항목은 `["*"]`로 모든 NPC에 공개할 수 있다.
- `secrecy`: 관계·감정·청중에 따른 공개 등급이다.
- `priority`: 같은 검색 결과에서의 우선순위(0~10)다.
- `always_active`: 발화에 검색어가 없어도 기본 컨텍스트 후보가 되는지 나타낸다. 프롬프트 예산에 따라 상위 항목만 전달된다.

현재 데이터는 다음처럼 정리되어 있다.

- `WORLD`: 세계 공통 규칙과 신화권 공통 정보. 현재 전역 항목은 `known_by: ["*"]`를 사용한다.
- `PERSON`: 특정 신격의 정체성·권능·태도 정보
- `parent_id`: 대부분 해당 NPC의 God ID
- 기존 `known_by`와 `secrecy`는 그대로 유지

## 검색 순서

```text
플레이어 발화
  → 한국어 조사·어미 정규화 (`아테나가` → `아테나`)
  → keys / title / content / id 일치도 계산
  → 모호한 요청의 1단계 LLM 검색어 및 지시어 대화 문맥을 보조 검색어로 반영
  → priority로 동점에 가까운 후보를 정렬
  → NPC known_by 공개 권한 검사
  → 관계·감정·청중 secrecy 검사
  → 허용된 상위 항목만 LLM 컨텍스트에 전달
```

계층 전체를 LLM에 넣지 않는다. 검색 결과는 현재 설정의 `maxRetrievedKnowledge` 범위로 제한되며, 허용되지 않은 내용은 진단 결과에만 상태로 남고 프롬프트에는 들어가지 않는다. NPC가 모르는 높은 점수의 항목은 허용된 지식의 프롬프트 슬롯을 차지하지 않는다.

기존 5필드 형식(`id`, `title`, `content`, `known_by`, `secrecy`)도 계속 읽을 수 있다. 새 항목부터 계층 필드를 추가하면 된다.
