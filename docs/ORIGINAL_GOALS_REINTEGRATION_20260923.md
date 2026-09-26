# 원래 목표와 새 대화방 재통합 — 구현·검증 기록

2026-09-23. 개발 게임 **1.0.15 / AI 0.1.16**의 구현·배포 기록.
사용자의 원래 목표 복원 요청에 따라 기존 공개/비밀방을 유지하며 누락된 연결을 구현했다.
설계 기준은 [06 재통합 설계](../추가개발/06_원래목표_대화방_재통합.md)다.
통합·릴리스 스킬에 따라 게임 권한, 소스 구현, 오프라인 검증, 실제 배포를 구분했다.

## 1. 이번에 구현한 동작

| 항목 | 구현 내용 | 경계 |
|---|---|---|
| 신 사이 관계 | 일반 대화·무리 선택에 현재 화자→참가 신의 동적 score/tags 연결, 생성 후 revision 재검증 | 상대의 내면·관계 원인/숨겨진 사건 history는 넣지 않음 |
| 관계 변화 제안 | 등록·AI 허용·현재 화자 소유이며 관련 신이 방에 모두 있는 전이만 제안 | 실제 적용은 기존 게임 Validator/Executor, 임의 수치 변경 불가 |
| 사건 연출 | 현재 Ollama provider를 04에 등록, 게임 확정 사실·허용 Hook만 제공, 실패 시 작성된 fallback | AI가 03의 확률/결과를 결정하거나 새로운 정사를 확정하지 않음 |
| 대화의 사건 지식 | 현재 화자와 전체 청중에게 허용된 의미만 일반 대화에 제공 | 공개방은 공공지식, 비밀방은 플레이어별 허용 교집합과 다른 신의 기존 지식까지 확인 |
| 사건 Hook | 실제 token을 숨기고 별칭만 AI에 제공, 방/화자/청중/정의/진행 변경 재검증 | 현재 자동 제안은 플레이어1·신1의 PRIVATE 일반방만; 시험방/Secondary 불가 |
| 다른 신의 발언 기억 | 게임이 청취를 인증한 신별로 발화 저장, 기억 소유 신과 실제 발화 신 분리 | 기록 on과 완전한 채팅 전달 증명 필요; 부분 HUD만으로 영속 청취 기억을 만들지 않음 |
| 발언 출처 회상 | ‘내가/네가/다른 신/정확한 신 ID가 한 말’의 출처를 구분, 같은 문장도 출처·화자로 구분 | 표시 이름을 임의로 ID에 매핑하지 않으며, 근거 없는 최근 문장을 회상 답으로 대체하지 않음 |
| 두 신의 순차 반응 | Primary 전달 후 다른 신이 자기 프로필·관계·기억으로 한 번 반응하거나 침묵 | 플레이어1·신2만; Secondary는 별도 분류 호출 없이 생성1회, 행동·초대·퇴장·Hook 권한 없음 |

다른 규모의 방은 기존 한 신 응답을 유지하며 최대64플레이어·16신 참가 기능은 보존한다.
플레이어 없는 무한 자율 토론, 예시 신들의 사건을 강제로 재현하는 규칙은 만들지 않았다.
Secondary가 침묵하더라도 반응 여부를 판단하는 모델 호출은 발생할 수 있다. 대사 길이·지연·
자연스러움은 실제 모델 검증 전이며, 오프라인 테스트 통과가 이를 보장하지 않는다.

## 2. 안전·일관성 보완

- 게임이 Primary를 실제 전송한 다음에만 Secondary를 시작한다. 최초 플레이어에게 채팅 원문
  또는 전체 HUD 페이지를 서버가 전송한 경우여야 한다. 클라이언트가 읽었다는 ACK는 아니다.
- 같은 턴 lease를 유지하며 새 입력·방 변경·분리/병합·퇴장·관계 변경·사건 공개 조건 변경 뒤
  늦은 응답을 적용하지 않는다. 보조 응답 실패/30초 만료는 이미 전달한 Primary를 취소하지 않는다.
- 요청에는 선택된 신 자신의 GodState만 제공한다. validator feedback은 방+신+플레이어별로 분리하고
  Secondary에는 행동 feedback·퀘스트 권한·초대 후보를 주지 않는다.
- Secondary의 원문 입력 우회 경로를 제거했다. 청취·청중·철회 필터를 통과한 history만 주고,
  개인 기억 검색 질의는 그중 마지막 신 발언을 사용한다. 유효한 신 발언이 없으면 모델 호출 없이 침묵한다.
