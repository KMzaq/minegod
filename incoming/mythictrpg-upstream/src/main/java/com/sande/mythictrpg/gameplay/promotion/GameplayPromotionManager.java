package com.sande.mythictrpg.gameplay.promotion;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.gameplay.observation.GameplayObservationTypes;
import com.sande.mythictrpg.gameplay.sampling.VanillaStatisticSource;
import com.sande.mythictrpg.gameplay.sampling.WatchedMetricDefinition;
import com.sande.mythictrpg.gameplay.sampling.WatchedMetricSnapshot;
import com.sande.mythictrpg.interaction.api.InteractionSignalType;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.neoforge.event.AddReloadListenerEvent;

import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class GameplayPromotionManager
        extends SimplePreparableReloadListener<GameplayPromotionManager.Prepared> {
    public static final GameplayPromotionManager INSTANCE = new GameplayPromotionManager();
    public static final int MAX_PROMOTIONS = 512;
    private static final FileToIdConverter CONVERTER =
            FileToIdConverter.json("mythictrpg/gameplay_promotions");
    private static final Map<ResourceLocation, GameplayPromotionAdapter<?, ?>> ADAPTERS = Map.of(
            GameplayObservationTypes.ANIMAL_BRED.id(), AnimalBredPromotionAdapter.INSTANCE,
            GameplayObservationTypes.ANIMAL_FED.id(), AnimalFedPromotionAdapter.INSTANCE,
            GameplayObservationTypes.BLOCK_BROKEN.id(), BlockBrokenPromotionAdapter.INSTANCE,
            GameplayObservationTypes.MATURE_CROP_HARVESTED.id(),
            MatureCropHarvestPromotionAdapter.INSTANCE,
            GameplayObservationTypes.ENTITY_KILLED.id(), EntityKilledPromotionAdapter.INSTANCE,
            GameplayObservationTypes.ITEM_FIRST_OBTAINED.id(),
            ItemFirstObtainedPromotionAdapter.INSTANCE,
            GameplayObservationTypes.PLAYER_DIED.id(), PlayerDiedPromotionAdapter.INSTANCE,
            GameplayObservationTypes.VANILLA_STAT_THRESHOLD_CROSSED.id(),
            VanillaStatThresholdPromotionAdapter.INSTANCE);

    private volatile GameplayPromotionSnapshot snapshot = GameplayPromotionSnapshot.empty(0);

    private GameplayPromotionManager() {
    }

    public void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(this);
    }

    public GameplayPromotionSnapshot snapshot() {
        return snapshot;
    }

    public WatchedMetricSnapshot watchedMetrics() {
        return snapshot.watchedMetrics();
    }

    @Override
    protected Prepared prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
        List<GameplayPromotionDefinition> parsed = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        CONVERTER.listMatchingResources(resourceManager).entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> parseResource(entry.getKey(), entry.getValue(), parsed, errors));
        if (!errors.isEmpty()) {
            throw new IllegalStateException("Rejected gameplay promotion reload with " + errors.size()
                    + " error(s); the previous snapshot remains active");
        }
        try {
            return compile(parsed);
        } catch (IllegalArgumentException exception) {
            MythicTrpg.LOGGER.error("Gameplay promotion index validation failed: {}", exception.getMessage());
            throw new IllegalStateException("Rejected gameplay promotion reload; the previous snapshot remains active",
                    exception);
        }
    }

    private static void parseResource(ResourceLocation file, Resource resource,
            List<GameplayPromotionDefinition> parsed, List<String> errors) {
        ResourceLocation id = CONVERTER.fileToId(file);
        try (Reader reader = resource.openAsReader()) {
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonObject()) {
                throw new IllegalArgumentException("Root value must be a JSON object");
            }
            parsed.add(GameplayPromotionSchema.parse(id, root.getAsJsonObject(), ADAPTERS));
        } catch (Exception exception) {
            String message = "Gameplay promotion " + file + " (ID " + id + ") from pack '"
                    + resource.sourcePackId() + "' failed: " + exception.getMessage();
            errors.add(message);
            MythicTrpg.LOGGER.error(message, exception);
        }
    }

    static Prepared compile(List<GameplayPromotionDefinition> definitions) {
        validatePromotionCount(definitions.size());
        Map<ResourceLocation, GameplayPromotionDefinition> byId = new LinkedHashMap<>();
        for (GameplayPromotionDefinition definition : definitions) {
            GameplayPromotionDefinition duplicate = byId.putIfAbsent(definition.id(), definition);
            if (duplicate != null) {
                throw new IllegalArgumentException("Duplicate gameplay promotion ID: " + definition.id());
            }
        }
        List<GameplayPromotionDefinition> ordered = definitions.stream()
                .sorted(GameplayPromotionDefinition.ORDERING).toList();
        Map<ResourceLocation, GameplayPromotionDefinition> immutable = Map.copyOf(byId);
        return new Prepared(immutable, ordered, GameplayPromotionIndex.build(ordered, ADAPTERS),
                compileWatchedMetrics(ordered), GameplayPromotionSignalTypeCompiler.compile(ordered));
    }

    static void validatePromotionCount(int count) {
        if (count < 0 || count > MAX_PROMOTIONS) {
            throw new IllegalArgumentException("Gameplay promotion count must be between 0 and "
                    + MAX_PROMOTIONS + ": " + count);
        }
    }

    private static WatchedMetricSnapshot compileWatchedMetrics(
            List<GameplayPromotionDefinition> definitions) {
        List<WatchedMetricDefinition> watches = definitions.stream()
                .filter(definition -> definition.matcher() instanceof VanillaStatThresholdPromotionMatcher)
                .map(definition -> {
                    VanillaStatThresholdPromotionMatcher matcher =
                            (VanillaStatThresholdPromotionMatcher) definition.matcher();
                    return WatchedMetricDefinition.milestone(
                            definition.id(), matcher.metricKey(),
                            new VanillaStatisticSource(matcher.vanillaStatisticKey()),
                            matcher.threshold(), matcher.samplingIntervalTicks());
                })
                .toList();
        return WatchedMetricSnapshot.of(watches);
    }

    @Override
    protected void apply(Prepared prepared, ResourceManager resourceManager, ProfilerFiller profiler) {
        long generation = snapshot.generation() + 1;
        snapshot = new GameplayPromotionSnapshot(prepared.definitions(), prepared.orderedDefinitions(),
                prepared.index(), prepared.watchedMetrics(), prepared.signalTypes(), generation);
        MythicTrpg.LOGGER.info("Loaded {} gameplay promotions (generation {}).",
                prepared.definitions().size(), generation);
    }

    void restoreSnapshotForTesting(GameplayPromotionSnapshot restored) {
        snapshot = restored;
    }

    protected record Prepared(
            Map<ResourceLocation, GameplayPromotionDefinition> definitions,
            List<GameplayPromotionDefinition> orderedDefinitions,
            GameplayPromotionIndex index,
            WatchedMetricSnapshot watchedMetrics,
            Map<ResourceLocation, InteractionSignalType<GameplayActionPayload>> signalTypes
    ) {
        protected Prepared {
            definitions = Map.copyOf(definitions);
            orderedDefinitions = List.copyOf(orderedDefinitions);
            java.util.Objects.requireNonNull(index, "index");
            java.util.Objects.requireNonNull(watchedMetrics, "watchedMetrics");
            signalTypes = Collections.unmodifiableMap(new LinkedHashMap<>(signalTypes));
        }
    }
}
