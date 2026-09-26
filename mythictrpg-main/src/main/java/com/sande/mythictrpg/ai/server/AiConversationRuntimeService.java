package com.sande.mythictrpg.ai.server;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.ai.action.AiActionGateway;
import com.sande.mythictrpg.ai.action.AiActionScope;
import com.sande.mythictrpg.ai.api.AiConversationControlContext;
import com.sande.mythictrpg.ai.api.AiConversationEngineRouter;
import com.sande.mythictrpg.ai.api.AiConversationStartContext;
import com.sande.mythictrpg.ai.api.AiConversationTurnSnapshot;
import com.sande.mythictrpg.data.god.GodDefinitionManager;
import com.sande.mythictrpg.data.player.GodIdentifiedEvent;
import com.sande.mythictrpg.data.player.PlayerGodKnowledgeService;
import com.sande.mythictrpg.interaction.content.ValidatedInteractionContent;
import com.sande.mythictrpg.interaction.director.InteractionPlan;
import com.sande.mythictrpg.network.AiConversationStatePayload;
import com.sande.mythictrpg.quest.QuestRuntimeService;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Server-authoritative runtime state for the optional AI conversation panel. */
public final class AiConversationRuntimeService {
    public static final AiConversationRuntimeService INSTANCE = new AiConversationRuntimeService();

    private final Map<UUID, State> states = new HashMap<>();
    private final Map<UUID, TestConversationScope> testScopes = new HashMap<>();

    private AiConversationRuntimeService() {
    }

    /** Explicit OP test lease: authorizes dialogue/memory only, never an Encounter or game action. */
    public Optional<UUID> beginTestConversation(ServerPlayer player, List<ResourceLocation> gods,
            boolean recording) {
        requireServerThread(player.server);
        State previous = states.getOrDefault(player.getUUID(), State.EMPTY);
        if (previous.enabled() || ConversationRooms.INSTANCE.hasMembership(player) || testScopes.containsKey(player.getUUID()) || gods == null
                || gods.isEmpty() || gods.size() > TestConversationScope.MAX_GODS
                || gods.stream().anyMatch(god -> god == null || GodDefinitionManager.INSTANCE.find(god).isEmpty())
                || new java.util.HashSet<>(gods).size() != gods.size()
                || recording && com.sande.mythictrpg.ai.memorycontract.MemoryFoundationSettings.mode()
                        == com.sande.mythictrpg.ai.memorycontract.MemoryFoundationSettings.Mode.OFF) {
            return Optional.empty();
        }
        UUID interaction = UUID.randomUUID();
        UUID generation = UUID.randomUUID();
        TestConversationScope scope = new TestConversationScope(player.getUUID(), interaction, generation, gods, recording);
        State next = new State(true, Optional.of(gods.getFirst()), Optional.of(interaction),
                Set.of(player.getUUID()), generation);
        com.sande.mythictrpg.quest.QuestParticipationService.INSTANCE.cancelForPlayer(player.server, player.getUUID());
        com.sande.mythictrpg.rumor.SocialRuntime.cancelPlayer(player.getUUID());
        testScopes.put(player.getUUID(), scope);
        states.put(player.getUUID(), next);
        sync(player, next);
        return Optional.of(interaction);
    }

    /** A stale close cannot disable a replacement conversation. No recursive engine callback. */
    public void endTestConversation(ServerPlayer player, UUID lease) {
        requireServerThread(player.server);
        TestConversationScope scope = testScopes.get(player.getUUID());
        if (scope == null || !scope.interactionId().equals(lease)) return;
        testScopes.remove(player.getUUID());
        State state = states.getOrDefault(player.getUUID(), State.EMPTY);
        if (scope.matches(state.interactionId().orElse(null), state.generation())) {
            com.sande.mythictrpg.rumor.SocialRuntime.cancelPlayer(player.getUUID());
            states.put(player.getUUID(), State.EMPTY);
            sync(player, State.EMPTY);
        }
    }

