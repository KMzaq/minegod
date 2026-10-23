package com.sande.mythictrpg.quest.reward;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import java.util.Set;
import java.util.stream.Collectors;

/** Read-only projection of the existing committed reward ledger, never an independent skill ownership DB. */
public final class SkillRewardBridge {
    private SkillRewardBridge() { }
    public static boolean isReady(ServerPlayer player) { return player.server.isSameThread() && RewardClaimState.get(player.server).isWritable(); }
    public static boolean ownsLearned(ServerPlayer player,ResourceLocation id) {
        return isReady(player) && RewardClaimState.get(player.server).ownsProgression(player.getUUID(),ProgressionRewardEntry.Kind.LEARNED,id);
    }
    public static boolean ownsSpecial(ServerPlayer player,ResourceLocation id) {
        return isReady(player) && RewardClaimState.get(player.server).ownsProgression(player.getUUID(),ProgressionRewardEntry.Kind.SPECIAL,id);
    }
    public static boolean owns(ServerPlayer player,ResourceLocation id) { return ownsLearned(player,id)||ownsSpecial(player,id); }
    public static Set<ResourceLocation> ownedIds(ServerPlayer player) {
        if(!isReady(player)) return Set.of();
        return RewardClaimState.get(player.server).progressionFor(player.getUUID()).stream().filter(value -> value.kind()==ProgressionRewardEntry.Kind.LEARNED)
                .map(RewardClaimState.ProgressionEntitlement::definitionId).collect(Collectors.toUnmodifiableSet());
    }
    public static Set<ResourceLocation> ownedSpecialIds(ServerPlayer player) {
        if(!isReady(player)) return Set.of();
        return RewardClaimState.get(player.server).progressionFor(player.getUUID()).stream().filter(value -> value.kind()==ProgressionRewardEntry.Kind.SPECIAL)
                .map(RewardClaimState.ProgressionEntitlement::definitionId).collect(Collectors.toUnmodifiableSet());
    }
}
