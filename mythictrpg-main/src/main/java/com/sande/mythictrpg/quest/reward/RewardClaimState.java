package com.sande.mythictrpg.quest.reward;

import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Durable reward receipts and non-expiring player choices. */
public final class RewardClaimState extends SavedData {
    public static final int CURRENT_DATA_VERSION = 1;
    private static final int MAX_CLAIMS = 4096;
    private static final String FILE_NAME = "mythictrpg_reward_claims";
    private static final Factory<RewardClaimState> FACTORY = new Factory<>(
            RewardClaimState::new, RewardClaimState::load);

    private final Map<UUID, RewardClaim> claims = new LinkedHashMap<>();
    private CompoundTag rejectedRawData;
    private String rejectionReason;

    public static RewardClaimState get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, FILE_NAME);
    }

    public Optional<RewardClaim> find(UUID claimId) {
        return Optional.ofNullable(claims.get(claimId));
    }

    public Optional<RewardClaim> findBySource(UUID playerId, ResourceLocation sourceId) {
        return claims.values().stream().filter(claim -> claim.playerId().equals(playerId)
                && claim.sourceId().equals(sourceId)).findFirst();
    }

    public List<RewardClaim> pendingFor(UUID playerId) {
        return claims.values().stream().filter(claim -> claim.playerId().equals(playerId)
                && claim.pendingChoice()).sorted(Comparator.comparingLong(RewardClaim::createdGameTime))
                .toList();
    }

    public boolean canCreate(UUID playerId, ResourceLocation sourceId) {
        if (!isWritable()) {
            return false;
        }
        if (findBySource(playerId, sourceId).isPresent() || claims.size() < MAX_CLAIMS) {
            return true;
        }
        return claims.values().stream().anyMatch(RewardClaim::fullyClaimed);
    }

    public RewardClaim create(RewardClaim claim) {
        ensureWritable();
        RewardClaim existing = findBySource(claim.playerId(), claim.sourceId()).orElse(null);
        if (existing != null) {
            return existing;
        }
        pruneClaimed();
        if (claims.size() >= MAX_CLAIMS) {
            throw new IllegalStateException("Reward claim storage is full of pending claims");
        }
        claims.put(claim.claimId(), claim);
        setDirty();
        return claim;
    }

    public RewardClaim markAutomaticGranted(UUID claimId) {
        RewardClaim claim = requireWritableClaim(claimId);
        if (!claim.automaticGranted()) {
            claim = claim.withAutomaticGranted();
            claims.put(claimId, claim);
            setDirty();
        }
        return claim;
    }

    public RewardClaim markSelected(UUID claimId, ResourceLocation optionId) {
        RewardClaim claim = requireWritableClaim(claimId);
        if (claim.selectedOptionId().isPresent()) {
            throw new IllegalStateException("Reward choice is already claimed");
        }
        claim = claim.withSelected(optionId);
        claims.put(claimId, claim);
        setDirty();
        return claim;
    }

    public boolean isWritable() {
        return rejectedRawData == null;
    }

    public Optional<String> rejectionReason() {
        return Optional.ofNullable(rejectionReason);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (rejectedRawData != null) {
            return rejectedRawData.copy();
        }
        tag.putInt("dataVersion", CURRENT_DATA_VERSION);
        ListTag list = new ListTag();
        claims.values().stream().sorted(Comparator.comparingLong(RewardClaim::createdGameTime)
                .thenComparing(claim -> claim.claimId().toString())).map(RewardClaimState::saveClaim)
                .forEach(list::add);
        tag.put("claims", list);
        return tag;
    }

    static RewardClaimState load(CompoundTag tag, HolderLookup.Provider registries) {
        RewardClaimState state = new RewardClaimState();
        try {
            if (!tag.contains("dataVersion", Tag.TAG_ANY_NUMERIC)
                    || tag.getInt("dataVersion") != CURRENT_DATA_VERSION) {
                throw new IllegalArgumentException("Unsupported or missing reward claim dataVersion");
            }
            ListTag list = requireList(tag, "claims");
            if (list.size() > MAX_CLAIMS) {
                throw new IllegalArgumentException("Too many persisted reward claims");
            }
            for (int index = 0; index < list.size(); index++) {
                RewardClaim claim = loadClaim(list.getCompound(index));
                if (state.claims.putIfAbsent(claim.claimId(), claim) != null
                        || state.findBySource(claim.playerId(), claim.sourceId()).filter(
                                other -> !other.claimId().equals(claim.claimId())).isPresent()) {
                    throw new IllegalArgumentException("Duplicate reward claim ID or source");
                }
            }
        } catch (RuntimeException exception) {
            state.claims.clear();
            state.rejectedRawData = tag.copy();
            state.rejectionReason = exception.getMessage();
            MythicTrpg.LOGGER.error("Rejected reward claim data without replacing it: {}",
                    exception.getMessage(), exception);
        }
        return state;
    }

    private static CompoundTag saveClaim(RewardClaim claim) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("claimId", claim.claimId());
        tag.putUUID("playerId", claim.playerId());
        tag.putString("godId", claim.godId().toString());
        tag.putString("sourceId", claim.sourceId().toString());
        tag.putString("selectionTitle", claim.selectionTitle());
        tag.put("automaticRewards", saveRewards(claim.automaticRewards()));
        ListTag choices = new ListTag();
        for (RewardChoiceOption choice : claim.choices()) {
            CompoundTag stored = new CompoundTag();
            stored.putString("optionId", choice.optionId().toString());
            stored.putString("displayName", choice.displayName());
            stored.put("rewards", saveRewards(choice.rewards()));
            choices.add(stored);
        }
        tag.put("choices", choices);
        tag.putBoolean("automaticGranted", claim.automaticGranted());
        claim.selectedOptionId().ifPresent(id -> tag.putString("selectedOptionId", id.toString()));
        tag.putLong("createdGameTime", claim.createdGameTime());
        return tag;
    }

    private static RewardClaim loadClaim(CompoundTag tag) {
        List<RewardChoiceOption> choices = new ArrayList<>();
        ListTag storedChoices = requireList(tag, "choices");
        for (int index = 0; index < storedChoices.size(); index++) {
            CompoundTag stored = storedChoices.getCompound(index);
            choices.add(new RewardChoiceOption(id(stored.getString("optionId"), "optionId"),
                    stored.getString("displayName"), loadRewards(stored, "rewards")));
        }
        Optional<ResourceLocation> selected = tag.contains("selectedOptionId", Tag.TAG_STRING)
                ? Optional.of(id(tag.getString("selectedOptionId"), "selectedOptionId"))
                : Optional.empty();
        return new RewardClaim(requireUuid(tag, "claimId"), requireUuid(tag, "playerId"),
                id(tag.getString("godId"), "godId"), id(tag.getString("sourceId"), "sourceId"),
                tag.getString("selectionTitle"), loadRewards(tag, "automaticRewards"), choices,
                tag.getBoolean("automaticGranted"), selected, tag.getLong("createdGameTime"));
    }

    private static ListTag saveRewards(List<RewardEntry> rewards) {
        ListTag list = new ListTag();
        rewards.stream().map(RewardEntryCodec::save).forEach(list::add);
        return list;
    }

    private static List<RewardEntry> loadRewards(CompoundTag parent, String key) {
        ListTag list = requireList(parent, key);
        List<RewardEntry> rewards = new ArrayList<>();
        for (int index = 0; index < list.size(); index++) {
            rewards.add(RewardEntryCodec.load(list.getCompound(index)));
        }
        return List.copyOf(rewards);
    }

    private RewardClaim requireWritableClaim(UUID claimId) {
        ensureWritable();
        RewardClaim claim = claims.get(claimId);
        if (claim == null) {
            throw new IllegalStateException("Unknown reward claim");
        }
        return claim;
    }

    private void pruneClaimed() {
        if (claims.size() < MAX_CLAIMS) {
            return;
        }
        claims.values().stream().filter(RewardClaim::fullyClaimed)
                .min(Comparator.comparingLong(RewardClaim::createdGameTime))
                .ifPresent(claim -> claims.remove(claim.claimId()));
    }

    private void ensureWritable() {
        if (!isWritable()) {
            throw new IllegalStateException("Reward claim state is read-only: " + rejectionReason);
        }
    }

    private static ListTag requireList(CompoundTag tag, String key) {
        if (!tag.contains(key, Tag.TAG_LIST)) {
            throw new IllegalArgumentException("Missing list '" + key + "'");
        }
        ListTag list = tag.getList(key, Tag.TAG_COMPOUND);
        if (!list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND) {
            throw new IllegalArgumentException("Invalid list type for '" + key + "'");
        }
        return list;
    }

    private static UUID requireUuid(CompoundTag tag, String key) {
        if (!tag.hasUUID(key)) {
            throw new IllegalArgumentException("Missing UUID '" + key + "'");
        }
        return tag.getUUID(key);
    }

    private static ResourceLocation id(String value, String field) {
        ResourceLocation id = ResourceLocation.tryParse(value);
        if (id == null || !value.contains(":")) {
            throw new IllegalArgumentException("Invalid namespaced ID in " + field + ": " + value);
        }
        return id;
    }
}
