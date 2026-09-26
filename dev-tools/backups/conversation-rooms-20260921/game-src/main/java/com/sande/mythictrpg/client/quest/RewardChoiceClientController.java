package com.sande.mythictrpg.client.quest;

import com.sande.mythictrpg.network.RewardChoicePayload;
import net.minecraft.client.Minecraft;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Serializes multiple durable reward offers without replacing an already visible choice. */
public final class RewardChoiceClientController {
    public static final RewardChoiceClientController INSTANCE = new RewardChoiceClientController();

    private final Deque<RewardChoicePayload> pending = new ArrayDeque<>();
    private final Set<UUID> known = new HashSet<>();
    private UUID visibleClaim;

    private RewardChoiceClientController() {
    }

    public void receive(RewardChoicePayload payload) {
        if (!known.add(payload.claimId())) {
            return;
        }
        pending.addLast(payload);
        showNext();
    }

    public void finish(UUID claimId) {
        if (claimId.equals(visibleClaim)) {
            visibleClaim = null;
        }
        showNext();
    }

    public void reset() {
        pending.clear();
        known.clear();
        visibleClaim = null;
    }

    private void showNext() {
        Minecraft minecraft = Minecraft.getInstance();
        if (visibleClaim != null || minecraft.screen instanceof RewardChoiceScreen) {
            return;
        }
        RewardChoicePayload next = pending.pollFirst();
        if (next == null) {
            return;
        }
        visibleClaim = next.claimId();
        minecraft.setScreen(new RewardChoiceScreen(next));
    }
}
