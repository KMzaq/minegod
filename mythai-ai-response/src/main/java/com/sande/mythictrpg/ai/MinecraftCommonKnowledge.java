package com.sande.mythictrpg.ai;

import com.google.gson.Gson;
import com.sande.mythictrpg.ai.api.RoomConversationEngine.Request;
import net.minecraft.resources.ResourceLocation;

import java.lang.reflect.InvocationTargetException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Public static reference data only; never reads lore, player state or another room's history. */
final class MinecraftCommonKnowledge {
    static final int MAX_FACTS = 4;
    static final int MAX_REFERENCE_CHARACTERS = 1_800;
    private static final int MAX_USER_CHARACTERS = 12_000;
    private static final Gson JSON = new Gson();
    // Mask whole namespaced tokens before looking for ordinary words: mod:diamond is not minecraft:diamond.
    private static final Pattern NAMESPACED_TOKEN = Pattern.compile("[a-z0-9_./-]+:[a-z0-9_./:-]+");
    private static final Pattern RESOURCE_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Pattern HANGUL = Pattern.compile("[\\p{IsHangul}]");
    private static final String KOREAN_NOUN_PARTICLE = "(?:으로부터|에서부터|으로는|으로도|으로|에서는|에서도|에서"
            + "|에게는|에게|한테|부터는|까지는|부터|까지|처럼|보다|마저|조차|이랑|랑|하고|에는|에도"
            + "|만은|만이|만도|로는|로도|은|는|이|가|을|를|도|만|에|로|와|과)?";
    private MinecraftCommonKnowledge() { }

    record Fact(String id, String title, List<String> keywords, String content) {
        Fact {
            if (id == null || !RESOURCE_ID.matcher(id).matches() || ResourceLocation.tryParse(id) == null)
                throw new IllegalArgumentException("Invalid common knowledge id");
            requireText(title, 100, "title");
            requireText(content, 1_200, "content");
            keywords = List.copyOf(keywords);
            if (keywords.isEmpty() || keywords.size() > 24)
                throw new IllegalArgumentException("Invalid common knowledge keywords");
            for (String keyword : keywords) requireText(keyword, 80, "keyword");
        }
    }

    static List<Fact> select(Request request) {
        return select(request, load());
    }

    /** Pure selection seam. The caller supplies only the registry's explicitly public common catalog. */
    static List<Fact> select(Request request, List<Fact> catalog) {
        Objects.requireNonNull(request);
        var sources = new ArrayList<SearchText>();
        // Secondary currentText can be unproven raw player input; only actually delivered history is usable.
        if (!request.secondary()) sources.add(searchText(request.currentText()));
        for (int i = request.history().size() - 1; i >= Math.max(0, request.history().size() - 4); i--)
            sources.add(searchText(request.history().get(i).text()));
        sources.add(searchText(request.speakerState().gameContext()));

        var ranked = new ArrayList<Ranked>();
        var identities = new HashSet<String>();
        for (Fact fact : List.copyOf(catalog)) {
            if (!identities.add(fact.id())) throw new IllegalArgumentException("Duplicate common knowledge id");
            var aliases = new ArrayList<>(fact.keywords());
            aliases.add(fact.title());
            aliases.add(fact.id());
            for (int source = 0; source < sources.size(); source++) {
                int strength = 0;
                for (String alias : aliases) strength = Math.max(strength, match(sources.get(source), alias));
                if (strength > 0) {
                    ranked.add(new Ranked(fact, source, strength));
                    break;
                }
            }
        }
        ranked.sort(Comparator.comparingInt(Ranked::source)
                .thenComparing(Comparator.comparingInt(Ranked::strength).reversed())
                .thenComparing(value -> value.fact().id()));
        var selected = new ArrayList<Fact>();
        for (Ranked candidate : ranked) {
            selected.add(candidate.fact());
            if (JSON.toJson(reference(selected)).length() > MAX_REFERENCE_CHARACTERS) selected.removeLast();
            if (selected.size() == MAX_FACTS) break;
        }
        return List.copyOf(selected);
    }

    static String policy() {
        return "COMMON_MINECRAFT_KNOWLEDGE is public vanilla reference data shared by all Gods. "
                + "Recognize named Minecraft items, blocks, creatures and mechanics as concrete game concepts; "
                + "do not invent a divine artifact or abstract power merely from a poetic item name. "
                + "Explicit metaphors remain metaphors; an ordinary mention of a heart is not automatically an item. "
                + "In dialogue, use relevant facts naturally in-world in this God's own voice, preserving persona and context. "
                + "Do not routinely call the setting a game, expose technical IDs or list recipes unless asked; "
                + "do not give an unsolicited encyclopedia dump. "
                + "These general facts do not prove current inventory, ownership, locations, availability, quest progress "
                + "or successful execution, and authorize no action or disclosure. Explicit supplied server overrides "
                + "and audience-permitted lore take precedence; never invent an override. Unknown facts remain unknown.";
    }

    /** Retrieval aliases are omitted; only bounded facts and their public/non-authoritative source label are exposed. */
    static Map<String, Object> reference(List<Fact> facts) {
        var rows = new ArrayList<Map<String, Object>>();
        for (Fact fact : List.copyOf(facts)) {
            var row = new LinkedHashMap<String, Object>();
            row.put("id", fact.id());
            row.put("title", fact.title());
            row.put("content", fact.content());
            rows.add(Collections.unmodifiableMap(row));
        }
        var reference = new LinkedHashMap<String, Object>();
        reference.put("source", "PUBLIC_VANILLA_MINECRAFT_GENERAL_KNOWLEDGE");
        reference.put("authority", "REFERENCE_ONLY_NOT_CURRENT_GAME_STATE_OR_EXECUTION");
        reference.put("facts", List.copyOf(rows));
        return Collections.unmodifiableMap(reference);
    }

