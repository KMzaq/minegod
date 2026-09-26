package com.sande.mythictrpg.quest.reward;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Persisted immutable reward offer plus its monotonic claim state. */
public record RewardClaim(UUID claimId, UUID playerId, ResourceLocation godId,
        ResourceLocation sourceId, String selectionTitle, List<RewardEntry> automaticRewards,
        List<RewardChoiceOption> choices, boolean automaticGranted,
        Optional<ResourceLocation> selectedOptionId, long createdGameTime) {
    public RewardClaim {
        Objects.requireNonNull(claimId, "claimId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(godId, "godId");
        Objects.requireNonNull(sourceId, "sourceId");
        selectionTitle = selectionTitle == null ? "보상을 선택하세요" : selectionTitle.trim();
        automaticRewards = List.copyOf(automaticRewards);
        choices = List.copyOf(choices);
        selectedOptionId = selectedOptionId == null ? Optional.empty() : selectedOptionId;
        if (!choices.isEmpty() && (choices.size() < 2 || choices.size() > 6)) {
            throw new IllegalArgumentException("Persisted reward choice must contain 2..6 options");
        }
        ResourceLocation selected = selectedOptionId.orElse(null);
        if (selected != null && choices.stream()
                .noneMatch(choice -> choice.optionId().equals(selected))) {
            throw new IllegalArgumentException("Selected reward option is not part of the claim");
        }
        if (createdGameTime < 0L) {
            throw new IllegalArgumentException("createdGameTime must be non-negative");
        }
    }

    public boolean pendingChoice() {
        return automaticGranted && !choices.isEmpty() && selectedOptionId.isEmpty();
    }

    public boolean fullyClaimed() {
        return automaticGranted && (choices.isEmpty() || selectedOptionId.isPresent());
    }

    public RewardClaim withAutomaticGranted() {
        return new RewardClaim(claimId, playerId, godId, sourceId, selectionTitle,
                automaticRewards, choices, true, selectedOptionId, createdGameTime);
    }

    public RewardClaim withSelected(ResourceLocation optionId) {
        return new RewardClaim(claimId, playerId, godId, sourceId, selectionTitle,
                automaticRewards, choices, automaticGranted, Optional.of(optionId), createdGameTime);
    }
}
