package com.sande.mythictrpg.ai.knowledge;

import com.google.gson.Gson;
import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.ai.data.BundledJsonData;
import net.neoforged.fml.loading.FMLPaths;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Editable local world-knowledge data. Entries remain unusable until an NPC-specific disclosure check approves them. */
public final class JsonKnowledgeRepository implements KnowledgeRepository {
    private static final Gson GSON = new Gson();
    private static final String BUNDLED_RESOURCE = "data/mythictrpg/ai/ai-knowledge.json";
    public static final JsonKnowledgeRepository INSTANCE = new JsonKnowledgeRepository();

    private final Path file = FMLPaths.CONFIGDIR.get().resolve("mythictrpg/ai-knowledge.json");
    private volatile List<KnowledgeEntry> entries = List.of();

    private JsonKnowledgeRepository() {
    }

    public synchronized void load() {
        try {
            BundledJsonData.ensureServerCopy(file, BUNDLED_RESOURCE);
            try (Reader reader = Files.newBufferedReader(file)) {
                entries = parse(GSON.fromJson(reader, RawStore.class));
            }
        } catch (Exception exception) {
            MythicTrpg.LOGGER.error("Could not load AI knowledge data {}", file, exception);
            entries = List.of();
        }
        MythicTrpg.LOGGER.info("Loaded {} AI knowledge entry(s) from {}.", entries.size(), file);
    }

    @Override
    public List<KnowledgeEntry> all() {
        return entries;
    }

    public Path file() {
        return file;
    }

    private static List<KnowledgeEntry> parse(RawStore raw) {
        if (raw == null || raw.entries == null) {
            throw new IllegalArgumentException("Knowledge data requires entries");
        }
        Map<String, KnowledgeEntry> parsed = new LinkedHashMap<>();
        for (RawEntry entry : raw.entries) {
            try {
                KnowledgeEntry value = new KnowledgeEntry(entry.id, entry.title, entry.content, entry.known_by,
                        KnowledgeSecrecy.valueOf(entry.secrecy.trim().toUpperCase(Locale.ROOT)),
                        entry.category == null ? inferCategory(entry.id) : entry.category,
                        entry.parent_id, entry.keys == null ? List.of() : entry.keys,
                        entry.priority == null ? 1 : entry.priority,
                        entry.always_active != null && entry.always_active);
                if (parsed.putIfAbsent(value.id(), value) != null) {
                    throw new IllegalArgumentException("Duplicate knowledge ID " + value.id());
                }
            } catch (Exception exception) {
                MythicTrpg.LOGGER.warn("Ignored invalid AI knowledge entry: {}", exception.getMessage());
            }
        }
        return List.copyOf(parsed.values());
    }

    private static final class RawStore {
        private int schemaVersion;
        private List<RawEntry> entries = new ArrayList<>();
    }

    private static final class RawEntry {
        private String id;
        private String title;
        private String content;
        private List<String> known_by;
        private String secrecy;
        private String category;
        private String parent_id;
        private List<String> keys;
        private Integer priority;
        private Boolean always_active;
    }

    private static String inferCategory(String id) {
        return id != null && id.startsWith("greek_") ? "PERSON" : "WORLD";
    }
}
