package com.sande.mythictrpg.ai;

import net.minecraft.resources.ResourceLocation;
import com.sande.mythictrpg.ai.proposal.VouchResolutionProposal;
import com.sande.mythictrpg.ai.vouch.PendingVouchInteraction;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Server-thread session ownership. Sessions do not share transcript or participant state. */
final class ConversationSessionManager {
    private final Map<UUID, Session> sessions = new LinkedHashMap<>();
    private final Map<UUID, UUID> sessionByPlayer = new LinkedHashMap<>();
    private final Map<ResourceLocation, LinkedHashSet<UUID>> sessionIdsByDivine = new LinkedHashMap<>();

    Session create(Collection<AiDialogueModels.Participant> participants, AiDialogueModels.LocationSnapshot location,
            int historyLimit) {
        return create(participants, location, historyLimit, null);
    }

    /** The interaction ID is supplied by the game interaction system; this manager never creates one. */
    Session create(Collection<AiDialogueModels.Participant> participants, AiDialogueModels.LocationSnapshot location,
            int historyLimit, UUID interactionId) {
        Session session = new Session(UUID.randomUUID(), interactionId, participants, location, historyLimit);
        sessions.put(session.id(), session);
        for (AiDialogueModels.Participant participant : session.participants()) {
            if (participant.kind() == AiDialogueModels.ParticipantKind.PLAYER
                    && participant.state() != AiDialogueModels.ParticipantState.OUTSIDE) {
                bindVisiblePlayer(session.id(), participant.playerId());
            }
            if (participant.kind() == AiDialogueModels.ParticipantKind.DIVINE && participant.godId() != null) {
                bindDivine(session.id(), participant.godId());
            }
        }
        return session;
    }

    Optional<Session> findByPlayer(UUID playerId) {
        UUID sessionId = sessionByPlayer.get(playerId);
        return sessionId == null ? Optional.empty() : Optional.ofNullable(sessions.get(sessionId));
    }

