package com.sande.mythai.response;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Selects one participant for the next isolated test generation; it does not add participants. */
public final class DialogueTestSpeakerPolicy {
    private static final Pattern GROUP_ADDRESS = Pattern.compile("모두|너희|각자|다들|다음\\s*신|다른\\s*신");

    private DialogueTestSpeakerPolicy() {}

    /**
     * Explicit mentions take precedence. One named participant speaks; multiple named
     * participants rotate in their first-mention order. Without a name, a group address
     * rotates the full audience, while an ordinary continuation stays with the previous
     * participant. An absent/removed previous participant defaults to the first candidate.
     */
    public static String select(String text, List<DialogueTestArguments.God> gods, String previousSpeakerId) {
        Objects.requireNonNull(gods, "gods");
        var unique = new LinkedHashMap<String, DialogueTestArguments.God>();
        for (DialogueTestArguments.God god : gods) unique.putIfAbsent(god.id(), god);
        if (unique.isEmpty()) throw new IllegalArgumentException("대화 참가 신이 없습니다.");
        if (unique.size() > DialogueTestArguments.MAX_GODS) {
            throw new IllegalArgumentException("대화 참가 신은 최대 " + DialogueTestArguments.MAX_GODS + "명입니다.");
        }
        String utterance = text == null ? "" : text;
        List<String> mentioned = mentions(utterance, List.copyOf(unique.values()));
        if (!mentioned.isEmpty()) return next(mentioned, previousSpeakerId);
        List<String> participants = List.copyOf(unique.keySet());
        if (GROUP_ADDRESS.matcher(utterance).find()) return next(participants, previousSpeakerId);
        return unique.containsKey(previousSpeakerId) ? previousSpeakerId : participants.getFirst();
    }

    private static String next(List<String> candidates, String previous) {
        int index = previous == null ? -1 : candidates.indexOf(previous);
        return candidates.get((index + 1) % candidates.size());
    }

    private static List<String> mentions(String text, List<DialogueTestArguments.God> gods) {
        var matches = new ArrayList<Mention>();
        for (DialogueTestArguments.God god : gods) {
            find(text, god.id(), god.id(), true, matches);
            find(text, god.path(), god.id(), true, matches);
            if (!god.displayName().isBlank()) find(text, god.displayName(), god.id(), false, matches);
        }
        // A short Korean name inside a longer explicitly named participant must not steal the turn.
        matches.sort(Comparator.comparingInt(Mention::length).reversed().thenComparingInt(Mention::start));
        var retained = new ArrayList<Mention>();
        for (Mention candidate : matches) {
            boolean shadowed = retained.stream().anyMatch(other -> candidate.start < other.end
                    && other.start < candidate.end && (candidate.start != other.start || candidate.end != other.end));
            if (!shadowed) retained.add(candidate);
        }
        retained.sort(Comparator.comparingInt(Mention::start));
        var ids = new LinkedHashSet<String>();
        for (Mention mention : retained) ids.add(mention.id);
        return List.copyOf(ids);
    }

    private static void find(String text, String reference, String id, boolean resourceReference, List<Mention> matches) {
        var matcher = Pattern.compile(Pattern.quote(reference), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(text);
        while (matcher.find()) {
            int start = matcher.start(), end = matcher.end();
            boolean leftBoundary = start == 0 || !continuesReference(text.codePointBefore(start), resourceReference)
                    || !continuesReference(reference.codePointAt(0), resourceReference);
            boolean rightBoundary = end == text.length() || !continuesReference(text.codePointAt(end), resourceReference)
                    || !continuesReference(reference.codePointBefore(reference.length()), resourceReference);
            if (leftBoundary && rightBoundary) matches.add(new Mention(id, start, end));
        }
    }

    private static boolean continuesReference(int point, boolean resourceReference) {
        boolean latinWord = Character.UnicodeScript.of(point) == Character.UnicodeScript.LATIN
                || Character.isDigit(point) || point == '_';
        return latinWord || point == ':' || point == '/' || resourceReference && (point == '.' || point == '-');
    }

    private record Mention(String id, int start, int end) {
        int length() { return end - start; }
    }
}
