package com.sande.mythictrpg.quest.structure;

import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Coordinates render work, advisory LLM work, validation, and authoritative persistence. */
public final class StructureVisualEvaluationService {
    public static final StructureVisualEvaluationService INSTANCE = new StructureVisualEvaluationService();
    private final ExecutorService renderExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "mythictrpg-structure-render");
        thread.setDaemon(true); return thread;
    });
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    private StructureVisualEvaluationService() {}

    public void submit(MinecraftServer server, UUID playerId, FreeStructureRecord structure,
            StructureEvaluationPolicy policy, StructureEvaluationReport report,
            ServerLevel level, StructureBuildRecord sampledBuild) {
        submit(server, playerId, structure.id(), structure.name(), "free:" + structure.id(),
                policy, report, level, sampledBuild);
    }

    public void submitQuest(MinecraftServer server, UUID playerId, ResourceLocation questId,
            StructureEvaluationPolicy policy, StructureEvaluationReport report,
            ServerLevel level, StructureBuildRecord sampledBuild) {
        String sourceKey = "quest:" + playerId + ":" + questId;
        UUID structureId = UUID.nameUUIDFromBytes(sourceKey.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        submit(server, playerId, structureId, questId.toString(), sourceKey,
                policy, report, level, sampledBuild);
    }

    private void submit(MinecraftServer server, UUID playerId, UUID structureId, String structureName,
            String sourceKey, StructureEvaluationPolicy policy, StructureEvaluationReport report,
            ServerLevel level, StructureBuildRecord sampledBuild) {
        var profile = policy.visualProfile().orElse(null);
        if (profile == null || !StructureVisualEvaluationGateway.INSTANCE.isAvailable()) return;
        StructureVoxelRenderService.Scene scene;
        try {
            scene = StructureVoxelRenderService.capture(level, sampledBuild, report.snapshot());
        } catch (RuntimeException exception) {
            MythicTrpg.LOGGER.warn("Could not capture visual scene for structure {}", structureId, exception);
            return;
        }
        UUID requestId = UUID.randomUUID();
        CompletableFuture<StructureVisualAssessment> future = CompletableFuture
                .supplyAsync(() -> StructureVoxelRenderService.render(scene), renderExecutor)
                .thenCompose(views -> StructureVisualEvaluationGateway.INSTANCE.evaluate(
                        new StructureVisualEvaluationGateway.Request(requestId, playerId, structureId,
                                structureName, policy.id(), policy.godId(), report.score(),
                                report.buildScore(), report.environmentScore(), report.evidenceSummary(),
                                report.snapshot().features(), profile, views)).toCompletableFuture());
        pending.put(requestId, new Pending(server, playerId, future));
        future.whenComplete((assessment, failure) -> {
            pending.remove(requestId);
            server.execute(() -> complete(server, playerId, structureId, structureName, sourceKey,
                    policy, report, assessment, failure));
        });
    }

    public int pending(MinecraftServer server, UUID playerId) {
        return (int) pending.values().stream().filter(value -> value.server() == server
                && value.playerId().equals(playerId)).count();
    }

    public void onServerStopped(ServerStoppedEvent event) {
        pending.entrySet().removeIf(entry -> {
            if (entry.getValue().server() != event.getServer()) return false;
            entry.getValue().future().cancel(true); return true;
        });
    }

    private static void complete(MinecraftServer server, UUID playerId, UUID structureId,
            String structureName, String sourceKey,
            StructureEvaluationPolicy policy, StructureEvaluationReport report,
            StructureVisualAssessment assessment, Throwable failure) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (failure != null || assessment == null) {
            MythicTrpg.LOGGER.warn("Visual structure evaluation failed for {}: {}", structureId,
                    failure == null ? "empty result" : failure.toString());
            notify(player, "건축물 시각 분석을 건너뛰고 블록 분석 점수를 유지했습니다", true);
            return;
        }
        boolean applied = PlayerConstructionState.get(server).applyVisualEvaluation(sourceKey,
                policy.id(), report.snapshot().fingerprint(), assessment, policy.visualProfile().orElseThrow());
        if (!applied) {
            MythicTrpg.LOGGER.info("Discarded stale visual evaluation for structure {}", structureId);
            return;
        }
        var stored = PlayerConstructionState.get(server).evaluation(sourceKey, policy.id()).orElseThrow();
        String confidence = Math.round(assessment.confidence() * 100.0D) + "%";
        String appliedText = assessment.isConfident(policy.visualProfile().orElseThrow().minimumConfidence())
                ? "최종 " + stored.score() + "점" : "신뢰도 부족으로 블록 점수 " + stored.score() + "점 유지";
        notify(player, "[시각 분석] " + structureName + " / " + assessment.buildingType() + " / "
                + (assessment.subtype().isBlank() ? "세부 유형 미정" : assessment.subtype())
                + " / 스타일 " + (assessment.styles().isEmpty() ? "미정" : String.join(", ", assessment.styles()))
                + " / 신뢰도 " + confidence + " / " + appliedText, false);
    }

    private static void notify(ServerPlayer player, String message, boolean warning) {
        if (player != null) player.sendSystemMessage(Component.literal(message)
                .withStyle(warning ? ChatFormatting.YELLOW : ChatFormatting.LIGHT_PURPLE));
    }

    private record Pending(MinecraftServer server, UUID playerId,
            CompletableFuture<StructureVisualAssessment> future) {}
}
