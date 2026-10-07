package com.sande.mythictrpg.ai;

import com.google.gson.Gson;
import com.sande.mythictrpg.ai.MinecraftCommonKnowledge.Fact;
import com.sande.mythictrpg.ai.api.RoomConversationEngine.GodState;
import com.sande.mythictrpg.ai.api.RoomConversationEngine.HistoryLine;
import com.sande.mythictrpg.ai.api.RoomConversationEngine.Request;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Offline retrieval, prompt-budget and reflection-contract checks; never invokes a model or a server. */
public final class MinecraftCommonKnowledgeTest {
    private static final Gson JSON = new Gson();
    private static final ResourceLocation GOD = ResourceLocation.parse("test:god");
    private static final UUID PLAYER = UUID.fromString("b0adab0a-485d-409b-bf41-d5acffb2a8bc");
    private static final Fact HEART = new Fact("test:heart", "바다의 심장",
            List.of("바다의 심장", "heart of the sea", "minecraft:heart_of_the_sea"),
            "바다의 심장은 플레이어가 획득할 수 있는 일반 Minecraft 아이템이다.");
    private static final Fact DIAMOND = new Fact("test:diamond", "다이아몬드",
            List.of("다이아몬드", "diamond", "minecraft:diamond"), "다이아몬드는 제작 재료다.");
    private static final Fact FURNACE = new Fact("test:furnace", "화로",
            List.of("화로", "furnace", "minecraft:furnace"), "화로는 아이템을 제련하는 블록이다.");
    private static final Fact TORCH = new Fact("test:torch", "횃불",
            List.of("횃불", "torch", "minecraft:torch"), "횃불은 빛을 내는 블록이다.");
    private static final Fact WHEAT = new Fact("test:wheat", "밀 작물",
            List.of("밀 작물", "wheat", "minecraft:wheat"), "밀은 농사로 얻을 수 있는 작물이다.");
    private static final Fact BREAD = new Fact("test:bread", "빵 음식",
            List.of("빵 음식", "bread", "minecraft:bread"), "빵은 먹을 수 있는 아이템이다.");
    private static final List<Fact> CATALOG = List.of(HEART, DIAMOND, FURNACE, TORCH, WHEAT, BREAD);
    private static int checks;

    public static void main(String[] args) {
        aliasesAndIds();
        singleHangulNouns();
        rankingAndScope();
        selectionBudget();
        immutableData();
        classificationBudget();
        reflectionContract();
        System.out.println("MinecraftCommonKnowledgeTest: PASS (" + checks
                + " checks; no model, server or narrative-quality claim)");
    }

    private static void aliasesAndIds() {
        for (String text : List.of("바다의 심장은 어디서 구해?", "바다의심장", "바 다 의\t심 장을 찾자",
                "HEART   of THE Sea?", "minecraft:heart_of_the_sea", "minecraft:heart_of_the_sea.",
                "{\"itemId\":\"minecraft:heart_of_the_sea\"}")) {
            check(selected(text).equals(List.of(HEART)), "Korean, English and exact ID alias: " + text);
        }
        for (String text : List.of("다른 화제를 이야기하자", "심장이 두근거려", "heartfelt", "diamondback",
                "mod:diamond", "other:heart_of_the_sea", "mod:minecraft:heart_of_the_sea",
                "minecraft:heart_of_the_sea_extra", "prefix/minecraft:heart_of_the_sea",
                "minecraft:heart_of_the_sea/variant")) {
            check(selected(text).isEmpty(), "unrelated or colliding token is not common knowledge: " + text);
        }
        check(selected("a DIAMOND!").equals(List.of(DIAMOND)), "English word boundary accepts punctuation");
        var oneCharacter = new Fact("test:short", "금속 재료", List.of("철", "빛", "x", "minecraft:iron_ingot"), "금속 설명");
        check(MinecraftCommonKnowledge.select(request("철학과 빛나는 x", List.of(), "", false), List.of(oneCharacter)).isEmpty(),
                "single-character natural aliases never become fuzzy matches");
        check(MinecraftCommonKnowledge.select(request("minecraft:iron_ingot", List.of(), "", false), List.of(oneCharacter))
                .equals(List.of(oneCharacter)), "one-character names still have exact-ID lookup");
    }

