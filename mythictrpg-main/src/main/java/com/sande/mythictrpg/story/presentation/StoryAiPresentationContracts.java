package com.sande.mythictrpg.story.presentation;

import com.sande.mythictrpg.story.definition.StoryDefinitions.GenerationPolicy;
import com.sande.mythictrpg.story.definition.StoryDefinitions.PresentationKind;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/** Public, non-authoritative contract consumed by optional AI modules. */
public final class StoryAiPresentationContracts {
    private StoryAiPresentationContracts() {}

    public record AllowedStatement(String text, boolean canonical) {
        public AllowedStatement {
            text = bounded(text, "statement", 1_000);
        }
    }

    public record HookOffer(String alias, String title, String summary) {
        public HookOffer {
            alias = bounded(alias, "hook alias", 40);
            title = bounded(title, "hook title", 160);
            summary = bounded(summary, "hook summary", 500);
        }
    }

    /** Contains semantic text and opaque aliases only; fact/event/hook IDs never cross this boundary. */
    public record Snapshot(UUID requestId, UUID audiencePlayerId, ResourceLocation speakerGodId,
            PresentationKind kind, GenerationPolicy generationPolicy, List<AllowedStatement> allowedStatements,
            List<String> performanceDirectives, List<HookOffer> hookOffers, int maximumSpeechLines) {
        public Snapshot {
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(audiencePlayerId, "audiencePlayerId");
            Objects.requireNonNull(speakerGodId, "speakerGodId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(generationPolicy, "generationPolicy");
            allowedStatements = List.copyOf(allowedStatements);
            performanceDirectives = performanceDirectives.stream()
                    .map(value -> bounded(value, "performance directive", 160)).toList();
            hookOffers = List.copyOf(hookOffers);
            if (allowedStatements.size() > 8 || hookOffers.size() > 3
                    || maximumSpeechLines < 1 || maximumSpeechLines > 4) {
                throw new IllegalArgumentException("Story AI Snapshot exceeds its bounded shape");
            }
        }
    }

    public record Response(List<String> speech, Optional<String> proposedHookAlias) {
        public Response {
            speech = List.copyOf(speech).stream().map(value -> bounded(value, "speech", 1_200)).toList();
            proposedHookAlias = Objects.requireNonNull(proposedHookAlias, "proposedHookAlias")
                    .map(value -> bounded(value, "proposedHookAlias", 40));
            if (speech.isEmpty() || speech.size() > 4) {
                throw new IllegalArgumentException("Story AI response requires 1..4 speech lines");
            }
        }
    }

    @FunctionalInterface
    public interface Provider {
        CompletionStage<Response> generate(Snapshot snapshot);
    }

    private static String bounded(String value, String field, int maximum) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty() || normalized.codePointCount(0, normalized.length()) > maximum) {
            throw new IllegalArgumentException(field + " is blank or too long");
        }
        return normalized;
    }
}
