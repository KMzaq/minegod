# HanesTest — 실험 브랜치 인수인계

최종 갱신일: 2026-10-07. 대상 브랜치: **`HanesTest`**.

**2026-10-07 후속 실험:** 개발 게임 **1.0.27 / AI 0.1.31 / 콘텐츠 0.1.3**, 미배포. [주 응답 지침 간결화·문맥 예시 비교](COMPACT_DIALOGUE_PROMPT_20261007.md)를 적용했다. 동일 장면 12개×3회, 기존/변경 총36쌍을 실제 Gemma로 비교했다. 입력 토큰은 약9.8% 감소했고 일부 대화 진행이 개선됐지만, **전체 자연스러움 개선은 확인되지 않았으며 지식·실행 상태 오류가 남는다.** 관련544검사·JAR 검사 통과는 의미 품질 합격이 아니다. 아래 0.1.30 기록의 단회 결과와 구분한다.

**2026-10-07 개발 갱신:** 게임 **1.0.27 / AI 0.1.30 / 콘텐츠 0.1.3**, 미배포. [대화 의미·게임 실행 근거 개선](DIALOGUE_GROUNDING_20261007.md)을 추가했다. 페르소나 수정 없이 주체/대상 해석, typed 결과, 선택적 검토·1회 수정을 연결했다. 집중 검사·모의 LLM 개발 GameTest·실 Gemma 합성 평가를 마쳤다. 자연스러움 완성이나 모든 환각 차단을 뜻하지 않으며 최종 9개 실모델 사례 중 1개는 재거절로 출력 차단됐다. 아래 10월 4일까지의 버전/이력은 당시 상태다.

이 문서는 페르소나 중심 NPC 하네스 개선을 시험하는 브랜치 전용 기록이다. 공용 기준에 병합되었거나 운영 서버에 적용되었다는 의미가 아니다.

**2026-10-04 당시 개발 작업본**은 **게임 1.0.26 / AI 0.1.29 / 콘텐츠 0.1.3**이며 **미배포**다. 최신 추가는 [NPC 일상 활동 기억·감정 연결](NPC_ACTIVITY_MEMORY_20261002.md)이며 [퀘스트 접촉 확인·영구가호·가호 전투력](QUEST_CONTACT_BLESSINGS_20261002.md), [NPC 퀘스트 참가자 재편성](QUEST_REORGANIZATION_20261002.md), [NPC 공간 인식·생활활동](NPC_ACTIVITIES_20260930.md)을 유지한다(게임 protocol 10). 아직 모든 시스템이 완성된 것은 아니다. 아래 후속 기록과 [시스템 완성 작업표](SYSTEM_COMPLETION_TRACKER.md)의 완료 범위·미완료 이유를 먼저 확인한다. 실제 `server/mods`는 이번 작업에서 교체하지 않았으며 10월 3일 파일 확인은 게임 1.0.16 / AI 0.1.19 / 콘텐츠 0.1.2다. 운영 서버 부팅·실제 LLM 대화 확인과 개발용 모의 통합 검사를 혼동하지 않는다. 앞선 기록은 각 개발/배포 시점의 이력이다.

## 문서 사용 원칙

**콘텐츠 작성 기준(2026-10-04):** 신규 God ID와 프로필 파일명은 사용자 확정 `소속신화_소속_신이름`의 소문자 영어로 작성한다. 기존 테스트 ID의 게임 정의·참조·월드 상태는 자동 이관하지 않았다. [명명 기준·전달 양식·현재 파일 연결 상태](CONTENT_AUTHORING_IDS_20261004.md)를 참고한다. 자료 제작과 문서 갱신만이며 운영 배포/등록 검증을 뜻하지 않는다.

**최신 개별 요청(2026-10-02 시작·10-03 재개):** 실제 NPC 활동의 시작·완료·실패·중단 및 실제 주변 발화를 기존 활동 저장소의 신별 경험으로 연결했다. 기존 자율 선택 호출에서 선택적으로 감정을 해석하고, primary/secondary 대화에는 현재 청중에 허용된 자기 경험만 제공한다. 비밀방 감정·관계 수치와 분리하며 OFF/임시방, 지연 응답, 출처 및 프롬프트 예산을 검증했다. 활동 GameTest 7/7과 실제 모듈 방 대화·후속 회상 1/1, 관련 단위 검사를 통과했다. [구현·검증·한계](NPC_ACTIVITY_MEMORY_20261002.md)를 우선한다. 무한 기억, 새 아카이브 전체 전환, 운영 배포/활성화 또는 실모델 자연스러움 검증을 완료했다는 뜻은 아니다.

**최신 개별 요청(2026-10-02 후속):** 목표 달성만으로 신이 방문/완료하지 않도록 실제 접촉 게이트를 연결했다. 원격은 현재 주시 중이고 독서·산책 등 다른 활동이 없을 때만 가능하며, 비주시 플레이어는 수주/지정 장소의 대면 또는 실제 재조우가 필요하다. 영구가호는 우유 제거를 유지하고 `/mythblessing reapply`·사망 부활로 복구하며 보유 기반 효과별 전투력을 계산한다. [이번 구현·검증·호환 기록](QUEST_CONTACT_BLESSINGS_20261002.md)을 우선한다. 일반 원격 자유 대화 신규 시스템, 자동 방문 개시, 운영 콘텐츠/배포를 완료한 것은 아니다.

**최신 개별 요청(2026-10-02):** 처음 선택된 사람만 그 신의 메인을 진행하는 의도를 유지하면서, 고정 단체/전원제출 랭킹의 부재자 정체를 풀기 위한 NPC 재편성을 구현했다. 퀘스트별 opt-in이며 본인 포기·부재 제외·후보 본인 동의·기존 제출 보호·반환 정책을 서버가 검증한다. [설정/호환 안내](../../../mythictrpg-main/docs/QUEST_REORGANIZATION_GUIDE.md) 및 [이번 기록](QUEST_REORGANIZATION_20261002.md)을 따른다. 기존 실행 자동 이관·활성 퀘스트 설정 변경·서버 배포·실제 LLM 대화 검증은 하지 않았다.

