package com.sande.mythictrpg.godavatar.directive;

import com.google.gson.Gson;
import com.sande.mythictrpg.ai.action.*;
import com.sande.mythictrpg.ai.server.ConversationRooms;
import com.sande.mythictrpg.data.god.GodDefinitionManager;
import com.sande.mythictrpg.godavatar.*;
import com.sande.mythictrpg.godavatar.activity.NpcActivityAccess;
import com.sande.mythictrpg.raid.RaidRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/** Server-issued physical choices. No arbitrary commands, spawning, player damage or world edits. */
public final class NpcDirectiveRuntime {
    public static final NpcDirectiveRuntime INSTANCE = new NpcDirectiveRuntime();
    // Context/lease bounds, not new damage, speed or reward balance. Physical ranges use the avatar definition.
    private static final int MAX_OFFERS = 1024, MAX_CHOICES = 16, MAX_HOSTILES = 4;
    private static final long OFFER_LIFETIME_TICKS = 4000;
    private static final Gson JSON = new Gson();
    private MinecraftServer server;
    private final Map<UUID, Offer> offers = new LinkedHashMap<>();
    private final Map<UUID, Running> running = new HashMap<>();
    public enum Kind { APPROACH, FOLLOW, STOP, WITHDRAW, ATTACK_HOSTILE, PROTECT_PARTICIPANT }
    private record Scope(UUID player, ResourceLocation god, UUID room, long revision, UUID session) { }
    private record Choice(UUID id, Kind kind, UUID target, BlockPos destination) { }
    private record Offer(Scope scope, GodAvatarEntity actor, long order, long godGeneration,
                         long avatarGeneration, long expires, Choice choice) { }
    private record Running(Scope scope, GodAvatarEntity actor, long order, long godGeneration,
                           long avatarGeneration, Choice choice, Vec3 origin) { }
    private NpcDirectiveRuntime() { }

    /** Capture only from an exact action-authorized room; model IDs never supply authority. */
    public String capture(ServerPlayer player, UUID roomId, long revision, ResourceLocation godId, UUID actionSessionId) {
        attach(player.server);
        Scope scope = new Scope(player.getUUID(), godId, roomId, revision, actionSessionId);
        prune();
        if (!scopeCurrent(scope)) return unavailable("NO_GAME_ACTION_SCOPE");
        var actor = GodAvatarService.INSTANCE.findLoaded(server, godId).orElse(null);
        if (actor == null || !observable(player, actor) || !available(actor, scope))
            return unavailable("NO_AVAILABLE_VISIBLE_PHYSICAL_AVATAR");
        offers.entrySet().removeIf(entry -> entry.getValue().scope().equals(scope));
        List<Choice> choices = new ArrayList<>();
        var current = running.get(actor.getUUID());
        if (current != null) choices.add(choice(Kind.STOP, null, null));
        var definition = actor.currentDefinition().orElseThrow();
        List<ServerPlayer> participants = ConversationRooms.INSTANCE.actionPlayers(player, actionSessionId, godId).stream()
                .map(server.getPlayerList()::getPlayer).filter(Objects::nonNull)
                .filter(target -> eligibleParticipant(actor, target, definition))
                .sorted(Comparator.<ServerPlayer>comparingInt(target -> target == player ? 0 : 1)
                        .thenComparingDouble(actor::distanceToSqr)).limit(3).toList();
        for (var target : participants) {
            if (definition.movement().enabled() && reachable(actor, target.blockPosition())) {
                choices.add(choice(Kind.APPROACH, target.getUUID(), null));
                if (definition.movement().visit()) choices.add(choice(Kind.FOLLOW, target.getUUID(), null));
            }
            if (definition.combat().enabled()) choices.add(choice(Kind.PROTECT_PARTICIPANT, target.getUUID(), null));
        }
        if (definition.movement().enabled()) {
            BlockPos away = withdrawal(actor, player, definition);
            if (away != null) choices.add(choice(Kind.WITHDRAW, player.getUUID(), away));
        }
        if (definition.combat().enabled()) hostiles(actor, definition).stream().limit(MAX_HOSTILES)
                .forEach(target -> choices.add(choice(Kind.ATTACK_HOSTILE, target.getUUID(), null)));
        List<Map<String, String>> views = new ArrayList<>();
        for (Choice selected : choices.stream().limit(MAX_CHOICES).toList()) {
            offers.put(selected.id(), new Offer(scope, actor, actor.orderRevision(), GodDefinitionManager.INSTANCE.generation(),
                    GodAvatarDefinitionManager.INSTANCE.generation(), now() + OFFER_LIFETIME_TICKS, selected));
            var view = new LinkedHashMap<String, String>();
            view.put("choice_id", selected.id().toString()); view.put("directive", selected.kind().name());
            if (selected.target() != null) {
                var target = ((ServerLevel) actor.level()).getEntity(selected.target());
                view.put("target_id", selected.target().toString());
                if (target != null) view.put("target_type", BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString());
            }
            views.add(view);
        }
        while (offers.size() > MAX_OFFERS) offers.remove(offers.keySet().iterator().next());
        return "[NPC_DIRECTIVE_CONTEXT]\n" + JSON.toJson(Map.of("physical_state", current == null ? "IDLE" : current.choice().kind().name(),
                "available_choices", views,
                "rules", "Choose npc_directive with exactly one offered choice_id when you decide to act. "
                        + "APPROACH/FOLLOW/WITHDRAW start walking, never teleport. ATTACK_HOSTILE begins actual melee combat, not a guaranteed kill. "
                        + "PROTECT_PARTICIPANT follows only a present participant and attacks only nearby hostile mobs currently targeting them. "
                        + "STOP ends this physical directive. No player attack, raid takeover or invented target. No success claim before game result."));
    }

