package com.sande.mythictrpg.godavatar;

import com.sande.mythictrpg.godavatar.visit.*;
import com.sande.mythictrpg.ai.server.ConversationRooms;
import com.sande.mythictrpg.data.god.*;
import com.sande.mythictrpg.data.player.PlayerMythDataService;
import com.sande.mythictrpg.data.world.MythicWorldState;
import com.sande.mythictrpg.quest.structure.*;
import net.minecraft.core.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Game owns candidates, authorization, deadlines and navigation. Never spawns, teleports or takes another order. */
public final class GodHomeVisitService {
    public static final GodHomeVisitService INSTANCE = new GodHomeVisitService();
    private MinecraftServer server;
    private Pending pending;
    private int cursor;
    private record Target(GodVisitPlanner.Candidate view, BlockPos destination) { }
    private record Pending(MinecraftServer server, GodAvatarEntity avatar, ServerPlayer player,
            GodVisitPlanner.Request request, GodVisitPolicy policy, List<Target> targets,
            long orderRevision, long policyGeneration, long avatarGeneration, long godGeneration,
            long expires, CompletableFuture<GodVisitPlanner.Decision> result) { }
    private GodHomeVisitService() { }

    public boolean canRequest(ServerPlayer player, ResourceLocation god, boolean dialogue) {
        requireThread(player.server);
        attach(player.server);
        var policy = GodVisitPolicies.INSTANCE.snapshot().get(god);
        var avatar = GodAvatarService.INSTANCE.findLoaded(player.server, god).orElse(null);
        return pending == null && GodVisitPlanner.available() && policy != null
                && (dialogue ? policy.dialogue() : policy.autonomous()) && eligible(player, avatar, policy)
                && now(player.server) >= avatar.nextVisitDecision();
    }

    /** Dialogue callers must supply an exact game-issued action generation, never a UI-selected room. */
    public boolean request(ServerPlayer player, ResourceLocation god, Optional<UUID> actionSession) {
        if (!canRequest(player, god, actionSession.isPresent())) return false;
        var dialogue = actionSession.flatMap(id -> ConversationRooms.INSTANCE.visitDialogue(player, id, god));
        if (actionSession.isPresent() && dialogue.isEmpty()) return false;
        var avatar = GodAvatarService.INSTANCE.findLoaded(player.server, god).orElseThrow();
        var policy = GodVisitPolicies.INSTANCE.snapshot().get(god);
        avatar.deferVisitDecision(now(player.server) + policy.checkIntervalTicks());
        var targets = candidates(player, avatar, policy);
        if (targets.isEmpty()) return false;
        var request = new GodVisitPlanner.Request(UUID.randomUUID(), god.toString(), player.getUUID(),
                actionSession.isPresent() ? "DIALOGUE" : "AUTONOMOUS", affinity(player, god),
                avatar.level().getDayTime(), avatar.level().isRaining(), targets.stream().map(Target::view).toList(), dialogue);
        CompletableFuture<GodVisitPlanner.Decision> result;
        try { result = Objects.requireNonNull(GodVisitPlanner.choose(request)); }
        catch (RuntimeException unavailable) { return false; }
        pending = new Pending(player.server, avatar, player, request, policy, targets, avatar.orderRevision(),
                GodVisitPolicies.INSTANCE.generation(), GodAvatarDefinitionManager.INSTANCE.generation(),
                GodDefinitionManager.INSTANCE.generation(), now(player.server) + policy.requestTimeoutTicks(), result);
        return true;
    }

    public void tick(ServerTickEvent.Post event) {
        attach(event.getServer());
        if (pending != null) {
            var job = pending;
            if (now(server) >= job.expires()) {
                job.result().cancel(true); pending = null;
                if (job.request().dialogue().isPresent()) notifyOutcome(job.avatar(), job.player().getUUID(), "UNAVAILABLE");
                return;
            }
            if (!job.result().isDone()) return;
            pending = null;
            try { apply(job, job.result().join()); }
            catch (RuntimeException unavailable) {
                if (job.request().dialogue().isPresent()) notifyOutcome(job.avatar(), job.player().getUUID(), "UNAVAILABLE");
            }
            return;
        }
        if (server.getTickCount() % 20 != 0 || !GodVisitPlanner.available()) return;
        var policies = GodVisitPolicies.INSTANCE.snapshot().values().stream().filter(GodVisitPolicy::autonomous)
                .sorted(Comparator.comparing(p -> p.godId().toString())).toList();
        var players = server.getPlayerList().getPlayers();
        if (policies.isEmpty() || players.isEmpty()) return;
        // One pair per second; round-robin bounded work, no whole-world/entity/structure scan per tick.
        long slot = Integer.toUnsignedLong(cursor++);
        var policy = policies.get((int) (slot % policies.size()));
        var player = players.get((int) ((slot / policies.size()) % players.size()));
        request(player, policy.godId(), Optional.empty());
    }

