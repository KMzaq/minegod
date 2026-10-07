# 기록 v2 M5 — 공통 검색 묶음과 SHADOW 렌더링

2026-09-30 / `HanesTest`, 게임 `1.0.22`, AI `0.1.25` 개발 작업본. **통합 집중13개 task 및 격리 SHADOW fullwire/NEW 거절 GameTest 각각1/1 성공. 운영 미배포·실제 모델 미호출이며 NEW foreground 및 전체 M5 완료가 아니다.**

## 1. 이번에 바뀐 실제 경로

이전에는 원문·semantic·해석·Watch·Rumor의 SHADOW 소비자가 각각 비교를 수행했다. 현재 production `MythAiRoomConversationEngine`에서는 이 여러 진입을 **[RecordedRetrievalShadow.compare](../../../mythai-ai-response/src/main/java/com/sande/mythai/response/memory/RecordedRetrievalShadow.java) 한 곳**으로 교체했다.

```text
기존 대화/기억 선택 완료
    → 기존 선택 ID 및 이미 만들어진 회상 계획 전달
    → 실제 게임 read session 1개 발급
    → 선택적 query vector 1개
    → 원문 / semantic / 파생 해석 / Watch / Rumor의 순차 조회
    → 현재성 검증된 일시적 typed Bundle
    → SHADOW용 구조화 렌더링 검증
    → 내용 없는 비교 집계
```

기존 답변 생성은 이 비교를 기다리지 않는다. 비교를 위해 legacy 검색을 다시 실행하지도 않는다. 예전 `RecordedMemoryShadow`, `RecordedSemanticShadow`, `RecordedInterpretationShadow`, `RecordedObservationShadow`, `RecordedRumorShadow`의 단독 경로는 기존 계약·집중 fixture용으로 남았지만 production engine이 전부 병행 호출하지 않는다. 새 결과는 실제 대사 프롬프트·게임 Proposal·관계·퀘스트·보상·후속 발행 히스토리에 아직 넣지 않는다.

## 2. Query와 공유 권한

[RecordedRecallQuery](../../../mythai-ai-response/src/main/java/com/sande/mythai/response/memory/RecordedRecallQuery.java)는 이미 계산된 player 회상 계획을 재사용한다. 이어지는 짧은 질문도 이전 focus의 room generation·플레이어·화자 신·청중·질문/시각이 일치할 때만 사용한다. 임의의 오래된 질의를 다른 방으로 가져오지 않는다.

- 화자 조건은 실제 발화자 metadata를 좁히는 `ActorSelection`이다. `플레이어가 말함`, `현재 신이 말함`, `다른 신이 말함`을 소문의 subject나 Watch observer로 바꾸지 않는다.
- 화자 선택은 검색 필터이지 지식 획득이나 공개 권한이 아니다. 부모 발언/파생물의 전체 입력은 별도로 권한 검사한다.
- 발언 속 “내일”은 실제 발화 발생 시각과 다르므로 언급된 날짜를 원문 occurrence-time 필터로 오용하지 않는다.
- Watch/Rumor는 현재 private·정확히 한 신이라는 기존 경계를 유지하며, actor selector 또는 UTC 조건이 있으면 그 조건을 ANY/무시로 넓혀 조회하지 않는다. Rumor는 기존 `RUMOR_TEST` 축도 별도로 필요하다.

게임이 발급한 [MemoryReadSession](../../../mythictrpg-main/src/main/java/com/sande/mythictrpg/recording/api/MemoryReadSession.java) 하나를 모든 경로가 공유한다. 발급 당시의 같은 committed watermark `W`, room/turn/revision, 신/플레이어 청중, 현재 proof/receipt/계보 경계가 유지된다. lane마다 새 session을 열어 더 최신 권한이나 별도의 호출 예산을 얻지 않는다.

## 3. Typed Bundle은 portable proof가 아니다

[RecordedRetrievalBundle](../../../mythai-ai-response/src/main/java/com/sande/mythai/response/memory/RecordedRetrievalBundle.java)은 다음 결과를 구분해서 보관한다.

