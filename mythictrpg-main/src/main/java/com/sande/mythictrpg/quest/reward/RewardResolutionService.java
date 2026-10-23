package com.sande.mythictrpg.quest.reward;

import com.sande.mythictrpg.quest.reward.luck.LuckDefinitions;
import com.sande.mythictrpg.quest.reward.luck.LuckSelector;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import java.util.ArrayList;
import java.util.List;

/** Resolves explicit dynamic operations once; the calling claim stores the entire result before side effects. */
public final class RewardResolutionService {
    private RewardResolutionService() { }
    public static boolean requiresResolution(RewardEntry entry) {
        return entry instanceof AdaptiveBlessingRewardEntry || entry instanceof LuckyRewardEntry
                || entry instanceof EquipmentRewardEntry;
    }
    public static boolean unresolved(ResolvedQuestReward reward) {
        return reward.automaticRewards().stream().anyMatch(RewardResolutionService::requiresResolution)
                || reward.choices().stream().flatMap(choice -> choice.rewards().stream()).anyMatch(RewardResolutionService::requiresResolution);
    }
    /** Static equipment does not need an online player. Freeze it when an offline reward is promised. */
    public static ResolvedQuestReward freezeEquipment(ResolvedQuestReward reward) {
        return new ResolvedQuestReward(reward.selectionTitle(), freezeEquipment(reward.automaticRewards()),
                reward.choices().stream().map(choice -> new RewardChoiceOption(choice.optionId(),choice.displayName(),
                        freezeEquipment(choice.rewards()))).toList());
    }
    private static List<RewardEntry> freezeEquipment(List<RewardEntry> rewards) {
        return rewards.stream().map(entry -> entry instanceof EquipmentRewardEntry equipment ? equipment.resolve() : entry).toList();
    }
    public static ResolvedQuestReward resolve(ServerPlayer player, ResourceLocation godId, ResourceLocation sourceId, ResolvedQuestReward reward) {
        if (!player.server.isSameThread()) throw new IllegalStateException("Reward resolution requires server thread");
        int level = com.sande.mythictrpg.quest.reward.luck.EffectiveLuck.level(player);
        String seedKey = player.getUUID() + "|" + godId + "|" + sourceId;
        boolean refund=sourceId.getNamespace().equals("mythictrpg")&&sourceId.getPath().startsWith("quest_refund/");
        double goldBonus=refund?0:com.sande.mythictrpg.accessory.AccessoryRuntime.goldBonusFraction(player);
        var automatic = resolveLucky(reward.automaticRewards(), level, seedKey + "|automatic",goldBonus);
        var choices = new ArrayList<RewardChoiceOption>();
        for (var choice : reward.choices()) choices.add(new RewardChoiceOption(choice.optionId(), choice.displayName(),
                resolveLucky(choice.rewards(), level, seedKey + "|choice|" + choice.optionId(),goldBonus)));
        return AdaptiveBlessingService.resolve(player, godId, new ResolvedQuestReward(reward.selectionTitle(), automatic, choices));
    }
    public static boolean hasCurrency(ResolvedQuestReward reward) {
        return reward.automaticRewards().stream().anyMatch(CurrencyRewardEntry.class::isInstance)
                || reward.choices().stream().flatMap(choice->choice.rewards().stream()).anyMatch(CurrencyRewardEntry.class::isInstance);
    }
    private static List<RewardEntry> resolveLucky(List<RewardEntry> rewards, int level, String seedKey,double goldBonus) {
        var output = new ArrayList<RewardEntry>();
        for (int i = 0; i < rewards.size(); i++) {
            var entry = rewards.get(i);
            if (entry instanceof EquipmentRewardEntry equipment) entry = equipment.resolve();
            if (entry instanceof LuckyRewardEntry lucky) {
                var pool = LuckDefinitions.INSTANCE.find(lucky.poolId())
                        .orElseThrow(() -> new IllegalStateException("Luck reward pool is not registered: " + lucky.poolId()));
                var selected = LuckSelector.select(pool, level, seed(seedKey + "|" + i + "|" + lucky.poolId()), LuckDefinitions.INSTANCE.model());
                entry = new ResolvedLuckyRewardEntry(lucky.poolId(), selected);
            }
            if(entry instanceof CurrencyRewardEntry currency && goldBonus>0) {
                long bonus=java.math.BigDecimal.valueOf(currency.amount()).multiply(java.math.BigDecimal.valueOf(goldBonus))
                        .setScale(0,java.math.RoundingMode.FLOOR).longValueExact();
                entry=new ResolvedCurrencyRewardEntry(currency.amount(),bonus);
            }
            output.add(entry);
        }
        return List.copyOf(output);
    }
    private static long seed(String value) {
        // Stable across JVM runs, player sessions and registry reloads; receipt still owns the resolved outcome.
        long result = 0xcbf29ce484222325L;
        for (byte character : value.getBytes(java.nio.charset.StandardCharsets.UTF_8)) { result ^= character & 255; result *= 0x100000001b3L; }
        return result;
    }
}
