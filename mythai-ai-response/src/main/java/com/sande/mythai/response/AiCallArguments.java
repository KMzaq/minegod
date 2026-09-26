package com.sande.mythai.response;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Exact God IDs for /ai_call; parsing never starts or changes a conversation. */
public final class AiCallArguments {
    public static final int MAX_GODS = 16;
    public static final int MAX_INPUT_LENGTH = 2048;
    private static final Pattern GOD_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");

    private AiCallArguments() {}

    /** Accepts only namespace:path IDs separated by whitespace or commas. */
    public static List<String> parseGodIds(String input, Collection<DialogueTestArguments.God> catalog) {
        List<Token> tokens = tokenize(input);
        if (tokens.isEmpty()) throw new IllegalArgumentException("대화할 신 ID를 한 개 이상 입력하세요.");
        Set<String> available = catalogIds(catalog);
        Set<String> selected = new LinkedHashSet<>();
        for (Token token : tokens) {
            selected.add(requireGodId(token.value(), available));
            if (selected.size() > MAX_GODS) {
                throw new IllegalArgumentException("신은 한 번에 최대 " + MAX_GODS + "명까지 선택할 수 있습니다.");
            }
        }
        return List.copyOf(selected);
    }

    /** Suggestions replace the entire greedy argument and contain only full God IDs. */
    public static List<String> suggestGodIds(String input, Collection<DialogueTestArguments.God> catalog) {
        if (input == null || input.length() > MAX_INPUT_LENGTH) return List.of();
        List<Token> tokens = tokenize(input);
        int replacementStart = input.length();
        String partial = "";
        int completed = tokens.size();
        if (!tokens.isEmpty() && tokens.getLast().end() == input.length()) {
            Token last = tokens.getLast();
            replacementStart = last.start();
            partial = last.value();
            completed--;
        }
        Set<String> available = catalogIds(catalog);
        Set<String> selected = new LinkedHashSet<>();
        try {
            for (int i = 0; i < completed; i++) {
                selected.add(requireGodId(tokens.get(i).value(), available));
            }
        } catch (IllegalArgumentException invalidPrefix) {
            return List.of();
        }
        if (selected.size() >= MAX_GODS) return List.of();
        String prefix = input.substring(0, replacementStart);
        List<String> suggestions = new ArrayList<>();
        for (String id : available) {
            if (!selected.contains(id) && id.startsWith(partial)
                    && prefix.length() + id.length() <= MAX_INPUT_LENGTH) {
                suggestions.add(prefix + id);
            }
        }
        return List.copyOf(suggestions);
    }

    private static Set<String> catalogIds(Collection<DialogueTestArguments.God> catalog) {
        Set<String> ids = new LinkedHashSet<>();
        for (DialogueTestArguments.God god : catalog) ids.add(god.id());
        return ids;
    }

    private static String requireGodId(String id, Set<String> available) {
        if (!GOD_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("전체 신 ID(namespace:path)만 입력하세요: " + id
                    + ". 한글 이름과 짧은 ID는 사용할 수 없습니다.");
        }
        if (!available.contains(id)) throw new IllegalArgumentException("등록된 AI 프로필이 없는 신 ID입니다: " + id);
        return id;
    }

    private static List<Token> tokenize(String input) {
        if (input == null) throw new IllegalArgumentException("신 ID 입력이 없습니다.");
        if (input.length() > MAX_INPUT_LENGTH) {
            throw new IllegalArgumentException("신 ID 입력은 최대 " + MAX_INPUT_LENGTH + "자까지 가능합니다.");
        }
        List<Token> tokens = new ArrayList<>();
        int cursor = 0;
        while (cursor < input.length()) {
            if (separator(input.charAt(cursor))) { cursor++; continue; }
            int start = cursor;
            while (cursor < input.length() && !separator(input.charAt(cursor))) cursor++;
            tokens.add(new Token(input.substring(start, cursor), start, cursor));
        }
        return tokens;
    }

    private static boolean separator(char value) {
        return Character.isWhitespace(value) || value == ',';
    }

    private record Token(String value, int start, int end) {}
}
