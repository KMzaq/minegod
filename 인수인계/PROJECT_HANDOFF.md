# Minecraft RPG Local AI NPC Engine — 프로젝트 인수인계

> 이 문서는 새 에이전트/개발자가 작업을 시작할 때 가장 먼저 읽는 문서다. 문서와 실제 코드가 다르면 **실제 코드와 빌드 결과를 우선**한다.

> 2026-09-15 최신 개발 상태: 승인된 로드맵 **0~1단계 소스·오프라인 작업 완료, 미배포**. 상세 인수인계는 **32절**, 구현·검사 근거는 [작업 기록](../docs/RECALL_STAGE01_20260915.md)을 따른다. 개발 AI **0.1.4**, 실제 서버는 **AI 0.1.3/게임 1.0.2/콘텐츠 0.1.0** 유지다. LP 보존·직전 상태 2,177개 백업·오프라인 870개 검사를 완료했다. 신규 회상은 기본 OFF이며 서버/GameTest/LLM/모델 설치/배포는 하지 않았다. 실제 자연스러움·전체 응답 시간은 미검증, 시간·주시 정책은 미확정, **2단계 미착수**다.

> 2026-09-15 설계 이력: [상세 행동 기록·신의 주시·기억·소문 로드맵](../docs/ACTION_OBSERVATION_MEMORY_ROADMAP_20260915.md). 상세 일상 기록 및 조건/관계 기반 주시 요구를 반영했다. 공통 계약 → 개인 회상 개선 → 작은 행동 원장 → 관찰 증명 → 대화까지의 종단 연결 → 범위/혼합 검색 확장 → 전서구 → 평판 효과 순서다. 최초 작성은 설계만의 작업이었으나 **이후 0~1단계만 승인받아 소스·오프라인 작업을 완료**했다. 전체 순서는 이 로드맵을, 현재 상태는 32절을 따른다. 2단계 이후 구현·배포는 미승인이다.

> 2026-09-15 개인 회상 설계 이력: [사건 중심 장기기억·회상 개선안](../docs/EPISODIC_MEMORY_UPGRADE_DESIGN_20260915.md). 원문+정리 기억, 시간/후속 회상, 의미 검색, 관계 입력 점검, 게임 경험→소문의 상세안이다. 이 문서의 0/1A에 해당하는 작업은 최신 로드맵 0~1단계로 진행했지만 자동 정정 sidecar·임베딩 등 후속 제안까지 구현한 것은 아니다. 두 문서의 단계 번호는 다르므로 로드맵의 대응표를 따른다. 한국 현실 날짜는 제안/선택 가능한 구현일 뿐, 사용자 답변 없이 운영 기준으로 확정하지 않았고 기본값은 UNSPECIFIED다.

> 2026-09-14 서버 배포 기준: 개인 기억 회상 개선본 **AI 0.1.3**을 서버에 배치했다. 게임/클라이언트 **1.0.2**, 콘텐츠 0.1.0/PERSONAL은 유지한다. 당시 LP 원본과 수정 직전 100개 파일을 보존했고 빌드/오프라인 710개 검사를 통과했다. 서버는 실행하지 않았으며 실제 모델 비교는 Ollama 미실행으로 연결 실패했다. **현재 배포본**은 31절과 [회상 개선 기록](../docs/MEMORY_RECALL_TUNING_20260914.md), [서버 시험 안내](../docs/LP_PERSONAL_MEMORY_TEST_SETUP.md)를, **미배포 개발본**은 32절을 따른다. 28~30절은 이전 이력이다.

> [장기기억·소문·평판 통합 설계](../docs/LP_LONG_TERM_MEMORY_DESIGN.md)는 전체 목표다. 신별 직접 경험·전언·평가 분리와 해당 플레이어만의 소문 차단은 기반으로 구현·시험했지만 자동 기억 추출·실제 전서구·전파망·기계적 평판 효과는 미구현이다. 남은 콘텐츠 선택은 설계 10절을 따른다. OPELA 전체 실험을 재도입한 것이 아니며 24·25절은 과거 이력이다.

> 2026-09-09 작업 진입 지침: [루트 AGENTS.md](../AGENTS.md)에 프로젝트 목표, 작업 위치, 모듈 권한 경계와 검증·배포 기준을 정리했다. 작업별 스킬은 `.agents/skills/`의 모드 연동·대화 분석·콘텐츠 작성·서버 배포 4종이다. 새 작업은 AGENTS.md를 읽고 이 문서의 관련 절과 최신 기록을 확인한다. 과거의 테스트 수·해시·배포 기록은 해당 시점의 결과이며 현재 상태의 증거를 대신하지 않는다. 이번 갱신은 작업 지침/스킬만 추가했으며 게임 코드·JAR·서버 설정은 변경하지 않았다.

## 1. 목표

신(NPC)들과의 자연스러운 AI 대화·상호작용을 중심으로 레이드, 퀘스트, 탐험, 장비 성장, 경제와 스토리가 연결되는 Minecraft 소규모 RPG 서버를 만든다. 로컬 LLM 대화 엔진 자체만 완성하는 것이 최종 목표가 아니다. NPC는 프로필, 말투, 관계, 감정, 기억, 지식, 현재 게임 상태를 바탕으로 대화하고 퀘스트·보상·관계 변화 등의 제안을 생성한다. 이 목표 목록은 모든 콘텐츠가 구현 완료되었다는 뜻이 아니다.

LLM은 대사와 Proposal을 제안할 뿐이며, 실제 게임 상태 변경의 최종 권한은 Minecraft/RPG 시스템에 있다.

## 2. 디렉터리와 모드 역할

작업 루트: `C:\Users\ADMIN\Desktop\markmar`

| 경로 | 역할 |
|---|---|
| `mythictrpg-main` | 현재 MythicTRPG NeoForge 모드. 게임 상태, 신 정의, 상호작용, 퀘스트 권한/검증, 클라이언트 HUD를 소유한다. |
| `mythai-ai-content-registry` | 신 프로필, 세계관 지식, 예시 대화, 퀘스트 목록 등 정적 AI 콘텐츠를 Datapack 리소스로 제공한다. |
| `mythai-ai-response` | AI 대화 오케스트레이션, 프롬프트, Ollama 호출, Proposal 정규화/전달을 담당하는 서버 모드다. |
| `ftb-quests` | FTB Quests 1.21.1/main 소스 작업본. 기부형 아이템 Task 변경은 이 저장소에 있으며, 기본 배포 JAR과 별개다. |
| `server` | NeoForge 테스트 서버. 서버용 JAR은 `server/mods`, 클라이언트에 필요한 JAR은 `server/client-required-mods`에 있다. |
| `인수인계` | 이 문서와 후속 작업 메모를 보관한다. |

## 3. 실행 환경

- Minecraft 1.21.1
- NeoForge 21.1.248
- Java 21 (`server/start-neoforge-ai-server.bat`는 Android Studio JBR 21.0.10을 사용)
- Ollama API: `http://127.0.0.1:11434/api/chat`
- 현재 서버 설정 모델: `gemma4:12b` (설정 파일에서 변경 가능)
- 같은 PC에서 서버와 클라이언트를 실행하는 구성을 기준으로 한다.

### 서버 실행

1. Ollama를 실행하고 사용할 모델이 설치되어 있는지 확인한다.
2. `server/start-neoforge-ai-server.bat` 실행.
3. 클라이언트 `mods` 폴더에 `server/client-required-mods`의 JAR을 모두 복사한다.
4. 클라이언트에서 `localhost:25565`로 접속한다.

서버 종료는 콘솔에 `stop`을 입력한다. 서버 폴더의 `world`에는 진행 상태가 저장되지만, 정적 프로필/퀘스트 정의는 모드 JAR·Datapack·`config`에서 읽는다.

## 4. 현재 설치된 모드

서버와 클라이언트 필수 목록:

- Architectury API 13.0.11
- FTB Library 2101.1.35
- FTB Teams 2101.1.11
- FTB Quests 2101.1.34
- MythicTRPG 1.0.2 (현재 서버/클라이언트 배포용)

서버 전용:

- `mythaiaicontent-0.1.0.jar`
- `mythai_ai_response-0.1.3.jar`

FTB Quests는 퀘스트북 UI·목표·진행도 표시를 담당한다. Mythic 직접 보상을 선언한 퀘스트의 최종 확인·완료·보상은 MythicTRPG 서버가 authoritative하게 관리한다. 기존 `fortuna_deep_sea_heart` 배포 샘플에는 호환용 FTB 아이템 보상이 남아 있고 `rewards` 바인딩을 아직 선언하지 않았으므로 FTB 보상 버튼이 작동한다. 새 Mythic 직접 보상 퀘스트에서는 같은 보상을 FTB에 중복 배치하지 않는다.

## 5. AI 대화 파이프라인

기본 흐름은 다음과 같다.

```text
기존 게임 상호작용/스냅샷
        ↓
AI 응답 모드의 Conversation Session
        ↓
1단계: 상황·관계·말투·지식 키워드 분류
        ↓
콘텐츠 레지스트리에서 NPC 프로필/지식/예시/퀘스트 후보 검색
        ↓
2단계: Ollama에 컨텍스트를 보내 대사와 Proposal 생성
        ↓
Proposal 정규화 및 MythicTRPG Gateway 검증
        ↓
대사 표시 / 검증된 게임 처리
```

일반 잡담·명확한 인사 등은 빠른 규칙 경로를 사용할 수 있고, 모호한 부탁·비밀·협상·다중 참가자 상황은 분류 후 생성 경로를 사용한다. 반복적인 놀이 상태 등은 대화 세션의 conversational state로 유지하며 실제 월드 상태와 혼동하지 않는다.

AI는 아이템 지급, 퀘스트 완료, 월드 변경, 관계 수치 영구 저장을 직접 하지 않는다. AI의 `quest_offer`, `relationship_change_proposal`, `reward_proposal` 등은 게임 측 검증 대상이다.

### 5.1 AI Context 구성 원칙

Lorebook과 별도의 게임 상태 DB를 AI 모드에 만들지 않는다. 요청마다 필요한 정보를 다음 출처에서 조립한다.

```text
AI Context
├─ Minecraft 기본 지식 Provider
├─ MythicTRPG 규칙 Provider
├─ Content Registry의 관련 Lore/신 프로필/예시
├─ MythicTRPG의 현재 Conversation Snapshot
└─ 세션의 최근 대화와 conversational state
```

- 제작법·일반 블록·일반 몹 행동처럼 공통인 Minecraft 지식은 짧은 Provider 또는 System Context로 필요한 경우에만 전달한다.
- MythicTRPG에서 재해석된 Minecraft 규칙이나 고유 세계관은 Lorebook에 작성한다.
- 관계·감정·퀘스트·인벤토리·위치·월드 진행도는 Lorebook에 저장하지 않고 MythicTRPG Snapshot에서 읽는다.
- 모든 지식을 매 요청에 넣지 말고 현재 발화와 Interaction에 관련된 항목만 선택한다.
- RisuAI 등 외부 대화 시스템은 사용하지 않는다. 로컬 AI provider와 MythicTRPG/Content Registry 계약을 기준으로 한다.

## 6. 콘텐츠 데이터 위치

AI 콘텐츠는 `mythai-ai-content-registry/src/main/resources/data/` 아래에 있다.

- `mythai_ai/god_profiles/*.json`: 신 프로필, 태그, 말투 지침, 관계 단계별 지침, `questListIds`
- `mythai_ai/lore/*.json`: 세계관 지식과 단계별 공개 내용
- `mythai_ai/dialogue_examples/*.json`: 태그 기반 재사용 예시 대화
- `mythai_ai/quest_lists/*.json`: 신 또는 공용 세력의 논리 퀘스트 목록

평가형 퀘스트의 서버 바인딩과 NPC 보상 등급표는 MythicTRPG 모드의 Datapack 리소스로 관리한다.

- `mythictrpg-main/src/main/resources/data/mythictrpg/mythictrpg/ftb_quests/*.json`: FTB 퀘스트 바인딩, 재촉 정책, 평가 정책
- `mythictrpg-main/src/main/resources/data/mythictrpg/mythictrpg/reward_tables/*.json`: NPC별 보상 등급표

Lore 파일의 정식 작성 형식은 `schemaVersion: 2`의 `knowledgeLevels` 단계형 JSON이다. Lore는 세계관·인물·비밀 지식만 담고, 게임 중 변하는 상태는 담지 않는다. `loreKnowledge`는 신 프로필에서 해당 Lore를 몇 단계까지 아는지 선언한다.

신 프로필·퀘스트 목록·논리 퀘스트 ID는 정식 콘텐츠 기준으로 `namespace:소속_이름` 규칙을 사용한다. 예: `mythictrpg:olympus_demeter`, `mythictrpg:roman_fortuna`, `mythictrpg:quest_list_olympus`, `mythictrpg:fortuna_deep_sea_heart`. 현재 `mythictrpg:demeter`와 `mythictrpg:fortuna`는 기존 MythicTRPG ID와 연결된 테스트용 프로필 예외다. 정식 배포 전에는 해당 게임 측 God ID와 함께 새 규격으로 변경해야 하며, 프로필 파일 내부의 `godId`가 기준이다.

정적 콘텐츠를 수정한 뒤에는 해당 콘텐츠 레지스트리 JAR을 다시 빌드해 서버 `mods`에 교체하거나, 프로젝트가 허용하는 Datapack 방식으로 배포한다.

## 7. FTB Quests 연동

상세 규격은 [FTB Quests 연동 가이드](../mythictrpg-main/docs/FTB_QUESTS_INTEGRATION_GUIDE.md)를 참조한다.

현재 샘플은 다음과 같다.

- 논리 ID: `mythictrpg:fortuna_deep_sea_heart`
- FTB 실제 퀘스트 ID: `21F48990D282287B`
- 수주 마커 ID: `5DD74E73298791E8`
- 완료 방식: `PLAYER_RETURN_TO_NPC`
- 목표: `minecraft:heart_of_the_sea` 2개
- 보상: `minecraft:totem_of_undying` 1개
- 완료 시 전역 진행도: `mythictrpg:progress_fortuna` +10

바인딩은 `mythictrpg-main/src/main/resources/data/mythictrpg/mythictrpg/ftb_quests/`의 JSON에 있다. FTB 원본 팩은 `mythictrpg-main/ftbquests-pack/`에 있다.