| 경로 | 보관 의미 |
|---|---|
| raw | 실제 화자가 말한 원문/발췌. 발언 내용이 사실이라는 뜻은 아님 |
| semantic | 권한 검증된 native 원문의 prefix 및 similarity/coverage |
| interpretations | 정확한 인용·전체 입력 coverage·취소/정정 link를 동반한 비권위 CANDIDATE |
| observations | 신이 실제 관찰한 허용된 과거 Event projection |
| rumors | 실제 수신 주장과 해당 신의 현재 reception/assessment |

각 lane에는 `status`, `entries`, `attempted`가 있다. `UNAVAILABLE`, `STALE` 또는 미시도 lane에 본문이 남아서는 안 된다. `PARTIAL`, 빈 결과, 예산상 생략은 사건/약속/소문이 없었다는 증거가 아니다.

Bundle의 Scope는 요청 식별용 DTO이며 권한 토큰이 아니다. Scope가 같거나 JSON이 유효하다는 이유만으로 사용할 수 없다. 발급 session이 실제 반환한 페이지의 identity와 current predicate를 보존하고, `bundle.current()`로 게임 스레드에서 다시 확인해야 한다. 다른 방·턴·청중, source/receipt 철회, 현재 평판 변화, projection revision 등 해당 경로의 무효화 조건을 피하지 않는다.

Bundle은 임시 비교 결과다. 저장하거나 다른 요청으로 옮겨 쓸 수 있는 game-owned seal, 모델에게 직접 줄 수 있는 권한, 새로운 archive 조회 capability로 취급하지 않는다.

## 4. 한도와 실패 처리

[RecordedRetrievalCoordinator](../../../mythai-ai-response/src/main/java/com/sande/mythai/response/memory/RecordedRetrievalCoordinator.java)의 production 시작값:

- 총 read 최대8회, 동시에1회. 개별 source/해석/cursor 조회도 이 공통 예산에 포함한다.
- 총 응답 자료 예산32KiB, lane별 최대4개 선택 자료, 페이지 continuation 최대2회 설정.
- 수집 시간15초. 원래 game session의60초 lease 및 개별 read2초 timeout/SQL 약250ms 한도를 늘리지 않는다.
- 받은 전체 typed card의 metadata·인용·source·coverage까지 계산한다. 중복이나 similarity 미달 응답도 받은 비용에는 포함한다.
- 원문 API의 본문 byte 예산과 소비자의 전체 JSON card 예산은 다르므로 escaping 확장과 row metadata의 여유를 보수적으로 계산한다. UTF-8/UUID/Instant/Optional은 명시적 wire 형태로 처리한다.

모든 읽기를 순차 처리하며, 한 경로의 일반적인 unavailable은 해당 경로를 사용할 수 없다고 표시한다. 반면 어떤 보유 페이지의 권한이 `STALE`이면 앞서 모은 내용을 다른 성공 페이지로 정당화하지 않고 전체 결과를 사용할 수 없게 한다. dispatch 거절·취소·전체 timeout은 private 내용을 없는 실패 묶음으로 닫으며, 완료 후 늦은 callback은 새 요청을 열거나 이전 partial 결과를 다시 노출하지 않는다.

실제 저장소가 계속 `PARTIAL`을 반환하는 이유와 각 source의 한도는 [native 원문 색인](RECORDING_V2_M5_LEXICAL_SEARCH_20260930.md), [파생 해석](RECORDING_V2_M5_NATIVE_INTERPRETATION_READ_20260930.md), [semantic](RECORDING_V2_M5_NATIVE_SEMANTIC_READ_20260930.md), [Watch](RECORDING_V2_M5_WATCH_OBSERVATION_READ_20260930.md), [Rumor](RECORDING_V2_M5_NATIVE_RUMOR_READ_20260930.md)를 따른다. 이 묶음이 이를 전체 검색 완료로 바꾸지 않는다.

## 5. 정정/취소 근거를 함께 유지

의미 검색에서만 찾은 원문도 그 **실제 발급 semantic page**를 seed로 사용해 파생 해석을 요청한다. 가짜 raw page나 임의 message ID로 권한을 만들지 않는다.

