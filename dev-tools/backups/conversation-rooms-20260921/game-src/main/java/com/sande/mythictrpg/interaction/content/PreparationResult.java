package com.sande.mythictrpg.interaction.content;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.Optional;

public record PreparationResult(Status status, Optional<PreparedInteractionContent> content,
        Optional<ResourceLocation> reason) {
    public PreparationResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(reason, "reason");
        if (status == Status.PREPARED && (content.isEmpty() || reason.isPresent())) {
            throw new IllegalArgumentException("PREPARED requires content and no failure reason");
        }
        if (status != Status.PREPARED && (content.isPresent() || reason.isEmpty())) {
            throw new IllegalArgumentException("Non-prepared results require a reason and no content");
        }
    }

    public static PreparationResult prepared(PreparedInteractionContent content) {
        return new PreparationResult(Status.PREPARED, Optional.of(content), Optional.empty());
    }

    public static PreparationResult noContent(ResourceLocation reason) {
        return new PreparationResult(Status.NO_CONTENT, Optional.empty(), Optional.of(reason));
    }

    public static PreparationResult failed(ResourceLocation reason) {
        return new PreparationResult(Status.FAILED, Optional.empty(), Optional.of(reason));
    }

    public enum Status {
        PREPARED,
        NO_CONTENT,
        FAILED
    }
}
