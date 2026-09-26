package com.sande.mythai.response;

import java.util.ArrayList;
import java.util.List;

public final class AiCallArgumentsTest {
    private static int checks;
    private static final String DEMETER = "mythictrpg:olympus_demeter";
    private static final String FORTUNA = "mythictrpg:roman_fortuna";
    private static final String OTHER_DEMETER = "other:olympus_demeter";
    private static final List<DialogueTestArguments.God> GODS = List.of(
            new DialogueTestArguments.God(DEMETER, "데메테르"),
            new DialogueTestArguments.God(FORTUNA, "포르투나"),
            new DialogueTestArguments.God(OTHER_DEMETER, "데메테르"),
            new DialogueTestArguments.God("test:pretender", "unknown:display_alias"),
            new DialogueTestArguments.God("mod-name.1:family/path_2.test-3", "Quoted Name"));

    private static void check(boolean condition, String description) {
        checks++;
        if (!condition) throw new AssertionError(description);
    }

    private static void rejects(Runnable action, String messagePart) {
        try {
            action.run();
            throw new AssertionError("Expected rejection containing: " + messagePart);
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains(messagePart), "useful failure: " + messagePart);
        }
    }

    public static void main(String[] args) {
        check(AiCallArguments.parseGodIds(DEMETER, GODS).equals(List.of(DEMETER)), "exact full ID");
        check(AiCallArguments.parseGodIds(FORTUNA + ",\t" + DEMETER + "\n" + OTHER_DEMETER, GODS)
                .equals(List.of(FORTUNA, DEMETER, OTHER_DEMETER)), "mixed separators preserve selection order");
        check(AiCallArguments.parseGodIds(" , " + FORTUNA + ",," + DEMETER + " " + FORTUNA + ", ", GODS)
                .equals(List.of(FORTUNA, DEMETER)), "duplicate full IDs select a god only once");
        check(AiCallArguments.parseGodIds("mod-name.1:family/path_2.test-3", GODS)
                .equals(List.of("mod-name.1:family/path_2.test-3")), "ResourceLocation punctuation and path separators");
        for (String invalid : new String[]{"데메테르", "olympus_demeter", "roman_fortuna", "\"" + DEMETER + "\"",
                "mythictrpg:데메테르", "MYTHICTRPG:olympus_demeter", "mythictrpg:Olympus_demeter",
                ":olympus_demeter", "mythictrpg:", "mythictrpg::olympus_demeter", "bad/namespace:god", "mod:god!"}) {
            rejects(() -> AiCallArguments.parseGodIds(invalid, GODS), "전체 신 ID");
        }
        rejects(() -> AiCallArguments.parseGodIds("unknown:god", GODS), "AI 프로필이 없는");
        rejects(() -> AiCallArguments.parseGodIds("unknown:display_alias", GODS), "AI 프로필이 없는");
        rejects(() -> AiCallArguments.parseGodIds(DEMETER, List.of()), "AI 프로필이 없는");
        rejects(() -> AiCallArguments.parseGodIds("", GODS), "한 개 이상");
        rejects(() -> AiCallArguments.parseGodIds(" ,\t\n", GODS), "한 개 이상");
        rejects(() -> AiCallArguments.parseGodIds(null, GODS), "입력이 없습니다");
        rejects(() -> AiCallArguments.parseGodIds("x".repeat(2049), GODS), "최대 2048");
        String boundaryInput = " ".repeat(AiCallArguments.MAX_INPUT_LENGTH - DEMETER.length()) + DEMETER;
        check(AiCallArguments.parseGodIds(boundaryInput, GODS).equals(List.of(DEMETER)), "2048 character boundary inclusive");

        List<DialogueTestArguments.God> many = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i <= AiCallArguments.MAX_GODS; i++) {
            String id = "test:god" + i;
            many.add(new DialogueTestArguments.God(id, "신" + i));
            ids.add(id);
        }
        String sixteen = String.join(" ", ids.subList(0, AiCallArguments.MAX_GODS));
        check(AiCallArguments.parseGodIds(sixteen, many).size() == AiCallArguments.MAX_GODS, "16 distinct gods accepted");
        check(AiCallArguments.parseGodIds(sixteen + " " + ids.getFirst(), many).size() == AiCallArguments.MAX_GODS,
                "duplicate ID does not consume another participant slot");
        rejects(() -> AiCallArguments.parseGodIds(String.join(" ", ids), many), "최대 16");
        List<String> previous = AiCallArguments.parseGodIds(FORTUNA, GODS);
        rejects(() -> AiCallArguments.parseGodIds(DEMETER + " unknown:god", GODS), "AI 프로필이 없는");
        rejects(() -> AiCallArguments.parseGodIds(DEMETER + " 포르투나", GODS), "전체 신 ID");
        check(previous.equals(List.of(FORTUNA)), "failed parse cannot change an earlier selection");
        try {
            previous.add(DEMETER);
            throw new AssertionError("selection must be immutable");
        } catch (UnsupportedOperationException expected) { checks++; }

        check(AiCallArguments.suggestGodIds("", GODS).equals(GODS.stream().map(DialogueTestArguments.God::id).toList()),
                "empty completion contains only full IDs in catalog order");
        check(AiCallArguments.suggestGodIds("myth", GODS).equals(List.of(DEMETER, FORTUNA)), "partial namespace completion");
        check(AiCallArguments.suggestGodIds(FORTUNA + " mythictrpg:o", GODS).equals(List.of(FORTUNA + " " + DEMETER)),
                "last partial is replaced within the entire greedy argument");
        check(AiCallArguments.suggestGodIds(FORTUNA + ",\tmythictrpg:o", GODS).equals(List.of(FORTUNA + ",\t" + DEMETER)),
                "completion preserves separators exactly");
        check(AiCallArguments.suggestGodIds(DEMETER + " ", GODS).contains(DEMETER + " " + FORTUNA), "trailing separator appends ID");
        check(!AiCallArguments.suggestGodIds(DEMETER + " ", GODS).contains(DEMETER + " " + DEMETER), "selected ID excluded");
        check(AiCallArguments.suggestGodIds(DEMETER + " mythictrpg:o", GODS).isEmpty(), "selected partial ID excluded");
        check(AiCallArguments.suggestGodIds(sixteen + " ", many).isEmpty(), "no seventeenth participant suggestion");
        check(AiCallArguments.suggestGodIds(sixteen, many).contains(sixteen), "sixteenth ID can still be completed");
        check(AiCallArguments.suggestGodIds(ids.getFirst() + " " + ids.getFirst() + " test:g", many)
                .contains(ids.getFirst() + " " + ids.getFirst() + " " + ids.get(1)), "duplicate prefixes share one slot");
        for (String invalid : new String[]{"데", "olympus_d", "MYTH", "\"mythictrpg:o", "unknown:god ",
                "데메테르 ", "roman_fortuna ", DEMETER + " bad:id ", String.join(" ", ids) + " "}) {
            check(AiCallArguments.suggestGodIds(invalid, GODS).isEmpty(), "invalid or unresolved prefix has no suggestions: " + invalid);
        }
        check(AiCallArguments.suggestGodIds(null, GODS).isEmpty(), "null completion is harmless");
        check(AiCallArguments.suggestGodIds("x".repeat(2049), GODS).isEmpty(), "overlong completion is rejected");
        check(AiCallArguments.suggestGodIds(" ".repeat(2048), GODS).isEmpty(), "suggestions cannot exceed input limit");
        check(AiCallArguments.suggestGodIds(boundaryInput, GODS).equals(List.of(boundaryInput)), "boundary completion remains valid");
        check(AiCallArguments.suggestGodIds("", List.of(GODS.getFirst(), GODS.getFirst())).equals(List.of(DEMETER)),
                "duplicate catalog entries do not duplicate suggestions");
        for (String input : List.of("", "myth", FORTUNA + ", mythictrpg:", DEMETER + " ")) {
            for (String suggestion : AiCallArguments.suggestGodIds(input, GODS)) {
                check(!AiCallArguments.parseGodIds(suggestion, GODS).isEmpty(), "every suggestion is accepted by the exact ID parser");
            }
        }
        System.out.println("AiCallArgumentsTest: " + checks + " checks passed");
    }
}
