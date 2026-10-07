package com.sande.mythictrpg.ai.agent;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Declarative information boundary for an NPC. These scopes are metadata for a later context builder;
 * they never grant gameplay authority.
 */
public record KnowledgePermissions(Set<String> allowedScopes, Set<String> deniedScopes) {
    public KnowledgePermissions {
        allowedScopes = immutableScopes(allowedScopes, "allowedScopes");
        deniedScopes = immutableScopes(deniedScopes, "deniedScopes");
    }

    public static KnowledgePermissions none() {
        return new KnowledgePermissions(Set.of(), Set.of());
    }

    public boolean permits(String scope) {
        if (scope == null || scope.isBlank()) {
            return false;
        }
        String normalized = scope.trim();
        return allowedScopes.contains(normalized) && !deniedScopes.contains(normalized);
    }

    private static Set<String> immutableScopes(Set<String> scopes, String name) {
        if (scopes == null || scopes.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String scope : scopes) {
            if (scope == null || scope.isBlank()) {
                throw new IllegalArgumentException(name + " must not contain a blank scope");
            }
            normalized.add(scope.trim());
        }
        return Set.copyOf(normalized);
    }
}
