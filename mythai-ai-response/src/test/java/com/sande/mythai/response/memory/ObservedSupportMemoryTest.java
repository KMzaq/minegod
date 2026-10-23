package com.sande.mythai.response.memory;

import com.sande.mythictrpg.ai.experiencecontract.ExperienceView;
import java.util.*;

public final class ObservedSupportMemoryTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static ExperienceView.Event crop(UUID id) {
        return new ExperienceView.Event(UUID.randomUUID(), id, 1, "DIRECT_WATCH", "MATURE_CROP_REMOVED",
                "minecraft:wheat", "BLOCK_REMOVED_NOT_ITEM_ACQUISITION", "42");
    }
    private static ExperienceView view(List<ExperienceView.Event> events) {
        return new ExperienceView(1, true, "READY", events, ExperienceView.Relationship.UNKNOWN);
    }
    public static void main(String[] args) {
        check(ObservedSupportMemory.select(ExperienceView.unavailable("OFF")).events().isEmpty(), "unavailable has no hypothesis");
        check(ObservedSupportMemory.prompt(ObservedSupportMemory.select(view(List.of()))).isEmpty(), "absence is not a negative tendency");
        var one = crop(UUID.randomUUID());
        String single = ObservedSupportMemory.prompt(ObservedSupportMemory.select(view(List.of(one))));
        check(single.contains("MATURE_CROP_REMOVED") && !single.contains("hypotheses"), "one act stays a fact");
        check(single.contains("literalMeaning") && single.contains("돌봄·아이템 획득은 미확인"), "explicit action extent");
        check(!single.contains("\"category\""), "broad activity category is not a dialogue trait");
        check(ObservedSupportMemory.boundary(ExperienceMemory.Selection.EMPTY).isEmpty(), "no boundary without selected facts");
        var boundary=ObservedSupportMemory.boundary(ObservedSupportMemory.select(view(List.of(one))));
        check(boundary.get("confirmed").toString().contains("밀(minecraft:wheat)"), "known target label does not change resource identity");
        check(boundary.get("unknown").toString().contains("정성"), "inner state is not an observed fact");
        var repeated = ObservedSupportMemory.select(view(List.of(one, crop(one.eventId()))));
        check(repeated.events().size() == 1, "same source is not accumulated twice");
        String multiple = ObservedSupportMemory.prompt(ObservedSupportMemory.select(view(List.of(one, crop(UUID.randomUUID())))));
        check(!multiple.contains("POSSIBLE_INTEREST_ONLY") && !multiple.contains("hypotheses"), "repetition does not inject a preference label");
        check(ObservedSupportMemory.prompt(repeated, com.sande.mythictrpg.ai.intent.SupportConsultation.Use.NONE).isEmpty(), "ordinary conversation has no support context");
        for (var use : com.sande.mythictrpg.ai.intent.SupportConsultation.Use.values()) {
            boolean recall = use == com.sande.mythictrpg.ai.intent.SupportConsultation.Use.OBSERVED_ACTION_RECALL;
            check(ObservedSupportMemory.requested(use) == recall, "only explicit observation recall loads samples: " + use);
            check(ObservedSupportMemory.prompt(repeated, use).isEmpty() != recall, "personalized help never substitutes a small witness sample: " + use);
        }
        check(multiple.contains("another person's plan remains theirs"), "actor attribution remains explicit");
        check(multiple.contains("Current explicit correction outranks"), "correction outranks hypothesis");
        check(multiple.contains("No reward, penalty, affinity"), "support never executes gameplay");
        var many = new ArrayList<ExperienceView.Event>();
        for (int i = 0; i < 16; i++) many.add(crop(UUID.randomUUID()));
        var bounded = ObservedSupportMemory.select(view(many));
        check(bounded.events().size() == 6 && bounded.ids().size() == 6, "bounded text and proof use same selection");
        check(ObservedSupportMemory.prompt(bounded).length() < 4000, "interactive prompt is bounded");
        for (String outcome : List.of("VICTORY", "DEFEAT", "WITHDRAWN", "ABORTED")) {
            var battle = new ExperienceView.Event(UUID.randomUUID(), UUID.randomUUID(), 1, "DIRECT_WATCH", "BATTLE_RESULT",
                    "test:boss", outcome, "42");
            check(ObservedSupportMemory.select(view(List.of(battle))).events().isEmpty(), "outcome is not play style: " + outcome);
        }
        System.out.println("ObservedSupportMemoryTest: " + checks + " checks passed");
    }
}