**현재 작업 범위/대기(2026-09-30):** LLM/게임 연결 묶음 마감 후 별도 사용자 요청으로 플레이어형 스킨과 **NPC 생활활동 묶음**을 추가했다. 사용자의 일반 “쓰지 마”는 신이 거절할 수 있고 관리자 금지는 항상 집행한다. [최신 활동 기록](NPC_ACTIVITIES_20260930.md)의 구현·검증·미완료 범위를 우선한다. 무관한 후속 시스템과 운영 배포를 자동 재개한 것은 아니다. 외형은 [스킨 추가 안내](../../../mythictrpg-main/docs/NPC_SKINS.md), 앞선 게임 연결 범위는 [작업표](SYSTEM_COMPLETION_TRACKER.md)를 따른다. 활동 자세/소품 metadata가 추가되어 게임 protocol은 **10**이다.

**현재 우선순위(2026-09-30 사용자 변경):** LLM 강화는 진행하던 schema11 [해석 근거 발급](RECORDING_V2_NATIVE_INTERPRETATION_SEAL_20260930.md)·[typed foreground 준비](RECORDING_V2_NATIVE_INTERPRETATION_FOREGROUND_20260930.md) 범위에서 마무리한다. 다음 작업은 신 타격·공물·가호·퀘스트·건축·레이드·상점의 기존 실행 경로를 확인하고 누락 시스템을 연결하는 것이다. 새 기억의 NEW 자동 전환, 교차 신 해석 공유, Watch/Rumor 출력 근거 확장과 추가 LLM 고도화는 후순위이며 미완료로 남긴다. 기존 대화 경로는 유지한다. 사용 가능한 게임 시스템까지 LLM 정비를 이유로 계속 미루지 않는다.

**현재 LLM 묶음 검증:** 게임 focused18 tasks/65초(typed SQL178·Session133·기존 RAW202 포함), AI focused13 tasks/13초(typed120·483 classes/게임 중복0), 마지막 비인용 입력 그룹 fixture 보강 후 typed121 단독7 tasks/7초 통과. 실제 typed 발행→다음 턴 회상→재추출 후 차단 GameTest1/1(1.025초), 기존 CONTENT 후손 회귀1/1(878.5ms) 통과. 실제 모델·운영 배포·NEW 활성화는 하지 않았다. 이 묶음의 완료는 전체 기억 전환 또는 RPG 완성이 아니다.

- 기본 프로젝트 구조와 기존 배포 상태는 [공용 인수인계](../../PROJECT_HANDOFF.md)를 읽는다. 이 브랜치의 추가 변경과 검증 상태는 이 문서를 우선한다.
- 사용자 요청에 따라 이번 실험의 기록은 이 폴더에서만 갱신한다. 공용 인수인계와 기존 통합 가이드에는 실험 결과를 자동으로 반영하지 않는다.
- 공용 기록에 앞서 추가했던 하네스 개선 배너와 59절, 통합 가이드의 후속 개발 배너를 이 폴더로 분리했다. 기존 공용 기록은 보존한다.
- 상세 구현·검증·비교 실행 방법은 [페르소나 하네스 기록](NPC_PERSONA_HARNESS_20260927.md)을 따른다.
- 관계 단계·힘/후원 근거 입력의 최신 변경은 [신의 자율성·사회맥락](DIVINE_SOCIAL_CONTEXT_20260929.md)을 따른다.
- 브랜치 병합, 서버 배포, 실제 모델 비교 실행은 각각 별도 작업이다. 이 문서 이동은 코드 변경이나 서버 배포가 아니다.

## 2026-09-27 — 페르소나 중심 NPC 하네스 개선 (개발 검증·미배포)

- 사용자는 몰입 가능한 세상에서 NPC가 자신의 페르소나에 따라 상호작용하도록 하네스 개선 구현을 요청했다. 기존 엔진을 교체하지 않고 현재 대화방 Primary의 생성/후처리를 `RoomPersonaPrompt`로 분리했다. 기본 분류 흐름과 gameplay proposal 게이트, Secondary 선택적 반응, 게임의 권한·참가자/공개·기억/Story 근거 검증은 유지했다.
- 잡담에도 성격·가치·말투·제약을 유지하고 실제 대화·현재 발화·관계/감정·기억/허용 로어·게임 결과를 구분한다. 분류/말투 태그와 활동명은 가설/잠정 힌트다. 인사를 `안녕.`으로 고정 치환하거나 특정 표현을 이유로 재생성하지 않는다. 생성 문장의 여러 문장을 보존하며 구조 문제만 한 번 교정한다. 새로운 감정 수치 저장·관계 단계 매핑·게임 행동 엔진을 만든 것은 아니다.
- 메모리의 신/플레이어/방과 명시된 lease가 현재 요청에 속하는지 **분류 전부터** 검사한다. Session A/B Quest/Reward constraint, validator feedback, history/기억 격리 회귀를 유지했다. 코드의 실행 권한을 프롬프트 금지 규칙으로 대체하지 않았다. 12,000자 데이터 문맥 한도 초과 시 필수 권한을 자르지 않고 실패한다.
- AI **0.1.18** 최종 `build exportNpcHarnessComparison --offline --no-daemon` 성공. 오프라인5,139 checks(새 helper137·RoomPrompt/룸96 포함), PowerShell 도구33 checks, 세 모듈 실제 로드 전용 GameTest1/1 통과. 실제 프로필/청중 필터/Primary→Secondary/발행/청취 기억은 검사했지만 LLM transport만 모의했으므로 모델 품질 검증이 아니다. GameTest는 `mythictrpg-main/build/persona-harness-gametest-20260927`에서 자동 종료했다.
- `exportNpcHarnessComparison`과 `dev-tools/Compare-NpcHarness.ps1`로 합성10사례의 기존/새 프롬프트 비교를 준비했다. 같은 분류/과거 대화를 고정한 생성 비교이며 전체 2단계·연속 대화 rollout이 아니다. 기본 dry-run은 무통신, `-Execute`에서만 로컬 Ollama를 사용하고 결과는 개발 build 폴더에만 저장한다. 실제 모델 사용 여부를 질문했으나 답변이 없어 이번에는 실행하지 않았다.
- 게임 쪽 변경은 통합 검사 대상 AI JAR을 선택하는 `build.gradle`의 `roomAiResponseJar` 옵션뿐이다. 실행 Java·콘텐츠 프로필·모듈 간 IO는 바꾸지 않았다. `mine/mine` 원본과 결합 JAR도 그대로다. 개발 검사 과정에서 AI 프로젝트의 추적된 압축 debug 로그5개가 회전됐으며 소스 변경과 별개다.
- **운영 미배포:** 서버/mods·클라이언트·월드·설정·운영 기억은 변경하지 않았고 운영 서버는 시작/중지하지 않았다. 설치본은 게임1.0.16/AI0.1.17/콘텐츠0.1.1. 다음은 허가된 실제 모델 비교, 결과 검토, 별도 배포와 인게임 확인이다. 현재 게임의 일부 관계/감정 label은 기본값이고 수치→관계 단계 매핑은 UNDEFINED이므로 합성 친밀/분노 입력 검사를 완전한 실서버 관계 연동으로 해석하지 않는다.

