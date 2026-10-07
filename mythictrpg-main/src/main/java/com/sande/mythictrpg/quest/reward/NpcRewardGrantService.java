package com.sande.mythictrpg.quest.reward;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import java.util.List;

/** Reusable authoritative execution boundary for one authored NPC reward tier. */
public final class NpcRewardGrantService {
    private NpcRewardGrantService() {
    }

    public static Validation validate(ResourceLocation npcId, ResourceLocation tableId, int tier) {
        return validate(npcId, tableId, tier, RewardGrantPurpose.QUEST);
    }

    public static Validation validate(ResourceLocation npcId, ResourceLocation tableId, int tier,
            RewardGrantPurpose purpose) {
        NpcRewardTable table = NpcRewardTableManager.INSTANCE.find(tableId).orElse(null);
        if (table == null) {
            return Validation.reject("NPC reward table is not loaded");
        }
        if (!table.npcId().equals(npcId)) {
            return Validation.reject("The acting God does not own this reward table");
        }
        NpcRewardTier selected = table.tier(tier).orElse(null);
        if (selected == null) {
            return Validation.reject("Reward tier is not registered in the God reward table");
        }
        RewardExecutionService.Validation execution = RewardExecutionService.validate(
                selected.rewards(), purpose);
        return execution.allowed() ? Validation.allow() : Validation.reject(execution.reason());
    }

    public static Result grant(ServerPlayer player, ResourceLocation npcId,
            ResourceLocation tableId, int tier) {
        return grant(player, npcId, tableId, tier, RewardGrantPurpose.QUEST);
    }

    public static Result grant(ServerPlayer player, ResourceLocation npcId,
            ResourceLocation tableId, int tier, RewardGrantPurpose purpose) {
        Validation validation = validate(npcId, tableId, tier, purpose);
        if (!validation.allowed()) {
            return Result.reject(validation.reason());
        }
        NpcRewardTier selected = NpcRewardTableManager.INSTANCE.find(tableId).orElseThrow()
                .tier(tier).orElseThrow();
        // Preserve the existing table/God/tier and AI_ACTION checks before committing permanent ownership.
        // Repeated authorized table grants retain one source per effect; unrelated temporary/item rewards keep their behavior.
        if (selected.rewards().stream().anyMatch(reward -> reward instanceof BlessingRewardEntry blessing && blessing.permanent())) {
            if (!player.server.isSameThread()) return Result.reject("Reward grant requires server thread");
            var live = RewardExecutionService.validateGrant(player, selected.rewards(), purpose);
            if (!live.allowed()) return Result.reject(live.reason());
            try {
                RewardClaimState.get(player.server).acquireTableBlessings(player.getUUID(), npcId, tableId, tier,
                        selected.rewards(), player.server.overworld().getGameTime());
            } catch (RuntimeException invalid) { return Result.reject(invalid.getMessage()); }
        }
        RewardExecutionService.Result result = RewardExecutionService.grant(
                player, npcId, selected.rewards(), purpose);
        return result.granted() ? Result.granted(result.rewards()) : Result.reject(result.reason());
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
