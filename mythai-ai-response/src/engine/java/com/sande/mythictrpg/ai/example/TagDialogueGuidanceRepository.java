package com.sande.mythictrpg.ai.example;

import java.util.List;
import java.util.Optional;

/** Content boundary for tag-level writing guidance; a future datapack or database implementation can replace JSON. */
public interface TagDialogueGuidanceRepository {
    Optional<TagDialogueGuidance> find(DialogueExampleTag tag);

    List<TagDialogueGuidance> all();
}
