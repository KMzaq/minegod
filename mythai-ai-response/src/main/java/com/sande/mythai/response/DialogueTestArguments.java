package com.sande.mythai.response;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Pure parsing of the administrator's selection, before any conversation is changed. */
public final class DialogueTestArguments {
    public static final int MAX_GODS = 16;
    private static final int MAX_INPUT_LENGTH = 2048;

    private DialogueTestArguments() {}

    /** The caller supplies the live game registry; this is not a separate god registry. */
    public record God(String id, String displayName) {
        public God {
            Objects.requireNonNull(id, "id");
            if (!id.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) {
                throw new IllegalArgumentException("유효하지 않은 신 ID: " + id);
            }
            displayName = displayName == null ? "" : displayName;
        }

        public String path() {
            return id.substring(id.indexOf(':') + 1);
        }
    }

    /** Deliberately does not accept true/false or an omitted logging decision. */
    public static boolean parseRecording(String value) {
        if ("on".equals(value)) return true;
        if ("off".equals(value)) return false;
        throw new IllegalArgumentException("기록 여부는 on 또는 off로 입력하세요.");
    }

    /**
     * Accepts canonical IDs, exact short paths, or exact display names. Separators are
     * whitespace and commas; double quotes allow names containing separators. Duplicate
     * aliases select the same god only once and preserve the first appearance order.
     * Invalid input returns no partial result, so callers can validate before starting.
     */
    public static List<String> parseGods(String input, Collection<God> catalog) {
        List<Token> tokens = tokenize(input, false);
        if (tokens.isEmpty()) throw new IllegalArgumentException("대화할 신을 한 명 이상 입력하세요.");
        if (tokens.size() > MAX_GODS) {
            throw new IllegalArgumentException("신은 한 번에 최대 " + MAX_GODS + "개까지 입력할 수 있습니다.");
        }
        Set<String> selected = new LinkedHashSet<>();
        for (Token token : tokens) selected.add(resolve(token.value(), catalog));
        return List.copyOf(selected);
    }

    /**
     * Completions replace the entire greedy god argument. A Brigadier caller can pass
     * these strings to the existing SuggestionsBuilder without changing its offset.
     * Already selected gods and ambiguous short names are not suggested again.
     */
    public static List<String> suggestGods(String input, Collection<God> catalog) {
        if (input == null || input.length() > MAX_INPUT_LENGTH) return List.of();
        try {
            List<Token> tokens = tokenize(input, true);
            int replacementStart = input.length();
            String partial = "";
            int completed = tokens.size();
            if (!tokens.isEmpty()) {
                Token last = tokens.getLast();
                if (last.end() == input.length()) {
                    replacementStart = last.start();
                    partial = last.value();
                    completed--;
                }
            }
            if (completed >= MAX_GODS) return List.of();
            Set<String> selected = new LinkedHashSet<>();
            for (int i = 0; i < completed; i++) selected.add(resolve(tokens.get(i).value(), catalog));
            String prefix = input.substring(0, replacementStart);
            String match = partial.toLowerCase(Locale.ROOT);
            Set<String> suggestions = new LinkedHashSet<>();
            for (God god : catalog) {
                if (selected.contains(god.id())) continue;
                for (String reference : List.of(god.id(), god.path(), god.displayName())) {
                    if (reference.isEmpty() || !reference.toLowerCase(Locale.ROOT).startsWith(match)) continue;
                    try {
                        if (resolve(reference, catalog).equals(god.id())) {
                            suggestions.add(prefix + quoteIfNeeded(reference));
                        }
                    } catch (IllegalArgumentException ignored) {
                        // The canonical ID is still available for an ambiguous name.
                    }
                }
            }
            return List.copyOf(suggestions);
        } catch (IllegalArgumentException ignored) {
            return List.of();
        }
    }

    private static String resolve(String reference, Collection<God> catalog) {
        // A fully qualified ID always selects that entry, even if another display name matches it.
        for (God god : catalog) if (god.id().equals(reference)) return god.id();
        Set<String> matches = new LinkedHashSet<>();
        for (God god : catalog) {
            if (god.path().equals(reference) || god.displayName().equals(reference)) matches.add(god.id());
        }
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("알 수 없는 신: " + reference + ". 이름 또는 전체 신 ID를 확인하세요.");
        }
        if (matches.size() > 1) {
            throw new IllegalArgumentException("여러 신과 일치하는 이름: " + reference
                    + ". 전체 ID를 사용하세요: " + String.join(", ", matches));
        }
        return matches.iterator().next();
    }

    private static List<Token> tokenize(String input, boolean partialAllowed) {
        if (input == null || input.length() > MAX_INPUT_LENGTH) {
            throw new IllegalArgumentException("신 이름 입력이 없거나 너무 깁니다.");
        }
        List<Token> tokens = new ArrayList<>();
        int cursor = 0;
        while (cursor < input.length()) {
            if (separator(input.charAt(cursor))) { cursor++; continue; }
            int start = cursor;
            StringBuilder value = new StringBuilder();
            if (input.charAt(cursor) == '"') {
                cursor++;
                boolean closed = false;
                while (cursor < input.length()) {
                    char next = input.charAt(cursor++);
                    if (next == '"') { closed = true; break; }
                    if (next == '\\') {
                        if (cursor == input.length()) {
                            if (partialAllowed) break;
                            throw new IllegalArgumentException("따옴표 안의 이스케이프가 끝나지 않았습니다.");
                        }
                        next = input.charAt(cursor++);
                        if (next != '"' && next != '\\') {
                            throw new IllegalArgumentException("따옴표 안에서는 따옴표와 역슬래시만 이스케이프할 수 있습니다.");
                        }
                    }
                    value.append(next);
                }
                if (!closed && !partialAllowed) throw new IllegalArgumentException("신 이름의 닫는 따옴표가 없습니다.");
                if (closed && cursor < input.length() && !separator(input.charAt(cursor))) {
                    throw new IllegalArgumentException("신 이름 사이는 공백 또는 쉼표로 구분하세요.");
                }
                if (value.isEmpty() && (closed || !partialAllowed)) {
                    throw new IllegalArgumentException("빈 신 이름은 사용할 수 없습니다.");
                }
            } else {
                while (cursor < input.length() && !separator(input.charAt(cursor))) {
                    char next = input.charAt(cursor++);
                    if (next == '"') throw new IllegalArgumentException("공백이 있는 신 이름은 전체를 큰따옴표로 감싸세요.");
                    value.append(next);
                }
            }
            tokens.add(new Token(value.toString(), start, cursor));
        }
        return tokens;
    }

    private static boolean separator(char value) {
        return Character.isWhitespace(value) || value == ',';
    }

    private static String quoteIfNeeded(String value) {
        if (value.chars().anyMatch(c -> separator((char) c) || c == '"' || c == '\\')) {
            return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
        }
        return value;
    }

    private record Token(String value, int start, int end) {}
}