    private void apply(Pending job, GodVisitPlanner.Decision decision) {
        if (server != job.server() || now(server) >= job.expires()
                || !job.policy().equals(GodVisitPolicies.INSTANCE.snapshot().get(job.policy().godId()))
                || job.policyGeneration() != GodVisitPolicies.INSTANCE.generation()
                || job.avatarGeneration() != GodAvatarDefinitionManager.INSTANCE.generation()
                || job.godGeneration() != GodDefinitionManager.INSTANCE.generation()
                || job.avatar().orderRevision() != job.orderRevision()
                || !eligible(job.player(), job.avatar(), job.policy())
                || affinity(job.player(), job.policy().godId()) != job.request().affinity()) return;
        if (job.request().dialogue().isPresent()) {
            var d = job.request().dialogue().orElseThrow();
            if (!ConversationRooms.INSTANCE.socialTurnCurrent(d.roomId(), d.revision(), d.turnSequence())) return;
        }
        job.avatar().deferVisitDecision(now(server) + job.policy().cooldownTicks());
        if (decision == null) throw new IllegalArgumentException("Null visit decision");
        if (decision.structureId().isEmpty()) {
            if (job.request().dialogue().isPresent()) notifyOutcome(job.avatar(), job.player().getUUID(), "DECLINED");
            return;
        }
        var selected = job.targets().stream().filter(t -> t.view().structureId().equals(decision.structureId().get())).findFirst().orElse(null);
        if (selected == null || candidates(job.player(), job.avatar(), job.policy()).stream().noneMatch(selected::equals)) return;
        var avatar = job.avatar(); var destination = selected.destination();
        if (!navigationChunksLoaded(avatar)) { notifyOutcome(avatar, job.player().getUUID(), "NO_PATH"); return; }
        var path = avatar.getNavigation().createPath(destination, 0);
        if (path == null || !path.canReach()
                || !avatar.getNavigation().moveTo(path, avatar.currentDefinition().orElseThrow().movement().navigationSpeed())) {
            notifyOutcome(avatar, job.player().getUUID(), "NO_PATH"); return;
        }
        avatar.startHomeVisit(selected.view().structureId(), job.player().getUUID(), destination,
                now(server) + job.policy().travelTimeoutTicks());
        notifyOutcome(avatar, job.player().getUUID(), "TRAVELLING");
    }

    private static boolean eligible(ServerPlayer player, GodAvatarEntity avatar, GodVisitPolicy policy) {
        if (avatar == null || avatar.getServer() != player.server || !avatar.isAlive() || !avatar.hasAuthoritativeBinding()
                || avatar.isRemoved() || avatar.busyForVisit() || !player.isAlive()
                || player.server.getPlayerList().getPlayer(player.getUUID()) != player
                || !PlayerMythDataService.get(player.server).isReady()
                || !PlayerConstructionState.get(player.server).isWritable()
                || !progressAllows(player.server, policy)
                || GodAvatarRegistryState.get(player.server).raidOwner(policy.godId()).isPresent()) return false;
        if (com.sande.mythictrpg.ai.memorycontract.MemoryFoundationSettings.mode()
                == com.sande.mythictrpg.ai.memorycontract.MemoryFoundationSettings.Mode.RUMOR_TEST
                || com.sande.mythictrpg.ai.server.AiConversationRuntimeService.INSTANCE.isTestConversation(player)
                || ConversationRooms.INSTANCE.memberships(player).stream().anyMatch(r -> r.recordingScope().isTest())) return false;
        return avatar.currentDefinition().map(d -> d.movement().enabled() && d.movement().visit()).orElse(false);
    }
    private static boolean progressAllows(MinecraftServer server, GodVisitPolicy policy) {
        var state = MythicWorldState.get(server);
        return !state.isRejected() && policy.minimumProgress().entrySet().stream()
                .allMatch(e -> state.questProgress(e.getKey()) >= e.getValue());
    }
    private static int affinity(ServerPlayer player, ResourceLocation god) {
        return PlayerMythDataService.get(player.server).find(player.getUUID())
                .map(p -> p.affinities().getOrDefault(god, 0)).orElse(0);
    }

