package com.sande.mythictrpg.ai;

import com.sande.mythictrpg.ai.intent.ConversationIntent;
import java.util.List;

/** Executes the generated production directive without bootstrapping Minecraft or a model. */
public final class RecallDirectiveChecks {
    public static int run() {
        var recall = DialogueTurnDirective.recall();
        if (recall.mode() != DialogueTurnDirective.Mode.INFORMATION || recall.proposalsAllowed())
            throw new AssertionError("recall must not use casual-event handling or grant actions");
        if (!recall.promptBlock().contains("RECALL_REQUEST") || !recall.promptBlock().contains("persona"))
            throw new AssertionError("recall directive loses request or persona");
        var greeting = DialogueTurnDirective.plan("안녕", List.of(), 1, ConversationIntent.heuristicFallback());
        if (greeting.mode() != DialogueTurnDirective.Mode.GREETING) throw new AssertionError("legacy greeting changed");
        if (!recall.naturalSpeech("첫 문장. 다음 문장.").equals("첫 문장. 다음 문장."))
            throw new AssertionError("recall loses multi-sentence meaning");
        return 4;
    }
}
