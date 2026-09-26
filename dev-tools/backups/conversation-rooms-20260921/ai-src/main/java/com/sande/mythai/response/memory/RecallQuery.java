package com.sande.mythai.response.memory;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

/** Player-only discourse state. Never carries NPC claims, selected facts or game authority. */
public record RecallQuery(String text, boolean explicit, boolean followUp, long askedAt, Focus focus, Scope scope) {
    public record Scope(MemoryJournal.Key key, UUID generation, Set<UUID> audience) {
        public Scope { audience = Set.copyOf(audience); }
    }
    public record Focus(Scope scope, String question, long firstTurn, long askedAt) {}
    public static RecallQuery plan(Scope scope, String input, long turn, long now, Focus previous) {
        String text = input == null ? "" : input.trim();
        if (topicChange(text) || text.matches("^(응|네|알겠.*|고마워.*|됐어.*|이해했.*)[.!~ ]*$"))
            return new RecallQuery(text, false, false, now, null, scope);
        if (previous != null && previous.scope().equals(scope) && turn > previous.firstTurn()
                && turn - previous.firstTurn() <= 3 && now >= previous.askedAt()
                && now - previous.askedAt() <= Duration.ofMinutes(5).toMillis()
                && bareFollowUp(text))
            return new RecallQuery(previous.question(), true, true, previous.askedAt(), previous, scope);
        if (explicitRecall(text)) {
            Focus next = new Focus(scope, text, turn, now);
            return new RecallQuery(text, true, false, now, next, scope);
        }
        return new RecallQuery(text, false, false, now, null, scope);
    }
    public static boolean bareFollowUp(String text) {
        String compact = text.replaceAll("\\s+", "");
        return compact.length() <= 60 && compact.matches("^(아니|제발|좀|그거|그걸|그때|다시|한번만|한번|이번엔)*"
                + "(알려줘|알려주라|알려줄래|알려주세요|말해줘|말해주라|말해줄래|말해봐|말해주세요|기억나|기억해|기억해봐)[.!?？~]*$");
    }
    public static boolean topicChange(String text) {
        return text.matches(".*(다른 얘기|다른 이야기|화제.*바|주제.*바|그건 ?됐|그만|아무튼|anyway|change the subject).*");
    }
    public static boolean explicitRecall(String text) {
        if (topicChange(text)) return false;
        boolean question = text.matches(".*(어디|언제|누구|무엇|뭐|얼마|어떤|왜|어떻게).*");
        boolean reported = text.matches(".*(했더라|했지|했었|했던|했는지|했을까|했냐|했어요|말한|말했|얘기했|이야기했|라고|다고|하기로).*");
        return (question && reported) || text.matches(".*(기억나|기억해\\s*[?？]|기억하니|기억하고 있|기억해줘야|기억나는|기억해 줄|remember.*[?]).*")
                || (question && text.matches(".*(약속|계획|일정).*") && text.matches(".*(내 |내가|우리|전에|지난|어제|내일).*") );
    }
    public boolean planQuestion() {
        return text.matches(".*(계획|일정|예정|약속|내일|모레|어디.*[가간갈]|언제.*[가간갈]).*");
    }
}
