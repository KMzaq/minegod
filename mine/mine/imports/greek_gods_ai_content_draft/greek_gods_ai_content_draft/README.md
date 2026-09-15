# 그리스 신격 AI 콘텐츠 초안 패키지

## 생성 범위
- 원본: `그리스_신화_주요_신격_영웅_등장인물_태그_DB .md`
- 대상: 분류 1~5 (원초신·우주적 존재 / 티탄·고대 신족 / 올림포스·천상 주요 신격 / 명계·죽음·운명·마법 신격 / 바다·자연·목가 신격)
- 총 대상: **135개**
- 영웅·왕·괴물 등 분류 6~9는 이번 패키지에서 제외

## 중요한 ID 주의
`AI_CHARACTER_CONTENT_AUTHORING_GUIDE`는 God ID를 콘텐츠 작성자가 임의로 만들지 말고 기존 Datapack의 `namespace:path`를 사용하도록 요구한다.
현재 실제 Datapack God ID 목록이 제공되지 않았기 때문에, 이 패키지는 모든 캐릭터에 `draft_greek:<slug>` 임시 식별자를 사용한다.

1. `god-id-map.json`의 각 `finalGodId`에 실제 ID를 입력한다.
2. `python apply_god_id_map.py`를 실행한다.
3. `merge-ready/`에 실제 ID로 치환된 5개 JSON 파일이 생성된다.
4. 그 후 기존 `config/mythictrpg` JSON에 병합하고 `/mythai reload`로 확인한다.

## 포함 파일
- `ai-personas.greek.draft.json`: 135 personas
- `npc-agents.greek.draft.json`: 135 agents
- `npc-character-tags.greek.draft.json`: 135 tag records
- `ai-knowledge.greek.draft.json`: 277 knowledge entries
- `dialogue-examples.greek.draft.json`: 675 dialogue examples (신격당 5개)
- `god-id-map.json`: 실제 God ID 입력용 매핑
- `apply_god_id_map.py`: ID 치환 및 merge-ready 파일 생성 도구
- `STYLE_AND_SOURCE_NOTES.md`: 말투/출처/작성 원칙
- `generation-report.json`: 자동 검증 결과

## 세계관 반영 범위
세계관 배경 스토리에서 확정된 다음 공통 설정만 반영했다.
- 업은 선악 점수가 아니라 존재가 남긴 흔적의 총량이다.
- 충분한 업과 이야기는 신격으로 이어질 수 있다.
- 각 신화는 창구를 통해 세계에 연결된다.
- 신격자는 육체 파괴만으로 죽지 않으며 봉인이 핵심 제약 수단이다.
- 같은 신화에 속해도 반드시 같은 세력인 것은 아니다.
- 올림푸스는 신들의 연합 중심 축 가운데 하나였으나 모든 그리스 신격이 제우스 측은 아니었다.

타 신화권 **개별 신과의 친분·적대·개인적 과거**는 사용자의 지시에 따라 이번 패키지에서 의도적으로 작성하지 않았다.

## 콘텐츠 초안 주의
원본 태그 DB가 제공하지 않는 말투·personality 수치·likes/dislikes는 게임 대화를 위한 **콘텐츠 설계 초안**이다. 신화학적 확정 사실로 간주하지 않는다.
