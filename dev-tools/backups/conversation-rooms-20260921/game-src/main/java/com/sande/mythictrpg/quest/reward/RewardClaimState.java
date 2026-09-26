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
    public static final int CURRENT_DATA_VERSION = 2;
    private static final int MAX_CLAIMS = 4096;
    private static final String FILE_NAME = "mythictrpg_reward_claims";
    private static final Factory<RewardClaimState> FACTORY = new Factory<>(
            RewardClaimState::new, RewardClaimState::load);

    private final Map<UUID, RewardClaim> claims = new LinkedHashMap<>();
    private final Map<WatchKey, WatchEntitlement> watches = new LinkedHashMap<>();
    private static final int MAX_WATCHES = 4096;

    public record WatchKey(UUID playerId, ResourceLocation godId) { }
    /** Kept independently of prunable completed receipts, in the SAME SavedData transaction. */
    public record WatchEntitlement(UUID playerId, ResourceLocation godId, UUID claimId,
            ResourceLocation sourceId, String displayName, long grantGameTime) { }

    public List<WatchEntitlement> watchesFor(UUID playerId) {
        if (!isWritable()) return List.of();
        return watches.values().stream().filter(w -> w.playerId().equals(playerId)).toList();
    }
    public boolean hasWatch(UUID playerId, ResourceLocation godId) {
        return isWritable() && watches.containsKey(new WatchKey(playerId, godId));
    }
    public boolean canGrantWatches(UUID playerId, List<RewardEntry> rewards) {
        long additions = rewards.stream().filter(WatchRewardEntry.class::isInstance)
                .map(WatchRewardEntry.class::cast).map(w -> new WatchKey(playerId, w.godId()))
                .distinct().filter(k -> !watches.containsKey(k)).count();
        return isWritable() && watches.size() + additions <= MAX_WATCHES;
    }
    private void acquireWatches(RewardClaim claim, List<RewardEntry> rewards, long gameTime) {
        if (gameTime < 0 || !canGrantWatches(claim.playerId(), rewards))
            throw new IllegalStateException("Watch entitlement storage unavailable");
        for (RewardEntry reward : rewards) if (reward instanceof WatchRewardEntry watch) {
            var key = new WatchKey(claim.playerId(), watch.godId());
            watches.putIfAbsent(key, new WatchEntitlement(claim.playerId(), watch.godId(), claim.claimId(),
                    claim.sourceId(), watch.displayName(), gameTime));
        }
    }
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

    public List<RewardClaim> undeliveredFor(UUID playerId) {
        return claims.values().stream().filter(claim -> claim.playerId().equals(playerId)
                && !claim.automaticGranted()).toList();
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

    /** Reserve capacity for a whole settlement before creating any receipt. Server-thread only. */
    public boolean canCreateBatch(java.util.Set<UUID> players, ResourceLocation sourceId) {
        if (!isWritable()) return false;
        long needed = players.stream().filter(id -> findBySource(id, sourceId).isEmpty()).count();
        long retained = claims.values().stream().filter(claim -> !claim.fullyClaimed()
                || (players.contains(claim.playerId()) && claim.sourceId().equals(sourceId))).count();
        return retained + needed <= MAX_CLAIMS;
    }

    public void createBatch(List<RewardClaim> batch) {
        ensureWritable();
        if (batch.isEmpty()) return;
        ResourceLocation source = batch.getFirst().sourceId();
        java.util.Set<UUID> players = new java.util.HashSet<>();
        java.util.Set<UUID> ids = new java.util.HashSet<>();
        for (RewardClaim claim : batch) {
            if (!source.equals(claim.sourceId()) || !players.add(claim.playerId())
                    || !ids.add(claim.claimId()) || claims.containsKey(claim.claimId()))
                throw new IllegalArgumentException("Invalid reward settlement batch");
        }
        if (!canCreateBatch(players, source)) throw new IllegalStateException("Reward claim storage unavailable");
        long needed = batch.stream().filter(c -> findBySource(c.playerId(), source).isEmpty()).count();
        var disposable = claims.values().stream().filter(c -> c.fullyClaimed()
                && !(players.contains(c.playerId()) && c.sourceId().equals(source)))
                .sorted(Comparator.comparingLong(RewardClaim::createdGameTime)).toList();
        for (RewardClaim old : disposable) {
            if (claims.size() + needed <= MAX_CLAIMS) break;
            claims.remove(old.claimId());
        }
        for (RewardClaim claim : batch) if (findBySource(claim.playerId(), source).isEmpty())
            claims.put(claim.claimId(), claim);
        setDirty();
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
        return markAutomaticGranted(claimId, requireWritableClaim(claimId).createdGameTime());
    }
    public RewardClaim markAutomaticGranted(UUID claimId, long gameTime) {
        RewardClaim claim = requireWritableClaim(claimId);
        if (!claim.automaticGranted()) {
            acquireWatches(claim, claim.automaticRewards(), gameTime);
            claim = claim.withAutomaticGranted();
            claims.put(claimId, claim);
            setDirty();
        }
        return claim;
    }

    public RewardClaim markSelected(UUID claimId, ResourceLocation optionId) {
        return markSelected(claimId, optionId, requireWritableClaim(claimId).createdGameTime());
    }
    public RewardClaim markSelected(UUID claimId, ResourceLocation optionId, long gameTime) {
        RewardClaim claim = requireWritableClaim(claimId);
        if (!claim.automaticGranted()) throw new IllegalStateException("Automatic grant is pending");
        if (claim.selectedOptionId().isPresent()) {
            throw new IllegalStateException("Reward choice is already claimed");
        }
        var rewards = claim.choices().stream().filter(c -> c.optionId().equals(optionId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown choice")).rewards();
        acquireWatches(claim, rewards, gameTime);
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
        ListTag acquired = new ListTag();
        for (WatchEntitlement watch : watches.values()) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("playerId", watch.playerId()); entry.putString("godId", watch.godId().toString());
            entry.putUUID("claimId", watch.claimId()); entry.putString("sourceId", watch.sourceId().toString());
            entry.putString("displayName", watch.displayName()); entry.putLong("grantGameTime", watch.grantGameTime());
            acquired.add(entry);
        }
        tag.put("watches", acquired);
        return tag;
    }

    static RewardClaimState load(CompoundTag tag, HolderLookup.Provider registries) {
        RewardClaimState state = new RewardClaimState();
        try {
            if (!tag.contains("dataVersion", Tag.TAG_ANY_NUMERIC)
                    || tag.getInt("dataVersion") < 1 || tag.getInt("dataVersion") > CURRENT_DATA_VERSION) {
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
            if (tag.getInt("dataVersion") >= 2) {
                ListTag acquired = requireList(tag, "watches");
                if (acquired.size() > MAX_WATCHES) throw new IllegalArgumentException("Too many watch entitlements");
                for (int i = 0; i < acquired.size(); i++) {
                    CompoundTag entry = acquired.getCompound(i);
                    WatchRewardEntry watch = new WatchRewardEntry(id(entry.getString("godId"), "godId"), entry.getString("displayName"));
                    UUID player = requireUuid(entry, "playerId");
                    if (!entry.contains("grantGameTime", Tag.TAG_LONG) || entry.getLong("grantGameTime") < 0)
                        throw new IllegalArgumentException("Invalid watch grant time");
                    var value = new WatchEntitlement(player, watch.godId(), requireUuid(entry, "claimId"),
                            id(entry.getString("sourceId"), "sourceId"), watch.displayName(), entry.getLong("grantGameTime"));
                    if (state.watches.putIfAbsent(new WatchKey(player, watch.godId()), value) != null)
                        throw new IllegalArgumentException("Duplicate watch entitlement");
                }
            }
            for(RewardClaim claim:state.claims.values()) {
                var delivered=new ArrayList<RewardEntry>();
                if(claim.automaticGranted())delivered.addAll(claim.automaticRewards());
                claim.selectedOptionId().ifPresent(selected->claim.choices().stream().filter(c->c.optionId().equals(selected))
                        .findFirst().ifPresent(c->delivered.addAll(c.rewards())));
                for(RewardEntry reward:delivered)if(reward instanceof WatchRewardEntry watch && !state.hasWatch(claim.playerId(),watch.godId()))
                    throw new IllegalArgumentException("Granted watch receipt missing entitlement");
            }
        } catch (RuntimeException exception) {
            state.claims.clear();
            state.watches.clear();
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
        if(!tag.contains("automaticGranted",Tag.TAG_BYTE) || !tag.contains("createdGameTime",Tag.TAG_LONG))
            throw new IllegalArgumentException("Missing receipt state/time");
        if(tag.contains("selectedOptionId")&&!tag.getBoolean("automaticGranted"))throw new IllegalArgumentException("Choice without automatic receipt");
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
        ListTag list = (ListTag) tag.get(key);
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
