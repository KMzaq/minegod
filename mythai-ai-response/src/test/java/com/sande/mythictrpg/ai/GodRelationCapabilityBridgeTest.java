package com.sande.mythictrpg.ai;

import com.sande.mythictrpg.ai.action.AiActionCapability;
import com.sande.mythictrpg.relation.GodRelationTag;
import com.sande.mythictrpg.relation.GodRelationTransition;
import com.sande.mythictrpg.relation.GodRelationTransitionChange;
import net.minecraft.resources.ResourceLocation;
import java.util.*;
import java.util.function.Function;

/** Actual normalization/filtering with immutable authored fixtures; no world/registry mutation or LLM. */
public final class GodRelationCapabilityBridgeTest {
    private static final ResourceLocation A = id("first");
    private static final ResourceLocation B = id("second");
    private static final ResourceLocation C = id("outside");
    private static final ResourceLocation TYPE = ResourceLocation.parse("mythictrpg:god_relation_transition");
    private static final ResourceLocation VALID = id("valid_transition");
    private static int checks;

    public static void main(String[] args) {
        exactTransitionAuthority();
        rejectArbitraryModelFields();
        roomParticipantProjection();
        invalidScopesFailClosed();
        existingCapabilityCompatibility();
        affinityIntegerBoundaries();
        System.out.println("GodRelationCapabilityBridgeTest: " + checks + " assertions passed");
    }

    private static void exactTransitionAuthority() {
        var transition = transition(VALID, A, true, B);
        var cap = capability(transition);
        var proposal = proposal("god_relation_transition", Map.of("transition_id", VALID.toString()), List.of());
        var result = normalize(proposal, List.of(cap), Map.of(VALID, transition));
        check(result != null && result.type().equals("god_relation_transition"), "registered authored transition normalizes");
        check(result.parameters().equals(Map.of("transition_id", VALID.toString())), "only canonical transition ID passes through");
        check(result.targetParticipantIds().isEmpty(), "targets come only from authored game transition");
        check(result.title().equals(proposal.title()) && result.summary().equals(proposal.summary()), "non-authoritative offer narration preserved");
        check(normalize(proposal("mythictrpg:god_relation_transition", proposal.parameters(), List.of()), List.of(cap), Map.of(VALID, transition)) != null,
                "official namespaced action type allowed");
        check(normalize(proposal("outside:god_relation_transition", proposal.parameters(), List.of()), List.of(cap), Map.of(VALID, transition)) == null,
                "unrelated namespace cannot impersonate game action type");
        check(normalize(proposal, List.of(), Map.of(VALID, transition)) == null, "registry entry without offered capability rejected");
        check(normalize(proposal, List.of(cap), Map.of()) == null, "removed transition rejected after prior capability lookup");
        check(normalize(proposal, List.of(cap), Map.of(VALID, transition(VALID, A, false, B))) == null, "disabled transition rejected despite stale cap");
        check(normalize(proposal, List.of(cap), Map.of(VALID, transition(VALID, B, true, A))) == null, "acting God mismatch rejected despite stale cap");
        check(normalize(proposal, List.of(cap), Map.of(VALID, transition(id("different_id"), A, true, B))) == null,
                "malformed lookup cannot redirect ID");
        var foreignCap = new AiActionCapability(ResourceLocation.parse("outside:god_relation_transition"), Optional.of(VALID), "foreign");
        check(normalize(proposal, List.of(foreignCap), Map.of(VALID, transition)) == null, "foreign capability cannot authorize game action");
    }

    private static void rejectArbitraryModelFields() {
        var t = transition(VALID, A, true, B); var caps = List.of(capability(t)); var definitions = Map.of(VALID, t);
        for (var extra : List.of("score_delta", "add_tags", "remove_tags", "source_god", "target_god", "result", "template_id", "token")) {
            check(normalize(proposal("god_relation_transition", Map.of("transition_id", VALID.toString(), extra, "injected"), List.of()), caps, definitions) == null,
                    "extra parameter rejected: " + extra);
        }
        check(normalize(proposal("god_relation_transition", Map.of("template_id", VALID.toString()), List.of()), caps, definitions) == null,
                "template_id cannot substitute transition_id");
        check(normalize(proposal("god_relation_transition", Map.of(), List.of()), caps, definitions) == null, "missing ID rejected");
        for (var invalid : List.of("", "valid_transition", " " + VALID, VALID + " ", "bad id", id("unlisted").toString())) {
            check(normalize(proposal("god_relation_transition", Map.of("transition_id", invalid), List.of()), caps, definitions) == null,
                    "non-exact or absent ID rejected: " + invalid);
        }
        for (var target : List.of(A.toString(), B.toString(), UUID.randomUUID().toString(), "everybody")) {
            check(normalize(proposal("god_relation_transition", Map.of("transition_id", VALID.toString()), List.of(target)), caps, definitions) == null,
                    "model target rejected: " + target);
        }
    }

