package com.sande.mythictrpg.ai.tone;

import java.util.List;
import java.util.Objects;

/**
 * Immutable, game-owned authority snapshot for one NPC turn.  Reasons are prompt-safe labels such as
 * "appointed commander" or "contract holder" and never grant the LLM authority to alter rank.
 */
public record NpcSocialAuthorityContext(RelativeAuthority relativeAuthority, List<String> reasons) {
    public NpcSocialAuthorityContext {
        relativeAuthority = relativeAuthority == null ? RelativeAuthority.UNKNOWN : relativeAuthority;
        reasons = reasons == null ? List.of() : reasons.stream().filter(Objects::nonNull).map(String::trim)
                .filter(reason -> !reason.isEmpty()).limit(4).toList();
    }

    public static NpcSocialAuthorityContext unknown() {
        return new NpcSocialAuthorityContext(RelativeAuthority.UNKNOWN, List.of());
    }
}
