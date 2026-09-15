package com.sande.mythictrpg.ai.tone;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Prompt-safe, non-authoritative result of combining a player's delivery with one NPC's live relationship/context. */
public record PlayerToneResponseGuidance(Set<PlayerSpeechTone> toneTags,
        NpcSocialAuthorityContext socialAuthority, ToneResponseDisposition disposition, List<String> rules) {
    public PlayerToneResponseGuidance {
        toneTags = toneTags == null ? Set.of() : Set.copyOf(toneTags);
        socialAuthority = socialAuthority == null ? NpcSocialAuthorityContext.unknown() : socialAuthority;
        disposition = disposition == null ? ToneResponseDisposition.NOTICE : disposition;
        rules = rules == null ? List.of() : rules.stream().filter(Objects::nonNull).map(String::trim)
                .filter(rule -> !rule.isEmpty()).limit(5).toList();
    }
}
