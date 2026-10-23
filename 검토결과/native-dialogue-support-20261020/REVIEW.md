# Native 기억·행동 지원 실제 대화 연결 검증

2026-10-20 · `behaivior` · 게임 개발1.0.35 / AI0.1.47 / 콘텐츠0.1.3 · 모델 `gemma4:12b` 유지.

## 판정

**전체 품질 gate는 통과하지 못했으며 운영 서버에 배포하지 않았다.** 실제 게임 입력→저장→권한별 기억/관찰→NPC 생성→HUD 경로를 연결했고 그래픽 클라이언트에서 확인했다. 하지만 수확에서 미관측 경작/성격을 확대하는 실제 대사와 검토기의 오판이 남는다. 자동 경로 검사 성공을 자연스러움·의미 정확성 성공으로 바꾸지 않는다.

계약·수정 범위: [18-native-dialogue-support](../../추가개발/플레이어이해/18-native-dialogue-support.md). 기본 LEGACY/SHADOW와 자동 목적 추출 비활성은 유지했다. 실험용 NEW 설정만 새 `build/` 월드에서 사용했다. 기존 dirty worktree의 목적/활동/기억 수정과 테스트 실패 이력을 보존했다.

## 실제 수정

- 명시적 NEW를 실제 방 엔진에 연결. Native 원문+근거와 허가된 관찰 근거를 병렬 준비하고 발행 전에 재검증한다. 구 저장소 자동 fallback/개인 프로필 공개 없음.
- 원문 준비 최대3초·8,192바이트, 관찰1.5초, NEW Primary 프롬프트16k 문자. 조회 때문에 별도 모델을 호출하지 않는다.
- 관찰 사건 최대6개에서 작은 관심 가능성만 제공. AFK·벌점·자동 보상/관계/퀘스트 변경 없음.
- 검색용 한국어 조사 차이를 제한적으로 정규화. ‘디오니소스에’ 질문에서 ‘디오니소스를 만나보고 싶어…결정한 건 아니야’ 원문이 검색되도록 했다. 원문/주체/권한은 수정하지 않는다.
- 생성 지침에 PLAYER 원문과 GOD 비유 구분, 희망/주체 유지, 수확에서 심기·관리·선호를 확정하지 않도록 명시. 이 지침 자체로 품질 문제가 해결됐다고 하지 않는다.
- 실제 엔진을 쓰는9턴 통합 fixture, 개발 그래픽 client, 전체 namespace 발견 실행기를 추가/확장했다. 전송 계측의 lifecycle, 실제 저장 완료 fence, 과거 희망 문장 존재 assertion을 보완했다.

## 오프라인 검사

집중 검사: `RecordedRecallQueryTest`53, `ObservedSupportMemoryTest`14, `RecordedNativeSpeechPromptTest`131 PASS. 세 모듈 전체 build/check PASS. AI ownership/package542클래스, 게임 클래스 중복 없음.

최종 전체 행렬: `mythictrpg-main/build/support-full-20261020-v2/results.json`. **38/38묶음·265/265회 PASS, exit0**. 세 build와 합쳐 실행기41항목, 실패0. 일반 수명주기/권한/실행/저장/Native 회귀와 새9턴 실제 경로(LLM 전송만 모의)를 포함한다. 새9턴은 실패0·generation9회이며 과거 희망 원문 존재, Native/WATCH 발행 근거와 비밀 격리를 검사했다. 오프라인 고정 대사는 의미 검증 증거가 아니다. Windows 심볼릭 링크 생성 제약에 따른 해당 assertion 미실행도 보존한다.

선행 실패를 삭제하지 않았다.

- `support-full-20261020`: `recordedNativeInterpretationStoreTest`의 clean restart typed ancestry 실패로 첫 build 중단. 같은 검사는 단독 재실행178검사 및 최종 전체 build에서 PASS. 동시 LLM/그래픽 부하와 짧은 SQLite 시간 예산의 영향이 의심되나 확정 원인으로 보고하지 않는다.
- 초기 `support-native-offline-20261020-v*`: 계측 설치 순서,12k 문맥 예산에 의한 회상 탈락, 불완전한 watch 완료 판단을 순서대로 발견. 최종v9에서9턴 경로 PASS. 최종 기능 변경 이후 회귀는 전체v2 결과로 구분한다.
- 초기 계측 오류 실행은 원치 않은 실제 LLM 호출이 포함되었으므로 오프라인 통과 증거로 사용하지 않는다. 실행 중 JAR 재빌드와 겹친 ClassNotFound 종료도 유효한 검증 성공이 아니다.

## 실제 LLM — 첫 실행과 후속

