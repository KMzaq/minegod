package com.sande.mythictrpg.client.ai;

import com.sande.mythictrpg.network.AiActionConfirmationPayload;
import com.sande.mythictrpg.network.AiActionDecisionPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import net.minecraft.util.FormattedCharSequence;
import java.util.ArrayList;
import java.util.List;

public final class AiActionConfirmationScreen extends Screen {
    private final AiActionConfirmationPayload payload;
    private boolean decided;
    private int ticksRemaining;
    private final List<BodyLine> body = new ArrayList<>();
    private int scroll;

    private record BodyLine(FormattedCharSequence text, int color) { }

    public AiActionConfirmationScreen(AiActionConfirmationPayload payload) {
        super(Component.translatable("screen.mythictrpg.ai_action.title"));
        this.payload = payload;
        this.ticksRemaining = payload.timeoutSeconds() * 20;
    }

    @Override
    protected void init() {
        body.clear();
        addBody(Component.translatable("screen.mythictrpg.ai_action.terms"), 0xFFD76A);
        for (String term : payload.verifiedTerms()) addBody(Component.literal(term), 0xFFFFFF);
        addBody(Component.literal(" "), 0xFFFFFF);
        addBody(Component.translatable("screen.mythictrpg.ai_action.narrative"), 0xAAAAAA);
        addBody(Component.literal(payload.title()), 0xDDDDDD);
        addBody(Component.literal(payload.summary()), 0xDDDDDD);
        scroll = Math.min(scroll, maxScroll());
        int y = height - 30;
        addRenderableWidget(Button.builder(Component.translatable("screen.mythictrpg.ai_action.accept"),
                button -> decide(true)).bounds(width / 2 - 105, y, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.mythictrpg.ai_action.reject"),
                button -> decide(false)).bounds(width / 2 + 5, y, 100, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 16, 0xFFFFFF);
        int x = (width - contentWidth()) / 2;
        graphics.enableScissor(x - 2, 36, x + contentWidth() + 8, height - 64);
        int y = 38 - scroll;
        for (BodyLine line : body) {
            graphics.drawString(font, line.text(), x, y, line.color());
            y += lineHeight();
        }
        graphics.disableScissor();
        if (maxScroll() > 0) {
            int top = 36, available = Math.max(1, height - 100);
            int thumb = Math.max(8, available * available / Math.max(1, body.size() * lineHeight()));
            int offset = scroll * (available - thumb) / maxScroll();
            graphics.fill(x + contentWidth() + 4, top, x + contentWidth() + 6, top + available, 0xFF555555);
            graphics.fill(x + contentWidth() + 4, top + offset, x + contentWidth() + 6, top + offset + thumb, 0xFFCCCCCC);
        }
        graphics.drawCenteredString(font, Component.translatable("screen.mythictrpg.ai_action.timeout",
                Math.max(0, (ticksRemaining + 19) / 20)), width / 2, height - 52, 0xAAAAAA);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private int contentWidth() { return Math.max(40, Math.min(440, width - 60)); }
    private int lineHeight() { return font.lineHeight + 3; }
    private int maxScroll() { return Math.max(0, body.size() * lineHeight() - Math.max(1, height - 104)); }
    private void addBody(Component text, int color) {
        for (var line : font.split(text, contentWidth())) body.add(new BodyLine(line, color));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        scroll = Math.max(0, Math.min(maxScroll(), scroll - (int) (vertical * lineHeight() * 3)));
        return true;
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void tick() {
        if (!decided && --ticksRemaining <= 0) {
            // Closing a timed-out view is not an explicit refusal. Server game time owns expiry.
            decided = true;
            if (minecraft != null) minecraft.setScreen(null);
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
