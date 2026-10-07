# JSON 작성 설명

이 안내는 묶음의 빈 양식을 위한 설명이다. JSON에는 주석이나 끝 쉼표를 넣지 않는다. 인코딩은 UTF-8, 문자열 안의 줄바꿈은 `\n`, 큰따옴표는 `\"`로 표현한다. 배열 항목을 늘릴 때는 쉼표로 구분한다.

## 1. 파일명과 ID 연결

`data/<namespace>/mythai_ai/<종류>/<경로>.json`의 콘텐츠 ID는 `<namespace>:<경로>`다. 종류 폴더 이름과 `.json`은 ID에 넣지 않는다.

| 예시 경로 | ID |
|---|---|
| `data/mythaiaicontent/mythai_ai/god_profiles/affiliation_name.json` | 프로필 콘텐츠 ID `mythaiaicontent:affiliation_name` |
| `data/mythaiaicontent/mythai_ai/lore/world/topic_name.json` | 로어 ID `mythaiaicontent:world/topic_name` |
| `data/mythaiaicontent/mythai_ai/dialogue_examples/shared/style_001.json` | 예시 ID `mythaiaicontent:shared/style_001` |
| `data/mythictrpg/mythai_ai/quest_lists/affiliation_name.json` | 목록 ID `mythictrpg:affiliation_name` |

`godId`는 위 프로필 콘텐츠 ID가 아니라 **게임의 신 ID**다. 동일한 path라도 namespace가 다르면 다른 ID다. 파일명을 바꾼다고 기존 게임 God ID가 바뀌지는 않는다.

신규 이름은 `소속_이름`의 소문자 영어를 기본으로 정하되 다른 신화의 동명이인과 겹치지 않게 소속을 구체화한다. 실제 등록 ID를 이미 전달받았다면 그것을 우선한다. 표시 이름과 본문은 한글로 써도 된다.

하위 폴더도 ID의 일부다. `lore/world/topic_name.json`을 `lore/topic_name.json`으로 옮기면 참조도 `mythaiaicontent:topic_name`으로 바뀐다. 폴더명을 정리하려고 기존 ID를 무심코 바꾸지 않는다.

## 2. 프로필 작성

| 필드 | 넣을 내용 |
|---|---|
| `identity` | 공개할 정체성·관장 영역·위치 |
| `description` | 배경, 인간을 보는 관점, 목표, 좋아하고 싫어하는 것, 성격의 모순 |
| `personality` | 구체적인 성격 특징과 발현 방식 |
| `values` | 중요한 가치와 양보할 수 없는 기준 |
| `speechStyles` | `P_*` 말투 태그 배열. 태그만으로 모든 연기를 정하려 하지 말 것 |
| `dialogueGuidelines` | 존댓말/반말·호칭·리듬·유머·기본 대화 방식 |
| `situationGuidelines` | 실제 상황에 맞춰 사용할 반응 지침 |
| `relationshipGuidelines` | 9단계 관계에서 각각 달라지는 태도 |
| `restrictions` | 하지 않아야 할 서술, 게임 실행/지식의 한계 |
| `characterTags` | 신화권·소속·권능·성격 등 정적 분류 문자열 |
| `loreKnowledge` | 그 신이 알고 있는 로어 ID와 최고 단계 |
| `questListIds` | 사용할 수 있는 신 전용·공용 퀘스트 목록 ID들 |
| `signatureExampleIds` | 선택적 고유 예시 ID들. 현재 생성 원문 주입 여부는 첫 안내 참고 |

`repetitionGuidelines`는 고급 선택 항목이다. 양식은 `{}`로 두었다. 의미가 비슷한 말을 반복할 때의 태도는 우선 일반 대화 지침에 적는다. `심심해`처럼 특정 문장의 고정 답변을 늘리지 않는다.

핵심 지침은 서로 충돌하지 않게 짧고 명확히 쓴다. 예를 들어 ‘기본적으로 말수가 적지만 정확한 설명이나 중요한 경고에는 필요한 만큼 말한다’처럼 조건을 적는다. ‘무조건 한 문장’·‘항상 질문’·‘모든 부탁 거절’은 자연스러운 문맥 반응을 막기 쉽다.

