package com.sande.mythictrpg.ai.action;

import java.util.ArrayList;
import java.util.List;

/** Server-authored mechanics, deliberately independent of the model's title and summary. */
final class AiActionConfirmationTerms {
    private AiActionConfirmationTerms() { }

    static Terms capture(AiActionProposal proposal) {
        AiActionTemplate template = AiActionParameters.template(proposal, AiActionTemplate.class);
        if (proposal.parameters().containsKey("template_id") && template == null) {
            throw new IllegalArgumentException("Confirmation template is no longer available");
        }
        List<String> lines = new ArrayList<>();
        if (template instanceof ItemRequestTemplate item) {
            lines.add("아이템 소비: " + item.itemId() + " × " + item.count());
            lines.add("본인 인벤토리와 확인 시 바라보는 근처 상자에서 소비합니다.");
            lines.add("아이템 전달만 수행하며, 보상·호감도 상승을 자동으로 약속하지 않습니다.");
        } else if (template instanceof BlessingOfferTemplate blessing) {
            lines.add("임시 효과: " + blessing.effectId() + " / 단계 " + (blessing.amplifier() + 1));
            lines.add("지속 시간: " + blessing.durationTicks() + "틱 (정상 속도에서 "
                    + (blessing.durationTicks() / 20.0) + "초)");
            lines.add("서버가 현재 효과와 다시 비교합니다. 적용이 거절되면 성공으로 처리하지 않습니다.");
        } else if (template instanceof WorldInteractionTemplate event) {
            lines.add("주변 연출: " + event.eventKind() + " / " + event.eventId());
            lines.add("소리·입자 연출이며 아이템 지급이나 지형 변경은 아닙니다.");
        } else if (proposal.actionType().equals(AiActionTypes.RAID_OFFER)) {
            lines.add("레이드 모집 생성: " + proposal.parameters().getOrDefault("raid_id", ""));
            lines.add("모집만 생성합니다. 전투 시작·이동·보상은 실행하지 않습니다.");
            lines.add("참가자들이 직접 합류한 뒤 리더가 시작해야 합니다.");
        } else {
            lines.add("실행 요청: " + proposal.actionType());
            lines.add("서버가 등록된 설정과 현재 조건을 다시 검증합니다.");
        }
        return new Terms(lines, template);
    }

    record Terms(List<String> lines, AiActionTemplate definitionSnapshot) {
        Terms { lines = List.copyOf(lines); }

        boolean matches(AiActionProposal proposal) {
            return definitionSnapshot == null
                    ? !proposal.parameters().containsKey("template_id")
                    : definitionSnapshot.equals(AiActionParameters.template(proposal, AiActionTemplate.class));
        }
    }
}
