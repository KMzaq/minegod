package com.sande.mythictrpg.quest.structure;

import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Persistent always-on placement provenance, free structures, and reusable evaluation history. */
public final class PlayerConstructionState extends SavedData implements StructureEvaluationView {
    private static final int DATA_VERSION = 3;
    private static final String FILE_NAME = "mythictrpg_player_constructions";
    private static final Factory<PlayerConstructionState> FACTORY = new Factory<>(
            PlayerConstructionState::new, PlayerConstructionState::load);

    private final Map<LedgerKey, PlacementRecord> ledger = new LinkedHashMap<>();
    private final Map<UUID, FreeStructureRecord> structures = new LinkedHashMap<>();
    private final Map<EvaluationKey, StoredStructureEvaluation> evaluations = new LinkedHashMap<>();
    private long currentGameTick;
    private CompoundTag rejectedRaw;
    public boolean isWritable() { return rejectedRaw == null; }
    public Optional<PlacementRecord> placement(ResourceKey<Level> dimension, BlockPos pos) {
        return Optional.ofNullable(ledger.get(new LedgerKey(dimension,pos.asLong())));
    }

    public static PlayerConstructionState get(MinecraftServer server) {
        PlayerConstructionState state = server.overworld().getDataStorage().computeIfAbsent(FACTORY, FILE_NAME);
        state.currentGameTick = server.overworld().getGameTime();
        return state;
    }

    public void recordBlock(ResourceKey<Level> dimension, BlockPos pos, UUID placer,
            PlacementSource source, long gameTick) {
        if (!isWritable()) return;
        // Tool transformations do not launder already-known direct placement/generator provenance.
        var previous = placement(dimension,pos);
        PlacementRecord placement = source == PlacementSource.DERIVED && previous.isPresent()
                ? previous.get() : new PlacementRecord(placer, gameTick, source);
        ledger.put(new LedgerKey(dimension, pos.asLong()), placement);
        for (FreeStructureRecord structure : structures.values()) {
            if (structure.region().dimension().equals(dimension) && structure.region().contains(pos)) {
                structure.remove(pos);
                structure.record(pos, placement);
            }
        }
        currentGameTick = gameTick; setDirty();
    }

    public void removeBlock(ResourceKey<Level> dimension, BlockPos pos) {
        if (!isWritable()) return;
        boolean changed = ledger.remove(new LedgerKey(dimension, pos.asLong())) != null;
        for (FreeStructureRecord structure : structures.values()) {
            if (structure.region().dimension().equals(dimension)) changed |= structure.remove(pos);
        }
        if (changed) setDirty();
    }

    public FreeStructureRecord register(UUID ownerId, String name, StructureRegion region,
            Set<UUID> contributors, long gameTick) {
        requireWritable();
        if (findByName(ownerId, name).isPresent()) throw new IllegalArgumentException("같은 이름의 건축물이 이미 있습니다");
        FreeStructureRecord structure = new FreeStructureRecord(UUID.randomUUID(), ownerId, name,
                region, contributors, gameTick);
        ledger.forEach((key, placement) -> {
            if (key.dimension().equals(region.dimension()) && region.contains(BlockPos.of(key.position()))
                    && structure.eligibleContributors().contains(placement.placerId())) {
                structure.restorePlacement(key.position(), placement);
            }
        });
        structures.put(structure.id(), structure); currentGameTick = gameTick; setDirty();
        return structure;
    }

    public Optional<FreeStructureRecord> findByName(UUID ownerId, String name) {
        return structures.values().stream().filter(value -> value.ownerId().equals(ownerId)
                && value.name().equalsIgnoreCase(name.trim())).findFirst();
    }
    public Optional<FreeStructureRecord> find(UUID id) { return Optional.ofNullable(structures.get(id)); }
    public Collection<FreeStructureRecord> structuresFor(UUID ownerId) {
        return structures.values().stream().filter(value -> value.ownerId().equals(ownerId)).toList();
    }
    public Collection<FreeStructureRecord> structures() { return java.util.List.copyOf(structures.values()); }

