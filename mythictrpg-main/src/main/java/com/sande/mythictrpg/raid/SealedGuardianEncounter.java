package com.sande.mythictrpg.raid;

import com.sande.mythictrpg.raid.combat.RaidAttackGeometry;
import com.sande.mythictrpg.raid.combat.RaidPatternTimeline;
import com.sande.mythictrpg.raid.trial.SealedGuardianEntity;
import com.sande.mythictrpg.understanding.RaidChoiceEvidence;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Authored, non-canonical first encounter. Server-only attack decisions; entity data is cosmetic.
 * Active attempts intentionally abort on restart through RaidRuntime, so casts are never resumed. */
final class SealedGuardianEncounter {
    static final ResourceLocation RAID_ID = ResourceLocation.fromNamespaceAndPath("mythictrpg", "trial/sealed_guardian");
    private static final Map<UUID, Fight> FIGHTS = new HashMap<>();
    private static final DustParticleOptions DANGER = new DustParticleOptions(new Vector3f(1, .20f, .06f), 1.4f);
    private static final DustParticleOptions SEALED = new DustParticleOptions(new Vector3f(.12f, .8f, .95f), 1.3f);
    private static final DustParticleOptions RELEASED = new DustParticleOptions(new Vector3f(.95f, .85f, .2f), 1.5f);
    static final int SEAL_CHARGE_TICKS = 40, SHIELD_TICKS = 900;
    private static final double SWEEP_RANGE = 8, SWEEP_HALF_ANGLE = 60,
            RING_INNER = 3, RING_OUTER = 11, MARK_RADIUS = 2.5, HIT_MIN_Y = -.5, HIT_MAX_Y = 3;
    enum Pattern { SWEEP, RING, BOMBARD }
    private record Cast(Pattern pattern, Vec3 center, Vec3 direction, List<Vec3> marks,
                        long start, int windup, int recovery, RaidPatternTimeline timeline) { }
    private static final class Fight {
        final ServerBossEvent bar = new ServerBossEvent(Component.literal("봉인된 석상 수호자"),
                BossEvent.BossBarColor.BLUE, BossEvent.BossBarOverlay.PROGRESS);
        int phase = 1, sequence;
        final int[] sealCharge = new int[3];
        final List<Map<UUID, SealOpportunity>> sealOpportunities = List.of(new HashMap<>(),new HashMap<>(),new HashMap<>());
        final java.util.Set<UUID> sealInstructionRecipients = new HashSet<>();
        boolean shieldDone, shielding;
        long shieldUntil, staggerUntil, nextAttack, lastTick = Long.MIN_VALUE;
        Cast cast;
    }
    private static final class SealOpportunity {
        int continuous, longest;
        boolean attempted, closed;
    }
    record View(int phase, boolean shielding, int releasedSeals, boolean casting, long nextAttack) { }

    static void begin(MinecraftServer server, RaidState.Attempt attempt, SealedGuardianEntity boss, long now) {
        stop(attempt.id);
        Fight fight = new Fight();
        fight.nextAttack = now + 80;
        FIGHTS.put(attempt.id, fight);
        boss.getAttribute(Attributes.MAX_HEALTH).setBaseValue(180 + 70 * (attempt.members.size() - 1));
        boss.setHealth(boss.getMaxHealth());
        boss.setNoAi(true);
        boss.setCustomName(Component.literal("봉인된 석상 수호자"));
        boss.setCombatVisual(SealedGuardianEntity.IDLE, 0, 100, 1, false);
        participants(server,attempt).forEach(p->fight.sealInstructionRecipients.add(p.getUUID()));
        announce(server, attempt, "시련 시작: 주황색 바닥은 공격 범위입니다. 봉인석이 켜지면 곁에서 2초간 웅크리세요.");
    }

