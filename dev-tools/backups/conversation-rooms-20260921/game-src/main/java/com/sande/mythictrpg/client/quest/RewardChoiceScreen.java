package com.sande.mythictrpg.client.quest;

import com.sande.mythictrpg.network.RewardChoicePayload;
import com.sande.mythictrpg.network.RewardChoiceSelectionPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/** Non-expiring quest reward selector. Closing defers the choice until the next login. */
public final class RewardChoiceScreen extends Screen {
    private final RewardChoicePayload payload;
    private boolean finished;

    public RewardChoiceScreen(RewardChoicePayload payload) {
        super(Component.translatable("screen.mythictrpg.reward_choice.title"));
        this.payload = payload;
    }

    @Override
    protected void init() {
        int rowHeight = 34;
        int startY = Math.max(54, height / 2 - payload.options().size() * rowHeight / 2);
        for (int index = 0; index < payload.options().size(); index++) {
            RewardChoicePayload.Option option = payload.options().get(index);
            int y = startY + index * rowHeight;
            addRenderableWidget(Button.builder(Component.literal(option.displayName()),
                    button -> choose(option)).bounds(width / 2 - 150, y, 300, 20).build());
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 18, 0xFFFFFF);
        graphics.drawCenteredString(font, Component.literal(payload.title()), width / 2, 34, 0xFFD76A);
        int rowHeight = 34;
        int startY = Math.max(54, height / 2 - payload.options().size() * rowHeight / 2);
        for (int index = 0; index < payload.options().size(); index++) {
            RewardChoicePayload.Option option = payload.options().get(index);
            var summary = font.split(Component.literal(option.summary()), Math.min(420, width - 40))
                    .getFirst();
            graphics.drawCenteredString(font, summary, width / 2,
                    startY + index * rowHeight + 22, 0xB8B8B8);
        }
        graphics.drawCenteredString(font,
                Component.translatable("screen.mythictrpg.reward_choice.defer"), width / 2,
                Math.min(height - 18, startY + payload.options().size() * rowHeight + 6), 0x999999);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        if (finished) {
            super.onClose();
            return;
        }
        finished = true;
        super.onClose();
        RewardChoiceClientController.INSTANCE.finish(payload.claimId());
    }

    private void choose(RewardChoicePayload.Option option) {
        if (finished) {
            return;
        }
        finished = true;
        PacketDistributor.sendToServer(new RewardChoiceSelectionPayload(payload.claimId(), option.optionId()));
        if (minecraft != null) {
            minecraft.setScreen(null);
        }
        RewardChoiceClientController.INSTANCE.finish(payload.claimId());
    }
}
