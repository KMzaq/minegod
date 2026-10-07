package com.sande.mythictrpg.ai.example;

import com.sande.mythictrpg.ai.AiDialogueModels;
import com.sande.mythictrpg.ai.agent.NpcAgent;
import com.sande.mythictrpg.ai.relationship.CurrentEmotion;
import com.sande.mythictrpg.ai.relationship.DefaultRelationshipStateResolver;
import com.sande.mythictrpg.ai.relationship.RelationshipMetrics;
import com.sande.mythictrpg.ai.relationship.RelationshipStateResolver;
import com.sande.mythictrpg.ai.relationship.RelationshipTag;
import com.sande.mythictrpg.ai.tag.ExampleStyleTag;

import java.util.EnumSet;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Converts existing agent/session state into library-selection tags. This is advisory prompt context only; it never
 * writes relationship, emotion, quest, or world state.
 */
public final class DialogueExampleContextResolver {
    private final RelationshipStateResolver relationships;

    public DialogueExampleContextResolver() {
        this(new DefaultRelationshipStateResolver());
    }

    public DialogueExampleContextResolver(RelationshipStateResolver relationships) {
        this.relationships = Objects.requireNonNull(relationships, "relationships");
    }

    public Set<DialogueExampleTag> resolve(AiDialogueModels.SessionSnapshot session, String triggeringParticipantId,
            AiDialogueModels.Participant divine, AiDialogueModels.RelationshipContext triggerRelationship,
            NpcAgent agent, String currentText,
            Map<String, Map<String, AiDialogueModels.RelationshipContext>> relationshipsByPlayer) {
        return resolve(session, triggeringParticipantId, divine, triggerRelationship, agent, currentText,
                relationshipsByPlayer, Set.of());
    }

