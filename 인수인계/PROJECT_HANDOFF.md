# Minecraft RPG Local AI NPC Engine — 프로젝트 인수인계

> **2026-09-23 최신 배포 — 원래 목표와 대화방 재통합:** 게임1.0.15 / AI0.1.16을 `server/mods`, 게임1.0.15를 `server/client-required-mods`에 설치했다. **56절**과 [구현·배포·미검증 범위](../docs/ORIGINAL_GOALS_REINTEGRATION_20260923.md)를 우선한다. 서버는 꺼진 상태였고 부팅하지 않았다. 실제 런처/게임 내 대화는 미검증. 05의 전체 RAW/SQLite·100GB·지식/검색 통합 완료를 뜻하지 않는다.

> **2026-09-23 개발 기록 — 원래 목표와 대화방 재통합:** 게임 **1.0.15 / AI0.1.16**. 방향별 동적 신 관계, 선택적 Story AI 연출/방별 공개 context·Hook, 청취한 신의 발언 기억, 플레이어1·신2의 Primary→선택적 Secondary를 연결했다. **55절**과 [구현·검증·남은 범위](../docs/ORIGINAL_GOALS_REINTEGRATION_20260923.md)를 우선한다. 이 개발 구현은 05의 전체 RAW/SQLite·100GB·지식/검색 통합 완료를 뜻하지 않는다.

> **2026-09-23 최신 개발 — `/ai_call`로 시험 시작 통합(미배포):** 사용자 선택대로 전체 신 ID만 받는다. `/ai_call <public|mobile|private> <on|off> <신 ID>...`가 채팅+HUD 대화방을 만들며 `/ai_test`와 `/mythroom open`은 안내만 한다. **54절**과 [명령 가이드](../docs/AI_DIALOGUE_TEST_COMMAND.md)를 우선한다. 게임1.0.14/AI0.1.15 빌드·오프라인592검사 PASS. 서버 설치본은 여전히1.0.13/0.1.14이며 실제 새 명령 배포·접속 확인은 하지 않았다. 아래 정지 상태/구 명령 표시는 당시 이력이지 현재 프로세스 상태 보장이 아니다.

> **2026-09-21 최신 배포 — 게임1.0.13 / AI0.1.14:** 사용자 요청으로 새 공개·비밀방과 기록·기억 연결 보완 JAR을 `server/mods`에 적용하고 `client-required-mods`의 게임 JAR도 맞췄다. **53절**과 [배포 결과](../docs/ROOM_RECORDING_RELEASE_20260921.md)를 먼저 읽는다. 배포 직전 오프라인1,250 checks 재통과, 기존 모드/월드/설정541개 백업·해시 검증 완료. 서버는 꺼진 상태로 유지하며 실제 부팅/클라이언트/LLM은 미검증이다. 콘텐츠0.1.0/설정은 그대로고 별도 데메테르 프로필 변경은 미배포다. 아래 구버전·미배포 표시는 각 작업 당시 이력이다.

> **2026-09-21 후속 — 대화 시험 명령 통합(소스, 미배포):** `/ai_test <on|off> <신 이름/ID>...`의 정책·지원 범위는 [새 명령 가이드](../docs/AI_DIALOGUE_TEST_COMMAND.md)와 **49절**을 따른다. `off`는 기존 기억 조회를 유지하되 이번 대화의 새 기억/소문/분석 로그를 남기지 않는다. 1~16신 등록, 현재는 한 턴 한 신 생성이다. 별도 M 설계의 전체 RAW/SQLite/연속 다중 발화 구현 완료와 혼동하지 않는다. 실제 서버는 기존 게임1.0.2/AI0.1.3 그대로다.

> **2026-09-20 최신 — 소문·회복 핵심 연결 완료(소스/오프라인):** **47절**과 [실제 연결·설정·남은 검증](../docs/SOCIAL_PIPELINE_COMPLETION_20260920.md)을 먼저 읽는다. 개발 게임 **1.0.11 / AI 0.1.12**. 일반 신 대화(비밀/차폐 제외)·작성된 중요 사건 → 의미 후보/게임 발행·전달 → 신별 인식 → 실제 해명 검토·회복 → 후속 대화 소비를 연결했다. 오프라인 **11,782개 PASS**, LP/직전2365개 보존. 운영 OFF·RUMOR_TEST 제한·미배포이며 실제 전서구/별도 스폰·콘텐츠 매핑·모델/인게임 수용은 남았다. 아래 이전 ‘생산자/검토기/소비자 미연결’과 버전·시험 수는 당시 이력이다.

> **2026-09-20 최신 — 7단계 평판·회복 기반:** **46절**과 [상세 계약·남은 검증](../docs/REPUTATION_STAGE07_20260920.md)을 먼저 읽는다. 개발 게임 **1.0.10 / AI 0.1.11 유지**, 원본 호감도와 분리한 관계 판단 보정·회복 기반을 구현했다. 사용자 선택대로 **대화 설득 회복을 허용**하되 게임 검토가 필요하며 실제 검토기/자동 후보/대화 소비자는 미연결이다. 운영 OFF, LP/직전2347개 보존, 오프라인9,254개 PASS. 전체 운영 수용은 미완료이며 서버·LLM 실행/설정 변경/배포 없이 이번 범위에서 멈춘다. 아래 이전 버전·미착수 문구는 당시 이력이다.

> **2026-09-20 최신 — 6단계 후속 연결 기반:** **45절**과 [구현·미완료 기록](../docs/RUMOR_STAGE06_IMPLEMENTATION_20260920.md)을 먼저 읽는다. 전서구 엔티티/스폰 이벤트는 추후 제작하라는 사용자 요청에 따라 자동 스폰·재생성 OFF, 실제 기존 개체의 연결 API·목격/소문/수신/대사 경계를 게임 **1.0.9 / AI0.1.11**에 구현했다. 소스/오프라인 범위이며 **실제 엔티티·관측 생산자·자연어 후보 자동 판단·운영 수용은 남아 있다**. LP/직전2329개 보존, 서버 설정/배포/실행 없음, 7단계 미착수. 아래 ‘대기/미착수’는 해당 시점 이력이다.

> **2026-09-20 최신 — 6단계 요청 선행 점검/결정 대기:** **44절**과 [점검 기록](../docs/RUMOR_STAGE06_PREFLIGHT_20260920.md)을 먼저 읽는다. 개발 게임1.0.8/AI0.1.10은43절 모델 연결까지 포함한다. LP/새 snapshot2326개를 보존했고 선행 오프라인3938개를 재통과했다. 실제 Minecraft 종단·다인 운영 수용은 미완료다. 전서구 사냥 귀속은 사냥자가 아니라 **관찰 대상**으로 확정, 개발 시험/자동재생성 범위와 소문 처리 제안은 답변 대기. 새6단계 소스·배포·실행은 아직 하지 않았다.

> **2026-09-20 최신 소스 — 1~5단계 정책 적용·오프라인 검증:** 개발 게임 **1.0.8 / AI 0.1.9**, 컴파일·총 **4,608개 오프라인 검사 PASS**. 주시 보상/개인 획득, 차폐와 허용 활동 요약, 출처별 채굴 인정 실적, 중요 전투·퀘스트·발전 과제 기록, 개인 회상을 연결했다. [구현 계약·남은 검증](../docs/MEMORY_POLICY_IMPLEMENTATION_20260920.md)과 **42절**을 먼저 읽는다. LP/직전 작업 백업 보존, 서버 **게임 1.0.2/AI 0.1.3/콘텐츠 0.1.0** 유지. 서버/GameTest·실제 모델·설치·배포·6단계는 이번에 하지 않았다. 아래 ‘문서만’/구버전 문구는 해당 시점 이력이다.

> **2026-09-20 최신 기억·주시 설계 — 문서만 반영:** 사용자가 기존 메인/첫 완료 자동 주시를 **퀘스트 보상 ‘OOO의 주시’의 실제 개인 지급→획득 기록**으로 변경했다. 기본/외부 모드 발전 과제도 전부 기록한다. 자연스러운 기억 기준을 위임받아 보완했고 상위 존재 신전의 신별 차폐/완전 차폐 공간 방향을 반영했다. [단일 정책](../docs/ACTION_RECORDING_POLICY_20260917.md)과 **41절**을 우선하며 39절의 메인 완료 기준은 이전 설계다. 보상 타입/관측/확장 업적·기억 정책 소스는 미적용이며 6단계 미착수다.

> **2026-09-20 최신 퀘스트 개발:** 게임 **1.0.7 / AI 0.1.8**에 등록형 퀘스트의 SOLO/GROUP/COMPETITIVE/RANKING, 직접 다인 대화 합류, 전원 동의, 개인 목표와 공동 정산을 추가했다. 상세 설정·한계는 [참여 유형 가이드](../mythictrpg-main/docs/QUEST_PARTICIPATION_GUIDE.md), 검증/배포 상태는 **40절**이다. 운영 서버와 기존 퀘스트 정의/월드는 변경하지 않았다. 아래 9/17 버전·미실행 문구는 당시 이력이며 기억 로드맵/주시 정책의 추가 구현을 뜻하지 않는다.

> **2026-09-17 설계 이력 — 주시 시작 조건은 후속 41절로 대체:** 일반 행동 통계 우선과 채굴 인정 실적 규칙(38절)에 더해 **전투 상세 기록은 네임드 이상/제작 전투 콘텐츠의 결과, 퀘스트·업적은 전부, 장소는 좌표·바이옴**으로 확정했다. 공용 해금→개인 조우(지역·아이템·행동·확률 또는 연계 퀘스트)→그 신의 메인 퀘스트 완료→원칙적 지속 주시를 반영했다. 자연스러운 일상 관찰은 허용 구간의 활동 요약으로 설계했다. 단일 기준은 [기록·실적·주시 정책](../docs/ACTION_RECORDING_POLICY_20260917.md), 최신 근거/남은 질문은 **39절**이다. **시작 대상은 메인 퀘스트(입문 메인 포함) 완료로 확정됐고, 수주/사이드 완료는 제외한다. 새 정책은 소스/서버에 적용하지 않았다.**

> **2026-09-17 최신 소스·검증 상태: 5단계 남은 개인 기억 소스 연결·오프라인 검사 완료, OFF·미배포. 전체 운영 수용 완료는 아님.** 상세는 **37절**과 [후속 구현 기록](../docs/MEMORY_STAGE05_COMPLETION_20260917.md). 개발 게임 **1.0.6/AI 0.1.7**, 서버 **게임 1.0.2/AI 0.1.3/콘텐츠 0.1.0** 유지. 실제 Ollama backend 소스·영속 벡터·개인 회상 소비·유휴 원문 정리/정정 후보·공통 모델 입장을 연결했으나 모델/서버는 실행하지 않았다. 새 정책의 선별/인정 실적 소스 적용과 4~5단계 실제 품질/부하/호환 검증은 남았으며 **6단계는 시작하지 않았다**. 아래 날짜별 최신/완료 문구는 그 시점 이력이다.

> 이 문서는 새 에이전트/개발자가 작업을 시작할 때 가장 먼저 읽는 문서다. 문서와 실제 코드가 다르면 **실제 코드와 빌드 결과를 우선**한다.

> 2026-09-15 최신 개발 상태: 승인된 **로드맵 4단계 관찰→대화의 소스·오프라인 연결 완료, 미배포**. 상세는 **35절**과 [4단계 기록](../docs/EXPERIENCE_STAGE04_20260915.md)이다. 게임 개발 **1.0.5**, AI 개발 **0.1.5**, 실제 서버 게임 **1.0.2/AI 0.1.3/콘텐츠 0.1.0** 유지. 기본 OFF인 관리자 시험 주시·생명주기·90% 용량 알림·현재 게임 대화의 ExperienceLease를 연결했고 오프라인 1,630개 검사 통과. 관계 조건 자동 주시는 임계값 미확정/비활성이다. 포르투나→zaqGlGlT는 서버 한정 시험 명령만 준비했으며 **현재 서버에서 활성화하지 않았다**. 실제 Minecraft/LLM/HUD·응답시간 검증은 남았고 **5단계 미착수**다. 아래 단계별 문구는 해당 완료 시점의 이력이다.

> 2026-09-15 0~1단계 완료 당시 기록: 승인된 로드맵 **0~1단계 소스·오프라인 작업 완료, 미배포**. 상세 인수인계는 **32절**, 구현·검사 근거는 [작업 기록](../docs/RECALL_STAGE01_20260915.md)을 따른다. 개발 AI **0.1.4**, 실제 서버는 **AI 0.1.3/게임 1.0.2/콘텐츠 0.1.0** 유지다. LP 보존·직전 상태 2,177개 백업·오프라인 870개 검사를 완료했다. 신규 회상은 기본 OFF이며 서버/GameTest/LLM/모델 설치/배포는 하지 않았다. 실제 자연스러움·전체 응답 시간은 미검증, 시간·주시 정책은 미확정이었다. 이후 진행한 2단계는 33절을 따른다.

> 2026-09-15 설계 이력: [행동 기록·신의 주시·기억·소문 로드맵](../docs/ACTION_OBSERVATION_MEMORY_ROADMAP_20260915.md)은 당시 상세 일상 기록 및 조건/관계 기반 주시 요구를 반영했다. 0~1단계는 32절, 2단계는 33절, 3단계는 34절, 4단계는 35절, 5단계 후속은 36~37절을 따른다. **기록 범위는 이후 38절의 통계 우선 정책으로 조정됐다.** 단계 이력은 새 정책 적용 완료나 6단계/배포 승인으로 해석하지 않는다.

> 2026-09-15 개인 회상 설계 이력: [사건 중심 장기기억·회상 개선안](../docs/EPISODIC_MEMORY_UPGRADE_DESIGN_20260915.md). 원문+정리 기억, 시간/후속 회상, 의미 검색, 관계 입력 점검, 게임 경험→소문의 상세안이다. 두 문서의 단계 번호는 다르므로 로드맵의 대응표를 따른다. 당시에는 상대 시간 기준이 미응답이고 후속 기능은 제안이었다. 최신 소스 상태는 37절, 현실 날짜/게임 일광의 사용자 결정은 38절을 따른다. 정책 확정과 서버 설정 적용을 혼동하지 않는다.

> 2026-09-14 서버 배포 기준: 개인 기억 회상 개선본 **AI 0.1.3**을 서버에 배치했다. 게임/클라이언트 **1.0.2**, 콘텐츠 0.1.0/PERSONAL은 유지한다. 당시 LP 원본과 수정 직전 100개 파일을 보존했고 빌드/오프라인 710개 검사를 통과했다. 서버는 실행하지 않았으며 실제 모델 비교는 Ollama 미실행으로 연결 실패했다. **현재 배포본**은 31절과 [회상 개선 기록](../docs/MEMORY_RECALL_TUNING_20260914.md), [서버 시험 안내](../docs/LP_PERSONAL_MEMORY_TEST_SETUP.md)를, **미배포 개발본**은 32절을 따른다. 28~30절은 이전 이력이다.

> [장기기억·소문·평판 통합 설계](../docs/LP_LONG_TERM_MEMORY_DESIGN.md)는 전체 목표다. 신별 직접 경험·전언·평가 분리와 해당 플레이어만의 소문 차단 기반은 구현·시험했고 개인 발언 자동 추출 후보는 37절에서 OFF로 연결했다. 실제 전서구·전파망·기계적 평판 효과는 미구현이다. 남은 콘텐츠 선택은 설계 10절을 따른다. OPELA 전체 실험을 재도입한 것이 아니며 24·25절은 과거 이력이다.

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
- MythicTRPG 1.0.13 (현재 서버/클라이언트 배포용, 실제 런처 설치는 별도)

서버 전용:

- `mythaiaicontent-0.1.0.jar`
- `mythai_ai_response-0.1.14.jar`

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

- 38~41절의 기록/주시 정책은 문서 설계다. 주시는 퀘스트 보상 지급으로 획득하고 업적은 바닐라/외부 모드 발전 과제를 포함한다. 출처/인정 실적·전투 참가자/업적/바이옴 생산자·보상 지급→주시 기록/관측·차폐와 활동 요약·중요 기억 보존의 소스 적용/검증이 남았다. 기존 메인 완료 자동 부여를 구현하지 않는다. 상세 수집 자동 활성화/삭제나 과거 검사 결과의 새 정책 검증 전용도 금지한다.
- 현재 개발 게임 1.0.7/AI 0.1.8(40절)과 서버 게임 1.0.2/AI 0.1.3을 구분한다. 개인 회상 32절, 원장 33절, 관찰 증명 34절, 작물 경험 연결 35절, 상세 수집/파생 36절, 기억 backend/회상/유휴 정리 연결 37절은 각 단계 이력이다. 40절 퀘스트 개발은 기억 소스를 보존했지만 41절의 새 정책까지 구현하지 않았다. 기억 확장은 OFF·미배포이며 5단계 운영 수용/6단계 착수는 미충족이다.
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

## 33. 2026-09-15 — 로드맵 2단계 행동 원장 최소 기반 (미배포)

