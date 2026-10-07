package com.sande.mythictrpg.ai.tag;

import com.google.gson.Gson;
import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.ai.data.BundledJsonData;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.loading.FMLPaths;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Editable NPC tag profiles keyed by the same resource location as GodPersona. */
public final class NpcCharacterTagRepository {
    private static final Gson GSON = new Gson();
    private static final String BUNDLED_RESOURCE = "data/mythictrpg/ai/npc-character-tags.json";
    public static final NpcCharacterTagRepository INSTANCE = new NpcCharacterTagRepository();

    private final Path file = FMLPaths.CONFIGDIR.get().resolve("mythictrpg/npc-character-tags.json");
    private volatile Map<ResourceLocation, NpcTagProfile> profiles = Map.of();

    private NpcCharacterTagRepository() {
    }

    public synchronized void load() {
        try {
            BundledJsonData.ensureServerCopy(file, BUNDLED_RESOURCE);
            try (Reader reader = Files.newBufferedReader(file)) {
                profiles = parse(GSON.fromJson(reader, RawRepository.class));
            }
        } catch (Exception exception) {
            MythicTrpg.LOGGER.error("Could not load NPC character tags {}", file, exception);
            profiles = Map.of();
        }
        MythicTrpg.LOGGER.info("Loaded {} NPC character tag profile(s) from {}.", profiles.size(), file);
    }

    public Optional<NpcTagProfile> find(ResourceLocation npcId) {
        return Optional.ofNullable(profiles.get(npcId));
    }

    public Map<ResourceLocation, NpcTagProfile> all() {
        return profiles;
    }

    public Path file() {
        return file;
    }

    private static Map<ResourceLocation, NpcTagProfile> parse(RawRepository raw) {
        if (raw == null || raw.npcs == null) {
            throw new IllegalArgumentException("NPC character tag data requires npcs");
        }
        NpcTagClassifier classifier = new NpcTagClassifier(CharacterTagRegistry.INSTANCE);
        Map<ResourceLocation, NpcTagProfile> parsed = new LinkedHashMap<>();
        raw.npcs.forEach((rawId, entry) -> {
            try {
                ResourceLocation npcId = ResourceLocation.parse(rawId);
                List<String> rawTags = entry == null || entry.tags == null ? List.of() : List.copyOf(entry.tags);
                NpcTagClassification explicit = explicitClassification(entry == null ? null : entry.classification);
                NpcTagProfile profile = new NpcTagProfile(rawTags, explicit, classifier.classify(rawTags, explicit));
                parsed.put(npcId, profile);
            } catch (Exception exception) {
                MythicTrpg.LOGGER.warn("Ignored NPC character tag profile '{}': {}", rawId, exception.getMessage());
            }
        });
        return Map.copyOf(parsed);
    }

    private static NpcTagClassification explicitClassification(Map<String, List<String>> raw) {
        if (raw == null || raw.isEmpty()) {
            return NpcTagClassification.empty();
        }
        Map<CharacterTagCategory, List<String>> parsed = new EnumMap<>(CharacterTagCategory.class);
        raw.forEach((categoryName, tags) -> CharacterTagCategory.parse(categoryName).ifPresentOrElse(category ->
                parsed.put(category, tags == null ? List.of() : new ArrayList<>(tags)), () ->
                MythicTrpg.LOGGER.warn("Ignoring unknown explicit NPC tag category '{}'", categoryName)));
        return new NpcTagClassification(parsed);
    }

    private static final class RawRepository {
        private int schemaVersion;
        private Map<String, RawNpcProfile> npcs;
    }

    private static final class RawNpcProfile {
        private List<String> tags;
        private Map<String, List<String>> classification;
    }
}
