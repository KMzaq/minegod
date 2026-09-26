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
        if (!controller.rooms().rooms().isEmpty()) {
            int x=graphics.guiWidth()-230;
            int y=TOP;
            for(var room:controller.rooms().rooms().stream().limit(6).toList()) {
                boolean chosen=controller.rooms().selectedPrivate().filter(room.id()::equals).isPresent();
                graphics.fill(x-4,y-2,graphics.guiWidth()-8,y+23,0xB0101010);
                graphics.drawString(font,(chosen?"▶ ":"")+"["+room.code()+"] "+(room.isPrivate()?"비밀":"공개"),x,y,room.color(),true);
                graphics.drawString(font,font.plainSubstrByWidth(room.gods(),210),x,y+11,0xFFFFFFFF,true);y+=28;
            }
            graphics.drawString(font,"H: 방 선택 / G: UI 숨기기",x,y,0xFFAAAAAA,false);
            if(minecraft.screen instanceof net.minecraft.client.gui.screens.ChatScreen) {
                String destination=controller.rooms().rooms().stream().filter(r->controller.rooms().selectedPrivate().filter(r.id()::equals).isPresent())
                        .map(r->"/s → ["+r.code()+"] "+r.gods()).findFirst().orElse("/s: 비밀대화 선택 필요 (H)");
                graphics.drawString(font,destination,4,graphics.guiHeight()-30,0xFFFFCC66,true);
            }
            return;
        }
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
