package com.sande.mythictrpg.ai;

import com.sande.mythictrpg.ai.example.DialogueExampleTag;
import com.sande.mythictrpg.ai.intent.ConversationAct;
import com.sande.mythictrpg.ai.intent.ConversationIntent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A small, deterministic plan for one reply. It keeps the player's immediate social intent separate from an NPC's
 * long-lived identity, so a chance deity does not turn every greeting into a lesson about chance.
 */
final class DialogueTurnDirective {
    enum Mode {
        GREETING,
        REPEATED_GREETING,
        CASUAL_FEELING,
        SMALL_TALK,
        INFORMATION,
        REQUEST,
        APOLOGY,
        CONFLICT,
        UNKNOWN_REFERENCE,
        GENERAL
    }

    private final Mode mode;
    private final String replyGoal;
    private final List<String> rules;
    private final boolean proposalsAllowed;
    private final int maximumNpcReplies;
    private final int preferredSpeechCharacters;

    private DialogueTurnDirective(Mode mode, String replyGoal, List<String> rules, boolean proposalsAllowed,
            int maximumNpcReplies, int preferredSpeechCharacters) {
        this.mode = mode;
        this.replyGoal = replyGoal;
        this.rules = List.copyOf(rules);
        this.proposalsAllowed = proposalsAllowed;
        this.maximumNpcReplies = maximumNpcReplies;
        this.preferredSpeechCharacters = preferredSpeechCharacters;
    }

    static DialogueTurnDirective plan(AiDialogueModels.SessionSnapshot session, String triggeringParticipantId,
            ConversationIntent intent) {
        String text = triggeringText(session, triggeringParticipantId);
        List<String> history = session.history().stream().map(AiDialogueModels.ConversationTurn::text).toList();
        int divineCount = (int) session.visibleParticipants().stream()
                .filter(participant -> participant.kind() == AiDialogueModels.ParticipantKind.DIVINE).count();
        return plan(text, history, divineCount, intent);
    }

    static DialogueTurnDirective recall() {
        return new DialogueTurnDirective(Mode.INFORMATION,
                "Answer the player's request about earlier words or observed actions using RECALL_REQUEST and authorized evidence.",
                List.of("Preserve persona and actual relationship; this is recall, not a new event or NPC-question answer.",
                        "Ambiguous/missing evidence needs a brief clarification, not invented history or hostility.",
                        "Statements and plans do not establish completed game actions. Return proposals as an empty array."),
                false, 1, 120);
    }

