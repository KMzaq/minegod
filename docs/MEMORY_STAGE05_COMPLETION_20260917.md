# 5단계 남은 개인 기억 소스 연결 — 2026-09-17

**승인된 독립 소스 연결·오프라인 검증 완료 / 기본 OFF / 미배포 / 5단계 전체 운영 수용은 미완료 / 6단계 미착수.**

직전 [5단계 기록](MEMORY_STAGE05_20260916.md)의 ‘실제 backend·영속 벡터·검색 소비·자동 정리 후보·공통 모델 입장 관리 미연결’을 이번에 연결했다. 새 행동 타입 공개 정책, 자동 중요도/영구 보존 정책, 전체 행동 요약의 운영 적용을 임의 확정한 것은 아니다. 구 기록·구 delta는 당시 증거로 그대로 보존한다.

## 1. 승인·선행 조건·보존

- 사용자: 6단계에 앞서 **5단계 남은 소스 구현부터 마무리**. 추가 설치·실제 호출 없이 독립 구현을 진행했다. 서버/GameTest 실행, 모델/임베딩 호출, 설치·다운로드, JAR 배포·서버 설정 변경은 하지 않았다.
- 선행 게임 1.0.6/AI 0.1.6 소스와 JAR, 이전 delta 45개 및 미구현 경계를 대조했다. 4단계 인게임·모델·지연 수용 조건은 여전히 미충족이다.
- 통합·대화 검토·릴리스 스킬에 따라 실제 게임 권한/청중/세션 검증과 개발·배포 구분을 유지했다. 합성 응답 성공을 실제 대사 개선으로 보고하지 않는다.
- LP 원본 JAR **3개**, LP 소스 **298개**, 기억 개발 직전 **906개**, 기존 5단계 직전 **2,231개** 무결성 확인. LP를 덮어쓰지 않았다.
- 수정 전 별도 snapshot: `server/backups/before-memory-stage05-20260917-053507-275/`, manifest **2,255개**. 소스·기존 개발/배포 JAR·설정·기억/대화 로그이며 전체 월드 지형 백업은 아니다.
- 게임·FTB·콘텐츠·legacy 원본과 서버 파일은 이번 수정 대상이 아니다. 게임 개발 **1.0.6** 그대로, AI 개발만 **0.1.7**. 배포 게임/AI/콘텐츠는 **1.0.2/0.1.3/0.1.0** 그대로다.

## 2. 실제 연결된 경로

```text
기존 권한별 개인 원문 v1
  → 무접속·전경 작업 없음 확인 → 제한된 유휴 worker
  → 원문 벡터 + 원문에 묶인 발언 종류/정정 후보 → 별도 index-v2

대화의 권한별 ReadView → 기존 단어 검색
  → 명시적 회상 질문에만 선택적 질의 임베딩 → 혼합 검색
  → 현재 원문·청중·시간 재검사 → 원문 최대 3개와 불확실성 → 기존 분류/대사 생성
```

### 모델 연결·공통 자원 입장 관리

- `OllamaMemoryBackend`: `/api/embed`, `/api/chat` 실제 클라이언트 소스. 숫자 loopback HTTP origin만 허용하고 redirect를 따라가지 않는다. 구성 생성은 접속/설치가 아니다. 모델 pull/start/install API가 없다.
- 각 작업 전후 `/api/tags`의 **정확한 모델 이름 + SHA-256 digest**를 확인한다. 관리자가 이름만 같은 모델로 바꿨을 때 예전 벡터 공간을 사용하지 않는다. 이름 alias는 자동 정규화하지 않으므로 목록의 정확한 이름/digest가 필요하다. 외부에서 요청 도중 모델 태그를 여러 번 바꿨다가 돌려놓는 비정상 운영까지 원자적으로 검출하는 프로토콜은 아니다.
- 응답은 버퍼링 중 **256KiB** 상한, UTF-8 오류/모델 불일치/차원·숫자·0 벡터 검사, 작업 전체 timeout을 적용한다. 강제 `keep_alive:0`로 기존 모델을 매번 unload하지 않는다. 모델 로딩/상주에 따른 실제 GPU 영향은 미측정이다.
- `ModelAdmission`을 **실제 legacy LocalLlmRequestScheduler 오버레이**와 시각 평가 경로에 연결했다. 분류·일반 대화·자발 대사와 시각 평가의 queued/active 작업이 있으면 선택적 기억 작업은 즉시 fallback/defer한다. 기존 대화 큐 용량과 설정 동시 요청 수는 보존하면서 공유 상한을 적용한다.
- 백그라운드는 **접속자 수가 확인된 0명**일 때만 한 건씩 진행한다. tick은 신호만 보내며 파일/모델 작업은 worker에서 한다. 단계 사이에도 입장을 재확인한다. 미래에 활성화했을 때 새 플레이어가 들어오면 이미 시작한 한 작업의 제한 시간만큼 전경 요청이 기다릴 수 있다. 실행 중인 모델 계산이 즉시 중단된다고 보장하지 않는다.
- 질의 timeout/cancel 뒤에도 실제 transport가 끝나기 전에는 입장권을 조기 반환하지 않는다. 종료 시 새 작업을 차단하고 worker/저장소를 drain한다. 닫힌 journal의 늦은 reader가 다시 인덱스를 열지 못한다.