    /** A routed intent replaces only the text-keyword situation guess; persona, relationship and audience remain live. */
    public Set<DialogueExampleTag> resolve(AiDialogueModels.SessionSnapshot session, String triggeringParticipantId,
            AiDialogueModels.Participant divine, AiDialogueModels.RelationshipContext triggerRelationship,
            NpcAgent agent, String currentText,
            Map<String, Map<String, AiDialogueModels.RelationshipContext>> relationshipsByPlayer,
            Set<DialogueExampleTag> routedIntentTags) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(triggeringParticipantId, "triggeringParticipantId");
        Objects.requireNonNull(divine, "divine");
        Objects.requireNonNull(relationshipsByPlayer, "relationshipsByPlayer");
        EnumSet<DialogueExampleTag> result = EnumSet.noneOf(DialogueExampleTag.class);
        if (agent != null) {
            for (ExampleStyleTag style : agent.speechStyles()) {
                addSpeechStyle(result, style);
            }
        }
        RelationshipMetrics metrics = triggerRelationship == null ? RelationshipMetrics.neutral()
                : triggerRelationship.metrics();
        for (RelationshipTag tag : relationships.resolve(metrics)) {
            addMatching(result, tag.name());
        }
        addEmotion(result, triggerRelationship == null ? CurrentEmotion.calm() : triggerRelationship.emotion());
        if (routedIntentTags == null || routedIntentTags.isEmpty()) {
            addSituationAndTextContext(result, currentText == null ? "" : currentText);
        } else {
            // Only the advisory situation is LLM-routed.  Participant/audience visibility remains a game-owned fact
            // and is added below from the immutable session snapshot.
            routedIntentTags.stream().filter(tag -> tag.name().startsWith("S_")).forEach(result::add);
            addSituationContextTags(result);
        }
        addAudienceContext(result, session, triggeringParticipantId, divine, relationshipsByPlayer);
        return Set.copyOf(result);
    }

    /**
     * Produces the initial weighted StyleContext for tag retrieval. High-signal NPC style and requested situation
     * outrank transient emotion or broad audience context while preserving every derived tag for future retrievers.
     */
    public WeightedExampleStyleContext resolveWeighted(AiDialogueModels.SessionSnapshot session,
            String triggeringParticipantId, AiDialogueModels.Participant divine,
            AiDialogueModels.RelationshipContext triggerRelationship, NpcAgent agent, String currentText,
            Map<String, Map<String, AiDialogueModels.RelationshipContext>> relationshipsByPlayer) {
        return resolveWeighted(session, triggeringParticipantId, divine, triggerRelationship, agent, currentText,
                relationshipsByPlayer, Set.of());
    }

    public WeightedExampleStyleContext resolveWeighted(AiDialogueModels.SessionSnapshot session,
            String triggeringParticipantId, AiDialogueModels.Participant divine,
            AiDialogueModels.RelationshipContext triggerRelationship, NpcAgent agent, String currentText,
            Map<String, Map<String, AiDialogueModels.RelationshipContext>> relationshipsByPlayer,
            Set<DialogueExampleTag> routedIntentTags) {
        EnumMap<DialogueExampleTag, Integer> weights = new EnumMap<>(DialogueExampleTag.class);
        for (DialogueExampleTag tag : resolve(session, triggeringParticipantId, divine, triggerRelationship, agent,
                currentText, relationshipsByPlayer, routedIntentTags)) {
            weights.put(tag, defaultWeight(tag));
        }
        return new WeightedExampleStyleContext(weights);
    }

    private static int defaultWeight(DialogueExampleTag tag) {
        return switch (tag) {
            case P_GRUFF, P_AGGRESSIVE -> 3;
            case P_INDIRECT_CARE -> 3;
            case P_GENTLE, P_STRICT, P_COLD, P_IMPERIOUS, P_CUNNING, P_ARROGANT, P_PLAYFUL, P_CALM,
                    P_WISE, P_HONORABLE, P_FORMAL, P_MYSTERIOUS, P_SHORT, P_TALKATIVE, P_DRY_HUMOR -> 3;
            case R_CLOSE, R_DISTRUST, R_HOSTILE -> 5;
            case R_STRANGER, R_ACQUAINTANCE, R_FRIENDLY -> 4;
            case E_NEUTRAL -> 2;
            case E_HAPPY, E_ANGRY, E_ANNOYED, E_CURIOUS, E_SAD, E_GRATEFUL -> 3;
            case S_CHAT, S_ITEM_REQUEST, S_POWER_REQUEST, S_HELP_REQUEST, S_INFORMATION_REQUEST,
                    S_QUEST_INQUIRY, S_REWARD_NEGOTIATION, S_GIFT_OFFER, S_APOLOGY, S_CONFLICT -> 6;
            case S_SMALLTALK, S_SECRET_REQUEST, S_QUEST_OFFER, S_VOUCH -> 2;
            case C_UNTRUSTED_LISTENER, C_PRIVATE_TOPIC, C_ARGUMENT, C_PLAYER_VOUCHING -> 5;
            case C_ONE_TO_ONE, C_GROUP, C_TRUSTED_FRIEND_PRESENT, C_MULTIPLE_GODS -> 4;
        };
    }

    private static void addSpeechStyle(Set<DialogueExampleTag> result, ExampleStyleTag style) {
        addMatching(result, style.name());
        switch (style) {
            case P_IMPERIOUS -> result.add(DialogueExampleTag.P_ARROGANT);
            case P_STRICT -> result.add(DialogueExampleTag.P_FORMAL);
            default -> {
                // Legacy styles without a direct v1 shared-library equivalent are intentionally not guessed.
            }
        }
    }

    private static void addEmotion(Set<DialogueExampleTag> result, CurrentEmotion emotion) {
        String strongest = emotion.intensities().entrySet().stream().filter(entry -> entry.getValue() > 0)
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getKey).findFirst().orElse("");
        switch (strongest) {
            case "happiness" -> result.add(DialogueExampleTag.E_HAPPY);
            case "anger" -> result.add(DialogueExampleTag.E_ANGRY);
            case "annoyance", "disappointment" -> result.add(DialogueExampleTag.E_ANNOYED);
            case "curiosity" -> result.add(DialogueExampleTag.E_CURIOUS);
            case "sadness", "fear" -> result.add(DialogueExampleTag.E_SAD);
            case "gratitude" -> result.add(DialogueExampleTag.E_GRATEFUL);
            default -> result.add(DialogueExampleTag.E_NEUTRAL);
        }
    }

    private static void addSituationAndTextContext(Set<DialogueExampleTag> result, String rawText) {
        String text = rawText.toLowerCase(Locale.ROOT);
        boolean situation = false;
        if (containsAny(text, "만들어", "제작", "수리", "고쳐", "아이템", "재료", "craft", "repair", "item")) {
            result.add(DialogueExampleTag.S_ITEM_REQUEST);
            situation = true;
        }
        if (containsAny(text, "가호", "축복", "권능", "힘을 빌려", "blessing")) {
            result.add(DialogueExampleTag.S_POWER_REQUEST);
            situation = true;
        }
        if (containsAny(text, "도와", "도움", "위험해", "피할 방법", "help me")) {
            result.add(DialogueExampleTag.S_HELP_REQUEST);
            situation = true;
        }
        if (containsAny(text, "알려", "무엇", "뭐", "누구", "어디", "왜", "어떤", "information", "tell me")) {
            result.add(DialogueExampleTag.S_INFORMATION_REQUEST);
            situation = true;
        }
        if (containsAny(text, "비밀", "숨겨", "secret", "confidential")) {
            result.add(DialogueExampleTag.S_INFORMATION_REQUEST);
            result.add(DialogueExampleTag.C_PRIVATE_TOPIC);
            situation = true;
        }
        if (containsAny(text, "퀘스트", "의뢰", "임무", "시험", "필요한 거 있어", "필요한게 있어", "quest", "mission", "trial")) {
            result.add(DialogueExampleTag.S_QUEST_INQUIRY);
            situation = true;
        }
        if (containsAny(text, "보상", "대가", "흥정", "reward", "payment", "negotiate")) {
            result.add(DialogueExampleTag.S_REWARD_NEGOTIATION);
            situation = true;
        }
        if (containsAny(text, "선물", "공물", "바치", "제단에", "gift", "offering")) {
            result.add(DialogueExampleTag.S_GIFT_OFFER);
            situation = true;
        }
        if (containsAny(text, "미안", "사과", "잘못", "sorry", "apolog")) {
            result.add(DialogueExampleTag.S_APOLOGY);
            situation = true;
        }
        if (containsAny(text, "거짓", "싫어", "꺼져", "바보", "싸우", "배신", "liar", "hate", "fight")) {
            result.add(DialogueExampleTag.S_CONFLICT);
            result.add(DialogueExampleTag.C_ARGUMENT);
            situation = true;
        }
        if (containsAny(text, "보증", "보장", "증명해", "vouch", "guarantee")) {
            result.add(DialogueExampleTag.C_PLAYER_VOUCHING);
        }
        if (!situation) {
            result.add(DialogueExampleTag.S_CHAT);
        }
        addSituationContextTags(result);
    }

    private static void addSituationContextTags(Set<DialogueExampleTag> result) {
        if (result.contains(DialogueExampleTag.S_CONFLICT)) {
            result.add(DialogueExampleTag.C_ARGUMENT);
        }
    }

    private void addAudienceContext(Set<DialogueExampleTag> result, AiDialogueModels.SessionSnapshot session,
            String triggeringParticipantId, AiDialogueModels.Participant divine,
            Map<String, Map<String, AiDialogueModels.RelationshipContext>> relationshipsByPlayer) {
        long visiblePlayers = session.visibleParticipants().stream().filter(participant -> participant.kind()
                == AiDialogueModels.ParticipantKind.PLAYER).count();
        long divines = session.participants().stream().filter(participant -> participant.kind()
                == AiDialogueModels.ParticipantKind.DIVINE).count();
        if (visiblePlayers <= 1 && divines <= 1) {
            result.add(DialogueExampleTag.C_ONE_TO_ONE);
        }
        if (visiblePlayers > 1) {
            result.add(DialogueExampleTag.C_GROUP);
        }
        if (divines > 1) {
            result.add(DialogueExampleTag.C_MULTIPLE_GODS);
        }
        for (AiDialogueModels.Participant player : session.visibleParticipants()) {
            if (player.kind() != AiDialogueModels.ParticipantKind.PLAYER
                    || player.participantId().equals(triggeringParticipantId)
                    || player.state() != AiDialogueModels.ParticipantState.LISTENER) {
                continue;
            }
            AiDialogueModels.RelationshipContext relationship = relationshipsByPlayer
                    .getOrDefault(player.participantId(), Map.of())
                    .get(divine.godId() == null ? "" : divine.godId().toString());
            Set<RelationshipTag> listenerTags = relationships.resolve(relationship == null
                    ? RelationshipMetrics.neutral() : relationship.metrics());
            if (listenerTags.contains(RelationshipTag.R_FRIENDLY) || listenerTags.contains(RelationshipTag.R_CLOSE)) {
                result.add(DialogueExampleTag.C_TRUSTED_FRIEND_PRESENT);
            } else {
                result.add(DialogueExampleTag.C_UNTRUSTED_LISTENER);
            }
        }
    }

    private static void addMatching(Set<DialogueExampleTag> result, String rawTag) {
        try {
            result.add(DialogueExampleTag.valueOf(rawTag));
        } catch (IllegalArgumentException ignored) {
            // The source has a richer legacy tag vocabulary than this library. It is not a configuration failure.
        }
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