    private static void singleHangulNouns() {
        var wheat = new Fact("test:wheat_single", "밀 재배와 용도", List.of("밀", "wheat"), "밀 설명");
        var bread = new Fact("test:bread_single", "빵", List.of("bread"), "빵 설명");
        var iron = new Fact("test:iron_single", "철 원석과 철 주괴", List.of("철", "iron"), "철 설명");
        var catalog = List.of(wheat, bread, iron);
        for (String text : List.of("밀 10개", "밀을 구할까", "밀은 어디서 구하지?", "밀이 필요해", "밀도 줘", "밀만 가져와",
                "밀에 관심 있어", "밀로 빵을 만들자", "밀과 재료", "밀부터 찾자", "밀까지 가져와", "[밀]", "밀.")) {
            check(MinecraftCommonKnowledge.select(request(text, List.of(), "", false), List.of(wheat)).equals(List.of(wheat)),
                    "one-syllable Hangul keyword respects noun and particle boundary: " + text);
        }
        for (String text : List.of("빵 좀 줘", "빵을 먹자", "빵이 필요해", "빵", "빵으로도 만들 수 있어?")) {
            check(MinecraftCommonKnowledge.select(request(text, List.of(), "", false), List.of(bread)).equals(List.of(bread)),
                    "single-character title is a complete noun: " + text);
        }
        for (String text : List.of("철을 구할까", "철 10개", "철은 남아 있어", "철로는 뭘 만들지?")) {
            check(MinecraftCommonKnowledge.select(request(text, List.of(), "", false), List.of(iron)).equals(List.of(iron)),
                    "single-character iron alias works without guessing title prefixes: " + text);
        }
        for (String text : List.of("비밀", "비밀은 지켜", "정밀", "정밀하게 보자", "빵긋", "빵긋 웃어", "철학", "철학을 공부해",
                "철수", "철수가 왔어", "밀집", "밀가루", "밀려와", "mythictrpg:밀")) {
            check(MinecraftCommonKnowledge.select(request(text, List.of(), "", false), catalog).isEmpty(),
                    "one-syllable noun cannot match inside another word: " + text);
        }
        var letter = new Fact("test:letter", "letter reference", List.of("x"), "definition");
        check(MinecraftCommonKnowledge.select(request("x", List.of(), "", false), List.of(letter)).isEmpty(),
                "isolated English one-character aliases stay disabled");
    }

    private static void rankingAndScope() {
        var history = List.of(line("화로"), line("minecraft:diamond"));
        var current = request("바다의심장", history, "minecraft:torch", false);
        check(MinecraftCommonKnowledge.select(current, CATALOG).equals(List.of(HEART, DIAMOND, FURNACE, TORCH)),
                "current mention precedes newest history, older history and supplied game context");
        var old = List.of(line("바다의 심장"), line("one"), line("two"), line("three"), line("four"));
        check(MinecraftCommonKnowledge.select(request("hello", old, "", false), CATALOG).isEmpty(),
                "only the last four permitted history lines participate");
        check(MinecraftCommonKnowledge.select(request("hello", List.of(), "quest item minecraft:heart_of_the_sea", false), CATALOG)
                .equals(List.of(HEART)), "supplied quest game context can select a public item definition");

        var rawSecondary = request("바다의 심장", List.of(), "", true);
        check(MinecraftCommonKnowledge.select(rawSecondary, CATALOG).isEmpty(), "unproven Secondary raw text cannot select facts");
        var deliveredSecondary = request("UNPROVEN_PLAYER_TEXT", List.of(line("바다의 심장 이야기를 하자")), "", true);
        check(MinecraftCommonKnowledge.select(deliveredSecondary, CATALOG).equals(List.of(HEART)),
                "Secondary uses the actually delivered Primary conversation");

        var roomA = request("바다의 심장", List.of(), "", false);
        var roomB = request("다이아몬드", List.of(), "", false);
        var calls = new ArrayList<CompletableFuture<Boolean>>();
        for (int i = 0; i < 40; i++) {
            boolean firstRoom = i % 2 == 0;
            calls.add(CompletableFuture.supplyAsync(() -> MinecraftCommonKnowledge.select(firstRoom ? roomA : roomB, CATALOG)
                    .equals(List.of(firstRoom ? HEART : DIAMOND))));
        }
        for (var call : calls) check(call.join(), "concurrent same-player same-God rooms keep local selection");
        check(current.history().equals(history), "selection does not rewrite request history");
        check(MinecraftCommonKnowledge.select(request("hello", List.of(), "", false), CATALOG).isEmpty(),
                "no remembered catalog query or unrelated lore is carried to the next request");
    }

