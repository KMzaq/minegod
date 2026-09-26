package com.sande.mythictrpg.quest.structure;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Optional server-side boundary implemented by the separate local-LLM module. */
public final class StructureVisualEvaluationGateway {
    public static final StructureVisualEvaluationGateway INSTANCE = new StructureVisualEvaluationGateway();
    private static final Provider UNAVAILABLE = request -> CompletableFuture.failedFuture(
            new IllegalStateException("Structure visual provider is unavailable"));
    private volatile Provider provider = UNAVAILABLE;

    private StructureVisualEvaluationGateway() {}

    public void configureProductionProvider(Provider provider) {
        this.provider = Objects.requireNonNull(provider);
    }

    public boolean isAvailable() { return provider != UNAVAILABLE; }

    public CompletionStage<StructureVisualAssessment> evaluate(Request request) {
        return provider.evaluate(Objects.requireNonNull(request));
    }

    @FunctionalInterface
    public interface Provider {
        CompletionStage<StructureVisualAssessment> evaluate(Request request);
    }

    public record Request(UUID requestId, UUID playerId, UUID structureId, String structureName,
            ResourceLocation policyId, ResourceLocation godId, int objectiveScore,
            double buildScore, double environmentScore, String objectiveEvidence,
            Map<String, Double> objectiveFeatures, StructureEvaluationPolicy.VisualProfile visualProfile,
            List<RenderedView> views) {
        public Request {
            Objects.requireNonNull(requestId); Objects.requireNonNull(playerId); Objects.requireNonNull(structureId);
            structureName = bounded(structureName, 80); Objects.requireNonNull(policyId); Objects.requireNonNull(godId);
            objectiveEvidence = bounded(objectiveEvidence, 1_200);
            objectiveFeatures = Map.copyOf(objectiveFeatures);
            Objects.requireNonNull(visualProfile);
            views = List.copyOf(views);
            if (views.isEmpty() || views.size() > 6) throw new IllegalArgumentException("Invalid rendered view count");
        }
    }

    public record RenderedView(String name, byte[] pngBytes) {
        public RenderedView {
            name = bounded(name, 32);
            pngBytes = pngBytes == null ? new byte[0] : pngBytes.clone();
            if (name.isBlank() || pngBytes.length == 0 || pngBytes.length > 2_000_000) {
                throw new IllegalArgumentException("Invalid rendered structure view");
            }
        }
        @Override public byte[] pngBytes() { return pngBytes.clone(); }
    }

    private static String bounded(String value, int maximum) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= maximum ? normalized : normalized.substring(0, maximum);
    }
}