정정 card를 받은 뒤 예산이나 후속 조회 실패로 그 card를 버리면, 이미 확인한 취소 내용은 없어지고 옛 약속만 완전한 기억처럼 남을 수 있다. 이를 막기 위해 coordinator는 버리는 해석의 전체 input message를 추적하고, 연결된 해석·raw·semantic 자료도 해당 묶음에서 제거하거나 PARTIAL로 남긴다. 이것은 취소 사실을 게임 상태에 적용하는 것이 아니라, 알고도 맥락을 잘라서 잘못된 기억을 제시하지 않도록 하는 처리다.

여전히 검색하지 못한 정정이 있을 수 있다. 그래서 정정 card를 못 찾았다고 옛 약속이 현재도 유효하다고 확정할 수 없으며, 조회 전체를 완전한 히스토리로 부르지 않는다.

## 6. 구조화 렌더러

[RecordedMemoryPrompt](../../../mythai-ai-response/src/main/java/com/sande/mythai/response/memory/RecordedMemoryPrompt.java)는 SHADOW/fixture용 renderer다. production wrapper는 최대16개 연결 묶음/32KiB로 형식을 검증하되, 만들어진 private JSON은 모델에 보내거나 로그·디스크에 저장하지 않는다.

- `RAW_SPEECH`, `CANDIDATE_INTERPRETATION`, `OBSERVED_EVENT`, `RECEIVED_CLAIM`을 구분한다.
- recorded text 안의 역할·명령·ID·허가 주장은 untrusted data로 취급하고 구조화 source metadata를 바꾸지 않는다.
- 인용/정정/link/input을 message 기준으로 연결하여 한 atomic group으로 포함하거나 전체를 생략한다. 긴 정확 인용을 중간에서 잘라 반대 의미로 만드는 요약은 하지 않는다.
- 의미 벡터의1,600 UTF-16 prefix coverage와 더 긴 extraction coverage는 별개다. link가 있다는 이유로 벡터가 원문 전체를 읽었다고 표시하지 않는다.
- Rumor의 `UNKNOWN`/`UNASSESSED`와 실제 판단값을 그대로 구분한다. 신의 acceptance를 독립 증거나 월드 정사로 확정하지 않는다.
- 틀 자체가 byte budget에 들어가지 않으면 `UNAVAILABLE`. 생략 여부와 lane 상태를 표시하며 항상 bounded partial임을 알린다.

`Rendered.contentFor(expectedScope)`는 scope 일치와 현재성을 확인한다. `content()`도 current guard 없이 문자열을 내주지 않는다. `toString()`에는 private 내용 대신 상태/개수만 있다. 이러한 renderer guard만으로 NEW foreground에 필요한 portable proof·출력/히스토리 취소 연동이 완성된 것은 아니다.

## 7. 선택적 query vector와 진단

명시적 회상이며 적절한 길이인 질의에 한해 [RecordedEmbeddingService.submitQuery](../../../mythai-ai-response/src/main/java/com/sande/mythai/response/memory/RecordedEmbeddingService.java)가 선택적 query vector 하나를 만들 수 있다. 기존 executor와 process-wide `ModelAdmission.followup`을 사용한다. foreground가 시작되면 기존 선점 규칙을 적용하며 별도 병렬 모델 pool을 만들지 않는다.

호출자가 취소하거나 SHADOW가5초 대기를 끝냈어도 실제 backend가 반환하기 전에는 querying/admission을 풀지 않아 모델 호출이 겹치지 않도록 한다. 늦게 끝난 vector가 새로운 session을 다시 열지 않는다. unavailable vector는 SHADOW의 semantic 경로를 unavailable로 남길 뿐이며, NEW가 자동 legacy로 되돌아가는 동작은 아니다.

최종 집계 직전에 페이지/묶음/렌더러의 현재성을 재확인한다. 진단은 완료/불가, read 횟수, 각 lane의 기존 선택과 겹친 개수, partial/unavailable lane 수, 렌더링 group 수뿐이다. claim·원문·신/플레이어 ID·개별 방·현재 평판 내용을 기록하지 않는다. 이 개수만으로 검색 품질·자연스러움이나 실제 모델의 의미 이해를 입증하지 않는다.

## 8. 검증 이력

최종 통합 집중 실행은 **13개 task 성공**, engine **459개 class / 게임 클래스 중복0**이다.

| 검사 | 통과 수 |
|---|---:|
| Shared retrieval coordinator | 185 |
| 구조화 prompt renderer | 65 |
| Embedding query scheduling/cancellation | 17 |
| Shared SHADOW wrapper | 13 |
| 기존 room engine | 98 |

