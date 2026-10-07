package com.sande.mythictrpg.story.runtime;

import com.sande.mythictrpg.network.StoryChoiceBrowsePayload;
import com.sande.mythictrpg.network.StoryChoicePagePayload;
import com.sande.mythictrpg.network.StoryChoiceSelectionPayload;
import com.sande.mythictrpg.story.definition.StoryDefinitions.ChoicePolicy;
import com.sande.mythictrpg.story.definition.StoryDefinitions.Effect;
import com.sande.mythictrpg.story.definition.StoryDefinitions.OutcomeDefinition;
import com.sande.mythictrpg.story.definition.StoryDefinitions.ScopeSelector;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Optional;

/** Offline shape/compatibility checks; GameTest covers the authoritative world path. */
public final class StoryChoiceContractTest {
    private static int checks;

    private StoryChoiceContractTest() {}

    public static void main(String[] args) {
        ResourceLocation event = id("test_event");
        ResourceLocation yes = id("yes");
        var option = new StoryChoicePagePayload.Option(yes, yes.toString(), "");
        var offer = new StoryChoicePagePayload.Offer("test-instance", 2, event,
                event.toString(), "", List.of(option));
        var page = new StoryChoicePagePayload(0, 1, true, Optional.of(offer));
        check(page.offer().orElseThrow().revision() == 2, "revision is carried to UI");
        check(page.offer().orElseThrow().options().getFirst().id().equals(yes), "option ID is preserved");
        check(new StoryChoicePagePayload(0, 0, false, Optional.empty()).offer().isEmpty(),
                "empty pending view has no offer");
        reject(() -> new StoryChoicePagePayload(1, 0, false, Optional.empty()), "empty page index");
        reject(() -> new StoryChoicePagePayload(0, 1, false, Optional.empty()), "missing offer");
        reject(() -> new StoryChoicePagePayload.Offer("test-instance", 1, event, "event", "",
                List.of(option, option)), "duplicate option ID");
        reject(() -> new StoryChoicePagePayload.Option(yes, "", ""), "blank option label");
        reject(() -> new StoryChoicePagePayload.Option(yes, "valid", "x".repeat(601)),
                "oversized option description");
        reject(() -> new StoryChoiceBrowsePayload(-1), "negative browse page");
        reject(() -> new StoryChoiceSelectionPayload("", 1, yes), "blank instance");
        reject(() -> new StoryChoiceSelectionPayload("instance", -1, yes), "negative revision");

        var legacyPolicy = new ChoicePolicy(-1, Optional.empty());
        check(legacyPolicy.displayName().isEmpty() && legacyPolicy.description().isEmpty(),
                "old choice policy constructor remains valid");
        var authoredPolicy = new ChoicePolicy(20, Optional.of(yes), Optional.of("Choose"),
                Optional.of("Description"));
        check(authoredPolicy.displayName().orElseThrow().equals("Choose"), "authored policy label");
        reject(() -> new ChoicePolicy(-1, Optional.empty(), Optional.of("x".repeat(121)),
                Optional.empty()), "oversized choice label");

        var effect = new Effect.SetFact(id("set_fact"), id("fact"), ScopeSelector.EVENT_SCOPE, true);
        var legacyOutcome = new OutcomeDefinition(yes, 1, Optional.empty(), List.of(effect));
        check(legacyOutcome.displayName().isEmpty() && legacyOutcome.description().isEmpty(),
                "old outcome constructor remains valid");
        var authoredOutcome = new OutcomeDefinition(yes, 1, Optional.empty(), List.of(effect),
                Optional.of("Yes"), Optional.of("Description"));
        check(authoredOutcome.displayName().orElseThrow().equals("Yes"), "authored option label");
        reject(() -> new OutcomeDefinition(yes, 1, Optional.empty(), List.of(effect),
                Optional.empty(), Optional.of("x".repeat(601))), "oversized outcome description");

        System.out.println("StoryChoiceContractTest: PASS (" + checks + " checks; no server or model)");
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("mythictrpg", path);
    }

    private static void reject(Runnable action, String description) {
        try {
            action.run();
            throw new AssertionError("Accepted " + description);
        } catch (IllegalArgumentException expected) {
            checks++;
        }
    }

    private static void check(boolean condition, String description) {
        checks++;
        if (!condition) throw new AssertionError(description);
    }
}
