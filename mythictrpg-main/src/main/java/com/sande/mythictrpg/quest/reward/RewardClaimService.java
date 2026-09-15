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
            // Persist the monotonic receipt before applying side effects so a restart cannot duplicate rewards.
            state.markAutomaticGranted(claim.claimId());
            if (!claim.automaticRewards().isEmpty()) {
                RewardExecutionService.Result grant = RewardExecutionService.grant(player, claim.godId(),
                        claim.automaticRewards(), RewardGrantPurpose.QUEST);
                if (!grant.granted()) {
                    MythicTrpg.LOGGER.error("Reward claim {} was recorded but automatic grant failed: {}",
                            claim.claimId(), grant.reason());
                    return Result.reject("Automatic reward grant failed after receipt creation: " + grant.reason());
                }
                player.sendSystemMessage(Component.literal("[퀘스트 보상] "
                        + String.join(", ", grant.rewards())).withStyle(ChatFormatting.GOLD));
            }
            claim = state.find(claim.claimId()).orElseThrow();
        }
        if (claim.pendingChoice()) {
            sendChoice(player, claim);
            return Result.choicePending(claim.claimId());
        }
        return Result.granted(claim.claimId());
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
        try {
            state.markSelected(claimId, optionId);
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
        return Result.granted(claimId);
    }

    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        for (RewardClaim claim : RewardClaimState.get(player.server).pendingFor(player.getUUID())) {
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
