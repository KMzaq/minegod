package com.sande.mythictrpg.dialogue.playback;

import com.sande.mythictrpg.network.ClientDialoguePayload;

import java.util.Optional;

public record DialoguePlaybackSnapshot(Optional<ClientDialoguePayload> current,
        DialoguePlaybackStage stage, float alpha) {
    public static DialoguePlaybackSnapshot idle() {
        return new DialoguePlaybackSnapshot(Optional.empty(), DialoguePlaybackStage.IDLE, 0.0F);
    }
}
