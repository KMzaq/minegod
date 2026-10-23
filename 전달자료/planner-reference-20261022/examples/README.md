# JSON 예시 읽는 법

이 폴더는 설치용 데이터팩이 아니다. 정식 인물·등급·외형·능력 수치를 자동 등록하지 않는다. 전체 설명과 필수 연결은 [장비·스킬·장신구 작성 규격](../02-equipment-skills-accessories.md)과 [인물·퀘스트·Story 연결](../03-god-quest-story.md)을 따른다.

| 파일 | 종류와 적용 위치 |
|---|---|
| [equipment-armor.item-rule.json](equipment-armor.item-rule.json), [equipment-weapon.item-rule.json](equipment-weapon.item-rule.json) | **전투력 정책 `items`의 한 행**. 각각을 독립 정책 파일로 설치하지 않는다 |
| [equipment-rewards.json](equipment-rewards.json) | 위 두 등록 변형을 실제로 지급할 `RewardEntry` 배열 |
| [skills-weapon-spark.json](skills-weapon-spark.json) | `data/example/mythictrpg/weapon_skills/weapon_spark.json`. 무기 행의 `rightClickSkill`과 `skills`로 연결 |
| [skills-learned-heal.json](skills-learned-heal.json) | `data/example/mythictrpg/learned_skills/learned_heal.json` |
| [skills-learned-reward.json](skills-learned-reward.json) | 위 습득 스킬을 주는 **보상 항목 한 개**. 제공 신의 하급 이상 등급 필요 |
| [accessory-mana-charm.json](accessory-mana-charm.json) | `data/example/mythictrpg/accessories/mana_charm.json` |
| [accessory-reward.json](accessory-reward.json) | 위 장신구를 주는 **보상 항목 한 개**. 제공 신의 상급 이상 등급 필요 |
| [story-harvest-reward.json](story-harvest-reward.json) | `data/example/mythictrpg/story_events/harvest_reward.json`. 새 `GRANT_REWARDS` 행동의 전체 사건 예시. 실제 데메테르 지급 정책을 확정한 것이 아님 |

02 문서에 있는 JSON 8개를 그대로 별도 파일로 꺼냈다. 파일 이름 앞의 `skills-` 같은 분류 접두어를 실제 데이터팩의 ID에 붙이지 않는다. `example:*`는 위 경로 설명에 맞춘 예시 ID이며 정식 ID·수치·God ID·등급을 결정한 뒤 참조를 함께 맞춘다. 보상 항목 조각을 정의 파일 전체로 넣거나, 전투력 등록 행을 보상 항목으로 넣지 않는다.
