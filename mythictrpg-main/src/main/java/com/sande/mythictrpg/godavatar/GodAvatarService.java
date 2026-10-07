package com.sande.mythictrpg.godavatar;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.ai.room.RoomType;
import com.sande.mythictrpg.ai.server.ConversationRooms;
import com.sande.mythictrpg.data.god.GodDefinitionManager;
import com.sande.mythictrpg.interaction.api.ExplicitGodCallPayload;
import com.sande.mythictrpg.interaction.api.InteractionSignal;
import com.sande.mythictrpg.interaction.api.InteractionSignalTypes;
import com.sande.mythictrpg.interaction.content.InteractionContentPreparer;
import com.sande.mythictrpg.interaction.content.PreparationResult;
import com.sande.mythictrpg.interaction.orchestration.InteractionOrchestrator;
import com.sande.mythictrpg.interaction.spontaneous.ContentPreparerResolution;
import com.sande.mythictrpg.interaction.spontaneous.InteractionContentPreparerResolverRouter;
import com.sande.mythictrpg.interaction.start.InteractionStartResult;
import com.sande.mythictrpg.interaction.start.InteractionStartStatus;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Game-owned spawn, movement, directed combat and physically initiated dialogue. */
public final class GodAvatarService {
    public static final GodAvatarService INSTANCE = new GodAvatarService();
    private static final ResourceLocation AVATAR_STALE = ResourceLocation.fromNamespaceAndPath(
            MythicTrpg.MOD_ID, "god_avatar_stale");
    private MinecraftServer pendingServer;
    private final Map<UUID, PendingTalk> pendingTalks = new HashMap<>();
    private long nextTalkToken;

    private GodAvatarService() {}

    /** Does not replace an existing entity for this God, including one in an unloaded chunk. */
    public Optional<GodAvatarEntity> spawn(ServerLevel level, ResourceLocation godId, Vec3 location) {
        return spawnInternal(level, godId, location, null);
    }

    /** Raid owner must be acquired first; an existing world avatar is never moved or duplicated. */
    public Optional<GodAvatarEntity> spawnForRaid(ServerLevel level, ResourceLocation godId,
            Vec3 location, UUID attemptId) {
        return spawnInternal(level, godId, location, attemptId);
    }

    private Optional<GodAvatarEntity> spawnInternal(ServerLevel level, ResourceLocation godId,
            Vec3 location, UUID attemptId) {
        requireServerThread(level.getServer());
        if (GodDefinitionManager.INSTANCE.find(godId).isEmpty()
                || GodAvatarDefinitionManager.INSTANCE.find(godId).isEmpty()
                || !level.hasChunkAt(BlockPos.containing(location))) return Optional.empty();
        GodAvatarRegistryState registry = GodAvatarRegistryState.get(level.getServer());
        if (!registry.isReady() || registry.find(godId).isPresent()
                || (attemptId == null ? registry.raidOwner(godId).isPresent()
                        : !registry.raidOwner(godId).filter(attemptId::equals).isPresent())) return Optional.empty();
        GodAvatarEntity avatar = GodAvatarEntities.GOD_AVATAR.get().create(level);
        if (avatar == null) return Optional.empty();
        avatar.moveTo(location.x, location.y, location.z, level.random.nextFloat() * 360, 0);
        avatar.bind(godId);
        avatar.setHealth(avatar.getMaxHealth());
        if (!level.noCollision(avatar) || !registry.reserve(godId, avatar.getUUID(), level.dimension().location()))
            return Optional.empty();
        if (!level.addFreshEntity(avatar)) {
            registry.release(godId, avatar.getUUID());
            return Optional.empty();
        }
        return Optional.of(avatar);
    }

    public boolean acquireRaidLease(MinecraftServer server, ResourceLocation godId, UUID attemptId) {
        requireServerThread(server);
        if (GodDefinitionManager.INSTANCE.find(godId).isEmpty()
                || GodAvatarDefinitionManager.INSTANCE.find(godId).isEmpty()) return false;
        return GodAvatarRegistryState.get(server).acquireRaid(godId, attemptId);
    }

    public void releaseRaidLease(MinecraftServer server, ResourceLocation godId, UUID attemptId) {
        requireServerThread(server);
        if (GodAvatarRegistryState.get(server).raidOwner(godId).filter(attemptId::equals).isPresent()) {
            findLoaded(server, godId).ifPresent(GodAvatarEntity::clearRaidTarget);
            GodAvatarRegistryState.get(server).releaseRaid(godId, attemptId);
        }
    }