## 2026-09-27 — Minecraft 공용 기본 지식 (개발 검증·미배포)

- 요청: `바다의 심장` 등을 실제 Minecraft 아이템으로 이해하고, 거창한 신물·추상적 개념으로 임의 해석하지 않도록 기본 상식을 공급한다. [상세 구조·작성·조회 규칙](MINECRAFT_COMMON_KNOWLEDGE.md)을 참고한다.
- 콘텐츠 레지스트리 **0.1.2**에 공개 전용 `common_knowledge` JSON 범주와 불변 `publicCommonKnowledge()` 조회를 추가했다. 신별 비밀 로어·지식 단계·청중 공개 정책은 변경하지 않았다. 바다의 심장·전달체·토템·엔더 아이템·광물·농사 등 17항목을 실제 Minecraft 1.21.1 자료와 대조했다. 기존 Snapshot 생성자 호환은 유지한다.
- AI **0.1.19**에서 현재 방의 발화·최근 허용 대화·화자 게임 문맥으로 최대 4항목/1,800자를 선택한다. 1단계 분류, Primary 생성, Secondary 반응에 공급한다. Secondary는 실제 전달 이력만 보고 원시 현재 입력은 검색하지 않는다. 한글 공백·영문·전체 ID와 한 글자 명사/조사 경계를 구분하며, 다른 모드 네임스페이스·다른 방·원본 비밀 로어를 섞지 않는다.
- 기본 지식은 게임 상태와 분리된 공개 참고다. 서버의 명시적 재정의가 우선하고, NPC 성격·감정·대사를 고정하지 않는다. 플레이어 소유·지급·퀘스트 완료·실행 권한을 생성하지 않는다. 문맥 예산 부족 시 선택적 기본 지식을 먼저 줄인다. 게임 실행 Java, God ID, 프로필, 퀘스트·보상 규칙, 기존 `mine/mine` 입력은 이번 변경으로 수정하지 않았다.
- **검증:** 응답 `assemble` 및 관련 5검사 태스크 **447 checks** 통과(공용 검색156 / Primary144 / Secondary32 / 기존 지식 경계19 / 방 엔진96). 콘텐츠 JAR 생성 및 신규123·기존 공개 정책70, **193 checks** 통과. 합계640개의 관련 오프라인 검사이며 전체 회귀·GameTest·실제 Ollama·HUD 검증을 완료했다는 뜻이 아니다. 최종 응답 빌드는 루트 wrapper로 성공했다. 중간 별도 빌드의 클래스 누락 실패는 동시 빌드 충돌 가능성이 있었으며 최종 성공 결과와 구분한다.
- **패키징:** 응답 JAR의 새 공급자·두 프롬프트·생성 오버레이 클래스와 콘텐츠 최소 의존성 `0.1.2`를 확인했다. 콘텐츠 JAR에 17개 JSON이 모두 포함되고 최종 원본과 일치하는지 확인했다. 모듈 작성 가이드에 새 스키마·조회 계약을 기록했다.
- 개발 산출물: `mythai-ai-response/build/libs/mythai_ai_response-0.1.19.jar`, SHA-256 `E3BA2CF5E2CF1B53F0C75861F8FAF48D2391C214E5A22E0C4BE3A1FB7502BB7A`; `mythai-ai-content-registry/build/libs/mythaiaicontent-0.1.2.jar`, SHA-256 `CA81C37988392EB2DC6AA03B2EE1E3E377F985E2B10FE3C51068803F2760CD95`.
- **미배포·실모델 미확인:** 서버/클라이언트 JAR·설정·월드·운영 기억을 교체하지 않았고 운영 서버나 LLM을 실행하지 않았다. AI 0.1.19를 시험할 때는 콘텐츠 0.1.2도 함께 필요하다. 현재 게임 개발용 통합 테스트 설정의 콘텐츠 JAR 기본값은 여전히 0.1.1이므로, 새 조합의 GameTest를 실행하려면 해당 개발 테스트 입력도 별도로 맞춰야 한다. 실제 모델에서 오해가 완전히 사라졌다는 보장은 아직 하지 않는다.
- 공용 `인수인계/PROJECT_HANDOFF.md`와 기존 전체 방 통합 가이드는 수정하지 않았고 Git 차이 없음을 재확인했다. 이 후속 상태는 HanesTest 폴더에만 기록한다.

## 2026-09-27 — HanesTest 서버 파일 배포 (미부팅)

- 사용자 요청에 따라 `server/mods`의 AI **0.1.17 → 0.1.19**, 콘텐츠 **0.1.1 → 0.1.2**를 교체했다. 위 개발 산출물 SHA-256과 배포 파일의 일치를 확인했다. 게임 **1.0.16**, 다른 모드와 `client-required-mods`는 그대로다. 클라이언트 파일을 새로 설치할 필요는 없으며, 실제 런처를 조작하지 않았다.
- 배포 전 및 교체 직전에 Java 프로세스와 25565 listen port가 없음을 확인했다. 서버를 시작하거나 중지하지 않았으며 Ollama 요청도 보내지 않았다.
- 백업: [server/backups/hanestest-common-knowledge-20260927](../../../server/backups/hanestest-common-knowledge-20260927). 모드·클라이언트 필수 파일·월드·설정·AI 데이터/대화 기록·운영자 목록·실행기 등 **557파일 / 52,999,467 bytes**를 복사하고 SHA-256을 확인했다. `backup-manifest.csv`에 원본 경로·크기·해시가 있다. 구버전 두 JAR은 활성 mods 밖의 `retired-mods`에도 보존했다. 삭제하지 않았으므로 되돌릴 수 있다.
- 교체 직후 대상 JAR 두 개를 제외한 보호 대상 **555파일의 해시 일치**를 확인했다. 이후 의도적으로 수정한 파일은 서버 `README.md`의 실험 배포 안내와 설치 목록뿐이다. 월드·플레이어·설정·기억·클라이언트 파일은 유지했다.
- 정적 검사: 최종 후보 7개 JAR의 요구 버전 충족, 중복 mod ID/클래스/split-package/ZIP 엔트리 없음. 콘텐츠 JAR은 기존 리소스 42개를 그대로 유지하고 공개 기본 지식 17개만 추가한다. 기존 월드 데이터팩 두 개가 새 콘텐츠 리소스를 덮지 않음을 확인했다. 이 검사는 실제 서버 로드나 모델 품질 검증을 대신하지 않는다.
- 배포 스크립트: [Deploy-HanesTest-20260927.ps1](../../../dev-tools/Deploy-HanesTest-20260927.ps1). 기존 백업을 덮는 재실행, 예상 해시/파일과 다른 상태, Java 실행 중 교체를 거절한다. 이미 성공한 스크립트를 다시 실행하지 않는다.
- **다음:** Ollama를 준비하고 `server/start-neoforge-ai-server.bat`으로 서버를 실행한 뒤 실제 대화에서 아이템 의미·NPC 말투를 확인한다. 현 시점은 파일 배포 완료이지 서버 부팅/인게임 테스트 완료가 아니다. 공용 인수인계와 기존 공용 통합 가이드는 수정하지 않았다.

