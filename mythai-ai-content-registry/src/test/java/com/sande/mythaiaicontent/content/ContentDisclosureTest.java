package com.sande.mythaiaicontent.content;

import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import java.lang.reflect.InvocationTargetException;
import java.util.*;

/** Offline author-schema and audience projection checks, no server/model or runtime ownership mutation. */
public final class ContentDisclosureTest {
    private static int checks;
    private static final ResourceLocation A = id("a"), B = id("b"), C = id("c"), LORE = id("lore");
    private static final UUID P = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final ContentDisclosure PRIVATE = new ContentDisclosure(ContentDisclosure.Mode.PRIVATE_ROOM, Set.of(B));

    public static void main(String[] args) throws Exception {
        var privateAB = audience(false, A, B);
        var publicAB = audience(true, A, B);
        check(PRIVATE.permits(privateAB), "explicit private audience allowed");
        check(!PRIVATE.permits(publicAB), "private content denied in public");
        check(!PRIVATE.permits(audience(false, A, B, C)), "one unauthorized god rejects whole audience");
        check(PRIVATE.permits(audience(false, A)), "speaker is not its own disclosure recipient");
        check(!ContentDisclosure.NEVER.permits(privateAB), "never denies");
        for (LoreSecrecy secrecy : LoreSecrecy.values()) {
            var lore = new LoreEntry(LORE, "title", List.of(new LoreKnowledgeLevel(1, "first", false)), secrecy, List.of());
            check(ContentAudienceResolver.loreFor(lore, 1, privateAB).isPresent() == (secrecy == LoreSecrecy.PUBLIC),
                    "legacy secrecy has explicit safe default " + secrecy);
        }
        var tiered = new LoreEntry(LORE, "safe shared title", List.of(
                new LoreKnowledgeLevel(1, "public fact", false, ContentDisclosure.PUBLIC),
                new LoreKnowledgeLevel(2, "private fact", true, PRIVATE),
                new LoreKnowledgeLevel(3, "never fact", false, ContentDisclosure.NEVER),
                new LoreKnowledgeLevel(4, "cannot skip prerequisite", false, ContentDisclosure.PUBLIC)), LoreSecrecy.SECRET, List.of("safe keyword"));
        var privateLore = ContentAudienceResolver.loreFor(tiered, 4, privateAB).orElseThrow();
        check(privateLore.knowledgeLevel() == 2, "private disclosed prefix ends before denied prerequisite");
        check(privateLore.accessibleLevels().size() == 2, "later public tier cannot bypass hidden prerequisite");
        check(privateLore.knowledgeHolders().isEmpty(), "holder identities not leaked");
        check(privateLore.accessibleLevels().stream().noneMatch(LoreKnowledgeLevel::revealKnowledgeHolders), "holder flags not leaked");
        check(ContentAudienceResolver.loreFor(tiered, 4, publicAB).orElseThrow().knowledgeLevel() == 1, "public loses private tier");
        check(ContentAudienceResolver.loreFor(tiered, 1, privateAB).orElseThrow().knowledgeLevel() == 1, "disclosure cannot grant knowledge");
        check(ContentAudienceResolver.loreFor(tiered, 0, privateAB).isEmpty(), "unknown lore absent");

        var profile = profile(Map.of("description", PRIVATE, "identity", ContentDisclosure.NEVER));
        var ordinary = new DialogueExample(id("public_example"), Set.of("S_MISC"), Set.of(), turns());
        var secretExample = new DialogueExample(id("private_example"), Set.of("S_MISC"), Set.of(A), turns(), PRIVATE);
        var foreignExample = new DialogueExample(id("foreign_example"), Set.of("S_MISC"), Set.of(B), turns());
        var snapshot = snapshot(profile, tiered, ordinary, secretExample, foreignExample);
        var visible = ContentAudienceResolver.resolve(snapshot, RelationshipTier.NEUTRAL, privateAB).orElseThrow();
        var hidden = ContentAudienceResolver.resolve(snapshot, RelationshipTier.NEUTRAL, publicAB).orElseThrow();
        check(visible.profile().description().equals(profile.description()), "private profile knowledge allowed by author");
        check(hidden.profile().description().isEmpty(), "public profile secret omitted before prompt");
        check(hidden.profile().identity().isEmpty(), "NEVER field absent");
        check(hidden.profile().personality().equals(profile.personality()), "unrelated personality preserved");
        check(hidden.profile().speechStyles().equals(profile.speechStyles()), "voice not replaced when biography redacted");
        check(hidden.profile().values().equals(profile.values()), "values preserved");
        check(hidden.profile().restrictions().equals(profile.restrictions()), "ordinary restrictions preserved");
        check(hidden.profile().displayName().equals(profile.displayName()), "name not invented during masking");
        check(visible.examples().size() == 2 && hidden.examples().size() == 1, "example availability and audience both required");
        check(hidden.lore().getFirst().knowledgeLevel() == 1, "projection lore filtered");
        check(ContentAudienceResolver.resolve(snapshot, RelationshipTier.NEUTRAL,
                new ContentAudience(C, false, List.of(C), Set.of(P))).isEmpty(), "missing speaker profile not guessed");
        check(ContentAudienceResolver.resolve(snapshot(profile(Map.of("examples", ContentDisclosure.NEVER,
                "relationshipGuidelines", ContentDisclosure.NEVER)), tiered, ordinary), RelationshipTier.NEUTRAL, privateAB)
                .orElseThrow().examples().isEmpty(), "profile may hide all example snippets");
        check(ContentAudienceResolver.resolve(snapshot(profile(Map.of("relationshipGuidelines", ContentDisclosure.NEVER)),
                tiered), RelationshipTier.NEUTRAL, privateAB).orElseThrow().relationshipGuidance().isEmpty(), "relationship prose can be protected");

        var relationSnapshot = new AiContentRegistry.Snapshot(snapshot.godsByContentId(), snapshot.godsByGodId(), snapshot.loreById(),
                snapshot.examplesById(), snapshot.socialRelationsById(), snapshot.questListsById(), snapshot.knowledgeLevelsByGodId(),
                snapshot.holdersByLoreId(), Map.of(A, Map.of(B, List.of(SocialRelationTag.ENEMY))), snapshot.generation());
        var historical = ContentAudienceResolver.resolve(relationSnapshot, RelationshipTier.NEUTRAL, privateAB).orElseThrow();
        var authorAbsent = ContentAudienceResolver.resolve(relationSnapshot, RelationshipTier.NEUTRAL,
                new ContentAudience(A, false, List.of(B), Set.of(P)), List.of(A, B)).orElseThrow();
        check(authorAbsent.equals(historical), "original author need not still participate for a permitted recall");
        check(authorAbsent.socialRelationTags().equals(List.of("RT_ENEMY")), "historical relation lookup remains stable across participant change");
        var forbiddenNewAudience = ContentAudienceResolver.resolve(relationSnapshot, RelationshipTier.NEUTRAL,
                new ContentAudience(A, false, List.of(C), Set.of(P)), List.of(A, B)).orElseThrow();
        check(forbiddenNewAudience.profile().description().isEmpty(), "historical targets cannot substitute for actual new audience");
        check(forbiddenNewAudience.lore().getFirst().knowledgeLevel() == 1, "new god cannot receive author-restricted private tier through history");
        var relationLimited = profile(Map.of("socialRelationTags", new ContentDisclosure(ContentDisclosure.Mode.PUBLIC, Set.of(B))));
        var limitedSnapshot = new AiContentRegistry.Snapshot(Map.of(relationLimited.contentId(), relationLimited), Map.of(A, relationLimited),
                relationSnapshot.loreById(), relationSnapshot.examplesById(), Map.of(), Map.of(), Map.of(), Map.of(),
                relationSnapshot.socialTagsBySourceGodId(), 7);
        check(ContentAudienceResolver.resolve(limitedSnapshot, RelationshipTier.NEUTRAL, audience(true, A, B, C), List.of(A, B))
                .orElseThrow().socialRelationTags().isEmpty(), "directional pair lookup cannot bypass whole-audience relation policy");

        var gods = new ArrayList<ResourceLocation>();
        for (int i = 0; i < 16; i++) gods.add(id("g" + i));
        var players = new HashSet<UUID>();
        for (int i = 0; i < 64; i++) players.add(new UUID(0, i));
        check(new ContentAudience(gods.getFirst(), false, gods, players).playerIds().size() == 64, "64 player 16 god room preserved");
        players.add(new UUID(0, 64));
        rejects(() -> new ContentAudience(gods.getFirst(), false, gods, players), "private room exceeds limit");
        check(new ContentAudience(gods.getFirst(), true, gods, players).playerIds().size() == 65, "public actual audience may exceed room membership cap");
        rejects(() -> new ContentAudience(A, false, List.of(A, A), Set.of(P)), "duplicate gods rejected");
        rejects(() -> new ContentAudience(A, false, List.of(A), Set.of()), "unknown audience rejected");
        rejects(() -> profile(Map.of("typoDescription", ContentDisclosure.PUBLIC)), "unknown profile policy rejected");
        schema();
        System.out.println("ContentDisclosureTest: " + checks + " checks PASS");
    }

