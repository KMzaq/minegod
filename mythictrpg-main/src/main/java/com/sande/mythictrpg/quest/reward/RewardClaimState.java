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
import com.sande.mythictrpg.quest.reward.blessing.ResolvedBlessing;

/** Durable reward receipts and non-expiring player choices. */
public final class RewardClaimState extends SavedData {
    // v6 preserves the authoritative acquisition channel separately from the receipt mechanism.
    public static final int CURRENT_DATA_VERSION = 7;
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
    private final Map<NamedBlessingKey, AcquiredBlessing> acquiredBlessings = new LinkedHashMap<>();
    private record DynamicSource(UUID playerId, ResourceLocation sourceId) { }
    private final java.util.Set<DynamicSource> settledDynamicSources = new java.util.LinkedHashSet<>();
    private final Map<UUID,ProgressionEntitlement> progression = new LinkedHashMap<>();
    public record ProgressionEntitlement(UUID entitlementId,UUID playerId,UUID claimId,ResourceLocation sourceId,
            ResourceLocation godId,ProgressionRewardEntry.Kind kind,ResourceLocation definitionId,RewardOrigin origin,String bundle,int rewardIndex) { }
    public List<ProgressionEntitlement> progressionFor(UUID playerId) {
        ensureWritable(); return progression.values().stream().filter(value -> value.playerId().equals(playerId)).toList();
    }
    public boolean ownsProgression(UUID playerId,ProgressionRewardEntry.Kind kind,ResourceLocation id) {
        return progressionFor(playerId).stream().anyMatch(value -> value.kind()==kind && value.definitionId().equals(id));
    }
    public boolean canGrantProgression(List<RewardEntry> rewards) {
        return isWritable() && progression.size()+rewards.stream().filter(ProgressionRewardEntry.class::isInstance).count()<=MAX_PERMANENT_BLESSINGS;
    }
    private void acquireProgression(RewardClaim claim,List<RewardEntry> rewards,String bundle) {
        for(int i=0;i<rewards.size();i++) if(rewards.get(i) instanceof ProgressionRewardEntry reward) {
            UUID id=UUID.nameUUIDFromBytes((claim.claimId()+"|"+bundle+"|"+i).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var value=new ProgressionEntitlement(id,claim.playerId(),claim.claimId(),claim.sourceId(),claim.godId(),reward.kind(),reward.definitionId(),claim.origin(),bundle,i);
            var prior=progression.putIfAbsent(id,value);
            if(prior!=null && !prior.equals(value)) throw new IllegalStateException("Progression entitlement identity changed");
        }
    }

    public enum BlessingSource { QUEST_CLAIM, NPC_TABLE }
    private record BlessingKey(UUID playerId, BlessingSource source, ResourceLocation sourceId,
            ResourceLocation godId, ResourceLocation effectId) { }
    private record SuppressedBlessing(UUID playerId, ResourceLocation effectId) { }
    public record PermanentBlessingEntitlement(UUID playerId, ResourceLocation godId,
            BlessingSource source, ResourceLocation sourceId, Optional<UUID> claimId,
            ResourceLocation effectId, int amplifier, long grantGameTime, RewardOrigin origin) { }
    private record NamedBlessingKey(UUID playerId, BlessingSource source, ResourceLocation sourceId,
            ResourceLocation definitionId) { }
    public record AcquiredBlessing(UUID playerId, BlessingSource source, ResourceLocation sourceId,
            Optional<UUID> claimId, ResolvedBlessing blessing, boolean active, long grantGameTime, RewardOrigin origin) { }

    /** Route history: only direct raid acquisitions are exempt; milk and inactive effects do not erase acquisition. */
    public boolean hasNonRaidBlessing(UUID playerId) {
        return permanentBlessingsFor(playerId).stream().anyMatch(value -> value.origin().countsForNonRaidBlessingRoute())
                || acquiredBlessingsFor(playerId).stream().anyMatch(value -> value.origin().countsForNonRaidBlessingRoute());
    }

    public List<AcquiredBlessing> acquiredBlessingsFor(UUID playerId) {
        ensureWritable();
        return acquiredBlessings.values().stream().filter(value -> value.playerId().equals(playerId)).toList();
    }

    public double blessingIntrinsicPower(UUID playerId) {
        return acquiredBlessingsFor(playerId).stream().mapToDouble(value -> value.blessing().intrinsicScore()).sum();
    }

    public boolean ownsResolvedBlessing(UUID playerId, ResolvedBlessing blessing) {
        return acquiredBlessingsFor(playerId).stream().anyMatch(value -> value.blessing().equals(blessing));
    }
    public boolean hasGrantedLuckyReward(UUID playerId, ResolvedLuckyRewardEntry reward) {
        return claims.values().stream().filter(claim -> claim.playerId().equals(playerId)).anyMatch(claim ->
                claim.automaticGranted() && claim.automaticRewards().contains(reward)
                || claim.selectedOptionId().isPresent() && claim.choices().stream()
                .filter(choice -> claim.selectedOptionId().orElseThrow().equals(choice.optionId()))
                .anyMatch(choice -> choice.rewards().contains(reward)));
    }

    public boolean hasGrantedCurrencyReward(UUID playerId,ResolvedCurrencyRewardEntry reward) {
        return claims.values().stream().filter(claim -> claim.playerId().equals(playerId)).anyMatch(claim ->
                claim.automaticGranted() && claim.automaticRewards().contains(reward)
                || claim.selectedOptionId().isPresent() && claim.choices().stream()
                .filter(choice -> claim.selectedOptionId().orElseThrow().equals(choice.optionId())).anyMatch(choice -> choice.rewards().contains(reward)));
    }
    private static boolean hasDynamicOffer(RewardClaim claim) {
        return java.util.stream.Stream.concat(claim.automaticRewards().stream(),
                claim.choices().stream().flatMap(choice -> choice.rewards().stream()))
                .anyMatch(reward -> reward instanceof ResolvedLuckyRewardEntry || reward instanceof ResolvedBlessingRewardEntry
                        || reward instanceof ProgressionRewardEntry || reward instanceof ResolvedCurrencyRewardEntry
                        || EquipmentRewardEntry.resolvedEquipment(reward));
    }

    private void retainDynamicSource(RewardClaim claim) {
        if (hasDynamicOffer(claim))
            settledDynamicSources.add(new DynamicSource(claim.playerId(), claim.sourceId()));
    }

    /** Possession survives milk, death, logout and completed-claim pruning. Never infer it from active effects. */
    public List<PermanentBlessingEntitlement> permanentBlessingsFor(UUID playerId) {
        ensureWritable();
        return blessings.values().stream().filter(value -> value.playerId().equals(playerId)).toList();
    }

    public Map<ResourceLocation, Integer> ownedPermanentBlessingLevels(UUID playerId) {
        Map<ResourceLocation, Integer> levels = new LinkedHashMap<>();
        permanentBlessingsFor(playerId).forEach(value -> levels.merge(value.effectId(), value.amplifier(), Math::max));
        acquiredBlessingsFor(playerId).stream().filter(AcquiredBlessing::active).forEach(value ->
                value.blessing().effectId().ifPresent(effect -> levels.merge(effect, value.blessing().amplifier(), Math::max)));
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
        long named = rewards.stream().filter(ResolvedBlessingRewardEntry.class::isInstance)
                .map(ResolvedBlessingRewardEntry.class::cast).map(ResolvedBlessingRewardEntry::blessing)
                .distinct().filter(value -> !ownsResolvedBlessing(playerId, value)).count();
        return isWritable() && blessings.size() + acquiredBlessings.size() + count + named <= MAX_PERMANENT_BLESSINGS;
    }

    private boolean canAcquireBlessings(UUID playerId, ResourceLocation godId, BlessingSource source,
            ResourceLocation sourceId, List<RewardEntry> rewards, RewardOrigin origin) {
        var seen = new java.util.HashMap<NamedBlessingKey, ResolvedBlessing>();
        for (RewardEntry reward : rewards) {
            if (RewardResolutionService.requiresResolution(reward))
                throw new IllegalArgumentException("Unresolved reward cannot be acquired");
            if (reward instanceof ResolvedBlessingRewardEntry resolved) {
                var value = resolved.blessing();
                if (!value.godId().equals(godId)) throw new IllegalArgumentException("Blessing belongs to another God");
                var key = new NamedBlessingKey(playerId, source, sourceId, value.definitionId());
                if (seen.putIfAbsent(key, value) != null) throw new IllegalArgumentException("Duplicate acquired blessing in bundle");
                var prior = acquiredBlessings.get(key);
                if (prior != null && (!prior.blessing().equals(value) || prior.origin() != origin))
                    throw new IllegalArgumentException("An acquired blessing outcome cannot be changed");
            }
            if (reward instanceof BlessingRewardEntry blessing && blessing.permanent()) {
                var prior = blessings.get(new BlessingKey(playerId, source, sourceId, godId, blessing.effectId()));
                if (prior != null && prior.origin() != origin && prior.origin() != RewardOrigin.LEGACY_UNSPECIFIED)
                    throw new IllegalArgumentException("Blessing acquisition origin cannot change");
            }
        }
        long count = rewards.stream().filter(BlessingRewardEntry.class::isInstance)
                .map(BlessingRewardEntry.class::cast).filter(BlessingRewardEntry::permanent)
                .map(value -> new BlessingKey(playerId, source, sourceId, godId, value.effectId()))
                .distinct().filter(key -> !blessings.containsKey(key)).count();
        long named = rewards.stream().filter(ResolvedBlessingRewardEntry.class::isInstance)
                .map(ResolvedBlessingRewardEntry.class::cast).map(ResolvedBlessingRewardEntry::blessing)
                .peek(value -> { if (!value.godId().equals(godId)) throw new IllegalArgumentException("Blessing belongs to another God"); })
                .map(value -> new NamedBlessingKey(playerId, source, sourceId, value.definitionId()))
                .distinct().filter(key -> !acquiredBlessings.containsKey(key)).count();
        return isWritable() && blessings.size() + acquiredBlessings.size() + count + named <= MAX_PERMANENT_BLESSINGS;
    }

    public boolean ownsPermanentBlessing(UUID playerId, ResourceLocation godId, BlessingRewardEntry reward) {
        return permanentBlessingsFor(playerId).stream().anyMatch(value -> value.godId().equals(godId)
                && value.effectId().equals(reward.effectId()) && value.amplifier() >= reward.amplifier());
    }

    private void acquireBlessings(UUID playerId, ResourceLocation godId, BlessingSource source,
            ResourceLocation sourceId, Optional<UUID> claimId, List<RewardEntry> rewards, long gameTime, RewardOrigin origin) {
        if (gameTime < 0 || !canAcquireBlessings(playerId, godId, source, sourceId, rewards, origin))
            throw new IllegalStateException("Permanent blessing ownership storage unavailable");
        for (RewardEntry reward : rewards) if (reward instanceof BlessingRewardEntry blessing && blessing.permanent()) {
            BlessingKey key = new BlessingKey(playerId, source, sourceId, godId, blessing.effectId());
            PermanentBlessingEntitlement prior = blessings.get(key);
            if (prior == null || prior.amplifier() < blessing.amplifier()) {
                blessings.put(key, new PermanentBlessingEntitlement(playerId, godId, source, sourceId,
                        claimId, blessing.effectId(), blessing.amplifier(), gameTime, prior == null ? origin : prior.origin()));
                setDirty();
            }
        }
        for (RewardEntry reward : rewards) if (reward instanceof ResolvedBlessingRewardEntry resolved) {
            var key = new NamedBlessingKey(playerId, source, sourceId, resolved.blessing().definitionId());
            var prior = acquiredBlessings.get(key);
            if (prior != null && !prior.blessing().equals(resolved.blessing()))
                throw new IllegalStateException("An acquired blessing outcome cannot be changed");
            acquiredBlessings.putIfAbsent(key, new AcquiredBlessing(playerId, source, sourceId, claimId,
                    resolved.blessing(), true, gameTime, origin));
            setDirty();
        }
    }

    /** Only the existing God-owned authored table boundary calls this; no new AI payload authority. */
    void acquireTableBlessings(UUID playerId, ResourceLocation godId, ResourceLocation tableId, int tier,
            List<RewardEntry> rewards, long gameTime) {
        ensureWritable();
        acquireBlessings(playerId, godId, BlessingSource.NPC_TABLE,
                ResourceLocation.fromNamespaceAndPath(tableId.getNamespace(), tableId.getPath() + "/tier_" + tier),
                Optional.empty(), rewards, gameTime, RewardOrigin.NPC_DIRECT);
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
    /** The only permitted receipt edit: unresolved opt-in becomes one persisted result, before delivery. */
    public RewardClaim freezeAdaptiveRewards(UUID claimId, ResolvedQuestReward frozen) {
        RewardClaim prior = requireWritableClaim(claimId);
        if (prior.automaticGranted() || prior.selectedOptionId().isPresent())
            throw new IllegalStateException("Delivered rewards cannot be resolved again");
        verifyResolution(prior.automaticRewards(), frozen.automaticRewards(), prior.godId());
        if (!prior.selectionTitle().equals(frozen.selectionTitle()) || prior.choices().size() != frozen.choices().size())
            throw new IllegalArgumentException("Reward offer identity changed");
        for (int i = 0; i < prior.choices().size(); i++) {
            var before = prior.choices().get(i); var after = frozen.choices().get(i);
            if (!before.optionId().equals(after.optionId()) || !before.displayName().equals(after.displayName()))
                throw new IllegalArgumentException("Reward choice identity changed");
            verifyResolution(before.rewards(), after.rewards(), prior.godId());
        }
        var result = new RewardClaim(prior.claimId(), prior.playerId(), prior.godId(), prior.sourceId(),
                prior.selectionTitle(), frozen.automaticRewards(), frozen.choices(), false, Optional.empty(), prior.createdGameTime(), prior.origin());
        claims.put(claimId, result); setDirty(); return result;
    }

    private static void verifyResolution(List<RewardEntry> before, List<RewardEntry> after, ResourceLocation godId) {
        if (before.size() != after.size()) throw new IllegalArgumentException("Reward bundle size changed");
        for (int i = 0; i < before.size(); i++) {
            if (before.get(i) instanceof EquipmentRewardEntry equipment) {
                if (!equipment.resolve().equals(after.get(i)))
                    throw new IllegalArgumentException("Invalid frozen equipment result");
            } else if (before.get(i) instanceof AdaptiveBlessingRewardEntry adaptive) {
                if (!(after.get(i) instanceof ResolvedBlessingRewardEntry resolved)
                        || !adaptive.definitionId().equals(resolved.blessing().definitionId())
                        || !godId.equals(resolved.blessing().godId()))
                    throw new IllegalArgumentException("Invalid adaptive blessing result");
            } else if (before.get(i) instanceof LuckyRewardEntry lucky) {
                if (!(after.get(i) instanceof ResolvedLuckyRewardEntry resolved) || !lucky.poolId().equals(resolved.poolId()))
                    throw new IllegalArgumentException("Invalid frozen luck result");
            } else if (before.get(i) instanceof CurrencyRewardEntry currency && after.get(i) instanceof ResolvedCurrencyRewardEntry resolved) {
                if(currency.amount()!=resolved.baseAmount()) throw new IllegalArgumentException("Base currency changed");
            } else if (!before.get(i).equals(after.get(i))) throw new IllegalArgumentException("Fixed reward changed");
        }
    }
    public RewardClaim markAutomaticGranted(UUID claimId, long gameTime) {
        RewardClaim claim = requireWritableClaim(claimId);
        if (!claim.automaticGranted()) {
            if (claim.choices().stream().flatMap(choice -> choice.rewards().stream())
                    .anyMatch(RewardResolutionService::requiresResolution))
                throw new IllegalStateException("Resolve all adaptive choices before delivery");
            checkEntitlementCapacity(claim, claim.automaticRewards(), gameTime);
            acquireProgression(claim,claim.automaticRewards(),"automatic");
            acquireWatches(claim, claim.automaticRewards(), gameTime);
            acquireBlessings(claim.playerId(), claim.godId(), BlessingSource.QUEST_CLAIM, claim.sourceId(),
                    Optional.of(claim.claimId()), claim.automaticRewards(), gameTime, claim.origin());
            retainDynamicSource(claim);
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
        acquireProgression(claim,rewards,"choice/"+optionId);
        acquireWatches(claim, rewards, gameTime);
        acquireBlessings(claim.playerId(), claim.godId(), BlessingSource.QUEST_CLAIM, claim.sourceId(),
                Optional.of(claim.claimId()), rewards, gameTime, claim.origin());
        retainDynamicSource(claim);
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
            entry.putString("origin", blessing.origin().name());
            blessing.claimId().ifPresent(value -> entry.putUUID("claimId", value));
            entry.putString("effectId", blessing.effectId().toString()); entry.putInt("amplifier", blessing.amplifier());
            entry.putLong("grantGameTime", blessing.grantGameTime()); ownedBlessings.add(entry);
        }
        tag.put("permanentBlessings", ownedBlessings);
        ListTag named = new ListTag();
        for (AcquiredBlessing value : acquiredBlessings.values()) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("playerId", value.playerId()); entry.putString("source", value.source().name());
            entry.putString("sourceId", value.sourceId().toString());
            entry.putString("origin", value.origin().name());
            value.claimId().ifPresent(claim -> entry.putUUID("claimId", claim));
            entry.put("result", RewardEntryCodec.save(new ResolvedBlessingRewardEntry(value.blessing())));
            entry.putBoolean("active", value.active()); entry.putLong("grantGameTime", value.grantGameTime());
            named.add(entry);
        }
        tag.put("acquiredBlessings", named);
        ListTag settled = new ListTag();
        for (var source : settledDynamicSources) {
            var entry = new CompoundTag(); entry.putUUID("playerId", source.playerId());
            entry.putString("sourceId", source.sourceId().toString()); settled.add(entry);
        }
        tag.put("settledDynamicSources", settled);
        ListTag suppressed = new ListTag();
        for (SuppressedBlessing blessing : suppressedBlessings) {
            CompoundTag entry = new CompoundTag(); entry.putUUID("playerId", blessing.playerId());
            entry.putString("effectId", blessing.effectId().toString()); suppressed.add(entry);
        }
        tag.put("suppressedBlessings", suppressed);
        ListTag progressionList=new ListTag();
        for(var value:progression.values()) {
            CompoundTag entry=new CompoundTag(); entry.putUUID("id",value.entitlementId()); entry.putUUID("playerId",value.playerId());
            entry.putUUID("claimId",value.claimId()); entry.putString("sourceId",value.sourceId().toString());
            entry.putString("godId",value.godId().toString()); entry.putString("kind",value.kind().name());
            entry.putString("definitionId",value.definitionId().toString()); entry.putString("origin",value.origin().name());
            entry.putString("bundle",value.bundle()); entry.putInt("rewardIndex",value.rewardIndex());
            progressionList.add(entry);
        }
        tag.put("progressionRewards",progressionList);
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
            if(tag.getInt("dataVersion")>=7) {
                ListTag acquired=requireList(tag,"progressionRewards");
                if(acquired.size()>MAX_PERMANENT_BLESSINGS) throw new IllegalArgumentException("Too many progression entitlements");
                for(int i=0;i<acquired.size();i++) {
                    CompoundTag entry=acquired.getCompound(i);
                    var value=new ProgressionEntitlement(requireUuid(entry,"id"),requireUuid(entry,"playerId"),requireUuid(entry,"claimId"),
                            id(entry.getString("sourceId"),"sourceId"),id(entry.getString("godId"),"godId"),
                            ProgressionRewardEntry.Kind.valueOf(entry.getString("kind")),id(entry.getString("definitionId"),"definitionId"),
                            RewardOrigin.valueOf(entry.getString("origin")),entry.getString("bundle"),entry.getInt("rewardIndex"));
                    if(!entry.contains("bundle",Tag.TAG_STRING)||!entry.contains("rewardIndex",Tag.TAG_INT)||value.rewardIndex()<0||value.rewardIndex()>31
                            ||!value.bundle().equals("automatic")&&(!value.bundle().startsWith("choice/")||ResourceLocation.tryParse(value.bundle().substring(7))==null)
                            ||!value.entitlementId().equals(UUID.nameUUIDFromBytes((value.claimId()+"|"+value.bundle()+"|"+value.rewardIndex()).getBytes(java.nio.charset.StandardCharsets.UTF_8))))
                        throw new IllegalArgumentException("Noncanonical progression entitlement ID");
                    if(state.progression.putIfAbsent(value.entitlementId(),value)!=null) throw new IllegalArgumentException("Duplicate progression entitlement");
                }
            }
            if (list.size() > MAX_CLAIMS) {
                throw new IllegalArgumentException("Too many persisted reward claims");
            }
            for (int index = 0; index < list.size(); index++) {
                RewardClaim claim = loadClaim(list.getCompound(index), tag.getInt("dataVersion"));
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
                    BlessingRewardEntry reward = RewardEntryCodec.restoreBlessing(effectId, -1, entry.getInt("amplifier"));
                    var value = new PermanentBlessingEntitlement(playerId, godId, source, sourceId, claimId,
                            effectId, reward.amplifier(), entry.getLong("grantGameTime"), loadOrigin(entry, tag.getInt("dataVersion")));
                    validateOriginSource(value.source(), value.origin());
                    if (state.blessings.putIfAbsent(new BlessingKey(playerId, source, sourceId, godId, effectId), value) != null)
                        throw new IllegalArgumentException("Duplicate permanent blessing source");
                }
                if (tag.getInt("dataVersion") >= 5) {
                    ListTag settled = requireList(tag, "settledDynamicSources");
                    if (settled.size() > MAX_PERMANENT_BLESSINGS) throw new IllegalArgumentException("Too many dynamic reward sources");
                    for (int i = 0; i < settled.size(); i++) {
                        var entry = settled.getCompound(i);
                        if (!state.settledDynamicSources.add(new DynamicSource(requireUuid(entry, "playerId"),
                                id(entry.getString("sourceId"), "sourceId"))))
                            throw new IllegalArgumentException("Duplicate settled dynamic source");
                    }
                    ListTag named = requireList(tag, "acquiredBlessings");
                    if (named.size() + state.blessings.size() > MAX_PERMANENT_BLESSINGS)
                        throw new IllegalArgumentException("Too many acquired blessings");
                    for (int i = 0; i < named.size(); i++) {
                        var entry = named.getCompound(i);
                        if (!entry.contains("result", Tag.TAG_COMPOUND) || !entry.contains("active", Tag.TAG_BYTE)
                                || !entry.contains("grantGameTime", Tag.TAG_LONG) || entry.getLong("grantGameTime") < 0)
                            throw new IllegalArgumentException("Invalid acquired blessing record");
                        if (!(RewardEntryCodec.load(entry.getCompound("result")) instanceof ResolvedBlessingRewardEntry resolved))
                            throw new IllegalArgumentException("Acquired blessing is unresolved");
                        var source = BlessingSource.valueOf(entry.getString("source"));
                        var claimId = source == BlessingSource.QUEST_CLAIM ? Optional.of(requireUuid(entry, "claimId")) : Optional.<UUID>empty();
                        if (source == BlessingSource.NPC_TABLE && entry.contains("claimId"))
                            throw new IllegalArgumentException("Table blessing cannot invent a claim");
                        var value = new AcquiredBlessing(requireUuid(entry, "playerId"), source,
                                id(entry.getString("sourceId"), "sourceId"), claimId, resolved.blessing(),
                                entry.getBoolean("active"), entry.getLong("grantGameTime"), loadOrigin(entry, tag.getInt("dataVersion")));
                        validateOriginSource(value.source(), value.origin());
                        if (state.acquiredBlessings.putIfAbsent(new NamedBlessingKey(value.playerId(), source,
                                value.sourceId(), value.blessing().definitionId()), value) != null)
                            throw new IllegalArgumentException("Duplicate acquired blessing");
                    }
                }
                ListTag suppressed = requireList(tag, "suppressedBlessings");
                if (suppressed.size() > MAX_PERMANENT_BLESSINGS)
                    throw new IllegalArgumentException("Too many suppressed blessings");
                var ownedEffects = new java.util.HashSet<SuppressedBlessing>();
                state.blessings.values().forEach(value -> ownedEffects.add(new SuppressedBlessing(value.playerId(), value.effectId())));
                state.acquiredBlessings.values().forEach(value -> value.blessing().effectId().ifPresent(effect ->
                        ownedEffects.add(new SuppressedBlessing(value.playerId(), effect))));
                for (int i = 0; i < suppressed.size(); i++) {
                    CompoundTag entry = suppressed.getCompound(i);
                    var value = new SuppressedBlessing(requireUuid(entry, "playerId"), id(entry.getString("effectId"), "effectId"));
                    if (!ownedEffects.contains(value)
                            || !state.suppressedBlessings.add(value))
                        throw new IllegalArgumentException("Invalid suppressed blessing ownership");
                }
            }
            for(RewardClaim claim:state.claims.values()) {
                if(claim.automaticGranted()) state.verifyProgression(claim,claim.automaticRewards(),"automatic");
                if(claim.selectedOptionId().isPresent()) {
                    var selected=claim.selectedOptionId().orElseThrow();
                    var option=claim.choices().stream().filter(value -> value.optionId().equals(selected)).findFirst().orElseThrow();
                    state.verifyProgression(claim,option.rewards(),"choice/"+selected);
                }
                var offer = new ResolvedQuestReward(claim.selectionTitle(), claim.automaticRewards(), claim.choices());
                if (claim.automaticGranted() && RewardResolutionService.unresolved(offer))
                    throw new IllegalArgumentException("Granted automatic receipt has unresolved choices");
                if (tag.getInt("dataVersion") < 6 && claim.automaticGranted() && hasDynamicOffer(claim)) {
                    state.settledDynamicSources.add(new DynamicSource(claim.playerId(), claim.sourceId()));
                    if (state.settledDynamicSources.size() > MAX_PERMANENT_BLESSINGS)
                        throw new IllegalArgumentException("Too many migrated dynamic sources");
                }
                if (claim.automaticGranted() && hasDynamicOffer(claim)
                        && !state.settledDynamicSources.contains(new DynamicSource(claim.playerId(), claim.sourceId())))
                    throw new IllegalArgumentException("Frozen dynamic offer missing settled source");
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
                            || !owned.claimId().equals(Optional.of(claim.claimId())) || owned.origin() != claim.origin())
                        throw new IllegalArgumentException("Granted permanent blessing receipt missing entitlement");
                }
                for (RewardEntry reward : delivered) {
                    if (RewardResolutionService.requiresResolution(reward))
                        throw new IllegalArgumentException("Delivered unresolved reward");
                    if (reward instanceof ResolvedBlessingRewardEntry resolved) {
                        var owned = state.acquiredBlessings.get(new NamedBlessingKey(claim.playerId(), BlessingSource.QUEST_CLAIM,
                                claim.sourceId(), resolved.blessing().definitionId()));
                        if (owned == null || !owned.blessing().equals(resolved.blessing())
                                || !resolved.blessing().godId().equals(claim.godId())
                                || !owned.claimId().equals(Optional.of(claim.claimId())) || owned.origin() != claim.origin())
                            throw new IllegalArgumentException("Granted named blessing receipt missing entitlement");
                    }
                    if (reward instanceof ResolvedLuckyRewardEntry
                            && !state.settledDynamicSources.contains(new DynamicSource(claim.playerId(), claim.sourceId())))
                        throw new IllegalArgumentException("Granted luck reward missing settled source");
                }
            }
            if (tag.getInt("dataVersion") < CURRENT_DATA_VERSION) state.setDirty();
            for(var value:state.progression.values()) {
                var claim=state.claims.get(value.claimId());
                if(claim==null) {
                    if(!state.settledDynamicSources.contains(new DynamicSource(value.playerId(),value.sourceId())))
                        throw new IllegalArgumentException("Progression entitlement missing retained source");
                } else {
                    if(!claim.playerId().equals(value.playerId()) || !claim.godId().equals(value.godId())
                            || !claim.sourceId().equals(value.sourceId()) || claim.origin()!=value.origin())
                        throw new IllegalArgumentException("Progression receipt authority mismatch");
                    List<RewardEntry> bundle;
                    if(value.bundle().equals("automatic")&&claim.automaticGranted())bundle=claim.automaticRewards();
                    else if(claim.selectedOptionId().isPresent()&&value.bundle().equals("choice/"+claim.selectedOptionId().orElseThrow()))
                        bundle=claim.choices().stream().filter(option->option.optionId().equals(claim.selectedOptionId().orElseThrow())).findFirst().orElseThrow().rewards();
                    else throw new IllegalArgumentException("Progression entitlement belongs to an undelivered bundle");
                    if(value.rewardIndex()>=bundle.size()||!(bundle.get(value.rewardIndex()) instanceof ProgressionRewardEntry reward)
                            ||reward.kind()!=value.kind()||!reward.definitionId().equals(value.definitionId()))
                        throw new IllegalArgumentException("Progression entitlement index mismatch");
                }
            }
        } catch (RuntimeException exception) {
            state.claims.clear();
            state.progression.clear();
            state.watches.clear();
            state.blessings.clear();
            state.acquiredBlessings.clear();
            state.settledDynamicSources.clear();
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
        tag.putString("origin", claim.origin().name());
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

    private static RewardClaim loadClaim(CompoundTag tag, int dataVersion) {
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
                tag.getBoolean("automaticGranted"), selected, tag.getLong("createdGameTime"), loadOrigin(tag, dataVersion));
    }

    private static RewardOrigin loadOrigin(CompoundTag entry, int version) {
        if (version < 6) return RewardOrigin.LEGACY_UNSPECIFIED;
        if (!entry.contains("origin", Tag.TAG_STRING)) throw new IllegalArgumentException("Missing reward acquisition origin");
        return RewardOrigin.valueOf(entry.getString("origin"));
    }

    private static void validateOriginSource(BlessingSource source, RewardOrigin origin) {
        if (source == BlessingSource.NPC_TABLE && origin != RewardOrigin.NPC_DIRECT && origin != RewardOrigin.LEGACY_UNSPECIFIED)
            throw new IllegalArgumentException("NPC table acquisition has an impossible reward origin");
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
        if(!canGrantProgression(rewards)) throw new IllegalStateException("Progression entitlement storage unavailable");
        if (hasDynamicOffer(claim)
                && !settledDynamicSources.contains(new DynamicSource(claim.playerId(), claim.sourceId()))
                && settledDynamicSources.size() >= MAX_PERMANENT_BLESSINGS)
            throw new IllegalStateException("Settled dynamic reward source storage unavailable");
        if (gameTime < 0 || !canGrantWatches(claim.playerId(), rewards)
                || !canAcquireBlessings(claim.playerId(), claim.godId(), BlessingSource.QUEST_CLAIM, claim.sourceId(), rewards, claim.origin()))
            throw new IllegalStateException("Reward entitlement storage unavailable");
    }

    private void verifyProgression(RewardClaim claim,List<RewardEntry> rewards,String bundle) {
        for(int i=0;i<rewards.size();i++) if(rewards.get(i) instanceof ProgressionRewardEntry reward) {
            UUID id=UUID.nameUUIDFromBytes((claim.claimId()+"|"+bundle+"|"+i).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var value=progression.get(id);
            if(value==null || !value.playerId().equals(claim.playerId()) || value.kind()!=reward.kind()
                    || !value.definitionId().equals(reward.definitionId()) || !value.claimId().equals(claim.claimId()))
                throw new IllegalArgumentException("Delivered progression reward missing entitlement");
        }
    }

    private boolean hasPrunedBlessingReceipt(UUID playerId, ResourceLocation sourceId) {
        return findBySource(playerId, sourceId).isEmpty() && (blessings.values().stream().anyMatch(value ->
                value.source() == BlessingSource.QUEST_CLAIM && value.playerId().equals(playerId) && value.sourceId().equals(sourceId))
                || acquiredBlessings.values().stream().anyMatch(value -> value.source() == BlessingSource.QUEST_CLAIM
                && value.playerId().equals(playerId) && value.sourceId().equals(sourceId))
                || settledDynamicSources.contains(new DynamicSource(playerId, sourceId)));
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