    public Optional<UUID> testConversationId(ServerPlayer player) {
        requireServerThread(player.server);
        return Optional.ofNullable(testScopes.get(player.getUUID())).map(TestConversationScope::interactionId);
    }

    public boolean isTestConversation(ServerPlayer player) {
        return testConversationId(player).isPresent();
    }

    /** Participant IDs are fixed by the issuing game command for the lifetime of a test lease. */
    public List<ResourceLocation> conversationGods(ServerPlayer player) {
        requireServerThread(player.server);
        State state = states.getOrDefault(player.getUUID(), State.EMPTY);
        if (!state.enabled()) return List.of();
        TestConversationScope scope = testScopes.get(player.getUUID());
        if (scope != null) return scope.matches(state.interactionId().orElse(null), state.generation())
                ? scope.godIds() : List.of();
        return state.currentGodId().map(List::of).orElseGet(List::of);
    }

    /** Recording OFF retains read authority; it never silently becomes a new memory/rumor write lease. */
    public boolean recordingAllowed(ServerPlayer player,
            com.sande.mythictrpg.ai.memorycontract.ConversationMemoryContext expected) {
        requireServerThread(player.server);
        if (!memoryContextCurrent(player, expected)) return false;
        var roomRecording = ConversationRooms.INSTANCE.recordingAllowed(player, expected);
        if (roomRecording.isPresent()) return roomRecording.get();
        TestConversationScope scope = testScopes.get(player.getUUID());
        return scope == null || scope.recordingAllowed(expected);
    }

    public void onInteractionStarted(MinecraftServer server, UUID interactionId,
            InteractionPlan plan, ValidatedInteractionContent content) {
        requireServerThread(server);
        ResourceLocation primaryGod = plan.participants().primaryGodId();
        if (ConversationRooms.enabled()) {
            ConversationRooms.INSTANCE.startInteraction(server, interactionId, plan, content);
            if (com.sande.mythictrpg.ai.memorycontract.MemoryFoundationSettings.mode()
                    != com.sande.mythictrpg.ai.memorycontract.MemoryFoundationSettings.Mode.RUMOR_TEST)
                QuestRuntimeService.INSTANCE.onNpcInteraction(server, plan.initiatingPlayerId(), primaryGod, plan.mode());
            return;
        }
        for (UUID playerId : plan.audience().recipientPlayerIds()) {
            testScopes.remove(playerId);
            com.sande.mythictrpg.quest.QuestParticipationService.INSTANCE.cancelForPlayer(server, playerId);
            State state = new State(true, Optional.of(primaryGod), Optional.of(interactionId),
                    Set.copyOf(plan.audience().recipientPlayerIds()), UUID.randomUUID());
            states.put(playerId, state);
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player != null) {
                sync(player, state);
            }
        }