지원 완료 모드:

- `AUTO`: FTB 일반 목표가 모두 완료되면 즉시 완료
- `PLAYER_RETURN_TO_NPC`: 목표 완료 후 올바른 NPC와 명시적 상호작용 필요
- `NPC_VISIT_PLAYER`: 목표 완료 후 기존 사건/상호작용 시스템이 NPC 방문을 생성했을 때 완료

NPC 상호작용으로 끝나는 퀘스트는 authoritative 완료 후 AI에 검증된 완료 상황을 전달해 HUD 완료 대사를 생성한다. AI를 사용할 수 없거나 생성에 실패하면 같은 NPC HUD 영역에 `[퀘스트가 완료되었습니다]`가 표시되며 대화 세션은 유지된다.

퀘스트를 수주한 NPC는 항상 최종 확인할 수 있다. 바인딩의 `completionNpcIds`는 수주자가 지정한 **추가 확인 NPC** 목록이므로, “포르투나에게 반드시 돌아가기”가 아니라 “퀘스트 제공자 또는 제공자가 지정한 상대에게 말 걸기”가 기준이다.

퀘스트 바인딩의 선택적 `reminder`로 AI 재촉 이벤트를 켤 수 있다. `unrelatedActivityTicks`와 `cooldownTicks`는 퀘스트마다 독립 설정하며, `relevantActions`에 선언된 행동은 타이머를 초기화한다. 그 외 관찰 행동만 오래 이어지고 목표가 아직 미완료일 때 `signalId`에 연결된 신이 자발적 AI 대사로 재촉한다. `reminder`가 없는 퀘스트는 재촉하지 않는다.

### 7.1 평가형 퀘스트 처리

평가형 퀘스트는 일반적인 NPC 방문 완료 경로와 분리된다. 결과물 분석기 또는 외부 평가기가 점수와 근거를 서버 스레드의 `QuestEvaluationGateway`에 제출하면 다음 순서로 처리한다.

```text
결과물 제출/확인
        ↓
분석기 → QuestEvaluationGateway(score, evidenceSummary)
        ↓
서버 검증: 수주 상태·담당 NPC·평가 정책·보상표·등급 범위
        ├─ 불합격 → 퀘스트 유지 → AI 평가 대사 → 재도전
        └─ 합격   → 점수→등급 변환 → 퀘스트 완료·보상 지급 → AI 완료 대사
```

서버가 권위 있는 결과를 먼저 확정하고, AI에는 확정된 점수·합격 여부·등급·지급 보상·평가 근거만 전달한다. AI는 이 값을 변경하지 않고 NPC의 자연스러운 평가 대사만 생성한다. AI 호출이나 생성에 실패하면 결과에 맞춰 NPC 대화 HUD에 `[평가 결과: 재도전이 필요합니다]` 또는 `[퀘스트가 완료되었습니다]`를 표시한다. 대화 세션은 종료하지 않는다.

현재 불합격 처리는 기존 퀘스트 요구사항을 유지한 재도전이다. AI가 임의로 FTB 목표나 보상 조건을 바꾸지는 않는다. 요구사항 자동 갱신은 향후 구조/결과물 분석기와 별도의 검증 가능한 요구사항 갱신 계약을 연결해야 한다.

바인딩의 `evaluation` 형식은 다음과 같다.

```json
"evaluation": {
  "rewardTableId": "mythictrpg:fortuna",
  "minimumRewardTier": 2,
  "maximumRewardTier": 8,
  "passingScore": 60
}
```

- `passingScore` 이상만 합격이며 점수 범위는 0–100이다.
- 합격 점수는 지정 범위의 최소 등급에서 시작하고, 100점은 최대 등급이 된다.
- 평가형 바인딩은 `AUTO` 완료 모드를 사용할 수 없고, 평가 담당 NPC ID를 반드시 선언해야 한다.
- 평가형 퀘스트의 FTB 원본에는 동일 보상을 중복 수령할 수 있는 고정 수령형 보상을 배치하지 않는다.

### 7.2 신 기본 보상 등급표와 통합 보상

보상표는 `data/<namespace>/mythictrpg/reward_tables/*.json`에 작성한다. 신규 schemaVersion 2는 `godId`와 `defaultForGod`를 사용하며, 한 신에게 기본 표는 하나만 허용한다. 등급은 1부터 최대 등급까지 빠짐없이 연속되어야 한다. 요청에 따라 **캐릭터별 보상표 계층은 구현하지 않았다.**

```json
{
  "schemaVersion": 2,
  "godId": "mythictrpg:fortuna",
  "defaultForGod": true,
  "tiers": [
    {"tier": 1, "rewards": [
      {"type": "item", "itemId": "minecraft:gold_ingot", "count": 1},
      {"type": "affinity", "amount": 10}
    ]}
  ]
}
```

실행 타입은 `item`, `affinity`, `blessing`, `title`이다. 퀘스트 호감도 보상은 항목당 +1~+200이며 전체값은 -1000~1000으로 제한된다. AI 관계 액션의 1회 ±50 제한은 그대로다. 가호는 데이터팩에 등록된 Minecraft 효과만 임시 적용하고, 칭호 ID는 플레이어 프로필에 영구 해금한다.

바인딩의 `rewards.mode`는 `ADD`와 `REPLACE`를 지원한다. ADD는 신 기본 표의 고정 `baseTier` 또는 평가 점수로 정해진 등급에 직접 보상을 더하고, REPLACE는 기본 표를 무시한다. 자동 보상 외에 2~6개 선택 묶음을 둘 수 있으며, 각 묶음은 아이템·호감도·가호·칭호를 혼합할 수 있다. 상세 JSON은 `mythictrpg-main/docs/REWARD_SYSTEM_GUIDE.md`를 따른다.

선택권은 퀘스트 완료 후 `mythictrpg_reward_claims` SavedData에 영구 저장한다. 만료 시간이 없고 ESC로 닫아도 다음 접속 때 다시 표시된다. claim·플레이어·option ID를 서버가 재검증하며 정확히 한 번만 선택·지급한다. 샘플 `fortuna.json`은 1–10등급이며, 기존 `fortuna_deep_sea_heart` 일반 퀘스트에 자동으로 연결된 것은 아니다.

퀘스트 진행은 플레이어별 수주가 가능하지만 논리 퀘스트의 최초 완료는 서버 전역이다. 한 플레이어가 완료하면 다른 수주자의 동일 퀘스트는 무효화된다.

### 7.3 오늘 확정한 최종 퀘스트 구조

```text
FTB Quests: 퀘스트북·기부 진행도·제출 UI
        ↓
제공자 또는 지정 확인 NPC와 대화
        ↓
MythicTRPG: 실제 조건 재검증 → NPC 대사 → 최종 완료 → 직접 보상
```

- FTB의 제출은 최종 보상 수령이 아니다. NPC 직접 보상 퀘스트는 FTB 보상을 비우고, FTB 퀘스트에 `custom` 최종 확인 게이트를 둬 NPC 확인 전 보상 버튼이 열리지 않게 한다.
- NPC 대화 시 서버가 인벤토리, 주·보조손, 거리·시야 조건을 만족하는 플레이어가 바라보는 컨테이너를 확인하는 구조가 필요하다. 이 실시간 검증·소비는 아직 MythicTRPG에 연결하지 않았다.
- AI는 보상 선택을 대사로 제안할 수 있으나, 선택지·난이도·등급·지급은 서버가 미리 허용한 범위 안에서만 결정한다.

#### 기부형 아이템 Task

`ftb-quests` 작업본에는 기존 `ItemTask`를 변경하지 않는 새 `DonationItemTask`가 추가되어 있다.

- “가능한 많이 가져와 달라” 유형에만 사용하는 별도 퀘스트 Task다. 모든 퀘스트를 기부형으로 바꾸지 않는다.
- 아이템을 여러 번 나누어 기부하고 팀 진행도에 누적한다.
- `minimumCount`, `maximumCount`, `consumeItems`, `manualCompletion`, `autoCompleteAtMaximum`, `hideMaximum`을 설정한다.
- 최대치 근처에서는 인정된 수량만 소비하며 나머지는 플레이어에게 남긴다.
- 수동 완료 시 실제 기부량을 유지한 채 완료 상태만 기록한다. 예: `173` 기부 후 완료 → `progress=173`, `completed=true`.
- `hideMaximum=true`이면 일반 플레이어 UI에는 `현재값 / ???`로 표시하며, 완료 패킷도 서버에서 최소치·수주 상태·미완료 상태를 재검증한다.

**상태:** FTB Quests NeoForge JAR `ftb-quests-neoforge-2101.1.34.jar` 빌드에 성공했고 서버 `mods`와 `client-required-mods`에 배포했다. JAR 안에 `DonationItemTask`, `DonationItemsScreen`, `CompleteDonationTaskMessage`가 포함된 것을 확인했으며 전용 서버 부팅도 성공했다. 실제 버튼 클릭·표시 검증은 클라이언트에서 수행해야 한다.

#### 보상 지정의 최종 모델

```text
신 설정: 신의 기본 보상 등급표 참조
퀘스트 설정: 직접 보상 + 사용할 기본 등급 또는 평가 등급 범위
```

일반 퀘스트는 직접 보상과 고정 등급을 조합한다. 평가형·기부 성과형 퀘스트는 보상표별 등급 범위를 지정하고, 서버 평가 점수 또는 실제 기부량으로 그 범위 안의 최종 등급을 정한다. `포르투나의 보상`, `호감도 +20` 등은 가능한 표시명 예시일 뿐 NPC 중심 표기가 강제되는 것은 아니다.

신 기본 표 참조, 퀘스트 직접 보상 ADD/REPLACE, 호감도·가호·칭호, 선택형 보상 UI와 서버 검증까지 구현됐다. 캐릭터별 보상표는 사용자 요청으로 제외했다. AI 신 프로필은 보상표 권한을 가지지 않으며 실제 보상표와 선택 claim은 MythicTRPG 서버 데이터에 둔다.

## 8. 개발용 명령어

운영자 권한이 필요하다.

```text
/mythadmin gods
/mythadmin quest list
/mythadmin quest assign <player> <questId> <godId>
/mythadmin quest check-return <player> <godId>
/mythadmin quest evaluate <player> <questId> <godId> <0..100> <summary>
/mythadmin quest status <player>
/mythadmin reward test-choice <player> <godId>
/mythadmin interaction god <player> <godId> <text>
```

포르투나 샘플 테스트:

```text
/mythadmin quest assign ADMIN mythictrpg:fortuna_deep_sea_heart mythictrpg:fortuna
/give ADMIN minecraft:heart_of_the_sea 2
/mythadmin quest check-return ADMIN mythictrpg:fortuna
/mythadmin quest status ADMIN
```

현재 배포 샘플 테스트에서는 먼저 FTB 퀘스트북에서 수주 마커와 목표가 표시되는지 확인하고, 아이템 목표 후 포르투나와 대화한다. 현재 샘플 보상은 FTB UI에서 수령한다. NPC 직접 보상으로 전환한 퀘스트에서는 FTB 보상을 비운다.

평가형 퀘스트는 분석기 연동 전 개발 검증용으로 다음 명령을 사용할 수 있다. 해당 퀘스트가 평가 정책을 가진 바인딩이어야 하며, `<summary>`는 평가 근거를 설명하는 문자열이다.

```text
/mythadmin quest evaluate ADMIN mythictrpg:example_evaluation mythictrpg:fortuna 82 해변과 조화를 이루는 설계
```

이 명령도 실제 분석을 수행하지 않는다. 점수와 근거를 제출하는 테스트 호출이며, 합격·등급·완료·보상은 동일한 서버 Gateway 경로를 사용한다.

## 9. 주요 코드 경계

- `FtbQuestBindingManager`: 논리 퀘스트 ↔ FTB ID 바인딩을 Datapack에서 로드
- `FtbQuestAdapter`: 버전이 고정된 FTB API 접근부. FTB 타입은 이 어댑터 밖으로 노출하지 않는다.
- `QuestRuntimeService`: 수주, 완료 정책, 전역 진행도, NPC 상호작용 연결
- `QuestEvaluationGateway`: 평가 점수 제출, 합격/재도전 판정, 보상 등급 변환, 완료 및 보상 지급
- `QuestEvaluationPolicy`: 퀘스트별 합격 점수와 보상 등급 범위
- `NpcRewardTableManager`, `NpcRewardTable`: NPC별 Datapack 보상표 로드·검증
- `QuestReminderService`, `QuestReminderState`: 퀘스트별 무관 행동 누적, 재촉 쿨다운, 자발 대사 트리거
- `MythicQuestState`: `mythictrpg_quests` SavedData. authoritative 수주/완료 기록
- `QuestProposalGateway`: AI 응답 모드가 검증된 `quest_offer`를 전달하는 공개 경계
- `AiActionGateway`: 모든 AI 액션 제안의 중앙 진입점. 서버 대화 범위 확인, allowlist 조회, Validator, 확인 대기, Executor를 순서대로 처리
- `AiActionRegistry`, `AiActionDefinition`: 게임 측 액션 등록소와 Validator/Executor 계약. 퀘스트·아이템·보상·관계·가호·안전 이벤트·플레이어 피해 액션이 등록됨
- `AiActionProposal`, `AiActionResult`: AI가 만든 비권위 입력과 서버의 제한된 실행 피드백 계약
- `AiQuestContentBridge`: AI 응답 모드가 콘텐츠 레지스트리의 퀘스트 후보를 선택적으로 조회하는 반사(reflection) 경계
- `AiQuestEvaluationContext`: 확정된 평가 결과를 AI 대화 모듈에 전달하는 계약
- `MythAiConversationEngine`, `GodAiDialogueService`: 평가/완료 결과를 NPC HUD 대사로 생성하고 실패 시 fallback 처리

기존 MythicTRPG 게임 기능을 AI 모듈에서 재구현하지 않는다. 신 해금·등장·관계/관찰·Condition Engine·Interaction/Encounter·Dialogue HUD·Minecraft 상태는 기존 시스템을 사용한다.

## 10. 빌드와 검증

MythicTRPG:

```powershell
cd C:\Users\ADMIN\Desktop\markmar\mythictrpg-main
.\gradlew.bat clean jar --no-daemon
```

AI 응답 모드:

