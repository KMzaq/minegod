package com.sande.mythai.response;

import java.util.ArrayList;
import java.util.List;

public final class DialogueTestSpeakerPolicyTest {
    private static int checks;
    private static final String DEMETER = "mythictrpg:demeter";
    private static final String FORTUNA = "mythictrpg:fortuna";
    private static final String ATHENA = "mythictrpg:athena";
    private static final String HERMES = "mythictrpg:hermes";
    private static final List<DialogueTestArguments.God> GODS = List.of(
            new DialogueTestArguments.God(DEMETER, "데메테르"),
            new DialogueTestArguments.God(FORTUNA, "포르투나"),
            new DialogueTestArguments.God(ATHENA, "아테나"),
            new DialogueTestArguments.God(HERMES, "Hermes"));

    private static void check(boolean condition, String why) {
        checks++;
        if (!condition) throw new AssertionError(why);
    }

    private static void selects(String text, String previous, String expected, String why) {
        check(DialogueTestSpeakerPolicy.select(text, GODS, previous).equals(expected), why);
    }

    public static void main(String[] args) {
        selects("안녕하세요", null, DEMETER, "first participant starts");
        selects("그럼 어떻게 하면 될까?", ATHENA, ATHENA, "ordinary follow-up stays with actual third speaker");
        selects(null, FORTUNA, FORTUNA, "null input does not redirect an existing speaker");
        selects("네 말 알겠어", "foreign:god", DEMETER, "unknown prior speaker falls back inside audience");
        selects("아테나님은 어떻게 생각해?", DEMETER, ATHENA, "Korean direct address reaches third god");
        selects("mythictrpg:hermes 어떻게 생각해?", DEMETER, HERMES, "canonical ID reaches fourth god");
        selects("fortuna, 이야기해 줘", ATHENA, FORTUNA, "short path direct address");
        selects("HERMES, your turn", FORTUNA, HERMES, "Latin display names case insensitive");
        selects("Hermes야 말해봐", DEMETER, HERMES, "Korean particle after Latin name remains addressable");
        selects("the hermesology book", FORTUNA, FORTUNA, "Latin prefix inside unrelated word not a mention");
        selects("antihermes tactics", ATHENA, ATHENA, "Latin suffix inside unrelated word not a mention");
        selects("xmythictrpg:athena was a typo", FORTUNA, FORTUNA, "partial canonical ID is not an explicit target");
        selects("other:athena was mentioned", DEMETER, DEMETER, "foreign namespace does not match selected path");
        selects("other:Hermes was mentioned", DEMETER, DEMETER, "foreign namespace does not match a Latin display name either");
        selects("book/athena_notes", FORTUNA, FORTUNA, "path fragment is not a god mention");
        selects("미참가 신 제우스에게 질문할게", ATHENA, ATHENA, "unknown god never enters participant list");

        String previous = null;
        for (String expected : List.of(DEMETER,FORTUNA,ATHENA,HERMES,DEMETER)) {
            String selected = DialogueTestSpeakerPolicy.select("모두 한마디 해줘", GODS, previous);
            check(selected.equals(expected), "group round-robin reaches every god and wraps");
            previous = selected;
        }
        for (String cue : List.of("너희", "각자", "다들", "다음 신", "다른 신", "다음  신", "다른신")) {
            selects(cue, FORTUNA, ATHENA, "group cue rotates: " + cue);
        }
        selects("모두 기다려, 아테나에게 물을게", DEMETER, ATHENA, "explicit named recipient overrides group cue");
        selects("아테나, 포르투나에게 물을게", null, ATHENA, "multiple mentions start in text order, not catalog order");
        selects("아테나, 포르투나에게 물을게", ATHENA, FORTUNA, "multiple mentions rotate only named participants");
        selects("아테나, 포르투나에게 물을게", FORTUNA, ATHENA, "multiple mentions wrap");
        selects("아테나, 포르투나에게 물을게", HERMES, ATHENA, "previous outside named subset starts first mention");
        selects("포르투나, 아테나, 포르투나", FORTUNA, ATHENA, "repeated named aliases do not steal next turn");
        selects("아테나", ATHENA, ATHENA, "one explicit name does not rotate to a different god");

        var nestedNames = List.of(new DialogueTestArguments.God("test:hera", "헤라"),
                new DialogueTestArguments.God("test:heracles", "헤라클레스"));
        check(DialogueTestSpeakerPolicy.select("헤라클레스님이 대답해 줘", nestedNames, "test:heracles")
                .equals("test:heracles"), "longest Korean name wins over embedded shorter name");
        check(DialogueTestSpeakerPolicy.select("헤라와 헤라클레스", nestedNames, "test:hera")
                .equals("test:heracles"), "separate shorter mention is kept alongside longer mention");
        var spacedNames = List.of(new DialogueTestArguments.God("test:luck", "Lady Luck"),
                new DialogueTestArguments.God("test:lady", "Lady"));
        check(DialogueTestSpeakerPolicy.select("Lady Luck, tell me", spacedNames, "test:luck").equals("test:luck"),
                "longest multi-word Latin display name wins");
        var duplicated = new ArrayList<>(GODS); duplicated.add(GODS.get(0));
        check(DialogueTestSpeakerPolicy.select("다음 신", duplicated, HERMES).equals(DEMETER), "duplicate catalog IDs do not duplicate a rotation slot");
        check(DialogueTestSpeakerPolicy.select("모두 말해줘", GODS.subList(0,1), DEMETER).equals(DEMETER), "single participant wraps to itself");
        var all = new ArrayList<DialogueTestArguments.God>();
        for (int i=0;i<DialogueTestArguments.MAX_GODS;i++) all.add(new DialogueTestArguments.God("test:god_"+i,"신격"+i));
        previous = null;
        for (var god : all) {
            String selected = DialogueTestSpeakerPolicy.select("다음 신", all, previous);
            check(selected.equals(god.id()), "all sixteen configured participants reachable"); previous = selected;
        }
        try {
            DialogueTestSpeakerPolicy.select("안녕", List.of(), null);
            throw new AssertionError("empty audience should not invent a god");
        } catch (IllegalArgumentException expected) { checks++; }
        all.add(new DialogueTestArguments.God("test:overflow", "초과"));
        try {
            DialogueTestSpeakerPolicy.select("모두", all, null);
            throw new AssertionError("over-limit audience should fail");
        } catch (IllegalArgumentException expected) { checks++; }
        System.out.println("DialogueTestSpeakerPolicyTest: " + checks + " checks passed");
    }
}
