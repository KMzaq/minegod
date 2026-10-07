package com.sande.mythictrpg.raid;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.condition.api.ConditionResult;
import com.sande.mythictrpg.condition.engine.ConditionContexts;
import com.sande.mythictrpg.condition.engine.ConditionEngine;
import com.sande.mythictrpg.gameplay.ledger.detail.ImportantEvents;
import com.sande.mythictrpg.godavatar.GodAvatarEntity;
import com.sande.mythictrpg.godavatar.GodAvatarDefinitionManager;
import com.sande.mythictrpg.godavatar.GodAvatarRegistryState;
import com.sande.mythictrpg.godavatar.GodAvatarService;
import com.sande.mythictrpg.quest.reward.ResolvedQuestReward;
import com.sande.mythictrpg.quest.reward.RewardClaimService;
import com.sande.mythictrpg.story.runtime.StoryEventService;
import com.sande.mythictrpg.story.runtime.StoryTeamResolver;
import com.sande.mythictrpg.story.signal.StorySignal;
import com.sande.mythictrpg.story.signal.StorySignalTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Server-thread raid authority. Every run holds a frozen authored definition and roster. */
@EventBusSubscriber(modid = MythicTrpg.MOD_ID)
public final class RaidRuntime {
    public static final RaidRuntime INSTANCE = new RaidRuntime();
    static final String RAID_TAG = "mythictrpg_raid_attempt";
    private final RaidChunkTickets tickets = new RaidChunkTickets();
    private MinecraftServer activeServer;
    private final Set<UUID> deathObserved = new LinkedHashSet<>();

    private RaidRuntime() { }

    public record Result(boolean succeeded, String message, Optional<UUID> attemptId) {
        static Result ok(String message, UUID id) { return new Result(true, message, Optional.of(id)); }
        static Result ready(String message) { return new Result(true, message, Optional.empty()); }
        static Result no(String message) { return new Result(false, message, Optional.empty()); }
    }

    /** Read-only validation shared by command creation and confirmed AI offers. Recheck at actual creation. */
    public Result canCreate(ServerPlayer leader, ResourceLocation raidId) {
        MinecraftServer server = leader.server;
        checkThread(server);
        RaidDefinition definition = RaidCatalog.INSTANCE.find(raidId).orElse(null);
        if (definition == null) return Result.no("작성된 레이드 정의가 없습니다.");
        RaidState state = RaidState.get(server);
        if (!state.ready()) return Result.no("레이드 저장 데이터가 복구 대기 중입니다.");
        if (definition.boss().godId().isPresent()) {
            var avatar = GodAvatarDefinitionManager.INSTANCE.find(definition.boss().godId().orElseThrow()).orElse(null);
            if (avatar == null || !avatar.combat().enabled() || !avatar.combat().damageable()
                    || !avatar.combat().raidControl())
                return Result.no("작성된 신 아바타가 레이드 전투·피해·제어를 허용하지 않습니다.");
        }
        if (state.membership(leader.getUUID()).isPresent()) return Result.no("이미 다른 레이드에 참가 중입니다.");
        if (state.attempts().stream().filter(RaidState.Attempt::occupiesPlayerSlot).count() >= 64)
            return Result.no("레이드 대기열이 가득 찼습니다.");
        if (definition.arenaIds().stream().noneMatch(id -> RaidCatalog.INSTANCE.snapshot().arenas().containsKey(id)))
            return Result.no("작성된 전투 구역을 사용할 수 없습니다.");
        if (!conditionMatches(server, definition, leader.getUUID()))
            return Result.no("참가 조건을 확인할 수 없거나 충족하지 못했습니다.");
        return Result.ready("레이드 모집을 만들 수 있습니다.");
    }

    /** An AI God can offer only a raid explicitly authored for that God; rewardGodId grants no offer authority. */
    public Result validateOffer(ServerPlayer player, ResourceLocation godId, ResourceLocation raidId) {
        checkThread(player.server);
        RaidDefinition definition = RaidCatalog.INSTANCE.find(raidId).orElse(null);
        if (definition == null || !definition.offerGodIds().contains(godId))
            return Result.no("해당 신이 제안할 수 있도록 작성된 레이드가 아닙니다.");
        return canCreate(player, raidId);
    }

    /** Creating freezes the team invitation set, not the raid roster. Joining is always explicit. */
    public Result create(ServerPlayer leader, ResourceLocation raidId) {
        Result validation = canCreate(leader, raidId);
        if (!validation.succeeded()) return validation;
        MinecraftServer server = leader.server;
        RaidDefinition definition = RaidCatalog.INSTANCE.find(raidId).orElseThrow();
        RaidState state = RaidState.get(server);
        Set<UUID> invited;
        UUID team;
        if (definition.mode() == RaidDefinition.Mode.PARTY_ISOLATED) {
            var snapshot = StoryTeamResolver.resolve(leader);
            team = snapshot.stableTeamId(); invited = snapshot.frozenMembers();
        } else {
            team = leader.getUUID(); invited = Set.of(leader.getUUID());
        }
        try {
            RaidState.Attempt attempt = state.create(leader.getUUID(), team, definition, invited,
                    server.overworld().getGameTime());
            return Result.ok("레이드 모집을 만들었습니다. 참가자는 /mythraid join, 리더는 /mythraid start를 사용하세요.", attempt.id);
        } catch (RuntimeException failure) { return Result.no(failure.getMessage()); }
    }

