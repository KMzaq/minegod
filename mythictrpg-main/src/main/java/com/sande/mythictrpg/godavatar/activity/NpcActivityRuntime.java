package com.sande.mythictrpg.godavatar.activity;

import com.google.gson.Gson;
import com.sande.mythictrpg.godavatar.*;
import com.sande.mythictrpg.godavatar.activity.work.NpcActivityWork;
import com.sande.mythictrpg.ai.room.ConversationRoomSnapshot;
import com.sande.mythictrpg.data.god.GodIdentityService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.sounds.*;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Physical activities are game state. All LLM choices remain revocable proposals. */
public final class NpcActivityRuntime {
    public static final NpcActivityRuntime INSTANCE = new NpcActivityRuntime();
    private static final Gson JSON = new Gson();
    private static final int MAX_CANDIDATES = 16;
    private final Map<UUID, GodAvatarEntity> actors = new LinkedHashMap<>();
    private final Map<UUID, Job> jobs = new HashMap<>();
    private final Map<UUID, UUID> peers = new HashMap<>();
    private final Map<UUID, Long> revisions = new HashMap<>(), nextDecision = new HashMap<>();
    private final Map<RoomKey, Offer> offers = new HashMap<>();
    private MinecraftServer server;
    private Pending pending;
    private int cursor;
    public record Choice(String token, NpcActivityDefinition definition, NpcActivityPerception.Site site) { }
    private record RoomKey(UUID room, long revision, UUID player, ResourceLocation god) { }
    private record Offer(GodAvatarEntity actor, long revision, long definitions, long access, long order, long expires,
            UUID session, boolean readOnly, List<Choice> choices, String context) { }
    private record Pending(GodAvatarEntity actor, long revision, long definitions, long access, long order,
            long expires, List<Choice> choices, Set<String> audienceGods, Set<UUID> audiencePlayers,
            GodActivityPlanner.Request request, CompletableFuture<GodActivityPlanner.Decision> future) { }
    private static final class Job {
        final UUID runId = UUID.randomUUID();
        final GodAvatarEntity actor; final Choice choice; final long order, definitions, access;
        final List<GodActivityPlanner.Speech> speech; final Set<UUID> listeners;
        final NpcActivityMemory.View sourceExperience;
        boolean experienceStarted, dieRolled;
        String stage = "APPROACHING"; String detail = "Not started";
        long started, remaining, deadline, heldUntil, nextWaypoint; boolean committed; int speechIndex;
        BlockPos goal, visualPosition; NpcActivityPerception.Site seat;
        Job(GodAvatarEntity actor, Choice choice, long now, List<GodActivityPlanner.Speech> speech, Set<UUID> listeners,
                NpcActivityMemory.View sourceExperience) {
            this.actor = actor; this.choice = choice; this.order = actor.orderRevision();
            definitions = NpcActivityDefinitions.INSTANCE.generation(); access = world(actor).revision();
            this.speech = List.copyOf(speech); this.listeners = Set.copyOf(listeners);
            this.sourceExperience = sourceExperience;
            remaining = choice.definition().durationTicks(); deadline = now + 2400 + remaining; goal = choice.site().approach();
        }
    }
    public static boolean active(GodAvatarEntity actor) { return INSTANCE.jobs.containsKey(actor.getUUID()) || INSTANCE.peers.containsKey(actor.getUUID()); }
    public static void interrupt(GodAvatarEntity actor, String reason) {
        if (!(actor.level() instanceof ServerLevel)) return;
        INSTANCE.attach(actor.getServer());
        UUID parent = INSTANCE.peers.get(actor.getUUID());
        if (parent != null) { var owner = INSTANCE.actors.get(parent); if (owner != null) INSTANCE.stop(owner, "INTERRUPTED: " + reason, false); }
        INSTANCE.stop(actor, "INTERRUPTED: " + reason, reason.contains("UNLOADED") || reason.contains("SERVER_STOP"));
    }
    public void joined(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof GodAvatarEntity actor && event.getLevel() instanceof ServerLevel level) {
            attach(level.getServer()); actors.put(actor.getUUID(), actor); nextDecision.put(actor.getUUID(), now() + 100);
        }
    }
    public void left(EntityLeaveLevelEvent event) {
        if (event.getEntity() instanceof GodAvatarEntity actor && !event.getLevel().isClientSide()) {
            interrupt(actor, actor.isAlive() ? "UNLOADED" : "REMOVED"); actors.remove(actor.getUUID());
        }
    }
    private void attach(MinecraftServer value) {
        if (server == value) return;
        if (pending != null) pending.future().cancel(true);
        actors.clear(); jobs.clear(); peers.clear(); offers.clear(); revisions.clear(); nextDecision.clear(); pending = null; server = value;
    }
    public void stopping(ServerStoppingEvent event) {
        if (server != event.getServer()) return;
        for (var job : List.copyOf(jobs.values())) stop(job.actor, "SERVER_STOP", true);
        if (pending != null) pending.future().cancel(true);
    }
    public void stopped(ServerStoppedEvent event) {
        if (server != event.getServer()) return;
        attach(null);
    }
    public void tick(ServerTickEvent.Post event) {
        attach(event.getServer());
        offers.entrySet().removeIf(e -> now() >= e.getValue().expires());
        for (var job : List.copyOf(jobs.values())) tickJob(job);
        if (pending != null) {
            var p = pending;
            if (now() >= p.expires()) { p.future().cancel(true); pending = null; }
            else if (p.future().isDone()) {
                pending = null;
                try {
                    var decision = p.future().join();
                    if (eligible(p.actor()) && revision(p.actor()) == p.revision() && p.actor().orderRevision() == p.order()
                            && NpcActivityDefinitions.INSTANCE.generation() == p.definitions() && world(p.actor()).revision() == p.access()
                            && decision != null && p.request().requestId().equals(decision.requestId())
                            && p.audienceGods().equals(perceivedGods(p.actor())) && p.audiencePlayers().equals(nearbyListeners(p.actor()))
                            && world(p.actor()).activityMemory().current(p.request().experience(), p.audienceGods(), p.audiencePlayers())) {
                        var choice = p.choices().stream().filter(c -> c.token().equals(decision.choiceId())).findFirst().orElse(null);
                        boolean control = Set.of("NONE", "STOP", "CONTINUE").contains(decision.choiceId());
                        if (choice != null || control) {
                            if (decision.activityAffect() != null)
                                world(p.actor()).activityMemory().applyAffect(p.request().experience(), decision.activityAffect(), p.audienceGods(), p.audiencePlayers());
                            if (choice != null) start(p.actor(), choice, decision.speech(), p.request().experience());
                        }
                    }
                } catch (RuntimeException invalid) { /* Failure is idle, never pretend an action occurred. */ }
            }
            return;
        }
        if (server.getTickCount() % 20 != 0 || actors.isEmpty()) return;
        var list = List.copyOf(actors.values()); var actor = list.get(Math.floorMod(cursor++, list.size()));
        if (!eligible(actor) || now() < nextDecision.getOrDefault(actor.getUUID(), 0L)) return;
        var policy = NpcActivityDefinitions.INSTANCE.policy(actor.godId().orElseThrow()).orElseThrow();
        nextDecision.put(actor.getUUID(), now() + policy.decisionIntervalTicks());
        if (resume(actor)) return;
        if (!policy.autonomous() || !GodActivityPlanner.available()) return;
        var choices = choices(actor, policy);
        var audienceGods = perceivedGods(actor); var audiencePlayers = nearbyListeners(actor);
        var experience = world(actor).activityMemory().view(actor.godId().orElseThrow().toString(), audienceGods, audiencePlayers,
                choices.stream().map(c -> c.definition().kind().name()).distinct().collect(java.util.stream.Collectors.joining(" ")));
        if (choices.isEmpty() && experience.experiences().isEmpty()) return;
        var request = new GodActivityPlanner.Request(UUID.randomUUID(), actor.godId().orElseThrow().toString(), revision(actor),
                actor.level().getDayTime(), actor.level().isRaining(), clip(state(actor), 700), List.of(),
                choices.stream().map(c -> view(actor, c)).toList(), presentPeers(actor).stream().map(a -> new GodActivityPlanner.Peer(a.godId().orElseThrow().toString(), clip(state(a), 200))).toList(), experience);
        try {
            pending = new Pending(actor, revision(actor), NpcActivityDefinitions.INSTANCE.generation(), world(actor).revision(),
                    actor.orderRevision(), now() + 440, choices, audienceGods, audiencePlayers, request, GodActivityPlanner.choose(request));
        } catch (RuntimeException unavailable) { pending = null; }
    }
    private boolean eligible(GodAvatarEntity actor) {
        return actor.getServer() == server && actor.isAlive() && !actor.isRemoved() && actor.hasAuthoritativeBinding()
                && actor.godId().flatMap(NpcActivityDefinitions.INSTANCE::policy).isPresent() && !actor.busyForVisit()
                && !NpcSparring.INSTANCE.active(actor) && !NpcSparring.INSTANCE.awaiting(actor) && !active(actor) && world(actor).ready()
                && GodAvatarRegistryState.get(server).raidOwner(actor.godId().orElseThrow()).isEmpty();
    }
    private List<Choice> choices(GodAvatarEntity actor, NpcActivityDefinitions.Policy policy) {
        var result = new ArrayList<Choice>(); var alternatives = new ArrayList<Choice>();
        var sites = new ArrayList<>(NpcActivityPerception.scan(actor, policy.radius()));
        if (!sites.isEmpty()) Collections.rotate(sites, (int)(now() / 100 % sites.size()));
        var ids = new ArrayList<>(policy.activities()); Collections.rotate(ids, (int)(now() / 100 % ids.size()));
        for (var id : ids) {
            var def = NpcActivityDefinitions.INSTANCE.find(id).orElseThrow(); int found = 0;
            for (var site : sites) {
                if (Collections.disjoint(site.tags(), def.siteTags())) continue;
                if (occupied(actor, site.anchor()) || !NpcActivityPerception.current(actor, site)) continue;
                if (def.kind() == ActivityKind.TRAIN && def.mode() == NpcActivityDefinition.Mode.REAL && !site.tags().contains("player_company")) continue;
                if (def.kind() == ActivityKind.SOCIAL && !site.tags().contains("npc_company")) continue;
                if ((def.kind() == ActivityKind.FOLLOW || def.kind() == ActivityKind.CONVERSE || def.kind() == ActivityKind.LISTEN)
                        && site.target() == null) continue;
                if (def.kind() == ActivityKind.READ && def.mode() == NpcActivityDefinition.Mode.REAL
                        && !site.evidence().contains("Readable")) continue;
                var candidate = new Choice(UUID.randomUUID().toString(), def, site);
                if (found++ == 0) result.add(candidate); else alternatives.add(candidate);
                if (found >= 2 || result.size() >= MAX_CANDIDATES) break;
            }
            if (result.size() >= MAX_CANDIDATES) break;
        }
        for (var extra : alternatives) { if (result.size() >= MAX_CANDIDATES) break; result.add(extra); }
        return List.copyOf(result);
    }
    private GodActivityPlanner.Candidate view(GodAvatarEntity actor, Choice c) {
        var target = c.site().target() == null ? null : ((ServerLevel)actor.level()).getEntity(c.site().target());
        var peerIds = target instanceof GodAvatarEntity peer && presentPeers(actor).contains(peer)
                ? List.of(peer.godId().orElseThrow().toString()) : List.<String>of();
        return new GodActivityPlanner.Candidate(c.token(), c.definition().id().toString(), c.definition().kind().name(),
                c.definition().mode().name(), c.site().key(), clip(c.site().evidence(), 220) + "; guidance=" + clip(c.definition().parameters().getOrDefault("guidance", ""), 120),
                peerIds);
    }
    private List<GodAvatarEntity> presentPeers(GodAvatarEntity actor) {
        return actors.values().stream().filter(a -> a != actor && a.level() == actor.level() && a.isAlive() && a.hasAuthoritativeBinding()
                && !a.busyForVisit() && !active(a) && a.distanceToSqr(actor) <= 64 && actor.hasLineOfSight(a)).limit(3).toList();
    }
    private boolean start(GodAvatarEntity actor, Choice choice, List<GodActivityPlanner.Speech> speech) {
        return start(actor, choice, speech, null);
    }
    private boolean start(GodAvatarEntity actor, Choice choice, List<GodActivityPlanner.Speech> speech, NpcActivityMemory.View sourceExperience) {
        if (!eligible(actor) || occupied(actor, choice.site().anchor()) || !NpcActivityPerception.current(actor, choice.site()) || !validSpeech(actor, choice, speech)) return false;
        if (choice.definition().kind() == ActivityKind.TRAIN && choice.definition().mode() == NpcActivityDefinition.Mode.REAL) {
            var target = choice.site().target() == null ? null : ((ServerLevel)actor.level()).getEntity(choice.site().target());
            if (!(target instanceof ServerPlayer player)) return false;
            var invited = NpcSparring.INSTANCE.invite(player, actor);
            if (invited.success()) { bump(actor); world(actor).remember(actor.getUUID(), "Practice invitation only; awaiting player consent"); }
            return invited.success();
        }
        if (choice.definition().mode() == NpcActivityDefinition.Mode.REAL && choice.definition().kind().work()
                && actor.distanceToSqr(Vec3.atCenterOf(choice.site().anchor())) <= 25 && !NpcActivityWork.available(actor, choice.site().anchor(), choice.definition())) return false;
        var listeners = nearbyListeners(actor);
        if (choice.definition().kind() == ActivityKind.GUIDE && listeners.isEmpty()) return false;
        var job = new Job(actor, choice, now(), speech, listeners, sourceExperience);
        if (choice.definition().kind() == ActivityKind.WITHDRAW) {
            var other = choice.site().target() == null ? null : ((ServerLevel)actor.level()).getEntity(choice.site().target());
            if (other != null) {
                var direction = actor.position().subtract(other.position()).normalize().scale(5);
                var away = BlockPos.containing(actor.position().add(direction)); var goal = NpcActivityPerception.approach(actor, away);
                if (goal == null) return false; job.goal = goal;
            }
        }
        if (!navigate(actor, job.goal)) return false;
        jobs.put(actor.getUUID(), job); bump(actor);
        if (choice.definition().kind() == ActivityKind.SOCIAL) for (var peer : presentPeers(actor)) {
            peer.getNavigation().stop();
            if (NpcActivityBody.begin(peer, peer.blockPosition(), "TALK", ItemStack.EMPTY)) { peers.put(peer.getUUID(), actor.getUUID()); bump(peer); }
        }
        world(actor).suspended(actor.getUUID(), null);
        return true;
    }
    private boolean validSpeech(GodAvatarEntity actor, Choice choice, List<GodActivityPlanner.Speech> lines) {
        if (lines == null || lines.size() > 4) return false;
        var allowed = new HashSet<String>(); allowed.add(actor.godId().orElseThrow().toString());
        if (choice.definition().kind() == ActivityKind.SOCIAL) presentPeers(actor).forEach(p -> allowed.add(p.godId().orElseThrow().toString()));
        return lines.stream().allMatch(s -> allowed.contains(s.godId()) && s.text() != null && !s.text().isBlank()
                && s.text().length() <= 300 && s.text().chars().noneMatch(ch -> ch < 32 && ch != '\n'));
    }
    private void tickJob(Job j) {
        var a = j.actor;
        if (!a.isAlive() || a.isRemoved() || !a.hasAuthoritativeBinding() || a.orderRevision() != j.order || a.getTarget() != null
                || NpcSparring.INSTANCE.active(a) || NpcActivityDefinitions.INSTANCE.generation() != j.definitions
                || world(a).revision() != j.access || !NpcActivityPerception.current(a, j.choice.site())
                || !NpcActivityAccess.canUse(a, j.goal) || now() >= j.deadline) { stop(a, "INTERRUPTED_OR_TARGET_CHANGED", false); return; }
        if (now() < j.heldUntil) { j.deadline++; a.getNavigation().stop(); return; }
        for (var entry : List.copyOf(peers.entrySet())) if (entry.getValue().equals(a.getUUID())) {
            var peer = actors.get(entry.getKey());
            if (peer == null || peer.level() != a.level() || !peer.isAlive() || !peer.hasAuthoritativeBinding()
                    || peer.getTarget() != null || peer.distanceToSqr(a) > 64 || !peer.hasLineOfSight(a)
                    || !NpcActivityAccess.canUse(peer, peer.blockPosition())) { stop(a, "SOCIAL_PARTICIPANT_CHANGED", false); return; }
            peer.getNavigation().stop(); peer.getLookControl().setLookAt(a, 30, 30);
        }
        if (j.stage.equals("PAUSED_FOR_DIALOGUE")) { j.stage = "APPROACHING"; bump(a); if (!navigate(a, j.goal)) { stop(a, "NO_PATH", false); return; } }
        var def = j.choice.definition(); var target = j.choice.site().target() == null ? null : ((ServerLevel)a.level()).getEntity(j.choice.site().target());
        if (def.kind() == ActivityKind.GUIDE && !j.stage.equals("ACTIVE")) {
            boolean followed = j.listeners.stream().map(server.getPlayerList()::getPlayer).filter(Objects::nonNull)
                    .anyMatch(p -> p.level() == a.level() && p.distanceToSqr(a) <= 64 && a.hasLineOfSight(p));
            if (!followed) { a.getNavigation().stop(); j.detail = "Waiting for the originally nearby listeners to follow; no arrival"; return; }
        }
        if (target != null) {
            a.getLookControl().setLookAt(target, 30, 30);
            if (def.kind() == ActivityKind.FOLLOW) {
                if (a.distanceToSqr(target) > 9 && now() % 20 == 0) {
                    var approach = NpcActivityPerception.approach(a, target.blockPosition());
                    if (approach == null || !navigate(a, approach)) { stop(a, "NO_PATH", false); return; }
                    j.goal = approach;
                } else if (a.distanceToSqr(target) <= 9) a.getNavigation().stop();
                j.stage = "ACTIVE";
            }
        } else a.getLookControl().setLookAt(j.choice.site().anchor().getX() + .5, j.choice.site().anchor().getY() + 1, j.choice.site().anchor().getZ() + .5, 25, 25);
        if (j.stage.equals("APPROACHING") || j.stage.equals("MOVING_TO_SEAT")) {
            if (a.distanceToSqr(Vec3.atBottomCenterOf(j.goal)) > 2.25) {
                if (a.getNavigation().isDone() && !navigate(a, j.goal)) stop(a, "NO_PATH", false);
                return;
            }
            a.getNavigation().stop();
            if (def.kind() == ActivityKind.READ && j.stage.equals("APPROACHING")) { j.stage = "BROWSING"; j.started = now(); bump(a); return; }
            var visualPosition = def.kind() == ActivityKind.REST ? j.choice.site().anchor() : j.seat == null ? j.goal : j.seat.anchor();
            if (!NpcActivityBody.begin(a, visualPosition, def.kind().pose(), prop(def))) { stop(a, "POSE_OR_SEAT_UNAVAILABLE", false); return; }
            j.visualPosition = visualPosition;
            j.stage = "ACTIVE"; j.started = now(); j.detail = def.mode() == NpcActivityDefinition.Mode.DECORATIVE ? "Staged activity; no material output or consumption" : "Activity in progress; no completed output yet"; bump(a);
            if (!j.dieRolled && def.kind() == ActivityKind.PLAY && def.parameters().getOrDefault("game", "conversation").equals("dice")) {
                j.dieRolled = true;
                j.detail = "Actual die roll: " + (a.getRandom().nextInt(6) + 1) + "; no stake or reward";
                publishSpeech(j, a, "주사위: " + j.detail, nearbyListeners(a), "die");
            }
        }
        if (j.stage.equals("BROWSING")) {
            if (now() - j.started < 40) return;
            j.seat = NpcActivityPerception.scan(a, 6).stream().filter(s -> s.tags().contains("seat") && !occupied(a, s.anchor())).findFirst().orElse(null);
            if (j.seat != null) { var previous = j.goal; j.goal = j.seat.approach(); if (!navigate(a, j.goal)) { j.seat = null; j.goal = previous; } }
            j.stage = "MOVING_TO_SEAT"; bump(a); return;
        }
        if (!j.stage.equals("ACTIVE")) return;
        if (!j.experienceStarted) { j.experienceStarted = true; remember(j, "STARTED", "Physical activity began"); }
        if (j.visualPosition != null && !NpcActivityBody.canContinue(a, j.visualPosition)) { stop(a, "BODY_OR_SUPPORT_CHANGED", false); return; }
        if (j.seat != null && !NpcActivityPerception.current(a, j.seat)) { stop(a, "SEAT_REMOVED", false); return; }
        if (def.kind() == ActivityKind.TRAIN && now() % 20 == 0) a.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        if (def.kind() == ActivityKind.PERFORM) perform(j);
        if (!jobs.containsKey(a.getUUID())) return;
        if (def.kind() == ActivityKind.STROLL && now() >= j.nextWaypoint && j.remaining > 40) {
            j.nextWaypoint = now() + 60;
            var destination = NpcActivityPerception.scan(a, 8).stream()
                    .filter(s -> s.target() == null && !Collections.disjoint(s.tags(), def.siteTags())
                            && !s.approach().equals(j.goal) && !occupied(a, s.anchor())).findFirst().orElse(null);
            if (destination != null) {
                NpcActivityBody.end(a); j.visualPosition = null;
                if (navigate(a, destination.approach())) { j.goal = destination.approach(); j.stage = "APPROACHING"; j.remaining -= 20; return; }
                stop(a, "STROLL_PATH_BLOCKED", false); return;
            }
        }
        if (now() - j.started >= j.speechIndex * 60L && j.speechIndex < j.speech.size()) {
            var line = j.speech.get(j.speechIndex++); var speaker = actors.values().stream().filter(n -> n.godId().map(ResourceLocation::toString).filter(line.godId()::equals).isPresent()).findFirst().orElse(null);
            if (speaker != null && speaker.hasAuthoritativeBinding() && speaker.level() == a.level() && speaker.distanceToSqr(a) <= 64 && speaker.isAlive()
                    && a.hasLineOfSight(speaker) && (speaker == a || a.getUUID().equals(peers.get(speaker.getUUID())))) {
                publishSpeech(j, speaker, line.text(), j.listeners, "line-" + j.speechIndex);
            }
        }
        if (--j.remaining > 0) return;
        if (!j.committed && def.mode() == NpcActivityDefinition.Mode.REAL && def.kind().work()) {
            var outcome = NpcActivityWork.execute(a, j.choice.site().anchor(), def); j.committed = true;
            j.detail = outcome.detail(); stop(a, outcome.success() ? "COMPLETED: " + outcome.detail() : "FAILED: " + outcome.detail(), false);
        } else stop(a, "COMPLETED: " + j.detail, false);
    }
    private void perform(Job j) {
        var notes = j.choice.definition().parameters().getOrDefault("notes", "0,4,7,12").split(",");
        int interval;
        try { interval = Integer.parseInt(j.choice.definition().parameters().getOrDefault("note_interval_ticks", "12")); }
        catch (NumberFormatException invalid) { stop(j.actor, "INVALID_MUSIC", false); return; }
        if (interval < 4 || interval > 200 || notes.length > 64) { stop(j.actor, "INVALID_MUSIC", false); return; }
        long elapsed = now() - j.started;
        if (elapsed % interval != 0) return;
        try {
            int note = Integer.parseInt(notes[(int)(elapsed / interval % notes.length)]);
            if (note < 0 || note > 24) throw new IllegalArgumentException();
            j.actor.level().playSound(null, j.actor.blockPosition(), SoundEvents.NOTE_BLOCK_HARP.value(), SoundSource.NEUTRAL, .6F, (float)Math.pow(2, (note - 12) / 12.0));
            j.actor.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        } catch (IllegalArgumentException invalid) { stop(j.actor, "INVALID_MUSIC", false); }
    }
    private static ItemStack prop(NpcActivityDefinition def) {
        String id = def.parameters().get("prop");
        if (id == null) return switch (def.kind()) { case READ -> new ItemStack(Items.BOOK); case TRAIN -> new ItemStack(Items.STICK); default -> ItemStack.EMPTY; };
        var key = ResourceLocation.tryParse(id); return key != null && BuiltInRegistries.ITEM.containsKey(key) ? new ItemStack(BuiltInRegistries.ITEM.get(key)) : ItemStack.EMPTY;
    }
    private boolean occupied(GodAvatarEntity actor, BlockPos pos) {
        return jobs.values().stream().anyMatch(j -> j.actor != actor && j.actor.level() == actor.level()
                && (j.choice.site().anchor().equals(pos) || j.seat != null && j.seat.anchor().equals(pos)));
    }
    private boolean navigate(GodAvatarEntity actor, BlockPos goal) {
        if (!NpcActivityAccess.canUse(actor, goal)) return false;
        if (actor.distanceToSqr(Vec3.atBottomCenterOf(goal)) <= 2.25) return true;
        if (actor.currentDefinition().filter(d -> d.movement().enabled()).isEmpty()) return false;
        var path = actor.getNavigation().createPath(goal, 0);
        if (path == null || !path.canReach()) return false;
        for (int i = 0; i < path.getNodeCount(); i++) if (!NpcActivityAccess.canUse(actor, path.getNodePos(i))) return false;
        return actor.getNavigation().moveTo(path, speed(actor));
    }
    private double speed(GodAvatarEntity actor) { return actor.currentDefinition().map(d -> d.movement().navigationSpeed()).orElse(0D); }
    private void stop(GodAvatarEntity actor, String reason, boolean suspend) {
        var job = jobs.remove(actor.getUUID());
        if (pending != null && pending.actor() == actor) { pending.future().cancel(true); pending = null; }
        bump(actor);
        if (job == null) return;
        // A path failure is an interrupted attempt, not an invented completed activity.
        remember(job, reason.startsWith("COMPLETED:") ? "COMPLETED" : reason.startsWith("FAILED:") ? "FAILED" : "INTERRUPTED", reason);
        actor.getNavigation().stop(); NpcActivityBody.end(actor);
        for (var entry : List.copyOf(peers.entrySet())) if (entry.getValue().equals(actor.getUUID())) {
            peers.remove(entry.getKey()); var peer = actors.get(entry.getKey());
            if (peer != null) { NpcActivityBody.end(peer); bump(peer); nextDecision.put(peer.getUUID(), now() + 100); }
        }
        if (world(actor).ready()) {
            world(actor).remember(actor.getUUID(), job.choice.definition().id() + " " + reason);
            if (suspend && !job.committed) {
                var t = new CompoundTag(); t.putString("definition", job.choice.definition().id().toString());
                t.putString("dimension", actor.level().dimension().location().toString()); t.putLong("anchor", job.choice.site().anchor().asLong());
                t.putInt("remaining", (int)Math.max(20, job.remaining)); world(actor).suspended(actor.getUUID(), t);
            } else world(actor).suspended(actor.getUUID(), null);
        }
        nextDecision.put(actor.getUUID(), now() + 100);
    }
    private boolean resume(GodAvatarEntity actor) {
        var saved = world(actor).suspended(actor.getUUID()); if (saved.isEmpty()) return false;
        world(actor).suspended(actor.getUUID(), null); var t = saved.get();
        if (!actor.level().dimension().location().toString().equals(t.getString("dimension"))) return false;
        var policy = NpcActivityDefinitions.INSTANCE.policy(actor.godId().orElseThrow()).orElseThrow();
        var savedId = ResourceLocation.tryParse(t.getString("definition"));
        if (savedId == null || !policy.activities().contains(savedId)) return false;
        var choice = choices(actor, new NpcActivityDefinitions.Policy(List.of(savedId), false, policy.radius(), policy.decisionIntervalTicks())).stream().filter(c -> c.definition().id().toString().equals(t.getString("definition"))
                && c.site().anchor().asLong() == t.getLong("anchor")).findFirst().orElse(null);
        if (choice == null || !start(actor, choice, List.of())) return false;
        jobs.get(actor.getUUID()).remaining = Math.min(choice.definition().durationTicks(), Math.max(20, t.getInt("remaining"))); return true;
    }
    public String contextFor(ServerPlayer player, ResourceLocation god, ConversationRoomSnapshot room, boolean readOnly) {
        attach(player.server); var actor = GodAvatarService.INSTANCE.findLoaded(server, god).orElse(null);
        if (actor == null || actor.level() != player.level() || actor.distanceToSqr(player) > 256 || !player.hasLineOfSight(actor))
            return "[NPC_ACTIVITY_CONTEXT] Physical activity is not visible here; do not invent posture, objects or actions.";
        var policy = NpcActivityDefinitions.INSTANCE.policy(god).orElse(null);
        if (policy == null) return "[NPC_ACTIVITY_CONTEXT] No authored activity repertoire; no autonomous activity capability.";
        var key = new RoomKey(room.roomId(), room.revision(), player.getUUID(), god);
        var old = offers.get(key); if (old != null && old.readOnly() == readOnly && offerCurrent(old)) return old.context();
        if (!readOnly) {
            if (pending != null && pending.actor() == actor) { pending.future().cancel(true); pending = null; }
            var job = jobs.get(actor.getUUID());
            if (job != null) { job.heldUntil = now() + 4000; if (!job.stage.equals("PAUSED_FOR_DIALOGUE")) { job.stage = "PAUSED_FOR_DIALOGUE"; actor.getNavigation().stop(); NpcActivityBody.end(actor); bump(actor); } }
            nextDecision.put(actor.getUUID(), now() + 4000);
        }
        var selections = readOnly ? List.<Choice>of() : choices(actor, policy).stream().limit(8).toList();
        // Legacy strings have no audience or source receipts. Never promote them into model memory.
        String context = "[NPC_ACTIVITY_CONTEXT]\n" + JSON.toJson(Map.of("state", clip(state(actor), 1000), "recent", List.of(),
                "available_choices", selections.stream().map(c -> view(actor, c)).toList(), "revision", revision(actor), "read_only", readOnly,
                "rules", "Player objections are social requests: decide by persona, relationship and power; you may stop or refuse. Admin vetoes always win. "
                        + "Propose npc_activity_request with choice_id=STOP/CONTINUE/exact offered token. Never say a proposed action has already succeeded. "
                        + "Decorative props are not inventory or readable book evidence. No arbitrary book facts. Other private conversations are not supplied."));
        var scope = com.sande.mythictrpg.ai.server.ConversationRooms.INSTANCE.actionScope(player, room.roomId(), room.revision(), god);
        offers.put(key, new Offer(actor, revision(actor), NpcActivityDefinitions.INSTANCE.generation(), world(actor).revision(), actor.orderRevision(),
                now() + 4000, scope.map(s -> s.sessionId()).orElse(null), readOnly, selections, context));
        return context;
    }
    public boolean contextCurrent(ServerPlayer player, ResourceLocation god, ConversationRoomSnapshot room, String expected) {
        var offer = offers.get(new RoomKey(room.roomId(), room.revision(), player.getUUID(), god));
        return offer == null ? expected.equals(contextFor(player, god, room, true)) : offer.context().equals(expected) && offerCurrent(offer)
                && offer.actor().level() == player.level() && offer.actor().distanceToSqr(player) <= 256 && player.hasLineOfSight(offer.actor());
    }
    public void endDialogue(UUID room) {
        for (var entry : List.copyOf(offers.entrySet())) if (entry.getKey().room().equals(room)) {
            var actor = entry.getValue().actor(); offers.remove(entry.getKey());
            if (offers.values().stream().noneMatch(o -> o.actor() == actor)) {
                var job = jobs.get(actor.getUUID()); if (job != null) job.heldUntil = 0;
                nextDecision.put(actor.getUUID(), now() + 100);
            }
        }
    }
    public boolean choose(ServerPlayer player, ResourceLocation god, UUID room, long revision, String token) {
        var offer = offers.get(new RoomKey(room, revision, player.getUUID(), god));
        if (offer == null || offer.readOnly() || !offerCurrent(offer) || offer.actor().level() != player.level()
                || offer.actor().distanceToSqr(player) > 256 || !player.hasLineOfSight(offer.actor())) return false;
        var actor = offer.actor();
        if (token.equals("STOP")) { interrupt(actor, "STOPPED_BY_NPC_DECISION"); return true; }
        if (token.equals("CONTINUE")) { var job = jobs.get(actor.getUUID()); if (job != null) job.heldUntil = 0; bump(actor); return true; }
        var selected = offer.choices().stream().filter(c -> c.token().equals(token)).findFirst().orElse(null);
        if (selected == null || !NpcActivityPerception.current(actor, selected.site())) return false;
        interrupt(actor, "CHANGED_BY_NPC_DECISION");
        return start(actor, selected, List.of());
    }
    public boolean canChooseScope(ServerPlayer player, ResourceLocation god, UUID session, String token) {
        return scopedOffer(player, god, session).filter(e -> token != null && (token.equals("STOP") || token.equals("CONTINUE")
                || e.getValue().choices().stream().anyMatch(c -> c.token().equals(token)))).isPresent();
    }
    public boolean chooseScope(ServerPlayer player, ResourceLocation god, UUID session, String token) {
        var entry = scopedOffer(player, god, session).orElse(null);
        return entry != null && choose(player, god, entry.getKey().room(), entry.getKey().revision(), token);
    }
    private Optional<Map.Entry<RoomKey, Offer>> scopedOffer(ServerPlayer player, ResourceLocation god, UUID session) {
        if (server != player.server || !server.isSameThread() || !com.sande.mythictrpg.ai.server.ConversationRooms.INSTANCE.actionCurrent(player, session, god)) return Optional.empty();
        return offers.entrySet().stream().filter(e -> e.getKey().player().equals(player.getUUID()) && e.getKey().god().equals(god)
                && session.equals(e.getValue().session()) && !e.getValue().readOnly() && offerCurrent(e.getValue())
                && e.getValue().actor().level() == player.level() && e.getValue().actor().distanceToSqr(player) <= 256 && player.hasLineOfSight(e.getValue().actor())).findFirst();
    }
    private boolean offerCurrent(Offer o) { return server != null && o.actor().getServer() == server && o.actor().isAlive()
            && !o.actor().isRemoved() && o.actor().hasAuthoritativeBinding() && now() < o.expires() && revision(o.actor()) == o.revision()
            && o.actor().orderRevision() == o.order()
            && NpcActivityDefinitions.INSTANCE.generation() == o.definitions() && world(o.actor()).revision() == o.access(); }
    public boolean request(GodAvatarEntity actor, ResourceLocation definitionId) {
        attach(actor.getServer()); var policy = actor.godId().flatMap(NpcActivityDefinitions.INSTANCE::policy).orElse(null);
        if (policy == null || !policy.activities().contains(definitionId)) return false;
        var c = choices(actor, new NpcActivityDefinitions.Policy(List.of(definitionId), false, policy.radius(), policy.decisionIntervalTicks()))
                .stream().filter(v -> v.definition().id().equals(definitionId)).findFirst().orElse(null);
        return c != null && start(actor, c, List.of());
    }
    public String state(GodAvatarEntity actor) {
        if (NpcSparring.INSTANCE.active(actor)) return "CONSENSUAL_PRACTICE; virtual hit-count exercise; no real health, equipment or reward changes";
        if (NpcSparring.INSTANCE.awaiting(actor)) return "PRACTICE_INVITED; waiting for player consent; practice has not started";
        if (peers.containsKey(actor.getUUID())) return "SOCIAL_PARTICIPANT; nearby public NPC conversation; no private-room information";
        var job = jobs.get(actor.getUUID());
        return job == null ? "IDLE; no physical activity executing" : job.choice.definition().kind() + " mode=" + job.choice.definition().mode()
                + " stage=" + job.stage + "; " + job.detail + "; perceived=" + job.choice.site().evidence();
    }
    private Set<UUID> nearbyListeners(GodAvatarEntity actor) {
        return server.getPlayerList().getPlayers().stream().filter(p -> p.level() == actor.level() && p.distanceToSqr(actor) <= 256 && p.hasLineOfSight(actor))
                .map(ServerPlayer::getUUID).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
    private Set<String> perceivedGods(GodAvatarEntity actor) {
        var gods = new LinkedHashSet<String>(); gods.add(actor.godId().orElseThrow().toString());
        presentPeers(actor).forEach(peer -> gods.add(peer.godId().orElseThrow().toString()));
        return Set.copyOf(gods);
    }
    private List<GodAvatarEntity> hearingGods(Job job, GodAvatarEntity speaker) {
        return actors.values().stream().filter(peer -> (peer == job.actor || peer == speaker || job.actor.getUUID().equals(peers.get(peer.getUUID())))
                && peer.isAlive() && peer.hasAuthoritativeBinding() && peer.level() == speaker.level()
                && peer.distanceToSqr(speaker) <= 64 && (peer == speaker || peer.hasLineOfSight(speaker))).toList();
    }
    private void publishSpeech(Job job, GodAvatarEntity speaker, String text, Set<UUID> intended, String lineId) {
        var heard = hearingGods(job, speaker);
        var gods = heard.stream().map(god -> god.godId().orElseThrow().toString()).collect(java.util.stream.Collectors.toUnmodifiableSet());
        var recipients = new LinkedHashSet<UUID>();
        for (UUID id : intended) { var player = server.getPlayerList().getPlayer(id);
            if (player != null && player.level() == speaker.level() && player.distanceToSqr(speaker) <= 256 && player.hasLineOfSight(speaker)) recipients.add(id);
        }
        // Delayed lines may not disclose an evicted or newly unauthorized source, even after a valid choice.
        if (job.sourceExperience != null && !world(job.actor).activityMemory().disclosureCurrent(job.sourceExperience, gods, recipients)) return;
        var delivered = say(speaker, speaker.godId().orElseThrow(), text, recipients);
        for (var listener : heard) {
            var god = listener.godId().orElseThrow().toString();
            world(listener).activityMemory().record(new NpcActivityMemory.Event(eventId(job.runId, lineId + ":" + god), job.runId,
                    god, listener.getUUID(), now(), job.choice.definition().id().toString(), job.choice.definition().kind().name(),
                    job.choice.definition().mode().name(), "SPEECH", "Actual local speech; not proof its claims are true",
                    speaker.godId().orElseThrow().toString(), text, gods, delivered));
        }
    }
    private Set<UUID> say(GodAvatarEntity actor, ResourceLocation speaker, String text, Set<UUID> intended) {
        var delivered = new LinkedHashSet<UUID>();
        for (UUID id : intended) { var player = server.getPlayerList().getPlayer(id);
            if (player == null || player.level() != actor.level() || player.distanceToSqr(actor) > 256 || !player.hasLineOfSight(actor)) continue;
            player.sendSystemMessage(Component.literal("[주변 대화] ").append(GodIdentityService.INSTANCE.getDisplayName(server, id, speaker))
                    .append(Component.literal(": " + text)));
            delivered.add(id);
        }
        return Set.copyOf(delivered);
    }
    private void remember(Job job, String phase, String detail) {
        var actor = job.actor; var def = job.choice.definition(); var god = actor.godId().orElseThrow().toString();
        world(actor).activityMemory().record(new NpcActivityMemory.Event(eventId(job.runId, phase), job.runId, god, actor.getUUID(), now(),
                def.id().toString(), def.kind().name(), def.mode().name(), phase, clip(detail, 380), "", "", Set.of(god), Set.of()));
    }
    static UUID eventId(UUID run, String phase) {
        return UUID.nameUUIDFromBytes((run + ":" + phase).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private long revision(GodAvatarEntity actor) { return revisions.getOrDefault(actor.getUUID(), 0L); }
    private static String clip(String text, int length) { return text.length() <= length ? text : text.substring(0, length) + " [truncated]"; }
    private void bump(GodAvatarEntity actor) { revisions.merge(actor.getUUID(), 1L, Long::sum); }
    private long now() { return server == null ? 0 : server.overworld().getGameTime(); }
    private static NpcActivityWorldState world(GodAvatarEntity actor) { return NpcActivityWorldState.get(actor.getServer()); }
    private NpcActivityRuntime() { }
}
