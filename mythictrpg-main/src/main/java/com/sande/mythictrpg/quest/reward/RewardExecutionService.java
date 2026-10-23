package com.sande.mythictrpg.quest.reward;

import com.sande.mythictrpg.data.player.PlayerMythDataService;
import com.sande.mythictrpg.data.player.PlayerMythHistoryService;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import com.sande.mythictrpg.economy.CurrencyService;
import com.sande.mythictrpg.economy.CurrencyState;
import com.sande.mythictrpg.shop.ShopCatalogManager;
import com.sande.mythictrpg.shop.ShopService;
import com.sande.mythictrpg.shop.GlobalShopState;

/** Shared validation and execution for all authored reward sources. */
public final class RewardExecutionService {
    private RewardExecutionService() {
    }

    public static Validation validate(List<RewardEntry> rewards, RewardGrantPurpose purpose) {
        Validation authored = validateAuthored(rewards, purpose);
        if (!authored.allowed()) return authored;
        for (RewardEntry reward : rewards) {
            if (reward instanceof EquipmentRewardEntry equipment) {
                var definition=com.sande.mythictrpg.equipment.EquipmentCatalog.find(equipment.definitionId()).orElse(null);
                if(definition==null) return Validation.reject("Equipment definition is missing: " + equipment.definitionId());
                var skill=definition.rule().rightClickSkill();
                if(skill!=null && com.sande.mythictrpg.weaponskill.WeaponSkillDefinitions.INSTANCE.find(skill).isEmpty())
                    return Validation.reject("Equipment weapon skill is missing: " + skill);
            }
            if(reward instanceof ProgressionRewardEntry progression) {
                boolean exists=switch(progression.kind()) {
                    case LEARNED->com.sande.mythictrpg.skills.LearnedSkillDefinitions.INSTANCE.find(progression.definitionId()).isPresent();
                    case ACCESSORY->com.sande.mythictrpg.accessory.AccessoryDefinitions.INSTANCE.find(progression.definitionId()).isPresent();
                    case SPECIAL->com.sande.mythictrpg.specialskill.SpecialSkillDefinitions.INSTANCE.find(progression.definitionId()).isPresent();
                };
                if(!exists) return Validation.reject("Progression reward definition is missing: "+progression.definitionId());
            }
            if (reward instanceof AdaptiveBlessingRewardEntry adaptive
                    && com.sande.mythictrpg.quest.reward.blessing.BlessingDefinitions.INSTANCE.find(adaptive.definitionId()).isEmpty())
                return Validation.reject("Adaptive blessing definition is not registered: " + adaptive.definitionId());
            if (reward instanceof LuckyRewardEntry lucky
                    && com.sande.mythictrpg.quest.reward.luck.LuckDefinitions.INSTANCE.find(lucky.poolId()).isEmpty())
                return Validation.reject("Luck reward pool is not registered: " + lucky.poolId());
            if (reward instanceof WatchRewardEntry watch
                    && com.sande.mythictrpg.data.god.GodDefinitionManager.INSTANCE.find(watch.godId()).isEmpty())
                return Validation.reject("Watch god is not registered: " + watch.godId());
        }
        return Validation.allow();
    }

    /** Reload preparation must not consult another listener's not-yet-applied snapshot. */
    public static Validation validateAuthored(List<RewardEntry> rewards, RewardGrantPurpose purpose) {
        if (rewards == null || rewards.isEmpty() || rewards.size() > 32) {
            return Validation.reject("Reward bundle must contain 1..32 entries");
        }
        for (RewardEntry reward : rewards) {
            if (reward == null) {
                return Validation.reject("Reward bundle contains a null entry");
            }
            if (reward instanceof ResolvedLuckyRewardEntry lucky && !BuiltInRegistries.ITEM.containsKey(lucky.selection().reward().itemId()))
                return Validation.reject("Resolved luck item is not registered");
            if (reward instanceof ResolvedBlessingRewardEntry resolved && resolved.blessing().effectId().isPresent()) {
                var effect = BuiltInRegistries.MOB_EFFECT.get(resolved.blessing().effectId().orElseThrow());
                if (effect == null || effect.isInstantenous()) return Validation.reject("Invalid permanent blessing effect");
            }
            if (reward instanceof WatchRewardEntry watch) {
                if (purpose != RewardGrantPurpose.QUEST)
                    return Validation.reject("Watch entitlements require an authored quest reward claim");
            }
            if (reward instanceof NpcRewardEntry item
                    && !BuiltInRegistries.ITEM.containsKey(item.itemId())) {
                return Validation.reject("Reward item is no longer registered: " + item.itemId());
            }
            if (reward instanceof BlessingRewardEntry blessing
                    && !BuiltInRegistries.MOB_EFFECT.containsKey(blessing.effectId())) {
                return Validation.reject("Reward blessing is no longer registered: " + blessing.effectId());
            }
            if (reward instanceof BlessingRewardEntry blessing && blessing.permanent()
                    && BuiltInRegistries.MOB_EFFECT.get(blessing.effectId()).isInstantenous())
                return Validation.reject("Instant effects cannot be permanent blessings: " + blessing.effectId());
            if (reward instanceof AffinityRewardEntry affinity
                    && purpose == RewardGrantPurpose.AI_ACTION && affinity.amount() > 50) {
                return Validation.reject("AI action affinity rewards may not exceed +50");
            }
        }
        return Validation.allow();
    }