    /** Authored onEncounter only; failure leaves the already committed dialogue/UI unchanged. */
    public void onEncounterCommitted(MinecraftServer server, UUID initiatingPlayerId, Set<ResourceLocation> manifestedGods) {
        requireServerThread(server);
        ServerPlayer player = server.getPlayerList().getPlayer(initiatingPlayerId);
        if (player == null) return;
        ServerLevel level = player.serverLevel();
        for (ResourceLocation godId : manifestedGods) {
            var definition = GodAvatarDefinitionManager.INSTANCE.find(godId).orElse(null);
            if (definition == null || !definition.placement().onEncounter()
                    || GodAvatarRegistryState.get(server).find(godId).isPresent()) continue;
            spawnNear(level, godId, player.blockPosition(), definition.placement().spawnRadius())
                    .ifPresent(avatar -> MythicTrpg.LOGGER.info("Manifested God avatar {} as {}", godId, avatar.getUUID()));
        }
    }

    private Optional<GodAvatarEntity> spawnNear(ServerLevel level, ResourceLocation godId,
            BlockPos origin, int radius) {
        for (int distance = 0; distance <= radius; distance++) {
            for (int dx = -distance; dx <= distance; dx++) for (int dz = -distance; dz <= distance; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != distance) continue;
                for (int dy : new int[]{0, 1, -1}) {
                    BlockPos feet = origin.offset(dx, dy, dz);
                    if (!level.hasChunkAt(feet)) continue;
                    BlockState floor = level.getBlockState(feet.below());
                    if (!floor.isFaceSturdy(level, feet.below(), Direction.UP)) continue;
                    if (!level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                            || !level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()) continue;
                    var spawned = spawn(level, godId, Vec3.atBottomCenterOf(feet));
                    if (spawned.isPresent()) return spawned;
                }
            }
        }
        return Optional.empty();
    }

    public boolean despawn(GodAvatarEntity avatar) {
        requireServerThread(avatar.getServer());
        if (!avatar.hasAuthoritativeBinding()) return false;
        avatar.discard();
        return true;
    }

    public boolean move(GodAvatarEntity avatar, BlockPos destination) {
        requireServerThread(avatar.getServer());
        var definition = liveDefinition(avatar).orElse(null);
        if (definition == null || GodAvatarRegistryState.get(avatar.getServer())
                        .raidOwner(avatar.godId().orElseThrow()).isPresent() || !definition.movement().enabled()
                || !((ServerLevel) avatar.level()).hasChunkAt(destination)
                || avatar.distanceToSqr(Vec3.atCenterOf(destination))
                        > squared(definition.movement().maxCommandDistance())) return false;
        if (!avatar.getNavigation().moveTo(destination.getX() + 0.5, destination.getY(),
                destination.getZ() + 0.5, definition.movement().navigationSpeed())) return false;
        avatar.moveTo(destination);
        return true;
    }

    public boolean visit(GodAvatarEntity avatar, ServerPlayer player) {
        requireServerThread(avatar.getServer());
        var definition = liveDefinition(avatar).orElse(null);
        if (definition == null || GodAvatarRegistryState.get(avatar.getServer())
                        .raidOwner(avatar.godId().orElseThrow()).isPresent()
                || !definition.movement().enabled() || !definition.movement().visit()
                || player.level() != avatar.level() || !player.isAlive()
                || avatar.distanceToSqr(player) > squared(definition.movement().maxVisitDistance())) return false;
        if (!avatar.getNavigation().moveTo(player, definition.movement().navigationSpeed())) return false;
        avatar.visit(player.getUUID());
        return true;
    }

    /** Future raid controller may direct an authored combat-capable avatar; no automatic player targeting. */
    private boolean targetForRaidAuthorized(GodAvatarEntity avatar, ServerPlayer target) {
        requireServerThread(avatar.getServer());
        var definition = liveDefinition(avatar).orElse(null);
        if (definition == null || !definition.combat().enabled() || !definition.combat().raidControl()
                || !target.isAlive() || target.level() != avatar.level()
                || avatar.distanceToSqr(target) > squared(definition.stats().followRange())) return false;
        return true;
    }

    /** Stronger raid-owner check for active attempts; use this in the raid engine. */
    public boolean targetForRaid(GodAvatarEntity avatar, ServerPlayer target, UUID attemptId) {
        requireServerThread(avatar.getServer());
        ResourceLocation godId = avatar.godId().orElse(null);
        if (godId == null || !GodAvatarRegistryState.get(avatar.getServer()).raidOwner(godId)
                .filter(attemptId::equals).isPresent() || !targetForRaidAuthorized(avatar, target)) return false;
        avatar.directRaidTarget(target, attemptId);
        return true;
    }

    public void clearRaidTarget(GodAvatarEntity avatar) {
        requireServerThread(avatar.getServer());
        avatar.clearRaidTarget();
    }

    private static Optional<GodAvatarDefinition> liveDefinition(GodAvatarEntity avatar) {
        if (!avatar.isAlive() || !avatar.hasAuthoritativeBinding()) return Optional.empty();
        return avatar.currentDefinition();
    }

    /** A click is a request, not an AI answer; final distance and entity revision are checked after preparation. */
    public void interact(GodAvatarEntity avatar, ServerPlayer player) {
        requireServerThread(player.server);
        var definition = liveDefinition(avatar).orElse(null);
        if (definition == null || !isNear(avatar, player, definition.interactionRange())) {
            notice(player, "현재 이 존재와 대화를 시작할 수 없습니다.");
            return;
        }
        if (!ConversationRooms.enabled()) {
            notice(player, "대화방 AI 엔진이 연결되지 않았습니다.");
            return;
        }
        ResourceLocation godId = avatar.godId().orElseThrow();
        var existing = ConversationRooms.INSTANCE.memberships(player).stream()
                .filter(room -> room.godIds().contains(godId.toString())).findFirst();
        if (existing.isPresent()) {
            var room = existing.orElseThrow();
            if (room.type() == RoomType.PRIVATE) {
                ConversationRooms.INSTANCE.selectPrivate(player, room.roomId());
                notice(player, "기존 비밀 대화를 선택했습니다. /s 로 말을 거세요.");
            } else notice(player, "이미 대화 중입니다. 일반 채팅으로 말을 거세요.");
            com.sande.mythictrpg.quest.QuestContactService.met(player, avatar,
                    ConversationRooms.INSTANCE.actionScope(player, room.roomId(), room.revision(), godId).orElse(null));
            return;
        }
        pendingFor(player.server);
        PendingTalk previous = pendingTalks.get(player.getUUID());
        long now = player.server.getTickCount();
        if (previous != null && now - previous.startedTick() < 400) {
            notice(player, "이전 대화 요청을 처리 중입니다.");
            return;
        }
        long token = ++nextTalkToken;
        pendingTalks.put(player.getUUID(), new PendingTalk(token, now, avatar.getUUID()));
        var signal = new InteractionSignal<>(InteractionSignalTypes.EXPLICIT_GOD_CALL,
                player.getUUID(), Set.of(), new ExplicitGodCallPayload(godId,
                        InteractionSignalTypes.PLAYER_EXPLICIT_POLICY));
        ContentPreparerResolution resolved;
        try {
            resolved = InteractionContentPreparerResolverRouter.INSTANCE.resolve(signal);
        } catch (RuntimeException exception) {
            MythicTrpg.LOGGER.warn("God avatar content resolver failed", exception);
            finishTalk(player, token, "대화 생성기를 사용할 수 없습니다.");
            return;
        }
        if (resolved.status() != ContentPreparerResolution.Status.AVAILABLE) {
            finishTalk(player, token, "대화 생성기를 사용할 수 없습니다.");
            return;
        }
        long avatarGeneration = GodAvatarDefinitionManager.INSTANCE.generation();
        long godGeneration = GodDefinitionManager.INSTANCE.generation();
        InteractionContentPreparer guarded = request -> {
            CompletableFuture<PreparationResult> result = new CompletableFuture<>();
            try {
                resolved.preparer().orElseThrow().prepare(request).whenComplete((prepared, failure) ->
                        player.server.execute(() -> {
                            if (!talkCurrent(player, avatar, godId, token, avatarGeneration, godGeneration)) {
                                result.complete(PreparationResult.failed(AVATAR_STALE));
                            } else if (failure != null || prepared == null) {
                                result.complete(PreparationResult.failed(AVATAR_STALE));
                            } else result.complete(prepared);
                        }));
            } catch (RuntimeException exception) {
                result.complete(PreparationResult.failed(AVATAR_STALE));
            }
            return result;
        };
        try {
            InteractionOrchestrator.INSTANCE.execute(player.server, signal, guarded)
                    .whenComplete((result, failure) -> player.server.execute(() -> {
                        if (!isPending(player.getUUID(), token)) return;
                        pendingTalks.remove(player.getUUID());
                        if (failure != null || result == null) {
                            notice(player, "대화 요청이 실패했습니다.");
                        } else if (result.status() != InteractionStartStatus.STARTED) {
                            notice(player, "대화를 시작하지 못했습니다 (" + result.status() + ").");
                        } else if (result.delivery().orElseThrow().status()
                                != com.sande.mythictrpg.interaction.start.DeliveryStatus.ALL_SENT) {
                            notice(player, "대화는 기록됐지만 발화 전달을 확인하지 못했습니다.");
                        } else {
                            com.sande.mythictrpg.quest.QuestContactService.met(player, avatar,
                                    ConversationRooms.INSTANCE.contactScope(player, result.interactionId().orElseThrow(), godId).orElse(null));
                        }
                    }));
            notice(player, "대화 요청을 보냈습니다. 서버 응답을 기다려 주세요.");
        } catch (RuntimeException exception) {
            MythicTrpg.LOGGER.warn("God avatar interaction submission failed", exception);
            finishTalk(player, token, "대화 요청이 실패했습니다.");
        }
    }

    private boolean talkCurrent(ServerPlayer player, GodAvatarEntity avatar, ResourceLocation godId,
            long token, long avatarGeneration, long godGeneration) {
        return isPending(player.getUUID(), token)
                && player.server.getPlayerList().getPlayer(player.getUUID()) == player
                && avatar.isAlive() && avatar.godId().filter(godId::equals).isPresent()
                && avatar.getUUID().equals(pendingTalks.get(player.getUUID()).avatarId())
                && avatar.hasAuthoritativeBinding()
                && ((ServerLevel) avatar.level()).getEntity(avatar.getUUID()) == avatar
                && GodAvatarDefinitionManager.INSTANCE.generation() == avatarGeneration
                && GodDefinitionManager.INSTANCE.generation() == godGeneration
                && avatar.currentDefinition().filter(value -> isNear(avatar, player, value.interactionRange())).isPresent();
    }

    private static boolean isNear(GodAvatarEntity avatar, ServerPlayer player, double range) {
        return avatar.level() == player.level() && player.isAlive() && avatar.distanceToSqr(player) <= squared(range);
    }
    private static double squared(double value) { return value * value; }

    private void pendingFor(MinecraftServer server) {
        if (pendingServer != server) {
            pendingServer = server;
            pendingTalks.clear();
        }
    }
    private boolean isPending(UUID playerId, long token) {
        PendingTalk pending = pendingTalks.get(playerId);
        return pending != null && pending.token() == token;
    }
    private void finishTalk(ServerPlayer player, long token, String message) {
        if (!isPending(player.getUUID(), token)) return;
        pendingTalks.remove(player.getUUID());
        notice(player, message);
    }
    private static void notice(ServerPlayer player, String text) {
        player.sendSystemMessage(Component.literal("[신 아바타] " + text));
    }

    public Optional<GodAvatarEntity> findLoaded(MinecraftServer server, ResourceLocation godId) {
        requireServerThread(server);
        var ref = GodAvatarRegistryState.get(server).find(godId).orElse(null);
        if (ref == null) return Optional.empty();
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, ref.dimension());
        ServerLevel level = server.getLevel(dimension);
        if (level == null) return Optional.empty();
        Entity entity = level.getEntity(ref.entityId());
        return entity instanceof GodAvatarEntity avatar && avatar.hasAuthoritativeBinding()
                ? Optional.of(avatar) : Optional.empty();
    }

    public void onServerStopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event) {
        if (pendingServer == event.getServer()) {
            pendingServer = null;
            pendingTalks.clear();
        }
    }

    private static void requireServerThread(MinecraftServer server) {
        if (server == null || !server.isSameThread())
            throw new IllegalStateException("God avatar changes require server thread");
    }

    private record PendingTalk(long token, long startedTick, UUID avatarId) {}
}
