package com.sande.mythictrpg.client.accessory;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import java.util.List;

/** The two slots fit in vanilla's unused space between the player portrait and crafting grid. */
public final class AccessoryInventoryDecoration {
    private AccessoryInventoryDecoration() { }

    public static void drawSlot(GuiGraphics g, int x, int y, boolean empty) {
        g.fill(x-1, y-1, x+17, y+17, 0xFF373737);
        g.fill(x, y, x+17, y+17, 0xFFFFFFFF);
        g.fill(x, y, x+16, y+16, 0xFF8B8B8B);
        if (!empty) return;
        int tint = 0xFF62596E;
        g.fill(x+6, y+4, x+10, y+5, tint);
        g.fill(x+4, y+6, x+5, y+10, tint);
        g.fill(x+11, y+6, x+12, y+10, tint);
        g.fill(x+6, y+11, x+10, y+12, tint);
        g.fill(x+5, y+5, x+6, y+6, tint);
        g.fill(x+10, y+5, x+11, y+6, tint);
        g.fill(x+5, y+10, x+6, y+11, tint);
        g.fill(x+10, y+10, x+11, y+11, tint);
        g.fill(x+7, y+2, x+9, y+5, 0xFFAC8BCD);
    }

    public static void tooltip(GuiGraphics g, Font font, int logical, int mouseX, int mouseY) {
        g.renderComponentTooltip(font, List.of(Component.literal("장신구 " + (logical+1)),
                Component.literal("본인 장신구를 옮겨 장착 · Shift 클릭으로 빠른 장착/해제")), mouseX, mouseY);
    }
}