- 사용자 승인에 따라 **2단계만** 진행했다. 최신 계약/실제 소스/0~1단계 산출물 해시를 대조하고 선행 AI 오프라인 831개 검사를 다시 통과했다. 실제 모델 품질 수용은 여전히 미검증이며 이를 원장 구현 완료와 혼동하지 않는다.
- LP JAR 3개/LP 소스 298개/이전 전체 소스 906개 및 0~1단계 직전 백업 2,177개 무결성을 확인했다. 이번 직전 상태는 `server/backups/before-action-ledger-stage02-20260915-195314-636/`의 [manifest](../server/backups/before-action-ledger-stage02-20260915-195314-636/file-manifest.csv)에 **2,186개**로 별도 보존했다. LP/사용자 변경/기존 배포본을 덮거나 삭제하지 않았다. 전체 월드 백업은 아니다.
- 게임 개발본 **1.0.3**에 성숙 작물 제거/플레이어 귀속 비플레이어 생명체 사망의 성공 지점 기록, 불변 사건 ID/발생 순서·UTC/게임 시간, segment/checkpoint/읽기 인덱스, 제한 큐·단일 IO 작성자, 중복·실패·공백·재시작 처리와 관리자 조회를 추가했다. 실제 아이템 획득/보상·모든 이동/채집/전투 기록으로 확대하지 않았다.
- 기존 취소 가능한 관찰 이벤트/카운터/묶음 전달/퀘스트·조우·스토리 실행은 유지한다. 실제 결과를 읽는 비취소형 Mixin 2개를 추가했으며 원장 재생이 게임 소비자를 다시 호출하지 않는다. 제공된 게임 바이트코드에서 위치와 기존 알림 경로 동등성을 검사했지만 **실제 Mixin 적용/다른 모드와의 런타임 충돌은 미검증**이다. 배포 후 부팅이 검증됐다고 안내하지 않는다.
- 사용자 답변에 따라 **보관 용량·정리 정책은 측정 뒤 결정**한다. 요청한 **90% 사용량 서버 전체 알림/로그·관리자 증설 안내**와 30분 재알림 제한을 구현했다. 기본 `enabled=false`, 예제 용량 0이며 서버 설정은 추가하지 않았다. 자동 삭제는 없고 설정 한도/안전 여유/인덱스 초과는 명시적 실패로 처리한다. 기록이 중단돼도 기존 게임은 진행된다.
- 관리자 전용 `/mythadmin ledger status`, `/mythadmin ledger recent <player_uuid> [after_sequence]`를 추가했다. 비동기 조회 완료 후 권한/현재 접속자를 재검증한다. 원본은 ADMIN_ONLY_UNPROJECTED이며 **신의 주시·관찰 증명·AI 행동 기억은 연결하지 않았다.** 기존 대상 플레이어만의 소문 차단 정책도 바꾸지 않았다.
- 게임 컴파일/JAR 및 원장 **604개**, 기존 소문 **39개**, 후보 게임 API를 사용하는 AI **831개**, 합계 **1,474개 오프라인 검사**를 통과했다. 별도 테스트 JVM의 비정상 종료·복구도 검사했다. 후보 게임과 서버 AI/개발 AI 조합 각각 7 JAR/241 패키지 분리 검사를 통과했다. 시험 수는 인게임 시나리오/모델 정확도가 아니다.
- 실제 서버/클라이언트 배포 폴더는 **게임 1.0.2 / AI 0.1.3 / 콘텐츠 0.1.0**, AI 개발 JAR **0.1.4** 그대로다. AI 변경은 후보 게임 JAR을 지정하는 선택적 빌드 인자뿐이며 기본 legacy/overlay 입력을 유지한다. 서버/GameTest 서버·LLM·모델 설치·JAR 배포를 하지 않았다.
- 상세 지원표·파일 형식·설정/90% 안내·실패 한계·측정·복원은 [2단계 작업 기록](../docs/ACTION_LEDGER_STAGE02_20260915.md), 파일별 차이는 [2단계 변경 목록](../docs/action-ledger-stage02-changes-20260915.csv)을 따른다. 단순 수확 표본은 약 739~744 bytes/건이나 실제 1/4/6인 서버 tick·기록 빈도·전체 메모리/용량·알림/취소·신규 월드 저장 통합 시험은 남았다. 운영 보관 기간/용량은 확정하지 않았다.
- **2단계 소스·허용된 오프라인 검증 범위에서 종료하며 3단계는 미착수다.** 다음에는 별도 승인 후 신별 주시/관찰 증명의 기반을 검토하고 미확정 관측 범위·시작/유지/종료 규칙을 확인한다. 주시 운영이나 AI 원장 공개를 자동 시작하지 않는다.

## 34. 2026-09-15 — 로드맵 3단계 주시·관찰 증명 기반 (미배포)

- 이번 승인은 **3단계만**이다. 최신 로드맵·공통 계약·실제 원장/퀘스트 관심 대상/신 registry/관계 원본을 확인하고 2단계 변경 27개 해시와 선행 원장 604개·소문 39개 테스트를 재검증했다. 다른 작업의 미커밋 변경은 보존했다.
- 사용자 답변: **신별 권능·영역·장소와 가림 조건에 따라 관찰 제한**, 시작·유지·중지/관계 조건은 아직 미기획. 임의 임계값이나 운영 신별 설정은 만들지 않았다. 실제 GodAttentionState의 의미는 그대로이며 관심/자격과 실제 목격을 분리했다.
- LP JAR 3개/LP AI 소스 298개/이전 전체 개발 소스 906개와 2단계 백업 2,186개 무결성 확인. 현재 상태는 [3단계 직전 manifest](../server/backups/before-god-watch-stage03-20260915-210456-433/file-manifest.csv)에 **2,203개**로 새 보존했다. LP를 덮지 않았으며 전체 월드 청크/플레이어 진행도 백업은 아니다. 백업 helper의 stage3 이름 선택 추가만 snapshot 전에 준비했고, 그 이전 해시도 단계 변경 목록에 남겼다.
- 게임 개발 **1.0.4**의 새 `gameplay.watch`에 주시 구간/정책 snapshot, 대상별 활성 인덱스, 제한된 관측 투영, 현재 청중별 공개/복합 플레이어 검사, 근거 취소, append-only 증명/처리 cursor, 제한 큐·IO worker를 구현했다. raw에 추가한 FIFO fence로 같은 tick/저장 pending 상태에서도 시작 전 사건을 소급 목격하지 않는다. 재시작·재접속은 기술적 일시중지 후 명시적 새 구간을 요구하며 이미 관찰한 증명은 보존한다.
- `GameWatchGateway`는 실제 게임 ID/runtime/스레드/접속 객체 검사용 **미등록 adapter**다. **운영 capture hook·자동 시작·로그아웃/respawn/reload·새 저장소 관리자 진단은 연결하지 않았다.** 오프라인에서는 실제 원장 durable receipt→관찰 저장을 연결해 검증했다. 이 둘을 혼동하지 않는다. 기존 원장 기본 OFF와 90% 서버 알림, Mixin/퀘스트/보상/FTB/스토리/AI 프롬프트/서버 설정은 변경하지 않았다.
- 게임 JAR/컴파일 + 신규 주시 **98개**/원장 **604개**/소문 **39개**, 후보 게임 API를 쓰는 기존 AI 기억 **831개**, 합계 **1,572개 오프라인 검사** 통과. 파일 손상/잠금/force 후 오류 주입/큐 포화/용량/원본 실패/늦은 콜백·청중/증거 취소를 포함한다. 실제 게임·모델 평가 횟수가 아니다.
- 두 후보 조합 각각 **7 JAR / 242 패키지** 분리 통과. 게임 1.0.3의 기존 ZIP 엔트리 **969/971개 동일**이며 변경은 FIFO fence 본체/버전뿐, 새 watch 패키지만 추가됐다. 보호 baseline은 **2,196/2,203개 동일**, 승인된 기존 7개 + helper + 새 파일 8개 = 변경 목록 16개. 기존 LP/서버 배포본/개인 기억/다른 모드 소스와 이전 단계 CSV는 보존했다.
- 새 후보 JAR은 `mythictrpg-main/build/libs/mythictrpg-1.0.4.jar`, SHA `5EF3FAA0068F34884DFC199B68C58396E422D83DD44E4F59CCE13C0597B17936`. 기존 개발 AI 0.1.4와 서버 게임 1.0.2/AI 0.1.3/콘텐츠 0.1.0 그대로다. 서버/GameTest 서버·실제 LLM·모델 설치·JAR 배포는 하지 않았다.
- 세부 계약 매핑/제약/검사/복원은 [3단계 기록](../docs/GOD_WATCH_STAGE03_20260915.md), 개별 경로/전후 해시는 [3단계 변경 목록](../docs/god-watch-stage03-changes-20260915.csv)을 기준으로 한다. 관찰 누락 진단 카운터는 현재 프로세스 상태이며 영구 누락 요약은 미구현이다. 새 증명 저장소의 quota는 raw와 별개인 명시적 Limits이고 **운영 설치 전에 총량/90% 알림을 연결해야 한다**. 현재 raw의 90% 알림이 새 저장소까지 감시한다고 안내하지 않는다.
- **3단계의 미확정 운영 정책을 제외한 기반·오프라인 범위에서 종료한다. 4단계는 시작하지 않았다.** 다음 승인 시 명시적 시험 정책으로 작은 행동→허용 경험→기억·대화 연결에 착수할 기술적 출발점은 준비됐다. 운영 규칙·game lifecycle/현재 세션에서 발급하는 읽기 capability·대사 적용 직전 검증은 함께 완성해야 한다. 실제 Mixin/타 모드 런타임·OS 강제 종료·1/4/6인 tick/부하·LLM 자연스러움/HUD는 별도 승인 후 검증할 사항이다.

## 35. 2026-09-15 — 로드맵 4단계 관찰한 작물 행동→기억·대화 연결 (미배포)

- 승인 범위는 **4단계만**이다. 실제 3단계 변경 16개 해시와 선행 원장/주시/소문 741개 검사를 재검증했다. 미연결이었던 시험용 capture fan-out·생명주기·증명 quota 경고·현재 게임 대화에서 발급하는 Experience capability를 이번 최소 종단 연결에 포함했다. 기존 사용자 수정과 다른 모드 동작을 보존했다.
- LP JAR 3개/LP 소스 298개/기억 기반 이전 전체 소스 906개와 이전 단계 백업 2,203개 무결성을 확인했다. 직전 상태는 [4단계 baseline](../server/backups/before-experience-stage04-20260915-213244-306/file-manifest.csv) **2,213개**로 새 보존했다. LP/이전 백업을 덮거나 삭제하지 않았다. 전체 월드 청크·플레이어 진행도 백업은 아니며 helper의 snapshot 전 라벨 추가도 이전 해시를 남겼다.
- 게임 **1.0.5**: 원장 단일 submit→durable receipt 일치→주시 증명→게임 소유 `ai.experiencecontract`를 연결했다. 현재 지원은 성숙 작물 블록 제거이며 아이템 획득/수량/퀘스트·보상 완료로 승격하지 않는다. 미관찰 신·불허 청중·숨은 필드·소급 관찰을 차단하고 원장 재생으로 기존 게임 소비자를 재실행하지 않는다.
- AI **0.1.5**: 허용 최신 관찰 16개 중 관련 1개 + 기존 발언/소문 최대 2개를 같은 분류/생성 자료로 사용한다. 관계는 기존 PlayerMythDataService를 읽고 임계값/tier/친밀도를 임의 확정하지 않는다. 추가 LLM/임베딩 호출 없음, 선택적 관찰 조회는 500ms 후 기존 개인 회상으로 fallback한다. 단어·최근 화제 기반 작은 연결이며 의미 검색/일상 전체 요약을 완성한 것은 아니다.
- 대사/Proposal 적용 직전에 세션·턴·generation·청중·관계 snapshot·근거/공개 revision을 다시 확인한다. 큐에 남은 취소도 먼저 차단한다. 관찰 기반 NPC 대사와 후속 재진술은 원본 lease 계보를 임시 유지하고 독립 NPC journal로 복제하지 않는다. 퀘스트 완료/평가 **대사 턴**도 이전 사용자 턴의 기억을 재사용하지 않게 했다. 실제 퀘스트/평가/보상·FTB Validator/Executor는 변경하지 않았다.
- 사용자 답변은 ‘일정 관계 이상 신이 해당 플레이어 주시’ 방향이며 수치/유지·종료 조건은 미정이다. **자동 관계 주시는 OFF**다. 포르투나가 `zaqGlGlT`를 현재 서버에서만 주시하게 해달라는 요청에는 **개발 시험 경로만 준비**했다. 배포 게임 1.0.2에 기능이 없고 JAR 배포 금지가 유지되므로 실제 활성화/서버 설정 변경은 하지 않았다. 미래 `/mythadmin watch trial_start mythictrpg:fortuna zaqGlGlT <반경>`은 별도 배포 허가·명시 quota·온라인 대상·실제 게임 대화가 필요하다. 공통 신 프로필/다른 서버 기본값은 바꾸지 않았다.
- 시험 규칙은 명시한 위치의 고정 상자 영역+하늘 가림+성숙 작물+본인 대화만 공개다. 정식 포르투나 권능/비밀 장소 정책이 아니다. 로그아웃/respawn/재시작은 구간 일시중지, 신 정의 reload는 runtime 종료/재시작 요구. 원장·증명 각각 90% 용량 경고와 증설 안내, 자동 삭제 없음. 실제 서버 설정은 OFF 예제조차 배치하지 않았다.
- watch journal BOOT/CLEAN_CLOSE 확장으로 비정상 종료/기존 marker 없는 시험 저장소는 `UNCLEAN_WATCH_REQUIRES_REVIEW`로 조회를 닫고 원본을 보존한다. 큐에만 있던 취소를 재시작 뒤 잊고 공개하지 않기 위한 조치다. 자동 복구/봉인 해제/영구 누락 집계는 미구현이다. 현재 서버에 watch 자료를 새로 생성하지 않았으며 구 LP/3단계 코드로 새 저장소를 완전 다운그레이드하는 호환성은 보장하지 않는다.
- 최종 게임 컴파일/JAR 및 원장 **604**, 주시 **99**, Experience 계약 **24**, 소문 **39**, AI 기억 **656**, 개인 회상 **54**, 1단계 회귀 **121**, 새 경험/대화 연결 **33** = **1,630개 오프라인 검사 PASS**. 실제 파일 종료·재개→투영→prompt helper, 다른 신/청중/취소/재진술, 생성 소스 연결을 검사했으며 Minecraft 안의 전체 adapter/LLM/HUD 실행은 아니다.
- 후보 두 조합 각각 **7 JAR/243 패키지** 분리 PASS. 이전 게임 ZIP 엔트리 **992/1,006**, AI **303/318** 동일이며 다른 부분은 명시한 연결 클래스/버전 metadata만 변경했다. [Test-ExperienceStage.ps1](../dev-tools/Test-ExperienceStage.ps1)으로 LP/직전 baseline·기존 후보/배포 JAR·서버 설정·개인 기억·다른 모드/legacy 파일 불변과 [단계별 차이](../docs/experience-stage04-changes-20260915.csv)를 재확인한다. 기존 단계 CSV는 덮지 않는다.
- 빌드 입력은 새 게임 API JAR **1.0.5**, AI 최소 게임 의존성도 `[1.0.5,)`. `mine/mine` legacy source/JAR·checked overlay 방식은 유지했고 build/generated를 직접 수정하지 않았다. **현재 소스를 재빌드하면 1.0.5/0.1.5이며 LP/현재 배포본이 아니다.** 후보 해시·경로는 [4단계 작업 기록](../docs/EXPERIENCE_STAGE04_20260915.md)에 기록했다.
- 실제 서버/클라이언트 필수 게임 **1.0.2**, AI **0.1.3**, 콘텐츠 **0.1.0**, PERSONAL/gemma4:12b 그대로다. 서버/GameTest 서버 실행·중지·실제 LLM 호출·모델 설치·JAR 배포를 하지 않았다. 자연스러움/응답시간 개선과 타 모드 런타임 호환이 확인됐다고 말하지 않는다.
- **허용된 4단계 소스·오프라인 작업에서 멈춘다. 5단계 미착수.** 남은 실제 Mixin/명령·생명주기·1/4/6인 부하·Fortuna/다른 신/관객/취소·LLM/HUD 종단 검증은 별도 승인 뒤 수행한다. 5단계 확대의 기술적 출발점은 생겼으나 로드맵의 실제 모델/인게임 통과 조건은 아직 미충족이다. 보관 용량·정리 정책은 실측 후 결정한다는 사용자 선택을 유지한다.

## 36. 2026-09-16 — 로드맵 5단계 상세 수집 확장·파생 기억·검색-only 기반 (미배포, 단계 전체는 미완료)

