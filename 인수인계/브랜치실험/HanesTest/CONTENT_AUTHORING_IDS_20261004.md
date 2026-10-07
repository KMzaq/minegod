# 콘텐츠 작성용 God ID 기준과 전달 자료 — 2026-10-04

사용자는 신규 God ID 명명 기준을 **영어 소문자 `소속신화_소속_신이름`**으로 확정했다. 이전 `소속_이름` 기준을 대체한다. 프로필 파일명도 같은 path를 사용하고 namespace는 게임 신 `mythictrpg`, 기본 프로필 콘텐츠 `mythaiaicontent`를 구분한다.

현재 그리스 테스트 데이터팩의 예는 `mythictrpg:greek_olympian_demeter`, `mythictrpg:greek_titan_cronus`다. `olympian`과 `olympus`는 자동 별칭이 아니다. 기존 테스트 `mythictrpg:demeter`, `mythictrpg:fortuna`의 게임 정의/참조/저장 자료는 이번에 이관하지 않았다. 포르투나의 새 소속 분류도 임의 확정하지 않았다.

`mythai-ai-content-registry/NPC_PROFILE_AUTHORING_GUIDE.md`의 신 명명 기준을 갱신했다. 콘텐츠 제작 전달 묶음 v2는 `전달자료/content-authoring-kit-20261004-v2/`와 같은 이름의 ZIP에 있다. God 정의 파일 기준 목록(개발 소스 4 + 서버 그리스 데이터팩 135), 현재 퀘스트·보상·FTB 원문 사본, 신규 연결 양식을 포함한다.

원문 점검에서 개발 포르투나 후보/FTB 목표는 심장 2개, 서버 FTB 목표는 1개임을 확인했다. 현재 데메테르/올림포스 후보 목록은 비어 있고 `test5` 프로필의 게임 신 정의는 점검 범위에서 없다. 원문 차이를 전달 자료에 기록했으며 게임/서버 정의를 고치지 않았다.

이번 작업은 작성 자료와 문서 갱신이다. JSON 구문/파일 ID 연결/양식 링크/사본 동등성을 검사한다. 코드·월드·운영 JAR·서버 실행·LLM 호출은 변경하지 않으며, 파일에 정의된 목록을 실행 중 실제 등록 목록으로 단정하지 않는다. 참고 사본은 적용 대상이 아니며 작성 후 게임 참조/공개 범위/실행 기능을 검토해야 한다.
