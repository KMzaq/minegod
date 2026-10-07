package com.sande.mythictrpg.quest.reward;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.network.RewardChoicePayload;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;
import java.util.UUID;

/** Creates durable reward receipts and executes each automatic or selected bundle at most once. */
public final class RewardClaimService {
    public static final RewardClaimService INSTANCE = new RewardClaimService();

    private RewardClaimService() {
    }

    public Result preflight(ServerPlayer player, ResourceLocation sourceId, ResolvedQuestReward reward) {
        requireServerThread(player);
        Validation validation = validate(reward);
        if (!validation.allowed()) {
            return Result.reject(validation.reason());
        }
        String invalidItems = invalidItemComponents(player.registryAccess(), reward);
        if (invalidItems != null) return Result.reject(invalidItems);
        RewardClaimState state = RewardClaimState.get(player.server);
        if (!state.isWritable()) {
            return Result.reject(state.rejectionReason().orElse("Reward claim state is unavailable"));
        }
        if (!state.canCreate(player.getUUID(), sourceId)) {
            return Result.reject("Reward claim storage is full of pending choices");
        }
        return Result.ready();
    }

    public Result issue(ServerPlayer player, ResourceLocation godId, ResourceLocation sourceId,
            ResolvedQuestReward reward) {
        requireServerThread(player);
        Validation validation = validate(reward);
        if (!validation.allowed()) {
            return Result.reject(validation.reason());
        }
        String invalidItems = invalidItemComponents(player.registryAccess(), reward);
        if (invalidItems != null) return Result.reject(invalidItems);

        RewardClaimState state = RewardClaimState.get(player.server);
        if (!state.isWritable()) {
            return Result.reject(state.rejectionReason().orElse("Reward claim state is unavailable"));
        }
        RewardClaim claim;
        try {
            claim = state.create(new RewardClaim(UUID.randomUUID(), player.getUUID(), godId, sourceId,
                    reward.selectionTitle(), reward.automaticRewards(), reward.choices(), false,
                    java.util.Optional.empty(), player.server.overworld().getGameTime()));
        } catch (RuntimeException exception) {
            return Result.reject(exception.getMessage());
        }
        if (claim.fullyClaimed()) {
            return Result.alreadyClaimed(claim.claimId());
        }
        if (!claim.automaticGranted()) {
            if (!claim.automaticRewards().isEmpty()) {
                var live = RewardExecutionService.validateGrant(player, claim.automaticRewards(), RewardGrantPurpose.QUEST);
                if (!live.allowed()) return Result.reject(live.reason());
            }
            // Persist the monotonic receipt before applying side effects so a restart cannot duplicate rewards.
            try {
                state.markAutomaticGranted(claim.claimId(), player.server.overworld().getGameTime());
            } catch (RuntimeException unavailable) { return Result.reject(unavailable.getMessage()); }
            if (!claim.automaticRewards().isEmpty()) {
                RewardExecutionService.Result grant = RewardExecutionService.grant(player, claim.godId(),
                        claim.automaticRewards(), RewardGrantPurpose.QUEST);
                if (!grant.granted()) {
                    MythicTrpg.LOGGER.error("Reward claim {} was recorded but automatic grant failed: {}",
                            claim.claimId(), grant.reason());
                    return Result.reject("Automatic reward grant failed after receipt creation: " + grant.reason());
                }
                boolean refund = claim.sourceId().getNamespace().equals("mythictrpg") && claim.sourceId().getPath().startsWith("quest_refund/");
                player.sendSystemMessage(Component.literal((refund ? "[제출품 반환] " : "[퀘스트 보상] ")
                        + String.join(", ", grant.rewards())).withStyle(ChatFormatting.GOLD));
                recordGrant(player,claim,refund ? "QUEST_SUBMISSION_REFUNDED" : "AUTOMATIC_REWARD_GRANTED");
            }
            claim = state.find(claim.claimId()).orElseThrow();
        }
        if (claim.pendingChoice()) {
            sendChoice(player, claim);
            return Result.choicePending(claim.claimId());
        }
        return Result.granted(claim.claimId());
    }