- 승인 범위/사용자 답변/타입별 지원표/남은 일은 [5단계 기록](../docs/MEMORY_STAGE05_20260916.md), 변경 파일·선택 복원은 [5단계 delta](../docs/memory-stage05-changes-20260916.csv), 재검증은 [Test-MemoryStage05.ps1](../dev-tools/Test-MemoryStage05.ps1)을 따른다. 이전 단계 CSV는 덮어쓰지 않는다.
- 선행 4단계 37개 파일 해시·실제 소스/빌드 입력과 기존 오프라인 **1,630개**를 다시 확인했다. **4단계 실제 Minecraft/모델/지연 수용 조건은 미충족**이라 운영 확대는 하지 않고 독립 가능한 5B 검색-only와 OFF 수집 기반을 진행했다.
- LP 원본 JAR 3개·LP source 298개·기억 개발 직전 906개·4단계 직전 2,213개를 검증했다. 이번 작업 전 `server/backups/before-memory-stage05-20260916-223454-266/`에 **2,231개** 별도 백업. LP/기존 dirty 변경/이전 개발 JAR을 보존한다.
- **게임 1.0.6**: 주기 위치 샘플, 차원 변경/표준 텔레포트, 채굴 요청·중단·시도·실제 제거, 근접 공격 시도/반환·최종 피해, 지상 줍기, 플레이어 사망, 퀘스트 완료/평가를 read-only로 수집한다. 함수 반환/시도와 실제 결과를 구분하고 기존 FTB/보상/조우/스토리 실행은 유지한다. 지원하지 않는 API·모드 경로와 불완전 자료를 완전한 일상 이력으로 과장하지 않는다.
- `action-detail.json`은 **OFF, movementIntervalTicks=0(구체 간격 미정)**. 새 타입은 관리자 raw 전용이며 신에게 자동 투영하지 않는다. 기존 허용 성숙 작물 관찰 6종/ExperienceLease 경계를 유지한다. schemaVersion=1 enum 확장으로 새 자료의 구버전 읽기는 호환 보장하지 않는다.
- **AI 0.1.6**: 원문 ID/hash/scope/audience 추적 sidecar, 계획/시간/인용 힌트, 명시적 정정 링크 계약, 허용 경험 집계, 합성 벡터 의미+단어 검색/fallback을 추가했다. 삭제·정정·pin 변경·청중 변경·종료 후 늦은 worker 경계를 검증했다. raw v1은 덮거나 이관하지 않는다. 실제 `DerivedService.search` 결과는 기존 검색 그대로이며 **임베딩 backend/영속 벡터 인덱스·운영 검색 소비·자동 정정/요약·모델 공통 입장 관리는 아직 없다**. 의미 검색이 운영에 적용됐다고 설명하지 않는다.
- raw/관찰 저장소의 기존 90% 알림을 유지하고 파생 저장소에도 동일 임계 알림/관리자 용량 증가 안내를 추가했다. 용량/정리 정책은 실측 후 결정하며 자동 삭제·몰래 샘플 간격 축소를 하지 않는다. 개인 원문 v1의 기존 한도/사소한 기억 30일·수동 pin은 바꾸지 않았다.
- 최종 게임/AI 컴파일·개발 JAR과 **2,470개 오프라인 검사** PASS: 기존 1,630 + 상세 수집 402 + 파생/혼합 검색 434 + 허용 경험 집계 4. 1/4/6인 합성 raw/파생 자료와 기본 fallback·권한·손상/재시작을 검사했다. 실제 tick·전체 대화 지연·자연스러움 또는 실제 Mixin 적용을 측정한 것은 아니다. artifact/해시는 5단계 기록을 따른다.
- 최종 보존 대조는 2,231개 중 2,208개 불변, 이번 delta 45개(기존 수정 23·신규 21·snapshot 직전 helper 1)다. 후보 조합 각각 7 JAR/244 패키지 격리, 이전 게임 ZIP 엔트리 1,002/1,017·AI 316/322 동일, 퀘스트/FTB/보상 소스의 정확한 read hook 외 불변을 확인했다.
- 서버/클라이언트 배포본·설정·월드 개인 기억·사용자 대화 로그·FTB/콘텐츠/legacy 원본은 변경하지 않았다. 서버/GameTest 서버 실행·중지·실제 모델 호출·모델 설치·JAR 배포를 하지 않았다. Fortuna→zaqGlGlT 시험 주시는 현재 서버에서 활성화하지 않았다.
- **6단계로 넘어가지 않고 여기서 멈춘다.** 다음에는 별도 승인과 설정 선택 후 4단계 작은 실제 종단을 검증하고, 5A 모드 호환·부하 실측 및 5B 운영 검색/정정/자원 관리 연결을 완료해야 한다. 이번 결과만으로 5단계 완료나 전서구 제작 준비 완료를 선언하지 않는다.

## 37. 2026-09-17 — 5단계 남은 개인 기억 소스 연결·오프라인 완료 (OFF·미배포)

- 사용자 최신 승인: 6단계 전에 5단계 남은 소스부터 마무리. 범위/설정/API/검증/한계는 [후속 구현 기록](../docs/MEMORY_STAGE05_COMPLETION_20260917.md), 선택 복원은 [후속 delta](../docs/memory-stage05-completion-changes-20260917.csv), 보존·JAR 재검사는 [Test-MemoryStage05Completion.ps1](../dev-tools/Test-MemoryStage05Completion.ps1)을 따른다. 이전 36절의 미연결 상태는 당시 이력이다.
- LP JAR3/소스298·기억 개발 직전906·이전 5단계 직전2,231 무결성을 확인했다. 수정 전 별도 `server/backups/before-memory-stage05-20260917-053507-275/` **2,255개** snapshot을 생성·검증했다. LP 덮어쓰기/다른 사용자 변경 초기화 없음.
- **AI 0.1.7**: 실제 Ollama embed/chat backend 소스, 전후 모델 digest 검증, 별도 index-v2 append 저장·모델 버전별 재색인, 개인 회상 소비의 OFF/SHADOW/ON와 timeout/fallback, 무접속 유휴 원문 정리/정정 후보, 분류·대사·자발 대사·시각 평가의 공통 입장 관리 연결. 실제 LocalLlmRequestScheduler를 checked overlay로 생성·패키징하며 legacy 원본은 수정하지 않았다.
- 자동 종류/정정/취소 판단은 **후보**이며 전후 원문을 함께 전달한다. 원문 삭제·중요 pin·약속 이행·게임 사실·관계/보상 권한으로 승격하지 않는다. 청중/원문 현재성/세션 종료, full/손상/retry 상한과 90% 용량 알림을 유지한다. [새 설정 예시](../docs/examples/ai-memory-index.example.json)는 OFF/용량0/모델·digest 미지정이다. 서버에 설치하지 않았다.
- 신규 **1,878개**(runtime1,874+실제 scheduler의 가짜 요청4), 기존 AI1,302, 게임1,168 = **4,348개 오프라인 검사 PASS**. 모듈 컴파일·AI 개발 JAR PASS. 합성 1/4/6인·벡터/추출 fixture는 실제 대화 자연스러움·GPU/서버 지연 결과가 아니다. 초기 테스트 선언 오류/대기 큐 경쟁은 수정 후 재검사했다.
- 최종 snapshot **2,243/2,255개 불변**, delta23(기존12·신규11). 이전 AI ZIP 엔트리330/344 동일, 나머지는 허용한 연결 클래스/metadata, 새 조합7 JAR/244 패키지 격리 PASS. 게임/FTB/콘텐츠/legacy source, 기존 개발 JAR, 실제 서버/클라이언트 JAR·설정·개인 기억·로그는 불변이다.
- 개발 게임 **1.0.6** 그대로, AI **0.1.7** JAR SHA-256 `389A9A160A9F53F34BADD76731C1560424783BE84B50DC4D911DB3978806394B`. 배포 게임/AI/콘텐츠 **1.0.2/0.1.3/0.1.0** 그대로. 서버/GameTest 실행·모델 호출/설치·배포·운영 설정 변경은 하지 않았다.
- 남은 것: 실제 모델 품질/전체 응답시간·1/4/6인 인게임 부하/모드 호환·HUD, 모델/용량/간격 선택 및 별도 실행 승인. 신별 상세 행동 공개·자동 주시·중요도/아카이브 정책은 미확정, 경험 집계의 전체 행동 프롬프트 소비는 아직 운영 확대하지 않는다. 기본 원문 한도/30일 조회/수동 pin은 유지한다.
- **승인된 독립 소스 연결은 마무리했지만 5단계 전체 운영 수용/6단계 착수 조건은 미충족이다. 6단계는 진행하지 않고 여기서 멈춘다.**

## 38. 2026-09-17 — 행동 기록과 실적 정책 확정 반영 (문서만 변경)

- 사용자 요청은 변경된 설계 중 확정된 내용의 문서 반영과 다음 논의 정리다. 원본 기획서의 통계 우선/필요한 별도 카운터/중요 사건 기억 원칙을 따라 [확정 정책](../docs/ACTION_RECORDING_POLICY_20260917.md)을 추가했다. 상세 규칙의 단일 기준은 이 문서이며 원본 `Mythic_TRPG_기획서_v0.1.docx`는 보존했다.
- 일반 행동은 기본 통계, 업적·보상은 출처를 반영한 별도 인정 실적, 네임드·중요 사건은 장소를 포함한 기록으로 구분한다. 플레이어 직접 설치는 본인/타인 모두 재채굴 제외, 정상 재배 작물·나무 인정, 돌 생성기 같은 산출 블록 제외, 출처 기록 없음은 자연 생성으로 인정한다. 기록 없음의 인정은 실제 자연 생성/목격 증명과 구분하며 알려진 생성기 출처까지 무시하지 않는다.
- 기능 완성 후 새 월드에서 추적을 켜고 시작한다는 운영 전제를 기록했다. **현재 월드 생성·초기화·교체는 하지 않았다.** “내일”은 현실 날짜, 일출·일몰은 Minecraft 태양 기준이라는 앞선 답변도 반영했다. 시간대·수면/시간 명령 세부 정책과 실제 적용은 남았다.
- [로드맵](../docs/ACTION_OBSERVATION_MEMORY_ROADMAP_20260915.md)의 목적/5A/후속 순서, [공통 계약](../docs/ACTION_OBSERVATION_CONTRACT_20260915.md)의 통계·실적·사건 구분, [개인 회상 설계](../docs/EPISODIC_MEMORY_UPGRADE_DESIGN_20260915.md)의 시간 선택·최신 정책 링크를 갱신했다. 기존 0~5단계 구현/테스트 기록과 상세 수집 소스는 보존했고 이번 결정을 구현 완료로 소급하지 않았다. mythai-integration 기준으로 게임의 통계/보상 권한과 신별 지식 공개 경계를 유지했다.
- 수정 전 기존 문서 4개를 `server/backups/before-action-policy-docs-20260917-205937-038/`에 복사하고 원본/복사본 SHA-256 일치를 확인했다. LP 원본 JAR **3개**와 LP 필수 소스 manifest **298개**도 읽기 전용으로 검증해 모두 일치했다. 원본 Word 파일의 변경 전 SHA-256은 `017E4508788E0F0866EC4FEAFED4ECA96D253117FEFCE61D85D2AB658259D905`다. 기존 미커밋 변경은 되돌리지 않았다.
- 문서 검증 PASS: 대상 문서 5개의 로컬 링크 **101개** 존재 확인, 제목 뒤 빈 줄/코드 펜스/충돌 표시 검사, 변경 diff 및 새 문서 공백 검사. 수정 전 문서 4개 백업 해시와 원본 Word 해시도 재확인해 일치했다. 백업의 `manifest.json`에 기준 해시를 남겼다. Java/Gradle 빌드·게임 테스트는 문서 변경만이므로 수행하지 않았다.
- 미구현/미검증: 새 정책용 원장 선별·출처 추적 보완·실적 소비자 연결, 중요 사건 대상/장소 표현, 통계 기반 관찰 범위, 실제 인게임/모델 품질·지연·부하·모드 호환. 기존 4,348개 검사 결과는 이번 정책의 검증 결과가 아니다.
- 다음 논의는 **중요 사건 대상과 장소 표현 → 통계 중심 일상 관찰 → 신별 주시 조건 → 전서구 세부 콘텐츠** 순서를 권장한다. 보관 용량/정리 정책은 변경된 범위의 측정 후 결정하며 90% 서버 알림/관리자 증설 안내 요구는 유지한다. 이번에는 문서 외 코드·JAR·서버 설정·월드/기억을 변경하거나 서버/GameTest·실제 LLM·모델 설치·배포를 실행하지 않았고 **6단계도 시작하지 않았다**.

## 39. 2026-09-17 — 상세 기록 대상과 조우 후 지속 주시 기획 (문서만 변경)

- 38절 다음 사용자 답변을 [단일 정책](../docs/ACTION_RECORDING_POLICY_20260917.md)에 반영했다. 네임드 몬스터 포함 그 이상급과 레이드 등 제작 전투 콘텐츠는 **전투 결과**를 상세 기록하고 모든 퀘스트/업적을 기록 대상으로 삼는다. 언제·어디서·누구와·어떤 대상/결과인지를 구분하며 장소는 사건 당시 **좌표·바이옴**과 기존 차원 ID다. 일반 몹 처치 통계와 기존 채굴 실적 정책은 유지한다.
- 신 해금은 서버 공용으로 A가 달성해도 B가 조우할 수 있고, 특별한 재잠금 사건 외에는 유지한다. 개인 조우는 신별 지역·아이템·행동·확률의 전부/일부 또는 연계 퀘스트로 이루어진다. 이후 그 신에게 받은 퀘스트를 **완료**하면 지속 주시하고 신/플레이어의 특별한 상황이 없으면 유지한다. 관계 수치 임계값을 추가 기본 요건으로 정하지 않았다. 구체 재잠금/중지 사건은 별도 확인 사항이며 시작 퀘스트 종류는 아래 후속 답변으로 메인으로 확정됐다.
- 사용자가 자연스러운 관찰 방식을 위임해, 중요 결과는 허용된 사건 기억으로, 일상은 관측 가능한 구간의 제한된 활동 요약으로 설계했다. 관련 대화에서만 성격/관계에 맞게 활용하고 매 행동 LLM 호출·상시 상세 일지·매번 감시 보고를 요구하지 않는다. 가림/오프라인/재시작 공백의 통계 증가를 목격으로 소급하지 않고, 지속 주시 관계와 일시 관측 불가를 구분한다.
- 실제 소스 확인: `GodUnlockService`/`MythicWorldState`는 공용 누적 해금이고, `QuestRuntimeService.assign`은 `MAIN_ENTRY` **수주 시** `GodAttentionState`를 기록한다. `commit`은 전역 최초 완료/다른 수주 무효화와 보상 처리를 구분한다. 새 주시는 이 수주 권한과 분리해 완료 결과에서 연결해야 하며 기존 저장소/FTB 권한을 바꾸지 않았다. `ActionRecord`의 현재 단일 행위자/대상·좌표를 바이옴/공동 전투/업적 전체 지원으로 간주하지 않는다.
- 당시 질문과 후속 답변: 메인·사이드 구분 없는 첫 완료와 입문/메인 완료 중 질문했고, 사용자는 **메인 퀘스트 완료**를 선택했다. 미응답으로 다시 취급하지 않는다. 자동 연결은 아직 하지 않았고, 전역 최초 메인 완료에 따른 공동 완료 귀속/후발 플레이어 경로는 후속 확인한다. 바닐라/외부 모드 발전 과제 범위·정식 전투 ID·특수 예외는 연결 전 확인하며 전서구와 보관 용량 논의도 별도다.
- 갱신 문서는 정책·로드맵·공통 계약·인수인계 및 [FTB 퀘스트 가이드](../mythictrpg-main/docs/FTB_QUESTS_INTEGRATION_GUIDE.md)의 수주 권한/주시 구분 안내다. 나머지 과거 단계 기록은 당시 이력으로 보존했다. 수정 전 **5개 문서**를 `server/backups/before-watch-policy-docs-20260917-223128-377/`에 복사해 SHA-256 일치를 확인하고 `manifest.json`에 기준을 남겼다. LP 원본 JAR **3개**, LP 소스 manifest **298개** 읽기 전용 검증도 모두 통과했다. 원본 Word는 수정하지 않았다.
- 문서 검증 PASS: 갱신 대상 **5개 문서의 로컬 링크 93개**, 제목/코드 펜스/충돌 표시와 변경 diff·새 정책 문서 공백 검사, 수정 전 문서 **5개 백업 해시** 및 원본 Word 불변 확인. 문서 작업이므로 빌드·서버/GameTest·실제 LLM·모델 설치·JAR 배포는 하지 않았다. 새 정책의 소스 구현/인게임 검증과 기존 4~5단계 운영 수용은 여전히 남았으며 **6단계를 시작하지 않았다**.

### 후속 확정 — 메인 퀘스트 완료 시 주시 시작

- 사용자 답변: **메인 퀘스트를 완료했을 때** 주시를 시작한다. 기존 `QuestNarrativeRole.isMainQuest()`가 입문 메인 `MAIN_ENTRY`와 `MAIN`을 포함하고 `SIDE`를 제외함을 확인해 같은 분류로 문서화했다. 수주/목표 충족만이나 사이드 완료로 지속 주시를 시작하지 않는다. 기존 메인 수주 권한은 변경하지 않는다.
- 정책·로드맵·공통 계약·FTB 가이드·인수인계의 현재 미응답 문구를 해소했다. 메인 퀘스트의 전역/공동 완료에 따른 개인 주시 귀속, 구체 중지/종료 예외는 별도 확인 사항으로 보존했다. 이미 진행 중인 주시를 반복 완료로 중복 생성하지 않는 경계도 명시했다.
- 수정 직전 문서 5개는 `server/backups/before-main-watch-docs-20260917-224458-340/`에 보존했다. LP 원본 JAR 3개와 보존 소스 298개 해시도 모두 일치했다. 문서 검증 PASS: 5개 문서의 로컬 링크 94개, 제목 간격·코드 펜스·충돌 표시·공백 검사, 관련 저장소 diff 검사, 수정 전 문서 5개 백업 해시 및 원본 Word 불변 확인. 코드·JAR·서버/모델 실행·설정·원본 Word 변경은 없으며 6단계 미착수를 유지한다.

## 40. 2026-09-20 — 퀘스트 참여 유형·직접 다인 대화·동의·정산 (미배포)