- Story에서 봉인/부재로 등록된 신은 발언·청취·행동 scope에서 제외한다. Story actor 미등록은
  가상의 부재 상태를 발명하지 않는다. 여러 actor가 같은 신을 가리키면 모두 이용 가능해야 한다.
- 실제 전송되지 않은 신 발언은 공개 history/신 식별/청취 기억의 성공 근거로 쓰지 않는다.
  관찰·소문·Story의 철회 가능한 근거는 일반 영구 기억으로 복사하지 않고 RAM provenance로 추적한다.
  기억 OFF에서도 근거 검증은 유지한다. 기록 off는 새 영속 기록을 남기지 않는다.
- 신 이름 발언에 따른 식별이 동기 Story 사건을 일으킬 수 있으므로 전체 발화의 근거 추적을
  등록한 뒤 식별을 반영한다. 전송 직후 만료된 근거도 차단용 provenance는 남겨 재사용을 막는다.
- 일반 대화의 Story context 자체는 지식 획득 효과가 아니다. 정식 사건 연출은 기존 전달 후
  지식 획득 경로를 유지한다. 8개 초과 사실은 조용히 잘라서 초과분까지 지식 지급하지 않고 거절한다.

## 3. 핵심 파일과 호환성

- 게임: `GodRelationRoomContext`, `ConversationRooms`, `RoomConversationEngine`,
  `RoomDialogueEvent`, `RoomTurnPolicy`, `StoryRoomConversationService`, `StoryRoomDisclosure`,
  `StoryAiHookTokenService`, `StoryPresentationService`/`StoryPresentationSnapshotService`.
- AI: `MythAiRoomConversationEngine`, `RoomReactionPrompt`, `AiActionCapabilityBridge`,
  `StoryOllamaPresentationProvider`, `StoryConversationContextBridge`,
  `DialogueMemoryBridge`, `RoomListeningMemory`, `RecallSourceScope`와 기존 검색/기록 구현.
- AI 빌드 입력은 게임1.0.15 JAR, 최소 게임 버전도1.0.15다. 구 `mine/mine` 오버레이 의존성은
  보존했다. generated Java만 수정하거나 구 결합형 JAR을 재배포하지 않았다. 네트워크 protocol6 유지.
- 기억 JSON에 선택적 `speakerGodId`를 추가했다. 과거 NPC row는 기억 소유 신을 화자로 해석해
  읽을 수 있다. **새 청취 기록 생성 후 구 AI JAR로 단순 하향하면 다른 신 발언의 화자 정보가
  소실/오해될 수 있다.** 배포 전 기억 백업과 저장 호환성 검토가 필요하며 지금 서버 기억은 변경하지 않았다.

## 4. 검증

최종 게임 `compileJava jar`와 관련 7개 회귀 작업, 이후 완성된 게임 JAR을 입력으로 AI의
`compileJava jar check --continue`를 직렬 실행해 모두 성공했다. `--offline --no-daemon` 사용.

| 검증 | 실제 최종 결과 |
|---|---|
| 게임 | 관계 context53 + 순차 턴99 + 방147 + 실제 Gateway45 + 공통 발행19 + 실제 퀘스트 동의32 + 실제 Hook token 서비스26 = **421 PASS** |
| AI 명령/대화 | 호출 인자67·라우팅210·기존 인자59·대화87·화자58·경험37·퀘스트25·방90·반응28·관계 제안62·Story653 |
| AI 기억/검색/소문 | derived435+summary4·recording20·청중/청취/출처146·journal656·index1874+scheduler4+connection50·recall54·natural62·stage121·rumor16·social41 |
| AI 합계 | **4,859 PASS**, 별도로 기존 로그 가드37개 위치 검사 |
| 전체 | **5,280개 오프라인 검사 PASS**. 실제 플레이 횟수·자연스러움 점수가 아님 |
| 개발 산출물 | 두 JAR 생성, 신규 핵심 class 포함, 중복 ZIP 엔트리0/신규 test fixture 포함0/게임↔AI split-package0 |

실제 게임 서비스 일부는 격리 컴파일한 실제 소스와 world/FTB 등 stub을 조합한다.
공개 정책 조합/용량·반복 검사도 개수에 포함되며 독립 기능5,280개를 검증한 것은 아니다.
초기 ID 회상 실패는 긴 ID가 lexical 관련성 분모를 희석한 문제로, 주어·수신자 ID를
내용 검색과 분리하고 엄격한 출처 필터는 유지해 해결했다. 전달 후 근거 만료, 다른 화자/턴의
receipt, 부재 화자 Hook, secondary 미청취 원문 우회도 회귀에 포함했다.