    private static void roomParticipantProjection() {
        var inside = transition(VALID, A, true, B);
        var outside = transition(id("outside_transition"), A, true, C);
        var mixed = new GodRelationTransition(id("mixed_transition"), A, true, 1, "MIXED_HIDDEN_SUMMARY",
                List.of(change(A, B), change(C, A)));
        var disabled = transition(id("disabled"), A, false, B);
        var wrongOwner = transition(id("other_owner"), B, true, A);
        var values = Map.of(inside.id(), inside, outside.id(), outside, mixed.id(), mixed, disabled.id(), disabled, wrongOwner.id(), wrongOwner);
        var caps = values.values().stream().map(GodRelationCapabilityBridgeTest::capability).toList();
        var safe = AiActionCapabilityBridge.roomCapabilities(caps, A, List.of(A, B), lookup(values));
        check(safe.size() == 1 && safe.getFirst().templateId().orElseThrow().equals(VALID), "only current-room owned enabled transition remains");
        var text = new StringBuilder(); AiActionCapabilityBridge.appendCapabilities(text, safe);
        check(text.toString().contains(VALID.toString()) && text.toString().contains("transition_id"), "filtered authored offer enters prompt with correct field");
        check(!text.toString().contains(outside.id().toString()) && !text.toString().contains("MIXED_HIDDEN_SUMMARY"),
                "outside target transitions and summaries omitted before prompting");
        check(text.toString().contains("empty targetParticipantIds"), "prompt requires no model targets");
        var outsideProposal = proposal("god_relation_transition", Map.of("transition_id", outside.id().toString()), List.of());
        check(normalize(outsideProposal, safe, values) == null, "normalization cannot use excluded outside-room transition");
        check(normalize(outsideProposal, caps, values) != null, "legacy unrestricted caller retains authored game authority");
        var expanded = AiActionCapabilityBridge.roomCapabilities(caps, A, List.of(A, B, C), lookup(values));
        check(expanded.size() == 3, "all transition endpoints must be present, including reverse directions");
        var reversed = new GodRelationTransition(id("reverse"), A, true, 1, "Reverse authored relation", List.of(change(B, A)));
        check(AiActionCapabilityBridge.roomCapabilities(List.of(capability(reversed)), A, List.of(A, B), lookup(Map.of(reversed.id(), reversed))).size() == 1,
                "acting God can be target when authored transition permits it");
        check(AiActionCapabilityBridge.roomCapabilities(caps, A, List.of(A), lookup(values)).isEmpty(), "one-God room cannot expose absent-target change");
        check(AiActionCapabilityBridge.roomCapabilities(caps, A, List.of(A, B), lookup(Map.of())).isEmpty(), "reloaded-away definitions omitted from prompt");
    }

    private static void invalidScopesFailClosed() {
        var t = transition(VALID, A, true, B); var caps = List.of(capability(t)); var definitions = lookup(Map.of(VALID, t));
        check(AiActionCapabilityBridge.roomCapabilities(caps, A, null, definitions).isEmpty(), "missing scope does not revert to legacy authority");
        check(AiActionCapabilityBridge.roomCapabilities(caps, A, List.of(), definitions).isEmpty(), "empty participant scope rejected");
        check(AiActionCapabilityBridge.roomCapabilities(caps, A, List.of(B, C), definitions).isEmpty(), "acting God must be present");
        check(AiActionCapabilityBridge.roomCapabilities(caps, A, List.of(A, B, B), definitions).isEmpty(), "duplicate participants rejected");
        check(AiActionCapabilityBridge.roomCapabilities(caps, A, Arrays.asList(A, B, null), definitions).isEmpty(), "null participant rejected");
        var many = new ArrayList<>(List.of(A, B));
        for (int i = 2; i < 16; i++) many.add(id("participant_" + i));
        check(AiActionCapabilityBridge.roomCapabilities(caps, A, many, definitions).size() == 1, "16 participants remain supported");
        many.add(id("participant_16"));
        check(AiActionCapabilityBridge.roomCapabilities(caps, A, many, definitions).isEmpty(), "over-limit participants rejected");
    }