- 사용자 확정: 솔로는 대화 청중의 적격자 중 한 명, 단체는 한 명도 가능하며 모두 네/아니요를 답할 때까지 대기한다. 청중은 **같은 NPC 근처에서 직접 대화에 참여한 사람**이다. 랭킹 종료는 퀘스트마다 시간제한/전원 제출 중 선택한다. 동점은 공동 순위(1,1,3)·동일 보상의 기본값으로 구현했으며 별도 순위 타이브레이크 규칙을 확정하지 않았다.
- 단일 설정/사용법은 [QUEST_PARTICIPATION_GUIDE](../mythictrpg-main/docs/QUEST_PARTICIPATION_GUIDE.md). 기존 FTB 바인딩에 선택적 `participation`을 추가했다. 목표는 아이템 제출/누적 기부/지원 행동 관찰/서버 평가이고 기존 완료 방식·서사 분류·재촉·보상 정책과 분리한다. 단체는 확정 참가자 **각자** 목표와 확인을 마친 뒤 전원 완료, 경쟁은 첫 유효 최종 제출자, 랭킹은 검증 점수별 보상이다.
- `/mythtalk join <대화 중인 플레이어>`는 같은 차원·16블록·활성 God ID 대화와 직접 참여를 검사한다(최대16명). 현 시스템은 물리 NPC 엔티티의 거리가 아닌 대화 중인 플레이어 기준이다. 신규 합류는 새 세션·generation을 발급해 개인 히스토리를 공유하지 않고 늦은 응답을 막는다. `/mythtalk leave`는 기존 HUD 일시정지/재개와 분리한다.
- 모집 중 세션 변경/이탈/로그아웃/reload는 취소, 확정 뒤 roster는 고정이다. 오래된/중복/외부인 응답과 부적격자의 네를 거절한다. 개인 목표/제출 상태는 기존 `MythicQuestState`에 확장 저장하며 FTB 팀 실적과 분리한다. FTB에는 참가자별 고유 CustomTask 미러만 생성한다.
- 보상은 기존 `RewardClaimState`에 전원 검증한 지급권을 묶어 저장한 후 종료한다. 오프라인 지급/개인 선택 UI/중복 방지를 재사용한다. 잔액 등 일시 지급 불가는 영수증을 소비하지 않고 `/mythquest rewards`로 재시도한다. 전역 진행도/스토리 완료 사건은 한 번만, 개인 완료 이력은 성공 참가자별이다. 아무도 제출하지 않은 시간 마감은 성공 완료/보상으로 기록하지 않는다.
- AI에는 후보별 유형·적격 수주자 UUID와 현재 청중의 참여 대기/본인 진행/검증 점수/제출 소요 시간/최종 순위를 전달한다. `quest_offer.recipient_id`를 서버가 현재 청중/자격으로 검증한다. `WAITING_FOR_PARTICIPANTS`/`SUBMITTED`를 완료와 구별한다. 완료 AI 대사 실패는 NPC HUD `[퀘스트가 완료되었습니다]`이며 대화는 계속 가능하다. 게임/연동 스킬에 따라 실제 판정·소비·보상은 게임, 생성 대사는 AI 권한으로 유지했다.
- 기존 v1 바인딩/생성자와 SavedData 읽기를 보존했다. `participation` 없는 퀘스트는 기존 동작이며 **현재 포르투나 정의를 자동 이관하지 않았다**. `GeneratedQuestTemplate`/`generated_quest_offer`의 기존 개인 즉석 생성 경로는 그대로이고 다인화가 남았다. 신규 SavedData의 구버전 JAR 재저장은 지원하지 않으며 여러 파일·인벤토리를 아우르는 강제 종료 트랜잭션 보장을 새로 추가한 것은 아니다.
- 개발 버전 게임 **1.0.7**, AI **0.1.8**, AI의 최소 게임 의존성 `[1.0.7,)`과 입력 JAR을 함께 올렸다. 기존 미커밋 기억/관찰 개발은 보존했으며 새 JAR에도 포함된다. legacy `mine/mine`·FTB 소스·콘텐츠 JSON은 수정하지 않았다. `build/generated` 직접 수정 없이 기존 생성 오버레이 뒤에 `quest-participation-overlay.gradle`을 적용한다.
- 검증: 퀘스트 상태 머신 **61개**, 생성된 공유 대화 메서드/세션 guard **21개**, AI 회상 **54개**·이전 회귀 **121개**·경험 대화 **33개** PASS. Minecraft **201 GameTest**(신규9개 포함) PASS. 다인 가입·전원응답·이탈 취소→아이템 소비→FTB 미러→NPC 확인→보상, 저장 복원·부정 입력·선착순/동점·보상 일괄 검증·오프라인/일시 지급 실패를 검사했다. 모의 클라이언트 및 stub을 사용했으므로 실제 LLM/HUD 종단 품질 검증과 구별한다.
- 중간 발견/수정: HUD 재활성화 시 기존 interaction ID 계약을 깨는 문제를 명시적 leave와 분리했다. FTB 패킷을 협상하지 않은 바닐라 모의 플레이어 문제는 테스트 연결을 NeoForge mock으로 설정하고 번역 캐시를 복원해 해결했다. 첫 실패 결과를 최종 성공으로 덮어 설명하지 않는다. GameTest는 `mythictrpg-main/build/quest-participation-*` 격리 폴더이며 실제 `server`를 켜거나 멈추지 않았다.
- 실제 `server/mods` 및 `client-required-mods`는 게임 **1.0.2**, 서버 AI **0.1.3**, 콘텐츠 **0.1.0** 그대로다. JAR 배포·서버 설정/월드 초기화·실제 LLM 호출은 하지 않았다. 다음 단계는 새 ID 시험용 콘텐츠 선택/적용, 실제 2인 이상 대화와 HUD/FTB 확인, 별도 즉석 생성 경로의 다인화다. 기존 기억 로드맵 6단계/주시 정책은 이번 작업으로 완료 처리하지 않는다.
- 최종 GameTest 로그는 `mythictrpg-main/build/quest-participation-verification-final/logs/latest.log`이며 **201/201 PASS**다. 게임/AI JAR 간 중복 클래스 **0**, 갱신 문서 로컬 링크 **66개**·신규 JSON 예시 **3개**·CRLF를 고려한 diff 공백 검사 PASS. 최종 빌드 SHA-256: 게임 `build/libs/mythictrpg-1.0.7.jar` = `6F98E28937E8287C4BF816796817B416B9C436AB1FC50FE72C3783DA58352012`, AI `build/libs/mythai_ai_response-0.1.8.jar` = `94012FC17472F2E8690709F35C84837455D7CE9A791ECA5E96D2588C1565A580`(각 모듈 기준 경로). 이전 버전 개발 JAR은 삭제하지 않았다.

## 41. 2026-09-20 — 보상형 주시·전체 발전 과제·기억/차폐 설계 (문서만 변경)

- 사용자 확정: 주시는 첫/메인 퀘스트 완료자의 자동 효과가 아니라 **퀘스트 보상 ‘OOO의 주시’를 획득한 플레이어의 신별 기록**이다. 메인/사이드 분류보다 작성된 보상·실제 개인 지급이 기준이다. 39절의 입문/메인 완료 자동 주시 및 이전 대화의 유형별 완료자→주시 대응은 대체됐다.
- 사용자 확정: 상세 업적 기록에 프로젝트 업적뿐 아니라 **Minecraft 기본/외부 모드 발전 과제도 포함**한다. 타 모드 판정/보상을 변경하거나 숨겨진 달성을 모든 신에게 공개하지 않는다.
- 사용자 위임: 기억 기준의 추가 포함/제외 요구는 없으며 더 자연스러운 방식을 차용한다. [정책 4절](../docs/ACTION_RECORDING_POLICY_20260917.md)에 미해결 약속/중요 결과 우선, 해결 후 결과 중심 요약, 현재 대화 우선, 정정·변화 존중, 반복 회상 비증폭과 지연 억제를 설계했다. 모델 해석은 후보이며 원문 삭제·게임 사실/관계 변경 권한이 아니다. 기존 원문 한도/30일 조회/수동 중요 표시의 자동 확장은 미구현이다.
- 사용자 방향: **상위 존재의 신전 같은 신별 차폐 공간**, **아무도 외부에서 볼 수 없는 특수공간**. 획득 상태를 지우지 않고 새 관측을 차단하며 나와서 이전 공백을 소급 목격하지 않는 설계다. 신별 위계·신전 소유자 예외·정식 영역/허용 목록을 임의 확정하지 않았다.
- 실제 확인: `RewardEntry/RewardEntryCodec`에는 주시 타입이 없다. `WatchContract`는 game-owned 신/플레이어·ACTIVE/PAUSED/ENDED와 정책/가림 증명 기반이고 실제 보상 지급 연결은 없다. 통합 스킬 기준으로 칭호 해금/메인 수주 권한/주시 획득/현재 관측을 구분했다. 기존 `RewardClaimState`와 `GodAttentionState` 권한을 보존하는 설계이며 새로운 AI 원본 DB는 만들지 않는다.
- 40절의 개인 완료와 보상 대상은 구분한다. RANKING은 유효 제출자가 완료되더라도 보상 구간 밖이면 주시를 자동 획득하지 않는다. 선택 대기·오프라인·재시도/중복·다중 저장소 중단 복구를 후속 구현에서 검증한다. 기존 포르투나 바인딩/새 참여 유형/즉석 SIDE를 자동 이관하지 않았다.
- 이제 남은 것은 실제 보상 퀘스트/신·차폐 영역/관측 정책 매핑, 보상/획득/관측·전체 발전 과제/공동 오프라인 기록·실적/기억 보존의 소스 연결, 실측 기반 용량/기간/모델 선택과 별도 실행·배포 승인이다. 첫 완료자 선정이나 업적 포함 범위를 다시 미응답으로 취급하지 않는다. 90% 용량 경고·관리자 증설 안내와 6단계 미착수는 유지한다.
- 수정 전 **7개 문서**는 `server/backups/before-watch-reward-docs-20260920-141745-063/`에 별도 보존했다. LP 원본 JAR **3개**/보존 소스 **298개** 해시가 모두 일치했다. 원본 Word는 보존했다. 정책·로드맵·공통 계약·FTB/참여 유형/보상 가이드·인수인계만 수정하며 다른 미커밋 변경은 유지했다.
- 문서 검증 PASS: 7개 문서의 로컬 링크 106개, 제목 간격/코드 펜스/충돌 표시/공백과 두 저장소 diff 검사, 수정 전 문서 7개 백업 해시 및 원본 Word 불변 확인. 이번에는 소스/JSON/설정/JAR/월드/기억 데이터 변경, 빌드·서버/GameTest 실행·실제 LLM/모델 설치·배포를 하지 않았다. 40절의 격리 GameTest 결과는 별도 퀘스트 작업 이력이지 이번 새 기억 정책 검증이 아니다.

## 42. 2026-09-20 — 최신 정책 기준 1~5단계 소스·오프라인 적용 (미배포)

- 사용자 승인: ‘최신 설계를 반영한 1~5단계 소스 구현·오프라인 검증’. 게임 연동·서버 빌드·대화 검토 스킬 절차에 따라 실제 게임 권한, 기존 오버레이, 저장/세션/청중 경계와 미배포 상태를 분리했다. 실제 LLM 없이 대사 품질이 개선됐다고 단정하지 않는다.
- 작업 전 LP 소스 **298**, 기억 기반 이전 **906**, LP 원본 JAR **3** 해시 확인. 변경 직전 **2,284개** 파일은 `server/backups/before-memory-stage05-20260920-143804-077/`에 별도 보존했다. 지형 전체 백업은 아니며 소스/문서/기존 JAR/설정/기억/시험 로그/데이터팩 등 범위는 manifest를 따른다.
- 단일 상세 기록은 [구현 보고서](../docs/MEMORY_POLICY_IMPLEMENTATION_20260920.md). 일반 행동 원문은 기본 저장하지 않고 중요 결과를 선별한다. 기존 통계/조우/스토리는 보존하고 성공한 채굴 실적만 기존 건축 출처로 보정해 참여형/즉석 퀘스트 보상 목표에 전달한다. 일반 플레이어 설치를 서로 캐는 우회, 확인된 생성기, 도구 변환·피스톤·낙하 출처 보존 기반을 추가했다.
- `ActionRecord.Details`는 바이옴/위치 상태/run/참가자, 중요 타입은 전투 결과/퀘스트 전환/발전 과제/허용 활동 요약이다. 오프라인 좌표를 만들지 않는다. 동일 전환 재시도는 최초 근거를 유지하고 새 주시 증명을 만들지 않는다. 네임드 타입 태그/제작 전투 API는 준비했지만 전서구/레이드/실제 분류 매핑은 만들지 않았다.
- 기존 보상 claim 저장 안에 `watch` 타입과 개인 신별 획득을 추가했다. 실제 자동 지급/선택 결과만 획득하고 중복/영수증 정리로 초기화하지 않는다. `/mythquest watches`는 본인 획득 조회다. 메인/관계/GodAttention/공동 완료를 주시 부여로 바꾸지 않으며 랭킹 보상 구간 밖은 제외한다. 기존 지급의 모든 외부 부수 효과까지 원자화한 것은 아니다.
- `reward-watch.json`이 없으면 OFF. 명시 관측 정책과 실제 획득으로만 재개하며 신별/절대 차폐는 시험 경로에도 적용한다. 실제 보인 순간의 성공 행동만 제한 집계하고 통계 차이로 숨은 구간을 채우지 않는다. 퀘스트/숨겨진 업적은 별도 공개 근거가 없어 원격 AI 투영을 차단한다. 공동 사건 참가자의 신 전부에게 목격을 생성하지 않는다.
- 개인 회상은 보호 후보 우선/중복 검색 슬롯 제거/잡담 연상 감쇠, 정정·취소·이행 진술 구분, 현실 날짜와 게임 일광 분리다. 선택적 중요 기억 예약량·전체 바이트 제한과 90% 증설 안내를 추가했으나 예약 기본 0, 원문 자동 삭제/장기 아카이브는 미활성이다. 오래 기억할 수 있는 후보와 무한 저장 보장을 구분한다. 실제 의미 모델/추출은 기존 opt-in 그대로다.
- 최종 오프라인: 게임 **1,339**, AI **3,269**(하위 fixture 포함), 합계 **4,608 PASS**. 1/4/6인 합성 파일 검사 통과. 최종 2,000행 회상/15ms 예산 검사 5회 중 1회 초과는 안전 fallback이며 실서버 응답시간 보장이 아니다. 새 API 테스트 컴파일 오류/옛 정책 기대값 실패를 수정 후 재실행했다. 손상 NBT 음성 검사의 ERROR는 예상된 거부 로그다.
- 개발 JAR SHA-256: 게임 1.0.8 `F2DDE942245069AF17E287B3D8261FA25EF1316D2E141F26F235D45BB60ED5C1`, AI 0.1.9 `2D34B931A03B0EFB2E036B756512EAF40117DE0BF0FED0D2E225CDE4A0C86654`. AI 최소 게임 의존성 `[1.0.8,)`. 구 개발 JAR은 삭제/덮어쓰지 않았다. legacy/콘텐츠/FTB 소스와 실제 서버 배포물은 그대로다.
- 남은 일: 실제 콘텐츠 보상·신/차폐/네임드 매핑, 실제 Minecraft 설치취소/이동/성장/다른 모드 조합, 저장 중단·재접속·HUD/LLM 품질·응답시간과 운영 부하/용량 검증. 새로 만드는 업적은 인정 counter를 사용해야 하며 독자 외부 업적 DB/생성기는 개별 연결이 필요하다. 40절 GameTest 결과를 이번 변경의 서버 검증으로 사용하지 않는다.
- 다음 작업은 이 소스/오프라인 기반에서 시작할 수 있으나 **운영 수용 완료는 아니다**. 6단계는 별도 승인과 전서구 귀속/재생성·차단 종료/전파 조건 확인 후 진행하며 이번에 착수하지 않았다. 새 저장 버전의 LP 하향 복원은 JAR뿐 아니라 호환되는 시험 직전 데이터도 함께 필요하다.
- 최종 보존/호환 감사: `dev-tools/Test-MemoryPolicyImplementation.ps1` PASS. LP 298+906개/원본 JAR3·직전 snapshot2,284개 재검증, 현재 기준 파일2,226개 불변(58개 수정), 신규24개(개발 fixture 로그4개 포함). [작업별 변경 manifest](../docs/memory-policy-changes-20260920.csv)를 Git의 기존 미커밋 변경과 구분해 사용한다. 개발 게임/AI와 나머지 서버 모드 총7개 JAR/244패키지의 split-package 0. 이는 실제 Mixin 적용·서버 부팅 검증을 대신하지 않는다.

## 43. 2026-09-20 — 하드웨어 기준 기억 모델 연결·합성 실측 (미배포)

