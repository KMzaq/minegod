package com.sande.mythictrpg.ai.intent;

import com.sande.mythictrpg.ai.AiDialogueModels;
import com.sande.mythictrpg.ai.example.DialogueExampleTag;
import com.sande.mythictrpg.ai.tone.PlayerSpeechTone;
import com.sande.mythictrpg.ai.tone.PlayerSpeechToneHeuristics;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Chooses when a turn needs a small first-pass local-LLM classification. Clear greetings and acknowledgements use the
 * existing deterministic retrieval path; all meaningful or ambiguous turns are routed before examples are selected.
 */
public final class ConversationIntentRouter {
    private static final Set<DialogueExampleTag> ALLOWED_TAGS = EnumSet.of(
            DialogueExampleTag.S_CHAT, DialogueExampleTag.S_ITEM_REQUEST, DialogueExampleTag.S_POWER_REQUEST,
            DialogueExampleTag.S_HELP_REQUEST, DialogueExampleTag.S_INFORMATION_REQUEST,
            DialogueExampleTag.S_QUEST_INQUIRY, DialogueExampleTag.S_REWARD_NEGOTIATION,
            DialogueExampleTag.S_GIFT_OFFER, DialogueExampleTag.S_APOLOGY, DialogueExampleTag.S_CONFLICT);