    public boolean canChoose(AiActionContext context, AiActionProposal proposal) { return find(context, proposal) != null; }

    public AiActionExecution execute(AiActionContext context, AiActionProposal proposal) {
        Offer offer = find(context, proposal);
        if (offer == null) return AiActionExecution.rejected("Physical directive is no longer available");
        var actor = offer.actor(); var selected = offer.choice();
        // Consume all siblings before executing. New proposal UUIDs cannot replay this same snapshot.
        offers.entrySet().removeIf(entry -> entry.getValue().actor() == actor);
        if (selected.kind() == Kind.STOP) {
            cancel(actor);
            return outcome(selected, "STOPPED", true);
        }
        long order = GodAvatarService.INSTANCE.beginConversationDirective(actor, offer.order());
        if (order < 0) return AiActionExecution.rejected("A newer physical order owns this avatar");
        var active = new Running(offer.scope(), actor, order, offer.godGeneration(), offer.avatarGeneration(), selected, actor.position());
        running.put(actor.getUUID(), active);
        if (!advance(active)) {
            cancel(actor);
            return AiActionExecution.rejected("Physical target/path changed before execution; no completed action");
        }
        return outcome(selected, "STARTED", false);
    }

    private AiActionExecution outcome(Choice selected, String status, boolean completed) {
        var details = new LinkedHashMap<String, String>();
        details.put("status", status); details.put("directive", selected.kind().name());
        details.put("work_completed", Boolean.toString(completed));
        if (selected.target() != null) details.put(selected.kind() == Kind.ATTACK_HOSTILE ? "target_entity_id" : "target_player_id", selected.target().toString());
        return new AiActionExecution(true, completed ? "Physical directive stopped." : "Physical directive started; arrival, kill or successful protection is not yet established.", details);
    }

