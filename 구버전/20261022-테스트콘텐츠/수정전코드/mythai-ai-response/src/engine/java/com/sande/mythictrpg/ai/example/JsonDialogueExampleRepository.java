package com.sande.mythictrpg.ai.example;

import com.google.gson.Gson;
import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.ai.data.BundledJsonData;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.loading.FMLPaths;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Server-editable JSON implementation of the shared dialogue-example library. */
public final class JsonDialogueExampleRepository implements DialogueExampleRepository {
    private static final Gson GSON = new Gson();
    private static final String BUNDLED_RESOURCE = "data/mythictrpg/ai/dialogue-examples.json";
    public static final JsonDialogueExampleRepository INSTANCE = new JsonDialogueExampleRepository();

    private final Path file = FMLPaths.CONFIGDIR.get().resolve("mythictrpg/dialogue-examples.json");
    private volatile List<DialogueExample> examples = List.of();

    private JsonDialogueExampleRepository() {
    }

    public synchronized void load() {
        try {
            BundledJsonData.ensureServerCopy(file, BUNDLED_RESOURCE);
            try (Reader reader = Files.newBufferedReader(file)) {
                examples = parse(GSON.fromJson(reader, RawStore.class));
            }
        } catch (Exception exception) {
            MythicTrpg.LOGGER.error("Could not load dialogue examples {}", file, exception);
            examples = List.of();
        }
        MythicTrpg.LOGGER.info("Loaded {} shared dialogue example(s) from {}.", examples.size(), file);
    }

    @Override
    public List<DialogueExample> all() {
        return examples;
    }

    public Path file() {
        return file;
    }

    private static List<DialogueExample> parse(RawStore raw) {
        if (raw == null || raw.examples == null) {
            throw new IllegalArgumentException("Dialogue example data requires examples");
        }
        Map<String, DialogueExample> parsed = new LinkedHashMap<>();
        for (RawExample entry : raw.examples) {
            try {
                DialogueExample example = new DialogueExample(entry.exampleId, tags(entry.tags), knownBy(entry.known_by),
                        turns(entry.dialogue));
                if (parsed.putIfAbsent(example.exampleId(), example) != null) {
                    throw new IllegalArgumentException("Duplicate dialogue example ID " + example.exampleId());
                }
            } catch (Exception exception) {
                MythicTrpg.LOGGER.warn("Ignored invalid dialogue example: {}", exception.getMessage());
            }
        }
        return List.copyOf(parsed.values());
    }

    private static EnumSet<DialogueExampleTag> tags(List<String> rawTags) {
        if (rawTags == null || rawTags.isEmpty()) {
            throw new IllegalArgumentException("Dialogue example requires tags");
        }
        EnumSet<DialogueExampleTag> parsed = EnumSet.noneOf(DialogueExampleTag.class);
        for (String tag : rawTags) {
            parsed.add(DialogueExampleTag.valueOf(required(tag, "tag").toUpperCase(Locale.ROOT)));
        }
        return parsed;
    }

    private static List<DialogueExampleTurn> turns(List<RawTurn> rawTurns) {
        if (rawTurns == null) {
            throw new IllegalArgumentException("Dialogue example requires dialogue");
        }
        List<DialogueExampleTurn> parsed = new ArrayList<>();
        for (RawTurn turn : rawTurns) {
            if (turn == null) {
                throw new IllegalArgumentException("Dialogue example contains a null turn");
            }
            parsed.add(new DialogueExampleTurn(DialogueExampleRole.valueOf(required(turn.role, "role")
                    .toUpperCase(Locale.ROOT)), required(turn.text, "text")));
        }
        return parsed;
    }

    private static java.util.Set<ResourceLocation> knownBy(List<String> rawIds) {
        if (rawIds == null || rawIds.isEmpty()) {
            return java.util.Set.of();
        }
        java.util.LinkedHashSet<ResourceLocation> parsed = new java.util.LinkedHashSet<>();
        for (String rawId : rawIds) {
            try {
                parsed.add(ResourceLocation.parse(required(rawId, "known_by NPC ID")));
            } catch (Exception exception) {
                throw new IllegalArgumentException("Dialogue example has an invalid known_by NPC ID: " + rawId,
                        exception);
            }
        }
        return java.util.Set.copyOf(parsed);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Dialogue example " + name + " must not be blank");
        }
        return value.trim();
    }

    private static final class RawStore {
        private int schemaVersion;
        private List<RawExample> examples = new ArrayList<>();
    }

    private static final class RawExample {
        private String exampleId;
        private List<String> tags;
        private List<String> known_by;
        private List<RawTurn> dialogue;
    }

    private static final class RawTurn {
        private String role;
        private String text;
    }
}
