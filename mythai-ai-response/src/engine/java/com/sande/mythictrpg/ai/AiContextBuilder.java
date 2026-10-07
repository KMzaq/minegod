package com.sande.mythictrpg.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sande.mythictrpg.ai.context.GameConversationSnapshot;
import com.sande.mythictrpg.ai.context.ConversationTurnContextSnapshot;
import com.sande.mythictrpg.ai.relationship.EmotionSnapshot;
import com.sande.mythictrpg.ai.relationship.RelationshipAxes;
import com.sande.mythictrpg.ai.relationship.RelationshipMetrics;
import com.sande.mythictrpg.ai.relationship.RelationshipSnapshot;
import com.sande.mythictrpg.ai.agent.NpcAgent;
import com.sande.mythictrpg.ai.agent.NpcAgentRepository;
import com.sande.mythictrpg.ai.example.DialogueExampleContextResolver;
import com.sande.mythictrpg.ai.example.DialogueExampleRetriever;
import com.sande.mythictrpg.ai.example.DialogueExampleSnippet;
import com.sande.mythictrpg.ai.example.ExampleRetrievalQuery;
import com.sande.mythictrpg.ai.example.JsonDialogueExampleRepository;
import com.sande.mythictrpg.ai.example.WeightedExampleStyleContext;
import com.sande.mythictrpg.ai.example.WeightedTagDialogueExampleRetriever;
import com.sande.mythictrpg.ai.example.JsonTagDialogueGuidanceRepository;
import com.sande.mythictrpg.ai.example.TagDialogueGuidance;
import com.sande.mythictrpg.ai.example.TagDialogueGuidanceResolver;
import com.sande.mythictrpg.ai.tone.NpcSocialAuthorityContext;
import com.sande.mythictrpg.ai.tone.PlayerToneResponseGuidance;
import com.sande.mythictrpg.ai.tone.PlayerToneResponseGuidanceResolver;
import com.sande.mythictrpg.ai.intent.ConversationIntent;
import com.sande.mythictrpg.ai.tag.NpcCharacterTagRepository;
import com.sande.mythictrpg.ai.memory.MemoryQuery;
import com.sande.mythictrpg.ai.memory.MemorySnippet;
import com.sande.mythictrpg.ai.memory.NpcMemoryEngine;
import com.sande.mythictrpg.ai.proposal.QuestProposal;
import com.sande.mythictrpg.ai.proposal.RequestJudgmentProposal;
import com.sande.mythictrpg.ai.proposal.RewardProposal;
import com.sande.mythictrpg.ai.proposal.VouchResolutionProposal;
import com.sande.mythictrpg.ai.relationship.RelationshipChangeProposal;
import com.sande.mythictrpg.ai.vouch.PendingVouchInteraction;
import com.sande.mythictrpg.ai.reaction.MinecraftWeather;
import com.sande.mythictrpg.ai.reaction.ReactionGuidelineMatch;
import com.sande.mythictrpg.ai.reaction.ReactionGuidelineRepository;
import com.sande.mythictrpg.ai.reaction.ReactionGuidelineRetriever;
import com.sande.mythictrpg.ai.reaction.ReactionGuidelineSnippet;
import com.sande.mythictrpg.ai.reaction.SituationContext;
import com.sande.mythictrpg.ai.relationship.RelationshipTag;
import com.sande.mythictrpg.ai.knowledge.KnowledgeAccessContext;
import com.sande.mythictrpg.ai.knowledge.KnowledgeAudienceMember;
import com.sande.mythictrpg.ai.knowledge.KnowledgeAudienceState;
import com.sande.mythictrpg.ai.knowledge.KnowledgeEngine;
import com.sande.mythictrpg.ai.knowledge.KnowledgeQuery;
import com.sande.mythictrpg.ai.knowledge.KnowledgeSnippet;
import com.sande.mythictrpg.ai.voice.JsonVoiceStyleRepository;
import com.sande.mythictrpg.ai.voice.VoiceStyleProfile;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds an immutable, bounded LLM context from game-owned snapshots. */
final class AiContextBuilder {
    private static final Gson GSON = new GsonBuilder().create();
    private static final List<String> PROPOSAL_TYPES = List.of(QuestProposal.TYPE, RewardProposal.TYPE,
            RelationshipChangeProposal.TYPE, "vouch_request", RequestJudgmentProposal.TYPE);
    private final DialogueExampleRetriever dialogueExampleRetriever = new WeightedTagDialogueExampleRetriever(
            JsonDialogueExampleRepository.INSTANCE);
    private final DialogueExampleContextResolver dialogueExampleContextResolver = new DialogueExampleContextResolver();
    private final TagDialogueGuidanceResolver tagDialogueGuidanceResolver = new TagDialogueGuidanceResolver(
            JsonTagDialogueGuidanceRepository.INSTANCE);
    private final PlayerToneResponseGuidanceResolver playerToneResponseGuidanceResolver =
            new PlayerToneResponseGuidanceResolver();
    private final ReactionGuidelineRetriever reactionGuidelineRetriever = new ReactionGuidelineRetriever(
            ReactionGuidelineRepository.INSTANCE);

    AiDialogueModels.ConversationContext build(AiDialogueModels.SessionSnapshot snapshot,
            String triggeringParticipantId, GameConversationSnapshot gameSnapshot, int exampleRetrievalLimit) {
        return build(snapshot, triggeringParticipantId, gameSnapshot, exampleRetrievalLimit, 3, 3);
    }

    private AiDialogueModels.ConversationContext build(AiDialogueModels.SessionSnapshot snapshot,
            String triggeringParticipantId, GameConversationSnapshot gameSnapshot, int exampleRetrievalLimit,
            int maximumMemories, int maximumKnowledge) {
        return build(snapshot, triggeringParticipantId, gameSnapshot, exampleRetrievalLimit, maximumMemories,
                maximumKnowledge, 3, java.util.Optional.empty(), ConversationIntent.heuristicFallback());
    }

