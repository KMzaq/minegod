package com.sande.mythictrpg.godavatar.activity;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.data.god.GodDefinitionManager;
import com.sande.mythictrpg.godavatar.GodAvatarDefinitionManager;
import com.sande.mythictrpg.godavatar.GodAvatarEntity;
import com.sande.mythictrpg.godavatar.GodAvatarRegistryState;
import com.sande.mythictrpg.raid.RaidState;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Explicitly accepted melee practice. Never calls hurt(), damages equipment, gives loot or changes affinity. */
@EventBusSubscriber(modid = MythicTrpg.MOD_ID)
public final class NpcSparring {
    public static final NpcSparring INSTANCE = new NpcSparring();
    private final Map<UUID, Invitation> invitations = new HashMap<>();
    private final Map<UUID, Match> matches = new HashMap<>();
    private NpcSparring() {}
    public record Result(boolean success, String message) {}
    public record Score(int playerRemaining, int npcRemaining, int requiredHits) {}

    public Result invite(ServerPlayer player, GodAvatarEntity avatar) { return invite(player, avatar, 20); }
    public Result invite(ServerPlayer player, GodAvatarEntity avatar, int requiredHits) {
        checkThread(player.server);
        if (requiredHits < 1 || requiredHits > 100) return no("연습 목표는 1~100회입니다.");
        if (!eligible(player, avatar) || active(player) || active(avatar)) return no("지금 이 상대와 대련할 수 없습니다.");
        if (invitations.size() >= 64 && !invitations.containsKey(player.getUUID())) return no("대련 초대 대기열이 가득 찼습니다.");
        invitations.put(player.getUUID(), new Invitation(player, avatar, clock(player.server) + 600, requiredHits,
                GodAvatarDefinitionManager.INSTANCE.generation(), GodDefinitionManager.INSTANCE.generation(),
                NpcActivityDefinitions.INSTANCE.generation(), avatar.orderRevision()));
        player.sendSystemMessage(Component.literal("대련 초대: /mythnpc spar accept 로 동의하거나 decline 으로 거절하세요. 실제 체력·장비·보상은 바뀌지 않습니다."));
        return ok("대련 동의를 기다립니다.");
    }
    public Result accept(ServerPlayer player) {
        checkThread(player.server);
        Invitation invite = invitations.remove(player.getUUID());
        if (invite == null || invite.player != player || invite.deadline < clock(player.server)
                || invite.avatarGeneration != GodAvatarDefinitionManager.INSTANCE.generation()
                || invite.godGeneration != GodDefinitionManager.INSTANCE.generation()
                || invite.activityGeneration != NpcActivityDefinitions.INSTANCE.generation()
                || invite.orderRevision != invite.avatar.orderRevision()
                || !eligible(player, invite.avatar) || active(player) || active(invite.avatar)) return no("유효한 대련 초대가 없습니다.");
        NpcActivityRuntime.interrupt(invite.avatar, "SPARRING_ACCEPTED");
        NpcActivityBody.end(invite.avatar);
        Match match = new Match(invite, clock(player.server));
        matches.put(player.getUUID(), match);
        invite.avatar.setActivityVisual("TRAIN", ItemStack.EMPTY);
        experience(match, "STARTED", "Consensual virtual-hit practice began; no real damage or rewards");
        display(match);
        return ok("연습 대련을 시작했습니다. leave로 언제든 끝낼 수 있습니다.");
    }
    public Result decline(ServerPlayer player) {
        checkThread(player.server);
        return invitations.remove(player.getUUID()) != null ? ok("대련 초대를 거절했습니다.") : no("대기 중인 초대가 없습니다.");
    }
    public Result leave(ServerPlayer player) {
        checkThread(player.server);
        invitations.remove(player.getUUID());
        Match match = matches.get(player.getUUID());
        if (match == null) return no("대련 중이 아닙니다.");
        finish(match, "자발적 종료"); return ok("대련을 끝냈습니다.");
    }
    public boolean active(ServerPlayer player) { Match match = matches.get(player.getUUID()); return match != null && match.player == player; }
    public boolean active(GodAvatarEntity avatar) { return match(avatar) != null; }
    public boolean awaiting(GodAvatarEntity avatar) {
        return invitations.values().stream().anyMatch(i -> i.avatar == avatar && i.deadline >= clock(i.player.server)
                && i.avatarGeneration == GodAvatarDefinitionManager.INSTANCE.generation()
                && i.godGeneration == GodDefinitionManager.INSTANCE.generation()
                && i.activityGeneration == NpcActivityDefinitions.INSTANCE.generation()
                && i.orderRevision == avatar.orderRevision() && eligible(i.player, avatar));
    }
    public java.util.Optional<Score> score(ServerPlayer player) {
        Match match = matches.get(player.getUUID());
        return match == null || match.player != player ? java.util.Optional.empty()
                : java.util.Optional.of(new Score(match.playerRemaining, match.npcRemaining, match.requiredHits));
    }
    public void interrupt(GodAvatarEntity avatar, String reason) {
        invitations.values().removeIf(invite -> invite.avatar == avatar);
        Match match = match(avatar);
        if (match != null) finish(match, reason);
    }