신이 상위 존재라는 기본 인식과 실제 전투력, 관계, 사회적 후원은 별개다. 플레이어의 힘/보호자가 달라질 때 캐릭터가 어떻게 판단할지는 쓸 수 있지만 현재 플레이어 수치나 ‘무조건 내가 최강’ 같은 게임 사실을 임의로 저장하지 않는다.

### 관계 태그

| 단계 | 태그 | 의미 |
|---:|---|---|
| -4 | `R_EXTREME_HOSTILE` | 극도의 적대 |
| -3 | `R_HOSTILE` | 적대 |
| -2 | `R_DISLIKE` | 불쾌·반감 |
| -1 | `R_WARY` | 비호감·경계 |
| 0 | `R_NEUTRAL` | 중립 |
| +1 | `R_FAVORABLE` | 관심·호의 |
| +2 | `R_FRIENDLY` | 호감 |
| +3 | `R_TRUSTED` | 강한 호감·신뢰 |
| +4 | `R_DEEP_BOND` | 깊은 유대·애정 |

이 숫자는 단계 설명이지 프로필에 저장할 호감도 수치가 아니다. 친밀한 상태에서도 현재 화가 날 수 있고, 적대하는 상대라도 강한 후원 때문에 행동을 자제할 수 있다. 관계 단계만으로 반응을 고정하지 않는다.

### 현재 상황 태그

`S_CHAT`, `S_ITEM_REQUEST`, `S_POWER_REQUEST`, `S_HELP_REQUEST`, `S_INFORMATION_REQUEST`, `S_QUEST_INQUIRY`, `S_REWARD_NEGOTIATION`, `S_GIFT_OFFER`, `S_APOLOGY`, `S_CONFLICT`.

의미는 순서대로 잡담, 아이템 부탁, 가호 부탁, 도움 요청, 정보 질문, 퀘스트 문의, 보상 협상, 선물/공물 제안, 사과, 갈등이다. `S_QUEST_INQUIRY`에는 ‘필요한 거 있어?’도 포함한다. 신규 자료에 예전 `S_SMALLTALK`, `S_QUEST_OFFER`, `S_VOUCH`를 쓰지 않는다.

### 선택적 말투 태그 예

`P_GRUFF` 무뚝뚝함, `P_GENTLE` 부드러움, `P_ARROGANT` 오만함, `P_PLAYFUL` 장난스러움, `P_FORMAL` 격식, `P_MYSTERIOUS` 신비로움, `P_SHORT` 짧은 말, `P_TALKATIVE` 수다스러움, `P_DRY_HUMOR` 무심한 유머, `P_INDIRECT_CARE` 간접적 배려, `P_CALM` 침착함, `P_WISE` 지혜로움.

핵심 2~4개 정도부터 선택하고 자연어 지침으로 구체화하면 된다. 목록의 수는 작성 권장이지 파서의 강제 조건은 아니다. 태그는 전투 능력이나 게임 실행 권한을 부여하지 않는다.

## 3. 세계 지식과 비밀

프로필의 `loreKnowledge`에 `{ "loreId": "mythaiaicontent:world/topic_name", "level": 3 }`이면 그 신은 1~3단계를 누적해서 안다. 단계는 1부터 연속되어야 한다. 로어에 별도 `id` 또는 `known_by`를 쓰지 않는다. 보유자 목록은 프로필들을 통해 역으로 계산된다.

단계에 추가할 새 정보를 적는다. 공개 사실·전승·당사자의 주장·불확실한 소문은 본문에서 구분한다. 아직 플레이 중 일어나지 않은 예정 사건은 기획서로 보내고 확정 사실로 로어에 넣지 않는다.

### 알고 있음과 공개 가능함은 다르다

| 값 | 현재 동작 |
|---|---|
| `secrecy` | `PUBLIC`, `RESTRICTED`, `DIVINE`, `SECRET` 중 하나. 명시 공개 규칙이 없으면 PUBLIC만 공급 |
| `disclosure.mode: PUBLIC` | 공개/비밀방 모두 현재 청중에게 제공 가능 |
| `PRIVATE_ROOM` | 비밀방의 현재 플레이어 **전원**에게 제공 가능 |
| `NEVER` | 대화방 프롬프트에 공급하지 않음 |
| `allowedGodIds` | 비어 있지 않으면 화자 이외의 참가 신 전원이 이 목록에 있어야 함. 빈 배열은 신별 제한 없음 |

