package com.sande.mythictrpg.ai.action;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.ai.server.AiConversationRuntimeService;
import com.sande.mythictrpg.ai.server.ConversationRooms;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import com.sande.mythictrpg.network.AiActionConfirmationPayload;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Authoritative ingress for every AI-proposed game action.
 *
 * <p>The caller supplies narrative data only. The active session, target player,
 * allow-listed action definition, validation and execution are all resolved by
 * MythicTRPG on the server thread.</p>
 */
public final class AiActionGateway {
    private static final long CONFIRMATION_TIMEOUT_TICKS = 1_200L;
    private static final int MAX_PENDING_PER_PLAYER = 8;
    private static final Map<UUID, PendingAction> PENDING = new HashMap<>();
    private static final Map<ExecutionKey, ExecutionState> EXECUTION_STATE = new HashMap<>();

    private AiActionGateway() {
    }

    public static AiActionResult submit(ServerPlayer player, ResourceLocation actingGodId,
            String protocolActionType, String title, String summary, Map<String, String> parameters) {
        return submit(player, actingGodId, protocolActionType, title, summary, parameters, false);
    }

    public static AiActionResult submit(ServerPlayer player, ResourceLocation actingGodId,
            String protocolActionType, String title, String summary, Map<String, String> parameters,
            boolean playerDeclaredItemReady) {
        requireServerThread(player);
        return submitScoped(player, AiConversationRuntimeService.INSTANCE.currentActionScope(player), false,
                actingGodId, protocolActionType, title, summary, parameters, playerDeclaredItemReady, null);
    }

    /** Explicit room ingress. The room/revision is game transport data, never a model parameter. */
    public static AiActionResult submitRoom(ServerPlayer player, UUID roomId, long revision,
            ResourceLocation actingGodId, String protocolActionType, String title, String summary,
            Map<String, String> parameters, boolean playerDeclaredItemReady) {
        requireServerThread(player);
        return submitScoped(player, ConversationRooms.INSTANCE.actionScope(player, roomId, revision, actingGodId), true,
                actingGodId, protocolActionType, title, summary, parameters, playerDeclaredItemReady,
                new RoomOrigin(player.server, roomId, revision));
    }

    private static AiActionResult submitScoped(ServerPlayer player, Optional<AiActionScope> scope,
            boolean roomAction, ResourceLocation actingGodId, String protocolActionType, String title,
            String summary, Map<String, String> parameters, boolean playerDeclaredItemReady, RoomOrigin origin) {
        ResourceLocation actionType;
        try {
            actionType = AiActionTypes.fromProtocolName(protocolActionType);
        } catch (IllegalArgumentException exception) {
            return scopedRejection(player, scope, actingGodId, origin,
                    rejected(UUID.randomUUID(), fallbackType(), exception.getMessage()));
        }

        if (com.sande.mythictrpg.ai.memorycontract.MemoryFoundationSettings.mode()
                == com.sande.mythictrpg.ai.memorycontract.MemoryFoundationSettings.Mode.RUMOR_TEST) {
            return rejected(UUID.randomUUID(), actionType, "Memory rumor test mode is dialogue-only; game proposals are disabled");
        }
        if (scope.isEmpty()) {
            return rejected(UUID.randomUUID(), actionType,
                    roomAction ? "The requested room revision does not authorize this game action"
                            : "No server-authorized AI conversation is active for this player");
        }
        if (!scope.orElseThrow().actingGodId().equals(actingGodId)) {
            return rejected(UUID.randomUUID(), actionType,
                    "The proposed actor is not the server-authorized conversation actor");
        }

        AiActionProposal proposal;
        try {
            Map<String, String> canonicalParameters = new LinkedHashMap<>(
                    parameters == null ? Map.of() : parameters);
            if (actionType.equals(AiActionTypes.ITEM_REQUEST)) {
                canonicalParameters.put("player_ready", Boolean.toString(playerDeclaredItemReady));
            }
            proposal = new AiActionProposal(UUID.randomUUID(), scope.orElseThrow().sessionId(), actionType,
                    actingGodId, player.getUUID(), title, summary, canonicalParameters);
        } catch (IllegalArgumentException exception) {
            return scopedRejection(player, scope, actingGodId, origin,
                    rejected(UUID.randomUUID(), actionType, exception.getMessage()));
        }
        AiActionResult result = validateAndDispatch(player, proposal, roomAction, origin);
        publishOutcome(player, proposal, origin, result);
        return result;
    }

