package com.sande.mythictrpg.ai.tag;

import com.google.gson.Gson;
import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.ai.data.BundledJsonData;
import net.neoforged.fml.loading.FMLPaths;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Data-driven, deliberately narrow mapping from character traits to example speech-style tags. */
public final class CharacterStyleTagMapper {
    private static final Gson GSON = new Gson();
    private static final String BUNDLED_RESOURCE = "data/mythictrpg/ai/character-style-mappings.json";
    public static final CharacterStyleTagMapper INSTANCE = new CharacterStyleTagMapper();

    private final Path file = FMLPaths.CONFIGDIR.get().resolve("mythictrpg/character-style-mappings.json");
    private volatile Map<String, Set<ExampleStyleTag>> mappings = Map.of();

    private CharacterStyleTagMapper() {
    }

    public synchronized void load() {
        try {
            BundledJsonData.ensureServerCopy(file, BUNDLED_RESOURCE);
            try (Reader reader = Files.newBufferedReader(file)) {
                mappings = parse(GSON.fromJson(reader, RawMappings.class));
            }
        } catch (Exception exception) {
            MythicTrpg.LOGGER.error("Could not load character-to-style mappings {}", file, exception);
            mappings = Map.of();
        }
        MythicTrpg.LOGGER.info("Loaded {} character-to-example-style mappings from {}.", mappings.size(), file);
    }

    public ExampleStyleContext map(NpcTagProfile profile) {
        EnumSet<ExampleStyleTag> styles = EnumSet.noneOf(ExampleStyleTag.class);
        for (List<String> tags : profile.classification().asMap().values()) {
            for (String tag : tags) {
                styles.addAll(mappings.getOrDefault(tag, Set.of()));
            }
        }
        return new ExampleStyleContext(styles,
                profile.classification().tags(CharacterTagCategory.HUMAN_ATTITUDE));
    }

    public Path file() {
        return file;
    }

    private static Map<String, Set<ExampleStyleTag>> parse(RawMappings raw) {
        if (raw == null || raw.mappings == null) {
            throw new IllegalArgumentException("character style mappings require mappings");
        }
        Map<String, Set<ExampleStyleTag>> parsed = new LinkedHashMap<>();
        for (RawMapping mapping : raw.mappings) {
            if (mapping == null || mapping.characterTag == null || mapping.styleTags == null) {
                continue;
            }
            EnumSet<ExampleStyleTag> styles = EnumSet.noneOf(ExampleStyleTag.class);
            for (String rawStyle : mapping.styleTags) {
                try {
                    styles.add(ExampleStyleTag.valueOf(rawStyle));
                } catch (IllegalArgumentException exception) {
                    throw new IllegalArgumentException("Unknown example style tag '" + rawStyle + "'", exception);
                }
            }
            if (!styles.isEmpty()) {
                parsed.put(mapping.characterTag.trim(), Set.copyOf(styles));
            }
        }
        return Map.copyOf(parsed);
    }

    private static final class RawMappings {
        private int schemaVersion;
        private List<RawMapping> mappings;
    }

    private static final class RawMapping {
        private String characterTag;
        private List<String> styleTags;
    }
}