양식의 1단계 공개/2단계 비밀방/3단계 금지는 **서로 다른 정책을 보여 주는 예시**다. 원하는 공개 범위로 수정해야 한다. 명시 `disclosure`는 `secrecy`의 기본 공개 판단을 대체하지만 지식 보유 수준은 늘리지 않는다. 중간 단계가 차단되면 그 위 단계도 차단된다.

`PRIVATE_ROOM`은 ‘친한 사람 한 명에게만’이라는 뜻이 아니다. 호감도·퀘스트 진실 발견 조건에 따라 공개하고 싶으면 그 조건을 기획서에 따로 적는다. 임의의 `minAffinity` 같은 필드를 추가하지 않는다. 그런 조건은 게임의 Story 공개 권한과 연결해 구현/설정해야 한다.

`revealKnowledgeHolders`는 지식 보유자 정보를 원본 조회에 포함하는 옵션이다. 현재 대화방용 조회에서는 보유자 정보가 제외되므로 이것을 켰다고 NPC가 다른 지식 보유자를 알려 주는 것으로 기대하지 않는다. 기본값 false를 유지해도 된다.

`title`과 `keywords`에도 반전/비밀을 무심코 쓰지 않는다. 최소 허용 단계와 함께 보여도 되는 표현을 쓴다. 방대한 세계관을 한 파일에 몰지 말고 사건·인물·조직·지역 등 검색할 의미 단위로 나눈다.

프로필 필드 자체가 비밀이면 `fieldDisclosure`를 사용할 수 있다. 예: `"description": {"mode":"NEVER","allowedGodIds":[]}`. 다만 해당 필드 전체가 가려진다. 공개 성격과 비밀 사건을 한 필드에 섞기보다는 비밀 사실을 로어로 분리한다. 이 필터는 게임의 이름표/정체 식별 정책을 바꾸지 않는다.

## 4. 신과 신의 관계

`participantA`, `participantB`에 서로 다른 게임 God ID를 넣고, 두 인물 모두 실제 프로필이 있어야 한다. 양식의 `affiliation_other_name` 프로필은 제공하지 않았으므로 상대 프로필을 별도로 작성하거나 기존 신 ID로 바꾼다.

방향은 **말하는 쪽의 지위**다. A가 B의 아버지라면 `aToBTags: ["RT_FATHER"]`, `bToATags: ["RT_CHILD"]`다. 형제/자매도 A가 남성 형제이면 A→B에 BROTHER, B가 여성 형제이면 B→A에 SISTER를 적는다.

허용 태그: `RT_FATHER`, `RT_MOTHER`, `RT_CHILD`, `RT_BROTHER`, `RT_SISTER`, `RT_TWIN`, `RT_BLOOD_RELATION`, `RT_SPOUSE`, `RT_LOVER`, `RT_COMRADE`, `RT_MASTER`, `RT_SERVANT`, `RT_NEMESIS`, `RT_RIVAL`, `RT_ENEMY`, `RT_CREATOR`, `RT_CREATION`.

여러 태그를 붙일 수 있으며 한 방향은 빈 배열도 가능하지만 양쪽이 모두 비면 안 된다. 양식의 RT_RIVAL은 형식 예시일 뿐 확정 관계가 아니다. 동일한 쌍을 A/B를 뒤집어 두 파일로 중복 작성하지 않는다. 관계가 된 이유는 필요한 로어/기획서에 쓰고 이 JSON에 임의 필드를 추가하지 않는다.

`R_*`는 호감도 단계, `RT_*`는 관계 유형이다. 혈연이라도 적대할 수 있다. 정적 가족/신화 관계와 플레이 중 변하는 실제 호감도는 따로 관리한다.

## 5. 퀘스트 목록

기존 `QUEST_LIST_TEMPLATE.json`의 구조를 재사용했다. `questListId`는 파일 ID와 같아야 하고, 각 `questId`는 모든 목록에서 유일해야 한다. `progressTrackId`가 같은 목록끼리 서버 전체 진행도를 공유한다. 현재 진행값은 적지 않는다(초기 0, 최대 100).

`quests`에 여러 퀘스트를 추가한다. 각 퀘스트는 `title`, `content`, 하나 이상의 `objectives`/`rewards`, `acceptanceConditions`, `progressOnClear`로 작성한다. `parameters`는 문자열/숫자 등 단순 값이며 중첩 객체/배열을 임의로 넣지 않는다.

