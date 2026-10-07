package com.sande.mythictrpg.quest;

import com.sande.mythictrpg.ai.action.AiActionScope;
import com.sande.mythictrpg.ai.server.AiConversationRuntimeService;
import com.sande.mythictrpg.ai.server.ConversationRooms;
import com.sande.mythictrpg.godavatar.GodAvatarEntity;
import com.sande.mythictrpg.godavatar.GodAvatarRegistryState;
import com.sande.mythictrpg.godavatar.GodAvatarService;
import com.sande.mythictrpg.gameplay.watch.GodWatchRuntime;
import com.sande.mythictrpg.interaction.api.InteractionMode;
import com.sande.mythictrpg.quest.reward.RewardClaimState;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.*;

/** Game-issued, player/God/session-bound contact. Locations and AI claims cannot issue a receipt. */
public final class QuestContactService {
    private QuestContactService() { }
    private enum Kind { PHYSICAL, ENCOUNTER, REMOTE }
    private record Key(UUID player, ResourceLocation god) { }
    private record Receipt(ServerPlayer player, Kind kind, AiActionScope scope, UUID avatar,
                           QuestContactLocation place, long tick) { }
    private static MinecraftServer server;
    private static final Map<Key, Receipt> contacts = new HashMap<>();

    public static void clear() {
        contacts.clear(); server = null;
        com.sande.mythictrpg.quest.dynamic.GeneratedQuestService.INSTANCE.clear();
    }
    private static void attach(ServerPlayer player) {
        if (!player.server.isSameThread()) throw new IllegalStateException("Quest contact requires server thread");
        if (server != player.server) { clear(); server = player.server; }
    }
    private static boolean scopeCurrent(ServerPlayer player, AiActionScope expected) {
        return expected != null && (ConversationRooms.INSTANCE.actionCurrent(player, expected.sessionId(), expected.actingGodId())
                || !ConversationRooms.enabled() && AiConversationRuntimeService.INSTANCE.currentActionScope(player)
                    .filter(expected::equals).isPresent());
    }
    /** Called only after the ordinary appearance planner committed an actual spontaneous encounter. */
    public static void encountered(ServerPlayer player, ResourceLocation god, UUID interactionId) {
        attach(player);
        var explicit = ConversationRooms.enabled() ? ConversationRooms.INSTANCE.contactScope(player, interactionId, god)
                : AiConversationRuntimeService.INSTANCE.currentActionScope(player).filter(s -> s.sessionId().equals(interactionId)
                    && s.actingGodId().equals(god));
        if (explicit.isEmpty()) return;
        explicit.ifPresent(s -> contacts.put(new Key(player.getUUID(), god),
                new Receipt(player, Kind.ENCOUNTER, s, null, QuestContactLocation.capture(player), player.server.overworld().getGameTime())));
        confirmContact(player, god, InteractionMode.SPONTANEOUS);
    }
    /** An actual entity click is the proof; a command-created room or materialized avatar is insufficient. */
    public static boolean met(ServerPlayer player, GodAvatarEntity avatar, AiActionScope current) {
        attach(player);
        ResourceLocation god = avatar.godId().orElse(null);
        if (god == null || !physicallyNear(player, avatar) || !speakerAvailable(player, god)) return false;
        if (current == null || !current.actingGodId().equals(god) || !scopeCurrent(player, current)) return false;
        contacts.put(new Key(player.getUUID(), god), new Receipt(player, Kind.PHYSICAL, current,
                avatar.getUUID(), QuestContactLocation.capture(player), player.server.overworld().getGameTime()));
        if (ConversationRooms.enabled()) ConversationRooms.INSTANCE.memberships(player).stream()
                .filter(room -> ConversationRooms.INSTANCE.actionScope(player, room.roomId(), room.revision(), god)
                        .filter(current::equals).isPresent()).findFirst()
                .ifPresent(room -> com.sande.mythictrpg.godavatar.activity.NpcActivityRuntime.INSTANCE.contextFor(player, god, room, false));
        confirmContact(player, god, InteractionMode.EXPLICIT);
        return true;
    }
    private static boolean physicallyNear(ServerPlayer player, GodAvatarEntity avatar) {
        return player.isAlive() && avatar.isAlive() && avatar.hasAuthoritativeBinding() && avatar.level() == player.level()
                && player.serverLevel().getEntity(avatar.getUUID()) == avatar
                && avatar.currentDefinition().filter(d -> avatar.distanceToSqr(player) <= d.interactionRange() * d.interactionRange()).isPresent();
    }
    /** The physical encounter is real; only its current authored appearance conditions are re-evaluated. */
    private static boolean appearanceConditions(ServerPlayer player, ResourceLocation god) {
        var signal = new com.sande.mythictrpg.interaction.api.InteractionSignal<>(
                com.sande.mythictrpg.interaction.api.InteractionSignalTypes.EXPLICIT_GOD_CALL, player.getUUID(), Set.of(),
                new com.sande.mythictrpg.interaction.api.ExplicitGodCallPayload(god,
                        com.sande.mythictrpg.interaction.api.InteractionSignalTypes.PLAYER_EXPLICIT_POLICY));
        var context = com.sande.mythictrpg.interaction.context.InteractionContextFactory.create(player.server, signal, Set.of(),
                com.sande.mythictrpg.interaction.policy.CooldownView.NONE,
                com.sande.mythictrpg.interaction.policy.InteractionRuntimeView.AVAILABLE);
        return context.ready() && com.sande.mythictrpg.data.god.GodAppearanceService.INSTANCE.evaluateAutomaticAppearance(
                context.godSnapshot(), context.conditionContext(), true, god).eligible();
    }
    private static boolean speakerAvailable(ServerPlayer player, ResourceLocation god) {
        return com.sande.mythictrpg.data.god.GodDefinitionManager.INSTANCE.find(god).isPresent()
                && com.sande.mythictrpg.story.presentation.StoryRoomConversationService.INSTANCE.speakerAvailable(player, god)
                && GodAvatarRegistryState.get(player.server).isReady()
                && GodAvatarRegistryState.get(player.server).raidOwner(god).isEmpty();
    }
    /** Reading/walking and all other physical activities block remote attention, but not a direct click. */
    public static boolean remoteAvailable(ServerPlayer player, ResourceLocation god) {
        attach(player);
        if (!speakerAvailable(player, god) || !GodWatchRuntime.watchingNow(player, god)
                || ConversationRooms.INSTANCE.godConversing(player, god)
                || AiConversationRuntimeService.INSTANCE.godConversing(god)) return false;
        var registry = GodAvatarRegistryState.get(player.server);
        var actor = GodAvatarService.INSTANCE.findLoaded(player.server, god).orElse(null);
        if (registry.find(god).isPresent() && actor == null) return false;
        return actor == null || actor.isAlive() && !actor.busyForVisit() && actor.getNavigation().isDone();
    }
    /** Normal player path: a synchronous reply to the currently watched player, never NPC summoning. */
    public static boolean call(ServerPlayer player, ResourceLocation god) {
        attach(player);
        if (!remoteAvailable(player, god)) return false;
        var key = new Key(player.getUUID(), god);
        contacts.put(key, new Receipt(player, Kind.REMOTE, null, null, QuestContactLocation.capture(player),
                player.server.overworld().getGameTime()));
        try {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("[신의 응답] 현재 주시를 통해 의뢰 수행 여부를 확인합니다."));
            confirmContact(player, god, InteractionMode.EXPLICIT);
        } finally { contacts.remove(key); }
        return true;
    }
    private static void confirmContact(ServerPlayer player, ResourceLocation god, InteractionMode mode) {
        QuestRuntimeService.INSTANCE.onNpcInteraction(player.server, player.getUUID(), god, mode);
        com.sande.mythictrpg.quest.dynamic.GeneratedQuestService.INSTANCE.onNpcContact(player, god);
    }
    private static Receipt current(ServerPlayer player, ResourceLocation god) {
        attach(player);
        var key = new Key(player.getUUID(), god);
        var receipt = contacts.get(key);
        if (receipt == null) return null;
        boolean valid = receipt.player() == player && player.server.getPlayerList().getPlayer(player.getUUID()) == player
                && player.isAlive() && speakerAvailable(player, god);
        if (receipt.kind() == Kind.REMOTE) valid &= receipt.tick() == player.server.overworld().getGameTime() && remoteAvailable(player, god);
        else valid &= scopeCurrent(player, receipt.scope());
        if (valid && receipt.kind() == Kind.PHYSICAL) {
            var avatar = GodAvatarService.INSTANCE.findLoaded(player.server, god).orElse(null);
            valid = avatar != null && avatar.getUUID().equals(receipt.avatar()) && physicallyNear(player, avatar)
                    && avatar.getTarget() == null;
        }
        if (valid && receipt.kind() == Kind.ENCOUNTER) valid = receipt.place().contains(player);
        if (!valid) { contacts.remove(key); return null; }
        return receipt;
    }
    public static boolean matchesScope(ServerPlayer player, AiActionScope expected) {
        if (expected == null) return false;
        var receipt = current(player, expected.actingGodId());
        return receipt != null && expected.equals(receipt.scope());
    }
    public static boolean canConfirm(ServerPlayer player, ResourceLocation god,
            Optional<QuestContactLocation> origin, Optional<QuestContactLocation> destination, QuestCompletionMode mode) {
        attach(player);
        if (mode == QuestCompletionMode.AUTO) return true; // Explicit content exception only.
        var receipt = current(player, god);
        if (receipt == null) return false;
        if (mode == QuestCompletionMode.NPC_VISIT_PLAYER && receipt.kind() != Kind.ENCOUNTER) return false;
        boolean watching = receipt.kind() == Kind.REMOTE && GodWatchRuntime.watchingNow(player, god);
        boolean encounter = receipt.kind() == Kind.ENCOUNTER || receipt.kind() == Kind.PHYSICAL && appearanceConditions(player, god);
        return QuestContactPolicy.permits(true, receipt.kind() == Kind.PHYSICAL, encounter,
                RewardClaimState.get(player.server).hasWatch(player.getUUID(), god), watching,
                receipt.kind() != Kind.REMOTE || remoteAvailable(player, god),
                origin.filter(p -> p.contains(player)).isPresent() || destination.filter(p -> p.contains(player)).isPresent(),
                mode == QuestCompletionMode.NPC_VISIT_PLAYER);
    }
    public static boolean canConfirm(ServerPlayer player, FtbQuestBinding binding, ResourceLocation god) {
        var assignment = MythicQuestState.get(player.server).assignmentsFor(player.getUUID()).stream()
                .filter(a -> a.questId().equals(binding.questId())).findFirst().orElse(null);
        return assignment != null && binding.acceptsCompletionNpc(assignment.giverGodId(), god)
                && canConfirm(player, god, assignment.origin(), binding.returnLocation(), binding.completionMode());
    }
    public static Optional<AiActionScope> currentScope(ServerPlayer player, ResourceLocation god) {
        var receipt = current(player, god);
        return receipt == null ? Optional.empty() : Optional.ofNullable(receipt.scope());
    }
}