    /** Revalidates and executes one previously pending action after explicit player confirmation. */
    public static AiActionResult confirm(ServerPlayer player, UUID proposalId) {
        requireServerThread(player);
        expirePending(player.server);
        PendingAction pending = PENDING.get(proposalId);
        if (!ownedBy(pending, player)) {
            return rejected(proposalId, fallbackType(), "Pending AI action was not found for this player");
        }
        PENDING.remove(proposalId);
        if (!current(player, pending.proposal(), pending.roomAction())) {
            return terminal(player, pending, rejected(proposalId, pending.proposal().actionType(),
                    "The conversation that created this action is no longer active"));
        }
        if (!pending.terms().matches(pending.proposal())) return terminal(player, pending,
                rejected(proposalId, pending.proposal().actionType(), "Confirmed action terms changed; request a new offer"));
        AiActionDefinition definition = AiActionRegistry.INSTANCE.find(pending.proposal().actionType()).orElse(null);
        if (definition == null) {
            return terminal(player, pending, rejected(proposalId, pending.proposal().actionType(), "AI action type is no longer registered"));
        }
        return terminal(player, pending, validateAndExecute(player, pending.proposal(), definition, pending.roomAction()));
    }

    public static boolean cancel(ServerPlayer player, UUID proposalId) {
        requireServerThread(player);
        expirePending(player.server);
        PendingAction pending = PENDING.get(proposalId);
        if (!ownedBy(pending, player)) {
            return false;
        }
        PENDING.remove(proposalId);
        terminal(player, pending, result(pending.proposal(), AiActionResult.Status.CANCELLED,
                "Player declined the pending action; nothing was executed", Map.of()));
        return true;
    }

    public static Set<ResourceLocation> registeredActionTypes() {
        return AiActionRegistry.INSTANCE.actionTypes();
    }

    public static void discardPlayer(UUID playerId) {
        PENDING.values().removeIf(pending -> pending.proposal().targetPlayerId().equals(playerId));
        EXECUTION_STATE.keySet().removeIf(key -> key.playerId().equals(playerId));
    }

    public static void clear() {
        PENDING.clear();
        EXECUTION_STATE.clear();
    }

    private static AiActionResult validateAndDispatch(ServerPlayer player, AiActionProposal proposal, boolean roomAction,
            RoomOrigin origin) {
        if (!current(player, proposal, roomAction)) return rejected(proposal.proposalId(), proposal.actionType(),
                "The conversation that created this action is no longer active");
        AiActionDefinition definition = AiActionRegistry.INSTANCE.find(proposal.actionType()).orElse(null);
        if (definition == null) {
            return rejected(proposal.proposalId(), proposal.actionType(),
                    "AI action type is not registered by MythicTRPG");
        }
        String executionRejection = executionRejection(player, proposal);
        if (executionRejection != null) {
            return rejected(proposal.proposalId(), proposal.actionType(),
                    executionRejection);
        }
        AiActionValidation validation = validate(player, proposal, definition, roomAction);
        if (!validation.accepted()) {
            return rejected(proposal.proposalId(), proposal.actionType(), validation.reason());
        }
        if (definition.confirmationPolicy()
                == AiActionDefinition.ConfirmationPolicy.PLAYER_CONFIRMATION_REQUIRED) {
            long gameTime = player.server.overworld().getGameTime();
            expirePending(player.server);
            long pendingForPlayer = PENDING.values().stream()
                    .filter(value -> value.proposal().targetPlayerId().equals(player.getUUID()))
                    .count();
            if (pendingForPlayer >= MAX_PENDING_PER_PLAYER) {
                return rejected(proposal.proposalId(), proposal.actionType(),
                        "Too many AI actions are already waiting for player confirmation");
            }
            long expiresAt = gameTime + CONFIRMATION_TIMEOUT_TICKS;
            AiActionConfirmationTerms.Terms terms;
            try { terms = AiActionConfirmationTerms.capture(proposal); }
            catch (IllegalArgumentException changed) {
                return rejected(proposal.proposalId(), proposal.actionType(), "Action terms are no longer available");
            }
            PENDING.put(proposal.proposalId(), new PendingAction(player.server, proposal, expiresAt, roomAction, origin, terms));
            try {
                PacketDistributor.sendToPlayer(player, new AiActionConfirmationPayload(
                        proposal.proposalId(), proposal.actionType(), proposal.title(), proposal.summary(),
                        (int) (CONFIRMATION_TIMEOUT_TICKS / 20L), terms.lines()));
            } catch (RuntimeException unavailable) {
                PENDING.remove(proposal.proposalId());
                return failed(proposal, "Confirmation could not be delivered; nothing was executed");
            }
            return result(proposal, AiActionResult.Status.PENDING_CONFIRMATION,
                    "Explicit player confirmation is required", Map.of());
        }
        return execute(player, proposal, definition, roomAction);
    }