    private Offer find(AiActionContext context, AiActionProposal proposal) {
        if (server != context.server() || !server.isSameThread()
                || !context.targetPlayer().getUUID().equals(proposal.targetPlayerId())
                || context.roomScope().filter(scope -> scope.sessionId().equals(proposal.sessionId())
                        && scope.actingGodId().equals(proposal.actingGodId())).isEmpty()) return null;
        UUID token;
        try { token = UUID.fromString(proposal.parameters().get("choice_id")); }
        catch (RuntimeException invalid) { return null; }
        var offer = offers.get(token);
        if (offer == null || !offer.scope().player().equals(proposal.targetPlayerId())
                || !offer.scope().god().equals(proposal.actingGodId()) || !offer.scope().session().equals(proposal.sessionId())
                || now() >= offer.expires() || !scopeCurrent(offer.scope()) || !available(offer.actor(), offer.scope())
                || offer.actor().orderRevision() != offer.order() || !generations(offer.godGeneration(), offer.avatarGeneration())
                || !observable(context.targetPlayer(), offer.actor()) || !choiceCurrent(offer)) return null;
        return offer;
    }

    private boolean choiceCurrent(Offer offer) {
        var actor = offer.actor(); var definition = actor.currentDefinition().orElseThrow(); var choice = offer.choice();
        if (choice.kind() == Kind.STOP) return running.containsKey(actor.getUUID());
        if (choice.kind() == Kind.ATTACK_HOSTILE) return hostile(actor, entity(actor, choice.target()), definition);
        var player = server.getPlayerList().getPlayer(choice.target());
        if (!participant(offer.scope(), player) || !eligibleParticipant(actor, player, definition)) return false;
        return switch (choice.kind()) {
            case APPROACH -> definition.movement().enabled() && reachable(actor, player.blockPosition());
            case FOLLOW -> definition.movement().enabled() && definition.movement().visit() && reachable(actor, player.blockPosition());
            case WITHDRAW -> definition.movement().enabled() && choice.destination() != null
                    && actor.distanceToSqr(Vec3.atBottomCenterOf(choice.destination())) <= squared(definition.movement().maxCommandDistance())
                    && reachable(actor, choice.destination());
            case PROTECT_PARTICIPANT -> definition.combat().enabled();
            default -> false;
        };
    }

    /** Runs before entity AI each tick so a stale target cannot receive one final melee hit. */
    public void tick(GodAvatarEntity actor) {
        Running active = running.get(actor.getUUID());
        if (active == null) return;
        if (actor.getServer() != server || !server.isSameThread()) return;
        if (!valid(active) || !navigationAllowed(actor)) { cancel(actor); return; }
        if (actor.tickCount % 10 == 0 && !advance(active)) cancel(actor);
    }

    public boolean active(GodAvatarEntity actor) {
        var value = running.get(actor.getUUID());
        return value != null && value.actor() == actor;
    }

    /** External orders invalidate our ownership before they mutate navigation or a combat target. */
    public void cancel(GodAvatarEntity actor) {
        var value = running.remove(actor.getUUID());
        if (value != null && value.actor() == actor && actor.getServer() == server && server.isSameThread())
            GodAvatarService.INSTANCE.stopConversationDirective(actor, value.order());
    }

    public boolean combatAllowed(GodAvatarEntity actor, LivingEntity target) {
        var active = running.get(actor.getUUID());
        if (active == null) return true;
        if (!valid(active) || target == null) return false;
        var definition = actor.currentDefinition().orElse(null);
        if (definition == null || !hostile(actor, target, definition)) return false;
        return active.choice().kind() == Kind.ATTACK_HOSTILE ? target.getUUID().equals(active.choice().target())
                : active.choice().kind() == Kind.PROTECT_PARTICIPANT && target instanceof Mob mob
                        && mob.getTarget() != null && mob.getTarget().getUUID().equals(active.choice().target());
    }