    public Map<Long, PlacementRecord> placements(ResourceKey<Level> dimension, Set<UUID> contributors) {
        Map<Long, PlacementRecord> result = new LinkedHashMap<>();
        ledger.forEach((key, placement) -> {
            if (key.dimension().equals(dimension) && contributors.contains(placement.placerId())) {
                result.put(key.position(), placement);
            }
        });
        return Map.copyOf(result);
    }

    public boolean delete(UUID ownerId, String name) {
        if (!isWritable()) return false;
        Optional<FreeStructureRecord> found = findByName(ownerId, name);
        if (found.isEmpty()) return false;
        UUID id = found.orElseThrow().id(); structures.remove(id);
        evaluations.keySet().removeIf(key -> key.sourceKey().equals("free:" + id));
        setDirty(); return true;
    }

    public void recordDecoration(FreeStructureRecord structure, UUID entityId, UUID placer, long tick) {
        requireWritable();
        if (structure.recordDecoration(entityId,
                new PlacementRecord(placer, tick, PlacementSource.PLAYER_PLACED))) setDirty();
    }

    public void recordEvaluation(String sourceKey, UUID ownerId, Set<UUID> contributors,
            StructureEvaluationReport report, long gameTick) {
        requireWritable();
        StoredStructureEvaluation value = new StoredStructureEvaluation(sourceKey, ownerId, contributors,
                report.policyId(), report.godId(), report.score(), report.buildScore(),
                report.environmentScore(), gameTick, report.snapshot().fingerprint(),
                report.snapshot().features(), report.evidenceSummary());
        evaluations.put(new EvaluationKey(sourceKey, report.policyId()), value);
        currentGameTick = gameTick; setDirty();
    }

    public boolean applyVisualEvaluation(String sourceKey, ResourceLocation policyId, String fingerprint,
            StructureVisualAssessment assessment, StructureEvaluationPolicy.VisualProfile profile) {
        if (!isWritable()) return false;
        EvaluationKey key = new EvaluationKey(sourceKey, policyId);
        StoredStructureEvaluation current = evaluations.get(key);
        if (current == null || !current.fingerprint().equals(fingerprint)) return false;
        boolean confident = assessment.isConfident(profile.minimumConfidence());
        int weight = confident ? profile.visualWeight() : 0;
        int combined = (int) Math.round(current.objectiveScore() * (100 - weight) / 100.0D
                + assessment.godPreferenceScore() * weight / 100.0D);
        Map<String, Double> features = new LinkedHashMap<>(current.features());
        features.put("visual_confidence", assessment.confidence());
        features.put("visual_quality_score", assessment.visualQualityScore() / 100.0D);
        features.put("visual_preference_score", assessment.godPreferenceScore() / 100.0D);
        features.put("visual_completeness_score", assessment.completenessScore() / 100.0D);
        StoredStructureEvaluation updated = new StoredStructureEvaluation(current.sourceKey(), current.ownerId(),
                current.contributors(), current.policyId(), current.godId(), combined, current.objectiveScore(),
                current.buildScore(), current.environmentScore(), current.evaluatedAtGameTick(), current.fingerprint(),
                features, current.evidenceSummary(), Optional.of(assessment));
        evaluations.put(key, updated); setDirty(); return true;
    }

    public Optional<StoredStructureEvaluation> evaluation(String sourceKey, ResourceLocation policyId) {
        return Optional.ofNullable(evaluations.get(new EvaluationKey(sourceKey, policyId)));
    }

