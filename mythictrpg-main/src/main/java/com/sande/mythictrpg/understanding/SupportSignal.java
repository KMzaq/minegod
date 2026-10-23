package com.sande.mythictrpg.understanding;

import java.util.*;

/** Positive, server-scoped inputs. A signal is not a personality label or permission to change gameplay. */
public record SupportSignal(Kind kind, int count, String context) {
    public enum Kind { RAID_ATTACK, RAID_GUARD, DIALOGUE_INFORMATION, DIALOGUE_HELP, DIALOGUE_SOCIAL }
    public static final String SUBJECT = "mythictrpg:support_signal";
    public SupportSignal {
        Objects.requireNonNull(kind);
        if (count < 1 || count > 1_000_000 || context == null || context.length() > 256)
            throw new IllegalArgumentException("support signal bounds");
    }
    public Map<String,String> payload() { return Map.of("kind",kind.name(),"count",Integer.toString(count),"context",context); }
    public static SupportSignal parse(Map<String,String> value) {
        if (!value.keySet().equals(Set.of("kind","count","context"))) throw new IllegalArgumentException("support signal shape");
        return new SupportSignal(Kind.valueOf(value.get("kind")),Integer.parseInt(value.get("count")),value.get("context"));
    }
}