    private static AiActionResult validateAndExecute(ServerPlayer player, AiActionProposal proposal,
            AiActionDefinition definition, boolean roomAction) {
        String executionRejection = executionRejection(player, proposal);
        if (executionRejection != null) {
            return rejected(proposal.proposalId(), proposal.actionType(),
                    executionRejection);
        }
        AiActionValidation validation = validate(player, proposal, definition, roomAction);
        return validation.accepted() ? execute(player, proposal, definition, roomAction)
                : rejected(proposal.proposalId(), proposal.actionType(), validation.reason());
    }

    private static AiActionValidation validate(ServerPlayer player, AiActionProposal proposal,
            AiActionDefinition definition, boolean roomAction) {
        if (!current(player, proposal, roomAction)) return AiActionValidation.reject("Action conversation is no longer current");
        try {
            AiActionValidation validation = definition.validator().validate(
                    context(player, proposal, roomAction), proposal);
            return validation == null ? AiActionValidation.reject("AI action validator returned no result")
                    : validation;
        } catch (RuntimeException exception) {
            MythicTrpg.LOGGER.error("AI action validator failed for {}", proposal.actionType(), exception);
            return AiActionValidation.reject("AI action validation failed internally");
        }
    }

    private static AiActionResult execute(ServerPlayer player, AiActionProposal proposal,
            AiActionDefinition definition, boolean roomAction) {
        if (!current(player, proposal, roomAction)) return rejected(proposal.proposalId(), proposal.actionType(),
                "Action conversation changed before execution");
        try {
            AiActionExecution execution = definition.executor().execute(
                    context(player, proposal, roomAction), proposal);
            if (execution == null) {
                return failed(proposal, "AI action executor returned no result");
            }
            AiActionResult result = execution.executed()
                    ? result(proposal, AiActionResult.Status.EXECUTED, execution.reason(), execution.details())
                    : rejected(proposal.proposalId(), proposal.actionType(), execution.reason());
            if (execution.executed()) {
                recordExecution(player, proposal);
            }
            MythicTrpg.LOGGER.info("AI action {} proposal={} session={} actor={} player={} result={} reason={}",
                    proposal.actionType(), proposal.proposalId(), proposal.sessionId(), proposal.actingGodId(),
                    proposal.targetPlayerId(), result.status(), result.reason());
            return result;
        } catch (RuntimeException exception) {
            MythicTrpg.LOGGER.error("AI action executor failed for {}", proposal.actionType(), exception);
            return failed(proposal, "AI action execution failed internally");
        }
    }

    /** No inference or message generation: only resolves server-owned pending outcomes. */
    public static void expirePending(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("AI action expiry requires server thread");
        long gameTime = server.overworld().getGameTime();
        var expired = new ArrayList<PendingAction>();
        Iterator<PendingAction> iterator = PENDING.values().iterator();
        while (iterator.hasNext()) {
            PendingAction pending = iterator.next();
            if (pending.server() == server && pending.expiresAtGameTick() <= gameTime) {
                iterator.remove();
                expired.add(pending);
            }
        }
        for (var pending : expired) {
            var player = server.getPlayerList().getPlayer(pending.proposal().targetPlayerId());
            if (player != null) terminal(player, pending, result(pending.proposal(), AiActionResult.Status.EXPIRED,
                    "Confirmation expired; nothing was executed", Map.of()));
        }
    }