    @Override public boolean isReady() { return isWritable(); }
    private void requireWritable() { if(!isWritable())throw new IllegalStateException("Construction provenance unavailable; raw data preserved"); }
    @Override public Optional<StoredStructureEvaluation> best(UUID playerId, ResourceLocation policyId,
            long maximumAgeTicks) {
        long now = currentGameTick;
        return evaluations.values().stream().filter(value -> value.belongsTo(playerId))
                .filter(value -> value.policyId().equals(policyId))
                .filter(value -> maximumAgeTicks < 0 || now - value.evaluatedAtGameTick() <= maximumAgeTicks)
                .max(Comparator.comparingInt(StoredStructureEvaluation::score)
                        .thenComparingLong(StoredStructureEvaluation::evaluatedAtGameTick));
    }

    @Override public boolean matches(UUID playerId, ResourceLocation policyId, int minimumScore,
            double minimumBuildScore, long maximumAgeTicks, Map<String, Double> minimumFeatures) {
        long now = currentGameTick;
        return evaluations.values().stream().filter(value -> value.belongsTo(playerId))
                .filter(value -> value.policyId().equals(policyId))
                .filter(value -> maximumAgeTicks < 0 || now - value.evaluatedAtGameTick() <= maximumAgeTicks)
                .anyMatch(value -> value.score() >= minimumScore && value.buildScore() >= minimumBuildScore
                        && minimumFeatures.entrySet().stream().allMatch(feature ->
                        value.features().getOrDefault(feature.getKey(), 0.0D) >= feature.getValue()));
    }

    @Override public CompoundTag save(CompoundTag root, HolderLookup.Provider registries) {
        if (rejectedRaw != null) return rejectedRaw.copy();
        root.putInt("dataVersion", DATA_VERSION);
        ListTag ledgerList = new ListTag(); ledger.forEach((key, placement) -> {
            CompoundTag tag = placementTag(placement); tag.putString("dimension", key.dimension().location().toString());
            tag.putLong("pos", key.position()); ledgerList.add(tag);
        }); root.put("ledger", ledgerList);
        ListTag structureList = new ListTag(); structures.values().forEach(value -> structureList.add(writeStructure(value)));
        root.put("structures", structureList);
        ListTag evaluationList = new ListTag(); evaluations.values().forEach(value -> evaluationList.add(writeEvaluation(value)));
        root.put("evaluations", evaluationList);
        return root;
    }

    private static CompoundTag writeStructure(FreeStructureRecord value) {
        CompoundTag tag = new CompoundTag(); tag.putUUID("id", value.id()); tag.putUUID("owner", value.ownerId());
        tag.putString("name", value.name()); tag.putString("dimension", value.region().dimension().location().toString());
        tag.putInt("minX", value.region().minX()); tag.putInt("maxX", value.region().maxX());
        tag.putInt("minZ", value.region().minZ()); tag.putInt("maxZ", value.region().maxZ());
        tag.putLong("registered", value.registeredAtGameTick());
        ListTag contributors = new ListTag(); value.eligibleContributors().forEach(id -> {
            CompoundTag item = new CompoundTag(); item.putUUID("id", id); contributors.add(item);
        }); tag.put("contributors", contributors);
        ListTag placements = new ListTag(); value.placements().forEach((pos, placement) -> {
            CompoundTag item = placementTag(placement); item.putLong("pos", pos); placements.add(item);
        }); tag.put("placements", placements);
        ListTag decorations = new ListTag(); value.decorations().forEach((id, placement) -> {
            CompoundTag item = placementTag(placement); item.putUUID("entity", id); decorations.add(item);
        }); tag.put("decorations", decorations); return tag;
    }

