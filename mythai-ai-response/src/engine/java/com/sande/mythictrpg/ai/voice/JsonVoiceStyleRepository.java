package com.sande.mythictrpg.ai.voice;

import com.google.gson.Gson;
import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.ai.data.BundledJsonData;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.loading.FMLPaths;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Server-editable ID library for NPC voice guidance, kept separate from character lore and example retrieval tags. */
public final class JsonVoiceStyleRepository {
    private static final Gson GSON = new Gson();
    private static final String BUNDLED_RESOURCE = "data/mythictrpg/ai/voice-styles.json";
    public static final JsonVoiceStyleRepository INSTANCE = new JsonVoiceStyleRepository();

    private final Path file = FMLPaths.CONFIGDIR.get().resolve("mythictrpg/voice-styles.json");
    private volatile Map<ResourceLocation, VoiceStyleProfile> styles = Map.of();

    private JsonVoiceStyleRepository() {
    }

    public synchronized void load() {
        try {
            BundledJsonData.ensureServerCopy(file, BUNDLED_RESOURCE);
            try (Reader reader = Files.newBufferedReader(file)) {
                styles = parse(GSON.fromJson(reader, RawStore.class));
            }
        } catch (Exception exception) {
            MythicTrpg.LOGGER.error("Could not load voice styles {}", file, exception);
            styles = Map.of();
        }
        MythicTrpg.LOGGER.info("Loaded {} NPC voice style profile(s) from {}.", styles.size(), file);
    }

    public Optional<VoiceStyleProfile> find(ResourceLocation id) {
        return Optional.ofNullable(styles.get(id));
    }

    /** Resolves only explicitly assigned IDs, preserving author-defined priority and removing duplicate references. */
    public List<VoiceStyleProfile> resolve(List<ResourceLocation> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<ResourceLocation> unique = new LinkedHashSet<>(ids);
        List<VoiceStyleProfile> resolved = new ArrayList<>();
        for (ResourceLocation id : unique) {
            VoiceStyleProfile profile = styles.get(id);
            if (profile != null) {
                resolved.add(profile);
            }
        }
        return List.copyOf(resolved);
    }

    public Path file() {
        return file;
    }

    private static Map<ResourceLocation, VoiceStyleProfile> parse(RawStore raw) {
        if (raw == null || raw.styles == null) {
            throw new IllegalArgumentException("Voice style data requires styles");
        }
        Map<ResourceLocation, VoiceStyleProfile> parsed = new LinkedHashMap<>();
        for (RawStyle rawStyle : raw.styles) {
            try {
                if (rawStyle == null) {
                    throw new IllegalArgumentException("Voice style entry is null");
                }
                VoiceStyleProfile profile = new VoiceStyleProfile(ResourceLocation.parse(required(rawStyle.id, "id")),
                        rawStyle.guidance);
                if (parsed.putIfAbsent(profile.id(), profile) != null) {
                    throw new IllegalArgumentException("Duplicate voice style ID " + profile.id());
                }
            } catch (Exception exception) {
                MythicTrpg.LOGGER.warn("Ignored invalid voice style: {}", exception.getMessage());
            }
        }
        return Map.copyOf(parsed);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Voice style " + name + " must not be blank");
        }
        return value.trim();
    }

    private static final class RawStore {
        private int schemaVersion;
        private List<RawStyle> styles = new ArrayList<>();
    }

    private static final class RawStyle {
        private String id;
        private List<String> guidance;
    }
}