    static DialogueTurnDirective plan(String playerText, List<String> history, int divineCount,
            ConversationIntent intent) {
        String normalized = playerText == null ? "" : playerText.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
        Set<DialogueExampleTag> tags = intent == null ? Set.of() : intent.tags();
        List<String> previousTurns = new ArrayList<>(history);
        if (!previousTurns.isEmpty() && normalize(previousTurns.get(previousTurns.size() - 1)).equals(normalized)) {
            previousTurns.remove(previousTurns.size() - 1);
        }
        boolean unknownReference = containsAny(normalized, "그 장소", "그곳", "그 마을", "그 사람", "그 신", "그 아이템")
                && previousTurns.stream().noneMatch(turn -> turn != null && turn.contains(referenceNoun(normalized)));
        boolean repeated = previousTurns.stream().map(DialogueTurnDirective::normalize).anyMatch(normalized::equals);
        String casualNeed = casualFeelingNeed(normalized);
        int priorMatchingExpressions = casualNeed.isBlank()
                ? (int) previousTurns.stream().map(DialogueTurnDirective::normalize).filter(normalized::equals).count()
                : (int) previousTurns.stream().map(DialogueTurnDirective::casualFeelingNeed)
                        .filter(casualNeed::equals).count();
        int maxReplies = divineCount > 1 && containsAny(normalized, "둘", "두 신", "너희", "모두") ? 2 : 1;
        List<String> common = new ArrayList<>();
        common.add("Answer the literal social meaning of PLAYER_TEXT before using persona motifs.");
        common.add("Do not repeat the last NPC wording or treat a repeated player line as new evidence about the player.");
        common.add("Prefer one short, complete sentence. Do not add a lesson, explanation, example, or follow-up unless it is needed to answer clearly.");
        if (intent != null && intent.conversationAct() == ConversationAct.ANSWERING_NPC_QUESTION) {
            common.add("PLAYER_TEXT directly answers the NPC's previous question. Acknowledge that answer before continuing; do not turn its words into a philosophical theme or ask the same question again.");
        }
        if (repeated) {
            common.add("The player repeated themself. Acknowledge that naturally or answer from a fresh angle; do not repeat the prior NPC reply.");
        }
        if (unknownReference) {
            common.add("The referent is not identified by current context. Ask what the player means or say that the context is insufficient; do not claim personal experience of it.");
            return new DialogueTurnDirective(Mode.UNKNOWN_REFERENCE,
                    "Clarify the unidentified referent without inventing a world fact.", common, false, maxReplies, 48);
        }
        if (isPlainGreeting(normalized)) {
            if (repeated) {
                common.add("PLAYER_TEXT repeats a plain greeting. Notice that naturally and move the exchange forward with a different short reply; never repeat the exact greeting.");
                common.add("Return proposals as an empty array.");
                return new DialogueTurnDirective(Mode.REPEATED_GREETING,
                        "Acknowledge the repeated greeting with a fresh, brief social response.", common, false, maxReplies, 24);
            }
            common.add("PLAYER_TEXT is a plain greeting. Reply with only a natural Korean greeting; do not add a question, observation, or new topic.");
            common.add("Return proposals as an empty array.");
            return new DialogueTurnDirective(Mode.GREETING,
                    "Return a brief, natural greeting in the NPC's voice.", common, false, maxReplies, 18);
        }
        if (isCasualFeeling(normalized) && !hasRequestIntent(tags, normalized)) {
            common.add("Treat this as an ordinary social feeling, not a philosophical question.");
            common.add("Do not define, interpret, diagnose, reframe, or teach about the player's feeling. Never use the pattern 'being bored means...' or an equivalent explanation.");
            common.add("For boredom, never call boredom a sign, signal, meaning, opportunity, fate, or lesson. Do not restate it as an abstract concept.");
            common.add("Reply like a present conversation partner: briefly acknowledge it, tease lightly when appropriate, or ask one concrete conversational question. Do not speak about boredom itself as a general subject.");
            common.add("Do not introduce fate, chance, choices, tests, quests, rewards, locations, items, walking, travel, roaming, or a joint physical activity.");
            if (priorMatchingExpressions > 0) {
                common.add("The player has expressed this same underlying feeling " + (priorMatchingExpressions + 1)
                        + " times in the visible conversation, even if the wording changed. Read the earlier NPC replies and treat this as an ongoing, unresolved social beat.");
                common.add("Generate a fresh response that reacts to the accumulated exchange: notice that the earlier approach did not land, then either respond to the repetition itself, change the conversational angle, or let the player state what they want. Do not emit a canned escalation, repeat a previous suggestion, or mistake this repetition for an answer to the NPC's last question.");
            }
            common.add("Return proposals as an empty array.");
            return new DialogueTurnDirective(Mode.CASUAL_FEELING,
                    "Respond naturally to the player's casual feeling without explaining it.", common, false, maxReplies, 48);
        }
        if (intent != null && intent.conversationAct() == ConversationAct.SHARING_RECENT_EVENT) {
            common.add("PLAYER_TEXT shares a recent event rather than requesting lore or a solution. React to it as a present conversation partner.");
            common.add("Do not repeat facts already stated by the player, force a lesson, or introduce fate, chance, choice, tests, quests, rewards, locations, or items.");
            common.add("A short acknowledgement, light in-character reaction, or one relevant follow-up question is enough.");
            common.add("A reactive server-authorized proposal is allowed only when the event clearly warrants an immediate in-character action.");
            return new DialogueTurnDirective(Mode.SMALL_TALK,
                    "React naturally to the recent event the player shared.", common, true, maxReplies, 44);
        }
        if ((tags.contains(DialogueExampleTag.S_CHAT) || isSmallTalk(normalized))
                && !hasRequestIntent(tags, normalized)) {
            common.add("Treat this as ordinary small talk. Be light, direct, and socially responsive.");
            common.add("Use one short sentence, normally only a greeting, acknowledgement, or light question.");
            common.add("Do not introduce chance, fate, choice, tests, quests, rewards, locations, or items unless PLAYER_TEXT or live history already introduced that subject.");
            common.add("A reactive server-authorized proposal is allowed only when this social gesture clearly warrants an immediate in-character action.");
            return new DialogueTurnDirective(Mode.SMALL_TALK,
                    "Respond naturally to the player's immediate feeling or social gesture.", common, true, maxReplies, 28);
        }
        if ((tags.contains(DialogueExampleTag.S_INFORMATION_REQUEST) || containsAny(normalized, "왜", "어떻게", "무엇", "뭐야", "알려"))
                && !hasRequestIntent(tags, normalized)) {
            common.add("Give the best direct answer supported by KNOWN_LORE and CURRENT_GAME_CONTEXT. Say what is unknown instead of filling gaps.");
            common.add("Use more than one sentence only when the requested fact or quest detail would otherwise be unclear.");
            return new DialogueTurnDirective(Mode.INFORMATION, "Answer the player's question directly.", common, false, maxReplies, 112);
        }
        if (tags.contains(DialogueExampleTag.S_APOLOGY) || containsAny(normalized, "미안", "사과")) {
            common.add("Read RECENT_CONVERSATION to identify what the player is apologizing for. If a specific offense is visible, react to that exact exchange before judging or advising.");
            common.add("Use an immediate conversational response, not a proverb, verdict, or general lesson about apologies. If the offense is unclear, simply receive the apology and ask what they mean only when needed.");
            common.add("Keep the emotional reaction proportionate to RELATIONSHIP and the visible exchange; do not declare forgiveness, punishment, or a relationship change as completed.");
            return new DialogueTurnDirective(Mode.APOLOGY, "React naturally to the player's apology.", common, true, maxReplies, 56);
        }
        if (tags.contains(DialogueExampleTag.S_CONFLICT) || containsAny(normalized, "싫어", "거짓말", "화났", "꺼져")) {
            common.add("Address the disagreement directly. A server-authorized reactive action may be proposed when proportionate to the NPC and scene, but never claim it succeeded.");
            return new DialogueTurnDirective(Mode.CONFLICT, "Address the conflict without inventing consequences.", common,
                    true, maxReplies, 56);
        }
        boolean request = tags.contains(DialogueExampleTag.S_ITEM_REQUEST)
                || tags.contains(DialogueExampleTag.S_POWER_REQUEST)
                || tags.contains(DialogueExampleTag.S_HELP_REQUEST)
                || tags.contains(DialogueExampleTag.S_QUEST_INQUIRY)
                || tags.contains(DialogueExampleTag.S_REWARD_NEGOTIATION)
                || tags.contains(DialogueExampleTag.S_GIFT_OFFER)
                || containsAny(normalized, "줄 수", "가호", "보상", "퀘스트", "도와", "필요한 거 있어", "준비됐", "준비했", "가져왔", "챙겨왔", "다 모았", "건넬게", "받아", "넣어뒀", "상자에 넣었");
        if (request) {
            common.add("State the NPC's narrative stance first. A proposal may describe only a supported future possibility; it never confirms delivery or execution.");
            common.add("Add detail only when a condition, limitation, or quest-specific fact needs clarification.");
            return new DialogueTurnDirective(Mode.REQUEST, "Respond to the player's request without claiming it was fulfilled.",
                    common, true, maxReplies, 88);
        }
        common.add("Stay on the player's subject. Persona motifs are optional flavor, never a replacement topic.");
        common.add("A reactive server-authorized proposal is allowed only when the immediate scene clearly warrants it.");
        return new DialogueTurnDirective(Mode.GENERAL, "Respond directly and naturally to PLAYER_TEXT.", common, true,
                maxReplies, 56);
    }