    private static CompoundTag writeEvaluation(StoredStructureEvaluation value) {
        CompoundTag tag = new CompoundTag(); tag.putString("source", value.sourceKey()); tag.putUUID("owner", value.ownerId());
        tag.putString("policy", value.policyId().toString()); tag.putString("god", value.godId().toString());
        tag.putInt("score", value.score()); tag.putInt("objectiveScore", value.objectiveScore());
        tag.putDouble("buildScore", value.buildScore());
        tag.putDouble("environmentScore", value.environmentScore()); tag.putLong("evaluated", value.evaluatedAtGameTick());
        tag.putString("fingerprint", value.fingerprint()); tag.putString("evidence", value.evidenceSummary());
        ListTag contributors = new ListTag(); value.contributors().forEach(id -> {
            CompoundTag item = new CompoundTag(); item.putUUID("id", id); contributors.add(item);
        }); tag.put("contributors", contributors);
        CompoundTag features = new CompoundTag(); value.features().forEach(features::putDouble); tag.put("features", features);
        value.visualAssessment().ifPresent(assessment -> tag.put("visual", writeVisual(assessment)));
        return tag;
    }

    private static CompoundTag writeVisual(StructureVisualAssessment value) {
        CompoundTag tag = new CompoundTag(); tag.putString("type", value.buildingType().name());
        tag.putString("subtype", value.subtype()); tag.putDouble("confidence", value.confidence());
        tag.putInt("quality", value.visualQualityScore()); tag.putInt("preference", value.godPreferenceScore());
        tag.putInt("completeness", value.completenessScore()); tag.putString("analyzer", value.analyzer());
        tag.put("styles", writeStrings(value.styles())); tag.put("evidence", writeStrings(value.evidence()));
        tag.put("concerns", writeStrings(value.concerns())); return tag;
    }

    private static ListTag writeStrings(java.util.List<String> values) {
        ListTag list = new ListTag(); values.forEach(value -> {
            CompoundTag item = new CompoundTag(); item.putString("value", value); list.add(item);
        }); return list;
    }

    private static PlayerConstructionState load(CompoundTag root, HolderLookup.Provider registries) {
        PlayerConstructionState state = new PlayerConstructionState();
        try {
            int version = root.getInt("dataVersion");
            if (version < 1 || version > DATA_VERSION) throw new IllegalArgumentException("Unsupported construction data version");
            if (!(root.get("ledger") instanceof ListTag ledgerTags)
                    || !ledgerTags.isEmpty() && ledgerTags.getElementType()!=Tag.TAG_COMPOUND)
                throw new IllegalArgumentException("Invalid provenance ledger");
            for (Tag raw : ledgerTags) {
                CompoundTag tag = (CompoundTag) raw;
                if(!tag.contains("pos",Tag.TAG_LONG))throw new IllegalArgumentException("Missing provenance position");
                if(state.ledger.putIfAbsent(new LedgerKey(dimension(tag.getString("dimension")), tag.getLong("pos")), readPlacement(tag))!=null)
                    throw new IllegalArgumentException("Duplicate provenance position");
            }
            for (Tag raw : root.getList("structures", Tag.TAG_COMPOUND)) {
                FreeStructureRecord value = readStructure((CompoundTag) raw); state.structures.put(value.id(), value);
            }
            for (Tag raw : root.getList("evaluations", Tag.TAG_COMPOUND)) {
                StoredStructureEvaluation value = readEvaluation((CompoundTag) raw);
                state.evaluations.put(new EvaluationKey(value.sourceKey(), value.policyId()), value);
            }
        } catch (RuntimeException exception) {
            state.ledger.clear(); state.structures.clear(); state.evaluations.clear();
            state.rejectedRaw = root.copy();
            MythicTrpg.LOGGER.error("Rejected player construction state", exception);
        }
        return state;
    }

    private static FreeStructureRecord readStructure(CompoundTag tag) {
        Set<UUID> contributors = new LinkedHashSet<>();
        for (Tag raw : tag.getList("contributors", Tag.TAG_COMPOUND)) contributors.add(((CompoundTag) raw).getUUID("id"));
        StructureRegion region = new StructureRegion(dimension(tag.getString("dimension")), tag.getInt("minX"),
                tag.getInt("maxX"), tag.getInt("minZ"), tag.getInt("maxZ"));
        FreeStructureRecord value = new FreeStructureRecord(tag.getUUID("id"), tag.getUUID("owner"),
                tag.getString("name"), region, contributors, tag.getLong("registered"));
        for (Tag raw : tag.getList("placements", Tag.TAG_COMPOUND)) {
            CompoundTag item = (CompoundTag) raw; value.restorePlacement(item.getLong("pos"), readPlacement(item));
        }
        for (Tag raw : tag.getList("decorations", Tag.TAG_COMPOUND)) {
            CompoundTag item = (CompoundTag) raw; value.restoreDecoration(item.getUUID("entity"), readPlacement(item));
        }
        return value;
    }

