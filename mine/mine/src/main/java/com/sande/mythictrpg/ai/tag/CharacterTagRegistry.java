package com.sande.mythictrpg.ai.tag;

import com.google.gson.Gson;
import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.ai.data.BundledJsonData;
import net.neoforged.fml.loading.FMLPaths;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Loads standard character tag definitions from editable server-local JSON. */
public final class CharacterTagRegistry {
    private static final Gson GSON = new Gson();
    private static final String BUNDLED_RESOURCE = "data/mythictrpg/ai/character-tags.json";
    public static final CharacterTagRegistry INSTANCE = new CharacterTagRegistry();

    private final Path file = FMLPaths.CONFIGDIR.get().resolve("mythictrpg/character-tags.json");
    private volatile Map<String, CharacterTagDefinition> definitions = Map.of();

    private CharacterTagRegistry() {
    }

    public synchronized void load() {
        try {
            BundledJsonData.ensureServerCopy(file, BUNDLED_RESOURCE);
            try (Reader reader = Files.newBufferedReader(file)) {
                RawRegistry raw = GSON.fromJson(reader, RawRegistry.class);
                definitions = parse(raw);
            }
        } catch (Exception exception) {
            MythicTrpg.LOGGER.error("Could not load character tag registry {}", file, exception);
            definitions = Map.of();
        }
        MythicTrpg.LOGGER.info("Loaded {} character tag definitions from {}.", definitions.size(), file);
    }

    public Optional<CharacterTagDefinition> find(String tag) {
        return Optional.ofNullable(definitions.get(tag));
    }

    public Map<String, CharacterTagDefinition> all() {
        return definitions;
    }

    public Path file() {
        return file;
    }

    private static Map<String, CharacterTagDefinition> parse(RawRegistry raw) {
        if (raw == null || raw.categories == null) {
            throw new IllegalArgumentException("character tag registry requires categories");
        }
        Map<String, CharacterTagDefinition> parsed = new LinkedHashMap<>();
        raw.categories.forEach((categoryName, tags) -> CharacterTagCategory.parse(categoryName).ifPresentOrElse(category -> {
            if (tags == null) {
                return;
            }
            for (String tag : tags) {
                CharacterTagDefinition definition = new CharacterTagDefinition(tag, category);
                CharacterTagDefinition previous = parsed.putIfAbsent(definition.name(), definition);
                if (previous != null && previous.category() != category) {
                    MythicTrpg.LOGGER.warn("Character tag '{}' appears in both {} and {}; keeping {}.",
                            definition.name(), previous.category(), category, previous.category());
                }
            }
        }, () -> MythicTrpg.LOGGER.warn("Ignoring unknown character tag category '{}'", categoryName)));
        return Map.copyOf(parsed);
    }

    private static final class RawRegistry {
        private int schemaVersion;
        private Map<String, List<String>> categories;
    }
}
