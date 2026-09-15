package com.sande.mythictrpg.data.god;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.condition.engine.ConditionChangeDispatcher;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.neoforge.event.AddReloadListenerEvent;

import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class GodDefinitionManager extends SimplePreparableReloadListener<GodDefinitionManager.Prepared>
        implements GodDefinitionRepository {
    public static final GodDefinitionManager INSTANCE = new GodDefinitionManager();
    private static final FileToIdConverter CONVERTER = FileToIdConverter.json("mythictrpg/gods");

    private volatile ProgressionSnapshot snapshot = new ProgressionSnapshot(
            Map.of(), GodUnlockDependencyIndex.empty(), GodAppearanceIndex.empty(), GodCategoryIndex.empty(), 0);

    private GodDefinitionManager() {
    }

    public void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(this);
    }

    @Override
    public Map<ResourceLocation, GodDefinition> definitions() {
        return snapshot.definitions();
    }

    public long generation() {
        return snapshot.generation();
    }

    public GodUnlockDependencyIndex unlockIndex() {
        return snapshot.unlockIndex();
    }

    public GodAppearanceIndex appearanceIndex() {
        return snapshot.appearanceIndex();
    }

    public ProgressionSnapshot progressionSnapshot() {
        return snapshot;
    }

    @Override
    protected Prepared prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
        Map<ResourceLocation, GodDefinition> parsed = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();

        CONVERTER.listMatchingResources(resourceManager).entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> parseResource(entry.getKey(), entry.getValue(), parsed, errors));

        if (!errors.isEmpty()) {
            throw new IllegalStateException("Rejected God definition reload with " + errors.size()
                    + " error(s); the previous cache remains active");
        }
        Map<ResourceLocation, GodDefinition> definitions = Map.copyOf(parsed);
        return new Prepared(definitions, GodUnlockDependencyIndex.build(definitions),
                GodAppearanceIndex.build(definitions));
    }

    private static void parseResource(ResourceLocation file, Resource resource,
            Map<ResourceLocation, GodDefinition> parsed, List<String> errors) {
        ResourceLocation id = CONVERTER.fileToId(file);
        try (Reader reader = resource.openAsReader()) {
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonObject()) {
                throw new IllegalArgumentException("Root value must be a JSON object");
            }
            parsed.put(id, GodDefinitionSchema.parse(id, root.getAsJsonObject()));
        } catch (Exception exception) {
            String message = "God definition " + file + " (ID " + id + ") from pack '"
                    + resource.sourcePackId() + "' failed: " + exception.getMessage();
            errors.add(message);
            MythicTrpg.LOGGER.error(message, exception);
        }
    }

    @Override
    protected void apply(Prepared prepared, ResourceManager resourceManager, ProfilerFiller profiler) {
        long generation = snapshot.generation() + 1;
        snapshot = new ProgressionSnapshot(prepared.definitions(), prepared.unlockIndex(),
                prepared.appearanceIndex(), GodCategoryIndex.build(prepared.definitions()), generation);
        MythicTrpg.LOGGER.info("Loaded {} God definitions (generation {}).", prepared.definitions().size(), generation);
        ConditionChangeDispatcher.publishDefinitionsReloaded();
    }

    protected record Prepared(Map<ResourceLocation, GodDefinition> definitions,
            GodUnlockDependencyIndex unlockIndex, GodAppearanceIndex appearanceIndex) {
        protected Prepared(Map<ResourceLocation, GodDefinition> definitions,
                GodUnlockDependencyIndex unlockIndex) {
            this(definitions, unlockIndex, GodAppearanceIndex.build(definitions));
        }
    }

    public record ProgressionSnapshot(Map<ResourceLocation, GodDefinition> definitions,
            GodUnlockDependencyIndex unlockIndex, GodAppearanceIndex appearanceIndex,
            GodCategoryIndex categoryIndex, long generation) {
        public ProgressionSnapshot {
            definitions = Map.copyOf(definitions);
        }

        public ProgressionSnapshot(Map<ResourceLocation, GodDefinition> definitions,
                GodUnlockDependencyIndex unlockIndex, GodAppearanceIndex appearanceIndex, long generation) {
            this(definitions, unlockIndex, appearanceIndex, GodCategoryIndex.build(definitions), generation);
        }
    }
}