## 2026-09-29 — 신의 자율성·관계·힘/후원 근거 (개발 검증·미배포)

- 요청: 신은 기본적으로 인간보다 높은 신격/사회적 위상을 가진 독립적인 존재이며 무조건적 도우미가 아니다. 관계, 현재 반응, 확인된 힘과 다른 신의 후원 등에 따라 자연스럽게 달라지도록 구현했다. [상세 계약/설정](DIVINE_SOCIAL_CONTEXT_20260929.md)을 먼저 읽는다.
- 공통 `DivineSocialPrompt`를 Primary/Secondary/분리 판단에 공급한다. 호감·존중·두려움·위상·전투 우위·의무를 혼동하지 않고, 높은 호감이나 강한 상대도 자동 복종/도움을 뜻하지 않게 한다. 분류/생성 2단계와 실제 게임 실행 권한은 유지했다. 데메테르·포르투나 프로필과 작성 가이드는 기존 스키마로 독립성/조건부 반응을 보강했다.
- 게임 `RoomSocialContext`가 실제 플레이어별 affinity를 읽고 9단계를 선택한다. 기본 테스트 경계는 -900/-600/-300/-100/100/300/600/900이며 `config/mythictrpg/ai-affinity-tiers.json`으로 교체 가능하다. 사용자에게 임시 경계를 제안하고 JSON 변경 가능한 테스트 기본값으로 진행했다. 영구 밸런스 확정이 아니며 호감도 원본이나 보상 규칙은 변경하지 않았다. 현재 감정은 무조건 중립 대신 `E_UNASSESSED`다.
- 새 `RoomSocialContextProvider`는 기존 게임 서비스가 확인한 힘·후원·의무·평판을 출처/증거/화자 지식/전체 청중 권한과 함께 공급하는 인터페이스다. 기본 등록 공급자는 없고 미제공 값은 `UNKNOWN`이다. 전투 서열·보호 계약·다른 신의 즉시 개입을 생성하거나 자동 추정하지 않았다. 실제 관련 게임 기능이 구현되면 이 Adapter에 연결해야 한다.
- 턴/분리 판단의 관계·정책·공급자 revision·증거·청중을 결과 적용 전 다시 확인한다. 세션/화자/참가자별 정보를 혼합하지 않으며 참조된 후원자는 자동 참가하지 않는다. 방 초기화는 공급자 등록을 유지하고 실제 서버 종료 때 정리한다. 분리는 현재 발화자 없이 `SYSTEM_SPLIT_CONTEXT`로 전달한다.
- 독립 리뷰가 발견한 비밀방 주시 기억의 오래된 `tier_mapping: UNDEFINED` 안내를 수정했다. `ExperienceMemory`는 숫자 근거만 제공하고 게임 사회맥락의 관계 단계를 덮지 않는다. 미정 관계를 임의로 계산하는 다른 원본을 만들지 않는다.
- **빌드/오프라인:** 게임 `assemble` + 사회맥락/대화방/신 관계/턴/행동 게이트 5검사 통과(2,417 assertions 중 2,001은 전체 affinity 정수 구간 검사). AI `assemble` + Primary/Secondary/방 엔진/지식/퀘스트/기본상식/경험 기억 7검사 516 checks 통과. 콘텐츠 `assemble` + 공개/퀘스트/공용지식 3검사 193 checks 통과. 합계 3,126이며 자연스러움 품질 평가 횟수가 아니다. JSON/링크/형식과 세 JAR의 중복 클래스 없음, 의존성 메타데이터를 검사했다.
- **실제 모듈 통합:** 게임 1.0.17·AI 0.1.20·콘텐츠 0.1.3을 개발용 `build/divine-social-focused-gametest-20260929`에 함께 로드하여 전용 GameTest **1/1 통과**. 서로 다른 플레이어/신 affinity가 실제 `R_TRUSTED`/`R_HOSTILE`과 해당 프로필 지침 선택으로 이어지는지, Primary→Secondary 출력/공개 필터/청취 기억을 검사했다. LLM transport는 모의하며 HTTP 호출은 없다. 서버는 자동 종료했다.
- **별도 실패 기록:** 처음 namespace에 `minecraft`까지 포함하여 기존 GameTest 205개가 함께 실행됐고 7개가 실패했다(`build/divine-social-gametest-20260929/logs/latest.log` 보존). 공급자 미설치를 전제로 한 검사 등 기존 fixture 조건과 통합 구성의 차이가 포함돼 있다. 해당 실행을 전체 회귀 성공으로 보고하지 않는다. 이후 목표 namespace `mythai_room_full_path`만 지정한 위 단독 검사는 통과했다. 무관한 기존 검사를 이번 범위에서 수정하지 않았다.
- 실패 7건의 읽기 전용 분류: 5건은 `PhaseFourAFourCGameTests`/`PhaseFourBOneGameTests`의 content provider `UNAVAILABLE` 전제와 실제 세 모듈 조합의 `AVAILABLE` 상태가 맞지 않는다. 나머지 `QuestParticipationGameTests`/`MemoryFoundationGameTests` 2건은 fixed-room 예약 없이 `onInteractionStarted`를 직접 호출하여 방 엔진의 기존 예약 검증에 거절된다. 새 사회맥락 코드의 직접 실패 증거는 찾지 못했다. 향후 전체 통합 회귀 구성에 맞게 이 fixture를 별도로 정비할 필요가 있다.
- `exportNpcHarnessComparison`은 합성 고정 문맥 16개를 출력했다. 같은 부탁의 중립/신뢰/신뢰+분노, 미확인/확인 후원, 확인/미확인 우위 비교를 포함한다. 실제 Ollama가 듣는 상태는 확인되지 않아 실모델 비교·품질 평가는 실행하지 않았다.
- **산출물 SHA-256:** 게임 `mythictrpg-main/build/libs/mythictrpg-1.0.17.jar` = `42EB38382735F50B58723FEFF8D3A26371D962CEE9C6037364EBF796228FB6A7`; AI `mythai-ai-response/build/libs/mythai_ai_response-0.1.20.jar` = `8B032A7734F36F069AA5430319703711B60B5FB764507B4EAE190960C130AACC`; 콘텐츠 `mythai-ai-content-registry/build/libs/mythaiaicontent-0.1.3.jar` = `FE759A1D98DDC230BFD9A952EE23D0F7ABD609B12D43E755CFC017E3C5429FF2`.
- **미배포:** 운영 `server`의 JAR/설정/월드/클라이언트 파일을 교체하지 않았고 운영 서버도 시작/중지하지 않았다. 게임 JAR이 바뀌었으므로 이후 배포 시 클라이언트 필수 게임 JAR도 맞춘다. AI 0.1.20은 게임 1.0.17 이상을 요구한다. 운영 설치와 실제 인게임/LLM 검증이 남아 있다. 공용 인수인계·기존 공용 통합 완료 목록에는 실험 완료로 승격하지 않았다.

