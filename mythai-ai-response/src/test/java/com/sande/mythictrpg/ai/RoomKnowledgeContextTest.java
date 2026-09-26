package com.sande.mythictrpg.ai;

import com.sande.mythictrpg.ai.api.RoomEvidenceReference;
import net.minecraft.resources.ResourceLocation;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Portable evidence and profile-preservation tests; policy evaluation itself is tested in the content registry. */
public final class RoomKnowledgeContextTest {
    private static int checks;
    public static void main(String[] args) {
        var god = ResourceLocation.parse("test:source");
        var listener = ResourceLocation.parse("test:listener");
        var player = UUID.randomUUID();
        var players = Set.of(player);
        var original = snapshot("authorized biography", 1, Map.of("S_FEAR", List.of("fearful"), "S_MISC", List.of("reserved")));
        var proof = RoomKnowledgeContext.evidence(god, "R_WARY", original, List.of(god, listener));
        check(proof.kind().equals(RoomKnowledgeContext.EVIDENCE_KIND), "known portable kind");
        check(!proof.payload().contains("authorized biography") && !proof.payload().contains("ordinary secret"), "proof has no secret prose");
        var received = new AtomicBoolean();
        check(RoomKnowledgeContext.validEvidence(proof, false, List.of(listener), players, (source, tier, publicly, gods, audience, references) -> {
            received.set(source.equals(god) && tier.equals("R_WARY") && !publicly && gods.equals(List.of(listener)) && audience.equals(players)
                    && references.equals(List.of(god, listener)));
            return original;
        }), "another god may recall author-proven content");
        check(received.get(), "original owner and whole current audience passed to authoritative projection");
        var restarted = snapshot("authorized biography", 928, Map.of("S_MISC", List.of("reserved"), "S_FEAR", List.of("fearful")));
        check(RoomKnowledgeContext.fingerprint(original).equals(RoomKnowledgeContext.fingerprint(restarted)), "reload counter and map order do not invalidate persisted evidence");
        check(RoomKnowledgeContext.validEvidence(proof, false, List.of(god), players, (a,b,c,d,e,f) -> restarted), "same current content survives restart generation reset");
        check(!RoomKnowledgeContext.validEvidence(proof, true, List.of(god), players,
                (a,b,c,d,e,f) -> snapshot("", 1, original.profile().situationGuidelines())), "public projection masking secret revokes replay");
        check(!RoomKnowledgeContext.validEvidence(proof, false, List.of(god), players,
                (a,b,c,d,e,f) -> snapshot("changed canonical biography", 1, original.profile().situationGuidelines())), "canonical text change revokes replay even equal generation");
        check(!RoomKnowledgeContext.validEvidence(proof, false, List.of(god), players,
                (a,b,c,d,e,f) -> { throw new IllegalStateException("profile removed"); }), "missing registry or author fails closed");
        check(!RoomKnowledgeContext.validEvidence(new RoomEvidenceReference("UNKNOWN", proof.payload()), false, List.of(god), players,
                (a,b,c,d,e,f) -> original), "unknown proof kind cannot be repurposed");
        for (String payload : List.of("{}", "[]", "null", "{\"speakerGodId\":\"test:source\",\"relationshipTier\":\"R_WARY\",\"sha256\":\"bad\"}",
                proof.payload().replace("\"sha256\"", "\"ignoredFingerprint\""), proof.payload().replace("test:source", "BAD ID"))) {
            check(!RoomKnowledgeContext.validEvidence(new RoomEvidenceReference(RoomKnowledgeContext.EVIDENCE_KIND, payload),
                    false, List.of(god), players, (a,b,c,d,e,f) -> original), "malformed proof denied: " + payload);
        }
        var masked = snapshot("", 1, original.profile().situationGuidelines());
        check(masked.profile().personality().equals(original.profile().personality()), "biography masking does not rewrite temperament");
        check(masked.profile().speechStyles().equals(original.profile().speechStyles()), "biography masking does not rewrite voice");
        check(!RoomKnowledgeContext.fingerprint(masked).equals(RoomKnowledgeContext.fingerprint(original)), "masked and known content distinct fingerprints");
        System.out.println("RoomKnowledgeContextTest: " + checks + " checks PASS");
    }
    private static AiTestContentRegistryBridge.ContentSnapshot snapshot(String description, long generation, Map<String,List<String>> situations) {
        var profile = new AiTestContentRegistryBridge.Profile("source", "identity", description, List.of("reserved"),
                List.of("freedom"), List.of("terse"), List.of("distinct persona"), situations, Map.of(), List.of("do not invent"), List.of("C_PROUD"));
        return new AiTestContentRegistryBridge.ContentSnapshot(profile, List.of(new AiTestContentRegistryBridge.Lore(
                "test:lore", "safe title", "SECRET", 1, List.of("ordinary secret already authorized by author policy"))),
                List.of(), List.of("R_WARY prose"), List.of(), generation);
    }
    private static void check(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }
}
