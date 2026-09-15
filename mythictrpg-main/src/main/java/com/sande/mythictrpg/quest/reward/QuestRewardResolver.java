package com.sande.mythictrpg.quest.reward;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Resolves God default + quest direct rewards without consulting AI output. */
public final class QuestRewardResolver {
    private QuestRewardResolver() {
    }

    public static Resolution resolve(ResourceLocation godId, Optional<QuestRewardPolicy> policy,
            Optional<TableTier> evaluatedBase) {
        List<RewardEntry> automatic = new ArrayList<>();
        List<RewardChoiceOption> choices = List.of();
        String title = "보상을 선택하세요";

        if (policy.isPresent()) {
            QuestRewardPolicy authored = policy.orElseThrow();
            title = authored.selectionTitle();
            if (authored.mode() == QuestRewardPolicy.Mode.ADD) {
                TableTier base;
                if (evaluatedBase.isPresent()) {
                    base = evaluatedBase.orElseThrow();
                } else {
                    NpcRewardTable defaultTable = NpcRewardTableManager.INSTANCE.defaultForGod(godId)
                            .orElse(null);
                    if (defaultTable == null) {
                        return Resolution.reject("God has no default reward table: " + godId);
                    }
                    base = new TableTier(defaultTable.id(), authored.baseTier());
                }
                NpcRewardTable table = NpcRewardTableManager.INSTANCE.find(base.tableId()).orElse(null);
                if (table == null || !table.npcId().equals(godId)) {
                    return Resolution.reject("Resolved base reward table is missing or owned by another God");
                }
                NpcRewardTier tier = table.tier(base.tier()).orElse(null);
                if (tier == null) {
                    return Resolution.reject("Resolved base reward tier does not exist");
                }
                automatic.addAll(tier.rewards());
            }
            automatic.addAll(authored.automaticRewards());
            choices = authored.choices();
        } else if (evaluatedBase.isPresent()) {
            TableTier base = evaluatedBase.orElseThrow();
            NpcRewardTable table = NpcRewardTableManager.INSTANCE.find(base.tableId()).orElse(null);
            if (table == null || !table.npcId().equals(godId)) {
                return Resolution.reject("Evaluation reward table is missing or owned by another God");
            }
            NpcRewardTier tier = table.tier(base.tier()).orElse(null);
            if (tier == null) {
                return Resolution.reject("Evaluation reward tier does not exist");
            }
            automatic.addAll(tier.rewards());
        } else {
            return Resolution.none();
        }

        if (!automatic.isEmpty()) {
            RewardExecutionService.Validation validation = RewardExecutionService.validate(
                    automatic, RewardGrantPurpose.QUEST);
            if (!validation.allowed()) {
                return Resolution.reject(validation.reason());
            }
        }
        for (RewardChoiceOption choice : choices) {
            RewardExecutionService.Validation validation = RewardExecutionService.validate(
                    choice.rewards(), RewardGrantPurpose.QUEST);
            if (!validation.allowed()) {
                return Resolution.reject("Invalid choice " + choice.optionId() + ": " + validation.reason());
            }
        }
        return Resolution.resolved(new ResolvedQuestReward(title, automatic, choices));
    }

    public record TableTier(ResourceLocation tableId, int tier) {
    }

    public record Resolution(Status status, Optional<ResolvedQuestReward> reward, String reason) {
        static Resolution resolved(ResolvedQuestReward reward) {
            return new Resolution(Status.RESOLVED, Optional.of(reward), "");
        }

        static Resolution none() {
            return new Resolution(Status.NONE, Optional.empty(), "");
        }

        static Resolution reject(String reason) {
            return new Resolution(Status.REJECTED, Optional.empty(), reason);
        }
    }

    public enum Status {
        RESOLVED,
        NONE,
        REJECTED
    }
}
