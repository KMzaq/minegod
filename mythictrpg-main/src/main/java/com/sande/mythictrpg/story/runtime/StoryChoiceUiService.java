package com.sande.mythictrpg.story.runtime;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.network.StoryChoicePagePayload;
import com.sande.mythictrpg.story.definition.StoryDefinitionManager;
import com.sande.mythictrpg.story.definition.StoryDefinitions.EventDefinition;
import com.sande.mythictrpg.story.state.StoryRuntimeModels.EventInstance;
import com.sande.mythictrpg.story.state.StoryRuntimeModels.EventStatus;
import com.sande.mythictrpg.story.state.StoryRuntimeState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Projects only this player's eligible choices; never sends effects or hidden conditions. */
public final class StoryChoiceUiService {
    public static final StoryChoiceUiService INSTANCE = new StoryChoiceUiService();
    private static final int BROWSE_INTERVAL_TICKS = 4;
    private MinecraftServer browseServer;
    private final Map<UUID, Integer> lastBrowseTickByPlayer = new HashMap<>();

    private StoryChoiceUiService() {}

    public List<StoryChoicePagePayload.Offer> pendingFor(ServerPlayer player) {
        requireServerThread(player.server);
        StoryRuntimeState state = StoryRuntimeState.get(player.server);
        if (!state.isReady()) return List.of();
        var result = new ArrayList<StoryChoicePagePayload.Offer>();
        state.eventInstances().values().stream()
                .filter(instance -> instance.status() == EventStatus.WAITING_FOR_CHOICE)
                .filter(instance -> instance.frozenAudiencePlayerIds().contains(player.getUUID()))
                .sorted(Comparator.comparingLong(EventInstance::triggeredAtGameTime)
                        .thenComparing(EventInstance::instanceId))
                .forEach(instance -> offer(player.server, instance).ifPresent(result::add));
        return List.copyOf(result);
    }

    private Optional<StoryChoicePagePayload.Offer> offer(MinecraftServer server, EventInstance instance) {
        EventDefinition definition = StoryDefinitionManager.INSTANCE.event(instance.eventId()).orElse(null);
        if (definition == null || !definition.fingerprint().equals(instance.definitionFingerprint())
                || definition.choicePolicy().isEmpty()) return Optional.empty();
        var policy = definition.choicePolicy().orElseThrow();
        if (policy.timeoutTicks() > 0
                && server.overworld().getGameTime() - instance.triggeredAtGameTime() >= policy.timeoutTicks())
            return Optional.empty();
        var choices = StoryEventService.INSTANCE.eligibleOutcomes(server, definition, instance.scope(),
                instance.initiatingPlayerId());
        if (choices.isEmpty()) return Optional.empty();
        var options = choices.stream().map(choice -> new StoryChoicePagePayload.Option(choice.id(),
                choice.displayName().orElse(choice.id().toString()), choice.description().orElse(""))).toList();
        return Optional.of(new StoryChoicePagePayload.Offer(instance.instanceId(), instance.revision(),
                instance.eventId(), policy.displayName().orElse(instance.eventId().toString()),
                policy.description().orElse(""), options));
    }

    public StoryChoicePagePayload pageFor(ServerPlayer player, int requestedPage, boolean open) {
        var offers = pendingFor(player);
        if (offers.isEmpty()) return new StoryChoicePagePayload(0, 0, open, Optional.empty());
        int page = Math.max(0, Math.min(requestedPage, offers.size() - 1));
        return new StoryChoicePagePayload(page, offers.size(), open, Optional.of(offers.get(page)));
    }

    public void sendPage(ServerPlayer player, int page, boolean open) {
        requireServerThread(player.server);
        try {
            sendPayload(player, pageFor(player, page, open));
        } catch (RuntimeException exception) {
            MythicTrpg.LOGGER.warn("Could not send Story choice view to {}", player.getUUID(), exception);
        }
    }

    /** Throttles only explicit browsing; automatic refresh and choice recovery remain unrestricted. */
    public StoryChoicePagePayload browsePage(ServerPlayer player, int page) {
        requireServerThread(player.server);
        if (browseServer != player.server) {
            browseServer = player.server;
            lastBrowseTickByPlayer.clear();
        }
        int now = player.server.getTickCount();
        Integer previous = lastBrowseTickByPlayer.get(player.getUUID());
        if (previous != null && (long) now - previous < BROWSE_INTERVAL_TICKS) return null;
        lastBrowseTickByPlayer.put(player.getUUID(), now);
        StoryChoicePagePayload payload = pageFor(player, page, true);
        try {
            sendPayload(player, payload);
        } catch (RuntimeException exception) {
            MythicTrpg.LOGGER.warn("Could not browse Story choice view for {}", player.getUUID(), exception);
        }
        return payload;
    }

    private static void sendPayload(ServerPlayer player, StoryChoicePagePayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    public void refreshAudience(MinecraftServer server, Set<UUID> audience, boolean open) {
        requireServerThread(server);
        for (UUID playerId : audience) {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player != null) sendPage(player, 0, open);
        }
    }

    private static void requireServerThread(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Story choice view requires server thread");
    }
}
