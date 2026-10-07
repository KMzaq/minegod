package com.sande.mythictrpg.client.story;

import com.sande.mythictrpg.network.StoryChoiceBrowsePayload;
import com.sande.mythictrpg.network.StoryChoicePagePayload;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.PacketDistributor;

/** Client presentation only. The server owns the pending list and every choice decision. */
public final class StoryChoiceClientController {
    public static final StoryChoiceClientController INSTANCE = new StoryChoiceClientController();

    private StoryChoicePagePayload latest;
    private boolean deferredOpen;

    private StoryChoiceClientController() {}

    public void receive(StoryChoicePagePayload payload) {
        latest = payload;
        Minecraft minecraft = Minecraft.getInstance();
        boolean showing = minecraft.screen instanceof StoryChoiceScreen;
        if (payload.total() == 0) {
            deferredOpen = false;
            if (showing) minecraft.setScreen(null);
            return;
        }
        if (showing || payload.open()) {
            deferredOpen = payload.open() && !showing;
            showIfPossible();
        }
    }

    public void tick() {
        if (deferredOpen) showIfPossible();
    }

    private void showIfPossible() {
        Minecraft minecraft = Minecraft.getInstance();
        if (latest == null || latest.offer().isEmpty()) return;
        if (minecraft.screen == null || minecraft.screen instanceof StoryChoiceScreen) {
            deferredOpen = false;
            minecraft.setScreen(new StoryChoiceScreen(latest));
        }
    }

    public void browse(int page) {
        PacketDistributor.sendToServer(new StoryChoiceBrowsePayload(page));
    }

    public void dismiss() {
        deferredOpen = false;
    }

    public void reset() {
        latest = null;
        deferredOpen = false;
    }
}