```powershell
cd C:\Users\ADMIN\Desktop\markmar\mythai-ai-response
..\mythictrpg-main\gradlew.bat -p . clean jar --no-daemon
```

수정한 FTB Quests 공통 모듈:

```powershell
cd C:\Users\ADMIN\Desktop\markmar\ftb-quests
.\gradlew.bat :neoforge:build --no-daemon
```

배포 결과는 `ftb-quests/neoforge/build/libs/ftb-quests-neoforge-2101.1.34.jar`이다. 현재 서버와 `client-required-mods`는 이 빌드로 교체되어 있다. 서버와 모든 클라이언트는 반드시 같은 FTB Quests JAR을 사용해야 한다.

PowerShell 실행 정책으로 `gradlew.ps1` 또는 `pnpm.ps1`가 막히면 `.bat`/`.cmd` 확장자를 사용한다. FTB Quests 3개 챕터·2개 퀘스트·1개 바인딩의 서버 로드는 과거 검증 이력이며 이후 개발본의 부팅 검증을 대신하지 않는다. 최신 0~1단계의 실제 오프라인 명령·결과는 [작업 기록](../docs/RECALL_STAGE01_20260915.md)을 따른다. JAR 배포와 서버/GameTest 실행은 별도 승인 범위이며 빌드 후 자동으로 `server/mods`에 복사하지 않는다.

## 11. 알려진 제한과 다음 작업

- 최신 개인 회상은 개발 AI 0.1.4에만 구현됐고 서버는 0.1.3이다. 공통 사건/주시 계약은 문서 단계이며 상세 행동 원장·관찰 증명·임베딩 의미 검색은 아직 없다. 신규 회상 활성화 조건, 미확정 정책과 다음 단계 경계는 32절을 따른다.
- 실제 플레이어 접속을 통한 전체 퀘스트 UI/보상 수령 테스트는 개발자가 직접 클라이언트에서 수행해야 한다.
- 건축물 분석기는 블록·환경의 서버 권위 분석과 Gemma4 다중 시점 시각 분류까지 구현됐다.
  자유 건축은 등록/탐색 시 유형·스타일·신 취향 점수를 자동 분석한다. 상세 내용은 15~17절과
  `mythictrpg-main/docs/STRUCTURE_EVALUATION_GUIDE.md`를 참고한다. 건축 외 임의 결과물 분석은 별도 범위다.
- 평가형 퀘스트의 불합격은 현재 기존 요구사항을 유지한 재도전으로 처리한다. 분석 결과에 따른 요구사항 자동 갱신은 아직 연결하지 않았다.
- 통합 보상은 아이템·호감도·임시 가호·영구 칭호를 지원한다. 임의 명령어·스크립트 보상은 허용하지 않는다.
- 신 기본 보상표와 퀘스트 직접 보상 ADD/REPLACE는 구현됐다. 캐릭터 개별 보상표는 요청에 따라 제외했다.
- 인벤토리·손·시선 컨테이너를 NPC 최종 확인 조건으로 검사·소비하는 정책은 아직 구현하지 않았다.
- 선택형 보상은 2~6개 UI, 서버 재검증, 영구 pending claim, 재접속 재표시, 1회 지급까지 구현됐다. AI는 현재도 보상을 독자적으로 확정할 수 없다.
- 공통 AI 액션 Gateway에는 `quest_offer`, `item_request`, `reward_proposal`, `relationship_change`, `blessing_offer`, `world_interaction`, `player_damage` Definition이 등록되어 있다. `npc_visit_request`는 외부 물리 NPC 모드 연동 전까지 예약 ID로만 유지한다.
- 확인 대기형 액션은 서버 API, 60초 만료·플레이어당 8개 제한, 승인·거절 클라이언트 화면과 C2S 네트워크까지 연결되어 있다. 서버 재시작 시 pending action은 안전하게 폐기된다.
- `item_request`는 현재 발화의 준비 의사를 AI 응답 모드와 서버 Gateway 양쪽에서 확인하고, 승인 시 인벤토리와 시선 앞 블록 컨테이너를 재검증한 뒤 소비한다.
- 보상·가호·월드 개입은 `mythictrpg/ai_actions` 데이터팩 템플릿만 실행한다. 월드 개입은 sound/simple-particle만 허용하며 명령어·블록 변경·임의 좌표·몹 소환은 허용하지 않는다.
- `player_damage`는 NPC별 데이터팩 템플릿만 즉시 실행한다. 고정·최대 체력 비례·현재 체력 비례·치명 모드를 지원하고, 비치명 체력 1 보존, 대화당 사용 횟수, 재사용 간격, 등록 DamageType을 서버가 검증한다.
- AI 관계 변화는 행동 신 자신의 호감도만 변경하며 1회 최대 ±50, 전체 범위 -1000~1000으로 제한한다.
- 퀘스트 바인딩은 `narrativeRole=SIDE|MAIN_ENTRY|MAIN`과 `minimumAffinity=-1000..1000`을 지원한다. `MAIN_ENTRY`를 실제 수주한 복수 플레이어는 `GodAttentionState`에 해당 신의 주목 대상으로 저장되고, 후속 `MAIN`은 이들에게만 허용된다.
- 주목 대상은 플레이어 행동 점수를 서로 비교해 선발하지 않는다. 기존 상호작용 규칙의 행동 조건·확률로 조우한 뒤 첫 고정 퀘스트를 실제 수주한 사람(들)이 선택된다. 비주목 플레이어도 `SIDE` 퀘스트, 가호, 보상, 일반 상호작용, 공격 대상이 될 수 있다.
- AI 응답 모드의 퀘스트 후보 조회는 `QuestRuntimeService.validateAssignment`를 플레이어별로 통과한 항목만 반환하므로 권한 없는 메인 퀘스트를 대사로 제안하지 않는다.
- `DonationItemTask` 포함 FTB Quests JAR은 테스트 서버와 클라이언트 필수 모드 폴더에 배포됐다. 실제 클라이언트 버튼 조작 검증만 남아 있다.
- 평가형 퀘스트는 평가 바인딩과 NPC 보상표가 모두 로드되어야 하며, 퀘스트의 보상 범위가 해당 NPC 표의 최대 등급을 초과하면 제출이 거절된다.
- `NPC_VISIT_PLAYER`는 `readyForNpcVisit` 후보를 제공한다. NPC 이동·방문을 언제 생성할지는 기존 사건/상호작용 시스템과 연결해야 한다.
- FTB Teams가 같은 팀이면 FTB 목표 진행이 공유될 수 있다. 개인 추적이 필요하면 별도 팀 정책을 사용한다.
- FTB 버전을 바꾸면 `FtbQuestAdapter` API와 ID 형식을 우선 점검한다.
- 새 MythicTRPG upstream JAR을 받으면 AI 응답 모드가 기대하는 `QuestProposalGateway` 등 공개 계약이 유지되는지 먼저 확인한다. 계약이 바뀌면 AI 모드의 `build.gradle` 소스 오버레이와 연동 문서를 함께 갱신한다.

### 권장 제작 순서

새 신을 추가할 때는 대량의 Lore와 기능을 먼저 만들지 말고 한 신을 수직 완성한다.

```text
신 선정
→ 게임플레이 역할·실행 가능 범위 정의
→ 최소 신 프로필 작성
→ 관련 최소 Lorebook 작성
→ Interaction/Snapshot 연결
→ 로컬 AI 대사·Proposal 연결
→ MythicTRPG Validator 검증
→ 퀘스트·아이템·가호 확장
→ 레이드·다중 참가자·물리 NPC 확장
```

현재 첫 검증 대상은 Fortuna다. Fortuna에서 실제 호출부터 HUD 출력, 실패 복구, Proposal 거절/승인까지 확인한 뒤 다른 신을 추가한다. 게임에 아직 없는 기능은 AI 응답에서 Proposal-only 또는 기본 거절로 유지한다.

### 콘텐츠와 게임 상태의 경계

- 정적: 신 프로필, 말투 지침, Lore 단계, 예시 대화, 정적 퀘스트 후보 → Content Registry
- 권위 있는 동적 상태: 관계 수치·게임 사건·관찰 사실·퀘스트 진행·보상·인벤토리·월드 상태 → MythicTRPG
- 비권위 대화 상태: 대화 이력·감정 해석·개인 기억 원문/요약 → AI 응답 모듈. 발언/소문을 게임 사실로 승격하지 않으며 신별로 알고 있는 범위와 청중에게 공개 가능한 범위를 구분한다.
- AI의 게임 실행 요청: 대사와 Proposal 경로를 사용하며 기억만으로 보상·관계·월드를 직접 변경하지 않음
- 실행: 반드시 기존 MythicTRPG Validator와 외부 모드 Adapter를 통과

## 12. 작업 원칙

1. 먼저 실제 디렉터리·코드·빌드 결과를 확인한다.
2. MythicTRPG의 authoritative 게임 데이터를 복제하지 않는다.
3. AI는 제안만 만들고, 게임 시스템이 검증·적용한다.
4. 기존 API 계약을 깨지 않는 최소 변경을 우선한다.
5. 모드 간 계약 변경은 문서와 예제 JSON을 함께 갱신한다.
6. 코드 변경은 관련 컴파일·핵심 오프라인 테스트를 수행하고, 소스/검사/JAR 배포/서버 부팅/인게임 확인을 구분해 보고한다. 서버 로딩·GameTest·실제 LLM 호출은 승인된 경우에만 진행하며 생략하면 미검증으로 기록한다. 문서만 변경했으면 링크·형식 검사로 마무리한다.

AI 액션 추가 절차는 `mythictrpg-main/docs/AI_ACTION_INTEGRATION_GUIDE.md`를 기준으로 한다.

## 13. 2026-08-30 — AI 즉석 SIDE 퀘스트 구현

### 구현 완료

- 새 AI 액션 `generated_quest_offer`를 등록했다. AI는 신별로 데이터팩에 등록된
  `template_id`와 제한된 제목·요약만 제안할 수 있다.
- 템플릿 경로는 `data/<namespace>/mythictrpg/generated_quest_templates/*.json`이다.
  목표 관찰·대상·횟수, 허용 월드 진행도 구간, NPC 보상표와 기본/최대 단계, 최대 +1 보정,
  쿨다운, 만료 시간을 서버가 엄격하게 로드한다.
- 생성되는 퀘스트는 전부 `SIDE`다. `MAIN_ENTRY`/`MAIN` 권한이나 `GodAttentionState`를
  변경하는 필드와 실행 경로가 없다.
- `GeneratedQuestState`가 플레이어별 활성 퀘스트, 진행도, 만료, 템플릿별 쿨다운과 FTB 미러
  ID를 `mythictrpg_generated_quests` SavedData에 저장한다. 플레이어당 활성 즉석 의뢰는 하나다.
- 기존 Gameplay Observation 중 엔티티 처치, 블록 파괴, 성숙 작물 수확, 동물 먹이주기·번식만
  목표로 허용했다. 목표 도달 후 행동 신 소유의 `NpcRewardTable` 등록 단계만 서버가 지급한다.
- 포르투나 샘플 템플릿 3개(좀비 처치, 거미 처치, 밀 수확)를 추가했다. 서로 다른 월드 진행도
  구간과 1단계 이내의 보상 상한을 사용한다.
- FTB Quests 팩에 `즉석 의뢰` 챕터(`1D1A4D1C00000001`)를 추가했다. 런타임에 플레이어별
  숨김 수주 마커, 표시 퀘스트, `custom` 진행 태스크를 만들고 Mythic 진행도를 미러링한다.
  FTB 쪽에는 보상을 만들지 않으며 실제 판정·완료·지급은 계속 MythicTRPG가 소유한다.
- AI 응답 모듈의 capability 프롬프트와 정규화 allowlist도 새 액션을 지원한다. AI가 목표 수치,
  보상, 메인 역할을 새로 만들면 정규화 또는 서버 Validator에서 거절된다.

### 전투력 보정 상태와 반드시 지켜야 할 후속 기준

`CombatPowerProvider`, `CombatPowerAssessment`, `CombatPowerService` 확장 경계를 추가했다.
현재 프로젝트에는 확정된 전투력 공식과 월드 진행도별 권장 전투력 데이터가 없으므로 기본
Provider는 반드시 `UNAVAILABLE`을 반환한다. 따라서 현재 빌드에서는 플레이어 장비를 추측해
보상을 높이지 않으며 보정 단계는 0이다.

향후 전투력 시스템을 연결할 때 실제 전투력은 **현재 착용 장비가 아니라 플레이어가 지금까지
받은 보상 중 가장 좋은 장비**, 현재 유효한 가호 및 기타 전투 보정을 포함해 산정한다. 이 값을
**세계 진행도별 권장 전투력 표**와 비교하여 명확히 뒤처진 플레이어에게만 보상 +1단계를 제안한다.
이를 위해 다음 두 데이터가 아직 필요하다.

1. 보상 지급 이력에서 슬롯별 최고 장비 또는 최고 보상 장비를 안정적으로 조회하는 원장/점수표
2. 각 월드 진행 트랙·구간별 권장 전투력을 정의하는 데이터팩 표

Provider가 잘못된 값을 반환해도 `GeneratedQuestService`가 보정을 0~1로 다시 제한하고,
최종 단계는 각 템플릿의 `maximumTier`를 절대 넘지 않는다. 플레이어 간 전투력 비교는
캐치업 보정 자료일 뿐 신의 주목 대상이나 메인 퀘스트 수주자를 선발하는 데 사용하면 안 된다.

### 배포·운영 주의

- 서버의 `config/ftbquests/quests/`에 최신 `mythictrpg-main/ftbquests-pack`을 복사해야 즉석
  의뢰 챕터가 보인다. 챕터가 없으면 MythicTRPG 권위 퀘스트는 진행되지만 FTB 미러는 생성되지 않는다.
- FTB Teams가 같은 팀이면 표시용 태스크 진행이 공유될 수 있다. 실제 목표 판정과 보상 대상은
  생성 당시 플레이어 UUID로 고정되지만 개인 UI가 필요하면 플레이어를 별도 FTB 팀으로 유지한다.
- 상세 스키마와 확장 계약은 `mythictrpg-main/docs/GENERATED_QUESTS_GUIDE.md`를 기준으로 한다.

## 14. 2026-08-30 — 통합 보상·선택 UI·FTB 배포 완료

### 구현 완료