- 사용자 요청은 현 장비/코드에 맞춘 연결 및 이득이 있을 경우 정리 모델 설치다. 합성 한국어 실제 모델 호출을 별도로 승인받았다. 통합/서버 빌드/대화 검토 스킬의 권한·실측·배포 경계에 따라 작업했으며 실제 플레이어 원문은 모델 시험에 사용하지 않았다.
- LP 298+906개/원본 JAR3 확인 후 현재 2,311개 파일을 `server/backups/before-memory-stage05-20260920-161949-064/`에 추가 보존했다. LP/143804 이전 snapshot/기존 개발 JAR/사용자 미커밋 작업은 유지했다.
- 단일 상세 기록은 [모델 연결·실측 보고](../docs/MEMORY_MODEL_CONNECTION_20260920.md). Ryzen 7 9800X3D / RAM 약64GB / RTX5070Ti 16GB / Ollama0.32.15. CPU bge-m3의 준비 후 합성 요청은 약48~59ms였고 GPU 비교는 중간 timeout이 발생했다. 개선된 동일 지침의 정리 후보 시험은 Gemma 11/12, Qwen7B CPU 9/12; 표본이 작고 의미 오분류도 남았다.
- **추가 설치 없음.** 기존 bge-m3:latest(CPU4스레드) + 기존 대화 gemma4:12b(무접속 시간 재사용)를 선택했다. 대화 모델/ai-dialogue.json은 변경하지 않았다. 접속자 0명에서만 원문 색인·정리, 접속/전경 대기에는 인터럽트로 양보하고 transport 종료 전 입장권을 보존한다. 확실한 단어 검색은 의미 호출 생략, 질의 최대350ms 후 fallback이다.
- 소스 AI **0.1.10**: 요청별 실행 설정, JSON Schema/원문 인용·한국어 분류 지침, extractive-v3 지문, 정리 실패 후 벡터 보존/재사용·독립 검색, 중복 의미 슬롯 제거. 원문 수정/삭제·공개 경계·SHADOW·게임 사실/보상 권한은 유지한다. 별도 일반 요약으로 원문을 대체하거나 중요한 약속을 무한 보장하는 시스템은 아니다.
- 새 서버 설정은 `ai-memory-recall.json`(REAL_KST/ON), `ai-memory-index.json`(의미/정리ON, 모델digest 고정, 색인256MiB/12000행) 두 개다. 원문 한도/자동 삭제 정책은 변경하지 않고 90% 증설 안내를 유지한다. 이 설정만으로 구 배포 AI가 신기능을 실행하지는 않는다.
- AI 관련 오프라인 **3,319개 PASS**, compileJava/jar PASS. 새 연결 fixture50개는 CPU 옵션/Schema/부분 실패·재시작/전경 중단·입장권/fast path/중복/1024차원 저장 검사다. 2000행15ms 검색은 5회 중3회 예산 초과 후 안전 fallback; 실서버 응답 SLA로 인용하지 않는다. 실제 모델 비교는 opt-in backend benchmark로만 실행했으며 최종 적재 모델은 자동 만료 후0개였다.
- 개발 AI0.1.10 SHA-256 `FDD7583743EE13569617ACAE5F07209B7E003DA752C4ABC3DE5921B6932A7AE3`, 최소 게임1.0.8 유지. **JAR 미배포: 서버 게임1.0.2/AI0.1.3/콘텐츠0.1.0 그대로**. Minecraft/GameTest 실행·실제 HUD/플레이어 대화·모델 다운로드·월드 기억 변경·6단계 착수 없음.
- 다음 작업은 별도 배포/실행 승인 후 최신 데이터 백업, 게임/AI/클라이언트 맞는 버전 조합 검증, 실제1/4/6인 지연·회상/공개 경계·재접속·종료 검증이다. 앞 단계의 콘텐츠 매핑·운영 수용 미완료도 유지한다. [변경 manifest](../docs/memory-model-changes-20260920.csv)와 `dev-tools/Test-MemoryModelConnection.ps1`을 이번 작업의 보존/호환 기준으로 사용한다.
- 최종 보존/호환 감사 PASS: snapshot2311개 중 현재2300개 불변(수정11), 신규13개(빌드 정리와 분리 보존한 합성 측정 JSON7개 포함). LP/기존 개발·서버 JAR/게임·FTB·콘텐츠·legacy·월드/원문 불변. 새 게임/AI 조합+나머지 서버 모드7개 JAR/244패키지의 split-package0, 로컬 링크89개/공백 검사 통과. 감사 중 자체 생성 manifest 링크 검사 순서를 바로잡고 재검사했으며 배포·부팅 검증으로 간주하지 않는다.

## 44. 2026-09-20 — 6단계 선행 점검·백업·콘텐츠 답변 일부 확정

- 사용자가 6단계를 요청했으나 앞 단계의 실제 충족과 기존 실행 제한 유지를 요구했다. 통합/서버 빌드 스킬의 게임 권한·배포 분리 기준으로 현재 소스/기존 상태 머신/AI 소비 경로와 로드맵을 대조했다. 단일 상세 기록은 [6단계 점검](../docs/RUMOR_STAGE06_PREFLIGHT_20260920.md).
- LP298+906개/원본 JAR3 및43절 소스·설정·개발JAR 보존 감사 PASS. 현재2326개를 `server/backups/before-memory-stage05-20260920-172344-666/`에 별도 보존/검증했다. 라벨은 기존 도구의 memory-stage05를 사용했으나 이번6단계 요청의 변경 전 기준이다. 전체 월드 지형 백업이 아니며 manifest 범위를 따른다.
- 선행 소스 컴파일·관련 오프라인을 새로 재실행: 게임1317, AI2621, 합계3938 PASS. 43절의 합성 실제 모델 결과는 기존 근거로만 읽었고 이번 새 호출은 없다. 4~5단계의 실제 Minecraft 관찰→회상 종단과1/4/6인 품질·tick/부하 수용은 미완료이므로 전체 운영 선행 조건 충족으로 선언하지 않는다.
- 사용자 답변 확정: A의 전서구를 B가 사냥하더라도 **관찰 대상A의 소문만 차단**한다. 소문 설계7.4/10절에 반영했으며 기존 RumorLedger는 이미 그 귀속으로39개 경계 검사를 통과한다. 이것이 실제 개체/사망 hook 연결 완료를 뜻하지 않는다.
- 답변 대기 두 묶음: 정식 규칙 대신 기본OFF·관리자 지정 개체의 개발 시험 경로로 진행할지 / 기존 소문 처리 제안(기수신 유지·미전달폐기/재전송없음·긍부정공통·전서구채널만·대사만)을 채택할지. 나머지 자동 스폰·재생성/정보망·관측/발행 기준을 임의 확정하지 않았다.
- 이번은 사전 점검과 문서 반영까지다. Java/빌드 설정/서버 설정/기존JAR/월드·기억 변경, 신규JAR 생성·배포, Minecraft/GameTest/실제 모델 실행·설치, 7단계 착수 없음. 두 답변 이후 명시된 범위의 소스 구현을 이어가며 실제 운영 시험/배포는 별도 승인으로 남긴다. 6단계 완료로 보고하지 않는다.
- 작업 전2326개와 대조한 변경은 문서3개·오프라인 검사에 따른 개발 로그4개이며 나머지2319개는 그대로다. 새 점검 문서1개 추가. 회전 전 로그는 새 snapshot에서 복구할 수 있으며 서버 로그·월드·LP·기존JAR은 변경하지 않았다.

## 45. 2026-09-20 — 6단계 후속 엔티티용 소문 연결 기반 (미배포)

- 사용자 요청: 실제 전서구 엔티티와 스폰 이벤트는 나중에 따로 만들며 **자동 스폰·재생성은 우선 OFF**. 이번에는 대체 동물/가짜 엔티티·모델/렌더러·스폰 명령·구조물·타이머를 만들지 않았다. 게임 연동 스킬의 권한/IO 경계와 서버 릴리스 스킬의 소스/빌드/배포 분리를 적용했다.
- 선행44절의 점검을 이어 실제 기존 소스/빌드 입력/서버 배포본을 대조했다. 소스 기반은 갖춰져 있으나 1~5단계의 실제 Minecraft 종단·다인 운영 수용은 미완료라는 판정을 유지한다. 이번 작업을6단계 전체 운영 완료로 보지 않는다.
- 수정 전 LP 소스298개·기반 이전906개·원본JAR3 확인. 직전 **2329개**는 `server/backups/before-memory-stage05-20260920-173428-369/`에 별도 보존하고 해시 검증했다. 라벨은 기존 도구명이며 전체 월드 지형 백업은 아니다. LP/과거 개발JAR/사용자 미커밋 변경을 덮어쓰지 않았다.
- 단일 상세 계약/연결 지침은 [6단계 구현 보고서](../docs/RUMOR_STAGE06_IMPLEMENTATION_20260920.md). 게임 **1.0.9**의 `CourierSettings/Proof/Engine/RumorService`는 미래 콘텐츠가 이미 추가한 실제 개체를 대상에 연결하고, 승인된 발췌의 차원/거리/로드된 시야 경로·출처를 검증한다. 원장/채팅 전체를 감청하거나 통계를 목격 사실로 바꾸지 않는다. 실제 사건/공개 승인 생산자의 API 호출은 후속이다.
- A의 관찰 개체를 B가 사냥해도 A만 차단한다. 확정 사망을 전달보다 먼저 반영하며 취소/언로드/로그아웃을 죽음으로 오인하지 않는다. 기수신 유지·미전달 폐기/새 epoch 후 재전송 없음·긍부정 공통 차단·전서구 채널만·대사만의 개발 시험 제한안을 적용했다. 직접 주시·관계·퀘스트·보상을 변경하지 않는다.
- 후보/발행/전달/수신을 분리하고 게임 source ID/revision·발췌 hash·정책 지문·epoch·유효 기간을 저장한다. 발행 전 근거 철회도 tombstone으로 막는다. 새 전용 LLM worker/자연어 의미 판단/자동 발행기는 만들지 않았으며 미래 생산자는 별도 의미/공개 승인 후 호출해야 한다. `AUTHORED`도 실제 게임 사건 공급과 작성된 규칙이 있어야 동작한다. 기본은 빈 규칙/OFF다.
- AI **0.1.11**은 수신 신의 IGNORE/CAUTIOUS/INTERESTED를 반영하고 소문을 사실/영구 게임 태그로 승격하지 않는다. 인용 NPC 대사의 세션 출처를 보존해 철회 후 후속 인용도 제외하며 기존 기억·비동기/청중·RUMOR_TEST 실행 제한을 유지한다. 출처64개 중2개는 새 관찰/소문용으로 예약한다. PERSONAL에 새 소문을 몰래 섞거나 과거 journal을 삭제하지 않는다.
- 새 proof가 있는 소문 저장만 v2로 기록한다. v1 읽기 지원, OFF로 기존 상태를 조회한 것만으로 v1을 올리지 않는다. 손상/미지원 NBT 원본 보존과 항목별4096개/90% 서버 알림·증설 검토 안내, 꽉 찼을 때 새 기록 거부를 검증했다. 자동 삭제/아카이브·LP 하향 호환 보장은 하지 않는다.
- 컴파일·오프라인 **게임5567 + AI3335 = 8902 assertion PASS**. 신규 Courier4189에는 용량 삽입4096개가 포함되고 신규 RumorDialogue16개다. 기존 퀘스트/행동/주시/실적/회상/모델 입장/세션 회귀도 재실행했다. 시야 ray의 중간 청크 로드 가능성과 출처65개 초과 경계를 보완하고 다시 통과했다. 손상 NBT의 ERROR는 의도한 거부 검사다. 합성1/4/6인 fixture와2000행 검색을 실제 tick/응답시간 검증으로 간주하지 않는다.
- 개발 JAR: 게임1.0.9 SHA-256 `8313238A1AF569A7CE94FD8D3BFF1070FA3C60E09AA4451CC233F27155F6D357`; AI0.1.11 `DD83D2105390FEE3757BC8C550AC47442544A5DF4B33BDD7F25B412A4CD8586F`. AI 최소 게임 `[1.0.9,)`. 이전 개발JAR은 그대로다. 개발 조합+나머지 서버 모드7개JAR/244패키지에서 split-package0, 중복 엔트리/오프라인 fixture 포함 없음.
- `dev-tools/Test-RumorStage06.ps1`와 [이번 변경 manifest](../docs/rumor-stage06-changes-20260920.csv)로 보호 경로·신규 파일·LP·snapshot·기존JAR·패키지/링크를 검증했다. 비교 기준2329개 중2305개 불변, 변경24개에는 개발 테스트 Log4j 회전/현재 로그6개가 포함된다. 신규 소스/문서/시험 로그 등15개(새 회전 로그6개 포함)와 개발JAR2개는 별도이며 manifest 자체는 자체 hash 재귀를 피하려 목록에서 제외한다. 로컬 링크140개와 저장소의 기본 줄바꿈 설정에서 공백 검사 PASS. 기존 무관한 게임 소스/FTB/콘텐츠/legacy와 실제 서버 파일은 불변이다.
- **실행/배포 없음:** Minecraft/GameTest 서버·실제 LLM·모델 설치·서버/클라이언트 JAR 교체·서버 설정/월드/기억 수정 없음. 서버 게임1.0.2/AI0.1.3/콘텐츠0.1.0 그대로다. 엔티티 생명주기와 타 모드 사망 취소의 실제 조합·공개/차폐 정책·실제 말투/HUD·다인 부하는 미검증이다.
- 다음 연결 준비: 미래 실제 EntityType/스폰 이벤트 → `bindExisting` → 승인된 게임 관측 생산자 → 필요하면 의미 후보/게임 승인 → 규칙/수신/첫인상. 각 API·필수 인수·거절 조건·삭제/재생성 미지원 경계는 보고서에 기록했다. **현재 자동 소문 운영은 불가능하며**, 후속 콘텐츠 연결과 별도 실행·배포 승인이 필요하다. 이번 범위에서 멈추고7단계 평판 효과/회복은 시작하지 않는다.

## 46. 2026-09-20 — 7단계 평판 판단 보정·대화 회복 기반 (운영 OFF·미배포)

- 사용자 결정: **관계 판단용 보정 기반만 구현하고 운영값 OFF**, **대화로 납득시킨 경우도 회복 가능하게 설계**. 두 질문은 답변 완료다. 통합 스킬의 게임 권한/세션·청중 경계, 릴리스 스킬의 백업·개발 빌드/배포 분리를 적용했다. 실제 신별 강도/금기/설득 기준을 임의로 작성하지 않았다.
- 선행 점검: 45절 변경 manifest의41개 경로가 작업 전 해시와 일치했다. 게임 컴파일과 선행 Courier4189/Rumor39/Quest61=4289개를 재통과했다. 그러나 실제 엔티티·관측/자동 후보 생산자는 없고 4~5단계 Minecraft 종단·다인 운영 수용도 미완료이므로, 독립적인 승인 범위만 구현했으며 전체 선행 조건 충족을 선언하지 않는다.
- LP 소스298개·기반 이전906개·원본JAR3 무결성 확인. 직전 **2347개**는 `server/backups/before-memory-stage05-20260920-182320-030/`에 별도 보존/검증했다. 라벨은 기존 도구명이며 지형 전체 백업은 아니다. 기존 사용자 미커밋 변경·이전 개발JAR·AI/FTB/콘텐츠/legacy·실제 서버 파일을 보존했다.
- 단일 상세 계약은 [7단계 구현 보고서](../docs/REPUTATION_STAGE07_20260920.md). `ReputationLedger/Engine/Service`는 현재 수신이 허용된 실제 CourierProof의 신별 평가 영수증과 읽기 전용 판단값을 관리한다. 기존 PlayerMythDataService affinity·퀘스트/보상 소유권을 바꾸지 않는다. 같은 source 중첩·반복 차감, 직접 효과가 이미 적용됐거나 불명확한 근거의 이중 보정을 막는다. 양/음 상한·기존 관계 구간·정책 지문/철회/청중 재검증과 비동기 재검사 포트를 추가했다.
- 회복/철회는 평가의 종결 상태로 지연 요청의 재적용을 거절한다. 최초 승인/최신 결정을 저장하고 과거 소문·대화 원문은 삭제하지 않는다. 모든 중간 전환의 별도 영구 원장을 만든 것은 아니다. 부분 회복/감쇠/동일 원본 재개방의 콘텐츠 규칙은 미정이다.
- `DialogueRecovery`와 서비스의 검토 포트는 현재 플레이어·월드·신·세션/세대·청중·턴·평가 버전·수신 근거를 확인한다. 게임 검토 승인 후 재검증해 RECOVERED를 적용하며 일반 승인 API로 DIALOGUE_REVIEW를 우회할 수 없다. **실제 Reviewer 구현/등록·대화 후보 생산·회복 후 대사 인식 반영은 미연결**이다. 검토기가 없으면 거절한다. 설명 후보나 AI의 동의 자체를 성공/월드 사실로 저장하지 않는다. 검토기는 게임 스레드에서 모델을 기다리면 안 되며 승인 참조의 진위는 신뢰된 게임 호출자가 확인해야 한다.
- 설정 예제는 OFF/양쪽cap0/빈rules이며 서버에 복사하지 않았다. 서비스는 RUMOR_TEST+명시적 enabled에서만 동작하고 기존 액션 차단은 유지한다. 새 SavedData `mythictrpg_reputation_judgement_v1`은 월드 귀속/손상·미지원 격리를 적용하며 기존 LP/소문/프로필 형식을 이관하지 않는다. 항목4096개 코드 상한·90% 서버/관리자 경고(1200tick 억제), 가득 차도 기존 회복 가능, 자동 삭제 없음이다. 설정 가능한 바이트 용량으로 오해하지 않는다.
- 최종 컴파일·오프라인 **게임8524 + AI730 = 9254 assertion PASS**. 새 평판4221에는 용량 삽입4096개가 포함된다. 새/기존 평판·소문·퀘스트·주시 보상과 AI 소문/경험/개인 기억/참여형 대화 검사를 통과했다. 손상 NBT ERROR는 의도한 음성 검사다. 중간 병렬 Log4j 잠금 경고는 새 시험 작업 경로 격리 및 최종 max-workers=1 재실행으로 해소했다. 실제 Minecraft/LLM 품질·서버 장애 복구 검증을 대신하지 않는다.
- 새 개발 게임 **1.0.10** SHA-256 `F0CE9630F08A5E3AFD9BB17FE17219ABFAACB06D41DB4E9249FB7FBE2360C685`. 기존 AI **0.1.11** JAR `DD83D2105390FEE3757BC8C550AC47442544A5DF4B33BDD7F25B412A4CD8586F`는 변경/재생성하지 않았다. AI는 명령행 API 경로만 새 게임으로 지정해 컴파일했으며 기본1.0.9 입력·legacy 오버레이·버전/소스는 그대로다. 서버는 게임1.0.2/AI0.1.3/콘텐츠0.1.0 유지다.
- 보존/호환 감사는 [전용 스크립트](../dev-tools/Test-ReputationStage07.ps1)와 [이번 변경 manifest](../docs/reputation-stage07-changes-20260920.csv)를 사용한다. 이전6단계 manifest는 재생성하지 않았다. 새 게임/기존 AI+나머지 서버 모드7개JAR/244패키지 split-package0, 중복 엔트리/오프라인 fixture 포함 없음. 개발 로그 자동 회전으로 정리된 `mythictrpg-main/logs/2026-09-20-4.log.gz`의 원본은 직전 백업에 해시 검증돼 있어 복구 가능하다. 서버 로그/월드 삭제는 없다.
- **실행·배포 없음:** Minecraft/GameTest 서버·실제 LLM 호출·모델 설치·JAR 배포·서버 설정/월드/기억 변경을 하지 않았다. 미완료는 실제 전서구/별도 스폰과 관측 생산자, 신별 정책/평가·설득 검토기·회복 후 대화 소비, 타 모드/인게임/1·4·6인 품질·부하/용량 수용이다. 자동 스폰·재생성 OFF 유지. **승인된7단계 기반 구현 범위에서 종료하며 추가 단계·운영 활성화는 자동 시작하지 않는다.**
- 최종 보존/문서 검사 PASS: 기준2347개 중2325개 불변, 수정21개·개발 회전 로그 정리1개, 신규17개·개발JAR1개로 manifest40행(자기 자신 제외). 로컬 링크127개/코드 펜스 및 기본 줄바꿈 기준 `git diff --check` 통과. LP/직전 백업 전체 해시와 보호 경로 불변을 문서 작성 뒤 재확인했다.

