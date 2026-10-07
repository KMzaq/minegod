package com.sande.mythictrpg.ai;

import com.google.gson.*;
import com.sande.mythictrpg.ai.api.RoomEvidenceReference;
import net.minecraft.resources.ResourceLocation;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Portable evidence and profile-preservation tests; policy evaluation itself is tested in the content registry. */
public final class RoomKnowledgeContextTest {
    private static final Gson JSON = new Gson();
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
        malformedBeforeOwner(); reloadEveryProjectedField(); currentAudienceAndNoCache();
        System.out.println("RoomKnowledgeContextTest: " + checks + " checks PASS");
    }

    private static void malformedBeforeOwner() {
        var source = ResourceLocation.parse("test:source"); var player = UUID.randomUUID();
        var current = richSnapshot(); var reference = RoomKnowledgeContext.evidence(source, "R_WARY", current);
        var calls = new AtomicInteger();
        RoomKnowledgeContext.Lookup owner = (a,b,c,d,e,f) -> { calls.incrementAndGet(); return current; };
        for (Consumer<JsonObject> change : List.<Consumer<JsonObject>>of(
                json -> json.addProperty("speakerGodId", 123), json -> json.add("speakerGodId", JsonNull.INSTANCE),
                json -> json.addProperty("relationshipTier", false), json -> json.add("relationshipTier", new JsonArray()),
                json -> json.addProperty("sha256", "A".repeat(64)), json -> json.addProperty("sha256", "a".repeat(63)),
                json -> json.add("sha256", JsonNull.INSTANCE), json -> json.addProperty("unrecognizedGrant", true),
                json -> json.remove("relationshipTier"), json -> json.remove("relationTargets"),
                json -> json.addProperty("relationTargets", "test:source"), json -> json.add("relationTargets", new JsonArray()),
                json -> json.getAsJsonArray("relationTargets").add(42),
                json -> json.getAsJsonArray("relationTargets").add(JsonNull.INSTANCE),
                json -> json.getAsJsonArray("relationTargets").add("test:source"),
                json -> json.getAsJsonArray("relationTargets").set(0, new JsonPrimitive("test:other")),
                json -> json.getAsJsonArray("relationTargets").set(0, new JsonPrimitive("INVALID ID")),
                json -> { for (int i=0; i<16; i++) json.getAsJsonArray("relationTargets").add("test:extra_"+i); })) {
            var data = JsonParser.parseString(reference.payload()).getAsJsonObject(); change.accept(data); calls.set(0);
            check(!RoomKnowledgeContext.validEvidence(new RoomEvidenceReference(RoomKnowledgeContext.EVIDENCE_KIND, JSON.toJson(data)),
                    false, List.of(source), Set.of(player), owner), "malformed descriptor fails closed");
            check(calls.get() == 0, "malformed descriptors never become owner lookup scopes");
        }
        calls.set(0);
        check(!RoomKnowledgeContext.validEvidence(new RoomEvidenceReference(RoomKnowledgeContext.EVIDENCE_KIND,
                "{" + " ".repeat(16384)), false, List.of(source), Set.of(player), owner) && calls.get()==0,
                "oversized malformed descriptor rejected before owner invocation");
        check(!RoomKnowledgeContext.validEvidence(null, false, List.of(source), Set.of(player), owner), "null reference grants nothing");
        var fakeDigest = JsonParser.parseString(reference.payload()).getAsJsonObject(); fakeDigest.addProperty("sha256", "0".repeat(64)); calls.set(0);
        check(!RoomKnowledgeContext.validEvidence(new RoomEvidenceReference(RoomKnowledgeContext.EVIDENCE_KIND, JSON.toJson(fakeDigest)),
                false, List.of(source), Set.of(player), owner) && calls.get()==1, "well-formed hash is not authority: live owner projection still compared");
        calls.set(0);
        var fakeOwner = JsonParser.parseString(reference.payload()).getAsJsonObject(); fakeOwner.addProperty("speakerGodId", "test:missing");
        fakeOwner.getAsJsonArray("relationTargets").set(0, new JsonPrimitive("test:missing"));
        check(!RoomKnowledgeContext.validEvidence(new RoomEvidenceReference(RoomKnowledgeContext.EVIDENCE_KIND, JSON.toJson(fakeOwner)),
                false, List.of(source), Set.of(player), (a,b,c,d,e,f) -> { calls.incrementAndGet(); throw new IllegalStateException("missing owner"); })
                && calls.get()==1, "syntactically valid fictional owner cannot use another profile's checksum");
    }

    private static AiTestContentRegistryBridge.ContentSnapshot richSnapshot() {
        var original = snapshot("authorized biography", 7, Map.of("S_CHAT", List.of("listen")));
        var examples = List.of(new AiTestContentRegistryBridge.Example("test:example", List.of("P_FORMAL"), List.of(
                new AiTestContentRegistryBridge.ExampleTurn("player", "hello"), new AiTestContentRegistryBridge.ExampleTurn("npc", "greetings"))));
        return new AiTestContentRegistryBridge.ContentSnapshot(original.profile(), original.lore(), examples,
                original.relationshipGuidance(), List.of("R_ALLY"), original.generation());
    }
    private static AiTestContentRegistryBridge.ContentSnapshot altered(AiTestContentRegistryBridge.ContentSnapshot value, Consumer<JsonObject> change) {
        var json = JSON.toJsonTree(value).getAsJsonObject(); change.accept(json);
        return JSON.fromJson(json, AiTestContentRegistryBridge.ContentSnapshot.class);
    }
    private static void reloadEveryProjectedField() {
        var god = ResourceLocation.parse("test:source"); var players = Set.of(UUID.randomUUID()); var original = richSnapshot();
        var reference = RoomKnowledgeContext.evidence(god, "R_WARY", original);
        var current = new AtomicReference<>(original); var calls = new AtomicInteger();
        RoomKnowledgeContext.Lookup owner = (a,b,c,d,e,f) -> { calls.incrementAndGet(); return current.get(); };
        check(RoomKnowledgeContext.validEvidence(reference, false, List.of(god), players, owner), "initial projected content accepted");
        for (String field : List.of("displayName", "identity", "description", "personality", "values", "speechStyles", "dialogueGuidelines",
                "situationGuidelines", "repetitionGuidelines", "restrictions", "characterTags")) {
            current.set(altered(original, json -> {
                var profile = json.getAsJsonObject("profile");
                var old = profile.get(field);
                if (old.isJsonArray()) old.getAsJsonArray().add("changed authored value");
                else if (old.isJsonObject()) old.getAsJsonObject().add("S_CHANGE", JSON.toJsonTree(List.of("changed authored value")));
                else profile.addProperty(field, "changed authored value");
            }));
            check(current.get().generation()==original.generation(), "same generation deliberately used in mutation fixture");
            check(!RoomKnowledgeContext.validEvidence(reference, false, List.of(god), players, owner), "every projected profile field participates in current fingerprint");
        }
        for (Consumer<JsonObject> change : List.<Consumer<JsonObject>>of(
                json -> json.getAsJsonArray("lore").get(0).getAsJsonObject().addProperty("id", "test:other_lore"),
                json -> json.getAsJsonArray("lore").get(0).getAsJsonObject().addProperty("title", "changed title"),
                json -> json.getAsJsonArray("lore").get(0).getAsJsonObject().addProperty("secrecy", "PUBLIC"),
                json -> json.getAsJsonArray("lore").get(0).getAsJsonObject().addProperty("knowledgeLevel", 2),
                json -> json.getAsJsonArray("lore").get(0).getAsJsonObject().getAsJsonArray("accessibleLevels").set(0,new JsonPrimitive("newly masked lore")),
                json -> json.add("lore",new JsonArray()),
                json -> json.getAsJsonArray("examples").get(0).getAsJsonObject().addProperty("id", "test:other_example"),
                json -> json.getAsJsonArray("examples").get(0).getAsJsonObject().getAsJsonArray("tags").add("P_SHORT"),
                json -> json.getAsJsonArray("examples").get(0).getAsJsonObject().getAsJsonArray("dialogue").get(1).getAsJsonObject().addProperty("text", "different response"),
                json -> json.add("examples",new JsonArray()),
                json -> json.getAsJsonArray("relationshipGuidance").set(0,new JsonPrimitive("changed relationship behavior")),
                json -> json.getAsJsonArray("socialRelationTags").set(0,new JsonPrimitive("R_ENEMY")))) {
            current.set(altered(original, change));
            check(!RoomKnowledgeContext.validEvidence(reference, false, List.of(god), players, owner), "lore/example/relationship/directional content reload revokes old replay");
        }
        current.set(altered(original, json -> json.addProperty("generation", 999)));
        check(RoomKnowledgeContext.validEvidence(reference, false, List.of(god), players, owner), "identical current content after restart/reload remains valid without generation-as-permission");
        current.set(null);
        check(!RoomKnowledgeContext.validEvidence(reference, false, List.of(god), players, owner), "removed/unavailable owner cannot reuse previous successful projection");
        current.set(original);
        check(RoomKnowledgeContext.validEvidence(reference, false, List.of(god), players, owner), "restored authoritative projection is checked anew rather than permanently cached denial");
        check(calls.get()==27, "every validity check invokes current owner; no local checksum permission cache");
    }

    private static void currentAudienceAndNoCache() {
        var author=ResourceLocation.parse("test:author"); var reader=ResourceLocation.parse("test:reader"); var stranger=ResourceLocation.parse("test:stranger");
        UUID player=UUID.randomUUID(), listener=UUID.randomUUID(); var original=richSnapshot();
        var proof=RoomKnowledgeContext.evidence(author,"R_WARY",original,List.of(author,reader));
        var calls=new AtomicInteger(); var forwarded=new AtomicBoolean(true);
        RoomKnowledgeContext.Lookup owner=(source,tier,publicly,gods,players,relations)->{
            calls.incrementAndGet();
            if(!source.equals(author)||!tier.equals("R_WARY")||!relations.equals(List.of(author,reader))) forwarded.set(false);
            boolean authorized=!publicly&&gods.equals(List.of(reader))&&players.equals(Set.of(player));
            return authorized?original:altered(original,json->{json.add("lore",new JsonArray());json.add("examples",new JsonArray());});
        };
        check(RoomKnowledgeContext.validEvidence(proof,false,List.of(reader),Set.of(player),owner), "current reader can revalidate absent original author without replacing identity");
        check(!RoomKnowledgeContext.validEvidence(proof,false,List.of(reader),Set.of(player,listener),owner), "new player listener's owner-masked projection invalidates old ref");
        check(!RoomKnowledgeContext.validEvidence(proof,false,List.of(reader,stranger),Set.of(player),owner), "all current Gods passed to owner, not historical two-God subset");
        check(!RoomKnowledgeContext.validEvidence(proof,true,List.of(reader),Set.of(player),owner), "public transition invokes actual public projection rather than cached private permission");
        check(RoomKnowledgeContext.validEvidence(proof,false,List.of(reader),Set.of(player),owner), "returning to allowed actual audience performs fresh current owner evaluation");
        check(forwarded.get()&&calls.get()==5, "original author/tier/relation targets and complete current audience preserved on every lookup");
        var immutable=new AtomicBoolean();
        check(RoomKnowledgeContext.validEvidence(proof,false,new ArrayList<>(List.of(reader)),new HashSet<>(Set.of(player)),(a,b,c,gods,players,targets)->{
            int rejected=0;
            try{gods.clear();}catch(UnsupportedOperationException expected){rejected++;}
            try{players.clear();}catch(UnsupportedOperationException expected){rejected++;}
            try{targets.clear();}catch(UnsupportedOperationException expected){rejected++;}
            immutable.set(rejected==3);return original;
        })&&immutable.get(), "owner receives immutable audience/reference snapshots, not caller-owned mutable containers");
        check(!RoomKnowledgeContext.validEvidence(proof,false,List.of(reader),Set.of(player),
                (a,b,c,d,e,f)->{throw new IllegalStateException("registry reload unavailable");}), "owner reload failure cannot fall back to previous hash or content");
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