재현 명령(프로젝트 루트 PowerShell):

```powershell
.\dev-tools\run-mythictrpg-gradle.cmd runRoomIntegrationGameTestServer -ProomAiIntegration=true -ProomTestNamespaces=mythai_room_full_path -ProomTestDirectory=build/divine-social-focused-gametest-20260929 --offline --no-daemon
```

이 전용 검사에는 `minecraft` namespace를 추가하지 않는다. 템플릿은 해당 모듈 namespace에 이미 포함돼 있다.

## 2026-09-29 — 콘텐츠 기획을 제외한 시스템 확장 (진행 중·미배포)

사용자는 남은 시스템 제작을 요청하되 기획 선택·밸런스를 임의로 확정하지 말라고 했다. 세부 진행 상태는 [작업표](SYSTEM_COMPLETION_TRACKER.md)가 관리한다. 문서·기반만 만든 기능과 실제 게임 경로를 연결한 기능을 구분한다.

- **확정 사항:** 공개/파티 격리 레이드를 정의별로 지원한다. 격리 레이드는 미리 만든 전용 구역을 배정하며 빈 구역 또는 같은 신의 단일 실체가 사용 중이면 기다린다. 맵 복제·삭제는 하지 않는다. 장비 성장은 더 좋은 장비를 얻어 교체만 하고 강화·개조는 제외한다. 후원/서약은 대화상의 기억이며 새로운 보호 계약 엔진을 만들지 않는다.
- **AI 독립 빌드:** `src/engine/java`로 AI 소유 156개 소스를 이관했다. 현재 게임 API JAR만 의존하며 `mine` 결합 JAR/소스 추출을 중단했다. [입력·검증·이전 빌드와의 비교](AI_STANDALONE_BUILD.md). `mine`/`mine2` 원본을 삭제하거나 다시 서버에 설치하지 않았다.
- **연결한 게임 경로:** [스토리 선택 UI](../../../mythictrpg-main/docs/STORY_CHOICE_UI_GUIDE.md), [리소스팩 component 보상](RESOURCEPACK_REWARDS.md), [전투력 공급자](COMBAT_POWER.md), [물리 God 아바타](../../../mythictrpg-main/docs/NPC_ACTORS.md), [작성형 레이드](../../../mythictrpg-main/docs/RAID_RUNTIME.md), [즉석 퀘스트 보상 동결/재시도](GENERATED_QUEST_RECOVERY.md). AI `raid_offer`는 확인 후 모집만 생성하며 전투/순간이동/보상 실행은 아니다.
- **대화 연속성:** [현재 감정](CURRENT_EMOTION.md)은 실제 발행된 대화에만 근거한 비권위 RAM 상태다. 필수 권한/페르소나/최근 실제 대화를 보존하고 선택 기억을 줄이는 문맥 예산 수정을 함께 검증했다. 실제 Ollama의 자연스러움 향상은 아직 평가하지 않았다.
- **장기기억은 진행 중:** [M1](RECORDING_V2_M1.md) SQLite WAL/5개 합산 용량과 [M2 방 수집](RECORDING_V2_M2_ROOM_CAPTURE.md)을 연결했다. 기록 저장은 새 기억 검색/요약/공개 권한/철회/일반 방 평판의 전환 완료를 뜻하지 않는다. 일반 채팅 채널 확장과 M3 이후 연결은 작업표를 따른다. 운영 기록 모드 기본 OFF와 기존 읽기 경로를 유지했다.
- **검증:** 게임 전체 오프라인 `build --offline --no-daemon --max-workers=1` 39 tasks, AI 전체 build36 tasks/410 class 소유권·게임 중복 없음 통과. 분리 GameTest로 물리 아바타1, component 보상3, Story 선택1, 기록 실제 SavedData1, 레이드2, 레이드 제안1, 전투력2, 즉석 보상 복구2와 모의 LLM 연속 대화1을 각 집중 실행에서 확인했다. 이 합계는 단일 전체 GameTest 실행이나 실제 클라이언트/모델 검사 결과가 아니다.
- **호환:** 게임 네트워크 protocol7로 이후 배포 때 서버와 클라이언트 게임 JAR을 함께 맞춰야 한다. 게임 저장 버전·SQLite migration·구 데이터 보존 경계는 각 상세 문서에 적었다. 다운그레이드/월드 일부 복원/강제 종료의 모든 경우를 보장하지 않는다. 정상 상태에서만 교체하고 이전 월드와 설정을 먼저 백업한다.
- **미배포:** `server`의 JAR·월드·설정·클라이언트 배포본을 이번 작업에서 변경하지 않았다. 운영 서버 시작/중지 및 실제 LLM 호출도 하지 않았다. 신별 운영 전투 수치·외형, 레이드/arena·보상·전투력 정책을 임의 제작하지 않았고 합성 fixture는 build 테스트 월드에만 사용했다.

이 시점에는 새 저장소 기반 전체 기억 파이프라인과 일반 방 평판 연결, 즉석 퀘스트 다인 확장 검토, AI 물리 방문 정책/연결, 통합 검증·릴리스가 남아 있었다. 후속 방문·사회·채널 작업 결과는 아래 기록을 따른다. 장비 강화는 미완료가 아니라 사용자 결정으로 제외된 기능이다.