    /** Checks static God/policy references before quest settlement, without selecting for an offline player. */
    public static Validation validateForGod(ResourceLocation godId, List<RewardEntry> rewards, RewardGrantPurpose purpose) {
        Validation validation = validate(rewards, purpose);
        if (!validation.allowed()) return validation;
        for (var reward : rewards) {
            String equipmentError=ProgressionRewards.equipmentError(godId,reward);
            if(equipmentError!=null)return Validation.reject(equipmentError);
            if(reward instanceof ProgressionRewardEntry progression) {
                String error=ProgressionRewards.error(godId,progression); if(error!=null)return Validation.reject(error);
            }
            if (reward instanceof ResolvedBlessingRewardEntry resolved && !resolved.blessing().godId().equals(godId))
                return Validation.reject("Resolved blessing belongs to another God");
            if (!(reward instanceof AdaptiveBlessingRewardEntry adaptive)) continue;
            var definitions = com.sande.mythictrpg.quest.reward.blessing.BlessingDefinitions.INSTANCE;
            var definition = definitions.find(adaptive.definitionId()).orElseThrow();
            if (!definition.godId().equals(godId)) return Validation.reject("Adaptive blessing belongs to another God");
            var god = com.sande.mythictrpg.data.god.GodDefinitionManager.INSTANCE.find(godId).orElse(null);
            if (god == null || god.rank().isEmpty()) return Validation.reject("Adaptive blessing God rank is not authored: " + godId);
            var rank = definitions.rankPolicy(god.rank().orElseThrow()).orElse(null);
            var godPolicy = definitions.godPolicy(godId).orElse(null);
            if (rank == null || godPolicy == null) return Validation.reject("Adaptive blessing God/rank policy is not authored: " + godId);
            boolean feasible = definition.candidates().stream().anyMatch(candidate -> {
                var rule = godPolicy.effects().get(candidate.effectId());
                Integer rankCap = rank.effectCaps().get(candidate.effectId());
                return rule != null && rankCap != null && candidate.level() <= Math.min(rule.maxLevel(), rankCap);
            });
            if (!feasible && definition.fallback() == com.sande.mythictrpg.quest.reward.blessing.BlessingDefinition.Fallback.NONE)
                return Validation.reject("Adaptive blessing has no candidate within its authored God/rank caps");
        }
        return Validation.allow();
    }

