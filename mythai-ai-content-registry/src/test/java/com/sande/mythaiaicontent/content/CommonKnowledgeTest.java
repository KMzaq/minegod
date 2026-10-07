package com.sande.mythaiaicontent.content;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** Real datapack prepare/apply and author schema checks without server or model calls. */
public final class CommonKnowledgeTest {
    private static int checks;
    private static final ResourceLocation ID = id("test:reference");
    private static final String DIRECTORY = "mythai_ai/common_knowledge/";

    public static void main(String[] args) throws Exception {
        parser();
        reload();
        bundledReferences();
        System.out.println("CommonKnowledgeTest: " + checks + " checks PASS");
    }

    private static JsonObject valid() {
        return JsonParser.parseString("""
                {"schemaVersion":2,"title":"바다의 심장","keywords":["바다의 심장","바다의심장",
                "heart of the sea","minecraft:heart_of_the_sea"],"content":"묻힌 보물 상자에서 얻을 수 있다."}
                """).getAsJsonObject();
    }

    private static CommonKnowledgeEntry parse(JsonObject json) {
        return AiContentRegistry.parseCommonKnowledge(ID, json);
    }

    private static void parser() {
        var parsed = parse(valid());
        check(parsed.id().equals(ID), "content identity remains file based");
        check(parsed.keywords().contains("minecraft:heart_of_the_sea"), "item identity is an alias");
        rejects(() -> parsed.keywords().add("mutated"), "keywords immutable");
        for (String extra : List.of("secrecy", "known_by", "level", "knowledgeLevels", "disclosure", "allowedGodIds",
                "godId", "loreKnowledge", "id", "unknown")) {
            var malformed = valid();
            malformed.addProperty(extra, "SECRET");
            rejects(() -> parse(malformed), "restricted/unknown field rejected: " + extra);
        }
        for (String field : List.of("schemaVersion", "title", "keywords", "content")) {
            var missing = valid();
            missing.remove(field);
            rejects(() -> parse(missing), "missing " + field);
            var explicitNull = valid();
            explicitNull.add(field, null);
            rejects(() -> parse(explicitNull), "null " + field);
        }
        for (String version : List.of("1", "3", "2.5", "true", "\"2\"", "{}", "[]")) {
            var malformed = valid();
            malformed.add("schemaVersion", JsonParser.parseString(version));
            rejects(() -> parse(malformed), "strict numeric schema2: " + version);
        }
        for (String field : List.of("title", "content")) {
            for (String value : List.of("42", "true", "[]", "{}", "\" \"")) {
                var malformed = valid();
                malformed.add(field, JsonParser.parseString(value));
                rejects(() -> parse(malformed), "nonstring/blank " + field + ": " + value);
            }
        }
        for (String keywords : List.of("[]", "{}", "[3]", "[true]", "[null]", "[{}]", "[\"\"]", "[\"  \"]")) {
            var malformed = valid();
            malformed.add("keywords", JsonParser.parseString(keywords));
            rejects(() -> parse(malformed), "strict keyword array: " + keywords);
        }
        var boundary = valid();
        boundary.addProperty("title", "x".repeat(100));
        boundary.addProperty("content", "x".repeat(1200));
        var keywords = new JsonArray();
        for (int i = 0; i < 24; i++) keywords.add("x".repeat(80));
        boundary.add("keywords", keywords);
        check(parse(boundary).keywords().size() == 24, "all inclusive bounds accepted");
        for (String field : List.of("title", "content")) {
            var malformed = boundary.deepCopy();
            malformed.addProperty(field, "x".repeat(field.equals("title") ? 101 : 1201));
            rejects(() -> parse(malformed), "overlong " + field);
        }
        var tooMany = boundary.deepCopy();
        tooMany.getAsJsonArray("keywords").add("extra");
        rejects(() -> parse(tooMany), "25 keywords denied");
        var tooLong = valid();
        tooLong.getAsJsonArray("keywords").add("x".repeat(81));
        rejects(() -> parse(tooLong), "81 character keyword denied");
        var mutable = new ArrayList<>(List.of("original"));
        var copied = new CommonKnowledgeEntry(ID, "title", mutable, "fact");
        mutable.set(0, "changed");
        check(copied.keywords().equals(List.of("original")), "caller cannot mutate entry aliases");
        var legacy = new AiContentRegistry.Snapshot(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of(), Map.of(), Map.of(), 7);
        check(legacy.publicCommonKnowledge().isEmpty() && legacy.generation() == 7, "previous snapshot constructor compatible");
    }

