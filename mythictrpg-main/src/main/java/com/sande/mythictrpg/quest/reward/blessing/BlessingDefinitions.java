package com.sande.mythictrpg.quest.reward.blessing;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import java.io.Reader;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.ToDoubleFunction;

/** Opt-in datapack definitions. A failed reload never replaces any part of the previous snapshot. */
@EventBusSubscriber(modid = MythicTrpg.MOD_ID)
public final class BlessingDefinitions extends SimplePreparableReloadListener<BlessingDefinitions.Snapshot> {
    public static final BlessingDefinitions INSTANCE = new BlessingDefinitions();
    private static final FileToIdConverter FILES = FileToIdConverter.json("mythictrpg/blessings");
    private volatile Snapshot snapshot = new Snapshot(Map.of(), Map.of(), Map.of());
    private BlessingDefinitions() { }

    public record Snapshot(Map<ResourceLocation, BlessingDefinition> definitions,
            Map<ResourceLocation, GodBlessingPolicy> godPolicies, Map<GodRank, BlessingRankPolicy> rankPolicies) {
        public Snapshot {
            definitions = Map.copyOf(definitions);
            godPolicies = Map.copyOf(godPolicies);
            rankPolicies = Map.copyOf(rankPolicies);
        }
    }

    @SubscribeEvent
    public static void registerReload(AddReloadListenerEvent event) { event.addListener(INSTANCE); }
    public Optional<BlessingDefinition> find(ResourceLocation id) { return Optional.ofNullable(snapshot.definitions().get(id)); }
    public Optional<GodBlessingPolicy> godPolicy(ResourceLocation godId) { return Optional.ofNullable(snapshot.godPolicies().get(godId)); }
    public Optional<BlessingRankPolicy> rankPolicy(GodRank rank) { return Optional.ofNullable(snapshot.rankPolicies().get(rank)); }
    public Snapshot snapshot() { return snapshot; }

    public BlessingSelector.Selection select(ResourceLocation definitionId, ResourceLocation godId, GodRank rank,
            Map<ResourceLocation, Integer> currentLevels, ToDoubleFunction<Map<ResourceLocation, Integer>> scorer) {
        var view = snapshot;
        var definition = view.definitions().get(definitionId);
        var godPolicy = view.godPolicies().get(godId);
        var rankPolicy = view.rankPolicies().get(rank);
        if (definition == null || !definition.godId().equals(godId) || godPolicy == null || rankPolicy == null) {
            return new BlessingSelector.Selection(BlessingSelector.Status.INVALID_POLICY, Optional.empty(), 0, 0,
                    "missing_or_mismatched_definition_god_or_rank_policy");
        }
        return BlessingSelector.select(definition, godPolicy, rankPolicy, currentLevels, scorer);
    }

    /** Pure parser shared by reload and authoring tests. Effect registry checks happen on server reload. */
    public static Snapshot parse(Map<ResourceLocation, JsonObject> documents) {
        if (documents.size() > 4096) throw new IllegalArgumentException("Too many blessing documents (maximum 4096)");
        Map<ResourceLocation, BlessingDefinition> definitions = new LinkedHashMap<>();
        Map<ResourceLocation, GodBlessingPolicy> gods = new LinkedHashMap<>();
        Map<GodRank, BlessingRankPolicy> ranks = new LinkedHashMap<>();
        documents.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            try {
                var json = entry.getValue();
                switch (BlessingDefinitionSchema.kind(json)) {
                    case "definition" -> definitions.put(entry.getKey(), BlessingDefinitionSchema.definition(entry.getKey(), json));
                    case "god_policy" -> {
                        var policy = BlessingDefinitionSchema.godPolicy(json);
                        if (gods.putIfAbsent(policy.godId(), policy) != null) throw new IllegalArgumentException("Duplicate god policy " + policy.godId());
                    }
                    case "rank_policy" -> {
                        var policy = BlessingDefinitionSchema.rankPolicy(json);
                        if (ranks.putIfAbsent(policy.rank(), policy) != null) throw new IllegalArgumentException("Duplicate rank policy " + policy.rank());
                    }
                    default -> throw new IllegalArgumentException("Unknown blessing document kind");
                }
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("Invalid blessing document " + entry.getKey() + ": " + exception.getMessage(), exception);
            }
        });
        for (var definition : definitions.values()) {
            var godPolicy = gods.get(definition.godId());
            if (godPolicy == null) throw new IllegalArgumentException("Missing god allowlist for blessing " + definition.id());
            for (var candidate : definition.candidates()) {
                if (!godPolicy.effects().containsKey(candidate.effectId())) {
                    throw new IllegalArgumentException("Blessing " + definition.id() + " contains effect outside god allowlist: " + candidate.effectId());
                }
            }
        }
        return new Snapshot(definitions, gods, ranks);
    }

    @Override protected Snapshot prepare(ResourceManager resources, ProfilerFiller profiler) {
        var files = FILES.listMatchingResources(resources);
        if (files.size() > 4096) throw new IllegalArgumentException("Too many blessing documents");
        Map<ResourceLocation, JsonObject> documents = new LinkedHashMap<>();
        files.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            try (Reader reader = entry.getValue().openAsReader()) {
                var json = JsonParser.parseReader(reader);
                if (!json.isJsonObject()) throw new IllegalArgumentException("Blessing document must be an object");
                documents.put(FILES.fileToId(entry.getKey()), json.getAsJsonObject());
            } catch (Exception exception) {
                throw new IllegalArgumentException("Failed to read blessing document " + entry.getKey(), exception);
            }
        });
        var parsed = parse(documents);
        parsed.godPolicies().values().forEach(policy -> policy.effects().keySet().forEach(BlessingDefinitions::validateEffect));
        parsed.rankPolicies().values().forEach(policy -> policy.effectCaps().keySet().forEach(BlessingDefinitions::validateEffect));
        return parsed;
    }

    private static void validateEffect(ResourceLocation id) {
        if (!BuiltInRegistries.MOB_EFFECT.containsKey(id) || BuiltInRegistries.MOB_EFFECT.get(id).isInstantenous()) {
            throw new IllegalArgumentException("Adaptive permanent blessing needs a registered non-instant effect: " + id);
        }
    }
    @Override protected void apply(Snapshot prepared, ResourceManager resources, ProfilerFiller profiler) {
        snapshot = prepared;
        MythicTrpg.LOGGER.info("Loaded {} opt-in blessing definitions, {} god policies and {} rank policies",
                prepared.definitions().size(), prepared.godPolicies().size(), prepared.rankPolicies().size());
    }
}
