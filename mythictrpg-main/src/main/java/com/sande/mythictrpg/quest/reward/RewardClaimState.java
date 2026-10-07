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
    // v4 retains permanent blessing ownership and removal state independently of prunable receipts.
    public static final int CURRENT_DATA_VERSION = 4;
    private static final int MAX_CLAIMS = 4096;
    private static final String FILE_NAME = "mythictrpg_reward_claims";
    private static final Factory<RewardClaimState> FACTORY = new Factory<>(
            RewardClaimState::new, RewardClaimState::load);

    private final Map<UUID, RewardClaim> claims = new LinkedHashMap<>();
    private final Map<WatchKey, WatchEntitlement> watches = new LinkedHashMap<>();
    private static final int MAX_WATCHES = 4096;
    private static final int MAX_PERMANENT_BLESSINGS = 16_384;
    private final Map<BlessingKey, PermanentBlessingEntitlement> blessings = new LinkedHashMap<>();
    private final java.util.Set<SuppressedBlessing> suppressedBlessings = new java.util.LinkedHashSet<>();

    public enum BlessingSource { QUEST_CLAIM, NPC_TABLE }
    private record BlessingKey(UUID playerId, BlessingSource source, ResourceLocation sourceId,
            ResourceLocation godId, ResourceLocation effectId) { }
    private record SuppressedBlessing(UUID playerId, ResourceLocation effectId) { }
    public record PermanentBlessingEntitlement(UUID playerId, ResourceLocation godId,
            BlessingSource source, ResourceLocation sourceId, Optional<UUID> claimId,
            ResourceLocation effectId, int amplifier, long grantGameTime) { }

    /** Possession survives milk, death, logout and completed-claim pruning. Never infer it from active effects. */
    public List<PermanentBlessingEntitlement> permanentBlessingsFor(UUID playerId) {
        ensureWritable();
        return blessings.values().stream().filter(value -> value.playerId().equals(playerId)).toList();
    }

    public Map<ResourceLocation, Integer> ownedPermanentBlessingLevels(UUID playerId) {
        Map<ResourceLocation, Integer> levels = new LinkedHashMap<>();
        permanentBlessingsFor(playerId).forEach(value -> levels.merge(value.effectId(), value.amplifier(), Math::max));
        return Map.copyOf(levels);
    }

    public boolean isBlessingSuppressed(UUID playerId, ResourceLocation effectId) {
        ensureWritable();
        return suppressedBlessings.contains(new SuppressedBlessing(playerId, effectId));
    }

    void suppressBlessing(UUID playerId, ResourceLocation effectId) {
        ensureWritable();
        if (ownedPermanentBlessingLevels(playerId).containsKey(effectId)
                && suppressedBlessings.add(new SuppressedBlessing(playerId, effectId))) setDirty();
    }

    void resumeBlessing(UUID playerId, ResourceLocation effectId) {
        ensureWritable();
        if (suppressedBlessings.remove(new SuppressedBlessing(playerId, effectId))) setDirty();
    }

    void clearBlessingSuppression(UUID playerId) {
        ensureWritable();
        if (suppressedBlessings.removeIf(value -> value.playerId().equals(playerId))) setDirty();
    }

    /** Conservative preflight independent of the eventual claim/template source. */
    public boolean canGrantPermanentBlessings(UUID playerId, List<RewardEntry> rewards) {
        if (!isWritable()) return false;
        var owned = ownedPermanentBlessingLevels(playerId);
        long count = rewards.stream().filter(BlessingRewardEntry.class::isInstance)
                .map(BlessingRewardEntry.class::cast).filter(BlessingRewardEntry::permanent)
                .map(BlessingRewardEntry::effectId).distinct()
                .filter(effect -> !owned.containsKey(effect)).count();
        return isWritable() && blessings.size() + count <= MAX_PERMANENT_BLESSINGS;
    }

    private boolean canAcquireBlessings(UUID playerId, ResourceLocation godId, BlessingSource source,
            ResourceLocation sourceId, List<RewardEntry> rewards) {
        long count = rewards.stream().filter(BlessingRewardEntry.class::isInstance)
                .map(BlessingRewardEntry.class::cast).filter(BlessingRewardEntry::permanent)
                .map(value -> new BlessingKey(playerId, source, sourceId, godId, value.effectId()))
                .distinct().filter(key -> !blessings.containsKey(key)).count();
        return isWritable() && blessings.size() + count <= MAX_PERMANENT_BLESSINGS;
    }

    public boolean ownsPermanentBlessing(UUID playerId, ResourceLocation godId, BlessingRewardEntry reward) {
        return permanentBlessingsFor(playerId).stream().anyMatch(value -> value.godId().equals(godId)
                && value.effectId().equals(reward.effectId()) && value.amplifier() >= reward.amplifier());
    }

    private void acquireBlessings(UUID playerId, ResourceLocation godId, BlessingSource source,
            ResourceLocation sourceId, Optional<UUID> claimId, List<RewardEntry> rewards, long gameTime) {
        if (gameTime < 0 || !canAcquireBlessings(playerId, godId, source, sourceId, rewards))
            throw new IllegalStateException("Permanent blessing ownership storage unavailable");
        for (RewardEntry reward : rewards) if (reward instanceof BlessingRewardEntry blessing && blessing.permanent()) {
            BlessingKey key = new BlessingKey(playerId, source, sourceId, godId, blessing.effectId());
            PermanentBlessingEntitlement prior = blessings.get(key);
            if (prior == null || prior.amplifier() < blessing.amplifier()) {
                blessings.put(key, new PermanentBlessingEntitlement(playerId, godId, source, sourceId,
                        claimId, blessing.effectId(), blessing.amplifier(), gameTime));
                setDirty();
            }
        }
    }

    /** Only the existing God-owned authored table boundary calls this; no new AI payload authority. */
    void acquireTableBlessings(UUID playerId, ResourceLocation godId, ResourceLocation tableId, int tier,
            List<RewardEntry> rewards, long gameTime) {
        ensureWritable();
        acquireBlessings(playerId, godId, BlessingSource.NPC_TABLE,
                ResourceLocation.fromNamespaceAndPath(tableId.getNamespace(), tableId.getPath() + "/tier_" + tier),
                Optional.empty(), rewards, gameTime);
    }

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
        if (hasPrunedBlessingReceipt(playerId, sourceId)) return false;
        if (findBySource(playerId, sourceId).isPresent() || claims.size() < MAX_CLAIMS) {
            return true;
        }
        return claims.values().stream().anyMatch(RewardClaim::fullyClaimed);
    }

    /** Reserve capacity for a whole settlement before creating any receipt. Server-thread only. */
    public boolean canCreateBatch(java.util.Set<UUID> players, ResourceLocation sourceId) {
        if (!isWritable()) return false;
        if (players.stream().anyMatch(player -> hasPrunedBlessingReceipt(player, sourceId))) return false;
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
        if (hasPrunedBlessingReceipt(claim.playerId(), claim.sourceId()))
            throw new IllegalStateException("Reward source already granted a permanent blessing; its completed receipt was pruned");
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
            checkEntitlementCapacity(claim, claim.automaticRewards(), gameTime);
            acquireWatches(claim, claim.automaticRewards(), gameTime);
            acquireBlessings(claim.playerId(), claim.godId(), BlessingSource.QUEST_CLAIM, claim.sourceId(),
                    Optional.of(claim.claimId()), claim.automaticRewards(), gameTime);
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
        checkEntitlementCapacity(claim, rewards, gameTime);
        acquireWatches(claim, rewards, gameTime);
        acquireBlessings(claim.playerId(), claim.godId(), BlessingSource.QUEST_CLAIM, claim.sourceId(),
                Optional.of(claim.claimId()), rewards, gameTime);
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
        ListTag ownedBlessings = new ListTag();
        for (PermanentBlessingEntitlement blessing : blessings.values()) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("playerId", blessing.playerId()); entry.putString("godId", blessing.godId().toString());
            entry.putString("source", blessing.source().name()); entry.putString("sourceId", blessing.sourceId().toString());
            blessing.claimId().ifPresent(value -> entry.putUUID("claimId", value));
            entry.putString("effectId", blessing.effectId().toString()); entry.putInt("amplifier", blessing.amplifier());
            entry.putLong("grantGameTime", blessing.grantGameTime()); ownedBlessings.add(entry);
        }
        tag.put("permanentBlessings", ownedBlessings);
        ListTag suppressed = new ListTag();
        for (SuppressedBlessing blessing : suppressedBlessings) {
            CompoundTag entry = new CompoundTag(); entry.putUUID("playerId", blessing.playerId());
            entry.putString("effectId", blessing.effectId().toString()); suppressed.add(entry);
        }
        tag.put("suppressedBlessings", suppressed);
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
            if (tag.getInt("dataVersion") >= 4) {
                ListTag ownedBlessings = requireList(tag, "permanentBlessings");
                if (ownedBlessings.size() > MAX_PERMANENT_BLESSINGS)
                    throw new IllegalArgumentException("Too many permanent blessing entitlements");
                for (int i = 0; i < ownedBlessings.size(); i++) {
                    CompoundTag entry = ownedBlessings.getCompound(i);
                    UUID playerId = requireUuid(entry, "playerId");
                    ResourceLocation godId = id(entry.getString("godId"), "godId");
                    ResourceLocation sourceId = id(entry.getString("sourceId"), "sourceId");
                    ResourceLocation effectId = id(entry.getString("effectId"), "effectId");
                    BlessingSource source = BlessingSource.valueOf(entry.getString("source"));
                    Optional<UUID> claimId = source == BlessingSource.QUEST_CLAIM
                            ? Optional.of(requireUuid(entry, "claimId")) : Optional.empty();
                    if (source == BlessingSource.NPC_TABLE && entry.contains("claimId"))
                        throw new IllegalArgumentException("Authored table blessing cannot invent a quest receipt");
                    if (!entry.contains("amplifier", Tag.TAG_INT) || !entry.contains("grantGameTime", Tag.TAG_LONG)
                            || entry.getLong("grantGameTime") < 0)
                        throw new IllegalArgumentException("Invalid permanent blessing level/time");
                    BlessingRewardEntry reward = new BlessingRewardEntry(effectId, -1, entry.getInt("amplifier"));
                    var value = new PermanentBlessingEntitlement(playerId, godId, source, sourceId, claimId,
                            effectId, reward.amplifier(), entry.getLong("grantGameTime"));
                    if (state.blessings.putIfAbsent(new BlessingKey(playerId, source, sourceId, godId, effectId), value) != null)
                        throw new IllegalArgumentException("Duplicate permanent blessing source");
                }
                ListTag suppressed = requireList(tag, "suppressedBlessings");
                if (suppressed.size() > MAX_PERMANENT_BLESSINGS)
                    throw new IllegalArgumentException("Too many suppressed blessings");
                var ownedEffects = new java.util.HashSet<SuppressedBlessing>();
                state.blessings.values().forEach(value -> ownedEffects.add(new SuppressedBlessing(value.playerId(), value.effectId())));
                for (int i = 0; i < suppressed.size(); i++) {
                    CompoundTag entry = suppressed.getCompound(i);
                    var value = new SuppressedBlessing(requireUuid(entry, "playerId"), id(entry.getString("effectId"), "effectId"));
                    if (!ownedEffects.contains(value)
                            || !state.suppressedBlessings.add(value))
                        throw new IllegalArgumentException("Invalid suppressed blessing ownership");
                }
            }
            for(RewardClaim claim:state.claims.values()) {
                var delivered=new ArrayList<RewardEntry>();
                if(claim.automaticGranted())delivered.addAll(claim.automaticRewards());
                claim.selectedOptionId().ifPresent(selected->claim.choices().stream().filter(c->c.optionId().equals(selected))
                        .findFirst().ifPresent(c->delivered.addAll(c.rewards())));
                for(RewardEntry reward:delivered)if(reward instanceof WatchRewardEntry watch && !state.hasWatch(claim.playerId(),watch.godId()))
                    throw new IllegalArgumentException("Granted watch receipt missing entitlement");
                for (RewardEntry reward : delivered) if (reward instanceof BlessingRewardEntry blessing && blessing.permanent()) {
                    var owned = state.blessings.get(new BlessingKey(claim.playerId(), BlessingSource.QUEST_CLAIM,
                            claim.sourceId(), claim.godId(), blessing.effectId()));
                    if (owned == null || owned.amplifier() < blessing.amplifier()
                            || !owned.claimId().equals(Optional.of(claim.claimId())))
                        throw new IllegalArgumentException("Granted permanent blessing receipt missing entitlement");
                }
            }
        } catch (RuntimeException exception) {
            state.claims.clear();
            state.watches.clear();
            state.blessings.clear();
            state.suppressedBlessings.clear();
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

    private void checkEntitlementCapacity(RewardClaim claim, List<RewardEntry> rewards, long gameTime) {
        if (gameTime < 0 || !canGrantWatches(claim.playerId(), rewards)
                || !canAcquireBlessings(claim.playerId(), claim.godId(), BlessingSource.QUEST_CLAIM, claim.sourceId(), rewards))
            throw new IllegalStateException("Reward entitlement storage unavailable");
    }

    private boolean hasPrunedBlessingReceipt(UUID playerId, ResourceLocation sourceId) {
        return findBySource(playerId, sourceId).isEmpty() && blessings.values().stream().anyMatch(value ->
                value.source() == BlessingSource.QUEST_CLAIM && value.playerId().equals(playerId) && value.sourceId().equals(sourceId));
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
