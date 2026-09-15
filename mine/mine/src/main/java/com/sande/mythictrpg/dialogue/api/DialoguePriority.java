package com.sande.mythictrpg.dialogue.api;

public enum DialoguePriority {
    NORMAL(0),
    IMPORTANT(1),
    CRITICAL(2);

    private final int networkId;

    DialoguePriority(int networkId) {
        this.networkId = networkId;
    }

    public int networkId() {
        return networkId;
    }

    public static DialoguePriority fromNetworkId(int networkId) {
        for (DialoguePriority priority : values()) {
            if (priority.networkId == networkId) {
                return priority;
            }
        }
        throw new IllegalArgumentException("Unknown dialogue priority ID: " + networkId);
    }
}