    Mode mode() {
        return mode;
    }

    String replyGoal() {
        return replyGoal;
    }

    List<String> rules() {
        return rules;
    }

    boolean proposalsAllowed() {
        return proposalsAllowed;
    }

    int maximumNpcReplies() {
        return maximumNpcReplies;
    }

    int preferredSpeechCharacters() {
        return preferredSpeechCharacters;
    }

    boolean requiresFreshReply() {
        return mode == Mode.REPEATED_GREETING;
    }

    boolean isSocialOnly() {
        return mode == Mode.CASUAL_FEELING;
    }

    String repeatedGreetingFallback() {
        return "또 인사하네.";
    }

    String naturalSpeech(String rawText) {
        if (mode == Mode.GREETING) {
            return "안녕.";
        }
        return rawText == null ? "" : rawText.replaceAll("\s+", " ").trim();
    }

    String promptBlock() {
        StringBuilder value = new StringBuilder("[TURN_DIRECTIVE]\nmode: ").append(mode)
                .append("\nreply_goal: ").append(replyGoal)
                .append("\nproposal_policy: ").append(proposalsAllowed ? "only when supported by the current turn and server-authorized capabilities" : "[]")
                .append("\nmaximum_npc_replies: ").append(maximumNpcReplies)
                .append("\npreferred_speech_characters: ").append(preferredSpeechCharacters)
                .append("\nrules:\n");
        for (String rule : rules) {
            value.append("- ").append(rule).append("\n");
        }
        return value.toString();
    }