    public Result join(ServerPlayer player, UUID attemptId) {
        Result allowed = canJoin(player, attemptId);
        if (!allowed.succeeded()) return allowed;
        if (!RaidState.get(player.server).join(attemptId, player.getUUID()))
            return Result.no("참가 불가: 초대·정원·중복 참가를 확인하세요.");
        return Result.ok("레이드에 참가했습니다.", attemptId);
    }

    /** Same eligibility for discovery and execution; a visible row grants no authority. */
    public Result canJoin(ServerPlayer player, UUID attemptId) {
        MinecraftServer server = player.server;
        checkThread(server);
        RaidState state = RaidState.get(server);
        RaidState.Attempt attempt = state.find(attemptId).orElse(null);
        if (attempt == null || attempt.status != RaidState.Status.FORMING) return Result.no("모집 중인 레이드가 아닙니다.");
        if (attempt.definition.mode() == RaidDefinition.Mode.PARTY_ISOLATED
                && !StoryTeamResolver.resolve(player).stableTeamId().equals(attempt.teamId))
            return Result.no("현재 리더의 FTB 팀에 속하지 않습니다.");
        if (!conditionMatches(server, attempt.definition, player.getUUID()))
            return Result.no("참가 조건을 확인할 수 없거나 충족하지 못했습니다.");
        if (!state.ready() || state.membership(player.getUUID()).isPresent()
                || attempt.members.size() >= attempt.definition.maximumPlayers()
                || attempt.definition.mode() == RaidDefinition.Mode.PARTY_ISOLATED
                && !attempt.invited.contains(player.getUUID()))
            return Result.no("참가 불가: 초대·정원·중복 참가를 확인하세요.");
        return Result.ready("레이드에 참가할 수 있습니다.");
    }

    public Result leave(ServerPlayer player, UUID attemptId) {
        checkThread(player.server);
        RaidState state = RaidState.get(player.server);
        RaidState.Attempt attempt = state.find(attemptId).orElse(null);
        if (attempt == null || !attempt.members.containsKey(player.getUUID()))
            return Result.no("본인이 참가 중인 레이드가 아닙니다.");
        if (attempt.leader.equals(player.getUUID()))
            return Result.no("리더는 /mythraid cancel " + attemptId + " 로 모집을 취소하세요.");
        if (!state.leaveForming(attemptId, player.getUUID()))
            return Result.no("모집 중에만 탈퇴할 수 있습니다. 대기열·전투 참가 명단은 이미 고정되었습니다.");
        notifyRoster(player.server, attempt, player.getGameProfile().getName() + " 님이 모집에서 탈퇴했습니다.");
        return Result.ok("레이드 모집에서 탈퇴했습니다.", attemptId);
    }

    /** The named leader alone freezes the roster and enters the resource queue. */
    public Result start(ServerPlayer leader, UUID attemptId) {
        checkThread(leader.server);
        RaidState state = RaidState.get(leader.server);
        RaidState.Attempt attempt = state.find(attemptId).orElse(null);
        if (attempt == null || !attempt.leader.equals(leader.getUUID())) return Result.no("레이드 리더가 아닙니다.");
        if (!state.queue(attemptId, leader.getUUID(), leader.server.overworld().getGameTime()))
            return Result.no("모집 상태 또는 최소 참가 인원을 확인하세요.");
        notifyRoster(leader.server, attempt, "대기열에 등록됐습니다. 전투 구역과 신 실체가 비면 시작됩니다.");
        return Result.ok("레이드 대기열에 등록했습니다.", attemptId);
    }

    public Result cancel(ServerPlayer leader, UUID attemptId) {
        checkThread(leader.server);
        RaidState.Attempt attempt = RaidState.get(leader.server).find(attemptId).orElse(null);
        if (attempt == null || !attempt.leader.equals(leader.getUUID()) || attempt.terminal()
                || attempt.status == RaidState.Status.VICTORY_PENDING
                || attempt.status == RaidState.Status.CLEANUP_PENDING) return Result.no("취소할 수 없는 레이드입니다.");
        finish(leader.server, attempt, RaidState.Status.CANCELLED, "LEADER_CANCELLED");
        return Result.ok("레이드를 취소했습니다.", attemptId);
    }

