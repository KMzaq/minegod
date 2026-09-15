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
        if (rewards == null || rewards.isEmpty() || rewards.size() > 32) {
            return Validation.reject("Reward bundle must contain 1..32 entries");
        }
        for (RewardEntry reward : rewards) {
            if (reward == null) {
                return Validation.reject("Reward bundle contains a null entry");
            }
            if (reward instanceof NpcRewardEntry item
                    && !BuiltInRegistries.ITEM.containsKey(item.itemId())) {
                return Validation.reject("Reward item is no longer registered: " + item.itemId());
            }
            if (reward instanceof BlessingRewardEntry blessing
                    && !BuiltInRegistries.MOB_EFFECT.containsKey(blessing.effectId())) {
                return Validation.reject("Reward blessing is no longer registered: " + blessing.effectId());
            }
            if (reward instanceof AffinityRewardEntry affinity
                    && purpose == RewardGrantPurpose.AI_ACTION && affinity.amount() > 50) {
                return Validation.reject("AI action affinity rewards may not exceed +50");
            }
        }
        return Validation.allow();
    }

    public static Result grant(ServerPlayer player, ResourceLocation godId,
            List<RewardEntry> rewards, RewardGrantPurpose purpose) {
        Validation validation = validate(rewards, purpose);
        if (!validation.allowed()) {
            return Result.reject(validation.reason());
        }
        long currencyTotal = 0L;
        for (RewardEntry reward : rewards) {
            if (reward instanceof CurrencyRewardEntry currency) {
                try {
                    currencyTotal = Math.addExact(currencyTotal, currency.amount());
                } catch (ArithmeticException exception) {
                    return Result.reject("Currency reward total overflow");
                }
            }
            if (reward instanceof UnlockShopProductRewardEntry unlock
                    && ShopCatalogManager.INSTANCE.product(unlock.key()).isEmpty()) {
                return Result.reject("Shop product is not registered: " + unlock.key());
            }
        }
        long balance = CurrencyService.INSTANCE.balance(player.server, player.getUUID());
        if (currencyTotal > 0L && !CurrencyState.get(player.server).isReady()) {
            return Result.reject("Currency data is read-only");
        }
        if (rewards.stream().anyMatch(UnlockShopProductRewardEntry.class::isInstance)
                && !GlobalShopState.get(player.server).isReady()) {
            return Result.reject("Global shop state is read-only");
        }
        if (currencyTotal > CurrencyState.MAX_BALANCE - balance) {
            return Result.reject("Currency reward would exceed the maximum balance");
        }
        List<String> descriptions = new ArrayList<>();
        for (RewardEntry reward : rewards) {
            switch (reward) {
                case NpcRewardEntry item -> grantItem(player, item);
                case AffinityRewardEntry affinity -> grantAffinity(player, godId, affinity, purpose);
                case BlessingRewardEntry blessing -> grantBlessing(player, blessing);
                case TitleRewardEntry title -> PlayerMythDataService.get(player.server)
                        .unlockTitle(player.getUUID(), title.titleId());
                case CurrencyRewardEntry currency -> CurrencyService.INSTANCE.credit(
                        player.server, player.getUUID(), currency.amount());
                case UnlockShopProductRewardEntry unlock -> ShopService.INSTANCE.unlock(
                        player.server, unlock.key());
            }
            descriptions.add(reward.description());
        }
        return Result.granted(descriptions);
    }

    private static void grantItem(ServerPlayer player, NpcRewardEntry reward) {
        ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(reward.itemId()), reward.count());
        ItemStack history = stack.copy();
        player.getInventory().add(stack);
        if (!stack.isEmpty()) {
            player.drop(stack, false);
        }
        PlayerMythHistoryService.recordItemObtained(player, history);
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
        Holder.Reference<MobEffect> effect = BuiltInRegistries.MOB_EFFECT.getHolder(reward.effectId())
                .orElseThrow(() -> new IllegalStateException("Reward effect disappeared after validation"));
        MobEffectInstance current = player.getEffect(effect);
        if (current != null && (current.getAmplifier() > reward.amplifier()
                || current.getAmplifier() == reward.amplifier()
                && current.getDuration() >= reward.durationTicks())) {
            return;
        }
        player.addEffect(new MobEffectInstance(effect, reward.durationTicks(), reward.amplifier(),
                false, true, true));
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