    private boolean valid(Running active) {
        var actor = active.actor(); var definition = actor.currentDefinition().orElse(null);
        if (definition == null || !actor.isAlive() || actor.isRemoved() || !actor.hasAuthoritativeBinding()
                || actor.orderRevision() != active.order() || !scopeCurrent(active.scope())
                || !generations(active.godGeneration(), active.avatarGeneration())
                || GodAvatarRegistryState.get(server).raidOwner(active.scope().god()).isPresent()
                || !NpcActivityAccess.canUse(actor, actor.blockPosition())) return false;
        var choice = active.choice();
        if (choice.kind() == Kind.ATTACK_HOSTILE) {
            var target = entity(actor, choice.target());
            return hostile(actor, target, definition) && actor.position().distanceToSqr(active.origin()) <= squared(definition.stats().followRange())
                    && target.position().distanceToSqr(active.origin()) <= squared(definition.stats().followRange());
        }
        var player = server.getPlayerList().getPlayer(choice.target());
        if (!participant(active.scope(), player) || !eligibleParticipant(actor, player, definition)) return false;
        return choice.kind() == Kind.PROTECT_PARTICIPANT ? definition.combat().enabled()
                : definition.movement().enabled() && (choice.kind() != Kind.FOLLOW || definition.movement().visit());
    }

    private boolean advance(Running active) {
        if (!valid(active)) return false;
        var actor = active.actor(); var choice = active.choice(); var definition = actor.currentDefinition().orElseThrow();
        if (choice.kind() == Kind.ATTACK_HOSTILE) {
            actor.setTarget(entity(actor, choice.target()));
            return true;
        }
        var player = server.getPlayerList().getPlayer(choice.target());
        if (choice.kind() == Kind.PROTECT_PARTICIPANT) {
            var attacker = hostiles(actor, definition).stream().filter(mob -> mob.getTarget() == player).findFirst().orElse(null);
            actor.setTarget(attacker);
            if (attacker != null) return true;
            if (!definition.movement().enabled() || actor.distanceToSqr(player) <= squared(definition.interactionRange())) {
                actor.getNavigation().stop(); return true;
            }
            return navigate(actor, player.blockPosition(), definition);
        }
        actor.setTarget(null);
        BlockPos target = choice.kind() == Kind.WITHDRAW ? choice.destination() : player.blockPosition();
        double distance = actor.distanceToSqr(Vec3.atBottomCenterOf(target));
        double stopDistance = choice.kind() == Kind.WITHDRAW ? 2.25 : squared(definition.interactionRange());
        if (distance <= stopDistance) {
            actor.getNavigation().stop();
            if (choice.kind() != Kind.FOLLOW) cancel(actor);
            return true;
        }
        return navigate(actor, target, definition);
    }

