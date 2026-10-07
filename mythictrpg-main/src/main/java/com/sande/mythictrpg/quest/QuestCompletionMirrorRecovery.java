package com.sande.mythictrpg.quest;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Read-only authoritative-history reconciliation. The port has no completion/reward operation. */
final class QuestCompletionMirrorRecovery {
    private QuestCompletionMirrorRecovery() {}

    interface Mirror {
        boolean restore(FtbQuestBinding binding, QuestCompletionRecord completion);
        void hideInvalidated(FtbQuestBinding binding);
    }

    static List<ResourceLocation> reconcile(MythicQuestState state, UUID player,
            Collection<FtbQuestBinding> bindings, Mirror mirror) {
        var failed = new ArrayList<ResourceLocation>();
        for (var binding : bindings) {
            var completion = state.completion(binding.questId()).orElse(null);
            if (completion == null || !completion.assignedPlayersAtCompletion().contains(player)) continue;
            try {
                if (completion.completedBy().equals(player)) {
                    // Participation runs have their own per-player mirrors and login reconciliation.
                    // Do not reinterpret a historical run as an ordinary quest after a content reload.
                    if (binding.participation().isEmpty() && state.participationRun(binding.questId()).isEmpty()
                            && !mirror.restore(binding, completion)) failed.add(binding.questId());
                } else {
                    mirror.hideInvalidated(binding);
                }
            } catch (RuntimeException ignored) {
                // A temporarily unavailable external adapter cannot block other quests or lose the retry.
                failed.add(binding.questId());
            }
        }
        return List.copyOf(failed);
    }
}