    public boolean shouldRoute(String rawText) {
        String text = rawText == null ? "" : rawText.trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            return false;
        }
        if (text.length() <= 14 && isClearLowRiskTurn(text)) {
            return false;
        }
        return true;
    }

    /**
     * Returns a high-confidence, deterministic intent for unambiguous keyword requests. Ambiguous 부탁/질문은
     * empty로 반환하여 the local classifier can consider the full conversation context.
     */
    public Optional<ConversationIntent> tryFastIntent(String rawText, AiDialogueModels.SessionSnapshot session) {
        String text = rawText == null ? "" : rawText.trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            return Optional.empty();
        }
        long activeDivine = session.participants().stream()
                .filter(participant -> participant.kind() == AiDialogueModels.ParticipantKind.DIVINE
                        && participant.state() == AiDialogueModels.ParticipantState.ACTIVE)
                .count();
        // Multi-god turns need speaker/social reasoning even when a familiar keyword is present.
        if (activeDivine > 1) {
            return Optional.empty();
        }
        DialogueExampleTag situation = null;
        if (containsAny(text, "아이템", "물건", "장비", "무기", "검", "방어구", "만들어줘", "제작해줘")) {
            situation = DialogueExampleTag.S_ITEM_REQUEST;
        } else if (containsAny(text, "가호", "축복", "권능", "힘을 빌려")) {
            situation = DialogueExampleTag.S_POWER_REQUEST;
        } else if (containsAny(text, "퀘스트", "의뢰", "임무", "필요한 거 있어", "필요한게 있어")) {
            situation = DialogueExampleTag.S_QUEST_INQUIRY;
        } else if (containsAny(text, "보상", "대가", "조건을", "흥정")) {
            situation = DialogueExampleTag.S_REWARD_NEGOTIATION;
        } else if (containsAny(text, "바치", "공물", "선물", "제단에")) {
            situation = DialogueExampleTag.S_GIFT_OFFER;
        } else if (containsAny(text, "미안", "사과할게", "죄송")) {
            situation = DialogueExampleTag.S_APOLOGY;
        } else if (containsAny(text, "알려줘", "알려 줘", "뭐야", "어디야", "왜", "어떻게", "누구")) {
            situation = DialogueExampleTag.S_INFORMATION_REQUEST;
        }
        if (situation == null || isAmbiguousFastMatch(text, situation)) {
            return Optional.empty();
        }
        // Audience tags are deliberately game-derived in the second stage.  A classifier must never be able to turn
        // a public conversation into a private one (or vice versa) by emitting C_* labels.
        return Optional.of(new ConversationIntent(EnumSet.of(situation), Set.of(),
                PlayerSpeechToneHeuristics.classify(rawText),
                ConversationAct.resolve(ConversationAct.UNSPECIFIED, rawText, latestDivineText(session)),
                92, ConversationIntent.Source.HEURISTIC));
    }

    /** Used for clear turns, disabled routing, and classifier failures without losing immediate speech-tone context. */
    public ConversationIntent heuristicIntent(String rawText) {
        return heuristicIntent(rawText, null);
    }

    public ConversationIntent heuristicIntent(String rawText, AiDialogueModels.SessionSnapshot session) {
        return new ConversationIntent(Set.of(), Set.of(), PlayerSpeechToneHeuristics.classify(rawText),
                ConversationAct.resolve(ConversationAct.UNSPECIFIED, rawText,
                        session == null ? "" : latestDivineText(session)), 0,
                ConversationIntent.Source.HEURISTIC);
    }

    /** Preserves LLM nuance while retaining unmistakable threats, abuse, mockery, and apologies from local rules. */
    public ConversationIntent mergeStrongHeuristicTones(ConversationIntent intent, String rawText) {
        return mergeStrongHeuristicTones(intent, rawText, null);
    }

    public ConversationIntent mergeStrongHeuristicTones(ConversationIntent intent, String rawText,
            AiDialogueModels.SessionSnapshot session) {
        ConversationIntent safe = intent == null ? heuristicIntent(rawText) : intent;
        java.util.EnumSet<PlayerSpeechTone> combined = java.util.EnumSet.noneOf(PlayerSpeechTone.class);
        combined.addAll(safe.playerToneTags());
        combined.addAll(PlayerSpeechToneHeuristics.strongSignals(rawText));
        boolean formalityClassified = combined.contains(PlayerSpeechTone.T_POLITE)
                || combined.contains(PlayerSpeechTone.T_INFORMAL);
        if (!formalityClassified) {
            for (PlayerSpeechTone tone : PlayerSpeechToneHeuristics.classify(rawText)) {
                if (tone == PlayerSpeechTone.T_POLITE || tone == PlayerSpeechTone.T_INFORMAL) {
                    combined.add(tone);
                }
            }
        }
        return new ConversationIntent(safe.tags(), safe.knowledgeKeywords(), combined,
                ConversationAct.resolve(safe.conversationAct(), rawText, session == null ? "" : latestDivineText(session)),
                safe.confidence(), safe.source(), safe.turnInterpretation().validatedFor(rawText));
    }

    public List<AiDialogueModels.OllamaMessage> messages(AiDialogueModels.SessionSnapshot session,
            String triggeringParticipantId) {
        StringBuilder recent = new StringBuilder();
        recent.append("PARTICIPANTS (background only; do not classify audience state):\n");
        for (AiDialogueModels.Participant participant : session.participants()) {
            recent.append("- ").append(participant.participantId()).append(" / ")
                    .append(participant.kind()).append(" / ").append(participant.state()).append(" / ")
                    .append(participant.displayName()).append("\n");
        }
        recent.append("\n");
        int first = Math.max(0, session.history().size() - 5);
        for (int index = first; index < session.history().size(); index++) {
            AiDialogueModels.ConversationTurn turn = session.history().get(index);
            String name = session.participants().stream().filter(participant -> participant.participantId()
                    .equals(turn.speakerId())).map(AiDialogueModels.Participant::displayName)
                    .findFirst().orElse(turn.speakerId());
            recent.append("[").append(name).append("] ").append(turn.text()).append("\n");
        }
        String currentText = "";
        for (int index = session.history().size() - 1; index >= 0; index--) {
            if (triggeringParticipantId.equals(session.history().get(index).speakerId())) {
                currentText = session.history().get(index).text();
                break;
            }
        }
        return List.of(new AiDialogueModels.OllamaMessage("system", instruction()),
                new AiDialogueModels.OllamaMessage("user", "TRIGGERING_PARTICIPANT=" + triggeringParticipantId
                        + "\nRECENT_CONVERSATION:\n" + recent + "\nCURRENT_PLAYER_MESSAGE:\n" + currentText));
    }

    public static Set<DialogueExampleTag> allowedTags() {
        return ALLOWED_TAGS;
    }

    private static String instruction() {
        List<String> allowed = new ArrayList<>();
        for (DialogueExampleTag tag : ALLOWED_TAGS) {
            allowed.add(tag.name());
        }
        return "You classify one Minecraft RPG conversation turn. Return JSON only, with no prose: "
                + "{\"primarySituation\":string,\"secondarySituations\":[string],"
                + "\"knowledgeKeywords\":[string],\"playerToneTags\":[string],\"conversationAct\":string,\"confidence\":number}. "
                + "Choose only from these tags: " + String.join(", ", allowed) + ". "
                + "primarySituation and secondarySituations may contain only S_ tags. "
                + "knowledgeKeywords contains at most five short entity names or search nouns from the player message; "
                + "do not invent facts or IDs. playerToneTags contains at most three values from: T_POLITE, T_INFORMAL, "
                + "T_IMPOLITE, T_AGGRESSIVE, T_MOCKING, T_THREATENING, T_APOLOGETIC. "
                + "Use T_INFORMAL for casual Korean banmal only when relevant, not merely because a message is short. "
                + "Use T_IMPOLITE for disrespectful delivery, not ordinary casual speech. Do not write dialogue, world facts, "
                + "reasoning, quests, rewards, or proposals. conversationAct must be exactly one of: "
                + "UNSPECIFIED, GREETING, CASUAL_FEELING, SEEKING_COMPANY, ANSWERING_NPC_QUESTION, CORRECTING_NPC, "
                + "SHARING_RECENT_EVENT, REQUESTING_ACTIVITY, CASUAL_BANTER. Use SHARING_RECENT_EVENT when the player "
                + "mentions a recent experience without asking a question. Use RECENT_CONVERSATION to detect when the player "
                + "is answering or correcting the NPC rather than beginning a new topic.\n"
                + TurnInterpretation.classificationInstruction();
    }

    private static String latestDivineText(AiDialogueModels.SessionSnapshot session) {
        for (int index = session.history().size() - 1; index >= 0; index--) {
            AiDialogueModels.ConversationTurn turn = session.history().get(index);
            AiDialogueModels.Participant participant = session.participants().stream()
                    .filter(entry -> entry.participantId().equals(turn.speakerId())).findFirst().orElse(null);
            if (participant != null && participant.kind() == AiDialogueModels.ParticipantKind.DIVINE) {
                return turn.text();
            }
        }
        return "";
    }

    private static boolean isClearLowRiskTurn(String text) {
        return text.matches("^(안녕|안녕하세요|안녕하세용|반가워|고마워|감사|응|네|아니|그래|잘가|bye|hello|hi|thanks?)$")
                || text.matches("^(심심해|심심해요|심심하다|뭐해|뭐해요|뭐하고 있어|잘 지내|재미없어|응응|그래그래)$")
                || text.matches("^(ㅋㅋ+|ㅎㅎ+|…+|\\.+)$");
    }

    private static boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAmbiguousFastMatch(String text, DialogueExampleTag situation) {
        // Short colloquial requests such as "해봐" or "부탁" need the classifier and current relationship context.
        if (text.length() <= 5) {
            return true;
        }
        return situation == DialogueExampleTag.S_ITEM_REQUEST
                && !containsAny(text, "아이템", "물건", "장비", "무기", "검", "방어구", "만들어", "제작");
    }
}
