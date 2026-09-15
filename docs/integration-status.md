# MythicTRPG 인수인계 및 통합 상태

전체 진행도와 단계별 개발 체크리스트는 `docs/implementation-roadmap.md`를 기준으로 관리한다.

## 보존 원본

- 경로: `incoming/mythictrpg-upstream`
- HEAD: `a6ffb9051d6a57ae87981a25c08b91f0d111832f`
- 검증 기능 기준 포함: `4e20737fea42160c52c52cf8b95588f0fbf1971b`
- 원본 작업 트리: clean
- 이 폴더는 비교와 복구용이며 수정하지 않는다.

## 개발본

- 경로: `mythictrpg-main`
- 브랜치: `integration/ai`
- 인수인계 태그: `upstream-handoff-2026-08-25`
- Java 21 override를 사용한 `clean build`: 성공
- `runGameTestServer`: 필수 GameTest 158/158 통과
- 기준 로그: `mythictrpg-main/run/logs/latest.log`

## upstream에서 추가된 중요 기능

- production spontaneous resolver/preparer 경계
- player/server in-flight permit와 timeout
- logout/server-stop/stale completion 차단
- read-only interaction dry-run
- Demeter 성숙한 밀 수확 gameplay promotion과 interaction binding
- 기준 GameTest 158개

## 현재 서버 상태

`server/mods`는 2026-08-25부터 새 upstream 개발본과 분리된 AI 응답 모드·콘텐츠 레지스트리 JAR을 사용한다.

- 새 MythicTRPG 정식 resolver/provider 계약 연결 완료
- 서버 권위 AI 대화 ON/OFF 상태와 우측 클라이언트 UI 추가
- 미식별 God 이름은 `????`로 마스킹
- 데메테르 성숙한 밀 이벤트용 AI 콘텐츠 provider 연결
- 교체 전 JAR은 `server/backups/mods-before-ai-provider-2026-08-25`에 보존
- 실제 서버에서 세 모드 로딩 및 `Done` 확인

## 필요한 AI 통합 작업

1. [완료] 기존 `mine2`의 reflection/live-object 브리지를 복사하지 않고 공개 configure-once 계약을 추가했다.
2. [완료] upstream의 `InteractionContentPreparerResolverRouter`에 정식 provider를 등록했다.
3. [완료] spontaneous interaction은 서버가 확정한 God, audience와 bounded signal/plan만 사용한다.
4. [부분 완료] 자유 채팅은 공개 AI conversation engine 계약으로 전달되며 다중 참가자 세션은 후속 구현한다.
5. [완료] AI 응답 모드는 Ollama·분류·프롬프트·대화 문맥·Proposal 생성을 담당한다.
6. [유지] 관계, 퀘스트, 보상, 인벤토리와 월드 변경은 MythicTRPG validator/executor만 수행한다.
7. [미완료] `mythai-ai-response`가 이전 결합형 JAR에서 AI bytecode를 추출하는 임시 빌드 의존성을 제거한다.

## 콘텐츠 정합성 주의

upstream production God은 `mythictrpg:demeter`, `mythictrpg:aphrodite`, `mythictrpg:lubras`다. 현재 콘텐츠 레지스트리의 테스트 프로필은 `mythictrpg:fortuna`이므로 통합 테스트 전에 다음 중 하나가 필요하다.

- 콘텐츠 레지스트리에 Demeter 프로필 추가
- upstream에 정식 Fortuna God definition 추가

콘텐츠 레지스트리에 Demeter 최소 production 프로필을 추가했다. 첫 인게임 검증은 `Demeter + 성숙한 밀 수확` 경로로 진행한다.

## 배포 전 필수 순서

1. upstream 개발본 전체 GameTest 확인 (2026-08-25 기준 158/158 통과)
2. deterministic fake provider
3. Ollama provider dry-run
4. HUD preview
5. 자유 채팅 세션
6. 서버 권위 Proposal validator/executor
7. 새 MythicTRPG JAR로 `server/mods` 교체
