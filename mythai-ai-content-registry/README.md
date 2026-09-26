# MythAI Content Registry

> 2026-09-12 서버 콘텐츠와 프로필 소스 모두 [LP 보존본](../server/backups/LP/README.md)으로 복원했다. 데메테르·포르투나 프로필을 LP 리소스로 되돌렸으며, 새 빌드의 전체 69개 파일 및 JAR SHA-256이 LP와 일치한다. [소스 복원 기록](../docs/LP_SOURCE_RESTORE.md)과 [LP 운영 안내](../docs/LP_RUNTIME_GUIDE.md)를 따른다.

독립 NeoForge 모드로, 신의 변하지 않는 기본 정보·단계형 세계 지식·예시 대화·정적 퀘스트 목록을 Datapack JSON에서 읽어 ResourceLocation ID로 제공한다.

2026-09-23 소스 계약 추가: 대화방은 신별 보유 지식과 현재 전체 청중의 공개 허가를 분리한 `audienceContentFor`를 사용한다. 기존 비PUBLIC 로어는 명시 허가 없으면 차단하며, 프로필/예시는 선택 공개 필드로 제한한다. 위 LP 기록은 과거 복원 이력이며 현재 개발 소스·배포 상태와 동일하다는 뜻이 아니다. [공개 양식](AI_CONTENT_REGISTRY_CONTENT_AUTHORING_GUIDE.md#대화방-공개-규칙--선택-필드-기존-자료-호환)과 [IO 계약](../데이터파일들/AI_CONTENT_REGISTRY_IO_CONTRACT.md)을 참고한다.

정적 퀘스트 후보도 선택 `disclosure`와 `audienceQuestCandidatesFor`로 전체 청중을 필터한다. 미지정 퀘스트는 기존 공개 제안 콘텐츠로 호환하며, 공급된 항목의 본문·정책 해시를 대화 증거에 남겨 미래 회상의 공개권을 재검증한다. 전체 청중이 없는 legacy 완료/리마인더 조회는 무제한 PUBLIC 후보만 반환한다. 실제 수락·진행·보상 엔진은 변경하지 않는다.

이 모드는 **MythicTRPG의 소스나 JAR에 의존하거나 수정하지 않는다.** 또한 LLM 호출, 플레이어별 기억, 관계도, 퀘스트 진행도, 실제 보상, 현재 사건을 소유하지 않는다.

## 세 모듈의 책임

```text
MythicTRPG
  - 게임 상태와 관계·유저·사건의 authoritative source
  - AI 응답 모드에 ConversationRequest 전달
  - 응답 Proposal 검증 및 실제 게임 기능 실행

MythAI Content Registry (이 프로젝트)
  - 정적 신 프로필, 단계형 로어, 예시 대화, 퀘스트 목록의 Datapack 로드
  - 기존 God ResourceLocation ID 기준 조회 API 제공

AI Response Module / External Bridge
  - ConversationRequest를 보관·조합
  - 정적 콘텐츠 조회, 기억·로그 검색, 1·2단계 LLM 호출
  - DialogueResponse와 Proposal을 MythicTRPG에 반환
```

## Datapack 경로와 ID

| 데이터 | 경로 | 예시 ID |
|---|---|---|
| 신 프로필 | `data/<namespace>/mythai_ai/god_profiles/*.json` | `mythaiaicontent:lubras` |
| 세계 지식 | `data/<namespace>/mythai_ai/lore/*.json` | `mythaiaicontent:dragon_ruin` |
| 예시 대화 | `data/<namespace>/mythai_ai/dialogue_examples/*.json` | `mythaiaicontent:lubras_greeting` |
| 퀘스트 목록 | `data/<namespace>/mythai_ai/quest_lists/*.json` | `mythictrpg:quest_list_demeter` |

신 프로필의 `godId`는 콘텐츠 ID가 아니라 MythicTRPG가 소유하는 기존 God ID다. 예: `mythictrpg:lubras`.

신 프로필의 `questListIds`는 여러 목록을 참조할 수 있다. 각 목록은 신 전용 또는 세력 공용 `progressTrackId`를 가지며, 서버 전체가 공유하는 0~100 진행도와 수주 가능 여부는 MythicTRPG가 저장·판정한다.

신 프로필은 `characterTags`에 신화권·종족·권능·성격 등 복수의 정적 태그를 담을 수 있다. 이 태그는 AI 문맥·검색 분류용 정보이며 게임 기능이나 현재 상태를 직접 바꾸지 않는다.

신 프로필은 `relationshipGuidelines`에 9단계 관계 태그(`R_EXTREME_HOSTILE`부터 `R_DEEP_BOND`)별 정적 말투·태도 지침도 담을 수 있다. 관계 수치와 단계 판정은 MythicTRPG가 소유하며, AI 응답 모드가 판정된 단계로 해당 지침을 조회한다.

신별 로어 보유 단계는 프로필의 `loreKnowledge`가 원본이다. 예를 들어 `level: 3`인 신은 해당 로어의 1·2·3단계만 받으며, 레지스트리는 이 정보를 기반으로 “누가 어느 단계까지 아는가” 역색인을 자동 생성한다.

`/reload` 후 콘텐츠가 다시 읽힌다. 잘못된 JSON·중복 God ID가 있으면 이전 정상 Snapshot을 유지하고 reload가 실패한다.

콘텐츠 작성 양식은 [AI_CONTENT_REGISTRY_CONTENT_AUTHORING_GUIDE.md](AI_CONTENT_REGISTRY_CONTENT_AUTHORING_GUIDE.md), AI 응답 모드 연동 규격은 [데이터파일들/AI_CONTENT_REGISTRY_IO_CONTRACT.md](../데이터파일들/AI_CONTENT_REGISTRY_IO_CONTRACT.md)를 참고한다.