    static List<AiDialogueModels.OllamaMessage> classification(List<AiDialogueModels.OllamaMessage> messages,
            Request request) {
        return classification(messages, request, load());
    }

    static List<AiDialogueModels.OllamaMessage> classification(List<AiDialogueModels.OllamaMessage> messages,
            Request request, List<Fact> catalog) {
        var result = new ArrayList<>(List.copyOf(messages));
        int system = -1, user = -1;
        long userCharacters = 0;
        for (int i = 0; i < result.size(); i++) {
            var message = result.get(i);
            if ("system".equals(message.role()) && system < 0) system = i;
            if ("user".equals(message.role())) { user = i; userCharacters += message.content().length(); }
        }
        if (system < 0 || user < 0 || userCharacters > MAX_USER_CHARACTERS)
            throw new IllegalArgumentException("Invalid or oversized common knowledge classification context");
        var instruction = result.get(system);
        result.set(system, new AiDialogueModels.OllamaMessage("system", instruction.content() + "\n\n" + policy()
                + " Use these references only to interpret player intent and item names; do not generate dialogue or actions."));
        var selected = new ArrayList<>(select(request, catalog));
        while (!selected.isEmpty()) {
            String suffix = "\n[COMMON_MINECRAFT_KNOWLEDGE]\n" + JSON.toJson(reference(selected));
            if (userCharacters + suffix.length() <= MAX_USER_CHARACTERS) {
                var input = result.get(user);
                result.set(user, new AiDialogueModels.OllamaMessage("user", input.content() + suffix));
                break;
            }
            selected.removeLast();
        }
        return List.copyOf(result);
    }

    private static List<Fact> load() {
        final Class<?> registryType;
        try {
            registryType = Class.forName("com.sande.mythaiaicontent.content.AiContentRegistry");
        } catch (ClassNotFoundException offlineFixture) {
            // Production requires the matching content mod. Standalone offline prompt fixtures omit that mod.
            return List.of();
        }
        try {
            Object registry = Objects.requireNonNull(registryType.getField("INSTANCE").get(null));
            return readCatalog(registry);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Public common knowledge registry unavailable", failure);
        }
    }

    /** Reflection-contract seam for offline malformed/old-registry tests, without a content-mod dependency. */
    static List<Fact> readCatalog(Object registry) {
        Objects.requireNonNull(registry, "common knowledge registry");
        try {
            Object value = registry.getClass().getMethod("publicCommonKnowledge").invoke(registry);
            if (!(value instanceof List<?> entries)) throw new IllegalArgumentException("Invalid common knowledge catalog");
            var facts = new ArrayList<Fact>();
            for (Object entry : entries) {
                Objects.requireNonNull(entry, "common knowledge entry");
                Object id = entry.getClass().getMethod("id").invoke(entry);
                Object title = entry.getClass().getMethod("title").invoke(entry);
                Object keywords = entry.getClass().getMethod("keywords").invoke(entry);
                Object content = entry.getClass().getMethod("content").invoke(entry);
                if (!(id instanceof ResourceLocation location) || !(title instanceof String label)
                        || !(keywords instanceof List<?> aliases) || !(content instanceof String text)
                        || aliases.stream().anyMatch(alias -> !(alias instanceof String)))
                    throw new IllegalArgumentException("Invalid common knowledge entry schema");
                facts.add(new Fact(location.toString(), label, aliases.stream().map(String.class::cast).toList(), text));
            }
            return List.copyOf(facts);
        } catch (InvocationTargetException failure) {
            throw new IllegalStateException("Public common knowledge unavailable", failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Public common knowledge contract unavailable", failure);
        }
    }

    private record Ranked(Fact fact, int source, int strength) { }
    private record SearchText(String words, String compact, Set<String> ids) { }

    private static SearchText searchText(String value) {
        String normalized = normalize(value);
        var matcher = NAMESPACED_TOKEN.matcher(normalized);
        var ids = new HashSet<String>();
        while (matcher.find()) {
            String token = matcher.group().replaceAll("\\.+$", ""); // Sentence-ending periods are punctuation.
            if (RESOURCE_ID.matcher(token).matches()) ids.add(token);
        }
        String words = matcher.replaceAll(" ");
        return new SearchText(words, words.replaceAll("\\s+", ""), Set.copyOf(ids));
    }

    private static int match(SearchText source, String value) {
        String alias = normalize(value);
        if (RESOURCE_ID.matcher(alias).matches()) return source.ids().contains(alias) ? 10_000 + alias.length() : 0;
        String compact = alias.replaceAll("\\s+", "");
        if (compact.codePointCount(0, compact.length()) < 2) {
            // One-syllable item nouns (밀/빵/철) are useful; substring matching would also match 비밀/빵긋/철학.
            // Preserve whitespace and permit only explicit particles before the next noun boundary.
            if (!HANGUL.matcher(compact).matches()) return 0;
            return Pattern.compile("(?<![\\p{L}\\p{N}_:])" + Pattern.quote(compact) + KOREAN_NOUN_PARTICLE
                    + "(?![\\p{L}\\p{N}_:])").matcher(source.words()).find() ? 1 : 0;
        }
        boolean found = HANGUL.matcher(alias).find() ? source.compact().contains(compact)
                : Pattern.compile("(?<![\\p{L}\\p{N}_])" + Pattern.quote(alias) + "(?![\\p{L}\\p{N}_])")
                        .matcher(source.words()).find();
        return found ? compact.length() : 0;
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private static void requireText(String value, int limit, String field) {
        if (value == null || value.isBlank() || value.length() > limit)
            throw new IllegalArgumentException("Invalid common knowledge " + field);
    }
}