성능 진단에서 2,000행 bucket/15ms 예산은 5회 중3회 초과했다. 정상적인 예산 초과 처리는
통과했지만 저지연 보장은 아니며 실제 모델/서버 응답 시간을 측정한 것도 아니다.
서버/GameTest·실제 LLM·클라이언트 HUD 검증과 다인 부하 시험은 실행하지 않았다.

### 개발 JAR과 설치본

- 게임: `mythictrpg-main/build/libs/mythictrpg-1.0.15.jar`, SHA-256
  `05986AA6C90CC71EAD59EE4DD7E0C6774B66788E54D01D9B5D888036AE8CACF7`.
- AI: `mythai-ai-response/build/libs/mythai_ai_response-0.1.16.jar`, SHA-256
  `001F90D6FFBB08BEC20F0D4AD188B3E6E80A99DCDB302CFA1030F09917F9F6BF`.
- 배포 전 서버/클라이언트 게임1.0.13은
  `DADDEB4FAF1C7788B7FE78ADBDD502DB2973F40C346BD5926A4607EA58FB6881`,
  서버 AI0.1.14는 `BF978F516C0497AFC68A60297A5CD248A9735B75AD8864E93A5549F10CE8D158`였다.
- 배포 후 서버에는 게임1.0.15/AI0.1.16, `client-required-mods`에는 게임1.0.15를 설치했다.
  개발·서버·클라이언트 게임 JAR SHA-256은 동일하다. 실제 개인 런처 파일은 수정하지 않았다.
- 배포 전 `server/world` 480개, `server/mods` 30개, `server/client-required-mods` 6개,
  `server/mythictrpg-ai-data` 1개 총 **517개 파일/51,294,818 bytes**를
  `server/backups/original-goals-reintegration-20260923`에 복사해 SHA-256으로 일치 확인했다.
  구버전 JAR은 활성 폴더에서 제외하고 backup 아래 보관했다. 기존 다른 모드/콘텐츠/설정은 건드리지 않았다.
- 배포 당시 서버는 꺼져 있었고 이후에도 부팅하지 않았다. 실제 NeoForge 로드·새 명령·Ollama 대화·
  HUD와 클라이언트 접속은 미검증이다. 현재 배포 표본에 남은 런타임 검증은 서버 부팅과 실제 수용 시험이다.
  새 패키지 검사만으로 전체 모드 부팅 호환을 보장하지 않는다.

## 5. 아직 남은 범위

- [05 장기기억·다중신 설계](../추가개발/05_AI_장기기억_다중신_대화_시스템/08_단계별_구현계획.md)의
  M1~M5 전체채널 RAW·SQLite/100GB·정확한 수신 view·지식/검색 통합과 M7 운영 수용은 별도다.
  이번 청취 기억/순차 반응은 기존 저장소 위의 연결이며 전체 M1~M7 완료가 아니다.
- 새 방의 SocialRuntime 소문 생성/설득 회복, 게임발 퀘스트 완료/평가 자동 AI 연출은
  명시적 방별 포트 이전이 남았다. 일반 SECRET 로어 공개, 임의 대규모 다중 발화도 확대하지 않았다.
- 원본 affinity는 유지하지만 수치→관계 단계와 누적 감정 규칙을 임의 확정하지 않았다.
  R_NEUTRAL/E_NEUTRAL 기본 태그의 한계는 남으며 동적 신간 관계 점수와는 별개다.
- 서버/클라이언트 JAR 배포는 완료했으며 실제 부팅·대화·사건 연출 수용이 남았다. 테스트 프로필/사건 fixture는
  서버 콘텐츠로 설치하지 않았으며, 조건·성격만으로 특정 예시 전개가 저절로 나온다는 검증도 아니다.

## 6. 다음 실제 수용 확인

배포 요청이 있으면 정상 종료/백업/게임·AI 및 클라이언트 게임 버전 정합성을 확인한 후 적용한다.
그다음 1플레이어·2신에서 상반된 관계와 말투, Primary 직후 새 입력에 의한 보조 취소,
침묵/거절, ‘내가/네가/다른 신이 말한 것’의 회상을 실제 모델로 확인한다.
비밀방에 다른 플레이어/신이 합류했을 때 지식 노출, Story actor 부재, 사건 상태 변경,
Hook 확인·거절·만료, 기록 off 및 서버 재시작 후 기억의 화자 보존도 함께 검사한다.
