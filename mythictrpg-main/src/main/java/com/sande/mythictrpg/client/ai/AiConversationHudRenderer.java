package com.sande.mythictrpg.client.ai;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

public final class AiConversationHudRenderer {
    private static final int WIDTH = 116;
    private static final int HEIGHT = 45;
    private static final int RIGHT_MARGIN = 8;
    private static final int TOP = 36;

    private AiConversationHudRenderer() {
    }

    public static void render(GuiGraphics graphics, DeltaTracker ignored) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options.hideGui || minecraft.player == null || minecraft.level == null) {
            return;
        }
        AiConversationHudController controller = AiConversationHudController.INSTANCE;
        if (!controller.visible()) {
            return;
        }
        Font font = minecraft.font;
        int left = graphics.guiWidth() - RIGHT_MARGIN - WIDTH;
        int right = left + WIDTH;
        int bottom = TOP + HEIGHT;
        int border = controller.conversationActive() ? 0xFF4CAF50 : 0xFF777777;
        int status = controller.conversationActive() ? 0xFF7CFF8A : 0xFFB8B8B8;

        graphics.fill(left, TOP, right, bottom, 0xB0101010);
        graphics.fill(left, TOP, right, TOP + 1, border);
        graphics.fill(left, bottom - 1, right, bottom, border);
        graphics.fill(left, TOP, left + 1, bottom, border);
        graphics.fill(right - 1, TOP, right, bottom, border);

        graphics.drawString(font, Component.literal("AI DIALOGUE"), left + 6, TOP + 5, 0xFFFFFFFF, true);
        graphics.drawString(font, Component.literal(controller.conversationActive() ? "[대화 중]" : "[대기]"),
                right - (controller.conversationActive() ? 50 : 32), TOP + 5, status, true);
        graphics.drawString(font, Component.literal("신: ").append(controller.godDisplayName()),
                left + 6, TOP + 18, 0xFFFFFFFF, true);
        graphics.drawString(font, Component.literal("G 키로 UI 숨기기"), left + 6, TOP + 31, 0xFFAAAAAA, false);
    }
}