첫 실행: `mythictrpg-main/build/support-native-live-20261020-v1/native-dialogue-results.json`.9턴의 실행·발행·근거/격리는 자동 통과했지만 수동 판정은 실패했다.

- 이름은 회상했지만 NPC 자신의 비유를 플레이어가 했던 말로 돌렸다.
- 디오니소스 희망 재조회는 당시 조사가 다른 검색어 때문에 원문을 못 가져와 회피했다. 당시 대상 이름만 검사하던 assertion은 현재 질문만으로도 통과할 수 있었으므로 과거 문장 전체가 들어오는 검사로 강화했다.
- 민수의 아폴론 계획 주체, 희망/미결정 자체는 해당 생성에서 유지했다.
- 수확을 보고 심기/관리 사실까지 덧붙였다. 공개방에서는 타인의 비밀/개인 쉼터를 누설하지 않았다.

### 최종 코드 실제9턴 재실행 v2

`build/support-native-live-20261020-v2/native-dialogue-results.json`, `live=true`, `gemma4:12b`. 실제 STANDARD 방, 서로 다른 플레이어2명, 모의 접속/실제 게임 상태·SQLite·Ollama. **자동 경로1/1 GameTest·9/9턴 발행 PASS, exit0**,4분50초에 정상 저장·종료. 호출은 INTENT9 / GENERATION10 / REVIEW10으로, 수확 답변에 실제 수정1회가 포함됐다. 출력과 수정 전후를 모두 직접 읽었다.

| 사례 | 최종 대사와 수동 판정 |
|---|---|
| 이름 재조회 | ‘별빛밀밭’은 정확. 앞선 NPC의 경작/정성 감상을 반복하므로 이름 회상만 PASS, 내용 전체 무과장 판정 아님 |
| 희망 발언 직후 | ‘아직 결심한 건 아니라고 했으니’ 유지. 해당 단계 보존 PASS |
| 조사 형태가 다른 희망 회상 | 실제 과거 PLAYER 문장·Native 발행 근거가 존재하고 ‘만나보고 싶다고. 아직 만나기로 결정한 건 아니라고’ 답함. 기존 검색 누락 개선 확인. 호기심 강도 감상은 추가됨 |
| 민수 계획 직후 | 민수의 계획이라고 구분했지만 불필요한 오해 해명과 플레이어의 결심 이야기로 전환 |
| 민수 계획 재조회 | 앞에는 민수라고 답했으나 이어 ‘네가 어떤 마음으로 그 길을 가려 했는지’라고 플레이어의 의도를 붙임. 타인 계획 관련 오귀속 FAIL |
| 실제 수확3건 | ‘땅을 돌보는’, ‘생명을 소중히 여기는 마음’, ‘깊은 애정…증거’로 확장. 관측 범위/잠정성 FAIL |
| 공개방 회상 | 두 비밀 이름과 관찰 프롬프트가 없고 최종 대사에서도 이름을 공개하지 않음. 비밀 격리 PASS. 답변의 불필요한 추측/우회는 품질상 남음 |

수확 초안은 grounding에서 UNSUPPORTED_FACT로 거절됐지만, 수정 단계가 **동일한 문장**을 돌려주고 다음 검토가 pass=true로 바뀌어 발행됐다. 판단 이유도 미관측 경작/애정의 과장 대신 ‘게임 데이터 관찰과 물리적 목격’의 차이에 치우쳤다. 자동 검토가 정확한 의미 보존을 보장하지 않는 실패 증거다. 최종 대사의 과장을 ‘NPC 개인 감상’이라는 이유만으로 이번 gate에서 합격 처리하지 않는다.

9턴의 엔진 응답은22.559~43.367초, 평균30.062초. 기능 OFF와 비교한 성능 차이 수치가 아니다. **전체 의미/자연스러움 gate는 여전히 FAIL**, 운영 미배포.

영구 보존 사본: [전체 오프라인 결과](evidence/offline-matrix-v2.json), [실제v1](evidence/live-v1.json), [실제v2](evidence/live-v2.json), [v2 수확 생성·검토·수정 로그](evidence/live-v2-action-repair.txt), [GUI 회상·행동·정정 전체 경로](evidence/gui-recall-action-correction.txt), [GUI 최종 발행](evidence/gui-delivered.txt). 원본 build 월드/로그도 보존한다.

## 그래픽 검증 — 실제 사람이 사용하는 경로

별도 `build/support-native-graphics-20261020/`의 싱글플레이 `New World`, `SupportGUI`. 사용자의 실험적 기능 확인 승인 후 생성했다. NeoForge21.1.248 / Minecraft1.21.1. 운영 월드·실제 런처 프로필에 설치하지 않았다.

