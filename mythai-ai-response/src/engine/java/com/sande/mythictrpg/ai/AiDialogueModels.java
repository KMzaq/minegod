package com.sande.mythictrpg.ai;

import com.sande.mythictrpg.ai.relationship.CurrentEmotion;
import com.sande.mythictrpg.ai.relationship.RelationshipMetrics;
import com.sande.mythictrpg.ai.memory.MemorySnippet;
import com.sande.mythictrpg.ai.knowledge.KnowledgeSnippet;
import com.sande.mythictrpg.ai.example.DialogueExampleSnippet;
import com.sande.mythictrpg.ai.example.TagDialogueGuidance;
import com.sande.mythictrpg.ai.tone.PlayerToneResponseGuidance;
import com.sande.mythictrpg.ai.proposal.AiGameProposal;
import com.sande.mythictrpg.ai.proposal.QuestRewardContext;
import com.sande.mythictrpg.ai.reaction.ReactionGuidelineSnippet;
import com.sande.mythictrpg.ai.vouch.PendingVouchInteraction;
import com.sande.mythictrpg.ai.voice.VoiceStyleProfile;
import com.sande.mythictrpg.ai.intent.ConversationIntent;
import net.minecraft.resources.ResourceLocation;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Immutable transport and snapshot values shared by the AI conversation module. */
public final class AiDialogueModels {
    private AiDialogueModels() {
    }

    public enum ParticipantKind {
        PLAYER,
        DIVINE
    }

    /** A participant may speak, hear only, or be entirely excluded from a session. */
    public enum ParticipantState {
        ACTIVE,
        LISTENER,
        OUTSIDE
    }

    public record Participant(String participantId, ParticipantKind kind, String displayName, UUID playerId,
            ResourceLocation godId, ParticipantState state) {
        public Participant {
            participantId = requireText(participantId, "participantId");
            Objects.requireNonNull(kind, "kind");
            displayName = requireText(displayName, "displayName");
            Objects.requireNonNull(state, "state");
            if (kind == ParticipantKind.PLAYER && playerId == null) {
                throw new IllegalArgumentException("A player participant requires playerId");
            }
            if (kind == ParticipantKind.DIVINE && godId == null) {
                throw new IllegalArgumentException("A divine participant requires godId");
            }
            if (kind == ParticipantKind.PLAYER && godId != null) {
                throw new IllegalArgumentException("A player participant must not contain godId");
            }
            if (kind == ParticipantKind.DIVINE && playerId != null) {
                throw new IllegalArgumentException("A divine participant must not contain playerId");
            }
        }

        public Participant withState(ParticipantState changedState) {
            return new Participant(participantId, kind, displayName, playerId, godId, changedState);
        }
    }

    public record LocationSnapshot(String dimension, int x, int y, int z) {
        public LocationSnapshot {
            dimension = requireText(dimension, "dimension");
        }
    }

    public record ConversationTurn(UUID messageId, Instant createdAt, String speakerId, String text,
            List<String> listenerIds) {
        public ConversationTurn {
            Objects.requireNonNull(messageId, "messageId");
            Objects.requireNonNull(createdAt, "createdAt");
            speakerId = requireText(speakerId, "speakerId");
            text = requireText(text, "text");
            listenerIds = List.copyOf(Objects.requireNonNull(listenerIds, "listenerIds"));
        }
    }

    /** The optional interaction ID is a game-owned reference, not an AI-generated interaction record. */
    public record SessionSnapshot(UUID sessionId, java.util.Optional<UUID> interactionId, List<Participant> participants, LocationSnapshot location,
            Instant createdAt, List<ConversationTurn> history, String currentTopic, String pendingInteraction,
            boolean requestInFlight) {
        public SessionSnapshot {
            Objects.requireNonNull(sessionId, "sessionId");
            interactionId = interactionId == null ? java.util.Optional.empty() : interactionId;
            participants = List.copyOf(Objects.requireNonNull(participants, "participants"));
            Objects.requireNonNull(location, "location");
            Objects.requireNonNull(createdAt, "createdAt");
            history = List.copyOf(Objects.requireNonNull(history, "history"));
            currentTopic = currentTopic == null ? "" : currentTopic;
            pendingInteraction = pendingInteraction == null ? "" : pendingInteraction;
        }

        public List<Participant> visibleParticipants() {
            return participants.stream().filter(participant -> participant.state() != ParticipantState.OUTSIDE).toList();
        }

        /** Compatibility constructor for sessions created without an Interaction/Encounter source. */
        public SessionSnapshot(UUID sessionId, List<Participant> participants, LocationSnapshot location,
                Instant createdAt, List<ConversationTurn> history, String currentTopic, String pendingInteraction,
                boolean requestInFlight) {
            this(sessionId, java.util.Optional.empty(), participants, location, createdAt, history, currentTopic,
                    pendingInteraction, requestInFlight);
        }
    }