## 47. 2026-09-20 — 소문 생성·신별 인식·대화 설득 회복의 실제 연결 (운영 OFF·미배포)

- 사용자 승인: 기반만 있고 핵심 연결이 빠진 부분의 구현을 진행. 추가 답변 **일반 신 대화는 관찰 가능, 비밀·차폐 대화만 제외**를 반영했다. 실제 전서구 엔티티/별도 스폰은 추후 제작, 자동 스폰/재생성 OFF, 수치 관계 효과 OFF라는 기존 결정을 유지했다.
- LP 소스298개·기반 이전906개·원본JAR3개 해시 검증, 직전 **2365개**를 `server/backups/before-memory-stage05-20260920-192438-700/`에 별도 보존했다. 기존 도구 라벨이며 이번 연결 전 snapshot이다. 전체 지형 백업이 아닌 manifest 범위다. 기존 사용자 변경·다른 모드·과거 JAR/manifest를 덮어쓰지 않는다.
- 단일 상세 계약은 [소문·회복 연결 보고서](../docs/SOCIAL_PIPELINE_COMPLETION_20260920.md). 실제 수락된 발언/중앙 NPC 출력, 기존 ImportantEvents 결과 생산자를 게임 `SocialRuntime`에 연결했다. 후자는 정확히 작성된 공개 사건 매핑만 투영한다. 실제로 연결된 같은 전서구가 발언/답변 양 시점을 보아야 하며 없는 개체나 추정 목격을 만들지 않는다.
- 새 `SocialReviewProvider/OllamaSocialReview`는 기존 대화 모델로 제한된 구조화 해석을 비동기 수행하는 실제 backend다. 이번 검사는 모의 transport만 사용했다. 역할은 후보·수신 태도·설득 해석이며 게임이 출처/공개/epoch/현재 신·플레이어·세션·청중·턴·affinity·평가 버전을 재검증한다. 새 모델 설치나 실제 호출은 없었다.
- 게임 소유 Reviewer를 실제 등록하고 최근 실제 대화+선택된 소문으로 설득을 검토한다. NPC 동의 한마디·근거 없는 게임 실적 주장으로 성공 처리하지 않는다. 대화만으로 납득한 회복은 허용한다. 다른 화자 개입·실패·종료·교체 뒤 지연 결과는 폐기한다. 회복 후 다음 프롬프트는 과거 소문과 현재 인식을 구분하고 이전 비난의 근거 없는 히스토리 재사용을 막는다.
- 기존 콘텐츠 프로필 personality/values/restrictions와 원본 affinity를 사용하며 숨은 로어/다른 신의 자료는 보내지 않는다. 수신 평가에 `RECEPTION_REVIEW` 종류·읽기 DTO의 평가/버전을 추가했다. 자동 의미 평가는 DirectImpact.UNKNOWN이므로 실제 수치 보정/affinity·퀘스트/보상을 변경하지 않는다. modifier/cap0의 서술용 규칙을 허용한다.
- 대화 후처리는 현재 대화에 실제 선택된 소문만 평가한다. 전 신에게 분석을 즉시 팬아웃하지 않는다. worker1/대기4, 게임 진행4, 실제 실패3회/100tick 대기, foreground 선점 시 유효한 턴에서 재시도한다. 기존 접속자0 유휴 기억 정리는 유지하고 접속 중 사회적 후처리만 새 저우선 경로를 쓴다. HTTP 중단의 잔여 경합은 실측 전 0이라고 보장하지 않는다.
- `social-rumor`/`ai-social-review` 예제는 OFF/빈 콘텐츠 매핑, 서버에 복사하지 않았다. 기존 courier/reputation 설정과 RUMOR_TEST가 별도로 필요하며 PERSONAL 자동 활성화나 RUMOR_TEST 액션 제한 해제는 없다. 관리자 비밀 표시/상태 명령은 추가했으나 실행하지 않았다. 자연어 비밀 요청은 보조 SKIP 지침이며 명시적 게임 비밀/차폐 경계를 대신하지 않는다.
- 최종 컴파일·개발JAR 및 오프라인 **게임8580 + AI3202 = 11782 assertion PASS**. 새 SocialPipeline56/SocialReview41, 기존 평판4221/전서구4189/소문39/퀘스트61/주시14, AI 소문16/경험37/개인기억656/참여대화22/색인1874+스케줄러4+연결50/파생435+요약4/자연회상63이다. 용량 삽입 반복을 포함하며 실제 모델/플레이 시나리오 횟수가 아니다. 생성 오버레이 순서와 독립 다인 테스트의 새 연결 stub 문제를 수정 후 최종 직렬 실행을 통과했다.
- 개발 게임 **1.0.11** SHA-256 `BA520704D419304A67581C93486EC1DBAB7E53FC97AB9ED6544EAE998A3C12E9`, AI **0.1.12** `7281FFBFD2E43A3F35264AA3DB80BE4F43791D9D5F3A6412381B86E4C771E447`. AI 기본 API/최소 게임 버전을1.0.11로 맞췄고 legacy mine/mine 소스/JAR과 오버레이 구조는 보존했다. generated 직접 수정이나 구 결합형 JAR 배포는 없다.
- 보존·호환 재검사는 [새 감사 스크립트](../dev-tools/Test-SocialPipeline.ps1), 선택 복원 delta는 [새 manifest](../docs/social-pipeline-changes-20260920.csv)를 따른다. 기존6·7단계 CSV/스크립트를 재실행해 과거 해시를 덮어쓰지 않는다. 테스트 로그 자동 회전 정리본은 baseline에서 복구 가능하며 서버 로그/월드 삭제는 없다.
- **남은 것:** 실제 전서구/별도 스폰과 신별 전파·공개 사건 매핑, 장비/친분 등 개별 콘텐츠 생산자, 실제 LLM 한국어/설득/비밀 판단·1/4/6인 품질/부하·모드 이벤트/HUD/실제 복구 수용. 수치 효과·큰 업 자동 상쇄 등 미작성 규칙은 임의로 확정하지 않았다. 핵심 검토기/생산자/대화 소비자는 이번 범위에서 실제 연결됐지만 게임 콘텐츠 전체가 완성된 것은 아니다.
- **실행·배포 없음:** Minecraft/GameTest·실제 LLM·모델 설치·서버 설정/월드/기억 변경·JAR 배포 없이 종료한다. 서버는 게임1.0.2/AI0.1.3/콘텐츠0.1.0 유지. 별도 승인 없이 운영 활성화/추가 단계는 진행하지 않는다.
- 최종 감사 PASS: 기준2365개 중2323개 불변, 수정41개/개발 회전 로그 정리1개/신규21개/개발JAR2개로 manifest65행. 새 개발 조합7JAR/244패키지 split-package0, 중복 엔트리/시험 fixture 포함 없음, 로컬 문서 링크138개·펜스·tracked diff 및 신규 파일 공백/충돌 마커 검사 통과. 실제 게임/Mixin 호환성을 대신하지 않는다.

## 48. 2026-09-20 — 장기기억·다중 신 통합 M0 기준 확정 (문서만 변경)

- 별도 통합 설계의 **M0 문서 단계만 완료**했다. 기존 행동/기억 로드맵 단계 번호와 다르며 M1 신규 코드 구현은 미착수다. 결과와 후속 요청문은 [M0 기준 확정](../추가개발/05_AI_장기기억_다중신_대화_시스템/M0_기준확정/README.md), 계약의 단일 정의는 해당 설계02번 문서를 따른다.
- 현재 개발 게임1.0.11/AI0.1.12와 배포 게임1.0.2/AI0.1.3/콘텐츠0.1.0을 구분했다. 행동 원장·주시·소문 생성/인식/회복은 기존 개발 구현을 재사용한다. 과거 test 기억 제외는 기존 월드·게임 상태·주시 권리 삭제가 아니며, worldId를 포함하는 RumorSavedData 전체를 보존한다.
- 실제 신 입력/중앙 NPC 출력/참가자별 HUD, 공개 채팅·귓속말·바닐라 팀·설치된 FTB Teams 팀 채팅·동의·스토리 채널의 수집 연결점을 확정했다. 논리 발언1건과 실제 수신자/view/부분 전달을 분리하고 기존 memory/social 후처리 중복 호출을 금지한다. 수집 hook 구현·실제 Mixin 적용은 M2 이후다.
- 100GB 합산 registry의 실제 원장/주시/소문/평판/신규 SQLite 파일 및 NeoForge SavedData 비동기 임시본을 명시했다. 기존 건수·frame·검색30일 제한과 새 경로 적용 차이를 기록했다. 새 RAW 무기한·총건수 제한 없음은 목표이며, 기존 source 안전 한도까지 이미 해제한 것으로 보고하지 않는다.
- UUID canonical TEXT와 게임 발급 typed 포트/서버 thread 검증을 확정했다. `sqlite-jdbc:3.53.4.0` 기본 JAR·게임 모듈 단일 Jar-in-Jar를 선정했고 라이선스/확보 계획을 기록했다. dependency 다운로드·native/FTS5·NeoForge 로딩은 하지 않았으며 M1의 검증 게이트로 남겼다.
- 문서 검사 PASS: 설계 폴더 Markdown15개/로컬 링크55개/JSON 예시1개, 펜스·공백·충돌 마커와 `git diff --check`, 주요 보호 파일45개 SHA-256 불변. 이전47절까지 내용도 보존했다. 전체 월드 백업/전수 감사는 아니며 이전11,782 assertion과 실제 모델 실측을 이번 검사로 재사용하지 않는다. Java·Gradle·콘텐츠·서버 설정·월드·JAR 변경, 게임/LLM 실행·배포·파일 삭제 없이 문서 범위에서 종료한다. 다음은 M1이며 자동 진행하지 않는다.

## 49. 2026-09-21 — 대화 시험 명령·기록 정책 통합 (개발 소스, 미배포)

- 사용자 확인: `on`은 이번 대화를 기록하여 이후 기억으로 사용할 수 있게 하며, `off`는 기존 기억과 현재 문맥은 사용하되 새 영속 대화/기억/소문을 생성하지 않는다. 궁극적으로 3명 이상의 신이 한 대화에 참가할 수 있어야 한다. 통합·릴리스 스킬의 권한 격리와 개발 빌드/서버 배포 구분을 적용했다.
- 새 명령은 `/ai_test <on|off> <신 이름 또는 ID>...`. 이름/ID/path, 이름 공백 인용, 중복 제거, 모호한 이름 거절 및 자동완성을 지원한다. 최대16신이며 모든 게임 정의/프로필을 검사한 후 시작한다. 구 `start`는 이전 안내, 별도 `participants`는 제거했다. `/mythadmin interaction`은 실제 게임 Encounter를 시험하는 관리자 기능이므로 유지한다. 대화 시험 표준 경로/예제/제약의 단일 문서는 [통합 명령 가이드](../docs/AI_DIALOGUE_TEST_COMMAND.md)다.
- 게임 `AiConversationRuntimeService`에 읽기/기록을 분리한 불변 시험 범위를 추가했다. 신별 `memoryContext(player,god)`, 기록 허가, 참가 신 목록/시험 ID를 발급하며 활성 세션 덮어쓰기, 운영 다인 세션과 합류, 임의 게임 액션을 거절한다. 종료/재시작/실제 Interaction 교체/로그아웃 시 옛 권한을 무효화한다. 기존 v1 memory DTO 생성자와 RUMOR_TEST readOnly 의미는 유지한다.
- AI 오버레이는 실제 생성 소스 입력에 추가했고 `mine/mine` 원본/구 결합형 JAR은 수정하지 않았다. 신 선택 후 자기 기억과 자기 프로필로 분류·생성을 수행한다. 일반 후속 발화는 같은 신, 이름 지정은 해당 신, 집단/다음 신 호명은 순환한다. **현재는 한 턴 한 신 응답**이며 3신 이상 등록이 자율적인 N신 연속 토론 구현을 의미하지 않는다.
- 기억 `Entry/ReadView.godAudience`를 추가했다. 구 원문은 소유 신 단독 공개로 해석한다. 새 공유 발언은 각 참가 신의 키에 저장하고, NPC 답변은 실제 발화자의 키에만 저장한다. 검색·파생 인덱스·정정 근거에도 신 청중 경계를 유지한다. 기억 ID에 월드/상호작용/플레이어/신을 넣어 다중 신 충돌을 막았다. `off`는 새 원문/NPC 기록과 분석 로그, 해당 대화의 social capture/recovery를 차단하며 이미 저장된 자료 검색/유휴 정리는 유지한다.
- 신 청중이 없는 기존 관찰 경험/소문 DTO는 다중 신 대화에 공개를 추정하지 않는다. 다중 신 시험에서는 해당 참고/소문 생성·회복을 제외하고, 한 신 경로는 기존대로 유지한다. 그룹에서 과거 사적 기억이 안 나오는 것은 의도된 공개 제한이다. NPC 청취 발언의 별도 장기 저장, M1 SQLite/100GB/무기한 RAW, 다수 플레이어+다수 신 통합은 이번 범위가 아니다.
- 전역 PERSONAL/RUMOR_TEST/OFF 설정은 변경하지 않았다. 전역 OFF에서 기록 on은 거절한다. PERSONAL에서 만든 새 on 기억은 현재 운영 개인 기억 저장소, RUMOR_TEST에서는 기존 시험 저장소를 사용한다. 새 저장 필드가 없는 예전 자료는 보수적으로 읽지만, 새 자료를 만든 뒤 구 JAR 하향 복원은 검증하지 않았다.
- 검증: 게임 컴파일/개발 JAR과 순수 범위17, AI 컴파일·명령59·화자58·실제 생성 메서드 fixture87(+로그지점37)·실제 기억 bridge20·청중/이전 JSON 재시작34 검사를 통과했다. 기존 기억656·회상54·회상 단계121·관찰 경험37·인덱스1874(별도 scheduler4/model fixture50)·파생435(별도 summary fixture4)·social review41을 재실행해 통과했다. 초기 검사 디렉터리 누락 및 peer fixture helper 누락은 시험 도구 쪽 문제로 수정했다. GameTest·마지막 패키징 결과는 아래 후속 검증 기록을 따른다.
- 최종 검증: 신 청중에 따른 벡터/파생 기억·fingerprint·정정 연결 검사까지 확장한 `memoryAudienceTest` **46개**, 새 peer/QuestReward 격리 **25개**, 명령59/화자58/세션87+로그37/기억bridge20 재검사 PASS. 격리 폴더 `mythictrpg-main/build/dialogue-test-gametest-runtime`에서 `runDialogueTestGameTestServer`를 실행해 **202 required GameTests 전부 PASS** 후 정상 저장·종료했다. Minecraft 템플릿 namespace로 기존 검사도 함께 선택됐으며, 새 시험은 3신과 동시 on/off 2플레이어의 실제 게임 스레드 권한·종료·재시작을 확인했다. 실제 Ollama/HUD 클라이언트 시험은 아니다. `git diff --check` 및 AI JAR 중복 엔트리/시험 fixture 포함0 확인.
- 개발 산출물: `mythictrpg-main/build/libs/mythictrpg-1.0.11.jar` SHA-256 `D0BD0D819450F337A156A281882302F64D1205E19150C4D3AF6364DA12329AAE`, `mythai-ai-response/build/libs/mythai_ai_response-0.1.12.jar` SHA-256 `FBEDEA33E617BAC957B9172A204D2E8D613B58021CDFA8BF7E5C992F9B389EC1`. 같은 버전명 이전 개발 JAR과 내용이 다르므로 향후 배포 시 두 모듈을 이 소스 기준으로 함께 맞춘다.
- **배포하지 않았다.** `server/mods`의 게임1.0.2/AI0.1.3/콘텐츠0.1.0, 클라이언트 배포 폴더·모델·월드·설정은 그대로다. 실제 Ollama 대화, 클라이언트 HUD/응답 품질 확인은 남아 있다. 기존 사용자 미커밋 변경을 보존했으며 커밋·정리·파일 삭제는 하지 않았다.

## 50. 2026-09-21 — 데메테르의 자애로운 프로필 보강 (콘텐츠 원본, 미배포)

