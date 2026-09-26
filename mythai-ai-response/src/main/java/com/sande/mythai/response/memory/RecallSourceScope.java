package com.sande.mythai.response.memory;

import java.util.*;
import java.util.regex.Pattern;

/** Query-source filters, not fixed dialogue replies or identity inference from display names. */
public record RecallSourceScope(Role role, Set<String> speakerGodIds) {
    public enum Role { PLAYER, THIS_GOD, OTHER_GOD, ANY_GOD, IDENTIFIED_GOD, UNSPECIFIED }
    private static final Pattern IDENTIFIED = Pattern.compile(
            "([a-z0-9_.-]+:[a-z0-9_./-]+)\\s*(?:(?:이|가|은|는)(?=\\s|$)|의\\s*(?:말|발언|약속|계획)|said\\b|told\\b)");
    private static final Pattern ID_ACTOR = Pattern.compile(
            "[a-z0-9_.-]+:[a-z0-9_./-]+\\s*(?:(?:에게|한테|께|이|가|은|는)(?=\\s|$)|의(?=\\s*(?:말|발언|약속|계획))|(?=said\\b|told\\b))");
    public RecallSourceScope { Objects.requireNonNull(role); speakerGodIds = Set.copyOf(speakerGodIds); }

    /** Explicit actor IDs are metadata, not topic terms. Keep the original question for attribution and prompting. */
    public static String lexicalQuery(String query) {
        return ID_ACTOR.matcher(query == null ? "" : query.toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
    }

    public static RecallSourceScope resolve(String query) {
        String text = query == null ? "" : query.toLowerCase(Locale.ROOT);
        var ids = new LinkedHashSet<String>();
        var matcher = IDENTIFIED.matcher(text);
        while (matcher.find() && ids.size() < 16) ids.add(matcher.group(1));
        if (!ids.isEmpty()) return new RecallSourceScope(Role.IDENTIFIED_GOD, ids);
        boolean player = text.matches("(?s).*(내가|제가|나는|저는|내\\s*(말|발언|이야기|약속|계획|일정)|\\bi\\s+(said|told|promised)\\b|\\bmy\\s+(words|promise|plan)).*");
        boolean self = text.matches("(?s).*(네가|너가|너는|네\\s*(말|발언|이야기|약속|계획)|당신이|당신의\\s*(말|발언)|\\byou\\s+(said|told|promised)\\b).*" );
        boolean other = text.matches("(?s).*(다른\\s*신|그\\s*신|저\\s*신|\\bother\\s+(god|gods|deity)).*");
        boolean gods = other || text.matches("(?s).*((?<![가-힣])신(?:이|은|의|들)|\\b(god|gods|deity)\\s+(said|told)).*");
        // Conflicting grammatical hints are not resolved by inventing an actor. Rows remain explicitly attributed.
        Role role = player && (self || gods) || self && other ? Role.UNSPECIFIED : player ? Role.PLAYER
                : self ? Role.THIS_GOD : other ? Role.OTHER_GOD : gods ? Role.ANY_GOD : Role.UNSPECIFIED;
        return new RecallSourceScope(role, Set.of());
    }

    public boolean allows(MemoryJournal.Entry entry) {
        if (entry.source() == MemoryJournal.Source.PLAYER_STATEMENT)
            return role == Role.PLAYER || role == Role.UNSPECIFIED;
        if (entry.source() != MemoryJournal.Source.NPC_UTTERANCE) return false;
        return switch (role) {
            case PLAYER -> false;
            case THIS_GOD -> entry.speakerGodId().equals(entry.key().god());
            case OTHER_GOD -> !entry.speakerGodId().equals(entry.key().god());
            case IDENTIFIED_GOD -> speakerGodIds.contains(entry.speakerGodId());
            case ANY_GOD, UNSPECIFIED -> true;
        };
    }
}
