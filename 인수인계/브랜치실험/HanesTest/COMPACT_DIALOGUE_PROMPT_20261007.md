# 주 응답 지침 간결화 + 문맥 예시 실험

2026-10-07, HanesTest. 개발 게임 1.0.27 / AI **0.1.31** / 콘텐츠 0.1.3. **미배포**.

사용자 요청: Gemma 대화 팁 중 간결한 핵심 지침과 문맥 예시를 적용하고 기존 방식과 실제 Ollama 결과를 비교한다.

## 변경 범위

- `mythai-ai-response/src/main/java/com/sande/mythictrpg/ai/RoomPersonaPrompt.java`: 도입부 중복 지침 축소, `NaturalConversationPolicy.primaryText()` 사용.
- 같은 경로의 `NaturalConversationPolicy.java`: 주 응답 전용 간결한 지침과 한국어 허구 예시 3개. 기존 `text()`는 Secondary용으로 그대로 유지.
- 게임 권한·청중·출력 계약, scene 구성/예산, 기억, 분류, 검토/수정 로직, 원본 페르소나, 모델 및 샘플링 설정은 유지했다. 새 IO나 영속 저장 스키마는 없다.
- 테스트 `BaselineVerboseRoomPersonaPrompt` / `BaselineVerboseConversationPolicy`는 이 변경 직전 원본을 동결한 비교용이며 제품 JAR에 포함되지 않는다.
- 테스트 `CompactDialogueLiveEvaluation`과 Gradle `compactDialoguePreflight` / `compactDialogueLiveEvaluation` 추가. 실 LLM 호출은 `-PdialogueLive=true` 명시 실행에만 해당하며 `check`에 포함하지 않는다.

## 결과와 한계

상세 수치·대표 대사·원본 링크·재현 명령은 [비교 보고서](../../../검토결과/compact-dialogue-20261007/COMPARISON.md)를 따른다.

- 기존9 + 추가3 상황, 각3회, 총36쌍. 같은 분류 결과/장면을 사용하고 같은 검토기를 거쳤다. 예시와 같은 유형의 표현 전이와 추가 성격·관계 사례를 포함한 작은 합성 시험이다.
- 시스템 메시지 문자 약15.6%, 최초 생성 입력 토큰 약9.8% 감소. 출력 대사 길이는 거의 같음.
- 이야기 약속을 실제 이어가는 사례는 개선됐지만 문맥 정정·사과·잡담의 자연스러움은 혼재한다. 모르는 경로 오류는 양쪽 각각3/3, 확인 대기 상태도 변경본2회에서 왜곡됐다.
- 후보 반환 기존35/36, 변경36/36은 품질 합격률이 아니다. 모델 검토가 사실 오류를 PASS한다. **전반적인 품질 개선이나 환각 차단 완료로 보고하지 않는다.**
- 관련544검사 + 비교 사전 검사 통과. JAR/소유권 검사 501 classes, 게임 클래스 중복0. 이번 수정은 프롬프트 위주이므로 전체 개발 GameTest는 다시 실행하지 않았다. 실제 Ollama 시험은 완료했지만 인게임 확인은 아니다.

개발 JAR: `mythai-ai-response/build/libs/mythai_ai_response-0.1.31.jar`.
SHA-256: `3dbb211aa635035da7579c859b8c6a035d4faa56e06c4422cb97a79dada18f78`.

운영 `server/mods`는 변경하지 않았다. 소스에 시험안을 적용한 상태이며, 운영 품질 개선판으로 확정한 상태가 아니다. 공용 인수인계로 승격하지 않는다. 후속 작업은 실제 사건 전제·게임 처리 상태를 왜곡하는 생성과 이를 놓치는 검토를 우선 분석해야 한다.