        List<ResourceLocation> gods = new ArrayList<>();
        gods.add(primaryGod);
        gods.addAll(plan.participants().secondaryGodIds());
        List<AiConversationTurnSnapshot> turns = content.turns().stream()
                .map(turn -> new AiConversationTurnSnapshot(turn.speakerGodId(), turn.text().getString()))
                .toList();
        try {
            AiConversationEngineRouter.INSTANCE.onConversationStarted(new AiConversationStartContext(
                    interactionId, plan.initiatingPlayerId(), plan.audience().recipientPlayerIds(), gods, turns));
        } catch (RuntimeException exception) {
            MythicTrpg.LOGGER.error("AI conversation start notification failed for {}", interactionId, exception);
        }
        if (com.sande.mythictrpg.ai.memorycontract.MemoryFoundationSettings.mode()
                != com.sande.mythictrpg.ai.memorycontract.MemoryFoundationSettings.Mode.RUMOR_TEST) {
            QuestRuntimeService.INSTANCE.onNpcInteraction(server, plan.initiatingPlayerId(), primaryGod, plan.mode());
        }
    }

    public void setEnabled(ServerPlayer player, boolean enabled) {
        requireServerThread(player.server);
        TestConversationScope test = testScopes.get(player.getUUID());
        if (test != null) {
            if (!enabled) {
                endTestConversation(player, test.interactionId());
                AiConversationEngineRouter.INSTANCE.onEnabledChanged(new AiConversationControlContext(
                        player.getUUID(), false, Optional.empty()));
            }
            return;
        }
        com.sande.mythictrpg.quest.QuestParticipationService.INSTANCE.cancelForPlayer(player.server, player.getUUID());
        State previous = states.getOrDefault(player.getUUID(), State.EMPTY);
        State next = new State(enabled, previous.currentGodId(), previous.interactionId(),
                previous.audience(), UUID.randomUUID());
        states.put(player.getUUID(), next);
        sync(player, next);
        try {
            AiConversationEngineRouter.INSTANCE.onEnabledChanged(new AiConversationControlContext(
                    player.getUUID(), enabled, next.currentGodId()));
        } catch (RuntimeException exception) {
            MythicTrpg.LOGGER.error("AI conversation toggle failed for player {}", player.getUUID(), exception);
        }
    }

    public void onServerChat(ServerChatEvent event) {
        ServerPlayer player = event.getPlayer();
        State state = states.getOrDefault(player.getUUID(), State.EMPTY);
        String text = event.getRawText();
        if (!testScopes.containsKey(player.getUUID()) && ConversationRooms.enabled()
                && text != null && ConversationRooms.INSTANCE.publicText(player, text)) {
            event.setCanceled(true); return;
        }
        if (!state.enabled() || text == null || text.isBlank() || text.startsWith("!")) {
            return;
        }
        try {
            if (!testScopes.containsKey(player.getUUID())
                    && com.sande.mythictrpg.quest.QuestParticipationService.INSTANCE.handleAnswer(player, text)) {
                event.setCanceled(true);
                return;
            }
            if (AiConversationEngineRouter.INSTANCE.onPlayerText(player.getUUID(), text)) {
                event.setCanceled(true);
            }
        } catch (RuntimeException exception) {
            MythicTrpg.LOGGER.error("Failed to forward player chat to the AI engine", exception);
        }
    }

    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            State state = states.getOrDefault(player.getUUID(), State.EMPTY);
            states.put(player.getUUID(), state);
            sync(player, state);
        }
    }

    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ConversationRooms.INSTANCE.loggedOut(player);
            com.sande.mythictrpg.quest.QuestParticipationService.INSTANCE.cancelAllForPlayer(player.server, player.getUUID());
            states.remove(player.getUUID());
            testScopes.remove(player.getUUID());
            AiActionGateway.discardPlayer(player.getUUID());
            com.sande.mythictrpg.story.presentation.StoryAiHookTokenService.INSTANCE
                    .discardPlayer(player.getUUID());
            AiConversationEngineRouter.INSTANCE.onPlayerLoggedOut(player.getUUID());
        }
    }

    /** Returns only the server-owned scope; AI output cannot choose either value. */
    public Optional<AiActionScope> currentActionScope(ServerPlayer player) {
        requireServerThread(player.server);
        if (testScopes.containsKey(player.getUUID())) return Optional.empty();
        if (com.sande.mythictrpg.ai.memorycontract.MemoryFoundationSettings.mode()
                == com.sande.mythictrpg.ai.memorycontract.MemoryFoundationSettings.Mode.RUMOR_TEST) return Optional.empty();
        State state = states.getOrDefault(player.getUUID(), State.EMPTY);
        if (!state.enabled() || state.currentGodId().isEmpty() || state.interactionId().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new AiActionScope(state.interactionId().orElseThrow(),
                state.currentGodId().orElseThrow()));
    }

    /** Existing live recipients only; no proximity-based implicit membership. */
    public Set<UUID> conversationPlayers(ServerPlayer player) {
        State origin = states.getOrDefault(player.getUUID(), State.EMPTY);
        if (!origin.enabled() || origin.interactionId().isEmpty()) return Set.of();
        return origin.audience().stream().filter(id -> {
            State other = states.getOrDefault(id, State.EMPTY);
            return other.enabled() && other.interactionId().equals(origin.interactionId())
                    && other.currentGodId().equals(origin.currentGodId())
                    && player.server.getPlayerList().getPlayer(id) != null;
        }).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public Optional<UUID> conversationGeneration(UUID playerId) {
        State state = states.getOrDefault(playerId, State.EMPTY);
        return state.enabled() ? Optional.of(state.generation()) : Optional.empty();
    }

    /** Explicit leave differs from the existing pause/resume HUD toggle. */
    public void leaveConversation(ServerPlayer player) {
        setEnabled(player, false);
        State previous = states.getOrDefault(player.getUUID(), State.EMPTY);
        states.put(player.getUUID(), new State(false, previous.currentGodId(), Optional.of(UUID.randomUUID()),
                Set.of(player.getUUID()), UUID.randomUUID()));
    }

    /** Explicit join near an existing same-God conversation; starts a fresh shared disclosure scope. */
    public boolean joinConversation(ServerPlayer joining, ServerPlayer host) {
        requireServerThread(joining.server);
        if (isTestConversation(joining) || isTestConversation(host)) return false;
        State state = states.getOrDefault(host.getUUID(), State.EMPTY);
        if (joining == host || !state.enabled() || state.currentGodId().isEmpty()
                || state.interactionId().isEmpty() || joining.level() != host.level()
                || joining.distanceToSqr(host) > 16.0 * 16.0) return false;
        Set<UUID> audience = new java.util.LinkedHashSet<>(conversationPlayers(host));
        if (audience.contains(joining.getUUID()) || audience.size() >= 16) return false;
        // A player already conversing with a different God must leave first.
        State previous = states.getOrDefault(joining.getUUID(), State.EMPTY);
        if (previous.enabled() && previous.currentGodId().isPresent()
                && !previous.currentGodId().equals(state.currentGodId())) return false;
        audience.add(joining.getUUID());
        UUID interaction = UUID.randomUUID();
        for (UUID id : audience) {
            com.sande.mythictrpg.quest.QuestParticipationService.INSTANCE.cancelForPlayer(joining.server, id);
            State next = new State(true, state.currentGodId(), Optional.of(interaction), Set.copyOf(audience), UUID.randomUUID());
            states.put(id, next);
            ServerPlayer participant = joining.server.getPlayerList().getPlayer(id);
            if (participant != null) sync(participant, next);
        }
        AiConversationEngineRouter.INSTANCE.onConversationStarted(new AiConversationStartContext(
                interaction, host.getUUID(), audience, List.of(state.currentGodId().orElseThrow()), List.of()));
        QuestRuntimeService.INSTANCE.onNpcInteraction(joining.server, joining.getUUID(), state.currentGodId().orElseThrow(),
                com.sande.mythictrpg.interaction.api.InteractionMode.EXPLICIT);
        return true;
    }

    /** Additive v1 contract; existing LP DTO constructors and callbacks stay binary compatible. */
    public Optional<com.sande.mythictrpg.ai.memorycontract.ConversationMemoryContext> memoryContext(ServerPlayer player) {
        requireServerThread(player.server);
        State state = states.getOrDefault(player.getUUID(), State.EMPTY);
        return state.currentGodId().flatMap(god -> memoryContext(player, god));
    }

    public Optional<com.sande.mythictrpg.ai.memorycontract.ConversationMemoryContext> memoryContext(
            ServerPlayer player, ResourceLocation god) {
        requireServerThread(player.server);
        var mode = com.sande.mythictrpg.ai.memorycontract.MemoryFoundationSettings.mode();
        if (mode == com.sande.mythictrpg.ai.memorycontract.MemoryFoundationSettings.Mode.OFF) return Optional.empty();
        State state = states.getOrDefault(player.getUUID(), State.EMPTY);
        if (!state.enabled() || state.currentGodId().isEmpty() || state.interactionId().isEmpty()
                || !state.audience().contains(player.getUUID()) || god == null
                || !conversationGods(player).contains(god)) return Optional.empty();
        // Keep the original disclosure boundary; never reinterpret a group exchange as private.
        var data = com.sande.mythictrpg.rumor.RumorSavedData.get(player.server);
        if (!data.ready()) return Optional.empty();
        return Optional.of(new com.sande.mythictrpg.ai.memorycontract.ConversationMemoryContext(
                data.worldId(), state.interactionId().orElseThrow(), state.generation(),
                player.getUUID(), god.toString(), state.audience(),
                mode == com.sande.mythictrpg.ai.memorycontract.MemoryFoundationSettings.Mode.RUMOR_TEST));
    }

    public boolean memoryContextCurrent(ServerPlayer player,
            com.sande.mythictrpg.ai.memorycontract.ConversationMemoryContext expected) {
        if (ConversationRooms.INSTANCE.memoryCurrent(player, expected)) return true;
        return expected != null && memoryContext(player, ResourceLocation.tryParse(expected.godId()))
                .filter(expected::equals).isPresent();
    }

    /** Refreshes the active panel as soon as its previously unknown God is identified. */
    public void onGodIdentified(GodIdentifiedEvent event) {
        requireServerThread(event.server());
        ServerPlayer roomPlayer = event.server().getPlayerList().getPlayer(event.playerId());
        if (roomPlayer != null && ConversationRooms.INSTANCE.hasMembership(roomPlayer)) ConversationRooms.INSTANCE.sync(roomPlayer);
        State state = states.get(event.playerId());
        if (state == null) {
            return;
        }
        ServerPlayer player = event.server().getPlayerList().getPlayer(event.playerId());
        if (player != null && conversationGods(player).contains(event.godId())) {
            sync(player, state);
        }
    }

    public void onServerStopped(ServerStoppedEvent event) {
        ConversationRooms.INSTANCE.clear();
        states.clear();
        testScopes.clear();
        com.sande.mythictrpg.quest.QuestParticipationService.INSTANCE.clear();
        AiActionGateway.clear();
        AiConversationEngineRouter.INSTANCE.onServerStopped();
        com.sande.mythictrpg.ai.memorycontract.MemoryFoundationSettings.reset();
    }

    private static void sync(ServerPlayer player, State state) {
        var displayName = Component.empty();
        List<ResourceLocation> gods = INSTANCE.testScopes.containsKey(player.getUUID())
                ? INSTANCE.conversationGods(player) : state.currentGodId().map(List::of).orElseGet(List::of);
        if (gods.isEmpty()) displayName.append("-");
        for (ResourceLocation god : gods) {
            if (!displayName.getString().isEmpty()) displayName.append(" / ");
            displayName.append(displayName(player, god));
        }
        try {
            PacketDistributor.sendToPlayer(player,
                    new AiConversationStatePayload(state.enabled(), displayName));
        } catch (UnsupportedOperationException exception) {
            // Embedded GameTest players deliberately do not negotiate custom payload channels.
            MythicTrpg.LOGGER.debug("AI conversation state channel is unavailable for player {}",
                    player.getUUID());
        }
    }

    private static Component displayName(ServerPlayer player, ResourceLocation godId) {
        boolean identified = PlayerGodKnowledgeService.get(player.server)
                .snapshot(player.getUUID(), godId).identified();
        if (!identified) {
            return Component.literal("????");
        }
        return GodDefinitionManager.INSTANCE.find(godId)
                .map(definition -> definition.displayName())
                .orElseGet(() -> Component.literal("????"));
    }

    private static void requireServerThread(MinecraftServer server) {
        if (!server.isSameThread()) {
            throw new IllegalStateException("AI conversation runtime may only change on the server thread");
        }
    }

    private record State(boolean enabled, Optional<ResourceLocation> currentGodId,
            Optional<UUID> interactionId, Set<UUID> audience, UUID generation) {
        private static final State EMPTY = new State(false, Optional.empty(), Optional.empty(), Set.of(), new UUID(0, 0));
    }
}