    public static Result grant(ServerPlayer player, ResourceLocation godId,
            List<RewardEntry> rewards, RewardGrantPurpose purpose) {
        Validation preflight = validateGrant(player, rewards, purpose);
        if (!preflight.allowed()) return Result.reject(preflight.reason());
        for (RewardEntry entry : rewards) {
            if(entry instanceof ProgressionRewardEntry progression && !RewardClaimState.get(player.server)
                    .ownsProgression(player.getUUID(),progression.kind(),progression.definitionId()))
                return Result.reject("Progression reward has no committed personal acquisition");
            if(entry instanceof ResolvedCurrencyRewardEntry currency && !RewardClaimState.get(player.server).hasGrantedCurrencyReward(player.getUUID(),currency))
                return Result.reject("Gold bonus has no committed personal acquisition");
            if (RewardResolutionService.requiresResolution(entry))
                return Result.reject("Dynamic reward requires a frozen reward claim");
            if (entry instanceof ResolvedLuckyRewardEntry lucky
                    && !RewardClaimState.get(player.server).hasGrantedLuckyReward(player.getUUID(), lucky))
                return Result.reject("Luck reward has no committed personal claim");
            if (entry instanceof ResolvedBlessingRewardEntry resolved
                    && (!resolved.blessing().godId().equals(godId)
                    || !RewardClaimState.get(player.server).ownsResolvedBlessing(player.getUUID(), resolved.blessing())))
                return Result.reject("Named blessing has no committed personal acquisition");
        }
        // Watch ownership is committed together with the claim, never via this unreceipted API.
        for (RewardEntry entry : rewards) if (entry instanceof WatchRewardEntry watch
                && !RewardClaimState.get(player.server).hasWatch(player.getUUID(), watch.godId()))
            return Result.reject("Watch reward has no committed personal claim");
        for (RewardEntry entry : rewards) if (entry instanceof BlessingRewardEntry blessing && blessing.permanent()
                && !RewardClaimState.get(player.server).ownsPermanentBlessing(player.getUUID(), godId, blessing))
            return Result.reject("Permanent blessing has no committed authored grant");
        List<String> descriptions = new ArrayList<>();
        for (RewardEntry reward : rewards) {
            switch (reward) {
                case EquipmentRewardEntry equipment -> throw new IllegalStateException("Unresolved equipment reward");
                case ProgressionRewardEntry progression -> {
                    if(progression.kind()==ProgressionRewardEntry.Kind.ACCESSORY)
                        com.sande.mythictrpg.accessory.RewardAccessoryItems.restoreGranted(player,progression.definitionId());
                }
                case ResolvedCurrencyRewardEntry currency -> CurrencyService.INSTANCE.credit(player.server,player.getUUID(),currency.amount());
                case NpcRewardEntry item -> grantItem(player, item);
                case AffinityRewardEntry affinity -> grantAffinity(player, godId, affinity, purpose);
                case BlessingRewardEntry blessing -> grantBlessing(player, blessing);
                case AdaptiveBlessingRewardEntry adaptive -> throw new IllegalStateException("Unresolved blessing");
                case LuckyRewardEntry lucky -> throw new IllegalStateException("Unresolved luck reward");
                case ResolvedLuckyRewardEntry lucky -> grantItem(player, lucky.selection().reward());
                case ResolvedBlessingRewardEntry resolved -> resolved.blessing().effectId()
                        .ifPresent(effect -> PermanentBlessingRuntime.applyGranted(player, effect));
                case TitleRewardEntry title -> PlayerMythDataService.get(player.server)
                        .unlockTitle(player.getUUID(), title.titleId());
                case CurrencyRewardEntry currency -> CurrencyService.INSTANCE.credit(
                        player.server, player.getUUID(), currency.amount());
                case UnlockShopProductRewardEntry unlock -> ShopService.INSTANCE.unlock(
                        player.server, unlock.key());
                case WatchRewardEntry watch -> { /* Already persisted with the claim. Runtime projects eligibility. */ }
            }
            descriptions.add(reward.description());
        }
        return Result.granted(descriptions);
    }

    /** Recheck live balances/catalogs before recording a non-repeatable claim receipt. */
    public static Validation validateGrant(ServerPlayer player, List<RewardEntry> rewards, RewardGrantPurpose purpose) {
        Validation validation = validate(rewards, purpose);
        if (!validation.allowed()) {
            return validation;
        }
        Validation itemValidation = validateItemComponents(player.registryAccess(), rewards);
        if (!itemValidation.allowed()) return itemValidation;
        if (rewards.stream().anyMatch(WatchRewardEntry.class::isInstance)
                && !RewardClaimState.get(player.server).canGrantWatches(player.getUUID(), rewards))
            return Validation.reject("Watch entitlement storage unavailable; existing ownership is preserved");
        if (rewards.stream().anyMatch(reward -> reward instanceof ResolvedBlessingRewardEntry
                || reward instanceof BlessingRewardEntry blessing && blessing.permanent())
                && !RewardClaimState.get(player.server).canGrantPermanentBlessings(player.getUUID(), rewards))
            return Validation.reject("Permanent blessing ownership storage unavailable");
        long currencyTotal = 0L;
        if(rewards.stream().anyMatch(ProgressionRewardEntry.class::isInstance) && !RewardClaimState.get(player.server).isWritable())
            return Validation.reject("Progression entitlement storage unavailable");
        for (RewardEntry reward : rewards) {
            if(reward instanceof ResolvedCurrencyRewardEntry currency) {
                try { currencyTotal=Math.addExact(currencyTotal,currency.amount()); }
                catch(ArithmeticException overflow) { return Validation.reject("Currency reward total overflow"); }
            }
            if (reward instanceof CurrencyRewardEntry currency) {
                try {
                    currencyTotal = Math.addExact(currencyTotal, currency.amount());
                } catch (ArithmeticException exception) {
                    return Validation.reject("Currency reward total overflow");
                }
            }
            if (reward instanceof UnlockShopProductRewardEntry unlock
                    && ShopCatalogManager.INSTANCE.product(unlock.key()).isEmpty()) {
                return Validation.reject("Shop product is not registered: " + unlock.key());
            }
        }
        long balance = CurrencyService.INSTANCE.balance(player.server, player.getUUID());
        if (currencyTotal > 0L && !CurrencyState.get(player.server).isReady()) {
            return Validation.reject("Currency data is read-only");
        }
        if (rewards.stream().anyMatch(UnlockShopProductRewardEntry.class::isInstance)
                && !GlobalShopState.get(player.server).isReady()) {
            return Validation.reject("Global shop state is read-only");
        }
        if (currencyTotal > CurrencyState.MAX_BALANCE - balance) {
            return Validation.reject("Currency reward would exceed the maximum balance");
        }
        return Validation.allow();
    }