- `RewardEntry` 공통 타입으로 아이템, 퀘스트 호감도, 임시 가호, 영구 칭호를 통합했다.
- 신 기본 보상표 schemaVersion 2와 신별 유일 기본 표 검증을 추가했다. schemaVersion 1은 호환된다.
- 퀘스트 바인딩에 직접 보상 `ADD`/`REPLACE`, 자동 보상, 2~6개 선택 묶음을 추가했다.
- 일반 완료와 평가형 완료 모두 완료 커밋 전에 보상 정의와 claim 저장 가능 여부를 검증한다.
- `RewardClaimState`가 자동 지급·선택 여부를 영구 저장한다. 선택에는 만료가 없고 재접속 시 다시
  표시되며, 서버가 플레이어·claim·option을 검증해 한 번만 지급한다.
- 클라이언트 선택 화면과 다중 pending 큐, S2C 선택지 payload, C2S 선택 payload를 구현했다.
- 플레이어 프로필은 dataVersion 4로 올라갔고 `unlockedTitles`를 저장한다. V1~V3은 자동 이관된다.
- 실제 화면 검증용 `/mythadmin reward test-choice <player> <godId>` 명령을 추가했다.
- 캐릭터별 보상표는 사용자 요청에 따라 구현 범위에서 제외했다.

### 검증·배포 결과

- `mythictrpg-main`: `clean build` 성공.
- GameTest: 전체 173개 필수 테스트 통과. 새 보상 테스트 4개가 ADD/REPLACE, 호감도 한도,
  비아이템 지급, pending 영속성과 중복 선택 거부를 검증한다.
- `ftb-quests`: `:neoforge:build` 성공. DonationItemTask 관련 3개 클래스가 JAR에 포함됐다.
- 서버 배포:
  - `server/mods/mythictrpg-1.0.0.jar`
  - `server/client-required-mods/mythictrpg-1.0.0.jar`
  - 양쪽 `ftb-quests-neoforge-2101.1.34.jar`
- 교체 전 MythicTRPG JAR 백업: `server/backups/codex-pre-reward-system-20260830`.
- 전용 서버 실부팅 성공. 139개 신 정의, FTB 바인딩 1개, 신 기본 보상표 1개,
  AI 액션 템플릿 6개, 즉석 SIDE 템플릿 3개를 로드했고 FTB Quests는 3개 챕터·2개 퀘스트를
  로드한 뒤 `Done`에 도달했다. 이후 `stop`으로 정상 저장·종료했다.
- 개발 클라이언트 `runClient` 스모크 테스트도 성공했다. NeoForge·FTB Quests·MythicTRPG와
  클라이언트 리소스가 Render thread까지 오류 없이 로드됐으며, 확인 후 프로세스를 종료했다.

### 남은 수동 확인

코드·자동 테스트·전용 서버 부팅은 완료됐다. 그래픽 클라이언트를 이 작업 환경에서 직접 조작할
수 없으므로 다음 두 가지는 실제 클라이언트에서 확인한다.

1. DonationItemTask의 누적 기부, `현재값 / ???`, 수동 완료 버튼과 최대치 소비 동작.
2. `/mythadmin reward test-choice ADMIN mythictrpg:fortuna` 실행 후 선택 UI 표시, ESC→재접속
   재표시, 한 선택지만 1회 지급되는지 확인.

보상 작성 스키마와 수동 점검 절차는 `mythictrpg-main/docs/REWARD_SYSTEM_GUIDE.md`를 기준으로 한다.

## 15. 2026-08-30 — 범용 건축물 평가 시스템 및 포르투나 모던 정책

### 구현 완료

- X/Z 최대 48×48, Y 차원 전체 높이를 허용하는 건축 영역과 전용 선택 아이템을 추가했다.
- `mythictrpg_structure_evaluations` SavedData에 퀘스트별 영역, 확정 시점의 FTB 팀원 UUID,
  sparse 블록 원장, 직접/파생 출처, 장식 엔티티, 평가 쿨다운과 성공 fingerprint를 저장한다.
- 영역 확정 후 참가자의 설치·파괴, 농지 변환, 물 source, 작물 현재 상태, 그림·아이템 액자·
  갑옷 거치대를 추적한다. 기존 지형·건물과 비참가자 블록은 BUILD 점수에서 제외한다.
- 원장 좌표만 읽어 실제 min/max Y를 계산한다. 제한된 내부 flood-fill, 최대 80개 환경 대표점,
  5×5 바이옴 표본을 사용하며 평가 때문에 청크를 강제 생성하거나 로드하지 않는다.
- Datapack `structure_evaluation_policies` 로더와 범용 criterion registry를 추가했다. scope별
  점수를 0~100으로 정규화한 뒤 정책의 BUILD/ENVIRONMENT 비중을 적용한다.
- 첫 정책은 `mythictrpg:fortuna_modern`이다. 포르투나는 모던 재료, 유리, 절제된 팔레트,
  기하 균형, 개방형 실내, 조명·기능성을 선호하며 BUILD 90%, 환경 10%를 사용한다.
- 퀘스트 바인딩의 `structureEvaluation.policyId`를 추가하고 독립 서비스에서 기존
  `QuestEvaluationGateway`로 결과를 전달한다. AI 액션 `structure_evaluation_request`는
  `quest_id`만 제출할 수 있고 점수·근거·합격·보상은 제출할 수 없다.
- 평가 보상표가 없는 신도 직접 보상 `REPLACE` 퀘스트면 완료할 수 있게 했다. `ADD`는 여전히
  신 기본 보상표가 필수다.
- `/mythadmin structure tool|pos1|pos2|confirm|status|inspect|evaluate|clear|policies`를 추가했다.
- 같은 퀘스트의 수정·재평가는 허용하고 성공한 동일 fingerprint의 다른 퀘스트 재사용은
  기본 차단한다. 정책 `allowReuse`만 예외를 허용한다.

### 검증 결과

- `compileJava` 성공.
- 데이터팩에서 포르투나 정책과 3개 블록 태그를 정상 로드했다.
- 전체 GameTest 178개 통과. 새 테스트 5개가 48 허용/49 거부, 깊은 지하~최고 높이 sparse
  경계, 기존 건물·비참가자 제외, tracked 상한, 포르투나 모던 선호 점수 차이, 0~100 제한과
  strict JSON을 검증한다.
- 최종 JAR을 `server/mods`와 `server/client-required-mods`에 배포했다. 세 파일의 SHA-256은
  `7F9AA6290B2C236F3BD02B168CE3C6E0D2C887D7D7685B1041EDB8332715D949`로 일치한다.
- 교체 전 JAR은 `server/backups/codex-pre-structure-evaluation-20260830`에 보관했다.
- 실제 전용 서버에서 신 정의 139개, 건축 평가 정책 1개, FTB 3개 챕터·2개 퀘스트를 로드하고
  `Done (0.748s)`에 도달했다. 확인 후 `stop`으로 정상 저장·종료했다.

### 후속 주의

- 실제 건축 퀘스트를 출시할 때 해당 FTB 바인딩에 `evaluation`과
  `structureEvaluation.policyId`를 함께 작성해야 한다. 현재 번들에는 평가 엔진과 포르투나
  정책만 있으며 기존 `fortuna_deep_sea_heart` 퀘스트의 의미는 변경하지 않았다.
- 묘목 성장 전체와 피스톤·폭발 provenance는 후속 범위다. 이 시점에 남아 있던 Vision AI는
  2026-09-01의 17절에서 구현했으며, 실제 텍스처와 정밀 지붕·창문·방 동선 판정은 여전히 후속 범위다.
- 스키마·운영 명령·성능 상한은 `mythictrpg-main/docs/STRUCTURE_EVALUATION_GUIDE.md`를 기준으로 한다.

## 16. 2026-08-30 — 무제한 자유 건축과 건축 기반 신 등장 조건

### 구현 완료

- `StructureRegion`의 48×48 하드 제한과 정책 영역 초과 거부를 제거했다. 건축 영역·원장에는
  사용자에게 보이는 크기 상한이 없으며 Y는 계속 차원 전체 높이를 사용한다.
- `mythictrpg_player_constructions` SavedData를 추가했다. 퀘스트와 무관하게 모드 적용 이후의
  설치·파괴·파생 블록 provenance를 상시 기록하고, 자유 건축물 등록 시 본인과 확정된 FTB
  팀원의 과거 기록을 가져온다.
- `/mythstructure pos1|pos2|register|discover|list|status|evaluate|delete` 플레이어 명령을
  추가했다. 선택 영역 등록과 현재 위치 근처 연결 구조 자동 탐색을 모두 지원한다.
- 자유 건축물 등록 시 로드된 모든 건축 정책 평가를 자동 예약한다. 원장 준비는 틱당 1,500개로
  분할하며, 정책의 `maxTrackedBlocks`를 넘으면 거절하지 않고 대표 표본을 사용한다. 미로드
  청크는 강제 로드하지 않고 표본에서 제외한다.
- 자유 건축과 퀘스트 건축의 평가 결과를 정책·신·점수·BUILD/ENVIRONMENT·feature·fingerprint·
  참가자와 함께 공통 이력으로 영구 저장한다.
- 조건 타입 `mythictrpg:structure_evaluated`를 추가했다. `policy`, `minimum_score`,
  `minimum_build_score`, 선택적 `maximum_age_ticks`, `minimum_features`를 지원하며 PLAYER,
  ANY_PLAYER, ALL_PLAYERS, ANY_ONLINE_PLAYER scope에서 사용할 수 있다.
- 이 조건은 기존 자동 등장 시스템의 후보 자격만 제어한다. 실제 조우 확률·상황 판정 흐름을
  우회해 신을 즉시 소환하지 않는다. 번들 포르투나의 `always` 등장 조건은 호환을 위해 유지했다.

### 운영 주의

- 상시 원장은 모드 배포 이후 행동만 안다. 배포 전에 완성된 건물은 자동 provenance가 없으므로
  소유권을 추정하지 않는다.
- “크기 제한 없음”은 등록 거부 상한이 없다는 의미다. 서버 보호를 위해 분석은 분할·표본화하고
  청크를 강제 로드하지 않으며, 내부 flood-fill은 기존 셀 예산을 넘으면 생략한다.
- 자유 건축 사용법과 등장 조건 JSON 예시는
  `mythictrpg-main/docs/STRUCTURE_EVALUATION_GUIDE.md`에 있다.

### 검증 및 배포

- `build` 성공.
- 전체 필수 GameTest 180개 통과. 기존 178개 회귀 테스트와 새 건축 등장 조건의 점수·BUILD·
  feature 임계값 일치 및 잘못된 임계값 거부 테스트 2개를 포함한다.
- 최종 JAR을 `server/mods`와 `server/client-required-mods`에 배포했다. 빌드 파일과 두 배포
  파일의 SHA-256은 모두
  `3C20A8FD8F5BD9C35011B062E452F8C313680B4588D3CF6F6A66F7C680CE8C80`이다.
- 교체 전 서버·클라이언트 JAR은
  `server/backups/codex-pre-free-build-20260830`에 보관했다.
- 실제 전용 서버에서 신 정의 139개, 건축 정책 1개, FTB 3개 챕터·2개 퀘스트를 정상 로드하고
  `Done (0.866s)`에 도달했다. `/mythadmin structure policies`에서 포르투나 정책을 확인한 뒤
  `stop`으로 정상 저장·종료했다.

## 17. 2026-09-01 — Gemma4 건축물 시각 분류 및 신 취향 평가

### 구현 완료

- 전용 서버가 플레이어/고정 팀원의 provenance 원장에서 최대 12,000개 대표 블록을 복사해
  512×512 PNG 5장(4방향 아이소메트릭, 상단)을 만드는 headless 소프트웨어 렌더러를 추가했다.
  클라이언트 렌더러·GPU·텍스처 파일을 사용하지 않으며 PNG는 기본적으로 디스크에 남기지 않는다.
- `mythai-ai-response`에 일반 대화와 분리된 Ollama Vision 요청 경로를 추가했다. 별도 프로세스나
  두 번째 모델 인스턴스를 띄우지 않고 기존 `ai-dialogue.json`의 URL과 `gemma4:12b`를 공유한다.
  비전 요청은 최대 16건의 bounded queue와 단일 worker를 사용한다.
- 고정 주 유형 `HOUSE`, `TEMPLE`, `ARENA_AMPHITHEATER`, `FORTRESS_CASTLE`, `TOWER`, `FARM`,
  `WORKSHOP`, `BRIDGE`, `MONUMENT`, `PUBLIC_BUILDING`, `SHIP`, `OTHER`, `UNKNOWN`을 정의했다.
  세부 유형·스타일은 길이와 개수가 제한된 자유 라벨이며, 시각 품질·완성도·신 선호도·신뢰도·
  관찰 근거·우려 사항을 구조화 JSON으로 받는다.
- 포르투나 정책에 데이터팩 기반 `visualProfile`을 추가했다. 모던·컨템퍼러리·미니멀·정돈된
  기하학과 유리 입면·개방감·절제된 팔레트를 선호하되 흰 블록과 유리 개수만으로 현대 건축을
  판정하지 않도록 지침을 넣었다. 다른 신도 같은 JSON 필드만 추가하면 된다.
- 서버는 응답 필드·enum·점수 범위·목록 길이를 재검증한다. `UNKNOWN`, 신뢰도 0.68 미만,
  타임아웃, Ollama 중단, 잘못된 JSON은 기존 블록 점수를 유지한다. 충분히 확실한 포르투나 결과는
  `블록 원점수 70% + 신 시각 선호도 30%`로 저장 평가 점수를 계산하며 AI 비중은 정책 스키마상
  최대 30%다.
- 건축 퀘스트의 동기 완료·보상은 계속 서버 블록 점수로 판정한다. 비동기 Vision은 퀘스트 및
  자유 건축 공통 평가 이력과 향후 `structure_evaluated` 등장 조건 점수에 반영한다.
- SavedData를 v2로 올리되 v1을 마이그레이션하도록 했다. 블록 원점수와 Vision 결과를 함께
  보관하며 fingerprint가 바뀐 뒤 늦게 도착한 응답은 폐기한다.
- 자유 건축 등록·`discover`·수동 `evaluate`가 Vision을 자동 예약한다. 새 명령
  `/mythstructure result <name> <policy>`로 최종/블록 점수, 유형, 세부 유형, 스타일, 신뢰도,
  품질·선호도·완성도와 근거를 확인한다. `status`는 블록/시각 대기열을 따로 표시한다.
