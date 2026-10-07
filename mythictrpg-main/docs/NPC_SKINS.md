# 신 NPC에 플레이어 스킨 추가하기

2026-09-30, HanesTest 개발본. 운영 서버에는 아직 배포하지 않았다. 신 NPC의 **외형만** Minecraft 플레이어 모델로 표시한다. 실제 엔티티는 기존 `mythictrpg:god_avatar`이며 플레이어 계정이나 가짜 플레이어로 바꾸지 않는다. 신 ID·관계·퀘스트·이동·전투·정체 식별은 그대로다. YSM은 필요하지 않다.

## 1. 신별 외형 설정

AI 페르소나 JSON이 아니라 서버 데이터팩의 **아바타 정의**를 편집한다.

```text
data/<신 ID의 namespace>/mythictrpg/god_avatars/<신 ID의 path>.json
```

예를 들어 기존 게임 ID가 `mythictrpg:demeter`라면 `data/mythictrpg/mythictrpg/god_avatars/demeter.json`이다. 이 예시를 따라 다른 신 ID를 새로 만들거나 기존 ID를 바꾸지 않는다.

기존 정의의 `appearance` 부분을 다음처럼 설정한다.

```json
"appearance": {
  "textureVariant": 1,
  "model": "slim",
  "scale": 1.0
}
```

- `textureVariant`: 사용할 이미지 번호. `1..255` 중 선택한다. 서로 다른 스킨은 다른 번호, 같은 스킨을 공유하려면 같은 번호를 사용한다. `0`은 기본 스킨이다.
- `model`: `classic`은 Steve형 4픽셀 팔, `slim`은 Alex형 3픽셀 팔. 생략하면 `classic`이며 대소문자와 철자가 정확해야 한다. PNG만 보고 자동 판별하지 않는다.
- `scale`: `0.5..2`, `1.0`이 기본 크기. 기존처럼 충돌 크기도 함께 바뀐다.

위 조각만으로는 완전한 아바타 정의가 아니다. 아직 아바타 파일이 없는 신은 [NPC_ACTORS.md](NPC_ACTORS.md)의 필수 stats/movement/combat/placement/interactionRange도 작성해야 한다. 게임 God Definition이 있어야 하며, 외형을 설정했다고 자동 출현하는 것은 아니다. 능력치나 출현 정책은 제작자가 결정한다.

## 2. PNG를 리소스팩에 넣기

**일반 Minecraft Java 플레이어용 64×64 PNG**를 준비한다. 피부/머리뿐 아니라 모자·재킷·양쪽 소매·바지 바깥 레이어와 좌우 독립 스킨을 표시한다. `slim` 스킨은 slim 체형으로 지정한다. 기존 64×32 구형 스킨, YSM 모델 파일, 일반 캐릭터 일러스트는 자동 변환하지 않는다. 스킨은 계정 이름/URL로 내려받지 않고 설치된 리소스팩에서 읽는다.

리소스팩 구조:

```text
mythai-god-skins/
  pack.mcmeta
  assets/
    mythictrpg/
      textures/
        entity/
          god_avatar/
            variant_1.png
            variant_2.png
```

파일명은 `textureVariant` 번호와 같아야 한다. `1`은 `variant_1.png`이며 `variant_01.png`가 아니다. 서버가 클라이언트에 신의 숨겨진 ID를 보내지 않도록 이미지 이름도 외형 번호를 사용한다. 다만 플레이어가 외형을 보고 신을 추측하는 것까지 막지는 않는다.

Minecraft 1.21.1용 `pack.mcmeta`:

```json
{
  "pack": {
    "pack_format": 34,
    "description": "MythAI God NPC skins"
  }
}
```

리소스팩 폴더 또는 ZIP을 **각 클라이언트의 게임 디렉터리 `resourcepacks/`**에 넣고 활성화한다. ZIP의 최상단은 `pack.mcmeta`와 `assets`여야 한다. 서버 배포용 리소스팩에 포함해 클라이언트에게 전달하는 방식도 가능하지만 이 작업에서는 배포/호스팅을 설정하지 않았다. **서버에 PNG만 복사해서는 접속자에게 전달되지 않는다.**

## 3. 적용과 확인

1. 이번 코드를 포함한 같은 게임 JAR을 서버와 클라이언트 모두 사용한다. 스킨 도입 시 protocol은 `9`였고 생활활동 자세/소품이 추가된 1.0.23은 `10`이다. 구버전과 혼용하지 않는다.
2. 서버 아바타 JSON 변경은 `/reload`한다. 기존 실체도 정의가 다시 로드되면 최대 약 1초(정상 20 TPS 기준) 안에 외형 선택이 갱신된다.
3. 클라이언트 PNG 변경은 리소스팩을 다시 로드한다(`F3+T`). 스킨 존재 여부 캐시도 함께 초기화된다. JSON과 PNG만 바꿀 때는 JAR을 다시 빌드하지 않아도 된다.
4. 실체가 없는 경우 OP의 `/mythavatar summon <정확한 신 ID>`로 시험한다. 아바타 정의, 충돌 없는 위치, God당 한 실체 원칙을 통과해야 한다. 일반 대화만 여는 명령과는 다르다.

스킨 번호가 `0`이거나 이미지가 없으면 classic은 기본 Steve, slim은 기본 Alex로 표시한다. 이미지가 존재하더라도 잘못된 PNG/UV 규격이면 정상 표시를 보장하지 않는다. 팔 모양, 바깥 레이어, 두 신의 서로 다른 외형, 리소스팩 재로드를 실제 클라이언트에서 확인한다. 별도 이름표는 표시하지 않으며 기존 플레이어별 신 이름 공개 UI를 유지한다.

## 이번 변경의 경계

추가한 것은 플레이어형 렌더링과 신별 외형 선택 기능이다. 신별 스킨 이미지 제작/지정, 활성 아바타 콘텐츠 작성, 서버 배포와 실제 화면 확인은 별도다. 망토·플레이어 계정 스킨 다운로드·YSM 전용 애니메이션은 포함하지 않는다.