    private static String triggeringText(AiDialogueModels.SessionSnapshot session, String triggeringParticipantId) {
        for (int index = session.history().size() - 1; index >= 0; index--) {
            AiDialogueModels.ConversationTurn turn = session.history().get(index);
            if (triggeringParticipantId.equals(turn.speakerId())) {
                return turn.text();
            }
        }
        return "";
    }

    private static boolean hasRequestIntent(Set<DialogueExampleTag> tags, String text) {
        return tags.contains(DialogueExampleTag.S_ITEM_REQUEST)
                || tags.contains(DialogueExampleTag.S_POWER_REQUEST)
                || tags.contains(DialogueExampleTag.S_HELP_REQUEST)
                || tags.contains(DialogueExampleTag.S_QUEST_INQUIRY)
                || tags.contains(DialogueExampleTag.S_REWARD_NEGOTIATION)
                || tags.contains(DialogueExampleTag.S_GIFT_OFFER)
                || containsAny(text, "퀘스트", "의뢰", "임무", "필요한 거", "할 일", "아이템", "물건", "가호",
                        "힘을 줘", "도와", "도움", "보상", "대가", "선물", "공물", "줄래", "줘봐", "해줘",
                        "준비됐", "준비했", "가져왔", "챙겨왔", "다 모았", "건넬게", "받아", "넣어뒀", "상자에 넣었");
    }

    private static boolean isSmallTalk(String text) {
        return containsAny(text, "안녕", "반가", "심심", "잘 지냈", "기분 어때", "뭐 해", "ㅎㅎ", "ㅋㅋ");
    }

    private static boolean isCasualFeeling(String text) {
        return !casualFeelingNeed(text).isBlank();
    }

    /** Mirrors the adapter's turn-local grouping without coupling this directive to Minecraft session classes. */
    private static String casualFeelingNeed(String text) {
        if (containsAny(text, "심심", "지루")) {
            return "BOREDOM";
        }
        if (containsAny(text, "피곤", "졸려")) {
            return "FATIGUE";
        }
        if (containsAny(text, "배고파", "배고프")) {
            return "HUNGER";
        }
        if (containsAny(text, "외로")) {
            return "LONELINESS";
        }
        if (containsAny(text, "기분이 별로", "기분 별로")) {
            return "LOW_MOOD";
        }
        return "";
    }

    private static boolean isPlainGreeting(String text) {
        return text.matches("^(안녕|안녕하세요|안녕하십니까|반가워|반갑다|왔어|왔네)[!?.~]*$");
    }

    private static int firstSentenceEnd(String text) {
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character == '.' || character == '!' || character == '?' || character == '\u3002') {
                return index + 1;
            }
        }
        return -1;
    }

    private static String referenceNoun(String text) {
        for (String noun : List.of("장소", "곳", "마을", "사람", "신", "아이템")) {
            if (text.contains(noun)) {
                return noun;
            }
        }
        return "";
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