    private static List<Target> candidates(ServerPlayer player, GodAvatarEntity avatar, GodVisitPolicy policy) {
        var data = PlayerConstructionState.get(player.server);
        var result = new ArrayList<Target>();
        // A deterministic bounded offer set, not an exhaustive ranking of every building in the world.
        for (var structure : data.structuresFor(player.getUUID()).stream()
                .sorted(Comparator.comparing(s -> s.id().toString())).limit(64).toList()) {
            if (!structure.region().dimension().equals(avatar.level().dimension())) continue;
            var destination = destination(avatar, structure.region());
            if (destination == null) continue;
            StringBuilder evidence = new StringBuilder();
            for (var id : policy.evaluationPolicies()) {
                var authored = StructureEvaluationPolicyManager.INSTANCE.find(id).orElse(null);
                var evaluation = data.evaluation("free:" + structure.id(), id).orElse(null);
                if (authored == null || !authored.godId().equals(policy.godId()) || evaluation == null
                        || !evaluation.godId().equals(policy.godId()) || !evaluation.belongsTo(player.getUUID())
                        || now(player.server) - evaluation.evaluatedAtGameTick() < 0
                        || now(player.server) - evaluation.evaluatedAtGameTick() > policy.maximumEvaluationAgeTicks()) continue;
                if (evidence.length() > 1800) break;
                evidence.append("Historical assessment ").append(id).append(": score=").append(evaluation.score());
                evaluation.visualAssessment().ifPresent(v -> evidence.append("; type=").append(v.buildingType())
                        .append("; styles=").append(v.styles()).append("; preference=").append(v.godPreferenceScore())
                        .append("; confidence=").append(v.confidence()));
                evidence.append('\n');
            }
            result.add(new Target(new GodVisitPlanner.Candidate(structure.id(), structure.name(), evidence.toString()), destination));
            if (result.size() == 8) break;
        }
        return List.copyOf(result);
    }