    /** Validate the entire roster and freeze all payouts before completing a multiplayer quest. */
    public Result queueBatch(net.minecraft.server.MinecraftServer server, ResourceLocation godId,
            ResourceLocation sourceId, java.util.Map<UUID, ResolvedQuestReward> rewards) {
        if (!server.isSameThread()) throw new IllegalStateException("Reward queue requires server thread");
        RewardClaimState state = RewardClaimState.get(server);
        if (!state.canCreateBatch(rewards.keySet(), sourceId)) return Result.reject("Reward claim storage unavailable");
        var claims = new java.util.ArrayList<RewardClaim>();
        for (var entry : rewards.entrySet()) {
            var reward = entry.getValue();
            Validation validation = validate(reward);
            if (!validation.allowed()) return Result.reject(validation.reason());
            String invalidItems = invalidItemComponents(server.registryAccess(), reward);
            if (invalidItems != null) return Result.reject(invalidItems);
            claims.add(new RewardClaim(UUID.randomUUID(), entry.getKey(), godId, sourceId,
                    reward.selectionTitle(), reward.automaticRewards(), reward.choices(), false,
                    java.util.Optional.empty(), server.overworld().getGameTime()));
        }
        try {
            state.createBatch(claims);
            return Result.ready();
        } catch (RuntimeException exception) { return Result.reject(exception.getMessage()); }
    }

    public void deliverQueued(ServerPlayer player) {
        for (RewardClaim claim : RewardClaimState.get(player.server).undeliveredFor(player.getUUID())) {
            Result result = issue(player, claim.godId(), claim.sourceId(), new ResolvedQuestReward(claim.selectionTitle(),
                    claim.automaticRewards(), claim.choices()));
            if (!result.succeeded()) player.sendSystemMessage(Component.literal("[보상 수령 보류] " + claim.sourceId()
                    + ": " + result.reason() + " — 조건 해결 후 /mythquest rewards 로 다시 확인할 수 있습니다."));
        }
    }