- 포르투나와 대조한 결과 기존 데메테르도 상황 10종·관계 9단계를 갖췄지만, 격식·간접적인 배려·절제된 칭찬이 겹쳐 엄격하고 거리감 있는 응답으로 읽힐 여지가 있었다. 사용자 요청에 따라 [데메테르 프로필](../mythai-ai-content-registry/src/main/resources/data/mythaiaicontent/mythai_ai/god_profiles/demeter.json)만 자애로운 방향으로 보강했다. 포르투나는 수정하지 않았다.
- 기본 말투를 `P_GENTLE / P_CALM / P_SHORT`와 구체적인 한국어 지침으로 정리했다. 초면의 따뜻한 환대, 직접적인 격려, 자연스러운 작은 사과 수용, 상대의 자립과 선택 존중을 강화했다. 친분·불행·과거 악행을 지어내지 않으며, 실제 해악에는 단호하고 친밀한 관계에서도 현재의 분노·서운함이 공존하도록 했다. 고정 답변이나 반복 횟수별 대사 목록은 추가하지 않았다.
- 콘텐츠 작성 스킬 기준으로 기존 스키마·`mythictrpg:demeter`·identity·퀘스트 목록 참조·빈 지식/예시 참조를 유지했다. 게임 상태나 관계 수치를 프로필에 저장하지 않는다. 실제 소비 코드의 일반/상황/관계/반복 지침 경로를 확인했으며, `P_GENTLE`에 별도 코드 분기가 있다고 가정하지 않고 자연어 말투 지침도 함께 제공한다.
- JSON 파싱, 포르투나와의 필드/상황/관계 구조 대조, 유지 필드, 실제 게임 GodDefinition·퀘스트 참조·말투 태그·God ID 중복 및 포르투나 무변경 등 **62개 검사 PASS**. 해당 프로필의 `git diff --check`도 통과했다. 이는 정적 콘텐츠 검증이며 실제 LLM 말투 품질 검증은 아니다.
- **소스 JSON만 변경했다.** JAR 빌드·서버/클라이언트 배포·서버 실행·월드 변경·LLM 호출은 하지 않았다. 새 프로필로 실제 대화하려면 별도 콘텐츠 배포가 필요하다.

## 51. 2026-09-21 — 공개·비밀 대화방 병행과 공간 병합/분리 (개발 소스·미배포)

- 사용자 확정: 같은 신은 공개방 한 곳과 복수 비밀방을 병행하고 플레이어도 병행할 수 있다. 공개는 전역 채팅, 비밀은 선택된 방에 `/s 할말`을 보낸다. 고정 공개는 이어진 동일 바이옴 구역, 이동 공개는 참가자 주변 16블록이며 이동방끼리 병합/고정방과 만나면 고정형/떨어진 무리별 분리한다. 신의 무리 선택은 LLM 제안, 실제 참가/주시 권한은 게임 소유다. 확률 조우 선정은 후속이다.
- 상세 동작·명령·입출력·상한·미연결 기능의 단일 가이드는 [공개·비밀 대화방](../docs/CONVERSATION_ROOMS.md)이다. `ConversationRoomLedger`/`ConversationRooms`, `RoomConversationEngine` 포트, `MythAiRoomConversationEngine`와 후행 room 오버레이를 추가했다. 기존 AI 분류·페르소나·검색·생성 코드를 요청별 wrapper로 재사용하며 mine/mine 원본이나 구 결합 JAR은 수정/배포하지 않았다.
- 게임에 방 UUID/revision/턴 lease, 1공개+다비밀 제약, 초대 토큰/수락/선택, 공개·비밀 수신자, 룸 코드, H 선택 화면/G UI 숨김, 신 식별 연동, 채팅+HUD 분할을 연결했다. 방당 최대64플레이어/16신, 플레이어64방/서버256방이며 실제 동접 보증치가 아니다. 신별 화자는 이름 지정/이전 화자 기반 한 턴 한 신이며 자율 N신 연속 토론은 아니다.
- 바이옴은 동일 차원·동일 바이옴의 6방향 블록 연결을 확인한다. 실제 로드된 청크만 읽고 불확실하면 UNKNOWN으로 보류한다. 고정 조우 commit 전에 실제 구역 handle/슬롯/참가자/신 예약, 실패 반환/성공 이전을 구현했다. 커다란 구역·미로드 구역에서 판단 지연/보류 가능하며 강제 로드나 ID만으로 연결 추정을 하지 않는다. 바이옴 편집 생산자의 `invalidateFixedRegions()` 호출 연결은 별도다.
- AI 컨텍스트·활동·기억·Quest/Reward/validator feedback과 비동기 결과를 방별로 격리한다. 변경/종료/분리/병합 및 같은 방의 새 턴으로 만료된 결과는 폐기한다. 퀘스트 동의를 처리한 턴도 이전 LLM 요청을 취소한다. 이전 history는 원래 플레이어 청중 **및 참가 신 전체**가 현재 청중을 포함할 때만 전달한다.
- 게임 액션은 명시적 `submitRoom`/generation으로 검증하고 확인 시 재검증한다. 퀘스트 `offerRoom/handleRoomAnswer/contextForRoom/confirmRoom`을 추가해 다른 방의 동의·권한으로 대체하지 않는다. 구 세션 취소와 전체 로그아웃 취소를 분리했다. 아이템 준비 어휘 판단은 기존 규칙을 공유 헬퍼로 이동했으며 실제 이전은 재료 검사+확인이 여전히 필요하다. 관계/보상/FTB 실행 원본은 기존 게임 시스템 그대로다.
- `/mythroom open <private|fixed|mobile> <on|off> <신들>`은 OP용 읽기 전용 게임 시험이다. `on` 새 기록/기억 허용, `off` 기존 자료 읽기만 허용하며 전역 기억 OFF에서 시험 on은 거절한다. 기존 `/ai_test`는 이전 회귀 경로로 유지했으며 중복 시작을 막았다. 기존49절의 `/ai_test` 시험과 새 방 기능을 혼동하지 않는다. 실제 Encounter는 STANDARD 방을 생성한다.
- 서버 루트 `ai-dialogue-logs/<플레이어명>/<날짜_시간>_<신 ID path>_<방UUID>.txt`에 참가자별 AI 발화/전달 기록, 월드 `mythictrpg-ai-room-logs`에 요청자별 단계 진단을 남긴다. 초기 Encounter/LLM 없이 처리한 동의 안내는 이 새 TXT 경로 밖이다. 기록 off는 두 경로와 신규 기억을 차단한다. 방 원장/현재 history는 RAM으로 서버 재시작 뒤 방 자체를 복원하지 않는다.
- 권한 확장을 추정하지 않았다. 새 방에서는 PUBLIC 로어만 허용하며, 기존 관찰 증명은 단일 신 비밀방에만 쓴다. 구 SocialRuntime의 소문 생성/회복과 게임발 퀘스트 완료/평가 자동 AI 연출은 아직 방별 포트로 옮기지 않았다. 이 기능 전체를 새 방에서 완성했다고 보고하면 안 된다. 원본 affinity는 전달하지만 수치→9단계 규칙/누적 감정은 새로 발명하지 않고 기본 R_NEUTRAL/E_NEUTRAL과 명시적 미정 정보를 사용한다.
- 통합·릴리스 스킬에 따라 게임 권한과 개발 빌드를 분리했다. 최종 게임 `jar`/오프라인 방147·연결구역/예약22945·실제 Gateway45·HUD34·실제 퀘스트 동의 메서드32 = **23,203 assertion PASS**. 무작위 연결성 대조·용량 반복을 포함하며 실제 플레이 횟수가 아니다. Gateway/동의 fixture의 월드/FTB는 stub이고 실보상/저장 게임 검증을 대신하지 않는다. 실제 다인 서버·GameTest·Ollama·클라이언트는 이번에 실행하지 않았다.
- 게임 개발 JAR `mythictrpg-main/build/libs/mythictrpg-1.0.12.jar`, SHA-256 `413F98A3F374A8FEC89C1FFDD683F183FAB09D969BE906A5777443202FB8708E`. AI는 0.1.13/최소 게임1.0.12, 게임 네트워크 protocol6이므로 향후 서버·클라이언트 게임 JAR을 함께 맞춰야 한다. 기존 개발1.0.11/AI0.1.12는 보존했다. 변경 전 작업 파일 보존 위치는 `dev-tools/backups/conversation-rooms-20260921`이며 월드 전체 백업은 아니다.
- **서버/클라이언트 JAR 배포·서버 실행/중지·설정/월드/기억 변경·실제 LLM 호출 없음.** `server/mods`는 게임1.0.2/AI0.1.3/콘텐츠0.1.0 그대로다. 현재 서버를 켜면 새 방 기능이 적용되는 상태가 아니다. 실제 배포와 다인 접속 검증은 별도 작업이다. 기존 미커밋 변경을 보존했고 파일 삭제/정리/커밋은 하지 않았다.
- AI 최종 검증: 고정된 게임 JAR 복사본(SHA-256 동일)을 API 입력으로 직렬 빌드해 **새 방 엔진62 / 기존 대화87+로그가드37 / 기억 청중46 PASS**, 컴파일/JAR 성공. 실제 생성 프롬프트에서 A/B별 QuestConstraint·RewardConstraint·validation feedback·관계·감정·기억 분리, 공개 허용 로어, 지연 응답, 분리 후보/긴 history 예산, 기록 on/off를 검사했다. 초기 FMLPaths 시험 부트스트랩 오류와 빌드 도중 게임 JAR 갱신으로 생긴 ZIP 읽기 경합은 각각 시험 초기화와 불변 입력 복사로 해결했다. 제품 서버나 LLM 실행은 아니다.
- AI 개발 JAR `mythai-ai-response/build/libs/mythai_ai_response-0.1.13.jar` SHA-256 `F29FB529BBE15C09C9153E087B0A00D94915427847483698A6D482E634D17702`. 최종 두 JAR의 중복 엔트리0/새 test fixture 포함0/게임↔AI split-package0 확인. 실제 NeoForge 부팅·다른 모드 전체 호환 검사를 의미하지 않는다.

## 52. 2026-09-21 — 변경된 대화방의 기록·기억 연결 보완 (개발 구현·미배포)

- 새 채팅과 기존 행동 기록/기억의 연결을 재확인하고 보완했다. 상세 변경·검증·남은 일은 [연결 결과](../docs/ROOM_RECORDING_INTEGRATION_20260921.md), 최신 동작은 [대화방 가이드](../docs/CONVERSATION_ROOMS.md), 장기기억 후속 설계는 [M0 변경 반영](../추가개발/05_AI_장기기억_다중신_대화_시스템/M0_기준확정/04_대화방_변경_연결_20260921.md)을 따른다. 통합/릴리스 스킬에 따라 게임 권한과 기록·기억·운영 배포를 구분했다.
- 게임 `RoomDialogueEvent`/`RoomDialoguePublisher`를 추가해 `ConversationRooms.publishPlayer/publishGod`의 실제 발화를 공통 통지한다. 초기 Encounter 대사·AI 없이 처리한 플레이어 동의 입력도 기존 참가자 TXT에 기록한다. AI 요청/완료에서 참가자 TXT를 다시 쓰던 경로는 제거했다. 시스템의 동의 요청/결과 안내를 비롯해 모든 채널의 RAW를 수집한 것은 아니다.
- 발화별 message UUID와 room/revision/optional turn, 참가자·신·실제 서버 전송 결과를 동결한다. 채팅/HUD 복제는 같은 발화이며 실패/오프라인/숨김을 성공 receipt로 만들지 않는다. 플레이어 본인의 수락 입력은 본인 표시가 없어도 보존한다. 기록 off는 새 영속 통지를 차단하고 기존 개인 기억 쓰기도 게임의 현재 기록 허용 여부를 재검증한다. 일반 행동 원장 기록은 그대로다.
- history에 sourceRoomId를 유지해 병합/분리 뒤 원래 방의 관찰·소문 근거를 확인한다. 일반 생성과 분리 판단에서 철회 근거를 제외하고 비동기 분리 응답 적용 시에도 동결한 근거를 재검증한다. 게임은 결과 bundle의 room/revision/turn·선택 신·본문을 검증해 미승인 화자가 섞인 응답을 부분 전달 완료로 처리하지 않는다.
- 설계는 기존 64플레이어·16신 참가/병행방을 보존하고 M6의 1플레이어·2신을 신규 순차 생성의 초기 시험 범위로 구분했다. 전체 기록의 명시적 off 예외와 현재 publication→미래 RAW adapter를 반영했다. 최초 M0 CSV와 과거 구현/검증 이력은 보존했다.
- 게임/AI `compileJava`·개발 JAR 성공. 오프라인 **게임277 + AI973 = 1,250 checks PASS**: publication19, 방147, 실제 Gateway45, 실제 동의 메서드32, HUD34, AI 방90, 청중46, recording bridge20, 기존 생성87+로그가드37, 경험37, journal656. 일부 게임 의존성은 stub이며 실제 플레이/보상/영속성 종단 검증은 아니다.
- 개발 게임 **1.0.13** SHA-256 `DADDEB4FAF1C7788B7FE78ADBDD502DB2973F40C346BD5926A4607EA58FB6881`, AI **0.1.14** `BF978F516C0497AFC68A60297A5CD248A9735B75AD8864E93A5549F10CE8D158`. AI 최소 게임1.0.13, 네트워크 protocol6 유지. 불변 게임 JAR 복사본으로 AI를 직렬 빌드했으며 이전 개발1.0.12/0.1.13은 보존했다. 두 새 JAR 중복 엔트리/새 test fixture/게임↔AI split-package 모두0이다.
- 변경 전 대상29개 백업은 `dev-tools/backups/room-recording-integration-20260921`, 보호 기준은 [78개 SHA-256](../docs/room-recording-protected-20260921.csv)이며 전부 불변이다. 서버/클라이언트 배포물·설정·주요 게임 SavedData·신 프로필·legacy JAR 등을 확인했고 전체 월드 백업을 뜻하지 않는다.
- **미완료 구분:** M1 SQLite/월드별100GB와 M2 전체 채널/정확한 수신 view/영속 멱등 RAW는 아직이다. 기존 TXT는 참가자별 관리 사본이며 날짜/청중/길이 등 기존 기억 제한이 모두 제거된 것은 아니다. SocialRuntime의 생성/설득 회복과 게임발 퀘스트 완료/평가 자동 AI 연출은 명시적 방별 포트 이전이 남아 있다. 권한을 추정하는 단일 플레이어 세션 fallback으로 연결하지 않았다.
- **서버/클라이언트 배포·실행/중지·월드/설정/기억 초기화·실제 LLM·GameTest·파일 삭제·콘텐츠 변경·커밋 없음.** 서버는 게임1.0.2/AI0.1.3/콘텐츠0.1.0을 유지한다. 실제 다인 노출/비밀·HUD·LLM 품질/부하·전체 모드 부팅 검증은 별도다.

## 53. 2026-09-21 — 게임1.0.13·AI0.1.14 재빌드 및 서버 JAR 적용 (부팅 미실행)

- 사용자 요청으로 52절의 두 모듈을 재빌드하고 `server/mods`를 게임1.0.13/AI0.1.14로 교체했다. `server/client-required-mods`의 게임도1.0.13으로 맞췄다. 절차·해시·복구 범위는 [배포 기록](../docs/ROOM_RECORDING_RELEASE_20260921.md)을 따른다. 릴리스 스킬에 따라 빌드/배포/부팅/인게임 검증을 구분했다.
- 서버 Java 프로세스와 포트를 확인해 정지 상태에서만 적용했다. 이번에는 서버를 실행/종료하지 않았으며 실제 Minecraft/Ollama/클라이언트 시험을 하지 않았다. 파일 적용 완료가 실제 부팅/대화 품질 검증 완료는 아니다.
- 빌드 입력/오버레이/최소 버전을 재확인하고 `compileJava jar` 및 관련 회귀 **게임277 + AI973 = 1,250 checks를 이번에 재실행해 PASS**했다. 변경 없는 컴파일/JAR은 UP-TO-DATE, 해시는52절의 검증본과 동일하다. 후보7JAR/246패키지 중복 mod ID·ZIP 엔트리·split-package0이다.
- 적용 게임 SHA-256 `DADDEB4FAF1C7788B7FE78ADBDD502DB2973F40C346BD5926A4607EA58FB6881`, AI `BF978F516C0497AFC68A60297A5CD248A9735B75AD8864E93A5549F10CE8D158`. 개발/서버/클라이언트 배포용 게임 해시 일치, AI 개발/서버 해시 일치. 배포 후 서버7JAR/클라이언트5JAR이며 필수5개 모두 양쪽 해시가 같고 구 JAR 중복이 없다. AI 최소 게임1.0.13·게임 protocol6이다.
- 백업 `server/backups/room-recording-release-20260921-223406`에 배포 전 mods·client-required-mods·world 전체·config·server.properties **541개/51,274,663 bytes**를 복사하고 전체 해시를 검증했다. 구 JAR3개는 삭제하지 않고 `retired`로 이동했다. 원래 월드478개/설정27개를 포함한 기존537개 파일은 배포 후에도 불변, world/config 추가·삭제도 없다. 이후 새 버전 플레이 뒤의 하향 복원은 저장 호환성을 별도 확인한다.
- 서버 설정·모델·FTB/Architectury·콘텐츠0.1.0·월드·기억은 변경하지 않았다. 별도50절 데메테르 프로필 변경은 이번 배포에서 제외했다. 신규 SQLite/100GB 및 소문/자동 퀘스트 연출의 방별 이전 등52절 미완료는 그대로다.
- **사용자 실제 런처 mods는 수정하지 않았다.** 구 MythicTRPG JAR을 모드 폴더 밖에 보존하고 [클라이언트 필수 폴더](../server/client-required-mods/README.md)의1.0.13으로 맞춰야 한다. 서버는 기존 실행기를 사용하며 최초 부팅/월드 읽기·저장/HUD·다인 공개/비밀·실제 LLM 검증은 후속이다.

## 54. 2026-09-23 — 정확한 신 ID 전용 `/ai_call`과 시험 시작 경로 통합 (개발·미배포)