최초에는 테스트 이름 충돌로 컴파일이 실패했다. 명명 충돌 수정 뒤 실행한 첫 `--continue`에서는 renderer fixture의 semantic `excerpt`/coverage 조합이 일치하지 않아 해당 검사가 실패했고, 그 실행에서 나머지185/17/13/98은 통과했다. fixture를 계약에 맞게 수정한 뒤 위13-task 실행에서 renderer65를 포함해 모두 통과했다. 최초 실패나 `--continue`의 부분 성공을 전체 성공으로 소급하지 않는다.

첫 실제 GameTest 실행에서는 NEW 거절 fixture가 fullwire의 template namespace를 공유해 SHADOW 전용 실행에 끼어들었다. 또한 fullwire의 새 SQLite 비동기 대기 한도가 짧아 실패했다. 테스트 template/namespace를 분리하고 격리 fixture의 대기를 수정한 뒤 AI7개 task로 JAR를 다시 생성했다.

그다음 v2 fullwire는 required test가 정확히1개였으나 phase4에서 실패했다. foreground의 생성6회·발행9회는 성공했지만 공유 bundle 완료는0이었다. 격리 fixture의256MiB archive 한도가 `FULL`에 도달했고, 즉시 끝나는 mock 응답이 비동기 SHADOW 조회의 room lease를 먼저 닫는 조건이었다. 이 실패를 foreground 성공만으로 통과 처리하지 않았다. **테스트 전용 archive 상한만2GiB로 높이고 mock 생성에1초 지연을 넣었다.** 상한은 사전 디스크 할당이 아니며 외부 모델을 호출하지 않는다. 운영 quota나 권한 lifetime을 완화한 변경은 아니다.

수정 뒤 실제 개발 GameTest 결과:

| 격리 개발 경로 | required 결과 | Gradle 결과 |
|---|---|---|
| `mythai-ai-response/build/recording-bundle-shadow-wire-v3-20260930` | SHADOW fullwire **1/1 성공**,6.666초 | 9개 task 성공,19초 |
| `mythai-ai-response/build/recording-new-policy-block-20260930` | NEW 조기 거절 **1/1 성공**,118.8ms | 9개 task 성공,12초 |
| `mythictrpg-main/build/recording-bundle-authority-20260930` | native authority 재검사 **1/1 성공**,448.9ms | 9개 task 성공,12초·정상 종료 |

각각 격리된 개발 fixture이며 NEW 정책은 독립 template namespace를 사용한다. NEW 거절 성공은 NEW 검색/프롬프트 활성화 성공이 아니다. JAR 생성이나 프로세스 종료 코드만으로 required test 성공을 대체하지 않는다.

독립 설정 분리의126개 집중 검사와 최초 source-cursor fixture 수정은 [retrieval switch](RECORDING_V2_RETRIEVAL_SWITCH_20260930.md)를 따른다. 운영 설정 변경·운영 서버 배포·실제 Ollama·클라이언트 인게임 품질·대규모 성능 검사는 하지 않았다.

### 후속 native 근거 연결 재검사

[NativeEvidenceGameTests](../../../mythictrpg-main/src/main/java/com/sande/mythictrpg/recording/server/NativeEvidenceGameTests.java)는 실제 게임 발급 요청/RoomRecordingCapture/SQLite의 RAW 페이지를 seal로 발급하고, 새 방의 허용된 동일 청중에서 저장된 참조를 다시 검증했다. 조작된 room/turn/public/secondary 범위와 복제된 page/seal은 거절되며, 각 방을 종료하면 해당 요청의 page/seal/cache 권한도 닫힌다. `mythictrpg-main/build/native-evidence-runtime-20260930`에서 **required1/1 성공,531.6ms**, Gradle **9개 task 성공,13초**였다.

이어 실제 history 근거를 비동기 준비한 뒤 최종 current 검사로 pruning하는 변경을 포함한 SHADOW fullwire 재검사는 **required1/1 성공,6.628초**, Gradle **9개 task 성공,18초**였다. 두 실행 모두 가짜/보류 engine을 사용하는 격리 개발 검증이며 외부 모델을 호출하지 않았다.

