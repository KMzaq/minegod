# 플레이어 말투·관계·권위 반응 규격

이 문서는 플레이어가 어떤 방식으로 말했는지에 따라 신 NPC의 **즉시 대사 반응**을 달리 만드는 AI 모듈 규격이다. 말투 분석은 대화 자문 데이터이며, 실제 관계 수치·계급·칭호·퀘스트 상태의 원본 데이터는 계속 MythicTRPG/RPG 시스템이 소유한다.

## 처리 흐름

```text
플레이어 발화
  ↓
1단계: S_* 요청 유형 + T_* 말투 태그 + 지식 검색어
  ↓
게임 Snapshot: R_* 관계 + E_* 감정 + 상대적 권위 + 청중
  ↓
NPC별 말투 반응 가이드
  ↓
2단계 LLM: NPC다운 실제 대사 생성
```

## 1. 플레이어 말투 태그

1단계 LLM은 아래 값 중 최대 3개만 반환한다. 명확한 인사·간단한 요청·분류 실패 시에는 서버 휴리스틱이 보수적으로 보완한다.

| 태그 | 의미 | 비고 |
|---|---|---|
| `T_POLITE` | 존댓말·정중한 요청 | 호의 또는 보상을 보장하지 않음 |
| `T_INFORMAL` | 일반적인 반말·친근한 말투 | 무례함과 다름 |
| `T_IMPOLITE` | 존중이 결여된 무례한 표현 | 한 번으로 관계 수치를 바꾸지 않음 |
| `T_AGGRESSIVE` | 공격적·도발적 표현 | 경계와 진정 유도 대상 |
| `T_MOCKING` | 조롱·비꼼 | 친한 장난과 구분하여 해석 |
| `T_THREATENING` | 위협·폭력적 협박 | 게임 공격이나 처벌이 실행됐다고 말하면 안 됨 |
| `T_APOLOGETIC` | 사과·낮은 태도 | 자동 용서가 아님 |

예시 출력:

```json
{
  "primarySituation": "S_INFORMATION_REQUEST",
  "secondarySituations": [],
  "knowledgeKeywords": ["티탄 전쟁"],
  "playerToneTags": ["T_INFORMAL"],
  "confidence": 0.86
}
```

## 2. 반말을 판단하는 기준

`T_INFORMAL` 자체는 벌점이 아니다. NPC별 반응은 다음 순서로 정해진다.

1. 위협·공격·명백한 모욕인지 확인한다.
2. 플레이어와 해당 NPC의 관계(`R_*`)를 확인한다.
3. 게임이 제공한 현재 상대적 권위(칭호·계약·만남의 역할 등)를 확인한다.
4. NPC의 격식성(`P_FORMAL`, `P_STRICT`, `P_IMPERIOUS`)과 성격을 반영한다.
5. 2단계 LLM이 위 조건을 NPC다운 실제 문장으로 만든다.

| 조건 | `T_INFORMAL` 기본 반응 |
|---|---|
| `R_CLOSE` | 수용. 반말만으로 예절 경고 금지 |
| `R_FRIENDLY` + 격식성이 낮음 | 대체로 수용 |
| 초면 + 격식적인 NPC | 가벼운 예절 경고 가능 |
| `PLAYER_SUPERIOR` | 수용. 반말만으로 예절 경고 금지 |
| 위협·공격·무례 태그 동시 존재 | 친밀도·권위와 별개로 경계 반응 가능 |

따라서 친한 플레이어의 “야, 이거 만들어줘”는 헤파이스토스 같은 NPC에게 퉁명스럽지만 자연스러운 친근함으로 처리할 수 있다. 반대로 초면의 격식적인 NPC에게 같은 발화는 관계 수치 하락 없이 우선 예절을 지적하는 대사가 나올 수 있다.

## 3. 상대적 권위 입력

AI 모듈은 플레이어가 신보다 높은지, 계약상 지휘권이 있는지, 왕인지 등을 스스로 추측하지 않는다. 다른 RPG 제작자가 아래 Provider를 설치해 **현재 턴의 Snapshot**으로 전달해야 한다.

```java
GodAiDialogueService.INSTANCE.installSocialAuthorityContextProvider(
    (session, triggeringParticipantId) -> Map.of(
        "divine:mythictrpg:greek_olympian_hephaestus",
        new NpcSocialAuthorityContext(
            RelativeAuthority.PLAYER_SUPERIOR,
            List.of("encounter commander")
        )
    )
);
```

값은 NPC `participantId`별로 제공한다.

| 값 | 의미 |
|---|---|
| `PLAYER_SUPERIOR` | 현재 장면에서 플레이어의 지위·권한이 더 높음 |
| `EQUAL` | 동등한 역할·협상 관계 |
| `NPC_SUPERIOR` | NPC 쪽 권위가 우세함 |
| `UNKNOWN` | 게임이 아직 판정하지 않음. AI는 우위를 추측하지 않음 |

현재 기존 MythicTRPG 데이터에는 이 권위 원본이 없으므로, Provider를 설치하기 전에는 모든 NPC가 `UNKNOWN`으로 처리된다. 관계 기반 반말 수용은 즉시 동작하지만, 왕·지휘관·계약자 같은 장면 권위는 RPG 시스템 담당자가 이 Provider에 연결한 뒤 활성화된다.

## 4. 관계 변화의 경계

말투 반응은 우선 한 턴의 **대사 분위기**만 바꾼다. AI가 관계 수치를 직접 저장하거나 "신뢰가 하락했다"고 확정해서는 안 된다.

명백한 모욕이나 반복된 위협에 대해 AI가 `relationship_change_proposal`을 제안할 수는 있지만, 실제 저장·수치 변경은 기존 게임 Validator가 현재 상태를 다시 검사한 뒤 결정해야 한다.