    private static boolean ownedBy(PendingAction pending, ServerPlayer player) {
        return pending != null && pending.server() == player.server
                && player.server.getPlayerList().getPlayer(player.getUUID()) == player
                && pending.proposal().targetPlayerId().equals(player.getUUID());
    }

    private static AiActionResult terminal(ServerPlayer player, PendingAction pending, AiActionResult result) {
        publishOutcome(player, pending.proposal(), pending.origin(), result);
        return result;
    }

    private static AiActionResult scopedRejection(ServerPlayer player, Optional<AiActionScope> scope,
            ResourceLocation god, RoomOrigin origin, AiActionResult result) {
        if (origin != null && scope.filter(value -> value.actingGodId().equals(god)).isPresent()) {
            // Do not reuse malformed model fields merely to report a game rejection.
            var rejectedProposal = new AiActionProposal(result.proposalId(), scope.orElseThrow().sessionId(),
                    result.actionType(), god, player.getUUID(), "", "", Map.of());
            publishOutcome(player, rejectedProposal, origin, result);
        }
        return result;
    }

    private static void publishOutcome(ServerPlayer player, AiActionProposal proposal, RoomOrigin origin, AiActionResult result) {
        if (origin == null) return; // Legacy sessions never acquire a currently selected room.
        try { ConversationRooms.INSTANCE.acceptActionOutcome(player, new RoomOutcome(origin, proposal, result)); }
        catch (RuntimeException failure) { MythicTrpg.LOGGER.error("AI action result feedback unavailable for {}", proposal.proposalId(), failure); }
    }

    private static AiActionResult rejected(UUID proposalId, ResourceLocation type, String reason) {
        return new AiActionResult(proposalId, type, AiActionResult.Status.REJECTED, reason, Map.of());
    }

    private static AiActionResult failed(AiActionProposal proposal, String reason) {
        return result(proposal, AiActionResult.Status.FAILED, reason, Map.of());
    }

    private static AiActionResult result(AiActionProposal proposal, AiActionResult.Status status,
            String reason, Map<String, String> details) {
        return new AiActionResult(proposal.proposalId(), proposal.actionType(), status, reason, details);
    }

    private static ResourceLocation fallbackType() {
        return ResourceLocation.fromNamespaceAndPath("mythictrpg", "invalid_action");
    }

    private static ExecutionKey executionKey(AiActionProposal proposal) {
        String discriminator = proposal.parameters().getOrDefault("template_id",
                proposal.parameters().getOrDefault("quest_id",
                        proposal.parameters().getOrDefault("transition_id",
                                proposal.parameters().getOrDefault("token", proposal.actionType().toString()))));
        return new ExecutionKey(proposal.sessionId(), proposal.targetPlayerId(), proposal.actingGodId(),
                proposal.actionType(), discriminator);
    }

    private static String executionRejection(ServerPlayer player, AiActionProposal proposal) {
        ExecutionState state = EXECUTION_STATE.get(executionKey(proposal));
        if (state == null) {
            return null;
        }
        ExecutionLimit limit = executionLimit(proposal);
        if (state.count() >= limit.maxUsesPerSession()) {
            return limit.maxUsesPerSession() == 1
                    ? "This AI action was already executed in the current conversation"
                    : "This AI action reached its per-conversation use limit";
        }
        long elapsed = player.server.overworld().getGameTime() - state.lastExecutionTick();
        return elapsed < limit.cooldownTicks()
                ? "This AI action is still on cooldown for the current conversation" : null;
    }

    private static void recordExecution(ServerPlayer player, AiActionProposal proposal) {
        ExecutionKey key = executionKey(proposal);
        long gameTime = player.server.overworld().getGameTime();
        EXECUTION_STATE.compute(key, (ignored, previous) -> previous == null
                ? new ExecutionState(1, gameTime)
                : new ExecutionState(previous.count() + 1, gameTime));
    }