실제 runtime room producer의 권한을 테스트만을 위해 넓히지 않았으므로 위 GameTest는 source receipt 철회를 시행하지 않는다. 그 항목은 `RecordedNativeEvidenceStoreTest.withdrawal(Path)`의 별도 실제 SQLite fixture에서 실제 수신 receipt 철회, 대기 중 즉시 무효화, 새 prepare 거절, 다른 신의 독립 권한 및 source revision 교체를 검사하는 범위다. 추가 FULL/8회 조회 후 발급/PRIVATE player의 원래 watermark 검사는 별도 진행 상태를 따르며, 이번 GameTest 성공으로 전체 schema9 검증을 완료했다고 하지 않는다.

이후 private PLAYER의 실제 delivery receipt도 원래 watermark 안에 있어야 한다는 gate를 추가한 뒤 집중 실행은 **native SQLite109 / RAW719 / codec92 / cache114 검사 통과**, Gradle **11개 task 성공,34초**였다. 그러나 같은 변경 뒤의 실제 runtime 재실행 `mythictrpg-main/build/native-evidence-runtime-final-20260930`은 phase3의 seal 미발급으로 **required1개 실패,525.4ms**, Gradle9개 task 실행·12초였다. 따라서 앞선 runtime 성공을 이 후속 변경의 최종 성공으로 적용하지 않는다.

실제 CHAT의 발송 view에는 방 코드·플레이어 이름 접두사가 들어가는데 새 gate가 그 전체와 발언 원문이 같아야 한다고 요구한 계약 불일치였다. 저장된 실제 view/parts/receipt hash를 그대로 검증하면서 CHAT의 원문 suffix와 HUD의 원문 일치, 정확한 delivery work 및 원래 watermark를 함께 보존하도록 수정했다. 수정 후 같은 집중 검사 **109/719/92/114가 재통과**, Gradle **11개 task 성공,31초**였다. `mythictrpg-main/build/native-evidence-chat-prefix-20260930`의 실제 native GameTest는 **required1/1 성공,509.1ms**, Gradle **9개 task 성공,12초**였다. 이어 `mythai-ai-response/build/native-history-chat-prefix-20260930`의 history fullwire도 **required1/1 성공,6.636초**, Gradle **9개 task 성공,18초**였다. fixture를 완화하거나 producer 권한을 넓힌 해결이 아니며 운영 서버·설정·배포는 변경하지 않았다.

그다음 실제 CONTENT owner 경계를 확인하기 위한 별도 `NativeContentEvidenceGameTests`가 추가되었다. 첫 실행 `mythai-ai-response/build/native-content-owner-wire-20260930`은 fixture 플레이어 이름이 Minecraft의16자 제한을 넘은17자여서 초기 접속 단계에서 실패했다(required1개 실패,359.6ms,9개 task 실행·12초). 이름만13자로 수정했으며 게임의 패킷/이름 검증을 완화하지 않았다. 아직 이 후속 테스트를 성공으로 합산하지 않는다.

## 9. 아직 남은 전환 조건

`NEW`는 [별도 설정](RECORDING_V2_RETRIEVAL_SWITCH_20260930.md)에서 요청값을 읽되 `BLOCKED_CONTRACT_NOT_READY`로 명시 차단한다. [native 근거 설계/범위](RECORDING_V2_NATIVE_EVIDENCE_SEAL_DESIGN_20260930.md)의 RAW/semantic 원문 seal과 저장 참조 재검증 경로는 후속으로 구현·검증 중이다. 이것은 Bundle 전체의 해석·Watch·Rumor를 sealing했다는 뜻도, NEW foreground가 켜졌다는 뜻도 아니다. foreground prompt 수용, 실제 발행 여부와 전체 후속 인용 히스토리, 철회/재시작 경계가 해당 지원 범위까지 연결되기 전에는 이 Bundle을 프롬프트에 주입하지 않는다.

또한 native 장문 전체 semantic coverage, 오래된 후보의 효율적인 전역 랭크 결합, Watch/Rumor 의미 검색·파생, 실제 모델 품질/규모 수용은 이 slice의 완료 범위가 아니다. 각 typed lane을 공통 예산으로 연결한 것은 해당 문제를 모두 해결했다는 뜻이 아니다.