    private static StoredStructureEvaluation readEvaluation(CompoundTag tag) {
        Set<UUID> contributors = new LinkedHashSet<>();
        for (Tag raw : tag.getList("contributors", Tag.TAG_COMPOUND)) contributors.add(((CompoundTag) raw).getUUID("id"));
        Map<String, Double> features = new LinkedHashMap<>(); CompoundTag featureTag = tag.getCompound("features");
        featureTag.getAllKeys().forEach(key -> features.put(key, featureTag.getDouble(key)));
        int score = tag.getInt("score");
        int objectiveScore = tag.contains("objectiveScore", Tag.TAG_INT) ? tag.getInt("objectiveScore") : score;
        Optional<StructureVisualAssessment> visual = tag.contains("visual", Tag.TAG_COMPOUND)
                ? Optional.of(readVisual(tag.getCompound("visual"))) : Optional.empty();
        return new StoredStructureEvaluation(tag.getString("source"), tag.getUUID("owner"), contributors,
                id(tag.getString("policy")), id(tag.getString("god")), score, objectiveScore,
                tag.getDouble("buildScore"), tag.getDouble("environmentScore"), tag.getLong("evaluated"),
                tag.getString("fingerprint"), features, tag.getString("evidence"), visual);
    }

    private static StructureVisualAssessment readVisual(CompoundTag tag) {
        return new StructureVisualAssessment(StructureVisualAssessment.BuildingType.parse(tag.getString("type")),
                tag.getString("subtype"), readStrings(tag.getList("styles", Tag.TAG_COMPOUND)),
                tag.getDouble("confidence"), tag.getInt("quality"), tag.getInt("preference"),
                tag.getInt("completeness"), readStrings(tag.getList("evidence", Tag.TAG_COMPOUND)),
                readStrings(tag.getList("concerns", Tag.TAG_COMPOUND)), tag.getString("analyzer"));
    }

    private static java.util.List<String> readStrings(ListTag list) {
        java.util.List<String> values = new java.util.ArrayList<>();
        for (Tag raw : list) values.add(((CompoundTag) raw).getString("value"));
        return java.util.List.copyOf(values);
    }

    private static CompoundTag placementTag(PlacementRecord value) {
        CompoundTag tag = new CompoundTag(); tag.putUUID("placer", value.placerId());
        tag.putLong("tick", value.placedAtGameTick()); tag.putString("source", value.source().name()); return tag;
    }
    private static PlacementRecord readPlacement(CompoundTag tag) {
        if(!tag.contains("tick",Tag.TAG_LONG)||!tag.contains("source",Tag.TAG_STRING))throw new IllegalArgumentException("Invalid provenance record");
        return new PlacementRecord(tag.getUUID("placer"), tag.getLong("tick"), PlacementSource.valueOf(tag.getString("source")));
    }
    private static ResourceLocation id(String raw) {
        ResourceLocation id = ResourceLocation.tryParse(raw); if (id == null || !raw.contains(":")) throw new IllegalArgumentException("Invalid ID " + raw); return id;
    }
    private static ResourceKey<Level> dimension(String raw) { return ResourceKey.create(Registries.DIMENSION, id(raw)); }
    private record LedgerKey(ResourceKey<Level> dimension, long position) {}
    private record EvaluationKey(String sourceKey, ResourceLocation policyId) {}
}