샘플은 `collect_item` 목표·`item` 보상·`world_quest_progress_range` 조건을 사용한다. 밀 1개, 에메랄드 1개, 완료 진행량 0은 문법 예시다. 실제 아이템/수량/진행량으로 바꾼다. 완료 진행량은 0~100이며 0은 트랙을 올리지 않는다. 구간의 최솟값/최댓값은 모두 포함한다.

`questListIds`를 여러 개 넣으면 개인 신 목록과 세력 공용 목록을 함께 참조할 수 있다. 여기서 ‘개인 신 목록’은 그 신 전용이라는 뜻이며 진행도는 플레이어 개인 값이 아니다.

목표 달성 즉시 자동 완료/보상으로 가정하지 않는다. 기본 완료는 신의 유효한 원격 주시·응답 또는 실제 대면/재조우 등 게임 접촉 규칙을 따른다. 완료 방식·참가 유형·보상 지급·대체 루트는 `02_기획서`에 적는다. 현재 목록 스키마에 없는 `completionMode`, `participation`, `ending` 등을 여기에 넣지 않는다.

실행하려면 같은 questId에 대한 MythicTRPG 바인딩과 FTB 정의/표시 연결, 실제 게임 목표·보상 설정을 별도로 맞춰야 한다. 후보의 보상 설명과 실제 지급 보상도 반드시 일치시킨다. 기획서에서 새로운 목표를 제안하는 것은 가능하지만 새 type 문자열을 적는 것만으로 기능이 생기지는 않는다.

퀘스트의 `disclosure`는 AI 후보 본문의 공개 범위다. FTB UI의 비밀 퀘스트 노출 권한까지 자동으로 처리하지 않는다.

## 6. 예시와 공개 기초 정보

예시 JSON 1파일은 1개의 대화 묶음이고 `dialogue`에 여러 발화를 담는다. 일반 예시는 최소 2발화, 각 발화는 최대 750자, role은 `player`/`npc`다. `known_by: []`는 공용, 특정 God ID 배열은 그 신의 예시로 제한한다. 로어에는 이 필드를 쓰지 않는다는 점과 구분한다.

모든 P/R/S 조합을 만들 필요는 없다. 한 가지 말투를 보여 주는 짧은 예시와 꼭 필요한 고유 반응만 작성한다. 고정 정답/기억된 실제 사건이 아니라 연기 참고다. **현재 주 대화 생성은 예시 원문 없이 페르소나 중심**이므로, 예시는 선택적 작성 자료다. 1단계 분류용 `intent_classifier/` 자료와도 별개다.

`common_knowledge`는 누구에게나 공개 가능한 Minecraft 기본 정보 전용이다. 네 필드(`schemaVersion`, `title`, `keywords`, `content`)만 사용한다. 제목 1~100자, 본문 1~1200자, 키워드 1~24개(각 1~80자), 전체 항목 최대 128개다. 실제 서버에서 변경된 제작법·획득처를 바닐라 사실로 단정하지 않는다. 아이템/블록 ID를 검색 별칭에 넣을 수 있지만 그것은 콘텐츠 파일 ID와 별개다.

## 검토 기준의 원본 위치

프로젝트 안에서는 다음 문서와 현재 파서를 기준으로 한다. 이 경로 표기는 ZIP 외부 문서를 함께 넣었다는 뜻이 아니다.

- `mythai-ai-content-registry/NPC_PROFILE_AUTHORING_GUIDE.md`
- `mythai-ai-content-registry/AI_CONTENT_REGISTRY_CONTENT_AUTHORING_GUIDE.md`
- `mythai-ai-content-registry/QUEST_LIST_AUTHORING_GUIDE.md`
- `mythai-ai-content-registry/src/main/java/com/sande/mythaiaicontent/content/`
- `mythictrpg-main/docs/FTB_QUESTS_INTEGRATION_GUIDE.md`
- `mythai-ai-response/src/main/java/com/sande/mythictrpg/ai/RoomPersonaPrompt.java`
- `mythai-ai-response/src/main/java/com/sande/mythictrpg/ai/RoomReactionPrompt.java`

옛 가이드의 예시 ID·상황 태그는 당시 자료일 수 있다. 이 묶음은 위 현재 파서·실제 생성 경로와 대조해 작성했으며, 작성 후 서버 적용 시에는 다시 검증한다.