    private static void selectionBudget() {
        var request = request("바다의 심장 diamond furnace torch wheat bread", List.of(), "", false);
        var first = MinecraftCommonKnowledge.select(request, CATALOG);
        check(first.size() == MinecraftCommonKnowledge.MAX_FACTS, "at most four relevant facts selected");
        check(JSON.toJson(MinecraftCommonKnowledge.reference(first)).length() <= MinecraftCommonKnowledge.MAX_REFERENCE_CHARACTERS,
                "reference including labels fits 1800 characters");
        var reversed = new ArrayList<>(CATALOG);
        Collections.reverse(reversed);
        check(first.equals(MinecraftCommonKnowledge.select(request, reversed)), "input catalog order does not affect ranking");
        check(JSON.toJson(MinecraftCommonKnowledge.reference(first))
                .equals(JSON.toJson(MinecraftCommonKnowledge.reference(MinecraftCommonKnowledge.select(request, reversed)))),
                "reference serialization is deterministic");
        var escaped = new Fact("test:escaped", "quotation", List.of("quotation"), "\"".repeat(1_200));
        check(MinecraftCommonKnowledge.select(request("quotation", List.of(), "", false), List.of(escaped)).isEmpty(),
                "JSON escaping is counted, oversized optional fact is omitted whole");
        var longFact = new Fact("test:long", "longdescription", List.of("longdescription"), "d".repeat(1_000));
        var packed = MinecraftCommonKnowledge.select(request("longdescription 바다의심장 diamond furnace torch", List.of(), "", false),
                List.of(longFact, HEART, DIAMOND, FURNACE, TORCH));
        check(JSON.toJson(MinecraftCommonKnowledge.reference(packed)).length() <= 1_800, "several matched long facts remain bounded");
        rejected(IllegalArgumentException.class, () -> MinecraftCommonKnowledge.select(request, List.of(HEART, HEART)),
                "duplicate IDs fail closed rather than shadow public definitions");
    }

    @SuppressWarnings("unchecked")
    private static void immutableData() {
        var aliases = new ArrayList<>(List.of("source_alias_unique"));
        var fact = new Fact("test:immutable", "plain title", aliases, "plain definition");
        aliases.clear();
        check(fact.keywords().equals(List.of("source_alias_unique")), "Fact copies mutable keyword inputs");
        rejected(UnsupportedOperationException.class, () -> fact.keywords().add("mutate"), "Fact keywords immutable");
        var result = MinecraftCommonKnowledge.select(request("source_alias_unique", List.of(), "", false), List.of(fact));
        rejected(UnsupportedOperationException.class, () -> result.clear(), "selection immutable");
        var reference = MinecraftCommonKnowledge.reference(result);
        var rows = (List<Map<String, Object>>) reference.get("facts");
        rejected(UnsupportedOperationException.class, () -> reference.put("override", true), "reference envelope immutable");
        rejected(UnsupportedOperationException.class, () -> rows.clear(), "reference rows immutable");
        rejected(UnsupportedOperationException.class, () -> rows.getFirst().put("content", "mutate"), "reference fact immutable");
        String json = JSON.toJson(reference);
        check(!json.contains("keywords") && !json.contains("source_alias_unique"), "retrieval aliases and query absent from reference");
        check(json.contains("PUBLIC_VANILLA_MINECRAFT_GENERAL_KNOWLEDGE")
                && json.contains("REFERENCE_ONLY_NOT_CURRENT_GAME_STATE_OR_EXECUTION"), "public reference has explicit source boundary");
        String policy = MinecraftCommonKnowledge.policy();
        for (String required : List.of("Explicit metaphors", "in-world", "own voice", "technical IDs", "persona",
                "do not prove current inventory", "successful execution", "authorize no action or disclosure", "server overrides"))
            check(policy.contains(required), "policy preserves natural voice and authority: " + required);
    }

