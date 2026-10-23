package com.sande.mythictrpg.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.ai.data.BundledJsonData;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.loading.FMLPaths;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Editable server-local persona data. It is independent of RisuAI and its card format. */
public final class GodPersonaRepository {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String BUNDLED_RESOURCE = "data/mythictrpg/ai/ai-personas.json";
    public static final GodPersonaRepository INSTANCE = new GodPersonaRepository();

    private final Path file = FMLPaths.CONFIGDIR.get().resolve("mythictrpg/ai-personas.json");
    private volatile Map<ResourceLocation, AiDialogueModels.GodPersona> personas = Map.of();

    private GodPersonaRepository() {
    }

    public synchronized void load() {
        RawRepository raw = null;
        try {
            BundledJsonData.ensureServerCopy(file, BUNDLED_RESOURCE, "{\"schemaVersion\":1,\"gods\":{}}\n");
            try (Reader reader = Files.newBufferedReader(file)) {
                raw = GSON.fromJson(reader, RawRepository.class);
            }
            if (raw == null) {
                throw new IllegalArgumentException("AI persona data is empty");
            }
            personas = parse(raw);
        } catch (Exception exception) {
            MythicTrpg.LOGGER.error("Could not load AI personas {}; leaving the persona catalog empty.", file, exception);
            personas = Map.of();
        }
        MythicTrpg.LOGGER.info("Loaded {} AI persona(s) from {}.", personas.size(), file);
    }

    public Optional<AiDialogueModels.GodPersona> find(ResourceLocation godId) {
        return Optional.ofNullable(personas.get(godId));
    }

    public Path file() {
        return file;
    }

    private static Map<ResourceLocation, AiDialogueModels.GodPersona> parse(RawRepository raw) {
        Map<ResourceLocation, AiDialogueModels.GodPersona> parsed = new LinkedHashMap<>();
        if (raw.gods != null) {
            raw.gods.forEach((id, persona) -> {
                try {
                    if (persona == null) {
                        return;
                    }
                    ResourceLocation godId = ResourceLocation.parse(id);
                    parsed.put(godId, new AiDialogueModels.GodPersona(persona.displayName, persona.systemPrompt,
                            persona.background, persona.knowledge, persona.examples));
                } catch (Exception exception) {
                    MythicTrpg.LOGGER.warn("Ignored invalid AI persona '{}': {}", id, exception.getMessage());
                }
            });
        }
        return Map.copyOf(parsed);
    }

    private static final class RawRepository {
        private int schemaVersion;
        private Map<String, RawPersona> gods;
    }

    private static final class RawPersona {
        private String displayName;
        private String systemPrompt;
        private String background;
        private List<String> knowledge = new ArrayList<>();
        private List<String> examples = new ArrayList<>();
    }
}
