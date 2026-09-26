package com.sande.mythictrpg.quest.structure;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Registers free builds and performs bounded, multi-tick preparation before authoritative scoring. */
public final class FreeStructureService {
    public static final FreeStructureService INSTANCE = new FreeStructureService();
    private static final int LEDGER_ENTRIES_PER_TICK = 1_500;
    private static final int DISCOVERY_SEED_DISTANCE = 32;
    private final Map<MinecraftServer, ArrayDeque<Job>> jobs = new IdentityHashMap<>();

    private FreeStructureService() {}

    public Registration registerSelection(ServerPlayer player, String name) {
        var draft = StructureEvaluationState.get(player.server).draft(player.getUUID()).orElse(null);
        if (draft == null || draft.first() == null || draft.second() == null) {
            return Registration.reject("두 지점을 먼저 지정해야 합니다");
        }
        try {
            StructureRegion region = StructureRegion.between(draft.dimension(), draft.first(), draft.second());
            FreeStructureRecord record = PlayerConstructionState.get(player.server).register(player.getUUID(), name,
                    region, StructureTeamAdapter.frozenContributors(player), player.serverLevel().getGameTime());
            int queued = queueAllPolicies(player, record);
            return Registration.accept(record, queued);
        } catch (RuntimeException exception) {
            return Registration.reject(exception.getMessage());
        }
    }

    /** Finds the connected component of the nearest player/team-authored block. */
    public Registration discoverConnected(ServerPlayer player, String name) {
        Set<UUID> contributors = StructureTeamAdapter.frozenContributors(player);
        Map<Long, PlacementRecord> ledger = PlayerConstructionState.get(player.server)
                .placements(player.serverLevel().dimension(), contributors);
        BlockPos origin = player.blockPosition();
        Optional<Long> seed = ledger.keySet().stream().filter(raw -> {
            BlockPos pos = BlockPos.of(raw);
            return Math.abs(pos.getX() - origin.getX()) <= DISCOVERY_SEED_DISTANCE
                    && Math.abs(pos.getY() - origin.getY()) <= DISCOVERY_SEED_DISTANCE
                    && Math.abs(pos.getZ() - origin.getZ()) <= DISCOVERY_SEED_DISTANCE;
        }).min(java.util.Comparator.comparingDouble(raw -> BlockPos.of(raw).distSqr(origin)));
        if (seed.isEmpty()) return Registration.reject("현재 위치 32블록 안에서 본인/팀의 설치 기록을 찾지 못했습니다");

        Set<Long> remaining = new LinkedHashSet<>(ledger.keySet());
        Set<Long> component = new LinkedHashSet<>(); ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        remaining.remove(seed.orElseThrow()); queue.add(BlockPos.of(seed.orElseThrow()));
        while (!queue.isEmpty()) {
            BlockPos pos = queue.removeFirst(); component.add(pos.asLong());
            for (var direction : net.minecraft.core.Direction.values()) {
                BlockPos next = pos.relative(direction);
                if (remaining.remove(next.asLong())) queue.add(next);
            }
        }
        int minX=Integer.MAX_VALUE,maxX=Integer.MIN_VALUE,minZ=Integer.MAX_VALUE,maxZ=Integer.MIN_VALUE;
        for (long raw : component) { BlockPos pos=BlockPos.of(raw); minX=Math.min(minX,pos.getX());
            maxX=Math.max(maxX,pos.getX());minZ=Math.min(minZ,pos.getZ());maxZ=Math.max(maxZ,pos.getZ()); }
        try {
            StructureRegion region = new StructureRegion(player.serverLevel().dimension(), minX, maxX, minZ, maxZ);
            FreeStructureRecord record = PlayerConstructionState.get(player.server).register(player.getUUID(), name,
                    region, contributors, player.serverLevel().getGameTime());
            int queued = queueAllPolicies(player, record);
            return Registration.accept(record, queued);
        } catch (RuntimeException exception) {
            return Registration.reject(exception.getMessage());
        }
    }

    public Request requestEvaluation(ServerPlayer player, String structureName, ResourceLocation policyId) {
        FreeStructureRecord structure = PlayerConstructionState.get(player.server)
                .findByName(player.getUUID(), structureName).orElse(null);
        if (structure == null) return Request.reject("해당 이름의 자유 건축물이 없습니다");
        StructureEvaluationPolicy policy = StructureEvaluationPolicyManager.INSTANCE.find(policyId).orElse(null);
        if (policy == null) return Request.reject("건축 평가 정책이 로드되지 않았습니다");
        ArrayDeque<Job> queue = jobs.computeIfAbsent(player.server, ignored -> new ArrayDeque<>());
        boolean duplicate = queue.stream().anyMatch(job -> job.structureId.equals(structure.id())
                && job.policy.id().equals(policyId));
        if (duplicate) return Request.reject("같은 건축물과 정책의 평가가 이미 대기 중입니다");
        queue.addLast(new Job(player.getUUID(), structure, policy));
        return Request.accept(queue.size());
    }

