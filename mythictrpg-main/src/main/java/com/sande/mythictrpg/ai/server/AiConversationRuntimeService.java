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

    private AiConversationRuntimeService() {
    }

    public void onInteractionStarted(MinecraftServer server, UUID interactionId,
            InteractionPlan plan, ValidatedInteractionContent content) {
        requireServerThread(server);
        ResourceLocation primaryGod = plan.participants().primaryGodId();
        for (UUID playerId : plan.audience().recipientPlayerIds()) {
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
        if (!state.enabled() || text == null || text.isBlank() || text.startsWith("!")) {
            return;
        }
        try {
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
            states.remove(player.getUUID());
            AiActionGateway.discardPlayer(player.getUUID());
            com.sande.mythictrpg.story.presentation.StoryAiHookTokenService.INSTANCE
                    .discardPlayer(player.getUUID());
            AiConversationEngineRouter.INSTANCE.onPlayerLoggedOut(player.getUUID());
        }
    }

    /** Returns only the server-owned scope; AI output cannot choose either value. */
    public Optional<AiActionScope> currentActionScope(ServerPlayer player) {
        requireServerThread(player.server);
        if (com.sande.mythictrpg.ai.memorycontract.MemoryFoundationSettings.mode()
                == com.sande.mythictrpg.ai.memorycontract.MemoryFoundationSettings.Mode.RUMOR_TEST) return Optional.empty();
        State state = states.getOrDefault(player.getUUID(), State.EMPTY);
        if (!state.enabled() || state.currentGodId().isEmpty() || state.interactionId().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new AiActionScope(state.interactionId().orElseThrow(),
                state.currentGodId().orElseThrow()));
    }

    /** Additive v1 contract; existing LP DTO constructors and callbacks stay binary compatible. */
    public Optional<com.sande.mythictrpg.ai.memorycontract.ConversationMemoryContext> memoryContext(ServerPlayer player) {
        requireServerThread(player.server);
        var mode = com.sande.mythictrpg.ai.memorycontract.MemoryFoundationSettings.mode();
        if (mode == com.sande.mythictrpg.ai.memorycontract.MemoryFoundationSettings.Mode.OFF) return Optional.empty();
        State state = states.getOrDefault(player.getUUID(), State.EMPTY);
        if (!state.enabled() || state.currentGodId().isEmpty() || state.interactionId().isEmpty()
                || !state.audience().contains(player.getUUID())) return Optional.empty();
        // Keep the original disclosure boundary; never reinterpret a group exchange as private.
        var data = com.sande.mythictrpg.rumor.RumorSavedData.get(player.server);
        if (!data.ready()) return Optional.empty();
        return Optional.of(new com.sande.mythictrpg.ai.memorycontract.ConversationMemoryContext(
                data.worldId(), state.interactionId().orElseThrow(), state.generation(),
                player.getUUID(), state.currentGodId().orElseThrow().toString(), state.audience(),
                mode == com.sande.mythictrpg.ai.memorycontract.MemoryFoundationSettings.Mode.RUMOR_TEST));
    }

    public boolean memoryContextCurrent(ServerPlayer player,
            com.sande.mythictrpg.ai.memorycontract.ConversationMemoryContext expected) {
        return expected != null && memoryContext(player).filter(expected::equals).isPresent();
    }

    /** Refreshes the active panel as soon as its previously unknown God is identified. */
    public void onGodIdentified(GodIdentifiedEvent event) {
        requireServerThread(event.server());
        State state = states.get(event.playerId());
        if (state == null || state.currentGodId().filter(event.godId()::equals).isEmpty()) {
            return;
        }
        ServerPlayer player = event.server().getPlayerList().getPlayer(event.playerId());
        if (player != null) {
            sync(player, state);
        }
    }

    public void onServerStopped(ServerStoppedEvent event) {
        states.clear();
        AiActionGateway.clear();
        AiConversationEngineRouter.INSTANCE.onServerStopped();
        com.sande.mythictrpg.ai.memorycontract.MemoryFoundationSettings.reset();
    }

    private static void sync(ServerPlayer player, State state) {
        Component displayName = state.currentGodId()
                .map(godId -> displayName(player, godId))
                .orElseGet(() -> Component.literal("-"));
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
