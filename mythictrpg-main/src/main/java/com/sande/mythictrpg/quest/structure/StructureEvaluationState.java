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

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Server-wide persistent drafts, locked regions, sparse ledgers, and successful fingerprints. */
public final class StructureEvaluationState extends SavedData {
    public static final int CURRENT_DATA_VERSION = 1;
    /** Legacy constant retained for binary/source compatibility; regions no longer stop recording at this value. */
    public static final int ABSOLUTE_TRACKING_LIMIT = 50_000;
    private static final String FILE_NAME = "mythictrpg_structure_evaluations";
    private static final Factory<StructureEvaluationState> FACTORY = new Factory<>(
            StructureEvaluationState::new, StructureEvaluationState::load);

    private final Map<UUID, Draft> drafts = new LinkedHashMap<>();
    private final Map<Key, StructureBuildRecord> builds = new LinkedHashMap<>();
    private final Map<String, ResourceLocation> successfulFingerprints = new LinkedHashMap<>();

    public static StructureEvaluationState get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, FILE_NAME);
    }

    public void setPoint(UUID playerId, ResourceKey<Level> dimension, BlockPos pos, boolean second) {
        Draft old = drafts.get(playerId);
        Draft next = old == null || !old.dimension().equals(dimension)
                ? new Draft(dimension, second ? null : pos.immutable(), second ? pos.immutable() : null)
                : new Draft(dimension, second ? old.first() : pos.immutable(), second ? pos.immutable() : old.second());
        drafts.put(playerId, next);
        setDirty();
    }

    public Optional<Draft> draft(UUID playerId) { return Optional.ofNullable(drafts.get(playerId)); }

    public StructureBuildRecord confirm(UUID ownerId, ResourceLocation questId,
            Set<UUID> contributors, long gameTick) {
        Draft draft = drafts.get(ownerId);
        if (draft == null || draft.first() == null || draft.second() == null) {
            throw new IllegalStateException("Both structure selector points must be set");
        }
        StructureBuildRecord record = new StructureBuildRecord(ownerId, questId,
                StructureRegion.between(draft.dimension(), draft.first(), draft.second()), contributors, gameTick);
        builds.put(new Key(ownerId, questId), record);
        setDirty();
        return record;
    }

    public Optional<StructureBuildRecord> build(UUID ownerId, ResourceLocation questId) {
        return Optional.ofNullable(builds.get(new Key(ownerId, questId)));
    }

    public java.util.Collection<StructureBuildRecord> builds() { return java.util.List.copyOf(builds.values()); }

    public boolean clear(UUID ownerId, ResourceLocation questId) {
        boolean changed = builds.remove(new Key(ownerId, questId)) != null;
        if (changed) setDirty();
        return changed;
    }

    public boolean recordBlock(ResourceKey<Level> dimension, BlockPos pos, UUID placer,
            PlacementSource source, long gameTick) {
        boolean changed = false;
        PlacementRecord placement = new PlacementRecord(placer, gameTick, source);
        for (StructureBuildRecord build : builds.values()) {
            if (build.region().dimension().equals(dimension) && build.region().contains(pos)) {
                changed |= build.remove(pos);
                changed |= build.record(pos, placement, 0);
            }
        }
        if (changed) setDirty();
        return changed;
    }

    public boolean removeBlock(ResourceKey<Level> dimension, BlockPos pos) {
        boolean changed = false;
        for (StructureBuildRecord build : builds.values()) {
            if (build.region().dimension().equals(dimension) && build.region().contains(pos)) {
                changed |= build.remove(pos);
            }
        }
        if (changed) setDirty();
        return changed;
    }

    public void recordDecoration(StructureBuildRecord build, UUID entityId, UUID placer, long tick) {
        if (build.recordDecoration(entityId, new PlacementRecord(placer, tick, PlacementSource.PLAYER_PLACED))) {
            setDirty();
        }
    }

    public boolean fingerprintUsedByAnotherQuest(String fingerprint, ResourceLocation questId) {
        ResourceLocation usedBy = successfulFingerprints.get(fingerprint);
        return usedBy != null && !usedBy.equals(questId);
    }

    public void markSuccessful(String fingerprint, ResourceLocation questId) {
        successfulFingerprints.putIfAbsent(fingerprint, questId);
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag root, HolderLookup.Provider registries) {
        root.putInt("dataVersion", CURRENT_DATA_VERSION);
        ListTag draftList = new ListTag();
        drafts.forEach((player, draft) -> {
            CompoundTag tag = new CompoundTag(); tag.putUUID("player", player);
            tag.putString("dimension", draft.dimension().location().toString());
            if (draft.first() != null) tag.putLong("first", draft.first().asLong());
            if (draft.second() != null) tag.putLong("second", draft.second().asLong());
            draftList.add(tag);
        });
        root.put("drafts", draftList);
        ListTag buildList = new ListTag();
        for (StructureBuildRecord build : builds.values()) buildList.add(writeBuild(build));
        root.put("builds", buildList);
        ListTag fingerprints = new ListTag();
        successfulFingerprints.forEach((hash, quest) -> {
            CompoundTag tag = new CompoundTag(); tag.putString("hash", hash); tag.putString("quest", quest.toString());
            fingerprints.add(tag);
        });
        root.put("fingerprints", fingerprints);
        return root;
    }

    private static CompoundTag writeBuild(StructureBuildRecord build) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("owner", build.ownerId()); tag.putString("quest", build.questId().toString());
        tag.putString("dimension", build.region().dimension().location().toString());
        tag.putInt("minX", build.region().minX()); tag.putInt("maxX", build.region().maxX());
        tag.putInt("minZ", build.region().minZ()); tag.putInt("maxZ", build.region().maxZ());
        tag.putLong("started", build.startedAtGameTick());
        tag.putLong("lastEvaluation", build.lastEvaluationTick());
        ListTag contributors = new ListTag();
        build.eligibleContributors().forEach(id -> { CompoundTag e = new CompoundTag(); e.putUUID("id", id); contributors.add(e); });
        tag.put("contributors", contributors);
        ListTag placements = new ListTag();
        build.placements().forEach((pos, placement) -> { CompoundTag e = placementTag(placement); e.putLong("pos", pos); placements.add(e); });
        tag.put("placements", placements);
        ListTag decorations = new ListTag();
        build.decorations().forEach((id, placement) -> { CompoundTag e = placementTag(placement); e.putUUID("entity", id); decorations.add(e); });
        tag.put("decorations", decorations);
        return tag;
    }

    private static CompoundTag placementTag(PlacementRecord placement) {
        CompoundTag tag = new CompoundTag(); tag.putUUID("placer", placement.placerId());
        tag.putLong("tick", placement.placedAtGameTick()); tag.putString("source", placement.source().name()); return tag;
    }

    private static StructureEvaluationState load(CompoundTag root, HolderLookup.Provider registries) {
        StructureEvaluationState state = new StructureEvaluationState();
        try {
            if (root.getInt("dataVersion") != CURRENT_DATA_VERSION) throw new IllegalArgumentException("Unsupported structure data version");
            for (Tag raw : root.getList("drafts", Tag.TAG_COMPOUND)) {
                CompoundTag tag = (CompoundTag) raw; UUID player = tag.getUUID("player");
                ResourceKey<Level> dimension = dimension(tag.getString("dimension"));
                state.drafts.put(player, new Draft(dimension,
                        tag.contains("first") ? BlockPos.of(tag.getLong("first")) : null,
                        tag.contains("second") ? BlockPos.of(tag.getLong("second")) : null));
            }
            for (Tag raw : root.getList("builds", Tag.TAG_COMPOUND)) {
                StructureBuildRecord build = readBuild((CompoundTag) raw);
                state.builds.put(new Key(build.ownerId(), build.questId()), build);
            }
            for (Tag raw : root.getList("fingerprints", Tag.TAG_COMPOUND)) {
                CompoundTag tag = (CompoundTag) raw;
                state.successfulFingerprints.put(tag.getString("hash"), id(tag.getString("quest")));
            }
        } catch (RuntimeException exception) {
            state.drafts.clear(); state.builds.clear(); state.successfulFingerprints.clear();
            MythicTrpg.LOGGER.error("Rejected structure evaluation state: {}", exception.getMessage(), exception);
        }
        return state;
    }

    private static StructureBuildRecord readBuild(CompoundTag tag) {
        UUID owner = tag.getUUID("owner"); ResourceLocation quest = id(tag.getString("quest"));
        StructureRegion region = new StructureRegion(dimension(tag.getString("dimension")),
                tag.getInt("minX"), tag.getInt("maxX"), tag.getInt("minZ"), tag.getInt("maxZ"));
        Set<UUID> contributors = new LinkedHashSet<>();
        for (Tag raw : tag.getList("contributors", Tag.TAG_COMPOUND)) contributors.add(((CompoundTag) raw).getUUID("id"));
        StructureBuildRecord build = new StructureBuildRecord(owner, quest, region, contributors, tag.getLong("started"));
        build.restoreLastEvaluationTick(tag.contains("lastEvaluation") ? tag.getLong("lastEvaluation") : Long.MIN_VALUE);
        for (Tag raw : tag.getList("placements", Tag.TAG_COMPOUND)) {
            CompoundTag e = (CompoundTag) raw; build.restorePlacement(e.getLong("pos"), readPlacement(e));
        }
        for (Tag raw : tag.getList("decorations", Tag.TAG_COMPOUND)) {
            CompoundTag e = (CompoundTag) raw; build.restoreDecoration(e.getUUID("entity"), readPlacement(e));
        }
        return build;
    }

    private static PlacementRecord readPlacement(CompoundTag tag) {
        return new PlacementRecord(tag.getUUID("placer"), tag.getLong("tick"), PlacementSource.valueOf(tag.getString("source")));
    }
    private static ResourceLocation id(String raw) {
        ResourceLocation id = ResourceLocation.tryParse(raw); if (id == null || !raw.contains(":")) throw new IllegalArgumentException("Invalid ID " + raw); return id;
    }
    private static ResourceKey<Level> dimension(String raw) { return ResourceKey.create(Registries.DIMENSION, id(raw)); }

    public record Draft(ResourceKey<Level> dimension, BlockPos first, BlockPos second) {}
    private record Key(UUID owner, ResourceLocation quest) {}
}