    static void tick(MinecraftServer server, RaidState.Attempt attempt, SealedGuardianEntity boss, long now) {
        Fight f = FIGHTS.get(attempt.id);
        if (f == null || now <= f.lastTick || attempt.status != RaidState.Status.ACTIVE || !boss.isAlive()) return;
        f.lastTick = now;
        ServerLevel level = (ServerLevel) boss.level();
        List<ServerPlayer> players = participants(server, attempt);
        var present = new HashSet<>(players);
        for (ServerPlayer old : List.copyOf(f.bar.getPlayers())) if (!present.contains(old)) f.bar.removePlayer(old);
        players.forEach(f.bar::addPlayer);
        f.bar.setProgress(Math.max(0, Math.min(1, boss.getHealth() / boss.getMaxHealth())));
        // The bound statue deliberately stays anchored; the ranged pattern prevents a distant safe spot.
        boss.getNavigation().stop();
        boss.setDeltaMovement(Vec3.ZERO);
        boss.setPos(attempt.arena.bossSpawn());
        if (players.isEmpty()) return;

        float ratio = boss.getHealth() / boss.getMaxHealth();
        if (!f.shieldDone && ratio <= .65f) {
            cancelCast(f);
            f.shieldDone = true;
            f.shielding = true;
            f.shieldUntil = now + SHIELD_TICKS;
            f.nextAttack = now + 60;
            announce(server, attempt, "봉인 결계! 빛나는 봉인석 세 곳의 2.5칸 이내에서 각각 2초간 웅크리세요. 제한 시간 45초.");
            sound(level, boss.position(), SoundEvents.BEACON_ACTIVATE, .7f);
        }
        if (f.shielding) {
            tickSeals(server, attempt, f, players, level, now);
            if (released(f) == 3 || now >= f.shieldUntil) {
                for(int seal=0;seal<3;seal++) closeSealEvidence(server,attempt,f,seal);
                boolean success = released(f) == 3;
                f.shielding = false;
                f.phase = 2;
                f.staggerUntil = now + (success ? 100 : 20);
                f.nextAttack = f.staggerUntil + 35;
                cancelCast(f);
                if (!success) for (ServerPlayer player : participants(server, attempt))
                    player.hurt(boss.damageSources().mobAttack(boss), 6);
                announce(server, attempt, success ? "봉인 해제 성공! 수호자가 5초간 무방비 상태가 됩니다."
                        : "봉인 해제 실패! 결계가 폭발했습니다. 수호자의 2단계가 시작됩니다.");
                sound(level, boss.position(), SoundEvents.BEACON_DEACTIVATE, .6f);
            }
        }
        if (!f.shielding && f.phase == 2 && ratio <= .30f && now >= f.staggerUntil) {
            f.phase = 3;
            cancelCast(f);
            f.nextAttack = now + 45;
            announce(server, attempt, "핵 과부하! 공격 예고가 빨라집니다.");
            sound(level, boss.position(), SoundEvents.IRON_GOLEM_REPAIR, .5f);
        }

        String status = f.shielding ? "봉인 " + released(f) + "/3 · " + Math.max(0, (f.shieldUntil - now + 19) / 20) + "초"
                : now < f.staggerUntil ? "무방비" : f.phase == 3 ? "3단계 · 핵 과부하" : f.phase + "단계";
        f.bar.setName(Component.literal("봉인된 석상 수호자 — " + status));
        f.bar.setColor(f.shielding ? BossEvent.BossBarColor.BLUE : f.phase == 3 ? BossEvent.BossBarColor.RED : BossEvent.BossBarColor.WHITE);
        if (now < f.staggerUntil) {
            boss.setCombatVisual(SealedGuardianEntity.STAGGER, 0, 100, f.phase, false);
            return;
        }
        if (f.cast == null && now >= f.nextAttack) {
            Pattern pattern = f.shielding ? Pattern.BOMBARD : Pattern.values()[f.sequence++ % 3];
            int windup = (pattern == Pattern.RING ? 48 : 40) - (f.phase == 3 ? 12 : 0);
            int recovery = f.phase == 3 ? 22 : 32;
            Vec3 center = boss.position();
            ServerPlayer target = players.get((f.sequence / 3) % players.size());
            Vec3 direction = target.position().subtract(center).multiply(1, 0, 1);
            if (direction.lengthSqr() < .001) direction = new Vec3(0, 0, 1);
            direction = direction.normalize();
            var marks = new ArrayList<Vec3>();
            if (pattern == Pattern.BOMBARD) for (ServerPlayer player : players.stream().limit(4).toList())
                marks.add(new Vec3(player.getX(), center.y, player.getZ()));
            f.cast = new Cast(pattern, center, direction, List.copyOf(marks), now, windup, recovery,
                    new RaidPatternTimeline(now, windup, 1, recovery));
            float yaw = (float) Math.toDegrees(Math.atan2(-direction.x, direction.z));
            boss.setYRot(yaw); boss.setYBodyRot(yaw); boss.setYHeadRot(yaw);
            announceAction(players, switch (pattern) {
                case SWEEP -> "전방 휩쓸기: 측면이나 뒤로 피하세요";
                case RING -> "환형 충격파: 핵에서 3칸보다 안쪽 또는 11칸 밖으로 피하세요";
                case BOMBARD -> "봉인 낙하: 표시된 원에서 벗어나세요";
            });
            sound(level, center, SoundEvents.NOTE_BLOCK_BELL.value(), .8f);
        }
        Cast cast = f.cast;
        if (cast == null) {
            boss.setCombatVisual(f.shielding ? SealedGuardianEntity.SHIELD : f.phase == 3
                    ? SealedGuardianEntity.RAGE : SealedGuardianEntity.IDLE, 0, 100, f.phase, f.shielding);
            return;
        }
        var step = cast.timeline.advance(now);
        if (step.stage() == RaidPatternTimeline.Stage.WINDUP && now % 4 == 0) telegraph(level, cast);
        if (step.enteredActive()) {
            for (ServerPlayer player : participants(server, attempt)) if (hits(cast, player.position()))
                player.hurt(boss.damageSources().mobAttack(boss), cast.pattern == Pattern.BOMBARD ? 4 : 6);
            level.sendParticles(ParticleTypes.POOF, cast.center.x, cast.center.y + .3, cast.center.z, 24, 2, .2, 2, .04);
            for (Vec3 mark : cast.marks) level.sendParticles(ParticleTypes.EXPLOSION, mark.x, mark.y + .2, mark.z, 1, 0, 0, 0, 0);
            sound(level, cast.center, SoundEvents.IRON_GOLEM_ATTACK, .7f);
        }
        long elapsed = now - cast.start;
        // Both native animation impact curves use .60. Tick time remains the sole authority for hits.
        int visual = elapsed < cast.windup ? (int) (60 * elapsed / cast.windup)
                : 60 + (int) Math.min(40, 40 * (elapsed - cast.windup) / (cast.recovery + 1));
        int pose = f.shielding ? SealedGuardianEntity.SHIELD : switch (cast.pattern) {
            case SWEEP -> SealedGuardianEntity.SWEEP;
            case RING -> SealedGuardianEntity.SLAM;
            case BOMBARD -> SealedGuardianEntity.BOMBARD;
        };
        boss.setCombatVisual(pose, visual, 100, f.phase, f.shielding);
        if (step.completed()) { f.cast = null; f.nextAttack = now + (f.shielding ? 35 : f.phase == 3 ? 10 : 24); }
    }