    private static void schema() throws Exception {
        String base = "{\"schemaVersion\":2,\"title\":\"test\",\"secrecy\":\"SECRET\",\"keywords\":[],\"knowledgeLevels\":[{\"level\":1,\"content\":\"safe\",\"disclosure\":%s}]}";
        var parsed = parseLore(base.formatted("{\"mode\":\"PRIVATE_ROOM\",\"allowedGodIds\":[\"test:b\"]}"));
        check(parsed.knowledgeLevels().getFirst().disclosure().equals(PRIVATE), "optional lore policy parsed");
        rejects(() -> parseLore(base.formatted("{\"mode\":\"PUBLIC\",\"allowdGodIds\":[]}")), "permission typo rejected");
        rejects(() -> parseLore(base.formatted("{\"mode\":\"SOMETIMES\"}")), "unknown mode rejected");
        rejects(() -> parseLore(base.formatted("null")), "explicit null policy rejected");
        var old = parseLore("{\"schemaVersion\":1,\"title\":\"old\",\"content\":\"old fact\",\"keywords\":[]}");
        check(old.knowledgeLevels().getFirst().disclosure() == null, "legacy schema stays supported");
        check(ContentAudienceResolver.loreFor(old, 1, audience(true, A)).isPresent(), "legacy public lore still works");
        String profile = "{\"schemaVersion\":2,\"godId\":\"test:a\",\"displayName\":\"A\",\"identity\":\"identity\",\"personality\":[],\"values\":[],\"speechStyles\":[],\"restrictions\":[],\"loreKnowledge\":[],\"signatureExampleIds\":[],\"fieldDisclosure\":%s}";
        var parsedProfile = (GodContentProfile) parse("parseGod", profile.formatted("{\"description\":{\"mode\":\"NEVER\"}}"));
        check(parsedProfile.fieldDisclosure().get("description").equals(ContentDisclosure.NEVER), "optional profile field policy parsed");
        rejects(() -> parse("parseGod", profile.formatted("{\"descrption\":{\"mode\":\"PUBLIC\"}}")), "profile policy field typo rejected");
        rejects(() -> parse("parseGod", profile.formatted("{\"description\":null}")), "null profile permission rejected");
        String example = "{\"schemaVersion\":2,\"tags\":[\"S_MISC\"],\"known_by\":[],\"dialogue\":[{\"role\":\"PLAYER\",\"text\":\"hello\"},{\"role\":\"NPC\",\"text\":\"reply\"}],\"disclosure\":{\"mode\":\"NEVER\"}}";
        check(((DialogueExample) parse("parseExample", example)).disclosure().equals(ContentDisclosure.NEVER), "example disclosure parser connected");
    }
    private static LoreEntry parseLore(String json) throws Exception {
        return (LoreEntry) parse("parseLore", json);
    }
    private static Object parse(String name, String json) throws Exception {
        var method = AiContentRegistry.class.getDeclaredMethod(name, ResourceLocation.class, com.google.gson.JsonObject.class);
        method.setAccessible(true);
        try { return method.invoke(null, LORE, JsonParser.parseString(json).getAsJsonObject()); }
        catch (InvocationTargetException wrapped) { throw (Exception) wrapped.getCause(); }
    }
    private static GodContentProfile profile(Map<String, ContentDisclosure> policies) {
        return new GodContentProfile(id("profile"), A, "Original Name", "private identity", "private biography",
                List.of("stubborn"), List.of("independence"), List.of("terse"), List.of("character guideline"),
                Map.of("S_MISC", List.of("ordinary situation")), Map.of("REPEAT", List.of("ordinary repetition")),
                List.of("do not invent events"), List.of("C_PROUD"), List.of(new LoreKnowledge(LORE, 4)),
                List.of(), List.of(), Map.of(RelationshipTier.NEUTRAL, List.of("neutral prose")), policies);
    }
    private static AiContentRegistry.Snapshot snapshot(GodContentProfile profile, LoreEntry lore, DialogueExample... examples) {
        var exampleMap = new HashMap<ResourceLocation, DialogueExample>();
        for (var example : examples) exampleMap.put(example.id(), example);
        return new AiContentRegistry.Snapshot(Map.of(profile.contentId(), profile), Map.of(A, profile), Map.of(LORE, lore),
                exampleMap, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), 7);
    }
    private static List<DialogueExampleTurn> turns() { return List.of(
            new DialogueExampleTurn(DialogueExampleTurn.Role.PLAYER, "hello"),
            new DialogueExampleTurn(DialogueExampleTurn.Role.NPC, "reply")); }
    private static ContentAudience audience(boolean publicRoom, ResourceLocation... gods) {
        return new ContentAudience(A, publicRoom, List.of(gods), Set.of(P));
    }
    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath("test", path); }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static void rejects(Checked operation, String message) {
        try { operation.run(); throw new AssertionError(message); }
        catch (IllegalArgumentException expected) { checks++; }
        catch (Exception unexpected) { throw new AssertionError(message, unexpected); }
    }
    private interface Checked { void run() throws Exception; }
}