    /** Runs before the avatar's authored invulnerability check. Only a current paired source is blocked. */
    public boolean avatarDamage(GodAvatarEntity avatar, DamageSource source) {
        Match match = match(avatar);
        if (match == null) return false;
        if (valid(match) && source.getEntity() == match.player) return true;
        finish(match, "외부 피해 또는 상태 변경"); return false;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void attack(AttackEntityEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Match match = INSTANCE.matches.get(player.getUUID());
        if (match == null || match.player != player) return;
        if (event.getTarget() != match.avatar || !INSTANCE.valid(match)) {
            INSTANCE.finish(match, "다른 대상 공격 또는 상태 변경"); return;
        }
        // Before Player.attack: sweep, enchantment effects and held-item durability never execute.
        event.setCanceled(true);
        long now = clock(player.server);
        if (now < match.nextPlayerHit || player.distanceToSqr(match.avatar) > 9 || !player.hasLineOfSight(match.avatar)) return;
        match.nextPlayerHit = now + 10;
        match.npcRemaining--;
        player.swing(InteractionHand.MAIN_HAND, true);
        player.resetAttackStrengthTicker();
        INSTANCE.display(match);
        if (match.npcRemaining <= 0) INSTANCE.finish(match, "플레이어 연습 목표 달성 (보상 없음)");
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void incoming(LivingIncomingDamageEvent event) {
        Match match = event.getEntity() instanceof ServerPlayer player ? INSTANCE.matches.get(player.getUUID())
                : event.getEntity() instanceof GodAvatarEntity avatar ? INSTANCE.match(avatar) : null;
        if (match == null) return;
        Entity paired = event.getEntity() == match.player ? match.avatar : match.player;
        if (INSTANCE.valid(match) && event.getSource().getEntity() == paired) event.setCanceled(true);
        else INSTANCE.finish(match, "외부 피해 또는 상태 변경"); // deliberately NOT canceled
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) { INSTANCE.tickMatches(event.getServer()); }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            INSTANCE.invitations.remove(player.getUUID());
            Match match = INSTANCE.matches.get(player.getUUID());
            if (match != null) INSTANCE.finish(match, "접속 종료");
        }
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) {
        INSTANCE.invitations.clear(); INSTANCE.matches.clear();
    }
    @SubscribeEvent public static void stopping(ServerStoppingEvent event) {
        // Close actual experience before SavedData is flushed, not after the server has stopped.
        INSTANCE.invitations.values().removeIf(invite -> invite.player.server == event.getServer());
        for (Match match : java.util.List.copyOf(INSTANCE.matches.values()))
            if (match.player.server == event.getServer()) INSTANCE.finish(match, "서버 정상 종료");
    }

