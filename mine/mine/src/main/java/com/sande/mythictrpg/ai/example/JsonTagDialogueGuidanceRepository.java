package com.sande.mythictrpg.ai.example;

import com.google.gson.Gson;
import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.ai.data.BundledJsonData;
import net.neoforged.fml.loading.FMLPaths;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Server-editable library of independently composable tag rules and concise example turns. */
public final class JsonTagDialogueGuidanceRepository implements TagDialogueGuidanceRepository {
    private static final Gson GSON = new Gson();
    private static final String BUNDLED_RESOURCE = "data/mythictrpg/ai/dialogue-tag-guidance.json";
    public static final JsonTagDialogueGuidanceRepository INSTANCE = new JsonTagDialogueGuidanceRepository();

    private final Path file = FMLPaths.CONFIGDIR.get().resolve("mythictrpg/dialogue-tag-guidance.json");
    private volatile Map<DialogueExampleTag, TagDialogueGuidance> guidance = Map.of();

    private JsonTagDialogueGuidanceRepository() {
    }

    public synchronized void load() {
        try {
            BundledJsonData.ensureServerCopy(file, BUNDLED_RESOURCE);
            try (Reader reader = Files.newBufferedReader(file)) {
                guidance = parse(GSON.fromJson(reader, RawStore.class));
            }
        } catch (Exception exception) {
            MythicTrpg.LOGGER.error("Could not load dialogue tag guidance {}", file, exception);
            guidance = Map.of();
        }
        MythicTrpg.LOGGER.info("Loaded {} dialogue tag guidance profile(s) from {}.", guidance.size(), file);
    }

    @Override
    public Optional<TagDialogueGuidance> find(DialogueExampleTag tag) {
        return Optional.ofNullable(guidance.get(tag));
    }

    @Override
    public List<TagDialogueGuidance> all() {
        return List.copyOf(guidance.values());
    }

    public Path file() {
        return file;
    }

    private static Map<DialogueExampleTag, TagDialogueGuidance> parse(RawStore raw) {
        if (raw == null || raw.guidance == null) {
            throw new IllegalArgumentException("Dialogue tag guidance requires guidance");
        }
        Map<DialogueExampleTag, TagDialogueGuidance> parsed = new EnumMap<>(DialogueExampleTag.class);
        for (RawGuidance entry : raw.guidance) {
            try {
                if (entry == null) {
                    throw new IllegalArgumentException("Dialogue tag guidance entry is null");
                }
                DialogueExampleTag tag = DialogueExampleTag.valueOf(required(entry.tag, "tag").toUpperCase(Locale.ROOT));
                TagDialogueGuidance profile = new TagDialogueGuidance(tag, entry.priority, entry.rules,
                        turns(entry.dialogue));
                if (parsed.putIfAbsent(tag, profile) != null) {
                    throw new IllegalArgumentException("Duplicate dialogue tag guidance " + tag);
                }
            } catch (Exception exception) {
                MythicTrpg.LOGGER.warn("Ignored invalid dialogue tag guidance: {}", exception.getMessage());
            }
        }
        return Map.copyOf(parsed);
    }

    private static List<DialogueExampleTurn> turns(List<RawTurn> rawTurns) {
        if (rawTurns == null) {
            return List.of();
        }
        List<DialogueExampleTurn> parsed = new ArrayList<>();
        for (RawTurn turn : rawTurns) {
            if (turn == null) {
                throw new IllegalArgumentException("Dialogue tag guidance contains a null turn");
            }
            parsed.add(new DialogueExampleTurn(DialogueExampleRole.valueOf(required(turn.role, "dialogue role")
                    .toUpperCase(Locale.ROOT)), required(turn.text, "dialogue text")));
        }
        return List.copyOf(parsed);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Dialogue tag guidance " + name + " must not be blank");
        }
        return value.trim();
    }

    private static final class RawStore {
        private int schemaVersion;
        private List<RawGuidance> guidance = new ArrayList<>();
    }

    private static final class RawGuidance {
        private String tag;
        private int priority;
        private List<String> rules;
        private List<RawTurn> dialogue;
    }

    private static final class RawTurn {
        private String role;
        private String text;
    }
}
