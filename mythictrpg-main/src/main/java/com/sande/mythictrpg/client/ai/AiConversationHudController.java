package com.sande.mythictrpg.client.ai;

import com.sande.mythictrpg.network.AiConversationStatePayload;
import net.minecraft.network.chat.Component;

public final class AiConversationHudController {
    public static final AiConversationHudController INSTANCE = new AiConversationHudController();

    private boolean conversationActive;
    private boolean visible = true;
    private Component godDisplayName = Component.literal("-");

    private AiConversationHudController() {
    }

    public void receive(AiConversationStatePayload payload) {
        conversationActive = payload.enabled();
        godDisplayName = payload.godDisplayName();
    }

    /** Client-only HUD preference. It never changes the server conversation state. */
    public void toggleVisibility() {
        visible = !visible;
    }

    public void reset() {
        conversationActive = false;
        godDisplayName = Component.literal("-");
    }

    public boolean conversationActive() {
        return conversationActive;
    }

    public boolean visible() {
        return visible;
    }

    public Component godDisplayName() {
        return godDisplayName.copy();
    }
}