    private static void reload() {
        var registry = AiContentRegistry.INSTANCE;
        var resources = new LinkedHashMap<ResourceLocation, String>();
        resources.put(id("test:" + DIRECTORY + "z.json"), valid().toString());
        resources.put(id("test:" + DIRECTORY + "a.json"), valid().toString());
        resources.put(id("test:mythai_ai/lore/secret.json"), """
                {"schemaVersion":2,"title":"secret lore","secrecy":"SECRET","keywords":["private"],
                "knowledgeLevels":[{"level":1,"content":"NEVER_PUBLIC_MARKER"}]}
                """);
        ResourceManager manager = resources(resources);
        var before = registry.snapshot();
        var pending = registry.prepare(manager, null);
        check(registry.snapshot() == before, "preparation does not publish partial state");
        registry.apply(pending, manager, null);
        var good = registry.snapshot();
        check(good.generation() == before.generation() + 1, "one snapshot generation for all categories");
        check(registry.publicCommonKnowledge().size() == 2, "new category loaded");
        check(registry.publicCommonKnowledge().getFirst().id().equals(id("test:a")), "stable resource ordering");
        check(good.loreById().size() == 1, "old category atomically loaded alongside reference facts");
        check(registry.publicCommonKnowledge().stream().noneMatch(entry -> entry.content().contains("NEVER_PUBLIC_MARKER")),
                "secret lore never becomes a shared reference");
        rejects(() -> registry.publicCommonKnowledge().clear(), "public accessor immutable");

        var malformed = valid();
        malformed.addProperty("secrecy", "SECRET");
        resources.put(id("test:" + DIRECTORY + "a.json"), malformed.toString());
        rejects(() -> registry.prepare(resources(resources), null), "malformed reference rejects reload");
        check(registry.snapshot() == good, "rejection retains complete previous snapshot and generation");
        check(registry.publicCommonKnowledge() == good.publicCommonKnowledge(), "previous reference list retained");

        var maximum = new LinkedHashMap<ResourceLocation, String>();
        for (int i = 0; i < 128; i++) maximum.put(id("pack" + (i % 2) + ":" + DIRECTORY + "entry_" + i + ".json"), valid().toString());
        var maxPrepared = registry.prepare(resources(maximum), null);
        check(maxPrepared.publicCommonKnowledge().size() == 128, "128 entries across namespaces accepted");
        maximum.put(id("another:" + DIRECTORY + "overflow.json"), valid().toString());
        rejects(() -> registry.prepare(resources(maximum), null), "129 entries across namespaces rejected");
        check(registry.snapshot() == good, "count rejection cannot publish other categories");
        var empty = resources(Map.of());
        registry.apply(registry.prepare(empty, null), empty, null);
        check(registry.publicCommonKnowledge().isEmpty(), "resource removal clears old references on successful reload");
        check(good.publicCommonKnowledge().size() == 2, "previous snapshot stays immutable after replacement");
    }

    private static void bundledReferences() throws Exception {
        Path directory = Path.of("src/main/resources/data/mythaiaicontent/mythai_ai/common_knowledge");
        var resources = new LinkedHashMap<ResourceLocation, String>();
        try (var files = Files.list(directory)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".json")).sorted().toList()) {
                String json = Files.readString(file);
                var entry = AiContentRegistry.parseCommonKnowledge(id("mythaiaicontent:" + file.getFileName().toString().replace(".json", "")),
                        JsonParser.parseString(json).getAsJsonObject());
                check(entry.content().length() <= 300, "bundled reference remains compact: " + entry.id());
                String shortNoun = Map.of("wheat", "밀", "bread", "빵", "iron", "철").get(entry.id().getPath());
                check(entry.keywords().stream().allMatch(keyword -> keyword.length() >= 2 || keyword.equals(shortNoun)),
                        "only curated one-character noun aliases: " + entry.id());
                check(entry.keywords().stream().anyMatch(keyword -> keyword.startsWith("minecraft:")), "actual item/block alias: " + entry.id());
                resources.put(id("mythaiaicontent:" + DIRECTORY + file.getFileName()), json);
            }
        }
        check(resources.size() >= 12 && resources.size() <= 20, "curated starter scope");
        var manager = resources(resources);
        var registry = AiContentRegistry.INSTANCE;
        registry.apply(registry.prepare(manager, null), manager, null);
        check(registry.publicCommonKnowledge().size() == resources.size(), "all bundled references pass the actual reload path");
        var heart = registry.publicCommonKnowledge().stream().filter(entry -> entry.id().getPath().equals("heart_of_the_sea")).findFirst().orElseThrow();
        check(heart.keywords().containsAll(List.of("바다의 심장", "바다의심장", "minecraft:heart_of_the_sea")), "heart spaced/unspaced/item aliases");
        check(heart.content().contains("묻힌 보물 상자") && heart.content().contains("희귀"), "heart is obtainable exploration loot, rarity retained");
    }

    @SuppressWarnings("unchecked")
    private static ResourceManager resources(Map<ResourceLocation, String> jsonByFile) {
        PackResources pack = (PackResources) Proxy.newProxyInstance(PackResources.class.getClassLoader(),
                new Class<?>[]{PackResources.class}, (proxy, method, args) -> {
                    if (method.getName().equals("packId")) return "common-knowledge-test";
                    throw new UnsupportedOperationException(method.getName());
                });
        return (ResourceManager) Proxy.newProxyInstance(ResourceManager.class.getClassLoader(),
                new Class<?>[]{ResourceManager.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("listResources")) throw new UnsupportedOperationException(method.getName());
                    String path = (String) args[0];
                    Predicate<ResourceLocation> filter = (Predicate<ResourceLocation>) args[1];
                    Map<ResourceLocation, Resource> matching = new LinkedHashMap<>();
                    jsonByFile.forEach((file, json) -> {
                        if (file.getPath().startsWith(path + "/") && filter.test(file)) {
                            matching.put(file, new Resource(pack, () -> new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))));
                        }
                    });
                    return matching;
                });
    }

    private static ResourceLocation id(String value) { return ResourceLocation.parse(value); }
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void rejects(Runnable action, String message) {
        try { action.run(); }
        catch (IllegalArgumentException | IllegalStateException | UnsupportedOperationException expected) {
            checks++;
            return;
        }
        throw new AssertionError("Expected rejection: " + message);
    }
}