    private AiDialogueModels.ConversationContext build(AiDialogueModels.SessionSnapshot snapshot,
            String triggeringParticipantId, GameConversationSnapshot gameSnapshot, int exampleRetrievalLimit,
            int maximumMemories, int maximumKnowledge, int maximumReactionGuidelines,
            java.util.Optional<PendingVouchInteraction> pendingVouchInteraction, ConversationIntent intent) {
        Map<String, AiDialogueModels.GodPersona> personas = new LinkedHashMap<>();
        for (AiDialogueModels.Participant participant : snapshot.participants()) {
            if (participant.kind() == AiDialogueModels.ParticipantKind.DIVINE) {
                personaFor(participant).ifPresent(persona ->
                        personas.put(participant.participantId(), persona));
            }
        }
        Map<String, Map<String, AiDialogueModels.RelationshipContext>> relationships = gameSnapshot
                .relationshipsByPlayerParticipantId();
        String currentText = triggeringText(snapshot, triggeringParticipantId);
        Map<String, List<TagDialogueGuidance>> tagGuidance = relevantTagGuidance(snapshot, triggeringParticipantId,
                currentText, relationships, exampleRetrievalLimit, intent);
        Map<String, PlayerToneResponseGuidance> playerToneResponse = relevantPlayerToneResponses(snapshot,
                triggeringParticipantId, relationships, gameSnapshot.socialAuthorityByDivineParticipantId(), intent);
        return new AiDialogueModels.ConversationContext(snapshot, triggeringParticipantId, personas, relationships,
                List.of(), relevantMemories(snapshot, triggeringParticipantId, currentText, maximumMemories),
                relevantKnowledge(snapshot, triggeringParticipantId, currentText, relationships, maximumKnowledge,
                        intent),
                // Examples remain available to intent/retrieval code, but phase-2 generation never sees their wording.
                // Local models otherwise copy their objects and sentence shapes into unrelated live dialogue.
                Map.of(),
                relevantReactionGuidelines(snapshot, triggeringParticipantId, currentText, relationships,
                        gameSnapshot.gameState(), maximumReactionGuidelines),
                relevantVoiceStyles(snapshot),
                tagGuidance,
                playerToneResponse,
                gameSnapshot.gameState(), gameSnapshot.questRewardContext(), pendingVouchInteraction,
                allowedProposalTypes(pendingVouchInteraction, triggeringParticipantId), intent);
    }

    /** Builds prompt context from one immutable queued-turn snapshot, never from live session or game objects. */
    AiDialogueModels.ConversationContext build(ConversationTurnContextSnapshot turnSnapshot,
            int exampleRetrievalLimit) {
        return build(turnSnapshot.session(), turnSnapshot.triggeringParticipantId(), turnSnapshot.gameSnapshot(),
                exampleRetrievalLimit, 3, 3, 3, turnSnapshot.pendingVouchInteraction(),
                ConversationIntent.heuristicFallback());
    }

    /** Uses all bounded retrieval settings captured for the queued turn. */
    AiDialogueModels.ConversationContext build(ConversationTurnContextSnapshot turnSnapshot,
            AiDialogueConfig.Settings settings) {
        return build(turnSnapshot, settings, ConversationIntent.heuristicFallback());
    }

    /** Builds generation context after this exact queued turn has received an advisory routing result. */
    AiDialogueModels.ConversationContext build(ConversationTurnContextSnapshot turnSnapshot,
            AiDialogueConfig.Settings settings, ConversationIntent intent) {
        return build(turnSnapshot.session(), turnSnapshot.triggeringParticipantId(), turnSnapshot.gameSnapshot(),
                settings.exampleRetrievalLimit(), settings.maxRetrievedMemories(), settings.maxRetrievedKnowledge(),
                settings.maxSelectedReactionGuidelines(), turnSnapshot.pendingVouchInteraction(), intent);
    }

    List<AiDialogueModels.OllamaMessage> messages(AiDialogueModels.ConversationContext context,
            AiDialogueConfig.Settings settings) {
        DialogueTurnDirective directive = DialogueTurnDirective.plan(context.session(), context.triggeringParticipantId(),
                context.intent());
        List<AiDialogueModels.OllamaMessage> messages = new ArrayList<>();
        messages.add(new AiDialogueModels.OllamaMessage("system", systemInstruction(context, settings)));
        messages.add(new AiDialogueModels.OllamaMessage("user", generationInput(context, directive)));
        return List.copyOf(messages);
    }