    Optional<Session> find(UUID sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    /** Read-only occupancy lookup used by the outer service to enforce per-NPC conversation policy. */
    List<Session> findByDivine(ResourceLocation godId) {
        LinkedHashSet<UUID> sessionIds = sessionIdsByDivine.get(godId);
        if (sessionIds == null || sessionIds.isEmpty()) {
            return List.of();
        }
        return sessionIds.stream().map(sessions::get).filter(java.util.Objects::nonNull).toList();
    }

    LeaveResult leave(UUID playerId) {
        UUID sessionId = sessionByPlayer.remove(playerId);
        if (sessionId == null) {
            return LeaveResult.notFound();
        }
        return removePlayer(sessionId, playerId);
    }

    StateChangeResult setPlayerState(UUID sessionId, UUID playerId, AiDialogueModels.ParticipantState state) {
        Session session = sessions.get(sessionId);
        if (session == null) {
            return StateChangeResult.notFound();
        }
        session.setPlayerState(playerId, state);
        if (state == AiDialogueModels.ParticipantState.OUTSIDE) {
            sessionByPlayer.remove(playerId, sessionId);
            if (!session.hasVisiblePlayers()) {
                close(sessionId);
                return StateChangeResult.closed(session);
            }
        } else {
            bindVisiblePlayer(sessionId, playerId);
        }
        return StateChangeResult.changed(session);
    }

    boolean addOrUpdatePlayer(UUID sessionId, AiDialogueModels.Participant participant) {
        if (participant.kind() != AiDialogueModels.ParticipantKind.PLAYER || participant.playerId() == null) {
            throw new IllegalArgumentException("Only a player participant can be added as a player");
        }
        Session session = sessions.get(sessionId);
        if (session == null) {
            return false;
        }
        session.addOrUpdateParticipant(participant);
        if (participant.state() == AiDialogueModels.ParticipantState.OUTSIDE) {
            sessionByPlayer.remove(participant.playerId(), sessionId);
        } else {
            bindVisiblePlayer(sessionId, participant.playerId());
        }
        return true;
    }

    boolean addOrUpdateDivine(UUID sessionId, AiDialogueModels.Participant participant) {
        if (participant.kind() != AiDialogueModels.ParticipantKind.DIVINE) {
            throw new IllegalArgumentException("Only a divine participant can be added as divine");
        }
        Session session = sessions.get(sessionId);
        if (session == null) {
            return false;
        }
        session.addOrUpdateParticipant(participant);
        if (participant.godId() != null) {
            bindDivine(sessionId, participant.godId());
        }
        return true;
    }

    void close(UUID sessionId) {
        Session removed = sessions.remove(sessionId);
        if (removed == null) {
            return;
        }
        removed.invalidateAllTurns();
        for (AiDialogueModels.Participant participant : removed.participants()) {
            if (participant.kind() == AiDialogueModels.ParticipantKind.PLAYER) {
                sessionByPlayer.remove(participant.playerId(), sessionId);
            } else if (participant.kind() == AiDialogueModels.ParticipantKind.DIVINE && participant.godId() != null) {
                unbindDivine(sessionId, participant.godId());
            }
        }
    }

    List<Session> allSessions() {
        return List.copyOf(sessions.values());
    }

    private LeaveResult removePlayer(UUID sessionId, UUID playerId) {
        Session session = sessions.get(sessionId);
        if (session == null) {
            return LeaveResult.notFound();
        }
        session.setPlayerState(playerId, AiDialogueModels.ParticipantState.OUTSIDE);
        if (!session.hasVisiblePlayers()) {
            close(sessionId);
            return LeaveResult.closed(session);
        }
        return LeaveResult.left(session);
    }

    private void bindVisiblePlayer(UUID sessionId, UUID playerId) {
        UUID previous = sessionByPlayer.put(playerId, sessionId);
        if (previous != null && !previous.equals(sessionId)) {
            removePlayer(previous, playerId);
        }
    }

    private void bindDivine(UUID sessionId, ResourceLocation godId) {
        sessionIdsByDivine.computeIfAbsent(godId, ignored -> new LinkedHashSet<>()).add(sessionId);
    }

    private void unbindDivine(UUID sessionId, ResourceLocation godId) {
        LinkedHashSet<UUID> sessionIds = sessionIdsByDivine.get(godId);
        if (sessionIds == null) {
            return;
        }
        sessionIds.remove(sessionId);
        if (sessionIds.isEmpty()) {
            sessionIdsByDivine.remove(godId);
        }
    }

    record LeaveResult(Optional<Session> session, boolean existed, boolean closed) {
        static LeaveResult notFound() {
            return new LeaveResult(Optional.empty(), false, false);
        }

        static LeaveResult left(Session session) {
            return new LeaveResult(Optional.of(session), true, false);
        }

        static LeaveResult closed(Session session) {
            return new LeaveResult(Optional.of(session), true, true);
        }
    }

    record StateChangeResult(Optional<Session> session, boolean existed, boolean closed) {
        static StateChangeResult notFound() {
            return new StateChangeResult(Optional.empty(), false, false);
        }

        static StateChangeResult changed(Session session) {
            return new StateChangeResult(Optional.of(session), true, false);
        }

        static StateChangeResult closed(Session session) {
            return new StateChangeResult(Optional.of(session), true, true);
        }
    }

    static final class Session {
        private final UUID id;
        private final UUID interactionId;
        private final Instant createdAt;
        private final int historyLimit;
        private final Map<String, AiDialogueModels.Participant> participants = new LinkedHashMap<>();
        private final Deque<AiDialogueModels.ConversationTurn> history = new ArrayDeque<>();
        private final Deque<QueuedPlayerTurn> pendingTurns = new ArrayDeque<>();
        private AiDialogueModels.LocationSnapshot location;
        private String currentTopic = "";
        private String pendingInteraction = "";
        private PendingVouchInteraction pendingVouch;
        private long nextTurnId = 1L;
        private long generation = 1L;
        private QueuedPlayerTurn activeTurn;

        private Session(UUID id, UUID interactionId, Collection<AiDialogueModels.Participant> initialParticipants,
                AiDialogueModels.LocationSnapshot location, int historyLimit) {
            this.id = id;
            this.interactionId = interactionId;
            this.createdAt = Instant.now();
            this.location = location;
            this.historyLimit = historyLimit;
            for (AiDialogueModels.Participant participant : initialParticipants) {
                if (participants.putIfAbsent(participant.participantId(), participant) != null) {
                    throw new IllegalArgumentException("Duplicate conversation participant " + participant.participantId());
                }
            }
            if (participants.values().stream().noneMatch(participant -> participant.kind()
                    == AiDialogueModels.ParticipantKind.PLAYER)) {
                throw new IllegalArgumentException("A conversation session requires at least one player");
            }
            if (participants.values().stream().noneMatch(participant -> participant.kind()
                    == AiDialogueModels.ParticipantKind.DIVINE)) {
                throw new IllegalArgumentException("A conversation session requires at least one divine participant");
            }
        }

        UUID id() {
            return id;
        }

        List<AiDialogueModels.Participant> participants() {
            return List.copyOf(participants.values());
        }

        Optional<AiDialogueModels.Participant> participant(String participantId) {
            return Optional.ofNullable(participants.get(participantId));
        }

        Optional<AiDialogueModels.Participant> player(UUID playerId) {
            return participants.values().stream().filter(participant -> participant.kind()
                    == AiDialogueModels.ParticipantKind.PLAYER && playerId.equals(participant.playerId())).findFirst();
        }

        void setPlayerState(UUID playerId, AiDialogueModels.ParticipantState state) {
            AiDialogueModels.Participant participant = player(playerId)
                    .orElseThrow(() -> new IllegalArgumentException("Player is not in this conversation"));
            participants.put(participant.participantId(), participant.withState(state));
            if (state == AiDialogueModels.ParticipantState.OUTSIDE) {
                discardTurnsFor(playerId);
                clearPendingVouchFor(playerId);
            }
        }

        void addOrUpdateParticipant(AiDialogueModels.Participant participant) {
            participants.put(participant.participantId(), participant);
        }

        boolean promoteListener(UUID playerId) {
            AiDialogueModels.Participant participant = player(playerId).orElse(null);
            if (participant == null || participant.state() == AiDialogueModels.ParticipantState.OUTSIDE) {
                return false;
            }
            if (participant.state() == AiDialogueModels.ParticipantState.LISTENER) {
                participants.put(participant.participantId(), participant.withState(AiDialogueModels.ParticipantState.ACTIVE));
            }
            return true;
        }

        TurnQueueResult enqueuePlayerTurn(UUID playerId, String speakerId, String displayName, String text) {
            AiDialogueModels.Participant participant = player(playerId)
                    .orElseThrow(() -> new IllegalArgumentException("Player is not in this conversation"));
            if (participant.state() != AiDialogueModels.ParticipantState.ACTIVE) {
                throw new IllegalStateException("Only ACTIVE players may queue a conversation turn");
            }
            TurnToken token = new TurnToken(nextTurnId++, generation);
            pendingTurns.addLast(new QueuedPlayerTurn(token, playerId, requireText(speakerId, "speakerId"),
                    requireText(displayName, "displayName"), requireText(text, "text"), Instant.now()));
            return new TurnQueueResult(token, pendingTurns.size(), activeTurn != null);
        }

        Optional<TurnStart> beginNextTurn() {
            if (activeTurn != null) {
                return Optional.empty();
            }
            while (!pendingTurns.isEmpty()) {
                QueuedPlayerTurn queued = pendingTurns.removeFirst();
                AiDialogueModels.Participant participant = player(queued.playerId()).orElse(null);
                if (participant == null || participant.state() != AiDialogueModels.ParticipantState.ACTIVE) {
                    continue;
                }
                activeTurn = queued;
                return Optional.of(new TurnStart(queued.token(), queued));
            }
            return Optional.empty();
        }

        boolean completeTurn(TurnToken token) {
            if (token == null || activeTurn == null || !token.equals(activeTurn.token())) {
                return false;
            }
            activeTurn = null;
            return true;
        }

        boolean isCurrentTurn(TurnToken token) {
            return token != null && activeTurn != null && token.equals(activeTurn.token());
        }

        int queuedTurnCount() {
            return pendingTurns.size();
        }

        private void discardTurnsFor(UUID playerId) {
            pendingTurns.removeIf(turn -> turn.playerId().equals(playerId));
            if (activeTurn != null && activeTurn.playerId().equals(playerId)) {
                activeTurn = null;
                generation++;
            }
        }

        private void invalidateAllTurns() {
            pendingTurns.clear();
            activeTurn = null;
            generation++;
        }

        void append(AiDialogueModels.ConversationTurn turn) {
            history.addLast(turn);
            while (history.size() > historyLimit) {
                history.removeFirst();
            }
        }

        void setCurrentTopic(String topic) {
            currentTopic = topic == null ? "" : topic.trim();
        }

        void setPendingInteraction(String pending) {
            pendingInteraction = pending == null ? "" : pending.trim();
        }

        /**
         * Starts one AI-owned social interaction. It is intentionally independent from the game proposal gateway:
         * asking a trusted player for an opinion changes no game state.
         */
        boolean beginPendingVouch(PendingVouchInteraction interaction) {
            expirePendingVouch();
            if (pendingVouch != null || interaction == null || !isActivePlayer(interaction.sponsorPlayerId())
                    || !isActivePlayer(interaction.beneficiaryPlayerId()) || !hasPresentDivine(interaction.npcId())) {
                return false;
            }
            pendingVouch = interaction;
            pendingInteraction = interaction.description();
            return true;
        }

        Optional<PendingVouchInteraction> pendingVouch() {
            expirePendingVouch();
            return Optional.ofNullable(pendingVouch);
        }

        /** Clears a matching pending request after its named sponsor has supplied a structured interpretation. */
        boolean resolvePendingVouch(VouchResolutionProposal resolution) {
            PendingVouchInteraction current = pendingVouch().orElse(null);
            if (current == null || resolution == null || !current.npcId().equals(resolution.npcId())
                    || !current.sponsorPlayerId().equals(resolution.sponsorPlayerId())
                    || !current.beneficiaryPlayerId().equals(resolution.beneficiaryPlayerId())) {
                return false;
            }
            if (resolution.stance() == com.sande.mythictrpg.ai.vouch.VouchStance.UNCLEAR) {
                return true;
            }
            pendingVouch = null;
            pendingInteraction = "";
            return true;
        }

        Set<String> visiblePlayerParticipantIds() {
            Set<String> ids = new LinkedHashSet<>();
            for (AiDialogueModels.Participant participant : participants.values()) {
                if (participant.kind() == AiDialogueModels.ParticipantKind.PLAYER
                        && participant.state() != AiDialogueModels.ParticipantState.OUTSIDE) {
                    ids.add(participant.participantId());
                }
            }
            return Set.copyOf(ids);
        }

        boolean hasVisiblePlayers() {
            return !visiblePlayerParticipantIds().isEmpty();
        }

        AiDialogueModels.SessionSnapshot snapshot(int maxHistory) {
            expirePendingVouch();
            List<AiDialogueModels.ConversationTurn> turns = new ArrayList<>(history);
            int from = Math.max(0, turns.size() - maxHistory);
            return new AiDialogueModels.SessionSnapshot(id, java.util.Optional.ofNullable(interactionId), participants(), location, createdAt,
                    turns.subList(from, turns.size()), currentTopic, pendingInteraction, activeTurn != null);
        }

        private boolean isActivePlayer(UUID playerId) {
            return player(playerId).map(participant -> participant.state() == AiDialogueModels.ParticipantState.ACTIVE)
                    .orElse(false);
        }

        private boolean hasPresentDivine(ResourceLocation godId) {
            return participants.values().stream().anyMatch(participant -> participant.kind()
                    == AiDialogueModels.ParticipantKind.DIVINE && godId.equals(participant.godId()));
        }

        private void clearPendingVouchFor(UUID playerId) {
            if (pendingVouch != null && (pendingVouch.sponsorPlayerId().equals(playerId)
                    || pendingVouch.beneficiaryPlayerId().equals(playerId))) {
                pendingVouch = null;
                pendingInteraction = "";
            }
        }

        private void expirePendingVouch() {
            if (pendingVouch != null && pendingVouch.expiredAt(Instant.now())) {
                pendingVouch = null;
                pendingInteraction = "";
            }
        }

        record TurnToken(long turnId, long generation) {
        }

        record QueuedPlayerTurn(TurnToken token, UUID playerId, String speakerId, String displayName, String text,
                Instant queuedAt) {
        }

        record TurnStart(TurnToken token, QueuedPlayerTurn queuedTurn) {
        }

        record TurnQueueResult(TurnToken token, int queuedTurnCount, boolean requestInFlight) {
        }

        private static String requireText(String value, String name) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
            return value.trim();
        }
    }

    static AiDialogueModels.Participant player(String displayName, UUID playerId,
            AiDialogueModels.ParticipantState state) {
        return new AiDialogueModels.Participant("player:" + playerId, AiDialogueModels.ParticipantKind.PLAYER,
                displayName, playerId, null, state);
    }

    static AiDialogueModels.Participant divine(ResourceLocation godId, String displayName) {
        return new AiDialogueModels.Participant("divine:" + godId, AiDialogueModels.ParticipantKind.DIVINE,
                displayName, null, godId, AiDialogueModels.ParticipantState.ACTIVE);
    }
}