    private static void tickSeals(MinecraftServer server, RaidState.Attempt attempt, Fight f,
                                  List<ServerPlayer> players, ServerLevel level, long now) {
        for (int i = 0; i < TrialRaidArenaService.SEAL_ANCHORS.size(); i++) {
            Vec3 seal = Vec3.atBottomCenterOf(TrialRaidArenaService.SEAL_ANCHORS.get(i));
            boolean complete = f.sealCharge[i] >= SEAL_CHARGE_TICKS;
            if(!complete) {
                var available=new HashSet<UUID>();
                for(var p:players) if(p.position().distanceToSqr(seal)<=6.25 && !p.isSleeping()) {
                    available.add(p.getUUID());
                    var opportunity=f.sealOpportunities.get(i).computeIfAbsent(p.getUUID(),key->new SealOpportunity());
                    opportunity.longest=Math.max(opportunity.longest,++opportunity.continuous);
                    opportunity.attempted |= p.isShiftKeyDown();
                }
                f.sealOpportunities.get(i).forEach((id,opportunity)->{if(!available.contains(id))opportunity.continuous=0;});
            }
            boolean occupied = players.stream().anyMatch(p -> p.isShiftKeyDown()
                    && p.position().distanceToSqr(seal) <= 6.25);
            if (!complete) {
                f.sealCharge[i] = occupied ? f.sealCharge[i] + 1 : 0;
                if (f.sealCharge[i] == SEAL_CHARGE_TICKS) {
                    closeSealEvidence(server,attempt,f,i);
                    announce(server, attempt, "봉인석 " + (i + 1) + " 해제 (" + released(f) + "/3)");
                    sound(level, seal, SoundEvents.AMETHYST_BLOCK_CHIME, 1.2f);
                }
            }
            if (now % 5 == 0) {
                circle(level, seal, 2.5, complete ? RELEASED : SEALED, 24);
                level.sendParticles(complete ? RELEASED : SEALED, seal.x, seal.y + 1.5 + f.sealCharge[i] / 40.0,
                        seal.z, 3, .2, .15, .2, 0);
            }
            if (occupied && !complete && now % 10 == 0) for (ServerPlayer player : players)
                if (player.position().distanceToSqr(seal) <= 6.25)
                    player.displayClientMessage(Component.literal("봉인석 " + (i + 1) + " · " + f.sealCharge[i] * 100 / SEAL_CHARGE_TICKS + "%"), true);
        }
    }

