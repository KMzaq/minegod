package com.sande.mythai.response;

import java.util.ArrayList;
import java.util.List;

public final class DialogueTestArgumentsTest {
    private static int checks;
    private static final String DEMETER = "mythictrpg:olympus_demeter";
    private static final String FORTUNA = "mythictrpg:roman_fortuna";
    private static final List<DialogueTestArguments.God> GODS = List.of(
            new DialogueTestArguments.God(DEMETER, "데메테르"),
            new DialogueTestArguments.God(FORTUNA, "포르투나"),
            new DialogueTestArguments.God("mythictrpg:roman_lucky", "Lady Luck"),
            new DialogueTestArguments.God("other:olympus_demeter", "대지의 여신"),
            new DialogueTestArguments.God("mythictrpg:greek_tyche", "행운의 신"),
            new DialogueTestArguments.God("other:lucky", "행운의 신"));

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
        check(DialogueTestArguments.parseRecording("on"), "explicit recording on");
        check(!DialogueTestArguments.parseRecording("off"), "explicit recording off");
        for (String invalid : new String[]{null, "", "true", "false", "ON", "off ", "offf"}) {
            rejects(() -> DialogueTestArguments.parseRecording(invalid), "on 또는 off");
        }

        check(DialogueTestArguments.parseGods("데메테르 포르투나", GODS).equals(List.of(DEMETER, FORTUNA)),
                "multiple Korean display names, stable speaker order");
        check(DialogueTestArguments.parseGods(FORTUNA + ",\t데메테르", GODS).equals(List.of(FORTUNA, DEMETER)),
                "mixed IDs and names, comma and whitespace");
        check(DialogueTestArguments.parseGods("roman_fortuna", GODS).equals(List.of(FORTUNA)), "unique path");
        check(DialogueTestArguments.parseGods("포르투나 roman_fortuna " + FORTUNA + " 데메테르", GODS)
                .equals(List.of(FORTUNA, DEMETER)), "aliases do not add duplicate participants");
        check(DialogueTestArguments.parseGods("\"Lady Luck\",데메테르", GODS)
                .equals(List.of("mythictrpg:roman_lucky", DEMETER)), "quoted name with spaces");
        check(DialogueTestArguments.parseGods(DEMETER, GODS).equals(List.of(DEMETER)), "canonical ID disambiguates shared path");
        rejects(() -> DialogueTestArguments.parseGods("olympus_demeter", GODS), "여러 신과 일치");
        rejects(() -> DialogueTestArguments.parseGods("\"행운의 신\"", GODS), "여러 신과 일치");
        rejects(() -> DialogueTestArguments.parseGods("unknown:god", GODS), "알 수 없는 신");
        rejects(() -> DialogueTestArguments.parseGods("포르투나 unknown:god", GODS), "알 수 없는 신");
        rejects(() -> DialogueTestArguments.parseGods("포르투나", List.of()), "알 수 없는 신");
        rejects(() -> DialogueTestArguments.parseGods("", GODS), "한 명 이상");
        rejects(() -> DialogueTestArguments.parseGods(" , \t", GODS), "한 명 이상");
        rejects(() -> DialogueTestArguments.parseGods(null, GODS), "입력이 없거나");
        rejects(() -> DialogueTestArguments.parseGods("\"\"", GODS), "빈 신 이름");
        rejects(() -> DialogueTestArguments.parseGods("\"Lady Luck", GODS), "닫는 따옴표");
        rejects(() -> DialogueTestArguments.parseGods("\"Lady Luck\"데메테르", GODS), "구분");
        rejects(() -> DialogueTestArguments.parseGods("Lady\"Luck", GODS), "전체를 큰따옴표");
        rejects(() -> DialogueTestArguments.parseGods("\"Lady\\q\"", GODS), "이스케이프");
        rejects(() -> DialogueTestArguments.parseGods("x".repeat(2049), GODS), "너무 깁니다");

        List<DialogueTestArguments.God> escaped = List.of(new DialogueTestArguments.God("test:one", "A \"B\" \\ C"));
        check(DialogueTestArguments.parseGods("\"A \\\"B\\\" \\\\ C\"", escaped).equals(List.of("test:one")), "quoted escape round trip");
        List<DialogueTestArguments.God> many = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (int i = 0; i <= DialogueTestArguments.MAX_GODS; i++) {
            many.add(new DialogueTestArguments.God("test:god" + i, "신" + i));
            names.add("신" + i);
        }
        check(DialogueTestArguments.parseGods(String.join(" ", names.subList(0, DialogueTestArguments.MAX_GODS)), many)
                .size() == DialogueTestArguments.MAX_GODS, "participant limit inclusive");
        rejects(() -> DialogueTestArguments.parseGods(String.join(" ", names), many), "최대 16");

        List<String> selectionBeforeFailure = DialogueTestArguments.parseGods("포르투나", GODS);
        rejects(() -> DialogueTestArguments.parseGods("데메테르 unknown:god", GODS), "알 수 없는 신");
        check(selectionBeforeFailure.equals(List.of(FORTUNA)), "invalid parse cannot mutate an earlier selection");
        try {
            selectionBeforeFailure.add(DEMETER);
            throw new AssertionError("selection must be immutable");
        } catch (UnsupportedOperationException expected) { checks++; }

        check(DialogueTestArguments.suggestGods("포르투나 데", GODS).contains("포르투나 데메테르"), "second name completion");
        check(DialogueTestArguments.suggestGods("포르투나,데", GODS).contains("포르투나,데메테르"), "comma completion");
        check(DialogueTestArguments.suggestGods("데메테르 \"Lady ", GODS).contains("데메테르 \"Lady Luck\""), "unfinished quoted completion");
        check(DialogueTestArguments.suggestGods("데메테르 ", GODS).contains("데메테르 " + FORTUNA), "completed argument keeps prefix");
        check(DialogueTestArguments.suggestGods("데메테르 ", GODS).stream().noneMatch(value -> value.equals("데메테르 " + DEMETER)), "selected god not suggested again");
        check(!DialogueTestArguments.suggestGods("olympus_d", GODS).contains("olympus_demeter"), "ambiguous path not suggested");
        check(!DialogueTestArguments.suggestGods("행운", GODS).contains("\"행운의 신\""), "ambiguous name not suggested");
        check(DialogueTestArguments.suggestGods("unknown:god ", GODS).isEmpty(), "unknown previous selection cannot autocomplete as valid");
        check(DialogueTestArguments.suggestGods("\"Lady Luck\"broken ", GODS).isEmpty(), "malformed prefix has no suggestions");
        check(DialogueTestArguments.suggestGods(String.join(" ", names.subList(0, DialogueTestArguments.MAX_GODS)) + " ", many)
                .isEmpty(), "autocomplete respects selection cap");
        for (String suggestion : DialogueTestArguments.suggestGods("", GODS)) {
            check(DialogueTestArguments.parseGods(suggestion, GODS).size() == 1, "every completion resolves unambiguously");
        }
        System.out.println("DialogueTestArgumentsTest: " + checks + " checks passed");
    }
}