## 2026-09-29 — 건축물 방문·채널 수집·방 사회 경로 후속 (미배포)

- **사용자 결정 반영:** JSON에 허용한 신과 기존 서버 공용 진행도 조건, 기존 등록 건축물을 사용한다. 소유자의 여러 건물 중 취향·같은 신의 허용된 과거 평가·맥락을 보고 선택하거나 거절한다. 대화 중 결정과 대화 밖 자율 판단을 모두 연결했다. 집 원본 DB나 별도 진행도/affinity를 만들지 않는다.
- **[실행되는 방문 경로](../../../mythictrpg-main/docs/GOD_HOME_VISITS.md):** `npc_visit_request`는 빈 parameter의 판단 접수다. 게임이 후보를 정하고 AI는 제시 ID/NONE만 선택한다. 기존 아바타의 실제 경로·이동/도착·실패·NBT 지속성을 연결했다. 다른 명령/레이드/닫힌 방/늦은 턴/정책·프로필 변경은 재검증하여 취소한다. 같은 방에서 한 번 쓴 뒤 영구 차단하지 않고 JSON 쿨다운을 따른다. 소환·순간이동·언로드 경로 강제 로드는 하지 않는다.
- **감정·공개 경계:** 동일 방·신·플레이어의 실제 발행 근거가 있을 때만 방문 판단에 현재 감정을 전달한다. 자율 방문의 공용 감정 공급자는 없으므로 `UNASSESSED`이며 타 비밀방을 조회하지 않는다. 결과 알림은 소유자에게만, 마지막 방문 상태는 소유자와 해당 신의 1:1 비밀방에만 공급한다. 실제 도착은 보상·신 정체 식별·새 방 자동 합류가 아니다.
- **[M2 일반/FTB 채널](RECORDING_V2_M2_CHANNEL_CAPTURE.md):** 실제 accepted 발화와 서버 성공 전송 receipt를 분리했다. 바닐라/귓속말/scoreboard 팀/플레이어 say·me와 설치 FTB의 GUI·redirect·message 경로를 연결했다. 모두 숨김/실패일 때도 RAW는 저장하되 receipt는 만들지 않는다. Architectury wrapper·개발 SQLite runtime 누락을 수정했다. 콘솔·과거 동기화·시스템 공지·임의 명령 수집은 제외한다. 신규 기억 검색과 legacy writer 전환은 아직 아니다.
- **[사회·평판 방 포트](ROOM_SOCIAL_REPUTATION_M3_PORT.md):** 정확한 room/revision/turn과 실제 발행 묶음, 전체 청중/신의 수신 근거로 기존 Reviewer/평판 저장소를 연결했다. RUMOR_TEST 제한과 PUBLIC 평판 비공개를 유지한다. M3 전체 또는 일반 운영 활성화 완료로 보지 않는다.
- **검증:** 게임 **1.0.19 build40 tasks**, AI **0.1.22 build37 tasks** 성공. AI package413 class·게임 중복0, 감정58·방문24·채널23 검사를 포함한다. 전용 방문 GameTest **2/2**, 채널 GameTest **1/1**, 세 모듈 실제 로드/모의 LLM 방 대화 GameTest **1/1**은 서로 다른 집중 실행에서 통과했다. 최신 실행 경로와 이전 실패의 수정 내역은 [작업표](SYSTEM_COMPLETION_TRACKER.md)와 상세 문서를 따른다.
- **호환·미검증:** AI 최소 게임 의존성을 `1.0.19`로 올렸다. 게임 network protocol은7을 유지한다. 실제 모델의 방문 선택/말투 품질·사람이 조작하는 클라이언트·2프로세스 재시작은 확인하지 않았다. 운영 허용 신/진행도/빈도·외형·레이드/장비 밸런스를 임의 작성하지 않았다. `server` JAR/클라이언트/월드/설정을 바꾸거나 운영 서버·실제 LLM을 실행하지 않았다.

**남은 시스템:** M2 legacy writer 전환, M3 durable source/지식·공개 권한과 철회, M4~M6 새 저장소의 파생/검색·모든 방 연결, 일반 운영 평판 경로, 즉석 퀘스트 다인 확장 검토가 남는다. 현재 완료 범위·제약·미완료 이유는 작업표가 관리한다. 이 후속 기록을 프로젝트 전체 완성 또는 서버 배포로 해석하지 않는다.

개발 산출물 SHA-256(미배포):

- `mythictrpg-main/build/libs/mythictrpg-1.0.19.jar`: `CE7B26E45E441608B5A69C946653BA08456CA7B4BAB8671FC959A11E79636ED4`
- `mythai-ai-response/build/libs/mythai_ai_response-0.1.22.jar`: `24CFC33F15FDBC95A8AE46028372F203CE73713973F95DC176371E8BF34F1C20`
- `mythai-ai-content-registry/build/libs/mythaiaicontent-0.1.3.jar`: `FE759A1D98DDC230BFD9A952EE23D0F7ABD609B12D43E755CFC017E3C5429FF2`

JAR 내 버전/최소 의존성 및 게임 방문 서비스·AI 방문 provider 포함을 확인했다. 이 SHA는 이후 재빌드 시 달라질 수 있으므로 배포 때 재확인한다.

## 2026-09-29~30 — 기존 기억·평판 재사용 점검 (미배포)

