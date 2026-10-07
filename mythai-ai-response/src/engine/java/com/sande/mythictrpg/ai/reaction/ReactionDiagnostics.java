package com.sande.mythictrpg.ai.reaction;

import com.sande.mythictrpg.ai.tag.NpcTagProfile;

import java.util.ArrayList;
import java.util.List;

/** Converts a preparation result into an inspectable diagnostic snapshot without exposing game internals to players. */
public final class ReactionDiagnostics {
    private ReactionDiagnostics() {
    }

    public static ReactionDebugSnapshot snapshot(NpcTagProfile npc, SituationContext situation,
            ReactionExamplePreparation preparation) {
        List<String> ids = new ArrayList<>();
        for (int index = 0; index < preparation.examples().selectedExamples().size(); index++) {
            ids.add("example_" + String.format(java.util.Locale.ROOT, "%03d", index + 1));
        }
        return new ReactionDebugSnapshot(npc.rawTags(), npc.classification(), situation, preparation.guidelines(),
                preparation.examples().styleContext(), ids);
    }
}