    private static BlockPos destination(GodAvatarEntity avatar, StructureRegion region) {
        int x = Math.clamp(avatar.getBlockX(), region.minX(), region.maxX());
        int z = Math.clamp(avatar.getBlockZ(), region.minZ(), region.maxZ());
        double max = avatar.currentDefinition().orElseThrow().movement().maxVisitDistance();
        if (avatar.distanceToSqr(x + .5, avatar.getY(), z + .5) > max * max) return null;
        for (int dy : new int[]{0, 1, -1, 2, -2, 3, -3, 4, -4})
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
                var pos = new BlockPos(x + dx, avatar.getBlockY() + dy, z + dz);
                if (!region.contains(pos) || !safeDestination(avatar, pos, max)) continue;
                return pos;
            }
        return null;
    }
    private static boolean safeDestination(GodAvatarEntity avatar, BlockPos pos, double max) {
        var level = (ServerLevel) avatar.level();
        if (!level.hasChunkAt(pos) || !level.getWorldBorder().isWithinBounds(pos)
                || avatar.distanceToSqr(Vec3.atBottomCenterOf(pos)) > max * max
                || !level.getFluidState(pos).isEmpty()
                || !level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)) return false;
        var box = avatar.getBoundingBox().move(Vec3.atBottomCenterOf(pos).subtract(avatar.position()));
        // Checking all touched chunks first prevents the collision query from loading distant chunks.
        for (int cx = ((int)Math.floor(box.minX)) >> 4; cx <= ((int)Math.floor(box.maxX)) >> 4; cx++)
            for (int cz = ((int)Math.floor(box.minZ)) >> 4; cz <= ((int)Math.floor(box.maxZ)) >> 4; cz++)
                if (!level.hasChunk(cx, cz)) return false;
        return level.noCollision(avatar, box);
    }
    boolean travelCurrent(GodAvatarEntity avatar) {
        var policy = avatar.godId().map(GodVisitPolicies.INSTANCE.snapshot()::get).orElse(null);
        if (policy == null || !policy.dialogue() && !policy.autonomous() || !progressAllows(avatar.getServer(), policy) || now(avatar.getServer()) >= avatar.homeDeadline()
                || avatar.homeOwner() == null || avatar.homeDestination() == null || avatar.getTarget() != null
                || GodAvatarRegistryState.get(avatar.getServer()).raidOwner(policy.godId()).isPresent()
                || avatar.currentDefinition().filter(d -> d.movement().enabled() && d.movement().visit()).isEmpty()) return false;
        var player = avatar.getServer().getPlayerList().getPlayer(avatar.homeOwner());
        var data = PlayerConstructionState.get(avatar.getServer());
        var structure = data.find(avatar.homeStructure()).orElse(null);
        return player != null && player.isAlive() && data.isWritable() && structure != null
                && structure.ownerId().equals(avatar.homeOwner()) && structure.region().dimension().equals(avatar.level().dimension())
                && structure.region().contains(avatar.homeDestination()) && safeDestination(avatar, avatar.homeDestination(),
                        avatar.currentDefinition().orElseThrow().movement().maxVisitDistance());
    }
    boolean insideDestination(GodAvatarEntity avatar) {
        return PlayerConstructionState.get(avatar.getServer()).find(avatar.homeStructure())
                .map(s -> s.region().contains(avatar.blockPosition())).orElse(false);
    }
    public void notifyOutcome(GodAvatarEntity avatar, UUID owner, String status) {
        var player = avatar.getServer().getPlayerList().getPlayer(owner);
        if (player == null || avatar.godId().isEmpty()) return;
        String text = switch (status) {
            case "TRAVELLING" -> "등록 건축물로 이동을 시작했습니다. 아직 도착하지 않았습니다.";
            case "ARRIVED" -> "등록 건축물에 도착했습니다.";
            case "DECLINED" -> "이번에는 방문하지 않기로 했습니다.";
            case "NO_PATH" -> "방문 경로를 찾지 못했습니다.";
            case "UNAVAILABLE" -> "방문 판단을 완료하지 못했습니다. 이동하지 않았습니다.";
            default -> "방문 이동이 중단되었습니다.";
        };
        player.sendSystemMessage(Component.literal("[방문] ").append(GodIdentityService.INSTANCE.getDisplayName(player, avatar.godId().orElseThrow()))
                .append(": " + text));
    }
    /** Vanilla pathfinding builds a region around the mob. Do not let it pull unloaded chunks in. */
    static boolean navigationChunksLoaded(GodAvatarEntity avatar) {
        int radius = (int)Math.ceil(avatar.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.FOLLOW_RANGE)) + 16;
        var level = (ServerLevel) avatar.level();
        for (int cx = (avatar.getBlockX() - radius) >> 4; cx <= (avatar.getBlockX() + radius) >> 4; cx++)
            for (int cz = (avatar.getBlockZ() - radius) >> 4; cz <= (avatar.getBlockZ() + radius) >> 4; cz++)
                if (!level.hasChunk(cx, cz)) return false;
        return true;
    }
    /** Physical visit history is disclosed only to that owner in a one-to-one private room. */
    public String contextFor(ServerPlayer player, ResourceLocation god, com.sande.mythictrpg.ai.room.ConversationRoomSnapshot room) {
        if (room.type() != com.sande.mythictrpg.ai.room.RoomType.PRIVATE || room.playerIds().size() != 1 || room.godIds().size() != 1) return "";
        var avatar = GodAvatarService.INSTANCE.findLoaded(player.server, god).orElse(null);
        if (avatar == null || !player.getUUID().equals(avatar.lastHomeOwner())) return "";
        return "[GAME_HOME_VISIT] lastStatus=" + avatar.homeVisitStatus() + "; eventGameTime=" + avatar.homeStatusTime()
                + "; TRAVELLING is not arrival; ARRIVED records a past arrival, not proof of current location. No reward or conversation start is implied.";
    }
    private void attach(MinecraftServer value) {
        requireThread(value);
        if (server != value) { if (pending != null) pending.result().cancel(true); pending = null; server = value; cursor = 0; }
    }
    public void stopped(net.neoforged.neoforge.event.server.ServerStoppedEvent e) {
        if (server == e.getServer()) { if (pending != null) pending.result().cancel(true); pending = null; server = null; }
    }
    private static long now(MinecraftServer server) { return server.overworld().getGameTime(); }
    private static void requireThread(MinecraftServer server) { if (!server.isSameThread()) throw new IllegalStateException("Visit game thread required"); }
}