    private static ExecutionLimit executionLimit(AiActionProposal proposal) {
        // This queues consideration only; actual scheduling uses the authored avatar cooldown.
        // A long-lived private room must not permanently disable visits after its first request.
        if (proposal.actionType().equals(AiActionTypes.NPC_VISIT_REQUEST)) return new ExecutionLimit(Integer.MAX_VALUE, 0);
        if (proposal.actionType().equals(AiActionTypes.NPC_ACTIVITY_REQUEST)) return new ExecutionLimit(Integer.MAX_VALUE, 0);
        if (proposal.actionType().equals(AiActionTypes.QUEST_ROSTER_REQUEST)) return new ExecutionLimit(Integer.MAX_VALUE, 20);
        if (proposal.actionType().equals(AiActionTypes.PLAYER_DAMAGE)) {
            PlayerDamageTemplate template = AiActionParameters.template(proposal, PlayerDamageTemplate.class);
            if (template != null) {
                return new ExecutionLimit(template.maxUsesPerSession(), template.cooldownTicks());
            }
        }
        return new ExecutionLimit(1, 0);
    }

    private static void requireServerThread(ServerPlayer player) {
        if (!player.server.isSameThread()) {
            throw new IllegalStateException("AI actions may only be submitted on the server thread");
        }
    }

    private static AiActionContext context(ServerPlayer player, AiActionProposal proposal, boolean roomAction) {
        return new AiActionContext(player.server, player, roomAction
                ? Optional.of(new AiActionScope(proposal.sessionId(), proposal.actingGodId())) : Optional.empty());
    }

    private static boolean current(ServerPlayer player, AiActionProposal proposal, boolean roomAction) {
        if (!proposal.targetPlayerId().equals(player.getUUID())
                || com.sande.mythictrpg.ai.memorycontract.MemoryFoundationSettings.mode()
                    == com.sande.mythictrpg.ai.memorycontract.MemoryFoundationSettings.Mode.RUMOR_TEST) return false;
        // Never substitute an ambient legacy session when an explicit room lease has expired.
        if (roomAction) return ConversationRooms.INSTANCE.actionCurrent(player, proposal.sessionId(), proposal.actingGodId());
        return AiConversationRuntimeService.INSTANCE.currentActionScope(player)
                .filter(scope -> scope.sessionId().equals(proposal.sessionId())
                        && scope.actingGodId().equals(proposal.actingGodId())).isPresent();
    }

    private record PendingAction(MinecraftServer server, AiActionProposal proposal, long expiresAtGameTick,
            boolean roomAction, RoomOrigin origin, AiActionConfirmationTerms.Terms terms) {
    }

    private record RoomOrigin(MinecraftServer server, UUID roomId, long revision) {}

    /** Opaque one-use game result. Neither a model-supplied proposal ID nor a caller-created DTO can mint it. */
    public static final class RoomOutcome {
        private final RoomOrigin origin;
        private final AiActionProposal proposal;
        private final AiActionResult result;
        private boolean consumed;
        private RoomOutcome(RoomOrigin origin, AiActionProposal proposal, AiActionResult result) {
            this.origin = origin; this.proposal = proposal; this.result = result;
        }
        public UUID roomId() { return origin.roomId(); }
        public long revision() { return origin.revision(); }
        public UUID sessionId() { return proposal.sessionId(); }
        public UUID playerId() { return proposal.targetPlayerId(); }
        public ResourceLocation godId() { return proposal.actingGodId(); }
        public AiActionResult result() { return result; }
        public boolean consumeFor(ServerPlayer player) {
            if (consumed || origin.server() != player.server || !player.server.isSameThread()
                    || player.server.getPlayerList().getPlayer(playerId()) != player
                    || !playerId().equals(player.getUUID())) return false;
            consumed = true; return true;
        }
    }

    private record ExecutionKey(UUID sessionId, UUID playerId, ResourceLocation actingGodId, ResourceLocation actionType,
            String discriminator) {
    }

    private record ExecutionState(int count, long lastExecutionTick) {
    }

    private record ExecutionLimit(int maxUsesPerSession, int cooldownTicks) {
    }
}