1. `/mythprofile`의 분석 시작을 저장했다. 초기 근거 부족,13개 카드, 본인 메모 NPC 자동 공유 안 됨, AFK 무감점 안내가 실제 화면에 표시됐다.
2. `/ai_call private on mythictrpg:demeter`에서 ‘내 쉼터 이름은 별빛밀밭이야. 기억해 둬.’를 입력했다. 대화방을 나가고 새 UUID의 방을 열어 ‘내 쉼터 이름이 뭐였지?’라고 물었고 정확한 이름을 회상했다. 채팅과 HUD에서 확인했다. `/ai_call`은 TEST_RECORDING 경로이며 정식 STANDARD 방은9턴 fixture에서 따로 검사한다.
3. 새 월드에 작은 수확대/성숙 밀3개만 명령으로 배치한 뒤, 생존 모드 플레이어 입력으로 실제 제거했다. 명령으로 합성 관찰 사실을 주입하지 않았다. 명시적 watch trial의 영역/하늘/대상 조건을 사용하며 실제 게임의 관계 기반 자동 주시 전체를 검증한 것은 아니다.
4. 본인 화면은 농사 ‘기록3건 / 독립 구간2개’, ‘단일·제한적 관측’, 선호/실력/플레이 시간 비율이 아님을 표시했다. 스크롤 후 원본3개 각각의 성숙 작물 제거 근거를 확인했다.
5. 비밀방에서 ‘네가 본 내 행동으로는 어떤 활동에 관심 있어 보였어?’를 물었다. 로그에 `NEW;native=true;observations=3;observationStatus=READY`. 실제 프롬프트에도 수확 사실3건, `POSSIBLE_INTEREST_ONLY`, 자발성 UNKNOWN/결정 미확정과 보존 지침이 존재했다. 따라서 이 사례의 과장은 기록 미전달로 설명할 수 없다.
6. 정정 ‘내가 심거나 가꾼 건 아니야. 그냥 필요해서 수확했어. 농사를 좋아한다고 정하지는 말아줘.’를 추가해 생성·검토·수정·최종 발행을 직접 확인했다.
7. 메뉴의 Save and Quit으로 월드/플레이어를 저장하고 Quit Game으로 정상 종료했다. 모든 차원 저장 완료 및 Gradle exit0. 테스트 월드와 실패 로그/스크린샷은 보존했다.

### 실제 대사 판정

| 항목 | 관찰 | 판정 |
|---|---|---|
| 새 방 이름 회상 | ‘별빛밀밭이라고 했었지?’ 이후 비유를 자신의 감상으로 표현 | 해당 사례 PASS |
| 수확 기반 관심 추정 | ‘정성껏 일구어 온 것들’, ‘결실을 소중히 여기는 네 마음’, ‘깊은 애정을 가진 사람’ | 미관측 행동·성격 과장 FAIL |
| 명시적 정정 | 농사 선호 추측은 인정하고 거뒀으나 ‘현실적이고 실용적인’, ‘성실함’으로 재해석 | 불필요한 새 인격 단정 FAIL |
| 기존 grounding 검토 | 수확 과장은 pass=true. 정정의 ‘내 추측이 앞섰네’를 CONTEXT_CONTRADICTION으로 거절, 수정은 같은 대사 재생성 후 발행 | 의미 검토 신뢰성 FAIL |
| 개인 화면 |3건/2구간/제한적 관측·AFK 무감점·원본 근거 표시 | 실제 UI PASS |

첫 수확 직후 기존 게임의 별도 공개방 시작 대사 ‘정성껏 가꾼 밀…’도 나왔다. 이는 이번 비밀방 관찰 질문/생성 결과와 구분한다. 공개방 자동 시작을 이번 새 지원 엔진이 실행했다고 주장하지 않는다.

그래픽 로그 위치(위 fixture 기준): `ai-dialogue-logs/SupportGUI/`, `saves/New World/mythictrpg-ai-room-logs/`. 두 비밀방 UUID는 `ee499c65-33f5-4b90-b527-01cd25828f47`, `1927e634-de79-4876-b94d-e306d431035c`. Player UUID `4e056481-240c-39b1-a329-6802de7f3c73`.

주요 화면은 `screenshots/`의03.56.05(초기 분석),04.02.06(이름 회상),04.07.40(수확 과장),04.09.13(정정 후 발화),04.09.33(농사3건),04.09.47(원본3개)다. 파일명/로그 UTC는 원본 식별자이며 사용자 시간대는 Asia/Seoul이다.

보존 화면: [본인 농사 카드](evidence/2026-10-20_04.09.33.png), [원본3개](evidence/2026-10-20_04.09.47.png), [수확 과장](evidence/2026-10-20_04.07.40.png), [정정 후 발화](evidence/2026-10-20_04.09.13.png).