- 웹 검색은 건축 점수 근거로 사용하지 않는다. 전체 운영 규칙과 `visualProfile` 예시는
  `mythictrpg-main/docs/STRUCTURE_EVALUATION_GUIDE.md`에 정리했다.

### 검증 및 배포

- 메인 모드와 AI 응답 모드 `compileJava`, `build` 성공.
- 전체 필수 GameTest 182개 통과. 새 테스트는 5개 PNG 생성·디코딩, 70/30 점수 결합,
  저신뢰도 fallback, 포르투나 시각 프로필 로드를 검증한다.
- 실행 중인 Ollama `gemma4:12b`에 이미지와 제품 동일 9필드 JSON schema를 직접 보냈고,
  비건축 이미지를 `UNKNOWN`, 신뢰도 0.1로 반환하여 저신뢰도 경로가 동작함을 확인했다.
- 최종 메인 JAR을 `server/mods`와 `server/client-required-mods`에 배포했다. SHA-256은 둘 다
  `B8938C910EB73313117225E87E8886AF689DD61279BBDBB00873E67C2CC4AD52`다.
- AI 응답 JAR은 서버에만 배포했다. SHA-256은
  `DBC7C095943817248E05CD86B58966DE8DF74075A54BB8474134AE7C68D8147F`다.
- 교체 전 JAR 3개는 `server/backups/codex-pre-structure-vision-20260901`에 보관했다.
- 실제 전용 서버에서 두 모드, `gemma4:12b`, 신 정의 139개, 건축 정책 1개,
  FTB 3개 챕터·2개 퀘스트를 정상 로드하고 최종 배포 JAR으로 `Done (0.763s)`에 도달했다.
  `/mythadmin structure policies`에서 `mythictrpg:fortuna_modern`을 확인한 뒤 정상 종료했다.

### 현재 한계

- 소프트웨어 렌더는 실제 텍스처, 셰이더, 계단·반블록의 정밀 형상, 엔티티 가구와 완전한 실내
  동선을 표현하지 않는다. Gemma4 프롬프트에도 이 한계를 명시하고 객관 수치와 함께 평가한다.
- 미등록 자유 건축은 소유권 원장에는 남지만 하나의 건축물 경계가 확정되지 않으므로 Vision을
  즉시 호출하지 않는다. `register` 또는 `discover`로 경계를 확정할 때 자동 평가한다.
- 건축 퀘스트의 보상을 Vision 완료까지 지연시키지는 않는다. 시각 취향을 퀘스트 합격의 필수
  조건으로 삼으려면 별도의 비동기 완료 상태/UI 설계가 필요하다.

## 18. 2026-09-01 — 신 간 관계 AI 반영 및 동적 관계 시스템

### 구현 완료

- Content Registry가 이미 제공하던 방향성 `social_relations`를 AI 일반 대화·관계 대화·즉석
  상호작용 Context에 연결했다. 현재 서버가 허용한 최대 두 신 사이의 `화자 → 상대` 태그만
  전달하며, 정적 관계와 현재 동적 관계를 분리해 프롬프트에 표시한다.
- AI 대화 서비스는 한 플레이어와 명시된 1~2명의 신을 받는다. 관계 정보를 근거로 Secondary
  God을 자동 선정하거나 새 참가자를 추가하지 않는다.
- `mythictrpg_god_relations` SavedData를 추가했다. 실제 존재하는 방향만 sparse하게 저장하며
  점수 -1000~1000, 상태 태그, revision, 마지막 원인, 방향별 최근 이력 16개를 보관한다.
- 두려움·원한·존경·무관심 등은 방향별로 독립적이다. `ALLIED`, `TRUCE`, `AT_WAR`만 대칭으로
  취급해 반대 방향에 같은 트랜잭션으로 적용한다.
- `data/<namespace>/mythictrpg/god_relation_transitions/*.json` strict loader를 추가했다. 전이별
  변경 방향·점수·태그·행동 신·AI 허용 여부·최대 적용 횟수를 데이터에서 고정한다. 여러 방향은
  전부 검증된 경우에만 커밋되며 모순 태그, 범위 초과, 존재하지 않는 God ID는 거절한다.
- 기본 `maxApplications=1`이고 적용 횟수를 월드에 저장하므로 일회성 전쟁·봉인 관련 사건이
  재접속이나 서버 재시작 후 중복 적용되지 않는다. 반복 가능한 전이만 1~1000 범위로 명시한다.
- 조건 타입 `mythictrpg:god_relation`을 추가했다. source/target 방향의 점수 범위와 필수·금지
  태그를 퀘스트·등장·향후 사건 조건에 사용할 수 있다.
- AI 액션 `god_relation_transition`을 추가했다. `aiEnabled=true`인 등록 `transition_id`만 제안할
  수 있고 항상 플레이어 확인을 거친다. 승인 순간 서버가 현재 상태와 적용 횟수를 재검증하며
  AI는 점수·태그·대상·결과를 자유 입력하지 못한다.
- `/mythadmin relation gods get|history|preview|apply` 운영 명령을 추가했다.
- 상세 스키마·태그 규칙·조건·명령과 닉스 사례의 모델링 예시는
  `mythictrpg-main/docs/GOD_RELATION_SYSTEM_GUIDE.md`에 정리했다.

### 검증 및 현재 데이터 상태

- 메인 모드, AI 응답 모드, Content Registry `build` 성공.
- 전체 필수 GameTest 183개 통과. 새 관계 테스트가 방향성 감정, 대칭 전쟁, 모순 전이 원자적
  거절, 1회 전이 중복 방지, 조건 평가, 저장·로드, 참가자 한정 Context를 검증한다.
- 메인 JAR SHA-256:
  `009E27315B348F6143E22668A31271DFB48F551A6A8A24019D91494A9DA1E3F4`
- AI 응답 JAR SHA-256:
  `5F074261352AFF9633EAA58F316532C2F855690E0BA023763AFD6B3C5088F6E1`
- 이번 작업에서는 빌드만 했고 `server/mods` 및 클라이언트 폴더에 배포하지 않았다.
- 실제 `social_relations`와 `god_relation_transitions` 운영 데이터는 아직 0개다. 닉스·제우스·
  아프로디테를 포함한 확정 God ID, 프로필, 전체 관계도를 받은 뒤 작성하고 resource reload 및
  Gemma4 실대화 스모크 테스트를 해야 한다.

## 19. 2026-09-01 — 인과 사건 엔진의 확정 설계 방향

- 신들끼리 장시간 AI 자율 대화를 실행해 스토리 결과를 만드는 기능은 개발하지 않는다.
- 플레이어 행동·퀘스트·지역·신의 부재·장소 점유·관계 같은 서버 신호가 관련 사건 후보만 깨운다.
- 사건 결과는 작성된 `FIXED` 또는 `WEIGHTED_ONCE` 후보 중 서버가 한 번만 확정하고 저장한다.
  재접속·재시작·대화 재시도·AI 실패는 결과를 재추첨하지 않는다.
- 사건의 실제 `Runtime World Fact`, 플레이어가 관찰한 단서, 각 신·세력이 아는 사실과 공개 가능
  수준을 분리한다. 신에게 질문했을 때는 정적 Lore와 동적 Story Knowledge에서 그 신이 실제로
  아는 내용만 AI Context에 전달한다.
- 플레이어가 모르는 사이 봉인을 풀고, 미지 존재가 비어 있는 루브라스 본거지에 정착하며,
  루브라스가 이유를 밝히지 않고 사라졌다가 다른 신의 제한적 설명과 루브라스 귀환 후 상세 공개로
  이어지는 사례를 첫 수직 사건의 기준으로 삼는다.
- 플레이어가 보지 않는 사건 단계는 AI 호출 없이 서버 상태로 처리한다. AI는 확정 사건을 프로필과
  관계에 맞게 말하는 연출과 등록된 조사 Hook 제안만 담당한다.
- 세부 구조·가중 결과·숨은 사실·지식 공개·지연 후속·테스트 기준은
  `추가개발/03_분기형_스토리_이벤트_엔진.md`에, 선택적 AI 역할은
  `추가개발/04_AI_동적_사건_안전_생성.md`에 반영했다.
- 이 절은 설계 확정 기록이며 03·04의 코드 구현 완료를 의미하지 않는다.

## 20. 2026-09-01 — 03·04 구현 가능 상세 설계 확정

### 사용자와 확정한 정책

- MAIN/WORLD의 실제 변화는 기본 서버 전역, 개인 지식·목격·개인 분기는 player별로 저장한다.
  TEAM 범위는 사건에 명시된 경우만 사용하며 발생 당시 구성원을 고정한다.
- 신뿐 아니라 미지의 존재와 세력도 범용 Story Actor로 등록한다. 봉인·활동·패배·소멸,
  대화 가능/부재와 실제 좌표가 아닌 논리적 장소를 저장한다.
- 예약 사건은 서버 game time만 사용하여 서버가 꺼진 동안에는 진행하지 않는다.
- MAIN/ENDING은 조건·플레이어 선택이 기본이며 명시적으로 허용된 사건만 1회 가중 추첨한다.
- 플레이어·신·세력 지식은 별도다. 팀 자동 공유는 없고 목격·대화·작성 grant로만 전달한다.
- 신은 숨기거나 일부만 말할 수 있다. 거짓말은 등록 Cover Story만 사용하며 AI가 새 거짓 정사를
  만들지 않는다.
- AI는 허용된 등록 Hook만 제안하고 플레이어 확인 뒤 서버가 세션·revision·조건을 재검증한다.

### 상세 설계 결과

- 03 문서에 actor/location/fact/cover story/disclosure/event/hook/presentation strict registry,
  StorySignal과 dependency index, SERVER/PLAYER/TEAM scope, `mythictrpg_story` SavedData,
  사건 상태 머신, FIXED/FIRST_MATCH/WEIGHTED_ONCE/PLAYER_CHOICE, canonical commit과 외부 effect
  outbox, game-time scheduler, 동적 Knowledge와 disclosure, reload fingerprint/recovery 계약을 정했다.
- 신호는 관련 사건만 평가하며 playerless 사건에 기존 `InteractionSignal`을 억지 재사용하지 않는다.
  기존 Gameplay ingress에는 Story adapter를 별도 소비자로 추가한다.
- 04 문서에 SCRIPTED_ONLY/AI_PARAPHRASE/AI_FLAVOR와 FLAVOR_ONLY/FACT_BEARING/CRITICAL 정책,
  player별 PresentationOpportunity, 비밀을 제거한 Snapshot, authored Cover Story, opaque Hook token,
  AiActionGateway 확인 흐름, offline/fallback/revision/idempotency 계약을 정했다.
- 모델의 의미 단위 환각을 완전히 막는다고 과장하지 않는다. MAIN/ENDING의 필수 사실은 AI 대사에만
  맡기지 않고 작성된 사건 저널·자막으로 함께 전달하며 CRITICAL 장면은 SCRIPTED_ONLY로 강제한다.
- Minecraft SavedData의 정상 저장·재시작 일관성은 설계했지만 저장 tick 사이 프로세스 강제 종료까지
  무손실을 보장하지 않는다. 해당 수준이 필요하면 별도 write-ahead journal이 후속으로 필요하다.
- 두 문서는 구현 순서, 관리자 명령, 루브라스 7단계 수직 검증 사례와 테스트 매트릭스를 포함한다.
- 이번 작업은 상세 설계 문서 확정이며 03·04 Java 코드와 데이터팩 구현 완료를 의미하지 않는다.

## 21. 2026-09-01 — 03 조건 기반 스토리 사건 엔진 1차 구현

### 구현 완료

- `story` 패키지에 actor/location/fact/cover story/disclosure/event/hook/presentation strict registry와
  하나의 atomic reload snapshot을 추가했다. 신호 exact/wildcard 역색인으로 관련 사건만 평가한다.
- `mythictrpg_story` SavedData v1에 범위별 Fact, Actor 상태와 논리 위치, Event Instance와 결정 roll,
  latched trigger, game-time schedule, 플레이어·행위자 Knowledge, public Knowledge, Presentation,
  Hook 수락 이력, audit를 저장한다. 깨진/미지원 NBT는 원본을 보존하고 READ_ONLY로 닫는다.
- SERVER/PLAYER/TEAM scope, 발생 당시 팀원 고정, ONCE/LIMITED/COOLDOWN, FIXED/FIRST_MATCH/
  WEIGHTED_ONCE/PLAYER_CHOICE, choice revision·audience·timeout 재검증을 구현했다.
- canonical fact/actor/location/knowledge/schedule/presentation effect와 외부 관계 transition effect를
  분리했다. 외부 실패는 결과를 다시 뽑지 않고 `EXTERNAL_EFFECT_PENDING`으로 저장해 재시도한다.
- Story 전용 Condition 6종과 Disclosure 5종을 추가했다. 실제 사실, 각 신의 지식, 플레이어 지식,
  공개 가능한 단계, 등록 Cover Story가 분리된다.
- 퀘스트 완료, 신 해금·식별, 관계 전이, Gameplay Observation을 StorySignal로 연결했다. 모든 연결은
  기존 시스템의 권위 커밋 이후에만 신호를 발생시킨다.
- 등록 Hook 서비스와 AI 없이 동작하는 fallback Presentation을 추가했다. 신 대화 채널 전송 실패 시
  시스템 메시지로 대체하며 오프라인 기회는 로그인 때 복구한다.
- `/mythadmin story health|event|fact|actor|knowledge|schedules|recovery` 운영 명령과
  `mythictrpg-main/docs/STORY_EVENT_ENGINE_GUIDE.md`를 추가했다.
- 엔진 검증용 루브라스 사례 데이터가 봉인 해제 → 예약 본거지 점유 → 루브라스 부재 → 아프로디테의
  제한적 단서 → 예약 귀환 → 루브라스의 전체 공개를 AI 없이 실행한다. 실제 운영 스토리는 확정
  프로필·관계도·배경 전개를 받은 뒤 같은 구조로 교체해야 한다.

### 검증과 남은 경계

- `compileJava` 성공, NeoForge 전체 필수 GameTest 186개 통과. 새 테스트는 strict definition/index,
  SavedData fail-closed 원본 보존, 전체 사건 연쇄와 Cover Story/Hook/fallback/저장·복원을 검증한다.