    private void tickMatches(MinecraftServer server) {
        long now = clock(server);
        invitations.values().removeIf(invite -> invite.player.server == server && (now > invite.deadline
                || !eligible(invite.player, invite.avatar)));
        for (Match match : java.util.List.copyOf(matches.values())) {
            if (match.player.server != server) continue;
            if (now > match.deadline || !valid(match)) { finish(match, "시간·거리·전투 상태 변경"); continue; }
            match.avatar.getLookControl().setLookAt(match.player, 30, 30);
            if (match.avatar.distanceToSqr(match.player) > 6.25) {
                if (match.avatar.currentDefinition().orElseThrow().movement().enabled()) {
                    var path = match.avatar.getNavigation().createPath(match.player, 0);
                    boolean allowed = path != null && path.canReach();
                    if (allowed) for (int node = 0; node < path.getNodeCount(); node++)
                        if (!NpcActivityAccess.canUse(match.avatar, path.getNodePos(node))) { allowed = false; break; }
                    if (!allowed) { finish(match, "허용된 접근 경로 없음"); continue; }
                    match.avatar.getNavigation().moveTo(path, match.avatar.currentDefinition().orElseThrow().movement().navigationSpeed());
                }
            } else {
                match.avatar.getNavigation().stop();
                if (now >= match.nextNpcHit && match.avatar.hasLineOfSight(match.player)) {
                    match.nextNpcHit = now + 30;
                    match.avatar.swing(InteractionHand.MAIN_HAND);
                    match.playerRemaining--; display(match);
                    if (match.playerRemaining <= 0) finish(match, "NPC 연습 목표 달성 (보상 없음)");
                }
            }
        }
    }
    private boolean eligible(ServerPlayer player, GodAvatarEntity avatar) {
        if (player.isRemoved() || !player.isAlive() || player.isSpectator() || !avatar.isAlive() || avatar.isRemoved()
                || player.level() != avatar.level() || player.distanceToSqr(avatar) > 144
                || !avatar.hasAuthoritativeBinding() || avatar.currentDefinition().isEmpty() || avatar.getTarget() != null) return false;
        if (!NpcActivityAccess.canUse(avatar, avatar.blockPosition()) || !NpcActivityAccess.canUse(avatar, player.blockPosition())) return false;
        var raid = RaidState.get(player.server);
        return raid.ready() && raid.membership(player.getUUID()).isEmpty() && avatar.godId()
                .filter(id -> GodAvatarRegistryState.get(player.server).raidOwner(id).isPresent()).isEmpty();
    }
    private boolean valid(Match match) {
        return eligible(match.player, match.avatar) && match.avatar.orderRevision() == match.orderRevision
                && match.avatarGeneration == GodAvatarDefinitionManager.INSTANCE.generation()
                && match.godGeneration == GodDefinitionManager.INSTANCE.generation()
                && match.activityGeneration == NpcActivityDefinitions.INSTANCE.generation();
    }
    private Match match(GodAvatarEntity avatar) {
        return matches.values().stream().filter(value -> value.avatar == avatar).findFirst().orElse(null);
    }
    private void finish(Match match, String reason) {
        if (!matches.remove(match.player.getUUID(), match)) return;
        experience(match, match.playerRemaining <= 0 || match.npcRemaining <= 0 ? "COMPLETED" : "INTERRUPTED", reason);
        match.avatar.getNavigation().stop();
        if (!match.avatar.isRemoved() && match.avatar.level() instanceof ServerLevel) match.avatar.clearActivityVisual();
        match.player.sendSystemMessage(Component.literal("대련 종료: " + reason + ". 실제 체력·보상 변화 없음."));
    }
    private static void experience(Match match, String phase, String detail) {
        var god = match.avatar.godId().orElseThrow().toString();
        NpcActivityWorldState.get(match.player.server).activityMemory().record(new NpcActivityMemory.Event(
                NpcActivityRuntime.eventId(match.runId, phase), match.runId, god, match.avatar.getUUID(), clock(match.player.server),
                "mythictrpg:npc_practice_runtime", "TRAIN", "REAL", phase, detail, "", "",
                java.util.Set.of(god), java.util.Set.of(match.player.getUUID())));
    }
    private void display(Match match) {
        match.player.displayClientMessage(Component.literal("연습 잔여 점수 나 " + Math.max(0, match.playerRemaining)
                + " / 상대 " + Math.max(0, match.npcRemaining) + " (" + match.requiredHits + "회, 무피해)"), true);
    }
    private static long clock(MinecraftServer server) { return server.overworld().getGameTime(); }
    private static void checkThread(MinecraftServer server) { if (!server.isSameThread()) throw new IllegalStateException("Server thread required"); }
    private static Result ok(String text) { return new Result(true, text); }
    private static Result no(String text) { return new Result(false, text); }
    private record Invitation(ServerPlayer player, GodAvatarEntity avatar, long deadline, int requiredHits,
            long avatarGeneration, long godGeneration, long activityGeneration, long orderRevision) {}
    private static final class Match {
        final UUID runId = UUID.randomUUID();
        final ServerPlayer player; final GodAvatarEntity avatar;
        final long deadline, avatarGeneration, godGeneration, activityGeneration, orderRevision;
        final int requiredHits; int playerRemaining, npcRemaining;
        long nextPlayerHit, nextNpcHit;
        Match(Invitation invite, long now) {
            player = invite.player; avatar = invite.avatar; requiredHits = invite.requiredHits;
            playerRemaining = npcRemaining = requiredHits; deadline = now + 3600;
            avatarGeneration = invite.avatarGeneration; godGeneration = invite.godGeneration;
            activityGeneration = invite.activityGeneration;
            orderRevision = avatar.orderRevision(); nextNpcHit = now + 40;
        }
    }
}