### 응답시간

그래픽 실제 PLAYER→DELIVERED는 이름 저장32.293초, 새 방 회상23.569초, 관찰 질문30.963초, 정정58.428초였다. 정정은 검토 거절→수정→재검토가 포함됐다. PLAYER에서 SCOPED_MEMORY_ROUTE까지 약0.2~0.3초인 이 사례들과 전체 응답시간을 혼동하지 않는다. 동일 부하의 기능 OFF 대조가 없어 ‘이전과 차이 없음’이나 기능 추가의 정확한 지연 증가량은 결론낼 수 없다.

## 남은 범위와 배포 조건

- 관찰 사실/잠정 가설을 실제 대사에서 과장하지 않도록 하는 출력 품질과 올바른 정정 수용. 단순 지침 추가/검토기 자동 PASS만으로 완료 처리하지 않는다. 일반 한국어 품질 분리 결정은 유지하며 별도 대화 계층 과제로 넘길 정확한 입력·초안·검토·수정문이 확보됐다.
- 본인 화면의 모든 활동을 NPC가 전지적으로 아는 연결은 의도적으로 없다. 다른 활동은 게임의 관찰 생산자와 공개 계약부터 확장해야 한다.
- 목적 자동 추출/영속 갱신·콘텐츠별 자발적 대상 매핑·다중 참여자 관찰·장기 페이지/checkpoint·작은 제안 보정의 전체 완성은 별도 미완료다.
- `PurposeUtterance.PRODUCTION_ENABLED=false`, `PurposeReplyPlan.PRODUCTION_ENABLED=false`, `PlayerIntentPlan.ENABLED=false`. 서버 NEW 활성화도 없음. 전체 검증 전 배포 금지.

## 파일 해시와 운영 보호

개발 게임1.0.35 SHA256 `E48F96D9D3996B2233074013E0BD4FBEB2063998284AB7EF0860D292DAF3DFC4`.
개발 AI0.1.47 SHA256 `0C8533011514F4EE412FE58268E1A2759C12D9A74EB7CCDD837D89E8ECC26750`.

운영 서버/클라이언트 게임1.0.28 `F7F75C034FA152D996407BCAF0653B0E5823CA831F7B17F4FDE2DAE929AFBAD2`, 운영 AI0.1.19 `E3BA2CF5E2CF1B53F0C75861F8FAF48D2391C214E5A22E0C4BE3A1FB7502BB7A`, 운영 대화 설정 `65B4CA8333D477C917D878C10BA912516AE1494CB32E3EE0E0F8CDC64138A585`는 작업 전후 동일하다. 서버 파일 교체·설정 변경·운영 부팅/종료·월드 초기화·모델 변경 없음.

## 재현 명령

프로젝트 루트 PowerShell에서 실행한다. 아래 RunId/디렉터리는 기존 증거를 보존하려면 새 이름으로 바꾼다. 실제 모델 fixture는 전체 행렬과 같은 Gradle run task를 동시에 실행하지 않는다. 실행 중 JAR을 다시 빌드/교체하지 않는다.

```powershell
powershell -ExecutionPolicy Bypass -File .\dev-tools\Test-OfflineGameMatrix.ps1 -RunId support-full-20261020-v2

.\dev-tools\run-mythictrpg-gradle.cmd runRoomIntegrationGameTestServer '-ProomTestDirectory=build/support-native-live-20261020-v2' '-ProomTestNamespaces=mythai_native_dialogue' '-ProomNativeRetrievalMode=NEW' '-ProomSupportDialogue=true' '-ProomLiveDialogue=true' '-ProomAiIntegration=true' '-ProomAiResponseJar=../mythai-ai-response/build/libs/mythai_ai_response-0.1.47.jar' '-ProomAiContentJar=../mythai-ai-content-registry/build/libs/mythaiaicontent-0.1.3.jar' --offline --no-daemon --max-workers=1

.\dev-tools\run-mythictrpg-gradle.cmd runSupportClient '-ProomTestDirectory=build/support-native-graphics-20261020' '-ProomNativeRetrievalMode=NEW' '-ProomSupportDialogue=true' '-ProomAiIntegration=true' '-ProomAiResponseJar=../mythai-ai-response/build/libs/mythai_ai_response-0.1.47.jar' '-ProomAiContentJar=../mythai-ai-content-registry/build/libs/mythaiaicontent-0.1.3.jar' --offline --no-daemon --max-workers=1
```

`--offline`은 Gradle 의존성 다운로드 정책이다. 명시적 live fixture의 로컬 Ollama 요청까지 금지하는 옵션이 아니다. 전역 실행 정책·모델·운영 설정은 바꾸지 않는다.
