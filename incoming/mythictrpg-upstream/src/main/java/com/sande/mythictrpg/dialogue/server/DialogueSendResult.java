package com.sande.mythictrpg.dialogue.server;

import java.util.Optional;
import java.util.Objects;
import java.util.UUID;

public record DialogueSendResult(DialogueSendStatus status, Optional<UUID> messageId) {
    public DialogueSendResult {
        Objects.requireNonNull(messageId, "messageId");
    }

    public static DialogueSendResult sent(UUID messageId) {
        return new DialogueSendResult(DialogueSendStatus.SENT, Optional.of(messageId));
    }

    public static DialogueSendResult failed(DialogueSendStatus status) {
        return new DialogueSendResult(status, Optional.empty());
    }
}
