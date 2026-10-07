package com.sande.mythictrpg.ai;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Test-only, read-only bridge to the optional MythAI Content Registry mod. Reflection deliberately keeps the
 * production MythicTRPG JAR loadable when the test content JAR is absent.
 */
final class AiTestContentRegistryBridge {
    private static final String MOD_ID = "mythaiaicontent";
    private static final String REGISTRY_CLASS = "com.sande.mythaiaicontent.content.AiContentRegistry";
    private static final String TIER_CLASS = "com.sande.mythaiaicontent.content.RelationshipTier";

    ContentSnapshot load(ResourceLocation godId, String relationshipTier, List<ResourceLocation> participants) {
        Object registry = registry();
        Object profile = optional(call(registry, "findGod", godId))
                .orElseThrow(() -> new IllegalStateException("AI content profile is missing for " + godId));
        Object staticContent = optional(call(registry, "staticContentFor", godId))
                .orElseThrow(() -> new IllegalStateException("Static AI content is missing for " + godId));

        List<Lore> lore = lore(list(call(registry, "loreAvailableTo", godId)));
        List<Example> examples = examples(list(call(registry, "dialogueExamplesAvailableTo", godId)));
        for (Example example : examples(list(read(staticContent, "signatureExamples")))) {
            if (examples.stream().noneMatch(existing -> existing.id().equals(example.id()))) {
                examples.add(example);
            }
        }
        Object tier = relationshipTier(relationshipTier);
        List<String> guidance = strings(list(call(registry, "relationshipGuidanceFor", godId, tier)));
        List<String> socialTags = new ArrayList<>();
        for (ResourceLocation participant : participants) {
            if (!godId.equals(participant)) {
                for (Object tag : list(call(registry, "socialRelationTagsFor", godId, participant))) {
                    socialTags.add(string(read(tag, "tag")));
                }
            }
        }
        long generation = number(read(call(registry, "snapshot"), "generation"));
        return new ContentSnapshot(profile(profile), List.copyOf(lore), List.copyOf(examples), List.copyOf(guidance),
                List.copyOf(new LinkedHashSet<>(socialTags)), generation);
    }

    private static Object registry() {
        if (!ModList.get().isLoaded(MOD_ID)) {
            throw new IllegalStateException("MythAI Content Registry mod (" + MOD_ID + ") is not installed");
        }
        try {
            Class<?> type = Class.forName(REGISTRY_CLASS);
            return type.getField("INSTANCE").get(null);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not access the optional AI content registry", exception);
        }
    }

    private static Object relationshipTier(String tag) {
        try {
            Class<?> type = Class.forName(TIER_CLASS);
            return type.getMethod("fromTag", String.class).invoke(null, tag);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not resolve AI relationship tier " + tag, exception);
        }
    }

    private static Profile profile(Object value) {
        return new Profile(string(read(value, "displayName")), string(read(value, "identity")),
                string(read(value, "description")), strings(list(read(value, "personality"))),
                strings(list(read(value, "values"))), strings(list(read(value, "speechStyles"))),
                strings(list(optionalRead(value, "dialogueGuidelines"))),
                stringGuidanceMap(optionalRead(value, "situationGuidelines")),
                namedGuidanceMap(optionalRead(value, "repetitionGuidelines")),
                strings(list(read(value, "restrictions"))), strings(list(read(value, "characterTags"))));
    }

    private static List<Lore> lore(List<Object> values) {
        List<Lore> result = new ArrayList<>();
        for (Object value : values) {
            List<String> levels = new ArrayList<>();
            for (Object level : list(read(value, "accessibleLevels"))) {
                levels.add("L" + number(read(level, "level")) + ": " + string(read(level, "content")));
            }
            result.add(new Lore(string(read(value, "id")), string(read(value, "title")),
                    string(read(value, "secrecy")), number(read(value, "knowledgeLevel")), List.copyOf(levels)));
        }
        return result.stream().sorted(Comparator.comparing(Lore::id)).toList();
    }

    private static List<Example> examples(List<Object> values) {
        List<Example> result = new ArrayList<>();
        for (Object value : values) {
            List<ExampleTurn> turns = new ArrayList<>();
            for (Object turn : list(read(value, "dialogue"))) {
                turns.add(new ExampleTurn(string(read(turn, "role")), string(read(turn, "text"))));
            }
            result.add(new Example(string(read(value, "id")), strings(list(read(value, "tags"))), List.copyOf(turns)));
        }
        return result;
    }

    private static Object call(Object target, String name, Object... arguments) {
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != arguments.length) {
                continue;
            }
            Class<?>[] types = method.getParameterTypes();
            boolean matches = true;
            for (int index = 0; index < types.length; index++) {
                if (arguments[index] != null && !types[index].isInstance(arguments[index])) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                try {
                    return method.invoke(target, arguments);
                } catch (IllegalAccessException exception) {
                    throw new IllegalStateException("AI content registry method is inaccessible: " + name, exception);
                } catch (InvocationTargetException exception) {
                    Throwable cause = exception.getCause();
                    throw new IllegalStateException("AI content registry call failed: " + name,
                            cause == null ? exception : cause);
                }
            }
        }
        throw new IllegalStateException("AI content registry method is unavailable: " + name);
    }

    private static Object read(Object target, String name) {
        return call(target, name);
    }

    /** New profile fields remain optional so a test server can still inspect older content JARs safely. */
    private static Object optionalRead(Object target, String name) {
        try {
            return call(target, name);
        } catch (IllegalStateException ignored) {
            return List.of();
        }
    }

    private static Optional<Object> optional(Object value) {
        return value instanceof Optional<?> optional ? optional.map(item -> (Object) item) : Optional.empty();
    }

    private static List<Object> list(Object value) {
        if (!(value instanceof Iterable<?> iterable)) {
            return List.of();
        }
        List<Object> result = new ArrayList<>();
        iterable.forEach(result::add);
        return result;
    }

    private static List<String> strings(List<Object> values) {
        return values.stream().map(AiTestContentRegistryBridge::string).filter(value -> !value.isBlank()).distinct().toList();
    }

    private static Map<String, List<String>> stringGuidanceMap(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            String tag = string(entry.getKey());
            if (!tag.startsWith("S_")) {
                continue;
            }
            List<String> guidelines = strings(list(entry.getValue()));
            if (!guidelines.isEmpty()) {
                result.put(tag, guidelines);
            }
        }
        return Map.copyOf(result);
    }

    private static Map<String, List<String>> namedGuidanceMap(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            String key = string(entry.getKey());
            if (!key.matches("[A-Z][A-Z0-9_]{1,63}")) {
                continue;
            }
            List<String> guidelines = strings(list(entry.getValue()));
            if (!guidelines.isEmpty()) {
                result.put(key, guidelines);
            }
        }
        return Map.copyOf(result);
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    record ContentSnapshot(Profile profile, List<Lore> lore, List<Example> examples, List<String> relationshipGuidance,
            List<String> socialRelationTags, long generation) {
    }

    record Profile(String displayName, String identity, String description, List<String> personality, List<String> values,
            List<String> speechStyles, List<String> dialogueGuidelines, Map<String, List<String>> situationGuidelines,
            Map<String, List<String>> repetitionGuidelines,
            List<String> restrictions,
            List<String> characterTags) {
    }

    record Lore(String id, String title, String secrecy, int knowledgeLevel, List<String> accessibleLevels) {
    }

    record Example(String id, List<String> tags, List<ExampleTurn> dialogue) {
    }

    record ExampleTurn(String role, String text) {
    }
}