- PLAYER_CHOICE 서버 API와 timeout은 구현됐지만 전용 선택 GUI/packet은 아직 없다.
- 일반 신 대화가 Hook 버튼을 자동 표시하고 AI에 비밀 제거 Snapshot을 주는 기능은 04 작업이다.
- 논리적 Actor 위치만 저장하며 실제 NPC 이동·전투 시뮬레이션은 하지 않는다.
- 정상 저장·재시작은 지원하지만 저장 tick 사이 프로세스 강제 종료까지 무손실은 보장하지 않는다.
- 이번 작업에서는 빌드·테스트까지만 수행하고 `server/mods`에는 배포하지 않았다.
- 최종 메인 JAR은 `mythictrpg-main/build/libs/mythictrpg-1.0.0.jar`, SHA-256은
  `D41CACA761995DB4EC55948C137521B01AAD51E9DB891A8E0654C9115EC7F1EE`다.
# 2026-09-01 — 04 선택적 AI 사건 연출 보조 구현

- Presentation 데이터에 종류, 생성 정책, 정사 전달 정책, fact 공개 요청, 연기 태그, Hook, 오프라인 정책을 추가했다.
- 서버가 공개 정책을 통과한 자연어 의미만 담는 bounded Snapshot을 만들고 optional AI provider에 전달한다.
- AI가 없거나 실패하면 기존 작성 fallback을 사용하며, FACT_BEARING 의미는 별도 시스템 문구와 Knowledge로 확정한다.
- 대화 중 Story Context는 화자 Knowledge만 shortlist하고, Hook은 실제 ID 대신 세션 바인딩 opaque token으로 제안한다.
- `story_event_hook`은 플레이어 확인 뒤 세션·화자·TTL·revision·조건을 재검증한다.
- `mythai-ai-response` Ollama 구현과 기존 대화 어댑터 연결을 완료했다.
- 검증: MythicTRPG GameTest 186/186, 양 모듈 compile 성공.
- 운영상 남은 확인: 실제 Ollama/Gemma 대사 품질 스모크 및 별도 영구 Journal UI.
- 상세: `mythictrpg-main/docs/STORY_AI_PRESENTATION_GUIDE.md`

## 22. 2026-09-02 — 개인 화폐와 전역 공유 상점 구현

### 구현 완료

- 플레이어 UUID별 `골드` 잔액을 `mythictrpg_currency` SavedData에 저장한다. 시작 금액은 0,
  최대 보유량은 9,000,000,000이다.
- `/money`, `/shop buy`, `/shop sell` 명령과 구매·판매 클라이언트 화면을 추가했다.
- 상점은 `data/<namespace>/mythictrpg/shops/*.json`에서 로드하며 구매/판매 상점, 무제한 재고,
  1~64묶음 거래를 지원한다.
- 상품 ItemStack은 Minecraft data component까지 보존한다. 판매 시 ID와 component가 모두 같은
  기본 인벤토리 아이템만 제거한다.
- 잠긴 상품 해금은 `mythictrpg_shops` SavedData에 월드 전역으로 저장한다. 한 플레이어의 퀘스트
  보상으로 해금되면 모든 플레이어에게 표시된다.
- 통합 퀘스트 보상에 개인 `currency`와 전역 `unlock_shop_product` 타입을 추가했다.
- 퀘스트 보상에는 다음처럼 작성한다. `currency`는 완료 플레이어 개인에게 지급되고,
  `unlock_shop_product`는 이미 Datapack에 등록된 상품을 모든 플레이어에게 해금한다.

```json
{"type":"currency","amount":100}
```

```json
{
  "type":"unlock_shop_product",
  "shopId":"mythictrpg:witch_buy",
  "productId":"mythictrpg:witch_healing_potion"
}
```

- AI는 새 아이템·가격·상품을 자유 생성하지 않는다. 등록된 퀘스트 또는 보상 정의를 제안할 수
  있을 뿐이며, 실제 상품 존재 여부와 해금은 MythicTRPG 서버가 검증·저장한다.
- 기본 판매 콘텐츠로 광물 10종, 잠긴 구매 콘텐츠 예시로 마녀의 회복 물약을 추가했다.
- 관리자 잔액 get/set/add/remove 및 상품 list/unlock/lock 명령을 추가했다.

### 현재 지원하는 해금 경로와 남은 경계

- 지원: 퀘스트 완료 보상 `unlock_shop_product`와 관리자 `economy shop unlock` 명령.
- 지원: 게임 진행으로 새로운 퀘스트가 열리고 그 퀘스트 완료 보상으로 상품을 전역 해금하는 간접 흐름.
- 미연결: 월드 진행도 값, 신 해금, Story Fact, 관계 조건이 충족되는 즉시 상품을 자동 해금하는
  범용 조건 규칙. 전역 해금 저장·검증 API는 있으므로 후속으로 조건 소비자만 연결하면 된다.
- 기본 상품은 `unlockedByDefault: true`로 정의하며 관리자 명령으로 잠글 수 없다. 진행형 상품은
  `false`로 정의한 뒤 보상 또는 관리자 명령으로 해금한다.

### 검증과 남은 수동 확인

- `compileJava` 성공.
- 전체 필수 GameTest 190개 통과. 신규 테스트는 구매·판매 원자적 순서, 최대 잔액, 개인 잔액
  격리·영속성, 전역 상품 해금 영속성, 회복 물약 component 보존과 보상 JSON을 검증한다.
- 실제 클라이언트에서 상점 화면의 해상도별 배치와 버튼 조작은 수동 확인해야 한다.
- 이번 변경은 빌드·테스트 기준이며 `server/mods`에는 아직 배포하지 않았다.
- 최종 JAR은 `mythictrpg-main/build/libs/mythictrpg-1.0.0.jar`, SHA-256은
  `51EF22FAF7F2149183CA58173FBCDBBB3A9C859A9E2198331542B13CFBC18EE8`이다.
- 상세 스키마와 명령은 `mythictrpg-main/docs/SHOP_ECONOMY_GUIDE.md`를 기준으로 한다.

## 23. 2026-09-02 — 초기 기획 범위 제외 확정

- 천계어·악마어·요괴어·고대어 언어 시스템은 도입하지 않는다. 언어별 암호화 텍스트, 언어 습득·해독 루트, 언어 기반 식별 조건도 구현·콘텐츠 범위에서 제외한다.
- RisuAI는 사용하거나 참고 구현체로 채택하지 않는다. AI/LLM은 로컬 AI provider와 MythicTRPG/Content Registry 계약을 기준으로 독립 구현한다.

## 24. 2026-09-11 — OPELA 참고 관점 + Hybrid RAG 대화 개선

> 과거 실험 기록. 2026-09-12 LP 복원으로 서버 적용이 취소됐다. 아래의 구현·검증·배포 표현은 당시 실험본에 대한 기록이다.

### 구현과 이전 중단 복구

- 이전 작업은 최종 검증을 끝내지 못한 상태였다. 화자 ID·감정·평가 출력 계약과 독립 Java 검증의 Gradle 연결을 보완해 마무리했다.
- 변경 소유 모듈은 `mythai-ai-response`, `mythai-ai-content-registry`다. 이번 작업에서 `mythictrpg-main` 게임 코드, 기존 사용자 변경, `mine/mine` 원본, 서버 파일은 수정하지 않았다.
- 실제 운영 facade가 사용하는 adapter에 실제 신별 affinity → 관계 지침, 일시 감정, 출처·청중별 기억과 키워드/실제 임베딩 혼합 검색을 연결했다.
- 최종 수락된 발언의 기억 저장, 원문 근거 기반 후처리·정정, 재접속 복원, 늦은 분석의 최신 기억 덮어쓰기 방지, 참가자 변경 시 사적 히스토리 정리를 구현했다.
- 퀘스트 완료·평가와 한 플레이어 대상 자발적 대사는 읽기 전용 RAG를 공유한다. 다인 청중에는 개인 기억을 전달하지 않는다. 기존 Story 조회는 플레이어 공개만 보장하므로 2신 프롬프트에서는 해당 개별 문맥을 생략한다.
- 포르투나·데메테르의 정체성·성격·ID를 유지하면서 자기 감상·자연스러운 회상·질문 반복 억제 지침을 추가했다. OPELA 데이터 복사·학습이 아니라 참고 관점의 자체 구현이다.
- 관찰용 품질 평가는 기록만 남긴다. 평가 점수로 대사 반복 재생성, 영구 호감도 변경, 보상 실행을 하지 않는다.

### 검증

- AI 응답 `ragLiveTest build --no-daemon` 성공. `build/check`에 독립 `ragTest`를 연결했다.
- 자동 검증 **67개 항목 통과**: 기억 영속성·격리·검색·정정·장애 처리·출력 스키마, 생성 adapter의 구조 검사 9개 포함.
- 로컬 Ollama 검증 **18개 추가 항목 통과**: 합계 85. 실제 `bge-m3` 의미 검색, `gemma4:12b`의 포르투나·데메테르 × 적대·신뢰 관계 대화 4쌍, 화자/청중/감정 ID, 기억 추출과 유효 품질 점수를 확인했다.
- “수영 불가”와 “깊은 강을 건너는 걱정”의 유사도 약 0.654: 키워드 검색은 놓치고 의미 검색은 찾았다. 감정은 이 비교에서 적대 `WARY`, 신뢰 `CONCERNED`로 출력됐다. 모델의 확대 해석·부자연스러움 가능성은 남는다.
- 콘텐츠 레지스트리 빌드 성공. 변경 프로필 JSON, 관계 9단계, 기존 퀘스트 목록 참조와 문서 링크를 확인했다. 활성 서버 ResourceManager 로딩 검증은 하지 않았다.
- 새 AI JAR의 핵심 클래스 포함, 중복 ZIP 항목 없음, 구 게임 `MythicTrpg.class` 미포함을 확인했다.
- 상세 보고: `mythai-ai-response/build/reports/rag-regression.json`, `rag-live-structured.json`. 실제 운영 시스템 프롬프트·요청 스키마·260 출력 토큰의 축소 문맥 테스트이며 인게임 종단 검증은 아니다.

### 배포 후보와 미실시 항목

- AI 응답: `mythai-ai-response/build/libs/mythai_ai_response-0.1.0.jar`
  - SHA-256: `7F97AC9F2414A648A53E2C7A6897135FAF10DD5915E8C0DF3B1B0F02C6E58E64`
- 콘텐츠: `mythai-ai-content-registry/build/libs/mythaiaicontent-0.1.0.jar`
  - SHA-256: `BEA3D41802A4B7E7DDED28BB2A3167ED3881F6A90DBAC3709005D3F8A67AA90F`
- 서버 JAR 교체는 2026-09-11에 완료했다. 기존 두 JAR은 `server/backups/ai-rag-upgrade-20260911-185936/`에 백업했고 새 JAR을 `server/mods`에 배치했다. 서버는 실행하지 않았으며, 재시작·전체 GameTest·실제 인게임/HUD·보상 확인 UI 검증은 아직 미실시다. 두 모드는 서버 전용이며 클라이언트 사본은 건드리지 않았다.
- `bge-m3`는 로컬 Ollama에 설치했다. 대화 모델 `gemma4:12b`와 기존 서버 설정은 그대로다.
- 새 저장소는 월드 아래 `mythictrpg-ai-memory/memories-v2.json`, 설정은 첫 사용 때 `config/mythictrpg/ai-memory.json`에 생성한다. 기존 `server/mythictrpg-ai-data/memories.json`은 보존하며 출처·공개 범위가 불분명해 자동 이관하지 않는다.
- 모델 후처리 부하, 제한된 기억 보관량, 로그 누적, 게임 사건의 범용 기억화 및 모델 품질 보정 등 제약은 [상세 가이드](../mythai-ai-response/AI_MEMORY_RAG_GUIDE.md)에 기록했다.

### 서버 계약 동기화 및 부팅 검증

- AI 응답 JAR 배포 뒤 서버의 구형 `mythictrpg-1.0.0.jar`에 `StoryAiPresentationContracts.Provider`가 없어 `NoClassDefFoundError`로 부팅이 중단되는 것을 확인했다.
- `mythictrpg-main`의 현재 작업본을 `build --no-daemon`으로 다시 검증하고, `mythictrpg-main/build/libs/mythictrpg-1.0.0.jar`을 `server/mods`와 `server/client-required-mods`에 동일하게 배포했다. 세 파일의 SHA-256은 `51EF22FAF7F2149183CA58173FBCDBBB3A9C859A9E2198331542B13CFBC18EE8`이다.
- 교체 전 서버·클라이언트 JAR은 `server/backups/mythictrpg-contract-sync-20260911-222813/`에 각각 보존했다. 이전 파일 SHA-256은 `B8938C910EB73313117225E87E8886AF689DD61279BBDBB00873E67C2CC4AD52`이다.
- 최종 배포본으로 전용 서버를 실제 실행해 MythicTRPG, MythAI Response, Content Registry와 정적 콘텐츠를 로드하고 `Done (0.883s)`에 도달했다. 로그에는 `ERROR`/`FATAL`이 없었다. 검증 뒤 콘솔 `stop`으로 모든 월드를 저장하고 정상 종료했으며 현재 서버와 25565 포트는 닫혀 있다.
- 실제 플레이어 접속, 인게임 AI 대화/HUD, 장기 기억 저장·재접속 복원은 아직 수동 종단 검증이 필요하다.

## 25. 2026-09-11 — LP 보존 후 AI 대화 튜닝 적용

> 과거 실험 기록. 2026-09-12 사용자가 LP 복원을 선택했다. 아래의 설정·서버 해시·검증 수는 현재 LP 버전에 적용하지 않는다.