    private static void existingCapabilityCompatibility() {
        var damage = new AiActionCapability(ResourceLocation.parse("mythictrpg:player_damage"), Optional.of(id("authored_damage")), "EXISTING_DAMAGE_SUMMARY");
        var affinity = new AiActionCapability(ResourceLocation.parse("mythictrpg:relationship_change"), Optional.empty(), "EXISTING_AFFINITY_SUMMARY");
        var safe = AiActionCapabilityBridge.roomCapabilities(List.of(damage, affinity), A, List.of(A, B), lookup(Map.of()));
        check(safe.equals(List.of(damage, affinity)), "existing game capabilities preserved by room filter");
        var prompt = new StringBuilder(); AiActionCapabilityBridge.appendCapabilities(prompt, safe);
        check(prompt.toString().contains("EXISTING_DAMAGE_SUMMARY") && prompt.toString().contains("EXISTING_AFFINITY_SUMMARY"),
                "existing capability descriptions preserved");
        check(!prompt.toString().contains("god_relation_transition accepts"), "unavailable new type is not advertised");
        var p = proposal("player_damage", Map.of("template_id", id("authored_damage").toString()), List.of());
        var normalized = normalize(p, safe, Map.of());
        check(normalized != null && normalized.parameters().equals(p.parameters()), "existing template normalization preserved");
    }

    private static void affinityIntegerBoundaries() {
        var cap = new AiActionCapability(ResourceLocation.parse("mythictrpg:relationship_change"), Optional.empty(), "existing");
        for (int value : new int[]{Integer.MIN_VALUE, Integer.MAX_VALUE, -51, 51, 0}) {
            var p = proposal("relationship_change", Map.of("affinity_delta", Integer.toString(value)), List.of());
            check(normalize(p, List.of(cap), Map.of()) == null, "out-of-range affinity rejected without abs overflow: " + value);
        }
        for (int value : new int[]{-50, -1, 1, 50}) {
            var p = proposal("relationship_change_proposal", Map.of("affinity_delta", Integer.toString(value)), List.of());
            var normalized = normalize(p, List.of(cap), Map.of());
            check(normalized != null && normalized.type().equals("relationship_change") && normalized.parameters().equals(p.parameters()),
                    "existing alias and legal boundary preserved: " + value);
        }
    }

    private static AiDialogueModels.Proposal normalize(AiDialogueModels.Proposal proposal, List<AiActionCapability> caps,
            Map<ResourceLocation, GodRelationTransition> transitions) {
        return AiActionCapabilityBridge.normalizeAuthorized(proposal, A, "player input", caps, lookup(transitions));
    }
    private static Function<ResourceLocation, Optional<GodRelationTransition>> lookup(Map<ResourceLocation, GodRelationTransition> transitions) {
        return id -> Optional.ofNullable(transitions.get(id));
    }
    private static AiDialogueModels.Proposal proposal(String type, Map<String,String> parameters, List<String> targets) {
        return new AiDialogueModels.Proposal(type, "Offer title", "Proposed authored transition", targets, parameters);
    }
    private static AiActionCapability capability(GodRelationTransition transition) {
        return new AiActionCapability(TYPE, Optional.of(transition.id()), "transition_id=" + transition.id() + "; " + transition.summary());
    }
    private static GodRelationTransition transition(ResourceLocation id, ResourceLocation actor, boolean enabled, ResourceLocation other) {
        return new GodRelationTransition(id, actor, enabled, 1, "Authored " + id, List.of(change(actor, other)));
    }
    private static GodRelationTransitionChange change(ResourceLocation source, ResourceLocation target) {
        return new GodRelationTransitionChange(source, target, -10, Set.of(GodRelationTag.WATCHFUL), Set.of());
    }
    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath("capability_test", path); }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
