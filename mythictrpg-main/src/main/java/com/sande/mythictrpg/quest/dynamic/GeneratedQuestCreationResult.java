package com.sande.mythictrpg.quest.dynamic;

import java.util.Optional;

public record GeneratedQuestCreationResult(boolean created, String reason,
        Optional<GeneratedQuestInstance> instance) {
    public GeneratedQuestCreationResult {
        instance = instance == null ? Optional.empty() : instance;
    }

    public static GeneratedQuestCreationResult created(GeneratedQuestInstance instance) {
        return new GeneratedQuestCreationResult(true, "", Optional.of(instance));
    }

    public static GeneratedQuestCreationResult rejected(String reason) {
        return new GeneratedQuestCreationResult(false, reason, Optional.empty());
    }
}
