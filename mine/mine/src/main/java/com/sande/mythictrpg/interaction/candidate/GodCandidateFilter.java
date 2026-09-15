package com.sande.mythictrpg.interaction.candidate;

import com.sande.mythictrpg.interaction.context.InteractionContext;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.Optional;

@FunctionalInterface
public interface GodCandidateFilter {
    FilterResult evaluate(InteractionContext context, CandidateSeed seed);

    record FilterResult(boolean accepted, Optional<ResourceLocation> rejectionReason) {
        public FilterResult {
            Objects.requireNonNull(rejectionReason, "rejectionReason");
            if (accepted == rejectionReason.isPresent()) {
                throw new IllegalArgumentException("Accepted filters cannot have a rejection reason");
            }
        }

        public static FilterResult accept() {
            return new FilterResult(true, Optional.empty());
        }

        public static FilterResult reject(ResourceLocation reason) {
            return new FilterResult(false, Optional.of(reason));
        }
    }
}