    private boolean available(GodAvatarEntity actor, Scope scope) {
        if (!actor.isAlive() || actor.isRemoved() || !actor.hasAuthoritativeBinding() || actor.currentDefinition().isEmpty()
                || GodAvatarRegistryState.get(server).raidOwner(scope.god()).isPresent()) return false;
        var own = running.get(actor.getUUID());
        return own == null ? !actor.busyForVisit() : own.scope().equals(scope) && valid(own);
    }
    private boolean scopeCurrent(Scope scope) {
        var player = server.getPlayerList().getPlayer(scope.player());
        return player != null && player.isAlive() && !player.isSpectator()
                && ConversationRooms.INSTANCE.actionScope(player, scope.room(), scope.revision(), scope.god())
                        .filter(value -> value.sessionId().equals(scope.session())).isPresent();
    }
    private boolean participant(Scope scope, ServerPlayer target) {
        var owner = server.getPlayerList().getPlayer(scope.player());
        return owner != null && target != null && ConversationRooms.INSTANCE.actionPlayers(owner, scope.session(), scope.god()).contains(target.getUUID());
    }
    private boolean eligibleParticipant(GodAvatarEntity actor, ServerPlayer player, GodAvatarDefinition definition) {
        return player != null && player.isAlive() && !player.isSpectator() && player.level() == actor.level()
                && actor.distanceToSqr(player) <= squared(definition.stats().followRange())
                && actor.distanceToSqr(player) <= squared(definition.movement().maxVisitDistance())
                && actor.hasLineOfSight(player) && NpcActivityAccess.canUse(actor, player.blockPosition());
    }
    private boolean observable(ServerPlayer player, GodAvatarEntity actor) {
        var definition = actor.currentDefinition().orElse(null);
        return definition != null && player.level() == actor.level() && player.hasLineOfSight(actor)
                && player.distanceToSqr(actor) <= squared(definition.stats().followRange());
    }
    private List<Mob> hostiles(GodAvatarEntity actor, GodAvatarDefinition definition) {
        return actor.level().getEntitiesOfClass(Mob.class, actor.getBoundingBox().inflate(definition.stats().followRange()),
                        mob -> hostile(actor, mob, definition)).stream().sorted(Comparator.comparingDouble(actor::distanceToSqr)).toList();
    }
    private boolean hostile(GodAvatarEntity actor, LivingEntity target, GodAvatarDefinition definition) {
        return definition.combat().enabled() && target instanceof Mob && target instanceof Enemy
                && target.isAlive() && !target.isRemoved() && target.level() == actor.level()
                && !target.isInvulnerable() && !actor.isAlliedTo(target) && !RaidRuntime.controlsEntity(target)
                && actor.distanceToSqr(target) <= squared(definition.stats().followRange()) && actor.hasLineOfSight(target)
                && NpcActivityAccess.canUse(actor, target.blockPosition());
    }
    private LivingEntity entity(GodAvatarEntity actor, UUID id) {
        var target = ((ServerLevel) actor.level()).getEntity(id);
        return target instanceof LivingEntity living ? living : null;
    }
    private boolean generations(long god, long avatar) {
        return god == GodDefinitionManager.INSTANCE.generation() && avatar == GodAvatarDefinitionManager.INSTANCE.generation();
    }
    private BlockPos withdrawal(GodAvatarEntity actor, ServerPlayer player, GodAvatarDefinition definition) {
        Vec3 direction = actor.position().subtract(player.position()).multiply(1, 0, 1);
        if (direction.lengthSqr() < .01) direction = actor.getLookAngle().multiply(-1, 0, -1);
        direction = direction.normalize();
        double maximum = Math.min(definition.movement().maxCommandDistance(), definition.stats().followRange());
        for (double distance = maximum; distance >= definition.interactionRange(); distance -= 2) {
            var candidate = BlockPos.containing(actor.position().add(direction.scale(distance)));
            if (reachable(actor, candidate)) return candidate;
        }
        return null;
    }
    private boolean reachable(GodAvatarEntity actor, BlockPos target) { return permittedPath(actor, target) != null; }
    private Path permittedPath(GodAvatarEntity actor, BlockPos target) {
        if (!NpcActivityAccess.canUse(actor, target)) return null;
        var path = actor.getNavigation().createPath(target, 0);
        if (path == null || !path.canReach()) return null;
        for (int index = 0; index < path.getNodeCount(); index++)
            if (!NpcActivityAccess.canUse(actor, path.getNodePos(index))) return null;
        return path;
    }
    private boolean navigationAllowed(GodAvatarEntity actor) {
        var path = actor.getNavigation().getPath();
        if (path == null) return true;
        for (int index = path.getNextNodeIndex(); index < path.getNodeCount(); index++)
            if (!NpcActivityAccess.canUse(actor, path.getNodePos(index))) return false;
        return true;
    }
    private boolean navigate(GodAvatarEntity actor, BlockPos target, GodAvatarDefinition definition) {
        var path = permittedPath(actor, target);
        return path != null && actor.getNavigation().moveTo(path, definition.movement().navigationSpeed());
    }
    private void attach(MinecraftServer requested) {
        if (!requested.isSameThread()) throw new IllegalStateException("NPC directives require the game thread");
        if (server != requested) { offers.clear(); running.clear(); server = requested; }
    }
    private void prune() { offers.entrySet().removeIf(entry -> now() >= entry.getValue().expires()); }
    private long now() { return server.overworld().getGameTime(); }
    private static Choice choice(Kind kind, UUID target, BlockPos destination) { return new Choice(UUID.randomUUID(), kind, target, destination); }
    private static String unavailable(String reason) { return "[NPC_DIRECTIVE_CONTEXT] " + reason + "; no executable physical choices. Do not claim physical action."; }
    private static double squared(double value) { return value * value; }
}