    private static void grantItem(ServerPlayer player, NpcRewardEntry reward) {
        ItemStack stack = reward.createStack(player.registryAccess());
        ItemStack history = stack.copy();
        try {
            int before = java.util.stream.IntStream.range(0, player.getInventory().getContainerSize()).mapToObj(player.getInventory()::getItem)
                    .filter(item -> ItemStack.isSameItemSameComponents(item, history))
                    .mapToInt(ItemStack::getCount).sum();
            player.getInventory().add(stack);
            int after = java.util.stream.IntStream.range(0, player.getInventory().getContainerSize()).mapToObj(player.getInventory()::getItem)
                    .filter(item -> ItemStack.isSameItemSameComponents(item, history))
                    .mapToInt(ItemStack::getCount).sum();
            // Creative overflow may consume the input without inserting it. Only a positive delta or live dropped entity proves delivery.
            boolean delivered = after > before;
            if (!stack.isEmpty()) {
                var dropped = player.drop(stack, false);
                delivered |= dropped != null && !dropped.isRemoved();
            }
            if (delivered) com.sande.mythictrpg.power.CombatPowerRuntime.rewardItemDelivered(player, history);
            else com.sande.mythictrpg.power.CombatPowerRuntime.rewardDeliveryUncertain(player);
        } catch (RuntimeException deliveryFailure) {
            com.sande.mythictrpg.power.CombatPowerRuntime.rewardDeliveryUncertain(player);
            throw deliveryFailure;
        }
        PlayerMythHistoryService.recordItemObtained(player, history);
    }

    /** Run before recording a non-repeatable completion, including offline batch claims. */
    public static Validation validateItemComponents(net.minecraft.core.HolderLookup.Provider registries,
            List<RewardEntry> rewards) {
        try {
            for (RewardEntry reward : rewards) {
                if (reward instanceof EquipmentRewardEntry equipment) equipment.resolve().createStack(registries);
                if (reward instanceof NpcRewardEntry item) item.createStack(registries);
                if (reward instanceof ResolvedLuckyRewardEntry lucky) lucky.selection().reward().createStack(registries);
                if (reward instanceof LuckyRewardEntry lucky) {
                    var pool = com.sande.mythictrpg.quest.reward.luck.LuckDefinitions.INSTANCE.find(lucky.poolId())
                            .orElseThrow(() -> new IllegalArgumentException("Luck reward pool is not registered"));
                    for (var candidate : pool.candidates()) candidate.item().createStack(registries);
                }
            }
            return Validation.allow();
        } catch (RuntimeException invalid) {
            return Validation.reject("Invalid reward item components: " + invalid.getMessage());
        }
    }

    private static void grantAffinity(ServerPlayer player, ResourceLocation godId,
            AffinityRewardEntry reward, RewardGrantPurpose purpose) {
        PlayerMythDataService service = PlayerMythDataService.get(player.server);
        if (purpose == RewardGrantPurpose.QUEST) {
            service.grantQuestAffinity(player.getUUID(), godId, reward.amount());
        } else {
            service.adjustAffinity(player.getUUID(), godId, reward.amount());
        }
    }

    private static void grantBlessing(ServerPlayer player, BlessingRewardEntry reward) {
        if (reward.permanent()) {
            PermanentBlessingRuntime.applyGranted(player, reward.effectId());
            return;
        }
        Holder.Reference<MobEffect> effect = BuiltInRegistries.MOB_EFFECT.getHolder(reward.effectId())
                .orElseThrow(() -> new IllegalStateException("Reward effect disappeared after validation"));
        // A lower, longer effect must remain behind the stronger one until its own expiry.
        BlessingEffectApplication.apply(player, new MobEffectInstance(effect,
                reward.durationTicks(), reward.amplifier(), false, true, true));
    }

    public record Validation(boolean allowed, String reason) {
        static Validation allow() {
            return new Validation(true, "");
        }

        static Validation reject(String reason) {
            return new Validation(false, reason);
        }
    }

    public record Result(boolean granted, String reason, List<String> rewards) {
        public Result {
            rewards = List.copyOf(rewards);
        }

        static Result granted(List<String> rewards) {
            return new Result(true, "", rewards);
        }

        static Result reject(String reason) {
            return new Result(false, reason, List.of());
        }
    }
}