API 형식은 Ollama 공식 [embed](https://docs.ollama.com/api/embed), [chat](https://docs.ollama.com/api/chat), [model list](https://docs.ollama.com/api/tags)를 확인했다. 실제 Ollama 접속은 하지 않았다.

### 자동 정리·정정의 의미

- 같은 world/god/player/**동일 원 청중**의 직전 최대 5개 PLAYER_STATEMENT + 대상 1개만 정리 입력이다. 다른 신의 대화, 전체 행동 원장, NPC 대사·추측을 선수의 실제 행동으로 합치지 않는다.
- 자유로운 이야기 재작성 대신 **원문 발췌와 종류 후보**를 검증한다. SELF_CLAIM/PLAN_OR_PROMISE/REPORTED/CONDITIONAL/JOKE/OTHER는 해석 후보다. 모델 발췌가 원문과 다르면 거절한다. 날짜는 원 발언의 시각과 기존 시간 설정을 사용하며 기본 UNSPECIFIED를 유지한다.
- CORRECTS/CONTRADICTS/CANCELS/ALSO_PLANNED는 원문 ID/hash가 연결된 **미검증 후보**다. 추가 계획을 자동 취소로 처리하지 않는다. 인용·조건·농담 후보로부터 개인 상태 정정 링크를 만들지 않는다. 정정/취소 일부는 명시적 표현도 요구하는 보수적인 gate다. 이 gate의 언어적 완전성은 보장하지 않는다.
- 조회할 때 관련된 이전·이후 원문을 함께 우선 배치하고 AMBIGUOUS와 후보임을 전달한다. `MemoryRecallPolicy`가 실제 프롬프트에 발언 종류와 정정 후보를 소비한다. 모델이 원문을 삭제/수정하거나 사실·약속 이행·중요 pin·보상/관계 수치를 확정하지 않는다.
- 추출 실패는 원문+설정 fingerprint별 세션당 최대 3회로 제한하고 상태에 드러낸다. 정상 재시작/새 설정 버전에서 재시도한다. 모든 입력이 다 의미 있게 요약됐다고 가정하지 않는다.

### 저장·검색·현재성

- `MemoryIndexStore`: 별도 `index-v2/index-v2.jsonl`, schema v2, 순번/checksum/원문 해시, 파일 lock, 단일 writer/queue16, force 후 게시. 원문 v1이나 기존 derived-v1 파일을 이관·덮어쓰지 않는다.
- 벡터는 embedding model+digest+차원+텍스트 전처리 버전, 정리 자료는 extraction model+digest+추출 버전으로 분리한다. 설정 변경 후 다시 시작하면 누락된 새 버전을 유휴 시간에 작성한다. 이전 버전은 검색에서 제외하되 bytes를 자동 삭제하지 않는다.
- 명시한 byte/행 한도 외에 기술 보호 상한 **16,777,216개 float 성분**을 둔다(벡터 값만 최대 약 64MiB, 전체 JVM 메모리 상한은 아님). FULL 사유를 구분하고 용량 부족 때 반복 모델 작업을 멈춘다. 삭제/압축/자동 용량 확장은 하지 않는다.
- 90% **byte 용량** 진입 시 서버 채팅·로그에 관리자 `ai-memory-index.json/maxStorageBytes` 증설·정상 재시작 안내. 기존 30분 재알림 제한 재사용. 행 한도는 `maxEntries`, 벡터 메모리 한도는 기술 상한이라 byte 설정만 늘려 해결되지 않을 수 있다. `/ai_memory status`에서 상태/bytes/rows/거절/사유/backlog/모드/자원 점유를 확인할 수 있다.
- 손상/잘린 tail/중복/스키마·checksum 불일치는 파일을 보존하고 FAILED로 차단한다. 원문 검색은 별도로 유지한다. 수동 delete/supersede/pin 변경과 청중 변경 시 현재 원문과 맞지 않는 파생 자료는 재사용하지 않는다. **논리적 망각이지 디스크의 안전 삭제가 아니다.**
- `DerivedService.search`가 실제 선택적 runtime 결과를 반환한다. 권한 제한 후 유사도 검색하며 기존 TTL/중요 pin/시간 조건을 다시 적용한다. 비슷하다는 이유만으로 사실·부정·정정을 확정하지 않는다.
- SHADOW는 실제 질의 계산/진단만 하고 기존 선택 결과와 후보 힌트까지 그대로 유지한다. ON에서 혼합 결과를 소비한다. 의미 검색은 현재 **명시적 회상·연결된 후속 회상 질문**에 한정한다. 모든 잡담마다 추가 임베딩을 호출하거나 배경 내용을 강제로 끼워 넣지 않는다.
- 미준비/모델 없음·교체/오류/입장 거절/timeout은 기존 단어 검색으로 돌아간다. 실제 ‘바다→수영’ 의미 품질은 합성 벡터 시험으로 입증되지 않았다.

## 3. 설정과 운영 전 선택

[ai-memory-index 예시](examples/ai-memory-index.example.json)는 **enabled=false, semanticMode=OFF, consolidate=false, 용량0, 모델/다이제스트 미지정**이다. 실제 서버에 복사하지 않았다. 기존 `ai-derived-memory.json/semanticRetrieval` 예약 필드를 운영 스위치로 재해석하지 않는다.

정상 재시작 기준으로 읽는 새 설정 위치는 `config/mythictrpg/ai-memory-index.json`이다. 사용할 때는 별도 승인과 함께 다음을 선택해야 한다.

1. 개인 기억 foundation/회상 기능 활성 상태, 모델 이름·정확한 digest와 지원 차원. extraction과 embedding은 역할별로 지정하며 기존 대화 모델을 자동 선택하지 않는다.
2. 측정 기반의 인덱스 byte/행 한도. 예시의 `maxEntries=12000`은 코드의 보호 기본치이지 사용자가 승인한 보관 정책이 아니다.
3. OFF → SHADOW 비교 → ON 여부. 예시의 질의250ms/배경5000ms는 변경 가능한 기술 예산이며 실제 장비의 권장값/실측값이 아니다. 질의는1~2000ms, 배경은1~30000ms 범위다.

모델과 설정이 없더라도 이번 소스/오프라인 구현은 가능했다. **지금 서버를 켜면 새 기능이 적용되는 상태는 아니다.**

## 4. 검증·산출물

```powershell
.\dev-tools\run-mythictrpg-gradle.cmd -p ../mythai-ai-response memoryIndexRuntimeTest derivedMemoryTest memoryFoundationTest memoryRecallTest recallStageTest experienceDialogueTest jar --offline --no-daemon
.\dev-tools\run-mythictrpg-gradle.cmd compileJava actionDetailTest actionLedgerTest godWatchTest experienceStageTest memoryFoundationTest --offline --no-daemon
.\dev-tools\Test-MemoryStage05Completion.ps1
```

기존 캐시 쓰기 승인을 받아 `--offline`만 사용했다. 실제 LLM 대신 주입한 transport/가짜 supplier만 사용하고 TCP 서버조차 만들지 않았다.

- AI 컴파일·오프라인 검사·개발 JAR PASS. 기존 AI 검사 **1,302개** + 신규 runtime **1,874개**, 실제 scheduler의 가짜 동시 요청 검사 **4개** = **3,180개**. 반복 데이터/파라미터 검사도 포함하므로 서로 다른 인게임 시나리오 3,180개라는 뜻은 아니다.
- 신규 검사는 backend 스키마/반환 타입·digest 전후 변경·차원/0벡터·응답 상한·원문 발췌/관계 출처, append/replay/손상/lock/한도, 무접속 후처리, 다른 신·월드·플레이어·청중/원문 revision/망각, SHADOW/fallback/timeout/재색인/종료와 실제 DerivedService→prompt 연결을 포함한다.
- 두 생산 scheduler에 가짜 전경 요청 6개를 넣어 공통 동시성·취소·입장권 회수를 확인했다. 시각 평가/일반 대화의 공통 입장 호출이 **최종 JAR bytecode에도 존재**함을 감사한다. 실제 vision/LLM 호출은 없다.
- 처음 검사 코드의 Java 복합 var 선언 오류와 반복 대기 fence의 큐 경쟁 조건을 발견해 수정 후 전체 AI 검사를 재실행했다.
- 최신 runtime artifact: `mythai-ai-response/build/memory-index-test-artifacts/index-17029579078560037803/`. 가짜 1/4/6인 12/48/72개, 10,661/42,671/64,011 bytes, 저장 포함 30/99/127ms. 거절0. 실제 GPU·tick·전체 대화 지연이 아니다.
- 게임 API 컴파일 PASS(소스 불변), 기존 원장604 + 주시99 + 경험24 + 기억/소문39 + 상세행동402 = **1,168개 PASS**. AI와 합계 **4,348개 PASS**. 게임 JAR은 재생성/교체하지 않았다. 기존 Gradle 폐기 예정 기능 경고는 남아 있으며 이번 빌드 실패가 아니다.
- AI **0.1.7** 개발 JAR: `mythai-ai-response/build/libs/mythai_ai_response-0.1.7.jar`, SHA-256 `389A9A160A9F53F34BADD76731C1560424783BE84B50DC4D911DB3978806394B`.
- 게임 **1.0.6** JAR 그대로: SHA-256 `B6D2809402F35D112D24DD5CEA81112D72B227CD78155D601518499A4539F53C`. AI 최소 게임 버전 1.0.6도 그대로다.
- 이전 AI 0.1.6의 ZIP 엔트리 **330/344 동일**, 나머지는 명시한 기억 연결/공통 입장 클래스와 버전 metadata. 새 조합 **7 JAR/244 패키지** 격리 PASS. 이것은 실제 모든 모드 런타임 호환 증명이 아니다.
- 변경 목록은 별도 [후속 delta](memory-stage05-completion-changes-20260917.csv). 이전 `memory-stage05-changes-20260916.csv`와 검사 도구를 덮어쓰지 않는다.
- 최종 snapshot 대조: **2,255개 중 2,243개 불변**, 기존 파일12개 수정 + 신규11개 = delta **23개**(자동 생성 delta CSV 자체 제외). 서버/설정/로그/기억, 모든 이전 개발 JAR, 게임·FTB·콘텐츠·legacy source 및 LP 보존 PASS. 감사 도구는 해시/범위/JAR만 확인하며 서버·모델 실행이 없다.

## 5. 미검증·미완료와 멈추는 지점

- **실제** 임베딩/추출 정확도, 모델 지연/상주 메모리, 개인 회상 대사의 자연스러움, HUD, 실제 Mixin/취소·보호 모드·FTB 조합, 1/4/6인 인게임 부하는 미검증이다. 활성화/배포/서버 실행/모델 설치·호출은 별도 승인이다.
- 새 상세 행동은 여전히 관리자 원장 전용이다. 신별 상세 공개 조건/자동 주시 임계값이 미정이고 4단계 운영 검증 전이므로, 기존 허용 작물 관찰 범위를 넓히지 않았다. `ObservedExperienceSummary`의 제한 창 집계는 검색-only 기반을 유지한다. 전체 행동 요약의 프롬프트 투입은 이번 개인 기억 연결 범위에 포함하지 않는다.
- 기존 원문 v1의 12,000개/버킷2,000개, 사소한 발언30일 조회/수동 pin을 유지한다. 중요 사건 자동 선정·영구 보존/용량 초과 삭제 규칙을 새로 확정하지 않았다. 인덱스가 원문 한도를 해결하는 장기 아카이브는 아니다.
- 소스상 backend·인덱스·개인 회상·유휴 정리는 이제 연결됐지만 **5단계 전체 수용 조건과 6단계 착수 조건이 통과한 것은 아니다.** 다음은 승인된 작은 종단 시험과 설정/모델 비교이지 6단계 자동 진행이 아니다.

## 6. 되돌리기

운영 변경이 없으므로 서버 되돌리기는 필요 없다. 개발 source/JAR의 이번 변경만 후속 delta와 2,255개 snapshot을 기준으로 선택 복원한다. 예전 dirty 변경까지 `git reset`하지 않는다. LP까지 되돌릴 때는 [LP 가이드](LP_MEMORY_FOUNDATION_GUIDE.md)의 이전 단계 delta도 함께 검토한다.

추후 활성화했다면 새 설정을 OFF 후 정상 재시작하여 질의/후처리를 중단할 수 있다. index-v2는 원문 v1과 분리되어 있지만, 실제 데이터를 삭제하거나 옛 JAR을 배포하는 작업은 별도 확인 없이 하지 않는다.
