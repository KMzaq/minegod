package com.sande.mythictrpg.client.dialogue;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.dialogue.playback.DialogueEnqueueResult;
import com.sande.mythictrpg.dialogue.playback.DialoguePlaybackSnapshot;
import com.sande.mythictrpg.dialogue.playback.DialoguePlaybackState;
import com.sande.mythictrpg.network.ClientDialoguePayload;
import net.minecraft.client.Minecraft;

public final class DialogueHudController {
    public static final DialogueHudController INSTANCE = new DialogueHudController();

    private final DialoguePlaybackState playback = new DialoguePlaybackState();

    private DialogueHudController() {
    }

    public void receive(ClientDialoguePayload payload) {
        DialogueEnqueueResult result = playback.enqueue(payload);
        if (result == DialogueEnqueueResult.DROPPED_OVERFLOW
                || result == DialogueEnqueueResult.QUEUED_AFTER_EVICTION) {
            MythicTrpg.LOGGER.debug("Dialogue queue {} for message {} from source {} (pending={})",
                    result, payload.messageId(), payload.source(), playback.pending().size());
        }
    }

    public void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        boolean playable = minecraft.player != null && minecraft.level != null
                && !minecraft.player.isDeadOrDying();
        playback.tick(playable);
    }

    public DialoguePlaybackSnapshot snapshot() {
        return playback.snapshot();
    }

    public void reset() {
        playback.reset();
    }
}