    private static void classificationBudget() {
        var base = List.of(new AiDialogueModels.OllamaMessage("system", "CLASSIFY_ONLY"),
                new AiDialogueModels.OllamaMessage("user", "KEEP_PLAYER_QUESTION"));
        var request = request("바다의 심장", List.of(), "", false);
        var result = MinecraftCommonKnowledge.classification(base, request, CATALOG);
        check(result.getFirst().content().startsWith("CLASSIFY_ONLY"), "classifier keeps its original task");
        check(result.getFirst().content().contains("do not generate dialogue or actions"), "shared policy cannot switch classifier task");
        check(result.getLast().content().startsWith("KEEP_PLAYER_QUESTION"), "original user context retained");
        check(result.getLast().content().contains("test:heart"), "classifier gets concrete item definition before generating intent");
        check(!base.getFirst().content().contains("COMMON_MINECRAFT_KNOWLEDGE")
                && base.getLast().content().equals("KEEP_PLAYER_QUESTION"), "wrapper leaves original messages untouched");
        rejected(UnsupportedOperationException.class, () -> result.clear(), "classification messages immutable");

        var saturated = List.of(base.getFirst(), new AiDialogueModels.OllamaMessage("user", "a".repeat(6_000)),
                new AiDialogueModels.OllamaMessage("user", "b".repeat(6_000)));
        var limited = MinecraftCommonKnowledge.classification(saturated, request, CATALOG);
        check(limited.get(1).equals(saturated.get(1)) && limited.get(2).equals(saturated.get(2)),
                "optional facts drop before any existing user text when combined user budget is full");
        check(userCharacters(limited) == 12_000, "budget accounts for every user message");
        var twoRequest = request("바다의 심장 diamond", List.of(), "", false);
        var twoFacts = MinecraftCommonKnowledge.select(twoRequest, CATALOG);
        int oneFactSuffix = "\n[COMMON_MINECRAFT_KNOWLEDGE]\n".length()
                + JSON.toJson(MinecraftCommonKnowledge.reference(List.of(twoFacts.getFirst()))).length();
        var almostFull = List.of(base.getFirst(), new AiDialogueModels.OllamaMessage("user", "k".repeat(12_000 - oneFactSuffix)));
        var partial = MinecraftCommonKnowledge.classification(almostFull, twoRequest, CATALOG);
        check(userCharacters(partial) == 12_000 && partial.getLast().content().contains(twoFacts.getFirst().id())
                && !partial.getLast().content().contains(twoFacts.getLast().id()),
                "classifier retains highest-priority fact and removes only optional lower-priority facts at exact boundary");
        rejected(IllegalArgumentException.class, () -> MinecraftCommonKnowledge.classification(
                List.of(base.getFirst(), new AiDialogueModels.OllamaMessage("user", "x".repeat(12_001))), request, CATALOG),
                "oversized mandatory classifier context fails closed");
        var noMatch = MinecraftCommonKnowledge.classification(base, request("hello", List.of(), "", false), CATALOG);
        check(noMatch.getLast().equals(base.getLast()), "no irrelevant definitions appended");
        var secondary = MinecraftCommonKnowledge.classification(base, request("바다의 심장", List.of(), "", true), CATALOG);
        check(secondary.getLast().equals(base.getLast()), "wrapper cannot reintroduce Secondary unproven raw text");
        rejected(IllegalArgumentException.class, () -> MinecraftCommonKnowledge.classification(List.of(base.getLast()), request, CATALOG),
                "missing system contract rejected");
        rejected(IllegalArgumentException.class, () -> MinecraftCommonKnowledge.classification(List.of(base.getFirst()), request, CATALOG),
                "missing user context rejected");
    }