    private static void closeSealEvidence(MinecraftServer server,RaidState.Attempt attempt,Fight f,int seal) {
        var decision=UUID.nameUUIDFromBytes((attempt.id()+"/seal/"+seal).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        f.sealOpportunities.get(seal).forEach((player,opportunity)->{
            if(opportunity.closed)return;
            opportunity.closed=true;
            var result=opportunity.attempted?RaidChoiceEvidence.Result.CHOSEN
                    :opportunity.longest>=SEAL_CHARGE_TICKS&&f.sealInstructionRecipients.contains(player)
                        ?RaidChoiceEvidence.Result.NOT_CHOSEN: RaidChoiceEvidence.Result.UNAVAILABLE;
            RaidChoiceCapture.record(server,attempt,player,new RaidChoiceEvidence(decision,RaidChoiceEvidence.Kind.MECHANIC,
                    result,"seal-"+seal,"sealed_guardian-v1",true));
        });
    }

    private static boolean hits(Cast cast, Vec3 p) {
        Vec3 c = cast.center;
        return switch (cast.pattern) {
            case SWEEP -> RaidAttackGeometry.containsCone(p.x,p.y,p.z,c.x,c.y,c.z,cast.direction.x,cast.direction.z,
                    SWEEP_RANGE,SWEEP_HALF_ANGLE,HIT_MIN_Y,HIT_MAX_Y);
            case RING -> RaidAttackGeometry.containsRing(p.x,p.y,p.z,c.x,c.y,c.z,RING_INNER,RING_OUTER,HIT_MIN_Y,HIT_MAX_Y);
            case BOMBARD -> cast.marks.stream().anyMatch(m -> RaidAttackGeometry.containsCircle(p.x,p.y,p.z,m.x,m.y,m.z,
                    MARK_RADIUS,HIT_MIN_Y,HIT_MAX_Y));
        };
    }

    private static void telegraph(ServerLevel level, Cast cast) {
        if (cast.pattern == Pattern.BOMBARD) {
            for (Vec3 mark : cast.marks) {
                circle(level, mark, MARK_RADIUS, DANGER, 24);
                level.sendParticles(DANGER, mark.x, mark.y + 4, mark.z, 3, .15, .3, .15, 0);
            }
        } else if (cast.pattern == Pattern.RING) {
            circle(level, cast.center, RING_INNER, DANGER, 24);
            circle(level, cast.center, (RING_INNER + RING_OUTER) / 2, DANGER, 40);
            circle(level, cast.center, RING_OUTER, DANGER, 56);
        } else {
            double angle = Math.atan2(cast.direction.z, cast.direction.x);
            for (int ring = 1; ring <= 4; ring++) for (int step = 0; step <= 16; step++) {
                double a = angle + Math.toRadians(-SWEEP_HALF_ANGLE + step * SWEEP_HALF_ANGLE / 8);
                dot(level, cast.center.add(Math.cos(a) * ring * SWEEP_RANGE / 4, 0,
                        Math.sin(a) * ring * SWEEP_RANGE / 4), DANGER);
            }
        }
    }
    private static void circle(ServerLevel level, Vec3 center, double radius, DustParticleOptions color, int points) {
        for (int i = 0; i < points; i++) {
            double angle = Math.PI * 2 * i / points;
            dot(level, center.add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius), color);
        }
    }
    private static void dot(ServerLevel level, Vec3 p, DustParticleOptions color) {
        level.sendParticles(color, p.x, p.y + .12, p.z, 1, 0, 0, 0, 0);
    }
    private static void sound(ServerLevel level, Vec3 p, net.minecraft.sounds.SoundEvent sound, float pitch) {
        level.playSound(null, p.x, p.y, p.z, sound, SoundSource.HOSTILE, .8f, pitch);
    }
    static List<ServerPlayer> participants(MinecraftServer server, RaidState.Attempt attempt) {
        if (attempt.status != RaidState.Status.ACTIVE || attempt.arena == null) return List.of();
        return attempt.members.values().stream().filter(m -> !m.eliminated && !m.awaitingRespawn)
                .map(m -> server.getPlayerList().getPlayer(m.playerId)).filter(java.util.Objects::nonNull)
                .filter(p -> p.isAlive() && p.serverLevel().dimension().location().equals(attempt.arena.dimension())
                        && attempt.arena.bounds().contains(p.position())).toList();
    }
    private static void announce(MinecraftServer server, RaidState.Attempt attempt, String message) {
        for (ServerPlayer p : participants(server, attempt)) p.sendSystemMessage(Component.literal("[석상 시련] " + message));
    }
    private static void announceAction(List<ServerPlayer> players, String message) {
        for (ServerPlayer p : players) p.displayClientMessage(Component.literal(message), true);
    }
    private static int released(Fight f) { int n = 0; for (int charge : f.sealCharge) if (charge >= SEAL_CHARGE_TICKS) n++; return n; }
    private static void cancelCast(Fight f) { if (f.cast != null) f.cast.timeline.cancel(); f.cast = null; }
    static View view(UUID id) {
        Fight f = FIGHTS.get(id);
        return f == null ? null : new View(f.phase, f.shielding, released(f), f.cast != null, f.nextAttack);
    }
    static void stop(UUID id) { Fight f = FIGHTS.remove(id); if (f != null) { cancelCast(f); f.bar.removeAllPlayers(); } }
    static void stopAll() { for (UUID id : List.copyOf(FIGHTS.keySet())) stop(id); }
}