    public Result choose(ServerPlayer player, UUID claimId, ResourceLocation optionId) {
        requireServerThread(player);
        RewardClaimState state = RewardClaimState.get(player.server);
        RewardClaim claim = state.find(claimId).orElse(null);
        if (claim == null || !claim.playerId().equals(player.getUUID())) {
            return Result.reject("Unknown reward claim");
        }
        if (!claim.automaticGranted()) {
            return Result.reject("Automatic rewards have not been processed");
        }
        if (claim.selectedOptionId().isPresent()) {
            return Result.alreadyClaimed(claimId);
        }
        RewardChoiceOption option = claim.choices().stream()
                .filter(candidate -> candidate.optionId().equals(optionId)).findFirst().orElse(null);
        if (option == null) {
            return Result.reject("Selected option is not part of this reward claim");
        }
        RewardExecutionService.Validation validation = RewardExecutionService.validate(
                option.rewards(), RewardGrantPurpose.QUEST);
        if (!validation.allowed()) {
            return Result.reject(validation.reason());
        }
        var live = RewardExecutionService.validateGrant(player, option.rewards(), RewardGrantPurpose.QUEST);
        if (!live.allowed()) return Result.reject(live.reason());
        try {
            state.markSelected(claimId, optionId, player.server.overworld().getGameTime());
        } catch (RuntimeException exception) {
            return Result.reject(exception.getMessage());
        }
        RewardExecutionService.Result grant = RewardExecutionService.grant(player, claim.godId(),
                option.rewards(), RewardGrantPurpose.QUEST);
        if (!grant.granted()) {
            MythicTrpg.LOGGER.error("Reward choice {} for claim {} was recorded but grant failed: {}",
                    optionId, claimId, grant.reason());
            return Result.reject("Selected reward grant failed after receipt creation: " + grant.reason());
        }
        player.sendSystemMessage(Component.literal("[선택 보상] " + option.displayName() + ": "
                + String.join(", ", grant.rewards())).withStyle(ChatFormatting.GREEN));
        recordGrant(player,claim,"CHOICE_REWARD_GRANTED");
        return Result.granted(claimId);
    }

    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        var pending = RewardClaimState.get(player.server).pendingFor(player.getUUID()).stream()
                .filter(RewardClaim::automaticGranted).toList();
        deliverQueued(player);
        for (RewardClaim claim : pending) {
            sendChoice(player, claim);
        }
    }

    private static Validation validate(ResolvedQuestReward reward) {
        if (reward.automaticRewards().isEmpty() && reward.choices().isEmpty()) {
            return Validation.reject("Resolved quest reward is empty");
        }
        if (!reward.automaticRewards().isEmpty()) {
            RewardExecutionService.Validation automatic = RewardExecutionService.validate(
                    reward.automaticRewards(), RewardGrantPurpose.QUEST);
            if (!automatic.allowed()) {
                return Validation.reject(automatic.reason());
            }
        }
        if (!reward.choices().isEmpty() && (reward.choices().size() < 2 || reward.choices().size() > 6)) {
            return Validation.reject("Reward choice must contain 2..6 options");
        }
        for (RewardChoiceOption option : reward.choices()) {
            RewardExecutionService.Validation choice = RewardExecutionService.validate(
                    option.rewards(), RewardGrantPurpose.QUEST);
            if (!choice.allowed()) {
                return Validation.reject("Invalid choice " + option.optionId() + ": " + choice.reason());
            }
        }
        return Validation.allow();
    }

    private static String invalidItemComponents(net.minecraft.core.HolderLookup.Provider registries,
            ResolvedQuestReward reward) {
        var all = new java.util.ArrayList<RewardEntry>(reward.automaticRewards());
        for (var choice : reward.choices()) all.addAll(choice.rewards());
        var validation = RewardExecutionService.validateItemComponents(registries, all);
        return validation.allowed() ? null : validation.reason();
    }

    private static void sendChoice(ServerPlayer player, RewardClaim claim) {
        List<RewardChoicePayload.Option> options = claim.choices().stream()
                .map(option -> new RewardChoicePayload.Option(option.optionId(), option.displayName(),
                        option.summary()))
                .toList();
        PacketDistributor.sendToPlayer(player,
                new RewardChoicePayload(claim.claimId(), claim.selectionTitle(), options));
    }

    private static void requireServerThread(ServerPlayer player) {
        if (!player.server.isSameThread()) {
            throw new IllegalStateException("Reward claims may only change on the server thread");
        }
    }
    private static void recordGrant(ServerPlayer player, RewardClaim claim, String transition) {
        com.sande.mythictrpg.gameplay.ledger.detail.ImportantEvents.transition(player.server,player.getUUID(),claim.sourceId(),
                transition,claim.claimId().toString(),java.time.Instant.now(),claim.claimId());
    }

    private record Validation(boolean allowed, String reason) {
        static Validation allow() {
            return new Validation(true, "");
        }

        static Validation reject(String reason) {
            return new Validation(false, reason == null ? "Unknown reward error" : reason);
        }
    }

    public record Result(Status status, UUID claimId, String reason) {
        static Result ready() {
            return new Result(Status.READY, new UUID(0L, 0L), "");
        }

        static Result granted(UUID claimId) {
            return new Result(Status.GRANTED, claimId, "");
        }

        static Result choicePending(UUID claimId) {
            return new Result(Status.CHOICE_PENDING, claimId, "");
        }

        static Result alreadyClaimed(UUID claimId) {
            return new Result(Status.ALREADY_CLAIMED, claimId, "");
        }

        static Result reject(String reason) {
            return new Result(Status.REJECTED, new UUID(0L, 0L),
                    reason == null ? "Unknown reward error" : reason);
        }

        public boolean succeeded() {
            return status != Status.REJECTED;
        }
    }

    public enum Status {
        READY,
        GRANTED,
        CHOICE_PENDING,
        ALREADY_CLAIMED,
        REJECTED
    }
}