- 사용자는 개선 전 백업을 **LP**라고 명명했다. `server/backups/LP/`에 최초 AI 응답·콘텐츠 JAR을 해시와 함께 보존했고 최초 원본 백업도 유지한다. LP 폴더에 새 빌드를 덮어쓰지 않는다.
- `dev-tools/Restore-LP.ps1`은 LP/게임 JAR 해시 검증, 실행 서버 확인, 교체 직전 추가 백업을 거쳐 두 AI JAR과 출력 한도(260)를 복원한다. 월드·플레이어·v2 기억은 보존한다. 실제 복원 스크립트 실행과 LP 서버 `Done (0.979s)`를 확인한 뒤 다시 튜닝본을 배포했다.
- 튜닝 전 RAG 배포본·설정·소스는 `server/backups/pre-dialogue-tuning-20260911/`에 별도 보존했다. LP와 이 백업은 서로 다른 버전이다.
- AI 응답 모듈에서 중복 프롬프트 축소, 최근 대화와 겹치는 기억 제외, 직접 인사 저장 생략, 세션 중 후처리 억제, 실제 품질 평가 요청 비활성화, Proposal 생략 가능·최대 1개, compact JSON을 적용했다. 서버 출력 상한 420, 기억 후보 3개, 기억 문맥 1200자, 추출 주기 8턴, 세션 종료 후 유휴 30초로 설정했다.
- 자동 회귀 79항목과 현재 기록 3턴의 튜닝 전후 실제 모델 생성을 검증했다. 최종 생성-only 비교에서 튜닝본 3/3 JSON 정상, 입력 토큰 약 19~21% 감소. 응답 시간은 일부 빨라지고 일부 느려졌으며 과장된 말투도 남아 있다. 자연스러움·인게임 속도의 전면 개선으로 보고하지 않는다.
- 최종 서버 AI JAR SHA-256은 `D5CECCB99F08A67EF1CAA80EC52CAE6F2E6ABD879935EF5BF083820C2B686BD1`. 콘텐츠·게임 모드는 기존 RAG 호환본이다. 최종 서버 `Done (1.219s)`와 ERROR/FATAL 없음 확인 후 콘솔 stop으로 저장·종료했다.
- 실제 인게임/HUD/행동 실행은 사용자의 시험이 남았다. 상세 결과와 복원 안내: [LP_DIALOGUE_TUNING_REPORT.md](../docs/LP_DIALOGUE_TUNING_REPORT.md).
- 최종 프롬프트 helper로 실제 의미 검색·포르투나/데메테르의 적대/신뢰 관계·화자/감정/추출을 확인한 `ragLiveTest`도 추가 18항목 통과했다(자동 79+실모델 18=97). 생성-only 비교 3쌍은 이 카운트와 별개다.

## 26. 2026-09-12 — LP 서버 바이너리 복원 (후속 소스 복원은 27절)

- 사용자 요청으로 `dev-tools/Restore-LP.ps1`을 실행해 `server/mods`의 AI 응답·콘텐츠 JAR을 LP 보존본과 동일하게 복원했다. 출력 한도는 260, 대화 모델은 기존 `gemma4:12b`다.
- 9월 12일 이 LP 복원본으로 서버를 부팅해 `Done (0.702s)` 및 ERROR/FATAL 없음을 확인하고 콘솔 stop으로 저장·종료했다. 당시 인게임 대화는 새로 실행하지 않았다. 이 기록은 후속 기억 시험 JAR의 부팅 검증이 아니다.
- AI 응답 SHA-256: `DBC7C095943817248E05CD86B58966DE8DF74075A54BB8474134AE7C68D8147F`.
- 콘텐츠 SHA-256: `38B8461C13619569E477BB24AB85F9BFAD317998402FEA3E5ACF0019C6CC8CBA`.
- 게임 JAR은 LP와 호환 검증한 `51EF22FAF7F2149183CA58173FBCDBBB3A9C859A9E2198331542B13CFBC18EE8`을 서버·client-required-mods에 유지한다. 게임 자체를 과거 버전으로 되감지 않았다.
- OPELA 참고 확장, v2 Hybrid RAG, 신규 감정·품질 평가, 1제안 제한 등은 LP에 적용된 기능으로 설명하지 않는다. `ai-memory.json`은 보존하되 enabled=false로 명시했고 LP에서는 읽지 않는다. 새 기억·월드·플레이어 데이터는 삭제하지 않았다.
- 교체 직전 튜닝 JAR·설정·문서는 `server/backups/before-LP-restore-20260912-170306-827/`에 보존했다. LP 원본 및 기존 실험 백업도 유지한다.
- 이 단계는 서버 배포만 복원해 개발 소스에는 실험본이 남아 있었다. **이 불일치는 후속 27절에서 해소했다.** 실험 재개는 별도 사용자 요청을 따른다.
- 운영 안내는 [LP_RUNTIME_GUIDE.md](../docs/LP_RUNTIME_GUIDE.md), 과거 튜닝 결과는 [보관용 보고서](../docs/LP_DIALOGUE_TUNING_REPORT.md)로 구분했다.

## 27. 2026-09-12 — LP 개발 소스 복원 및 동일 JAR 재빌드

- 사용자 요청에 따라 AI 응답의 OPELA/RAG 오버레이·추가 기억/감정/스키마 코드·실험 테스트를 활성 소스에서 제거하고, LP에 맞게 기존 호출부·프롬프트·신 프로필 2개를 복원했다. `build/generated`만 고친 것이 아니라 원본 Java와 `build.gradle`을 수정했다.
- LP에 없던 AI 측 다중 신 운영 facade 확장·동적 관계 컨텍스트·스토리 연출 연결도 실험 소스 백업에 보존한 뒤 LP 상태로 복원했다. MythicTRPG 게임 소스와 권한/저장소는 수정하지 않았다. LP 운영 facade는 플레이어 1명 + 신 1명이다.
- 두 모듈의 기존 `build` 전체를 별도 백업으로 이동하고 새로 빌드했다. 응답 265개·콘텐츠 69개 JAR 파일 엔트리가 LP와 바이트 단위로 일치하며, **JAR 전체 SHA-256도 각각 `DBC7C095943817248E05CD86B58966DE8DF74075A54BB8474134AE7C68D8147F`, `38B8461C13619569E477BB24AB85F9BFAD317998402FEA3E5ACF0019C6CC8CBA`로 원본과 같다.** 재빌드가 실험본을 되살리지 않는다.
- 새 읽기 전용 `dev-tools/Test-LPBuild.ps1`으로 전체 엔트리 비교를 수행했고, 실험 JAR을 넣으면 실패하는 역검사도 통과했다. 기본 Gradle test는 NO-SOURCE이며, 과거 RAG 테스트 97개를 이번 검증으로 인용하지 않는다.
- 복원 직전 소스·문서·빌드 출력: `server/backups/before-LP-source-restore-20260912/`. 검증한 LP 소스·필수 빌드 입력: `server/backups/LP-source-20260912/`. 원래 LP JAR은 덮어쓰지 않았다.
- 이번 작업은 소스·빌드·문서 복원이다. 서버는 기존 LP JAR을 유지하며 새 실행/교체·실제 LLM/인게임 검증은 하지 않았다. 모델·설정·월드·플레이어·기존 및 v2 기억 데이터를 바꾸지 않았다. 세부 재현·검증 안내는 [LP_SOURCE_RESTORE.md](../docs/LP_SOURCE_RESTORE.md).

## 28. 2026-09-14 — 승인된 기억·소문 기반 구현과 사용한도 중단 복구

> 아래는 기반 구현 완료/서버 배포 전 기록이다. 후속 PERSONAL 서버 세팅은 29절을 따른다.

- 9월 13일 "진행해" 승인 후 게임 전체 소스·AI 소스·필수 빌드 입력·서버/클라이언트 JAR·AI 설정을 `server/backups/before-memory-rumor-foundation-20260913-223016-887/`에 보존했다. 906개 파일 해시를 재검증했고 기존 `LP-source-20260912` 298개 및 원본 LP도 일치한다. 기존 사용자 미커밋 게임 변경을 되돌리지 않았다.
- 사용한도로 남아 있던 AI 신규 JAR 빌드, 개인 기억 테스트, 격리 GameTest, 문서 갱신을 이어서 완료했다. 테스트 런타임 Gson 누락·새 GameTest 임시 플레이어 미정리·중복 저장소 작성자 보호를 보완했다.
- 게임은 별도 월드/상호작용/generation/신/플레이어/청중 계약과 소문 SavedData를 소유한다. 개인 발언과 NPC 발언은 AI 저장소에 출처별로 기록하고, 로컬 권한 선필터 검색 결과를 분류/생성에 공유한다. 프롬프트 참고는 직접 기억+소문 합계 최대 3개이며 추가 분석 LLM·임베딩은 없다.
- 소문 상태 머신은 대상별 전서구 epoch, 관찰→주장→수신을 분리한다. A 차단이 B에 영향을 주지 않으며 이미 받은 소문과 미전달/늦은 작업을 구분한다. 실제 목격/전서구 엔티티 연결이나 자동 수식어 생성은 아직 없다.
- 모드는 재시작형 OFF/PERSONAL/RUMOR_TEST, 기본 OFF다. RUMOR_TEST에서는 AI Proposal 실행·NPC 방문 퀘스트 진척·AI 신 식별을 억제하고 시험 기억 경로도 분리한다. FTB·게임 보상 소유권·정적 신 프로필·레거시 AI 입력은 변경하지 않았다.
- 새 산출물은 게임 `mythictrpg-1.0.1.jar` / AI `mythai_ai_response-0.1.1.jar`; 콘텐츠는 LP 0.1.0. 새 AI는 게임 1.0.1 이상을 요구한다. **현재 소스를 재빌드하면 LP 원본이 아니라 새 기반 버전이 나온다.** 실제 서버에는 계속 기존 LP가 있다.
- 검증: 소문 39개 검사, AI 기억 656개 검사(6개 동시 작성 스레드·240건 순서/격리 및 개인 기억의 다른 신 명의 발화 차단 포함), 격리 NeoForge 필수 GameTest 192개 전부 통과. 새 JAR 중복 클래스 0, 메타데이터/의존성 검사 통과. 보호 대상 서버·클라이언트·AI 설정·콘텐츠/레거시 306개 파일 불변. `Test-MemoryFoundationRelease.ps1`으로 재검사 가능하다.
- 실제 서버 JAR/설정 교체·서버 실행/중지·실제 LLM 요청·인게임 확인은 하지 않았다. 자동 테스트 결과는 자연스러움/응답시간 개선의 실측 증거가 아니다.
- 남음: 자동 중요 기억 추출/정정·게임 완료 사건 연결·보존 정리, 실제 전서구/귀속·재생성·신화권 정보망·평판 효과 콘텐츠 선택, 운영 전 LLM/인게임/지연 비교와 별도 배포 승인. 새 SavedData를 사용한 월드의 LP 다운그레이드 시험도 남는다.
- 실제 스키마·경로·설정 예제·명령·복원 순서는 [LP_MEMORY_FOUNDATION_GUIDE.md](../docs/LP_MEMORY_FOUNDATION_GUIDE.md)에 모았다. 소스 차이 목록을 기반으로 선택 복원하며 루트 reset/전체 덮어쓰기 또는 두 AI JAR만으로 전체 복원이 끝난다는 안내를 하지 않는다.

## 29. 2026-09-14 — PERSONAL 서버 테스트 배치와 미완료 마무리

- 사용자 "서버에서 테스트 해볼 수 있게 세팅" 요청으로 게임 1.0.1 / AI 0.1.1을 `server/mods`에 배치하고 `server/client-required-mods`의 게임도 같은 1.0.1로 맞췄다. 콘텐츠 0.1.0·수정 FTB·데이터팩·기존 AI 모델/260토큰 설정·월드는 유지했다.
- `server/config/mythictrpg/ai-memory-foundation.json`을 schemaVersion=1, mode=PERSONAL로 새로 만들었다. 코드 기본값은 OFF지만 현재 서버 설정은 ON이다. AI 행동을 막는 RUMOR_TEST 또는 소문 자동 전파를 켜지 않았다.
- 실행 인자를 확인해 Java는 Gradle 빌드 데몬뿐이고 25565 수신 프로세스가 없을 때 교체했다. 서버를 실행·중지하지 않았다. 실제 런처 `mods`는 변경하지 않았으므로 사용자가 구 MythicTRPG를 폴더 밖에 보관하고 클라이언트용 1.0.1로 교체해야 한다.
- 배포 직전 29개 파일/문서를 `server/backups/before-personal-memory-deploy-20260914-194200-226/workspace/`에 해시와 함께 보존했다. 기존 활성 JAR 3개는 같은 백업의 `retired-active/`로 옮겼으며 삭제하지 않았다. LP 원본·298개 LP 소스·906개 전체 소스 백업도 재검증했다.
- 이번 작업에서 게임/AI JAR 최신성 확인 및 소문 39개·AI 기억 656개 검사를 다시 통과했다. 이전 격리 GameTest 192개 기록을 이번 서버 부팅 검증으로 재사용하지 않는다. 새 게임 SHA-256은 `8EB113E84D83B62CDAA5C4DCE8EB03E951DEC8EFC4FC896D2B2727718C9C7327`, AI는 `0B7CDD6D26EA5B0805C88E62B374F5BDCC0A6908E167A28FF63EC51793AF656B`다.
- `Test-MemoryFoundationRelease.ps1 -ServerProfile PERSONAL`로 새 배포/설정/서버·클라이언트 해시·구 JAR 부재·LP 원본·변경하지 않은 의존성/기존 AI 설정 302개를 검증한다. 기본 LP 검사는 배포 전 상태를 기대하므로 현재 서버에는 사용하지 않는다.
- 사용한도 관련 후속 요청 시 남아 있던 문서/인수인계 동기화와 최종 배포 검사를 마무리했다. 9월 12일 부팅 이력이 잘못 28절 끝에 붙어 있던 것도 26절로 옮겨 신규 JAR을 이미 부팅했다고 오인하지 않게 했다.
- 실제 서버 부팅·클라이언트 접속·LLM/HUD·회상 품질/지연은 아직 미확인이다. 테스트는 일반 게임 대화로 시작하고 OP는 `/ai_memory status`, `/ai_memory list`를 확인한다. 직접 `/ai_test start`만 연 세션에는 운영 기억이 연결되지 않는다.
- 시험 절차/제한과 OFF/LP 복원의 차이는 [LP_PERSONAL_MEMORY_TEST_SETUP.md](../docs/LP_PERSONAL_MEMORY_TEST_SETUP.md)를 따른다. 신규 SavedData가 사용된 월드의 LP 다운그레이드 검증은 별도다. 이번 배치는 자동 기억 추출·실제 전서구·전파망·평판 효과 콘텐츠를 완성한 작업이 아니다.

## 30. 2026-09-14 — 모듈 패키지 충돌로 인한 시작 종료 수정