- 사용자 요청: 기존 명령들이 혼동되므로 새 영어 호출 명령을 사용하고, 신 지정은 **정확한 전체 ID만** 받는다. `/ai_call <public|mobile|private> <on|off> <신 ID>...`를 추가했다. public은 PUBLIC_FIXED, mobile은 PUBLIC_MOBILE, private은 PRIVATE다. 기록 on/off를 생략하거나 true/false로 대체할 수 없고 OP2+플레이어가 필요하다.
- 채팅 누락 원인은 이전 `/ai_test` 진입점이었다. 기존 chat event가 vanilla 채팅을 취소하고 legacy adapter는 HUD 성공 시 채팅을 보내지 않았다. 최신 서버 JAR 누락이라고 단정하지 않는다. 조회 당시 `server/logs/latest.log`에는 9/23 게임1.0.13/AI0.1.14 로드 기록이 있었고 설치 JAR 해시도53절과 동일했다.
- 새 `AiCallCommands`는 로드된 AI 프로필과 게임 God Definition을 전부 검증한 뒤 게임 `ConversationRooms.create`에 위임한다. parser는 한글 이름/short path/따옴표/잘못된 전체 ID를 거절하며 공백·쉼표, stable dedup, 최대16신·2048자와 full ID-only 자동완성을 제공한다. 별도 신 원본 DB나 ID 변경은 없다. 코드·문서의 예시는 `mythictrpg:demeter`/`mythictrpg:fortuna`를 유지한다.
- `/ai_test`의 모든 하위 명령과 `/mythroom open`은 이제 새 사용법 안내만 하며 구 세션/방을 만들지 않는다. `/s`, `/mythroom list/select/leave/accept/confirm/invite_test`와 실제 게임 Encounter 관리 도구는 유지한다. 내부 legacy adapter/원본 AI 빌드 의존성은 보존했고 오버레이의 사용자 안내만 `/ai_test say` 제거했다.
- 새 시작은 기존 공개/비밀방의 `publishPlayer/publishGod → publish → RoomDialoguePublisher`를 사용한다. 공개는 온라인 전역 수신자(발언자 포함), 비밀은 참가자에게 채팅 원문을 보내며 신의 HUD 출력은 별개다. `off`도 대사를 전송하고 영속 recorder만 생략한다. 채팅 숨김/전송 실패는 표시 성공을 보장하지 않는다. 실제 클라이언트로 채팅 잔존을 관찰한 검증은 아직 아니다.
- 통합/릴리스 스킬에 따라 기존 권한 경계를 유지했다. 두 시험 기록 모드 모두 실제 아이템/관계/퀘스트 실행을 허용하지 않는다. 방 기록/참가/공간/비동기 유효성 검사는 게임에 남고 DTO·저장 형식·네트워크 protocol6은 변경하지 않았다. 현재 새 방의 중립 태그/비밀 로어/소문 후처리 제한도 확대하지 않았다.
- 컴파일/개발 JAR과 오프라인 **592검사 PASS**: 게임 방147·공통 발행19, AI full ID67·실제 명령/Brigadier210·기존 parser59·방 엔진90. 실제 세 명령 소스를 격리 컴파일해 3종×on/off, OP/콘솔, 전체 선검증, 옛 명령 안내-only, 자동완성과 게임 거절 전파를 검사했다. 게임/콘텐츠 경계 stub을 사용한 명령 검사는 Minecraft 전체 실행을 대신하지 않는다. GameTest·실제 LLM·다인 UI는 실행하지 않았다.
- 개발 산출물: 게임 `mythictrpg-main/build/libs/mythictrpg-1.0.14.jar` SHA-256 `2EF8C50176A16FA63FD76A4EBD7A17F2340D2256F6F80EA6F0EC75E2BBEAF89D`, AI `mythai-ai-response/build/libs/mythai_ai_response-0.1.15.jar` SHA-256 `7BC6E15B183EC57A441CC651509BC5EFC69E8A429B2C3BCF102E76A230DDA12D`. 게임 완료 후 AI를 직렬 빌드했다. AI 최소 게임1.0.14로 맞췄고 이전 개발 JAR을 보존했다. 두 JAR 중복 엔트리0/새 명령 test fixture 포함0, AI JAR의 새 명령/파서 포함을 확인했다.
- 변경 전 대상11파일은 `dev-tools/backups/ai-call-command-20260923`에 보존했다. [명령 가이드](../docs/AI_DIALOGUE_TEST_COMMAND.md)와 [대화방 가이드](../docs/CONVERSATION_ROOMS.md)를 갱신했다. 기존 미커밋 변경·legacy mine 소스/JAR·프로필·전역 설정·월드·기억은 보존했다.
- **새 JAR은 미배포.** server/mods 게임1.0.13/AI0.1.14와 client-required-mods 게임1.0.13 해시가53절과 같음을 재확인했다. 서버 실행/중지·실제 런처 수정·월드 초기화·커밋은 하지 않았다. 다음 적용은 정상 종료 확인 후 서버 두 JAR 및 클라이언트 게임 JAR을 맞추고, 공개 일반 채팅/비밀 `/s`에서 플레이어·신 대사의 채팅 표시와 비참가자 차단을 직접 확인하는 것이다.

## 55. 2026-09-23 — 원래 목표와 새 대화방 재통합 (개발 소스·오프라인, 미배포)

- 사용자 요청에 따라 [06 설계](../추가개발/06_원래목표_대화방_재통합.md)를 작성하고 현재 방 구조에 누락된 연결을 구현했다. 상세 계약·검증·한계·산출물 해시는 [재통합 기록](../docs/ORIGINAL_GOALS_REINTEGRATION_20260923.md)을 따른다. 구 LP/단일 세션을 통째 복원하거나 기존 공개/비밀·다인방을 폐기하지 않았다.
- 현재 화자→참가 신의 방향별 동적 score/tags를 대화·무리 선택에 연결하고 관계/방/청중 revision을 재검증한다. 원인·비밀 사건 history·상대의 내면은 주지 않는다. 등록된 AI 허용 전이의 소유 화자·관련 신 참가 여부를 확인하며 실제 변경은 게임에서 검증·실행한다.
- 04에 현재 Ollama provider를 등록하고 03이 확정한 사실의 선택적 AI 연출/fallback을 복구했다. 일반 방에는 전체 청중에게 공개 가능한 Story 의미만 준다. Hook 별칭→서버 token 정규화는 명시적 방에 귀속하며 자동 제안은 현재 플레이어1·신1 PRIVATE 일반방으로 제한한다. 부재/봉인·정의/사건/조건 변경·확인 만료를 재검증한다. AI가 사건 확률·결과를 정하지 않는다.
- 게임 발행의 실제 청취 신과 완전한 채팅 전달 증명을 이용해 신별 기억을 연결했다. 기억 소유자와 발화 신을 분리하고 플레이어/현재 신/다른 신/정확한 ID의 회상 출처를 검사한다. 같은 문장의 다른 화자를 dedup으로 지우지 않는다. Story/관찰/소문 근거 대사는 일반 영구 기억으로 복사하지 않고 철회 가능한 RAM provenance를 유지한다. OFF에서도 근거 검증은 유지한다.
- 플레이어1·신2 방에서 Primary 전달 후 다른 신이 독립 프로필·현재 관계·허용 기억·실제로 들은 history로 한 번 반응하거나 침묵한다. Secondary는 분류 없이 선택적 생성1회이며 모든 게임 Proposal/방 제어가 금지된다. 원래 플레이어 입력의 청취 우회는 없고 기억 질의도 필터된 신 발언을 사용한다. 다른 규모는 기존 한 신 응답을 유지한다. 무인 장시간 자율 토론·예시 강제 전개는 구현하지 않았다.
- 세션 안전 보완: GodState는 자기 것만 전달, feedback는 방+신+플레이어별 격리, 새 입력/방 변경으로 오래된 결과 폐기, 실제 전송 증명 뒤 신 식별 반영. 식별이 동기 사건을 발생시키더라도 전체 대사의 guard를 먼저 등록하고 전송 직후 만료된 근거는 차단용 provenance로 보존한다. 봉인/부재 신은 발화·청취·행동 scope에서 제외한다.
- 최종 게임 `compileJava jar` 및 관련7회귀 **421**, 이후 AI `compileJava jar check --continue` **4,859**, 합계 **5,280개 오프라인 검사 PASS**. 별도 기존 로그 가드37위치 확인. 공개 조합/반복/합성 검색과 일부 실제 서비스+stub 검사를 포함한다. 2,000행/15ms 검색 진단은5회 중3회 예산 초과였고 저지연·실제 자연스러움 보장이 아니다. GameTest·서버/클라이언트·실제 모델은 실행하지 않았다.
- 개발 게임 **1.0.15**, AI **0.1.16** JAR 생성/검사 완료. AI 최소 게임1.0.15, protocol6 유지. 두 JAR 중복 엔트리0/새 test fixture0/게임↔AI split-package0. 서버/클라이언트 배포용 게임1.0.13 및 서버 AI0.1.14 해시는53절과 동일하다. **배포·서버 실행/중지·월드/설정/프로필/운영 기억 변경 없음**, 기존 미커밋 변경과 legacy 빌드 입력도 보존했다.
- 저장 호환 주의: 새 선택적 `speakerGodId`는 과거 NPC 기억을 읽을 수 있지만 새 청취 기록 생성 후 구 AI로 단순 하향하면 다른 신의 말을 소유 신의 말로 오해할 수 있다. 배포 전에 기억 백업/하향 호환 검토가 필요하다. 지금 서버 기억을 마이그레이션한 것은 아니다.
- **남은 범위:** 05 M1~M5 전체채널 RAW/SQLite·100GB/정확한 수신 view·지식/검색 통합과 M7 운영 수용은 별도다. 새 방의 소문 생성/설득 회복 및 게임발 퀘스트 완료/평가 자동 연출, 일반 SECRET 로어 공개, 관계 수치→단계/누적 감정 규칙도 완성했다고 하지 않는다. 이번 것은 기존 기억 기반의 제한된 순차 반응까지 연결한 것이며 전체 장기기억 설계 완료가 아니다.

## 56. 2026-09-23 — 원래 목표 재통합 구현본 서버 파일 배포 (미부팅)

- 사용자 배포 요청으로 게임 **1.0.15**와 AI **0.1.16**을 `server/mods`, 게임1.0.15를 `server/client-required-mods`에 설치했다. AI mod metadata의 최소 게임 버전은1.0.15이며, 콘텐츠와 NeoForge 의존성은 현재 설치 기준을 충족한다. 활성 `mods`에는 같은 MythicTRPG/AI 구버전 JAR이 남아 있지 않다.
- 교체 전 `server/mods` 30개, `client-required-mods` 6개, `world` 480개, `mythictrpg-ai-data` 1개 총 **517개/51,294,818 bytes**를 `server/backups/original-goals-reintegration-20260923`에 백업해 SHA-256 일치 확인했다. 구 JAR은 backup의 `retired-install` 및 `retired-client-required-mods`에 보관했고 원본 설치본은 [배포 기록](../docs/ORIGINAL_GOALS_REINTEGRATION_20260923.md)에 기록했다.
- 서버 JAR SHA-256: 게임 `05986AA6C90CC71EAD59EE4DD7E0C6774B66788E54D01D9B5D888036AE8CACF7`, AI `001F90D6FFBB08BEC20F0D4AD188B3E6E80A99DCDB302CFA1030F09917F9F6BF`. 클라이언트 필수 게임 JAR도 같은 게임 해시다. 기존 FTB/Architectury/콘텐츠 파일과 설정을 변경하지 않았다. 실제 Minecraft 런처의 클라이언트 폴더는 이번 작업 범위 밖이다.
- 배포 전에 Java 프로세스와25565 listen port가 없음을 확인했다. 배포 후에도 서버를 부팅하지 않았으며 새 모드 로드, Client 접속, `/ai_call`, 실제 Ollama 대화·Story 연출은 미검증이다. 부팅 및 실제 수용 확인이 다음 단계다.

## 57. 2026-09-23 — 기존 대화방 전체에 다섯 목표 구현 (개발 빌드, 미배포)

- 사용자 요청은 초기 검증용 1인·2신/단독 비밀방으로 한정하지 않는 완성 구현이다. 55절의 해당 제한을 대체한다. 공개 고정/이동형·비밀방·동시 참가·병합/분리의 기존 게임 참가 구조는 유지했다. 단일 최신 계약·검증 기록은 [전체 방 통합](../docs/FULL_ROOM_DIALOGUE_INTEGRATION_20260923.md), 기억 저장 세부는 [신별 청취 기억](../docs/ROOM_HEARD_MEMORY_20260923.md)을 따른다.
- Primary 실제 전달 뒤 현재 다른 참가 신 각각이 자신의 성격·방향별 정적/현재 동적 관계·허용 기억으로 한 번 반응 또는 침묵한다. 후보 실패는 나머지 후보를 막지 않는다. 새 입력/방 변경/청중·근거·관계 변경은 오래된 응답을 적용하지 못하게 한다. 플레이어 없는 장시간 자율 대화는 만들지 않았다.
- 일반 방 및 작성된 사건 연출에 전체 청중의 Story 사실/위장 설명/Hook 정책을 연결했다. 모델이 선택한 canonical 문구를 게임이 실제 전달한 뒤 플레이어/신에게 단계순 지식을 준다. Cover는 진실 지식을 주지 않으며 시험 방은 게임 지식을 변경하지 않는다. Secondary도 자기 허용 Hook만 제안 가능하다. 부재 신의 출발 알림은 작성된 정식 연출의 일시적 권한으로만 허용한다.
- 새 `world + 실제 청취 God` 원장은 원문·실제 발화자·완전 수신자·청취 신·근거 계보를 보존한다. 질문자가 달라져도 허용 기억을 조회한다. PUBLIC/PRIVATE, OFF 비영속, PERSONAL/RUMOR_TEST 분리와 재시작 뒤 Story·콘텐츠·옛 개인 기억/소문·관찰 원본의 재검증을 연결했다. 과거 journal 일괄 이관이나 SQLite/100GB 전체채널 아카이브를 완료한 것은 아니다.
- 정적 로어 단계·프로필 필드·예시·퀘스트 후보 텍스트의 보유 지식/청중 공개를 사전 교차한다. 비밀을 모두 넣은 뒤 말하지 말라고만 지시하지 않는다. 퀘스트 후보의 공개 필터는 수락·보상 엔진을 바꾸지 않는다. 실제 기존 콘텐츠 정사/신 프로필을 이 기능 작업으로 새로 작성하지 않았으며 사용자 예시 사건도 설치하지 않았다.
- 개발 버전: 게임 **1.0.16**, AI **0.1.17**, 콘텐츠 **0.1.1**. AI 최소 게임/콘텐츠 버전을 함께 올렸다. 세 모듈 `build` 성공, 게임 오프라인 33,309 / AI 4,996 / 콘텐츠 70 검사, 실제 게임 단독 GameTest 204/204 및 세 모듈 동시 로드 전용 GameTest 1/1 통과. 후자는 실제 AI 엔진/레지스트리/발행/영속 저장/교차 질문자 회상이며 LLM transport만 모의한다. 게임 수에는 기존 바이옴 격자 22,945검사가 포함된다. 모델 사용권 반납과 검색 Future 완료의 경쟁도 수정하고 결정적 회귀를 추가했다. 개발 산출물 해시·실패 후 재검증 내역은 통합 기록을 따른다.
- **운영 배포 없음:** 56절의 서버 게임1.0.15·AI0.1.16 및 client-required 게임1.0.15 SHA-256이 그대로임을 확인했다. 개발 GameTest만 별도 `build` 디렉터리에서 실행했다. 운영 서버 시작/중지·월드/설정/기억 초기화·실제 클라이언트 수정·실제 Ollama 호출은 하지 않았다. 실제 모델 문장 품질·클라이언트 HUD·다인 지연/장기 부하는 미확인이며, 코드/자동 테스트 완료와 구분한다.

## 58. 2026-09-23 — 전체 대화방 통합본 서버 파일 배포 (미부팅)

- 사용자 배포 요청으로 검증된 게임 **1.0.16**, AI **0.1.17**, 콘텐츠 **0.1.1**을 `server/mods`에 설치했다. `server/client-required-mods` 게임도 1.0.16으로 맞추고 서버와 SHA-256 일치를 확인했다. 실제 Minecraft 런처의 클라이언트 폴더는 수정하지 않았다. 상세 해시·검증·배포 기록은 [전체 방 통합](../docs/FULL_ROOM_DIALOGUE_INTEGRATION_20260923.md)을 따른다.
- 배포 전 Java 프로세스와 25565 listen port가 없음을 확인하고 모드·클라이언트 필수 파일·월드·설정·기억/대화 파일 등 **546파일 / 52,728,412 bytes**를 `server/backups/full-room-dialogue-20260923`에 해시 검증하여 백업했다. `backup-manifest.csv`에 원본 목록/크기/해시를 남겼고, 구버전 JAR은 `retired-mods` 및 `retired-client-required-mods`에 추가 보관했다. 배포 후 월드·설정·기억 등 보호 대상509파일의 변경0을 확인했다.
- 활성 7개 JAR의 의존성과 중복 mod ID/클래스/패키지 충돌 없음, 기존 월드 데이터팩의 새 JAR 리소스 덮어쓰기 없음을 검사했다. 콘텐츠0.1.1에는 50절의 기존 사용자 요청에 따른 데메테르 프로필 보강도 포함된다. 이번 작업에서 새 프로필/정사를 작성한 것은 아니다. 기타 모드와 월드·설정은 유지했다.
- **파일 배포 완료, 서버 부팅은 하지 않음.** 이 배포 작업에서 새 모드의 운영 서버 로드·실제 Ollama 대화·클라이언트 접속/HUD를 확인한 것은 아니다. 이전 개발 GameTest 통과와 운영 실사용 검증을 구분한다. 배포용 스크립트는 `dev-tools/Deploy-FullRoomDialogue-20260923.ps1`이며 같은 백업 폴더를 덮어쓰는 재실행은 거절한다.