    public int pending(MinecraftServer server, UUID playerId) {
        return (int) jobs.getOrDefault(server, new ArrayDeque<>()).stream()
                .filter(job -> job.playerId.equals(playerId)).count();
    }

    public void onServerTickPost(ServerTickEvent.Post event) {
        ArrayDeque<Job> queue = jobs.get(event.getServer());
        if (queue == null || queue.isEmpty()) return;
        Job job = queue.peekFirst();
        if (job.process(event.getServer(), LEDGER_ENTRIES_PER_TICK)) queue.removeFirst();
        if (queue.isEmpty()) jobs.remove(event.getServer());
    }

    public void onServerStopped(ServerStoppedEvent event) { jobs.remove(event.getServer()); }

    private int queueAllPolicies(ServerPlayer player, FreeStructureRecord record) {
        int count = 0;
        for (ResourceLocation policy : StructureEvaluationPolicyManager.INSTANCE.ids().stream().sorted().toList()) {
            if (requestEvaluation(player, record.name(), policy).accepted()) count++;
        }
        return count;
    }

    private static final class Job {
        private final UUID playerId;
        private final UUID structureId;
        private final StructureEvaluationPolicy policy;
        private final List<Map.Entry<Long, PlacementRecord>> source;
        private final StructureBuildRecord sample;
        private final int stride;
        private int cursor;

        private Job(UUID playerId, FreeStructureRecord structure, StructureEvaluationPolicy policy) {
            this.playerId = playerId; this.structureId = structure.id(); this.policy = policy;
            this.source = new ArrayList<>(structure.placements().entrySet());
            this.stride = Math.max(1, (int) Math.ceil(source.size() / (double) policy.limits().maxTrackedBlocks()));
            this.sample = new StructureBuildRecord(structure.ownerId(),
                    ResourceLocation.fromNamespaceAndPath("mythictrpg", "free_structure"),
                    structure.region(), structure.eligibleContributors(), structure.registeredAtGameTick());
            structure.decorations().forEach(sample::restoreDecoration);
        }

        private boolean process(MinecraftServer server, int budget) {
            int end = Math.min(source.size(), cursor + budget);
            while (cursor < end) {
                Map.Entry<Long, PlacementRecord> entry = source.get(cursor);
                if (cursor % stride == 0) sample.restorePlacement(entry.getKey(), entry.getValue());
                cursor++;
            }
            if (cursor < source.size()) return false;
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            FreeStructureRecord structure = PlayerConstructionState.get(server).find(structureId).orElse(null);
            ServerLevel level = structure == null ? null : server.getLevel(structure.region().dimension());
            if (structure == null || level == null) {
                notify(player, "건축 평가를 완료하지 못했습니다: 건축물 또는 차원을 찾을 수 없습니다", true); return true;
            }
            StructureSnapshotService.Capture capture = StructureSnapshotService.capture(level, sample, policy);
            if (!capture.succeeded()) {
                notify(player, "건축 평가를 완료하지 못했습니다: " + capture.rejectionReason(), true); return true;
            }
            StructureEvaluationReport report = StructureCriterionRegistry.evaluate(capture.snapshot(), policy);
            PlayerConstructionState.get(server).recordEvaluation("free:" + structure.id(), structure.ownerId(),
                    structure.eligibleContributors(), report, level.getGameTime());
            notify(player, "[건축 평가] " + structure.name() + " / " + policy.id() + " = "
                    + report.score() + "점 (BUILD " + Math.round(report.buildScore()) + ", ENV "
                    + Math.round(report.environmentScore()) + ")", false);
            StructureVisualEvaluationService.INSTANCE.submit(server, playerId, structure, policy,
                    report, level, sample);
            return true;
        }

        private static void notify(ServerPlayer player, String message, boolean failure) {
            if (player != null) player.sendSystemMessage(Component.literal(message)
                    .withStyle(failure ? ChatFormatting.RED : ChatFormatting.AQUA));
        }
    }

    public record Registration(boolean accepted, String reason, FreeStructureRecord structure, int queuedPolicies) {
        static Registration accept(FreeStructureRecord structure, int queued) { return new Registration(true,"",structure,queued); }
        static Registration reject(String reason) { return new Registration(false,reason,null,0); }
    }
    public record Request(boolean accepted, String reason, int queuePosition) {
        static Request accept(int position) { return new Request(true,"",position); }
        static Request reject(String reason) { return new Request(false,reason,0); }
    }
}
