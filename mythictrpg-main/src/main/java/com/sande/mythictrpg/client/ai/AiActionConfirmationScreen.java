package com.sande.mythictrpg.client.ai;

import com.sande.mythictrpg.network.AiActionConfirmationPayload;
import com.sande.mythictrpg.network.AiActionDecisionPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

public final class AiActionConfirmationScreen extends Screen {
    private final AiActionConfirmationPayload payload;
    private boolean decided;
    private int ticksRemaining;

    public AiActionConfirmationScreen(AiActionConfirmationPayload payload) {
        super(Component.translatable("screen.mythictrpg.ai_action.title"));
        this.payload = payload;
        this.ticksRemaining = payload.timeoutSeconds() * 20;
    }

    @Override
    protected void init() {
        int y = height / 2 + 70;
        addRenderableWidget(Button.builder(Component.translatable("screen.mythictrpg.ai_action.accept"),
                button -> decide(true)).bounds(width / 2 - 105, y, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.mythictrpg.ai_action.reject"),
                button -> decide(false)).bounds(width / 2 + 5, y, 100, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, height / 2 - 90, 0xFFFFFF);
        graphics.drawCenteredString(font, Component.literal(payload.title()), width / 2,
                height / 2 - 60, 0xFFD76A);
        int y = height / 2 - 35;
        for (var line : font.split(Component.literal(payload.summary()), Math.min(360, width - 60))) {
            graphics.drawCenteredString(font, line, width / 2, y, 0xDDDDDD);
            y += font.lineHeight + 2;
        }
        graphics.drawCenteredString(font, Component.translatable("screen.mythictrpg.ai_action.timeout",
                Math.max(0, (ticksRemaining + 19) / 20)), width / 2, height / 2 + 45, 0xAAAAAA);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void tick() {
        if (!decided && --ticksRemaining <= 0) {
            decide(false);
        }
    }

    @Override
    public void onClose() {
        if (!decided) {
            decide(false);
        } else {
            super.onClose();
        }
    }

    private void decide(boolean accepted) {
        if (decided) {
            return;
        }
        decided = true;
        PacketDistributor.sendToServer(new AiActionDecisionPayload(payload.proposalId(), accepted));
        if (minecraft != null) {
            minecraft.setScreen(null);
        }
    }
}