    private static void reflectionContract() {
        var mutableEntries = new ArrayList<Object>(List.of(new PublicEntry(ResourceLocation.parse(HEART.id()), HEART.title(),
                HEART.keywords(), HEART.content())));
        var registry = new PublicRegistry(mutableEntries);
        var catalog = MinecraftCommonKnowledge.readCatalog(registry);
        check(catalog.equals(List.of(HEART)), "reflection converts actual contract shape without registry compile dependency");
        mutableEntries.clear();
        check(catalog.equals(List.of(HEART)), "reflection result remains an immutable snapshot after source reload");
        rejected(UnsupportedOperationException.class, () -> catalog.clear(), "reflection catalog immutable");
        check(MinecraftCommonKnowledge.readCatalog(new PublicRegistry(List.of())).isEmpty(), "empty public catalog is valid");
        rejected(IllegalStateException.class, () -> MinecraftCommonKnowledge.readCatalog(new OldRegistry()),
                "installed registry missing public method fails closed");
        rejected(IllegalStateException.class, () -> MinecraftCommonKnowledge.readCatalog(new FailingRegistry()),
                "public lookup error fails closed");
        rejected(IllegalArgumentException.class, () -> MinecraftCommonKnowledge.readCatalog(new WrongCollectionRegistry()),
                "wrong catalog collection shape rejected");
        for (Object wrong : List.of(new MalformedEntry("test:id", "title", List.of("keyword"), "content"),
                new MalformedEntry(ResourceLocation.parse("test:id"), 42, List.of("keyword"), "content"),
                new MalformedEntry(ResourceLocation.parse("test:id"), "title", List.of(42), "content"),
                new MalformedEntry(ResourceLocation.parse("test:id"), "title", List.of("keyword"), 42))) {
            rejected(IllegalArgumentException.class, () -> MinecraftCommonKnowledge.readCatalog(new PublicRegistry(List.of(wrong))),
                    "malformed common entry types rejected");
        }
        rejected(IllegalStateException.class, () -> MinecraftCommonKnowledge.readCatalog(new PublicRegistry(List.of(new Object()))),
                "entry missing record accessors rejected");
        rejected(IllegalArgumentException.class, () -> new Fact("test:id", "title", List.of(), "text"), "empty keywords rejected");
        rejected(IllegalArgumentException.class, () -> new Fact("test:id", "title", List.of("word"), "x".repeat(1_201)),
                "oversized source entry rejected");
    }

    public record PublicEntry(ResourceLocation id, String title, List<String> keywords, String content) { }
    public record MalformedEntry(Object id, Object title, Object keywords, Object content) { }
    public static final class PublicRegistry {
        private final List<?> entries;
        public PublicRegistry(List<?> entries) { this.entries = entries; }
        public List<?> publicCommonKnowledge() { return entries; }
        public Object lore() { throw new AssertionError("Common bridge must never access lore"); }
        public Object snapshot() { throw new AssertionError("Common bridge must never load whole raw content"); }
    }
    public static final class OldRegistry { }
    public static final class FailingRegistry {
        public List<?> publicCommonKnowledge() { throw new IllegalStateException("registry unavailable"); }
    }
    public static final class WrongCollectionRegistry {
        public Object publicCommonKnowledge() { return Map.of(); }
    }

    private static List<Fact> selected(String text) {
        return MinecraftCommonKnowledge.select(request(text, List.of(), "", false), CATALOG);
    }
    private static Request request(String text, List<HistoryLine> history, String context, boolean secondary) {
        return new Request(UUID.randomUUID(), 1, UUID.randomUUID(), PLAYER, "player", List.of(GOD), GOD,
                text, history, true, false, false, List.of(new GodState(GOD, "R_NEUTRAL", "E_NEUTRAL", context, null)), secondary);
    }
    private static HistoryLine line(String text) { return new HistoryLine("NPC", GOD.toString(), "god", text); }
    private static long userCharacters(List<AiDialogueModels.OllamaMessage> messages) {
        return messages.stream().filter(message -> message.role().equals("user")).mapToLong(message -> message.content().length()).sum();
    }
    private static void check(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }
    private static void rejected(Class<? extends RuntimeException> type, Runnable action, String label) {
        try { action.run(); } catch (RuntimeException expected) {
            if (!type.isInstance(expected)) throw new AssertionError(label, expected);
            checks++;
            return;
        }
        throw new AssertionError(label);
    }
}