    private String systemInstruction(AiDialogueModels.ConversationContext context,
            AiDialogueConfig.Settings settings) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("[ROLE]\nYou are the conversation orchestrator for a Minecraft RPG.\n\n")
                .append("[GLOBAL_HARD_RULES]\n")
                .append("Do not invent a place, item, person, event, action, or changing game state as a current fact. ")
                .append("Reply only with a valid JSON object, no markdown and no explanation outside JSON. ")
                .append("The JSON shape is {\"speech\":[{\"speakerId\":string,\"text\":string,\"audienceParticipantIds\":[string]}],")
                .append("\"currentTopic\":string,\"proposals\":[{\"type\":string,\"title\":string,\"summary\":string,")
                .append("\"targetParticipantIds\":[string],\"parameters\":{string:string}}]}. ")
                .append("Use only listed divine speaker IDs and participant IDs. Keep ordinary speech concise. ")
                .append("A proposal is a non-binding idea only: it never performs game actions, changes facts, awards items, ")
                .append("changes relationships, or creates quests. Never claim an unimplemented action succeeded. ")
                .append("The Minecraft game-state context is authoritative. ")
                .append(questRewardProposalInstruction()).append("\n\n")
                .append("[RESPONSE_PRIORITY]\n")
                .append("1. CURRENT_PLAYER_MESSAGE\n")
                .append("2. RECENT_CONVERSATION\n")
                .append("3. NPC_IDENTITY_AND_PERSONALITY\n")
                .append("4. RELATIONSHIP\n")
                .append("5. CURRENT_GAME_CONTEXT\n")
                .append("6. RESPONSE_INTENT\n")
                .append("7. SELECTED_DIALOGUE_GUIDELINES\n")
                .append("8. PERMITTED_WORLD_KNOWLEDGE\n\n")
                .append("[NATURAL_DIALOGUE_PREFERENCES]\n")
                .append("Respond as a present conversation partner to the current player message. Answer directly before adding flavor. ")
                .append("Do not add explanations, new topics, questions, analogies, lessons, jokes, or metaphors unless they genuinely help this reply. ")
                .append("Prefer a short, complete conversational response when that is enough. Do not repeat the previous NPC meaning or force a profile motif or guideline into every answer. ")
                .append("Natural conversation is more important than demonstrating every guideline. Treat guidelines as preferences, not mandatory content.\n\n");
        if (!context.intent().tags().isEmpty()) {
            prompt.append("INTERPRETED TURN INTENT (advisory retrieval routing, not a world fact): ")
                    .append(context.intent().tags()).append("; confidence=").append(context.intent().confidence())
                    .append(".\n\n");
        }
        if (context.intent().conversationAct() != com.sande.mythictrpg.ai.intent.ConversationAct.UNSPECIFIED) {
            prompt.append("INTERPRETED CONVERSATION ACT (how to continue the immediate exchange): ")
                    .append(context.intent().conversationAct()).append(". ")
                    .append(context.intent().conversationAct().replyGuidance()).append("\n\n");
        }
        if (!context.intent().playerToneTags().isEmpty()) {
            prompt.append("INTERPRETED PLAYER SPEECH TONE (advisory for this turn, not a persistent relationship fact): ")
                    .append(context.intent().playerToneTags()).append(".\n\n");
        }
        for (Map.Entry<String, AiDialogueModels.GodPersona> entry : context.personasByParticipantId().entrySet()) {
            AiDialogueModels.GodPersona persona = entry.getValue();
            prompt.append("[NPC_IDENTITY_AND_PERSONALITY: ").append(entry.getKey()).append("]\n")
                    .append("displayName: ").append(persona.displayName()).append("\n")
                    .append("identity and profile rules: ").append(persona.systemPrompt()).append("\n")
                    .append("background: ").append(persona.background()).append("\n");
            AiDialogueModels.Participant divine = context.session().participants().stream().filter(participant -> participant
                    .participantId().equals(entry.getKey())).findFirst().orElse(null);
            AiDialogueModels.RelationshipContext relationship = divine == null || divine.godId() == null
                    ? neutralRelationshipContext()
                    : context.relationshipsByPlayerParticipantId().getOrDefault(context.triggeringParticipantId(), Map.of())
                            .getOrDefault(divine.godId().toString(), neutralRelationshipContext());
            prompt.append("[RELATIONSHIP: ").append(entry.getKey()).append("]\n")
                    .append("derivedTags: ").append(relationship.derivedTags()).append("\n")
                    .append("interpretation: ").append(relationship.interpretation()).append("\n")
                    .append("emotion: ").append(relationship.emotion().intensities()).append("\n");
            List<VoiceStyleProfile> voiceStyles = context.voiceStylesByDivineParticipantId()
                    .getOrDefault(entry.getKey(), List.of());
            prompt.append("[SELECTED_DIALOGUE_GUIDELINES: ").append(entry.getKey())
                    .append("]\nBase profile guidance (writing guidance, not world facts): ")
                    .append(persona.systemPrompt()).append("\n");
            if (!voiceStyles.isEmpty()) {
                prompt.append("Voice style profiles (writing guidance, not world facts):\n");
                for (VoiceStyleProfile style : voiceStyles) {
                    prompt.append("- [").append(style.id()).append("] ")
                            .append(String.join("; ", style.guidance())).append("\n");
                }
            }
            List<TagDialogueGuidance> tagGuidance = context.tagGuidanceByDivineParticipantId()
                    .getOrDefault(entry.getKey(), List.of());
            if (!tagGuidance.isEmpty()) {
                prompt.append("Composed dialogue preferences (relationship and emotion modify the base voice rather than "
                        + "erase identity; apply only when they fit the live exchange):\n");
                for (TagDialogueGuidance guidance : tagGuidance) {
                    prompt.append("- [priority ").append(guidance.priority()).append(" / ")
                            .append(guidance.tag()).append("] ")
                            .append(String.join("; ", guidance.rules())).append("\n");
                }
            }
            PlayerToneResponseGuidance toneResponse = context.playerToneResponseByDivineParticipantId()
                    .get(entry.getKey());
            if (toneResponse != null && !toneResponse.toneTags().isEmpty()) {
                prompt.append("PLAYER SPEECH-TONE RESPONSE FOR THIS NPC ONLY (derived from live relationship and "
                        + "game-owned authority; do not claim any persistent relationship change happened):\n")
                        .append("- tones: ").append(toneResponse.toneTags())
                        .append(" | authority: ").append(toneResponse.socialAuthority().relativeAuthority())
                        .append(" | disposition: ").append(toneResponse.disposition()).append("\n");
                if (!toneResponse.socialAuthority().reasons().isEmpty()) {
                    prompt.append("- authority reasons: ")
                            .append(String.join("; ", toneResponse.socialAuthority().reasons())).append("\n");
                }
                for (String rule : toneResponse.rules()) {
                    prompt.append("- ").append(rule).append("\n");
                }
            }
            if (!persona.knowledge().isEmpty()) {
                prompt.append("KNOWLEDGE:\n- ").append(String.join("\n- ", persona.knowledge())).append("\n");
            }
            List<ReactionGuidelineSnippet> reactionGuidelines = context.reactionGuidelinesByDivineParticipantId()
                    .getOrDefault(entry.getKey(), List.of());
            if (!reactionGuidelines.isEmpty()) {
                prompt.append("REACTION GUIDANCE FOR THIS NPC ONLY (advisory, never an action or world fact):\n");
                for (ReactionGuidelineSnippet guideline : reactionGuidelines) {
                    prompt.append("- [").append(guideline.id()).append("] do: ")
                            .append(String.join("; ", guideline.guidance()));
                    if (!guideline.avoid().isEmpty()) {
                        prompt.append(" | avoid: ").append(String.join("; ", guideline.avoid()));
                    }
                    if (!guideline.requiredContext().isEmpty()) {
                        prompt.append(" | rely only on: ").append(String.join("; ", guideline.requiredContext()));
                    }
                    prompt.append("\n");
                }
            }
            List<MemorySnippet> memories = context.memoriesByDivineParticipantId().getOrDefault(entry.getKey(),
                    List.of());
            if (!memories.isEmpty()) {
                prompt.append("RELEVANT MEMORIES FOR THIS NPC ONLY:\n");
                for (MemorySnippet memory : memories) {
                    prompt.append("- [").append(memory.type()).append("] ").append(memory.summary())
                            .append("\n");
                }
            }
            List<KnowledgeSnippet> knowledge = context.knowledgeByDivineParticipantId().getOrDefault(entry.getKey(),
                    List.of());
            if (!knowledge.isEmpty()) {
                prompt.append("PERMITTED WORLD KNOWLEDGE FOR THIS NPC ONLY:\n");
                for (KnowledgeSnippet item : knowledge) {
                    prompt.append("- [").append(item.category()).append("] ").append(item.title());
                    if (item.parentId() != null && !item.parentId().isBlank()) {
                        prompt.append(" (parent=").append(item.parentId()).append(")");
                    }
                    prompt.append(": ").append(item.content()).append("\n");
                }
            }
        }
        prompt.append("\n[OUTPUT_RULES]\nMaximum desired response length is ").append(settings.maxResponseCharacters())
                .append(" Unicode characters across all speech entries.");
        return prompt.toString();
    }

    private static String generationInput(AiDialogueModels.ConversationContext context, DialogueTurnDirective directive) {
        String currentPlayerText = triggeringText(context.session(), context.triggeringParticipantId());
        StringBuilder prompt = new StringBuilder("[CURRENT_PLAYER_MESSAGE]\n")
                .append(currentPlayerText).append("\n\n[RECENT_CONVERSATION]\n");
        List<AiDialogueModels.ConversationTurn> history = context.session().history();
        int earlierTurns = Math.max(0, history.size() - 1);
        for (int index = 0; index < earlierTurns; index++) {
            AiDialogueModels.ConversationTurn turn = history.get(index);
            String displayName = context.session().participants().stream()
                    .filter(participant -> participant.participantId().equals(turn.speakerId()))
                    .map(AiDialogueModels.Participant::displayName).findFirst().orElse(turn.speakerId());
            prompt.append(turn.speakerId().equals(context.triggeringParticipantId()) ? "PLAYER" : "NPC")
                    .append(" (").append(displayName).append("): ").append(turn.text()).append("\n");
        }
        if (earlierTurns == 0) {
            prompt.append("No earlier live conversation.\n");
        }
        prompt.append("\n[CURRENT_GAME_CONTEXT]\n").append(GSON.toJson(contextForPrompt(context)))
                .append("\n\n[RESPONSE_INTENT]\n")
                .append("tags: ").append(context.intent().tags()).append("\n")
                .append("conversationAct: ").append(context.intent().conversationAct()).append("\n")
                .append("confidence: ").append(context.intent().confidence()).append("\n\n")
                .append(directive.promptBlock())
                .append("\n[OUTPUT_RULES]\nReturn the JSON object only.");
        return prompt.toString();
    }

    private static Map<String, Object> contextForPrompt(AiDialogueModels.ConversationContext context) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("sessionId", context.session().sessionId().toString());
        context.session().interactionId().ifPresent(interactionId -> value.put("interactionId", interactionId.toString()));
        value.put("participants", context.session().participants());
        value.put("location", context.session().location());
        value.put("currentTopic", context.session().currentTopic());
        value.put("intent", context.intent());
        value.put("pendingInteraction", context.session().pendingInteraction());
        context.pendingVouchInteraction().ifPresent(interaction -> value.put("pendingVouchInteraction", interaction));
        value.put("reactionGuidelineIds", context.reactionGuidelinesByDivineParticipantId().entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,
                        entry -> entry.getValue().stream().map(ReactionGuidelineSnippet::id).toList())));
        value.put("voiceStyleIds", context.voiceStylesByDivineParticipantId().entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,
                        entry -> entry.getValue().stream().map(style -> style.id().toString()).toList())));
        value.put("tagGuidance", context.tagGuidanceByDivineParticipantId().entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,
                        entry -> entry.getValue().stream().map(guidance -> Map.of(
                                "tag", guidance.tag().name(), "priority", guidance.priority())).toList())));
        value.put("playerToneResponses", context.playerToneResponseByDivineParticipantId());
        value.put("relationshipsByPlayer", context.relationshipsByPlayerParticipantId());
        value.put("gameState", context.gameState());
        value.put("questRewardConstraints", context.questRewardContext().constraints());
        value.put("gameProposalValidationFeedback", context.questRewardContext().validationFeedback());
        value.put("allowedProposalTypes", context.allowedProposalTypes());
        return value;
    }

    private static String questRewardProposalInstruction() {
        return "For quest ideas use type 'quest_proposal' only. Required parameters are giverNpcIds (comma-separated "
                + "divine ResourceLocation IDs already in this session), shared (true/false), targetConcept, and "
                + "narrativeReason. objectiveType, concreteItemId, suggestedAmount, and difficulty are optional and "
                + "must be omitted unless the supplied questRewardConstraints explicitly allow them. Multiple quest "
                + "givers additionally require collaborationReason and must have an actual narrative reason to cooperate. "
                + "For reward discussion use type 'reward_proposal' only. Required parameters are negotiationDecision "
                + "(OFFER, ACCEPT, REJECT, NEGOTIATE, DOWNGRADE, OFFER_ALTERNATIVE, or ASK_FOR_MORE) and narrativeReason. "
                + "When offering a reward, category, theme, description, and powerLevel are also required and must comply "
                + "with reward constraints; concreteItemId is permitted only when it is in the supplied catalogue. "
                + "Use validator feedback to negotiate, downgrade, offer an alternative, or refuse naturally. Never invent "
                + "Minecraft item/entity IDs, a quest ID, reward delivery, quest completion, or any authoritative result. "
                + "For a relationship change use type 'relationship_change_proposal' with npcId, targetPlayerId, "
                + "changes (comma-separated axis:delta entries), and reason; both IDs must name current participants. "
                + "When the current player clearly asks for information, an item, a blessing, a quest, a reward, or a "
                + "general favor, you may add one non-binding type 'request_judgment' with npcId, requesterPlayerId, "
                + "requestKind (INFORMATION, ITEM, BLESSING, QUEST, REWARD, or GENERIC), disposition (ACCEPT, REJECT, "
                + "NEGOTIATE, ASK_FOR_VOUCH, OFFER_QUEST, ASK_FOR_PROOF, or DEFER), and reason. It describes only the NPC's "
                + "narrative posture; ACCEPT never grants anything. ASK_FOR_VOUCH requires another active player and a separate "
                + "vouch_request. "
                + "For a vouch request use type 'vouch_request' with npcId, sponsorPlayerId, beneficiaryPlayerId, and "
                + "reason; both players and the NPC must be ACTIVE/current session participants. These remain proposals "
                + "and do not alter relationship data themselves. When pendingVouchInteraction names the current speaker "
                + "as sponsor, interpret only that sponsor's clear reply with type 'vouch_resolution' and npcId, "
                + "sponsorPlayerId, beneficiaryPlayerId, stance (SUPPORT, DECLINE, or UNCLEAR), and reason. A vouch is "
                + "never an automatic acceptance: evaluate the original request against relationship, risk, emotion, and "
                + "persona, then make only ordinary Quest/Reward/relationship proposals if appropriate.";
    }

    private static List<String> allowedProposalTypes(java.util.Optional<PendingVouchInteraction> pendingVouch,
            String triggeringParticipantId) {
        if (pendingVouch.isEmpty()) {
            return PROPOSAL_TYPES;
        }
        String sponsorParticipantId = "player:" + pendingVouch.get().sponsorPlayerId();
        if (!sponsorParticipantId.equals(triggeringParticipantId)) {
            return PROPOSAL_TYPES;
        }
        List<String> allowed = new ArrayList<>(PROPOSAL_TYPES);
        allowed.add(VouchResolutionProposal.TYPE);
        return List.copyOf(allowed);
    }

    private static AiDialogueModels.RelationshipContext neutralRelationshipContext() {
        return new AiDialogueModels.RelationshipContext(RelationshipMetrics.neutral(),
                com.sande.mythictrpg.ai.relationship.CurrentEmotion.calm(), List.of(), List.of());
    }

    private Map<String, List<MemorySnippet>> relevantMemories(AiDialogueModels.SessionSnapshot snapshot,
            String triggeringParticipantId, String currentText, int maximumMemories) {
        if (maximumMemories <= 0) {
            return Map.of();
        }
        AiDialogueModels.Participant triggering = participant(snapshot, triggeringParticipantId);
        if (triggering == null || triggering.playerId() == null || divineCount(snapshot) != 1) {
            return Map.of();
        }
        AiDialogueModels.Participant divine = onlyDivine(snapshot);
        if (divine == null || divine.godId() == null) {
            return Map.of();
        }
        List<MemorySnippet> memories = NpcMemoryEngine.INSTANCE.relevant(new MemoryQuery(divine.godId().toString(),
                triggering.playerId().toString(), currentText, maximumMemories));
        return memories.isEmpty() ? Map.of() : Map.of(divine.participantId(), memories);
    }

    private Map<String, List<KnowledgeSnippet>> relevantKnowledge(AiDialogueModels.SessionSnapshot snapshot,
            String triggeringParticipantId, String currentText,
            Map<String, Map<String, AiDialogueModels.RelationshipContext>> relationships, int maximumKnowledge,
            ConversationIntent intent) {
        if (maximumKnowledge <= 0) {
            return Map.of();
        }
        AiDialogueModels.Participant requester = participant(snapshot, triggeringParticipantId);
        if (requester == null || requester.playerId() == null) {
            return Map.of();
        }
        Map<String, List<KnowledgeSnippet>> selected = new LinkedHashMap<>();
        List<String> hints = knowledgeSearchHints(snapshot, currentText, intent);
        for (AiDialogueModels.Participant divine : snapshot.participants()) {
            if (divine.kind() != AiDialogueModels.ParticipantKind.DIVINE || divine.godId() == null) {
                continue;
            }
            AiDialogueModels.RelationshipContext requesterRelationship = relationshipContextForTrigger(snapshot,
                    triggeringParticipantId, divine, relationships);
            KnowledgeAccessContext access = new KnowledgeAccessContext(divine.godId().toString(),
                    requester.playerId().toString(), relationshipSnapshot(requester.playerId().toString(), divine,
                    requesterRelationship), emotionSnapshot(requester.playerId().toString(), divine,
                    requesterRelationship), knowledgeAudience(snapshot, divine, relationships));
            List<KnowledgeSnippet> knowledge = KnowledgeEngine.INSTANCE.relevant(new KnowledgeQuery(access, currentText,
                    hints, maximumKnowledge)).allowed();
            if (!knowledge.isEmpty()) {
                selected.put(divine.participantId(), knowledge);
            }
        }
        return Map.copyOf(selected);
    }

    /**
     * Prior turns are used only for explicitly referential wording such as "그 신" or "아까 말한 곳". This avoids
     * old topics hijacking a new question while still allowing natural follow-up questions to find their lore.
     */
    private static List<String> knowledgeSearchHints(AiDialogueModels.SessionSnapshot snapshot, String currentText,
            ConversationIntent intent) {
        List<String> hints = new ArrayList<>();
        if (intent != null) {
            hints.addAll(intent.knowledgeKeywords());
        }
        if (!isReferentialKnowledgeQuestion(currentText)) {
            return hints.stream().distinct().limit(8).toList();
        }
        if (snapshot.currentTopic() != null && !snapshot.currentTopic().isBlank()) {
            hints.add(snapshot.currentTopic());
        }
        int lastHistorical = Math.max(0, snapshot.history().size() - 1);
        int firstHistorical = Math.max(0, lastHistorical - 4);
        for (int index = firstHistorical; index < lastHistorical; index++) {
            hints.add(snapshot.history().get(index).text());
        }
        return hints.stream().distinct().limit(8).toList();
    }

    private static boolean isReferentialKnowledgeQuestion(String rawText) {
        String text = rawText == null ? "" : rawText.replaceAll("\\s+", " ").trim();
        return text.startsWith("그 ") || text.startsWith("그신") || text.startsWith("그녀")
                || text.startsWith("그분") || containsAny(text, "그 신", "그것", "그거", "그녀", "그분", "그곳",
                        "거기", "아까", "방금", "앞에서", "전에 말한");
    }

    private Map<String, List<DialogueExampleSnippet>> relevantDialogueExamples(
            AiDialogueModels.SessionSnapshot snapshot, String triggeringParticipantId, String currentText,
            Map<String, Map<String, AiDialogueModels.RelationshipContext>> relationships, int maximumExamples,
            ConversationIntent intent, Map<String, List<TagDialogueGuidance>> tagGuidance) {
        Map<String, List<DialogueExampleSnippet>> selected = new LinkedHashMap<>();
        for (AiDialogueModels.Participant divine : snapshot.participants()) {
            if (divine.kind() != AiDialogueModels.ParticipantKind.DIVINE || divine.godId() == null) {
                continue;
            }
            // The new tag library is the normal composition path.  Existing multi-tag/NPC-scoped examples remain a
            // compatibility fallback for incomplete content packs instead of competing with every tag demonstration.
            if (!tagGuidance.getOrDefault(divine.participantId(), List.of()).isEmpty()) {
                continue;
            }
            AiDialogueModels.RelationshipContext relationship = relationshipContextForTrigger(snapshot,
                    triggeringParticipantId, divine, relationships);
            NpcAgent agent = NpcAgentRepository.INSTANCE.find(divine.godId()).orElse(null);
            WeightedExampleStyleContext styleContext = dialogueExampleContextResolver.resolveWeighted(snapshot,
                    triggeringParticipantId, divine, relationship, agent, currentText, relationships,
                    intent == null ? java.util.Set.of() : intent.tags());
            List<DialogueExampleSnippet> examples = dialogueExampleRetriever.retrieve(new ExampleRetrievalQuery(styleContext,
                    divine.godId(), maximumExamples));
            if (!examples.isEmpty()) {
                selected.put(divine.participantId(), examples);
            }
        }
        return Map.copyOf(selected);
    }

    private Map<String, List<TagDialogueGuidance>> relevantTagGuidance(AiDialogueModels.SessionSnapshot snapshot,
            String triggeringParticipantId, String currentText,
            Map<String, Map<String, AiDialogueModels.RelationshipContext>> relationships, int maximumExamples,
            ConversationIntent intent) {
        Map<String, List<TagDialogueGuidance>> selected = new LinkedHashMap<>();
        for (AiDialogueModels.Participant divine : snapshot.participants()) {
            if (divine.kind() != AiDialogueModels.ParticipantKind.DIVINE || divine.godId() == null) {
                continue;
            }
            AiDialogueModels.RelationshipContext relationship = relationshipContextForTrigger(snapshot,
                    triggeringParticipantId, divine, relationships);
            NpcAgent agent = NpcAgentRepository.INSTANCE.find(divine.godId()).orElse(null);
            WeightedExampleStyleContext styleContext = dialogueExampleContextResolver.resolveWeighted(snapshot,
                    triggeringParticipantId, divine, relationship, agent, currentText, relationships,
                    intent == null ? java.util.Set.of() : intent.tags());
            List<TagDialogueGuidance> guidance = tagDialogueGuidanceResolver.resolve(styleContext, maximumExamples);
            if (!guidance.isEmpty()) {
                selected.put(divine.participantId(), guidance);
            }
        }
        return Map.copyOf(selected);
    }

    private Map<String, PlayerToneResponseGuidance> relevantPlayerToneResponses(
            AiDialogueModels.SessionSnapshot snapshot, String triggeringParticipantId,
            Map<String, Map<String, AiDialogueModels.RelationshipContext>> relationships,
            Map<String, NpcSocialAuthorityContext> socialAuthority, ConversationIntent intent) {
        if (intent == null || intent.playerToneTags().isEmpty()) {
            return Map.of();
        }
        Map<String, PlayerToneResponseGuidance> selected = new LinkedHashMap<>();
        for (AiDialogueModels.Participant divine : snapshot.participants()) {
            if (divine.kind() != AiDialogueModels.ParticipantKind.DIVINE || divine.godId() == null) {
                continue;
            }
            AiDialogueModels.RelationshipContext relationship = relationshipContextForTrigger(snapshot,
                    triggeringParticipantId, divine, relationships);
            NpcAgent agent = NpcAgentRepository.INSTANCE.find(divine.godId()).orElse(null);
            NpcSocialAuthorityContext authority = socialAuthority.getOrDefault(divine.participantId(),
                    NpcSocialAuthorityContext.unknown());
            selected.put(divine.participantId(), playerToneResponseGuidanceResolver.resolve(intent.playerToneTags(),
                    relationship, agent, authority));
        }
        return Map.copyOf(selected);
    }

    /**
     * Converts only immutable turn/game snapshot facts into advisory reaction guidance. Minecraft APIs are never read
     * here, and guidance is capped separately from reusable dialogue examples.
     */
    private Map<String, List<ReactionGuidelineSnippet>> relevantReactionGuidelines(
            AiDialogueModels.SessionSnapshot snapshot, String triggeringParticipantId, String currentText,
            Map<String, Map<String, AiDialogueModels.RelationshipContext>> relationships,
            Map<String, Object> gameState, int maximumGuidelines) {
        if (maximumGuidelines <= 0) {
            return Map.of();
        }
        Map<String, List<ReactionGuidelineSnippet>> selected = new LinkedHashMap<>();
        for (AiDialogueModels.Participant divine : snapshot.participants()) {
            if (divine.kind() != AiDialogueModels.ParticipantKind.DIVINE || divine.godId() == null) {
                continue;
            }
            var tagProfile = NpcCharacterTagRepository.INSTANCE.find(divine.godId()).orElse(null);
            if (tagProfile == null) {
                continue;
            }
            AiDialogueModels.RelationshipContext relationship = relationshipContextForTrigger(snapshot,
                    triggeringParticipantId, divine, relationships);
            SituationContext situation = reactionSituation(snapshot, currentText, relationship, gameState);
            List<ReactionGuidelineSnippet> matches = reactionGuidelineRetriever.retrieve(situation, tagProfile).matches()
                    .stream().limit(maximumGuidelines).map(ReactionGuidelineSnippet::from).toList();
            if (!matches.isEmpty()) {
                selected.put(divine.participantId(), matches);
            }
        }
        return Map.copyOf(selected);
    }

    /** Resolves immutable voice guidance on the server thread before any LLM worker receives the turn context. */
    private Map<String, List<VoiceStyleProfile>> relevantVoiceStyles(AiDialogueModels.SessionSnapshot snapshot) {
        Map<String, List<VoiceStyleProfile>> selected = new LinkedHashMap<>();
        for (AiDialogueModels.Participant divine : snapshot.participants()) {
            if (divine.kind() != AiDialogueModels.ParticipantKind.DIVINE || divine.godId() == null) {
                continue;
            }
            NpcAgentRepository.INSTANCE.find(divine.godId()).map(NpcAgent::voiceStyleIds)
                    .map(JsonVoiceStyleRepository.INSTANCE::resolve).filter(styles -> !styles.isEmpty())
                    .ifPresent(styles -> selected.put(divine.participantId(), styles));
        }
        return Map.copyOf(selected);
    }

    private static SituationContext reactionSituation(AiDialogueModels.SessionSnapshot snapshot, String currentText,
            AiDialogueModels.RelationshipContext relationship, Map<String, Object> gameState) {
        String normalized = currentText == null ? "" : currentText.toLowerCase(java.util.Locale.ROOT);
        boolean raining = truthy(gameState.get("raining"));
        boolean thundering = truthy(gameState.get("thundering"));
        MinecraftWeather weather = thundering ? MinecraftWeather.THUNDER : raining ? MinecraftWeather.RAIN
                : gameState.containsKey("raining") ? MinecraftWeather.CLEAR : MinecraftWeather.UNKNOWN;
        java.util.Optional<Double> healthRatio = ratio(gameState.get("health"), gameState.get("maxHealth"));
        java.util.Optional<Long> dayTime = longValue(gameState.get("dayTime"));
        return new SituationContext(new SituationContext.PlayerState(healthRatio, truthy(gameState.get("recentCombat")),
                java.util.Optional.empty(), java.util.Optional.empty()),
                new SituationContext.WorldState(dayTime, weather, truthy(gameState.get("weatherChanged"))),
                new SituationContext.ConversationState(false, containsAny(normalized, "안녕", "반가", "hello"), false,
                        containsAny(normalized, "퀘스트", "의뢰", "심심", "시험"), false, false,
                        containsAny(normalized, "대단", "멋지", "존경", "훌륭"),
                        containsAny(normalized, "바보", "멍청", "싫어", "무능"),
                        containsAny(normalized, "미안", "죄송", "사과"),
                        containsAny(normalized, "다른 신", "아테나", "헤르메스", "제우스"),
                        containsAny(normalized, "누구", "어디", "무엇", "뭐야", "왜")),
                new SituationContext.RelationshipState(relationshipTags(relationship.derivedTags())),
                SituationContext.MemoryState.unknown(), SituationContext.QuestState.unknown(),
                new SituationContext.SocialContext((int) snapshot.participants().stream().filter(participant -> participant.kind()
                        == AiDialogueModels.ParticipantKind.PLAYER && participant.state()
                        != AiDialogueModels.ParticipantState.OUTSIDE).count(),
                        snapshot.visibleParticipants().stream().filter(participant -> participant.kind()
                                == AiDialogueModels.ParticipantKind.PLAYER).count() > 1));
    }

    private static java.util.Set<RelationshipTag> relationshipTags(List<String> rawTags) {
        java.util.EnumSet<RelationshipTag> tags = java.util.EnumSet.noneOf(RelationshipTag.class);
        for (String rawTag : rawTags) {
            try {
                tags.add(RelationshipTag.valueOf(rawTag));
            } catch (IllegalArgumentException ignored) {
                // Relationship interpretations are text; only valid explicit enum tags can influence reaction rules.
            }
        }
        return java.util.Set.copyOf(tags);
    }

    private static boolean containsAny(String text, String... terms) {
        for (String term : terms) {
            if (text.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private static boolean truthy(Object value) {
        return value instanceof Boolean flag && flag;
    }

    private static java.util.Optional<Long> longValue(Object value) {
        return value instanceof Number number ? java.util.Optional.of(number.longValue()) : java.util.Optional.empty();
    }

    private static java.util.Optional<Double> ratio(Object health, Object maximum) {
        if (!(health instanceof Number current) || !(maximum instanceof Number maximumValue)
                || maximumValue.doubleValue() <= 0.0D) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(Math.max(0.0D, Math.min(1.0D,
                current.doubleValue() / maximumValue.doubleValue())));
    }

    private static AiDialogueModels.RelationshipContext relationshipContextForTrigger(
            AiDialogueModels.SessionSnapshot snapshot, String triggeringParticipantId, AiDialogueModels.Participant divine,
            Map<String, Map<String, AiDialogueModels.RelationshipContext>> relationships) {
        if (divine.godId() == null) {
            return neutralRelationshipContext();
        }
        return relationships.getOrDefault(triggeringParticipantId, Map.of())
                .getOrDefault(divine.godId().toString(), neutralRelationshipContext());
    }

    /** NPC agent data may reuse a shared persona definition; the visible session name remains the NPC's own name. */
    private static java.util.Optional<AiDialogueModels.GodPersona> personaFor(
            AiDialogueModels.Participant divineParticipant) {
        if (divineParticipant.godId() == null) {
            return java.util.Optional.empty();
        }
        net.minecraft.resources.ResourceLocation personaId = NpcAgentRepository.INSTANCE.find(divineParticipant.godId())
                .map(NpcAgent::personaId).orElse(divineParticipant.godId());
        return GodPersonaRepository.INSTANCE.find(personaId).map(persona -> new AiDialogueModels.GodPersona(
                divineParticipant.displayName(), persona.systemPrompt(), persona.background(), persona.knowledge(),
                persona.examples()));
    }

    private static String formatDialogueExample(DialogueExampleSnippet example) {
        StringBuilder formatted = new StringBuilder("[").append(example.exampleId()).append(" / tags: ")
                .append(example.tags()).append("]\n");
        for (var turn : example.dialogue()) {
            formatted.append(turn.role()).append(": ").append(turn.text()).append("\n");
        }
        return formatted.toString().trim();
    }

    private static String formatTagDialogueExample(TagDialogueGuidance guidance) {
        StringBuilder formatted = new StringBuilder("[").append(guidance.tag()).append(" / priority ")
                .append(guidance.priority()).append("]\n");
        for (var turn : guidance.dialogue()) {
            formatted.append(turn.role()).append(": ").append(turn.text()).append("\n");
        }
        return formatted.toString().trim();
    }

    private static List<KnowledgeAudienceMember> knowledgeAudience(AiDialogueModels.SessionSnapshot snapshot,
            AiDialogueModels.Participant divine,
            Map<String, Map<String, AiDialogueModels.RelationshipContext>> relationships) {
        return snapshot.participants().stream().filter(participant -> participant.kind()
                == AiDialogueModels.ParticipantKind.PLAYER).filter(participant -> participant.playerId() != null)
                .map(player -> new KnowledgeAudienceMember(player.playerId().toString(), audienceState(player.state()),
                        relationshipSnapshot(player.playerId().toString(), divine, relationships
                                .getOrDefault(player.participantId(), Map.of())
                                .getOrDefault(divine.godId().toString(), neutralRelationshipContext())))).toList();
    }

    private static RelationshipSnapshot relationshipSnapshot(String playerId, AiDialogueModels.Participant divine,
            AiDialogueModels.RelationshipContext relationship) {
        RelationshipMetrics metrics = relationship.metrics();
        return new RelationshipSnapshot(divine.godId().toString(), playerId, Map.of(
                RelationshipAxes.AFFINITY, metrics.affinity(),
                RelationshipAxes.TRUST, metrics.trust(),
                RelationshipAxes.RESPECT, metrics.respect(),
                RelationshipAxes.CAUTION, metrics.caution()));
    }

    private static EmotionSnapshot emotionSnapshot(String playerId, AiDialogueModels.Participant divine,
            AiDialogueModels.RelationshipContext relationship) {
        return new EmotionSnapshot(divine.godId().toString(), playerId, relationship.emotion().intensities());
    }

    private static KnowledgeAudienceState audienceState(AiDialogueModels.ParticipantState state) {
        return switch (state) {
            case ACTIVE -> KnowledgeAudienceState.ACTIVE;
            case LISTENER -> KnowledgeAudienceState.LISTENER;
            case OUTSIDE -> KnowledgeAudienceState.OUTSIDE;
        };
    }

    private static String triggeringText(AiDialogueModels.SessionSnapshot snapshot, String triggeringParticipantId) {
        for (int index = snapshot.history().size() - 1; index >= 0; index--) {
            AiDialogueModels.ConversationTurn turn = snapshot.history().get(index);
            if (turn.speakerId().equals(triggeringParticipantId)) {
                return turn.text();
            }
        }
        return "";
    }

    private static AiDialogueModels.Participant participant(AiDialogueModels.SessionSnapshot snapshot,
            String participantId) {
        return snapshot.participants().stream().filter(participant -> participant.participantId().equals(participantId))
                .findFirst().orElse(null);
    }

    private static AiDialogueModels.Participant onlyDivine(AiDialogueModels.SessionSnapshot snapshot) {
        return snapshot.participants().stream().filter(participant -> participant.kind()
                == AiDialogueModels.ParticipantKind.DIVINE).findFirst().orElse(null);
    }

    private static long divineCount(AiDialogueModels.SessionSnapshot snapshot) {
        return snapshot.participants().stream().filter(participant -> participant.kind()
                == AiDialogueModels.ParticipantKind.DIVINE).count();
    }

}