    public record GodPersona(String displayName, String systemPrompt, String background, List<String> knowledge,
            List<String> examples) {
        public GodPersona {
            displayName = requireText(displayName, "displayName");
            systemPrompt = requireText(systemPrompt, "systemPrompt");
            background = background == null ? "" : background.trim();
            knowledge = immutableTexts(knowledge);
            examples = immutableTexts(examples);
        }
    }

    /** Prompt-safe explanation of one player's independent relationship and current emotion with one god. */
    public record RelationshipContext(RelationshipMetrics metrics, CurrentEmotion emotion,
            List<String> derivedTags, List<String> interpretation) {
        public RelationshipContext {
            Objects.requireNonNull(metrics, "metrics");
            Objects.requireNonNull(emotion, "emotion");
            derivedTags = immutableTexts(derivedTags);
            interpretation = immutableTexts(interpretation);
        }
    }

    /** Server-thread snapshot only; no Minecraft runtime object may cross into an LLM worker thread. */
    public record ConversationContext(SessionSnapshot session, String triggeringParticipantId,
            Map<String, GodPersona> personasByParticipantId,
            Map<String, Map<String, RelationshipContext>> relationshipsByPlayerParticipantId,
            List<String> memories, Map<String, List<MemorySnippet>> memoriesByDivineParticipantId,
            Map<String, List<KnowledgeSnippet>> knowledgeByDivineParticipantId,
            Map<String, List<DialogueExampleSnippet>> dialogueExamplesByDivineParticipantId,
            Map<String, List<ReactionGuidelineSnippet>> reactionGuidelinesByDivineParticipantId,
            Map<String, List<VoiceStyleProfile>> voiceStylesByDivineParticipantId,
            Map<String, List<TagDialogueGuidance>> tagGuidanceByDivineParticipantId,
            Map<String, PlayerToneResponseGuidance> playerToneResponseByDivineParticipantId,
            Map<String, Object> gameState,
            QuestRewardContext questRewardContext,
            java.util.Optional<PendingVouchInteraction> pendingVouchInteraction,
            List<String> allowedProposalTypes,
            ConversationIntent intent) {
        public ConversationContext {
            Objects.requireNonNull(session, "session");
            triggeringParticipantId = requireText(triggeringParticipantId, "triggeringParticipantId");
            personasByParticipantId = Map.copyOf(Objects.requireNonNull(personasByParticipantId,
                    "personasByParticipantId"));
            relationshipsByPlayerParticipantId = immutableNestedRelationships(relationshipsByPlayerParticipantId);
            memories = immutableTexts(memories);
            memoriesByDivineParticipantId = immutableNestedLists(memoriesByDivineParticipantId,
                    "memoriesByDivineParticipantId");
            knowledgeByDivineParticipantId = immutableNestedLists(knowledgeByDivineParticipantId,
                    "knowledgeByDivineParticipantId");
            dialogueExamplesByDivineParticipantId = immutableNestedLists(dialogueExamplesByDivineParticipantId,
                    "dialogueExamplesByDivineParticipantId");
            reactionGuidelinesByDivineParticipantId = immutableNestedLists(reactionGuidelinesByDivineParticipantId,
                    "reactionGuidelinesByDivineParticipantId");
            voiceStylesByDivineParticipantId = immutableNestedLists(voiceStylesByDivineParticipantId,
                    "voiceStylesByDivineParticipantId");
            tagGuidanceByDivineParticipantId = immutableNestedLists(tagGuidanceByDivineParticipantId,
                    "tagGuidanceByDivineParticipantId");
            playerToneResponseByDivineParticipantId = Map.copyOf(Objects.requireNonNull(
                    playerToneResponseByDivineParticipantId, "playerToneResponseByDivineParticipantId"));
            gameState = Map.copyOf(new LinkedHashMap<>(Objects.requireNonNull(gameState, "gameState")));
            questRewardContext = questRewardContext == null ? QuestRewardContext.safeDefaults() : questRewardContext;
            pendingVouchInteraction = pendingVouchInteraction == null ? java.util.Optional.empty()
                    : pendingVouchInteraction;
            allowedProposalTypes = immutableTexts(allowedProposalTypes);
            intent = intent == null ? ConversationIntent.heuristicFallback() : intent;
        }

        /** Compatibility constructor for full contexts created before pending social state was captured. */
        public ConversationContext(SessionSnapshot session, String triggeringParticipantId,
                Map<String, GodPersona> personasByParticipantId,
                Map<String, Map<String, RelationshipContext>> relationshipsByPlayerParticipantId,
                List<String> memories, Map<String, List<MemorySnippet>> memoriesByDivineParticipantId,
                Map<String, List<KnowledgeSnippet>> knowledgeByDivineParticipantId,
                Map<String, List<DialogueExampleSnippet>> dialogueExamplesByDivineParticipantId,
                Map<String, Object> gameState, QuestRewardContext questRewardContext,
                List<String> allowedProposalTypes) {
            this(session, triggeringParticipantId, personasByParticipantId, relationshipsByPlayerParticipantId,
                    memories, memoriesByDivineParticipantId, knowledgeByDivineParticipantId,
                    dialogueExamplesByDivineParticipantId, Map.of(), Map.of(), Map.of(), Map.of(), gameState,
                    questRewardContext,
                    java.util.Optional.empty(), allowedProposalTypes, ConversationIntent.heuristicFallback());
        }

        /** Compatibility constructor for callers that do not yet supply retrieval-engine context. */
        public ConversationContext(SessionSnapshot session, String triggeringParticipantId,
                Map<String, GodPersona> personasByParticipantId,
                Map<String, Map<String, RelationshipContext>> relationshipsByPlayerParticipantId,
                List<String> memories, Map<String, Object> gameState, List<String> allowedProposalTypes) {
            this(session, triggeringParticipantId, personasByParticipantId, relationshipsByPlayerParticipantId,
                    memories, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), gameState,
                    QuestRewardContext.safeDefaults(),
                    java.util.Optional.empty(), allowedProposalTypes, ConversationIntent.heuristicFallback());
        }
    }

    public record OllamaMessage(String role, String content) {
        public OllamaMessage {
            role = requireText(role, "role");
            content = requireText(content, "content");
        }
    }

    /** Parsed model output. Proposals are data only and have no game-side authority. */
    public record StructuredAiResult(List<Speech> speech, String currentTopic, List<Proposal> proposals,
            String currentEmotion) {
        /** Existing non-room producers need not supply an emotional interpretation. */
        public StructuredAiResult(List<Speech> speech, String currentTopic, List<Proposal> proposals) {
            this(speech, currentTopic, proposals, "");
        }
        public StructuredAiResult {
            speech = speech == null ? List.of() : List.copyOf(speech);
            currentTopic = currentTopic == null ? "" : currentTopic.trim();
            proposals = proposals == null ? List.of() : List.copyOf(proposals);
            currentEmotion = RoomEmotionState.normalize(currentEmotion);
        }

        public static StructuredAiResult empty() {
            return new StructuredAiResult(List.of(), "", List.of());
        }
    }

    public record Speech(String speakerId, String text, List<String> audienceParticipantIds) {
        public Speech {
            speakerId = speakerId == null ? "" : speakerId.trim();
            text = text == null ? "" : text.trim();
            audienceParticipantIds = audienceParticipantIds == null ? List.of() : List.copyOf(audienceParticipantIds);
        }
    }

    public record Proposal(String type, String title, String summary, List<String> targetParticipantIds,
            Map<String, String> parameters) {
        public Proposal {
            type = type == null ? "" : type.trim();
            title = title == null ? "" : title.trim();
            summary = summary == null ? "" : summary.trim();
            targetParticipantIds = targetParticipantIds == null ? List.of() : List.copyOf(targetParticipantIds);
            parameters = parameters == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(parameters));
        }
    }

    public record ProposalEnvelope(UUID sessionId, Proposal proposal, SessionSnapshot session,
            ConversationContext context, java.util.Optional<AiGameProposal> typedProposal) {
        public ProposalEnvelope {
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(proposal, "proposal");
            Objects.requireNonNull(session, "session");
            Objects.requireNonNull(context, "context");
            typedProposal = typedProposal == null ? java.util.Optional.empty() : typedProposal;
        }

        /** Compatibility constructor for existing non-typed proposal integrations. */
        public ProposalEnvelope(UUID sessionId, Proposal proposal, SessionSnapshot session, ConversationContext context) {
            this(sessionId, proposal, session, context, java.util.Optional.empty());
        }
    }

    private static List<String> immutableTexts(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream().filter(Objects::nonNull).map(String::trim).filter(value -> !value.isEmpty()).toList();
    }

    private static Map<String, Map<String, RelationshipContext>> immutableNestedRelationships(
            Map<String, Map<String, RelationshipContext>> relationships) {
        Objects.requireNonNull(relationships, "relationshipsByPlayerParticipantId");
        Map<String, Map<String, RelationshipContext>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, RelationshipContext>> entry : relationships.entrySet()) {
            copy.put(requireText(entry.getKey(), "relationship player participant ID"),
                    Map.copyOf(Objects.requireNonNull(entry.getValue(), "relationship map")));
        }
        return Map.copyOf(copy);
    }

    private static <T> Map<String, List<T>> immutableNestedLists(Map<String, List<T>> values, String name) {
        Objects.requireNonNull(values, name);
        Map<String, List<T>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<T>> entry : values.entrySet()) {
            copy.put(requireText(entry.getKey(), name + " key"), List.copyOf(Objects.requireNonNull(entry.getValue(),
                    name + " value")));
        }
        return Map.copyOf(copy);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