- 사용자 지시에 따라 기존 원문/청취 기억, 개인 검색·파생 기억, 주시 경험, 전서구 수신과 신별 평판·회복 경로를 재확인했다. 이미 있는 기능을 새 DB로 대체하지 않았다. [상세 점검·수정·검증](MEMORY_REPUTATION_REUSE_20260929.md)을 참고한다.
- 게임의 기존 수신 증거를 읽는 `RoomRumorAccess`로 **다신 비밀방**의 공통 수신 소문을 공급한다. 신마다 평가/믿음은 독립적이며 PUBLIC 공개와 RUMOR_TEST 외 자동 활성화는 하지 않는다. 원래 신이 떠나도 남은 청취자의 과거 발언 기억을 현재 근거로 재검증할 수 있고, 철회/평가 변경/청중 불허는 차단한다.
- 회상 질문이 자기 자신이나 과거 회상 요청을 증거로 가져오는 문제를 기존 질문 역할 필터로 보완했다. 실제 prompt 예산에 들어간 소문만 회복 주제로 전달하고, 개인 평가의 소유 신과 공개 허용 청중을 구분했다. 원문·affinity·저장 형식은 변경하지 않았다.
- **검증:** 게임 컴파일/관련 검사/게임1.0.20 JAR, AI 컴파일/관련 검사/AI0.1.23 JAR·413 class 중복 검사를 통과했다. 세 모듈 실제 로드 GameTest **1/1 통과**(`build/memory-reputation-reuse-20260929`). LLM은 모의이며 실제 전서구와 SocialReview를 합친 새 종단 검사는 아니다. 이번 수정 후 전체 offline build를 다시 수행한 것은 아니다.
- **남음:** recording-v2 정식 읽기/권한/철회·legacy writer 전환, 새 방의 파생/의미 검색 연결, 일반 운영 평판 활성화. 기존 MemoryJournal에 해당 검색이 없다는 뜻은 아니다. 운영 서버·클라이언트 JAR/월드/설정은 그대로이며 실제 LLM·사용자 클라이언트 검증은 하지 않았다.
- **호환:** AI 최소 게임1.0.20, 콘텐츠0.1.3, network protocol7 유지. 향후 배포는 서버/클라이언트 게임 JAR과 AI를 함께 맞춘다.
- 개발 SHA-256: 게임1.0.20 `0B58575F1FFF14AC403DB037F2FF45D192DDC7F19E58F626CDC21CAC462C5248`; AI0.1.23 `447A330AD8DF7E2A95106E978FC5921F8415271C026A19F12A240F4ABE4C595E`. 콘텐츠0.1.3은 이번에 수정/재빌드하지 않았다.

## 2026-09-30 — 기억 권한 조회·관찰 연결·SHADOW 연속 작업 (미배포)

- 사용자 요청에 따라 작은 수정마다 다음 진행을 묻지 않고, 기존 시간 회상 재사용 → 새 저장소 권한 읽기 → native 신별 청취 receipt → 수신자별 본문 중복 제거 → Watch 관찰 저장 연결 → 진단/회귀를 이어 진행했다. [상세 구현·계약·검증·미완료](RECORDING_V2_AUTHORIZED_READ_20260930.md)를 기준으로 이어간다.
- 새 `MemoryReadSession`은 실제 게임 방·턴·청중에 묶인 opaque 권한이다. 원문/신별 실제 수신/현재 청중/부모 대사 계보 및 외부 증거를 모두 검사한다. 다른 방 요청, 신규 입력, 퇴장, 재시작·엔진/저장소 교체 뒤에는 이전 결과를 사용할 수 없다.
- SHADOW는 기존에 이미 선택한 기억과 새 결과의 집계만 비교한다. 생성 prompt나 Proposal에는 아직 새 결과를 넣지 않는다. 기본 OFF, PERSONAL/RUMOR_TEST와 기록 on/off 의미를 유지했다.
- SQLite schema4에서 새 수신자는 공통 본문의 part 참조를 저장한다. 구 데이터는 2/3→4 전진 migration으로 보존하며 구 JAR 단독 downgrade는 지원하지 않는다. Watch는 실제 raw·관찰 commit 이후 기존 허용 projection만 저장하고 원장/보상을 재실행하지 않는다.
- 현재 검사와 다음 범위는 상세 문서에 기록했다. 새 저장소 Watch 검색, 개별 receipt 철회, Rumor durable root/receipt cursor, 새 파생/FTS·의미검색 전체 전환은 아직 남았다. 인터페이스 추가나 SHADOW 연결을 전체 기능 완료로 표시하지 않는다.
- 게임1.0.21/AI0.1.24/콘텐츠0.1.3, protocol7. 운영 `server`와 실제 모델·클라이언트는 변경/실행하지 않았다. AI 최소 게임1.0.21을 함께 맞춰야 한다.

## 2026-09-30 — 수신·철회 후속 및 새 기억 정리 연결 진행

- [M3 상세](RECORDING_V2_M3_SOURCE_RECONCILIATION_20260930.md): 실제 Rumor SavedData의 영속 metadata·root/receipt cursor, 늦은 신 수신, 신별 KnowledgeReceipt 철회와 Watch 원본 재검증을 연결했다. 기존 게임 평판 수치·소문 판단을 새 저장소에서 복제하거나 재실행하지 않는다.
- 새 집중 검사와 개발용 NeoForge 저장/권한 4/4, 소문 실제 tick 경로 1/1을 통과했다. 최초 소문 namespace 실행은 테스트0개였으므로 통과로 세지 않았고, 등록/템플릿을 고친 뒤 실제 1개가 실행·통과했다. 운영 월드·LLM·클라이언트를 시험한 것은 아니다.
- M4는 별도 `recording-projection.json` 명시 opt-in과 기존 모델 설정을 함께 요구하는 무접속 정리 경로를 구현 중이다. 파일 부재는 OFF이며 SHADOW 활성화만으로 모델을 호출하지 않는다. 출처·실제 화자·관점·허용 청중을 유지하고, 자유 요약을 게임의 확정 사실로 저장하지 않는다.
- 현재 M4 구현/검증 중 상태이며 전체 단계 완료가 아니다. 새 검색의 운영 반영, 의미 검색 전환, legacy writer 전환과 실제 모델 품질 수용은 남아 있다. 추가 결정이 필요하지 않은 다음 작업은 계속 진행한다.

## 2026-09-30 — native 파생 해석·의미 검색 검증 체크포인트 (미배포)