    /** Operator-only command adapter; it cannot bypass fail-closed cleanup or revoke settled victory. */
    public Result cancelAdministrative(MinecraftServer server, UUID attemptId) {
        checkThread(server);
        RaidState.Attempt attempt = RaidState.get(server).find(attemptId).orElse(null);
        if (attempt == null || attempt.terminal() || attempt.status == RaidState.Status.VICTORY_PENDING
                || attempt.status == RaidState.Status.CLEANUP_PENDING)
            return Result.no("관리 취소할 수 없는 레이드입니다.");
        finish(server, attempt, RaidState.Status.CANCELLED, "ADMIN_CANCELLED");
        return Result.ok("관리자가 레이드를 취소했습니다.", attemptId);
    }

    public Optional<RaidState.Attempt> status(MinecraftServer server, UUID attemptId) {
        checkThread(server);
        return RaidState.get(server).find(attemptId);
    }

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) { INSTANCE.onTick(event.getServer()); }
    @SubscribeEvent(priority = EventPriority.LOWEST) public static void death(LivingDeathEvent event) { INSTANCE.onDeath(event); }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) { INSTANCE.onLogout(event); }
    @SubscribeEvent public static void login(PlayerEvent.PlayerLoggedInEvent event) { INSTANCE.onLogin(event); }
    @SubscribeEvent public static void respawn(PlayerEvent.PlayerRespawnEvent event) { INSTANCE.onRespawn(event); }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) { INSTANCE.onStopped(event.getServer()); }

    private void onTick(MinecraftServer server) {
        if (activeServer != server) {
            activeServer = server;
            deathObserved.clear();
            recover(server);
        }
        RaidState state = RaidState.get(server);
        if (!state.ready()) return;
        long now = server.overworld().getGameTime();
        for (RaidState.Attempt attempt : state.attempts()) {
            switch (attempt.status) {
                case QUEUED -> {
                    if (now - attempt.queuedAt >= attempt.definition.queueTimeoutTicks())
                        finish(server, attempt, RaidState.Status.FAILED, "QUEUE_TIMEOUT");
                    else tryStart(server, attempt, now);
                }
                case ACTIVE -> tickActive(server, attempt, now);
                case CLEANUP_PENDING -> {
                    if (server.getTickCount() % 20 == 0) finalizeCleanup(server, attempt);
                }
                case VICTORY_PENDING -> settleVictory(server, attempt);
                default -> { }
            }
        }
    }

    private void recover(MinecraftServer server) {
        RaidState state = RaidState.get(server);
        if (!state.ready()) return;
        for (RaidState.Attempt attempt : state.attempts()) {
            if (attempt.status == RaidState.Status.STARTING || attempt.status == RaidState.Status.ACTIVE) {
                // Runtime tickets/teleports cannot be presumed after a restart. Never resume combat blindly.
                abortInterrupted(server, attempt);
            } else if (attempt.status == RaidState.Status.CLEANUP_PENDING) {
                returnPlayers(server, attempt);
                finalizeCleanup(server, attempt);
            } else if (attempt.status == RaidState.Status.VICTORY_PENDING) {
                if (!cleanup(server, attempt)) {
                    attempt.pendingFinalStatus = RaidState.Status.VICTORY_PENDING;
                    attempt.status = RaidState.Status.CLEANUP_PENDING;
                    state.changed();
                    continue;
                }
                returnPlayers(server, attempt);
                settleVictory(server, attempt);
            } else if (attempt.terminal()) {
                returnPlayers(server, attempt);
            }
        }
    }

    private void tryStart(MinecraftServer server, RaidState.Attempt attempt, long now) {
        RaidState state = RaidState.get(server);
        for (RaidState.Member member : attempt.members.values()) {
            ServerPlayer player = server.getPlayerList().getPlayer(member.playerId);
            if (player == null || !player.isAlive()) return; // Queue waits, bounded by authored timeout.
            if (!conditionMatches(server, attempt.definition, member.playerId)) {
                finish(server, attempt, RaidState.Status.FAILED, "ENTRY_CONDITION_CHANGED"); return;
            }
        }
        List<RaidDefinition.Arena> options = attempt.definition.arenaIds().stream()
                .map(id -> RaidCatalog.INSTANCE.snapshot().arenas().get(id))
                .filter(java.util.Objects::nonNull).filter(state::arenaAvailable).toList();
        for (RaidDefinition.Arena arena : options) {
            ServerLevel level = level(server, arena.dimension());
            if (level == null) continue;
            if (attempt.definition.boss().godId().isPresent()
                    && !GodAvatarService.INSTANCE.acquireRaidLease(server,
                            attempt.definition.boss().godId().orElseThrow(), attempt.id)) continue;
            attempt.arena = arena;
            attempt.status = RaidState.Status.STARTING;
            state.changed();
            if (!tickets.acquire(attempt.id, level, arena.bounds())) {
                releaseGod(server, attempt);
                attempt.arena = null; attempt.status = RaidState.Status.QUEUED; state.changed();
                continue;
            }
            try {
                for (RaidState.Member member : attempt.members.values()) {
                    ServerPlayer player = server.getPlayerList().getPlayer(member.playerId);
                    if (player == null || !player.isAlive()) throw new IllegalStateException("Roster changed before teleport");
                    member.returnPoint = RaidState.ReturnPoint.of(player);
                    member.returned = false;
                    state.changed();
                    teleport(player, level, arena.entry(), player.getYRot(), player.getXRot());
                    if (player.serverLevel() != level || !arena.bounds().contains(player.position()))
                        throw new IllegalStateException("Entry teleport failed");
                }
                LivingEntity boss = spawnBoss(server, attempt, level, arena.bossSpawn());
                if (boss == null) throw new IllegalStateException("Authored boss could not spawn");
                attempt.bossId = boss.getUUID();
                attempt.spawnedEntities.add(boss.getUUID());
                attempt.startedAt = now;
                attempt.status = RaidState.Status.ACTIVE;
                state.changed();
                notifyRoster(server, attempt, "전투가 시작됐습니다.");
                return;
            } catch (RuntimeException failure) {
                MythicTrpg.LOGGER.error("Raid {} start failed", attempt.id, failure);
                finish(server, attempt, RaidState.Status.FAILED, "START_FAILED");
                return;
            }
        }
    }

    private LivingEntity spawnBoss(MinecraftServer server, RaidState.Attempt attempt, ServerLevel level, Vec3 at) {
        LivingEntity boss;
        if (attempt.definition.boss().godId().isPresent()) {
            boss = GodAvatarService.INSTANCE.spawnForRaid(level,
                    attempt.definition.boss().godId().orElseThrow(), at, attempt.id).orElse(null);
        } else {
            ResourceLocation typeId = attempt.definition.boss().entityType().orElseThrow();
            Entity entity = BuiltInRegistries.ENTITY_TYPE.getOptional(typeId).map(type -> type.create(level)).orElse(null);
            if (!(entity instanceof LivingEntity living)) return null;
            living.moveTo(at.x, at.y, at.z, level.random.nextFloat() * 360, 0);
            if (!level.noCollision(living) || !level.addFreshEntity(living)) return null;
            if (living instanceof Mob mob) mob.setPersistenceRequired();
            boss = living;
        }
        if (boss != null) boss.getPersistentData().putUUID(RAID_TAG, attempt.id);
        return boss;
    }

    private void tickActive(MinecraftServer server, RaidState.Attempt attempt, long now) {
        if (now - attempt.startedAt >= attempt.definition.combatTimeoutTicks()) {
            finish(server, attempt, RaidState.Status.FAILED, "COMBAT_TIMEOUT"); return;
        }
        ServerLevel level = level(server, attempt.arena.dimension());
        if (level == null) { finish(server, attempt, RaidState.Status.FAILED, "ARENA_UNAVAILABLE"); return; }
        if (attempt.definition.mode() == RaidDefinition.Mode.PARTY_ISOLATED && server.getTickCount() % 5 == 0) {
            for (ServerPlayer outsider : level.getEntitiesOfClass(ServerPlayer.class, attempt.arena.bounds())) {
                if (!attempt.members.containsKey(outsider.getUUID()) || attempt.members.get(outsider.getUUID()).eliminated)
                    teleport(outsider, level, attempt.arena.exit(), outsider.getYRot(), outsider.getXRot());
            }
        }
        LivingEntity boss = attempt.bossId == null ? null : living(level.getEntity(attempt.bossId));
        if (boss == null || !boss.isAlive()) {
            if (deathObserved.remove(attempt.id)) finishVictory(server, attempt);
            else finish(server, attempt, RaidState.Status.FAILED, "BOSS_MISSING_WITHOUT_DEATH");
            return;
        }
        deathObserved.remove(attempt.id); // A cancelled/resurrected death must never become a later false victory.
        if (!attempt.arena.bounds().contains(boss.position())) {
            finish(server, attempt, RaidState.Status.FAILED, "BOSS_LEFT_ARENA"); return;
        }
        for (UUID id : attempt.spawnedEntities) {
            if (id.equals(attempt.bossId) || attempt.deadEntities.contains(id)) continue;
            Entity add = level.getEntity(id);
            if (add != null && add.isAlive() && !attempt.arena.bounds().contains(add.position())) {
                finish(server, attempt, RaidState.Status.FAILED, "REINFORCEMENT_LEFT_ARENA"); return;
            }
        }
        for (RaidState.Member member : attempt.members.values()) {
            if (member.eliminated || member.awaitingRespawn) continue;
            ServerPlayer player = server.getPlayerList().getPlayer(member.playerId);
            if (player == null) {
                if (member.offlineSince < 0) { member.offlineSince = now; RaidState.get(server).changed(); }
                if (now - member.offlineSince >= attempt.definition.disconnectGraceTicks()) eliminate(server, attempt, member, "DISCONNECTED");
            } else {
                if (member.offlineSince >= 0) { member.offlineSince = -1; RaidState.get(server).changed(); }
                if (!player.isAlive()) continue;
                if (player.serverLevel() != level || !attempt.arena.bounds().contains(player.position()))
                    eliminate(server, attempt, member, "LEFT_ARENA");
            }
        }
        if (attempt.members.values().stream().allMatch(member -> member.eliminated)) {
            finish(server, attempt, RaidState.Status.FAILED, "ALL_ELIMINATED"); return;
        }
        if (server.getTickCount() % 20 == 0) {
            ServerPlayer target = attempt.members.values().stream().filter(member -> !member.eliminated && !member.awaitingRespawn)
                    .map(member -> server.getPlayerList().getPlayer(member.playerId)).filter(java.util.Objects::nonNull)
                    .filter(player -> player.isAlive() && player.serverLevel() == level && attempt.arena.bounds().contains(player.position()))
                    .min(Comparator.comparingDouble(boss::distanceToSqr)).orElse(null);
            if (target != null) {
                if (boss instanceof GodAvatarEntity god) GodAvatarService.INSTANCE.targetForRaid(god, target, attempt.id);
                else if (boss instanceof Mob mob) mob.setTarget(target);
            }
            for (UUID addId : attempt.spawnedEntities) {
                if (addId.equals(attempt.bossId)) continue;
                Entity add = level.getEntity(addId);
                if (add instanceof Mob mob && mob.isAlive()) mob.setTarget(target);
            }
        }
        double ratio = boss.getHealth() / boss.getMaxHealth();
        for (int i = 0; i < attempt.definition.phases().size(); i++) {
            int bit = 1 << i;
            if ((attempt.phaseMask & bit) == 0 && ratio <= attempt.definition.phases().get(i).atHealthRatio()) {
                // Persist the monotonic trigger before spawning: a restart must never duplicate a wave.
                attempt.phaseMask |= bit; RaidState.get(server).changed();
                if (!spawnPhase(level, attempt, attempt.definition.phases().get(i))) {
                    finish(server, attempt, RaidState.Status.FAILED, "PHASE_SPAWN_FAILED"); return;
                }
            }
        }
    }

    private boolean spawnPhase(ServerLevel level, RaidState.Attempt attempt, RaidDefinition.Phase phase) {
        for (RaidDefinition.Spawn spawn : phase.spawns()) for (int i = 0; i < spawn.count(); i++) {
            Entity entity = BuiltInRegistries.ENTITY_TYPE.getOptional(spawn.entityType())
                    .map(type -> type.create(level)).orElse(null);
            if (!(entity instanceof LivingEntity living)) return false;
            Vec3 at = findPhaseSpawn(level, attempt.arena, living,
                    attempt.arena.bossSpawn().add(spawn.offset()));
            if (at == null) return false;
            living.moveTo(at.x, at.y, at.z, level.random.nextFloat() * 360, 0);
            if (!level.addFreshEntity(living)) return false;
            living.getPersistentData().putUUID(RAID_TAG, attempt.id);
            if (living instanceof Mob mob) mob.setPersistenceRequired();
            attempt.spawnedEntities.add(living.getUUID()); RaidState.get(level.getServer()).changed();
        }
        return true;
    }

    private static Vec3 findPhaseSpawn(ServerLevel level, RaidDefinition.Arena arena, LivingEntity entity, Vec3 center) {
        for (int radius = 0; radius <= 4; radius++)
            for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                for (int dy : new int[]{0, 1, -1}) {
                    Vec3 at = center.add(dx, dy, dz);
                    if (!arena.bounds().deflate(0.25).contains(at)) continue;
                    entity.moveTo(at.x, at.y, at.z, 0, 0);
                    if (level.noCollision(entity)) return at;
                }
            }
        return null;
    }

    private void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity().level() instanceof ServerLevel level)) return;
        MinecraftServer server = level.getServer();
        if (event.getEntity() instanceof ServerPlayer player) {
            RaidState.get(server).membership(player.getUUID()).ifPresent(attempt -> {
                if (attempt.status != RaidState.Status.ACTIVE) return;
                RaidState.Member member = attempt.members.get(player.getUUID());
                if (member == null || member.eliminated || member.awaitingRespawn) return;
                member.deaths++;
                member.awaitingRespawn = true;
                if (member.deaths >= attempt.definition.livesPerPlayer()) member.eliminated = true;
                RaidState.get(server).changed();
            });
        } else {
            UUID run = raidTag(event.getEntity());
            if (run == null) return;
            RaidState.Attempt attempt = RaidState.get(server).find(run).orElse(null);
            if (attempt != null && attempt.status == RaidState.Status.ACTIVE
                    && attempt.spawnedEntities.contains(event.getEntity().getUUID())) {
                attempt.deadEntities.add(event.getEntity().getUUID());
                RaidState.get(server).changed();
                if (event.getEntity().getUUID().equals(attempt.bossId)) deathObserved.add(run);
            }
        }
    }

    private void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        RaidState.get(player.server).membership(player.getUUID()).ifPresent(attempt -> {
            if (attempt.status != RaidState.Status.ACTIVE) return;
            RaidState.Member member = attempt.members.get(player.getUUID());
            if (member != null && member.offlineSince < 0) {
                member.offlineSince = player.server.overworld().getGameTime(); RaidState.get(player.server).changed();
            }
        });
    }

    private void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        RaidState state = RaidState.get(player.server);
        if (!state.ready()) return;
        for (RaidState.Attempt attempt : state.attempts()) {
            RaidState.Member member = attempt.members.get(player.getUUID());
            if (member == null) continue;
            if (attempt.terminal() || attempt.status == RaidState.Status.VICTORY_PENDING
                    || attempt.status == RaidState.Status.CLEANUP_PENDING || member.eliminated) {
                returnMember(player.server, member); continue;
            }
            if (attempt.status == RaidState.Status.ACTIVE && member.offlineSince >= 0) {
                long elapsed = player.server.overworld().getGameTime() - member.offlineSince;
                if (elapsed >= attempt.definition.disconnectGraceTicks()) eliminate(player.server, attempt, member, "DISCONNECTED");
                else {
                    member.offlineSince = -1; state.changed();
                    ServerLevel level = level(player.server, attempt.arena.dimension());
                    if (level != null && player.isAlive()) teleport(player, level, attempt.arena.entry(), player.getYRot(), player.getXRot());
                }
            }
        }
    }

    private void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        MinecraftServer server = player.server;
        for (RaidState.Attempt attempt : RaidState.get(server).attempts()) {
            RaidState.Member member = attempt.members.get(player.getUUID());
            if (member == null || !member.awaitingRespawn) continue;
            member.awaitingRespawn = false; RaidState.get(server).changed();
            if (attempt.status == RaidState.Status.ACTIVE && !member.eliminated) {
                ServerLevel level = level(server, attempt.arena.dimension());
                if (level != null) teleport(player, level, attempt.arena.entry(), player.getYRot(), player.getXRot());
                else eliminate(server, attempt, member, "ARENA_UNAVAILABLE");
            } else returnMember(server, member);
        }
    }

    private void eliminate(MinecraftServer server, RaidState.Attempt attempt, RaidState.Member member, String reason) {
        if (member.eliminated) return;
        member.eliminated = true; RaidState.get(server).changed();
        ServerPlayer player = server.getPlayerList().getPlayer(member.playerId);
        if (player != null && player.isAlive()) returnMember(server, member);
        if (player != null) player.sendSystemMessage(Component.literal("[레이드] 참가 종료: " + reason));
    }

    private void finishVictory(MinecraftServer server, RaidState.Attempt attempt) {
        if (attempt.status != RaidState.Status.ACTIVE) return;
        for (RaidState.Member member : attempt.members.values()) {
            if (attempt.definition.rewardEligibility() == RaidDefinition.RewardEligibility.ALL_FROZEN_ROSTER
                    || !member.eliminated && member.offlineSince < 0 && present(server, attempt, member))
                attempt.rewardRecipients.add(member.playerId);
        }
        attempt.status = RaidState.Status.VICTORY_PENDING;
        attempt.reason = "BOSS_DEFEATED";
        RaidState.get(server).changed();
        attempt.pendingFinalStatus = RaidState.Status.VICTORY_PENDING;
        attempt.status = RaidState.Status.CLEANUP_PENDING;
        RaidState.get(server).changed();
        returnPlayers(server, attempt);
        finalizeCleanup(server, attempt);
    }

    private boolean present(MinecraftServer server, RaidState.Attempt attempt, RaidState.Member member) {
        ServerPlayer player = server.getPlayerList().getPlayer(member.playerId);
        return player != null && player.isAlive() && player.serverLevel().dimension().location().equals(attempt.arena.dimension())
                && attempt.arena.bounds().contains(player.position());
    }

    private void settleVictory(MinecraftServer server, RaidState.Attempt attempt) {
        if (attempt.status != RaidState.Status.VICTORY_PENDING) return;
        if (!attempt.definition.rewards().isEmpty() && !attempt.rewardRecipients.isEmpty()) {
            var payout = new ResolvedQuestReward(attempt.definition.displayName(), attempt.definition.rewards(), List.of());
            Map<UUID, ResolvedQuestReward> batch = new LinkedHashMap<>();
            for (UUID player : attempt.rewardRecipients) batch.put(player, payout);
            ResourceLocation source = ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, "raid/" + attempt.id);
            var queued = RewardClaimService.INSTANCE.queueBatch(server, attempt.definition.rewardGodId(), source, batch);
            if (!queued.succeeded()) {
                MythicTrpg.LOGGER.error("Raid {} won but reward receipts remain pending: {}", attempt.id, queued.reason());
                return;
            }
        }
        attempt.status = RaidState.Status.SUCCEEDED;
        RaidState.get(server).changed();
        publishOutcome(server, attempt, "VICTORY");
        for (UUID id : attempt.rewardRecipients) {
            ServerPlayer online = server.getPlayerList().getPlayer(id);
            if (online != null) RewardClaimService.INSTANCE.deliverQueued(online);
        }
        notifyRoster(server, attempt, "승리했습니다. 작성된 보상 영수증이 등록됐습니다.");
    }

    private void finish(MinecraftServer server, RaidState.Attempt attempt, RaidState.Status terminal, String reason) {
        if (attempt.terminal()) return;
        attempt.reason = reason;
        attempt.pendingFinalStatus = terminal;
        attempt.status = RaidState.Status.CLEANUP_PENDING;
        RaidState.get(server).changed();
        returnPlayers(server, attempt);
        finalizeCleanup(server, attempt);
    }

    private void finalizeCleanup(MinecraftServer server, RaidState.Attempt attempt) {
        if (attempt.status != RaidState.Status.CLEANUP_PENDING || attempt.pendingFinalStatus == null) return;
        if (!cleanup(server, attempt)) return;
        returnPlayers(server, attempt);
        RaidState.Status destination = attempt.pendingFinalStatus;
        attempt.pendingFinalStatus = null;
        attempt.status = destination;
        RaidState.get(server).changed();
        if (destination == RaidState.Status.VICTORY_PENDING) settleVictory(server, attempt);
        else {
            if (attempt.bossId != null) publishOutcome(server, attempt,
                    destination == RaidState.Status.CANCELLED ? "WITHDRAWN" : "DEFEAT");
            notifyRoster(server, attempt, "종료: " + attempt.reason);
        }
    }

    /** Returns false while an entity or region cannot be proven safe to release. No duplicate God lease. */
    private boolean cleanup(MinecraftServer server, RaidState.Attempt attempt) {
        if (attempt.arena != null) {
            ServerLevel level = level(server, attempt.arena.dimension());
            if (level == null || !tickets.isHeld(attempt.id)
                    && !tickets.acquire(attempt.id, level, attempt.arena.bounds())) return false;
            boolean unknownEntity = false;
            for (UUID id : List.copyOf(attempt.spawnedEntities)) {
                Entity entity = level.getEntity(id);
                if (entity == null && !attempt.deadEntities.contains(id) && !attempt.cleanedEntities.contains(id))
                    unknownEntity = true;
                else if (entity != null && attempt.id.equals(raidTag(entity))) {
                    entity.discard();
                    attempt.cleanedEntities.add(id);
                    RaidState.get(server).changed();
                }
                else if (entity != null) unknownEntity = true;
            }
            for (Entity entity : level.getEntities((Entity) null, attempt.arena.bounds().inflate(1),
                    candidate -> attempt.id.equals(raidTag(candidate)))) {
                entity.discard();
                if (attempt.spawnedEntities.contains(entity.getUUID())) {
                    attempt.cleanedEntities.add(entity.getUUID());
                    RaidState.get(server).changed();
                }
            }
            if (unknownEntity) return false;
        }
        if (!releaseGod(server, attempt)) return false;
        tickets.release(attempt.id);
        return true;
    }

    private boolean releaseGod(MinecraftServer server, RaidState.Attempt attempt) {
        ResourceLocation god = attempt.definition.boss().godId().orElse(null);
        if (god == null) return true;
        GodAvatarRegistryState registry = GodAvatarRegistryState.get(server);
        if (!registry.isReady()) return false;
        if (registry.raidOwner(god).filter(attempt.id::equals).isEmpty()) {
            // A waiting/forming attempt never acquired this resource. Cancelling it must not
            // depend on, remove or wait for another attempt's/live world's God instance.
            if (attempt.arena == null && attempt.bossId == null && attempt.spawnedEntities.isEmpty()) return true;
            return registry.find(god).isEmpty();
        }
        var ref = registry.find(god).orElse(null);
        if (ref != null) {
            if (attempt.bossId != null && !ref.entityId().equals(attempt.bossId)) return false;
            GodAvatarEntity avatar = GodAvatarService.INSTANCE.findLoaded(server, god).orElse(null);
            if (avatar == null || attempt.arena == null || avatar.level() != level(server, attempt.arena.dimension())
                    || !attempt.arena.bounds().inflate(4).contains(avatar.position())) return false;
            avatar.discard();
            if (registry.find(god).isPresent()) return false;
        }
        GodAvatarService.INSTANCE.releaseRaidLease(server, god, attempt.id);
        return true;
    }

    private void returnPlayers(MinecraftServer server, RaidState.Attempt attempt) {
        for (RaidState.Member member : attempt.members.values()) returnMember(server, member);
    }

    private void returnMember(MinecraftServer server, RaidState.Member member) {
        if (member.returned || member.returnPoint == null || member.awaitingRespawn) return;
        ServerPlayer player = server.getPlayerList().getPlayer(member.playerId);
        if (player == null || !player.isAlive()) return; // Persist until login/respawn.
        RaidState.ReturnPoint origin = member.returnPoint;
        ServerLevel level = level(server, origin.dimension());
        if (level == null) {
            MythicTrpg.LOGGER.error("Raid return dimension {} missing for {}", origin.dimension(), member.playerId);
            return;
        }
        teleport(player, level, origin.position(), origin.yaw(), origin.pitch());
        if (player.serverLevel() == level && player.position().distanceToSqr(origin.position()) < 4) {
            member.returned = true; RaidState.get(server).changed();
        }
    }

    private void publishOutcome(MinecraftServer server, RaidState.Attempt attempt, String outcome) {
        if (attempt.storyPublished || attempt.arena == null) return;
        // The result transition is authoritative even when the optional ledger or Story projection is unavailable.
        attempt.storyPublished = true; RaidState.get(server).changed();
        ServerLevel level = level(server, attempt.arena.dimension());
        if (level != null) ImportantEvents.battle(server, attempt.id, attempt.definition.id(), attempt.leader,
                Set.copyOf(attempt.members.keySet()), level, BlockPos.containing(attempt.arena.bossSpawn()),
                outcome, "FROZEN_EXPLICIT_RAID_ROSTER");
        ResourceLocation type = outcome.equals("VICTORY") ? StorySignalTypes.RAID_VICTORY : StorySignalTypes.RAID_FAILED;
        try {
            var submission = StoryEventService.INSTANCE.submit(server, new StorySignal(attempt.id, type,
                    Optional.of(attempt.leader), Optional.of(attempt.definition.id()), Optional.empty(),
                    Optional.of(attempt.id.toString()), server.overworld().getGameTime()));
            if (submission.status() == StoryEventService.Status.REJECTED)
                MythicTrpg.LOGGER.warn("Raid Story signal rejected for {}: {}", attempt.id, submission.reason());
        } catch (RuntimeException failure) { MythicTrpg.LOGGER.warn("Raid Story signal unavailable for {}", attempt.id, failure); }
    }

    private void notifyRoster(MinecraftServer server, RaidState.Attempt attempt, String text) {
        for (UUID id : attempt.members.keySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player != null) player.sendSystemMessage(Component.literal("[레이드 " + attempt.id + "] " + text));
        }
    }

    private void onStopped(MinecraftServer server) {
        if (activeServer == server) { tickets.releaseAll(); deathObserved.clear(); activeServer = null; }
    }

    private void abortInterrupted(MinecraftServer server, RaidState.Attempt attempt) {
        tickets.release(attempt.id);
        finish(server, attempt, RaidState.Status.FAILED, "SERVER_RESTART");
    }

    /** GameTest-only interrupted-attempt recovery; does not disturb parallel test attempts. */
    void simulateRuntimeRestartForTesting(MinecraftServer server, UUID attemptId) {
        checkThread(server);
        RaidState.Attempt attempt = RaidState.get(server).find(attemptId).orElseThrow();
        if (attempt.status != RaidState.Status.STARTING && attempt.status != RaidState.Status.ACTIVE)
            throw new IllegalStateException("Expected an interrupted active raid");
        abortInterrupted(server, attempt);
    }

    static UUID raidTag(Entity entity) {
        return entity.getPersistentData().hasUUID(RAID_TAG) ? entity.getPersistentData().getUUID(RAID_TAG) : null;
    }
    static LivingEntity living(Entity entity) { return entity instanceof LivingEntity living ? living : null; }
    static ServerLevel level(MinecraftServer server, ResourceLocation id) {
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
    }
    private static void teleport(ServerPlayer player, ServerLevel level, Vec3 point, float yaw, float pitch) {
        player.teleportTo(level, point.x, point.y, point.z, yaw, pitch);
    }
    private static boolean conditionMatches(MinecraftServer server, RaidDefinition definition, UUID player) {
        try { return ConditionEngine.INSTANCE.evaluate(definition.entryCondition(),
                ConditionContexts.forServer(server, Optional.of(player))) == ConditionResult.MATCH; }
        catch (RuntimeException failure) { MythicTrpg.LOGGER.warn("Raid entry evaluation failed for {}", player, failure); return false; }
    }
    private static void checkThread(MinecraftServer server) {
        if (server == null || !server.isSameThread()) throw new IllegalStateException("Raid mutation requires server thread");
    }
}