- 실제 20:35 로그의 `ResolutionException`으로 원인을 확정했다. 새 게임 계약과 AI의 레거시 코드가 `com.sande.mythictrpg.ai.memory` 패키지를 함께 내보냈다. 클래스 이름은 달라 기존 중복 검사가 놓친 Java 모듈 패키지 충돌이다. 외부 FTB/MixinExtras 모드를 수정할 문제가 아니다.
- 수정 전 LP 원본·298개 LP 소스·906개 전체 소스·29개 배포 전 파일 해시를 재검증했다. 새 `server/backups/before-memory-package-fix-20260914-203705-251/`에 수정 직전 소스/JAR/설정/문서/오류 로그 673개를 해시와 함께 보존했다. 오류 JAR 3개도 `retired-active/`로 옮겼으며 삭제하지 않았다.
- 게임 계약 네 클래스를 `com.sande.mythictrpg.ai.memorycontract`로 옮기고 생산자/소비자 참조를 함께 수정했다. 게임 1.0.2 / AI 0.1.2로 구분하고 AI의 게임 의존성도 `[1.0.2,)`로 올렸다. DTO 필드/JSON/SavedData/기억 경로/권한과 PERSONAL 설정은 그대로다. 레거시 소스/JAR, 콘텐츠, FTB 및 월드에는 수정이 없다.
- 게임/AI 빌드 및 소문 39개·AI 기억 656개 검사 통과. 서버를 실행하지 않는 Java 21 `--validate-modules` 검사 통과. 새 `Test-ModPackageIsolation.ps1`은 실제 JAR의 패키지 겹침을 검사하며 보존한 오류 조합을 거절하는 회귀 검사도 배포 감사에 포함했다.
- 서버 게임/AI와 클라이언트 배포용 게임을 교체했다. 게임 SHA-256 `32C70B972640EE534AE5FF09FDBBA5578C863197EE9B3F9EFD6CA62253226124`, AI `DDB3CD2EFA525ED6613946AB397572A2B162758B1F19A99686FF7F9A6D996E88`. 실제 런처의 1.0.0/1.0.1 게임 JAR은 사용자가 1.0.2로 교체해야 한다.
- 사용자는 "서버 실행 없이 수정만"을 선택했다. 서버/GameTest 서버를 실행·중지하거나 실제 LLM 요청을 보내지 않았다. 확인된 패키지 오류는 해소했지만 실제 부팅과 후속 실행 단계까지 확인했다고 안내하지 않는다.
- [수정 상세](../docs/MEMORY_PACKAGE_FIX_20260914.md), [최신 합산 소스 차이](../docs/memory-foundation-current-changes-20260914.csv)를 추가했다. 최초 36개 목록은 이력이며 새 패키지 경로가 포함된 목록으로 선택 LP 복원한다. 원본 백업 덮어쓰기/전체 Git reset을 하지 않는다.

## 31. 2026-09-14 — 실제 대화 분석 후 개인 기억 회상 개선

- 사용자 21:12~21:15 포르투나 로그에서 수영 불가 발언의 저장/재시작 후 생성 전달은 확인했으나 답변 활용이 약했다. 바다 발언에는 기억이 검색되지 않았다. 사용자 개선 요청에 따라 최근 플레이어 문장 최대 2개의 맥락 보조 검색, 자기 귀환 시 6시간 내 이전 세션 발언 1개의 연결, 조건부 기억 활용 지침을 구현했다. 신/플레이어/월드/청중 격리를 유지한다.
- AI **0.1.3**만 서버에 배치했다. 게임/클라이언트 1.0.2, 콘텐츠 0.1.0, PERSONAL/gemma4:12b/260토큰/FTB/기존 기억·월드는 그대로다. 실제 런처 교체는 불필요하다. AI SHA-256 `CC5547CB643D5A1354828850ECDA6F3425D6245B24B2CD766F5CE2E86DC10F80`.
- 기억이 실제 있을 때 과거/현재의 연결을 짧은 잡담 선호보다 우선하되 성격/관계/게임 권한을 유지한다. 기록 시각·최신 정보 우선과 `MEMORY_RECALL` 로그도 추가했다. 거절된 `/ai_test start`가 활성 기억 연결을 끊던 문제를 수정했다. 추가 LLM 호출/임베딩/자동 분류·저장소 정정은 없다. 바다→수영 같은 의미 검색은 아직 제한된다.
- LP 원본/298개 LP 소스/906개 전체 소스 및 이전 백업을 재검증했다. 수정 직전 AI 소스/JAR/문서/설정/기억/로그 100개는 `server/backups/before-memory-recall-tuning-20260914-212335-935/`에 보존했고 기존 AI 0.1.2는 같은 폴더 `retired-active/`에도 보관했다. 삭제는 없다.
- 최종 `build` 성공, 기존 기억 656개+새 회상/연결 54개 = **710개 검사 통과**. 서버 전체 패키지 분리/Java `--validate-modules`/보호 파일 302개/배포 해시 검사 통과. 서버나 GameTest 서버는 실행하지 않았다.
- 생성-only A/B 도구 `memoryRecallReplay`는 Ollama 11434 미실행으로 연결 실패했다. 모델을 임의 시작하지 않았고 실제 생성 품질/지연/인게임 검증은 남았다. 구 0.1.2 사용자 부팅 기록을 새 0.1.3 검증으로 인용하지 않는다. 범위·시험·이번 튜닝만의 복원은 [회상 개선 기록](../docs/MEMORY_RECALL_TUNING_20260914.md), 전체 LP 선택 복원은 최신 합산 목록과 기반 가이드를 따른다.

## 32. 2026-09-15 — 로드맵 0~1단계 완료 및 후속 인수인계 보완 (미배포)

### 완료 범위와 현재 적용 상태

- 사용자가 승인한 범위는 LP 무결성 확인·현재 상태 별도 백업·공통 계약 정리·개인 회상 소스 수정·관련 컴파일/오프라인 검사다. **0~1단계의 해당 작업은 완료했으며 2단계는 시작하지 않았다.** 상세 구현/설정/명령은 [0~1단계 작업 기록](../docs/RECALL_STAGE01_20260915.md)을 단일 기준으로 읽는다.
- 개발 산출물은 `mythai-ai-response/build/libs/mythai_ai_response-0.1.4.jar`다. 실제 서버는 **AI 0.1.3 / MythicTRPG 1.0.2 / 콘텐츠 0.1.0, PERSONAL / gemma4:12b**를 유지한다. 게임·콘텐츠·FTB·레거시 소스, 기존 배포 JAR·서버 설정·기억·월드는 이 단계에서 변경하지 않았다.
- [공통 계약](../docs/ACTION_OBSERVATION_CONTRACT_20260915.md)에 사건 ID/순서/중복 처리, 주시 자격과 실제 관찰의 구분, 관찰 근거·출처·공개 가능 범위·재검증 경계를 정리했다. **게임 DTO·상세 행동 원장·주시 시스템을 구현한 것은 아니다.** 기존 Snapshot/Proposal·보상 소유권과 FTB 연동을 유지한다. 전서구 사냥으로 막는 소문은 해당 플레이어 대상이며 다른 플레이어 전체의 소문이나 별도 직접 관찰을 일괄 차단하지 않는 설계를 유지한다.
- 개인 회상에는 후속 질문의 주제/원래 질문 시점 유지, 허용된 원문·계획·시간 단서 조회, 저장 대기 중 읽기, 제한된 비동기 검색과 세션/청중/근거 재검증을 추가했다. 회상 실패·불확실·복수 근거를 구분하는 응답 지침을 기존 분류/생성 경로에 연결했다. 새 기억 전용 LLM 호출·자동 추출/정정 루프는 추가하지 않았다.
- 실행 폴더의 `config/mythictrpg/ai-recall.json`이 없으면 **기존 회상 경로**다. [설정 예제](../docs/examples/ai-recall.example.json)는 `recallV2=false`, `timeBasis=UNSPECIFIED`이며 서버에 복사하지 않았다. 신규 경로를 쓰려면 이후 승인된 개발 JAR 배포와 설정이 모두 필요하다. 설정은 첫 승인 대화 연결 시 읽으며 hot reload가 없다. 기능 OFF와 LP 복원은 다른 작업이다.

### 백업·복원·검증 근거

- 구현 전과 최종 감사에서 LP 원본 JAR **3개**, `LP-source-20260912`의 **298개**, `before-memory-rumor-foundation-20260913-223016-887`의 **906개** 해시 일치를 확인했다. 298개는 LP AI 필수 소스/입력이지 전체 게임 소스가 아니며, 906개 보존본에 기억 기반 도입 전 게임 전체 개발 소스가 포함된다. LP 백업을 덮거나 삭제하지 않았다.
- 이번 수정 직전 상태 **2,177개**는 `server/backups/before-recall-stage01-20260915-183337-145/`에 별도로 보존했다. [파일 manifest](../server/backups/before-recall-stage01-20260915-183337-145/file-manifest.csv)와 [LP 감사 기록](../server/backups/before-recall-stage01-20260915-183337-145/lp-audit.csv)을 확인한다. 사용자 미커밋 소스·빌드 입력·배포본·설정·개인 기억/로그 등을 포함하지만 `.git`·캐시·전체 월드 청크/플레이어 진행 데이터의 완전 백업은 아니다.
- 당시 게임 컴파일/소문 **39개**, AI 컴파일·JAR·check 및 기억 **656개 + 기존 회상 54개 + 신규 121개**, 합계 **870개 오프라인 검사**를 통과했다. 이는 모델 답변 정확도나 인게임 시나리오 870개를 뜻하지 않는다. 1/4/6개 동시 작성·조회 격리도 오프라인에서 확인했으며 실제 6명 서버 부하 검증은 아니다.
- 새 AI 후보와 기존 모드 **7 JAR / 238 패키지**의 split-package 없음, 중복 ZIP 엔트리·신규 클래스·버전/의존성을 검사했다. 보호 대상 소스/배포본/설정도 대조했다. 당시 검색 시간 측정은 제한된 오프라인 표본의 진단이며 전체 응답 지연 개선을 보장하지 않는다.
- [단계 변경 목록](../docs/recall-stage01-changes-20260915.csv)은 수정 10개/신규 9개, 총 19개의 완료 시점 기록이다. 이번 단계만 되돌릴 때는 직전 백업과 이 목록을, LP까지 되돌릴 때는 [기반 복원 가이드](../docs/LP_MEMORY_FOUNDATION_GUIDE.md)의 0.1.3까지의 합산 목록도 함께 검토한다. 새 파일과 기존 사용자 변경을 구분하며 전체 폴더 초기화나 Git reset으로 복원하지 않는다. 월드 LP 다운그레이드 호환성은 미검증이다.

### 미검증·미확정 사항과 다음 단계

- 서버/GameTest 서버 실행, 실제 LLM 호출, 모델 설치·교체, JAR 배포는 이 단계에서 수행하지 않았다. 실제 신별 말투·관계 반영·자연스러움·회상 품질, 서버 부팅/HUD, 전체 응답 시간은 별도 승인 후 확인해야 한다. 현재 서버에서 이미 신규 회상이 적용됐다고 안내하지 않는다.
- “어제·내일”의 기준(한국 현실 날짜/게임 날짜)과 신의 주시 범위는 질문했으나 이 기록 시점에 미응답이다. `REAL_KST`는 선택 가능한 구현일 뿐 확정 콘텐츠 규칙이 아니며 게임 날짜 해석은 미지원이다. 행동 기록 정밀도·보존 기간·주시 임계값도 임의로 확정하지 않았다.
- 검색은 원문/표면 표현·시간 단서 중심이다. 임베딩 의미 검색, 자동 기억 정리/정정·영구 중요도 판정, 상세 행동 원장·실제 전서구/전파망·평판 효과는 미완료다. 기존 개인 기억/소문 기반이 있다는 사실과 후속 콘텐츠 완성을 구분한다.
- 관계 입력 감사에서 LP facade의 QuestReward/SocialAuthority Provider 설치가 non-null 확인만 하고 Adapter는 R_NEUTRAL/E_NEUTRAL로 시작하는 제한을 확인했다. 별도 관계 DB나 임의 매핑을 만들지 않았으며 실제 관계 읽기 연결은 로드맵 4단계의 독립 패치/검증 대상으로 남겼다.
- [최신 로드맵](../docs/ACTION_OBSERVATION_MEMORY_ROADMAP_20260915.md)의 **2단계 최소 행동 원장에 착수할 기술적 출발점은 준비됐다.** 이는 1단계 인게임 품질 수용을 의미하지 않는다. 다음 요청에서 기존 묶음 알림/퀘스트 실행을 보존하는 범위와 미확정 정책을 확인하고, 승인 없이 2단계를 자동 시작하지 않는다.

### GPT-6 참고 논의와 이번 문서 후속 반영

- 사용자는 작업 시 GPT-6의 응답 방식을 참고할 수 있는지 질문했다. 앞선 답변에서는 공개 가이드의 문맥/의도 연결, 충돌 지침 정리, 명확한 말투 지정을 설계 참고 방향으로 설명했다. 신의 성격·관계별 반응을 일반 비서 말투로 대체하자는 뜻이 아니다.
- 비공개 내부 구조 복제나 같은 수준의 응답 품질을 보장한 것이 아니며 **이 논의로 추가 코드 수정·GPT-6 API/모델 전환·실제 모델 호출을 수행하지 않았다.** 기존 0~1단계와 방향이 맞는다는 설명이고 실제 gemma4:12b의 자연스러움 비교는 미검증이다.
- 이번 사용자 요청에서는 빠져 있던 상세 인수인계와 위 후속 논의를 추가하고, 상단의 설계 당시 미승인 문구/서버 배포 기준 및 검증 지침을 현재 상태에 맞췄다. 개발/서버 AI JAR의 해시는 기존 작업 기록과 다시 대조했으며 서버 신규 회상 설정 파일이 없음을 확인했다. 소스·JAR·서버 파일은 변경하지 않았고 컴파일/870개 검사·LP 전체 감사는 재실행하지 않았다.
- 위 단계 변경 CSV의 `PROJECT_HANDOFF.md` 해시는 **이번 문서 보완 전 완료 시점** 값이다. 이 후속 편집으로 문서 해시가 달라지는 것은 정상이며 과거 감사 CSV를 덮어쓰지 않았다. 이번 후속 작업의 검증 범위는 인수인계의 로컬 링크·제목/형식이다.