- 원문 검색 결과의 파생 후보 해석과 별도의 native embedding 작업/검색을 연결했다. [해석 조회](RECORDING_V2_M5_NATIVE_INTERPRETATION_READ_20260930.md), [의미 검색](RECORDING_V2_M5_NATIVE_SEMANTIC_READ_20260930.md)을 기준으로 읽는다. 게임이 발급한 exact source/receipt/청중 권한을 벡터 검색도 그대로 재검증하며 AI 모델이 게임 사실/관계를 저장하지 않는다.
- schema8은 embedding 작업·모델 공간·immutable vector를 추가한다. 원문은 보존하며 명시 opt-in 없이 새 모델 작업/기존 DB backfill을 실행하지 않는다. 최초1,600자 prefix와 최대128개 후보 window의 제한된 PARTIAL 검색이다. 장문 전체나 오래된 모든 기억의 검색 품질을 보장하지 않는다.
- 전체 오프라인 build: 게임 **53 tasks**, AI **42 tasks**, AI **433 classes/게임 중복0** 성공. 주요 검사는 원문719·파생 조회97·파생 저장327·semantic555·설정9·embedding113, AI runtime67·semantic SHADOW55·native fake backend pipeline103이다. 마지막 live-lease 복구 경합 보완은 후속 focused build **8 tasks/저장119 checks**로 확인하고 게임 JAR를 다시 생성했다. 전체 build를 그 보완 뒤 다시 실행했다고 소급하지 않는다.
- 개발 NeoForge 실제 모드 검사: `build/recording-semantic-authority-20260930` **1/1**, `build/recording-semantic-final-wire-20260930` **1/1** 통과. 앞선 것은 새 build 폴더의 제한된 fixture 설정 자동 준비·게임발 턴 권한·native semantic 기본 OFF를, 뒤는 모의 LLM의 Primary/Secondary·기억/발행 경로를 확인했다. 운영 서버나 실제 Ollama 품질 검사가 아니다.
- 이 체크포인트 산출물 SHA-256: 게임1.0.22 `3EE9A4AB4D4FB917BB803F9FDB4FA6C73F0229C2940A855619770F263F45CDA4`; AI0.1.25 `0BF62506DBDEC10D96EC2E1F08135CEA6AE2841734D373AB000A41543E26403E`; 콘텐츠0.1.3 `FE759A1D98DDC230BFD9A952EE23D0F7ABD609B12D43E755CFC017E3C5429FF2`. 같은 버전의 후속 개발 빌드와 구분한다.
- 후속으로 [Watch typed 관찰 조회](RECORDING_V2_M5_WATCH_OBSERVATION_READ_20260930.md)를 구현 중이다. semantic 검색의 실제 화자별 의도 필터, semantic seed의 정정/취소 파생 연결, 검색 결합·실모델 수용·ON 전환·legacy writer 전환도 남았다. 새 검색은 계속 SHADOW 비교용이며 운영 `server`/클라이언트/월드/설정은 변경하지 않았다.

## 2026-09-30 — Watch·정정 해석·회상 문맥 후속 체크포인트 (미배포)

- 게임이 직접 관찰한 `ExperienceView.Event`를 새 저장소에서 읽고 실제 Watch proof를 비동기로 준비·현재성 재검사한다. 한 신/대상 플레이어의 PERSONAL 비밀방으로 제한하며 원본 ledger payload나 아이템 획득을 추정하지 않는다. 실제 Watch journal·SQLite·발급 세션370 checks 통과. 초기 fixture의 import 충돌, producer 중복 등록, 재시작 커서 오류는 수정 후 재검사했으며 처음 실패한 실행들을 통과로 소급하지 않는다.
- 의미 검색으로 얻은 실제 발급 페이지를 seed로 파생된 취소·정정 근거를 조회한다. RAW 페이지를 가짜로 만들지 않으며 기존 전체 input/quote/청중 검사를 유지한다. 실제 API114 checks 통과. 원본 source가 더 높은 revision으로 교체될 때 기존 발급 페이지와 새 RAW 조회 모두 구 revision을 거절하도록 보강했다. 일반 새 사건/늦은 receipt 추가는 이 교체 fence를 켜지 않는다.
- 기존 회상 계획을 한 번만 계산해 새 RAW/semantic 비교에 함께 전달한다. “다시 알려줘”도 최초 질문/시간/발화자를 유지하며, 내 말·현재 신의 말·다른 신의 말을 exact actor로 좁힌다. 같은 Query의 cursor에서 actor를 바꿀 수 없고, 취소·정정의 다른 화자 의존성은 지우지 않는다. 상세 계약과 제한은 [회상 범위](RECORDING_V2_M5_RECALL_SCOPE_20260930.md)를 따른다.
- 게임 집중 결과: Watch370, semantic 해석114, actor66, RAW719, semantic555, 별도 앞선 파생 해석97/knowledge65 통과. Watch 수정 뒤 단독6 tasks 성공이며 전체 게임 build를 재실행한 것은 아니다.
- AI 집중 결과: **15 tasks 성공**, 관찰 SHADOW52·query48·계획 전달38·기억 청중374·semantic SHADOW55/runtime67·실제 SQLite+모의 backend103·방 엔진98, **440 classes/게임 중복0**. 실제 모델 호출이나 자연스러움 수용 시험이 아니다.
- 후속 native Rumor의 정확한 root별 수신/평가 조회를 구현 중이다. 새 결과의 실제 생성 사용·ON·legacy writer 종료, 장문 전체 coverage·검색 결합·실모델 품질은 남았다. 최신 소스가 이 체크포인트 후 더 진행될 수 있으므로 배포 전에 다시 빌드한다. 운영 `server` 및 공용 인수인계는 변경하지 않았다.

## 2026-09-30 — native 원문 근거·콘텐츠 공개·생성 준비 체크포인트 (미배포)

- [영속 seal](RECORDING_V2_NATIVE_EVIDENCE_SEAL_DESIGN_20260930.md)은 실제 발급 RAW/semantic 페이지, 원래 시점의 전체 청취/전송 receipt, 원문/context hash와 부모 출처를 저장·재검증한다. 공개 descriptor의 hash만으로 읽기나 출력 권한이 생기지 않는다. schema9의 issued manifest가 실제 존재해야 한다.
- CONTENT 및 정적 Quest 설명의 소유자 검증을 원문 재조회·출력 출처에 연결했다. 실제 퀘스트 수주·완료·보상 실행은 계속 게임이 판정한다. [foreground 준비](RECORDING_V2_NATIVE_FOREGROUND_PREPARATION_20260930.md)는 실제 조회 페이지 전체를 선택→seal→현재 소유자 검증으로 연결했으나 production의 NEW 경로에는 아직 넣지 않았다.
- 기존 엔진의 분류/생성/Secondary 완료 때 오래된 근거는 같은 요청·같은 출처로만 재준비한다. 기록 OFF 대화에는 기록 읽기 권한을 요구하지 않으며, Secondary의 NPC 발화가 플레이어 회상 focus를 덮어쓰지 않도록 분리했다.
- 이 시점 게임 native SQL172, AI focused17 tasks·475 classes/게임 중복0 통과. 실제 CONTENT foreground 왕복1/1(768.0ms), Primary→Secondary SHADOW 대화1/1(6.705초) 통과. 앞선 닉네임16자 초과 fixture 및 enum import 충돌 실패는 수정 후 재검증했으며 실패를 성공으로 소급하지 않는다. 실제 LLM·운영 서버 시험은 아니다.
- 이어지는 schema10 입력 무결성, Story leaf 및 typed interpretation 계약은 별도 후속 작업이다. 이 절의 성공을 그 변경의 검증 결과로 대신하지 않는다. 전체 M4/M5·기억 전환·RPG 시스템 완성을 선언하지 않는다.
