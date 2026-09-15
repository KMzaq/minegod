# 그리스 신격 AI 콘텐츠 적용 보고서

적용 원본: C:\Users\ADMIN\Downloads\greek_gods_ai_content_draft.zip

## 적용 결과

| 항목 | 적용 수 |
|---|---:|
| AI persona | 135개 추가, 총 138개 |
| NPC agent | 135개 추가, 총 141개 |
| NPC character tag profile | 135개 추가, 총 141개 |
| Knowledge entry | 277개 추가 |
| Dialogue example | 675개 추가, 총 684개 |

기존 루브라스와 개발용 fixture 데이터는 보존했다.

## ID 해석 원칙

원본 ZIP은 실제 Datapack God ID가 없어서 draft_greek:slug 임시 ID를 사용했다. 최종적으로 아래처럼 소속신화_소속_신이름 규칙을 사용한다.

~~~text
draft_greek:<name>
  → mythictrpg:greek_<affiliation>_<name>
~~~

현재 그리스 데이터의 affiliation은 원본 분류에 따라 primordial, titan, olympian, underworld, sea_nature 중 하나다.

예:

~~~text
draft_greek:chaos      → mythictrpg:greek_primordial_chaos
draft_greek:cronus     → mythictrpg:greek_titan_cronus
draft_greek:zeus       → mythictrpg:greek_olympian_zeus
draft_greek:hades      → mythictrpg:greek_underworld_hades
draft_greek:pan        → mythictrpg:greek_sea_nature_pan
~~~

최종 ID 목록과 현재 테스트 가능 여부는 resolved-god-id-map.json에 기록했다.

## 지금 바로 테스트 가능한 신

135개 모두 같은 ID의 최소 God Definition을 개발 월드 전용 데이터팩으로 제공해 자동 해금 상태로 만들었다. 따라서 개발 월드에서 135개 모두 대화를 시작할 수 있다.

~~~text
mythictrpg:greek_primordial_chaos
mythictrpg:greek_titan_cronus
mythictrpg:greek_olympian_zeus
mythictrpg:greek_underworld_hades
mythictrpg:greek_sea_nature_pan
~~~

테스트 정의는 모드 JAR에 포함하지 않았다. 기본 MythicTRPG 데이터셋과 자동 GameTest가 기존 신 정의 3개 전제를 유지하도록 하기 위해서다. 개발 월드에는 다음 데이터팩이 이미 설치되어 있다.

~~~text
run/world/datapacks/mythictrpg-greek-ai-test/
~~~

게임에서 아래 순서로 확인한다.

~~~text
/reload
/datapack list enabled
/mythai reload
/mythai start mythictrpg:greek_olympian_zeus
대화 종료
/mythai start mythictrpg:greek_titan_prometheus
~~~

`file/mythictrpg-greek-ai-test`가 비활성 상태라면 다음을 한 번 실행한 뒤 `/reload`한다.

~~~text
/datapack enable "file/mythictrpg-greek-ai-test"
~~~

생성한 God Definition은 테스트용 최소 정의다. 표시 이름, 그리스 origin, 분류 category, 자동 해금만 포함하고 퀘스트·보상·관계·등장 연출을 구현하지 않는다. 추후 MythicTRPG 담당자가 같은 ID의 완성된 Definition을 제공하면 이 테스트 정의를 교체하면 된다.

기존 mythictrpg:aphrodite와 mythictrpg:demeter Definition은 호환성을 위해 보존했다. 본 테스트의 표준 ID는 위의 greek_ 접두어 ID다.

## 시스템 적합성 보완

생성 패키지에는 NPC Agent가 지원하는 18종 말투 태그가 대화 예시에도 사용되었다. 기존 예시 대화 파서는 그중 일부를 거절하므로, 아래 8개 태그를 예시 대화 파서에 추가 허용했다.

~~~text
P_STRICT
P_COLD
P_IMPERIOUS
P_AGGRESSIVE
P_CUNNING
P_CALM
P_WISE
P_HONORABLE
~~~

이는 새 게임 기능이나 권한을 추가하지 않으며, 이미 NPC Agent가 지원하던 말투 태그가 예시 검색에서도 정상적으로 작동하게 하는 호환성 수정이다.

또한 ai-personas.json을 JAR의 기본 리소스로 포함하도록 보완했다. 따라서 새 서버에서는 첫 실행 시 AI persona 설정도 다른 AI 데이터처럼 config/mythictrpg에 자동 생성된다.

## 병합 위치

다음 두 위치에 같은 그리스 AI 콘텐츠를 병합했다.

~~~text
src/main/resources/data/mythictrpg/ai/
run/config/mythictrpg/
dev-content/mythictrpg-greek-ai-test/
run/world/datapacks/mythictrpg-greek-ai-test/
~~~

첫 번째는 배포 JAR에 포함되는 기본 AI 설정이며, 두 번째는 현재 개발 실행 환경 설정이다. 세 번째는 배포 JAR와 분리된 테스트 데이터팩 원본이고, 네 번째는 현재 개발 월드에 적용된 복사본이다.

## 백업

각 ID 이관 전 실행 설정·번들 AI JSON과 방금 교체한 테스트 Definition은 아래 폴더에 복사했다.

~~~text
backups/before_greek_import_20260818_012348/
backups/before_greek_systematic_id_20260818_013158/
backups/before_greek_affiliation_id_20260818_013417/
~~~

기존 God Definition 세 개, Quest/Reward 시스템, 플레이어 관계 원본, 월드 데이터는 수정하지 않았다.
